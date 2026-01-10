package space.kscience.controls.connectivity

import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.controls.connectivity.config.AddressSource
import space.kscience.controls.connectivity.config.DiscoveredAddressSource
import space.kscience.controls.connectivity.config.StaticAddressSource
import space.kscience.controls.api.features.Feature

public val connectivitySerializersModule: SerializersModule = SerializersModule {
    polymorphic(Feature::class) {
        subclass(RemoteMirrorFeature::class)
        subclass(ChildBindingsFeature::class)
        subclass(BinaryDataFeature::class)
        subclass(CompositionFeature::class)
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