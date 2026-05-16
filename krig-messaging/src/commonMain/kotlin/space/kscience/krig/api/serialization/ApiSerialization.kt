package space.kscience.krig.api.serialization

import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.krig.api.addressing.TransportAddress
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.context.SimplePrincipal
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.MemberAttribute
import space.kscience.krig.api.descriptors.MemberDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.descriptors.attributes.*
import space.kscience.krig.api.faults.*
import space.kscience.krig.api.features.DeviceFeatureSpec
import space.kscience.krig.api.features.MetadataFeature
import space.kscience.krig.api.messages.ActionFaultMessage
import space.kscience.krig.api.messages.ActionRequestMessage
import space.kscience.krig.api.messages.ActionResponseMessage
import space.kscience.krig.api.messages.DeviceAttachedMessage
import space.kscience.krig.api.messages.DeviceDetachedMessage
import space.kscience.krig.api.messages.DeviceErrorMessage
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceOfflineMessage
import space.kscience.krig.api.messages.DeviceOnlineMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.PropertyFaultMessage
import space.kscience.krig.api.messages.PropertyReadRequest
import space.kscience.krig.api.messages.PropertyReadResponse
import space.kscience.krig.api.messages.PropertyWriteRequest
import space.kscience.krig.api.messages.PropertyWriteResponse
import space.kscience.krig.api.meta.AdapterBinding
import space.kscience.krig.api.meta.MemberTag
import space.kscience.krig.api.meta.ProfileTag

/**
 * Polymorphic registrations for open hierarchies. Sealed hierarchies
 * (DeviceDepartureReason, HubEvent) use the auto-generated sealed polymorphic
 * serializer and are not listed here.
 */
public val krigApiSerializersModule: SerializersModule = SerializersModule {
    polymorphic(TransportAddress::class)

    polymorphic(MemberAttribute::class) {
        subclass(MetadataAttribute::class)
        subclass(BehaviorAttribute::class)
        subclass(AccessAttribute::class)
        subclass(BindingsAttribute::class)
    }

    polymorphic(DeviceFault::class) {
        subclass(AuthorizationFault::class)
        subclass(GenericDeviceFault::class)
        subclass(InvalidStateFault::class)
        subclass(TimeoutFault::class)
        subclass(ValidationFault::class)
    }

    polymorphic(DeviceMessage::class) {
        subclass(ActionFaultMessage::class)
        subclass(DeviceErrorMessage::class)
        subclass(PropertyChangedMessage::class)
        subclass(DeviceAttachedMessage::class)
        subclass(DeviceDetachedMessage::class)
        subclass(DeviceOnlineMessage::class)
        subclass(DeviceOfflineMessage::class)
        subclass(PropertyReadRequest::class)
        subclass(PropertyReadResponse::class)
        subclass(PropertyWriteRequest::class)
        subclass(PropertyWriteResponse::class)
        subclass(PropertyFaultMessage::class)
        subclass(ActionRequestMessage::class)
        subclass(ActionResponseMessage::class)
    }

    polymorphic(DeviceFeatureSpec::class) {
        subclass(MetadataFeature::class)
    }

    polymorphic(AdapterBinding::class)

    polymorphic(MemberTag::class) {
        subclass(ProfileTag::class)
        defaultDeserializer { UnknownMemberTag.serializer() }
    }

    polymorphic(Principal::class) {
        subclass(AnonymousPrincipal::class)
        subclass(SimplePrincipal::class)
    }

    polymorphic(MemberDescriptor::class) {
        subclass(ActionDescriptor::class)
        subclass(PropertyDescriptor::class)
    }
}

@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("tag.unknown")
public data class UnknownMemberTag(val type: String) : MemberTag
