package space.kscience.krig.assembly

import space.kscience.krig.api.discovery.FeatureContributions
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.api.result.okUnit
import space.kscience.krig.core.features.Feature
import space.kscience.krig.core.features.FeatureCatalog
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.gather
import space.kscience.dataforge.meta.Meta

/**
 * Lightweight runtime sanity check for contributed [Feature]s.
 *
 * The strict `@KrigFeatureSpec.id == @SerialName` invariant is enforced at compile time
 * by the KSP `FeatureSpecContractValidator`. Runtime code intentionally avoids reflective DTO
 * inspection so the SDK contract stays multiplatform.
 */
public object FeatureContract {

    /** Cheap non-reflective check: non-blank [Feature.id] and a named [Feature.specClass]. */
    public fun validate(feature: Feature<*, *>): OperationOutcome<Unit> {
        if (feature.id.toString().isBlank()) {
            return validationFailure("feature.id must be a non-blank stable identifier.")
        }
        if (feature.specClass.simpleName == null) {
            return validationFailure(
                "feature.specClass must expose a non-null simpleName for id \"${feature.id}\".",
            )
        }
        return okUnit()
    }

    private fun validationFailure(message: String): OperationOutcome<Unit> =
        OperationOutcome.Fail(ValidationFault(details = Meta { "message" put message }))
}

/** Discovers contributed [Feature]s and fails if any contribution violates the runtime contract. */
public fun Context.gatherFeatureCatalogOutcome(): OperationOutcome<FeatureCatalog> {
    val discovered = gather<Feature<*, *>>(FeatureContributions.Target.id)
    val features = ArrayList<Feature<*, *>>(discovered.size)
    for (feature in discovered.values) {
        when (val outcome = FeatureContract.validate(feature)) {
            is OperationOutcome.Ok -> features += feature
            is OperationOutcome.Fail -> return outcome
        }
    }
    return OperationOutcome.Ok(FeatureCatalog.of(features))
}

/** Strict catalog discovery for the usual assembly path. */
public fun Context.gatherFeatureCatalog(): FeatureCatalog =
    gatherFeatureCatalogOutcome().getOrThrow()
