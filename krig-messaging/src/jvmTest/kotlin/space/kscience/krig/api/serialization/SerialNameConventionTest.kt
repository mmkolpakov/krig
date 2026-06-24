package space.kscience.krig.api.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.modules.SerializersModuleCollector
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageType
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regex guard over every @SerialName registered in [krigApiSerializersModule]:
 * all-lowercase, dot-scoped, internal segments kebab-cased. Wire-convention freeze.
 */
@OptIn(ExperimentalSerializationApi::class)
class SerialNameConventionTest {

    private val conventionRegex: Regex = Regex("^[a-z][a-z0-9-]*(\\.[a-z0-9][a-z0-9-]*)*$")

    @Test
    fun everyRegisteredSerialNameMatchesConvention() {
        val collector = NameCollectingCollector()
        krigApiSerializersModule.dumpTo(collector)

        val violations = collector.collected
            .filterNot { (_, serialName) -> conventionRegex.matches(serialName) }
            .map { (cls, name) -> "${cls.simpleName}: \"$name\"" }

        assertTrue(violations.isEmpty(), "convention violations: ${violations.joinToString()}")
    }

    @Test
    fun everyRegisteredSubclassHasSerialName() {
        val collector = NameCollectingCollector()
        krigApiSerializersModule.dumpTo(collector)
        val missing = collector.collected
            .filter { (cls, serialName) ->
                // kotlinx-serialization fills SerialName from the @SerialName annotation or falls back
                // to the class's FQN. An FQN means the class lacks an explicit @SerialName.
                serialName == cls.qualifiedName
            }
        if (missing.isNotEmpty()) {
            fail("missing @SerialName on ${missing.joinToString { it.first.qualifiedName ?: "?" }}")
        }
    }

    /**
     * [DeviceMessageType.all] is assembled by hand; the compiler cannot notice a missed entry when
     * a new message type is registered. This test pins the manual registry to the serializers module.
     */
    @Test
    fun deviceMessageTypeAllMatchesRegisteredSubclasses() {
        val collector = NameCollectingCollector()
        krigApiSerializersModule.dumpTo(collector)

        val registered = collector.deviceMessageNames.toSet()

        assertEquals(
            registered,
            DeviceMessageType.all,
            "DeviceMessageType.all must list exactly the @SerialName of every registered DeviceMessage subclass",
        )
    }

    private class NameCollectingCollector : SerializersModuleCollector {
        val collected: MutableList<Pair<KClass<*>, String>> = mutableListOf()
        val deviceMessageNames: MutableList<String> = mutableListOf()

        override fun <T : Any> contextual(kClass: KClass<T>, provider: (List<KSerializer<*>>) -> KSerializer<*>) {
            // no-op
        }

        override fun <Base : Any, Sub : Base> polymorphic(
            baseClass: KClass<Base>,
            actualClass: KClass<Sub>,
            actualSerializer: KSerializer<Sub>,
        ) {
            val descriptor: SerialDescriptor = actualSerializer.descriptor
            // For sealed polymorphic descriptors the serialName stands for the whole hierarchy.
            if (descriptor.kind == PolymorphicKind.SEALED) return
            collected += actualClass to descriptor.serialName
            if (baseClass == DeviceMessage::class) {
                deviceMessageNames += descriptor.serialName
            }
        }

        override fun <Base : Any> polymorphicDefaultDeserializer(
            baseClass: KClass<Base>,
            defaultDeserializerProvider: (className: String?) -> kotlinx.serialization.DeserializationStrategy<Base>?,
        ) {
            // no-op
        }

        override fun <Base : Any> polymorphicDefaultSerializer(
            baseClass: KClass<Base>,
            defaultSerializerProvider: (value: Base) -> kotlinx.serialization.SerializationStrategy<Base>?,
        ) {
            // no-op
        }
    }
}
