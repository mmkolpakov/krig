@file:OptIn(
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.KrigPerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)
package space.kscience.krig.dsl

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.double
import space.kscience.dataforge.meta.long
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.asName
import space.kscience.krig.core.contracts.readProperty
import space.kscience.krig.core.contracts.writeProperty
import space.kscience.krig.core.contracts.execute
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.services.AllowAllAuthorizationService
import space.kscience.krig.api.services.AuditService
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.BackendEnvironment
import space.kscience.krig.core.contracts.BoundDeviceBackend
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceBackend
import space.kscience.krig.core.contracts.DeviceMessaging
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.contracts.readOutcome
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import space.kscience.krig.core.operations.HybridLogicalClock
import space.kscience.krig.core.pipeline.PipelineDevice
import space.kscience.dataforge.meta.MetaConverter
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Device DSL lambdas receive narrow device scopes: enough for computed properties,
 * without exposing the full device lifecycle surface.
 */
class DeviceDslContextParametersTest {
    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private class DelegatingTimeSource : TimeSource {
        override fun markNow(): TimeMark = TimeSource.Monotonic.markNow()
    }

    private class EnvironmentCapturingBackend : DeviceBackend {
        val environment: AtomicReference<BackendEnvironment?> = AtomicReference(null)

        override fun bind(environment: BackendEnvironment): BoundDeviceBackend {
            this.environment.store(environment)
            return object : BoundDeviceBackend {
                override val environment: BackendEnvironment = environment

                override suspend fun read(property: PropertyDescriptor): Meta = Meta.EMPTY

                override suspend fun write(property: PropertyDescriptor, value: Meta) = Unit

                override suspend fun execute(
                    action: ActionDescriptor,
                    argument: Meta?,
                ): Meta? = null

                override fun close() = Unit
            }
        }
    }

    private val lexicalReceiverToken: String = "outer-receiver"

    private fun permissiveContext(name: String): Context = Context(name) {
        plugin(AllowAllAuthorizationService)
        plugin(AuditService)
    }

    private fun testRuntime(name: String): DeviceRuntime {
        val clock = FixedClock(Instant.fromEpochMilliseconds(12_345L))
        return DeviceRuntime(
            context = permissiveContext(name),
            clock = clock,
            messaging = DeviceMessaging(controlBufferCapacity = 7, dataBufferCapacity = 3, replay = 1),
            hlc = HybridLogicalClock(physicalClock = clock),
            timeSource = DelegatingTimeSource(),
        )
    }

    private fun assertRuntimeIdentity(expected: DeviceRuntime, device: Device) {
        val baseDevice = assertIs<AbstractDevice>(assertIs<PipelineDevice>(device).delegate)
        val actual = baseDevice.runtime
        assertSame(expected, actual)
        assertSame(expected.context, actual.context)
        assertSame(expected.clock, actual.clock)
        assertSame(expected.timeSource, actual.timeSource)
        assertSame(expected.messaging, actual.messaging)
        assertSame(expected.hlc, actual.hlc)
        assertSame(expected.clock, device.clock)
        assertSame(expected.timeSource, device.timeSource)
    }

    @Test
    fun contextRuntimeIsPreservedByDeclarativeDevice() = runTest {
        val runtime = testRuntime("dsl-context-runtime-declarative")
        val created = with(runtime) {
            device("declarative") {
                property("timestamp") { clock.now().toEpochMilliseconds() }
            }
        }

        try {
            assertRuntimeIdentity(runtime, created)
        } finally {
            created.shutdown()
        }
    }

    @Test
    fun contextRuntimeIsPreservedByExplicitBackendDevice() = runTest {
        val runtime = testRuntime("dsl-context-runtime-explicit")
        val backend = EnvironmentCapturingBackend()
        val created = with(runtime) {
            device("explicit", backend)
        }

        try {
            assertRuntimeIdentity(runtime, created)
            val environment = assertNotNull(backend.environment.load())
            assertSame(runtime.context, environment.context)
            assertSame(runtime.clock, environment.clock)
            assertSame(runtime.timeSource, environment.timeSource)
        } finally {
            created.shutdown()
        }
    }

