package space.kscience.krig.api.data

import space.kscience.krig.api.faults.OperationFault
import space.kscience.dataforge.names.toStringUnescaped
import kotlin.jvm.JvmInline

/**
 * Boundary policy that converts operation/protocol faults into observation quality.
 *
 * Adapters with native protocol status should create [ObservedValue] directly. This
 * policy is the SDK fallback for predictable operation faults.
 */
public fun interface QualityPolicy {
    public fun qualityForFault(fault: OperationFault, namespace: QualityNamespace): DataQuality
}

/** Stable domain for SDK-produced quality codes. */
@JvmInline
public value class QualityNamespace(public val id: String) {
    init {
        require(id.isNotBlank()) { "Quality namespace must not be blank." }
    }

    public fun code(localCode: String): QualityCode {
        require(localCode.isNotBlank()) { "Quality code suffix must not be blank." }
        return QualityCode("$id.$localCode")
    }
}

private const val KRIG_QUALITY_PREFIX: String = "krig"

private fun krigQualityNamespace(name: String): QualityNamespace =
    QualityNamespace("$KRIG_QUALITY_PREFIX.$name")

/** Stable domains for SDK-produced quality codes. */
public object QualityNamespaces {
    public val Operation: QualityNamespace = krigQualityNamespace("operation")
    public val Acquisition: QualityNamespace = krigQualityNamespace("acquisition")
    public val DataPlatform: QualityNamespace = krigQualityNamespace("data-platform")
    public val Expression: QualityNamespace = krigQualityNamespace("expression")
}

/** Stable quality codes used by SDK-level fallbacks. */
public object StandardQualityCodes {
    public val Stale: QualityCode = QualityNamespaces.Operation.code("stale")
    public val ExpressionUnavailable: QualityCode = QualityNamespaces.Expression.code("unavailable")
    public val ExpressionMissing: QualityCode = QualityNamespaces.Expression.code("missing")
}

/** Default conservative policy: predictable operation faults make the observation bad. */
public object DefaultQualityPolicy : QualityPolicy {
    override fun qualityForFault(fault: OperationFault, namespace: QualityNamespace): DataQuality {
        val faultCode = fault.faultType.toStringUnescaped().replace('.', '-').lowercase()
        return DataQuality(
            severity = QualitySeverity.BAD,
            code = namespace.code(faultCode),
            detail = if (fault.message == fault.faultType.toString()) {
                fault.faultType.toStringUnescaped()
            } else {
                fault.message
            },
        )
    }
}

/** Converts [fault] with [policy], using a stable namespaced quality code. */
public fun OperationFault.toDataQuality(
    namespace: QualityNamespace = QualityNamespaces.Operation,
    policy: QualityPolicy = DefaultQualityPolicy,
): DataQuality = policy.qualityForFault(this, namespace)

/** Quality for a last-known value whose freshness is no longer guaranteed. */
public fun staleDataQuality(
    code: QualityCode = StandardQualityCodes.Stale,
    detail: String? = null,
    severity: QualitySeverity = QualitySeverity.UNCERTAIN,
): DataQuality = DataQuality(severity = severity, code = code, detail = detail)
