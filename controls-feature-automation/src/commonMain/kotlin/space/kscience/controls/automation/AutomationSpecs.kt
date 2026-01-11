package space.kscience.controls.automation

import space.kscience.controls.core.features.FeatureSpec

/**
 * Typed specification for PlanExecutor capability.
 */
public object PlanExecutorSpec : FeatureSpec<PlanExecutorFeature, PlanExecutorCapability>(
    id = "feature.planExecutor",
    serializer = PlanExecutorFeature.serializer()
)

/**
 * Typed specification for TaskExecutor capability.
 */
public object TaskExecutorSpec : FeatureSpec<TaskExecutorFeature, TaskExecutorCapability>(
    id = "feature.taskExecutor",
    serializer = TaskExecutorFeature.serializer()
)