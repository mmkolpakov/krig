plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "Assembly layer: DataForge plugins, blueprint/factory discovery, data-platform configuration and polling"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":krig-runtime"))
                api(libs.dataforge.context)
                api(libs.dataforge.meta)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kotlinx.collections.immutable)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
