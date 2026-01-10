package space.kscience.controls.composite.dsl

import space.kscience.controls.core.contracts.Device
import space.kscience.controls.core.contracts.tags
import space.kscience.controls.api.meta.ProfileTag
import space.kscience.dataforge.context.Global
import space.kscience.dataforge.meta.*
import kotlin.test.*

private class MyTestSpec : DeviceSpecification<Device>() {
    // No explicit ID
    override fun CompositeSpecBuilder<Device>.configure() {
        driver { _, _ -> error("Not for runtime") }
    }
}

private class MyTestSpecWithId : DeviceSpecification<Device>() {
    override val id = "my.explicit.id"
    override fun CompositeSpecBuilder<Device>.configure() {
        driver { _, _ -> error("Not for runtime") }
    }
}

/**
 * Tests for the primary blueprint factory functions: `deviceBlueprint` and `compositeDevice`.
 */
//not all passed
class BlueprintBuilderTest {

    /**
     * Verifies that `deviceBlueprint` and `compositeDevice` with an explicit ID correctly set it.
     */
    @Test
    fun testShouldUseExplicitIdWhenProvided() {
        val fromDsl = deviceBlueprintUnchecked<Device>("my.dsl.id", Global) {
            driver { _, _ -> error("Not for runtime") }
        }
        assertEquals("my.dsl.id", fromDsl.id.value)

        val fromSpec = compositeDeviceUnchecked(MyTestSpecWithId(), Global)
        assertEquals("my.explicit.id", fromSpec.id.value)
    }

    /**
     * Verifies that `compositeDevice` correctly infers the blueprint ID from the specification's class name.
     */
    @Test
    fun testShouldUseSpecificationClassNameAsId() {
        val fromSpec = compositeDeviceUnchecked(MyTestSpec(), Global)
        assertEquals("MyTestSpec", fromSpec.id.value)
    }

    /**
     * A regression test to ensure that using an anonymous `DeviceSpecification` without an explicit `id`
     * throws a descriptive `IllegalStateException`.
     */
    @Test
    fun testShouldFailOnAnonymousSpecWithoutId() {
        assertFailsWith<IllegalStateException> {
            compositeDeviceUnchecked(object : DeviceSpecification<Device>() {
                override fun CompositeSpecBuilder<Device>.configure() {
                    driver { _, _ -> error("Not for runtime") }
                }
            }, Global)
        }
    }

    /**
     * Verifies that `version` and `meta` blocks in the DSL are correctly applied to the final blueprint.
     */
    @Test
    fun testShouldConfigureMetaAndVersion() {
        val blueprint = deviceBlueprintUnchecked<Device>("test", Global, version = "1.2.3") {
            driver { _, _ -> error("Not for runtime") }
            meta { "myKey" put "myValue" }
            logic { } // Add a non-null logic block for testing
        }

        assertEquals("1.2.3", blueprint.version)
        assertEquals("myValue", blueprint.meta["myKey"].string)
    }

    /**
     * Verifies that the `addTag` method in the DSL correctly adds tags to the final blueprint.
     */
    @Test
//    FAILED The blueprint should have one tag. (was 0)
    fun testShouldAddTagsToBlueprint() {
        val profileTag = ProfileTag("my.test.profile", "1.0.0")

        val blueprint = deviceBlueprintUnchecked<Device>("test.tagged", Global) {
            driver { _, _ -> error("Not for runtime") }
            // Add a tag to the blueprint itself.
            addTag(profileTag)
        }

        assertEquals(1, blueprint.tags.size, "The blueprint should have one tag.")
        val tag = blueprint.tags.first()
        assertIs<ProfileTag>(tag, "The tag should be of type ProfileTag.")
        assertEquals("my.test.profile", tag.name)
        assertEquals("1.0.0", tag.version)
    }
}