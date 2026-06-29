package space.kscience.krig.core.contracts.typed

import space.kscience.dataforge.io.Binary
import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.descriptors.PropertyDescriptor
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.BackendEnvironment

/**
 * Body of a protocol-neutral batch reader: one physical read for a set of property descriptors,
 * returning one `Meta` outcome per descriptor name.
 */
public typealias BatchMetaReadBody =
        suspend BackendEnvironment.(Collection<PropertyDescriptor>) -> Map<Name, OperationOutcome<Meta>>

/** Batch reader body that preserves per-item quality and source timestamps. */
public typealias BatchObservedReadBody =
        suspend BackendEnvironment.(Collection<PropertyDescriptor>) -> Map<Name, OperationOutcome<ObservedValue<Meta?>>>

/** Batch reader body for binary payloads that must not cross `Meta`. */
public typealias BatchBinaryReadBody =
        suspend BackendEnvironment.(Collection<PropertyDescriptor>) -> Map<Name, OperationOutcome<Binary>>

/** Batch writer body for one physical write transaction, returning one outcome per descriptor name. */
public typealias BatchWriteBody =
        suspend BackendEnvironment.(Map<PropertyDescriptor, Meta>) -> Map<Name, OperationOutcome<Unit>>