    @Test
    fun propertyLambdaSeesClockWithoutPrefix() = runTest {
        val sawClock = AtomicBoolean(false)
        val seenMillis = AtomicLong(-1L)
        val device = device("d", permissiveContext("dsl-ctx-receiver-1")) {
            property("now") {
                sawClock.store(true)
                seenMillis.store(clock.now().toEpochMilliseconds())
                clock.now().toEpochMilliseconds()
            }
        }
        val meta = device.readProperty("now".asName())
        assertTrue(sawClock.load(), "property lambda must observe clock as a context member")
        assertTrue(seenMillis.load() > 0L, "clock.now() must return non-zero millis")
        // The reader returned a Long — wraps as a root-value Meta whose .value is the Long.
        assertNotNull(meta.value?.long ?: meta["value".asName()]?.long)
    }

    @Test
    fun actionLambdaSeesNameWithoutPrefix() = runTest {
        val seenName = AtomicReference<String?>(null)
        val device = device("named-thing", permissiveContext("dsl-ctx-receiver-2")) {
            // a no-op property so the device builder has at least one element
            property("dummy") { 0.0 }
            action("identify") { _ ->
                seenName.store(name.toString())
                metaOf(name.toString())
            }
        }
        val result = device.execute("identify".asName(), null)
        assertEquals("named-thing", seenName.load())
        assertNotNull(result)
    }

    @Test
    fun scopedDslCanStillReachOuterReceiverByLabel() = runTest {
        val seenThis = AtomicReference<String?>(null)
        val device = device("lexical", permissiveContext("dsl-ctx-context-4")) {
            property("receiver") {
                seenThis.store(this@DeviceDslContextParametersTest.lexicalReceiverToken)
                name.toString()
            }
        }

        device.readProperty("receiver".asName()).let { }

        assertEquals("outer-receiver", seenThis.load())
    }

    @Test
    fun computedPropertyCanReadSameDeviceProperty() = runTest {
        val device = device("computed", permissiveContext("dsl-computed-property")) {
            mutableProperty("base", initial = 21.0)
            propertyDouble("doubleBase") {
                readDouble("base") * 2.0
            }
        }

        val read = device.readProperty("doubleBase".asName())
        assertEquals(42.0, read.value?.double ?: read["value".asName()]?.double)
    }

    @Test
    fun mutablePropertyStillWorksAfterContextMigration() = runTest {
        // Cell-backed mutables don't use the Device receiver, but they must keep working
        // (since the underlying lambdas were re-typed). Smoke-check: write/read round-trip.
        val device = device("setpoint-host", permissiveContext("dsl-ctx-receiver-3")) {
            mutableProperty("setpoint", initial = 20.0)
        }
        device.writeProperty("setpoint".asName(), metaOf(42.5))
        val read = device.readProperty("setpoint".asName())
        val v = read.value?.double ?: read["value".asName()]?.double ?: 0.0
        assertEquals(42.5, v)
    }

    @Test
    fun declarativeBackendUsesProvidedEnvironmentDirectly() = runTest {
        val backend = declarativeBackend(
            readers = mapOf("value".asName() to DeviceReadBlock { 1.0 }),
            writers = emptyMap(),
            valueWriters = emptyMap(),
            actions = emptyMap(),
            stepBody = null,
            closeBody = null,
        )
        val env = BackendEnvironment(
            context = Context("declarative-backend-env-test"),
            name = "plain-env".asName(),
            clock = Clock.System,
        )

        val outcome = backend.bind(env).readOutcome(synthesizeProperty("value".asName(), mutable = false))

        val ok = assertIs<OperationOutcome.Ok<Meta>>(outcome)
        assertEquals(1.0, ok.value.value?.double ?: ok.value["value".asName()]?.double)
    }

    @Test
    fun declarativeTypedWriteMismatchBecomesValidationFault() = runTest {
        val forgedSpec = object : MutableDevicePropertyContract<String> {
            override val name = "setpoint".asName()
            override val descriptor: PropertyDescriptor = synthesizeProperty(name, mutable = true)
            override val converter: MetaConverter<String> = object : MetaConverter<String> {
                override fun convert(obj: String): Meta = Meta(obj)
                override fun readOrNull(source: Meta): String? = source["never".asName()]?.string
            }
        }
        val device = device("typed-mismatch", permissiveContext("dsl-typed-mismatch")) {
            mutableProperty("setpoint", initial = 20.0)
            action("badWrite") { _ ->
                write(forgedSpec, "not-a-double")
                null
            }
        }

        val failure = assertFailsWith<OperationFaultException> {
            device.execute("badWrite".asName(), null)
        }

        val fault = assertIs<ValidationFault>(failure.fault)
        assertEquals("setpoint", fault.details["property".asName()]?.string)
    }
}
