@file:OptIn(
    space.kscience.krig.core.UnstableKrigForSubclassing::class,
    space.kscience.krig.core.InternalKrigApi::class,
    space.kscience.krig.core.KrigPerformancePitfall::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package space.kscience.krig.assembly

import kotlin.concurrent.atomics.AtomicInt
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MetaConverter
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.NameToken
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import space.kscience.dataforge.names.plus
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.TypeIds
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.runCatchingOperation
import space.kscience.krig.core.contracts.AbstractDevice
import space.kscience.krig.core.contracts.DeviceRuntime
import space.kscience.krig.core.contracts.asNode
import space.kscience.krig.core.contracts.deviceTree
import space.kscience.krig.core.contracts.typed.TypedReader
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.dsl.deviceGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Read-only leaf exposing a single constant `Double` through the typed + Meta surfaces. */
private class ConstantDoubleDevice(
    name: Name,
    context: Context,
    private val constant: Double,
) : AbstractDevice(name, DeviceRuntime(context)) {

    val valueSpec: DevicePropertyContract<Double> = object : DevicePropertyContract<Double> {
        override val name: Name = "value".asName()
        override val descriptor: PropertyDescriptor = PropertyDescriptor(
            name = this.name,
            kind = PropertyKind.PHYSICAL,
            valueTypeId = TypeIds.DOUBLE,
        )
        override val converter: MetaConverter<Double> = MetaConverter.double
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> reader(spec: DevicePropertyContract<T>): TypedReader<T> =
        if (spec === valueSpec) TypedReader { constant } as TypedReader<T>
        else super.reader(spec)

    override fun propertySpec(propertyName: Name): DevicePropertyContract<*>? =
        if (propertyName == valueSpec.name) valueSpec else null

    override suspend fun doReadPropertyOutcome(propertyName: Name): OperationOutcome<Meta> =
        runCatchingOperation {
            if (propertyName == valueSpec.name) MetaConverter.double.convert(constant)
            else error("Unknown property '$propertyName'")
        }
}

private val contextSeq = AtomicInt(0)

class DeviceGroupAcquisitionTest {

    private suspend fun samplePlant(scope: kotlinx.coroutines.CoroutineScope) = run {
        val context = Context("site-${contextSeq.addAndFetch(1)}")
        val main = ConstantDoubleDevice("main".asName(), context, 1_200.0)
        val booster = ConstantDoubleDevice("booster".asName(), context, 42.0)
        val group = deviceGroup {
            deviceGroup("plant") {
                device("main", main)
                deviceGroup("aux") { device("booster", booster) }
            }
        }.start("site", context, scope)
        Triple(group, main, booster)
    }

    @Test
    fun flattenDeviceTopologyPreservesHierarchicalNames() = runTest {
        val (group, main, booster) = samplePlant(this)
        val topology = group.flattenDeviceTopology()

        assertTrue("plant.main".parseAsName() in topology, "leaf under nested folder addressable")
        assertTrue("plant.aux.booster".parseAsName() in topology, "deeply nested leaf addressable")
        assertTrue("plant.aux".parseAsName() in topology, "intermediate group is itself addressable")
        assertEquals(main, topology["plant.main".parseAsName()])
        assertEquals(booster, topology["plant.aux.booster".parseAsName()])
    }

    @Test
    fun flattenDevicesProducesAcquisitionSourceIds() = runTest {
        val (group, main, booster) = samplePlant(this)
        val flat = group.flattenDevices()

        assertTrue("plant.main".asName() in flat, "leaf under nested folder addressable")
        assertTrue("plant.aux.booster".asName() in flat, "deeply nested leaf addressable")
        assertTrue("plant.aux".asName() in flat, "intermediate group is itself addressable")
        assertEquals(main, flat["plant.main".asName()])
        assertEquals(booster, flat["plant.aux.booster".asName()])
    }

    @Test
    fun readAtResolvesLeafByPath() = runTest {
        val (group, _, booster) = samplePlant(this)

        val ok = group.readAt("plant.aux.booster".parseAsName(), booster.valueSpec)
        assertIs<OperationOutcome.Ok<Double>>(ok)
        assertEquals(42.0, ok.value)
    }

    @Test
    fun readAtMissingPathFailsAsValue() = runTest {
        val (group, _, booster) = samplePlant(this)

        val fail = group.readAt("plant.nonexistent".asName(), booster.valueSpec)
        assertIs<OperationOutcome.Fail>(fail)
    }

    @Test
    fun acquisitionReaderSamplesGroupHierarchyByPath() = runTest {
        val (group, _, _) = samplePlant(this)
        val config = dataAcquisition {
            source("plant.main", connector = AcquisitionConnectors.KrigDevice)
            tag("rpm").from("plant.main", "value", TypeIds.DOUBLE)
            timer("fast", 10.milliseconds) { samples("rpm") }
        }

        val observations = config.pollTimer(
            timerId = "fast",
            ticks = flowOf(Unit),
            reader = group.acquisitionReader(),
        ).toList()

        assertEquals(listOf("rpm".asName()), observations.map { it.spec.id })
        assertTrue(observations.single().isOk)
        assertEquals(1_200.0, MetaConverter.double.read(observations.single().observed.value!!))
    }

    @Test
    fun topologySourceSeparatesSourceIdFromDevicePath() = runTest {
        val (group, _, _) = samplePlant(this)
        val config = dataAcquisition {
            topologySource(
                id = "main-feed",
                topologyPath = "plant.main".parseAsName(),
            )
            tag("rpm").from("main-feed", "value", TypeIds.DOUBLE)
            timer("fast", 10.milliseconds) { samples("rpm") }
        }

        val observations = config.pollTimer(
            timerId = "fast",
            ticks = flowOf(Unit),
            reader = deviceTreeAcquisitionReader(group.flattenDeviceTopology()),
        ).toList()

        assertTrue(observations.single().isOk)
        assertEquals(1_200.0, MetaConverter.double.read(observations.single().observed.value!!))
    }

    @Test
    fun topologyFlatteningKeepsIndexedAndEscapedTokens() {
        val context = Context("site-${contextSeq.addAndFetch(1)}-escaped")
        val pump = ConstantDoubleDevice("pump".asName(), context, 1.0)
        val lineToken = NameToken("line.a")
        val pumpToken = NameToken("pump", "2")
        val tree = deviceTree(
            children = mapOf(
                lineToken.asName() to deviceTree(
                    children = mapOf(pumpToken.asName() to pump.asNode()),
                ),
            ),
        )
        val topologyPath = lineToken.asName() + pumpToken.asName()
        val sourceId = topologyPath.toAcquisitionSourceId()

        assertEquals(pump, tree.flattenDeviceTopology()[topologyPath])
        assertEquals(sourceId, topologyPath.toAcquisitionSourceId())
        assertEquals(topologyPath.toString(), sourceId.tokens.single().body)
        assertEquals(topologyPath, sourceId.asAcquisitionTopologyPath())
    }
}
