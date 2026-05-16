package space.kscience.krig.dsl

import kotlin.reflect.KClass
import space.kscience.krig.api.features.DeviceFeatureSpec
import space.kscience.krig.core.contracts.DeviceFeatureInstaller
import space.kscience.krig.core.hook.Hook
import space.kscience.krig.core.pipeline.TypedPipelineBuilder

/** Receiver for [deviceFeatureInstaller]'s install lambda. Carries the runtime [config] and [pipeline]. */
public class DeviceFeatureInstallerScope<C : Any>(
    public val config: C,
    public val pipeline: TypedPipelineBuilder,
) {
    public fun <H : Any> on(hook: Hook<H>, handler: H) {
        pipeline.on(hook, handler)
    }
}

/** Inline builder for a [DeviceFeatureInstaller] without subclassing. */
public fun <C : Any, F : DeviceFeatureSpec> deviceFeatureInstaller(
    id: String,
    featureClass: KClass<F>,
    createConfig: () -> C,
    install: DeviceFeatureInstallerScope<C>.() -> Unit,
): DeviceFeatureInstaller<C, F> = object : DeviceFeatureInstaller<C, F> {
    override val id: String = id
    override val featureClass: KClass<F> = featureClass
    override fun createConfig(): C = createConfig()
    override fun install(config: C, pipeline: TypedPipelineBuilder) {
        DeviceFeatureInstallerScope(config, pipeline).apply(install)
    }
}
