package space.kscience.magix.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A plugin that can be attached to a Magix server implementation to add custom logic or transport protocols.
 * It provides a clean interface to interact with the central message flow of a Magix loop.
 */
@ExperimentalMagixApi
public fun interface MagixFlowPlugin {

    /**
     * Starts the plugin's logic.
     *
     * @param scope The [CoroutineScope] in which the plugin should run its background tasks.
     *              The scope is managed by the server and will be cancelled on server shutdown.
     * @param receive A [Flow] of all messages passing through the Magix loop. The plugin can subscribe to this
     *                to process incoming messages.
     * @param sendMessage A suspendable function to inject a new message back into the Magix loop.
     * @return A [Job] representing the running plugin. Cancelling this job should stop the plugin's activities.
     */
    public fun start(
        scope: CoroutineScope,
        receive: Flow<MagixMessage>,
        sendMessage: suspend (MagixMessage) -> Unit,
    ): Job

}

/**
 * A convenience extension to start a [MagixFlowPlugin] using a single [MutableSharedFlow] for both
 * sending and receiving messages. This is a common pattern for simple, in-process Magix loops.
 */
@ExperimentalMagixApi
public fun MagixFlowPlugin.start(scope: CoroutineScope, magixFlow: MutableSharedFlow<MagixMessage>): Job =
    start(scope, magixFlow) { magixFlow.emit(it) }
