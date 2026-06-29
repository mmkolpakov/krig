plugins {
    id("krig-mpp-full")
}

description = "Continuous-flow model primitives and deterministic KRig backend projection."

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":krig-contracts"))
            }
        }
    }
}
