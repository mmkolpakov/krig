import app.cash.sqldelight.gradle.VerifyMigrationTask
import space.kscience.gradle.KScienceNativeTarget
import space.kscience.gradle.Maturity

plugins {
    id("space.kscience.gradle.mpp")
    `maven-publish`
    alias(libs.plugins.sqldelight)
}

description = "A high-performance, SQLite-based implementation of AuditLogService using SQLDelight."

kscience {
    jvm()
    native {
        setTargets(
            KScienceNativeTarget.linuxX64,
            KScienceNativeTarget.mingwX64,
        )
    }
    useCoroutines()
    useSerialization()

    commonMain {
        api(projects.controlsCore)
        api(projects.controlsServiceApi)
        api(libs.sqldelight.runtime)
        implementation(libs.okio)
        api(libs.sqldelight.coroutines)

    }

    jvmMain {
        implementation(libs.sqldelight.driver.sqlite)
    }

    nativeMain {
        api(libs.sqldelight.driver.native)
    }

    commonTest {
        implementation(kotlin("test"))
    }
}

tasks.withType<VerifyMigrationTask> {
    enabled = false
}

sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("space.kscience.controls.composite.persistence.log")
        }
    }
}

readme {
    maturity = Maturity.PROTOTYPE
}