package space.kscience.controls.core.state

import kotlinx.atomicfu.AtomicIntArray
import kotlinx.atomicfu.AtomicLongArray
import kotlinx.atomicfu.atomicArrayOfNulls
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.yield
import space.kscience.controls.api.data.DataQuality
import space.kscience.controls.api.data.Quality
import space.kscience.controls.api.data.StateValue
import space.kscience.controls.api.structure.PropertyDescriptor
import space.kscience.controls.common.atomics.AtomicDoubleArray
import space.kscience.controls.common.tokens.PropertyToken
import space.kscience.controls.common.tokens.PropertyToken.Companion.TYPE_BOOLEAN
import space.kscience.controls.common.tokens.PropertyToken.Companion.TYPE_DOUBLE
import space.kscience.controls.common.tokens.PropertyToken.Companion.TYPE_LONG
import space.kscience.controls.common.tokens.PropertyToken.Companion.TYPE_META
import space.kscience.controls.core.InternalControlsApi
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.provider.Provider
import kotlin.time.Instant

/**
 * A high-performance, concurrent, in-memory storage for device state using Hybrid Structure of Arrays (SoA).
 *
 * **Architecture:**
 * - **Fast Path (Write):** Uses atomic arrays ([AtomicDoubleArray], [AtomicLongArray]) indexed by [PropertyToken].
 *   Zero object allocation for primitive types updates.
 * - **Consistency:** Uses a Sequence Lock (SeqLock) mechanism via `versions` array to guarantee
 *   that readers always see a consistent snapshot (Value + Timestamp + Quality) without blocking writers.
 * - **Traffic Shaping:** Integrates with [QoSController] to throttle event emission based on policies.
 *
 * @param descriptors The map of property descriptors defining the topology.
 * @param qos The controller managing update emission policies (Deadband, Sampling, etc).
 */
