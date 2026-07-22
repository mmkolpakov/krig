package space.kscience.krig.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @Test
    fun rejectsDuplicateEffectiveKeysAcrossPackages() {
        val result = compileContributors(
            SourceFile.kotlin(
                "FirstProtocol.kt",
                """
                package first
                import space.kscience.krig.assembly.ContributesProtocol

                @ContributesProtocol
                object Duplicate
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "SecondProtocol.kt",
                """
                package second
                import space.kscience.krig.assembly.ContributesProtocol

                @ContributesProtocol
                object Duplicate
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "Duplicate contribution keys on target 'krig.protocol': Duplicate")
    }

    @Test
    fun rejectsPrivateContributorBeforeGeneratingAnInaccessibleReference() {
        val result = compileContributors(
            SourceFile.kotlin(
                "PrivateProtocol.kt",
                """
                package sample
                import space.kscience.krig.assembly.ContributesProtocol

                @ContributesProtocol
                private object PrivateProtocol
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "cannot reference private/protected contributor sample.PrivateProtocol")
    }

    @Test
    fun usesExplicitStableNamesForDistinctCustomTargets() {
        val result = compileContributors(
            SourceFile.kotlin(
                "CustomTargets.kt",
                """
                package sample

                import space.kscience.krig.api.annotations.Contributes
                import space.kscience.krig.api.discovery.TargetId

                @TargetId("a.protocol", generatedName = "AProtocols")
                object ATarget

                @TargetId("b.protocol", generatedName = "BProtocols")
                object BTarget

                @Contributes(ATarget::class)
                object AContribution

                @Contributes(BTarget::class)
                object BContribution
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "UseCustomTargets.kt",
                """
                package sample

                import space.kscience.krig.generated.contributes_validation_test.MergedAProtocolsPlugin
                import space.kscience.krig.generated.contributes_validation_test.MergedBProtocolsPlugin

                val customTargetsAreDistinct: Boolean = run {
                    check(MergedAProtocolsPlugin.TARGET == "a.protocol")
                    check(MergedBProtocolsPlugin.TARGET == "b.protocol")
                    check(MergedAProtocolsPlugin.tag != MergedBProtocolsPlugin.tag)
                    check(MergedAProtocolsPlugin.entries.isNotEmpty())
                    check(MergedBProtocolsPlugin.entries.isNotEmpty())
                    true
                }
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun rejectsGeneratedNameCollisionInsteadOfOverwritingAPlugin() {
        val result = compileContributors(
            SourceFile.kotlin(
                "CollidingTargets.kt",
                """
                package sample

                import space.kscience.krig.api.annotations.Contributes
                import space.kscience.krig.api.discovery.TargetId

                @TargetId("a.protocol", generatedName = "Protocols")
                object ATarget

                @TargetId("b.protocol", generatedName = "Protocols")
                object BTarget

                @Contributes(ATarget::class)
                object AContribution

                @Contributes(BTarget::class)
                object BContribution
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(
            result.messages,
            "Duplicate TargetId.generatedName values: Protocols -> a.protocol, b.protocol",
        )
    }

    @Test
    fun rejectsConflictingNamesForTheSameWireTarget() {
        val result = compileContributors(
            SourceFile.kotlin(
                "ConflictingTarget.kt",
                """
                package sample

                import space.kscience.krig.api.annotations.Contributes
                import space.kscience.krig.api.discovery.TargetId

                @TargetId("same.protocol", generatedName = "FirstProtocols")
                object FirstTarget

                @TargetId("same.protocol", generatedName = "SecondProtocols")
                object SecondTarget

                @Contributes(FirstTarget::class)
                object FirstContribution

                @Contributes(SecondTarget::class)
                object SecondContribution
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(
            result.messages,
            "Target id 'same.protocol' declares conflicting generated names 'FirstProtocols' and 'SecondProtocols'",
        )
    }

    @Test
    fun rejectsNonCanonicalWireIdsAndInvalidGeneratedIdentifiers() {
        val result = compileContributors(
            SourceFile.kotlin(
                "InvalidTargets.kt",
                """
                package sample

                import space.kscience.krig.api.annotations.Contributes
                import space.kscience.krig.api.discovery.TargetId

                @TargetId("bad\"id", generatedName = "BadWire")
                object BadWireTarget

                @TargetId("valid.target", generatedName = "bad-name")
                object BadNameTarget

                @Contributes(BadWireTarget::class)
                object BadWireContribution

                @Contributes(BadNameTarget::class)
                object BadNameContribution
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "TargetId.value 'bad\"id' must be at most 128 characters")
        assertContains(result.messages, "TargetId.generatedName 'bad-name' must match")
    }

    @Test
    fun rejectsManifestIdsThatDataForgeWouldCanonicalizeDifferently() {
        val result = compileContributors(
            SourceFile.kotlin(
                "InvalidManifestIds.kt",
                """
                package sample

                import space.kscience.dataforge.names.parseAsName
                import space.kscience.krig.assembly.ContributesManifest
                import space.kscience.krig.core.contracts.DeviceManifest
                import space.kscience.krig.core.contracts.manifestOf

                @ContributesManifest("a\\b")
                object EscapedManifest {
                    operator fun invoke(): DeviceManifest = manifestOf(
                        id = "placeholder".parseAsName(),
                        properties = emptyMap(),
                    )
                }

                @ContributesManifest("a..b")
                object EmptySegmentManifest {
                    operator fun invoke(): DeviceManifest = manifestOf(
                        id = "placeholder".parseAsName(),
                        properties = emptyMap(),
                    )
                }
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "ContributesManifest.manifestId 'a\\b'")
        assertContains(result.messages, "ContributesManifest.manifestId 'a..b'")
        assertContains(result.messages, "[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)*")
    }

    @Test
    fun rejectsBacktickedContributorWhoseSimpleNameIsNotACanonicalKey() {
        val result = compileContributors(
            SourceFile.kotlin(
                "InvalidDefaultKey.kt",
                """
                package sample

                import space.kscience.krig.assembly.ContributesProtocol

                @ContributesProtocol
                object `a.b`
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "needs a canonical generated key")
        assertContains(result.messages, "[A-Za-z_][A-Za-z0-9_]*")
    }

    @Test
    fun keepsGenericInvokeAsFactoryOpenForNonManifestResults() {
        val result = compileContributors(
            SourceFile.kotlin(
                "CustomFactory.kt",
                """
                package sample

                import space.kscience.krig.api.annotations.Contributes
                import space.kscience.krig.api.annotations.EmissionStrategy
                import space.kscience.krig.api.discovery.TargetId

                @TargetId("sample.values", generatedName = "Values")
                object ValuesTarget

                @Contributes(ValuesTarget::class, strategy = EmissionStrategy.INVOKE_AS_FACTORY)
                object StringFactory {
                    operator fun invoke(): String = "factory-value"
                }
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "UseCustomFactory.kt",
                """
                package sample

                import space.kscience.krig.generated.contributes_validation_test.MergedValuesPlugin

                val customFactoryValue: Any? = MergedValuesPlugin.entries.values.single()
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val generated = result.classLoader.loadClass("sample.UseCustomFactoryKt")
            .getMethod("getCustomFactoryValue")
            .invoke(null)
        assertEquals("factory-value", generated)
    }

    @Test
    fun failsAtRuntimeWhenManifestFactoryBreaksItsDeclaredIdContract() {
        val result = compileContributors(
            SourceFile.kotlin(
                "MismatchedManifest.kt",
                """
                package sample

                import space.kscience.dataforge.names.parseAsName
                import space.kscience.krig.assembly.ContributesManifest
                import space.kscience.krig.core.contracts.DeviceManifest
                import space.kscience.krig.core.contracts.manifestOf

                @ContributesManifest("declared")
                object MismatchedManifestFactory {
                    operator fun invoke(): DeviceManifest = manifestOf(
                        id = "actual".parseAsName(),
                        properties = emptyMap(),
                    )
                }
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "RuntimeMismatchProbe.kt",
                """
                package sample

                import space.kscience.krig.generated.contributes_validation_test.MergedManifestsPlugin

                object RuntimeMismatchProbe {
                    val entries = MergedManifestsPlugin.entries
                }
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val failure = assertFailsWith<ExceptionInInitializerError> {
            Class.forName("sample.RuntimeMismatchProbe", true, result.classLoader)
        }
        assertContains(failure.cause?.message.orEmpty(), "produced a manifest with a different id")
    }
}

@OptIn(ExperimentalCompilerApi::class)
private fun compileContributors(vararg extra: SourceFile): com.tschuchort.compiletesting.JvmCompilationResult =
    compileWithKrigKsp(
        *extra,
        generatedModule = "contributes_validation_test",
        generatedLayer = "jvmAggregation",
    )
