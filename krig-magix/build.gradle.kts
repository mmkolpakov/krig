plugins {
    id("krig-mpp")
    kotlin("plugin.serialization")
}

description = "A universal API for the Magix message bus, defining core contracts for endpoints and messages."

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(libs.dataforge.context)
                api(libs.dataforge.meta)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
