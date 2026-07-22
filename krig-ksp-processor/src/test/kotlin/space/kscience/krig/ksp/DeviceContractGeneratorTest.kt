package space.kscience.krig.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import space.kscience.krig.api.annotations.KrigDeviceContract
import space.kscience.krig.core.meta.DeviceContractBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCompilerApi::class)
class DeviceContractGeneratorTest {

    @Test
    fun generatorFqnsMatchRealApi() {
        assertEquals(
            KrigDeviceContract::class.qualifiedName,
            DeviceContractGenerator.KRIG_DEVICE_CONTRACT_FQN,
            "KrigDeviceContract moved/renamed - update DeviceContractGenerator.KRIG_DEVICE_CONTRACT_FQN.",
        )
        assertEquals(
            DeviceContractBuilder::class.qualifiedName,
            DeviceContractGenerator.DEVICE_CONTRACT_BUILDER_FQN,
            "DeviceContractBuilder moved/renamed - update DeviceContractGenerator.DEVICE_CONTRACT_BUILDER_FQN.",
        )
    }

    @Test
    fun generatesCommonRegistryForAnnotatedContractObject() {
        val generatedName = DeviceContractGenerator.generatedArtifactName("sample.MotorContract", "MotorContract")
        val result = compileDeviceContracts(
            SourceFile.kotlin(
                "MotorContract.kt",
                """
                package sample

                import space.kscience.dataforge.names.parseAsName
                import space.kscience.krig.api.annotations.KrigDeviceContract
                import space.kscience.krig.core.meta.DeviceContractBuilder
                import space.kscience.krig.core.meta.doubleProperty
                import space.kscience.krig.core.meta.mutableDoubleProperty
                import space.kscience.krig.generated.contract_test.$generatedName

                @KrigDeviceContract(id = "lab.motor", version = "2.0.0")
                object MotorContract : DeviceContractBuilder() {
                    val rpm by doubleProperty()
                    val target by mutableDoubleProperty()
                }

                val generatedRegistry = $generatedName.registry
                val generatedManifest = $generatedName.manifest()
                val generatedJsonSchema = $generatedName.jsonSchema()

                val generatedContractSmoke: Boolean = run {
                    check(generatedRegistry.id == "lab.motor".parseAsName())
                    check(generatedRegistry.version == "2.0.0")
                    check(generatedRegistry.propertiesByName.containsKey(MotorContract.rpm.name))
                    check(generatedRegistry.propertiesByName.containsKey(MotorContract.target.name))
                    check(generatedManifest.properties.containsKey(MotorContract.rpm.name))
                    check($generatedName.schemaHash.startsWith("fnv1a64:"))
                    check(generatedJsonSchema.containsKey("properties"))
                    true
                }
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun generatesFormSchemaHookWhenEnabled() {
        val generatedName = DeviceContractGenerator.generatedArtifactName("sample.FormContract", "FormContract")
        val result = compileDeviceContracts(
            SourceFile.kotlin(
                "FormContract.kt",
                """
                package sample

                import space.kscience.dataforge.names.parseAsName
                import space.kscience.krig.api.annotations.KrigDeviceContract
                import space.kscience.krig.core.meta.DeviceContractBuilder
                import space.kscience.krig.core.meta.doubleProperty
                import space.kscience.krig.generated.contract_test.$generatedName

                @KrigDeviceContract(id = "lab.form", version = "1.1.0")
                object FormContract : DeviceContractBuilder() {
                    val temperature by doubleProperty()
                }

                val generatedFormSchema = $generatedName.formSchema()

                val generatedFormSchemaSmoke: Boolean = run {
                    check(generatedFormSchema.manifestId == "lab.form".parseAsName())
                    check(generatedFormSchema.manifestVersion == "1.1.0")
                    check(generatedFormSchema.properties.single().name == FormContract.temperature.name)
                    check(generatedFormSchema.commands.any { it.target.name == FormContract.temperature.name })
                    true
                }
                """.trimIndent(),
            ),
            extraProcessorOptions = mapOf(DeviceContractGenerator.GENERATED_FORM_SCHEMA_OPTION to "true"),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun rendersEscapedContractIdentifierWithKotlinPoet() {
        val generatedName = DeviceContractGenerator.generatedArtifactName("sample.odd-name", "odd-name")
        val result = compileDeviceContracts(
            SourceFile.kotlin(
                "EscapedContract.kt",
                """
                package sample

                import space.kscience.dataforge.names.parseAsName
                import space.kscience.krig.api.annotations.KrigDeviceContract
                import space.kscience.krig.core.meta.DeviceContractBuilder
                import space.kscience.krig.core.meta.doubleProperty
                import space.kscience.krig.generated.contract_test.$generatedName

                @KrigDeviceContract(id = "lab.escaped")
                object `odd-name` : DeviceContractBuilder() {
                    val value by doubleProperty()
                }

                val escapedContractSmoke: Boolean = run {
                    check($generatedName.registry.id == "lab.escaped".parseAsName())
                    check($generatedName.registry.propertiesByName.containsKey(`odd-name`.value.name))
                    true
                }
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun nestedContractsWithSameShortNameGenerateDistinctArtifacts() {
        val leftName = DeviceContractGenerator.generatedArtifactName("sample.Left.Contract", "Contract")
        val rightName = DeviceContractGenerator.generatedArtifactName("sample.Right.Contract", "Contract")
        assertNotEquals(leftName, rightName)

        val result = compileDeviceContracts(
            SourceFile.kotlin(
                "NestedContracts.kt",
                """
                package sample

                import space.kscience.dataforge.names.parseAsName
                import space.kscience.krig.api.annotations.KrigDeviceContract
                import space.kscience.krig.core.meta.DeviceContractBuilder
                import space.kscience.krig.core.meta.doubleProperty
                import space.kscience.krig.generated.contract_test.$leftName
                import space.kscience.krig.generated.contract_test.$rightName

                object Left {
                    @KrigDeviceContract(id = "lab.left")
                    class Contract : DeviceContractBuilder() {
                        val value by doubleProperty()
                    }
                }

                object Right {
                    @KrigDeviceContract(id = "lab.right")
                    class Contract : DeviceContractBuilder() {
                        val value by doubleProperty()
                    }
                }

                val nestedContractsSmoke: Boolean = run {
                    check($leftName.registry.id == "lab.left".parseAsName())
                    check($rightName.registry.id == "lab.right".parseAsName())
                    true
                }
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun rejectsAnnotatedObjectThatIsNotAContractBuilder() {
        val result = compileDeviceContracts(
            SourceFile.kotlin(
                "BadContract.kt",
                """
                package sample

                import space.kscience.krig.api.annotations.KrigDeviceContract

                @KrigDeviceContract(id = "bad.contract")
                object BadContract {
                    const val TOUCHED: String = "yes"
                }

                val badContractSmoke: Boolean = BadContract.TOUCHED.isNotEmpty()
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("does not extend" in result.messages, result.messages)
    }

    @Test
    fun rejectsAnnotatedClassWithConstructorArguments() {
        val result = compileDeviceContracts(
            SourceFile.kotlin(
                "ParametrizedContract.kt",
                """
                package sample

                import space.kscience.krig.api.annotations.KrigDeviceContract
                import space.kscience.krig.core.meta.DeviceContractBuilder
                import space.kscience.krig.core.meta.doubleProperty

                @KrigDeviceContract(id = "bad.parametrized")
                class ParametrizedContract(private val prefix: String) : DeviceContractBuilder() {
                    val value by doubleProperty()

                    fun label(): String = prefix + value.name.toString()
                }

                val parametrizedContractSmoke: String = ParametrizedContract("x").label()
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("public no-arg constructor" in result.messages, result.messages)
    }

    @Test
    fun rejectsAbstractAndSealedContracts() {
        val result = compileDeviceContracts(
            SourceFile.kotlin(
                "NonConcreteContracts.kt",
                """
                package sample

                import space.kscience.krig.api.annotations.KrigDeviceContract
                import space.kscience.krig.core.meta.DeviceContractBuilder

                @KrigDeviceContract(id = "bad.abstract")
                abstract class AbstractContract : DeviceContractBuilder()

                @KrigDeviceContract(id = "bad.sealed")
                sealed class SealedContract : DeviceContractBuilder()
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("AbstractContract' must be concrete; abstract" in result.messages, result.messages)
        assertTrue("SealedContract' must be concrete; sealed" in result.messages, result.messages)
    }

    @Test
    fun rejectsGenericContract() {
        val result = compileDeviceContracts(
            SourceFile.kotlin(
                "GenericContract.kt",
                """
                package sample

                import space.kscience.krig.api.annotations.KrigDeviceContract
                import space.kscience.krig.core.meta.DeviceContractBuilder

                @KrigDeviceContract(id = "bad.generic")
                class GenericContract<T> : DeviceContractBuilder()
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("GenericContract' must not declare type parameters" in result.messages, result.messages)
    }

    @Test
    fun rejectsInnerContract() {
        val result = compileDeviceContracts(
            SourceFile.kotlin(
                "InnerContract.kt",
                """
                package sample

                import space.kscience.krig.api.annotations.KrigDeviceContract
                import space.kscience.krig.core.meta.DeviceContractBuilder

                class Container {
                    @KrigDeviceContract(id = "bad.inner")
                    inner class InnerContract : DeviceContractBuilder()
                }
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("InnerContract' must not be inner" in result.messages, result.messages)
    }

    @Test
    fun rejectsNonPublicNoArgConstructors() {
        val result = compileDeviceContracts(
            SourceFile.kotlin(
                "HiddenConstructors.kt",
                """
                package sample

                import space.kscience.krig.api.annotations.KrigDeviceContract
                import space.kscience.krig.core.meta.DeviceContractBuilder

                @KrigDeviceContract(id = "bad.private-constructor")
                class PrivateContract private constructor() : DeviceContractBuilder()

                @KrigDeviceContract(id = "bad.protected-constructor")
                open class ProtectedContract protected constructor() : DeviceContractBuilder()

                @KrigDeviceContract(id = "bad.internal-constructor")
                class InternalContract internal constructor() : DeviceContractBuilder()
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("PrivateContract' must have a public no-arg constructor" in result.messages, result.messages)
        assertTrue("ProtectedContract' must have a public no-arg constructor" in result.messages, result.messages)
        assertTrue("InternalContract' must have a public no-arg constructor" in result.messages, result.messages)
        assertTrue("private, protected and internal constructors" in result.messages, result.messages)
    }
}

@OptIn(ExperimentalCompilerApi::class)
private fun compileDeviceContracts(
    vararg extra: SourceFile,
    extraProcessorOptions: Map<String, String> = emptyMap(),
): com.tschuchort.compiletesting.JvmCompilationResult =
    compileWithKrigKsp(
        *extra,
        generatedModule = "contract_test",
        generatedLayer = "common",
        extraProcessorOptions = extraProcessorOptions,
    )
