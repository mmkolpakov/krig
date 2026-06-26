plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "State data: lifecycle states, timestamps, quality, and observed values"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlinx.serialization.core)
            }
        }
    }
}
