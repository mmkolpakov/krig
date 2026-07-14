package space.kscience.krig.build.bom

import java.nio.charset.StandardCharsets
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import space.kscience.krig.build.publication.PublicationSurfaceLoader

@CacheableTask
public abstract class CheckBomCompletenessTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val modulePolicyFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val publicationSurfaceFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val pomFile: RegularFileProperty

    @get:Input
    public abstract val expectedVersion: Property<String>

    @get:OutputFile
    public abstract val jsonReportFile: RegularFileProperty

    @get:OutputFile
    public abstract val markdownReportFile: RegularFileProperty

    @TaskAction
    public fun checkBomCompleteness() {
        val surface = PublicationSurfaceLoader.load(
            publicationSurfaceFile.get().asFile,
            modulePolicyFile.get().asFile,
        )
        val policy = BomPolicy(
            expectedBomCoordinate = surface.bom.coordinate,
            expectedVersion = expectedVersion.get(),
            expectedPublications = surface.libraries.map { entry ->
                BomExpectedPublication(entry.coordinate, entry.kind)
            },
        )
        val parsed = runCatching { MavenBomParser.parse(pomFile.get().asFile) }
        val snapshot = parsed.getOrElse {
            BomSnapshot(
                identity = BomIdentity(
                    modelVersion = "UNPARSED",
                    group = "UNPARSED",
                    artifact = "UNPARSED",
                    version = "UNPARSED",
                    packaging = "UNPARSED",
                ),
                constraints = emptyList(),
            )
        }
        val verification = if (parsed.isSuccess) {
            BomCompletenessVerifier.verify(policy, snapshot)
        } else {
            BomVerification(
                listOf("[$BOM_PARSE_ERROR_CODE] Generated BOM POM is not valid secure Maven XML."),
            )
        }
        val report = BomCompletenessReportRenderer.render(policy, snapshot, verification)
        val jsonFile = jsonReportFile.get().asFile
        val markdownFile = markdownReportFile.get().asFile
        jsonFile.parentFile?.mkdirs()
        markdownFile.parentFile?.mkdirs()
        jsonFile.writeText(report.json, StandardCharsets.UTF_8)
        markdownFile.writeText(report.markdown, StandardCharsets.UTF_8)

        if (!verification.isSuccessful) {
            throw GradleException(
                buildString {
                    appendLine("BOM completeness check failed:")
                    verification.errors.forEach { appendLine(" - $it") }
                    append("Reports: $jsonFile, $markdownFile")
                },
                parsed.exceptionOrNull(),
            )
        }
    }
}
