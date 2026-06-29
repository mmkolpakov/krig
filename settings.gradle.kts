@file:Suppress("UnstableApiUsage")

rootProject.name = "krig"

pluginManagement {
    // Single source of truth for the Kotlin version: gradle/libs.versions.toml.
    // The version catalog is not yet available inside pluginManagement, so read the entry directly.
    val kotlinVersion: String = file("gradle/libs.versions.toml").readLines()
        .first { it.trim().startsWith("kotlin = ") }
        .substringAfter('"').substringBefore('"')

    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://repo.kotlin.link")
        maven("https://maven.sciprog.center")
        google()
        // Last on purpose: locally installed artifacts must never shadow published ones
        // for third-party builds of the SDK.
        mavenLocal {
            content {
                excludeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?")
            }
        }
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
        mavenCentral()
        maven("https://repo.kotlin.link")
        maven("https://maven.sciprog.center")
        google()
        // Last on purpose: locally installed artifacts must never shadow published ones
        // for third-party builds of the SDK.
        mavenLocal {
            content {
                excludeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?")
            }
        }
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
    ":krig-runtime-stdlib",
    ":krig-assembly",

    // Transport Contract.
    ":krig-magix",

    // Simulation — deterministic virtual-time adapters for tests, notebooks and replay.
    ":krig-simulation",

    // Flow simulation — optional continuous-flow primitives over KRig stepping backends.
    ":krig-flow",

    // Jupyter integration — JVM-only convenience module for notebook demos.
    ":krig-jupyter",

    // HTTP integration — optional JVM-only Ktor routes for device discovery and operations.
    ":krig-server",

    // Analytics interop — JVM-only export of dense telemetry to Apache Arrow / Feather V2.
    ":krig-arrow",

    // Analytics workspace — multiplatform DataForge Workspace tasks over krig event journals.
    ":krig-analytics",

    // Build Tools.
    ":krig-ksp-processor",

    // Demo — minimal self-contained device, zero external dependencies.
    ":krig-demo",
    ":krig-benchmarks",

    // BOM.
    ":krig-bom",
)
