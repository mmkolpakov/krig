@file:Suppress("UnusedSymbol", "UnusedReceiverParameter")

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
                import space.kscience.dataforge.meta.Meta
                import space.kscience.dataforge.names.Name
                import space.kscience.krig.api.factory.DeviceFactory
                import space.kscience.krig.api.features.PipelineFeatureSpec
                import space.kscience.krig.assembly.ContributesFactory
                import space.kscience.krig.assembly.ContributesManifest
                import space.kscience.krig.assembly.ContributesPipelineFeature
                import space.kscience.krig.core.contracts.Device
                import space.kscience.krig.core.contracts.DeviceManifest
                import space.kscience.krig.core.features.PipelineFeature
                import space.kscience.krig.core.pipeline.PipelineBuilder

                object DemoDevice : Device

                class DemoManifest : DeviceManifest {
                    override val id: Name = Name("demo")
                }

                @ContributesManifest("demo")
                object DemoManifestFactory {
                    operator fun invoke(): DeviceManifest = DemoManifest()
                }

                @ContributesFactory
                object DemoFactory : DeviceFactory<Device, Unit>()

                @ContributesPipelineFeature
                object DemoFeature : PipelineFeature<Unit, PipelineFeatureSpec>
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
        sources = CONTRIBUTES_STUBS + extra.toList()
        inheritClassPath = false
        configureKsp {
            processorOptions["krig.generated.module"] = "contributes_validation_test"
            processorOptions["krig.generated.layer"] = "jvmAggregation"
            withCompilation = true
            symbolProcessorProviders += KrigSymbolProcessorProvider()
        }
    }.also { it.useKsp2() }.compile()

private val CONTRIBUTES_STUBS: List<SourceFile> = listOf(
    SourceFile.kotlin(
        "DataForgeContextStubs.kt",
        """
        package space.kscience.dataforge.context

        import space.kscience.dataforge.meta.Meta
        import space.kscience.dataforge.names.Name

        open class AbstractPlugin(val meta: Meta) {
            open val tag: PluginTag get() = PluginTag("", PluginTag.DATAFORGE_GROUP)
            open fun content(target: String): Map<Name, Any> = emptyMap()
        }
        class Context
        interface PluginFactory<T> {
            val tag: PluginTag
            fun build(context: Context, meta: Meta): T
        }
        class PluginTag(val id: String, val group: String) {
            companion object {
                const val DATAFORGE_GROUP: String = "dataforge"
            }
        }
        """.trimIndent(),
    ),
    SourceFile.kotlin(
        "DataForgeMetaStubs.kt",
        """

        package space.kscience.dataforge.meta
        class Meta {
            companion object {
                val EMPTY: Meta = Meta()
            }
        }
        """.trimIndent(),
    ),
    SourceFile.kotlin(
        "DataForgeNameStubs.kt",
        """

        package space.kscience.dataforge.names
        data class Name(val value: String)
        fun String.parseAsName(): Name = Name(this)
        """.trimIndent(),
    ),
    SourceFile.kotlin(
        "ContributesStubs.kt",
        """
        package space.kscience.krig.api.annotations

        import kotlin.reflect.KClass

        enum class EmissionStrategy { DIRECT, INVOKE_AS_FACTORY }

        @Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
        annotation class Contributes(
            val anchor: KClass<*>,
            val strategy: EmissionStrategy = EmissionStrategy.DIRECT,
        )
        """.trimIndent(),
    ),
    SourceFile.kotlin(
        "DiscoveryStubs.kt",
        """
        package space.kscience.krig.api.discovery

        @Target(AnnotationTarget.CLASS)
        annotation class TargetId(val value: String)

        @TargetId("krig.pipeline-feature")
        object PipelineFeatureContributions
        """.trimIndent(),
    ),
    SourceFile.kotlin(
        "ContractStubs.kt",
        """
        package space.kscience.krig.core.contracts

        import space.kscience.dataforge.names.Name

        interface Device
        interface DeviceManifest {
            val id: Name
        }
        """.trimIndent(),
    ),
    SourceFile.kotlin(
        "FactoryStubs.kt",
        """
        package space.kscience.krig.api.factory

        import space.kscience.krig.core.contracts.Device

        abstract class DeviceFactory<D : Device, C>
        """.trimIndent(),
    ),
    SourceFile.kotlin(
        "PipelineFeatureStubs.kt",
        """
        package space.kscience.krig.core.features

        import space.kscience.krig.api.features.PipelineFeatureSpec

        interface PipelineFeature<C : Any, F : PipelineFeatureSpec>
        """.trimIndent(),
    ),
    SourceFile.kotlin(
        "PipelineFeatureSpecStubs.kt",
        """

        package space.kscience.krig.api.features
        interface PipelineFeatureSpec
        """.trimIndent(),
    ),
    SourceFile.kotlin(
        "PipelineBuilderStubs.kt",
        """

        package space.kscience.krig.core.pipeline
        class PipelineBuilder
        """.trimIndent(),
    ),
    SourceFile.kotlin(
        "AssemblyStubs.kt",
        """
        package space.kscience.krig.assembly

        import space.kscience.krig.api.annotations.Contributes
        import space.kscience.krig.api.annotations.EmissionStrategy
        import space.kscience.krig.api.discovery.PipelineFeatureContributions
        import space.kscience.krig.api.discovery.TargetId

        class DeviceCatalog {
            @TargetId("krig.manifest")
            companion object
        }

        class DeviceFactoryPlugin {
            @TargetId("krig.factory")
            companion object
        }

        @Target(AnnotationTarget.CLASS)
        @Contributes(DeviceCatalog::class, strategy = EmissionStrategy.INVOKE_AS_FACTORY)
        annotation class ContributesManifest(val manifestId: String)

        @Target(AnnotationTarget.CLASS)
        @Contributes(DeviceFactoryPlugin::class)
        annotation class ContributesFactory

        @Target(AnnotationTarget.CLASS)
        @Contributes(PipelineFeatureContributions::class)
        annotation class ContributesPipelineFeature
        """.trimIndent(),
    ),
)
