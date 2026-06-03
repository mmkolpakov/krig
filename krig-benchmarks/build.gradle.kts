plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    alias(libs.plugins.kotlinx.benchmark)
    id("dev.detekt")
    application
}

description = "JVM benchmark companion: storage macro runs and JMH data-plane/pipeline microbenchmarks."

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("space.kscience.krig.benchmarks.storage.MacroStorageBenchKt")
}

benchmark {
    configurations {
        named("main") {
            warmups = 2
            iterations = 3
            iterationTime = 1
            iterationTimeUnit = "s"
        }
    }
    targets {
        register("main")
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    source.setFrom(files("src/main/kotlin"))
}

dependencies {
    implementation(project(":krig-contracts"))
    implementation(project(":krig-messaging"))
    implementation(project(":krig-operation"))
    implementation(project(":krig-runtime"))
    implementation(project(":krig-runtime-stdlib"))
    implementation(project(":krig-assembly"))
    implementation(project(":krig-simulation"))
    implementation(project(":krig-storage"))
    implementation(project(":krig-arrow"))
    implementation(libs.dataforge.meta)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.benchmark.runtime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.testcontainers)
    implementation(libs.testcontainers.postgresql)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.kotlin.datetime)

    runtimeOnly(libs.h2)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.slf4j.nop)
}
