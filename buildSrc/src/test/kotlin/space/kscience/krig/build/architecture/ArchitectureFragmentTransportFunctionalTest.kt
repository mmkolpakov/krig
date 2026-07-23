package space.kscience.krig.build.architecture

import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

class ArchitectureFragmentTransportFunctionalTest {
    @Test
    fun transportsTaskOwnedFragmentsWithIsolatedProjects() {
        val project = Files.createTempDirectory("architecture-fragment-transport")
        project.resolve("settings.gradle").writeText(
            "rootProject.name = 'architecture-fragment-transport'\ninclude 'one', 'two'\n",
        )
        project.resolve("build.gradle").writeText(
            """
            import org.gradle.api.attributes.Category
            import org.gradle.api.attributes.DocsType

            plugins { id 'base' }

            def fragmentDependencies = configurations.dependencyScope('fragmentDependencies')
            def fragments = configurations.resolvable('fragments') {
                extendsFrom(fragmentDependencies.get())
                transitive = false
                attributes {
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, Category.DOCUMENTATION))
                    attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType, 'krig-architecture-module-v1'))
                }
            }
            dependencies {
                add(fragmentDependencies.name, project(':one'))
                add(fragmentDependencies.name, project(':two'))
            }
            tasks.register('collectArchitectureFragments', Zip) {
                from(fragments)
                archiveFileName = 'fragments.zip'
                destinationDirectory = layout.buildDirectory.dir('collected')
                duplicatesStrategy = DuplicatesStrategy.INCLUDE
            }
            """.trimIndent(),
        )
        project.resolve("one/build.gradle").apply {
            parent.createDirectories()
            writeText(
                """
                plugins {
                    id 'krig-architecture-module'
                    id 'org.jetbrains.kotlin.multiplatform'
                }
                kotlin {
                    jvm()
                    sourceSets.commonMain.dependencies {
                        api(project(':two'))
                    }
                }
                """.trimIndent(),
            )
        }
        project.resolve("two/build.gradle").apply {
            parent.createDirectories()
            writeText(
                """
                plugins {
                    id 'krig-architecture-module'
                    id 'org.jetbrains.kotlin.multiplatform'
                }
                kotlin { jvm() }
                """.trimIndent(),
            )
        }

        val first = runner(project).build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":one:generateArchitectureFragment")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":two:generateArchitectureFragment")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(":collectArchitectureFragments")?.outcome)

        val unchanged = runner(project).build()
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":one:generateArchitectureFragment")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":two:generateArchitectureFragment")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":collectArchitectureFragments")?.outcome)
        assertTrue(unchanged.output.contains("Reusing configuration cache."), unchanged.output)

        val archive = project.resolve("build/collected/fragments.zip").toFile()
        val entries = ZipFile(archive).use { zip ->
            zip.entries().asSequence().map { entry ->
                entry.name to zip.getInputStream(entry).bufferedReader().use { it.readText() }
            }.toList()
        }
        assertEquals(listOf("dependencies.tsv", "dependencies.tsv"), entries.map(Pair<String, String>::first).sorted())
        assertTrue(
            entries.any { (_, content) ->
                "module\tone" in content && "one\ttwo\tcommonMain\tapi" in content
            },
            entries.toString(),
        )
        assertTrue(entries.any { (_, content) -> "module\ttwo" in content }, entries.toString())
    }

    private fun runner(project: java.nio.file.Path): GradleRunner = GradleRunner.create()
        .withProjectDir(project.toFile())
        .withArguments(
            "collectArchitectureFragments",
            "--configuration-cache",
            "--configuration-cache-problems=fail",
            "-Dorg.gradle.unsafe.isolated-projects=true",
            "--stacktrace",
        )
        .withPluginClasspath()
}
