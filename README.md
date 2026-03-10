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

`example_models/` holds three DSL files that are loaded by all starters:

| File | Purpose |
|------|---------|
| `domain.kmeta` | Metamodel — defines `Lightbulb` and `Room` types |
| `world.kmodel` | World model — instantiates a room with a lightbulb |
| `lightbulb.kstates` | State machine — drives the lightbulb through `light_off → movement_detected → light_on` states |

## Running a starter

Start a Karpfen runtime server first, then from the relevant directory, execute the `run.sh` files or the respective main classes directly