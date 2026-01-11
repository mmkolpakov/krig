package space.kscience.controls.core.contracts

import space.kscience.controls.core.meta.DeviceActionSpec

/**
 * A typed, low-overhead handle for executing device actions, bypassing the [space.kscience.dataforge.meta.Meta] boxing.
 *
 * @param I The input type of the action.
 * @param O The output type of the action.
 */
public sealed interface ActionAccessor<I, O> {
    /**
     * The specification of the action this accessor is bound to.
     */
    public val actionSpec: DeviceActionSpec<*, I, O>
}

/**
 * A specialized accessor for actions that take no arguments and return no result (e.g., "Stop", "Reset").
 * This ensures absolute zero-allocation overhead during execution.
 */
public interface UnitActionAccessor : ActionAccessor<Unit, Unit> {
    /**
     * Executes the action directly.
     */
    public suspend fun invoke()
}

/**
 * A generic accessor for actions with typed input and output.
 */
public interface TypedActionAccessor<I, O> : ActionAccessor<I, O> {
    /**
     * Executes the action with the given input.
     *
     * @param input The typed input argument.
     * @return The typed result.
     */
    public suspend fun invoke(input: I): O
}