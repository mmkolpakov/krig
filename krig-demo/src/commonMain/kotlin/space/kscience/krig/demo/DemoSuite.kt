package space.kscience.krig.demo

import kotlinx.coroutines.runBlocking

/**
 * Runs the curated alpha-3 demo set.
 */
fun main(): Unit = runBlocking {
    industrialAssemblyDemo()
    metaInteropDemo()
    dataPlatformDemo()
    externalPollingDemo()
    streamingDemo()
    sharedTimerControlDemo()
    flakyNetworkDemo()
    policyFaultsDemo()
    authAuditDemo()
    simulationProcessDemo()
    dynamicHubDemo()
    timeTravelDemo()
    expressionDemo()
    deviceDslDemo()
}
