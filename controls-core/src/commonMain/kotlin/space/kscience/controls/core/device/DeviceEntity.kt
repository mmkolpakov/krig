package space.kscience.controls.core.device

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import space.kscience.controls.api.data.DataQuality
import space.kscience.controls.api.data.RawValue
import space.kscience.controls.api.data.coerceToBoolean
import space.kscience.controls.api.data.coerceToDouble
import space.kscience.controls.api.data.coerceToLong
import space.kscience.controls.api.io.DeviceIO
import space.kscience.controls.api.io.ScalarOutputIO
import space.kscience.controls.api.lifecycle.DeviceLifecycleState
import space.kscience.controls.api.messages.DeviceMessage
import space.kscience.controls.api.structure.DeviceManifest
import space.kscience.controls.common.tokens.PropertyToken
import space.kscience.controls.common.tokens.PropertyToken.Companion.TYPE_BOOLEAN
import space.kscience.controls.common.tokens.PropertyToken.Companion.TYPE_DOUBLE
import space.kscience.controls.common.tokens.PropertyToken.Companion.TYPE_LONG
import space.kscience.controls.common.tokens.PropertyToken.Companion.TYPE_META
import space.kscience.controls.core.InternalControlsApi
import space.kscience.controls.core.bundle.DeviceHub
import space.kscience.controls.core.capability.Capability
import space.kscience.controls.core.capability.CapabilitySandbox
import space.kscience.controls.core.faults.DeviceLifecycleException
import space.kscience.controls.core.state.PropertyRegistry
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.ContextAware
import space.kscience.dataforge.context.Logger
import space.kscience.dataforge.context.error
import space.kscience.dataforge.context.logger
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.misc.Named
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.provider.Provider
import kotlin.time.Clock

/**
 * The runtime Actor representing a live device.
 *
 * It enforces the **Single Writer Principle** for hardware access, ensuring that all
 * write operations and action executions are serialized through a single coroutine (`actorLoop`).
 *
 * **Architecture:**
 * 1. **Supervisor:** Manages the lifecycle of capabilities and the driver connection via [DeviceLifecycleFsm].
 * 2. **Actor:** Processes a mailbox of [DeviceCommand]s sequentially.
 * 3. **Container:** Holds the [PropertyRegistry] (State) and [Capability] list (Logic).
 *
 * @param hub The parent hub managing this device.
 * @param name The unique ID of this device.
 * @param manifest The static definition (blueprint).
 * @param driver The configured hardware interface.
 * @param properties The allocated property storage.
 * @param physicalMask A boolean array indexed by property token. True indicates the property is bound to the driver.
 */
