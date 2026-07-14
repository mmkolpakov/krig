@file:Suppress("NOTHING_TO_INLINE")

package space.kscience.krig.build.architecture.fixtures.api

import space.kscience.krig.build.architecture.fixtures.external.ExternalApiMarker
import space.kscience.krig.build.architecture.fixtures.external.ExternalBound
import space.kscience.krig.build.architecture.fixtures.external.ExternalReceiver
import space.kscience.krig.build.architecture.fixtures.external.ExternalSupertype
import space.kscience.krig.build.architecture.fixtures.external.ExternalSuspendResult
import space.kscience.krig.build.architecture.fixtures.external.ExternalType
import space.kscience.krig.build.architecture.fixtures.external.InlineDefaultOnly
import space.kscience.krig.build.architecture.fixtures.external.InlineDefaultLambdaOnly
import space.kscience.krig.build.architecture.fixtures.external.InlineMemberDefaultOnly
import space.kscience.krig.build.architecture.fixtures.external.InlineNestedCarrierOnly
import space.kscience.krig.build.architecture.fixtures.external.InlineOnly
import space.kscience.krig.build.architecture.fixtures.external.InlinePropertyOnly
import space.kscience.krig.build.architecture.fixtures.external.InlineSuspendDefaultOnly
import space.kscience.krig.build.architecture.fixtures.external.InlineAnonymousExternalMarker
import space.kscience.krig.build.architecture.fixtures.external.InlineAnonymousFieldOnly
import space.kscience.krig.build.architecture.fixtures.external.InlineAnonymousGenericOnly
import space.kscience.krig.build.architecture.fixtures.external.InlineAnonymousMethodOnly
import space.kscience.krig.build.architecture.fixtures.external.NamedInlineHelper
import space.kscience.krig.build.architecture.fixtures.external.NonInlineDefaultOnly
import space.kscience.krig.build.architecture.fixtures.external.NonInlineHelperDefaultOnly
import space.kscience.krig.build.architecture.fixtures.external.PrivateFieldImplementationMarker
import space.kscience.krig.build.architecture.fixtures.external.PrivateSetterImplementationMarker
import space.kscience.krig.build.architecture.fixtures.external.PublicFieldSurfaceMarker
import space.kscience.krig.build.architecture.fixtures.external.PublicSetterSurfaceMarker
import space.kscience.krig.build.architecture.fixtures.external.PublishedFieldSurfaceMarker
import space.kscience.krig.build.architecture.fixtures.external.NonInlinePropertyOnly

class PublicOuter {
    class Nested
}

private class PrivateOuter {
    class Nested
}

data class DataFixture(val value: ExternalType)

@ExternalApiMarker(ExternalType::class)
class GenericSurface<T : ExternalBound>(
    val value: ExternalType,
    val item: T? = null,
) : ExternalSupertype<ExternalType> {
    @ExternalApiMarker(ExternalType::class)
    suspend fun <R : ExternalBound> ExternalReceiver.transform(
        value: Map<ExternalType, R>,
    ): ExternalSuspendResult {
        value.size
        return ExternalSuspendResult()
    }
}

typealias ExternalAlias = ExternalType

@PublishedApi
internal fun publishedHelper(): ExternalType = ExternalType()

@PublishedApi
internal class PublishedInternal

inline fun inlineLeak(block: () -> Unit) {
    InlineOnly.touch()
    block()
}

inline fun inlineDefaultLeak(
    value: String = InlineDefaultOnly.value(),
    transform: (String) -> String = { InlineDefaultLambdaOnly.transform(it) },
): String = transform(value)

class InlineDefaultMember {
    inline fun memberDefault(value: String = InlineMemberDefaultOnly.value()): String = value
}

suspend inline fun inlineSuspendDefault(
    value: String = InlineSuspendDefaultOnly.value(),
): String = value

class JavaBridgeSurface {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun bridge(value: ExternalType = ExternalType(), count: Int = 0): ExternalType {
            count.hashCode()
            return value
        }
    }
}

fun nonInlineDefaultLeak(value: String = NonInlineDefaultOnly.value()): String = value

fun invokePropertyBlock(block: () -> String): String = block()

inline val inlinePropertyLeak: String
    get() = invokePropertyBlock { InlinePropertyOnly.value() }

val nonInlinePropertyLeak: String
    get() = invokePropertyBlock { NonInlinePropertyOnly.value() }

inline fun inlineAnonymousObjectLeak(): Any = object : InlineAnonymousExternalMarker<InlineAnonymousGenericOnly> {
    val field: InlineAnonymousFieldOnly? = null

    fun map(value: InlineAnonymousMethodOnly): InlineAnonymousMethodOnly = value
}

inline fun inlineNestedCarrierLeak(): String = invokePropertyBlock {
    invokePropertyBlock { InlineNestedCarrierOnly.value() }
}

fun nonInlineHelperWithDefault(value: String = NonInlineHelperDefaultOnly.value()): String = value

inline fun inlineCallsNonInlineHelperDefault(): String = nonInlineHelperWithDefault()

inline fun inlineNamedImplementationBoundary(): Any = NamedInlineHelper()

@set:PrivateSetterImplementationMarker
var privateSetterImplementation: String = "private"
    private set

@set:PublicSetterSurfaceMarker
var publicSetterSurface: String = "public"

@field:PrivateFieldImplementationMarker
val privateFieldImplementation: String = "private-field"

@JvmField
@field:PublicFieldSurfaceMarker
val publicFieldSurface: String = "public-field"

@PublishedApi
@field:PublishedFieldSurfaceMarker
internal val publishedInlineField: String = "published-field"

inline val publishedFieldSurface: String
    get() = publishedInlineField
