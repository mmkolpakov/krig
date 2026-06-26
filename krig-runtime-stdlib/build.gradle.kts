plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "Runtime standard library: device hubs, state history, expressions, peer runtime and time travel"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":krig-runtime"))
                api(project(":krig-contracts"))
                api(project(":krig-storage"))
                api(libs.dataforge.data)
                api(libs.dataforge.context)
                api(libs.dataforge.io)
                api(libs.dataforge.meta)
                api(libs.tables.kt)
                implementation(libs.kmath.core)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kotlinx.collections.immutable)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
