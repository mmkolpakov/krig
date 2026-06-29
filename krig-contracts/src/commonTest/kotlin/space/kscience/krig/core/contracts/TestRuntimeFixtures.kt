@file:OptIn(
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.contracts

import kotlin.concurrent.atomics.AtomicInt
import space.kscience.dataforge.context.Context

private val testContextSeq: AtomicInt = AtomicInt(0)

internal fun freshTestContext(prefix: String): Context =
    Context("$prefix-${testContextSeq.addAndFetch(1)}")

internal fun testRuntime(prefix: String): DeviceRuntime =
    DeviceRuntime(freshTestContext(prefix))
