@file:Suppress("UnusedSymbol")

package space.kscience.krig.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

@OptIn(ExperimentalCompilerApi::class)
class PipelineFeatureSpecContractValidatorTest {

    @Test
    fun acceptsMatchingPipelineFeatureSpecIdAndSerialName() {
        val result = compilePipelineFeature(
            """
            @KrigPipelineFeatureSpec(id = "sample.feature")
            @SerialName("sample.feature")
            class SampleFeature {
                companion object {
                    const val ID: String = "sample.feature"
                }
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun rejectsMissingSerialName() {
        val result = compilePipelineFeature(
            """
            @KrigPipelineFeatureSpec(id = "sample.feature")
            class SampleFeature {
                companion object {
                    const val ID: String = "sample.feature"
                }
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "must also be annotated with @SerialName(\"sample.feature\")")
    }

    @Test
    fun rejectsMismatchedSerialName() {
        val result = compilePipelineFeature(
            """
            @KrigPipelineFeatureSpec(id = "sample.feature")
            @SerialName("wrong.feature")
            class SampleFeature {
                companion object {
                    const val ID: String = "sample.feature"
                }
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "id=\"sample.feature\" must equal @SerialName=\"wrong.feature\"")
    }

    @Test
    fun rejectsNonConstCompanionId() {
        val result = compilePipelineFeature(
            """
            @KrigPipelineFeatureSpec(id = "sample.feature")
            @SerialName("sample.feature")
            class SampleFeature {
                companion object {
                    val ID: String = "sample.feature"
                }
            }
            """.trimIndent(),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "companion ID must be declared exactly as `const val ID: String = \"sample.feature\"`")
    }

    private fun compilePipelineFeature(featureBody: String): com.tschuchort.compiletesting.JvmCompilationResult =
        compileWithKrigKsp(
            SourceFile.kotlin(
                "KrigPipelineFeatureSpec.kt",
                """

                    package space.kscience.krig.api.annotations

                    @Target(AnnotationTarget.CLASS)
                    annotation class KrigPipelineFeatureSpec(val id: String)
                    """.trimIndent(),
            ),
            SourceFile.kotlin(
                "SerialName.kt",
                """

                    package kotlinx.serialization

                    @Target(AnnotationTarget.CLASS)
                    annotation class SerialName(val value: String)
                    """.trimIndent(),
            ),
            SourceFile.kotlin(
                "SampleFeature.kt",
                """

                    package sample

                    import kotlinx.serialization.SerialName
                    import space.kscience.krig.api.annotations.KrigPipelineFeatureSpec

                    $featureBody
                    """.trimIndent(),
            ),
            generatedModule = "feature_contract_validator_test",
            inheritClassPath = false,
        )
}
