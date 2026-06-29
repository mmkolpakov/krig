plugins {
    `java-platform`
    `maven-publish`
}

description = "krig Bill of Materials — version-aligned dependency constraints for all published modules"

val publishedModules = listOf(
    ":krig-state",
    ":krig-identity",
    ":krig-model",
    ":krig-operation",
    ":krig-messaging",
    ":krig-storage",
    ":krig-contracts",
    ":krig-runtime",
    ":krig-runtime-stdlib",
    ":krig-assembly",
    ":krig-magix",
    ":krig-simulation",
    ":krig-flow",
    ":krig-server",
    ":krig-arrow",
    ":krig-analytics",
)

dependencies {
    constraints {
        publishedModules.forEach { path ->
            api(project(path))
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
