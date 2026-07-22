package space.kscience.krig.ksp

import com.google.devtools.ksp.processing.JsPlatformInfo
import com.google.devtools.ksp.processing.JvmPlatformInfo
import com.google.devtools.ksp.processing.NativePlatformInfo
import com.google.devtools.ksp.processing.PlatformInfo
import com.google.devtools.ksp.processing.UnknownPlatformInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KrigProcessingLayerTest {

    @Test
    fun explicitLayerValuesWinOverPlatformInference() {
        assertEquals(KrigProcessingLayer.COMMON, resolveProcessingLayer("common", listOf(JVM)))
        assertEquals(
            KrigProcessingLayer.JVM_AGGREGATION,
            resolveProcessingLayer("jvmAggregation", listOf(unknown("metadata"))),
        )
        assertEquals(KrigProcessingLayer.ALL, resolveProcessingLayer("all", listOf(unknown("metadata"))))
    }

    @Test
    fun autoLayerGeneratesCompleteJvmOutputAndCommonSafeOutputElsewhere() {
        assertEquals(KrigProcessingLayer.ALL, resolveProcessingLayer("auto", listOf(JVM)))
        assertEquals(KrigProcessingLayer.COMMON, resolveProcessingLayer("auto", listOf(JS)))
        assertEquals(KrigProcessingLayer.COMMON, resolveProcessingLayer("auto", listOf(NATIVE)))
        assertEquals(KrigProcessingLayer.COMMON, resolveProcessingLayer("auto", listOf(unknown("metadata"))))
        assertEquals(KrigProcessingLayer.COMMON, resolveProcessingLayer("auto", listOf(JVM, JS)))
        assertEquals(KrigProcessingLayer.COMMON, resolveProcessingLayer("auto", emptyList()))
    }

    @Test
    fun missingLayerUsesPlatformInference() {
        assertEquals(KrigProcessingLayer.ALL, resolveProcessingLayer(null, listOf(JVM)))
        assertEquals(KrigProcessingLayer.COMMON, resolveProcessingLayer("", listOf(unknown("metadata"))))
    }

    @Test
    fun invalidLayerValueFailsLoudly() {
        assertFailsWith<IllegalStateException> {
            resolveProcessingLayer("surprise", listOf(JVM))
        }
    }

    private companion object {
        val JVM: JvmPlatformInfo = object : JvmPlatformInfo {
            override val platformName: String = "JVM"
            override val jvmTarget: String = "21"
            override val jvmDefaultMode: String = "enable"
        }

        val JS: JsPlatformInfo = object : JsPlatformInfo {
            override val platformName: String = "JS"
        }

        val NATIVE: NativePlatformInfo = object : NativePlatformInfo {
            override val platformName: String = "Native"
            override val targetName: String = "mingw_x64"
        }

        fun unknown(name: String): PlatformInfo = object : UnknownPlatformInfo {
            override val platformName: String = name
        }
    }
}
