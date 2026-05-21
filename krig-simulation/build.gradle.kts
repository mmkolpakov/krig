plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "Deterministic virtual-time adapters and coroutine simulation helpers for krig."

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":krig-contracts"))
                api(project(":krig-runtime-stdlib"))
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
