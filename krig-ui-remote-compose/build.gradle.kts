plugins {
    id("krig-architecture-module")
    kotlin("jvm")
    id("org.jetbrains.dokka")
    id("dev.detekt")
    `maven-publish`
}

description = "Optional JVM Remote Compose renderer for KRig neutral device form schemas."

dependencies {
    api(project(":krig-ui-schema"))

    implementation(libs.androidx.compose.remote.core)
    implementation(libs.androidx.compose.remote.creation.core)
    implementation(libs.androidx.compose.remote.creation.jvm)

    testImplementation(kotlin("test-junit5"))
    testImplementation(project(":krig-contracts"))
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
                name.set("krig-ui-remote-compose")
                description.set(project.description)
            }
        }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
