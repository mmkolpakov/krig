package space.kscience.krig.server

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.openapi.hide
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.ExperimentalKtorApi
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.sse.ServerSentEvent
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.faults.faultDetails
import space.kscience.krig.api.serialization.krigJson
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.contracts.DynamicDescriptorOverlay
import space.kscience.krig.core.contracts.SubscribeOptions
import space.kscience.krig.core.contracts.schemaHash
import space.kscience.krig.core.contracts.toJsonSchema
import space.kscience.krig.ui.schema.DeviceFormCommandEnvelope
import space.kscience.krig.ui.schema.DeviceFormCommandResult
import space.kscience.krig.ui.schema.DeviceFormSchema
import space.kscience.krig.ui.schema.DeviceFormStatePatch
import space.kscience.krig.ui.schema.DeviceFormStreamClientMessage
import space.kscience.krig.ui.schema.DeviceFormStreamOptions
import space.kscience.krig.ui.schema.DeviceFormStreamServerMessage
import space.kscience.krig.ui.schema.executeDeviceFormCommand
import space.kscience.krig.ui.schema.readDeviceFormPatch
import space.kscience.krig.ui.schema.readDeviceFormState
import space.kscience.krig.ui.schema.toDeviceFormSchema
import kotlin.math.roundToLong
import kotlin.reflect.typeOf
import kotlin.time.Duration.Companion.milliseconds

private const val KRIG_SERVER_API_VERSION: String = "0.1.0"

