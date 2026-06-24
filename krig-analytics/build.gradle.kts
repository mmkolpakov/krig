plugins {
    id("krig-mpp-full")
}

description = "Analytics: DataForge Workspace tasks and data selectors over krig event journals."

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":krig-storage"))
                api(project(":krig-runtime-stdlib"))
                api(libs.dataforge.workspace)
            }
        }
    }
}
