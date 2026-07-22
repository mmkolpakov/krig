package space.kscience.krig.ksp

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

@OptIn(ExperimentalCompilerApi::class)
class ContributesAggregatorRoundTest {

    @Test
    fun includesContributionGeneratedInALaterRound() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin(
                "UseGeneratedContribution.kt",
                """
                package sample

                import space.kscience.krig.generated.contributes_round_test.MergedProtocolsPlugin

                val generatedEntries = MergedProtocolsPlugin.entries
                """.trimIndent(),
            ),
            generatedModule = "contributes_round_test",
            generatedLayer = "jvmAggregation",
            extraSymbolProcessorProviders = listOf(LateContributionProvider()),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun rejectsDuplicateManifestIdGeneratedInALaterRound() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin(
                "BaselineManifest.kt",
                """
                package sample

                import space.kscience.dataforge.names.parseAsName
                import space.kscience.krig.assembly.ContributesManifest
                import space.kscience.krig.core.contracts.DeviceManifest
                import space.kscience.krig.core.contracts.manifestOf

                @ContributesManifest("duplicate")
                object BaselineManifest {
                    operator fun invoke(): DeviceManifest = manifestOf(
                        id = "baseline".parseAsName(),
                        properties = emptyMap(),
                    )
                }
                """.trimIndent(),
            ),
            generatedModule = "contributes_late_duplicate_test",
            generatedLayer = "jvmAggregation",
            extraSymbolProcessorProviders = listOf(SecondRoundDuplicateManifestProvider()),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "Duplicate contribution keys on target 'krig.manifest': duplicate")
    }
}

private class LateContributionProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
        private var emitted = false

        override fun process(resolver: Resolver): List<KSAnnotated> {
            if (emitted) return emptyList()
            emitted = true
            environment.codeGenerator.createNewFile(
                dependencies = Dependencies(aggregating = false),
                packageName = "sample.generated",
                fileName = "GeneratedProtocolContribution",
            ).bufferedWriter().use { writer ->
                writer.write(
                    """
                    package sample.generated

                    import space.kscience.krig.assembly.ContributesProtocol

                    @ContributesProtocol
                    object GeneratedProtocolContribution
                    """.trimIndent(),
                )
            }
            return emptyList()
        }
    }
}

private class SecondRoundDuplicateManifestProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
        private var invocation = 0

        override fun process(resolver: Resolver): List<KSAnnotated> {
            invocation++
            when (invocation) {
                1 -> generate("RoundMarker", "package sample.generated\nclass RoundMarker")
                2 -> generate(
                    "LateManifest",
                    """
                    package sample.generated

                    import space.kscience.dataforge.names.parseAsName
                    import space.kscience.krig.assembly.ContributesManifest
                    import space.kscience.krig.core.contracts.DeviceManifest
                    import space.kscience.krig.core.contracts.manifestOf

                    @ContributesManifest("duplicate")
                    object LateManifest {
                        operator fun invoke(): DeviceManifest = manifestOf(
                            id = "late".parseAsName(),
                            properties = emptyMap(),
                        )
                    }
                    """.trimIndent(),
                )
            }
            return emptyList()
        }

        private fun generate(fileName: String, source: String) {
            environment.codeGenerator.createNewFile(
                dependencies = Dependencies(aggregating = false),
                packageName = "sample.generated",
                fileName = fileName,
            ).bufferedWriter().use { it.write(source) }
        }
    }
}
