import groovy.json.JsonSlurper

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka")
    id("dev.detekt")
    `maven-publish`
}

description = "Kotlin Jupyter integration for krig — auto-imports, HTML renderers for Device," +
        " DeviceMessage, Timeline, Timestamped, OperationOutcome, and lifecycle states."

dependencies {
    compileOnly(libs.kotlin.jupyter.api)

    api(project(":krig-contracts"))
    api(project(":krig-runtime-stdlib"))
    api(project(":krig-runtime"))
    api(project(":krig-assembly"))
    api(project(":krig-simulation"))
    api(project(":krig-magix"))

    testImplementation(kotlin("test-junit5"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("krig-jupyter")
                description.set(project.description)
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        filters.exclude.annotatedWith.add("space.kscience.krig.core.InternalKrigApi")
    }
}

val verifyNotebookResources = tasks.register("verifyNotebookResources") {
    description = "Verifies Kotlin Notebook descriptor and intro notebook resources."

    val descriptor = layout.projectDirectory.file("src/main/resources/krig.json")
    val notebook = layout.projectDirectory.file("src/main/resources/krig-intro.ipynb")
    val projectVersion = version.toString()
    val krigVersionPlaceholder = "$" + "krig"

    inputs.file(descriptor)
    inputs.file(notebook)

    doLast {
        val descriptorText = descriptor.asFile.readText()
        val notebookText = notebook.asFile.readText()
        val parsedNotebook = JsonSlurper().parse(notebook.asFile) as Map<*, *>
        val codeSources = (parsedNotebook["cells"] as List<*>)
            .filterIsInstance<Map<*, *>>()
            .filter { it["cell_type"] == "code" }
            .joinToString("\n") { cell ->
                (cell["source"] as List<*>).joinToString("")
            }

        check("\"value\": \"$projectVersion\"" in descriptorText) {
            "krig.json must expose project version $projectVersion"
        }
        check("\"space.kscience:krig-jupyter:$krigVersionPlaceholder\"" in descriptorText) {
            "krig.json must point at the krig-jupyter JVM integration artifact"
        }
        check(Regex("""(?m)^\s*%use\s+@file\[krig\.json]\s*$""").containsMatchIn(codeSources)) {
            "krig-intro.ipynb must use the local descriptor while krig is not published"
        }
        check("Unknown library" !in notebookText) {
            "krig-intro.ipynb must not keep stale Kotlin Notebook error output"
        }
        check("\"output_type\": \"error\"" !in notebookText) {
            "krig-intro.ipynb must not keep stale error cells"
        }
        check("ReplCompilerException" !in notebookText && "ReplLibraryException" !in notebookText) {
            "krig-intro.ipynb must not keep stale Kotlin Notebook exception traces"
        }
        check(!Regex("""(?m)^\s*%use\s+krig(-simulation)?\s*$""").containsMatchIn(codeSources)) {
            "krig-intro.ipynb must not use unpublished descriptor names"
        }
    }
}

tasks.named("check") {
    dependsOn(verifyNotebookResources)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
