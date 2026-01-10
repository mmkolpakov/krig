plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
}

description = "Connectivity abstractions: MessageBroker, PeerConnection, Discovery"

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
        api(libs.dataforge.io)
    }
}