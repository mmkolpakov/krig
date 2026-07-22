package space.kscience.krig.api.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class KrigSerializationRuntimeTest {

    private val serializer = PolymorphicSerializer(RuntimeValue::class)

    @Test
    fun contributorConflictByClassFailsFast() {
        val generated = polymorphicSerializationContributor<RuntimeValue, RuntimeClass>(RuntimeClass.serializer())
        val alternate = polymorphicSerializationContributor<RuntimeValue, RuntimeClass>(
            object : KSerializer<RuntimeClass> by RuntimeClass.serializer() {},
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            buildKrigSerializersModule(generated, alternate)
        }

        assertContains(failure.message.orEmpty(), "already registered")
    }

    @Test
    fun contributorConflictBySerialNameFailsFast() {
        val first = polymorphicSerializationContributor<RuntimeValue, FirstCollision>(FirstCollision.serializer())
        val second = polymorphicSerializationContributor<RuntimeValue, SecondCollision>(SecondCollision.serializer())

        val failure = assertFailsWith<IllegalArgumentException> {
            buildKrigSerializersModule(first, second)
        }

        assertContains(failure.message.orEmpty(), "same serial name 'test.runtime.collision'")
    }

    @Test
    fun repeatedIdenticalContributorIsAccepted() {
        val contributor = polymorphicSerializationContributor<RuntimeValue, RuntimeClass>(RuntimeClass.serializer())
        val json = krigJson(contributor, contributor)
        val value = RuntimeClass("value")

        assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun canonicalJsonFormatsRoundTripClassAndObjectWithTypeDiscriminator() {
        val classContributor =
            polymorphicSerializationContributor<RuntimeValue, RuntimeClass>(RuntimeClass.serializer())
        val objectContributor =
            polymorphicSerializationContributor<RuntimeValue, RuntimeObject>(RuntimeObject.serializer())

        for (json in listOf(krigJson(classContributor, objectContributor), krigStorageJson(classContributor, objectContributor))) {
            assertEquals("type", json.configuration.classDiscriminator)
            assertEquals(ClassDiscriminatorMode.POLYMORPHIC, json.configuration.classDiscriminatorMode)
            assertRoundTrip(json, RuntimeClass("value"), "test.runtime.class")
            assertRoundTrip(json, RuntimeObject, "test.runtime.object")

            val concrete = json.encodeToJsonElement(RuntimeClass.serializer(), RuntimeClass("value")).jsonObject
            assertFalse("type" in concrete, "POLYMORPHIC mode must not tag statically concrete values")
        }
    }

    @Test
    fun directTypePropertyFailsWhenEncodedPolymorphically() {
        val contributor =
            polymorphicSerializationContributor<RuntimeValue, ReservedTypeProperty>(ReservedTypeProperty.serializer())
        val json = krigJson(contributor)

        val failure = assertFailsWith<SerializationException> {
            json.encodeToString(serializer, ReservedTypeProperty("payload"))
        }

        assertContains(failure.message.orEmpty(), "conflicts with JSON class discriminator 'type'")
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun jsonNamesTypeAliasCapturesDiscriminatorWhenStorageOmitsDefault() {
        val contributor = polymorphicSerializationContributor<RuntimeValue, TypeAlias>(TypeAlias.serializer())
        val json = krigStorageJson(contributor)
        val original = TypeAlias()
        val encoded = json.encodeToJsonElement(serializer, original).jsonObject
        val decoded = json.decodeFromJsonElement(serializer, encoded)

        assertEquals("test.runtime.alias", encoded.getValue("type").jsonPrimitive.content)
        assertFalse("payload" in encoded)
        assertNotEquals(original, decoded)
        assertEquals(TypeAlias("test.runtime.alias"), decoded)
    }

    private fun assertRoundTrip(json: Json, value: RuntimeValue, expectedType: String) {
        val encoded = json.encodeToJsonElement(serializer, value).jsonObject

        assertEquals(expectedType, encoded.getValue("type").jsonPrimitive.content)
        assertEquals(value, json.decodeFromJsonElement(serializer, encoded))
    }
}

private interface RuntimeValue

@Serializable
@SerialName("test.runtime.class")
private data class RuntimeClass(val payload: String) : RuntimeValue

@Serializable
@SerialName("test.runtime.object")
private data object RuntimeObject : RuntimeValue

@Serializable
@SerialName("test.runtime.collision")
private data class FirstCollision(val payload: String = "first") : RuntimeValue

@Serializable
@SerialName("test.runtime.collision")
private data class SecondCollision(val payload: String = "second") : RuntimeValue

@Serializable
@SerialName("test.runtime.reserved")
private data class ReservedTypeProperty(val type: String) : RuntimeValue

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("test.runtime.alias")
private data class TypeAlias(@JsonNames("type") val payload: String = "default") : RuntimeValue
