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
        api(libs.dataforge.data)
        implementation(libs.kotlinx.atomicfu)
        implementation(libs.kstatemachine.core)
        implementation(libs.kstatemachine.coroutines)
    }
}