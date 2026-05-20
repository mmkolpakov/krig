plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "State data: lifecycle states, timestamps, quality, snapshots, and timelines"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(libs.dataforge.meta)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.io.core)
            }
        }
    }
}
