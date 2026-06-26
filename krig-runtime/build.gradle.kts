plugins {
    id("krig-mpp-full")
    kotlin("plugin.serialization")
}

description = "Runtime composition: PipelineDevice, device pipeline adapters, " +
        "HybridLogicalClock, DeviceBuilder DSL, BackendDevice."

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":krig-contracts"))
                api(project(":krig-operation"))
                implementation(libs.kotlinx.collections.immutable)
                implementation(libs.kotlinx.atomicfu)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
