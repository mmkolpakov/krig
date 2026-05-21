plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "Storage vocabulary: event journals and typed time-series sinks"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":krig-state"))
                api(project(":krig-messaging"))
                api(libs.dataforge.meta)
                api(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.atomicfu)
            }
        }
    }
}
