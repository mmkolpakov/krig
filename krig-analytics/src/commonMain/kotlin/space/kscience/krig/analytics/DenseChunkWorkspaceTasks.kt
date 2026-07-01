package space.kscience.krig.analytics

import space.kscience.dataforge.actions.Action
import space.kscience.dataforge.data.Data
import space.kscience.dataforge.data.DataTree
import space.kscience.dataforge.data.await
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.string
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.parseAsName
import space.kscience.dataforge.workspace.DataSelector
import space.kscience.dataforge.workspace.Task
import space.kscience.dataforge.workspace.from
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.storage.timeseries.ColumnarTimeSeriesChunk
import kotlin.time.Instant

public object DenseChunkTaskMetaKeys {
    public val INPUT: Name = "krig.denseChunk.input".parseAsName()
    public val OUTPUT: Name = "krig.denseChunk.output".parseAsName()
}

public data class DenseChunkTaskConfig(
    public val input: Name = Name.EMPTY,
    public val output: Name = "metrics".parseAsName(),
)

public data class DenseChunkMetrics(
    public val seriesCount: Int,
    public val rowCount: Int,
    public val firstTime: Instant?,
    public val lastTime: Instant?,
    public val worstQuality: DataQuality,
)

public fun <C : ColumnarTimeSeriesChunk> denseChunkMetricsAction(
    config: DenseChunkTaskConfig = DenseChunkTaskConfig(),
): Action<C, DenseChunkMetrics> = Action { source, meta, _ ->
    val effective = config.withOverrides(meta)
    val chunkData = source.read(effective.input)
        ?: error("Dense chunk input '${effective.input}' is missing.")
    DataTree {
        put(effective.output, Data(meta = effective.toMeta(), dependencies = listOf(chunkData)) {
            chunkData.await().toMetrics()
        })
    }
}

public fun <C : ColumnarTimeSeriesChunk> denseChunkMetricsTask(
    source: DataSelector<C>,
    config: DenseChunkTaskConfig = DenseChunkTaskConfig(),
): Task<DenseChunkMetrics> = Task {
    denseChunkMetricsAction<C>(config).execute(from(source), taskMeta, workspace)
}

private fun DenseChunkTaskConfig.withOverrides(meta: Meta): DenseChunkTaskConfig = copy(
    input = meta.nameOrNull(DenseChunkTaskMetaKeys.INPUT) ?: input,
    output = meta.nameOrNull(DenseChunkTaskMetaKeys.OUTPUT) ?: output,
)

private fun DenseChunkTaskConfig.toMeta(): Meta = Meta {
    DenseChunkTaskMetaKeys.INPUT put input.toString()
    DenseChunkTaskMetaKeys.OUTPUT put output.toString()
}

private fun ColumnarTimeSeriesChunk.toMetrics(): DenseChunkMetrics {
    var worst = DataQuality.GOOD
    for (row in 0 until rowCount) worst = worst.combine(aggregateQualityAt(row))
    return DenseChunkMetrics(
        seriesCount = series.size,
        rowCount = rowCount,
        firstTime = times.firstOrNull(),
        lastTime = times.lastOrNull(),
        worstQuality = worst,
    )
}

private fun Meta.nameOrNull(key: Name): Name? =
    get(key)?.string?.takeIf { it.isNotBlank() }?.parseAsName()
