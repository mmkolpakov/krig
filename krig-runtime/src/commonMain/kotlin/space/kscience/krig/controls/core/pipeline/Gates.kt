package space.kscience.krig.core.pipeline

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.coroutineContext
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.context.executionContext
import space.kscience.krig.api.faults.AuthorizationFault
import space.kscience.krig.api.faults.DeviceFaultException
import space.kscience.krig.api.faults.DeviceSecurityException
import space.kscience.krig.api.faults.InvalidStateFault
import space.kscience.krig.api.identifiers.ControlsPermissions
import space.kscience.krig.api.identifiers.Permission
import space.kscience.krig.api.lifecycle.ConnectionState
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.services.AuthorizationService
import space.kscience.krig.core.meta.DeviceActionSpec
import space.kscience.krig.core.meta.DevicePropertySpec
import space.kscience.krig.core.meta.MutableDevicePropertySpec

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
    override suspend fun check(spec: DevicePropertySpec<*, *>) {
        val state = lifecycleState()
        if (!policy.allows(state)) throw DeviceFaultException(
            InvalidStateFault(
                currentState = state.toString(),
                requiredState = policy.allowedStates.joinToString(),
                operation = "read '${spec.name}' from '$deviceName'",
            ),
        )
    }
}

/** Write-plane analogue of [LifecycleReadGate]. */
public class LifecycleWriteGate(
    private val deviceName: String,
    private val lifecycleState: () -> LifecycleState,
    private val policy: LifecyclePolicy = LifecyclePolicy.Running,
) : WriteGate {
    override suspend fun check(spec: MutableDevicePropertySpec<*, *>) {
        val state = lifecycleState()
        if (!policy.allows(state)) throw DeviceFaultException(
            InvalidStateFault(
                currentState = state.toString(),
                requiredState = policy.allowedStates.joinToString(),
                operation = "write '${spec.name}' on '$deviceName'",
            ),
        )
    }
}

/** Action-plane analogue of [LifecycleReadGate]. */
public class LifecycleActionGate(
    private val deviceName: String,
    private val lifecycleState: () -> LifecycleState,
    private val policy: LifecyclePolicy = LifecyclePolicy.Running,
) : ActionGate {
    override suspend fun check(spec: DeviceActionSpec<*, *, *>) {
        val state = lifecycleState()
        if (!policy.allows(state)) throw DeviceFaultException(
            InvalidStateFault(
                currentState = state.toString(),
                requiredState = policy.allowedStates.joinToString(),
                operation = "execute '${spec.name}' on '$deviceName'",
            ),
        )
    }
}

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
    override suspend fun check(spec: DevicePropertySpec<*, *>) {
        if (policy == ConnectionPolicy.AllowAll) return
        val state = connectionState()
        if (state != ConnectionState.Connected) throw DeviceFaultException(
            InvalidStateFault(
                currentState = state.toString(),
                requiredState = ConnectionState.Connected.toString(),
                operation = "read '${spec.name}' from '$deviceName'",
            ),
        )
    }
}

/** Write-plane analogue of [ConnectionStateReadGate]. */
public class ConnectionStateWriteGate(
    private val deviceName: String,
    private val connectionState: () -> ConnectionState,
    private val policy: ConnectionPolicy = ConnectionPolicy.Reject,
) : WriteGate {
    override suspend fun check(spec: MutableDevicePropertySpec<*, *>) {
        if (policy == ConnectionPolicy.AllowAll) return
        val state = connectionState()
        if (state != ConnectionState.Connected) throw DeviceFaultException(
            InvalidStateFault(
                currentState = state.toString(),
                requiredState = ConnectionState.Connected.toString(),
                operation = "write '${spec.name}' on '$deviceName'",
            ),
        )
    }
}

/** Action-plane analogue of [ConnectionStateReadGate]. */
public class ConnectionStateActionGate(
    private val deviceName: String,
    private val connectionState: () -> ConnectionState,
    private val policy: ConnectionPolicy = ConnectionPolicy.Reject,
) : ActionGate {
    override suspend fun check(spec: DeviceActionSpec<*, *, *>) {
        if (policy == ConnectionPolicy.AllowAll) return
        val state = connectionState()
        if (state != ConnectionState.Connected) throw DeviceFaultException(
            InvalidStateFault(
                currentState = state.toString(),
                requiredState = ConnectionState.Connected.toString(),
                operation = "execute '${spec.name}' on '$deviceName'",
            ),
        )
    }
}

// --- RBAC gates --------------------------------------------------------------------

private suspend fun currentPrincipal(): Principal =
    currentCoroutineContext().executionContext?.principal ?: AnonymousPrincipal

private suspend fun AuthorizationService.checkOrFault(
    principal: Principal,
    permission: Permission,
) {
    try {
        checkPermission(principal, permission)
    } catch (_: DeviceSecurityException) {
        throw DeviceFaultException(
            AuthorizationFault(
                principalName = principal.name,
                requiredPermission = permission.id,
            ),
        )
    }
}

/**
 * RBAC gate for reads. Resolves the [Principal] from the coroutine's
 * [ExecutionContextElement][space.kscience.krig.api.context.ExecutionContextElement]
 * (defaults to [AnonymousPrincipal]) and checks
 * [ControlsPermissions.deviceRead] against [authorizationService]. Denials surface as
 * [AuthorizationFault] in a [DeviceFaultException].
 */
public class RbacReadGate(
    private val deviceName: String,
    private val authorizationService: AuthorizationService,
) : ReadGate {
    override suspend fun check(spec: DevicePropertySpec<*, *>) {
        val principal = currentPrincipal()
        val permission = ControlsPermissions.deviceRead(deviceName, spec.name.toString())
        authorizationService.checkOrFault(principal, permission)
    }
}

/** Write-plane analogue of [RbacReadGate]. */
public class RbacWriteGate(
    private val deviceName: String,
    private val authorizationService: AuthorizationService,
) : WriteGate {
    override suspend fun check(spec: MutableDevicePropertySpec<*, *>) {
        val principal = currentPrincipal()
        val permission = ControlsPermissions.deviceWrite(deviceName, spec.name.toString())
        authorizationService.checkOrFault(principal, permission)
    }
}

/** Action-plane analogue of [RbacReadGate]. */
public class RbacActionGate(
    private val deviceName: String,
    private val authorizationService: AuthorizationService,
) : ActionGate {
    override suspend fun check(spec: DeviceActionSpec<*, *, *>) {
        val principal = currentPrincipal()
        val permission = ControlsPermissions.deviceExecute(deviceName, spec.name.toString())
        authorizationService.checkOrFault(principal, permission)
    }
}
