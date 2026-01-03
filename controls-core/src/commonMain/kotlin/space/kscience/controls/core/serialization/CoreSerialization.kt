package space.kscience.controls.core.serialization

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.controls.api.descriptors.ActionDescriptor
import space.kscience.controls.api.descriptors.MemberDescriptor
import space.kscience.controls.api.descriptors.PropertyDescriptor
import space.kscience.controls.api.descriptors.StreamDescriptor
import space.kscience.controls.api.composition.ChildComponentConfig
import space.kscience.controls.api.composition.LocalChildComponentConfig
import space.kscience.controls.api.composition.RemoteChildComponentConfig
import space.kscience.controls.api.connectivity.AddressSource
import space.kscience.controls.api.connectivity.DiscoveredAddressSource
import space.kscience.controls.api.connectivity.StaticAddressSource
import space.kscience.controls.api.features.Feature
import space.kscience.controls.api.messages.ActionFaultMessage
import space.kscience.controls.api.messages.BinaryDataRequest
import space.kscience.controls.api.messages.BinaryReadyNotification
import space.kscience.controls.api.messages.DescriptionMessage
import space.kscience.controls.api.messages.DeviceAttachedMessage
import space.kscience.controls.api.messages.DeviceDetachedMessage
import space.kscience.controls.api.messages.DeviceErrorMessage
import space.kscience.controls.api.messages.DeviceMessage
import space.kscience.controls.api.messages.PredicateChangedMessage
import space.kscience.controls.api.messages.PropertyChangedMessage
import space.kscience.controls.core.features.MetadataFeature
import space.kscience.controls.core.features.ReconfigurableFeature
import space.kscience.controls.api.meta.AdapterBinding
import space.kscience.controls.api.meta.AliasTag
import space.kscience.controls.api.meta.MemberTag
import space.kscience.controls.api.meta.ModbusTestBinding
import space.kscience.controls.api.meta.ProfileTag
import space.kscience.controls.api.validation.CustomPredicateRuleDescriptor
import space.kscience.controls.api.validation.MinLengthRuleDescriptor
import space.kscience.controls.api.validation.RangeRuleDescriptor
import space.kscience.controls.api.validation.RegexRuleDescriptor
import space.kscience.controls.api.validation.ValidationRuleDescriptor
import space.kscience.controls.common.meta.baseJson

/**
 * A `SerializersModule` containing polymorphic serialization rules for all sealed interfaces
 * and classes defined within the `controls-core` module.
 */
public val controlsCoreSerializersModule: SerializersModule = SerializersModule {
    polymorphic(AddressSource::class) {
        subclass(DiscoveredAddressSource::class)
        subclass(StaticAddressSource::class)
    }

    polymorphic(ChildComponentConfig::class) {
        subclass(LocalChildComponentConfig::class)
        subclass(RemoteChildComponentConfig::class)
    }

    polymorphic(DeviceMessage::class) {
        subclass(ActionFaultMessage::class)
        subclass(DescriptionMessage::class)
        subclass(DeviceErrorMessage::class)
        subclass(PropertyChangedMessage::class)
        subclass(DeviceAttachedMessage::class)
        subclass(DeviceDetachedMessage::class)
        subclass(PredicateChangedMessage::class)
        subclass(BinaryDataRequest::class)
        subclass(BinaryReadyNotification::class)
    }

    polymorphic(Feature::class) {
        subclass(ReconfigurableFeature::class)
        subclass(MetadataFeature::class)
    }

    polymorphic(MemberDescriptor::class) {
        subclass(ActionDescriptor::class)
        subclass(PropertyDescriptor::class)
        subclass(StreamDescriptor::class)
    }

    polymorphic(MemberTag::class) {
        subclass(AliasTag::class)
        subclass(ProfileTag::class)
    }

    polymorphic(AdapterBinding::class) {
        subclass(ModbusTestBinding::class)
    }

    polymorphic(ValidationRuleDescriptor::class) {
        subclass(CustomPredicateRuleDescriptor::class)
        subclass(MinLengthRuleDescriptor::class)
        subclass(RangeRuleDescriptor::class)
        subclass(RegexRuleDescriptor::class)
    }
}

/**
 * A default, convenience `Json` instance configured *only* with the `controlsCoreSerializersModule`.
 *
 * This instance is suitable for serialization tasks that are strictly confined to the `controls-core` module,
 * such as in internal logic or property delegates that handle simple, non-polymorphic types.
 * It serves as a sensible default to avoid boilerplate in common cases.
 */
public val defaultCoreJson: Json = Json(baseJson) {
    serializersModule = controlsCoreSerializersModule
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}