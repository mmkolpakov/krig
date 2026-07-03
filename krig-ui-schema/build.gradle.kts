plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "UI schema: neutral KMP form descriptors projected from device manifests."

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":krig-contracts"))
                api(libs.kotlinx.serialization.core)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
