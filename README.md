# krig

Kotlin Multiplatform SDK for describing, running, testing, streaming, and replaying
devices with the same contracts. It is aimed at lab rigs, industrial controllers,
simulators, and distributed device systems.

## Quickstart

```kotlin
import kotlinx.coroutines.runBlocking
import space.kscience.krig.core.contracts.DeviceManifest
import space.kscience.krig.core.contracts.manifestOf
import space.kscience.krig.core.contracts.read
import space.kscience.krig.core.contracts.deviceBackend
import space.kscience.krig.core.contracts.sampling.doubleSampler
import space.kscience.krig.core.contracts.write
import space.kscience.krig.core.meta.DeviceContractBuilder
import space.kscience.krig.core.meta.doubleProperty
import space.kscience.krig.core.meta.mutableDoubleProperty
import space.kscience.krig.dsl.device

object PumpSpec : DeviceContractBuilder() {
    val rpm by mutableDoubleProperty()
    val load by doubleProperty()
}

val PumpManifest: DeviceManifest = manifestOf("demo.pump", PumpSpec)

fun pumpBackend() = deviceBackend {
    var rpm = 0.0
    val samples = doubleSampler()

    reader(PumpSpec.rpm) { rpm }
    writer(PumpSpec.rpm) { value -> rpm = value; samples.publishDouble(value) }
    sampler(PumpSpec.rpm) { samples }
    reader(PumpSpec.load) { rpm / 3_000.0 }
}

fun main() = runBlocking {
    val pump = device("mainPump", pumpBackend()) {
        manifest(PumpManifest)
    }

    pump.write(PumpSpec.rpm, 1_200.0)
    println(pump.read(PumpSpec.rpm))
}
```

The device DSL is useful for notebooks and scripts:

```kotlin
val thermo = device("thermo") {
    propertyDouble("sensor") { 23.5 }
    mutableProperty("setpoint", initial = 20.0)
}
```

## Design

1. **Manifests are separate from execution.** `DeviceManifest` is data. `DeviceBackend`
   is hardware, simulation, or transport.
2. **Contracts are pure.** `DeviceContractBuilder` describes typed properties and actions;
   backends provide execution.
3. **Typed access is the default path.** `read(spec)` / `write(spec, value)` avoid the
   `Meta` boundary for normal code. `readProperty(Name)` remains the control-plane adapter.
4. **Faults are values.** `OperationOutcome<T>` returns `Ok(value)` or `Fail(fault)`;
   fault types are open `Name` keys, not string switches.
5. **Quality is an opt-in overlay.** The default read path `read(spec): T` is quality-free;
   `Timestamped<T>` and `ObservedValue<T>` add time and `DataQuality` only on the observed path,
   without weighing down the core.
6. **Acquisition mapping is protocol-neutral.** krig validates named sources, timers,
   tags, and device-property targets; concrete connectors live outside the SDK core.
7. **Device identity is a `Name`.** Transport routes and physical addresses stay at the
   connector/envelope boundary, outside core device messages.
8. **Serialization is explicit.** KSP builds static indexes; REPL and integrations can add
   runtime `SerializationContributor`s without classpath scanning. DataForge `Context.gather`
   remains the runtime composition mechanism for contributed manifests, factories, and pipeline features.
9. **Real and virtual devices share one model.** The same contracts work with wall-clock
   devices, deterministic simulation, event logs, and counterfactual replay.
10. **Pipeline features assemble policies; capabilities hold per-host state.** Pipeline feature ids are
    `Name`s; application services are requested from the DataForge `Context`.

## Modules

Modules are layered. Dependencies point strictly down the layer numbers (and sideways to
leaves); contracts never depend on implementations, and no product module depends on
`krig-demo` / `krig-benchmarks` / `krig-jupyter`.

