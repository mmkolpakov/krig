package space.kscience.controls.composite.dsl

import ru.nsk.kstatemachine.statemachine.BuildingStateMachine
import space.kscience.controls.composite.dsl.children.ChildConfigBuilder
import space.kscience.controls.composite.dsl.lifecycle.DriverLogicBuilder
import space.kscience.controls.core.runtime.HydratableDeviceState
import space.kscience.controls.core.runtime.DeviceFlows
import space.kscience.controls.services.discovery.BlueprintRegistry
import space.kscience.controls.core.features.Feature
import space.kscience.controls.fsm.LifecycleFeature
import space.kscience.controls.fsm.OperationalFsmFeature
import space.kscience.controls.fsm.guards.OperationalGuardsFeature
import space.kscience.controls.validation.TimedPredicateGuardSpec
import space.kscience.controls.fsm.guards.ValueChangeGuardSpec
import space.kscience.controls.fsm.LifecycleContext
import space.kscience.controls.core.meta.DeviceActionSpec
import space.kscience.controls.core.meta.DevicePropertySpec
import space.kscience.controls.core.meta.DeviceStreamSpec
import space.kscience.controls.core.meta.MemberTag
import space.kscience.controls.core.identifiers.BlueprintId
import space.kscience.controls.core.addressing.Address
import space.kscience.controls.composite.dsl.lifecycle.DriverLogicFragment
import space.kscience.controls.connectivity.CompositionFeature
import space.kscience.controls.connectivity.ConnectivityFeature
import space.kscience.controls.core.composition.ChildComponentConfig
import space.kscience.controls.core.connectivity.DiscoveredAddressSource
import space.kscience.controls.core.connectivity.FailoverStrategy
import space.kscience.controls.core.connectivity.PeerBlueprint
import space.kscience.controls.core.composition.LocalChildComponentConfig
import space.kscience.controls.core.connectivity.PeerConnection
import space.kscience.controls.core.connectivity.PeerDriver
import space.kscience.controls.core.connectivity.SimplePeerBlueprint
import space.kscience.controls.core.composition.RemoteChildComponentConfig
import space.kscience.controls.core.connectivity.StaticAddressSource
import space.kscience.controls.core.InternalControlsApi
import space.kscience.controls.core.contracts.Device
import space.kscience.controls.core.contracts.DeviceBlueprint
import space.kscience.controls.core.contracts.DeviceDriver
import space.kscience.controls.core.features.MetadataFeature
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.ContextAware
import space.kscience.dataforge.context.logger
import space.kscience.dataforge.context.warn
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.meta.MutableMeta
import space.kscience.dataforge.meta.ObservableMeta
import space.kscience.dataforge.names.Name
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * A DSL marker for building composite device specifications.
 */
@DslMarker
public annotation class CompositeSpecDsl


/**
 * A builder for composing [space.kscience.controls.core.contracts.DeviceBlueprint] instances using a DSL.
 * This class is the primary entry point for defining a device's structure and behavior.
 * Properties and actions are typically declared using delegated properties, which automatically register themselves.
 * This builder is used to configure other aspects like child components, lifecycle, and the device driver.
 *
 * @param D The type of the device contract being specified.
 * @property context The [Context] for this builder, used for logging and accessing plugins.
 */
