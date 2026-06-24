package space.kscience.krig.core.contracts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.context.executionContext
import space.kscience.krig.api.faults.InvalidStateFault
import space.kscience.krig.api.faults.OperationFaultException
import space.kscience.krig.api.identifiers.isSpecified
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.messages.DeviceMessageFrame
import space.kscience.krig.api.messages.MessageContext
import space.kscience.krig.api.messages.PropertyChangedMessage
import space.kscience.krig.api.messages.frame
import space.kscience.krig.api.messages.withHlcStamp
import space.kscience.krig.core.InternalKrigApi
import space.kscience.krig.core.KrigPerformancePitfall
import space.kscience.krig.core.operations.HybridLogicalClock

/**
 * Two-plane message transport for a device. Routes by message type into a per-plane mailbox and runs
 * one pump per plane, so a slow control subscriber never blocks data-plane publication (and vice
 * versa). Each pump applies the optional HLC stamp immediately before publishing, preserving
 * monotonic stamp order within its plane.
 *
 * Reliability invariants live here in isolation: the control plane never drops (carries faults,
 * lifecycle, attach/detach), the data plane honours the configured backpressure policy, and [emit]
 * on a closed bus fails predictably with [InvalidStateFault] instead of suspending forever.
 */
@OptIn(InternalKrigApi::class, KrigPerformancePitfall::class)
internal class DeviceMessageBus(
    private val name: Name,
    deviceScope: CoroutineScope,
    messaging: DeviceMessaging,
    private val hlc: HybridLogicalClock?,
) {
    private val mutableControlFlow: MutableSharedFlow<DeviceMessageFrame<DeviceMessage>> = MutableSharedFlow(
        replay = messaging.replay,
        extraBufferCapacity = messaging.controlBufferCapacity,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    private val mutableDataFlow: MutableSharedFlow<DeviceMessageFrame<DeviceMessage>> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = messaging.dataBufferCapacity,
        onBufferOverflow = messaging.toDataBufferOverflow(),
    )

    val controlFlow: SharedFlow<DeviceMessageFrame<DeviceMessage>> = mutableControlFlow.asSharedFlow()
    val dataFlow: SharedFlow<DeviceMessageFrame<DeviceMessage>> = mutableDataFlow.asSharedFlow()

    private val controlMailbox: Channel<DeviceMessageFrame<DeviceMessage>> = Channel(
        capacity = messaging.controlBufferCapacity,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    private val dataMailbox: Channel<DeviceMessageFrame<DeviceMessage>> = Channel(
        capacity = mailboxCapacity(messaging.dataBufferCapacity, messaging.toDataBufferOverflow()),
        onBufferOverflow = messaging.toDataBufferOverflow(),
    )

    init {
        deviceScope.launch { pump(controlMailbox, mutableControlFlow) }
        deviceScope.launch { pump(dataMailbox, mutableDataFlow) }
    }

    /** Suspending publish; stamps the ambient correlation id onto the frame for causal tracing. */
    suspend fun emit(message: DeviceMessage) {
        emit(message.frame(ambientMessageContext()))
    }

    suspend fun emit(envelope: DeviceMessageFrame<DeviceMessage>) {
        try {
            mailboxFor(envelope).send(envelope)
        } catch (closed: ClosedSendChannelException) {
            throw OperationFaultException(
                InvalidStateFault(
                    currentState = "Stopped",
                    requiredState = "Running",
                    operation = "emit '${envelope.payload.messageType}' on device '$name'",
                ),
                closed,
            )
        }
    }

    /** Non-suspending publish; returns `false` when the selected plane cannot accept the message now. */
    fun tryEmit(message: DeviceMessage): Boolean = tryEmit(message.frame())

    fun tryEmit(envelope: DeviceMessageFrame<DeviceMessage>): Boolean =
        mailboxFor(envelope).trySend(envelope).isSuccess

    /** Closes both mailboxes so suspended producers resume with [InvalidStateFault]; pumps drain and stop. */
    fun close() {
        controlMailbox.close()
        dataMailbox.close()
    }

    private suspend fun ambientMessageContext(): MessageContext {
        val correlationId = currentCoroutineContext().executionContext?.correlationId
        return if (correlationId != null && correlationId.isSpecified) {
            MessageContext(correlationId = correlationId)
        } else {
            MessageContext.Empty
        }
    }

    private suspend fun pump(
        mailbox: ReceiveChannel<DeviceMessageFrame<DeviceMessage>>,
        target: MutableSharedFlow<DeviceMessageFrame<DeviceMessage>>,
    ) {
        for (envelope in mailbox) {
            val stamped = hlc?.let { envelope.withHlcStamp(it.tick()) } ?: envelope
            target.emit(stamped)
        }
    }

    private fun mailboxFor(envelope: DeviceMessageFrame<DeviceMessage>): Channel<DeviceMessageFrame<DeviceMessage>> =
        when (envelope.payload) {
            is PropertyChangedMessage -> dataMailbox
            else -> controlMailbox // errors, lifecycle, attach/detach, faults — never drop
        }

    private fun mailboxCapacity(capacity: Int, overflow: BufferOverflow): Int = when (overflow) {
        BufferOverflow.SUSPEND -> capacity
        BufferOverflow.DROP_OLDEST, BufferOverflow.DROP_LATEST -> maxOf(1, capacity)
    }
}