| Layer | Module | Purpose |
|---|---|---|
| L0 | `krig-state` | Values, `Timestamped`, `ObservedValue`, `DataQuality` |
| L0 | `krig-magix` | Magix bus contract (`space.kscience.magix.*` namespace) |
| L1 | `krig-identity` | Principals, permissions, audit, authorization |
| L2 | `krig-model` | Descriptors, `TypeId`, expressions, conditions, retry policy |
| L2 | `krig-operation` | `OperationOutcome`, faults, QoS pipeline, gates, observers, locks |
| L3 | `krig-messaging` | Device message DTOs, frames, hub events, serialization |
| L4 | `krig-storage` | Event-journal and time-series contracts plus in-memory references |
| L5 | `krig-contracts` | The waist: `Device`, `DeviceBackend`, `DeviceManifest`, `AbstractDevice`, typed access, samplers, HLC |
| L6 | `krig-runtime-stdlib` | Default contract implementations: device hubs, groups, reconcile, time travel, file journal |
| L7 | `krig-runtime` | Engine and authoring DSL: QoS pipeline, `device { }` / `stateModel`, dynamic groups |
| L8 | `krig-assembly` | Acquisition DSL, Manifest/factory catalog, data-platform polling |
| — | `krig-simulation` | Deterministic scheduler, resources, process DSL (virtual time) |
| — | `krig-flow` | KMP declarative flow graphs and distributed flow-transfer DTOs |
| — | `krig-ui-schema` | KMP neutral form descriptors projected from manifests |
| — | `krig-server` | JVM-only Ktor routes for discovery and device operations |
| — | `krig-arrow` | JVM-only: Apache Arrow / Feather export |
| — | `krig-analytics` | DataForge Workspace tasks and data selectors over the event journal (multiplatform) |
| — | `krig-jupyter` | JVM-only: Kotlin Notebook integration and renderers |
| — | `krig-ksp-processor` | Compile-time validation and serializers-module generation |
| — | `krig-bom` | Aligned dependency versions |
| — | `krig-demo` | Runnable examples |
| — | `krig-benchmarks` | Storage, sampler, and pipeline benchmark profiles |

`krig-runtime-stdlib` holds the default *implementations* of the `krig-contracts` interfaces
(hubs, groups, journals, time travel). `krig-runtime` is the *engine and DSL* that orchestrates
them (pipeline execution, `device { }`). Add a new default implementation to the former; add a
new pipeline step or authoring DSL to the latter.

## Stack

Kotlin **2.4.0**, kotlinx.coroutines **1.11.0**, KSP **2.3.9**,
Gradle **9.6.0**. Targets: JVM 21, JS browser, Wasm JS, Linux x64,
Windows x64, macOS, and iOS.

## Demos

Run all demos:

```shell
./gradlew :krig-demo:jvmRun
```

For the notebook demo from a local checkout, publish the JVM integration first:

```shell
./gradlew publishToMavenLocal
```

Read the demos by product story: typed and Meta device access, acquisition and
topology, broker/distributed wire contracts, storage/replay/analytics, virtual
devices, and operation policies.

