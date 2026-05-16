package space.kscience.krig.core.pipeline

import kotlin.time.Duration
import space.kscience.krig.api.lifecycle.ConnectionState
import space.kscience.krig.api.lifecycle.LifecycleState
import space.kscience.krig.api.services.AuditService
import space.kscience.krig.api.services.AuthorizationService
import space.kscience.krig.api.spec.RetryPolicy

/**
 * Canonical [ReadPipelineSpec] for a device. Composes lifecycle gate, optional
 * connection gate, latency-budget observer, and optional audit observer (lifecycle →
 * connection → latency-budget → audit). RBAC for reads is opt-in via [extraGates].
 */
public fun defaultReadPipelineSpec(
    deviceName: String,
    lifecycleState: () -> LifecycleState,
    connectionState: (() -> ConnectionState)? = null,
    auditService: AuditService? = null,
    onLatencyBudgetViolation: (String) -> Unit = {},
    defaultTimeout: Duration? = null,
    defaultRetry: RetryPolicy? = null,
    defaultLatencyBudget: Duration? = null,
    extraGates: List<ReadGate> = emptyList(),
    extraObservers: List<ReadObserver> = emptyList(),
): ReadPipelineSpec = ReadPipelineSpec(
    gates = buildList {
        add(LifecycleReadGate(deviceName, lifecycleState))
        if (connectionState != null) add(ConnectionStateReadGate(deviceName, connectionState))
        addAll(extraGates)
    },
    observers = buildList {
        add(LatencyBudgetReadObserver(defaultLatencyBudget, onLatencyBudgetViolation))
        if (auditService != null && auditService.isActive) {
            add(AuditReadObserver(deviceName, auditService))
        }
        addAll(extraObservers)
    },
    defaultTimeout = defaultTimeout,
    defaultRetry = defaultRetry,
    defaultLatencyBudget = defaultLatencyBudget,
)

/**
 * Canonical [WritePipelineSpec]. RBAC is mandatory for writes — every write goes through
 * [RbacWriteGate], audit (if configured) records the attempt with success/failure flag.
 */
public fun defaultWritePipelineSpec(
    deviceName: String,
    lifecycleState: () -> LifecycleState,
    authorizationService: AuthorizationService,
    connectionState: (() -> ConnectionState)? = null,
    auditService: AuditService? = null,
    onLatencyBudgetViolation: (String) -> Unit = {},
    defaultTimeout: Duration? = null,
    defaultRetry: RetryPolicy? = null,
    defaultLatencyBudget: Duration? = null,
    extraGates: List<WriteGate> = emptyList(),
    extraObservers: List<WriteObserver> = emptyList(),
): WritePipelineSpec = WritePipelineSpec(
    gates = buildList {
        add(LifecycleWriteGate(deviceName, lifecycleState))
        if (connectionState != null) add(ConnectionStateWriteGate(deviceName, connectionState))
        add(RbacWriteGate(deviceName, authorizationService))
        addAll(extraGates)
    },
    observers = buildList {
        add(LatencyBudgetWriteObserver(defaultLatencyBudget, onLatencyBudgetViolation))
        if (auditService != null && auditService.isActive) {
            add(AuditWriteObserver(deviceName, auditService))
        }
        addAll(extraObservers)
    },
    defaultTimeout = defaultTimeout,
    defaultRetry = defaultRetry,
    defaultLatencyBudget = defaultLatencyBudget,
)

/** Canonical [ActionPipelineSpec]. Mirrors [defaultWritePipelineSpec] for executes. */
public fun defaultActionPipelineSpec(
    deviceName: String,
    lifecycleState: () -> LifecycleState,
    authorizationService: AuthorizationService,
    connectionState: (() -> ConnectionState)? = null,
    auditService: AuditService? = null,
    onLatencyBudgetViolation: (String) -> Unit = {},
    defaultTimeout: Duration? = null,
    defaultRetry: RetryPolicy? = null,
    defaultLatencyBudget: Duration? = null,
    extraGates: List<ActionGate> = emptyList(),
    extraObservers: List<ActionObserver> = emptyList(),
): ActionPipelineSpec = ActionPipelineSpec(
    gates = buildList {
        add(LifecycleActionGate(deviceName, lifecycleState))
        if (connectionState != null) add(ConnectionStateActionGate(deviceName, connectionState))
        add(RbacActionGate(deviceName, authorizationService))
        addAll(extraGates)
    },
    observers = buildList {
        add(LatencyBudgetActionObserver(defaultLatencyBudget, onLatencyBudgetViolation))
        if (auditService != null && auditService.isActive) {
            add(AuditActionObserver(deviceName, auditService))
        }
        addAll(extraObservers)
    },
    defaultTimeout = defaultTimeout,
    defaultRetry = defaultRetry,
    defaultLatencyBudget = defaultLatencyBudget,
)
