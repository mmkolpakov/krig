package space.kscience.controls.composite.dsl

import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.controls.api.descriptors.bindings
import space.kscience.controls.composite.dsl.properties.PropertyDescriptorBuilder
import space.kscience.controls.composite.dsl.properties.doubleProperty
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.api.meta.AdapterBinding
import space.kscience.controls.api.meta.ModbusTestBinding
import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * A test-specific, type-safe DSL builder for [ModbusTestBinding].
 */
private class ModbusTestBindingBuilder {
    var unitId: Int = 1
    var registerAddress: Int = 0
    var registerType: String = "holding"

    fun build(): ModbusTestBinding = ModbusTestBinding(unitId, registerAddress, registerType)
}

/**
 * A test-specific DSL extension function that allows attaching a [ModbusTestBinding]
 * to a property in a type-safe manner. This demonstrates the pattern that real
 * adapter modules would implement.
 */
private fun PropertyDescriptorBuilder.modbus(block: ModbusTestBindingBuilder.() -> Unit) {
    val builder = ModbusTestBindingBuilder().apply(block)
    // The key "modbus" is the unique identifier for this adapter.
    attachBinding("modbus", builder.build())
}

/**
 * A test-specific serializers module that registers our custom [ModbusTestBinding].
 */
private val testSerializersModule = SerializersModule {
    include(ControlsCompositeSerializersModule)
    polymorphic(AdapterBinding::class) {
        subclass(ModbusTestBinding::class)
    }
}

private val testJson = kotlinx.serialization.json.Json {
    serializersModule = testSerializersModule
    prettyPrint = true
    classDiscriminator = "type"
}

//all passed
/**
 * A test suite to verify the declaration, registration, and serialization of [AdapterBinding].
 */
class AdapterBindingTest {

    @Test
    fun `should register and serialize a custom AdapterBinding via DSL`() {
        // 1. Define a specification that uses the custom protocol binding DSL.
        val testSpec = object : DeviceSpecification<Device>() {
            override val id = "test.spec.with.binding"

            val voltage by doubleProperty(
                read = { 12.0 },
                descriptorBuilder = {
                    description = "Input voltage"
                    modbus {
                        unitId = 5
                        registerAddress = 101
                        registerType = "inputRegister"
                    }
                }
            )

            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("Not for runtime") }
            }
        }

        // 2. Build the blueprint.
        val blueprint = compositeDeviceUnchecked(testSpec, Context("test"))

        // 3. Assertions on the blueprint structure.
        val propertySpec = blueprint.properties["voltage".asName()]
        assertNotNull(propertySpec)

        val descriptor = propertySpec.descriptor
        assertEquals(1, descriptor.bindings.size, "The property should have exactly one binding.")

        val binding = descriptor.bindings["modbus"]
        assertNotNull(binding, "A binding with key 'modbus' should exist.")
        assertIs<ModbusTestBinding>(binding, "The binding should be of the custom type ModbusTestBinding.")
        assertEquals(5, binding.unitId)
        assertEquals(101, binding.registerAddress)
        assertEquals("inputRegister", binding.registerType)

        // 4. Verify serialization and deserialization.
        val jsonString = testJson.encodeToString(PropertyDescriptor.serializer(), descriptor)
        val restoredDescriptor = testJson.decodeFromString(PropertyDescriptor.serializer(), jsonString)

        assertEquals(descriptor, restoredDescriptor, message = "The descriptor should be correctly deserialized.")
        val restoredBinding = restoredDescriptor.bindings["modbus"]
        assertIs<ModbusTestBinding>(restoredBinding)
        assertEquals(5, restoredBinding.unitId)
    }
}