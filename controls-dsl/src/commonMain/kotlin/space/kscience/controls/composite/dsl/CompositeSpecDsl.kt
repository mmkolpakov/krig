package space.kscience.controls.composite.dsl

import space.kscience.controls.validation.BlueprintValidationException
import space.kscience.controls.core.legacy_alpha_2.contracts.Device
import space.kscience.controls.core.legacy_alpha_2.contracts.DeviceBlueprint
import space.kscience.controls.validation.CompositeSpecValidator
import space.kscience.dataforge.context.Context
import kotlin.reflect.typeOf

/**
 * Creates a [DeviceBlueprint] using a [DeviceSpecification] object.
 * This approach is recommended for creating reusable, well-structured, and easily testable device definitions.
 * The blueprint ID is taken from the specification's `id` property, or derived from its class name if not provided.
 *
 * @param D The type of the device contract.
 * @param spec The specification object to use.
 * @param context The context for creating the blueprint.
 * @return A fully configured [DeviceBlueprint]. This function does not perform deep validation.
 */
public inline fun <reified D : Device> compositeDeviceUnchecked(
    spec: DeviceSpecification<D>,
    context: Context,
): DeviceBlueprint<D> {
    val builder = CompositeSpecBuilder<D>(context)
    builder.version = spec.version
    spec.apply(builder)
    val id = spec.id ?: spec::class.simpleName ?: error("Cannot determine blueprint ID for anonymous specification.")
    val contractFqName = typeOf<D>().toString()

    return builder.build(id, contractFqName)
}

/**
 * A "strict" factory that creates a [DeviceBlueprint] from a specification and then performs a full,
 * deep validation against the services available in the provided [Context].
 *
 * If any validation errors are found (including in nested children), this function will throw a
 * [BlueprintValidationException], failing fast. This is the recommended factory to use in production
- * code and integration tests to ensure system integrity.
 *
 * @param D The type of the device contract.
 * @param spec The specification object to use.
 * @param context The [Context] for creating the blueprint. It must contain a [BlueprintRegistry]
 *                and a [FeatureValidatorRegistry] (usually via `DefaultValidatorsPlugin`).
 * @return A fully configured and validated [DeviceBlueprint].
 * @throws BlueprintValidationException if any validation errors are discovered.
 */
public inline fun <reified D : Device> compositeDevice(
    spec: DeviceSpecification<D>,
    context: Context,
): DeviceBlueprint<D> {
    val blueprint = compositeDeviceUnchecked(spec, context)
    val errors = CompositeSpecValidator.validateWithContext(blueprint, context)
    if (errors.isNotEmpty()) {
        throw BlueprintValidationException(errors)
    }
    return blueprint
}


/**
 * Creates a [DeviceBlueprint] directly from a DSL configuration block, without needing a separate [DeviceSpecification] class.
 * This is useful for defining simple or one-off blueprints, for example in scripts or tests.
 * This function does not perform deep validation.
 *
 * @param D The type of the device contract.
 * @param id The unique identifier for this blueprint.
 * @param context The context for creating the blueprint.
 * @param block The DSL configuration block.
 * @return A fully configured [DeviceBlueprint].
 */
public inline fun <reified D : Device> deviceBlueprintUnchecked(
    id: String,
    context: Context,
    version: String = "0.1.0",
    block: CompositeSpecBuilder<D>.() -> Unit,
): DeviceBlueprint<D> {
    val contractFqName = typeOf<D>().toString()

    return CompositeSpecBuilder<D>(context).apply {
        this.version = version
        block()
    }.build(id, contractFqName)
}

/**
 * A "strict" factory that creates a [DeviceBlueprint] from a DSL block and then performs a full,
 * deep validation against the services available in the provided [Context].
 *
 * @see compositeDevice
 * @return A fully configured and validated [DeviceBlueprint].
 * @throws BlueprintValidationException if any validation errors are discovered.
 */
public inline fun <reified D : Device> deviceBlueprint(
    id: String,
    context: Context,
    version: String = "0.1.0",
    block: CompositeSpecBuilder<D>.() -> Unit,
): DeviceBlueprint<D> {
    val blueprint = deviceBlueprintUnchecked(id, context, version, block)
    val errors = CompositeSpecValidator.validateWithContext(blueprint, context)
    if (errors.isNotEmpty()) {
        throw BlueprintValidationException(errors)
    }
    return blueprint
}