package space.kscience.krig.demo

import kotlinx.coroutines.runBlocking

/**
 * Runs the curated alpha-3 demo set.
 */
public fun main(): Unit = runBlocking {
    deviceDslDemo()
    industrialAssemblyDemo()
    flakyNetworkDemo()
    faultsAsValuesDemo()
    streamingDemo()
    dynamicHubDemo()
    timeTravelDemo()
    expressionDemo()
}
