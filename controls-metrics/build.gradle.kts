import space.kscience.gradle.Maturity
import space.kscience.gradle.KScienceNativeTarget

plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
}

description = "Cross-platform metrics API and a default AtomicFU-based implementation for composite devices."

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
        implementation(libs.kotlinx.atomicfu)
        api(libs.dataforge.io)
    }

    commonTest {
        implementation(kotlin("test"))
        implementation(libs.kotlinx.coroutines.test)
    }
}

readme {
    maturity = Maturity.EXPERIMENTAL
}