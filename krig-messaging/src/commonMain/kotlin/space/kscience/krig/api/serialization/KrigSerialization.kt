package space.kscience.krig.api.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/**
 * Source of polymorphic subclass registrations for [krigJson].
 *
 * FeatureSpec and protocol modules expose their registrations as `object` / `val`
 * contributors. The application wires them explicitly at startup — no
 * DataForge-plugin auto-discovery, no classpath scanning, no reflection. Explicit
 * composition is transparent to the reader and works on every KMP target.
 */
public fun interface SerializationContributor {
    public val serializersModule: SerializersModule get() = module()

    /**
     * Returns the module contributed by this source. Callers normally read
     * [serializersModule]; this method exists so `fun interface` lambdas can be
     * used where a one-off module is passed inline.
     */
    public fun module(): SerializersModule
}

/** Wraps an already-built [SerializersModule] as a contributor. */
public fun SerializationContributor(module: SerializersModule): SerializationContributor =
    SerializationContributor { module }

/**
 * Runtime polymorphic registration for REPL/Jupyter and integration modules that do not
 * use krig's KSP index. The serializer is still supplied explicitly, so the helper works
 * on all Kotlin targets without reflection or classpath scanning.
 */
public inline fun <reified B : Any, reified S : B> polymorphicSerializationContributor(
    serializer: KSerializer<S>,
): SerializationContributor = SerializationContributor(
    SerializersModule {
        polymorphic(B::class) {
            subclass(S::class, serializer)
        }
    },
)

/**
 * Aggregates [krigApiSerializersModule] with [contributors] into a single
 * [SerializersModule]. Conflicting polymorphic registrations fail fast; repeated
 * inclusion is accepted only when it resolves to the same serializer.
 */
public fun buildKrigSerializersModule(
    vararg contributors: SerializationContributor,
): SerializersModule = SerializersModule {
    include(krigApiSerializersModule)
    for (contributor in contributors) include(contributor.serializersModule)
}

/**
 * Ready-to-use [Json] configured with [buildKrigSerializersModule] and the
 * wire defaults (`classDiscriminator = "type"`, polymorphic discriminators,
 * `ignoreUnknownKeys`, `encodeDefaults`).
 */
@OptIn(ExperimentalSerializationApi::class)
public fun krigJson(vararg contributors: SerializationContributor): Json = Json {
    serializersModule = buildKrigSerializersModule(*contributors)
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
    classDiscriminatorMode = ClassDiscriminatorMode.POLYMORPHIC
}

/** Compact storage JSON: same polymorphic module, but default fields are omitted. */
public fun krigStorageJson(vararg contributors: SerializationContributor): Json = Json(krigJson(*contributors)) {
    encodeDefaults = false
}
