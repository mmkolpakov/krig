package space.kscience.krig.demo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonPrimitive
import space.kscience.dataforge.names.asName
import space.kscience.dataforge.names.parseAsName
import space.kscience.magix.api.AuthorizedMagixSubscriptionPlan
import space.kscience.magix.api.MagixEndpoint
import space.kscience.magix.api.MagixMessage
import space.kscience.magix.api.MagixMessageFilter
import space.kscience.magix.api.MagixSubscriptionPushdown
import space.kscience.magix.api.UnstableMagixEndpoint
import space.kscience.magix.api.authorizedSubscribe
import space.kscience.magix.api.filter
import space.kscience.krig.api.messages.KrigWireFormats

internal data class MagixAclPushdownSnapshot(
    val pushdown: MagixSubscriptionPushdown,
    val brokerFilter: MagixMessageFilter,
    val received: Int,
    val deniedDroppedLocally: Boolean,
)

/** ACL-aware Magix subscription plan: optional broker pushdown plus mandatory local authorization. */
suspend fun magixAclPushdownDemo() {
    val snapshot = magixAclPushdownSnapshot()

    println("=== Magix ACL pushdown ===")
    println("  pushdown: ${snapshot.pushdown}")
    println("  broker filter: ${snapshot.brokerFilter}")
    println("  received messages: ${snapshot.received}")
    println("  denied dropped locally: ${snapshot.deniedDroppedLocally}")
    println("\nDone - Magix ACL pushdown demo complete.")
}

internal suspend fun magixAclPushdownSnapshot(): MagixAclPushdownSnapshot {
    val allowedTopic = "edge.lineA.**".parseAsName()
    val allowed = message("edge.lineA.pump", "edge.lineA.pump.telemetry")
    val denied = message("edge.lineB.pump", "edge.lineB.pump.telemetry")
    val endpoint = RecordingMagixEndpoint(
        messages = listOf(allowed, denied),
        applyBrokerFilter = false,
    )
    val filter = MagixMessageFilter(
        format = setOf(KrigWireFormats.MagixEnvelope),
        topicPattern = allowedTopic,
    )
    val plan = AuthorizedMagixSubscriptionPlan(
        messageFilter = filter,
        pushdown = MagixSubscriptionPushdown.MessageFilter,
    )
    val received = endpoint.authorizedSubscribe(plan) { message ->
        message.sourceEndpoint == "edge.lineA.pump".asName()
    }.toList()

    return MagixAclPushdownSnapshot(
        pushdown = plan.pushdown,
        brokerFilter = endpoint.filters.single(),
        received = received.size,
        deniedDroppedLocally = received.singleOrNull() == allowed,
    )
}

private fun message(source: String, topic: String): MagixMessage = MagixMessage(
    format = KrigWireFormats.MagixEnvelope,
    payload = JsonPrimitive(1),
    sourceEndpoint = source.asName(),
    topic = topic.parseAsName(),
)

@OptIn(UnstableMagixEndpoint::class)
private class RecordingMagixEndpoint(
    private val messages: List<MagixMessage>,
    private val applyBrokerFilter: Boolean,
) : MagixEndpoint {
    val filters: MutableList<MagixMessageFilter> = mutableListOf()

    override suspend fun broadcast(message: MagixMessage) = Unit

    override fun subscribe(filter: MagixMessageFilter): Flow<MagixMessage> {
        filters += filter
        val source = flowOf(*messages.toTypedArray())
        return if (applyBrokerFilter) source.filter(filter) else source
    }

    override fun close() = Unit
}
