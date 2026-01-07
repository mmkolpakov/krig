package space.kscience.controls.common.atomics

import kotlinx.atomicfu.AtomicLongArray
import kotlinx.atomicfu.getAndUpdate
import kotlinx.atomicfu.updateAndGet

/**
 * A Multiplatform implementation of an atomic array of Doubles.
 * It uses [AtomicLongArray] under the hood, converting Doubles to raw bits.
 * This component is critical for the "Fast Path" Zero-Allocation architecture.
 */
public class AtomicDoubleArray(public val size: Int) {
    private val storage: AtomicLongArray = AtomicLongArray(size)

    public operator fun get(index: Int): Double {
        return Double.fromBits(storage[index].value)
    }

    public operator fun set(index: Int, value: Double) {
        storage[index].value = value.toRawBits()
    }

    /**
     * Atomically sets the element at [index] to [newValue] if the current value equals [expectedValue].
     * Note: This compares raw bits, so it handles NaN correctly according to bitwise equality.
     */
    public fun compareAndSet(index: Int, expectedValue: Double, newValue: Double): Boolean {
        return storage[index].compareAndSet(expectedValue.toRawBits(), newValue.toRawBits())
    }

    /**
     * Atomically adds the given [delta] to the element at [index].
     * Note: This is a CAS-loop operation, so it might retry under contention.
     */
    public fun addAndGet(index: Int, delta: Double): Double {
        val newBits = storage[index].updateAndGet { currentBits ->
            (Double.fromBits(currentBits) + delta).toRawBits()
        }
        return Double.fromBits(newBits)
    }

    public fun getAndAdd(index: Int, delta: Double): Double {
        val oldBits = storage[index].getAndUpdate { currentBits ->
            (Double.fromBits(currentBits) + delta).toRawBits()
        }
        return Double.fromBits(oldBits)
    }

    public fun getAndSet(index: Int, value: Double): Double {
        return Double.fromBits(storage[index].getAndSet(value.toRawBits()))
    }

    public fun toDoubleArray(): DoubleArray {
        return DoubleArray(size) { i -> get(i) }
    }
}