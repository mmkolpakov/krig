package space.kscience.krig.server

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.openapi.hide
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.result.map
import space.kscience.krig.api.serialization.krigJson
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.contracts.DynamicDescriptorOverlay
import space.kscience.krig.core.contracts.schemaHash
import space.kscience.krig.core.contracts.toJsonSchema
import space.kscience.krig.ui.schema.DeviceFormSchema
import space.kscience.krig.ui.schema.toDeviceFormObservedMeta
import space.kscience.krig.ui.schema.toDeviceFormSchema
import kotlin.reflect.typeOf

private const val KRIG_SERVER_API_VERSION: String = "0.1.0"

/** Installs KRig's JSON defaults for Ktor responses. Call once before [krigDeviceServer]. */
public fun Application.installKrigServerJson(wireJson: Json = krigJson()) {
    install(ContentNegotiation) {
        json(wireJson)
    }
}

/** Installs the default KRig device HTTP routes into this [Application]. */
public fun Application.krigDeviceServer(
    registry: DeviceServerRegistry,
    settings: KrigServerSettings = KrigServerSettings(),
) {
    routing {
        val basePath = settings.basePath.trim('/')
        if (basePath.isEmpty()) {
            krigDeviceRoutes(registry, settings)
        } else {
            route("/$basePath") {
                krigDeviceRoutes(registry, settings)
            }
        }
    }
}

