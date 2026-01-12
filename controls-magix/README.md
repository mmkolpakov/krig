# Module controls-magix

**Maturity**: EXPERIMENTAL

## Description

This module provides an adapter layer to use the [Magix message bus](https://github.com/SciProgCentre/magix) as the primary communication backbone for `controls-ng`. It implements the core `MessageBroker` contract and provides services for peer-to-peer signaling, enabling the creation of distributed control systems.

## Key Features

- **`MagixMessageBroker`**: An implementation of the `MessageBroker` contract that uses a `MagixEndpoint` for publishing and subscribing to `DeviceMessage`s. It translates topic-based communication to Magix's source/target endpoint model.
- **`MagixPeerSignalingService`**: A service that leverages Magix for the *signaling* part of peer-to-peer binary transfers. It allows devices to notify each other about available binary data, which is then transferred out-of-band via a `PeerConnection` implementation (like one from `controls-ktor`).
- **`MagixBrokerPlugin`**: A DataForge plugin that simplifies configuration. It discovers available `MagixEndpointFactory` plugins (like `magix-transport-rsocket`) and provides configured instances of the broker and signaling service.

## Usage

Typically, you would configure the `MagixBrokerPlugin` in your main context.

```kotlin
// It is assumed that a plugin for the 'rsocket.ws' type is available in the context.
plugin(MagixBrokerPlugin) {
    "hubId" put "my-control-hub"
    "endpoint" {
        "type" put "rsocket.ws"
        "host" put "localhost"
        "port" put 7777
    }
}

// Then, in your runtime, you can get the broker:
val broker = context.request(MagixBrokerPlugin).broker()
```