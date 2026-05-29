package space.kscience.krig.core.features

import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.faults.OperationFaultDetails
import space.kscience.krig.api.features.PipelineFeatureSpec
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.okUnit
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/** Runtime catalog of installable [PipelineFeature]s keyed by [PipelineFeature.id]. */
public class PipelineFeatureCatalog private constructor(
    private val byId: Map<Name, PipelineFeature<*, *>>,
) : Iterable<PipelineFeature<*, *>> {
    public val ids: Set<Name> get() = byId.keys

    public operator fun get(id: Name): PipelineFeature<*, *>? = byId[id]

    override fun iterator(): Iterator<PipelineFeature<*, *>> = byId.values.iterator()

    public companion object {
        public val Empty: PipelineFeatureCatalog = PipelineFeatureCatalog(emptyMap())

        public fun of(features: Iterable<PipelineFeature<*, *>>): PipelineFeatureCatalog {
            val byId = linkedMapOf<Name, PipelineFeature<*, *>>()
            for (pipelineFeature in features) {
                require(pipelineFeature.id.toString().isNotBlank()) { "PipelineFeature id must not be blank." }
                val previous = byId.put(pipelineFeature.id, pipelineFeature)
                require(previous == null) { "Duplicate PipelineFeature id '${pipelineFeature.id}'." }
            }
            return PipelineFeatureCatalog(byId)
        }
    }
}

public fun pipelineFeatureCatalogOf(vararg features: PipelineFeature<*, *>): PipelineFeatureCatalog =
    PipelineFeatureCatalog.of(features.asIterable())

/** Data-first policy for a PipelineFeatureSpec without a matching [PipelineFeature]. */
public fun interface UnknownPipelineFeaturePolicy {
    public fun handle(id: Name, spec: PipelineFeatureSpec): OperationOutcome<Unit>

    public companion object {
        public val Skip: UnknownPipelineFeaturePolicy = UnknownPipelineFeaturePolicy { _, _ -> okUnit() }

        public val Fail: UnknownPipelineFeaturePolicy = UnknownPipelineFeaturePolicy { id, _ ->
            OperationOutcome.Fail(
                ValidationFault(
                    details = Meta {
                        "featureId" put id.toString()
                        OperationFaultDetails.MESSAGE put "No PipelineFeature is registered for PipelineFeatureSpec '$id'."
                    },
                ),
            )
        }

        public fun warn(report: (Name) -> Unit): UnknownPipelineFeaturePolicy =
            UnknownPipelineFeaturePolicy { id, _ ->
                report(id)
                okUnit()
            }
    }
}
