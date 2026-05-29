package space.kscience.krig.dsl

import space.kscience.krig.api.descriptors.attributes.RetryPolicy
import space.kscience.krig.core.pipeline.OperationGate
import space.kscience.krig.core.pipeline.OperationKinds
import space.kscience.krig.core.pipeline.OperationObserver
import space.kscience.krig.core.pipeline.observeAction
import space.kscience.krig.core.pipeline.observeRead
import space.kscience.krig.core.pipeline.observeWrite
import space.kscience.krig.core.pipeline.onAction
import space.kscience.krig.core.pipeline.onRead
import space.kscience.krig.core.pipeline.onWrite

public fun <C : Any> PipelineFeatureScope<C>.onRead(gate: OperationGate): Unit =
    pipeline.onRead(gate)

public fun <C : Any> PipelineFeatureScope<C>.onWrite(gate: OperationGate): Unit =
    pipeline.onWrite(gate)

public fun <C : Any> PipelineFeatureScope<C>.onAction(gate: OperationGate): Unit =
    pipeline.onAction(gate)

public fun <C : Any> PipelineFeatureScope<C>.observeRead(observer: OperationObserver): Unit =
    pipeline.observeRead(observer)

public fun <C : Any> PipelineFeatureScope<C>.observeWrite(observer: OperationObserver): Unit =
    pipeline.observeWrite(observer)

public fun <C : Any> PipelineFeatureScope<C>.observeAction(observer: OperationObserver): Unit =
    pipeline.observeAction(observer)

public fun <C : Any> PipelineFeatureScope<C>.retryReadsWritesAndActions(retry: RetryPolicy?): Unit =
    listOf(OperationKinds.Read, OperationKinds.Write, OperationKinds.Action).forEach { kind ->
        pipeline.retry(kind, retry)
    }

public fun <C : Any> PipelineFeatureScope<C>.read(block: PipelineFeatureOperationScope.() -> Unit): Unit =
    operation(OperationKinds.Read, block)

public fun <C : Any> PipelineFeatureScope<C>.write(block: PipelineFeatureOperationScope.() -> Unit): Unit =
    operation(OperationKinds.Write, block)

public fun <C : Any> PipelineFeatureScope<C>.action(block: PipelineFeatureOperationScope.() -> Unit): Unit =
    operation(OperationKinds.Action, block)
