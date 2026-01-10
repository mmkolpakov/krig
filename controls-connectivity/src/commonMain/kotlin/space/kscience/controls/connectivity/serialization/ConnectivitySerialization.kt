package space.kscience.controls.connectivity.serialization

import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.controls.api.addressing.TransportAddress
import space.kscience.controls.api.composition.ChildComponentConfig
import space.kscience.controls.connectivity.connectivity.RemoteChildComponentConfig
import space.kscience.controls.connectivity.addressing.IpcAddress
import space.kscience.controls.connectivity.addressing.TcpAddress
import space.kscience.controls.connectivity.config.AddressSource
import space.kscience.controls.connectivity.config.DiscoveredAddressSource
import space.kscience.controls.connectivity.config.StaticAddressSource

public val controlsConnectivitySerializersModule: SerializersModule = SerializersModule {
    polymorphic(ChildComponentConfig::class) {
        subclass(RemoteChildComponentConfig::class)
    }
    polymorphic(TransportAddress::class) {
        subclass(TcpAddress::class)
        subclass(IpcAddress::class)
    }
    polymorphic(AddressSource::class) {
        subclass(StaticAddressSource::class)
        subclass(DiscoveredAddressSource::class)
    }
}