import space.kscience.gradle.KScienceNativeTarget

plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
}

description = "Pure multiplatform old for composite devices"

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
        api(projects.controlsFeatureAutomation)
        api(projects.controlsFeatureAlarms)
        api(projects.controlsFeatureFsm)
        api(projects.controlsFeatureConnectivity)
        api(projects.controlsFeatureTelemetry)
        api(projects.controlsServiceApi)
        api(projects.controlsValidation)
        api(libs.dataforge.context)
        api(libs.dataforge.io)
        api(libs.dataforge.data)
        implementation(libs.kotlinx.atomicfu)
        api(libs.kstatemachine.core)
        api(libs.kotlinx.io.core)
    }
}

readme {
    maturity = space.kscience.gradle.Maturity.EXPERIMENTAL
}
