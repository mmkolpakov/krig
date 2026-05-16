plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "Core contracts: Device, DeviceBackend, DeviceBlueprint, deviceFeatureInstaller, DeviceCapability"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.krigState)
                api(projects.krigIdentity)
                api(projects.krigModel)
                api(projects.krigMessaging)

                api(libs.dataforge.context)
                api(libs.dataforge.meta)
                implementation(libs.kotlinx.collections.immutable)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
