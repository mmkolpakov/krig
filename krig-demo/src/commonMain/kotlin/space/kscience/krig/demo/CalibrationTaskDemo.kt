package space.kscience.krig.demo

import space.kscience.dataforge.names.Name
import space.kscience.dataforge.names.asName
import space.kscience.krig.api.descriptors.PropertyKind
import space.kscience.krig.api.descriptors.attributes.OperationAttributeKeys
import space.kscience.krig.api.descriptors.attributes.TaskAttribute
import space.kscience.krig.api.descriptors.attributes.taskStateProperty
import space.kscience.krig.api.descriptors.of
import space.kscience.krig.api.descriptors.operationAttributesOf
import space.kscience.krig.api.messages.TaskStateChangedMessage
import space.kscience.krig.api.tasks.DeviceTaskId
import space.kscience.krig.api.tasks.DeviceTaskPhase
import space.kscience.krig.api.tasks.DeviceTaskProgress
import space.kscience.krig.api.tasks.DeviceTaskState
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.contracts.deviceBackend
import space.kscience.krig.core.contracts.execute
import space.kscience.krig.core.contracts.manifestOf
import space.kscience.krig.core.contracts.read
import space.kscience.krig.core.meta.DeviceContractBuilder
import space.kscience.krig.dsl.device
import kotlin.time.Clock

object CalibrationTaskSpec : DeviceContractBuilder() {
    val taskState by serializableMutableProperty<DeviceTaskState>(kind = PropertyKind.LOGICAL)
    val startCalibration by serializableAction<Unit, DeviceTaskId>(
        attributes = operationAttributesOf(
            OperationAttributeKeys.Task of TaskAttribute(stateProperty = "taskState".asName()),
        ),
    )
}

val CalibrationTaskManifest: DeviceManifest = manifestOf(
    id = "space.kscience.krig.demo.calibration-task",
    contract = CalibrationTaskSpec,
    version = DemoManifestVersion,
)

data class CalibrationTaskDemoSnapshot(
    val taskId: DeviceTaskId,
    val taskPhase: DeviceTaskPhase,
    val progressFraction: Double?,
    val taskStateProperty: Name?,
    val messageType: String,
)

suspend fun calibrationTaskDemoSnapshot(): CalibrationTaskDemoSnapshot {
    val ctx = demoContext("calibration-task-demo")
    val device = device("calibrator", calibrationTaskBackend(), ctx) {
        manifest(CalibrationTaskManifest)
    }

    val taskId = device.execute(CalibrationTaskSpec.startCalibration, Unit)
        ?: error("Calibration action did not return a task id")
    val state = device.read(CalibrationTaskSpec.taskState)
    val message = TaskStateChangedMessage(
        time = Clock.System.now(),
        task = state,
        sourceDevice = "calibrator".asName(),
    )

    device.close()
    ctx.close()

    return CalibrationTaskDemoSnapshot(
        taskId = taskId,
        taskPhase = state.phase,
        progressFraction = state.progress.fraction,
        taskStateProperty = CalibrationTaskSpec.startCalibration.descriptor.taskStateProperty,
        messageType = message.messageType,
    )
}

suspend fun calibrationTaskDemo() {
    val snapshot = calibrationTaskDemoSnapshot()

    println("=== Calibration task ===")
    println("  task: ${snapshot.taskId.value}")
    println("  phase: ${snapshot.taskPhase}")
    println("  progress: ${snapshot.progressFraction}")
    println("  state property: ${snapshot.taskStateProperty}")
    println("  message type: ${snapshot.messageType}")
    println("\nDone - calibration task demo complete.")
}

private fun calibrationTaskBackend() = deviceBackend {
    var state = DeviceTaskState(
        taskId = DeviceTaskId("calibration-0"),
        actionName = CalibrationTaskSpec.startCalibration.name,
        phase = DeviceTaskPhase.Accepted,
    )

    reader(CalibrationTaskSpec.taskState) { state }
    writer(CalibrationTaskSpec.taskState) { value -> state = value }
    action(CalibrationTaskSpec.startCalibration) {
        val taskId = DeviceTaskId("calibration-1")
        state = DeviceTaskState(
            taskId = taskId,
            actionName = CalibrationTaskSpec.startCalibration.name,
            phase = DeviceTaskPhase.Running,
            progress = DeviceTaskProgress(
                fraction = 0.5,
                step = "reference-scan".asName(),
                message = "reference scan in progress",
            ),
        )
        taskId
    }
}
