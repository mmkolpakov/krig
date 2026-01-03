@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import space.kscience.gradle.KScienceNativeTarget

plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
}

description = "A persistence layer for controls-kt, allowing to save and restore device states."

kscience {
    jvm()
    js()
    native {
        setTargets(
            KScienceNativeTarget.linuxX64,
            KScienceNativeTarget.mingwX64
        )
    }
    wasmJs()

    useCoroutines()
    useSerialization()

    commonMain {
        api(projects.controlsCore)
        api(libs.dataforge.io)
        implementation(spclibs.kotlinx.coroutines.core)
        api(libs.okio)
    }

    commonTest {
        implementation(kotlin("test"))
        implementation(libs.okio.fakefilesystem)
    }

    wasmJsMain{
        implementation("org.jetbrains.kotlinx:kotlinx-browser:0.5.0")
        js {
            browser {
                testTask {
                    useKarma {
                        useFirefoxHeadless()
                        useChromeHeadless()
                    }
                }
            }
        }
    }
}

readme {
    maturity = space.kscience.gradle.Maturity.EXPERIMENTAL
}