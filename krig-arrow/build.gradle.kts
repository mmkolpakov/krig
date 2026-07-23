plugins {
    id("krig-architecture-module")
    kotlin("jvm")
    id("org.jetbrains.dokka")
    id("dev.detekt")
    `maven-publish`
}

description = "JVM-only analytics interop: export dense double telemetry chunks to Apache Arrow IPC / Feather V2."

dependencies {
    api(project(":krig-storage"))
    api(project(":krig-state"))

    implementation(libs.arrow.vector)
    implementation(libs.arrow.memory.netty)
    implementation(libs.arrow.compression)

    testImplementation(kotlin("test-junit5"))
}

kotlin {
    jvmToolchain(21)
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        binariesSource.set(org.jetbrains.kotlin.gradle.dsl.abi.BinariesSource.MAVEN_PUBLICATIONS)
        keepLocallyUnsupportedTargets.set(false)
        filters.exclude.annotatedWith.add("space.kscience.krig.core.InternalKrigApi")
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    source.setFrom(files("src/main/kotlin", "src/test/kotlin"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("krig-arrow")
                description.set(project.description)
            }
        }
    }
}

// Apache Arrow Java uses off-heap buffers and native compression codecs on recent JDKs.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
    )
}
