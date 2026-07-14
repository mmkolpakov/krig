@file:OptIn(space.kscience.krig.core.KrigPerformancePitfall::class)

package space.kscience.krig.dsl

import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.descriptors.TypeId
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.result.getOrThrow
import space.kscience.krig.api.services.AllowAllAuthorizationService
import space.kscience.krig.core.contracts.doubleValue
import space.kscience.krig.core.contracts.execute
import space.kscience.krig.core.contracts.metaOf
import space.kscience.krig.core.contracts.readProperty
import space.kscience.krig.core.contracts.stringValue
import space.kscience.krig.core.contracts.writeProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

private val collisionQuality = DataQuality(QualitySeverity(73))

private fun collisionContext(name: String): Context = Context(name) {
    plugin(AllowAllAuthorizationService)
}

private enum class PropertyRegistration(
    private val baseValue: Any,
    val valueTypeId: TypeId,
    val isMutable: Boolean = false,
    val isObserved: Boolean = false,
) {
    PLAIN("plain", TypeIds.META) {
        override fun register(builder: DeclarativeDeviceBuilder, name: Name, variant: Int) {
            builder.property(name) { "plain-$variant" }
        }
    },
    OBSERVED("observed", TypeIds.META, isObserved = true) {
        override fun register(builder: DeclarativeDeviceBuilder, name: Name, variant: Int) {
            builder.observedProperty(name) {
                ObservedValue("observed-$variant", Instant.fromEpochMilliseconds(73), collisionQuality)
            }
        }
    },
    TYPED_DOUBLE(3.0, TypeIds.DOUBLE) {
        override fun register(builder: DeclarativeDeviceBuilder, name: Name, variant: Int) {
            builder.propertyDouble(name.toString()) { 3.0 + variant }
        }
    },
    MUTABLE_DOUBLE(4.0, TypeIds.DOUBLE, isMutable = true) {
        override fun register(builder: DeclarativeDeviceBuilder, name: Name, variant: Int) {
            builder.mutableProperty(name.toString(), initial = 4.0 + variant)
        }
    },
    ;

    abstract fun register(builder: DeclarativeDeviceBuilder, name: Name, variant: Int)

    fun value(variant: Int): Any = when (baseValue) {
        is Double -> baseValue + variant
        is String -> "$baseValue-$variant"
        else -> error("Unsupported test value: $baseValue")
    }

    fun readValue(meta: Meta): Any? = when (baseValue) {
        is Double -> meta.doubleValue
        is String -> meta.stringValue
        else -> error("Unsupported test value: $baseValue")
    }
}

private enum class ActionRegistration {
    NAME,
    STRING,
    ;

    fun register(builder: DeclarativeDeviceBuilder, name: Name, result: String) {
        when (this) {
            NAME -> builder.action(name) { metaOf(result) }
            STRING -> builder.action(name.toString()) { metaOf(result) }
        }
    }
}

private fun assertCollisionDiagnostic(error: IllegalStateException, name: Name, operation: String) {
    val message = error.message.orEmpty()
    assertTrue(name.toString() in message, "Collision diagnostic must identify '$name': $message")
    assertTrue(
        message.contains(operation, ignoreCase = true),
        "Collision diagnostic must identify the $operation lane: $message",
    )
}

class DeclarativeDeviceBuilderCollisionTest {
    @Test
    fun propertyRegistrationMatrixRejectsDuplicatesAndKeepsTheFirstDeclaration() = runTest {
        val propertyName = Name.of("value")
        for (first in PropertyRegistration.entries) {
            for (second in PropertyRegistration.entries) {
                lateinit var collision: IllegalStateException
                val device = device(
                    name = Name.of("collision-${first.name.lowercase()}-${second.name.lowercase()}"),
                    context = collisionContext("property-${first.name.lowercase()}-${second.name.lowercase()}"),
                ) {
                    first.register(this, propertyName, variant = 1)
                    collision = assertFailsWith<IllegalStateException>("$first -> $second") {
                        second.register(this, propertyName, variant = 2)
                    }
                }
                try {
                    assertCollisionDiagnostic(collision, propertyName, "property")
                    assertEquals(
                        first.value(variant = 1),
                        first.readValue(device.readProperty(propertyName)),
                        "$first must remain installed after rejecting $second",
                    )
                    assertEquals(first.valueTypeId, device.propertyDescriptors.getValue(propertyName).valueTypeId)

                    val observed = device.readObservedOutcome(propertyName).getOrThrow()
                    assertEquals(if (first.isObserved) collisionQuality else DataQuality.GOOD, observed.quality)

                    if (first.isMutable) {
                        device.writeProperty(propertyName, metaOf(9.0))
                        assertEquals(9.0, device.readProperty(propertyName).doubleValue)
                    } else {
                        assertFailsWith<OperationFaultException> {
                            device.writeProperty(propertyName, metaOf(9.0))
                        }
                    }
                } finally {
                    device.shutdown()
                }
            }
        }
    }

