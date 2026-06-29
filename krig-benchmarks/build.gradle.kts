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

// Allocation probe for typed-vs-Meta boundary and telemetry sampler hot paths: reports bytes/op.
tasks.register<JavaExec>("allocationProbe") {
    group = "benchmark"
    description = "Measures hot-path allocation (bytes/op) for typed/Meta and sampler paths."
    mainClass.set("space.kscience.krig.benchmarks.dataplane.AllocationProbeKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// Repeated in-process storage scenarios (H2 + chunk codecs, no Docker): write/read mean ± StdDev.
tasks.register<JavaExec>("storageStats") {
    group = "benchmark"
    description = "Repeated H2/chunk storage scenarios reporting write/read mean ± StdDev."
    mainClass.set("space.kscience.krig.benchmarks.storage.StorageStatsBenchKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// Transport discipline probe: per-message JSON/CBOR/PROTO vs columnar Arrow batch (bytes/alloc/time).
tasks.register<JavaExec>("transportProbe") {
    group = "benchmark"
    description = "Telemetry transport cost: per-message JSON/CBOR/PROTO vs Arrow/feather batch."
    mainClass.set("space.kscience.krig.benchmarks.transport.TelemetryTransportProbeKt")
    classpath = sourceSets["main"].runtimeClasspath
    // Apache Arrow Java needs these on the forked JVM (same as ArrowExportBenchmark).
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED")
}

benchmark {
    configurations {
        named("main") {
            warmups = 2
            iterations = 3
            iterationTime = 1
            iterationTimeUnit = "s"
        }
        register("transport") {
            warmups = 5
            iterations = 10
            iterationTime = 1
            iterationTimeUnit = "s"
            advanced("jvmForks", "1")
            include(".*TransportEncodingBenchmark.perMessage.*")
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
    implementation(project(":krig-magix"))
    implementation(libs.dataforge.meta)
    implementation(libs.kmath.stat)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.benchmark.runtime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.testcontainers)
    implementation(libs.testcontainers.postgresql)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.kotlin.datetime)

    runtimeOnly(libs.h2)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.slf4j.nop)
}
