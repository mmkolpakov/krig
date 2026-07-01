package space.kscience.krig.api.descriptors.attributes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import space.kscience.attributes.Attributes
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.descriptors.OperationAttributeEntry
import space.kscience.krig.api.descriptors.OperationDescriptor
import space.kscience.krig.api.descriptors.attr
import space.kscience.krig.api.descriptors.attribute
import space.kscience.krig.api.descriptors.operationAttributesOf
import space.kscience.krig.api.descriptors.of
import space.kscience.krig.api.expressions.Binding
import space.kscience.krig.api.expressions.NumericExpression
import space.kscience.krig.api.expressions.bindings

/**
 * Numeric deadband requested by a descriptor, subscription, or storage policy.
 */
@Serializable
public sealed interface DeadbandPolicy {
    @Serializable
    @SerialName("deadband.none")
    public data object None : DeadbandPolicy

    @Serializable
    @SerialName("deadband.absolute")
    public data class Absolute(
        public val delta: Double,
    ) : DeadbandPolicy {
        init {
            require(delta >= 0.0 && !delta.isNaN()) { "Absolute deadband delta must be non-negative: $delta" }
        }
    }

    /**
     * Fraction of the engineering span. `0.01` means one percent of the configured span.
     */
    @Serializable
    @SerialName("deadband.relative")
    public data class Relative(
        public val fraction: Double,
    ) : DeadbandPolicy {
        init {
            require(fraction >= 0.0 && !fraction.isNaN()) {
                "Relative deadband fraction must be non-negative: $fraction"
            }
        }
    }
}

/**
 * Display and alarm range for numeric engineering values.
 */
@Serializable
@SerialName("attr.engineering-range")
public data class EngineeringRangeAttribute(
    public val displayMin: Double? = null,
    public val displayMax: Double? = null,
    public val alarmLow: Double? = null,
    public val alarmHigh: Double? = null,
) {
    init {
        requireOrdered("display", displayMin, displayMax)
        requireOrdered("alarm", alarmLow, alarmHigh)
    }
}

/**
 * Default acquisition hints for a property. Per-subscription options may request stricter limits.
 */
@Serializable
@SerialName("attr.acquisition")
public data class AcquisitionPolicyAttribute(
    public val defaultMaxRateHz: Double? = null,
    public val deadband: DeadbandPolicy = DeadbandPolicy.None,
) {
    init {
        require(defaultMaxRateHz == null || (defaultMaxRateHz > 0.0 && !defaultMaxRateHz.isNaN())) {
            "defaultMaxRateHz must be positive when set: $defaultMaxRateHz"
        }
    }
}

/**
 * Message-plane intent for updates produced by this descriptor.
 */
@Serializable
public enum class MessageDeliveryClass {
    Telemetry,
    CriticalTelemetry,
    Safety,
    Audit,
}

@Serializable
@SerialName("attr.delivery-class")
public data class DeliveryClassAttribute(
    public val messageClass: MessageDeliveryClass = MessageDeliveryClass.Telemetry,
)

/**
 * Physical meaning of a measured value. Optional defaults are implied only when the descriptor does
 * not set the corresponding attribute explicitly.
 */
@Serializable
@SerialName("attr.physical-quantity")
public data class PhysicalQuantityAttribute(
    public val quantity: Name,
    public val dimension: Name? = null,
    public val defaultRange: EngineeringRangeAttribute? = null,
    public val defaultAcquisition: AcquisitionPolicyAttribute? = null,
    public val defaultDelivery: DeliveryClassAttribute? = null,
)

/**
 * Marks a property as derived from a numeric expression instead of a native backend tag.
 */
@Serializable
@SerialName("attr.virtual-property")
public data class VirtualPropertyAttribute(
    public val expression: NumericExpression,
)

public val VirtualPropertyAttribute.dependencyBindings: Set<Binding>
    get() = expression.bindings()

internal fun physicalQuantityImplications(value: PhysicalQuantityAttribute): Attributes? {
    val entries = buildList<OperationAttributeEntry<*>> {
        value.defaultRange?.let { add(OperationAttributeKeys.EngineeringRange of it) }
        value.defaultAcquisition?.let { add(OperationAttributeKeys.AcquisitionPolicy of it) }
        value.defaultDelivery?.let { add(OperationAttributeKeys.DeliveryClass of it) }
    }
    return entries.takeIf { it.isNotEmpty() }?.let { operationAttributesOf(*it.toTypedArray()) }
}

public val OperationDescriptor.physicalQuantity: PhysicalQuantityAttribute?
    get() = attribute(OperationAttributeKeys.PhysicalQuantity)

public val OperationDescriptor.engineeringRange: EngineeringRangeAttribute?
    get() = attribute(OperationAttributeKeys.EngineeringRange)

public val OperationDescriptor.displayMin: Double? by attr(
    OperationAttributeKeys.EngineeringRange,
    EngineeringRangeAttribute::displayMin,
)

public val OperationDescriptor.displayMax: Double? by attr(
    OperationAttributeKeys.EngineeringRange,
    EngineeringRangeAttribute::displayMax,
)

public val OperationDescriptor.acquisitionPolicy: AcquisitionPolicyAttribute?
    get() = attribute(OperationAttributeKeys.AcquisitionPolicy)

public val OperationDescriptor.defaultMaxRateHz: Double? by attr(
    OperationAttributeKeys.AcquisitionPolicy,
    AcquisitionPolicyAttribute::defaultMaxRateHz,
)

public val OperationDescriptor.deadbandPolicy: DeadbandPolicy
    get() = acquisitionPolicy?.deadband ?: DeadbandPolicy.None

public val OperationDescriptor.deliveryClass: DeliveryClassAttribute?
    get() = attribute(OperationAttributeKeys.DeliveryClass)

public val OperationDescriptor.messageDeliveryClass: MessageDeliveryClass
    get() = deliveryClass?.messageClass ?: MessageDeliveryClass.Telemetry

public val OperationDescriptor.virtualProperty: VirtualPropertyAttribute?
    get() = attribute(OperationAttributeKeys.VirtualProperty)

private fun requireOrdered(label: String, min: Double?, max: Double?) {
    require(min == null || !min.isNaN()) { "$label min must not be NaN" }
    require(max == null || !max.isNaN()) { "$label max must not be NaN" }
    require(min == null || max == null || min <= max) {
        "$label min must be <= max, got min=$min, max=$max"
    }
}
