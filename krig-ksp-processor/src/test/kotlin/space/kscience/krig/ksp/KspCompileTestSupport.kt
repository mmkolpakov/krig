package space.kscience.krig.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.useKsp2
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

@OptIn(ExperimentalCompilerApi::class)
internal fun compileWithKrigKsp(
    vararg sources: SourceFile,
    generatedModule: String,
    generatedLayer: String? = null,
    inheritClassPath: Boolean = true,
    withCompilation: Boolean = true,
): JvmCompilationResult =
    KotlinCompilation().apply {
        this.sources = sources.toList()
        this.inheritClassPath = inheritClassPath
        configureKsp {
            processorOptions["krig.generated.module"] = generatedModule
            if (generatedLayer != null) {
                processorOptions["krig.generated.layer"] = generatedLayer
            }
            this.withCompilation = withCompilation
            symbolProcessorProviders += KrigSymbolProcessorProvider()
        }
    }.also { it.useKsp2() }.compile()

internal val KOTLINX_SERIALIZATION_STUBS: String = """
    package kotlinx.serialization
    @Target(AnnotationTarget.CLASS) annotation class Serializable
    @Target(AnnotationTarget.CLASS) annotation class SerialName(val value: String)
    @Target(AnnotationTarget.CLASS) annotation class Polymorphic
""".trimIndent()

internal val SERIALIZERS_MODULE_STUBS: String = """
    package kotlinx.serialization.modules
    import kotlin.reflect.KClass
    class SerializersModule
    class SerializersModuleBuilder { fun include(module: SerializersModule) {} }
    class PolymorphicModuleBuilder<T : Any>
    fun SerializersModule(block: SerializersModuleBuilder.() -> Unit): SerializersModule =
        SerializersModule().also { SerializersModuleBuilder().block() }
    fun <T : Any> SerializersModuleBuilder.polymorphic(
        baseClass: KClass<T>, block: PolymorphicModuleBuilder<T>.() -> Unit,
    ) { PolymorphicModuleBuilder<T>().block() }
    fun <T : Any, S : T> PolymorphicModuleBuilder<T>.subclass(subclass: KClass<S>) {}
""".trimIndent()

internal val POLYMORPHIC_BASE_STUB: String = """
    package space.kscience.krig.api.annotations
    @Target(AnnotationTarget.CLASS) annotation class PolymorphicBase
""".trimIndent()
