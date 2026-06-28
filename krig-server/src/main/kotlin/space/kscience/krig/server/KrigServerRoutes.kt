package space.kscience.krig.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import space.kscience.krig.api.serialization.krigJson
import space.kscience.krig.core.contracts.schemaHash
import space.kscience.krig.core.contracts.toJsonSchema

/** Installs KRig's JSON defaults for Ktor responses. Call once before [krigDeviceServer]. */
public fun Application.installKrigServerJson(wireJson: Json = krigJson()): Unit {
    install(ContentNegotiation) {
        json(wireJson)
    }
}

/** Installs the default KRig device HTTP routes into this [Application]. */
public fun Application.krigDeviceServer(
    registry: DeviceServerRegistry,
    settings: KrigServerSettings = KrigServerSettings(),
): Unit {
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
public fun Route.krigDeviceRoutes(
    registry: DeviceServerRegistry,
    settings: KrigServerSettings = KrigServerSettings(),
): Unit {
    get("/server") {
        call.respond(
            KrigServerInfoDto(
                apiVersion = "0.1.0",
                readOnly = true,
                defaultSubscribeOptions = settings.defaultSubscribeOptions.toDto(),
            ),
        )
    }

    get("/openapi.json") {
        call.respond(krigOpenApiDocument(settings))
    }

    get("/tree") {
        call.respond(registry.treeDto())
    }

    get("/devices") {
        call.respond(registry.treeDto())
    }

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
    }

    get("/devices/{deviceId}/manifest") {
        val (deviceId, _) = call.resolveDevice(registry) ?: return@get
        val manifest = registry.manifest(deviceId)
        if (manifest == null) {
            call.respondNotFound("manifest.not-found", "Manifest for device '$deviceId' is not registered.")
            return@get
        }
        call.respond(manifest.toDto())
    }

    get("/devices/{deviceId}/schema") {
        val (deviceId, _) = call.resolveDevice(registry) ?: return@get
        val manifest = registry.manifest(deviceId)
        if (manifest == null) {
            call.respondNotFound("manifest.not-found", "Manifest for device '$deviceId' is not registered.")
            return@get
        }
        call.respond(manifest.toJsonSchema())
    }

    get("/devices/{deviceId}/actions") {
        val (deviceId, _) = call.resolveDevice(registry) ?: return@get
        val manifest = registry.manifest(deviceId)
        if (manifest == null) {
            call.respondNotFound("manifest.not-found", "Manifest for device '$deviceId' is not registered.")
            return@get
        }
        call.respond(manifest.actions.values.sortedBy { it.name.toString() }.map { it.toDto() })
    }

    get("/devices/{deviceId}/properties/{property}") {
        val (deviceId, device) = call.resolveDevice(registry) ?: return@get
        val property = call.pathName("property") ?: return@get
        val outcome = device.readPropertyOutcome(property)
        call.respond(outcome.toPropertyReadDto(deviceId.toString(), property.toString()))
    }

    get("/devices/{deviceId}/observations/{property}") {
        val (deviceId, device) = call.resolveDevice(registry) ?: return@get
        val property = call.pathName("property") ?: return@get
        val outcome = device.readObservedOutcome(property)
        call.respond(outcome.toObservedReadDto(deviceId.toString(), property.toString()))
    }
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

private suspend fun ApplicationCall.respondNotFound(type: String, message: String): Unit {
    respond(HttpStatusCode.NotFound, ServerFaultDto(type = type, message = message))
}

private suspend fun ApplicationCall.respondBadRequest(type: String, message: String): Unit {
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
