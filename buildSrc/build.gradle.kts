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
    implementation(libs.kotlin.metadata.jvm)
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.dokka.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    testImplementation(kotlin("test-junit"))
}
