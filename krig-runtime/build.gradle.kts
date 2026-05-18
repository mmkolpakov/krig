plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "Runtime composition: PipelinedDevice, Read/Write/Action pipeline, " +
        "HybridLogicalClock, DeviceBuilder DSL, BackendDevice."

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":krig-contracts"))
                api(project(":krig-primitives"))
                implementation(libs.arrow.core)
                implementation(libs.kotlinx.collections.immutable)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        named("jvmTest") {
            dependencies {
                implementation(libs.lincheck)
            }
        }
    }
}
