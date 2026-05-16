plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka")
    id("dev.detekt")
    `maven-publish`
}

description = "Kotlin Jupyter integration for krig — auto-imports, HTML renderers for Device," +
        " DeviceMessage, Timeline, Timestamped, DeviceOutcome, and lifecycle states."

dependencies {
    compileOnly(libs.kotlin.jupyter.api)

    api(projects.krigContracts)
    api(projects.krigPrimitives)
    api(projects.krigRuntime)
    api(projects.krigSimulation)
    api(projects.krigMagix)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
