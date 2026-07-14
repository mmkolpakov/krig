plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "Core contracts: Device, DeviceBackend, DeviceManifest, PipelineFeature, Capability"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":krig-state"))
                api(project(":krig-identity"))
                api(project(":krig-model"))
                api(project(":krig-operation"))
                api(project(":krig-messaging"))

                api(libs.attributes.kt)
                api(libs.dataforge.context)
                api(libs.dataforge.io)
                api(libs.dataforge.meta)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.core)
                api(libs.kotlinx.serialization.json)

                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kotlinx.collections.immutable)
            }
        }
    }
}
