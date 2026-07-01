package space.kscience.krig.core.expressions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.DefaultQualityPolicy
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualityCode
import space.kscience.krig.api.data.QualityNamespaces
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.data.StandardQualityCodes
import space.kscience.krig.api.data.combineAll
import space.kscience.krig.api.data.toDataQuality
import space.kscience.krig.api.expressions.*
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.services.AuthorizationException
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.state.DeviceState
import space.kscience.krig.core.state.combine
import space.kscience.krig.core.state.map
import space.kscience.dataforge.meta.double
import space.kscience.dataforge.names.Name
import kotlin.time.Instant

public class ExpressionContext internal constructor(
    private val scope: CoroutineScope,
    private val resolve: suspend (Name) -> Device,
    private val principal: Principal,
) {
    internal fun scope(): CoroutineScope = scope
    internal suspend fun device(name: Name): Device = resolve(name)
    internal fun auth(): Principal = principal

    public companion object {
        public fun from(
            scope: CoroutineScope,
            devices: Map<Name, Device>,
            principal: Principal,
        ): ExpressionContext = ExpressionContext(scope, { name ->
            devices[name] ?: error("Device '$name' not found in context")
        }, principal)
    }
}

/** Compiles a [NumericExpression] tree into a reactive [DeviceState<Double>]. */
public suspend fun NumericExpression.compile(ctx: ExpressionContext): DeviceState<Double> {
    val initialBindings = VirtualExpressionPlanner.initialSnapshot(this, ctx)
    return compile(ctx, initialBindings)
}

private suspend fun NumericExpression.compile(
    ctx: ExpressionContext,
    initialBindings: Map<Binding, ObservedValue<Double?>>,
): DeviceState<Double> =
    when (this) {
        is Binding -> bindingState(this, ctx, initialBindings.getValue(this))
        is Constant -> constantState(value)
        is Unary -> {
            val op = Float64Operations.unary(operation)
            argument.compile(ctx, initialBindings).map { op(it ?: Double.NaN) }
        }
        is Binary -> {
            val l = left.compile(ctx, initialBindings)
            val r = right.compile(ctx, initialBindings)
            val op = Float64Operations.binary(operation)
            l.combine(r) { lv, rv -> op(lv ?: Double.NaN, rv ?: Double.NaN) }
        }
        is NAry -> {
            val states = operands.map { it.compile(ctx, initialBindings) }
            val op = Float64Operations.nary(operation)
            combineAll(states, op)
        }
    }

internal object VirtualExpressionPlanner {
    suspend fun initialSnapshot(
        expression: NumericExpression,
        ctx: ExpressionContext,
    ): Map<Binding, ObservedValue<Double?>> {
        val bindings = expression.bindings()
        if (bindings.isEmpty()) return emptyMap()

        val result = LinkedHashMap<Binding, ObservedValue<Double?>>(bindings.size)
        for ((deviceName, deviceBindings) in bindings.groupBy { it.deviceName }) {
            val device = ctx.device(deviceName)
            val batch = try {
                device.readBatchOutcome(deviceBindings.map { it.propertyName }.toSet())
            } catch (e: CancellationException) {
                throw e
            } catch (_: OperationFaultException) {
                deviceBindings.forEach { binding ->
                    result[binding] = unavailableBindingValue(device, binding)
                }
                continue
            } catch (_: AuthorizationException) {
                deviceBindings.forEach { binding ->
                    result[binding] = unavailableBindingValue(device, binding)
                }
                continue
            }
            for (binding in deviceBindings) {
                result[binding] = when (val outcome = batch[binding.propertyName]) {
                    is OperationOutcome.Ok -> outcome.value.map { meta -> meta?.double }
                    is OperationOutcome.Fail -> ObservedValue(
                        null,
                        device.clock.now(),
                        outcome.fault.toDataQuality(QualityNamespaces.Expression, DefaultQualityPolicy),
                    )
                    null -> unavailableBindingValue(device, binding)
                }
            }
        }
        return result
    }
}

