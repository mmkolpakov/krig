package space.kscience.controls.common.atomics

import kotlinx.atomicfu.AtomicLongArray
import kotlinx.atomicfu.getAndUpdate
import kotlinx.atomicfu.updateAndGet

/**
 * A Multiplatform implementation of an atomic array of Doubles.
 * Optimized for "Zero-Allocation".
 */
public class AtomicDoubleArray(public val size: Int) {
    private val storage: AtomicLongArray = AtomicLongArray(size)

    public operator fun get(index: Int): Double {
        return Double.fromBits(storage[index].value)
    }

    public operator fun set(index: Int, value: Double) {
        storage[index].value = value.toBits()
    }

    /**
     * Atomically sets the element at [index] to [newValue] if the current value equals [expectedValue].
     *
     * This uses [Double.toBits], so all NaN representations are treated as equal.
     * However, +0.0 and -0.0 are treated as DIFFERENT values.
     */
    public fun compareAndSet(index: Int, expectedValue: Double, newValue: Double): Boolean {
        return storage[index].compareAndSet(expectedValue.toBits(), newValue.toBits())
    }

    /**
     * Atomically adds the given [delta] to the element at [index].
     * Uses a CAS-loop.
     */
    public fun addAndGet(index: Int, delta: Double): Double {
        val newBits = storage[index].updateAndGet { currentBits ->
            (Double.fromBits(currentBits) + delta).toBits()
        }
        return Double.fromBits(newBits)
    }

    public fun getAndAdd(index: Int, delta: Double): Double {
        val oldBits = storage[index].getAndUpdate { currentBits ->
            (Double.fromBits(currentBits) + delta).toBits()
        }
        return Double.fromBits(oldBits)
    }

    public fun getAndSet(index: Int, value: Double): Double {
        return Double.fromBits(storage[index].getAndSet(value.toBits()))
    }

    /**
     * Allocating snapshot (Standard).
     */
    public fun toDoubleArray(): DoubleArray {
        return DoubleArray(size) { i -> get(i) }
    }

    /**
     * Zero-Allocation snapshot.
     * Dumps the current state into [destination] array.
     * Useful for high-frequency telemetry loops where GC pressure is critical.
     */
    public fun dumpTo(destination: DoubleArray) {
        require(destination.size >= size) { "Destination array too small" }
        for (i in 0 until size) {
            destination[i] = get(i)
        }
    }

    override fun toString(): String {
        return "AtomicDoubleArray(size=$size)"
    }
}
