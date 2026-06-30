package space.kscience.krig.demo

import kotlinx.coroutines.runBlocking

/**
 * Runs the full demo smoke suite.
 */
fun main(): Unit = runBlocking {
    alpha3Showcase()
    goldenPathDemo()
    industrialAssemblyDemo()
    stateModelDemo()
    calibrationTaskDemo()
    metaInteropDemo()
    deviceTreeDemo()
    deviceTreeAcquisitionDemo()
    externalPollingDemo()
    streamingDemo()
    sharedTimerControlDemo()
    flakyNetworkDemo()
    policyFaultsDemo()
    authAuditDemo()
    simulationProcessDemo()
    digitalTwinDemo()
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
    deadPlcCircuitBreakerDemo()
    tagTableBackendDemo()
    labDiscoveryAdHocDemo()
    edgeTelemetryWireDemo()
    distributedTypedProxyDemo()
    distributedFlowTransferDemo()
    magixEnvelopeInteropDemo()
    magixAclPushdownDemo()
    correctableSimulationDemo()
    envelopeBrokerInteropDemo()
    binaryPayloadDemo()
    telemetryAnalyticsDemo()
    replayWhatIfWorkspaceDemo()
    timeTravelDemo()
}
