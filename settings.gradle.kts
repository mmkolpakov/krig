@file:Suppress("UnstableApiUsage")

rootProject.name = "krig"

pluginManagement {
    val kotlinVersion: String by extra

    repositories {
        mavenLocal {
            content {
                excludeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://repo.kotlin.link")
        maven("https://maven.sciprog.center")
        google()
    }

    plugins {
        kotlin("plugin.serialization") version kotlinVersion
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenLocal {
            content {
                excludeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?")
            }
        }
        mavenCentral()
        maven("https://repo.kotlin.link")
        maven("https://maven.sciprog.center")
        google()
    }
}

include(
    // Domain Data — pure serialisable models.
    ":krig-state",
    ":krig-identity",
    ":krig-model",
    ":krig-operation",
    ":krig-messaging",
    ":krig-storage",
    ":krig-contracts",
    // Runtime
    ":krig-runtime",
    ":krig-primitives",

    // Transport Contract.
    ":krig-magix",

    // Simulation — virtual time engine built on kotlinx-coroutines-test.
    ":krig-simulation",

    // Jupyter integration — JVM-only convenience module for notebook demos.
    ":krig-jupyter",

    // Build Tools.
    ":krig-ksp-processor",

    // Demo — minimal self-contained device, zero external dependencies.
    ":krig-demo",
    ":krig-benchmarks",

    // BOM.
    ":krig-bom",
)
