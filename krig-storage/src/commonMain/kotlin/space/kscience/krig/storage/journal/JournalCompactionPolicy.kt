package space.kscience.krig.storage.journal

public fun interface JournalCompactionPolicy {
    public suspend fun compact(journal: EventJournal, anchor: CheckpointAnchor?)

    public companion object {
        public val Disabled: JournalCompactionPolicy = JournalCompactionPolicy { _, _ -> }

        public val TruncateCoveredCursor: JournalCompactionPolicy = JournalCompactionPolicy { journal, anchor ->
            val cursor = anchor?.coveredCursor ?: return@JournalCompactionPolicy
            journal.truncateBefore(cursor)
        }
    }
}
