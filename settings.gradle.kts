rootProject.name = "controls-composite-kt"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
//    TODO use from libs versions toml
    val toolsVersion: String by extra

    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://repo.kotlin.link")
        maven("https://maven.sciprog.center")
    }

    plugins {
        id("space.kscience.gradle.project") version toolsVersion
        id("space.kscience.gradle.mpp") version toolsVersion
        id("space.kscience.gradle.jvm") version toolsVersion
        id("space.kscience.gradle.js") version toolsVersion
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    val toolsVersion: String by extra

    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven("https://repo.kotlin.link")
        maven("https://maven.sciprog.center")
    }

    versionCatalogs {
        create("spclibs") {
            from("space.kscience:version-catalog:$toolsVersion")

            library("kotlinx-coroutines-jdk9", "org.jetbrains.kotlinx", "kotlinx-coroutines-jdk9").versionRef("kotlinx-coroutines")

            library("ktor-client-core", "io.ktor", "ktor-client-core").versionRef("ktor")
            library("ktor-client-cio", "io.ktor", "ktor-client-cio").versionRef("ktor")
            library("ktor-network", "io.ktor", "ktor-network").versionRef("ktor")
            library("ktor-serialization-kotlinx-json", "io.ktor", "ktor-serialization-kotlinx-json").versionRef("ktor")

            library("ktor-server-cio", "io.ktor", "ktor-server-cio").versionRef("ktor")
            library("ktor-server-websockets", "io.ktor", "ktor-server-websockets").versionRef("ktor")
            library("ktor-server-content-negotiation", "io.ktor", "ktor-server-content-negotiation").versionRef("ktor")
            library("ktor-server-html-builder", "io.ktor", "ktor-server-html-builder").versionRef("ktor")
            library("ktor-server-status-pages", "io.ktor", "ktor-server-status-pages").versionRef("ktor")
        }
    }
}

include(
    // Core & Base
    ":controls-core",
    ":controls-common",
    ":controls-api",
    ":controls-service-api",
    ":controls-validation",

    // Features
    ":controls-feature-fsm",
    ":controls-feature-automation",
    ":controls-feature-alarms",
    ":controls-feature-telemetry",
    ":controls-feature-connectivity",
    ":controls-state",
    ":controls-feature-analytics",

    // Infrastructure
    ":controls-metrics",
    ":controls-persistence",
    ":controls-persistence-log",
    ":controls-ports",
    ":controls-protocol-api",
    ":controls-protocol-modbus",
    ":controls-exporter-prometheus",

    // Transport
    ":magix-api",
    ":magix-rsocket-core",
    ":magix-transport-rsocket-ws",
    ":magix-transport-rsocket-tcp",
    ":controls-magix",
    ":controls-ktor",

    // Tools
    ":controls-dsl",
    ":controls-simulation",
    ":controls-runtime",
    ":controls-data",
)
