/**
 * Applies the krig KSP processor to a multiplatform module. Emits
 * `Merged<Kind>Plugin` and `GeneratedKrigSerializersModule` into a
 * group+artifact-derived namespace so two modules that happen to share a Gradle
 * project name (e.g. `:utils`) never collide at runtime.
 */

plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    add("kspJvm", project(":krig-ksp-processor"))
}

ksp {
    val groupSlug = project.group.toString()
        .ifBlank { project.rootProject.name }
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "anon" }
    val nameSlug = project.name
        .removePrefix("krig-")
        .split('-', '_')
        .joinToString("") { s -> s.replaceFirstChar { it.uppercase() } }
        .replaceFirstChar { it.lowercase() }
        .ifBlank { "module" }
    arg("krig.generated.module", "${groupSlug}_$nameSlug")
}
