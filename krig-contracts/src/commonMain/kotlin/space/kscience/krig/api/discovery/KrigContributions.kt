package space.kscience.krig.api.discovery

/** `PipelineFeature<*, *>` objects — target of `ContributesPipelineFeature`. */
@TargetId("krig.pipeline-feature", generatedName = "PipelineFeatures")
public object PipelineFeatureContributions {
    public val Target: ContributionTarget<Any> = ContributionTarget("krig.pipeline-feature")
}

/** `ProtocolEngineFactory<*, *>` — target of `ContributesProtocol`. */
@TargetId("krig.protocol", generatedName = "Protocols")
public object ProtocolContributions {
    public val Target: ContributionTarget<Any> = ContributionTarget("krig.protocol")
}

/** `ActionSpecHandler<*>` — target of `ContributesActionHandler`. */
@TargetId("krig.action-handler", generatedName = "ActionHandlers")
public object ActionHandlerContributions {
    public val Target: ContributionTarget<Any> = ContributionTarget("krig.action-handler")
}
