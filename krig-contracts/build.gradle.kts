plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "Core contracts: Device, DeviceBackend, DeviceBlueprint, feature, Capability"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":krig-state"))
                api(project(":krig-identity"))
                api(project(":krig-model"))
                api(project(":krig-operation"))
                api(project(":krig-messaging"))

                api(libs.dataforge.context)
                api(libs.dataforge.meta)
                implementation(libs.kotlinx.collections.immutable)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
