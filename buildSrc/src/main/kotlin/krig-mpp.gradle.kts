/**
 * Base convention plugin for krig multiplatform modules.
 *
 * Targets: JVM (21, JUnit5) + JS (browser) + linuxX64 + mingwX64 + wasmJs (browser).
 * Applies: kotlin-multiplatform, maven-publish, dokka, detekt.
 * Sets: explicit API, progressive mode, opt-ins, test deps from version catalog.
 */

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.power-assert")
    id("org.jetbrains.dokka")
    id("dev.detekt")
    `maven-publish`
}

@Suppress("OPT_IN_USAGE")
powerAssert {
    functions.set(listOf(
        "kotlin.assert",
        "kotlin.test.assertTrue",
        "kotlin.test.assertFalse",
        "kotlin.test.assertEquals",
        "kotlin.test.assertNotNull",
        "kotlin.test.assertNull",
    ))
}

// Access version catalog from precompiled script (Gradle 8.5+)
val libs = versionCatalogs.named("libs")

kotlin {
    explicitApi()
    jvmToolchain(21)

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    js {
        browser()
    }

    linuxX64()
    mingwX64()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    applyDefaultHierarchyTemplate()

    // Language settings for ALL source sets (Kotlin 2.4.0 strictness)
    sourceSets.all {
        languageSettings {
            progressiveMode = true
            optIn("kotlin.ExperimentalUnsignedTypes")
            optIn("kotlin.time.ExperimentalTime")
            optIn("kotlin.ExperimentalStdlibApi")
            optIn("kotlin.contracts.ExperimentalContracts")
        }
    }

    sourceSets {
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.findLibrary("kotlinx-coroutines-test").get())
                implementation(libs.findLibrary("turbine").get())
            }
        }
        named("jvmTest") {
            dependencies {
                implementation(kotlin("test-junit5"))
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xreturn-value-checker=full")
    }

    // KGP 2.4.0 enables ABI validation by the mere presence of the `abiValidation { }`
    // block; the explicit `enabled.set(true)` getter is gone. Filters still apply.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        filters.exclude.annotatedWith.add("space.kscience.krig.core.InternalKrigApi")
    }
}

// Detekt configuration
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    source.setFrom(
        files(
            "src/commonMain/kotlin",
            "src/commonTest/kotlin",
            "src/jvmMain/kotlin",
            "src/jvmTest/kotlin",
            "src/jsMain/kotlin",
            "src/jsTest/kotlin",
            "src/linuxX64Main/kotlin",
            "src/linuxX64Test/kotlin",
            "src/mingwX64Main/kotlin",
            "src/mingwX64Test/kotlin",
            "src/wasmJsMain/kotlin",
            "src/wasmJsTest/kotlin",
        )
    )
}


// Publishing POM — Apache 2.0
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            url.set("https://github.com/mmkolpakov/krig")
            licenses {
                license {
                    name.set("The Apache Software License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
        }
    }
}
