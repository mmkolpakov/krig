# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

### Added

### Changed

### Deprecated

### Removed

### Fixed

### Security

## 1.0.0-alpha-1 - 2025-08-15

Initial release of the `controls-ng` framework. This version represents an architectural redesign and a paradigm shift from the previous `controls-kt` project, moving towards a more formal, declarative, and resilient model for building control systems.

### Added

-   **Core Model (`controls-model`)**:
    -   Introduced `DeviceBlueprint`: A declarative, serializable, and versioned model for defining a device's complete structure, behavior, and features, separating the specification from the runtime implementation.
    -   Formalized `Device` as a runtime contract with distinct `PropertyDevice` and `ActionDevice` capabilities.
    -   Established a standardized, serializable `DeviceMessage` sealed hierarchy for all system communication, including property changes, lifecycle events, and errors.
    -   Defined `DevicePropertySpec` and `DeviceActionSpec` for static, type-safe description of a device's public API.
    -   Introduced `DeviceState<T>` and `MutableDeviceState<T>`: A reactive, observable state model containing a `StateValue<T>` which includes the value, a high-precision timestamp, and a `Quality` enum (`OK`, `STALE`, `INVALID`, `ERROR`).

-   **Domain-Specific Language (`controls-dsl`)**:
    -   Created a type-safe Kotlin DSL for building `DeviceBlueprint` instances (`deviceBlueprint { ... }`) and reusable `DeviceSpecification` classes.
    -   Implemented delegated properties for effortless declaration of properties (`property`, `mutableProperty`, `stateProperty`, `derived`) and actions (`action`, `unitAction`).
    -   Added `child` and `children` DSL for declarative composition of devices, including `bindings` blocks for reactive property connections (`bindsTo`).
    -   Introduced `standardLifecycle` and `lifecycle` blocks for defining a device's lifecycle as a formal Finite State Machine (FSM) using KStateMachine.
    -   Added `plan { ... }` DSL for creating complex, multistep, transactional `TransactionPlan`s with support for compensating actions (Saga pattern).

-   **State Management and Persistence (`controls-persistence`)**:
    -   Defined the `StatefulDevice` contract for devices that support state snapshotting and restoration.
    -   Introduced the `SnapshotStore` interface for persistence backends.
    -   Provided multiplatform implementations: `FileSnapshotStore` (JVM/Native), `LocalStorageSnapshotStore` (JS/WasmJs), and `InMemorySnapshotStore` (common).
    -   Added a `PersistencePlugin` for easy integration and configuration.

-   **Metrics and Monitoring (`controls-metrics` & `controls-exporter-prometheus`)**:
    -   Created the `MetricCollector` API for instrumenting code with `Counter`, `Gauge`, and `Histogram` metrics.
    -   Provided `AtomicMetricCollector` as a high-performance, in-memory implementation.
    -   Introduced the `MetricExporter` API and `PrometheusExporterFactory` for pluggable monitoring backends.
    -   Implemented a JVM-native Prometheus exporter that serves metrics via a non-blocking Ktor server.

-   **Low-Level IO (`controls-ports`)**:
    -   Created a multiplatform `Port` and `SynchronousPort` API for raw byte-level communication, abstracting over transports like TCP or Serial.
    -   Added a `PortManager` plugin for discovering and creating `Port` instances from configuration.

## 1.0.0-alpha-2 - 2025-11-04

### Changed

-   **Architectural Shift: Separation of Specification and Logic**: The core paradigm has shifted. All executable logic for properties and actions is now defined exclusively in a `driverLogic { ... }` block, completely decoupling it from the `DeviceSpecification`. The `DeviceBlueprint` is now a pure, serializable data contract.
-   **`TransactionPlan` as a Workflow Engine**: `TransactionPlan` has been evolved from a simple sequence of commands into a workflow engine. It now supports passing data between steps using a type-safe `ref()` mechanism, conditional execution (`condition`), loops (`forEach`), and pauses (`delay`, `await`).
-   **Validation Framework**: Introduced a pluggable, deep validation system. A new `CompositeSpecValidator` discovers and applies `FeatureValidator` plugins to recursively verify an entire blueprint hierarchy before runtime.
-   **Persistence API**: The `SnapshotStore` interface has been updated to support storing and loading binary blobs in addition to `Meta`, enabling persistence for devices with file-based state.

### Added

-   **Behavioral Modeling & Guards**:
    -   Introduced `PropertyKind` enum (`PHYSICAL`, `LOGICAL`, `DERIVED`, `PREDICATE`) to semantically classify properties.
    -   Added a `guards { ... }` DSL to declaratively define operational guards that monitor predicates and automatically post events to the FSM (e.g., `whenTrue(...).forAtLeast(...).post<Event>()`).
-   **Distributed System Features**:
    -   Added a `mirrors { ... }` DSL and `RemoteMirrorFeature` to provide an "honest" abstraction for reactively mirroring properties from remote devices.
    -   Introduced `StreamPort` and `DeviceStreamSpec` to model continuous, bidirectional data streams as device members, complementing the message-based `Port`.
-   **API Formalization and Introspection**:
    -   Added `ActionOutputSpec` to formally declare the structure of an action's result `Meta`, enabling type-safe data flow in plans.
    -   Introduced extensible `MemberTag`s (e.g., `ProfileTag`, `AliasTag`) and `AdapterBinding` to attach semantic and protocol-specific metadata to device members.
    -   Added declarative validation rules for mutable properties via a `validation { ... }` block.
-   **Non-Functional Requirements in Model**:
    -   Added declarative resource locking (`ResourceLockSpec`) and caching policies (`CachePolicy`) to property and action descriptors.
-   **New Modules**:
    -   `controls-protocol-api`: Defines the `ProtocolAdapter` contract to separate protocol logic from transport.
    -   `controls-ktor`: Provides Ktor-based implementations for `Port` and `PeerConnection`.
    -   `controls-magix`: Provides `MessageBroker` and signaling services based on the Magix message bus.
    -   `controls-simulation`: Provides a virtual time engine for deterministic testing and simulation.
    -   `controls-persistence-log`: A high-performance, SQLite-based implementation of `AuditLogService` using SQLDelight.
