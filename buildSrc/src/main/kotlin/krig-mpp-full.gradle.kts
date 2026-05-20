/**
 * Convention plugin for krig modules with ALL native targets.
 * Extends krig-mpp with macOS and iOS targets.
 *
 * Used by the data-plane quartet (krig-state, krig-identity, krig-model,
 * krig-messaging), krig-contracts, krig-primitives, krig-simulation,
 * and pure-API FeatureSpec modules.
 */
plugins {
    id("krig-mpp")
}

kotlin {
    macosArm64()
    iosX64()
    iosArm64()
    iosSimulatorArm64()
}
