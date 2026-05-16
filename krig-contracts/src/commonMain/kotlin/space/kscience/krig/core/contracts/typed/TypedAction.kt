package space.kscience.krig.core.contracts.typed

/** Typed action handle, symmetric with [TypedReader] and [TypedWriter]. */
public interface TypedAction<I, O> {
    public suspend fun execute(input: I): O?
}

/** Generic typed action wrapper. */
public class GenericTypedAction<I, O>(
    private val body: suspend (I) -> O?,
) : TypedAction<I, O> {
    override suspend fun execute(input: I): O? = body(input)
}
