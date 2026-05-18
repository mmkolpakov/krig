package space.kscience.krig.core.pipeline

import kotlinx.coroutines.currentCoroutineContext
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.context.executionContext
import space.kscience.krig.api.faults.AuthorizationFault
import space.kscience.krig.api.faults.DeviceSecurityException
import space.kscience.krig.api.faults.InvalidStateFault
import space.kscience.krig.api.identifiers.ControlsPermissions
import space.kscience.krig.api.identifiers.Permission
import space.kscience.krig.api.lifecycle.ConnectionState
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.result.DeviceOutcome
import space.kscience.krig.api.result.okUnit
import space.kscience.krig.api.result.toOutcome
import space.kscience.krig.api.services.AuthorizationService
import space.kscience.krig.core.meta.DeviceActionContract
import space.kscience.krig.core.meta.DevicePropertyContract
import space.kscience.krig.core.meta.MutableDevicePropertyContract

/**
 * Lifecycle states in which gated operations are permitted. Default [Running] matches the
 * strictest safety stance; loosen via [allowingStates] when probes / introspection should
 * succeed during initialisation.
 */
public class LifecyclePolicy(
    public val allowedStates: Set<LifecycleState>,
) {
    public fun allows(state: LifecycleState): Boolean = state in allowedStates

    public companion object {
        public val Running: LifecyclePolicy = LifecyclePolicy(setOf(LifecycleState.Running))
        public fun allowingStates(vararg states: LifecycleState): LifecyclePolicy =
            LifecyclePolicy(states.toSet())
    }
}

// --- Lifecycle gates ---------------------------------------------------------------

/** Lifecycle gate for reads. Denies when the device is outside [policy]'s allowed states. */
public class LifecycleReadGate(
    private val deviceName: String,
    private val lifecycleState: () -> LifecycleState,
    private val policy: LifecyclePolicy = LifecyclePolicy.Running,
) : ReadGate {
    override suspend fun check(spec: DevicePropertyContract<*>): DeviceOutcome<Unit> {
        val state = lifecycleState()
        if (!policy.allows(state)) return lifecycleFault(state, policy, "read '${spec.name}' from '$deviceName'")
        return okUnit()
    }
}

/** Write-plane analogue of [LifecycleReadGate]. */
public class LifecycleWriteGate(
    private val deviceName: String,
    private val lifecycleState: () -> LifecycleState,
    private val policy: LifecyclePolicy = LifecyclePolicy.Running,
) : WriteGate {
    override suspend fun check(spec: MutableDevicePropertyContract<*>): DeviceOutcome<Unit> {
        val state = lifecycleState()
        if (!policy.allows(state)) return lifecycleFault(state, policy, "write '${spec.name}' on '$deviceName'")
        return okUnit()
    }
}

/** Action-plane analogue of [LifecycleReadGate]. */
public class LifecycleActionGate(
    private val deviceName: String,
    private val lifecycleState: () -> LifecycleState,
    private val policy: LifecyclePolicy = LifecyclePolicy.Running,
) : ActionGate {
    override suspend fun check(spec: DeviceActionContract<*, *>): DeviceOutcome<Unit> {
        val state = lifecycleState()
        if (!policy.allows(state)) return lifecycleFault(state, policy, "execute '${spec.name}' on '$deviceName'")
        return okUnit()
    }
}

private fun lifecycleFault(
    state: LifecycleState,
    policy: LifecyclePolicy,
    operation: String,
): DeviceOutcome<Unit> =
    InvalidStateFault(
        currentState = state.toString(),
        requiredState = policy.allowedStates.joinToString(),
        operation = operation,
    ).toOutcome()

// --- Connection-state gates --------------------------------------------------------

/**
 * Policy for connection-state gates. [Reject] denies when the backend is not
 * [ConnectionState.Connected]; [AllowAll] passes through unconditionally.
 */
public enum class ConnectionPolicy { Reject, AllowAll }

