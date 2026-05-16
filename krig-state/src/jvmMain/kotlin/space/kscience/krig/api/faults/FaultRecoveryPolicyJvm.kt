package space.kscience.krig.api.faults

import java.util.ServiceLoader

/**
 * Loads every [FaultRecoveryPolicy.Contribution] advertised through
 * `META-INF/services/space.kscience.krig.api.faults.FaultRecoveryPolicy$Contribution`
 * on [classLoader] and applies them on top of [base].
 *
 * Intended for jar-drop deployments where driver modules carry their own fault taxonomies
 * (spectrometer recalibration, vacuum purge) and want the runtime to pick them up without
 * explicit wiring.
 */
public fun FaultRecoveryPolicy.Companion.fromServiceLoader(
    classLoader: ClassLoader = FaultRecoveryPolicy::class.java.classLoader,
    base: FaultRecoveryPolicy = default(),
): FaultRecoveryPolicy = base.withContributions(
    ServiceLoader.load(FaultRecoveryPolicy.Contribution::class.java, classLoader).toList(),
)
