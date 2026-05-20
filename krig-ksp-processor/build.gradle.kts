plugins {
    kotlin("jvm")
}

description = "KSP2 symbol processor for krig: device accessors, compile-time Blueprint validation, SerializersModule auto-generation, and Data Plane Accessor generation."

dependencies {
    implementation(libs.ksp.api)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.kctfork.core)
    testImplementation(libs.kctfork.ksp)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

tasks.withType<Test>().configureEach {
    // kotlin("test-junit5") pulls JUnit Jupiter; Gradle must be told to use the platform runner.
    useJUnitPlatform()
}
