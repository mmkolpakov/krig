@file:OptIn(
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    space.kscience.krig.core.InternalKrigApi::class,
)

package space.kscience.krig.assembly

import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.ValueType
import space.kscience.dataforge.meta.descriptors.MetaDescriptor
import space.kscience.dataforge.meta.descriptors.required
import space.kscience.dataforge.meta.descriptors.value
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.factory.DeviceFactory
import space.kscience.krig.api.factory.DeviceFactoryConfigValidationException
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.manifestOf
import space.kscience.krig.core.contracts.schemaHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class StubDevice(name: Name, context: Context) : AbstractDevice(name, DeviceRuntime(context)) {
    override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        OperationOutcome.Ok(Meta.EMPTY)

    override suspend fun doWritePropertyOutcome(propertyName: Name, value: Meta): OperationOutcome<Unit> =
        OperationOutcome.OkUnit

    override suspend fun doExecuteOutcome(actionName: Name, argument: Meta?): OperationOutcome<Meta?> =
        OperationOutcome.Ok(null)
}

private fun factoryContext(name: String): Context = Context(name) { plugin(DeviceFactoryPlugin) }
private fun factoryCatalogContext(name: String): Context = Context(name) {
    plugin(DeviceFactoryPlugin)
    plugin(DeviceCatalog)
}

class MetaDeviceGroupTest {

    @Test
    fun buildsGroupFromMeta() {
        val context = factoryContext("meta-group")
        context.deviceFactories().register(
            DeviceFactory("stub") { childContext ->
                StubDevice(childContext.name, childContext)
            },
        )

        val group = context.metaDeviceGroup(
            "crate",
            Meta {
                "children" put {
                    "motor" put { "factory" put "stub" }
                    "sensor" put { "factory" put "stub" }
                }
            },
        )

        assertEquals(setOf("motor".asName(), "sensor".asName()), group.devices.keys)
        assertIs<StubDevice>(group.devices["motor".asName()])
    }

    @Test
    fun buildsGroupFromTopologySpec() {
        val context = factoryContext("topology-spec")
        context.deviceFactories().register(
            DeviceFactory("stub") { childContext ->
                StubDevice(childContext.name, childContext)
            },
        )

        val group = context.assembleDeviceTopology(
            "crate",
            DeviceTopologySpec(
                children = mapOf(
                    "motor".asName() to DeviceInstanceSpec(factory = "stub".asName()),
                ),
            ),
        )

        assertEquals(setOf("motor".asName()), group.devices.keys)
        assertIs<StubDevice>(group.devices["motor".asName()])
    }

    @Test
    fun topologySpecValidatesManifestRequirementBeforeCreation() {
        val context = factoryCatalogContext("topology-spec-manifest")
        val manifest = manifestOf("demo.stub".asName(), properties = emptyMap(), version = "1.0.0")
        context.deviceCatalog().register(manifest)
        var created = false
        context.deviceFactories().register(
            DeviceFactory("stub") { childContext ->
                created = true
                StubDevice(childContext.name, childContext)
            },
        )

        val group = context.assembleDeviceTopology(
            "crate",
            DeviceTopologySpec(
                children = mapOf(
                    "motor".asName() to DeviceInstanceSpec(
                        factory = "stub".asName(),
                        manifest = DeviceManifestRequirement(
                            manifestId = manifest.id,
                            version = manifest.version,
                            schemaHash = manifest.schemaHash(),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(setOf("motor".asName()), group.devices.keys)
        assertTrue(created)
    }

    @Test
    fun manifestRequirementMismatchFailsBeforeFactoryCreation() {
        val context = factoryCatalogContext("topology-spec-manifest-mismatch")
        val manifest = manifestOf("demo.stub".asName(), properties = emptyMap(), version = "1.0.0")
        context.deviceCatalog().register(manifest)
        var created = false
        context.deviceFactories().register(
            DeviceFactory("stub") { childContext ->
                created = true
                StubDevice(childContext.name, childContext)
            },
        )

        assertFailsWith<IllegalArgumentException> {
            context.assembleDeviceTopology(
                "crate",
                DeviceTopologySpec(
                    children = mapOf(
                        "motor".asName() to DeviceInstanceSpec(
                            factory = "stub".asName(),
                            manifest = DeviceManifestRequirement(
                                manifestId = manifest.id,
                                version = manifest.version,
                                schemaHash = "fnv1a64:0000000000000000",
                            ),
                        ),
                    ),
                ),
            )
        }
        assertFalse(created, "Factory create() must not run for incompatible manifest requirements")
    }

    @Test
    fun unknownFactoryFails() {
        val context = factoryContext("meta-group-missing")
        assertFailsWith<IllegalStateException> {
            context.metaDeviceGroup("crate", Meta { "children" put { "x" put { "factory" put "missing" } } })
        }
    }

    @Test
    fun missingFactoryKeyFails() {
        val context = factoryContext("meta-group-nokey")
        assertFailsWith<IllegalStateException> {
            context.metaDeviceGroup("crate", Meta { "children" put { "x" put { "note" put "no factory here" } } })
        }
    }

    @Test
    fun invalidChildConfigFailsBeforeFactoryCreation() {
        val context = factoryContext("meta-group-invalid-config")
        var created = false
        context.deviceFactories().register(
            DeviceFactory(
                id = "validated-stub",
                configDescriptor = MetaDescriptor {
                    value("port", ValueType.NUMBER) { required() }
                },
            ) { childContext ->
                created = true
                StubDevice(childContext.name, childContext)
            },
        )

        assertFailsWith<DeviceFactoryConfigValidationException> {
            context.metaDeviceGroup(
                "crate",
                Meta {
                    "children" put {
                        "motor" put {
                            "factory" put "validated-stub"
                            "config" put { "port" put "not-a-number" }
                        }
                    }
                },
            )
        }
        assertFalse(created, "Factory create() must not run for invalid child config")
    }
}
