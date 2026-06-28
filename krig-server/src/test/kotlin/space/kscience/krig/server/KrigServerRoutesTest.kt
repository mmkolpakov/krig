@file:OptIn(space.kscience.krig.core.UnstableKrigForSubclassing::class)

package space.kscience.krig.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
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
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.manifestOf
import space.kscience.krig.core.meta.DeviceContractBuilder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KrigServerRoutesTest {
    @Test
    fun devicesRouteReturnsManifestSummary() = testApplication {
        application {
            installKrigServerJson()
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
    fun schemaRouteReturnsDeviceJsonSchema() = testApplication {
        application {
            installKrigServerJson()
            krigDeviceServer(testRegistry())
        }

        val response = client.get("/devices/sensor/schema")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("object", json["type"]?.jsonPrimitive?.content)
        assertNotNull(json["properties"]?.jsonObject?.get("rpm"))
    }

    @Test
    fun propertyRouteKeepsOperationOutcomeEnvelope() = testApplication {
        application {
            installKrigServerJson()
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
            installKrigServerJson()
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
            installKrigServerJson()
            krigDeviceServer(testRegistry())
        }

        val response = client.get("/devices/missing/schema")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue("device.not-found" in response.bodyAsText())
    }

    @Test
    fun openApiRouteDocumentsReadOnlySurface() = testApplication {
        application {
            installKrigServerJson()
            krigDeviceServer(testRegistry())
        }

        val response = client.get("/openapi.json")

        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("3.1.0", json["openapi"]?.jsonPrimitive?.content)
        assertNotNull(json["paths"]?.jsonObject?.get("/devices/{deviceId}/properties/{property}"))
    }

    private object SensorContract : DeviceContractBuilder() {
        val rpm by property(MetaConverter.double, TypeIds.DOUBLE)
    }

    private class SensorDevice : AbstractDevice(
        "sensor".asName(),
        DeviceRuntime(Context("server-test-${contextIds.incrementAndGet()}")),
    ) {
        override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
            if (propertyName == SensorContract.rpm.name) {
                OperationOutcome.Ok(Meta(42.0.asValue()))
            } else {
                OperationOutcome.Fail(
                    GenericOperationFault(
                        faultType = OperationFaultTypes.UnknownProperty,
                        message = "Unknown property '$propertyName'.",
                    ),
                )
            }
    }

    private fun testRegistry(): DeviceServerRegistry {
        val manifest: DeviceManifest = manifestOf(
            id = "lab.sensor",
            contract = SensorContract,
            version = "1.0.0",
        )
        return StaticDeviceServerRegistry(
            devices = mapOf("sensor".asName() to SensorDevice()),
            manifestsByDevice = mapOf("sensor".asName() to manifest),
        )
    }

    private companion object {
        val contextIds: AtomicInteger = AtomicInteger()
    }
}