/** Installs KRig device routes under the current [Route]. */
@OptIn(ExperimentalKtorApi::class)
public fun Route.krigDeviceRoutes(
    registry: DeviceServerRegistry,
    settings: KrigServerSettings = KrigServerSettings(),
) {
    installServerMetadataRoutes(settings)
    installDeviceInventoryRoutes(registry)
    installManifestRoutes(registry)
    installFormRoutes(registry)
    installReadRoutes(registry)
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.installServerMetadataRoutes(settings: KrigServerSettings) {
    get("/server") {
        call.respond(
            KrigServerInfoDto(
                apiVersion = KRIG_SERVER_API_VERSION,
                readOnly = true,
                defaultSubscribeOptions = settings.defaultSubscribeOptions.toDto(),
            ),
        )
    }.describeRead<KrigServerInfoDto>("getServerInfo", "Server capabilities and defaults")

    get("/openapi.json") {
        val document = call.application.krigOpenApiDocumentText()
        call.respondText(document.content, document.contentType)
    }.hide()
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.installDeviceInventoryRoutes(registry: DeviceServerRegistry) {
    get("/tree") {
        call.respond(registry.treeDto())
    }.describeRead<DeviceTreeDto>("getDeviceTree", "Device tree")

    get("/devices") {
        call.respond(registry.treeDto())
    }.describeRead<DeviceTreeDto>("listDevices", "Device list")

    get("/devices/{deviceId}") {
        val deviceId = call.pathName("deviceId") ?: return@get
        val device = registry.devices[deviceId]
        if (device == null) {
            call.respondNotFound("device.not-found", "Device '$deviceId' is not registered.")
            return@get
        }
        val manifest = registry.manifest(deviceId)
        call.respond(
            DeviceSummaryDto(
                id = deviceId.toString(),
                manifestId = manifest?.id?.toString(),
                schemaHash = manifest?.schemaHash(),
            ),
        )
    }.describeRead<DeviceSummaryDto>(
        operationId = "getDeviceSummary",
        summary = "Device summary",
        pathParameters = listOf("deviceId"),
        notFound = true,
    )
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.installManifestRoutes(registry: DeviceServerRegistry) {
    get("/devices/{deviceId}/manifest") {
        val (_, manifest) = call.resolveManifest(registry) ?: return@get
        call.respond(manifest.toDto())
    }.describeRead<DeviceManifestDto>(
        operationId = "getDeviceManifest",
        summary = "Device manifest projection",
        pathParameters = listOf("deviceId"),
        notFound = true,
    )

    get("/devices/{deviceId}/schema") {
        val (_, manifest) = call.resolveManifest(registry) ?: return@get
        call.respond(manifest.toJsonSchema())
    }.describeRead<JsonObject>(
        operationId = "getDeviceJsonSchema",
        summary = "Device JSON Schema",
        pathParameters = listOf("deviceId"),
        notFound = true,
    )
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.installFormRoutes(registry: DeviceServerRegistry) {
    get("/devices/{deviceId}/form-schema") {
        val (_, device, manifest) = call.resolveDeviceManifest(registry) ?: return@get
        call.respond(manifest.deviceFormSchema(device))
    }.describeRead<DeviceFormSchema>(
        operationId = "getDeviceFormSchema",
        summary = "Device form schema",
        pathParameters = listOf("deviceId"),
        notFound = true,
    )

    get("/devices/{deviceId}/form-state") {
        val (deviceId, device, manifest) = call.resolveDeviceManifest(registry) ?: return@get
        val schema = manifest.deviceFormSchema(device)
        val values = (schema.properties + schema.discoveredProperties)
            .filter { it.readable }
            .associate { property ->
                property.name to device.readObservedOutcome(property.name).map { it.toDeviceFormObservedMeta() }
            }
        call.respond(
            DeviceFormStateReadDto(
                deviceId = deviceId.toString(),
                schemaHash = schema.schemaHash,
                values = values,
            ),
        )
    }.describeRead<DeviceFormStateReadDto>(
        operationId = "getDeviceFormState",
        summary = "Initial quality-aware device form state",
        pathParameters = listOf("deviceId"),
        notFound = true,
    )
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.installReadRoutes(registry: DeviceServerRegistry) {
    get("/devices/{deviceId}/actions") {
        val (_, manifest) = call.resolveManifest(registry) ?: return@get
        call.respond(manifest.actions.values.sortedBy { it.name.toString() }.map { it.toDto() })
    }.describeRead<List<ActionDescriptorDto>>(
        operationId = "listDeviceActions",
        summary = "Device action descriptors",
        pathParameters = listOf("deviceId"),
        notFound = true,
    )

    get("/devices/{deviceId}/properties/{property}") {
        val (deviceId, device) = call.resolveDevice(registry) ?: return@get
        val property = call.pathName("property") ?: return@get
        val outcome = device.readPropertyOutcome(property)
        call.respond(outcome.toPropertyReadDto(deviceId.toString(), property.toString()))
    }.describeRead<PropertyReadDto>(
        operationId = "readDeviceProperty",
        summary = "Read a property as Meta JSON",
        pathParameters = listOf("deviceId", "property"),
        notFound = true,
    )

    get("/devices/{deviceId}/observations/{property}") {
        val (deviceId, device) = call.resolveDevice(registry) ?: return@get
        val property = call.pathName("property") ?: return@get
        val outcome = device.readObservedOutcome(property)
        call.respond(outcome.toObservedReadDto(deviceId.toString(), property.toString()))
    }.describeRead<ObservedReadDto>(
        operationId = "readDeviceObservation",
        summary = "Read a quality-aware observed property",
        pathParameters = listOf("deviceId", "property"),
        notFound = true,
    )
}

private fun DeviceServerRegistry.treeDto(): DeviceTreeDto = DeviceTreeDto(
    devices = devices.entries
        .sortedBy { it.key.toString() }
        .map { (id, _) ->
            val manifest = manifest(id)
            DeviceSummaryDto(
                id = id.toString(),
                manifestId = manifest?.id?.toString(),
                schemaHash = manifest?.schemaHash(),
            )
        },
)

private suspend fun ApplicationCall.resolveDevice(
    registry: DeviceServerRegistry,
): Pair<Name, space.kscience.krig.core.contracts.Device>? {
    val deviceId = pathName("deviceId") ?: return null
    val device = registry.devices[deviceId]
    if (device == null) {
        respondNotFound("device.not-found", "Device '$deviceId' is not registered.")
        return null
    }
    return deviceId to device
}

private suspend fun ApplicationCall.resolveDeviceManifest(
    registry: DeviceServerRegistry,
): Triple<Name, Device, DeviceManifest>? {
    val (deviceId, device) = resolveDevice(registry) ?: return null
    val manifest = registry.manifest(deviceId)
    if (manifest == null) {
        respondNotFound("manifest.not-found", "Manifest for device '$deviceId' is not registered.")
        return null
    }
    return Triple(deviceId, device, manifest)
}

private suspend fun ApplicationCall.resolveManifest(
    registry: DeviceServerRegistry,
): Pair<Name, DeviceManifest>? {
    val (deviceId, _, manifest) = resolveDeviceManifest(registry) ?: return null
    return deviceId to manifest
}

private fun DeviceManifest.deviceFormSchema(device: Device): DeviceFormSchema {
    val overlay = device as? DynamicDescriptorOverlay
    return toDeviceFormSchema(
        discoveredProperties = overlay?.discoveredPropertyDescriptors?.values.orEmpty(),
        dynamicDiscoveryPolicy = overlay?.dynamicDiscoveryPolicy,
    )
}

private suspend fun ApplicationCall.respondNotFound(type: String, message: String) {
    respond(HttpStatusCode.NotFound, ServerFaultDto(type = type, message = message))
}

private suspend fun ApplicationCall.respondBadRequest(type: String, message: String) {
    respond(HttpStatusCode.BadRequest, ServerFaultDto(type = type, message = message))
}

private suspend fun ApplicationCall.pathName(parameter: String): Name? {
    val raw = parameters[parameter]
    if (raw.isNullOrBlank()) {
        respondBadRequest("route.parameter-missing", "Route parameter '$parameter' is missing.")
        return null
    }
    return try {
        raw.parseAsName()
    } catch (cause: IllegalArgumentException) {
        val detail = cause.message?.let { " $it" }.orEmpty()
        respondBadRequest("route.parameter-invalid", "Route parameter '$parameter' is not a valid Name.$detail")
        null
    }
}

private fun Application.krigOpenApiDocumentText(): OpenApiDocSource.Text =
    OpenApiDocSource.Routing().read(
        application = this,
        defaults = OpenApiDoc(info = OpenApiInfo("KRig Device Server", KRIG_SERVER_API_VERSION)),
    )

@OptIn(ExperimentalKtorApi::class)
private inline fun <reified T> Route.describeRead(
    operationId: String,
    summary: String,
    pathParameters: List<String> = emptyList(),
    notFound: Boolean = false,
): Route = describe {
    this.operationId = operationId
    this.summary = summary
    tag("KRig Device Server")
    if (pathParameters.isNotEmpty()) {
        parameters {
            pathParameters.forEach { parameter ->
                path(parameter) {
                    description = "DataForge Name path parameter."
                    schema = buildSchema(typeOf<String>())
                }
            }
        }
    }
    responses {
        HttpStatusCode.OK {
            description = "Successful response"
            schema = buildSchema(typeOf<T>())
        }
        if (notFound) {
            HttpStatusCode.NotFound {
                description = "Device, manifest or route target was not found."
                schema = buildSchema(typeOf<ServerFaultDto>())
            }
        }
        HttpStatusCode.BadRequest {
            description = "Route parameter validation failed."
            schema = buildSchema(typeOf<ServerFaultDto>())
        }
    }
}
