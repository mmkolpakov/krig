plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
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
        api(libs.dataforge.meta)
        api(libs.dataforge.io)
        api(libs.kotlinx.io.core)
        api(libs.kotlinx.datetime)
    }
}