package space.kscience.krig.core.contracts.typed

/** Typed action handle, symmetric with [TypedReader] and [TypedWriter]. SAM: `TypedAction { body(it) }`. */
public fun interface TypedAction<I, O> {
    public suspend fun execute(input: I): O?
}
