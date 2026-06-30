package space.kscience.krig.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
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
 * Manual Gradle smoke:
 * ```
 * ./gradlew :krig-model:krigKspIncrementalReport "-Pksp.incremental=true" "-Pksp.incremental.log=true"
 * cat krig-model/build/reports/krig/ksp-incremental-report.txt
 * ```
 * The report records the generator baseline and the KSP dirty-set logs for the
 * actual `krig-mpp-ksp` common/JVM split. Use it for the four boundary scenarios:
 * non-annotated source, `@Serializable` subclass, `@Contributes` object, and
 * unrelated source changes.
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
private fun run(vararg extra: SourceFile) =
    compileWithKrigKsp(
        SourceFile.kotlin("KotlinxSerialStubs.kt", KOTLINX_SERIALIZATION_STUBS),
        SourceFile.kotlin("ModuleStubs.kt", SERIALIZERS_MODULE_STUBS),
        SourceFile.kotlin("PolymorphicBaseStub.kt", POLYMORPHIC_BASE_STUB),
        SourceFile.kotlin("ExtensionPoint.kt", EXTENSION_POINT),
        SourceFile.kotlin("Use.kt", USE),
        *extra,
        generatedModule = "regression_test",
        inheritClassPath = false,
    )

private fun subclass(name: String, serialName: String) = """
    package sample
    import kotlinx.serialization.SerialName
    import kotlinx.serialization.Serializable
    import sample.api.ExtensionPoint
    @Serializable @SerialName("$serialName") data class $name(val id: String) : ExtensionPoint
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
