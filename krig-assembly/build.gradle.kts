plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "Assembly layer: DataForge plugins, Manifest/factory discovery, data-platform configuration and polling"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":krig-runtime"))
                api(project(":krig-runtime-stdlib"))
                api(project(":krig-storage"))
                api(libs.dataforge.context)
                api(libs.dataforge.meta)
                api(libs.kmath.core)
                implementation(libs.kmath.functions)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kotlinx.collections.immutable)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
