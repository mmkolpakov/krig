plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
}

description = "Data Plane: Contracts, DTOs, and Blueprints"

kscience {
    jvm(); js(); native(); wasmJs()
    useSerialization()

    commonMain {
        api(libs.attributes.kt)
        api(libs.attributes.kt.serialization)
        api(libs.dataforge.meta)
    }
}