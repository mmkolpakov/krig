@file:Suppress("UnstableApiUsage")

package space.kscience.krig.build.bom

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin

public class KrigBomCompletenessPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val generatePom: TaskProvider<GenerateMavenPom> = project.tasks.named(
            GENERATE_BOM_POM_TASK_NAME,
            GenerateMavenPom::class.java,
        )
        val checkBomCompleteness = project.tasks.register(
            "checkBomCompleteness",
            CheckBomCompletenessTask::class.java,
        ) {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Checks that the generated BOM POM exactly manages the canonical public root surface."
            modulePolicyFile.set(project.layout.settingsDirectory.file("config/architecture/modules.tsv"))
            publicationSurfaceFile.set(project.layout.settingsDirectory.file("config/publication-surface.tsv"))
            pomFile.set(project.layout.file(generatePom.map { it.destination }))
            expectedVersion.set(project.rootProject.version.toString())
            jsonReportFile.set(project.layout.buildDirectory.file("reports/bom-completeness/report.json"))
            markdownReportFile.set(project.layout.buildDirectory.file("reports/bom-completeness/report.md"))
            dependsOn(generatePom)
        }

        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME).configure {
            dependsOn(checkBomCompleteness)
        }
    }

    private companion object {
        const val GENERATE_BOM_POM_TASK_NAME: String = "generatePomFileForBomPublication"
    }
}
