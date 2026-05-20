plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "Messaging: device messages, execution events, and API serialization registry"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":krig-model"))
                api(project(":krig-identity"))
                api(project(":krig-operation"))
                api(project(":krig-state"))
                api(libs.dataforge.meta)
            }
        }
    }
}
