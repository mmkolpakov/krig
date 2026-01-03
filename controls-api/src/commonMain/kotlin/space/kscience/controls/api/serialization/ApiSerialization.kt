package space.kscience.controls.api.serialization

import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import space.kscience.controls.api.descriptors.*
import space.kscience.controls.api.descriptors.attributes.*
import space.kscience.controls.api.events.*
import space.kscience.controls.api.faults.*
//import space.kscience.controls.api.messages.*

/**
 * The serialization module for `controls-api`.
 * It registers all polymorphic types defined in the API module.
 */
public val controlsApiSerializersModule: SerializersModule = SerializersModule {

    // Member Attributes
    polymorphic(MemberAttribute::class) {
        subclass(MetadataAttribute::class)
        subclass(BehaviorAttribute::class)
        subclass(AccessAttribute::class)
        subclass(TelemetryAttribute::class)
        subclass(BindingsAttribute::class)
        subclass(PersistenceAttribute::class)
        subclass(ValidationAttribute::class)
        subclass(ImplementationAttribute::class)
        subclass(FsmAttribute::class)
        subclass(StreamAttribute::class)
    }

    // Descriptors
    polymorphic(MemberDescriptor::class) {
//        subclass(ActionDescriptor::class)
//        subclass(PropertyDescriptor::class)
//        subclass(StreamDescriptor::class)
    }

    // Device Faults
    polymorphic(DeviceFault::class) {
        subclass(AuthenticationFault::class)
        subclass(AuthorizationFault::class)
        subclass(GenericDeviceFault::class)
        subclass(InvalidStateFault::class)
        subclass(NotFoundFault::class)
        subclass(PreconditionFault::class)
        subclass(ResourceBusyFault::class)
        subclass(TimeoutFault::class)
        subclass(ValidationFault::class)
    }

    // Messages
//    polymorphic(DeviceMessage::class) {
//        subclass(ActionFaultMessage::class)
//        subclass(DescriptionMessage::class)
//        subclass(DeviceErrorMessage::class)
//        subclass(PropertyChangedMessage::class)
//        subclass(DeviceAttachedMessage::class)
//        subclass(DeviceDetachedMessage::class)
//        subclass(PredicateChangedMessage::class)
//        subclass(BinaryDataRequest::class)
//        subclass(BinaryReadyNotification::class)
//    }

    // Execution Events
    polymorphic(ExecutionEvent::class) {
        subclass(ActionCompleted::class)
        subclass(ActionDispatched::class)
        subclass(ActionStarted::class)
        subclass(CacheHit::class)
        subclass(CacheMiss::class)
        subclass(FaultReported::class)
    }
}