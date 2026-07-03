@file:OptIn(space.kscience.krig.core.UnstableKrigForSubclassing::class)

package space.kscience.krig.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.meta.asValue
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.serialization.krigJson
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.doubleValue
import space.kscience.krig.core.contracts.manifestOf
import space.kscience.krig.core.contracts.stringValue
import space.kscience.krig.core.meta.DeviceContractBuilder
import space.kscience.krig.ui.schema.DeviceFormCommandEnvelope
import space.kscience.krig.ui.schema.DeviceFormCommandKind
import space.kscience.krig.ui.schema.DeviceFormNodeId
import space.kscience.krig.ui.schema.DeviceFormSchema
import space.kscience.krig.ui.schema.DeviceFormStreamClientMessage
import space.kscience.krig.ui.schema.DeviceFormStreamOptions
import space.kscience.krig.ui.schema.DeviceFormStreamServerMessage
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KrigServerRoutesTest {
    @Test
    fun devicesRouteReturnsManifestSummary() = testApplication {
        application {
            installKrigServerDefaults()
            krigDeviceServer(testRegistry())
        }

        val response = client.get("/devices")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val devices = json["devices"].toString()
        assertTrue("sensor" in devices)
        assertTrue("lab.sensor" in devices)
        assertTrue("fnv1a64:" in devices)
    }

    @Test
    fun serverRouteAdvertisesFormCapabilities() = testApplication {
        application {
            installKrigServerDefaults()
            krigDeviceServer(testRegistry())
        }

        val response = client.get("/server")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue("\"FormSchema\"" in body)
        assertTrue("\"FormState\"" in body)
    }

    @Test
    fun schemaRouteReturnsDeviceJsonSchema() = testApplication {
        application {
            installKrigServerDefaults()
            krigDeviceServer(testRegistry())
        }

        val response = client.get("/devices/sensor/schema")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("object", json["type"]?.jsonPrimitive?.content)
        assertNotNull(json["properties"]?.jsonObject?.get("rpm"))
    }

    @Test
    fun formSchemaRouteReturnsNeutralSchema() = testApplication {
        application {
            installKrigServerDefaults()
            krigDeviceServer(testRegistry())
        }

        val response = client.get("/devices/sensor/form-schema")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue("\"manifestId\"" in body)
        assertTrue("\"commands\"" in body)
        assertTrue("\"valueDescriptor\"" in body)
        assertTrue("\"rpm\"" in body)
    }

    @Test
    fun formStateRouteReturnsQualityAwareValues() = testApplication {
        application {
            installKrigServerDefaults()
            krigDeviceServer(testRegistry())
        }

        val response = client.get("/devices/sensor/form-state")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue("\"schemaHash\"" in body)
        assertTrue("\"rpm\"" in body)
        assertTrue("\"label\":\"GOOD\"" in body)
        assertTrue("\"time\"" in body)
    }

    @Test
    fun propertyRouteKeepsOperationOutcomeEnvelope() = testApplication {
        application {
            installKrigServerDefaults()
            krigDeviceServer(testRegistry())
        }

        val response = client.get("/devices/sensor/properties/rpm")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue("\"property\":\"rpm\"" in body)
        assertTrue("42.0" in body)
    }

    @Test
    fun observedRouteExposesQualityAwareRead() = testApplication {
        application {
            installKrigServerDefaults()
            krigDeviceServer(testRegistry())
        }

        val response = client.get("/devices/sensor/observations/rpm")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue("\"label\":\"GOOD\"" in body)
        assertTrue("\"time\"" in body)
    }

    @Test
    fun missingDeviceIsProtocolFault() = testApplication {
        application {
            installKrigServerDefaults()
            krigDeviceServer(testRegistry())
        }

        val response = client.get("/devices/missing/schema")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue("device.not-found" in response.bodyAsText())
    }

    @Test
    fun openApiRouteDocumentsReadOnlySurface() = testApplication {
        application {
            installKrigServerDefaults()
            krigDeviceServer(testRegistry())
        }

        val response = client.get("/openapi.json")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("3.1.1", json["openapi"]?.jsonPrimitive?.content)
        assertNotNull(json["paths"]?.jsonObject?.get("/devices/{deviceId}/properties/{property}"))
        assertNotNull(json["paths"]?.jsonObject?.get("/devices/{deviceId}/form-schema"))
        assertNotNull(json["paths"]?.jsonObject?.get("/devices/{deviceId}/form-state"))
        assertNotNull(json["paths"]?.jsonObject?.get("/devices/{deviceId}/commands"))
        assertNotNull(json["paths"]?.jsonObject?.get("/devices/{deviceId}/form-events"))
        assertNotNull(json["paths"]?.jsonObject?.get("/devices/{deviceId}/streams"))
    }

    @Test
    fun commandRouteExecutesReadWriteAndActionCommands() = testApplication {
        application {
            installKrigServerDefaults()
            krigDeviceServer(testRegistry())
        }

        val schema = client.deviceFormSchema()
        val readCommand = schema.command(DeviceFormCommandKind.ReadProperty)
        val writeCommand = schema.command(DeviceFormCommandKind.WriteProperty)
        val actionCommand = schema.command(DeviceFormCommandKind.ExecuteAction)

        val readResponse = client.postCommand(DeviceFormCommandEnvelope(readCommand.id, correlationId = "read-1"))
        assertEquals(HttpStatusCode.OK, readResponse.status)
        val readBody = readResponse.bodyAsText()
        assertTrue("\"correlationId\":\"read-1\"" in readBody)
        assertTrue("42.0" in readBody)
        assertTrue("\"GOOD\"" in readBody)

        val writeResponse = client.postCommand(
            DeviceFormCommandEnvelope(writeCommand.id, input = Meta(55.0.asValue())),
        )
        assertEquals(HttpStatusCode.OK, writeResponse.status)
        assertTrue("command-output.completed" in writeResponse.bodyAsText())

        val afterWrite = client.get("/devices/sensor/properties/rpm").bodyAsText()
        assertTrue("55.0" in afterWrite)

        val actionResponse = client.postCommand(
            DeviceFormCommandEnvelope(actionCommand.id, input = Meta("ping".asValue())),
        )
        assertEquals(HttpStatusCode.OK, actionResponse.status)
        assertTrue("echo:ping" in actionResponse.bodyAsText())
    }

    @Test
    fun commandRouteKeepsInvalidCommandAsOperationOutcome() = testApplication {
        application {
            installKrigServerDefaults()
            krigDeviceServer(testRegistry())
        }

        val response = client.postCommand(
            DeviceFormCommandEnvelope(DeviceFormNodeId("command.missing".asName())),
        )

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue("fault.validation" in response.bodyAsText())
    }

    @Test
    fun formEventsRouteEmitsFiniteSsePatch() = testApplication {
        application {
            installKrigServerDefaults()
            krigDeviceServer(testRegistry())
        }

        val response = client.get("/devices/sensor/form-events?maxEvents=1")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue("event: form.patch" in body)
        assertTrue("\"rpm\"" in body)
        assertTrue("\"GOOD\"" in body)
    }

    @Test
    fun formEventsRouteKeepsReadFaultsInPatch() = testApplication {
        application {
            installKrigServerDefaults()
            krigDeviceServer(testRegistry(failReads = true))
        }

        val response = client.get("/devices/sensor/form-events?maxEvents=1")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue("event: form.patch" in body)
        assertTrue("\"rpm\"" in body)
        assertTrue("Read failed for property 'rpm'." in body)
    }

    @Test
    fun streamRouteSendsBoundedOutcomeAwarePatches() = testApplication {
        application {
            installKrigServerDefaults()
            krigDeviceServer(testRegistry())
        }
        val wsClient = createClient { install(ClientWebSockets) }

        wsClient.webSocket("/devices/sensor/streams") {
            sendClient(
                DeviceFormStreamClientMessage.Subscribe(
                    requestId = "stream-1",
                    options = DeviceFormStreamOptions(maxRateHz = 100.0, maxFrames = 1),
                ),
            )

            val subscribed = receiveServer()
            assertTrue(subscribed is DeviceFormStreamServerMessage.Subscribed)
            assertEquals("stream-1", subscribed.requestId)

            val patch = receiveServer()
            assertTrue(patch is DeviceFormStreamServerMessage.Patch)
            val rpm = patch.patch.updates[SensorContract.rpm.name]
            assertTrue(rpm is OperationOutcome.Ok)
            assertEquals(42.0, rpm.value.value?.doubleValue)

            val completed = receiveServer()
            assertTrue(completed is DeviceFormStreamServerMessage.Completed)
            assertEquals("stream-1", completed.requestId)
        }
    }

    @Test
    fun streamRouteHandlesPingFaultAndUnsubscribeFrames() = testApplication {
        application {
            installKrigServerDefaults()
            krigDeviceServer(testRegistry())
        }
        val wsClient = createClient { install(ClientWebSockets) }

        wsClient.webSocket("/devices/sensor/streams") {
            sendClient(DeviceFormStreamClientMessage.Ping(requestId = "ping-1"))
            val pong = receiveServer()
            assertTrue(pong is DeviceFormStreamServerMessage.Pong)
            assertEquals("ping-1", pong.requestId)

            sendClient(
                DeviceFormStreamClientMessage.Subscribe(
                    requestId = "bad-1",
                    options = DeviceFormStreamOptions(properties = setOf("missing".asName()), maxFrames = 1),
                ),
            )
            val fault = receiveServer()
            assertTrue(fault is DeviceFormStreamServerMessage.Fault)
            assertEquals("bad-1", fault.requestId)
            assertEquals(OperationFaultTypes.Validation, fault.fault.faultType)
            val validation = fault.fault
            assertTrue(validation is ValidationFault)
            assertTrue("missing" in validation.details.toString())

            sendClient(
                DeviceFormStreamClientMessage.Subscribe(
                    requestId = "stream-2",
                    options = DeviceFormStreamOptions(maxRateHz = 1.0),
                ),
            )
            assertTrue(receiveServer() is DeviceFormStreamServerMessage.Subscribed)
            assertTrue(receiveServer() is DeviceFormStreamServerMessage.Patch)
            sendClient(DeviceFormStreamClientMessage.Unsubscribe(requestId = "stream-2"))
            val completed = receiveServer()
            assertTrue(completed is DeviceFormStreamServerMessage.Completed)
            assertEquals("unsubscribed", completed.reason)
        }
    }

    private object SensorContract : DeviceContractBuilder() {
        val rpm by mutableProperty(MetaConverter.double, TypeIds.DOUBLE)
        val echo by action(MetaConverter.string, MetaConverter.string)
    }

    private class SensorDevice(
        private val failReads: Boolean = false,
    ) : AbstractDevice(
        "sensor".asName(),
        DeviceRuntime(Context("server-test-${contextIds.incrementAndGet()}")),
    ) {
        private var rpmValue: Double = 42.0

        override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
            if (propertyName == SensorContract.rpm.name) {
                if (failReads) {
                    OperationOutcome.Fail(
                        GenericOperationFault(
                            faultType = OperationFaultTypes.Transport,
                            message = "Read failed for property '$propertyName'.",
                        ),
                    )
                } else {
                    OperationOutcome.Ok(Meta(rpmValue.asValue()))
                }
            } else {
                OperationOutcome.Fail(
                    GenericOperationFault(
                        faultType = OperationFaultTypes.UnknownProperty,
                        message = "Unknown property '$propertyName'.",
                    ),
                )
            }

        override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
            if (propertyName == SensorContract.rpm.name) {
                val typedValue = value.doubleValue
                if (typedValue == null) {
                    OperationOutcome.Fail(
                        GenericOperationFault(
                            faultType = OperationFaultTypes.UnsupportedValue,
                            message = "Property '$propertyName' expects a Double value.",
                        ),
                    )
                } else {
                    rpmValue = typedValue
                    OperationOutcome.OkUnit
                }
            } else {
                OperationOutcome.Fail(
                    GenericOperationFault(
                        faultType = OperationFaultTypes.UnknownProperty,
                        message = "Unknown property '$propertyName'.",
                    ),
                )
            }

        override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
            if (actionName == SensorContract.echo.name) {
                OperationOutcome.Ok(Meta("echo:${argument?.stringValue.orEmpty()}".asValue()))
            } else {
                OperationOutcome.Fail(
                    GenericOperationFault(
                        faultType = OperationFaultTypes.UnknownAction,
                        message = "Unknown action '$actionName'.",
                    ),
                )
            }
    }

    private fun testRegistry(failReads: Boolean = false): DeviceServerRegistry {
        val manifest: DeviceManifest = manifestOf(
            id = "lab.sensor",
            contract = SensorContract,
            version = "1.0.0",
        )
        return StaticDeviceServerRegistry(
            devices = mapOf("sensor".asName() to SensorDevice(failReads)),
            manifestsByDevice = mapOf("sensor".asName() to manifest),
        )
    }

    private suspend fun HttpClient.deviceFormSchema(): DeviceFormSchema =
        wireJson.decodeFromString(DeviceFormSchema.serializer(), get("/devices/sensor/form-schema").bodyAsText())

    private fun DeviceFormSchema.command(kind: DeviceFormCommandKind) = commands.first { it.kind == kind }

    private suspend fun HttpClient.postCommand(envelope: DeviceFormCommandEnvelope) =
        post("/devices/sensor/commands") {
            contentType(ContentType.Application.Json)
            setBody(wireJson.encodeToString(DeviceFormCommandEnvelope.serializer(), envelope))
        }

    private suspend fun DefaultClientWebSocketSession.sendClient(message: DeviceFormStreamClientMessage) {
        val text = wireJson.encodeToString(DeviceFormStreamClientMessage.serializer(), message)
        send(Frame.Text(text))
    }

    private suspend fun DefaultClientWebSocketSession.receiveServer(): DeviceFormStreamServerMessage =
        withTimeout(5.seconds) {
            val text = (incoming.receive() as Frame.Text).readText()
            wireJson.decodeFromString(DeviceFormStreamServerMessage.serializer(), text)
        }

    private companion object {
        val contextIds: AtomicInteger = AtomicInteger()
        val wireJson: Json = krigJson()
    }
}
