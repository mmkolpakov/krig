package space.kscience.krig.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.useKsp2
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

@OptIn(ExperimentalCompilerApi::class)
class ContributesAggregatorValidationTest {

    @Test
    fun acceptsWellTypedContributors() {
        val result = compileContributors(
            SourceFile.kotlin(
                "ValidContributors.kt",
                """
                package sample

                import space.kscience.dataforge.context.Context
                import space.kscience.dataforge.meta.MetaConverter
                import space.kscience.dataforge.names.Name
                import space.kscience.dataforge.names.parseAsName
                import space.kscience.krig.api.factory.DeviceFactory
                import space.kscience.krig.api.features.PipelineFeatureSpec
                import space.kscience.krig.api.utils.unit
                import space.kscience.krig.assembly.ContributesFactory
                import space.kscience.krig.assembly.ContributesManifest
                import space.kscience.krig.assembly.ContributesPipelineFeature
                import space.kscience.krig.core.contracts.Device
                import space.kscience.krig.core.contracts.DeviceManifest
                import space.kscience.krig.core.contracts.manifestOf
                import space.kscience.krig.core.features.PipelineFeature
                import space.kscience.krig.core.pipeline.PipelineBuilder
                import kotlin.reflect.KClass

                @ContributesManifest("demo")
                object DemoManifestFactory {
                    operator fun invoke(): DeviceManifest = manifestOf(
                        id = "demo".parseAsName(),
                        properties = emptyMap(),
                    )
                }

                @ContributesFactory
                object DemoFactory : DeviceFactory<Device, Unit>(
                    id = "demo.factory".parseAsName(),
                    configConverter = MetaConverter.unit,
                ) {
                    override fun create(context: Context, config: Unit): Device {
                        check(context.toString().isNotEmpty() || config.toString().isNotEmpty())
                        error("The validation test checks contribution shape; it never creates a device.")
                    }
                }

                @ContributesPipelineFeature
                object DemoFeature : PipelineFeature<Unit, PipelineFeatureSpec> {
                    override val id: Name = "demo.feature".parseAsName()
                    override val specClass: KClass<PipelineFeatureSpec> = PipelineFeatureSpec::class
                    override fun createConfig(): Unit = Unit
                    override fun install(config: Unit, pipeline: PipelineBuilder) {
                        check(config.toString().isNotEmpty() || pipeline.toString().isNotEmpty())
                    }
                }

                val demoManifestSmoke: DeviceManifest = DemoManifestFactory()
                val demoFactorySmoke: DeviceFactory<Device, Unit> = DemoFactory
                val demoFeatureSmoke: PipelineFeature<Unit, PipelineFeatureSpec> = DemoFeature

                val demoContributorSmoke: Boolean = run {
                    check(demoManifestSmoke.id == "demo".parseAsName())
                    check(demoFactorySmoke.id == "demo.factory".parseAsName())
                    check(demoFeatureSmoke.id == "demo.feature".parseAsName())
                    true
                }
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "UseGenerated.kt",
                """
                package sample

                import space.kscience.krig.generated.contributes_validation_test.MergedFactoriesPlugin
                import space.kscience.krig.generated.contributes_validation_test.MergedManifestsPlugin
                import space.kscience.krig.generated.contributes_validation_test.MergedPipelineFeaturesPlugin

                val manifests = MergedManifestsPlugin.entries
                val factories = MergedFactoriesPlugin.entries
                val features = MergedPipelineFeaturesPlugin.entries

                val generatedContributionSmoke: Boolean = run {
                    check(manifests.isNotEmpty())
                    check(factories.isNotEmpty())
                    check(features.isNotEmpty())
                    true
                }
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun rejectsManifestContributorWithoutDeviceManifestInvoke() {
        val result = compileContributors(
            SourceFile.kotlin(
                "BadManifest.kt",
                """
                package sample
                import space.kscience.krig.assembly.ContributesManifest

                @ContributesManifest("bad")
                object BadManifestFactory

                val badManifestSmoke: Boolean = BadManifestFactory.toString().isNotEmpty()
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "must declare `operator fun invoke(): DeviceManifest`")
    }

    @Test
    fun rejectsWrongDeviceFactoryContributorType() {
        val result = compileContributors(
            SourceFile.kotlin(
                "BadFactory.kt",
                """
                package sample
                import space.kscience.krig.assembly.ContributesFactory

                @ContributesFactory
                object BadFactory

                val badFactorySmoke: Boolean = BadFactory.toString().isNotEmpty()
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "does not implement/extend space.kscience.krig.api.factory.DeviceFactory")
    }

    @Test
    fun rejectsWrongPipelineFeatureContributorType() {
        val result = compileContributors(
            SourceFile.kotlin(
                "BadFeature.kt",
                """
                package sample
                import space.kscience.krig.assembly.ContributesPipelineFeature

                @ContributesPipelineFeature
                object BadFeature

                val badFeatureSmoke: Boolean = BadFeature.toString().isNotEmpty()
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "does not implement/extend space.kscience.krig.core.features.PipelineFeature")
    }
}

@OptIn(ExperimentalCompilerApi::class)
private fun compileContributors(vararg extra: SourceFile): com.tschuchort.compiletesting.JvmCompilationResult =
    KotlinCompilation().apply {
        sources = extra.toList()
        inheritClassPath = true
        configureKsp {
            processorOptions["krig.generated.module"] = "contributes_validation_test"
            processorOptions["krig.generated.layer"] = "jvmAggregation"
            withCompilation = true
            symbolProcessorProviders += KrigSymbolProcessorProvider()
        }
    }.also { it.useKsp2() }.compile()
