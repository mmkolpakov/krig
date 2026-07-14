package space.kscience.krig.build.architecture.fixtures.external

import kotlin.reflect.KClass

class ExternalType

interface ExternalBound

interface ExternalSupertype<T>

class ExternalReceiver

class ExternalContext

class ExternalSuspendResult

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class ExternalApiMarker(val type: KClass<*>)

object InlineOnly {
    @JvmStatic
    fun touch() = Unit
}

object InlineDefaultOnly {
    fun value(): String = "inline"
}

object InlineDefaultLambdaOnly {
    fun transform(value: String): String = value
}

object InlineMemberDefaultOnly {
    fun value(): String = "inline-member"
}

object InlineSuspendDefaultOnly {
    fun value(): String = "inline-suspend"
}

class InlineLocalAnnotationOnly

object NonInlineDefaultOnly {
    fun value(): String = "non-inline"
}

object InlinePropertyOnly {
    fun value(): String = "inline-property"
}

object NonInlinePropertyOnly {
    fun value(): String = "non-inline-property"
}

interface InlineAnonymousExternalMarker<T>

class InlineAnonymousGenericOnly

class InlineAnonymousFieldOnly

class InlineAnonymousMethodOnly

object NonInlineHelperDefaultOnly {
    fun value(): String = "non-inline-helper-default"
}

object NamedInlineImplementationOnly {
    fun value(): String = "named-implementation"
}

class NamedInlineHelper {
    fun implementation(): String = NamedInlineImplementationOnly.value()
}

@Target(AnnotationTarget.PROPERTY_SETTER)
@Retention(AnnotationRetention.BINARY)
annotation class PrivateSetterImplementationMarker

@Target(AnnotationTarget.PROPERTY_SETTER)
@Retention(AnnotationRetention.BINARY)
annotation class PublicSetterSurfaceMarker

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.BINARY)
annotation class PrivateFieldImplementationMarker

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.BINARY)
annotation class PublicFieldSurfaceMarker

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.BINARY)
annotation class PublishedFieldSurfaceMarker
