plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
}

description = "Finite State Machine features (Lifecycle & Operational)"

kscience {
    jvm()
    js()
    native()
    wasmJs()

    useCoroutines()
    useSerialization()

    commonMain {
        api(projects.controlsCore)
        api(libs.dataforge.meta)
        api(libs.dataforge.context)
        api(libs.kstatemachine.core)
        implementation(libs.kstatemachine.coroutines)
    }
}