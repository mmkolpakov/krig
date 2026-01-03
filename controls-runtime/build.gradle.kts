import space.kscience.gradle.KScienceNativeTarget

plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
}

description = "A runtime implementation for composite devices, including lifecycle management, error handling, and transactions."

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
        api(projects.controlsMetrics)
        api(projects.controlsDsl)
        api(projects.controlsPersistence)
        implementation(libs.kotlinx.atomicfu)
        api(libs.kstatemachine.core)
        api(libs.kstatemachine.coroutines)
        api(libs.kstatemachine.serialization)
        api(projects.controlsFeatureFsm)
    }

    commonTest {
        implementation(kotlin("test"))
        implementation(libs.logback.classic)
        implementation(libs.kotlinx.coroutines.test)
    }
}

readme {
    maturity = space.kscience.gradle.Maturity.PROTOTYPE
}