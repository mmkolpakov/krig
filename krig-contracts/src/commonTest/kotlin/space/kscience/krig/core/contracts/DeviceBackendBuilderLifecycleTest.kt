package space.kscience.krig.core.contracts

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.core.contracts.sampling.doubleSampler
import space.kscience.krig.core.meta.DeviceContractBuilder
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object LifecycleSpec : DeviceContractBuilder() {
    val value by mutableProperty(MetaConverter.double, TypeIds.DOUBLE)
    val command by action(MetaConverter.string, MetaConverter.string)
}

private object LifecycleReadOnlySpec : DeviceContractBuilder() {
    val value by property(MetaConverter.double, TypeIds.DOUBLE)
}

class DeviceBackendBuilderLifecycleTest {

    @Test
    fun mapRegistrationFailsAfterBuildInsteadOfBeingIgnored() {
        lateinit var escapedBuilder: DeviceBackendBuilder
        val backend = deviceBackend {
            escapedBuilder = this
        }

        val registration = runCatching {
            escapedBuilder.reader(LifecycleSpec.value) { 42.0 }
        }

        assertNull(backend.reader(LifecycleSpec.value), "The built backend must remain an immutable snapshot")
        val error = assertIs<IllegalStateException>(registration.exceptionOrNull())
        assertContains(error.message.orEmpty(), "built")
    }

    @Test
    fun lifecycleRegistrationFailsAfterBuildInsteadOfBeingIgnored() = runTest {
        lateinit var escapedBuilder: DeviceBackendBuilder
        var lateCloseCalled = false
        val backend = deviceBackend {
            escapedBuilder = this
        }

        val registration = runCatching {
            escapedBuilder.onClose { lateCloseCalled = true }
        }
        backend.close()

        assertFalse(lateCloseCalled, "The built backend must not observe a late lifecycle callback")
        assertIs<IllegalStateException>(registration.exceptionOrNull())
    }

    @Test
    fun everyConfigurationRootRejectsMutationAfterBuild() {
        lateinit var escapedBuilder: DeviceBackendBuilder
        var samplerFactoryCalled = false
        val backend = deviceBackend {
            escapedBuilder = this
        }

        val attempts: List<Pair<String, DeviceBackendBuilder.() -> Unit>> = listOf(
            "reader" to { reader(LifecycleSpec.value) { 1.0 } },
            "observed reader" to { observedReader(LifecycleSpec.value) { error("must not run") } },
            "binary reader" to { binaryReader(LifecycleSpec.value) { error("must not run") } },
            "bytes reader" to { bytesReader(LifecycleSpec.value) { error("must not run") } },
            "writer" to { writer(LifecycleSpec.value) {} },
            "binding" to { bind(LifecycleSpec.value, read = { 1.0 }, write = {}) },
            "sampler" to {
                sampler(LifecycleSpec.value) {
                    samplerFactoryCalled = true
                    doubleSampler(capacity = 2)
                }
            },
            "typed action" to { action(LifecycleSpec.command) { it } },
            "named readable cell" to { readable("late-readable", 1.0, MetaConverter.double) },
            "typed readable cell" to { readable(LifecycleReadOnlySpec.value, initial = 1.0) },
            "named writable cell" to { writable("late-writable", 1.0, MetaConverter.double) },
            "typed writable cell" to { writable(LifecycleSpec.value, initial = 1.0) },
            "named computed cell" to { computed("late-computed") { 1.0 } },
            "typed computed cell" to { computed(LifecycleReadOnlySpec.value) { 1.0 } },
            "Meta action" to { action("late-action") { null } },
            "typed Meta action" to { actionMeta(LifecycleSpec.command) { null } },
            "batch Meta reader" to { batchMetaReader { emptyMap() } },
            "batch observed reader" to { batchObservedReader { emptyMap() } },
            "batch binary reader" to { batchBinaryReader { emptyMap() } },
            "batch writer" to { batchWriter { emptyMap() } },
            "step callback" to { onStep {} },
            "close callback" to { onClose {} },
        )

        attempts.forEach { (name, attempt) ->
            val error = assertFailsWith<IllegalStateException>("$name must reject configuration after build") {
                escapedBuilder.attempt()
            }
            assertContains(
                error.message.orEmpty(),
                "built",
                message = "$name must report the closed lifecycle",
            )
        }
        assertFalse(samplerFactoryCalled, "A rejected sampler must not evaluate its factory")
        assertNull(backend.propertySpec(LifecycleSpec.value.name))
    }

    @Test
    fun builderMaterializationIsSingleUse() {
        val builder = DeviceBackendBuilder()
        val backend = builder.build()

        assertFailsWith<IllegalStateException> {
            builder.build()
        }
        assertTrue(backend.propertySpecs().isEmpty())
    }

    @Test
    fun runtimeCallbackCannotReconfigureBuiltTopology() = runTest {
        lateinit var escapedBuilder: DeviceBackendBuilder
        val backend = deviceBackend {
            escapedBuilder = this
            reader(LifecycleSpec.value) {
                escapedBuilder.writer(LifecycleSpec.value) {}
                42.0
            }
        }
        val reader = assertNotNull(backend.reader(LifecycleSpec.value))

        val read = runCatching { reader.read() }

        assertNull(backend.writer(LifecycleSpec.value), "A runtime callback must not change backend topology")
        assertIs<IllegalStateException>(read.exceptionOrNull())
    }

    @Test
    fun runtimeCellHandleRemainsLiveAfterConfigurationIsFrozen() = runTest {
        lateinit var cell: ConnectionProperty<Double>
        val backend = deviceBackend {
            cell = writable(LifecycleSpec.value, initial = 1.0)
        }

        cell.value = 7.0

        val bound = backend.bind(
            BackendEnvironment.from(testRuntime("builder-lifecycle-test"), Name.of("runtime-cell")),
        )
        assertEquals(7.0, bound.read(LifecycleSpec.value.descriptor).doubleValue)
    }
}
