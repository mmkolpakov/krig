@file:OptIn(
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
)

package space.kscience.krig.core.contracts

import kotlin.concurrent.atomics.AtomicInt
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.result.OperationOutcome

private val testContextSeq: AtomicInt = AtomicInt(0)

internal fun freshTestContext(prefix: String): Context =
    Context("$prefix-${testContextSeq.addAndFetch(1)}")

internal fun testRuntime(prefix: String): DeviceRuntime =
    DeviceRuntime(freshTestContext(prefix))

internal abstract class AbstractTestDevice(
    name: Name,
    runtime: DeviceRuntime = testRuntime(name.toString()),
) : AbstractDevice(name, runtime) {

    constructor(name: String, runtime: DeviceRuntime = testRuntime(name)) : this(name.asName(), runtime)

    override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        error("Unexpected read of '$propertyName' on test device '$name'")

    override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
        error("Unexpected write of '$propertyName' on test device '$name'")

    override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
        error("Unexpected execute of '$actionName' on test device '$name'")
}
