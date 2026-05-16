# Module krig-magix

**Maturity**: EXPERIMENTAL

## Description

This module provides the core, transport-agnostic API for the Magix message bus. It defines the fundamental contracts for communication, including the message structure (`MagixMessage`), endpoint interface (`MagixEndpoint`), message filter (`MagixMessageFilter`), and an envelope wrapper (`MagixEnvelope`) for hetero-mesh relay compatibility.

Transport implementations (RSocket, Ktor WebSocket, MQTT, in-process) live in the `krig-integrations` sibling repository, not in this module.

## Key Features

- **`MagixMessage`**: A serializable data class for all messages, compatible with Waltz-Controls RFC1. Source/target endpoints are typed `Name` and serialise as plain strings via DataForge `NameSerializer`.
- **`MagixEnvelope`**: Wraps a `MagixMessage` inside the payload so RFC1-strict relays preserve `topic` and `headers` fields through heterogeneous mesh hops.
- **`MagixEndpoint`**: A universal interface (`subscribe` + `broadcast`) abstracting the underlying transport protocol.
- **`MagixMessageFilter`**: Declarative server-side filtering by source, target, topic, and format.
- **`MagixFlowPlugin`** (`@ExperimentalMagixApi`): Server-side SPI for extending an in-process Magix message loop.

## Dependencies

- `kotlinx-serialization-json`
- `dataforge-names` (for `Name` / `NameSerializer`)
