@file:Suppress("UnstableApiUsage")

import space.kscience.krig.build.bom.KrigBomCompletenessPlugin

plugins {
    `java-platform`
    `maven-publish`
}

description = "KRig Bill of Materials — version alignment for KMP root and direct JVM publication coordinates"

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
    ":krig-schema-json",
    ":krig-ui-schema",
    ":krig-ui-remote-compose",
    ":krig-jupyter",
    ":krig-server",
    ":krig-arrow",
    ":krig-analytics",
)

dependencies {
    constraints {
        publishedModules.forEach { path ->
            api(project.dependencyFactory.createProjectDependency(path))
        }
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

apply<KrigBomCompletenessPlugin>()
