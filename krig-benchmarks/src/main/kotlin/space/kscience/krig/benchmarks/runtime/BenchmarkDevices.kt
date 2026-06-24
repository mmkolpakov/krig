@file:OptIn(
    space.kscience.krig.core.KrigPerformancePitfall::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package space.kscience.krig.benchmarks.runtime

import kotlin.concurrent.atomics.AtomicInt
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.runtime.MutableDeviceHub
import space.kscience.krig.core.runtime.deviceHub
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.runCatchingOperation

private val contextSeq: AtomicInt = AtomicInt(0)

internal fun benchContext(prefix: String): Context =
    Context("$prefix-${contextSeq.addAndFetch(1)}")

internal fun benchHub(context: Context): MutableDeviceHub =
    deviceHub("hub", context)

internal fun benchLeaves(context: Context, count: Int): List<Pair<Name, BenchLeafDevice>> =
    (0 until count).map { index ->
        val name = "${context.name}.d$index".asName()
        name to BenchLeafDevice(name)
    }

internal class BenchLeafDevice(name: Name) : AbstractDevice(name, DeviceRuntime(benchContext(name.toString()))) {
    override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        runCatchingOperation { error("bench-only") }

    override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
        OperationOutcome.OkUnit

    override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
        runCatchingOperation { error("bench-only") }
}
