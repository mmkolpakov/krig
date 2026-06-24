package space.kscience.krig.core.pipeline

import space.kscience.dataforge.meta.Meta
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.result.OperationOutcome

/**
 * Value-substituting wrapper for the **batch** read plane, the coalescing counterpart of
 * [ReadDecorator]. Where [ReadDecorator] sits on a typed single-property reader, a batch read is the
 * Meta/observed plane (`Collection<Name> → Map<Name, OperationOutcome<ObservedValue<Meta?>>>`), so a
 * cache, mock, or rate-limit that must see whole-batch acquisitions implements this contract.
 *
 * Decorators compose outside-in: the first installed is the outermost wrapper.
 */
public interface BatchReadDecorator {
    public fun decorate(
        original: suspend (Collection<Name>) -> Map<Name, OperationOutcome<ObservedValue<Meta?>>>,
    ): suspend (Collection<Name>) -> Map<Name, OperationOutcome<ObservedValue<Meta?>>>
}
