plugins {
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