@CompositeSpecDsl
public class CompositeSpecBuilder<D : Device>(
    override val context: Context,
) : ContextAware {

    // Storage for public members
    internal val _properties = mutableMapOf<Name, DevicePropertySpec<D, *>>()
    internal val _actions = mutableMapOf<Name, DeviceActionSpec<D, *, *>>()
    internal val _streams = mutableMapOf<Name, DeviceStreamSpec<D>>()

    // Storage for all non-public members (protected, internal, private)
    internal val _protectedProperties = mutableMapOf<Name, DevicePropertySpec<D, *>>()
    internal val _protectedActions = mutableMapOf<Name, DeviceActionSpec<D, *, *>>()
    internal val _protectedStreams = mutableMapOf<Name, DeviceStreamSpec<D>>()

    internal val _children = mutableMapOf<Name, ChildComponentConfig>()
    internal val _peerConnections = mutableMapOf<Name, PeerBlueprint<out PeerConnection>>()
    private var _lifecycle: suspend BuildingStateMachine.(device: D, context: LifecycleContext<D>) -> Unit = { _, _ -> }
    private var _operationalFsm: (suspend BuildingStateMachine.(device: D, context: LifecycleContext<D>) -> Unit)? = null
    private var _operationalFsmStates: Set<String> = emptySet()
    private var userDeviceDriver: DeviceDriver<D>? = null

    // Internal maps to hold driver logic
    @PublishedApi
    internal val propertyReadLogic: MutableMap<Name, suspend D.() -> Any?> = mutableMapOf()

    @PublishedApi
    internal val propertyWriteLogic: MutableMap<Name, suspend D.(Any?) -> Unit> = mutableMapOf()

    @PublishedApi
    internal val actionExecutors: MutableMap<Name, suspend D.(Meta?) -> Meta?> = mutableMapOf()

    // Placeholders for lifecycle logic from driverLogic block
    @PublishedApi
    internal var onAttachLogic: suspend D.() -> Unit = {}
    @PublishedApi
    internal var onStartLogic: suspend D.() -> Unit = {}
    @PublishedApi
    internal var afterStartLogic: suspend D.() -> Unit = {}
    @PublishedApi
    internal var onStopLogic: suspend D.() -> Unit = {}
    @PublishedApi
    internal var afterStopLogic: suspend D.() -> Unit = {}
    @PublishedApi
    internal var onResetLogic: suspend D.() -> Unit = {}
    @PublishedApi
    internal var onFailLogic: suspend D.(Throwable?) -> Unit = { _ -> }
    @PublishedApi
    internal var onDetachLogic: suspend D.() -> Unit = {}

    @OptIn(InternalControlsApi::class)
    internal val registeredHydrators: MutableMap<Name, HydratableDeviceState<D, *>> = mutableMapOf()

    internal val _features = mutableMapOf<String, Feature>()

    /**
     * The version for the blueprint being built.
     */
    public var version: String = "0.1.0"

    /**
     * A block of code defining the internal reactive logic of the device.
     * This logic will be executed by the runtime when the device is started.
     */
    internal var logic: (suspend D.(flows: DeviceFlows) -> Unit)? = null


    /**
     * Additional metadata for the blueprint itself. This meta is layered at the bottom
     * of the final device's configuration meta.
     */
    public var meta: Meta = Meta.EMPTY

    /**
     * A DSL block to configure the blueprint's metadata.
     */
    public fun meta(block: MutableMeta.() -> Unit) {
        this.meta = Meta(block)
    }

    /**
     * Adds a semantic [MemberTag] to the blueprint itself, not to a specific property or action.
     * This is the primary mechanism for high-level classification of a device, for example,
     * to declare that it conforms to a specific profile or "dialect".
     *
     * Example:
     * ```
     * addTag(ProfileTag("yandex.light.dimmable", "1.0"))
     * ```
     *
     * @param tag The [MemberTag] instance to add to the blueprint's tag set.
     */
    public fun addTag(tag: MemberTag) {
        val existingFeature = _features[MetadataFeature.ID] as? MetadataFeature
        val existingTags = existingFeature?.tags ?: emptySet()
        val existingDesc = existingFeature?.description

        feature(MetadataFeature(tags = existingTags + tag, description = existingDesc))
    }

    /**
     * Sets a human-readable description for the blueprint via [MetadataFeature].
     */
    public fun description(text: String) {
        val existingFeature = _features[MetadataFeature.ID] as? MetadataFeature
        val existingTags = existingFeature?.tags ?: emptySet()

        feature(MetadataFeature(tags = existingTags, description = text))
    }

    /**
     * Defines the implementation logic for the device's properties, actions, and lifecycle hooks.
     * This block provides a type-safe way to connect the declarative API of a [DeviceSpecification]
     * to its concrete behavior. This is the **only** way to define property and action logic.
     *
     * @param spec An instance of the [DeviceSpecification] this logic implements. This is used
     *             for type inference and to ensure that the implemented logic matches the declared API.
     * @param fragments Optional, reusable [DriverLogicFragment]s to be applied before the main logic block.
     * @param block The DSL block where the logic is defined using `onRead`, `onWrite`, `onExecute`, etc.
     */
    public fun driverLogic(
        spec: DeviceSpecification<D>,
        vararg fragments: DriverLogicFragment<D>,
        block: DriverLogicBuilder<D>.() -> Unit
    ) {
        val builder = DriverLogicBuilder(spec).apply {
            // Apply fragments first. Logic in the main block can override fragments.
            fragments.forEach { it.apply(this) }
            // Apply the main logic block.
            block()
        }
        propertyReadLogic.putAll(builder.propertyReaders)
        propertyWriteLogic.putAll(builder.propertyWriters)
        actionExecutors.putAll(builder.actionExecutors)
        onAttachLogic = builder.onAttachLogic
        onStartLogic = builder.onStartLogic
        afterStartLogic = builder.afterStartLogic
        onStopLogic = builder.onStopLogic
        afterStopLogic = builder.afterStopLogic
        onResetLogic = builder.onResetLogic
        onFailLogic = builder.onFailLogic
        onDetachLogic = builder.onDetachLogic
    }

    /**
     * Defines the driver for the device. The factory lambda receives the parent's [Context] and an [ObservableMeta].
     * The driver is responsible for creating the device instance and handling its lifecycle hooks.
     * The logic for properties and actions MUST be defined in a separate [driverLogic] block.
     * A driver **must** be defined for a blueprint to be valid.
     *
     * @param factory A lambda that creates an instance of the device [D].
     */
    public fun driver(factory: (context: Context, meta: ObservableMeta) -> D) {
        this.userDeviceDriver = DeviceDriver { context, meta -> factory(context, meta) }
    }

    /**
     * Defines the implementation logic for the device's properties, actions, and lifecycle hooks.
     * This block provides a type-safe way to connect the declarative API of a [DeviceSpecification]
     * to its concrete behavior. This is the **only** way to define property and action logic.
     *
     * @param spec An instance of the [DeviceSpecification] this logic implements. This is used
     *             for type inference and to ensure that the implemented logic matches the declared API.
     * @param block The DSL block where the logic is defined using `onRead`, `onWrite`, `onExecute`, etc.
     */
    public fun driverLogic(spec: DeviceSpecification<D>, block: DriverLogicBuilder<D>.() -> Unit) {
        val builder = DriverLogicBuilder(spec).apply(block)
        propertyReadLogic.putAll(builder.propertyReaders)
        propertyWriteLogic.putAll(builder.propertyWriters)
        actionExecutors.putAll(builder.actionExecutors)
        onAttachLogic = builder.onAttachLogic
        onStartLogic = builder.onStartLogic
        afterStartLogic = builder.afterStartLogic
        onStopLogic = builder.onStopLogic
        afterStopLogic = builder.afterStopLogic
        onResetLogic = builder.onResetLogic
        onFailLogic = builder.onFailLogic
        onDetachLogic = builder.onDetachLogic
    }


    private fun checkNameCollision(name: Name) {
        require(!_properties.containsKey(name)) { "A public property with name '$name' is already registered." }
        require(!_protectedProperties.containsKey(name)) { "A non-public property with name '$name' is already registered." }
        require(!_actions.containsKey(name)) { "A public action with name '$name' is already registered." }
        require(!_protectedActions.containsKey(name)) { "A non-public action with name '$name' is already registered." }
        require(!_streams.containsKey(name)) { "A public stream with name '$name' is already registered." }
        require(!_protectedStreams.containsKey(name)) { "A non-public stream with name '$name' is already registered." }
        require(!_children.containsKey(name)) { "A child with name '$name' is already registered." }
        require(!_peerConnections.containsKey(name)) { "A peer connection with name '$name' is already registered." }
    }

    /**
     * Registers a pre-constructed public [DevicePropertySpec]. Called automatically by property delegates.
     * @param spec The property specification to register.
     * @throws IllegalArgumentException if a component with the same name already exists.
     */
    public fun registerProperty(spec: DevicePropertySpec<D, *>) {
        checkNameCollision(spec.name)
        _properties[spec.name] = spec
    }

    /**
     * Registers a pre-constructed non-public (protected, internal, or private) [DevicePropertySpec].
     * @param spec The property specification to register.
     * @throws IllegalArgumentException if a component with the same name already exists.
     */
    internal fun registerProtectedProperty(spec: DevicePropertySpec<D, *>) {
        checkNameCollision(spec.name)
        _protectedProperties[spec.name] = spec
    }

    /**
     * Registers a pre-constructed public [DeviceActionSpec]. Called automatically by action delegates.
     * @throws IllegalArgumentException if a component with the same name already exists.
     */
    public fun registerAction(spec: DeviceActionSpec<D, *, *>) {
        checkNameCollision(spec.name)
        _actions[spec.name] = spec
    }

    /**
     * Registers a pre-constructed non-public [DeviceActionSpec].
     * @throws IllegalArgumentException if a component with the same name already exists.
     */
    internal fun registerProtectedAction(spec: DeviceActionSpec<D, *, *>) {
        checkNameCollision(spec.name)
        _protectedActions[spec.name] = spec
    }

    /**
     * Registers a pre-constructed public [DeviceStreamSpec]. Called automatically by stream delegates.
     * @throws IllegalArgumentException if a component with the same name already exists.
     */
    public fun registerStream(spec: DeviceStreamSpec<D>) {
        checkNameCollision(spec.name)
        _streams[spec.name] = spec
    }

    /**
     * Registers a pre-constructed non-public [DeviceStreamSpec].
     * @throws IllegalArgumentException if a component with the same name already exists.
     */
    internal fun registerProtectedStream(spec: DeviceStreamSpec<D>) {
        checkNameCollision(spec.name)
        _protectedStreams[spec.name] = spec
    }

    /**
     * Registers a pre-configured child component.
     * @throws IllegalArgumentException if a component with the same name already exists.
     */
    public fun registerChild(name: Name, config: ChildComponentConfig) {
        checkNameCollision(name)
        _children[name] = config
    }

    /**
     * Registers a pre-configured peer connection blueprint.
     * @throws IllegalArgumentException if a component with the same name already exists.
     */
    public fun registerPeerConnection(name: Name, blueprint: PeerBlueprint<out PeerConnection>) {
        checkNameCollision(name)
        _peerConnections[name] = blueprint
    }

    /**
     * Declares a peer connection required by this device, using a static list of addresses.
     * A peer connection represents a direct, efficient communication link to another hub or service,
     * typically for binary data transfer.
     *
     * @param P The type of the [PeerConnection].
     * @param driver The driver to create the connection instance.
     * @param addresses A vararg list of static [Address] objects.
     * @param failoverStrategy The strategy for handling multiple addresses.
     * @return A [PropertyDelegateProvider] for a [PeerBlueprint]. The delegate ensures the blueprint
     * is registered at build time.
     */
    public fun <P : PeerConnection> peer(
        driver: PeerDriver<P>,
        vararg addresses: Address,
        failoverStrategy: FailoverStrategy = FailoverStrategy.ORDERED,
    ): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, PeerBlueprint<P>>> =
        PropertyDelegateProvider { _, property ->
            val name = Name.parse(property.name)
            val blueprint = SimplePeerBlueprint(
                id = name.toString(),
                addressSource = StaticAddressSource(addresses.toList()),
                failoverStrategy = failoverStrategy,
                driver = driver
            )
            registerPeerConnection(name, blueprint)
            ReadOnlyProperty { _, _ -> blueprint }
        }

    /**
     * Declares a peer connection that uses a discovery service to find its endpoint.
     *
     * @param P The type of the [PeerConnection].
     * @param driver The driver to create the connection instance.
     * @param serviceId The ID of the service to discover.
     * @param failoverStrategy The strategy for handling multiple discovered addresses.
     * @return A [PropertyDelegateProvider] for a [PeerBlueprint].
     */
    public fun <P : PeerConnection> peer(
        driver: PeerDriver<P>,
        serviceId: String,
        failoverStrategy: FailoverStrategy = FailoverStrategy.ROUND_ROBIN,
    ): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, PeerBlueprint<P>>> =
        PropertyDelegateProvider { _, property ->
            val name = Name.parse(property.name)
            val blueprint = SimplePeerBlueprint(
                id = name.toString(),
                addressSource = DiscoveredAddressSource(serviceId),
                failoverStrategy = failoverStrategy,
                driver = driver
            )
            registerPeerConnection(name, blueprint)
            ReadOnlyProperty { _, _ -> blueprint }
        }


    /**
     * Adds a [Feature] to the blueprint, declaring a specific capability.
     */
    public fun feature(feature: Feature) {
        _features[feature.capability] = feature
    }

    /**
     * Configures the device's lifecycle using KStateMachine's DSL.
     * @see space.kscience.controls.composite.dsl.lifecycle.standardLifecycle for a pre-configured lifecycle FSM.
     */
    public fun lifecycle(block: suspend BuildingStateMachine.(device: D, context: LifecycleContext<D>) -> Unit) {
        this._lifecycle = block
    }

    /**
     * Configures the device's operational logic using KStateMachine's DSL. This FSM is separate
     * from the lifecycle FSM and is responsible for managing the device's business logic states
     * (e.g., `Idle`, `Moving`, `AcquiringData`).
     *
     * The `states` parameter must provide a non-empty set of all possible state names, which is used
     * to automatically generate the [OperationalFsmFeature].
     *
     * @param states A set of all state names in this FSM. Used for static feature description.
     * @param block The suspendable lambda to configure the state machine.
     */
    public fun operationalFsm(
        states: Set<String>,
        block: suspend BuildingStateMachine.(device: D, context: LifecycleContext<D>) -> Unit
    ) {
        require(states.isNotEmpty()) { "Operational FSM must have at least one state defined." }
        this._operationalFsm = block
        this._operationalFsmStates = states
    }


    /**
     * Defines the internal reactive logic of the device. This block is executed by the runtime
     * when the device starts. It provides access to the device instance (`this`) and a [DeviceFlows]
     * context to get reactive flows for properties.
     *
     * Example:
     * ```
     * logic { flows ->
     *     val positionFlow = flows.flow(position)
     *     val speedFlow = flows.flow(speed)
     *
     *     combine(positionFlow, speedFlow) { pos, spd ->
     *         //...
     *     }.launchIn(this)
     * }
     * ```
     */
    public fun logic(block: suspend D.(flows: DeviceFlows) -> Unit) {
        this.logic = block
    }

    /**
     * A type-safe and recommended way to declare a remote child device accessed via a peer connection.
     *
     * This function declaratively creates a proxy for a device that exists in another hub. The runtime is
     * responsible for resolving the `hubId` from the provided `via` peer connection and combining it with
     * the `remoteDeviceName` to construct the full network-wide address for communication. This approach
     * eliminates redundancy and prevents configuration errors where the hub ID might not match the peer connection.
     *
     * Note: Property bindings are not supported for remote children as they imply a local, reactive data flow.
     *
     * @param name The local name for the remote child proxy within this composite device.
     * @param blueprint The blueprint of the remote device. This is used for type safety, static analysis,
     *                  and generating the client-side proxy with the correct properties and actions.
     * @param remoteDeviceName The local name of the target device *on the remote hub*.
     * @param via The [PeerBlueprint] instance (typically obtained from a `peer` delegate) that defines
     *            the connection to the remote hub.
     * @param configBuilder A DSL block to configure the local proxy's lifecycle and metadata overrides.
     */
    public fun <C : Device> remoteChild(
        name: Name,
        blueprint: DeviceBlueprint<C>,
        remoteDeviceName: Name,
        via: PeerBlueprint<out PeerConnection>,
        configBuilder: ChildConfigBuilder<D, C>.() -> Unit = {},
    ) {
        val peerName = this._peerConnections.entries.find { it.value == via }?.key
            ?: error("Peer connection blueprint '${via.id}' is not registered in this spec. It must be declared as a property before being used.")

        val builder = ChildConfigBuilder<D, C>().apply(configBuilder)

        if (builder.buildBindings().isNotEmpty()) {
            context.logger.warn { "Property bindings for remote child '$name' are not supported and will be ignored." }
        }

        val config = RemoteChildComponentConfig(
            remoteDeviceName = remoteDeviceName,
            peerName = peerName,
            blueprintId = blueprint.id,
            blueprintVersion = blueprint.version,
            meta = builder.meta
        )
        registerChild(name, config)
    }


    /**
     * Declares a single child component for the device.
     */
    public fun <C : Device> child(
        name: Name,
        blueprint: DeviceBlueprint<C>,
        configBuilder: ChildConfigBuilder<D, C>.() -> Unit = {},
    ) {
        contract {
            callsInPlace(configBuilder, InvocationKind.EXACTLY_ONCE)
        }
        val builder = ChildConfigBuilder<D, C>().apply(configBuilder)

        val config = LocalChildComponentConfig(
            blueprintId = blueprint.id,
            blueprintVersion = blueprint.version,
            meta = builder.meta,
        )
        registerChild(name, config)
    }

    /**
     * Declares a collection of child components of the same type.
     *
     * @param C The type of the child device.
     * @param blueprint The blueprint for all children in this collection.
     * @param names A collection of local names for the children.
     * @param configBuilder A DSL block to configure each child. It receives the child's name.
     */
    public fun <C : Device> children(
        blueprint: DeviceBlueprint<C>,
        names: Collection<Name>,
        configBuilder: ChildConfigBuilder<D, C>.(name: Name) -> Unit = {},
    ) {
        names.forEach { childName ->
            val builder = ChildConfigBuilder<D, C>().apply { configBuilder(childName) }
            val config = LocalChildComponentConfig(
                blueprintId = blueprint.id,
                blueprintVersion = blueprint.version,
                meta = builder.meta,
            )
            registerChild(childName, config)
        }
    }


    /**
     * Builds the final [DeviceBlueprint].
     * This method does NOT perform validation. Validation should be done by the caller
     * using [CompositeSpecValidator.validate] and a [BlueprintRegistry].
     */
    @OptIn(InternalControlsApi::class)
    @PublishedApi
    internal fun build(id: String, deviceContractFqName: String): DeviceBlueprint<D> {
        val finalDriver = this.userDeviceDriver ?: error("Device driver must be defined for the blueprint '$id'.")

        // 1. Auto-add LifecycleFeature using Type-Safe Key ID
        if (!_features.containsKey(LifecycleFeature.ID)) {
            feature(LifecycleFeature())
        }

        // 2. Pack Children into CompositionFeature
        if (_children.isNotEmpty()) {
            // Retrieve existing feature using ID constant
            val existingChildren = (_features[CompositionFeature.ID] as? CompositionFeature)?.children ?: emptyMap()
            feature(CompositionFeature(existingChildren + _children))
        }

        // 3. Pack Peers into ConnectivityFeature
        if (_peerConnections.isNotEmpty()) {
            val existingPeers = (_features[ConnectivityFeature.ID] as? ConnectivityFeature)?.peerConnections ?: emptyMap()
            feature(ConnectivityFeature(existingPeers + _peerConnections))
        }

        // 4. Auto-add OperationalFsmFeature
        if (this._operationalFsm != null || _features.containsKey(OperationalGuardsFeature.ID)) {
            val guardEventNames =
                (_features[OperationalGuardsFeature.ID] as? OperationalGuardsFeature)?.guards?.map {
                    when (it) {
                        is TimedPredicateGuardSpec -> it.postEventSerialName
                        is ValueChangeGuardSpec -> it.postEventSerialName
                        else -> "TODO()"
                    }
                }?.toSet() ?: emptySet()

            val existingFsmFeature = _features[OperationalFsmFeature.ID] as? OperationalFsmFeature

            val updatedFeature = OperationalFsmFeature(
                states = this._operationalFsmStates + (existingFsmFeature?.states ?: emptySet()),
                events = guardEventNames + (existingFsmFeature?.events ?: emptySet())
            )

            feature(updatedFeature)
        }

        return SimpleDeviceBlueprint(
            id = BlueprintId(id),
            version = this.version,
            deviceContractFqName = deviceContractFqName,
            properties = _properties.toMap(),
            actions = _actions.toMap(),
            streams = _streams.toMap(),
            meta = this.meta,
            lifecycle = this._lifecycle,
            operationalFsm = this._operationalFsm,
            logic = this.logic,
            driver = finalDriver,
            features = _features.toMap(),
            propertyReadLogic = propertyReadLogic.toMap(),
            propertyWriteLogic = propertyWriteLogic.toMap(),
            actionExecutors = actionExecutors.toMap(),
            derivedStateFactories = registeredHydrators.toMap(),
            protectedProperties = _protectedProperties.toMap(),
            protectedActions = _protectedActions.toMap(),
            protectedStreams = _protectedStreams.toMap()
        )
    }
}