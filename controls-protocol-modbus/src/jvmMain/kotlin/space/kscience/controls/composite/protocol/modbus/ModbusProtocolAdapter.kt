package space.kscience.controls.composite.protocol.modbus

import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.kscience.controls.api.descriptors.ActionDescriptor
import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.controls.composite.ports.Port
import space.kscience.controls.composite.protocol.api.ProtocolAdapter
import space.kscience.controls.composite.protocol.api.ProtocolChannel
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.meta.Meta

/**
 * A [ProtocolAdapter] implementation for the Modbus protocol.
 * This adapter uses metadata from property and action descriptors to construct
 * and interpret Modbus requests and responses.
 *
 * ### Property Descriptor Meta Configuration
 *
 * To use this adapter, a `PropertyDescriptor` must include a `modbus` metadata block:
 * ```kotlin
 * meta {
 *     "modbus" {
 *         "unitId" put 1
 *         "type" put "holdingRegister" // or "coil", "inputRegister", "discreteInput"
 *         "address" put 100
 *         // For multi-register values:
 *         "converter" put "float64" // Name of a IOFormat<T> factory
 *         "count" put 4 // Number of registers (e.g., 4 registers for float64)
 *     }
 * }
 * ```
 */
public class ModbusProtocolAdapter : ProtocolAdapter {

    override fun createChannel(
        port: Port,
        context: Context
    ): ProtocolChannel {
        TODO("Not yet implemented")
    }
}