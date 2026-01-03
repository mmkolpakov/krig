package space.kscience.controls.composite.dsl

import kotlinx.serialization.Transient
import ru.nsk.kstatemachine.statemachine.BuildingStateMachine
import space.kscience.controls.core.InternalControlsApi
import space.kscience.controls.core.composition.ChildComponentConfig
import space.kscience.controls.core.connectivity.PeerBlueprint
import space.kscience.controls.core.connectivity.PeerConnection
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.core.contracts.DeviceBlueprint
import space.kscience.controls.core.contracts.DeviceDriver
import space.kscience.controls.core.features.Feature
import space.kscience.controls.core.identifiers.BlueprintId
import space.kscience.controls.core.meta.DeviceActionSpec
import space.kscience.controls.core.meta.DevicePropertySpec
import space.kscience.controls.core.meta.DeviceStreamSpec
import space.kscience.controls.core.meta.MemberTag
import space.kscience.controls.core.runtime.DeviceFlows
import space.kscience.controls.core.runtime.HydratableDeviceState
import space.kscience.controls.fsm.LifecycleContext
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * A simple data-holding implementation of [space.kscience.controls.core.contracts.DeviceBlueprint].
 * This class stores all parts of the blueprint, including the non-serializable behavior logic.
 * It also separates the public API members from the non-public ones, which are intended for internal
 * use by the device driver.
 *
 * @property protectedProperties A map of all protected, internal, and private property specifications.
 * @property protectedActions A map of all protected, internal, and private action specifications.
 * @property protectedStreams A map of all protected, internal, and private stream specifications.
 */
@OptIn(InternalControlsApi::class)
public data class SimpleDeviceBlueprint<D : Device>(
    override val id: BlueprintId,
    override val version: String = "0.1.0",
    override val tags: Set<MemberTag> = emptySet(),
    override val features: Map<String, Feature>,
    override val properties: Map<Name, DevicePropertySpec<D, *>>,
    override val actions: Map<Name, DeviceActionSpec<D, *, *>>,
    override val streams: Map<Name, DeviceStreamSpec<D>>,
    override val meta: Meta,
    @Transient val lifecycle: suspend BuildingStateMachine.(device: D, context: LifecycleContext<D>) -> Unit,
    @Transient val operationalFsm: (suspend BuildingStateMachine.(device: D, context: LifecycleContext<D>) -> Unit)?,
    @Transient override val driver: DeviceDriver<D>,
    @Transient val logic: (suspend D.(DeviceFlows) -> Unit)?,
    val stateMigratorId: String? = null,
    override val deviceContractFqName: String,
    @Transient val propertyReadLogic: Map<Name, suspend D.() -> Any?>,
    @Transient val propertyWriteLogic: Map<Name, suspend D.(Any?) -> Unit>,
    @Transient val actionExecutors: Map<Name, suspend D.(Meta?) -> Meta?>,
    @Transient val derivedStateFactories: Map<Name, HydratableDeviceState<D, *>>,
    internal val protectedProperties: Map<Name, DevicePropertySpec<D, *>> = emptyMap(),
    internal val protectedActions: Map<Name, DeviceActionSpec<D, *, *>> = emptyMap(),
    internal val protectedStreams: Map<Name, DeviceStreamSpec<D>> = emptyMap(),
) : DeviceBlueprint<D>