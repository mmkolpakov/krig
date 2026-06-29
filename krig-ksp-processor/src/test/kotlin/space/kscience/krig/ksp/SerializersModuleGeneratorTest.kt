package space.kscience.krig.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCompilerApi::class)
class SerializersModuleGeneratorTest {

    @Test
    fun generatesModuleForOpenPolymorphicBaseSubclass() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin("SerializationAnnotations.kt", KOTLINX_SERIALIZATION_STUBS),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
            SourceFile.kotlin(
                "ExtensionPoint.kt",
                """

                    package sample.api

                    import kotlinx.serialization.Polymorphic
                    import space.kscience.krig.api.annotations.PolymorphicBase

                    @Polymorphic
                    @PolymorphicBase
                    interface ExtensionPoint
                    """.trimIndent(),
            ),
            SourceFile.kotlin(
                "IntegrationExtension.kt",
                """

                    package sample

                    import kotlinx.serialization.SerialName
                    import kotlinx.serialization.Serializable
                    import sample.api.ExtensionPoint

                    @Serializable
                    @SerialName("extension.integration")
                    data class IntegrationExtension(val id: String) : ExtensionPoint
                    """.trimIndent(),
            ),
            SourceFile.kotlin(
                "UseGenerated.kt",
                """

                    package sample

                    import space.kscience.krig.generated.extension_test.generatedKrigSerializersModule

                    val module = generatedKrigSerializersModule
                    """.trimIndent(),
            ),
            generatedModule = "extension_test",
            inheritClassPath = false,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }
}
