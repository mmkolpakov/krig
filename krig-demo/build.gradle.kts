plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

description = "Minimal demo device - proves KRig SDK builds and runs with zero external dependencies."

kotlin {
    jvmToolchain(21)

    jvm {
        mainRun {
            mainClass.set("space.kscience.krig.demo.DemoSuiteKt")
        }
    }
    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.krigRuntime)
            }
        }
    }
}
