plugins {
    kotlin("jvm")
}

description = "KSP2 symbol processor for krig: contract/Manifest validation, contract registry generation, SerializersModule auto-generation, and @Contributes plugin aggregation."

dependencies {
    implementation(libs.ksp.api)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.kctfork.core)
    testImplementation(libs.kctfork.ksp)
    testImplementation(libs.kt.schema.annotations)
    testImplementation(project(":krig-contracts"))
    testImplementation(project(":krig-assembly"))
    testImplementation(project(":krig-ui-schema"))
    testRuntimeOnly(libs.kt.schema.ksp)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

tasks.withType<Test>().configureEach {
    // kotlin("test-junit5") pulls JUnit Jupiter; Gradle must be told to use the platform runner.
    useJUnitPlatform()
}
