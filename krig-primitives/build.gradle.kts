plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "Runtime primitives: reactive state, reconciler, coroutine scope, expressions, time travel, storage, byte-stream framing"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":krig-contracts"))
                api(libs.dataforge.data)
                api(libs.dataforge.context)
                api(libs.dataforge.meta)
                api(libs.tables.kt)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kotlinx.collections.immutable)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.io.core)
            }
        }
    }
}
