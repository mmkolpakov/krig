package space.kscience.krig.api.discovery

/** `Feature<*, *>` objects — target of `ContributesFeature`. */
@TargetId("krig.feature")
public object FeatureContributions {
    public val Target: ContributionTarget<Any> = ContributionTarget("krig.feature")
}

/** `ProtocolEngineFactory<*, *>` — target of `ContributesProtocol`. */
@TargetId("krig.protocol")
public object ProtocolContributions {
    public val Target: ContributionTarget<Any> = ContributionTarget("krig.protocol")
}

/** `ActionSpecHandler<*>` — target of `ContributesActionHandler`. */
@TargetId("krig.action-handler")
public object ActionHandlerContributions {
    public val Target: ContributionTarget<Any> = ContributionTarget("krig.action-handler")
}
