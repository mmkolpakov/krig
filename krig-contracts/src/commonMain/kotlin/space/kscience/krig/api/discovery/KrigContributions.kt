package space.kscience.krig.api.discovery

/** `DeviceFeatureInstaller<*, *>` installers — target of `ContributesFeatureInstaller`. */
@TargetId("krig.device-feature-installer")
public object FeatureInstallerContributions {
    public val Target: ContributionTarget<Any> = ContributionTarget("krig.device-feature-installer")
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

/** `FaultRecoveryPolicy.Contribution` — target of `ContributesFaultRecovery`. */
@TargetId("krig.fault-recovery")
public object FaultRecoveryContributions {
    public val Target: ContributionTarget<Any> = ContributionTarget("krig.fault-recovery")
}
