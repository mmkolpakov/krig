import space.kscience.gradle.KScienceNativeTarget
import space.kscience.gradle.Maturity

plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
}

description = "Magix-based implementations for MessageBroker and Peer Signaling for composite devices."

kscience {
    jvm()
    js()
    native {
        setTargets(
            KScienceNativeTarget.linuxX64,
            KScienceNativeTarget.mingwX64
        )
    }
//    wasmJs()

    useCoroutines()
    useSerialization()

    commonMain {
        api(projects.controlsCore)
        api(projects.controlsServiceApi)
        api(projects.magixApi)
        implementation(libs.uuid)
    }

    commonTest {
        implementation(kotlin("test"))
        implementation(libs.kotlinx.coroutines.test)
    }
}

readme {
    maturity = Maturity.EXPERIMENTAL
}