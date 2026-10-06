# Karpfen Connectors

Starter templates for connecting to a [karpfen](https://github.com/karpfenproject) runtime server via its HTTP + WebSocket API.

## Starter templates

| Directory | Language |
|-----------|----------|
| `python_starter/` | Python |
| `java_starter/` | Java |
| `scala_starter/` | Scala |

Each starter contains a `KarpfenConnector` client class and a `Main`/`main.py` entry point that runs a minimal lightbulb demo against a local Karpfen server (`127.0.0.1:8080`).

## Example models

`example_models/` holds four DSL files that are loaded by all starters:

| File | Purpose |
|------|---------|
| `domain.kmeta` | Metamodel — defines `Lightbulb` and `Room` types |
| `world.kmodel` | World model — instantiates a room with a lightbulb |
| `EVENTS.kmeta` | Event payload definitions — the `movement detected` event carries a `duration` |
| `lightbulb.kstates` | State machine — drives the lightbulb through `light_off → movement_detected → light_on` states; the light stays on for the event's `duration` ticks |

## Running a starter

Start a Karpfen runtime server first, then from the relevant directory, execute the `run.sh` files or the respective main classes directly.

Press ENTER to send a `movement detected` event (payload `{"duration": 3}`), or type a number and press ENTER to use a custom duration. The Python starter requires Python 3.10+, the Java starter Java 17+, the Scala starter Scala 3.

## Connector API

All three `KarpfenConnector` classes expose the same operations (Python uses `snake_case`):

| Method | Endpoint | Notes |
|--------|----------|-------|
| `health` | `GET /health` | `true` if the server is reachable |
| `createEnvironment` | `POST /createEnvironment` | Returns the env key |
| `setMetamodel` | `PUT /setMetamodel` | Set before the model |
| `setModel` | `PUT /setModel` | |
| `setEventDefinitions` | `PUT /setEventDefinitions` | Optional, only for events with payloads |
| `setStateMachine` | `PUT /setStateMachine` | Attached to a model object ID |
| `setTickDelay` | `POST /setTickDelay` | |
| `setEventTtl` | `POST /setEventTtl` | `0` = events never expire |
| `registerClientForWebSocket` | `POST /registerClientForWebSocket` | Returns the WebSocket access key |
| `registerObjectObserver` | `POST /registerObjectObserver` | `objectChanged` / `objectDeleted` notifications |
| `registerDomainListener` | `POST /registerDomainListener` | `domainEvent` notifications |
| `runEnvironment` | `POST /runEnvironment` | Activates; required before start, environment is immutable afterwards |
| `startEnvironment` | `POST /startEnvironment` | |
| `stopEnvironment` | `POST /stopEnvironment` | |
| `setActiveState` | `POST /setActiveState` | Force a running state machine into a leaf state |
| `listEnvironments` | `GET /observatory/environments` | |
| `getStateMachineSource` | `GET /observatory/statemachine` | |
| `registerObservatoryClient` | `POST /observatory/registerClient` | Returns an access key for trace updates |
| `connectWebSocket` | `ws://…/ws` | Authenticates and delivers notifications to a callback |
| `sendEvent` | WebSocket message | `domain`, `eventName`, optional `payload` (empty, JSON, or kmodel `make object` block) and optional `payloadFormat` (`none`/`json`/`kmodel`, guessed when omitted) |

Failed requests raise an error containing the HTTP status and the server's message (`400` invalid parameters, `409` lifecycle conflict such as modifying an active environment).
