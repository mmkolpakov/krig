plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "JSON Schema projection adapters for KRig descriptors and transport DTOs."

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":krig-contracts"))
                api(libs.kt.schema.json)

                implementation(libs.kt.schema.generator.json)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
