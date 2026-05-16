# Module controls-simulation

**Maturity**: PROTOTYPE

## Description

Virtual-time engine and coroutine DES (Discrete-Event Simulation) helpers for `krig`.
Provides deterministic, fast-forward simulation of time-dependent device logic without relying on wall-clock time.

## Key Features

- **`DeterministicScheduler`**: A virtual-time scheduler wrapping `kotlinx-coroutines-test`. Exposes `advanceBy(Duration)`, `asDispatcher()`, and `asClock()` for transparent virtual-time integration.
- **`SimulationSession`**: Manages a group of `Device` instances under a single virtual-time clock.
- **`ClockManager`**: Configurable DataForge plugin providing `system`, `virtual`, and `compressed` clock modes.
- **`Resource` / `Signal`**: DES concurrency primitives — capacity-bounded claimable resources with priority queuing and predicate-driven wait signals.
- **`ProcessDsl`**: Coroutine-based process DSL (`hold`, `waitUntil`, `request`) composable on any `CoroutineScope` backed by `DeterministicScheduler`.
