package space.kscience.krig.dsl

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

@OptIn(ExperimentalCompilerApi::class)
class StateModelDslScopeCompileTest {

    @Test
    fun nestedStateModelCannotCaptureOuterBackendReceiver() {
        val result = compileProbe(
            """
            deviceBackend {
                stateModel(::ProbeState) {
                    onClose { }
                }
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "onClose")
        assertContains(result.messages, "implicit receiver")
    }

    @Test
    fun nestedStateModelCannotCaptureOuterKrigReceiver() {
        val result = compileProbe(
            """
            deviceGroup {
                stateModel(::ProbeState) {
                    deviceGroup("leak") { }
                }
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "deviceGroup")
        assertContains(result.messages, "implicit receiver")
    }

    @Test
    fun deferredStateCallbacksCannotCaptureBuilderReceiver() {
        val result = compileProbe(
            """
            stateModel(::ProbeState) {
                reader(valueSpec) {
                    leakFromReader()
                    value
                }
                writer(valueSpec) { next ->
                    leakFromWriter()
                    value = next
                }
                bind(
                    valueSpec,
                    read = {
                        leakFromBindRead()
                        value
                    },
                    write = { next ->
                        leakFromBindWrite()
                        value = next
                    },
                )
                action(actionSpec) { input ->
                    leakFromAction()
                    input
                }
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        for (symbol in listOf(
            "leakFromReader",
            "leakFromWriter",
            "leakFromBindRead",
            "leakFromBindWrite",
            "leakFromAction",
        )) {
            assertContains(result.messages, symbol)
        }
        assertContains(result.messages, "implicit receiver")
    }

    @Test
    fun explicitOuterReceiverAndOrdinaryStateAccessRemainAvailable() {
        val result = compileProbe(
            """
            deviceBackend outer@{
                stateModel(::ProbeState) {
                    this@outer.onClose { }
                    bind(valueSpec, read = { value }, write = { value = it })
                }
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    private fun compileProbe(body: String): JvmCompilationResult = KotlinCompilation().apply {
        inheritClassPath = true
        sources = listOf(
            SourceFile.kotlin(
                "StateModelDslScopeProbe.kt",
                """
                @file:Suppress("unused")

                package probe

                import space.kscience.dataforge.meta.MetaConverter
                import space.kscience.dataforge.names.Name
                import space.kscience.krig.api.descriptors.PropertyKind
                import space.kscience.krig.api.descriptors.TypeIds
                import space.kscience.krig.core.contracts.deviceBackend
                import space.kscience.krig.core.meta.deviceActionContract
                import space.kscience.krig.core.meta.mutableDevicePropertyContract
                import space.kscience.krig.dsl.deviceGroup
                import space.kscience.krig.dsl.StateModelBuilder
                import space.kscience.krig.dsl.stateModel

                private data class ProbeState(var value: Double = 0.0)

                private val valueSpec = mutableDevicePropertyContract(
                    name = Name.of("value"),
                    converter = MetaConverter.double,
                    kind = PropertyKind.PHYSICAL,
                    valueTypeId = TypeIds.DOUBLE,
                )

                private val actionSpec = deviceActionContract(
                    name = Name.of("adjust"),
                    inputConverter = MetaConverter.double,
                    outputConverter = MetaConverter.double,
                )

                private fun StateModelBuilder<ProbeState>.leakFromReader() = Unit
                private fun StateModelBuilder<ProbeState>.leakFromWriter() = Unit
                private fun StateModelBuilder<ProbeState>.leakFromBindRead() = Unit
                private fun StateModelBuilder<ProbeState>.leakFromBindWrite() = Unit
                private fun StateModelBuilder<ProbeState>.leakFromAction() = Unit

                fun probe() {
                    $body
                }
                """.trimIndent(),
            ),
        )
    }.compile()
}
