package space.kscience.krig.analytics

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import space.kscience.dataforge.data.Data
import space.kscience.dataforge.data.asSequence
import space.kscience.dataforge.data.await
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
 * Composable by construction — [source] may be an [EventJournalDataSelector], a previous task, or any
 * other [DataSelector], so a fold over recorded telemetry and a fold over a derived series share one
 * task shape.
 */
public fun <A> foldTask(
    source: DataSelector<Double>,
    initial: A,
    descriptor: MetaDescriptor? = null,
    finish: (A) -> Double = { it as? Double ?: Double.NaN },
    operation: (A, Double) -> A,
): Task<Double> = Task(descriptor) {
    var accumulator = initial
    for (data in from(source).asSequence()) {
        currentCoroutineContext().ensureActive()
        accumulator = operation(accumulator, data.await())
    }
    result(Data(finish(accumulator)))
}

/**
 * A scalar reduction over the `Double` data produced by [source]: awaits every selected sample (in
 * selection order) and folds them with [reducer] into a single result datum at the empty name. For
 * reductions expressible as a streaming accumulation, prefer [foldTask], which avoids materialising
 * the full sample list.
 */
public fun reductionTask(
    source: DataSelector<Double>,
    descriptor: MetaDescriptor? = null,
    reducer: (List<Double>) -> Double,
): Task<Double> = Task(descriptor) {
    val samples = ArrayList<Double>()
    for (data in from(source).asSequence()) {
        currentCoroutineContext().ensureActive()
        samples.add(data.await())
    }
    result(Data(reducer(samples)))
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
