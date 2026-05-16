plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "Identity & Context: principals, permissions, addresses, and authorization services"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":krig-state"))
                api(libs.attributes.kt)
                api(libs.dataforge.meta)
                api(libs.dataforge.context)
            }
        }
    }
}
