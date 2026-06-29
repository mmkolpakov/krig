package space.kscience.krig.flow

import space.kscience.dataforge.names.Name
import kotlin.jvm.JvmInline

/** Stable domain unit identifier used by flow ports. */
@JvmInline
public value class FlowUnit(public val id: String) {
    init {
        require(id.isNotBlank()) { "Flow unit id must not be blank" }
    }

    override fun toString(): String = id
}

/** Common unit identifiers for demos and tests; domains may define their own [FlowUnit] values. */
public object FlowUnits {
    public val Generic: FlowUnit = FlowUnit("generic")
    public val Kilogram: FlowUnit = FlowUnit("kg")
    public val Liter: FlowUnit = FlowUnit("l")
}

/** Non-negative finite amount of a material in a [FlowUnit]. */
@JvmInline
public value class FlowAmount(public val value: Double) : Comparable<FlowAmount> {
    init {
        require(value.isFinite()) { "Flow amount must be finite" }
        require(value >= 0.0) { "Flow amount must be non-negative" }
    }

    override fun compareTo(other: FlowAmount): Int = value.compareTo(other.value)

    public companion object {
        public val ZERO: FlowAmount = FlowAmount(0.0)
    }
}

/** Non-negative finite amount transferred per second. */
@JvmInline
public value class FlowRate(public val valuePerSecond: Double) : Comparable<FlowRate> {
    init {
        require(valuePerSecond.isFinite()) { "Flow rate must be finite" }
        require(valuePerSecond >= 0.0) { "Flow rate must be non-negative" }
    }

    override fun compareTo(other: FlowRate): Int = valuePerSecond.compareTo(other.valuePerSecond)

    public companion object {
        public val ZERO: FlowRate = FlowRate(0.0)
    }
}

/** Non-negative finite multiplier for conversion yields and split shares. */
@JvmInline
public value class FlowRatio(public val value: Double) : Comparable<FlowRatio> {
    init {
        require(value.isFinite()) { "Flow ratio must be finite" }
        require(value >= 0.0) { "Flow ratio must be non-negative" }
    }

    override fun compareTo(other: FlowRatio): Int = value.compareTo(other.value)

    public companion object {
        public val ZERO: FlowRatio = FlowRatio(0.0)
        public val ONE: FlowRatio = FlowRatio(1.0)
    }
}

/** A typed input or output of a flow block. */
public data class FlowPort(
    public val id: Name,
    public val unit: FlowUnit,
)

/** Reference to a [FlowPort] on a block. */
public data class FlowEndpoint(
    public val blockId: Name,
    public val portId: Name,
)

/** Directed transfer edge between two flow ports. */
public data class FlowConnection(
    public val source: FlowEndpoint,
    public val target: FlowEndpoint,
)
