plugins {
    `java-platform`
    `maven-publish`
}

description = "krig Bill of Materials — version-aligned dependency constraints for all published modules"

val excludedModules = setOf(
    "krig-demo",
    "krig-jupyter",
    "krig-bom",
)

dependencies {
    constraints {
        rootProject.subprojects
            .filter { it.name !in excludedModules && it.plugins.hasPlugin("maven-publish") }
            .forEach { subproject ->
                api(subproject)
            }

        // Align KMath versions for downstream consumers.
        api(libs.kmath.core)
        api(libs.kmath.coroutines)
        api(libs.kmath.functions)
    }
}

publishing {
    publications {
        create<MavenPublication>("bom") {
            from(components["javaPlatform"])
            artifactId = "krig-bom"
        }
    }
}
