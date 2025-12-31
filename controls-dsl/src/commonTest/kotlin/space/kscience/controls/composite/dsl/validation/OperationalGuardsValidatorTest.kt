@file:OptIn(DFExperimental::class)

package space.kscience.controls.composite.dsl.validation

import kotlinx.serialization.Serializable
import ru.nsk.kstatemachine.event.Event
import space.kscience.controls.composite.dsl.CompositeSpecBuilder
import space.kscience.controls.composite.dsl.DeviceSpecification
import space.kscience.controls.composite.dsl.compositeDeviceUnchecked
import space.kscience.controls.composite.dsl.guards.guards
import space.kscience.controls.composite.dsl.guards.post
import space.kscience.controls.composite.dsl.properties.booleanProperty
import space.kscience.controls.composite.dsl.properties.predicate
import space.kscience.controls.validation.ValidationError
import space.kscience.controls.core.identifiers.BlueprintId
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.core.contracts.DeviceBlueprint
import space.kscience.controls.services.discovery.BlueprintRegistry
import space.kscience.controls.fsm.guards.OperationalGuardsFeature
import space.kscience.controls.validation.TimedPredicateGuardSpec
import space.kscience.controls.validation.OperationalGuardsValidator
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Global
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.misc.DFExperimental
import space.kscience.dataforge.names.asName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Serializable
private object DummyEvent : Event

//all passed
/**
 * Unit tests for [OperationalGuardsValidator].
 * These tests are self-contained and do not require the `runtime` module.
 */
class OperationalGuardsValidatorTest {

    /**
     * A minimal, self-contained mock implementation of [BlueprintRegistry] for testing purposes.
     * It does not need to find any blueprints for these specific tests.
     */
    private object MockBlueprintRegistry : AbstractPlugin(), BlueprintRegistry {
        override val tag: PluginTag get() = PluginTag("mock.registry")
        override fun findById(id: BlueprintId): DeviceBlueprint<*>? = null
    }

    private val validator = OperationalGuardsValidator()

    private object TestSpecWithValidGuard : DeviceSpecification<Device>() {
        override val id = "validation.guards.valid"
        val isReady by predicate { true }
        override fun CompositeSpecBuilder<Device>.configure() {
            driver { _, _ -> error("Not for runtime") }
            guards {
                whenTrue(isReady).forAtLeast(1.seconds).post<DummyEvent>()
            }
        }
    }

    private object TestSpecWithInvalidGuardKind : DeviceSpecification<Device>() {
        override val id = "validation.guards.invalid.kind"
        val notAPredicate by booleanProperty(read = { true })
        override fun CompositeSpecBuilder<Device>.configure() {
            driver { _, _ -> error("Not for runtime") }
            // Manually create the feature because the DSL would (correctly) reject this.
            feature(
                OperationalGuardsFeature(
                    listOf(
                        TimedPredicateGuardSpec(
                            predicateName = "notAPredicate".asName(),
                            holdFor = 1.seconds,
                            postEventSerialName = "DummyEvent"
                        )
                    )
                )
            )
        }
    }

    private object TestSpecWithMissingPredicate : DeviceSpecification<Device>() {
        override val id = "validation.guards.missing.predicate"
        override fun CompositeSpecBuilder<Device>.configure() {
            driver { _, _ -> error("Not for runtime") }
            // Manually create the feature with a reference to a non-existent property.
            feature(
                OperationalGuardsFeature(
                    listOf(
                        TimedPredicateGuardSpec(
                            predicateName = "nonExistentPredicate".asName(),
                            holdFor = 1.seconds,
                            postEventSerialName = "DummyEvent"
                        )
                    )
                )
            )
        }
    }

    @Test
    fun `should return no errors for a valid guard`() {
        val blueprint = compositeDeviceUnchecked(TestSpecWithValidGuard, Global)
        val feature = blueprint.features[OperationalGuardsFeature.CAPABILITY] as OperationalGuardsFeature
        val errors = validator.validate(blueprint, feature, MockBlueprintRegistry)
        assertTrue(errors.isEmpty(), "A valid guard should produce no validation errors. Got: $errors")
    }

    @Test
    fun `should return an error if predicate property does not exist`() {
        val blueprint = compositeDeviceUnchecked(TestSpecWithMissingPredicate, Global)
        val feature = blueprint.features[OperationalGuardsFeature.CAPABILITY] as OperationalGuardsFeature
        val errors = validator.validate(blueprint, feature, MockBlueprintRegistry)

        assertEquals(1, errors.size)
        val error = errors.first()
        assertIs<ValidationError.InvalidGuard>(error)
        assertEquals("Predicate property not found on the blueprint.", error.reason)
        assertEquals("nonExistentPredicate", error.predicateName.toString())
    }

    @Test
    fun `should return an error if guarded property is not a predicate`() {
        val blueprint = compositeDeviceUnchecked(TestSpecWithInvalidGuardKind, Global)
        val feature = blueprint.features[OperationalGuardsFeature.CAPABILITY] as OperationalGuardsFeature
        val errors = validator.validate(blueprint, feature, MockBlueprintRegistry)

        assertEquals(1, errors.size)
        val error = errors.first()
        assertIs<ValidationError.InvalidGuard>(error)
        assertEquals("Property used in a 'whenTrue' guard is not of kind PREDICATE.", error.reason)
        assertEquals("notAPredicate", error.predicateName.toString())
    }
}