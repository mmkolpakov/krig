@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

description = "Demo devices and scenarios - prove the KRig SDK builds and runs using only KRig modules."

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
                implementation(project(":krig-analytics"))
                implementation(project(":krig-assembly"))
                implementation(project(":krig-magix"))
                implementation(project(":krig-simulation"))
            }
        }
        jvmMain {
            dependencies {
                implementation(project(":krig-arrow"))
                runtimeOnly(libs.slf4j.nop)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

private val arrowJvmArgs = listOf(
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED",
)

tasks.withType<Test>().configureEach {
    jvmArgs(arrowJvmArgs)
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs(arrowJvmArgs)
}
