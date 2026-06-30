@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Applies the krig KSP processor to a multiplatform module.
 *
 * Common/metadata KSP emits common-safe generated API, while the JVM pass emits
 * DataForge aggregation glue. Both use a group+artifact-derived namespace so two
 * modules that happen to share a Gradle project name (e.g. `:utils`) never
 * collide at runtime.
 */

plugins {
    id("com.google.devtools.ksp")
}

plugins.withId("org.jetbrains.kotlin.multiplatform") {
    dependencies {
        add("kspCommonMainMetadata", project(":krig-ksp-processor"))
        add("kspJvm", project(":krig-ksp-processor"))
    }

    extensions.configure<KotlinMultiplatformExtension>("kotlin") {
        sourceSets.named("commonMain") {
            generatedKotlin.srcDir(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
        }
    }

    tasks.matching { task ->
        task.name.startsWith("compile") && task.name.contains("Kotlin")
    }.configureEach {
        dependsOn("kspCommonMainKotlinMetadata")
    }

    tasks.matching { task ->
        task.name.startsWith("ksp") && task.name != "kspCommonMainKotlinMetadata"
    }.configureEach {
        dependsOn("kspCommonMainKotlinMetadata")
    }

    tasks.matching { task ->
        task.name == "detekt"
    }.configureEach {
        dependsOn("kspCommonMainKotlinMetadata")
    }

    tasks.register("krigKspIncrementalReport") {
        group = "verification"
        description = "Collect KRig KSP dirty-set logs into a stable report for incremental-boundary review."
        dependsOn("kspCommonMainKotlinMetadata", "kspKotlinJvm")
        outputs.upToDateWhen { false }

        doLast {
            val reportFile = layout.buildDirectory.file("reports/krig/ksp-incremental-report.txt").get().asFile
            val buildRoot = layout.buildDirectory.get().asFile
            val logNames = setOf(
                "kspDirtySet.log",
                "kspDirtySetByDeps.log",
                "kspDirtySetByOutputs.log",
                "kspSourceToOutputs.log",
            )
            val logFiles = buildRoot
                .walkTopDown()
                .filter { file -> file.isFile && file.name in logNames }
                .sortedBy { file -> file.relativeTo(buildRoot).invariantSeparatorsPath }
                .toList()

            reportFile.parentFile.mkdirs()
            reportFile.writeText(
                buildString {
                    appendLine("KRig KSP incremental boundary report")
                    appendLine("Project: ${project.path}")
                    appendLine()
                    appendLine("Run with: ./gradlew ${project.path}:krigKspIncrementalReport \"-Pksp.incremental=true\" \"-Pksp.incremental.log=true\"")
                    appendLine()
                    appendLine("Generator baseline:")
                    appendLine("- DeviceContractGenerator: isolating common contract artifacts.")
                    appendLine("- SerializersModuleGenerator: isolating per-subclass contributors plus one aggregating serializers index.")
                    appendLine("- ContributesAggregator: JVM aggregation plugin output per target id.")
                    appendLine()
                    appendLine("Manual scenario matrix:")
                    appendLine("- non-annotated source changed: should not expand KRig generated outputs.")
                    appendLine("- one @Serializable subclass changed: subclass contributor plus serializers index may be dirty.")
                    appendLine("- one @Contributes object changed: target JVM aggregation plugin may be dirty.")
                    appendLine("- unrelated file changed: inspect whether aggregating outputs are the only KRig-generated dirtiness.")
                    appendLine()
                    if (logFiles.isEmpty()) {
                        appendLine("No KSP incremental logs found. Re-run with \"-Pksp.incremental=true\" \"-Pksp.incremental.log=true\" after a clean build and one incremental rebuild.")
                    } else {
                        appendLine("Collected logs:")
                        for (file in logFiles) {
                            appendLine()
                            appendLine("## ${file.relativeTo(buildRoot).invariantSeparatorsPath}")
                            val lines = file.readLines()
                            val excerpt = lines.take(200)
                            for (line in excerpt) appendLine(line)
                            if (lines.size > excerpt.size) {
                                appendLine("... truncated ${lines.size - excerpt.size} lines ...")
                            }
                        }
                    }
                },
            )
            logger.lifecycle("KRig KSP incremental report written to ${reportFile.relativeTo(project.rootDir)}")
        }
    }
}

ksp {
    val groupSlug = project.group.toString()
        .ifBlank { project.rootProject.name }
        .lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter(String::isNotBlank)
        .joinToString(".")
        .ifBlank { "anon" }
    val nameSlug = project.name
        .removePrefix("krig-")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")
        .ifBlank { "module" }
    arg("krig.generated.module", "$groupSlug.$nameSlug")
    arg("krig.generated.layer", "auto")
}