    @Test
    fun propertyDelegatesUseTheSameCollisionBoundary() = runTest {
        val propertyName = Name.of("value")
        lateinit var readCollision: IllegalStateException
        val explicitFirst = device(Name.of("delegate-explicit-first"), collisionContext("delegate-explicit-first")) {
            propertyDouble(propertyName.toString()) { 1.0 }
            readCollision = assertFailsWith<IllegalStateException>("explicit -> delegated") {
                @Suppress("UNUSED_VARIABLE")
                val value by readDouble { 2.0 }
            }
        }
        try {
            assertCollisionDiagnostic(readCollision, propertyName, "property")
            assertEquals(1.0, explicitFirst.readProperty(propertyName).doubleValue)
        } finally {
            explicitFirst.shutdown()
        }

        lateinit var mutableCollision: IllegalStateException
        val delegateFirst = device(Name.of("delegate-mutable-first"), collisionContext("delegate-mutable-first")) {
            @Suppress("UNUSED_VARIABLE")
            val value by mutable(3.0)
            mutableCollision = assertFailsWith<IllegalStateException>("delegated -> explicit") {
                propertyString(propertyName.toString()) { "second" }
            }
        }
        try {
            assertCollisionDiagnostic(mutableCollision, propertyName, "property")
            delegateFirst.writeProperty(propertyName, metaOf(7.0))
            assertEquals(7.0, delegateFirst.readProperty(propertyName).doubleValue)
        } finally {
            delegateFirst.shutdown()
        }
    }

    @Test
    fun actionRegistrationFormsAreMutuallyExclusiveAndKeepTheFirstHandler() = runTest {
        val actionName = Name.of("command")
        for (first in ActionRegistration.entries) {
            for (second in ActionRegistration.entries) {
                lateinit var collision: IllegalStateException
                val device = device(
                    Name.of("action-${first.name.lowercase()}-${second.name.lowercase()}"),
                    collisionContext("action-${first.name.lowercase()}-${second.name.lowercase()}"),
                ) {
                    first.register(this, actionName, "first")
                    collision = assertFailsWith<IllegalStateException>("$first -> $second") {
                        second.register(this, actionName, "second")
                    }
                }
                try {
                    assertCollisionDiagnostic(collision, actionName, "action")
                    assertEquals("first", device.execute(actionName, null)?.stringValue)
                    assertTrue(actionName in device.actionDescriptors)
                } finally {
                    device.shutdown()
                }
            }
        }
    }

    @Test
    fun propertyAndActionNamesRemainSeparateNamespaces() = runTest {
        val sharedName = Name.of("shared")
        for (actionFirst in listOf(false, true)) {
            val device = device(
                Name.of("separate-operation-namespaces-$actionFirst"),
                collisionContext("separate-operation-namespaces-$actionFirst"),
            ) {
                if (actionFirst) {
                    action(sharedName) { metaOf("action") }
                    property(sharedName) { "property" }
                } else {
                    property(sharedName) { "property" }
                    action(sharedName) { metaOf("action") }
                }
            }
            try {
                assertEquals("property", device.readProperty(sharedName).stringValue)
                assertEquals("action", device.execute(sharedName, null)?.stringValue)
                assertTrue(sharedName in device.propertyDescriptors)
                assertTrue(sharedName in device.actionDescriptors)
            } finally {
                device.shutdown()
            }
        }
    }
}
