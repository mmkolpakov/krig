package space.kscience.krig.dsl

import kotlin.reflect.KClass
import kotlin.time.Duration
import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.attributes.RetryPolicy
import space.kscience.krig.api.features.PipelineFeatureSpec
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.capabilities.Capability
import space.kscience.krig.core.features.PipelineFeature
import space.kscience.krig.core.hook.Hook
import space.kscience.krig.core.pipeline.OperationGate
import space.kscience.krig.core.pipeline.OperationKind
import space.kscience.krig.core.pipeline.OperationObserver
import space.kscience.krig.core.pipeline.PipelineBuilder
import space.kscience.krig.core.pipeline.ReadDecorator

/** Scope for translating PipelineFeature config into pipeline policy and local capabilities. */
public class PipelineFeatureScope<C : Any>(
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

    public fun operation(kind: OperationKind, block: PipelineFeatureOperationScope.() -> Unit) {
        PipelineFeatureOperationScope(pipeline, kind).apply(block)
    }
}

/** Scoped policy builder for one [OperationKind]. */
public class PipelineFeatureOperationScope internal constructor(
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

/** Creates a [PipelineFeature] without subclassing. */
public fun <C : Any, F : PipelineFeatureSpec> pipelineFeature(
    id: Name,
    specClass: KClass<F>,
    createConfig: () -> C,
    install: PipelineFeatureScope<C>.() -> Unit,
): PipelineFeature<C, F> = object : PipelineFeature<C, F> {
    override val id: Name = id
    override val specClass: KClass<F> = specClass
    override fun createConfig(): C = createConfig()
    override fun install(config: C, pipeline: PipelineBuilder) {
        PipelineFeatureScope(config, pipeline).apply(install)
    }
}

/** String-id overload of [PipelineFeature]. */
public fun <C : Any, F : PipelineFeatureSpec> pipelineFeature(
    id: String,
    specClass: KClass<F>,
    createConfig: () -> C,
    install: PipelineFeatureScope<C>.() -> Unit,
): PipelineFeature<C, F> = pipelineFeature(id.asName(), specClass, createConfig, install)

/** Creates a spec-less [PipelineFeature]. */
public fun <C : Any> pipelineFeature(
    id: Name,
    createConfig: () -> C,
    install: PipelineFeatureScope<C>.() -> Unit,
): PipelineFeature<C, PipelineFeatureSpec> = object : PipelineFeature<C, PipelineFeatureSpec> {
    override val id: Name = id
    override val specClass: KClass<PipelineFeatureSpec> = PipelineFeatureSpec::class
    override fun createConfig(): C = createConfig()
    override fun install(config: C, pipeline: PipelineBuilder) {
        PipelineFeatureScope(config, pipeline).apply(install)
    }

    override fun installFromSpec(spec: PipelineFeatureSpec, pipeline: PipelineBuilder) {
        install(configureFromSpec(spec), pipeline)
    }
}

/** String-id overload of [PipelineFeature]. */
public fun <C : Any> pipelineFeature(
    id: String,
    createConfig: () -> C,
    install: PipelineFeatureScope<C>.() -> Unit,
): PipelineFeature<C, PipelineFeatureSpec> = pipelineFeature(id.asName(), createConfig, install)
