package space.kscience.krig.ksp

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContributesAggregatorIncrementalFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    @Test
    fun addChangeRemoveAndLastRemovalKeepAggregateExact() {
        writeFixture()

        val baseline = build()
        assertEquals(TaskOutcome.SUCCESS, baseline.task(":kspKotlin")?.outcome, baseline.output)
        val baselinePlugin = generatedPlugin()
        assertPlugin(baselinePlugin, present = listOf("to StableContribution"))
        assertPlugin(generatedPlugin("MergedHandlersPlugin.kt"), present = listOf("to StableHandler"))
        val baselineHash = sha256(baselinePlugin)
        val baselineSerializerIndex = generatedSerializerIndex()
        assertSerializerOutputs(present = listOf("StableFeature"))
        val baselineSerializerHash = sha256(baselineSerializerIndex)
        assertBinaryPresent("MergedProtocolsPlugin", "MergedHandlersPlugin", "StableFeature")

        val dynamicSource = source("fixture/DynamicContributions.kt")
        writeDynamicContribution(dynamicSource, "Alpha")
        val added = build()
        assertEquals(TaskOutcome.SUCCESS, added.task(":kspKotlin")?.outcome, added.output)
        assertPlugin(
            generatedPlugin(),
            present = listOf("to StableContribution", "to DynamicContributions.Alpha"),
            absent = listOf("to DynamicContributions.Beta"),
        )
        assertSerializerOutputs(present = listOf("StableFeature", "AlphaFeature"), absent = listOf("BetaFeature"))
        assertIncrementalEvidence("DynamicContributions.kt", "AlphaFeature_")

        writeDynamicContribution(dynamicSource, "Beta")
        val changed = build()
        assertEquals(TaskOutcome.SUCCESS, changed.task(":kspKotlin")?.outcome, changed.output)
        assertPlugin(
            generatedPlugin(),
            present = listOf("to StableContribution", "to DynamicContributions.Beta"),
            absent = listOf("to DynamicContributions.Alpha"),
        )
        assertSerializerOutputs(present = listOf("StableFeature", "BetaFeature"), absent = listOf("AlphaFeature"))
        assertBinaryAbsent("AlphaFeature", "DynamicContributions\$Alpha")
        assertIncrementalEvidence("DynamicContributions.kt", "BetaFeature_")

        dynamicSource.deleteExisting()
        val removed = build()
        assertEquals(TaskOutcome.SUCCESS, removed.task(":kspKotlin")?.outcome, removed.output)
        val restoredPlugin = generatedPlugin()
        assertPlugin(
            restoredPlugin,
            present = listOf("to StableContribution"),
            absent = listOf("to DynamicContributions.Alpha", "to DynamicContributions.Beta"),
        )
        assertEquals(baselineHash, sha256(restoredPlugin))
        assertSerializerOutputs(
            present = listOf("StableFeature"),
            absent = listOf("AlphaFeature", "BetaFeature"),
        )
        assertEquals(baselineSerializerHash, sha256(generatedSerializerIndex()))
        assertBinaryAbsent("AlphaFeature", "BetaFeature", "DynamicContributions")

        val unchanged = build()
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":kspKotlin")?.outcome, unchanged.output)
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":compileKotlin")?.outcome, unchanged.output)
        assertContains(unchanged.output, "Reusing configuration cache.")
        assertEquals(sha256(restoredPlugin), sha256(generatedPlugin()))
        assertEquals(baselineSerializerHash, sha256(generatedSerializerIndex()))

        writeProtocolAnnotations(handlerId = "handler-v2", handlerGeneratedName = "RenamedHandlers")
        val targetRenamed = build()
        assertEquals(TaskOutcome.SUCCESS, targetRenamed.task(":kspKotlin")?.outcome, targetRenamed.output)
        assertTrue(generatedPlugins("MergedHandlersPlugin.kt").isEmpty(), "Renamed target output must not remain stale.")
        assertPlugin(
            generatedPlugin("MergedRenamedHandlersPlugin.kt"),
            present = listOf("const val TARGET: String = \"handler-v2\"", "to StableHandler"),
        )
        assertBinaryAbsent("MergedHandlersPlugin")
        assertBinaryPresent("MergedRenamedHandlersPlugin")

        source("fixture/StableContribution.kt").deleteExisting()
        val protocolRemoved = build()
        assertEquals(TaskOutcome.SUCCESS, protocolRemoved.task(":kspKotlin")?.outcome, protocolRemoved.output)
        assertTrue(generatedPlugins("MergedProtocolsPlugin.kt").isEmpty(), "Removed target must not remain stale.")
        assertPlugin(generatedPlugin("MergedRenamedHandlersPlugin.kt"), present = listOf("to StableHandler"))

        source("fixture/StableHandler.kt").deleteExisting()
        val lastRemoved = build()
        assertEquals(TaskOutcome.SUCCESS, lastRemoved.task(":kspKotlin")?.outcome, lastRemoved.output)
        assertTrue(generatedPlugins().isEmpty(), "The last target aggregate must be removed, not left stale.")
        assertBinaryAbsent("MergedProtocolsPlugin", "MergedHandlersPlugin", "MergedRenamedHandlersPlugin")
        assertSerializerOutputs(present = listOf("StableFeature"))

        source("fixture/StableFeature.kt").deleteExisting()
        val lastSerializerRemoved = build()
        assertEquals(TaskOutcome.SUCCESS, lastSerializerRemoved.task(":kspKotlin")?.outcome, lastSerializerRemoved.output)
        assertTrue(generatedSerializerFiles().isEmpty(), "The last serializer aggregate must not remain stale.")
        assertBinaryAbsent("GeneratedKrigSerializersModule", "StableFeature", "_Contributor")
    }

    private fun writeFixture() {
        val processorJar = Path.of(requireNotNull(System.getProperty("krig.test.processor.jar")))
        require(Files.isRegularFile(processorJar)) { "Processor jar does not exist: $processorJar" }
        val kotlinPoetJar = Path.of(com.squareup.kotlinpoet.FileSpec::class.java.protectionDomain.codeSource.location.toURI())
        require(Files.isRegularFile(kotlinPoetJar)) { "KotlinPoet jar does not exist: $kotlinPoetJar" }
        val kotlinVersion = requireNotNull(System.getProperty("krig.test.kotlin.version"))
        val kspVersion = requireNotNull(System.getProperty("krig.test.ksp.version"))

        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            rootProject.name = "contributes-incremental-fixture"
            """.trimIndent(),
        )
        projectDir.resolve("gradle.properties").writeText(
            """
            org.gradle.configuration-cache=true
            org.gradle.caching=false
            ksp.incremental=true
            ksp.incremental.log=true
            """.trimIndent(),
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "$kotlinVersion"
                id("com.google.devtools.ksp") version "$kspVersion"
            }

            repositories { mavenCentral() }

            dependencies {
                ksp(files(
                    "${processorJar.invariantSeparatorsPathString}",
                    "${kotlinPoetJar.invariantSeparatorsPathString}",
                ))
            }

            kotlin { jvmToolchain(21) }

            ksp {
                arg("krig.generated.module", "contributes_incremental_fixture")
                arg("krig.generated.layer", "all")
            }
            """.trimIndent(),
        )

        writeSource(
            "space/kscience/krig/api/annotations/Contributes.kt",
            """
            package space.kscience.krig.api.annotations

            import kotlin.reflect.KClass

            enum class EmissionStrategy { DIRECT, INVOKE_AS_FACTORY }

            @Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
            @Retention(AnnotationRetention.BINARY)
            annotation class Contributes(
                val anchor: KClass<*>,
                val strategy: EmissionStrategy = EmissionStrategy.DIRECT,
            )
            """.trimIndent(),
        )
        writeSource(
            "space/kscience/krig/api/annotations/PolymorphicBase.kt",
            """
            package space.kscience.krig.api.annotations

            @Target(AnnotationTarget.CLASS)
            @Retention(AnnotationRetention.BINARY)
            annotation class PolymorphicBase
            """.trimIndent(),
        )
        writeSource(
            "space/kscience/krig/api/discovery/TargetId.kt",
            """
            package space.kscience.krig.api.discovery

            @Target(AnnotationTarget.CLASS)
            @Retention(AnnotationRetention.BINARY)
            annotation class TargetId(val value: String, val generatedName: String)
            """.trimIndent(),
        )
        writeSource(
            "kotlinx/serialization/Serializable.kt",
            """
            package kotlinx.serialization

            import kotlin.reflect.KClass

            interface KSerializer<T>

            @Target(AnnotationTarget.CLASS)
            @Retention(AnnotationRetention.BINARY)
            annotation class Serializable(
                val with: KClass<out KSerializer<*>> = KSerializer::class,
            )
            """.trimIndent(),
        )
        writeSource(
            "kotlinx/serialization/modules/SerializersModule.kt",
            """
            package kotlinx.serialization.modules

            import kotlin.reflect.KClass

            open class SerializersModule

            class SerializersModuleBuilder {
                fun include(module: SerializersModule) = Unit
            }

            class PolymorphicModuleBuilder<T : Any>

            fun SerializersModule(block: SerializersModuleBuilder.() -> Unit): SerializersModule =
                SerializersModule().also { SerializersModuleBuilder().block() }

            fun <T : Any> SerializersModuleBuilder.polymorphic(
                baseClass: KClass<T>,
                block: PolymorphicModuleBuilder<T>.() -> Unit,
            ) = PolymorphicModuleBuilder<T>().block()

            fun <T : Any, S : T> PolymorphicModuleBuilder<T>.subclass(subclass: KClass<S>) = Unit
            """.trimIndent(),
        )
        writeProtocolAnnotations(handlerId = "handler", handlerGeneratedName = "Handlers")
        writeSource(
            "fixture/StableFeature.kt",
            """
            package fixture

            import kotlinx.serialization.Serializable
            import space.kscience.krig.api.annotations.PolymorphicBase

            @PolymorphicBase
            interface FixtureFeature

            @Serializable
            class StableFeature : FixtureFeature
            """.trimIndent(),
        )
        writeSource(
            "fixture/StableContribution.kt",
            """
            package fixture

            @ProtocolContribution
            object StableContribution
            """.trimIndent(),
        )
        writeSource(
            "fixture/StableHandler.kt",
            """
            package fixture

            @ContributesHandler
            object StableHandler
            """.trimIndent(),
        )
        writeSource(
            "space/kscience/dataforge/meta/Meta.kt",
            """
            package space.kscience.dataforge.meta

            class Meta {
                companion object { val EMPTY: Meta = Meta() }
            }
            """.trimIndent(),
        )
        writeSource(
            "space/kscience/dataforge/names/Name.kt",
            """
            package space.kscience.dataforge.names

            @JvmInline
            value class Name(val value: String)

            fun String.parseAsName(): Name = Name(this)
            """.trimIndent(),
        )
        writeSource(
            "space/kscience/dataforge/context/Context.kt",
            """
            package space.kscience.dataforge.context

            import space.kscience.dataforge.meta.Meta
            import space.kscience.dataforge.names.Name

            interface Context

            data class PluginTag(val name: String, val group: String) {
                companion object { const val DATAFORGE_GROUP: String = "dataforge" }
            }

            interface PluginFactory<T> {
                val tag: PluginTag
                fun build(context: Context, meta: Meta): T
            }

            abstract class AbstractPlugin(protected val meta: Meta) {
                abstract val tag: PluginTag
                open fun content(target: String): Map<Name, Any> = emptyMap()
            }
            """.trimIndent(),
        )
    }

    private fun writeProtocolAnnotations(handlerId: String, handlerGeneratedName: String) {
        writeSource(
            "fixture/ProtocolAnnotations.kt",
            """
            package fixture

            import space.kscience.krig.api.annotations.Contributes
            import space.kscience.krig.api.discovery.TargetId

            @TargetId("protocol", generatedName = "Protocols")
            object ProtocolContributions

            @TargetId("$handlerId", generatedName = "$handlerGeneratedName")
            object HandlerContributions

            @Target(AnnotationTarget.CLASS)
            @Retention(AnnotationRetention.BINARY)
            @Contributes(ProtocolContributions::class)
            annotation class ContributesProtocol

            typealias ProtocolContribution = ContributesProtocol

            @Target(AnnotationTarget.CLASS)
            @Retention(AnnotationRetention.BINARY)
            @Contributes(HandlerContributions::class)
            annotation class ContributesHandler
            """.trimIndent(),
        )
    }

    private fun writeDynamicContribution(path: Path, name: String) {
        path.writeText(
            """
            package fixture

            import kotlinx.serialization.Serializable

            object DynamicContributions {
                @ContributesProtocol
                object $name
            }

            @Serializable
            class ${name}Feature : FixtureFeature
            """.trimIndent(),
        )
    }

    private fun build(): BuildResult = GradleRunner.create()
        .withProjectDir(projectDir.toFile())
        .withArguments(
            "jar",
            "--configuration-cache",
            "--configuration-cache-problems=fail",
            "--no-build-cache",
            "--stacktrace",
            "--console=plain",
        )
        .build()

    private fun source(relativePath: String): Path =
        projectDir.resolve("src/main/kotlin").resolve(relativePath).also { it.parent.createDirectories() }

    private fun writeSource(relativePath: String, content: String) {
        source(relativePath).writeText(content)
    }

    private fun generatedPlugins(fileName: String? = null): List<Path> {
        val root = projectDir.resolve("build/generated/ksp")
        if (!Files.exists(root)) return emptyList()
        return Files.walk(root).use { paths ->
            paths.filter { path ->
                Files.isRegularFile(path) &&
                    (fileName?.let { path.name == it } ?: (path.name.startsWith("Merged") && path.name.endsWith("Plugin.kt")))
            }.toList()
        }
    }

    private fun generatedPlugin(fileName: String = "MergedProtocolsPlugin.kt"): Path = generatedPlugins(fileName).single()

    private fun generatedSerializerFiles(): List<Path> {
        val root = projectDir.resolve("build/generated/ksp")
        if (!Files.exists(root)) return emptyList()
        return Files.walk(root).use { paths ->
            paths.filter { path ->
                Files.isRegularFile(path) &&
                    (path.name == "GeneratedKrigSerializersModule.kt" || path.name.endsWith("_Contributor.kt"))
            }.toList()
        }
    }

    private fun generatedSerializerIndex(): Path =
        generatedSerializerFiles().single { it.name == "GeneratedKrigSerializersModule.kt" }

    private fun assertSerializerOutputs(present: List<String>, absent: List<String> = emptyList()) {
        val text = generatedSerializerFiles().joinToString("\n") { it.readText() }
        for (expected in present) assertContains(text, expected)
        for (unexpected in absent) assertFalse(unexpected in text, "Unexpected stale serializer $unexpected in:\n$text")
    }

    private fun assertPlugin(path: Path, present: List<String>, absent: List<String> = emptyList()) {
        val text = path.readText()
        for (expected in present) assertContains(text, expected)
        for (unexpected in absent) assertFalse(unexpected in text, "Unexpected stale entry $unexpected in:\n$text")
    }

    private fun assertIncrementalEvidence(sourceName: String, outputMarker: String) {
        val dirtyLog = kspLog("kspDirtySet.log").readText()
        assertContains(dirtyLog, sourceName)
        val dirtyRatio = requireNotNull(Regex("""Dirty / All: ([0-9.]+)%""").find(dirtyLog)) {
            "KSP dirty ratio is absent from:\n$dirtyLog"
        }.groupValues[1].toDouble()
        assertTrue(dirtyRatio < 100.0, "Expected a partial KSP dirty set, got $dirtyRatio%:\n$dirtyLog")

        val outputsLog = kspLog("kspSourceToOutputs.log").readText()
        assertContains(outputsLog, sourceName)
        assertContains(outputsLog, outputMarker)
    }

    private fun kspLog(fileName: String): Path = Files.walk(projectDir.resolve("build/kspCaches")).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.name == fileName }.toList().single()
    }

    private fun assertBinaryPresent(vararg fragments: String) {
        val binaries = compiledAndJarEntries()
        for (fragment in fragments) {
            assertTrue(binaries.any { fragment in it }, "Expected binary containing '$fragment' in:\n$binaries")
        }
    }

    private fun assertBinaryAbsent(vararg fragments: String) {
        val binaries = compiledAndJarEntries()
        for (fragment in fragments) {
            assertFalse(binaries.any { fragment in it }, "Unexpected stale binary containing '$fragment' in:\n$binaries")
        }
    }

    private fun compiledAndJarEntries(): List<String> {
        val classRoot = projectDir.resolve("build/classes/kotlin/main")
        val classes = if (Files.exists(classRoot)) {
            Files.walk(classRoot).use { paths ->
                paths.filter(Files::isRegularFile).map(classRoot::relativize).map(Path::toString).toList()
            }
        } else {
            emptyList()
        }
        val jar = Files.list(projectDir.resolve("build/libs")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.name.endsWith(".jar") }.toList().single()
        }
        val entries = JarFile(jar.toFile()).use { file -> file.entries().asSequence().map { it.name }.toList() }
        return classes + entries
    }

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path))
        .joinToString("") { byte -> "%02x".format(byte) }
}
