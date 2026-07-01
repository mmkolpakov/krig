package space.kscience.krig.analytics

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import space.kscience.krig.api.tasks.DeviceTaskId
import space.kscience.krig.api.tasks.DeviceTaskState

public suspend fun Flow<DeviceTaskState>.awaitTerminalDeviceTaskState(): DeviceTaskState =
    first { state -> state.terminal }

public suspend fun Flow<DeviceTaskState>.awaitTerminalDeviceTaskState(taskId: DeviceTaskId): DeviceTaskState =
    first { state -> state.taskId == taskId && state.terminal }
