@file:Suppress("UnusedSymbol", "UnusedReceiverParameter")

package space.kscience.krig.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.useKsp2
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the serializers registry processor compiles correctly with
 * multiple `@PolymorphicBase` subclasses across separate files.
 *
 * ## Incremental build sanity (manual, Gradle only)
 * On the Kotlin 2.4 / KSP 2.x stack the generated registry is split into isolating
 * per-subclass contributors and one aggregating index. The index records all source
 * files that contributed registrations in `Dependencies(aggregating = true, ...)`.
 *
 * Manual Gradle test:
 * ```
 * ./gradlew clean :module:compileKotlinJvm -Pksp.incremental=true
 * cat build/kotlin/kspJvmKotlin/kspDirtySet.log
 * ./gradlew :module:compileKotlinJvm -Pksp.incremental=true
 * cat build/kotlin/kspJvmKotlin/kspDirtySetByDeps.log
 * ```
 * Keep this manual check around until the compile-testing harness can exercise a
 * real multi-invocation incremental Gradle build.
 */
@OptIn(ExperimentalCompilerApi::class)
class KspIncrementalRegressionTest {

    @Test
    fun singleSubclassCompiles() {
        val r = run(SourceFile.kotlin("ExtensionA.kt", subclass("ExtensionA", "extension.a")))
        assertEquals(KotlinCompilation.ExitCode.OK, r.exitCode, r.messages)
    }

    @Test
    fun twoSubclassesCompile() {
        val r = run(
            SourceFile.kotlin("ExtensionA.kt", subclass("ExtensionA", "extension.a")),
            SourceFile.kotlin("ExtensionB.kt", subclass("ExtensionB", "extension.b")),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, r.exitCode, r.messages)
    }

    @Test
    fun threeSubclassesCompile() {
        val r = run(
            SourceFile.kotlin("ExtensionA.kt", subclass("ExtensionA", "extension.a")),
            SourceFile.kotlin("ExtensionB.kt", subclass("ExtensionB", "extension.b")),
            SourceFile.kotlin("ExtensionC.kt", subclass("ExtensionC", "extension.c")),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, r.exitCode, r.messages)
    }
}

@OptIn(ExperimentalCompilerApi::class)
private fun run(vararg extra: SourceFile) = KotlinCompilation().apply {
    sources = listOf(
        SourceFile.kotlin("KotlinxSerialStubs.kt", KOTLINX_SERIALIZATION_STUBS),
        SourceFile.kotlin("ModuleStubs.kt", MODULE_STUBS),
        SourceFile.kotlin("PolymorphicBaseStub.kt", POLYMORPHIC_BASE_STUB),
        SourceFile.kotlin("ExtensionPoint.kt", EXTENSION_POINT),
        SourceFile.kotlin("Use.kt", USE),
    ) + extra.toList()
    inheritClassPath = false
    configureKsp {
        processorOptions["krig.generated.module"] = "regression_test"
        withCompilation = true
        symbolProcessorProviders += KrigSymbolProcessorProvider()
    }
}.also { it.useKsp2() }.compile()

private fun subclass(name: String, serialName: String) = """
    package sample
    import kotlinx.serialization.SerialName
    import kotlinx.serialization.Serializable
    import sample.api.ExtensionPoint
    @Serializable @SerialName("$serialName") data class $name(val id: String) : ExtensionPoint
""".trimIndent()

private val KOTLINX_SERIALIZATION_STUBS = """
    package kotlinx.serialization
    @Target(AnnotationTarget.CLASS) annotation class Serializable
    @Target(AnnotationTarget.CLASS) annotation class SerialName(val value: String)
    @Target(AnnotationTarget.CLASS) annotation class Polymorphic
""".trimIndent()

private val MODULE_STUBS = """
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

private val POLYMORPHIC_BASE_STUB = """
    package space.kscience.krig.api.annotations
    @Target(AnnotationTarget.CLASS) annotation class PolymorphicBase
""".trimIndent()

private val EXTENSION_POINT = """
    package sample.api
    import kotlinx.serialization.Polymorphic
    import space.kscience.krig.api.annotations.PolymorphicBase
    @Polymorphic @PolymorphicBase interface ExtensionPoint
""".trimIndent()

private val USE = """
    package sample
    import space.kscience.krig.generated.regression_test.generatedKrigSerializersModule
    val module = generatedKrigSerializersModule
""".trimIndent()
