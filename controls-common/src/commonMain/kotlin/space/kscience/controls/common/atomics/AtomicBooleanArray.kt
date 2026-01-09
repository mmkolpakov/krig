package space.kscience.controls.common.atomics

import kotlinx.atomicfu.AtomicIntArray

/**
 * A standard, memory-efficient atomic boolean array backed by [AtomicIntArray].
 *
 * Performance Note:
 * Uses 4 bytes per boolean. This is a tradeoff:
 * - 32x more memory than bit-packing (AtomicBitmap).
 * - Faster than bit-packing (no CAS loops on shared words).
 * - Vulnerable to False Sharing if adjacent indices are mutated by different threads concurrently.
 */
public class AtomicBooleanArray(public val size: Int) {
    private val storage = AtomicIntArray(size)

    public operator fun get(index: Int): Boolean = storage[index].value != 0

    public operator fun set(index: Int, value: Boolean) {
        storage[index].value = if (value) 1 else 0
    }

    /**
     * Atomically sets the element at [index] to [value] and returns the old value.
     */
    public fun getAndSet(index: Int, value: Boolean): Boolean {
        return storage[index].getAndSet(if (value) 1 else 0) != 0
    }

    /**
     * Atomically sets the element at [index] to [newValue] if the current value equals [expect].
     * @return true if the operation was successful.
     */
    public fun compareAndSet(index: Int, expect: Boolean, newValue: Boolean): Boolean {
        val expectInt = if (expect) 1 else 0
        val updateInt = if (newValue) 1 else 0
        return storage[index].compareAndSet(expectInt, updateInt)
    }
}
