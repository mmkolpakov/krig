# Module controls-metrics

**Maturity**: EXPERIMENTAL

## Description

This module provides a cross-platform, foundational API for metrics collection within the `controls-ng` framework. It defines universal contracts for common metric types like counters, gauges, and histograms, and includes a default, high-performance, in-memory implementation based on `kotlinx.atomicfu`.

The core philosophy is to decouple metric instrumentation from specific monitoring backends. Your device logic can record metrics using the provided APIs, and a separate exporter module (like `controls-exporter-prometheus`) can then collect and expose this data.

## Key Features

- **`MetricCollector` Interface**: A universal contract for a registry that creates, stores, and reports metrics.
- **Core Metric Types**:
    - **`Counter`**: A monotonically increasing value.
    - **`Gauge`**: A value that can arbitrarily go up and down.
    - **`Histogram`**: Samples observations (e.g., request durations) into configurable buckets.
- **`AtomicMetricCollector`**: A thread-safe, lock-free, in-memory implementation of `MetricCollector` suitable for multi-threaded environments.
- **`MetricExporter` API**: A pluggable factory-based API (`MetricExporterFactory`) for creating exporters to various monitoring systems.
- **`MetricPlugin`**: A DataForge plugin for managing `MetricCollector` instances within a context.
- **`NoOpMetricCollector`**: A fallback implementation that completely disables metric collection, ensuring zero performance overhead if metrics are not configured.

## Usage

### Instrumenting Code

```kotlin
// Get a collector from the context
val collector = context.metricCollector("my-device")

// Create and use a counter
val requestsTotal = collector.counter(MetricId("http_requests_total", mapOf("method" to "GET")))
requestsTotal.inc()

// Create and use a gauge
val queueSize = collector.gauge(MetricId("job_queue_size"))
queueSize.value = 15.0

// Measure execution time with a histogram
val requestDuration = collector.histogram(MetricId("http_request_duration_seconds"), buckets = listOf(0.1, 0.5, 1.0))
timed(requestDuration) {
    // some long-running operation
}

```