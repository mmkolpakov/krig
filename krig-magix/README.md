# Module krig-magix

**Maturity**: EXPERIMENTAL

## Description

This module provides the core, transport-agnostic API for the Magix message bus. It defines the fundamental contracts for communication, including the message structure (`MagixMessage`), endpoint interface (`MagixEndpoint`), message filter (`MagixMessageFilter`), and an envelope wrapper (`MagixEnvelope`) for hetero-mesh relay compatibility.

Transport implementations (RSocket, Ktor WebSocket, MQTT, in-process) live in the `krig-integrations` sibling repository, not in this module.

## Key Features

- **`MagixMessage`**: Runtime DTO for the DataForge/controls-kt Magix dialect (`sourceEndpoint`, `targetEndpoint`). Endpoints are typed `Name` and serialise as plain strings via DataForge `NameSerializer`.
- **`WaltzRfc1Message`**: Boundary DTO for canonical Waltz-Controls RFC1 (`origin`, `target`, `format`, `payload`) plus adapters to/from `MagixMessage`.
- **`MagixEnvelope`**: Wraps KRig metadata inside the payload so strict RFC1 relays preserve `topic`, `headers`, and the original payload `format` through heterogeneous mesh hops.
- **`MagixEndpoint`**: A universal interface (`subscribe` + `broadcast`) abstracting the underlying transport protocol.
- **`MagixMessageFilter`**: Declarative server-side filtering by source, target, topic, and format.
- **`MagixFlowPlugin`** (`@ExperimentalMagixApi`): Server-side SPI for extending an in-process Magix message loop.

## Dependencies

- `kotlinx-serialization-json`
- `dataforge-names` (for `Name` / `NameSerializer`)
