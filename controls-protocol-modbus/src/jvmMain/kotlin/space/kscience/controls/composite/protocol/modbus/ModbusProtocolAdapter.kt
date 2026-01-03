package space.kscience.controls.composite.protocol.modbus

import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.kscience.controls.api.descriptors.ActionDescriptor
import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.controls.composite.ports.Port
import space.kscience.controls.composite.protocol.api.ProtocolAdapter
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

    private suspend fun <R> withMaster(port: Port, block: suspend (ModbusTCPMaster) -> R): R {
        //TODO not simplified implementation (now creates a new master for each request)
        //TODO production-ready version would use a connection pool.
        val master = ModbusTCPMaster("localhost", 502) // TODO Port and host should come from Port meta
        return withContext(Dispatchers.IO) {
            master.connect()
            try {
                block(master)
            } finally {
                master.disconnect()
            }
        }
    }

    override suspend fun readProperty(port: Port, property: PropertyDescriptor): Meta {
        TODO()
//        val modbusMeta = property.metaDescriptor["modbus"] ?: error("Modbus meta is not defined for property ${property.name}")
////        TODO local val modbusMeta: MetaDescriptor -> None of the following candidates is applicable:
////val Value.int: Int
////val Meta?.int: Int?
//        val unitId = modbusMeta["unitId"].int ?: 0
//        //        TODO local val modbusMeta: MetaDescriptor -> None of the following candidates is applicable:
////val Value.int: Int
////val Meta?.int: Int?
//        val address = modbusMeta["address"].int ?: error("Modbus address is not defined for property ${property.name}")
//
//        return withMaster(port) { master ->
//            //        TODO local val modbusMeta: MetaDescriptor -> None of the following candidates is applicable:
////val Meta?.string: String?
////val val Value.string: String
//            when (val type = modbusMeta["type"].string) {
//                "coil" -> Meta(master.readCoils(unitId, address, 1).getBit(0))
//                "discreteInput" -> Meta(master.readInputDiscretes(unitId, address, 1).getBit(0))
//                "inputRegister" -> {
//                    val registers: Array<InputRegister> = master.readInputRegisters(unitId, address, 1)
//                    Meta(registers.first().toShort())
//                }
//
//                "holdingRegister" -> {
//                    val registers: Array<Register> = master.readMultipleRegisters(unitId, address, 1)
//                    Meta(registers.first().toShort())
//                }
//                else -> error("Unsupported Modbus property type: $type")
//            }
//        }
    }

    override suspend fun writeProperty(port: Port, property: PropertyDescriptor, value: Meta) {
        TODO()
//        val modbusMeta = property.metaDescriptor["modbus"] ?: error("Modbus meta is not defined for property ${property.name}")
////        TODO
//        val unitId = modbusMeta["unitId"].int ?: 0
//        val address = modbusMeta["address"].int ?: error("Modbus address is not defined for property ${property.name}")
//
//        withMaster(port) { master ->
//            //        TODO
//            when (val type = modbusMeta["type"].string) {
//                "coil" -> master.writeCoil(unitId, address, value.boolean ?: error("Value is not a boolean"))
//                "holdingRegister" -> master.writeSingleRegister(unitId, address, value.int?.let {
//                    com.ghgande.j2mod.modbus.procimg.SimpleRegister(it)
//                } ?: error("Value is not an integer"))
//                else -> error("Modbus property type $type is not writable")
//            }
//        }
    }

    override suspend fun execute(port: Port, action: ActionDescriptor, argument: Meta?): Meta {
        error("Modbus actions are not yet implemented in this adapter.")
    }
}