| Demo | File | Shows |
|---|---|---|
| Demo suite | [`DemoSuite.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/DemoSuite.kt) | Runs the showcase block and the full smoke suite |
| Golden path | [`GoldenPathDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/GoldenPathDemo.kt) | Typed data plane, quality-aware read, sampler snapshot |
| Industrial assembly | [`IndustrialAssemblyDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/IndustrialAssemblyDemo.kt) | `manifestOf`, typed `read`/`write`/action, retry installation |
| Calibration task | [`CalibrationTaskDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/CalibrationTaskDemo.kt) | Action-triggered task state as observable device data |
| State model | [`StateModelDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/StateModelDemo.kt) | Explicit virtual-device state mapped to typed properties and actions |
| Meta interop | [`MetaInteropDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/MetaInteropDemo.kt) | Dynamic Meta read/write and JSON interop beside the typed hot path |
| Lab discovery | [`LabDiscoveryAdHocDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/LabDiscoveryAdHocDemo.kt) | Schemaless/ad-hoc property discovery for lab setup |
| Device tree | [`DeviceTreeDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/DeviceTreeDemo.kt) | Folder nodes and alternative topology views over live devices |
| Device-tree acquisition | [`DeviceTreeAcquisitionDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/DeviceTreeAcquisitionDemo.kt) | Device group topology feeding reusable acquisition runners |
| Batch acquisition | [`BatchAcquisitionDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/BatchAcquisitionDemo.kt) | Quality-preserving batch reads and transactional batch writes |
| Tag table backend | [`TagTableBackendDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/TagTableBackendDemo.kt) | Tag-oriented source projected into device properties |
| Binary payload | [`BinaryPayloadDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/BinaryPayloadDemo.kt) | DataForge `Binary` payload reads and explicit unsupported-binary faults |
| Edge telemetry wire | [`EdgeTelemetryWireDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/EdgeTelemetryWireDemo.kt) | Common dense telemetry chunk for edge-to-analytics transfer |
| Distributed typed proxy | [`DistributedTypedProxyDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/DistributedTypedProxyDemo.kt) | Schema-hash guarded activation of a remote typed facade |
| Distributed flow transfer | [`DistributedFlowTransferDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/DistributedFlowTransferDemo.kt) | Flow boundary transfer carried as a broker message frame |
| Magix envelope interop | [`MagixEnvelopeInteropDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/MagixEnvelopeInteropDemo.kt) | KRig frame constants through a Magix envelope hop |
| Envelope broker interop | [`EnvelopeBrokerInteropDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/EnvelopeBrokerInteropDemo.kt) | DataForge `Envelope` bridge for broker/storage boundaries |
| Telemetry analytics | [`TelemetryAnalyticsDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/TelemetryAnalyticsDemo.kt) | Rows compression, `tables-kt` bridge, DataForge `DataSource`, and quality-aware diagnostic slicing |
| Replay what-if workspace | [`ReplayWhatIfWorkspaceDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/ReplayWhatIfWorkspaceDemo.kt) | DataForge Workspace action over replay/counterfactual state |
| External polling | [`ExternalPollingDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/ExternalPollingDemo.kt) | Protocol-neutral acquisition mapping driven by one shared timer |
| Streaming | [`StreamingDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/StreamingDemo.kt) | Primitive sampler, typed samples, shared ticks, zero-order hold for UI-rate streams |
| Shared timer control | [`SharedTimerControlDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/SharedTimerControlDemo.kt) | One timer shared by control loop and UI-rate sampling |
| Flaky network | [`FlakyNetworkDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/FlakyNetworkDemo.kt) | Transient driver faults recovered by operation retry |
| Policy and faults | [`PolicyFaultsDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/PolicyFaultsDemo.kt) | PipelineFeature-installed capability, write gate, validation fault, observer fault capture |
| Auth and audit | [`AuthAuditDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/AuthAuditDemo.kt) | DataForge `Context` plugins for global auth/audit, operation faults as values |
| Simulation process | [`SimulationProcessDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/SimulationProcessDemo.kt) | Virtual-time process driving a device |
| Digital twin | [`DigitalTwinDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/DigitalTwinDemo.kt) | Deterministic RK4 model attached through the same device contract |
| Device hub | [`DeviceHubDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/DeviceHubDemo.kt) | Attach, detach, hub events, reconcile loop |
| Replay navigation | [`ReplayNavigationDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/ReplayNavigationDemo.kt) | Branch points and alternative replay without mutating the source log |
| Time travel | [`TimeTravelDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/TimeTravelDemo.kt) | Replay, snapshots, branches, journal migration |
| Expressions | [`ExpressionDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/ExpressionDemo.kt) | Expression tree compiled into reactive device state |
| Device scripting DSL | [`DeviceDslDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/DeviceDslDemo.kt) | Declarative properties, computed same-device reads, property history |
| Kotlin Notebook | [`krig-intro.ipynb`](krig-jupyter/src/main/resources/krig-intro.ipynb) | local `%use @file[krig.json]`, device DSL, history, hub, timeline, simulation, storage |

## Benchmarks

| Bench | Module | Shows |
|---|---|---|
| Storage and data plane | [`krig-benchmarks`](krig-benchmarks) | controls-kt storage reference beside krig storage, sampler, and pipeline benchmark profiles |

## Documentation

Published API docs: <https://mmkolpakov.github.io/krig/>

```shell
./gradlew dokkaGenerate
```

## Related Projects

- [controls-kt](https://git.sciprog.center/kscience/controls-kt)
- [DataForge](https://github.com/SciProgCentre/dataforge-core)
- [kmath](https://github.com/SciProgCentre/kmath)

## License

[Apache 2.0](LICENSE) · Copyright 2024-2026 KScience / SPC MIPT