@OptIn(InternalControlsApi::class)
public class DeviceEntity @InternalControlsApi internal constructor(
    public val hub: DeviceHub,
    override val name: Name,
    public val manifest: DeviceManifest,
    private val driver: DeviceIO,
    public val properties: PropertyRegistry,
    private val physicalMask: BooleanArray
) : ContextAware, Named, Provider, CoroutineScope {

    override val context: Context get() = hub.context
    public val logger: Logger = context.logger

    // SupervisorJob ensures that failure in a child coroutine (Capability) doesn't kill the whole device
    private val supervisorJob = SupervisorJob(context.coroutineContext[Job])
    override val coroutineContext: kotlin.coroutines.CoroutineContext =
        context.coroutineContext + supervisorJob + CoroutineName("Device-$name")

    private val capabilities = ArrayList<Capability>()
    internal val actionRegistry = ActionRegistry()

    // Unbounded channel to prevent blocking senders.
    private val mailbox = Channel<DeviceCommand>(Channel.UNLIMITED)

    // Event bus for outgoing messages (from Capabilities)
    private val _messageFlow = MutableSharedFlow<DeviceMessage>(extraBufferCapacity = 64)
    public val messageFlow: SharedFlow<DeviceMessage> get() = _messageFlow.asSharedFlow()

    private val fsm = DeviceLifecycleFsm(
        scope = this,
        scopeLogger = logger,
        onStartHook = {
            // Start all capabilities
            capabilities.forEach { it.start() }
        },
        onStopHook = {
            val errors = mutableListOf<Throwable>()

            // Stop capabilities in reverse order (best effort)
            capabilities.reversed().forEach {
                try { it.stop() } catch (e: Throwable) { errors.add(e) }
            }

            // Close driver
            try { driver.close() } catch (e: Throwable) { errors.add(e) }

            if (errors.isNotEmpty()) {
                val first = errors.first()
                errors.drop(1).forEach { first.addSuppressed(it) }
                throw first
            }
        }
    )

    /**
     * The public lifecycle state of the device.
     */
    public val lifecycle: StateFlow<DeviceLifecycleState> = fsm.stateFlow

    init {
        // Start the Actor Loop
        launch {
            for (command in mailbox) {
                if (!isActive) break
                try {
                    processCommand(command)
                } catch (e: Throwable) {
                    // Fail the command promise
                    command.response.completeExceptionally(e)
                    logger.error(e) { "Device Actor failed to process command $command" }
                    if (e is CancellationException) throw e
                }
            }
            // Start the FSM machine
            fsm.start()
        }
    }

    /**
     * Binds the capabilities to this entity.
     * This must be called exactly once during the Construction Phase by [space.kscience.controls.core.factory.DeviceFactory].
     */
    @InternalControlsApi
    internal fun bindCapabilities(caps: List<Capability>) {
        if (capabilities.isNotEmpty()) error("Capabilities already bound")
        capabilities.addAll(caps)
    }

    /**
     * Asynchronously starts the device.
     * Triggers the transition to [DeviceLifecycleState.Starting] and then [DeviceLifecycleState.Running].
     */
    public suspend fun start() {
        fsm.dispatch(LifecycleEvent.Start)
    }

    /**
     * Asynchronously stops the device.
     * Triggers the transition to [DeviceLifecycleState.Stopping] and then [DeviceLifecycleState.Stopped].
     */
    public suspend fun stop() {
        fsm.dispatch(LifecycleEvent.Stop)
    }

    /**
     * Enqueues a device command for execution.
     *
     * @throws DeviceLifecycleException if the device is not in [DeviceLifecycleState.Running].
     */
    public suspend fun sendCommand(command: DeviceCommand) {
        if (lifecycle.value != DeviceLifecycleState.Running) {
            throw DeviceLifecycleException(name, "Device is not running (Current state: ${lifecycle.value})")
        }
        mailbox.send(command)
        // We do not wait for result here, the caller waits on command.response
    }

    /**
     * High-level helper to write a property by name.
     * Handles token resolution and type coercion before creating the command.
     */
    public suspend fun writeProperty(propertyName: Name, value: RawValue) {
        val token = properties.getToken(propertyName)
            ?: throw IllegalArgumentException("Unknown property '$propertyName' in device '$name'")

        val deferred = CompletableDeferred<Unit>()
        val command = try {
            when (token.typeOrdinal) {
                TYPE_DOUBLE -> {
                    val coerced = value.coerceToDouble()
                    if (coerced.isNaN()) error("Cannot coerce $value to Double for property $propertyName")
                    WriteDoubleCommand(token.raw, coerced, deferred)
                }
                TYPE_LONG -> WriteLongCommand(token.raw, value.coerceToLong(), deferred)
                TYPE_BOOLEAN -> WriteBooleanCommand(token.raw, value.coerceToBoolean(), deferred)
                TYPE_META -> {
                    // Requires unwrapping RawValue.M or throwing
                    if (value is RawValue.M) WriteMetaCommand(token.raw, value.value, deferred)
                    else error("Cannot coerce $value to Meta for property $propertyName")
                }
                else -> error("Invalid storage type ${token.typeOrdinal} for token $token")
            }
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        }

        sendCommand(command)
        deferred.await()
    }

    /**
     * The heart of the Actor. Processes commands strictly sequentially.
     * Handles strict type checking and interaction with the Driver.
     */
    private suspend fun processCommand(command: DeviceCommand) {
        // Double check lifecycle inside the loop to handle fast stop
        if (lifecycle.value != DeviceLifecycleState.Running) {
            throw DeviceLifecycleException(name, "Device halted during command execution")
        }

        // Optimized time source. Ideally should use a specialized provider,
        // but Clock.System.now().toEpochMilliseconds() is standard KMP.
        val now = Clock.System.now().toEpochMilliseconds()

        when (command) {
            is WriteDoubleCommand -> {
                // Only write to driver if mapped physically
                if (isPhysical(command.token)) {
                    val io = driver as? ScalarOutputIO
                        ?: throw UnsupportedOperationException("Driver does not support Scalar Output (Write Double)")
                    io.writeDouble(command.token, command.value)
                }
                // Always update memory (Logical or Physical shadow)
                properties.updateDouble(
                    PropertyToken(command.token),
                    command.value,
                    DataQuality.OK.quality.ordinal,
                    now
                )
                command.response.complete(Unit)
            }
            is WriteLongCommand -> {
                if (isPhysical(command.token)) {
                    val io = driver as? ScalarOutputIO
                        ?: throw UnsupportedOperationException("Driver does not support Scalar Output (Write Long)")
                    io.writeLong(command.token, command.value)
                }
                properties.updateLong(
                    PropertyToken(command.token),
                    command.value,
                    DataQuality.OK.quality.ordinal,
                    now
                )
                command.response.complete(Unit)
            }
            is WriteBooleanCommand -> {
                if (isPhysical(command.token)) {
                    val io = driver as? ScalarOutputIO
                        ?: throw UnsupportedOperationException("Driver does not support Scalar Output (Write Boolean)")
                    io.writeBoolean(command.token, command.value)
                }
                properties.updateBoolean(
                    PropertyToken(command.token),
                    command.value,
                    DataQuality.OK.quality.ordinal,
                    now
                )
                command.response.complete(Unit)
            }
            is WriteMetaCommand -> {
                if (isPhysical(command.token)) {
                    val io = driver as? ScalarOutputIO
                        ?: throw UnsupportedOperationException("Driver does not support Scalar Output (Write Meta)")
                    io.writeMeta(command.token, command.value)
                }
                properties.updateMeta(
                    PropertyToken(command.token),
                    command.value,
                    DataQuality.OK.quality.ordinal,
                    now
                )
                command.response.complete(Unit)
            }
            is ExecuteActionCommand -> {
                val handler = actionRegistry.get(command.action)
                if (handler == null) {
                    throw IllegalArgumentException("Action '${command.action}' not found")
                } else {
                    val resultMeta = handler(command.argument)
                    command.result.complete(resultMeta)
                }
            }
        }
    }

    /**
     * Checks if the token corresponds to a physical channel mapped to the driver.
     * Uses O(1) array lookup.
     */
    private fun isPhysical(tokenRaw: Int): Boolean {
        // Fast bitwise extraction of index (assuming tokenRaw matches index in our simple implementation)
        val index = PropertyToken(tokenRaw).index
        if (index >= physicalMask.size) return false
        return physicalMask[index]
    }

    /**
     * Factory method to create a restricted context for Capabilities.
     */
    internal fun createSandbox(): CapabilitySandbox {
        return CapabilitySandbox(this, driver, _messageFlow)
    }

    override fun content(target: String): Map<Name, Any> = properties.content(target)
}