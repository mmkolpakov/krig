package space.kscience.krig.core.pipeline

import kotlinx.coroutines.currentCoroutineContext
import space.kscience.dataforge.names.Name
import space.kscience.krig.api.context.AnonymousPrincipal
import space.kscience.krig.api.context.Principal
import space.kscience.krig.api.context.executionContext
import space.kscience.krig.api.descriptors.attributes.requiredCapabilityIds
import space.kscience.krig.api.faults.AuthorizationFault
import space.kscience.krig.api.faults.InvalidStateFault
import space.kscience.krig.api.identifiers.ControlsPermissions
import space.kscience.krig.api.identifiers.Permission
import space.kscience.krig.api.lifecycle.ConnectionState
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.result.OperationOutcome
import space.kscience.krig.api.result.okUnit
import space.kscience.krig.api.result.toOutcome
import space.kscience.krig.api.services.AuthorizationException
import space.kscience.krig.api.services.AuthorizationService
import space.kscience.krig.core.contracts.CapabilityToggles

/** Lifecycle states in which gated operations are permitted. */
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

/** Denies operations when the runtime node is outside [policy]'s allowed states. */
public class LifecycleGate(
    private val hostName: String,
    private val lifecycleState: () -> LifecycleState,
    private val policy: LifecyclePolicy = LifecyclePolicy.Running,
) : OperationGate {
    override suspend fun check(context: OperationContext): OperationOutcome<Unit> {
        val state = lifecycleState()
        if (!policy.allows(state)) return lifecycleFault(state, policy, context.operationLabel(hostName))
        return okUnit()
    }
}

private fun lifecycleFault(
    state: LifecycleState,
    policy: LifecyclePolicy,
    operation: String,
): OperationOutcome<Unit> =
    InvalidStateFault(
        currentState = state.toString(),
        requiredState = policy.allowedStates.joinToString(),
        operation = operation,
    ).toOutcome()

/** Policy for connection-state gates. */
public enum class ConnectionPolicy { Reject, AllowAll }

/** Denies operations when the backend is not [ConnectionState.Connected]. */
public class ConnectionStateGate(
    private val hostName: String,
    private val connectionState: () -> ConnectionState,
    private val policy: ConnectionPolicy = ConnectionPolicy.Reject,
) : OperationGate {
    override suspend fun check(context: OperationContext): OperationOutcome<Unit> {
        if (policy == ConnectionPolicy.AllowAll) return okUnit()
        val state = connectionState()
        if (state != ConnectionState.Connected) return connectionFault(state, context.operationLabel(hostName))
        return okUnit()
    }
}

private fun connectionFault(state: ConnectionState, operation: String): OperationOutcome<Unit> =
    InvalidStateFault(
        currentState = state.toString(),
        requiredState = ConnectionState.Connected.toString(),
        operation = operation,
    ).toOutcome()

/** Denies operations whose declared required capability is currently suppressed. */
public class CapabilityGate(
    private val hostName: String,
    private val toggles: CapabilityToggles,
) : OperationGate {
    override suspend fun check(context: OperationContext): OperationOutcome<Unit> =
        checkRequiredCapabilities(
            required = context.descriptor.requiredCapabilityIds,
            toggles = toggles,
            operation = context.operationLabel(hostName),
        )
}

private fun checkRequiredCapabilities(
    required: Set<Name>,
    toggles: CapabilityToggles,
    operation: String,
): OperationOutcome<Unit> {
    val suppressed = required.firstOrNull(toggles::isSuppressed) ?: return okUnit()
    return InvalidStateFault(
        currentState = "Capability '$suppressed' suppressed",
        requiredState = "Capability '$suppressed' active",
        operation = operation,
    ).toOutcome()
}

private suspend fun currentPrincipal(): Principal =
    currentCoroutineContext().executionContext?.principal ?: AnonymousPrincipal

private suspend fun AuthorizationService.checkOrFault(
    principal: Principal,
    permission: Permission,
): OperationOutcome<Unit> =
    try {
        checkPermission(principal, permission)
        okUnit()
    } catch (_: AuthorizationException) {
        AuthorizationFault(
            principalName = principal.name,
            requiredPermission = permission.id,
        ).toOutcome()
    }

/** Authorization gate for device-backed operations. */
public class DeviceAuthorizationGate(
    private val hostName: String,
    private val authorizationService: AuthorizationService,
) : OperationGate {
    override suspend fun check(context: OperationContext): OperationOutcome<Unit> {
        val principal = currentPrincipal()
        val permission = when (context.kind) {
            OperationKinds.Read -> ControlsPermissions.deviceRead(hostName, context.name.toString())
            OperationKinds.Write -> ControlsPermissions.deviceWrite(hostName, context.name.toString())
            OperationKinds.Action -> ControlsPermissions.deviceExecute(hostName, context.name.toString())
            else -> return okUnit()
        }
        return authorizationService.checkOrFault(principal, permission)
    }
}

internal fun OperationContext.operationLabel(hostName: String): String =
    when (kind) {
        OperationKinds.Read -> "read '$name' from '$hostName'"
        OperationKinds.Write -> "write '$name' on '$hostName'"
        OperationKinds.Action -> "execute '$name' on '$hostName'"
        else -> "${kind.name} '$name' on '$hostName'"
    }
