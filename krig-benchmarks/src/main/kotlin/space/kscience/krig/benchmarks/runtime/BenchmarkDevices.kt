@file:OptIn(
    space.kscience.krig.core.PerformancePitfall::class,
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
    override suspend fun readProperty(propertyName: Name): Meta = error("bench-only")
    override suspend fun writeProperty(propertyName: Name, value: Meta) = Unit
    @Suppress("RedundantNullableReturnType")
    override suspend fun execute(actionName: Name, argument: Meta?): Meta? = error("bench-only")
}