/** Installs KRig's Ktor defaults for JSON routes and bounded SSE form streams. */
public fun Application.installKrigServerDefaults(wireJson: Json = krigJson()) {
    install(ContentNegotiation) {
        json(wireJson)
    }
    install(SSE)
    install(WebSockets)
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
    installFormRoutes(registry, settings)
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
private fun Route.installFormRoutes(registry: DeviceServerRegistry, settings: KrigServerSettings) {
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
        call.respond(
            DeviceFormStateReadDto(
                deviceId = deviceId.toString(),
                schemaHash = schema.schemaHash,
                values = device.readDeviceFormState(schema).values,
            ),
        )
    }.describeRead<DeviceFormStateReadDto>(
        operationId = "getDeviceFormState",
        summary = "Initial quality-aware device form state",
        pathParameters = listOf("deviceId"),
        notFound = true,
    )

    post("/devices/{deviceId}/commands") {
        val (_, device, manifest) = call.resolveDeviceManifest(registry) ?: return@post
        val envelope = call.receive<DeviceFormCommandEnvelope>()
        val schema = manifest.deviceFormSchema(device)
        call.respond(device.executeDeviceFormCommand(schema, envelope))
    }.describeCommandRoute()

    sse("/devices/{deviceId}/form-events") {
        val (_, device, manifest) = call.resolveDeviceManifest(registry) ?: return@sse
        val schema = manifest.deviceFormSchema(device)
        val maxEvents = call.maxEvents()
        val delayMillis = settings.defaultSubscribeOptions.pollDelayMillis()
        var emitted = 0
        do {
            val patch = device.readDeviceFormPatch(schema)
            send(
                ServerSentEvent(
                    data = krigJson().encodeToString(DeviceFormStatePatch.serializer(), patch),
                    event = "form.patch",
                ),
            )
            emitted++
            if (maxEvents != null && emitted >= maxEvents) break
            delay(delayMillis.milliseconds)
        } while (currentCoroutineContext().isActive)
    }.describeSseRoute()

    webSocket("/devices/{deviceId}/streams") {
        val (_, device, manifest) = resolveDeviceManifestForStream(registry) ?: return@webSocket
        handleFormStream(
            device = device,
            schema = manifest.deviceFormSchema(device),
            settings = settings,
        )
    }.describeStreamRoute()
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

private suspend fun DefaultWebSocketServerSession.resolveDeviceManifestForStream(
    registry: DeviceServerRegistry,
): Triple<Name, Device, DeviceManifest>? {
    val deviceId = call.pathNameForWebSocket("deviceId")
    if (deviceId == null) {
        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Route parameter 'deviceId' is missing or invalid."))
        return null
    }
    val device = registry.devices[deviceId]
    if (device == null) {
        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Device '$deviceId' is not registered."))
        return null
    }
    val manifest = registry.manifest(deviceId)
    if (manifest == null) {
        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Manifest for device '$deviceId' is not registered."))
        return null
    }
    return Triple(deviceId, device, manifest)
}

private fun ApplicationCall.pathNameForWebSocket(parameter: String): Name? =
    parameters[parameter]
        ?.takeIf { it.isNotBlank() }
        ?.let { raw -> runCatching { raw.parseAsName() }.getOrNull() }

private suspend fun DefaultWebSocketServerSession.handleFormStream(
    device: Device,
    schema: DeviceFormSchema,
    settings: KrigServerSettings,
) {
    val sendMutex = Mutex()
    var streamJob: Job? = null

    suspend fun sendStream(message: DeviceFormStreamServerMessage) {
        val text = krigJson().encodeToString(DeviceFormStreamServerMessage.serializer(), message)
        sendMutex.withLock {
            send(Frame.Text(text))
        }
    }

    suspend fun startStream(requestId: String?, requestedOptions: DeviceFormStreamOptions) {
        val options = requestedOptions.withServerDefaults(settings)
        val fault = options.validationFault(schema)
        if (fault != null) {
            sendStream(DeviceFormStreamServerMessage.Fault(requestId, fault))
            return
        }
        streamJob?.cancelAndJoin()
        sendStream(
            DeviceFormStreamServerMessage.Subscribed(
                requestId = requestId,
                schemaHash = schema.schemaHash,
                options = options,
            ),
        )
        streamJob = launch {
            var emitted = 0
            while (isActive) {
                sendStream(
                    DeviceFormStreamServerMessage.Patch(
                        requestId = requestId,
                        patch = device.readDeviceFormPatch(schema, options.properties),
                    ),
                )
                emitted++
                val maxFrames = options.maxFrames
                if (maxFrames != null && emitted >= maxFrames) {
                    sendStream(DeviceFormStreamServerMessage.Completed(requestId, "maxFrames reached"))
                    break
                }
                delay(options.pollDelayMillis(settings).milliseconds)
            }
        }
    }

    try {
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val message = try {
                krigJson().decodeFromString(DeviceFormStreamClientMessage.serializer(), frame.readText())
            } catch (cause: SerializationException) {
                sendStream(DeviceFormStreamServerMessage.Fault(null, streamValidationFault(cause.message.orEmpty())))
                continue
            }
            when (message) {
                is DeviceFormStreamClientMessage.Subscribe -> startStream(message.requestId, message.options)
                is DeviceFormStreamClientMessage.UpdateOptions -> startStream(message.requestId, message.options)
                is DeviceFormStreamClientMessage.Unsubscribe -> {
                    streamJob?.cancelAndJoin()
                    streamJob = null
                    sendStream(DeviceFormStreamServerMessage.Completed(message.requestId, "unsubscribed"))
                }

                is DeviceFormStreamClientMessage.Ping ->
                    sendStream(DeviceFormStreamServerMessage.Pong(message.requestId))
            }
        }
    } finally {
        streamJob?.cancelAndJoin()
    }
}

private fun ApplicationCall.maxEvents(): Int? =
    parameters["maxEvents"]?.toIntOrNull()?.takeIf { it > 0 }

private fun SubscribeOptions.pollDelayMillis(): Long =
    maxRateHz
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?.let { (1_000.0 / it).roundToLong().coerceAtLeast(1L) }
        ?: 1_000L

private fun DeviceFormStreamOptions.withServerDefaults(settings: KrigServerSettings): DeviceFormStreamOptions =
    copy(maxRateHz = maxRateHz ?: settings.defaultSubscribeOptions.maxRateHz)

