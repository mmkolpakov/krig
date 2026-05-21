package space.kscience.krig.storage.profile

import kotlin.jvm.JvmInline
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName

/** Open storage-shape key. Integrations may add their own profiles. */
@JvmInline
public value class StorageProfile(public val name: Name) {
    override fun toString(): String = name.toString()
}

public object StorageProfiles {
    public val JournalFull: StorageProfile = StorageProfile("journal.full".parseAsName())
    public val JournalCompact: StorageProfile = StorageProfile("journal.compact".parseAsName())
    public val TimeSeriesRows: StorageProfile = StorageProfile("timeseries.rows".parseAsName())
    public val TimeSeriesChunks: StorageProfile = StorageProfile("timeseries.chunks".parseAsName())
    public val TimeSeriesDense: StorageProfile = StorageProfile("timeseries.dense".parseAsName())
    public val TimeSeriesDeadband: StorageProfile = StorageProfile("timeseries.deadband".parseAsName())
}
