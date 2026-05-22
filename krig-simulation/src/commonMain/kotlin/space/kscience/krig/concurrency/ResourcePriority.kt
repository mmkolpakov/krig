package space.kscience.krig.concurrency

import kotlin.jvm.JvmInline

/** Resource priority. Higher levels win; ties keep arrival order. */
@JvmInline
public value class ResourcePriority(public val level: Int) : Comparable<ResourcePriority> {
    override fun compareTo(other: ResourcePriority): Int = level.compareTo(other.level)

    override fun toString(): String = "ResourcePriority($level)"

    public companion object {
        public val Lowest: ResourcePriority = ResourcePriority(-2)
        public val Low: ResourcePriority = ResourcePriority(-1)
        public val Normal: ResourcePriority = ResourcePriority(0)
        public val High: ResourcePriority = ResourcePriority(1)
        public val Critical: ResourcePriority = ResourcePriority(2)

        public val DEFAULT: ResourcePriority = Normal
    }
}
