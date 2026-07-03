package space.kscience.krig.ksp

import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalCompilerApi::class)
class KtSchemaKspInteropTest {

    @Test
    fun ktSchemaKspCanRunBesideKrigContractGeneration() {
        val ktSchemaProvider = ServiceLoader.load(SymbolProcessorProvider::class.java)
            .firstOrNull { provider ->
                provider.javaClass.name == "me.kpavlov.kt.schema.ksp.SchemaExtensionProcessorProvider"
            }
        assertNotNull(ktSchemaProvider, "kt-schema KSP provider must be available on the test runtime classpath.")

        val result = compileWithKrigKsp(
            SourceFile.kotlin(
                "SchemaInterop.kt",
                """
                package sample.schema

                import me.kpavlov.kt.schema.Schema
                import space.kscience.krig.api.annotations.KrigDeviceContract
                import space.kscience.krig.core.meta.DeviceContractBuilder
                import space.kscience.krig.core.meta.doubleProperty
                import space.kscience.krig.generated.schema_interop.SchemaContractGenerated

                @Schema
                data class CommandDto(val speed: Double)

                @KrigDeviceContract(id = "lab.schema", version = "1.0.0")
                object SchemaContract : DeviceContractBuilder() {
                    val speed by doubleProperty()
                }

                val generatedSchemaString = CommandDto::class.jsonSchemaString
                val generatedRegistry = SchemaContractGenerated.registry
                val generatedManifest = SchemaContractGenerated.manifest()

                val schemaInteropSmoke: Boolean = run {
                    check(generatedSchemaString.contains("properties"))
                    check(generatedSchemaString.contains("speed"))
                    check(generatedRegistry.propertiesByName.containsKey(SchemaContract.speed.name))
                    check(generatedManifest.properties.containsKey(SchemaContract.speed.name))
                    check(SchemaContractGenerated.schemaHash.startsWith("fnv1a64:"))
                    true
                }
                """.trimIndent(),
            ),
            generatedModule = "schema_interop",
            generatedLayer = "common",
            extraProcessorOptions = mapOf(
                "me.kpavlov.kt.schema.rootPackage" to "sample.schema",
                "me.kpavlov.kt.schema.include" to "sample.schema.CommandDto",
                "me.kpavlov.kt.schema.exclude" to "sample.schema.internal.**",
                "me.kpavlov.kt.schema.withSchemaObject" to "false",
                "me.kpavlov.kt.schema.visibility" to "",
            ),
            extraSymbolProcessorProviders = listOf(ktSchemaProvider),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }
}
