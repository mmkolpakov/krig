# krig-simulation

**Maturity**: PROTOTYPE

Deterministic virtual-time helpers for tests, notebooks, replay and finite digital-twin runs.
Production polling and storage do not depend on this module.

## Key Features

- **`DeterministicScheduler`**: A virtual-time scheduler adapter. Exposes `advanceBy(Duration)`, `asDispatcher()`, and `asClock()` for transparent virtual-time integration.
- **`SimulationSession`**: Manages a group of `Device` instances under a single virtual-time clock.
- **`ClockManager`**: Configurable DataForge plugin providing `system`, `virtual`, and `compressed` clock modes.
- **`Resource` / `Signal`**: DES concurrency primitives with priority queuing and predicate-driven wait signals.
- **`ProcessDsl`**: Coroutine-based process DSL (`hold`, `waitUntil`, `request`) composable on a scheduler-backed `CoroutineScope`.
