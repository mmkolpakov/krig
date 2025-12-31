plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
}

description = "Static validation logic for Device Blueprints"

kscience {
    jvm()
    js()
    native()
    wasmJs()

    useCoroutines()

    commonMain {
        api(projects.controlsCore)
        api(projects.controlsServiceApi)
        api(projects.controlsFeatureConnectivity)
        api(projects.controlsFeatureFsm)
        api(projects.controlsFeatureAutomation)
    }
}