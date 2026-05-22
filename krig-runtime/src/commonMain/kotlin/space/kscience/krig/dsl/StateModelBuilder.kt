package space.kscience.krig.dsl

import space.kscience.krig.core.contracts.typed.BackendBuilder
import space.kscience.krig.core.contracts.typed.TypedDeviceBackend
import space.kscience.krig.core.contracts.typed.backend
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract

/** Builds a typed backend around explicit model state. */
public class StateModelBuilder<S : Any> internal constructor(
    private val state: S,
) {
    private val declarations: MutableList<BackendBuilder.() -> Unit> = mutableListOf()

    /** Registers a read-only property backed by [state]. */
    public fun <T> reader(
        spec: DevicePropertyContract<T>,
        read: suspend S.() -> T,
    ) {
        declarations += {
            reader(spec) { state.read() }
        }
    }

    /** Registers a mutable property writer backed by [state]. */
    public fun <T> writer(
        spec: MutableDevicePropertyContract<T>,
        write: suspend S.(T) -> Unit,
    ) {
        declarations += {
            writer(spec) { value -> state.write(value) }
        }
    }

    /** Registers a mutable property backed by [state]. */
    public fun <T> bind(
        spec: MutableDevicePropertyContract<T>,
        read: suspend S.() -> T,
        write: suspend S.(T) -> Unit,
    ) {
        reader(spec, read)
        writer(spec, write)
    }

    /** Registers an action backed by [state]. */
    public fun <I, O> action(
        spec: DeviceActionContract<I, O>,
        execute: suspend S.(I) -> O?,
    ) {
        declarations += {
            action(spec) { input -> state.execute(input) }
        }
    }

    internal fun build(): TypedDeviceBackend = backend {
        declarations.forEach { it() }
    }
}

/** Typed backend for virtual models whose state is explicit and snapshot-friendly. */
public fun <S : Any> stateModel(
    createState: () -> S,
    block: StateModelBuilder<S>.() -> Unit,
): TypedDeviceBackend {
    val builder = StateModelBuilder(createState())
    builder.block()
    return builder.build()
}