/** Read-plane connection-state gate. Denies when the backend is not [ConnectionState.Connected]. */
public class ConnectionStateReadGate(
    private val deviceName: String,
    private val connectionState: () -> ConnectionState,
    private val policy: ConnectionPolicy = ConnectionPolicy.Reject,
) : ReadGate {
    override suspend fun check(spec: DevicePropertyContract<*>): DeviceOutcome<Unit> {
        if (policy == ConnectionPolicy.AllowAll) return okUnit()
        val state = connectionState()
        if (state != ConnectionState.Connected) return connectionFault(state, "read '${spec.name}' from '$deviceName'")
        return okUnit()
    }
}

/** Write-plane analogue of [ConnectionStateReadGate]. */
public class ConnectionStateWriteGate(
    private val deviceName: String,
    private val connectionState: () -> ConnectionState,
    private val policy: ConnectionPolicy = ConnectionPolicy.Reject,
) : WriteGate {
    override suspend fun check(spec: MutableDevicePropertyContract<*>): DeviceOutcome<Unit> {
        if (policy == ConnectionPolicy.AllowAll) return okUnit()
        val state = connectionState()
        if (state != ConnectionState.Connected) return connectionFault(state, "write '${spec.name}' on '$deviceName'")
        return okUnit()
    }
}

/** Action-plane analogue of [ConnectionStateReadGate]. */
public class ConnectionStateActionGate(
    private val deviceName: String,
    private val connectionState: () -> ConnectionState,
    private val policy: ConnectionPolicy = ConnectionPolicy.Reject,
) : ActionGate {
    override suspend fun check(spec: DeviceActionContract<*, *>): DeviceOutcome<Unit> {
        if (policy == ConnectionPolicy.AllowAll) return okUnit()
        val state = connectionState()
        if (state != ConnectionState.Connected) return connectionFault(state, "execute '${spec.name}' on '$deviceName'")
        return okUnit()
    }
}

private fun connectionFault(state: ConnectionState, operation: String): DeviceOutcome<Unit> =
    InvalidStateFault(
        currentState = state.toString(),
        requiredState = ConnectionState.Connected.toString(),
        operation = operation,
    ).toOutcome()

// --- RBAC gates --------------------------------------------------------------------

private suspend fun currentPrincipal(): Principal =
    currentCoroutineContext().executionContext?.principal ?: AnonymousPrincipal

private suspend fun AuthorizationService.checkOrFault(
    principal: Principal,
    permission: Permission,
): DeviceOutcome<Unit> =
    try {
        checkPermission(principal, permission)
        okUnit()
    } catch (_: DeviceSecurityException) {
        AuthorizationFault(
            principalName = principal.name,
            requiredPermission = permission.id,
        ).toOutcome()
    }

/**
 * RBAC gate for reads. Resolves the [Principal] from the coroutine's
 * [ExecutionContextElement][space.kscience.krig.api.context.ExecutionContextElement]
 * (defaults to [AnonymousPrincipal]) and checks
 * [ControlsPermissions.deviceRead] against [authorizationService]. Denials surface as
 * [AuthorizationFault] values.
 */
public class RbacReadGate(
    private val deviceName: String,
    private val authorizationService: AuthorizationService,
) : ReadGate {
    override suspend fun check(spec: DevicePropertyContract<*>): DeviceOutcome<Unit> {
        val principal = currentPrincipal()
        val permission = ControlsPermissions.deviceRead(deviceName, spec.name.toString())
        return authorizationService.checkOrFault(principal, permission)
    }
}

/** Write-plane analogue of [RbacReadGate]. */
public class RbacWriteGate(
    private val deviceName: String,
    private val authorizationService: AuthorizationService,
) : WriteGate {
    override suspend fun check(spec: MutableDevicePropertyContract<*>): DeviceOutcome<Unit> {
        val principal = currentPrincipal()
        val permission = ControlsPermissions.deviceWrite(deviceName, spec.name.toString())
        return authorizationService.checkOrFault(principal, permission)
    }
}

/** Action-plane analogue of [RbacReadGate]. */
public class RbacActionGate(
    private val deviceName: String,
    private val authorizationService: AuthorizationService,
) : ActionGate {
    override suspend fun check(spec: DeviceActionContract<*, *>): DeviceOutcome<Unit> {
        val principal = currentPrincipal()
        val permission = ControlsPermissions.deviceExecute(deviceName, spec.name.toString())
        return authorizationService.checkOrFault(principal, permission)
    }
}
