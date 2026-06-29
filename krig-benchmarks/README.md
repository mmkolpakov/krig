# KRig benchmarks

JVM benchmark companion for KRig.

## Macro storage

Wall-clock storage scenarios:

- legacy-compatible `DeviceMessage` JSON journal through Exposed;
- direct JDBC event-journal baseline;
- legacy-compatible dense rows profile;
- typed narrow and wide time-series rows;
- compressed row chunks.

```shell
./gradlew :krig-benchmarks:run
```

The report is written to `krig-benchmarks/build/krig-benchmarks/storage-results.md`.
It includes controls-kt reference tables for the same benchmark shape as external baseline evidence.

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

Repeated local storage stats:

```shell
./gradlew :krig-benchmarks:storageStats
```

## Micro benchmarks

JMH-backed scenarios use `kotlinx-benchmark`:

- Meta conversion vs typed arithmetic;
- boxed `FlowSampler<Double>` vs `RingDoubleSampler`;
- raw operation call vs compiled krig pipeline;
- outcome values vs throw/catch fault path;
- device hub attach/detach.

```shell
./gradlew :krig-benchmarks:benchmark
```

Allocation-oriented hot-path probe:

```shell
./gradlew :krig-benchmarks:allocationProbe
```

The report is written to `krig-benchmarks/build/krig-benchmarks/allocation-results.md`.

## Transport encoding

The transport probe compares per-message `DeviceMessage` encoding with a columnar Arrow batch:

- JSON, CBOR, and ProtoBuf payload sizes and round-trips;
- Magix JSON envelope overhead;
- Arrow IPC sizes with and without ZSTD compression;
- per-value allocation and encode time for a fixed batch.

```shell
./gradlew :krig-benchmarks:transportProbe
```

The report is written to `krig-benchmarks/build/krig-benchmarks/transport-results.md`.
