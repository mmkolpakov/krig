plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
}

description = "Data Plane: Contracts, DTOs, and Blueprints"

kscience {
    jvm(); js(); native(); wasmJs()
    useSerialization()

    commonMain {
        api(projects.controlsCommon)
        api(libs.attributes.kt) //for Type-Safe Runtime Context
        api(libs.attributes.kt.serialization)
    }
}