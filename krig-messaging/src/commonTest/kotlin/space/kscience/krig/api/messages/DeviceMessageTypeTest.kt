package space.kscience.krig.api.messages

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.modules.SerializersModuleCollector
import space.kscience.krig.api.serialization.krigApiSerializersModule
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
class DeviceMessageTypeTest {
    private fun registeredDeviceMessageTypeNames(): Set<String> {
        val names = mutableSetOf<String>()

        krigApiSerializersModule.dumpTo(object : SerializersModuleCollector {
            override fun <T : Any> contextual(
                kClass: KClass<T>,
                provider: (typeArgumentsSerializers: List<KSerializer<*>>) -> KSerializer<*>,
            ) = Unit

            override fun <Base : Any, Sub : Base> polymorphic(
                baseClass: KClass<Base>,
                actualClass: KClass<Sub>,
                actualSerializer: KSerializer<Sub>,
            ) {
                if (baseClass == DeviceMessage::class) {
                    names += actualSerializer.descriptor.serialName
                }
            }

            override fun <Base : Any> polymorphicDefaultSerializer(
                baseClass: KClass<Base>,
                defaultSerializerProvider: (value: Base) -> SerializationStrategy<Base>?,
            ) = Unit

            override fun <Base : Any> polymorphicDefaultDeserializer(
                baseClass: KClass<Base>,
                defaultDeserializerProvider: (className: String?) -> DeserializationStrategy<Base>?,
            ) = Unit
        })

        return names
    }

    @Test
    fun allDeviceMessageTypeNamesMatchRegisteredSerializers() {
        assertEquals(registeredDeviceMessageTypeNames(), DeviceMessageType.all)
    }

    @Test
    fun allDeviceMessageTypeNamesUseDottedLowercaseWireFormat() {
        val pattern = Regex("[a-z]+(\\.[a-z0-9-]+)+")

        DeviceMessageType.all.forEach { type ->
            assertTrue(pattern.matches(type), "Unexpected DeviceMessage type name: $type")
        }
    }
}
