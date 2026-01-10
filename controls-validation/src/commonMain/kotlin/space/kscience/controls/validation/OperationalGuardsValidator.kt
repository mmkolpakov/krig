package space.kscience.controls.validation

import space.kscience.controls.core.contracts.DeviceBlueprint
import space.kscience.controls.connectivity.services.discovery.BlueprintRegistry
import space.kscience.controls.core.features.GuardSpec
import space.kscience.controls.fsm.guards.ValueChangeGuardSpec
import space.kscience.controls.api.descriptors.PropertyKind
import space.kscience.controls.fsm.guards.OperationalGuardsFeature
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta

/**
 * A validator for the [OperationalGuardsFeature]. It checks if the predicate properties
 * referenced by the guards actually exist on the blueprint and are of the correct kind (PREDICATE).
 */
public class OperationalGuardsValidator : FeatureValidator<OperationalGuardsFeature> {
    override fun validate(
        blueprint: DeviceBlueprint<*>,
        feature: OperationalGuardsFeature,
        registry: BlueprintRegistry,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        feature.guards.forEach { guard: GuardSpec ->
            val predicateName = when (guard) {
                is TimedPredicateGuardSpec -> guard.predicateName
                is ValueChangeGuardSpec -> guard.propertyName
                else -> { TODO() }
            }

            val predicateSpec = blueprint.properties[predicateName]
            if (predicateSpec == null) {
                errors.add(
                    ValidationError.InvalidGuard(
                        blueprint.id.value,
                        predicateName,
                        "Predicate property not found on the blueprint."
                    )
                )
            } else if (guard is TimedPredicateGuardSpec && predicateSpec.descriptor.kind != PropertyKind.PREDICATE) {
                // Only TimedPredicateGuardSpec strictly requires a PREDICATE kind.
                errors.add(
                    ValidationError.InvalidGuard(
                        blueprint.id.value,
                        predicateName,
                        "Property used in a 'whenTrue' guard is not of kind PREDICATE."
                    )
                )
            }
        }
        return errors
    }
}

/**
 * The factory for [OperationalGuardsValidator].
 */
internal object OperationalGuardsValidatorFactory : FeatureValidatorFactory {
    override val capability: String get() = OperationalGuardsFeature.CAPABILITY

    override fun build(context: Context, meta: Meta): FeatureValidator<*> = OperationalGuardsValidator()
}