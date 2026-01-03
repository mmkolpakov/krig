package space.kscience.controls.api.meta

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A base polymorphic interface for attaching strictly-typed, protocol-specific configuration
 * to a Property or Action descriptor.
 */
//TODO remove sealed
@Polymorphic
@Serializable
public sealed interface AdapterBinding

/**
 * A test-specific implementation of [AdapterBinding].
 */
@Serializable
@SerialName("binding.modbus.test")
public data class ModbusTestBinding(
    val unitId: Int,
    val registerAddress: Int,
    val registerType: String,
) : AdapterBinding