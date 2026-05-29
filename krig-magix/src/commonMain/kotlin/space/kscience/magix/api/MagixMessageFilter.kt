package space.kscience.magix.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.NameToken

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
    public fun accepts(message: MagixMessage): Boolean {
        val formatMatches = format?.contains(message.format) ?: true
        val sourceMatches = source?.contains(message.sourceEndpoint) ?: true
        val targetMatches = target?.contains(message.targetEndpoint) ?: true
        val topicMatches = compiledTopicPattern?.let { pattern ->
            message.topic?.let(pattern::matches) ?: false
        } ?: true
        return formatMatches && sourceMatches && targetMatches && topicMatches
    }

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
    private val tokens = pattern.tokens

    fun matches(topic: Name): Boolean = matchesAt(topic.tokens, 0, 0)

    private fun matchesAt(topic: List<NameToken>, topicIndex: Int, patternIndex: Int): Boolean {
        if (patternIndex == tokens.size) return topicIndex == topic.size

        val token = tokens[patternIndex]
        return when {
            token.isAllWildcard -> {
                if (patternIndex == tokens.lastIndex) return true
                if (matchesAt(topic, topicIndex, patternIndex + 1)) return true
                var nextIndex = topicIndex
                while (nextIndex < topic.size) {
                    nextIndex++
                    if (matchesAt(topic, nextIndex, patternIndex + 1)) return true
                }
                false
            }

            token.isAnyWildcard -> {
                topicIndex < topic.size && matchesAt(topic, topicIndex + 1, patternIndex + 1)
            }

            else -> {
                topicIndex < topic.size &&
                        topic[topicIndex] == token &&
                        matchesAt(topic, topicIndex + 1, patternIndex + 1)
            }
        }
    }
}

private val NameToken.isAnyWildcard: Boolean
    get() = body == "*" && index == null

private val NameToken.isAllWildcard: Boolean
    get() = body == "**" && index == null
