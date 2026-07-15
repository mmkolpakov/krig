plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        create("krigArchitecture") {
            id = "krig-architecture"
            implementationClass = "space.kscience.krig.build.architecture.ArchitecturePlugin"
        }
    }
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
    implementation(libs.kotlin.power.assert.plugin)
    implementation(libs.dokka.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test-junit"))
}
