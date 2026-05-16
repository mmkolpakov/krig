/**
 * Convention plugin for krig modules with ALL native targets.
 * Extends krig-mpp with macOS and iOS targets.
 *
 * Used by the data-plane quartet (controls-state, controls-identity, controls-model,
 * controls-messaging), controls-contracts, controls-primitives, controls-simulation,
 * and the pure-API DeviceFeatureSpec modules (controls-feature-caching, controls-feature-retry).
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
