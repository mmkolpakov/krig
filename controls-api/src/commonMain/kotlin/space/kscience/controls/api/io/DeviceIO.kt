package space.kscience.controls.api.io

import space.kscience.dataforge.meta.Meta

/**
 * The root marker interface for all hardware drivers.
 * A driver instance corresponds to one physical or logical connection context.
 *
 * **Lifecycle:**
 * 1. Instantiation (via Factory).
 * 2. [configure] - binding tokens to hardware registers.
 * 3. Operation (Read/Write via specific interfaces).
 * 4. [close] - release resources.
 */
public interface DeviceIO : AutoCloseable {
    /**
     * Configures the driver mapping.
     *
     * @param channelMap A map where:
     * - Key: The [space.kscience.controls.common.tokens.PropertyToken] raw value (Int).
     * - Value: The configuration [Meta] (e.g. `{"register": 4001, "type": "HOLDING"}`).
     *
     * @throws IllegalArgumentException If configuration is invalid.
     */
    public fun configure(channelMap: Map<Int, Meta>)

    /**
     * Closes the connection to the hardware and releases resources.
     */
    override fun close()
}