private fun DeviceFormStreamOptions.pollDelayMillis(settings: KrigServerSettings): Long =
    (maxRateHz ?: settings.defaultSubscribeOptions.maxRateHz)
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?.let { (1_000.0 / it).roundToLong().coerceAtLeast(1L) }
        ?: 1_000L

private fun DeviceFormStreamOptions.validationFault(schema: DeviceFormSchema): ValidationFault? {
    val rate = maxRateHz
    if (rate != null && (!rate.isFinite() || rate <= 0.0)) {
        return streamValidationFault("Stream maxRateHz must be positive and finite.")
    }
    val frameLimit = maxFrames
    if (frameLimit != null && frameLimit <= 0) {
        return streamValidationFault("Stream maxFrames must be positive.")
    }
    val readableNames = (schema.properties + schema.discoveredProperties)
        .asSequence()
        .filter { it.readable }
        .map { it.name }
        .toSet()
    val unknown = properties - readableNames
    return if (unknown.isEmpty()) {
        null
    } else {
        streamValidationFault(
            "Unknown or unreadable stream properties: ${unknown.sortedBy { it.toString() }.joinToString()}.",
        )
    }
}

private fun streamValidationFault(message: String): ValidationFault =
    ValidationFault(details = faultDetails(message))

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

@OptIn(ExperimentalKtorApi::class)
private fun Route.describeCommandRoute(): Route = describe {
    operationId = "executeDeviceFormCommand"
    summary = "Execute a neutral device form command"
    tag("KRig Device Server")
    parameters {
        path("deviceId") {
            description = "DataForge Name path parameter."
            schema = buildSchema(typeOf<String>())
        }
    }
    requestBody {
        required = true
        schema = buildSchema(typeOf<DeviceFormCommandEnvelope>())
    }
    responses {
        HttpStatusCode.OK {
            description = "Command result; predictable failures are encoded as OperationOutcome.Fail."
            schema = buildSchema(typeOf<DeviceFormCommandResult>())
        }
        HttpStatusCode.NotFound {
            description = "Device or manifest was not found."
            schema = buildSchema(typeOf<ServerFaultDto>())
        }
        HttpStatusCode.BadRequest {
            description = "Route parameter or request body validation failed."
            schema = buildSchema(typeOf<ServerFaultDto>())
        }
    }
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.describeSseRoute(): Route = describe {
    operationId = "subscribeDeviceFormEvents"
    summary = "Stream bounded device form state patches"
    tag("KRig Device Server")
    parameters {
        path("deviceId") {
            description = "DataForge Name path parameter."
            schema = buildSchema(typeOf<String>())
        }
        query("maxEvents") {
            description = "Optional positive event limit for tests and finite snapshots."
            schema = buildSchema(typeOf<Int>())
        }
    }
    responses {
        HttpStatusCode.OK {
            description = "text/event-stream where each form.patch event data is DeviceFormStatePatch JSON."
        }
        HttpStatusCode.NotFound {
            description = "Device or manifest was not found."
            schema = buildSchema(typeOf<ServerFaultDto>())
        }
    }
}

@OptIn(ExperimentalKtorApi::class)
private fun Route.describeStreamRoute(): Route = describe {
    operationId = "openDeviceFormStream"
    summary = "Open a bounded WebSocket device form state stream"
    tag("KRig Device Server")
    parameters {
        path("deviceId") {
            description = "DataForge Name path parameter."
            schema = buildSchema(typeOf<String>())
        }
    }
    description = "WebSocket endpoint. Text frames use DeviceFormStreamClientMessage and DeviceFormStreamServerMessage JSON."
    responses {
        HttpStatusCode.SwitchingProtocols {
            description = "WebSocket upgrade accepted."
        }
        HttpStatusCode.NotFound {
            description = "Device or manifest was not found before the WebSocket session starts."
            schema = buildSchema(typeOf<ServerFaultDto>())
        }
    }
}
