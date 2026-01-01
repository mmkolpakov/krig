plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
}

description = "Data models and specifications for analytical tasks (aggregations, statistics)"

kscience {
    jvm()
    js()
    native()
    wasmJs()

    useCoroutines()
    useSerialization()

    commonMain {
        api(projects.controlsCore)
        api(libs.kotlinx.datetime)
    }
}