package space.kscience.controls.fsm

import kotlinx.serialization.Serializable
import space.kscience.controls.api.serialization.SchemeAsMetaSerializer
import space.kscience.controls.api.utils.duration
import space.kscience.dataforge.meta.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The mode for persisting device state.
 */
@Serializable
public enum class PersistenceMode {
    /** Save the device state only when it is stopped gracefully. */
    ON_STOP,

    /** Save the device state periodically while it is running. */
    PERIODIC
}

/**
 * Configuration for device state persistence, implemented as a [Scheme] for type-safe DSL configuration.
 *
 * @property enabled If `true`, enables state persistence for the device.
 * @property mode The [PersistenceMode] to use.
 * @property interval The time interval for saving in `PERIODIC` mode.
 * @property restoreOnStart If `true`, attempts to restore the device's state upon starting.
 */
@Serializable(with = PersistenceConfig.Serializer::class)
public class PersistenceConfig : Scheme() {
    public var enabled: Boolean by boolean(false)
    public var mode: PersistenceMode by enum(PersistenceMode.ON_STOP)
    public var interval: Duration by duration(60.seconds)
    public var restoreOnStart: Boolean by boolean(true)

    public companion object : SchemeSpec<PersistenceConfig>(::PersistenceConfig)

    /**
     * Custom serializer for PersistenceConfig that delegates to Meta serialization.
     */
    public object Serializer : SchemeAsMetaSerializer<PersistenceConfig>(PersistenceConfig)
}

/**
 * A comprehensive configuration for a device's lifecycle management within a hub,
 * implemented as a [Scheme] for type-safe DSL configuration and automatic meta conversion.
 * It is now serializable via a custom serializer that treats it as Meta.
 *
 * @property lifecycleMode Defines how the device's lifecycle is tied to its parent.
 * @property startTimeout The maximum duration to wait for the device to start. Null means no timeout.
 * @property stopTimeout The maximum duration to wait for the device to stop. Null means no timeout.
 * @property onError The strategy to apply when this device fails as a child of another device.
 * @property restartPolicy The policy for automatically restarting this device upon failure.
 * @property lazyAttach If true, the device will be instantiated only on first access.
 * @property persistence Configuration for saving and restoring the device's state.
 */
@Serializable(with = DeviceLifecycleConfig.Serializer::class)
public class DeviceLifecycleConfig : Scheme() {
    public var lifecycleMode: LifecycleMode by enum(LifecycleMode.LINKED)
    public var startTimeout: Duration? by duration
    public var stopTimeout: Duration? by duration
    public var onError: ChildDeviceErrorHandler by enum(ChildDeviceErrorHandler.RESTART)
    public var restartPolicy: RestartPolicy by scheme(RestartPolicy)
    public var lazyAttach: Boolean by boolean(false)
    public var persistence: PersistenceConfig by scheme(PersistenceConfig)

    init {
        //Set default values for nullable properties after init
        if(startTimeout == null) startTimeout = 30.seconds
        if(stopTimeout == null) stopTimeout = 10.seconds
        // Validation
        startTimeout?.let { require(!it.isNegative()) { "Start timeout must not be negative." } }
        stopTimeout?.let { require(!it.isNegative()) { "Stop timeout must not be negative." } }
    }

    public companion object : SchemeSpec<DeviceLifecycleConfig>(::DeviceLifecycleConfig) {
        /**
         * The default, immutable configuration for a device lifecycle.
         */
        public val DEFAULT: DeviceLifecycleConfig by lazy { DeviceLifecycleConfig() }
    }

    /**
     * Custom serializer for DeviceLifecycleConfig that delegates to Meta serialization.
     */
    public object Serializer : SchemeAsMetaSerializer<DeviceLifecycleConfig>(DeviceLifecycleConfig)
}

/**
 * A lazily-initialized [MetaConverter] for [DeviceLifecycleConfig].
 *
 * Usage:
 * ```
 * val meta = MetaConverter.deviceLifecycleConfig.convert(myConfig)
 * val config = MetaConverter.deviceLifecycleConfig.read(myMeta)
 * ```
 */
public val MetaConverter.Companion.deviceLifecycleConfig: MetaConverter<DeviceLifecycleConfig> by lazy {
    DeviceLifecycleConfig
}