private suspend fun bindingState(
    binding: Binding,
    ctx: ExpressionContext,
    initial: ObservedValue<Double?>,
): DeviceState<Double> {
    val device = ctx.device(binding.deviceName)
    val unavailableQuality = bindingUnavailableQuality(binding)

    val messages = try {
        device.subscribe(ctx.auth())
    } catch (e: CancellationException) {
        throw e
    } catch (_: OperationFaultException) {
        return unavailableBindingState(device, unavailableQuality)
    } catch (_: AuthorizationException) {
        return unavailableBindingState(device, unavailableQuality)
    }

    val state = messages
        .map { it.payload }
        .filterIsInstance<PropertyChangedMessage>()
        .filter { it.property == binding.propertyName }
        .mapNotNull { msg ->
            val d = msg.value.double ?: return@mapNotNull null
            ObservedValue<Double?>(d, msg.time, msg.quality)
        }
        .distinctUntilChanged { old, new -> old.value == new.value && old.quality == new.quality }
        .catch { e ->
            when (e) {
                is CancellationException -> throw e
                is OperationFaultException, is AuthorizationException ->
                    emit(ObservedValue(null, device.clock.now(), unavailableQuality))
                else -> throw e
            }
        }
        .stateIn(
            scope = ctx.scope(),
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = initial,
        )
    return object : DeviceState<Double> {
        override val stateValue get() = state.value
        override val stateFlow get() = state
    }
}

private fun unavailableBindingValue(device: Device, binding: Binding): ObservedValue<Double?> =
    ObservedValue(null, device.clock.now(), bindingUnavailableQuality(binding))

private fun bindingUnavailableQuality(binding: Binding): DataQuality =
    expressionQuality(
        StandardQualityCodes.ExpressionUnavailable,
        "Binding '${binding.deviceName}.${binding.propertyName}' is unavailable",
    )

private fun unavailableBindingState(device: Device, quality: DataQuality): DeviceState<Double> =
    object : DeviceState<Double> {
        override val stateValue = ObservedValue<Double?>(null, device.clock.now(), quality)
        override val stateFlow: Flow<ObservedValue<Double?>> = flowOf(stateValue)
    }

private fun constantState(value: Double): DeviceState<Double> =
    object : DeviceState<Double> {
        override val stateValue = ObservedValue(value, Instant.DISTANT_PAST, DataQuality.GOOD)
        override val stateFlow: Flow<ObservedValue<Double?>> = flowOf(stateValue)
    }

private fun combineAll(
    states: List<DeviceState<Double>>,
    reducer: (List<Double>) -> Double,
): DeviceState<Double> = object : DeviceState<Double> {
    override val stateValue: ObservedValue<Double?>
        get() {
            val values = states.map { it.stateValue }
            val time = values.maxOfOrNull { it.time } ?: Instant.DISTANT_PAST
            val quality = values.map { it.quality }.combineAll()
            val ds = values.mapNotNull { it.value }
            return if (ds.size == states.size) {
                ObservedValue(reducer(ds), time, quality)
            } else {
                ObservedValue(
                    null,
                    time,
                    quality.combine(expressionQuality(StandardQualityCodes.ExpressionMissing, "One or more operands are missing")),
                )
            }
        }
    override val stateFlow: Flow<ObservedValue<Double?>> = combine(states.map { it.stateFlow }) { values ->
        val time = values.maxOf { it.time }
        val quality = values.map { it.quality }.combineAll()
        val ds = values.mapNotNull { it.value }
        if (ds.size == states.size) {
            ObservedValue(reducer(ds), time, quality)
        } else {
            ObservedValue<Double?>(
                null,
                time,
                    quality.combine(expressionQuality(StandardQualityCodes.ExpressionMissing, "One or more operands are missing")),
            )
        }
    }
}

private fun expressionQuality(code: QualityCode, detail: String): DataQuality =
    DataQuality(
        severity = QualitySeverity.BAD,
        code = code,
        detail = detail,
    )
