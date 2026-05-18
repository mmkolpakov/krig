plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "Virtual time simulation and coroutine DES helpers for krig."

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":krig-contracts"))
                api(project(":krig-primitives"))
                api(project(":krig-runtime"))
                api(libs.dataforge.context)
                api(libs.dataforge.meta)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
