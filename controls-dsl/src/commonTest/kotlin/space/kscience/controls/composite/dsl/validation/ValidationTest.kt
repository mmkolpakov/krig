@file:OptIn(InternalControlsApi::class)

package space.kscience.controls.composite.dsl.validation

import kotlinx.serialization.Serializable
import ru.nsk.kstatemachine.event.Event
import space.kscience.controls.composite.dsl.*
import space.kscience.controls.composite.dsl.children.mirror
import space.kscience.controls.composite.dsl.children.mirrors
import space.kscience.controls.composite.dsl.guards.guards
import space.kscience.controls.composite.dsl.guards.post
import space.kscience.controls.composite.dsl.properties.booleanProperty
import space.kscience.controls.composite.dsl.properties.predicate
import space.kscience.controls.api.addressing.Address
import space.kscience.controls.core.InternalControlsApi
import space.kscience.controls.validation.ValidationError
import space.kscience.controls.services.discovery.BlueprintRegistry
import space.kscience.controls.connectivity.MirrorEntry
import space.kscience.controls.fsm.guards.OperationalGuardsFeature
import space.kscience.controls.connectivity.RemoteMirrorFeature
import space.kscience.controls.validation.TimedPredicateGuardSpec
import space.kscience.controls.core.legacy_alpha_2.meta.DevicePropertySpec
import space.kscience.controls.core.legacy_alpha_2.contracts.Device
import space.kscience.controls.core.legacy_alpha_2.contracts.DeviceBlueprint
import space.kscience.controls.api.identifiers.BlueprintId
import space.kscience.controls.validation.CompositeSpecValidator
import space.kscience.controls.validation.DefaultValidatorsPlugin
import space.kscience.controls.validation.FeatureValidatorRegistry
import space.kscience.controls.validation.featureValidatorRegistry
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.misc.DFExperimental
import space.kscience.dataforge.names.asName
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

//not all passed
/**
 * A collection of unit and integration tests for the validation framework.
 * These tests are placed in the `dsl` module to allow usage of the DSL for creating
 * test blueprints, thus avoiding manual and verbose construction of blueprint objects.
 *
 * This test suite validates the public API of the validation system, ensuring that
 * the `CompositeSpecValidator` correctly discovers and applies all validation rules
 * provided by plugins in the context.
 */
@OptIn(DFExperimental::class)
class ValidationTest {

    @Serializable
    private object DummyEvent : Event

    /**
     * A mock blueprint registry for testing purposes. It provides a predefined "remote"
     * blueprint that validation rules can look up.
     */
    private object MockRegistry : BlueprintRegistry {
        val remoteBlueprint = deviceBlueprintUnchecked<Device>("remote-device", Context("test")) {
            driver { _, _ -> error("Not for runtime") }
            val temp = doubleProperty("temp".asName(), read = { 25.0 })
            val status = stringProperty("status".asName(), read = { "OK" })
        }

        private val blueprints = mapOf(remoteBlueprint.id to remoteBlueprint)
        override val tag get() = PluginTag("mockRegistry")
        override fun findById(id: BlueprintId): DeviceBlueprint<*>? = blueprints[id]
        override val meta: Meta get() = Meta.EMPTY
        override val context: Context get() = Context("test")
        override val isAttached: Boolean = true
        override fun dependsOn(): Map<PluginFactory<*>, Meta> = emptyMap()
        override fun attach(context: Context) {}
        override fun detach() {}
    }

    /**
     * A fully configured [Context] for validation tests. It includes:
     * - [space.kscience.controls.validation.DefaultValidatorsPlugin]: Provides the factories for built-in validators.
     * - [space.kscience.controls.validation.FeatureValidatorRegistry]: The service that discovers and manages validators.
     * - [MockRegistry]: A mock blueprint registry to resolve child blueprints.
     */
    private val validationContext = Context("validation") {
        plugin(DefaultValidatorsPlugin)
        plugin(FeatureValidatorRegistry)
        plugin(MockRegistry)
    }

