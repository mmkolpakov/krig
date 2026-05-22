package space.kscience.krig.demo

import kotlinx.coroutines.runBlocking

/**
 * Runs the curated alpha-3 demo set.
 */
fun main(): Unit = runBlocking {
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
    timeTravelDemo()
    expressionDemo()
    deviceDslDemo()
}
