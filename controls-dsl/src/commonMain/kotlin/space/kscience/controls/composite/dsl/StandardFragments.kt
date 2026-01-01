package space.kscience.controls.composite.dsl

import space.kscience.controls.composite.dsl.properties.predicate
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.fsm.IntrospectionFeature
import space.kscience.controls.fsm.guards.OperationalGuardsFeature
import space.kscience.controls.core.meta.DevicePropertySpec
import space.kscience.dataforge.misc.DFExperimental
import space.kscience.dataforge.names.asName

/**
 * A companion object for standard, reusable specification fragments.
 */
public object StandardFragments {
    public const val GET_LIFECYCLE_FSM_DIAGRAM_ACTION_NAME: String = "getLifecycleFsmDiagram"
    public const val GET_OPERATIONAL_FSM_DIAGRAM_ACTION_NAME: String = "getOperationalFsmDiagram"

    /**
     * A standard specification fragment that adds actions for introspecting
     * the device's Finite State Machines. Applying this fragment to a specification
     * will add `getLifecycleFsmDiagram` and `getOperationalFsmDiagram` actions.
     * It also declares the [IntrospectionFeature], signaling to the runtime that the device
     * supports these capabilities.
     *
     * Access to these actions should be controlled by the `device.diagnostics.read` permission.
     */
    public val WithFsmDiagrams: SpecificationFragment<Device> = SpecificationFragment { builder ->
        with(builder) {
            feature(IntrospectionFeature(providesFsmDiagrams = true))

            metaAction(
                name = GET_LIFECYCLE_FSM_DIAGRAM_ACTION_NAME.asName(),
                descriptorBuilder = {
                    description = "Returns a PlantUML string representation of the device's lifecycle FSM."
                    requires("device.diagnostics.read")
                }
            ) {
                error("FSM diagram retrieval must be handled by the runtime.")
            }

            metaAction(
                name = GET_OPERATIONAL_FSM_DIAGRAM_ACTION_NAME.asName(),
                descriptorBuilder = {
                    description = "Returns a PlantUML string representation of the device's operational FSM, if it exists."
                    requires("device.diagnostics.read")
                }
            ) {
                error("FSM diagram retrieval must be handled by the runtime.")
            }
        }
    }

    /**
     * A standard specification fragment that declares the device's intent to use operational guards.
     * This fragment simply adds the [OperationalGuardsFeature] to the blueprint. The actual guards
     * must still be defined within the `guards { ... }` block in the device's specification.
     *
     * Including this fragment makes the blueprint self-describing and allows static analysis tools
     * to recognize that the device employs guard-based logic.
     */
    public val WithGuards: SpecificationFragment<Device> = SpecificationFragment { builder ->
        if (builder._features[OperationalGuardsFeature.CAPABILITY] == null) {
            builder.feature(OperationalGuardsFeature(emptyList()))
        }
    }

    /**
     * A factory function that creates a specification fragment for a standard `isReady` predicate property.
     * This is the idiomatic way to define a device's readiness condition based on one or more of its other properties.
     *
     * The generated property is a read-only boolean property named `isReady` of kind `PREDICATE`.
     *
     * @param dependencies A vararg list of [DevicePropertySpec]s that determine the ready state.
     * @param logic A lambda that takes a list of the dependency values (in the same order) and returns `true`
     *              if the device is ready, `false` otherwise.
     * @return A [SpecificationFragment] that, when included, will add the `isReady` predicate to the blueprint.
     *
     * @sample
     * ```kotlin
     * object MyMotorSpec : DeviceSpecification<MyMotor>() {
     *     val onTarget by booleanProperty { ... }
     *     val temperature by doubleProperty { ... }
     *
     *     override fun CompositeSpecBuilder<MyMotor>.configure() {
     *         // The device is ready if it's on target and temperature is below 80.
     *         include(StandardFragments.WithReadiness(onTarget, temperature) { values ->
     *             val isOnTarget = values[0] as? Boolean ?: false
     *             val temp = values[1] as? Double ?: Double.POSITIVE_INFINITY
     *             isOnTarget && temp < 80.0
     *         })
     *         // ... other configurations
     *     }
     * }
     * ```
     */
    @OptIn(DFExperimental::class)
    public fun WithReadiness(
        vararg dependencies: DevicePropertySpec<*, *>,
        logic: (values: List<Any?>) -> Boolean,
    ): SpecificationFragment<Device> = SpecificationFragment { builder ->
        @Suppress("UNCHECKED_CAST")
        val castedDependencies = dependencies as Array<DevicePropertySpec<Device, *>>

        builder.predicate(
            name = "isReady".asName(),
            dependencies = castedDependencies,
            descriptorBuilder = {
                description = "Indicates if the device is in a ready state to perform its primary function."
            }
        ) { values ->
            logic(values)
        }
    }
}