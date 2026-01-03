plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
}

description = "Common utilities and extensions for controls framework"

kscience {
    jvm(); js(); native(); wasmJs()
    useSerialization()

    commonMain {
        api(libs.dataforge.meta)
        api(libs.dataforge.io)
        api(libs.kotlinx.io.core)
        api(libs.kotlinx.datetime)
    }
}