package space.kscience.krig.api.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.krig.api.addressing.TransportAddress
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.context.DevicePrincipal
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.context.SimplePrincipal
import space.kscience.krig.api.descriptors.ActionDescriptor
import space.kscience.krig.api.descriptors.OperationDescriptor
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.faults.*
import space.kscience.krig.api.features.MetadataFeature
import space.kscience.krig.api.features.PipelineFeatureSpec
import space.kscience.krig.api.messages.ActionCancelMessage
import space.kscience.krig.api.messages.ActionRequestMessage
import space.kscience.krig.api.messages.ActionResponseMessage
import space.kscience.krig.api.messages.BatchReadRequest
import space.kscience.krig.api.messages.BatchReadResponse
import space.kscience.krig.api.messages.BatchWriteRequest
import space.kscience.krig.api.messages.BatchWriteResponse
import space.kscience.krig.api.messages.DeviceAttachedMessage
import space.kscience.krig.api.messages.DeviceDetachedMessage
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceOfflineMessage
import space.kscience.krig.api.messages.DeviceOnlineMessage
import space.kscience.krig.api.messages.FaultMessage
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.PropertyReadRequest
import space.kscience.krig.api.messages.PropertyReadResponse
import space.kscience.krig.api.messages.PropertyWriteRequest
import space.kscience.krig.api.messages.PropertyWriteResponse
import space.kscience.krig.api.messages.TaskStateChangedMessage
import space.kscience.krig.api.messages.TimeSeriesRowMessage

/**
 * Polymorphic registrations for open hierarchies.
 */
@OptIn(ExperimentalSerializationApi::class)
public val krigApiSerializersModule: SerializersModule = SerializersModule {
    polymorphic(TransportAddress::class)

    // OperationFault is an open hierarchy keyed by a stable faultType Name: a peer on a newer
    // version may send a faultType this build does not know. Decode it into GenericOperationFault
    // (the body still carries faultType/message/details) instead of failing the whole message.
    polymorphic(OperationFault::class) {
        subclass(AuthorizationFault::class)
        subclass(GenericOperationFault::class)
        subclass(InvalidStateFault::class)
        subclass(TimeoutFault::class)
        subclass(TransportFault::class)
        subclass(ValidationFault::class)
        defaultDeserializer { GenericOperationFault.serializer() }
    }

    polymorphic(DeviceMessage::class) {
        subclass(FaultMessage::class)
        subclass(PropertyChangedMessage::class)
        subclass(DeviceAttachedMessage::class)
        subclass(DeviceDetachedMessage::class)
        subclass(DeviceOnlineMessage::class)
        subclass(DeviceOfflineMessage::class)
        subclass(PropertyReadRequest::class)
        subclass(PropertyReadResponse::class)
        subclass(PropertyWriteRequest::class)
        subclass(PropertyWriteResponse::class)
        subclass(ActionRequestMessage::class)
        subclass(ActionResponseMessage::class)
        subclass(ActionCancelMessage::class)
        subclass(TaskStateChangedMessage::class)
        subclass(BatchReadRequest::class)
        subclass(BatchReadResponse::class)
        subclass(BatchWriteRequest::class)
        subclass(BatchWriteResponse::class)
        subclass(TimeSeriesRowMessage::class)
    }

    polymorphic(PipelineFeatureSpec::class) {
        subclass(MetadataFeature::class)
    }

    polymorphic(Principal::class) {
        subclass(AnonymousPrincipal::class)
        subclass(SimplePrincipal::class)
        subclass(DevicePrincipal::class)
    }

    polymorphic(OperationDescriptor::class) {
        subclass(ActionDescriptor::class)
        subclass(PropertyDescriptor::class)
    }
}
