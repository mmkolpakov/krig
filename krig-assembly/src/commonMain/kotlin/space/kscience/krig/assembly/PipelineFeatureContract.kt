package space.kscience.krig.assembly

import space.kscience.krig.api.discovery.PipelineFeatureContributions
import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.faults.faultDetails
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.api.result.okUnit
import space.kscience.krig.core.features.PipelineFeature
import space.kscience.krig.core.features.PipelineFeatureCatalog
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.gather

/** Runtime checks for contributed [PipelineFeature]s that do not require DTO reflection. */
public object PipelineFeatureContract {

    /** Checks that the feature has a stable id and a named spec class. */
    public fun validate(pipelineFeature: PipelineFeature<*, *>): OperationOutcome<Unit> {
        if (pipelineFeature.id.toString().isBlank()) {
            return validationFailure("PipelineFeature.id must be a non-blank stable identifier.")
        }
        if (pipelineFeature.specClass.simpleName == null) {
            return validationFailure(
                "PipelineFeature.specClass must expose a non-null simpleName for id \"${pipelineFeature.id}\".",
            )
        }
        return okUnit()
    }

    private fun validationFailure(message: String): OperationOutcome<Unit> =
        OperationOutcome.Fail(ValidationFault(details = faultDetails(message)))
}

/** Discovers contributed [PipelineFeature]s and validates each contribution. */
public fun Context.gatherPipelineFeatureCatalogOutcome(): OperationOutcome<PipelineFeatureCatalog> {
    val discovered = gather<PipelineFeature<*, *>>(PipelineFeatureContributions.Target.id)
    val features = ArrayList<PipelineFeature<*, *>>(discovered.size)
    for (pipelineFeature in discovered.values) {
        when (val outcome = PipelineFeatureContract.validate(pipelineFeature)) {
            is OperationOutcome.Ok -> features += pipelineFeature
            is OperationOutcome.Fail -> return outcome
        }
    }
    return OperationOutcome.Ok(PipelineFeatureCatalog.of(features))
}

/** Discovers contributed [PipelineFeature]s or throws on validation failure. */
public fun Context.gatherPipelineFeatureCatalog(): PipelineFeatureCatalog =
    gatherPipelineFeatureCatalogOutcome().getOrThrow()
