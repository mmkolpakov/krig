@file:Suppress("UnusedSymbol", "UnusedReceiverParameter")

package space.kscience.krig.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.useKsp2
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCompilerApi::class)
class SerializersModuleGeneratorTest {

    @Test
    fun generatesModuleForOpenPolymorphicBaseSubclass() {
        val compilation = KotlinCompilation().apply {
            sources = listOf(
                SourceFile.kotlin(
                    "SerializationAnnotations.kt",
                    """

                    package kotlinx.serialization

                    @Target(AnnotationTarget.CLASS)
                    annotation class Serializable

                    @Target(AnnotationTarget.CLASS)
                    annotation class SerialName(val value: String)

                    @Target(AnnotationTarget.CLASS)
                    annotation class Polymorphic
                    """.trimIndent(),
                ),
                SourceFile.kotlin(
                    "SerializersModule.kt",
                    """

                    package kotlinx.serialization.modules

                    import kotlin.reflect.KClass

                    class SerializersModule

                    class SerializersModuleBuilder {
                        fun include(module: SerializersModule) {}
                    }

                    class PolymorphicModuleBuilder<T : Any>

                    fun SerializersModule(block: SerializersModuleBuilder.() -> Unit): SerializersModule =
                        SerializersModule().also { SerializersModuleBuilder().block() }

                    fun <T : Any> SerializersModuleBuilder.polymorphic(
                        baseClass: KClass<T>,
                        block: PolymorphicModuleBuilder<T>.() -> Unit,
                    ) {
                        PolymorphicModuleBuilder<T>().block()
                    }

                    fun <T : Any, S : T> PolymorphicModuleBuilder<T>.subclass(subclass: KClass<S>) {}
                    """.trimIndent(),
                ),
                SourceFile.kotlin(
                    "PolymorphicBase.kt",
                    """

                    package space.kscience.krig.api.annotations

                    @Target(AnnotationTarget.CLASS)
                    annotation class PolymorphicBase
                    """.trimIndent(),
                ),
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
            )
            inheritClassPath = false
            configureKsp {
                processorOptions["krig.generated.module"] = "extension_test"
                withCompilation = true
                symbolProcessorProviders += KrigSymbolProcessorProvider()
            }
        }

        compilation.useKsp2()
        val result = compilation.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }
}
