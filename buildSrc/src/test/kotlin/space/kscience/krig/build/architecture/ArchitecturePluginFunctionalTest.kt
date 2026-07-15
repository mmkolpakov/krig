package space.kscience.krig.build.architecture

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

class ArchitecturePluginFunctionalTest {
    @Test
    fun tracksArbitrarilyNamedProductionSourceSetAndItsMutations() {
        val project = Files.createTempDirectory("architecture-functional-test")
        project.resolve("settings.gradle").writeText(
            "rootProject.name = 'architecture-fixture'\ninclude 'sample', 'demo'\n",
        )
        project.resolve("build.gradle").writeText("plugins { id 'krig-architecture' }\n")
        project.resolve("demo/build.gradle").apply {
            parent.createDirectories()
            writeText("")
        }
        project.resolve("sample/build.gradle").apply {
            parent.createDirectories()
            writeText(
                """
                plugins { id 'org.jetbrains.kotlin.multiplatform' }

                def generatedOutput = layout.buildDirectory.dir('generated/contracts')
                def generateContracts = tasks.register('generateContracts') {
                    outputs.dir(generatedOutput)
                    doLast {
                        def output = generatedOutput.get().file('sample/Generated.kt').asFile
                        output.parentFile.mkdirs()
                        output.text = 'package sample.generated\\n\\nclass Generated\\n'
                    }
                }
                def compileAttachedOutput = layout.buildDirectory.dir('generated/compile-attached')
                def compileAttachedPackage = providers.gradleProperty('compileAttachedPackage')
                    .orElse('sample.compileattached')
                def generateCompileAttached = tasks.register('generateCompileAttached') {
                    inputs.property('packageName', compileAttachedPackage)
                    outputs.dir(compileAttachedOutput)
                    doLast {
                        def output = compileAttachedOutput.get()
                            .file('sample/compileattached/CompileAttached.kt').asFile
                        output.parentFile.mkdirs()
                        output.text = 'package ' + compileAttachedPackage.get() + '\\n\\nclass CompileAttached\\n'
                    }
                }
                def generatedJavaOutput = layout.buildDirectory.dir('generated/java')
                def generateJava = tasks.register('generateJava') {
                    outputs.dir(generatedJavaOutput)
                    doLast {
                        def output = generatedJavaOutput.get().file('sample/GeneratedJava.java').asFile
                        output.parentFile.mkdirs()
                        output.text = 'package sample; public final class GeneratedJava {}\n'
                    }
                }

                kotlin {
                    jvm('desktop')
                    iosArm64()
                    iosSimulatorArm64()
                    applyDefaultHierarchyTemplate()
                    def shared = sourceSets.create('shared')
                    sourceSets.desktopMain.dependsOn(shared)
                    sourceSets.shared.generatedKotlin.srcDir(generateContracts)
                }

                tasks.matching { it.name == 'compileCommonMainKotlinMetadata' }.configureEach {
                    source(project.files(compileAttachedOutput).builtBy(generateCompileAttached))
                }

                sourceSets.named('desktopMain') {
                    java.srcDir(layout.projectDirectory.dir('custom-java'))
                    java.srcDir(rootProject.layout.projectDirectory.dir('demo/custom-java'))
                    if (providers.gradleProperty('withGeneratedJava').isPresent()) {
                        java.srcDir(generateJava)
                    }
                }
                """.trimIndent(),
            )
        }
        val policy = project.resolve("config/architecture").apply { createDirectories() }
        policy.resolve("modules.tsv").writeText(
            "module\tkind\tlayer\n" +
                "demo\texample\t-\n" +
                "sample\tlibrary\tL0\n",
        )
        policy.resolve("edges.tsv").writeText("consumer\tdependency\n")
        policy.resolve("packages.tsv").writeText(
            "package\towner\tcontributors\n" +
                "sample.compileattached\tsample\tsample\n" +
                "sample.generated\tsample\tsample\n" +
                "sample.ios\tsample\tsample\n" +
                "sample.shared\tsample\tsample\n",
        )
        project.resolve("sample/src/iosMain/kotlin/sample/Ios.kt").apply {
            parent.createDirectories()
            writeText("package sample.ios\n\nclass Ios\n")
        }
        val source = project.resolve("sample/src/shared/kotlin/sample/Shared.kt").apply {
            parent.createDirectories()
            writeText("package sample.shared\n\nclass Shared\n")
        }
        project.resolve("sample/src/commonTest/kotlin/sample/TestOnly.kt").apply {
            parent.createDirectories()
            writeText("package sample.testonly\n\nclass TestOnly\n")
        }

        val first = runner(project).build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":checkArchitecture")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":sample:generateContracts")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":sample:generateCompileAttached")?.outcome)
        assertTrue(first.tasks.none { it.path.startsWith(":sample:compile") }, first.output)
        val firstReport = project.resolve("build/reports/architecture/report.json").toFile().readText()
        assertTrue(
            firstReport.contains(
                "{\"package\": \"sample.compileattached\", \"owner\": \"sample\", " +
                    "\"contributors\": [\"sample\"]}",
            ),
            firstReport,
        )
        assertTrue(!firstReport.contains("sample.testonly"), firstReport)

        val unchanged = runner(project).build()
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":checkArchitecture")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":sample:generateCompileAttached")?.outcome)
        assertTrue(unchanged.tasks.none { it.path.startsWith(":sample:compile") }, unchanged.output)
        assertTrue(unchanged.output.contains("Reusing configuration cache."), unchanged.output)

        val changedGenerated = runner(project, "-PcompileAttachedPackage=sample.compilechanged").buildAndFail()
        assertEquals(TaskOutcome.SUCCESS, changedGenerated.task(":sample:generateCompileAttached")?.outcome)
        assertTrue(changedGenerated.tasks.none { it.path.startsWith(":sample:compile") }, changedGenerated.output)
        assertTrue(
            changedGenerated.output.contains("Stale package policies: sample.compileattached"),
            changedGenerated.output,
        )
        assertTrue(
            changedGenerated.output.contains("Unclassified production packages: sample.compilechanged"),
            changedGenerated.output,
        )

        val script = project.resolve("sample/src/shared/kotlin/sample/Script.KTS").apply {
            writeText("package sample.script\n\nval scriptValue = 1\n")
        }
        val scriptSource = runner(project).buildAndFail()
        assertTrue(
            scriptSource.output.contains("Unclassified production packages: sample.script"),
            scriptSource.output,
        )
        Files.delete(script)

        source.writeText("package sample.changed\n\nclass Shared\n")
        val changed = runner(project).buildAndFail()
        assertTrue(changed.output.contains("Stale package policies: sample.shared"), changed.output)
        assertTrue(changed.output.contains("Unclassified production packages: sample.changed"), changed.output)

        source.writeText("package sample.shared\n\nclass Shared\n")
        val targetJava = project.resolve("sample/src/desktopMain/java/sample/JavaThing.java").apply {
            parent.createDirectories()
            writeText("package sample; public final class JavaThing {}\n")
        }
        val targetJavaSource = runner(project).buildAndFail()
        assertTrue(
            targetJavaSource.output.contains("Production Java package checking is not implemented"),
            targetJavaSource.output,
        )
        Files.delete(targetJava)

        val customJava = project.resolve("sample/custom-java/sample/CustomJava.java").apply {
            parent.createDirectories()
            writeText("package sample; public final class CustomJava {}\n")
        }
        val customJavaSource = runner(project).buildAndFail()
        assertTrue(
            customJavaSource.output.contains("Production Java package checking is not implemented"),
            customJavaSource.output,
        )
        Files.delete(customJava)

        val externalJava = project.resolve("demo/custom-java/sample/ExternalJava.java").apply {
            parent.createDirectories()
            writeText("package sample; public final class ExternalJava {}\n")
        }
        val externalJavaSource = runner(project).buildAndFail()
        assertTrue(
            externalJavaSource.output.contains("Production Java package checking is not implemented"),
            externalJavaSource.output,
        )
        Files.delete(externalJava)

        val generatedJavaSource = runner(project, "-PwithGeneratedJava").buildAndFail()
        assertEquals(TaskOutcome.SUCCESS, generatedJavaSource.task(":sample:generateJava")?.outcome)
        assertTrue(
            generatedJavaSource.output.contains("Production Java package checking is not implemented"),
            generatedJavaSource.output,
        )
    }

    @Test
    fun tracksKotlinJvmMainCompilation() {
        val project = Files.createTempDirectory("architecture-jvm-functional-test")
        project.resolve("settings.gradle").writeText(
            "rootProject.name = 'architecture-jvm-fixture'\ninclude 'sample'\n",
        )
        project.resolve("build.gradle").writeText("plugins { id 'krig-architecture' }\n")
        project.resolve("sample/build.gradle").apply {
            parent.createDirectories()
            writeText("plugins { id 'org.jetbrains.kotlin.jvm' }\n")
        }
        val policy = project.resolve("config/architecture").apply { createDirectories() }
        policy.resolve("modules.tsv").writeText("module\tkind\tlayer\nsample\tlibrary\tL0\n")
        policy.resolve("edges.tsv").writeText("consumer\tdependency\n")
        policy.resolve("packages.tsv").writeText(
            "package\towner\tcontributors\nsample.jvm\tsample\tsample\n",
        )
        project.resolve("sample/src/main/kotlin/sample/Jvm.kt").apply {
            parent.createDirectories()
            writeText("package sample.jvm\n\nclass Jvm\n")
        }

        val result = runner(project).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":checkArchitecture")?.outcome)
    }

    @Test
    fun tracksSplitPackageContributorChangesAcrossModules() {
        val project = Files.createTempDirectory("architecture-split-functional-test")
        project.resolve("settings.gradle").writeText(
            "rootProject.name = 'architecture-split-fixture'\ninclude 'alpha', 'beta', 'gamma'\n",
        )
        project.resolve("build.gradle").writeText("plugins { id 'krig-architecture' }\n")
        listOf("alpha", "beta", "gamma").forEach { module ->
            project.resolve("$module/build.gradle").apply {
                parent.createDirectories()
                writeText("plugins { id 'org.jetbrains.kotlin.jvm' }\n")
            }
        }
        val policy = project.resolve("config/architecture").apply { createDirectories() }
        policy.resolve("modules.tsv").writeText(
            "module\tkind\tlayer\n" +
                "alpha\tlibrary\tL0\n" +
                "beta\tlibrary\tL0\n" +
                "gamma\tlibrary\tL0\n",
        )
        policy.resolve("edges.tsv").writeText("consumer\tdependency\n")
        policy.resolve("packages.tsv").writeText(
            "package\towner\tcontributors\n" +
                "sample.shared\talpha\talpha,beta\n",
        )
        project.resolve("alpha/src/main/kotlin/sample/Alpha.kt").apply {
            parent.createDirectories()
            writeText("package sample.shared\n\nclass Alpha\n")
        }
        val beta = project.resolve("beta/src/main/kotlin/sample/Beta.kt").apply {
            parent.createDirectories()
            writeText("package sample.shared\n\nclass Beta\n")
        }

        val initial = runner(project).build()
        assertEquals(TaskOutcome.SUCCESS, initial.task(":checkArchitecture")?.outcome)

        Files.delete(beta)
        val missing = runner(project).buildAndFail()
        assertTrue(
            missing.output.contains("Package 'sample.shared' contributors changed; missing: beta"),
            missing.output,
        )

        beta.writeText("package sample.shared\n\nclass Beta\n")
        project.resolve("gamma/src/main/kotlin/sample/Gamma.kt").apply {
            parent.createDirectories()
            writeText("package sample.shared\n\nclass Gamma\n")
        }
        val added = runner(project).buildAndFail()
        assertTrue(
            added.output.contains("Package 'sample.shared' contributors changed; added: gamma"),
            added.output,
        )
    }

    private fun runner(project: java.nio.file.Path, vararg additionalArguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(project.toFile())
        .withArguments(
            listOf(
                "checkArchitecture",
                "--configuration-cache",
                "--configuration-cache-problems=fail",
                "--stacktrace",
            ) + additionalArguments,
        )
        .withPluginClasspath()
}
