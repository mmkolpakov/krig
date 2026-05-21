# krig benchmarks

JVM benchmark companion for the KRig.

## Macro storage

Wall-clock storage scenarios:

- controls-compatible `DeviceMessage` JSON journal through Exposed;
- direct JDBC event-journal baseline;
- compatible dense rows profile for controls-kt reference numbers;
- typed narrow and wide time-series rows;
- compressed row chunks.

```shell
./gradlew :krig-benchmarks:run
```

The report is written to `krig-benchmarks/build/krig-benchmarks/storage-results.md`.
The report includes controls-kt reference tables captured for the same benchmark
shape.

TimescaleDB is optional:

```shell
KRIG_STORAGE_BENCH_TIMESCALE=true ./gradlew :krig-benchmarks:run
```

External JDBC is optional:

```shell
KRIG_STORAGE_BENCH_JDBC_URL=jdbc:postgresql://localhost:5432/test?reWriteBatchedInserts=true
KRIG_STORAGE_BENCH_JDBC_USER=test
KRIG_STORAGE_BENCH_JDBC_PASSWORD=test
./gradlew :krig-benchmarks:run
```

## Micro benchmarks

JMH-backed scenarios use `kotlinx-benchmark`:

- Meta conversion vs typed arithmetic;
- boxed `FlowSampler<Double>` vs `RingDoubleSampler`;
- raw operation call vs compiled krig pipeline;
- outcome values vs throw/catch fault path;
- dynamic hub attach/detach.

```shell
./gradlew :krig-benchmarks:benchmark
```
