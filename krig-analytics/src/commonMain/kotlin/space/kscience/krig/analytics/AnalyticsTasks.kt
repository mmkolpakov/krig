package space.kscience.krig.analytics

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import space.kscience.dataforge.data.reduceToData
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.workspace.DataSelector
import space.kscience.dataforge.workspace.Task
import space.kscience.dataforge.workspace.from
import space.kscience.dataforge.workspace.result

/**
 * Streaming scalar fold over the `Double` data produced by [source]: awaits each selected sample in
 * selection order and accumulates it with [operation], starting from [initial], into a single result
 * datum at the empty name. No intermediate `List<Double>` is materialised, and the loop cooperates
 * with cancellation, so a long recorded window does not pin all samples in memory or block a
 * cancelled scope.
 *
 * Composable by construction — [source] may be an [EventJournalSnapshotDataSelector],
 * [EventJournalReplayWindowDataSelector], a previous task, or any other [DataSelector], so a fold
 * over recorded telemetry and a fold over a derived series share one task shape.
 */
public fun <A : Any> foldTask(
    source: DataSelector<Double>,
    initial: A,
    descriptor: MetaDescriptor? = null,
    finish: (A) -> Double = { it as? Double ?: Double.NaN },
    operation: (A, Double) -> A,
): Task<Double> = Task(descriptor) {
    val foldContext = workspace.context.coroutineContext
    val folded = from(source).reduceToData<Double, Double>(coroutineContext = foldContext) { samples ->
        var accumulator = initial
        for (sample in samples) {
            currentCoroutineContext().ensureActive()
            accumulator = operation(accumulator, sample.value)
        }
        finish(accumulator)
    }
    result(folded)
}

/**
 * A scalar reduction over the `Double` data produced by [source]: awaits every selected sample (in
 * selection order) and materialises the selected snapshot as a [List] before calling [reducer]. For
 * reductions expressible as a streaming accumulation, prefer [foldTask], [meanTask] or [sumTask],
 * which preserve DataForge's lazy data dependency graph without building an intermediate list.
 */
public fun snapshotReductionTask(
    source: DataSelector<Double>,
    descriptor: MetaDescriptor? = null,
    reducer: (List<Double>) -> Double,
): Task<Double> = Task(descriptor) {
    val reduceContext = workspace.context.coroutineContext
    val reduced = from(source).reduceToData<Double, Double>(coroutineContext = reduceContext) { samples ->
        currentCoroutineContext().ensureActive()
        reducer(samples.map { it.value })
    }
    result(reduced)
}

/** Arithmetic mean of [source]; an empty selection reduces to `Double.NaN`. Streaming (sum/count fold). */
public fun meanTask(source: DataSelector<Double>): Task<Double> =
    foldTask(
        source = source,
        initial = 0.0 to 0L,
        finish = { (sum, count) -> if (count == 0L) Double.NaN else sum / count },
    ) { (sum, count), sample -> sum + sample to count + 1 }

/** Sum of [source]; an empty selection reduces to `0.0`. Streaming fold. */
public fun sumTask(source: DataSelector<Double>): Task<Double> =
    foldTask(source = source, initial = 0.0, finish = { it }) { acc, sample -> acc + sample }
