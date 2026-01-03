import space.kscience.gradle.KScienceNativeTarget

plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
}

description = "A multiplatform low-level IO port abstraction for controls-kt."

kscience {
    jvm()
    js()
    native {
        setTargets(
            KScienceNativeTarget.linuxX64,
            KScienceNativeTarget.mingwX64
        )
    }
    wasmJs()
    useCoroutines()

    commonMain {
        api(libs.dataforge.context)
        api(libs.okio)
    }
}

readme {
    maturity = space.kscience.gradle.Maturity.PROTOTYPE
}
