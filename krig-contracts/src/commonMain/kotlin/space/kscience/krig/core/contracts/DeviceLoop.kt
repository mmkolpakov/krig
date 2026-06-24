package space.kscience.krig.core.contracts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * Runs a hardware/poll loop that cannot run away: it checks for cancellation every iteration, bounds
 * each iteration by an optional [iterationTimeout], and isolates non-fatal failures so a single bad
 * read degrades that tick instead of killing the loop. The loop ends only on cancellation (the
 * enclosing scope is cancelled) or an unrecoverable [Error] (e.g. OOM), which always propagate.
 *
 * Launch it on the device's `deviceScope` so it shares the device lifetime. A hung [iteration] is
 * cut off by [iterationTimeout] (the resulting [TimeoutCancellationException] is reported through
 * [onError], not propagated). On the JVM, wrap blocking native calls inside [iteration] in
 * `runInterruptible { … }` so a timeout actually interrupts the blocking call.
 *
 * @param interval delay between iterations (after each one completes); use [Duration.ZERO] for a tight loop.
 * @param iterationTimeout per-iteration budget; `null` means an iteration may take arbitrarily long.
 * @param onError invoked with any non-cancellation, non-[Error] failure (including iteration timeout).
 */
public suspend fun runDeviceLoop(
    interval: Duration,
    iterationTimeout: Duration? = null,
    onError: (Throwable) -> Unit = {},
    iteration: suspend () -> Unit,
) {
    while (currentCoroutineContext().isActive) {
        try {
            if (iterationTimeout == null) {
                iteration()
            } else {
                withTimeout(iterationTimeout) { iteration() }
            }
        } catch (e: TimeoutCancellationException) {
            // A stuck iteration is a degraded tick, not a reason to stop the loop.
            onError(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e is Error) throw e
            onError(e)
        }
        if (interval > Duration.ZERO) delay(interval)
        currentCoroutineContext().ensureActive()
    }
}
