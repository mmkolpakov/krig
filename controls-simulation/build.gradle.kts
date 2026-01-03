import space.kscience.gradle.KScienceNativeTarget
import space.kscience.gradle.Maturity

plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
}

description = "Virtual time simulation and time management services for controls-composite."

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
    useSerialization()

    commonMain {
        api(projects.controlsCore)
        api(libs.dataforge.context)
        api(libs.dataforge.meta)
        implementation(libs.kotlinx.atomicfu)
    }

    commonTest {
        implementation(kotlin("test"))
        implementation(libs.kotlinx.coroutines.test)
    }
}

readme {
    maturity = Maturity.PROTOTYPE
}