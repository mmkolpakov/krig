plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "State & Faults: data types, lifecycle states, outcomes, and fault taxonomy"

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
