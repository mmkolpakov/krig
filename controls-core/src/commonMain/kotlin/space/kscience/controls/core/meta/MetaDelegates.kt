package space.kscience.controls.core.meta

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import space.kscience.controls.core.serialization.defaultCoreJson
import space.kscience.controls.core.serialization.serializable
import space.kscience.dataforge.meta.*
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.meta.descriptors.ValueRestriction
import space.kscience.dataforge.meta.toMeta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.getIndexedList
import kotlin.reflect.KProperty

/**
 * A delegate for a map of serializable objects stored under a common meta node.
 * Allows handle collections of complex objects in a Scheme.
 *
 * @param T The type of the objects in the map.
 * @param serializer The explicit KSerializer for type T.
 * @param key An optional explicit name for the parent meta node. Defaults to the property name.
 */
public fun <T> Scheme.mapOfConvertable(
    serializer: KSerializer<T>,
    key: Name? = null,
    json: Json = defaultCoreJson
): MutableMetaDelegate<Map<Name, T>> = object : MutableMetaDelegate<Map<Name, T>> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): Map<Name, T> {
        val parentNode = meta[key ?: property.name.asName()]
        return parentNode?.items?.mapNotNull { (token, itemMeta) ->
            try {
                token.asName() to json.decodeFromJsonElement(serializer, itemMeta.toJson())
            } catch (e: Exception) {
                null
            }
        }?.toMap() ?: emptyMap()
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Map<Name, T>) {
        val parentName = key ?: property.name.asName()
        meta[parentName] = null
        val parentNode = meta.getOrCreate(parentName)
        value.forEach { (childName, childValue) ->
            val childMeta = json.encodeToJsonElement(serializer, childValue).toMeta()
            parentNode[childName] = childMeta
        }
    }

    override val descriptor: MetaDescriptor? by lazy {
        val itemDescriptor = MetaConverter.serializable(serializer).descriptor

        MetaDescriptor {
            multiple = true
            valueRestriction = ValueRestriction.ABSENT

            itemDescriptor?.let {
                attributes {
                    "@itemDescriptor" put it.toMeta()
                }
            }
        }
    }
}

/**
 * A delegate for a list of serializable objects stored as same-name-siblings under a common meta node.
 * This delegate allows managing a collection of complex objects within a [Scheme] in a type-safe manner,
 * leveraging `kotlinx.serialization` for conversion to and from `Meta`.
 *
 * @param T The type of the objects in the list. The type must be `@Serializable`.
 * @param serializer The explicit [KSerializer] for type T. This is crucial for handling generic types or
 *                   types for which a serializer cannot be inferred automatically.
 * @param key An optional explicit name for the property. If not provided, the delegated property name is used.
 * @return A [MutableMetaDelegate] that provides read-write access to a `List<T>`.
 */
public fun <T> Scheme.listOfConvertable(
    serializer: KSerializer<T>,
    key: Name? = null,
    json: Json = defaultCoreJson
): MutableMetaDelegate<List<T>> = object : MutableMetaDelegate<List<T>> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): List<T> {
        val name = key ?: property.name.asName()
        return meta.getIndexedList(name).map { itemMeta ->
            json.decodeFromJsonElement(serializer, itemMeta.toJson())
        }
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: List<T>) {
        val name = key ?: property.name.asName()
        val metaList = value.map {
            json.encodeToJsonElement(serializer, it).toMeta()
        }
        meta.setIndexed(name, metaList)
    }

    override val descriptor: MetaDescriptor? by lazy {
        val itemDescriptor = MetaConverter.serializable<T>(serializer).descriptor
        MetaDescriptor {
            multiple = true
            valueRestriction = ValueRestriction.ABSENT
            itemDescriptor?.let {
                attributes { "@itemDescriptor" put it.toMeta() }
            }
        }
    }
}

private fun MetaDescriptor.toMeta(json: Json = defaultCoreJson): Meta {
    val json = json.encodeToJsonElement(MetaDescriptor.serializer(), this)
    return json.toMeta()
}