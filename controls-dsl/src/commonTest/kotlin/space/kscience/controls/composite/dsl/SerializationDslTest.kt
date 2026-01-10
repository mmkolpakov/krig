package space.kscience.controls.composite.dsl

import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.controls.api.descriptors.bindings
import space.kscience.controls.api.descriptors.tags
import space.kscience.controls.composite.dsl.properties.doubleProperty
import space.kscience.controls.api.identifiers.BlueprintId
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.core.contracts.DeviceBlueprint
import space.kscience.controls.connectivity.services.discovery.BlueprintRegistry
import space.kscience.controls.api.meta.AdapterBinding
import space.kscience.controls.api.meta.MemberTag
import space.kscience.controls.api.meta.ModbusTestBinding
import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.controls.api.meta.UiTestHint
import space.kscience.controls.api.descriptors.maxValue
import space.kscience.controls.api.descriptors.minValue
import space.kscience.controls.api.descriptors.unit
import space.kscience.controls.validation.DefaultValidatorsPlugin
import space.kscience.controls.validation.FeatureValidatorRegistry
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

//not all passed
class SerializationDslTest {

    private object MockBlueprintRegistry : AbstractPlugin(), BlueprintRegistry {
        override val tag: PluginTag get() = PluginTag("mock.registry")
        override fun findById(id: BlueprintId): DeviceBlueprint<*>? = null
    }

    private val testContext by lazy {
        Context("test") {
            plugin(DefaultValidatorsPlugin)
            plugin(FeatureValidatorRegistry)
            plugin(MockBlueprintRegistry)
        }
    }

    private val testSerializersModule = SerializersModule {
        include(ControlsCompositeSerializersModule)
        polymorphic(MemberTag::class) {
            subclass(UiTestHint::class)
        }
        polymorphic(AdapterBinding::class) {
            subclass(ModbusTestBinding::class)
        }
    }

    private val testJson = kotlinx.serialization.json.Json {
        serializersModule = testSerializersModule
        prettyPrint = true
        classDiscriminator = "type"
    }

    @Test
//    FAILED minValue should be deserialized, actual null
    fun `should correctly serialize and deserialize PropertyDescriptor`() {
        val spec = object : DeviceSpecification<Device>() {
            override val id = "test.serialization.success"

            val myProp by doubleProperty(
                descriptorBuilder = {
                    description = "Test property"
                    range = 0.0..100.0
                    unit = "V"
                    addTag(UiTestHint("group1", "slider"))
                    attachBinding("modbus", ModbusTestBinding(1, 100, "holding"))
                },
                read = { 0.0 }
            )
            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("Not for runtime") }
            }
        }

        val blueprint = compositeDevice(spec, testContext)
        val originalDescriptor = blueprint.properties["myProp".asName()]!!.descriptor

        val jsonString = testJson.encodeToString(PropertyDescriptor.serializer(), originalDescriptor)

        val restoredDescriptor = testJson.decodeFromString(PropertyDescriptor.serializer(), jsonString)

        assertEquals(originalDescriptor.name, restoredDescriptor.name)
        assertEquals("V", restoredDescriptor.unit)
        assertEquals(0.0, restoredDescriptor.minValue, "minValue should be deserialized")
        assertEquals(100.0, restoredDescriptor.maxValue, "maxValue should be deserialized")

        assertEquals(1, restoredDescriptor.tags.size)
        val tag = restoredDescriptor.tags.first()
        assertIs<UiTestHint>(tag)
        assertEquals("group1", tag.group)

        assertEquals(1, restoredDescriptor.bindings.size)
        val binding = restoredDescriptor.bindings["modbus"]
        assertIs<ModbusTestBinding>(binding)
        assertEquals(1, binding.unitId)
    }
}