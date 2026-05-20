package space.kscience.krig.core.pipeline

public fun PipelineBuilder.onRead(gate: OperationGate): Unit =
    gate(OperationKinds.Read, gate)

public fun PipelineBuilder.onWrite(gate: OperationGate): Unit =
    gate(OperationKinds.Write, gate)

public fun PipelineBuilder.onAction(gate: OperationGate): Unit =
    gate(OperationKinds.Action, gate)

public fun PipelineBuilder.observeRead(observer: OperationObserver): Unit =
    observe(OperationKinds.Read, observer)

public fun PipelineBuilder.observeWrite(observer: OperationObserver): Unit =
    observe(OperationKinds.Write, observer)

public fun PipelineBuilder.observeAction(observer: OperationObserver): Unit =
    observe(OperationKinds.Action, observer)
