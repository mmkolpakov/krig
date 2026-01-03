package space.kscience.controls.connectivity

import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.controls.core.connectivity.AddressSource
import space.kscience.controls.core.connectivity.DiscoveredAddressSource
import space.kscience.controls.core.connectivity.StaticAddressSource
import space.kscience.controls.core.features.Feature

public val connectivitySerializersModule: SerializersModule = SerializersModule {
    polymorphic(Feature::class) {
        subclass(RemoteMirrorFeature::class)
        subclass(ChildBindingsFeature::class)
    }

    polymorphic(AddressSource::class) {
        subclass(StaticAddressSource::class)
        subclass(DiscoveredAddressSource::class)
    }

    polymorphic(PropertyBinding::class) {
        subclass(ConstPropertyBinding::class)
        subclass(ParentPropertyBinding::class)
        subclass(TransformedPropertyBinding::class)
    }

    polymorphic(PropertyTransformerDescriptor::class) {
        subclass(ToStringTransformerDescriptor::class)
        subclass(LinearTransformDescriptor::class)
    }
}