@InternalControlsApi
public class PropertyRegistry(
    public val descriptors: Map<Name, PropertyDescriptor>,
    private val qos: QoSController
) : Provider {

    private val tokenMap: Map<Name, PropertyToken>
    private val nameMap: Map<Int, Name>
    internal val size: Int

    init {
        val tMap = HashMap<Name, PropertyToken>(descriptors.size)
        val nMap = HashMap<Int, Name>(descriptors.size)
        var indexCounter = 0

        // deterministic order for consistent token assignment
        descriptors.entries.sortedBy { it.key.toString() }.forEach { (name, descriptor) ->
            val storageType = when (descriptor.valueTypeName) {
                "Double", "Float" -> TYPE_DOUBLE
                "Int", "Long", "Short", "Byte" -> TYPE_LONG
                "Boolean" -> TYPE_BOOLEAN
                else -> TYPE_META
            }

            val token = PropertyToken.create(storageType, indexCounter)
            tMap[name] = token
            nMap[indexCounter] = name
            indexCounter++
        }

        tokenMap = tMap
        nameMap = nMap
        size = indexCounter
    }

    // --- Storage Arrays (SoA) ---

    // Value storage for floating point numbers
    private val doubles = AtomicDoubleArray(size)

    // Value storage for integers and booleans (0L/1L)
    private val longs = AtomicLongArray(size)

    // Value storage for complex objects (Meta) - Slow Path fallback
    private val metas = atomicArrayOfNulls<Meta>(size)

    // Timestamp storage (Epoch Milliseconds)
    private val timestamps = AtomicLongArray(size)

    // Quality storage (Quality Enum Ordinal)
    private val qualities = AtomicIntArray(size)

    // Version/Sequence Lock storage.
    // Even = Consistent/Idle. Odd = Write in progress.
    private val versions = AtomicLongArray(size)

    // --- Notification Bus ---

    private val _updates = MutableSharedFlow<Name>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * A hot stream of property names that have updated and passed QoS filtering.
     */
    public val updates: Flow<Name> get() = _updates.asSharedFlow()

    // --- Lookup API ---

    public fun getToken(name: Name): PropertyToken? = tokenMap[name]

    public fun getName(token: PropertyToken): Name? = nameMap[token.index]

    // --- Fast Path Write API (Primitives) ---

    /**
     * Updates a double-precision property. Safe to call from multiple threads (e.g. driver poll loop).
     *
     * @param token The property token (must be of type [TYPE_DOUBLE]).
     * @param value The new value.
     * @param qualityOrdinal The ordinal of the [Quality] enum.
     * @param timeMs The timestamp in epoch milliseconds.
     */
    public fun updateDouble(token: PropertyToken, value: Double, qualityOrdinal: Int, timeMs: Long) {
        require(token.typeOrdinal == TYPE_DOUBLE) { "Token $token type mismatch: expected DOUBLE" }
        val idx = token.index

        // 1. Acquire Seqlock (Release Fence logic via incrementAndGet)
        // Increment to odd to signal write start
        val v = versions[idx].incrementAndGet()

        // 2. Write Data & Metadata
        doubles[idx] = value
        timestamps[idx].value = timeMs
        qualities[idx].value = qualityOrdinal

        // 3. Release Seqlock (Store Fence)
        // Increment to even to signal write finish
        versions[idx].value = v + 1

        // 4. QoS Traffic Shaping
        if (qos.checkDouble(idx, value)) {
            _updates.tryEmit(nameMap[idx]!!)
        }
    }

    /**
     * Updates an integer/long property.
     */
    public fun updateLong(token: PropertyToken, value: Long, qualityOrdinal: Int, timeMs: Long) {
        require(token.typeOrdinal == TYPE_LONG) { "Token $token type mismatch: expected LONG" }
        val idx = token.index

        val v = versions[idx].incrementAndGet()
        longs[idx].value = value
        timestamps[idx].value = timeMs
        qualities[idx].value = qualityOrdinal
        versions[idx].value = v + 1

        if (qos.checkLong(idx, value)) {
            _updates.tryEmit(nameMap[idx]!!)
        }
    }

    /**
     * Updates a boolean property.
     */
    public fun updateBoolean(token: PropertyToken, value: Boolean, qualityOrdinal: Int, timeMs: Long) {
        require(token.typeOrdinal == TYPE_BOOLEAN) { "Token $token type mismatch: expected BOOLEAN" }
        val idx = token.index
        val longValue = if (value) 1L else 0L

        val v = versions[idx].incrementAndGet()
        longs[idx].value = longValue
        timestamps[idx].value = timeMs
        qualities[idx].value = qualityOrdinal
        versions[idx].value = v + 1

        if (qos.checkLong(idx, longValue)) {
            _updates.tryEmit(nameMap[idx]!!)
        }
    }

    /**
     * Updates a Meta property (Slow Path).
     */
    public fun updateMeta(token: PropertyToken, value: Meta, qualityOrdinal: Int, timeMs: Long) {
        require(token.typeOrdinal == TYPE_META) { "Token $token type mismatch: expected META" }
        val idx = token.index

        val v = versions[idx].incrementAndGet()
        metas[idx].value = value
        timestamps[idx].value = timeMs
        qualities[idx].value = qualityOrdinal
        versions[idx].value = v + 1

        if (qos.checkObject(idx)) {
            _updates.tryEmit(nameMap[idx]!!)
        }
    }

    // --- Slow Path Read API (Snapshot) ---

    /**
     * Reads a consistent snapshot of the property.
     * Uses an adaptive spin-wait strategy (Optimistic Locking) to avoid blocking.
     *
     * This method allocates a [StateValue] object.
     */
    public suspend fun <T> readSnapshot(token: PropertyToken): StateValue<T> {
        val idx = token.index
        var attempts = 0
        val spinLimit = 5
        val totalLimit = 100

        while (true) {
            // 1. Read Version (Acquire Fence)
            val v1 = versions[idx].value

            // If version is odd, a write is in progress. Wait.
            if (v1 % 2 != 0L) {
                if (attempts++ > spinLimit) yield() // Cooperative backoff
                if (attempts > totalLimit) return createFallback(idx, token.typeOrdinal)
                continue
            }

            // 2. Read Data & Metadata
            val rawValue = readRaw(idx, token.typeOrdinal)
            val time = timestamps[idx].value
            val quality = qualities[idx].value

            // 3. Read Version (Verify Fence)
            val v2 = versions[idx].value

            // If version changed (v1 != v2), data is torn/dirty. Retry.
            if (v1 == v2) {
                val qualityEnum = Quality.entries.getOrElse(quality) { Quality.UNKNOWN }
                @Suppress("UNCHECKED_CAST")
                return StateValue(
                    value = rawValue as T,
                    timestamp = Instant.fromEpochMilliseconds(time),
                    quality = DataQuality(qualityEnum)
                )
            }

            attempts++
            if (attempts > spinLimit) yield()
            if (attempts > totalLimit) return createFallback(idx, token.typeOrdinal)
        }
    }

    private fun <T> createFallback(index: Int, typeOrdinal: Int): StateValue<T> {
        val raw = readRaw(index, typeOrdinal)
        @Suppress("UNCHECKED_CAST")
        return StateValue(
            value = raw as T,
            timestamp = Instant.fromEpochMilliseconds(timestamps[index].value), // Best effort time
            quality = DataQuality(Quality.UNKNOWN, "Concurrency live-lock fallback")
        )
    }

    private fun readRaw(index: Int, type: Int): Any? = when (type) {
        TYPE_DOUBLE -> doubles[index]
        TYPE_LONG -> longs[index].value
        TYPE_BOOLEAN -> longs[index].value == 1L
        TYPE_META -> metas[index].value
        else -> null
    }

    // --- DataForge Provider Implementation ---

    override val defaultTarget: String = "property"

    /**
     * Exposes properties as DataForge content.
     * Returns a map of [PropertyDataProxy] objects that allow lazy, consistent access.
     */
    override fun content(target: String): Map<Name, Any> {
        return if (target == defaultTarget) {
            tokenMap.mapValues { (name, token) ->
                val descriptor = descriptors[name]
                    ?: error("Descriptor inconsistency: Name $name exists in tokens but not descriptors")
                PropertyDataProxy<Any?>(this, token, descriptor)
            }
        } else {
            emptyMap()
        }
    }
}