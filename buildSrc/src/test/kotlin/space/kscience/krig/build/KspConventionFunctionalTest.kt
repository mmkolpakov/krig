package space.kscience.krig.build

import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

class KspConventionFunctionalTest {
    @Test
    fun wiresRenamedTargetAndArchivesCompileAttachedMetadataSources() {
        val project = Files.createTempDirectory("ksp-convention-functional-test")
        project.resolve("settings.gradle").writeText(
            "rootProject.name = 'ksp-convention-fixture'\n" +
                "include 'krig-ksp-processor', 'sample'\n",
        )
        project.resolve("build.gradle").writeText("allprojects { repositories { mavenCentral() } }\n")
        project.resolve("krig-ksp-processor/build.gradle").apply {
            parent.createDirectories()
            writeText(
                "plugins { id 'java-library' }\n" +
                    "dependencies { compileOnly 'com.google.devtools.ksp:symbol-processing-api:2.3.10' }\n",
            )
        }
        project.resolve("krig-ksp-processor/src/main/java/fixture/NoopProvider.java").apply {
            parent.createDirectories()
            writeText(
                """
                package fixture;

                import com.google.devtools.ksp.processing.Resolver;
                import com.google.devtools.ksp.processing.SymbolProcessor;
                import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
                import com.google.devtools.ksp.processing.SymbolProcessorProvider;
                import com.google.devtools.ksp.symbol.KSAnnotated;
                import java.util.Collections;
                import java.util.List;

                public final class NoopProvider implements SymbolProcessorProvider {
                    @Override
                    public SymbolProcessor create(SymbolProcessorEnvironment environment) {
                        System.out.println(
                            "KRIG_GENERATED_MODULE=" + environment.getOptions().get("krig.generated.module")
                        );
                        return new SymbolProcessor() {
                            @Override
                            public List<KSAnnotated> process(Resolver resolver) {
                                return Collections.emptyList();
                            }

                            @Override
                            public void finish() {}

                            @Override
                            public void onError() {}
                        };
                    }
                }
                """.trimIndent(),
            )
        }
        project.resolve(
            "krig-ksp-processor/src/main/resources/META-INF/services/" +
                "com.google.devtools.ksp.processing.SymbolProcessorProvider",
        ).apply {
            parent.createDirectories()
            writeText("fixture.NoopProvider\n")
        }
        project.resolve("sample/build.gradle").apply {
            parent.createDirectories()
            writeText(
                """
                plugins {
                    id 'org.jetbrains.kotlin.multiplatform'
                    id 'maven-publish'
                    id 'krig-mpp-ksp'
                }

                group = providers.gradleProperty('fixtureGroup').getOrElse('org.example.late')

                kotlin {
                    jvm('desktop')
                    js('jsIr', IR) {
                        browser()
                    }
                }

                def generatedOutput = layout.buildDirectory.dir('generated/compile-attached')
                def generateCompileAttached = tasks.register('generateCompileAttached') {
                    outputs.dir(generatedOutput)
                    doLast {
                        def output = generatedOutput.get().file('fixture/CompileAttached.kt').asFile
                        output.parentFile.mkdirs()
                        output.text = 'package fixture\n\nclass CompileAttached\n'
                    }
                }

                tasks.matching { it.name == 'compileCommonMainKotlinMetadata' }.configureEach {
                    source(project.files(generatedOutput).builtBy(generateCompileAttached))
                }

                def declaredOutput = layout.buildDirectory.dir('generated/declared-common')
                def generateDeclared = tasks.register('generateDeclared') {
                    outputs.dir(declaredOutput)
                    doLast {
                        def output = declaredOutput.get().file('fixture/Declared.kt').asFile
                        output.parentFile.mkdirs()
                        output.text = 'package fixture\n\nclass Declared\n'
                    }
                }
                kotlin.sourceSets.commonMain.kotlin.srcDir(
                    project.files(declaredOutput).builtBy(generateDeclared)
                )

                afterEvaluate {
                    ['kspCommonMainMetadata', 'kspDesktop', 'kspJs'].each { configurationName ->
                        def processorDependencies = configurations.getByName(configurationName).dependencies.toList()
                        assert processorDependencies.size() == 1
                        assert processorDependencies[0] instanceof org.gradle.api.artifacts.ProjectDependency
                        assert processorDependencies[0].path == ':krig-ksp-processor'
                    }
                    assert configurations.matching {
                        it.name.startsWith('ksp') && it.name.endsWith('Test')
                    }.every { it.dependencies.empty }
                }
                """.trimIndent(),
            )
        }
        project.resolve("sample/src/commonMain/kotlin/fixture/Source.kt").apply {
            parent.createDirectories()
            writeText("package fixture\n\nclass Source\n")
        }

        val first = runner(project).build()
        val expectedNamespace = generatedNamespace(
            group = "org.example.late",
            projectName = "sample",
            defaultGroup = ".sample",
        ).value
        assertTrue(first.output.contains("KRIG_GENERATED_MODULE=$expectedNamespace"), first.output)
        assertEquals(TaskOutcome.SUCCESS, first.task(":sample:generateCompileAttached")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":sample:generateDeclared")?.outcome)
        assertTrue(first.tasks.none { it.path.startsWith(":sample:compile") }, first.output)

        val sourcesJar = Files.walk(project.resolve("sample/build/libs")).use { paths ->
            paths.filter { path -> path.fileName.toString().endsWith("-sources.jar") }
                .findFirst()
                .orElse(null)
        }
        assertNotNull(sourcesJar)
        ZipFile(sourcesJar.toFile()).use { archive ->
            val entries = archive.entries().asSequence().map { it.name }.toList()
            assertEquals(entries.size, entries.distinct().size, entries.toString())
            assertEquals(1, entries.count { it == "commonMain/fixture/CompileAttached.kt" }, entries.toString())
            assertEquals(1, entries.count { it == "commonMain/fixture/Declared.kt" }, entries.toString())
        }

        val unchanged = runner(project).build()
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":sample:generateCompileAttached")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":sample:generateDeclared")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":sample:metadataSourcesJar")?.outcome)
        assertTrue(unchanged.tasks.none { it.path.startsWith(":sample:compile") }, unchanged.output)
        assertTrue(unchanged.output.contains("Reusing configuration cache."), unchanged.output)

        val renamedGroup = runner(project, "-PfixtureGroup=org.example.renamed").build()
        val renamedNamespace = generatedNamespace(
            group = "org.example.renamed",
            projectName = "sample",
            defaultGroup = ".sample",
        ).value
        assertEquals(TaskOutcome.SUCCESS, renamedGroup.task(":sample:kspCommonMainKotlinMetadata")?.outcome)
        assertTrue(renamedGroup.output.contains("KRIG_GENERATED_MODULE=$renamedNamespace"), renamedGroup.output)
    }

    private fun runner(project: java.nio.file.Path, vararg extraArguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(project.toFile())
        .withArguments(buildList {
            add(":sample:metadataSourcesJar")
            add("--configuration-cache")
            add("--configuration-cache-problems=fail")
            add("--stacktrace")
            addAll(extraArguments)
        })
        .withPluginClasspath()
}
