package space.kscience.krig.api.serialization

import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.krig.api.addressing.TransportAddress
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.context.SimplePrincipal
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.OperationDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.*
import space.kscience.krig.api.features.MetadataFeature
import space.kscience.krig.api.features.PipelineFeatureSpec
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

/**
 * Polymorphic registrations for open hierarchies.
 */
public val krigApiSerializersModule: SerializersModule = SerializersModule {
    polymorphic(TransportAddress::class)

    polymorphic(OperationFault::class) {
        subclass(AuthorizationFault::class)
        subclass(GenericOperationFault::class)
        subclass(InvalidStateFault::class)
        subclass(TimeoutFault::class)
        subclass(TransportFault::class)
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

    polymorphic(PipelineFeatureSpec::class) {
        subclass(MetadataFeature::class)
    }

    polymorphic(AdapterBinding::class)

    polymorphic(Principal::class) {
        subclass(AnonymousPrincipal::class)
        subclass(SimplePrincipal::class)
    }

    polymorphic(OperationDescriptor::class) {
        subclass(ActionDescriptor::class)
        subclass(PropertyDescriptor::class)
    }
}
