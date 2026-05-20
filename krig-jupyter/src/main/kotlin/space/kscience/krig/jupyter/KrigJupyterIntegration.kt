package space.kscience.krig.jupyter

import org.jetbrains.kotlinx.jupyter.api.HTML
import org.jetbrains.kotlinx.jupyter.api.libraries.JupyterIntegration
import space.kscience.krig.api.data.DataQuality
import space.kscience.krig.api.data.ObservedValue
import space.kscience.krig.api.data.QualitySeverity
import space.kscience.krig.api.data.Timestamped
import space.kscience.krig.api.faults.OperationFault
import space.kscience.krig.api.faults.SerializableOperationFailure
import space.kscience.krig.api.lifecycle.ConnectionState
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.messages.DeviceMessage
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.timetravel.Timeline
import space.kscience.dataforge.meta.Meta

/**
 * `%use @file[krig.json]` — wires the Kotlin Jupyter kernel to the krig SDK
 * from a local checkout. After publishing the descriptor, the same integration
 * is expected to be available as `%use krig`.
 *
 * Auto-imports the core DSL surface so notebooks start with zero manual imports.
 * Registers HTML renderers for [Device], [DeviceMessage], [OperationFault],
 * [LifecycleState], [ConnectionState], [Timestamped], [ObservedValue], [OperationOutcome], [Timeline],
 * and [Meta].
 */
public class KrigJupyterIntegration : JupyterIntegration() {

    override fun Builder.onLoaded() {
        imports()
        renderers()
    }

    private fun Builder.imports() {
        // Device / DSL.
        import("space.kscience.krig.dsl.*")
        import("space.kscience.krig.assembly.*")
        import("space.kscience.krig.core.contracts.*")
        import("space.kscience.krig.api.context.*")
        import("space.kscience.krig.api.addressing.*")
        import("space.kscience.krig.api.data.*")
        import("space.kscience.krig.api.descriptors.*")
        import("space.kscience.krig.api.faults.*")
        import("space.kscience.krig.api.hub.*")
        import("space.kscience.krig.api.identifiers.*")
        import("space.kscience.krig.api.lifecycle.*")
        import("space.kscience.krig.api.messages.*")
        import("space.kscience.krig.api.result.*")
        import("space.kscience.krig.api.factory.*")
        import("space.kscience.krig.api.services.*")
        import("space.kscience.krig.core.meta.*")
        import("space.kscience.krig.core.operations.*")
        import("space.kscience.krig.core.state.*")
        import("space.kscience.krig.core.runtime.*")
        import("space.kscience.krig.core.storage.*")
        import("space.kscience.krig.core.timetravel.*")
        import("space.kscience.krig.simulation.*")
        import("space.kscience.krig.concurrency.*")
        // Magix.
        import("space.kscience.magix.api.*")

        // DataForge.
        import("space.kscience.dataforge.context.*")
        import("space.kscience.dataforge.meta.*")
        import("space.kscience.dataforge.names.*")

        // kotlinx.coroutines.
        import("kotlinx.coroutines.flow.*")
        import("kotlinx.coroutines.runBlocking")
    }

