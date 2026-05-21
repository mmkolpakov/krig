# krig

Kotlin Multiplatform SDK for describing, running, testing, streaming, and replaying
devices with the same contracts. It is aimed at lab rigs, industrial controllers,
simulators, and distributed device systems.

## Quickstart

```kotlin
import kotlinx.coroutines.runBlocking
import space.kscience.krig.core.contracts.Device
import space.kscience.krig.core.contracts.DeviceBlueprint
import space.kscience.krig.core.contracts.blueprintOf
import space.kscience.krig.core.contracts.read
import space.kscience.krig.core.contracts.sampling.doubleSampler
import space.kscience.krig.core.contracts.typed.backend
import space.kscience.krig.core.contracts.write
import space.kscience.krig.core.meta.DeviceContractBuilder
import space.kscience.krig.dsl.device

object PumpSpec : DeviceContractBuilder() {
    val rpm by mutableDoubleProperty()
    val load by doubleProperty()
}

val PumpBlueprint: DeviceBlueprint<Device> = blueprintOf("demo.pump", PumpSpec)

fun pumpBackend() = backend {
    var rpm = 0.0
    val samples = doubleSampler()

    reader(PumpSpec.rpm) { rpm }
    writer(PumpSpec.rpm) { value -> rpm = value; samples.publishDouble(value) }
    sampler(PumpSpec.rpm) { samples }
    reader(PumpSpec.load) { rpm / 3_000.0 }
}

fun main() = runBlocking {
    val pump = device("mainPump", pumpBackend()) {
        blueprint(PumpBlueprint)
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

1. **Blueprints are separate from execution.** `DeviceBlueprint` is data. `DeviceBackend`
   is hardware, simulation, or transport.
2. **Contracts are pure.** `DeviceContractBuilder` describes typed properties and actions;
   backends provide execution.
3. **Typed access is the default path.** `read(spec)` / `write(spec, value)` avoid the
   `Meta` boundary for normal code. `readProperty(Name)` remains the control-plane adapter.
4. **Faults are values.** `OperationOutcome<T>` returns `Ok(value)` or `Fail(fault)`;
   fault types are open `Name` keys, not string switches.
5. **State keeps time and quality.** `Timestamped<T>` and `ObservedValue<T>` carry time and
   `DataQuality` through reactive views.
6. **Acquisition mapping is protocol-neutral.** krig validates sources, timers, tags, and
   device-property targets; concrete connectors live outside the SDK core.
7. **Device identity is a `Name`.** Transport routes and physical addresses stay at the
   connector/envelope boundary, outside core device messages.
8. **Serialization is explicit.** KSP builds static indexes; REPL and integrations can add
   runtime `SerializationContributor`s without classpath scanning.
9. **Real and virtual devices share one model.** The same contracts work with wall-clock
   devices, deterministic simulation, event logs, and counterfactual replay.
10. **Features assemble policies; capabilities hold local state.** Feature ids are `Name`s;
    global services stay in DataForge `Context` plugins.

## Modules

| Layer | Module | Purpose |
|---|---|---|
| Data | `krig-state` | Lifecycle, `Timestamped`, `ObservedValue`, `DataQuality`, snapshots |
| Data | `krig-identity` | Principals, permissions, audit, authorization |
| Model | `krig-model` | Descriptors, feature specs, expressions, retry policy |
| Operation | `krig-operation` | `OperationOutcome`, faults, QoS pipeline, gates, observers, locks |
| Messaging | `krig-messaging` | Device messages and serialization |
| Storage | `krig-storage` | Event journals, typed time-series samples/chunks, storage profiles |
| Contracts | `krig-contracts` | Device, backend, blueprint, typed access, samplers, HLC |
| Runtime | `krig-runtime` | DSL, operation pipeline assembly, gates, observers, dynamic groups |
| Primitives | `krig-primitives` | State, storage, event log, time travel, expressions, peer transport |
| Transport | `krig-magix` | Magix endpoint and envelope support |
| Simulation | `krig-simulation` | Deterministic scheduler, resources, signals, process DSL |
| Build | `krig-ksp-processor` | KSP2 validation and serializers module generation |
| Build | `krig-bom` | Aligned dependency versions |
| Demo | `krig-demo` | Runnable examples |
| Notebook | `krig-jupyter` | Kotlin Notebook integration and renderers |

## Stack

Kotlin **2.4.0-RC**, kotlinx.coroutines **1.11.0**, KSP **2.3.8**,
Gradle **9.5.1**. Targets: JVM 21, JS browser, Wasm JS, Linux x64,
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

| Demo | File | Shows |
|---|---|---|
| Demo suite | [`DemoSuite.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/DemoSuite.kt) | Runs the curated alpha-3 demo set |
| Industrial assembly | [`IndustrialAssemblyDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/IndustrialAssemblyDemo.kt) | `blueprintOf`, `backend`, typed `read`/`write`/action, retry installation |
| Meta interop | [`MetaInteropDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/MetaInteropDemo.kt) | Dynamic Meta read/write and JSON interop beside the typed hot path |
| Data platform | [`DataPlatformDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/DataPlatformDemo.kt) | Declarative platform map executed against a live device |
| External polling | [`ExternalPollingDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/ExternalPollingDemo.kt) | Protocol-neutral acquisition mapping driven by one shared timer |
| Streaming | [`StreamingDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/StreamingDemo.kt) | Primitive sampler, typed samples, shared ticks, zero-order hold for UI-rate streams |
| Shared timer control | [`SharedTimerControlDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/SharedTimerControlDemo.kt) | One timer shared by control loop and UI-rate sampling |
| Flaky network | [`FlakyNetworkDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/FlakyNetworkDemo.kt) | Transient driver faults recovered by operation retry |
| Policy and faults | [`PolicyFaultsDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/PolicyFaultsDemo.kt) | Feature-installed capability, write gate, validation fault, observer fault capture |
| Auth and audit | [`AuthAuditDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/AuthAuditDemo.kt) | DataForge `Context` plugins for global auth/audit, operation faults as values |
| Simulation process | [`SimulationProcessDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/SimulationProcessDemo.kt) | Virtual-time process driving a device |
| Dynamic hub | [`DynamicHubDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/DynamicHubDemo.kt) | Attach, detach, hub events, reconcile loop |
| Time travel | [`TimeTravelDemo.kt`](krig-demo/src/commonMain/kotlin/space/kscience/krig/demo/TimeTravelDemo.kt) | Event log, replay, counterfactual DSL |
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
