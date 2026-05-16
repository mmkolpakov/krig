/**
 * Root project build.
 *
 * ## Dokka 2.x multi-module aggregation
 *
 * Every subproject that applies the `krig-mpp` convention plugin also
 * picks up `org.jetbrains.dokka` (see `buildSrc/.../krig-mpp.gradle.kts`).
 * The root aggregator discovers those modules automatically via
 * `plugins.withId("krig-mpp") { ... }` — no manual list to keep in sync.
 *
 * The aggregated HTML site lands in `build/dokka/html`, rooted at the repo.
 *
 * GitHub Pages consumption:
 *
 * ```shell
 * ./gradlew :dokkaGenerate
 * # Upload build/dokka/html as the Pages site. Every module has a sub-directory
 * # and the top-level index.html links to all of them via a single table of contents.
 * ```
 */

plugins {
    alias(libs.plugins.ben.manes.versions)
    alias(libs.plugins.kotlinx.benchmark) apply false
    id("org.jetbrains.dokka")
}

allprojects {
    group = "space.kscience"
    version = "1.0.0-alpha-3"
}

subprojects {
    val sub = this
    plugins.withId("krig-mpp") {
        rootProject.dependencies.add("dokka", sub)
    }
}

dokka {
    moduleName.set("krig")
    dokkaPublications.html {
        // Root-level aggregated site. GitHub Pages workflow uploads this directory.
        outputDirectory.set(rootDir.resolve("build/dokka/html"))
    }
}
