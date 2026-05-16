package space.kscience.magix.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.toStringUnescaped

/**
 * A declarative filter for [MagixMessage]s, used to specify subscription criteria.
 * A `null` value for any collection means that the filter does not apply to that field (wildcard).
 *
 * @property format A collection of allowed format identifiers.
 * @property source A collection of allowed source endpoint identifiers.
 * @property target A collection of allowed target endpoint identifiers. A `null` in this collection
 *                  matches broadcast messages (where `targetEndpoint` is null).
 * @property topicPattern An optional topic pattern for content-based filtering.
 */
@Serializable
public data class MagixMessageFilter(
    val format: Collection<String>? = null,
    val source: Collection<Name>? = null,
    val target: Collection<Name?>? = null,
    val topicPattern: Name? = null,
) {
    @Transient
    private val compiledTopicPattern: CompiledTopicPattern? = topicPattern?.let(::CompiledTopicPattern)

    /**
     * Checks if a given [MagixMessage] is accepted by this filter.
     *
     * @param message The message to check.
     * @return `true` if the message passes all filter criteria, `false` otherwise.
     */
    public fun accepts(message: MagixMessage): Boolean =
        (format?.contains(message.format) ?: true)
                && (source?.contains(message.sourceEndpoint) ?: true)
                && (target?.contains(message.targetEndpoint) ?: true)
                && (compiledTopicPattern?.let { pattern -> message.topic?.let(pattern::matches) ?: false } ?: true)

    public companion object {
        /**
         * A singleton filter that accepts all messages.
         */
        public val ALL: MagixMessageFilter = MagixMessageFilter()
    }
}

/**
 * A convenience extension to apply a [MagixMessageFilter] to a [Flow] of [MagixMessage]s.
 * If the filter is [MagixMessageFilter.ALL], the original flow is returned without modification
 * to avoid unnecessary overhead.
 */
public fun Flow<MagixMessage>.filter(filter: MagixMessageFilter): Flow<MagixMessage> =
    if (filter == MagixMessageFilter.ALL) this else filter(filter::accepts)

private class CompiledTopicPattern(pattern: Name) {
    private val tokens: List<String> = tokenizePattern(pattern.toStringUnescaped())

    fun matches(topic: Name): Boolean = matchesAt(topic.toStringUnescaped(), 0, 0)

    private fun matchesAt(topic: String, topicIndex: Int, patternIndex: Int): Boolean {
        if (patternIndex == tokens.size) return nextSegment(topic, topicIndex) == NO_SEGMENT

        return when (val token = tokens[patternIndex]) {
            "**" -> {
                if (patternIndex == tokens.lastIndex) return true
                if (matchesAt(topic, topicIndex, patternIndex + 1)) return true
                var bounds = nextSegment(topic, topicIndex)
                while (bounds != NO_SEGMENT) {
                    val nextIndex = segmentEnd(bounds) + 1
                    if (matchesAt(topic, nextIndex, patternIndex + 1)) return true
                    bounds = nextSegment(topic, nextIndex)
                }
                false
            }

            "*" -> {
                val bounds = nextSegment(topic, topicIndex)
                bounds != NO_SEGMENT && matchesAt(topic, segmentEnd(bounds) + 1, patternIndex + 1)
            }

            else -> {
                val bounds = nextSegment(topic, topicIndex)
                bounds != NO_SEGMENT &&
                        segmentEquals(topic, segmentStart(bounds), segmentEnd(bounds), token) &&
                        matchesAt(topic, segmentEnd(bounds) + 1, patternIndex + 1)
            }
        }
    }
}

private const val NO_SEGMENT: Long = -1L

private fun tokenizePattern(pattern: String): List<String> = buildList {
    var index = 0
    while (true) {
        val bounds = nextSegment(pattern, index)
        if (bounds == NO_SEGMENT) break
        add(pattern.substring(segmentStart(bounds), segmentEnd(bounds)))
        index = segmentEnd(bounds) + 1
    }
}

private fun nextSegment(value: String, fromIndex: Int): Long {
    var start = fromIndex
    while (start < value.length && value[start] == '.') start++
    if (start >= value.length) return NO_SEGMENT

    var end = start
    while (end < value.length && value[end] != '.') end++
    return packSegment(start, end)
}

private fun packSegment(start: Int, end: Int): Long =
    (start.toLong() shl 32) or (end.toLong() and 0xffffffffL)

private fun segmentStart(bounds: Long): Int = (bounds shr 32).toInt()

private fun segmentEnd(bounds: Long): Int = bounds.toInt()

private fun segmentEquals(value: String, start: Int, end: Int, token: String): Boolean {
    if (end - start != token.length) return false
    for (offset in token.indices) {
        if (value[start + offset] != token[offset]) return false
    }
    return true
}
