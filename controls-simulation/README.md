# Module controls-simulation

**Maturity**: PROTOTYPE

## Description

This module provides a pluggable, multiplatform virtual time engine for `controls-ng`.
It allows for deterministic, fast-forward simulation and testing of complex, time-dependent device logic
without relying on real-world wall-clock time.

## Key Features

- **`VirtualTimeDispatcher`**: A `CoroutineDispatcher` that hijacks `delay` calls, scheduling them on a virtual timeline instead of waiting in real time.
- **`ClockManager` Plugin**: A DataForge plugin that provides a configurable `Clock` and `CoroutineDispatcher` to the context. It can be configured for `system`, `virtual`, or `compressed` time modes.
- **`withVirtualTime` DSL**: A simple extension for `ContextBuilder` to easily configure a context for simulation.