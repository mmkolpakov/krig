package space.kscience.controls.composite.protocol.modbus

import space.kscience.controls.composite.protocol.api.ProtocolAdapter
import space.kscience.dataforge.context.AbstractPlugin
import space.kscience.dataforge.context.Context
import space.kscience.dataforge.context.PluginFactory
import space.kscience.dataforge.context.PluginTag
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name

/**
 * A DataForge plugin that registers the [ModbusProtocolAdapter] as a service.
 * This makes the adapter discoverable by a runtime that needs to instantiate protocol-based devices.
 */
public class ModbusPlugin : AbstractPlugin() {
    override val tag: PluginTag get() = Companion.tag

    private val adapter: Any = TODO()

    override fun content(target: String): Map<Name, Any> = when (target) {
        // TODO A future standard target for protocol adapters
        ProtocolAdapter::class.simpleName -> mapOf(Name.of("modbus") to adapter)
        else -> emptyMap()
    }

    public companion object : PluginFactory<ModbusPlugin> {
        override val tag: PluginTag = PluginTag("protocol.modbus", group = PluginTag.DATAFORGE_GROUP)
        override fun build(context: Context, meta: Meta): ModbusPlugin = ModbusPlugin()
    }
}