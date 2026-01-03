import space.kscience.gradle.KScienceNativeTarget
import space.kscience.gradle.Maturity

plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
}

description = "A universal API layer for protocol adapters, decoupling device logic from transport protocols."

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
        api(projects.controlsCore)
        api(projects.controlsPorts)
    }
}

readme {
    maturity = Maturity.PROTOTYPE
}