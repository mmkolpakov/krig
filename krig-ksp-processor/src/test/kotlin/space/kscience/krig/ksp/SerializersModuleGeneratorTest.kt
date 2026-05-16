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
                    "MemberTag.kt",
                    """
                    package space.kscience.krig.api.meta

                    import kotlinx.serialization.Polymorphic
                    import space.kscience.krig.api.annotations.PolymorphicBase

                    @Polymorphic
                    @PolymorphicBase
                    interface MemberTag
                    """.trimIndent(),
                ),
                SourceFile.kotlin(
                    "IntegrationTag.kt",
                    """
                    package sample

                    import kotlinx.serialization.SerialName
                    import kotlinx.serialization.Serializable
                    import space.kscience.krig.api.meta.MemberTag

                    @Serializable
                    @SerialName("tag.integration")
                    data class IntegrationTag(val id: String) : MemberTag
                    """.trimIndent(),
                ),
                SourceFile.kotlin(
                    "UseGenerated.kt",
                    """
                    package sample

                    import space.kscience.krig.generated.member_tag_test.generatedKrigSerializersModule

                    val module = generatedKrigSerializersModule
                    """.trimIndent(),
                ),
            )
            inheritClassPath = false
            configureKsp {
                processorOptions["krig.generated.module"] = "member_tag_test"
                withCompilation = true
                symbolProcessorProviders += KrigSymbolProcessorProvider()
            }
        }

        compilation.useKsp2()
        val result = compilation.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }
}
