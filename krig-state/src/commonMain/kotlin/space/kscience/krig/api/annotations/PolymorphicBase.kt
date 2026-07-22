package space.kscience.krig.api.annotations

/**
 * Marks an accessible, non-sealed, non-generic interface as a polymorphic base discoverable
 * by the KSP `SerializersModuleGenerator`. A custom serializer on the base is not supported.
 * Every accessible, non-generic `@Serializable` class or object with a compiler-generated
 * concrete serializer is auto-registered in the generated module.
 *
 * Subtypes must have an object-shaped serializer compatible with KRig's default `type`
 * class discriminator; serialized property names and `@JsonNames` aliases cannot use `type`.
 * Enum and value declarations are excluded from auto-registration; alternate serializer shapes
 * require an explicitly configured module and compatible wire format.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class PolymorphicBase
