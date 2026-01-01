plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
}

description = "Common service contracts (Time, Security, Discovery)"

kscience {
    jvm()
    js()
    native()
    wasmJs()

    useCoroutines()
    useSerialization()

    commonMain {
        api(projects.controlsCore)
        api(libs.dataforge.context)
        api(libs.dataforge.meta)
        api(libs.dataforge.io)
    }
}