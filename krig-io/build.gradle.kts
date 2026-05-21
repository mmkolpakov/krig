plugins {
    id("krig-mpp-full")
}

description = "Byte-stream IO helpers: framers and flow adapters"

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.io.core)
            }
        }
    }
}
