package space.kscience.krig.assembly

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.faults.GenericOperationFault
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.faults.OperationFaultTypes
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.core.contracts.BackendEnvironment
import space.kscience.krig.core.contracts.BoundDeviceBackend
import space.kscience.krig.core.contracts.typed.TypedAction
import space.kscience.krig.core.contracts.typed.TypedDeviceBackend
import space.kscience.krig.core.contracts.typed.TypedObservedReader
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.contracts.typed.TypedWriter
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract
import space.kscience.krig.core.meta.devicePropertyContract
import space.kscience.krig.core.meta.mutableDevicePropertyContract
import kotlin.time.Clock

/**
 * Sink for tag-backed mutable properties.
 *
 * Acquisition sources are read-only by default. Provide this explicitly when a tag table is backed
 * by a writable cache, simulator, or protocol adapter.
 */
public fun interface TagTableSampleWriter {
    public suspend fun write(tag: AcquisitionTagSpec, observed: ObservedValue<Meta?>): OperationOutcome<Unit>
}

/**
 * Projects a [TagTable] as a KRig typed backend.
 *
 * Raw backend reads preserve the dynamic `Meta`/observed path. Consumers with a known
 * [DevicePropertyContract] can request typed readers by name and matching [TypeId][space.kscience.krig.api.descriptors.TypeId].
 */
public fun TagTable.toBackend(
    reader: AcquisitionSourceReader,
    writer: TagTableSampleWriter? = null,
    propertyKind: PropertyKind = PropertyKind.MEASURED,
    clock: Clock = Clock.System,
): TypedDeviceBackend = TagTableBackend(this, reader, writer, propertyKind, clock)

