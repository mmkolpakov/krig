plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
    alias(libs.plugins.kotlinx.atomicfu)
}

description = "Core data models and contracts for controls-composite-kt"

kscience {
    jvm()
    js()
    native()
    wasmJs()

    useCoroutines()
    useSerialization()

    commonMain {
        api(projects.controlsApi)
        api(libs.dataforge.context)
        implementation(libs.kotlinx.atomicfu)
    }
}