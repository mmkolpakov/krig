package space.kscience.krig.demo

import space.kscience.dataforge.io.asBinary
import space.kscience.krig.api.faults.displayType
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.contracts.manifestOf
import space.kscience.krig.core.contracts.typed.backend
import space.kscience.krig.core.meta.binaryProperty
import space.kscience.krig.core.meta.DeviceContractBuilder
import space.kscience.krig.dsl.device

/** Binary payloads stay off the Meta tree on the hot path. */
suspend fun binaryPayloadDemo() {
    val ctx = demoContext("binary-payload-demo")
    val waveform = ByteArray(4_096) { index -> (index % 256).toByte() }
    val oscilloscope = device("oscilloscope", binaryBackend(waveform), ctx) {
        manifest(BinaryPayloadManifest)
    }
    val broken = device("brokenOscilloscope", wrongRawBackend(), ctx) {
        manifest(BinaryPayloadManifest)
    }

    val payload = oscilloscope.readBinaryOutcome(BinaryPayloadSpec.waveform.name)
    val wrong = broken.readBinaryOutcome(BinaryPayloadSpec.waveform.name)

    println("=== Binary payload ===")
    when (payload) {
        is OperationOutcome.Ok -> println("  waveform bytes: ${payload.value.size}")
        is OperationOutcome.Fail -> println("  waveform failed: ${payload.fault.displayType}")
    }
    when (wrong) {
        is OperationOutcome.Ok -> println("  unexpected wrong raw success: ${wrong.value.size}")
        is OperationOutcome.Fail -> println("  missing binary path: ${wrong.fault.displayType}")
    }

    oscilloscope.close()
    broken.close()
    ctx.close()
    println("\nDone - binary payload demo complete.")
}

private object BinaryPayloadSpec : DeviceContractBuilder() {
    val waveform by binaryProperty()
}

private val BinaryPayloadManifest: DeviceManifest = manifestOf(
    id = "space.kscience.krig.demo.binary-payload",
    contract = BinaryPayloadSpec,
    version = "1.0.0-alpha-3",
)

private fun binaryBackend(payload: ByteArray) = backend {
    binaryReader(BinaryPayloadSpec.waveform) { payload.asBinary() }
}

private fun wrongRawBackend() = backend { }
