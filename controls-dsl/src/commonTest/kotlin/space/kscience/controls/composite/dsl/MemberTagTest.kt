package space.kscience.controls.composite.dsl

import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.controls.composite.dsl.properties.doubleProperty
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.core.descriptors.PropertyDescriptor
import space.kscience.controls.core.meta.UiTestHint
import space.kscience.dataforge.context.Context
import space.kscience.controls.core.meta.MemberTag
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * A test-specific serializers module that registers our custom [UiTestHint] tag.
 */
private val testSerializersModule = SerializersModule {
    include(ControlsCompositeSerializersModule)
    polymorphic(MemberTag::class) {
        subclass(UiTestHint::class)
    }
}

/**
 * A test context with a Json instance configured with the test module.
 */
private val testJson = kotlinx.serialization.json.Json {
    serializersModule = testSerializersModule
    prettyPrint = true
    classDiscriminator = "type"
}

/**
 * A test suite to verify the declaration, registration, and serialization of [MemberTag].
 */
//all passed
class MemberTagTest {

    @Test
    fun `should register and serialize a custom MemberTag`() {
        // 1. Define a specification that uses the custom tag.
        val testSpec = object : DeviceSpecification<Device>() {
            override val id = "test.spec.with.tag"

            val temperature by doubleProperty(
                read = { 25.0 },
                descriptorBuilder = {
                    description = "Room temperature"
                    addTag(UiTestHint(group = "Sensors", widget = "thermometer"))
                }
            )

            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("Not for runtime") }
            }
        }

        // 2. Build the blueprint.
        val blueprint = compositeDeviceUnchecked(testSpec, Context("test"))

        // 3. Assertions on the blueprint structure.
        val propertySpec = blueprint.properties["temperature".asName()]
        assertNotNull(propertySpec, "The 'temperature' property should exist.")

        val descriptor = propertySpec.descriptor
        assertEquals(1, descriptor.tags.size, "The property should have exactly one tag.")

        val tag = descriptor.tags.first()
        assertIs<UiTestHint>(tag, "The tag should be of the custom type UiTestHint.")
        assertEquals("Sensors", tag.group)
        assertEquals("thermometer", tag.widget)

        // 4. Verify serialization and deserialization.
        val jsonString = testJson.encodeToString(PropertyDescriptor.serializer(), descriptor)
        val restoredDescriptor = testJson.decodeFromString(PropertyDescriptor.serializer(), jsonString)

        assertEquals(descriptor, restoredDescriptor, message = "The descriptor should be correctly deserialized.")
        val restoredTag = restoredDescriptor.tags.first()
        assertIs<UiTestHint>(restoredTag)
        assertEquals("Sensors", restoredTag.group)
    }
}