package space.kscience.krig.assembly

import space.kscience.krig.api.annotations.Contributes
import space.kscience.krig.api.annotations.EmissionStrategy
import space.kscience.krig.api.discovery.ActionHandlerContributions
import space.kscience.krig.api.discovery.FeatureContributions
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

/** Marks a `Feature` object for discovery via [FeatureContributions]. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Contributes(FeatureContributions::class)
public annotation class ContributesFeature

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
