package space.kscience.krig.assembly

import space.kscience.krig.api.annotations.Contributes
import space.kscience.krig.api.annotations.EmissionStrategy
import space.kscience.krig.api.discovery.ActionHandlerContributions
import space.kscience.krig.api.discovery.FaultRecoveryContributions
import space.kscience.krig.api.discovery.FeatureInstallerContributions
import space.kscience.krig.api.discovery.ProtocolContributions

/**
 * Marks a `() -> DeviceBlueprint<*>` factory object for discovery via [BlueprintPlugin].
 * [blueprintId] becomes the `Name` key in the generated plugin's entries map; KSP enforces
 * uniqueness per module.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Contributes(BlueprintPlugin::class, strategy = EmissionStrategy.INVOKE_AS_FACTORY)
public annotation class ContributesBlueprint(public val blueprintId: String)

/** Marks a `DeviceFactory` object for discovery via [DeviceFactoryPlugin]. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Contributes(DeviceFactoryPlugin::class)
public annotation class ContributesFactory

/** Marks a `DeviceFeatureInstaller` installer object for discovery via [FeatureInstallerContributions]. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Contributes(FeatureInstallerContributions::class)
public annotation class ContributesFeatureInstaller

/** Marks a `ProtocolEngineFactory` object for discovery via [ProtocolContributions]. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Contributes(ProtocolContributions::class)
public annotation class ContributesProtocol

/** Marks an `ActionSpecHandler<S>` object for discovery via [ActionHandlerContributions]. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Contributes(ActionHandlerContributions::class)
public annotation class ContributesActionHandler

/** Marks a `FaultRecoveryPolicy.Contribution` object for discovery via [FaultRecoveryContributions]. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Contributes(FaultRecoveryContributions::class)
public annotation class ContributesFaultRecovery
