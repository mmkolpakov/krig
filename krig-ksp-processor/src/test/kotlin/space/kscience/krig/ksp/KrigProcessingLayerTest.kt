package space.kscience.krig.ksp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KrigProcessingLayerTest {

    @Test
    fun explicitLayerValuesWinOverPlatformInference() {
        assertEquals(KrigProcessingLayer.COMMON, resolveProcessingLayer("common", listOf("JVM")))
        assertEquals(KrigProcessingLayer.JVM_AGGREGATION, resolveProcessingLayer("jvmAggregation", listOf("metadata")))
        assertEquals(KrigProcessingLayer.ALL, resolveProcessingLayer("all", listOf("metadata")))
    }

    @Test
    fun autoLayerSeparatesCommonMetadataFromJvmAggregation() {
        assertEquals(KrigProcessingLayer.COMMON, resolveProcessingLayer("auto", listOf("metadata")))
        assertEquals(KrigProcessingLayer.COMMON, resolveProcessingLayer("auto", listOf("Common")))
        assertEquals(KrigProcessingLayer.COMMON, resolveProcessingLayer("auto", listOf("JVM", "JS", "Native")))
        assertEquals(KrigProcessingLayer.JVM_AGGREGATION, resolveProcessingLayer("auto", listOf("JVM")))
        assertEquals(KrigProcessingLayer.COMMON, resolveProcessingLayer("auto", listOf("JS")))
    }

    @Test
    fun missingLayerKeepsBackwardCompatibleAllGeneratorsMode() {
        assertEquals(KrigProcessingLayer.ALL, resolveProcessingLayer(null, listOf("JVM")))
        assertEquals(KrigProcessingLayer.ALL, resolveProcessingLayer("", listOf("metadata")))
    }

    @Test
    fun invalidLayerValueFailsLoudly() {
        assertFailsWith<IllegalStateException> {
            resolveProcessingLayer("surprise", listOf("JVM"))
        }
    }
}
