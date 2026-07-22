package space.kscience.krig.ksp

import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.useKsp2
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.File

@OptIn(ExperimentalCompilerApi::class)
internal fun compileWithKrigKsp(
    vararg sources: SourceFile,
    generatedModule: String,
    generatedLayer: String? = null,
    extraProcessorOptions: Map<String, String> = emptyMap(),
    extraSymbolProcessorProviders: List<SymbolProcessorProvider> = emptyList(),
    inheritClassPath: Boolean = true,
    classpaths: List<File> = emptyList(),
    withCompilation: Boolean = true,
): JvmCompilationResult =
    KotlinCompilation().apply {
        this.sources = sources.toList()
        this.inheritClassPath = inheritClassPath
        this.classpaths = classpaths
        configureKsp {
            processorOptions["krig.generated.module"] = generatedModule
            if (generatedLayer != null) {
                processorOptions["krig.generated.layer"] = generatedLayer
            }
            processorOptions.putAll(extraProcessorOptions)
            this.withCompilation = withCompilation
            symbolProcessorProviders += KrigSymbolProcessorProvider()
            symbolProcessorProviders += extraSymbolProcessorProviders
        }
    }.also { it.useKsp2() }.compile()

internal val KOTLINX_SERIALIZATION_STUBS: String = """
    package kotlinx.serialization
    import kotlin.reflect.KClass
    interface KSerializer<T>
    @Target(AnnotationTarget.CLASS)
    annotation class Serializable(
        val with: KClass<out KSerializer<*>> = KSerializer::class,
    )
    @Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY) annotation class SerialName(val value: String)
    @Target(AnnotationTarget.PROPERTY) annotation class Transient
    @Target(AnnotationTarget.CLASS) annotation class Polymorphic
    @Target(AnnotationTarget.CLASS) annotation class KeepGeneratedSerializer
    @Target(AnnotationTarget.ANNOTATION_CLASS) annotation class MetaSerializable
""".trimIndent()

internal val KOTLINX_SERIALIZATION_JSON_STUBS: String = """
    package kotlinx.serialization.json
    @Target(AnnotationTarget.CLASS)
    annotation class JsonClassDiscriminator(val discriminator: String)
    @Target(AnnotationTarget.PROPERTY)
    annotation class JsonNames(vararg val names: String)
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
    @Target(AnnotationTarget.CLASS)
    @Retention(AnnotationRetention.BINARY)
    annotation class PolymorphicBase
""".trimIndent()
