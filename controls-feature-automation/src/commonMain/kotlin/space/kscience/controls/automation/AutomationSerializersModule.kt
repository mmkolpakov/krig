package space.kscience.controls.automation

import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.controls.api.structure.ActionLogicSource
import space.kscience.controls.api.features.Feature

public val automationSerializersModule: SerializersModule = SerializersModule {
    polymorphic(ActionLogicSource::class) {
        subclass(PlanLogic::class)
    }

    polymorphic(Feature::class) {
        subclass(PlanExecutorFeature::class)
        subclass(TaskExecutorFeature::class)
    }

    polymorphic(ActionSpec::class) {
        subclass(AttachActionSpec::class)
        subclass(DetachActionSpec::class)
        subclass(StartActionSpec::class)
        subclass(StopActionSpec::class)
        subclass(WritePropertyActionSpec::class)
        subclass(SequenceActionSpec::class)
        subclass(ParallelActionSpec::class)
        subclass(DelayActionSpec::class)
        subclass(AwaitPredicateActionSpec::class)
        subclass(InvokeActionSpec::class)
        subclass(AwaitSignalActionSpec::class)
        subclass(ConditionalActionSpec::class)
        subclass(LoopActionSpec::class)
        subclass(RunWorkspaceTaskSpec::class)
    }
}