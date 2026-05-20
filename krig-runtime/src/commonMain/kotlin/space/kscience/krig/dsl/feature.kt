package space.kscience.krig.dsl

import kotlin.reflect.KClass
import kotlin.time.Duration
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.attributes.RetryPolicy
import space.kscience.krig.api.features.FeatureSpec
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.capabilities.Capability
import space.kscience.krig.core.contracts.Feature
import space.kscience.krig.core.hook.Hook
import space.kscience.krig.core.pipeline.OperationGate
import space.kscience.krig.core.pipeline.OperationKind
import space.kscience.krig.core.pipeline.OperationObserver
import space.kscience.krig.core.pipeline.PipelineBuilder
import space.kscience.krig.core.pipeline.ReadDecorator

/** Receiver for [feature]'s install lambda. */
public class FeatureScope<C : Any>(
    public val config: C,
    public val pipeline: PipelineBuilder,
) {
    public fun <H : Any> on(hook: Hook<H>, handler: H) {
        pipeline.on(hook, handler)
    }

    public fun gate(kind: OperationKind, gate: OperationGate): Unit = pipeline.gate(kind, gate)
    public fun observe(kind: OperationKind, observer: OperationObserver): Unit = pipeline.observe(kind, observer)
    public fun decorateRead(decorator: ReadDecorator): Unit = pipeline.decorateRead(decorator)
    public fun timeout(kind: OperationKind, timeout: Duration?): Unit = pipeline.timeout(kind, timeout)
    public fun retry(kind: OperationKind, retry: RetryPolicy?): Unit =
        pipeline.retry(kind, retry)
    public fun latencyBudget(kind: OperationKind, budget: Duration?): Unit =
        pipeline.latencyBudget(kind, budget)
    public fun capability(capability: Capability<*>): Unit =
        pipeline.registerCapability(capability)

    public fun operation(kind: OperationKind, block: FeatureOperationScope.() -> Unit) {
        FeatureOperationScope(pipeline, kind).apply(block)
    }
}

/** Scoped policy builder for one [OperationKind]. */
public class FeatureOperationScope internal constructor(
    private val pipeline: PipelineBuilder,
    public val kind: OperationKind,
) {
    public fun gate(gate: OperationGate): Unit = pipeline.gate(kind, gate)
    public fun observe(observer: OperationObserver): Unit = pipeline.observe(kind, observer)

    @OptIn(InternalKrigApi::class)
    public var timeout: Duration?
        get() = pipeline.operationSpec(kind).defaultTimeout
        set(value) {
            pipeline.timeout(kind, value)
        }

    @OptIn(InternalKrigApi::class)
    public var retry: RetryPolicy?
        get() = pipeline.operationSpec(kind).defaultRetry
        set(value) {
            pipeline.retry(kind, value)
        }

    @OptIn(InternalKrigApi::class)
    public var latencyBudget: Duration?
        get() = pipeline.operationSpec(kind).defaultLatencyBudget
        set(value) {
            pipeline.latencyBudget(kind, value)
        }
}

/** Builder for a [feature] without subclassing. */
public fun <C : Any, F : FeatureSpec> feature(
    id: Name,
    specClass: KClass<F>,
    createConfig: () -> C,
    install: FeatureScope<C>.() -> Unit,
): Feature<C, F> = object : Feature<C, F> {
    override val id: Name = id
    override val specClass: KClass<F> = specClass
    override fun createConfig(): C = createConfig()
    override fun install(config: C, pipeline: PipelineBuilder) {
        FeatureScope(config, pipeline).apply(install)
    }
}

/** String-id overload of [feature]. */
public fun <C : Any, F : FeatureSpec> feature(
    id: String,
    specClass: KClass<F>,
    createConfig: () -> C,
    install: FeatureScope<C>.() -> Unit,
): Feature<C, F> = feature(id.asName(), specClass, createConfig, install)

/** Builder for a spec-less [feature]. */
public fun <C : Any> feature(
    id: Name,
    createConfig: () -> C,
    install: FeatureScope<C>.() -> Unit,
): Feature<C, FeatureSpec> = object : Feature<C, FeatureSpec> {
    override val id: Name = id
    override val specClass: KClass<FeatureSpec> = FeatureSpec::class
    override fun createConfig(): C = createConfig()
    override fun install(config: C, pipeline: PipelineBuilder) {
        FeatureScope(config, pipeline).apply(install)
    }

    override fun installFromSpec(spec: FeatureSpec, pipeline: PipelineBuilder) {
        install(configureFromSpec(spec), pipeline)
    }
}

/** String-id overload of [feature]. */
public fun <C : Any> feature(
    id: String,
    createConfig: () -> C,
    install: FeatureScope<C>.() -> Unit,
): Feature<C, FeatureSpec> = feature(id.asName(), createConfig, install)
