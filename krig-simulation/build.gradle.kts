plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "Virtual time simulation and coroutine DES helpers for krig."

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.krigContracts)
                api(projects.krigPrimitives)
                api(projects.krigRuntime)
                api(libs.dataforge.context)
                api(libs.dataforge.meta)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
