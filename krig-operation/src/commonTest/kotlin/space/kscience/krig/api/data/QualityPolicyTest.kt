package space.kscience.krig.api.data

import space.kscience.krig.api.faults.TimeoutFault
import kotlin.test.Test
import kotlin.test.assertEquals

class QualityPolicyTest {
    @Test
    fun defaultPolicyMapsFaultToBadNamespacedQuality() {
        val quality = TimeoutFault().toDataQuality(QualityNamespaces.Acquisition)

        assertEquals(QualitySeverity.BAD, quality.severity)
        assertEquals(QualityNamespaces.Acquisition.code("fault-timeout"), quality.code)
        assertEquals("fault.timeout", quality.detail)
    }

    @Test
    fun staleQualityIsUncertainByDefault() {
        val quality = staleDataQuality()

        assertEquals(QualitySeverity.UNCERTAIN, quality.severity)
        assertEquals(StandardQualityCodes.Stale, quality.code)
    }
}
