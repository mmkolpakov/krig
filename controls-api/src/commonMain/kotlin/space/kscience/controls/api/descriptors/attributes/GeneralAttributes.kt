package space.kscience.controls.api.descriptors.attributes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.controls.api.identifiers.Permission
import space.kscience.controls.api.descriptors.MemberAttribute
import space.kscience.controls.api.descriptors.MemberDescriptor
import space.kscience.controls.api.descriptors.attr
import space.kscience.controls.api.descriptors.attribute
import space.kscience.controls.api.meta.AdapterBinding
import space.kscience.controls.api.meta.MemberTag
import space.kscience.controls.api.spec.CachePolicy
import space.kscience.controls.api.spec.QoS
import space.kscience.controls.api.spec.ResourceLockSpec
import space.kscience.controls.api.spec.StreamDirection
import space.kscience.controls.api.meta.serializableToMeta
import space.kscience.dataforge.meta.Meta
import kotlin.time.Duration

/**
 * Attributes related to human-readable metadata, UI generation, and categorization.
 */
@Serializable
@SerialName("attr.metadata")
public data class MetadataAttribute(
    val description: String? = null,
    val help: String? = null,
    val group: String? = null,
    val icon: String? = null,
    val unit: String? = null,
    val widgetHint: String? = null,
    val tags: Set<MemberTag> = emptySet()
) : MemberAttribute {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * Quick access to the [MetadataAttribute] of a descriptor.
 */
public val MemberDescriptor.metadata: MetadataAttribute?
    get() = attribute()
public val MemberDescriptor.description: String? by attr(MetadataAttribute::description)
public val MemberDescriptor.unit: String? by attr(MetadataAttribute::unit)
public val MemberDescriptor.tags: Set<MemberTag>
    get() = metadata?.tags ?: emptySet()

/**
 * Attributes defining the runtime behavior, timing, and concurrency constraints.
 */
@Serializable
@SerialName("attr.behavior")
public data class BehaviorAttribute(
    // For Properties: Read/Write timeout. For Actions: logic execution timeout hint.
    val timeout: Duration? = null,
    // For Actions: hard deadline for total execution (including queue).
    val executionDeadline: Duration? = null,
    val requiredLocks: List<ResourceLockSpec> = emptyList(),
    val cachePolicy: CachePolicy? = null
) : MemberAttribute {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

public val MemberDescriptor.behavior: BehaviorAttribute?
    get() = attribute()
public val MemberDescriptor.timeout: Duration? by attr(BehaviorAttribute::timeout)
public val MemberDescriptor.requiredLocks: List<ResourceLockSpec>
    get() = behavior?.requiredLocks ?: emptyList()

/**
 * Attributes defining security and access control.
 */
@Serializable
@SerialName("attr.access")
public data class AccessAttribute(
    val readable: Boolean = true,
    val mutable: Boolean = false,
    val readPermissions: Set<Permission> = emptySet(),
    val writePermissions: Set<Permission> = emptySet()
) : MemberAttribute {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

public val MemberDescriptor.access: AccessAttribute?
    get() = attribute()

public val MemberDescriptor.readable: Boolean by attr(true, AccessAttribute::readable)
public val MemberDescriptor.mutable: Boolean by attr(false, AccessAttribute::mutable)
public val MemberDescriptor.readPermissions: Set<Permission>
    get() = access?.readPermissions ?: emptySet()
public val MemberDescriptor.writePermissions: Set<Permission>
    get() = access?.writePermissions ?: emptySet()

/**
 * Attributes for configuring telemetry, metrics, and monitoring.
 */
@Serializable
@SerialName("attr.telemetry")
public data class TelemetryAttribute(
    val metrics: Meta = Meta.EMPTY,
    val labels: Map<String, String> = emptyMap()
) : MemberAttribute {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

/**
 * Attributes for protocol-specific bindings.
 */
@Serializable
@SerialName("attr.bindings")
public data class BindingsAttribute(
    val bindings: Map<String, AdapterBinding> = emptyMap()
) : MemberAttribute {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}

public val MemberDescriptor.bindings: Map<String, AdapterBinding>
    get() = attribute<BindingsAttribute>()?.bindings ?: emptyMap()

/**
 * Specific configuration for Data Streams.
 */
@Serializable
@SerialName("attr.stream")
public data class StreamAttribute(
    val suggestedRateHz: Double? = null,
    val direction: StreamDirection? = null,
    val deliveryHint: QoS? = null
) : MemberAttribute {
    override fun toMeta(): Meta = serializableToMeta(serializer(), this)
}