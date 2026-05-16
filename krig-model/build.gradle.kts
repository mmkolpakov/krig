plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "Device Model: descriptors, metadata, specs, features, and validation rules"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":krig-identity"))
                api(project(":krig-state"))
                api(libs.attributes.kt)
                api(libs.attributes.kt.serialization)
                api(libs.dataforge.meta)
            }
        }
    }
}
