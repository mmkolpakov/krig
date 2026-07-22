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
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCompilerApi::class)
class SerializersModuleGeneratorTest {

    @Test
    fun rendersConflictingShortNamesAndEscapedIdentifiersWithKotlinPoet() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin("SerializationAnnotations.kt", KOTLINX_SERIALIZATION_STUBS),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
            SourceFile.kotlin(
                "BaseThing.kt",
                """
                package base

                import space.kscience.krig.api.annotations.PolymorphicBase

                @PolymorphicBase
                interface Thing
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "SubtypeThing.kt",
                """
                package subtype

                import kotlinx.serialization.Serializable

                @Serializable
                data class Thing(val id: String) : base.Thing

                @Serializable
                data class `odd-name`(val id: String) : base.Thing
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "UseGenerated.kt",
                """
                package consumer

                import space.kscience.krig.generated.serializer_name_collision_test.generatedKrigSerializersModule

                val module = generatedKrigSerializersModule
                """.trimIndent(),
            ),
            generatedModule = "serializer_name_collision_test",
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val contributors = result.generatedFiles.filter { it.name.endsWith("_ContributorKt.class") }
        assertEquals(2, contributors.size, result.generatedFiles.joinToString { it.name })
        assertTrue(contributors.any { "odd_name" in it.name }, contributors.joinToString { it.name })
    }

    @Test
    fun rejectsEnumSubtypeUnsupportedByClassDiscriminatorJson() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin("SerializationAnnotations.kt", KOTLINX_SERIALIZATION_STUBS),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
            SourceFile.kotlin(
                "ExtensionPoint.kt",
                """
                package sample.api

                import space.kscience.krig.api.annotations.PolymorphicBase

                @PolymorphicBase
                interface ExtensionPoint
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "EnumExtension.kt",
                """
                package sample

                import kotlinx.serialization.Serializable
                import sample.api.ExtensionPoint

                @Serializable
                enum class EnumExtension : ExtensionPoint {
                    FIRST,
                    SECOND,
                }
                """.trimIndent(),
            ),
            generatedModule = "serializer_enum_test",
            inheritClassPath = false,
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(
            result.messages,
            "SerializersModuleGenerator cannot auto-register enum subtype sample.EnumExtension because " +
                "class-discriminator polymorphism requires an object-shaped serializer; " +
                "use a @Serializable class or object wrapper instead.",
        )
    }

    @Test
    fun rejectsValueSubtypeUnsupportedByClassDiscriminatorJson() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin("SerializationAnnotations.kt", KOTLINX_SERIALIZATION_STUBS),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
            SourceFile.kotlin(
                "ExtensionPoint.kt",
                """
                package sample.api

                import space.kscience.krig.api.annotations.PolymorphicBase

                @PolymorphicBase
                interface ExtensionPoint
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "ValueExtension.kt",
                """
                package sample

                import kotlinx.serialization.Serializable
                import sample.api.ExtensionPoint

                @Serializable
                @JvmInline
                value class ValueExtension(val value: String) : ExtensionPoint
                """.trimIndent(),
            ),
            generatedModule = "serializer_value_test",
            inheritClassPath = false,
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(
            result.messages,
            "SerializersModuleGenerator does not auto-register value subtype sample.ValueExtension because KSP " +
                "cannot prove its inline serializer is object-shaped; use a @Serializable class/object wrapper " +
                "or an explicitly configured compatible module and wire format.",
        )
    }

    @Test
    fun acceptsOmittedDefaultSerializerArgumentWhenKspCannotMaterializeDefault() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin(
                "SerializationAnnotations.kt",
                """
                package kotlinx.serialization

                @Target(AnnotationTarget.CLASS)
                annotation class Serializable
                """.trimIndent(),
            ),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
            SourceFile.kotlin(
                "Extension.kt",
                """
                package sample

                import kotlinx.serialization.Serializable
                import space.kscience.krig.api.annotations.PolymorphicBase

                @PolymorphicBase
                interface ExtensionPoint

                @Serializable
                data class Extension(val payload: String) : ExtensionPoint
                """.trimIndent(),
            ),
            generatedModule = "serializer_omitted_default_test",
            inheritClassPath = false,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue(
            result.generatedFiles.any { it.name.endsWith("_ContributorKt.class") },
            result.generatedFiles.joinToString { it.name },
        )
    }

    @Test
    fun acceptsOmittedDefaultSerializerFromRealBinaryAnnotation() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin(
                "RealSerializationAnnotation.kt",
                """
                package sample

                import kotlinx.serialization.Serializable
                import space.kscience.krig.api.annotations.PolymorphicBase

                @PolymorphicBase
                interface ExtensionPoint

                @Serializable
                data class Extension(val payload: String) : ExtensionPoint
                """.trimIndent(),
            ),
            generatedModule = "serializer_real_binary_default_test",
            withCompilation = false,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue(
            result.generatedFiles.any { it.name.endsWith("_ContributorKt.class") },
            result.generatedFiles.joinToString { it.name },
        )
    }

    @Test
    fun rejectsCustomSerializerWhoseDescriptorShapeCannotBeProved() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin("SerializationAnnotations.kt", KOTLINX_SERIALIZATION_STUBS),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
            SourceFile.kotlin(
                "ExtensionPoint.kt",
                """
                package sample.api

                import space.kscience.krig.api.annotations.PolymorphicBase

                @PolymorphicBase
                interface ExtensionPoint
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "CustomExtension.kt",
                """
                package sample

                import kotlinx.serialization.KSerializer
                import kotlinx.serialization.Serializable
                import sample.api.ExtensionPoint

                object CustomExtensionSerializer : KSerializer<CustomExtension>

                @Serializable(with = CustomExtensionSerializer::class)
                data class CustomExtension(val value: String) : ExtensionPoint

                object PositionalExtensionSerializer : KSerializer<PositionalExtension>

                @Serializable(PositionalExtensionSerializer::class)
                data class PositionalExtension(val value: String) : ExtensionPoint
                """.trimIndent(),
            ),
            generatedModule = "serializer_custom_test",
            inheritClassPath = false,
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(
            result.messages,
            "SerializersModuleGenerator cannot verify custom serializer sample.CustomExtensionSerializer " +
                "for subtype sample.CustomExtension; register it explicitly with a concrete KSerializer " +
                "and verify it against the chosen wire format.",
        )
        assertContains(
            result.messages,
            "SerializersModuleGenerator cannot verify custom serializer sample.PositionalExtensionSerializer " +
                "for subtype sample.PositionalExtension; register it explicitly with a concrete KSerializer " +
                "and verify it against the chosen wire format.",
        )
    }

    @Test
    fun rejectsConcreteSubtypeWithPolymorphicSerializer() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin("SerializationAnnotations.kt", KOTLINX_SERIALIZATION_STUBS),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
            SourceFile.kotlin(
                "ExtensionPoint.kt",
                """
                package sample.api

                import space.kscience.krig.api.annotations.PolymorphicBase

                @PolymorphicBase
                interface ExtensionPoint
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "PolymorphicExtension.kt",
                """
                package sample

                import kotlinx.serialization.Polymorphic
                import kotlinx.serialization.Serializable
                import sample.api.ExtensionPoint

                @Serializable
                @Polymorphic
                data class PolymorphicExtension(val value: String) : ExtensionPoint
                """.trimIndent(),
            ),
            generatedModule = "serializer_polymorphic_subtype_test",
            inheritClassPath = false,
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(
            result.messages,
            "SerializersModuleGenerator cannot auto-register subtype sample.PolymorphicExtension because " +
                "its class-level @Polymorphic serializer is not concrete; remove @Polymorphic from the " +
                "subtype or register a concrete KSerializer explicitly.",
        )
    }

    @Test
    fun rejectsDuplicateSubtypeSerialNamesWithinOneBase() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin("SerializationAnnotations.kt", KOTLINX_SERIALIZATION_STUBS),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
            SourceFile.kotlin(
                "ExtensionPoint.kt",
                """
                package sample.api

                import space.kscience.krig.api.annotations.PolymorphicBase

                @PolymorphicBase
                interface ExtensionPoint
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "Extensions.kt",
                """
                package sample

                import kotlinx.serialization.SerialName
                import kotlinx.serialization.Serializable
                import sample.api.ExtensionPoint

                @Serializable
                @SerialName("extension.duplicate")
                data class ExplicitOne(val value: String) : ExtensionPoint

                @Serializable
                @SerialName("extension.duplicate")
                data class ExplicitTwo(val value: String) : ExtensionPoint

                @Serializable
                data class DefaultName(val value: String) : ExtensionPoint

                @Serializable
                @SerialName("sample.DefaultName")
                data class DefaultAlias(val value: String) : ExtensionPoint
                """.trimIndent(),
            ),
            generatedModule = "serializer_duplicate_name_test",
            inheritClassPath = false,
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(
            result.messages,
            "duplicate serial name 'extension.duplicate' for base sample.api.ExtensionPoint",
        )
        assertContains(
            result.messages,
            "duplicate serial name 'sample.DefaultName' for base sample.api.ExtensionPoint",
        )
    }

    @Test
    fun rejectsPropertiesUsingReservedClassDiscriminator() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin("SerializationAnnotations.kt", KOTLINX_SERIALIZATION_STUBS),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
            SourceFile.kotlin(
                "ExtensionPoint.kt",
                """
                package sample.api

                import space.kscience.krig.api.annotations.PolymorphicBase

                @PolymorphicBase
                interface ExtensionPoint
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "Extensions.kt",
                """
                package sample

                import kotlinx.serialization.SerialName
                import kotlinx.serialization.Serializable
                import sample.api.ExtensionPoint

                @Serializable
                data class DirectType(val type: String) : ExtensionPoint

                @Serializable
                data class RenamedType(@SerialName("type") val payload: String) : ExtensionPoint

                @Serializable
                class BodyType(val payload: String) : ExtensionPoint {
                    val type: String = payload
                }
                """.trimIndent(),
            ),
            generatedModule = "serializer_discriminator_name_test",
            inheritClassPath = false,
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(
            result.messages,
            "subtype sample.DirectType because property type uses reserved class discriminator 'type'",
        )
        assertContains(
            result.messages,
            "subtype sample.RenamedType because property payload uses reserved class discriminator 'type'",
        )
        assertContains(
            result.messages,
            "subtype sample.BodyType because property type uses reserved class discriminator 'type'",
        )
    }

    @Test
    fun rejectsInheritedPrivateDiscriminatorAndDecodeAlias() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin("SerializationAnnotations.kt", KOTLINX_SERIALIZATION_STUBS),
            SourceFile.kotlin("JsonAnnotations.kt", KOTLINX_SERIALIZATION_JSON_STUBS),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
            SourceFile.kotlin(
                "ExtensionPoint.kt",
                """
                package sample.api

                import space.kscience.krig.api.annotations.PolymorphicBase

                @PolymorphicBase
                interface ExtensionPoint
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "Extensions.kt",
                """
                package sample

                import kotlinx.serialization.KSerializer
                import kotlinx.serialization.KeepGeneratedSerializer
                import kotlinx.serialization.MetaSerializable
                import kotlinx.serialization.Serializable
                import kotlinx.serialization.json.JsonNames
                import sample.api.ExtensionPoint

                @Serializable
                open class PrivateParent(private val type: String)

                @Serializable
                class InheritedType(payload: String) : PrivateParent(payload), ExtensionPoint

                object ParentSerializer : KSerializer<CustomParent>

                @Serializable(with = ParentSerializer::class)
                @KeepGeneratedSerializer
                open class CustomParent(private val type: String)

                @Serializable
                class KeptInheritedType(payload: String) : CustomParent(payload), ExtensionPoint

                @MetaSerializable
                @Target(AnnotationTarget.CLASS)
                annotation class DomainSerializable

                @DomainSerializable
                open class MetaParent(private val type: String)

                @Serializable
                class MetaInheritedType(payload: String) : MetaParent(payload), ExtensionPoint

                @Serializable
                data class DecodeAlias(@JsonNames("legacy", "type") val payload: String) : ExtensionPoint
                """.trimIndent(),
            ),
            generatedModule = "serializer_inherited_discriminator_test",
            inheritClassPath = false,
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(
            result.messages,
            "subtype sample.InheritedType because property type uses reserved class discriminator 'type'",
        )
        assertContains(
            result.messages,
            "subtype sample.KeptInheritedType because property type uses reserved class discriminator 'type'",
        )
        assertContains(
            result.messages,
            "subtype sample.MetaInheritedType because property type uses reserved class discriminator 'type'",
        )
        assertContains(
            result.messages,
            "subtype sample.DecodeAlias because property payload uses reserved class discriminator " +
                "'type' as a @JsonNames alias",
        )
    }

    @Test
    fun rejectsNonDefaultJsonClassDiscriminator() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin("SerializationAnnotations.kt", KOTLINX_SERIALIZATION_STUBS),
            SourceFile.kotlin("JsonAnnotations.kt", KOTLINX_SERIALIZATION_JSON_STUBS),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
            SourceFile.kotlin(
                "ExtensionPoint.kt",
                """
                package sample.api

                import kotlinx.serialization.json.JsonClassDiscriminator
                import space.kscience.krig.api.annotations.PolymorphicBase

                @PolymorphicBase
                @JsonClassDiscriminator("kind")
                interface ExtensionPoint
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "Extension.kt",
                """
                package sample

                import kotlinx.serialization.Serializable
                import sample.api.ExtensionPoint

                @Serializable
                data class Extension(val payload: String) : ExtensionPoint
                """.trimIndent(),
            ),
            generatedModule = "serializer_custom_discriminator_test",
            inheritClassPath = false,
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(
            result.messages,
            "base sample.api.ExtensionPoint because @JsonClassDiscriminator('kind') conflicts " +
                "with krig's 'type' wire discriminator",
        )
    }

    @Test
    fun rejectsInvalidPolymorphicBaseShapes() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin("SerializationAnnotations.kt", KOTLINX_SERIALIZATION_STUBS),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
            SourceFile.kotlin(
                "InvalidBases.kt",
                """
                package sample

                import kotlinx.serialization.KSerializer
                import kotlinx.serialization.Serializable
                import space.kscience.krig.api.annotations.PolymorphicBase

                @PolymorphicBase
                interface GenericBase<T>

                @Serializable
                data class GenericExtension(val payload: String) : GenericBase<String>

                @PolymorphicBase
                open class OpenBase

                @Serializable
                class OpenExtension : OpenBase()

                object CustomBaseSerializer : KSerializer<CustomBase>

                @PolymorphicBase
                @Serializable(with = CustomBaseSerializer::class)
                interface CustomBase

                @Serializable
                data class CustomExtension(val payload: String) : CustomBase

                @PolymorphicBase
                sealed interface SealedBase

                @Serializable
                object SealedExtension : SealedBase

                private object HiddenScope {
                    @PolymorphicBase
                    interface HiddenBase

                    @Serializable
                    data class HiddenExtension(val payload: String) : HiddenBase
                }
                """.trimIndent(),
            ),
            generatedModule = "serializer_invalid_base_test",
            inheritClassPath = false,
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(result.messages, "generic @PolymorphicBase sample.GenericBase")
        assertContains(result.messages, "@PolymorphicBase sample.OpenBase must be a non-sealed interface")
        assertContains(
            result.messages,
            "custom serializer sample.CustomBaseSerializer on @PolymorphicBase sample.CustomBase",
        )
        assertContains(result.messages, "sealed @PolymorphicBase sample.SealedBase")
        assertContains(result.messages, "inaccessible @PolymorphicBase sample.HiddenScope.HiddenBase")
    }

    @Test
    fun allowsSameSerialNameInDifferentBaseScopes() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin("SerializationAnnotations.kt", KOTLINX_SERIALIZATION_STUBS),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
            SourceFile.kotlin(
                "BaseScopes.kt",
                """
                package sample

                import kotlinx.serialization.SerialName
                import kotlinx.serialization.Serializable
                import space.kscience.krig.api.annotations.PolymorphicBase

                @PolymorphicBase
                interface FirstBase

                @PolymorphicBase
                interface SecondBase

                @Serializable
                @SerialName("shared")
                data class SharedExtension(val payload: String) : FirstBase, SecondBase

                @Serializable
                @SerialName("parallel")
                data class FirstExtension(val payload: String) : FirstBase

                @Serializable
                @SerialName("parallel")
                data class SecondExtension(val payload: String) : SecondBase
                """.trimIndent(),
            ),
            generatedModule = "serializer_base_scope_test",
            inheritClassPath = false,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val contributors = result.generatedFiles.filter { it.name.endsWith("_ContributorKt.class") }
        assertEquals(4, contributors.size, result.generatedFiles.joinToString { it.name })
    }

    @Test
    fun usesDiscriminatorFromStaticBaseHierarchyOnly() {
        val inheritedResult = compileWithKrigKsp(
            SourceFile.kotlin("SerializationAnnotations.kt", KOTLINX_SERIALIZATION_STUBS),
            SourceFile.kotlin("JsonAnnotations.kt", KOTLINX_SERIALIZATION_JSON_STUBS),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
            SourceFile.kotlin(
                "InheritedDiscriminator.kt",
                """
                package sample.inherited

                import kotlinx.serialization.Serializable
                import kotlinx.serialization.json.JsonClassDiscriminator
                import space.kscience.krig.api.annotations.PolymorphicBase

                @JsonClassDiscriminator("kind")
                interface WireAncestor

                @PolymorphicBase
                interface ExtensionPoint : WireAncestor

                @Serializable
                data class Extension(val payload: String) : ExtensionPoint
                """.trimIndent(),
            ),
            generatedModule = "serializer_inherited_discriminator_test",
            inheritClassPath = false,
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, inheritedResult.exitCode, inheritedResult.messages)
        assertContains(
            inheritedResult.messages,
            "base sample.inherited.ExtensionPoint because @JsonClassDiscriminator('kind') conflicts",
        )

        val leafResult = compileWithKrigKsp(
            SourceFile.kotlin("SerializationAnnotations.kt", KOTLINX_SERIALIZATION_STUBS),
            SourceFile.kotlin("JsonAnnotations.kt", KOTLINX_SERIALIZATION_JSON_STUBS),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
            SourceFile.kotlin(
                "LeafDiscriminator.kt",
                """
                package sample.leaf

                import kotlinx.serialization.Serializable
                import kotlinx.serialization.json.JsonClassDiscriminator
                import space.kscience.krig.api.annotations.PolymorphicBase

                @PolymorphicBase
                interface ExtensionPoint

                @Serializable
                @JsonClassDiscriminator("kind")
                data class Extension(val payload: String) : ExtensionPoint
                """.trimIndent(),
            ),
            generatedModule = "serializer_leaf_discriminator_test",
            inheritClassPath = false,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, leafResult.exitCode, leafResult.messages)
    }

    @Test
    fun allowsPropertiesAbsentFromGeneratedDescriptor() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin("SerializationAnnotations.kt", KOTLINX_SERIALIZATION_STUBS),
            SourceFile.kotlin("JsonAnnotations.kt", KOTLINX_SERIALIZATION_JSON_STUBS),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
            SourceFile.kotlin(
                "ExtensionPoint.kt",
                """
                package sample.api

                import kotlinx.serialization.json.JsonClassDiscriminator
                import space.kscience.krig.api.annotations.PolymorphicBase

                @PolymorphicBase
                @JsonClassDiscriminator("type")
                interface ExtensionPoint
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "Extensions.kt",
                """
                package sample

                import kotlinx.serialization.Serializable
                import kotlinx.serialization.KSerializer
                import kotlinx.serialization.Transient
                import sample.api.ExtensionPoint

                @Serializable
                data class TransientType(
                    @Transient val type: String = "runtime-only",
                    val payload: String,
                ) : ExtensionPoint

                @Serializable
                class ComputedType(val payload: String) : ExtensionPoint {
                    val type: String get() = payload
                }

                open class PlainParent {
                    val type: String = "runtime-only"
                }

                @Serializable
                data class PlainInheritedType(val payload: String) : PlainParent(), ExtensionPoint

                @Serializable
                class DelegatedType(val payload: String) : ExtensionPoint {
                    val type: String by lazy { payload }
                }

                interface InterfaceComputedType {
                    val type: String get() = "runtime-only"
                }

                @Serializable
                data class InterfacePropertyType(val payload: String) : ExtensionPoint, InterfaceComputedType

                object CustomParentSerializer : KSerializer<CustomParent>

                @Serializable(with = CustomParentSerializer::class)
                open class CustomParent {
                    val type: String = "runtime-only"
                }

                @Serializable
                data class CustomParentChild(val payload: String) : CustomParent(), ExtensionPoint

                @Serializable
                object ObjectType : ExtensionPoint {
                    val type: String = "runtime-only"
                }
                """.trimIndent(),
            ),
            generatedModule = "serializer_non_descriptor_property_test",
            inheritClassPath = false,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val contributors = result.generatedFiles.filter { it.name.endsWith("_ContributorKt.class") }
        assertEquals(7, contributors.size, result.generatedFiles.joinToString { it.name })
    }

    @Test
    fun rejectsGenericSubtypeWithoutExplicitSerializer() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin("SerializationAnnotations.kt", KOTLINX_SERIALIZATION_STUBS),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
            SourceFile.kotlin(
                "ExtensionPoint.kt",
                """
                package sample.api

                import space.kscience.krig.api.annotations.PolymorphicBase

                @PolymorphicBase
                interface ExtensionPoint
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "GenericExtension.kt",
                """
                package sample

                import kotlinx.serialization.Serializable
                import sample.api.ExtensionPoint

                @Serializable
                data class GenericExtension<T>(val value: T) : ExtensionPoint
                """.trimIndent(),
            ),
            generatedModule = "serializer_generic_test",
            inheritClassPath = false,
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(
            result.messages,
            "SerializersModuleGenerator cannot auto-register generic subtype sample.GenericExtension; " +
                "register it explicitly with a concrete KSerializer.",
        )
    }

    @Test
    fun generatesModuleForNestedSubclassWithIndirectPolymorphicBase() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin("SerializationAnnotations.kt", KOTLINX_SERIALIZATION_STUBS),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
            SourceFile.kotlin(
                "ExtensionPoint.kt",
                """

                    package sample.api

                    import kotlinx.serialization.Polymorphic
                    import space.kscience.krig.api.annotations.PolymorphicBase

                    @Polymorphic
                    @PolymorphicBase
                    interface ExtensionPoint

                    @Polymorphic
                    @PolymorphicBase
                    interface SecondaryExtensionPoint
                    """.trimIndent(),
            ),
            SourceFile.kotlin(
                "IntegrationExtension.kt",
                """

                    package sample

                    import kotlinx.serialization.SerialName
                    import kotlinx.serialization.Serializable
                    import sample.api.ExtensionPoint
                    import sample.api.SecondaryExtensionPoint

                    typealias SecondaryExtensionAlias = SecondaryExtensionPoint

                    abstract class IntermediateExtension : ExtensionPoint, SecondaryExtensionAlias

                    object Extensions {
                        @Serializable
                        @SerialName("extension.integration")
                        data class IntegrationExtension(val id: String) : IntermediateExtension()
                    }
                    """.trimIndent(),
            ),
            SourceFile.kotlin(
                "UseGenerated.kt",
                """

                    package sample

                    import space.kscience.krig.generated.extension_test.generatedKrigSerializersModule

                    val module = generatedKrigSerializersModule
                    """.trimIndent(),
            ),
            generatedModule = "extension_test",
            inheritClassPath = false,
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val contributorFiles = result.generatedFiles.filter {
            it.name.startsWith("Serializer_IntegrationExtension_") && it.name.endsWith("_ContributorKt.class")
        }
        assertEquals(2, contributorFiles.size, result.generatedFiles.joinToString { it.name })
    }

    @Test
    fun finalIndexIncludesSubclassGeneratedAfterKrigWasLocallyQuiet() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin("SerializationAnnotations.kt", KOTLINX_SERIALIZATION_STUBS),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
            SourceFile.kotlin(
                "ExtensionPoint.kt",
                """
                package sample.api

                import space.kscience.krig.api.annotations.PolymorphicBase

                @PolymorphicBase
                interface ExtensionPoint
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "BaselineExtension.kt",
                """
                package sample

                import kotlinx.serialization.Serializable
                import sample.api.ExtensionPoint

                @Serializable
                data class BaselineExtension(val id: String) : ExtensionPoint
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "UseGenerated.kt",
                """
                package sample

                import space.kscience.krig.generated.extension_round_test.generatedKrigSerializersModule

                val module = generatedKrigSerializersModule
                """.trimIndent(),
            ),
            generatedModule = "extension_round_test",
            inheritClassPath = false,
            extraSymbolProcessorProviders = listOf(SecondRoundSerializableProvider()),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val index = result.generatedFiles.single { it.name == "GeneratedKrigSerializersModuleKt.class" }
        val constantPool = index.readBytes().toString(Charsets.ISO_8859_1)
        assertContains(constantPool, "BaselineExtension_")
        assertContains(constantPool, "LateExtension_")
    }

    @Test
    fun doesNotCacheBaseValidationWhileOwnerIsAlreadyDeferred() {
        val result = compileWithKrigKsp(
            SourceFile.kotlin("SerializationAnnotations.kt", KOTLINX_SERIALIZATION_STUBS),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
            SourceFile.kotlin(
                "DeferredHierarchy.kt",
                """
                package sample

                import kotlinx.serialization.Serializable
                import kotlinx.serialization.json.JsonClassDiscriminator
                import space.kscience.krig.api.annotations.PolymorphicBase

                @PolymorphicBase
                @JsonClassDiscriminator("kind")
                interface ExtensionPoint

                interface DeferredBranch : GeneratedFirstAncestor, ExtensionPoint

                @Serializable
                data class Extension(val payload: String) : DeferredBranch
                """.trimIndent(),
            ),
            generatedModule = "serializer_deferred_base_cache_test",
            inheritClassPath = false,
            extraSymbolProcessorProviders = listOf(DeferredBaseSymbolsProvider()),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertContains(
            result.messages,
            "base sample.ExtensionPoint because @JsonClassDiscriminator('kind') conflicts",
        )
    }

    @Test
    fun discoversPolymorphicBaseFromConsumerClasspath() {
        val api = KotlinCompilation().apply {
            inheritClassPath = false
            sources = listOf(
                SourceFile.kotlin("PolymorphicBase.kt", POLYMORPHIC_BASE_STUB),
                SourceFile.kotlin(
                    "PublishedExtensionPoint.kt",
                    """
                    package published.api

                    import space.kscience.krig.api.annotations.PolymorphicBase

                    @PolymorphicBase
                    interface PublishedExtensionPoint

                    abstract class PublishedIntermediate : PublishedExtensionPoint
                    """.trimIndent(),
                ),
            )
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, api.exitCode, api.messages)
        val apiJar = api.outputDirectory.resolveSibling("published-api.jar")
        JarOutputStream(apiJar.outputStream().buffered()).use { archive ->
            api.outputDirectory.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(api.outputDirectory).invariantSeparatorsPath }
                .forEach { file ->
                    archive.putNextEntry(JarEntry(file.relativeTo(api.outputDirectory).invariantSeparatorsPath))
                    file.inputStream().buffered().use { input -> input.copyTo(archive) }
                    archive.closeEntry()
                }
        }

        val consumer = compileWithKrigKsp(
            SourceFile.kotlin("SerializationAnnotations.kt", KOTLINX_SERIALIZATION_STUBS),
            SourceFile.kotlin("SerializersModule.kt", SERIALIZERS_MODULE_STUBS),
            SourceFile.kotlin(
                "ConsumerExtension.kt",
                """
                package consumer

                import kotlinx.serialization.Serializable
                import published.api.PublishedIntermediate

                @Serializable
                data class ConsumerExtension(val id: String) : PublishedIntermediate()

                    @Serializable
                    private data class UnrelatedSerializable<T>(val id: T)
                """.trimIndent(),
            ),
            SourceFile.kotlin(
                "UseGenerated.kt",
                """
                package consumer

                import space.kscience.krig.generated.external_base_test.generatedKrigSerializersModule

                val module = generatedKrigSerializersModule
                """.trimIndent(),
            ),
            generatedModule = "external_base_test",
            inheritClassPath = false,
            classpaths = listOf(apiJar),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, consumer.exitCode, consumer.messages)
        val contributors = consumer.generatedFiles.filter { it.name.endsWith("_ContributorKt.class") }
        assertEquals(1, contributors.size, consumer.generatedFiles.joinToString { it.name })
        val constantPool = contributors.single().readBytes().toString(Charsets.ISO_8859_1)
        assertContains(constantPool, "PublishedExtensionPoint")
        assertContains(constantPool, "ConsumerExtension")
        assertFalse("UnrelatedSerializable" in constantPool, constantPool)
    }
}

