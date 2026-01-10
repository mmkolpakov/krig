plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
}

description = "The core DSL for defining Device Blueprints without specific feature dependencies."

kscience {
    jvm(); js(); native(); wasmJs()
    commonMain {
        api(projects.controlsCore)
        api(libs.dataforge.meta)
    }
}