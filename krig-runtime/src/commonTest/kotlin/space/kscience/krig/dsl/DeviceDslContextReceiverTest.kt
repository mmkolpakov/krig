@file:OptIn(
    space.kscience.krig.core.PerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)
package space.kscience.krig.dsl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.double
import space.kscience.dataforge.meta.get
import space.kscience.dataforge.meta.long
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.DeviceFaultException
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.identifiers.Permission
import space.kscience.krig.api.result.DeviceOutcome
import space.kscience.krig.api.services.AuditService
import space.kscience.krig.api.services.AuthorizationService
import space.kscience.krig.core.contracts.DeviceEnvironment
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.meta.MutableDevicePropertySpec
import space.kscience.dataforge.meta.MetaConverter
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Inline DSL lambdas receive narrow device scopes: enough for computed properties,
 * without exposing the full device lifecycle surface.
 */
class DeviceDslContextReceiverTest {
    private val lexicalReceiverToken: String = "outer-receiver"

    private object TestAuthorizationService : PluginFactory<AuthorizationService> {
        override val tag: PluginTag get() = AuthorizationService.tag

        override fun build(context: Context, meta: Meta): AuthorizationService =
            object : AbstractPlugin(meta), AuthorizationService {
                override suspend fun checkPermission(principal: Principal, permission: Permission) = Unit
            }
    }

    private fun permissiveContext(name: String): Context = Context(name) {
        plugin(TestAuthorizationService)
        plugin(AuditService)
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
        assertNotNull(meta.value?.long ?: meta["value"]?.long)
    }

    @Test
    fun actionLambdaSeesNameWithoutPrefix() = runTest {
        val seenName = AtomicReference<String?>(null)
        val device = device("named-thing", permissiveContext("dsl-ctx-receiver-2")) {
            // a no-op property so the inline-builder has at least one element
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
                seenThis.store(this@DeviceDslContextReceiverTest.lexicalReceiverToken)
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
        assertEquals(42.0, read.value?.double ?: read["value"]?.double)
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
        val v = read.value?.double ?: read["value"]?.double ?: 0.0
        assertEquals(42.5, v)
    }

    @Test
    fun inlineBackendUsesProvidedEnvironmentDirectly() = runTest {
        val backend = inlineBackend(
            readers = mapOf("value".asName() to DeviceReadBlock { 1.0 }),
            writers = emptyMap(),
            valueWriters = emptyMap(),
            actions = emptyMap(),
            stepBody = null,
            closeBody = null,
        )
        val env = object : DeviceEnvironment {
            override val clock: Clock = Clock.System
            override val deviceScope: CoroutineScope = this@runTest
            override val name = "plain-env".asName()
        }

        val outcome = context(env) {
            backend.read(synthesizeProperty("value".asName(), mutable = false))
        }

        val ok = assertIs<DeviceOutcome.Ok<Meta>>(outcome)
        assertEquals(1.0, ok.value.value?.double ?: ok.value["value"]?.double)
    }

    @Test
    fun inlineTypedWriteMismatchBecomesValidationFault() = runTest {
        val forgedSpec = object : MutableDevicePropertySpec<Device, String> {
            override val name = "setpoint".asName()
            override val descriptor: PropertyDescriptor = synthesizeProperty(name, mutable = true)
            override val converter: MetaConverter<String> = object : MetaConverter<String> {
                override fun convert(obj: String): Meta = Meta(obj)
                override fun readOrNull(source: Meta): String? = source["never"]?.string
            }
            override suspend fun read(device: Device): String? = null
            override suspend fun write(device: Device, value: String) = Unit
        }
        val device = device("typed-mismatch", permissiveContext("dsl-typed-mismatch")) {
            mutableProperty("setpoint", initial = 20.0)
            action("badWrite") { _ ->
                write(forgedSpec, "not-a-double")
                null
            }
        }

        val failure = assertFailsWith<DeviceFaultException> {
            device.execute("badWrite".asName(), null)
        }

        val fault = assertIs<ValidationFault>(failure.fault)
        assertEquals("setpoint", fault.details["property"]?.string)
    }
}
