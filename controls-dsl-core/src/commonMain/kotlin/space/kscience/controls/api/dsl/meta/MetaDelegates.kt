package space.kscience.controls.api.dsl.meta

import kotlinx.serialization.KSerializer
import space.kscience.controls.api.descriptors.collectionDescriptor
import space.kscience.controls.api.meta.serializableMetaConverter
import space.kscience.dataforge.meta.*
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.getIndexedList
import kotlin.reflect.KProperty

// --- Internal Helpers ---

/**
 * A private abstraction to avoid code duplication between List and Map delegates.
 * It holds the [MetaConverter] and standard [MetaDescriptor].
 */
private abstract class AbstractConvertableDelegate<T, Item>(
    val serializer: KSerializer<Item>
) : MutableMetaDelegate<T> {
    protected val itemConverter: MetaConverter<Item> by lazy {
        serializableMetaConverter(serializer)
    }
    override val descriptor: MetaDescriptor by lazy {
        collectionDescriptor(serializer)
    }
}

// --- Public DSL ---

/**
 * A delegate for a [Map] of serializable objects.
 */
public fun <T> Scheme.mapOfConvertable(
    serializer: KSerializer<T>,
    key: Name? = null,
): MutableMetaDelegate<Map<Name, T>> = object : AbstractConvertableDelegate<Map<Name, T>, T>(serializer) {

    override fun getValue(thisRef: Any?, property: KProperty<*>): Map<Name, T> {
        val nodeName = key ?: property.name.asName()
        val parentNode = meta[nodeName] ?: return emptyMap()

        return parentNode.items.mapNotNull { (token, itemMeta) ->
            val converted = itemConverter.readOrNull(itemMeta)
            if (converted != null) {
                token.asName() to converted
            } else {
                null
            }
        }.toMap()
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Map<Name, T>) {
        val nodeName = key ?: property.name.asName()
        meta[nodeName] = null

        if (value.isNotEmpty()) {
            val parentNode = meta.getOrCreate(nodeName)
            value.forEach { (childName, childValue) ->
                parentNode[childName] = itemConverter.convert(childValue)
            }
        }
    }
}

/**
 * A delegate for a [List] of serializable objects.
 */
public fun <T> Scheme.listOfConvertable(
    serializer: KSerializer<T>,
    key: Name? = null,
): MutableMetaDelegate<List<T>> = object : AbstractConvertableDelegate<List<T>, T>(serializer) {

    override fun getValue(thisRef: Any?, property: KProperty<*>): List<T> {
        val nodeName = key ?: property.name.asName()
        return meta.getIndexedList(nodeName).mapNotNull { itemMeta ->
            itemConverter.readOrNull(itemMeta)
        }
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: List<T>) {
        val nodeName = key ?: property.name.asName()
        val metaList = value.map { itemConverter.convert(it) }
        meta.setIndexed(nodeName, metaList)
    }
}