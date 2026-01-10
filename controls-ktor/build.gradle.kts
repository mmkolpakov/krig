import space.kscience.gradle.KScienceNativeTarget
import space.kscience.gradle.Maturity

plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
}

description = "Ktor-based implementations of Port and PeerConnection for controls-composite."

kscience {
    jvm()
    //js()
    native {
        setTargets(
            KScienceNativeTarget.linuxX64,
            KScienceNativeTarget.mingwX64
        )
    }
    //wasmJs()

    useCoroutines()

    commonMain {
        api(projects.controlsCore)
        api(projects.controlsConnectivity)
        api(projects.controlsServiceApi)
        api(projects.controlsPorts)
        api(libs.ktor.network)
        api(libs.kotlinx.coroutines.core)
    }

    commonTest {
        implementation(kotlin("test"))
        implementation(libs.kotlinx.coroutines.test)
    }
}

readme {
    maturity = Maturity.PROTOTYPE
}