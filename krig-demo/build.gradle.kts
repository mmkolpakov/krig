@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

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
                implementation(project(":krig-runtime"))
                implementation(project(":krig-runtime-stdlib"))
                implementation(project(":krig-assembly"))
                implementation(project(":krig-simulation"))
            }
        }
        jvmMain {
            dependencies {
                runtimeOnly(libs.slf4j.nop)
            }
        }
    }
}
