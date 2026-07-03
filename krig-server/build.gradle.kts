plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka")
    id("dev.detekt")
    `maven-publish`
}

description = "Optional JVM Ktor routes for KRig device discovery, manifests, schemas and read-only operations."

dependencies {
    api(project(":krig-contracts"))
    api(project(":krig-ui-schema"))
    api(libs.kotlinx.serialization.json)
    api(libs.ktor.server.core)
    api(libs.ktor.server.content.negotiation)
    api(libs.ktor.server.routing.openapi)
    api(libs.ktor.server.sse)
    api(libs.ktor.server.websockets)
    api(libs.ktor.serialization.kotlinx.json)

    implementation(project(":krig-messaging"))

    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.ktor.server.test.host)
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
                name.set("krig-server")
                description.set(project.description)
            }
        }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
