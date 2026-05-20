plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "Operation kernel: outcomes, faults, QoS pipeline, retry, timeouts, gates, observers, and resource locks"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":krig-state"))
                api(project(":krig-model"))

                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.io.core)
            }
        }
    }
}