    @Test
    fun `test operational guards validator via registry`() {
        // Test case 1: A valid guard using a property of kind PREDICATE.
        val validSpec = object : DeviceSpecification<Device>() {
            override val id = "validation.guards.valid"
            val isReady by predicate { true }
            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("Not for runtime") }
                guards { whenTrue(isReady).forAtLeast(1.seconds).post<DummyEvent>() }
            }
        }
        val validBlueprint = compositeDeviceUnchecked(validSpec, validationContext)
        val validErrors = CompositeSpecValidator.validateWithContext(validBlueprint, validationContext)
        assertTrue(validErrors.isEmpty(), "A valid guard should produce no validation errors. Got: $validErrors")

        // Test case 2: An invalid guard using a regular boolean property instead of a predicate.
        // Create this invalid blueprint programmatically to bypass the DSL's `require` check,
        // allowing testing the validator's logic in isolation.
        val baseSpecForGuardTest = object : DeviceSpecification<Device>() {
            override val id = "validation.guards.base"
            val isReady by predicate { true }
            val notAPredicate by booleanProperty(read = { true }) // The property to use for the invalid test
            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("Not for runtime") }
            }
        }
        val baseBlueprint = compositeDeviceUnchecked(baseSpecForGuardTest, validationContext)

        // Manually create a corrupted feature that references a non-predicate property.
        val invalidGuardFeature = OperationalGuardsFeature(
            guards = listOf(
                TimedPredicateGuardSpec(
                    predicateName = "notAPredicate".asName(),
                    holdFor = 1.seconds,
                    postEventSerialName = "DummyEvent"
                )
            )
        )

        // Create the invalid blueprint by copying the base and injecting the corrupted feature.
        val invalidBlueprint = (baseBlueprint as SimpleDeviceBlueprint).copy(
            features = baseBlueprint.features + (OperationalGuardsFeature.CAPABILITY to invalidGuardFeature)
        )

        val kindErrors = CompositeSpecValidator.validateWithContext(invalidBlueprint, validationContext)

        assertEquals(1, kindErrors.size)
        val error = kindErrors.first()
        assertIs<ValidationError.InvalidGuard>(error)
        assertTrue(
            error.message.contains("not of kind PREDICATE"),
            "Error message should indicate that the property is not a predicate."
        )
    }

    @Test
//    FAILED
    fun `test remote mirror validator via registry`() {
        val remoteBlueprint = MockRegistry.remoteBlueprint
        @Suppress("UNCHECKED_CAST")
        val remoteTempProperty = remoteBlueprint.properties["temp".asName()] as DevicePropertySpec<*, Double>


        // Test case 1: A valid mirror with matching types.
        val validSpec = object : DeviceSpecification<Device>() {
            override val id = "validation.mirror.valid"
            override fun CompositeSpecBuilder<Device>.configure() {
                driver { _, _ -> error("not for runtime") }
                val peer by peer({ _, _ -> error("not called") }, Address("remoteHub".asName(), "peer".asName()))
                remoteChild("remote".asName(), remoteBlueprint, "remote".asName(), via = peer)
                mirrors {
                    mirror("remote".asName(), remoteTempProperty, "local_temp".asName(), MetaConverter.double)
                }
            }
        }
        val validBlueprint = compositeDeviceUnchecked(validSpec, validationContext)
        val validErrors = CompositeSpecValidator.validateWithContext(validBlueprint, validationContext)
        assertTrue(validErrors.isEmpty(), "Valid mirror configuration should produce no errors. Got: $validErrors")

        // Test case 2: Type mismatch.
        // Test the validator's logic directly, because the DSL is too type-safe to create an invalid mirror.
        // This simulates a user creating an invalid blueprint manually (e.g., from YAML).
        val blueprintForManualTest = compositeDeviceUnchecked(validSpec, validationContext) // Use the valid spec as a base
        val validator = validationContext.featureValidatorRegistry.getValidatorFor(RemoteMirrorFeature(emptyList()))
        assertNotNull(validator)

        // Manually create a corrupted mirror entry where the type name does not match the remote property.
        val corruptedFeature = RemoteMirrorFeature(
            listOf(
                MirrorEntry(
                    remoteChildName = "remote".asName(),
                    remotePropertyName = "temp".asName(),
                    localPropertyName = "local_temp".asName(),
                    valueTypeName = "kotlin.String" // Intentional mismatch: remote is Double
                )
            )
        )

        val errors = validator.validate(blueprintForManualTest, corruptedFeature, MockRegistry)

        assertEquals(1, errors.size)
        assertIs<ValidationError.InvalidMirror>(errors.first())
        assertTrue(errors.first().message.contains("Type mismatch"))
    }

    @Test
    fun `test feature validator registry discovery`() {
        val registry = validationContext.featureValidatorRegistry

        // Test discovery of OperationalGuardsValidator
        val guardsFeature = OperationalGuardsFeature(emptyList())
        val guardsValidator = registry.getValidatorFor(guardsFeature)
        assertNotNull(guardsValidator, "Validator for OperationalGuardsFeature should be found.")
        assertEquals("OperationalGuardsValidator", guardsValidator::class.simpleName)

        // Test discovery of RemoteMirrorValidator
        val mirrorFeature = RemoteMirrorFeature(emptyList())
        val mirrorValidator = registry.getValidatorFor(mirrorFeature)
        assertNotNull(mirrorValidator, "Validator for RemoteMirrorFeature should be found.")
        assertEquals("RemoteMirrorValidator", mirrorValidator::class.simpleName)
    }
}