    private fun Builder.renderers() {
        render<Device> { device ->
            val state = device.lifecycleState
            val badge = state.htmlBadge()
            HTML(
                """
                <div style="font-family: system-ui; padding: 8px; border-left: 3px solid #4a90e2;">
                  <div><b>Device</b> <code>${device.name.escape()}</code> $badge</div>
                  <div style="color: #666;">context: <code>${device.context.name.escape()}</code></div>
                  <div style="color: #666;">properties: ${device.propertyDescriptors.size}; actions: ${device.actionDescriptors.size}</div>
                </div>
                """.trimIndent()
            )
        }

        render<DeviceMessage> { msg ->
            HTML(
                """
                <div style="font-family: system-ui, monospace; padding: 6px; background: #f6f8fa;">
                  <b>${msg::class.simpleName}</b>
                  <span style="color: #888; margin-left: 8px;">${msg.time}</span>
                  <span style="color: #888; margin-left: 8px;">from ${msg.sourceDevice}</span>
                </div>
                """.trimIndent()
            )
        }

        render<OperationFault> { fault ->
            HTML(
                """
                <div style="font-family: system-ui; padding: 8px; border-left: 3px solid #e24a4a; background: #fff5f5;">
                  <b>Fault</b> <code>${fault.faultType.toString().escape()}</code>
                  <div>${fault.message.escape()}</div>
                </div>
                """.trimIndent()
            )
        }

        render<SerializableOperationFailure> { failure ->
            HTML(
                """
                <div style="font-family: system-ui; padding: 8px; border-left: 3px solid #e2a04a;">
                  <b>${failure.type.escape()}</b>
                  <div>${failure.message.escape()}</div>
                </div>
                """.trimIndent()
            )
        }

        render<OperationOutcome<*>> { outcome ->
            when (outcome) {
                is OperationOutcome.Ok -> HTML(
                    """
                    <div style="font-family: system-ui; padding: 6px; border-left: 3px solid #2e8b57; background: #f0fff0;">
                      <b>Ok</b> <span style="color: #666;">${outcome.value.escape()}</span>
                    </div>
                    """.trimIndent()
                )
                is OperationOutcome.Fail -> HTML(
                    """
                    <div style="font-family: system-ui; padding: 6px; border-left: 3px solid #e24a4a; background: #fff5f5;">
                      <b>Fail</b> <code>${outcome.fault.faultType.toString().escape()}</code>
                      <div>${outcome.fault.message.escape()}</div>
                    </div>
                    """.trimIndent()
                )
            }
        }

        render<Timestamped<*>> { sv ->
            HTML(
                """
                <div style="font-family: system-ui, monospace; padding: 4px 8px;">
                  <span style="font-weight: bold;">${sv.value.escape()}</span>
                  <span style="color: #888; margin-left: 8px; font-size: 11px;">${sv.time}</span>
                </div>
                """.trimIndent()
            )
        }

        render<ObservedValue<*>> { sv ->
            val qualityBadge = sv.quality.htmlBadge()
            HTML(
                """
                <div style="font-family: system-ui, monospace; padding: 4px 8px;">
                  <span style="font-weight: bold;">${sv.value.escape()}</span>
                  <span style="color: #888; margin-left: 8px; font-size: 11px;">${sv.time}</span>
                  <span style="margin-left: 4px;">$qualityBadge</span>
                </div>
                """.trimIndent()
            )
        }

        render<Timeline> { tl ->
            HTML(
                """
                <div style="font-family: system-ui; padding: 8px; border-left: 3px solid #9060e2; background: #faf8ff;">
                  <b>Timeline</b>
                  <span style="color: #666; margin-left: 8px;">Flow&lt;DeviceMessage&gt;</span>
                  <div style="color: #888; font-size: 12px; margin-top: 2px;">
                    mergeable &bull; replay-capable &bull; counterfactual-ready
                  </div>
                </div>
                """.trimIndent()
            )
        }

        render<LifecycleState> { state -> HTML(state.htmlBadge()) }

        render<ConnectionState> { state ->
            val label = when (state) {
                is ConnectionState.Connected -> "Connected" to "#2e8b57"
                is ConnectionState.Disconnected -> "Disconnected" to "#aa4444"
                is ConnectionState.Connecting -> "Connecting…" to "#aa8844"
            }
            HTML("""<span style="padding: 2px 6px; border-radius: 3px; background: ${label.second}; color: white;">${label.first}</span>""")
        }

        render<Meta> { meta ->
            HTML(
                """
                <details style="font-family: monospace;">
                  <summary>Meta (${meta.items.size} items)</summary>
                  <pre>${meta.toString().escape()}</pre>
                </details>
                """.trimIndent()
            )
        }
    }
}

internal fun LifecycleState.htmlBadge(): String {
    val colour = when (this) {
        LifecycleState.Running -> "#2e8b57"
        is LifecycleState.Failed -> "#aa4444"
        LifecycleState.Detached -> "#888"
        LifecycleState.Starting, LifecycleState.Attaching -> "#4a90e2"
        LifecycleState.Stopping, LifecycleState.Detaching -> "#aa8844"
        LifecycleState.Stopped -> "#bbb"
    }
    val label = this::class.simpleName ?: "?"
    return """<span style="padding: 2px 6px; border-radius: 3px; background: $colour; color: white; font-size: 11px;">$label</span>"""
}

private fun DataQuality.htmlBadge(): String {
    val (label, colour) = when {
        severity >= QualitySeverity.BAD -> "Bad" to "#aa4444"
        severity >= QualitySeverity.UNCERTAIN -> "?" to "#aa8844"
        else -> "Good" to "#2e8b57"
    }
    val title = code?.id?.let { """ title="${it.escape()}"""" } ?: ""
    return """<span$title style="padding: 1px 5px; border-radius: 2px; background: $colour; color: white; font-size: 10px;">$label</span>"""
}

private fun Any?.escape(): String = (this?.toString() ?: "")
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