private class TagTableBackend(
    table: TagTable,
    private val sourceReader: AcquisitionSourceReader,
    private val sampleWriter: TagTableSampleWriter?,
    private val propertyKind: PropertyKind,
    private val clock: Clock,
) : TypedDeviceBackend {
    private val tagsById: Map<Name, AcquisitionTagSpec> = table.tags.associateBy { it.id }
    private val sourcesById: Map<Name, AcquisitionSourceSpec> = table.sources.associateBy { it.id }
    private val metaSpecs: Map<Name, DevicePropertyContract<Meta>> = table.tags.associate { tag ->
        tag.id to devicePropertyContract(
            name = tag.id,
            converter = MetaConverter.meta,
            kind = propertyKind,
            valueTypeId = tag.valueTypeId,
        )
    }
    private val mutableMetaSpecs: Map<Name, MutableDevicePropertyContract<Meta>> = table.tags.associate { tag ->
        tag.id to mutableDevicePropertyContract(
            name = tag.id,
            converter = MetaConverter.meta,
            kind = propertyKind,
            valueTypeId = tag.valueTypeId,
        )
    }

    override fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T>? {
        val tag = compatibleTag(spec) ?: return null
        return TypedReader {
            val observed = readTag(tag).getOrThrow()
            val value = observed.value ?: error("Tag '${tag.id}' has no usable value: quality=${observed.quality}")
            spec.converter.read(value)
        }
    }

    override fun <T> observedReader(spec: DevicePropertyContract<T>): TypedObservedReader<T>? {
        val tag = compatibleTag(spec) ?: return null
        return TypedObservedReader {
            readTag(tag).getOrThrow().map { meta -> meta?.let(spec.converter::read) }
        }
    }

    override fun <T> writer(spec: MutableDevicePropertyContract<T>): TypedWriter<T>? {
        val tag = compatibleTag(spec) ?: return null
        val writer = sampleWriter ?: return null
        return TypedWriter { value ->
            writer.write(tag, ObservedValue(spec.converter.convert(value), clock.now(), DataQuality.GOOD)).getOrThrow()
        }
    }

    override fun <I, O> action(spec: DeviceActionContract<I, O>): TypedAction<I, O>? = null

    override fun propertySpec(name: Name): DevicePropertyContract<*>? =
        if (sampleWriter == null) metaSpecs[name] else mutableMetaSpecs[name]

    override fun actionSpec(name: Name): DeviceActionContract<*, *>? = null

    override fun propertySpecs(): Map<Name, DevicePropertyContract<*>> =
        if (sampleWriter == null) metaSpecs else mutableMetaSpecs

    override fun actionSpecs(): Map<Name, DeviceActionContract<*, *>> = emptyMap()

    override fun bind(environment: BackendEnvironment): BoundDeviceBackend = BoundTagTableBackend(environment)

    override fun close() = Unit

    private inner class BoundTagTableBackend(
        override val environment: BackendEnvironment,
    ) : BoundDeviceBackend {
        override suspend fun read(property: PropertyDescriptor): Meta {
            val observed = readObserved(property)
            return observed.value ?: unsupported("Observed tag-backed property '${property.name}' has no Meta value.")
        }

        override suspend fun readObserved(property: PropertyDescriptor): ObservedValue<Meta?> {
            val tag = compatibleTag(property) ?: unknownProperty(property.name)
            return readTag(tag).getOrThrow()
        }

        override suspend fun readBatchObserved(
            properties: Collection<PropertyDescriptor>,
        ): Map<Name, OperationOutcome<ObservedValue<Meta?>>> {
            val result = LinkedHashMap<Name, OperationOutcome<ObservedValue<Meta?>>>()
            val known = properties.mapNotNull { property ->
                val tag = compatibleTag(property)
                if (tag == null) {
                    result[property.name] = unknownPropertyOutcome(property.name)
                    null
                } else {
                    property.name to tag
                }
            }
            for ((_, sourceTags) in known.map { it.second }.groupBy { it.sourceId }) {
                val source = sourcesById.getValue(sourceTags.first().sourceId)
                result.putAll(sourceReader.readSourceCatching(source, sourceTags))
            }
            return properties.associate { property ->
                property.name to result.getValue(property.name)
            }
        }

        override suspend fun write(property: PropertyDescriptor, value: Meta) {
            val writer = sampleWriter
                ?: unsupported("Tag-backed property '${property.name}' is read-only.")
            val tag = compatibleTag(property) ?: unknownProperty(property.name)
            writer.write(tag, ObservedValue(value, environment.clock.now(), DataQuality.GOOD)).getOrThrow()
        }

        override suspend fun execute(action: ActionDescriptor, argument: Meta?): Meta =
            unknownAction(action.name)

        override fun close() = Unit
    }

    private suspend fun readTag(tag: AcquisitionTagSpec): OperationOutcome<ObservedValue<Meta?>> {
        val source = sourcesById.getValue(tag.sourceId)
        return sourceReader.readSourceCatching(source, listOf(tag))[tag.id]
            ?: OperationOutcome.Fail(GenericOperationFault(message = "No acquisition result for tag '${tag.id}'."))
    }

    private fun compatibleTag(spec: DevicePropertyContract<*>): AcquisitionTagSpec? {
        val tag = tagsById[spec.name] ?: return null
        check(tag.valueTypeId == spec.descriptor.valueTypeId) {
            "Tag '${tag.id}' declares type '${tag.valueTypeId}', but requested '${spec.descriptor.valueTypeId}'."
        }
        return tag
    }

    private fun compatibleTag(property: PropertyDescriptor): AcquisitionTagSpec? {
        val tag = tagsById[property.name] ?: return null
        return if (tag.valueTypeId == property.valueTypeId) tag else null
    }

    private fun unknownPropertyOutcome(name: Name): OperationOutcome.Fail = OperationOutcome.Fail(
        GenericOperationFault(
            faultType = OperationFaultTypes.UnknownProperty,
            message = "Unknown tag-backed property '$name'.",
        ),
    )

    private fun unknownProperty(name: Name): Nothing =
        throw OperationFaultException(unknownPropertyOutcome(name).fault)

    private fun unknownAction(name: Name): Nothing =
        throw OperationFaultException(
            GenericOperationFault(
                faultType = OperationFaultTypes.UnknownAction,
                message = "Tag table backend has no action '$name'.",
            ),
        )

    private fun unsupported(message: String): Nothing =
        throw OperationFaultException(
            GenericOperationFault(
                faultType = OperationFaultTypes.UnsupportedValue,
                message = message,
            ),
        )
}
