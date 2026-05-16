package space.kscience.krig.api.serialization

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

/**
 * Source of polymorphic subclass registrations for [krigJson].
 *
 * DeviceFeatureSpec and protocol modules expose their registrations as `object` / `val`
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
 * Aggregates [krigApiSerializersModule] with [contributors] into a single
 * [SerializersModule]. Contributors are merged in declaration order; a later
 * contributor overrides an earlier one for clashing polymorphic keys.
 */
public fun buildKrigSerializersModule(
    vararg contributors: SerializationContributor,
): SerializersModule = SerializersModule {
    include(krigApiSerializersModule)
    for (contributor in contributors) include(contributor.serializersModule)
}

/**
 * Ready-to-use [Json] configured with [buildKrigSerializersModule] and the
 * wire defaults (`classDiscriminator = "type"`, `ignoreUnknownKeys`, `encodeDefaults`).
 */
public fun krigJson(vararg contributors: SerializationContributor): Json = Json {
    serializersModule = buildKrigSerializersModule(*contributors)
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}
