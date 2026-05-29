package space.kscience.krig.demo

import kotlinx.coroutines.runBlocking

/**
 * Runs the full demo smoke suite.
 */
fun main(): Unit = runBlocking {
    alpha3Showcase()
    industrialAssemblyDemo()
    stateModelDemo()
    metaInteropDemo()
    deviceTreeDemo()
    dataPlatformDemo()
    externalPollingDemo()
    streamingDemo()
    sharedTimerControlDemo()
    flakyNetworkDemo()
    policyFaultsDemo()
    authAuditDemo()
    simulationProcessDemo()
    deviceHubDemo()
    replayNavigationDemo()
    expressionDemo()
    deviceDslDemo()
}

/**
 * Short alpha-3 showcase: quality-aware batch acquisition, raw binary, time-series
 * analytics, and HLC/cursor replay.
 */
suspend fun alpha3Showcase() {
    batchAcquisitionDemo()
    binaryPayloadDemo()
    telemetryAnalyticsDemo()
    timeTravelDemo()
}
