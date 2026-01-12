# controls-ng

`controls-ng` is a multiplatform, asynchronous framework written in Kotlin for building declarative, resilient, and distributed control systems and device simulations. It is a complete architectural evolution of the original [controls-kt](https://github.com/SciProgCentre/controls-kt) project, redesigned from the ground up to support modern, mission-critical applications.

The framework's philosophy is centered around separating a device's "what" (its specification, or `DeviceBlueprint`) from its "how" (its runtime implementation and driver). This declarative approach enables features like static validation, automatic UI generation, transactional operations, and robust state management.

## Key Concepts

-   **Declarative Blueprints (`DeviceBlueprint`)**: Instead of writing imperative code to manage a device, you define its complete structure, properties, actions, and features in a serializable `DeviceBlueprint`. This blueprint acts as a self-contained factory for creating device instances.

-   **Formal Lifecycles (FSM)**: A device's lifecycle is not just `start()` and `stop()`. It's a formal Finite State Machine (FSM) managed by the powerful [KStateMachine](https://github.com/KStateMachine/kstatemachine) library. This provides predictable, robust, and extensible lifecycle management, including states like `Attaching`, `Running`, `Failed`, and `Detaching`.

-   **Reactive State Management (`DeviceState`)**: All device properties are exposed as reactive `DeviceState<T>` objects, which are backed by Kotlin `StateFlow`. A state contains not just a value, but also a high-precision timestamp and a `Quality` indicator, enabling sophisticated, real-time monitoring and logic.

-   **Composite Architecture**: Complex devices are built by composing smaller, reusable child devices. The framework's DSL provides a declarative way to define these parent-child relationships and create reactive `PropertyBinding`s that automatically propagate state changes.

-   **Transactional Plans (`TransactionPlan`)**: For complex, multistep operations that must succeed or fail as a single unit, the framework offers a `plan { ... }` DSL. This creates a serializable plan that supports sequences, parallel execution, conditions, loops, and compensating actions (Saga pattern) for reliable rollbacks.

-   **Pluggable Services**: The runtime is built on DataForge's context and plugin system. Core services like persistence (`SnapshotStore`), metrics (`MetricCollector`), and service discovery are pluggable, allowing you to choose implementations that fit your environment.

-   **Type-Safe DSL**: The primary way to define blueprints is through a type-safe Kotlin DSL (`deviceBlueprint { ... }` or `DeviceSpecification` classes). The DSL leverages delegated properties to make defining properties and actions intuitive and concise.

## Modules

### Core & Models

-   **[controls-api](controls-api)**: Pure, platform-agnostic data models, DTOs, and serializable message formats (`DeviceMessage`, `DeviceBlueprint`).
    > **Maturity**: EXPERIMENTAL

-   **[controls-core](controls-core)**: Core contracts (`Device`, `DeviceDriver`, `MessageBroker`), runtime interfaces, and base logic.
    > **Maturity**: EXPERIMENTAL

-   **[controls-service-api](controls-service-api)**: Common service contracts for Time, Security (`AuthorizationService`), and Discovery (`BlueprintRegistry`).
    > **Maturity**: EXPERIMENTAL

### DSL & Tools

-   **[controls-dsl](controls-dsl)**: A type-safe Kotlin DSL for building composite device specifications. This is the primary user-facing API.
    > **Maturity**: PROTOTYPE

-   **[controls-validation](controls-validation)**: A framework for deep static validation of blueprints, checking for consistency, cycles, and feature requirements.
    > **Maturity**: PROTOTYPE

-   **[controls-simulation](controls-simulation)**: Virtual time simulation engine and time management services for deterministic testing.
    > **Maturity**: PROTOTYPE

### Features

-   **controls-feature-\***: Specialized capabilities that can be mixed into devices:
    -   `controls-feature-fsm`: Lifecycle and Operational FSMs.
    -   `controls-feature-automation`: Transactional plans and task execution.
    -   `controls-feature-alarms`: Alarms and Events subsystem.
    -   `controls-feature-telemetry`: High-performance data streaming.
    -   `controls-feature-connectivity`: Property bindings and device composition.

### Infrastructure & IO

-   **[controls-metrics](controls-metrics)**: Cross-platform metrics API (`Counter`, `Gauge`, `Histogram`) with AtomicFU support.
    > **Maturity**: EXPERIMENTAL

-   **[controls-persistence](controls-persistence)**: Persistence layer for device state snapshots (File, LocalStorage, Memory).
    > **Maturity**: EXPERIMENTAL

-   **[controls-persistence-log](controls-persistence-log)**: High-performance audit logging based on SQLDelight (SQLite).
    > **Maturity**: PROTOTYPE

-   **[controls-ports](controls-ports)**: Multiplatform low-level IO `Port` abstraction for raw byte-level communication.
    > **Maturity**: PROTOTYPE

-   **[controls-protocol-api](controls-protocol-api)**: Universal API for protocol adapters, separating device logic from transport protocols.
    > **Maturity**: PROTOTYPE

### Transports

-   **controls-magix** & **magix-\***: Integration with the Magix message bus (RSocket, etc.) for distributed communication.
-   **controls-ktor**: Ktor-based implementations for Ports and Peer Connections.
-   **controls-exporter-prometheus**: KMP-native Prometheus exporter for metrics.

---

## Architectural Comparison: `controls-kt` vs. `controls-ng`

`controls-ng` is a complete architectural redesign of the original `controls-kt` framework. It moves from a flexible but imperative-heavy model to a formal, declarative, and more resilient paradigm. This table details the key conceptual shifts.

| Aspect                         | `controls-kt`                                                                                                                                           | `controls-ng` (New)                                                                                                                                                                                                     | Key Improvement / Rationale                                                                                                                                                                                                                                        |
|:-------------------------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Core Paradigm**              | **Imperative-Reactive**: Devices are created and wired together programmatically. Reactive logic is built manually using `Flow` operators.              | **Declarative & Formal**: Devices are defined as serializable `DeviceBlueprint` models. The runtime instantiates and manages them based on this formal description.                                                               | **Reproducibility & Static Analysis**: A declarative model can be statically validated, stored, and transmitted. It guarantees that a device's structure is consistent and decouples the definition from the implementation.                                       |
| **Device Definition**          | `DeviceSpec` is a `companion object` within a `Device` class, tightly coupling the specification to one implementation.                                 | `DeviceBlueprint` is a standalone, versioned model. Behavioral logic is defined declaratively within the specification (`driverLogic`). The `DeviceDriver` acts as a factory and lifecycle manager.                               | **Separation of Concerns**: The blueprint (the "what") defines the behavior, while the driver (the "how") manages resources. This allows easier simulation and multiple implementations for the same spec.                                                         |
| **State Management**           | Properties are simple types (`Double`, `String`). `CachingDevice` is an optional mix-in. No built-in concept of state quality or timestamp.             | State is represented by `DeviceState<T>`, which wraps a `StateValue<T>`. `StateValue` includes the `value`, a high-precision `timestamp`, and a `Quality` enum (`OK`, `STALE`, `INVALID`, `ERROR`).                               | **Rich & Observable State**: The state model is fundamentally more robust. It provides crucial metadata for resilience, diagnostics, and preventing the use of stale or invalid data in distributed systems.                                                       |
| **Lifecycle Management**       | A simple `WithLifeCycle` interface with `start()` and `stop()` methods. Lifecycle is managed imperatively.                                              | A formal **Finite State Machine (FSM)** via KStateMachine. The lifecycle is defined by states (`Attaching`, `Running`, `Failed`) and driven by `DeviceLifecycleEvent`s.                                                           | **Robustness & Predictability**: The FSM guarantees valid state transitions, prevents race conditions, and makes the device lifecycle explicit, observable, and extensible in a predictable way.                                                                   |
| **Device Composition**         | Imperative. Devices are grouped in a `DeviceManager` or `DeviceGroup`. Property connections are created manually by collecting and re-emitting `Flow`s. | **Declarative**. Child devices are defined in the parent's blueprint using a `child { ... }` DSL. Property connections are declared with `bindings { child.prop bindsTo parent.prop }`.                                           | **Declarative Data Flow**: Radically simplifies the creation of complex devices. It reduces boilerplate code, makes relationships explicit and analyzable, and allows the runtime to manage the reactive graph.                                                    |
| **Complex Operations**         | No built-in concept. Complex logic is implemented as a regular `suspend fun` within an `action`, with no transactional guarantees.                      | **`TransactionPlan`** with a `plan { ... }` DSL. Supports sequences, parallel execution, conditionals, loops, and compensating actions (**Saga Pattern**) for reliable rollbacks.                                                 | **Transactional Integrity**: Critical for operations that must succeed or fail as a whole (e.g., system startup, calibration routines). This prevents the system from being left in an inconsistent state.                                                         |
| **Persistence**                | Not a core feature. The `controls-storage` module was a basic, separate implementation.                                                                 | **First-Class Concept**. `StatefulDevice` contract, `SnapshotStore` API, and multiplatform implementations (`File`, `LocalStorage`, `InMemory`). Persistence is configured declaratively.                                         | **Resilience**: A core requirement for long-running systems. Built-in persistence allows devices to recover their state after restarts or failures, greatly improving system robustness.                                                                           |
| **Metrics & Monitoring**       | No built-in support.                                                                                                                                    | **First-Class Concept**. A multiplatform `MetricCollector` API (`Counter`, `Gauge`, `Histogram`) and a pluggable `PrometheusExporter`. Metrics are declared in property/action descriptors.                                       | **Observability**: Modern systems must be observable. This provides a standard, decoupled, and low-overhead way to instrument device logic and integrate with industry-standard monitoring tools.                                                                  |
| **Communication**              | `DeviceMessage` as a `sealed class`. Relies heavily on a `MagixEndpoint`. `PeerConnection` for binaries was a later addition.                           | `DeviceMessage` as a `sealed interface` for better extensibility. Abstracted `MessageBroker` for events and a more formal `PeerConnection` contract for binary data.                                                              | **Formalized Protocols**: The separation of a general-purpose `MessageBroker` for events from a `PeerConnection` for direct data transfer is a cleaner, more scalable design that aligns well with CQRS principles.                                                |
| **Simulation Model**           | The `controls-constructor` module provides a separate DSL for building simulations. Simulated devices are conceptually different from "real" ones.      | **Unified Model**. Simulation is built-in via `controls-simulation`. A simulated device uses the same blueprint but runs in a virtual time context managed by `VirtualTimeDispatcher`.                                            | **Seamless Substitution**: Eliminates the artificial distinction between real and simulated devices. This allows testing time-dependent logic deterministically without waiting for real wall-clock time.                                                          |
| **Integration with DataForge** | `controls-kt` is built as an application on top of DataForge, using its `Context`, `Plugin`, and `Meta` systems.                                        | `controls-ng` uses the same foundational DataForge components but formalizes its integration points via `Feature`s like `DataSourceFeature` and `TaskExecutorFeature`. A `Device` can be exposed as a `DataTree<Meta>`. | **Explicit Integration**: The new framework makes its integration points with other systems (like `dataforge-workspace`) explicit and part of its static contract. This makes the system more modular and easier to reason about.                                  |

---

## Core Dependencies & Technology Stack

### Foundational

-   **[Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html):** The core of the project. It allows the definition of common business logic and contracts in a shared module, with platform-specific implementations for JVM, JS, Native, and WasmJs. This enables the framework to run anywhere from servers to browsers and embedded systems.

-   **[Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines):** The backbone for all asynchronous operations. The framework's reactive state management and non-blocking I/O are all built on structured concurrency, ensuring efficient, scalable, and cancellation-safe code.

-   **[Kotlinx Serialization](https://github.com/Kotlin/kotlinx.serialization):** Used for all serialization tasks. It provides a robust, multi-format (JSON, ProtoBuf, etc.) mechanism for converting declarative models like `DeviceBlueprint` and `DeviceMessage` into transportable and persistent formats. Its support for polymorphic serialization is critical for the framework's sealed class/interface hierarchies.

-   **[DataForge](https://github.com/SciProgCentre/dataforge-core):** Provides the foundational building blocks for context management, plugins, and metadata.
    -   **`Context` & `Plugin`:** A powerful dependency injection and modularization system that underpins the framework's pluggable architecture (e.g., for persistence, metrics).
    -   **`Meta`:** A flexible, tree-like data structure used for device configuration, property values, and message payloads.
    -   **`Name`:** A hierarchical naming system used for addressing devices and their properties.

### State Management & Logic

-   **[KStateMachine](https://github.com/KStateMachine/kstatemachine):** A powerful, type-safe library for creating Finite State Machines (FSMs). It is the engine behind the formal lifecycle (`Attaching`, `Running`, `Failed`) and operational state management within devices, providing robustness and predictability to device behavior.

-   **[Kotlinx AtomicFU](https://github.com/Kotlin/kotlinx-atomicfu):** Used in the `controls-metrics` to provide performant, lock-free, multiplatform atomic operations. This is crucial for high-throughput components like the `AtomicMetricCollector` and internal state management.

### I/O, Networking, and Distributed Communication

-   **[Okio](https://square.github.io/okio/):** A modern, multiplatform I/O library that provides an efficient and easy-to-use abstraction over byte streams. It is the foundation for the `controls-persistence` module's file-based `SnapshotStore`.

-   **[Ktor](https://ktor.io/):** Used in the `controls-exporter-prometheus` module to run a lightweight, non-blocking HTTP server for exposing the `/metrics` endpoint. Its multiplatform nature makes it a natural fit for the project.

### Testing

-   **[Kotlinx Coroutines Test](https://github.com/Kotlin/kotlinx.coroutines/tree/master/kotlinx-coroutines-test):** Provides tools for testing coroutine-based code, including virtual time control (`runTest`), which is essential for reliably testing time-dependent logic and simulations.

-   **[Kotest](https://kotest.io/) / [JUnit](https://junit.org/junit5/):** (While not explicitly listed, one of these is typically used) Standard frameworks for structuring and running tests across all platforms.
