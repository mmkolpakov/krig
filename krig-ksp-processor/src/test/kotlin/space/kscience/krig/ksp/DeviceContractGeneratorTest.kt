package space.kscience.krig.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.useKsp2
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import space.kscience.krig.api.annotations.KrigDeviceContract
import space.kscience.krig.core.meta.DeviceContractBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
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
                import space.kscience.krig.generated.contract_test.MotorContractGenerated

                @KrigDeviceContract(id = "lab.motor", version = "2.0.0")
                object MotorContract : DeviceContractBuilder() {
                    val rpm by doubleProperty()
                    val target by mutableDoubleProperty()
                }

                val generatedRegistry = MotorContractGenerated.registry
                val generatedManifest = MotorContractGenerated.manifest()
                val generatedJsonSchema = MotorContractGenerated.jsonSchema()

                val generatedContractSmoke: Boolean = run {
                    check(generatedRegistry.id == "lab.motor".parseAsName())
                    check(generatedRegistry.version == "2.0.0")
                    check(generatedRegistry.propertiesByName.containsKey(MotorContract.rpm.name))
                    check(generatedRegistry.propertiesByName.containsKey(MotorContract.target.name))
                    check(generatedManifest.properties.containsKey(MotorContract.rpm.name))
                    check(MotorContractGenerated.schemaHash.startsWith("fnv1a64:"))
                    check(generatedJsonSchema.containsKey("properties"))
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
}

@OptIn(ExperimentalCompilerApi::class)
private fun compileDeviceContracts(vararg extra: SourceFile): com.tschuchort.compiletesting.JvmCompilationResult =
    KotlinCompilation().apply {
        sources = extra.toList()
        inheritClassPath = true
        configureKsp {
            processorOptions["krig.generated.module"] = "contract_test"
            processorOptions["krig.generated.layer"] = "common"
            withCompilation = true
            symbolProcessorProviders += KrigSymbolProcessorProvider()
        }
    }.also { it.useKsp2() }.compile()