private class SecondRoundSerializableProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
        private var invocation = 0

        override fun process(resolver: Resolver): List<KSAnnotated> {
            invocation++
            if (invocation != 2) return emptyList()
            environment.codeGenerator.createNewFile(
                dependencies = Dependencies(aggregating = false),
                packageName = "sample.generated",
                fileName = "LateExtension",
            ).bufferedWriter().use { writer ->
                writer.write(
                    """
                    package sample.generated

                    import kotlinx.serialization.Serializable
                    import sample.api.ExtensionPoint

                    @Serializable
                    data class LateExtension(val id: String) : ExtensionPoint
                    """.trimIndent(),
                )
            }
            return emptyList()
        }
    }
}

private class DeferredBaseSymbolsProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
        private var generated = false

        override fun process(resolver: Resolver): List<KSAnnotated> {
            if (generated) return emptyList()
            generated = true
            environment.codeGenerator.createNewFile(
                dependencies = Dependencies(aggregating = false),
                packageName = "sample",
                fileName = "GeneratedFirstAncestor",
            ).bufferedWriter().use { writer ->
                writer.write("package sample\ninterface GeneratedFirstAncestor")
            }
            environment.codeGenerator.createNewFile(
                dependencies = Dependencies(aggregating = false),
                packageName = "kotlinx.serialization.json",
                fileName = "JsonClassDiscriminator",
            ).bufferedWriter().use { writer ->
                writer.write(
                    """
                    package kotlinx.serialization.json
                    @Target(AnnotationTarget.CLASS)
                    annotation class JsonClassDiscriminator(val discriminator: String)
                    """.trimIndent(),
                )
            }
            return emptyList()
        }
    }
}
