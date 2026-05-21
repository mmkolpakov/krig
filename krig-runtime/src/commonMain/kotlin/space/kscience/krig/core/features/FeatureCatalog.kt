package space.kscience.krig.core.features

import space.kscience.krig.api.faults.ValidationFault
import space.kscience.krig.api.features.FeatureSpec
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.okUnit
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/** Runtime catalog of installable [Feature]s keyed by [Feature.id]. */
public class FeatureCatalog private constructor(
    private val byId: Map<Name, Feature<*, *>>,
) : Iterable<Feature<*, *>> {
    public val ids: Set<Name> get() = byId.keys

    public operator fun get(id: Name): Feature<*, *>? = byId[id]

    override fun iterator(): Iterator<Feature<*, *>> = byId.values.iterator()

    public companion object {
        public val Empty: FeatureCatalog = FeatureCatalog(emptyMap())

        public fun of(features: Iterable<Feature<*, *>>): FeatureCatalog {
            val byId = linkedMapOf<Name, Feature<*, *>>()
            for (feature in features) {
                require(feature.id.toString().isNotBlank()) { "Feature id must not be blank." }
                val previous = byId.put(feature.id, feature)
                require(previous == null) { "Duplicate Feature id '${feature.id}'." }
            }
            return FeatureCatalog(byId)
        }
    }
}

public fun featureCatalogOf(vararg features: Feature<*, *>): FeatureCatalog =
    FeatureCatalog.of(features.asIterable())

/** Data-first policy for a FeatureSpec without a matching [Feature]. */
public fun interface UnknownFeaturePolicy {
    public fun handle(id: Name, spec: FeatureSpec): OperationOutcome<Unit>

    public companion object {
        public val Skip: UnknownFeaturePolicy = UnknownFeaturePolicy { _, _ -> okUnit() }

        public val Fail: UnknownFeaturePolicy = UnknownFeaturePolicy { id, _ ->
            OperationOutcome.Fail(
                ValidationFault(
                    details = Meta {
                        "featureId" put id.toString()
                        "message" put "No Feature is registered for FeatureSpec '$id'."
                    },
                ),
            )
        }

        public fun warn(report: (Name) -> Unit): UnknownFeaturePolicy =
            UnknownFeaturePolicy { id, _ ->
                report(id)
                okUnit()
            }
    }
}
