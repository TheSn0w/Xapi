# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Xapi is a comprehensive action debugger and reverse-engineering toolkit built on the BotWithUs Java game scripting framework. It communicates with a game server via Windows named pipes using MessagePack-encoded JSON-RPC. The Xapi script is dynamically discovered at runtime via Java's ServiceLoader SPI and executes on virtual threads.

Group: `com.botwithus` | Java 21 | Gradle 8.14 (Kotlin DSL) | JUnit 5

## Build Commands

```bash
./gradlew build                    # Build all modules (also installs xapi-script JAR to scripts/)
./gradlew clean build              # Clean and rebuild
./gradlew :cli:run                 # Run the CLI/GUI application
./gradlew :xapi-script:build       # Build and auto-install xapi script to scripts/
./gradlew test                     # Run tests
./gradlew test --tests "com.botwithus.SomeTest.methodName"  # Run a single test
```

## Module Architecture

Four Gradle subprojects with strict dependency layering:

```
api                 (slf4j-api)      — Public interfaces, models, query builders
  ^ required by
core                (api + msgpack + logback) — RPC client, pipe transport, script runtime
  ^ required by
cli                 (api + core)     — Interactive CLI/GUI, command system
xapi-script         (api + core)     — Xapi action debugger script
```

### api (`com.botwithus.bot.api`)
Pure interface module (sole dependency: `slf4j-api`, exposed transitively). Contains:
- **`BotScript`** — SPI interface scripts implement (`onStart`/`onLoop`/`onStop`)
- **`GameAPI`** — 100+ methods for game interaction (entities, inventories, actions, UI, vars, cache)
- **`ScriptContext`** — Provides scripts access to GameAPI, EventBus, MessageBus
- **`inventory/ActionTranslator`** — Translates raw action params into copy-paste script code
- **`inventory/ActionTypes`** — Action ID constants and name lookups
- **`entities/`** — Fluent query builders: `Npcs`, `Players`, `SceneObjects`, `GroundItems`
- **`inventory/`** — `Backpack`, `Bank`, `Equipment` wrappers, `ComponentHelper`
- **`event/`** — `EventBus` and game events (ActionExecuted, Tick, VarbitChange, VarChange, ChatMessage, etc.)
- **`query/`** — Filter interfaces for entity/component/inventory queries

### core (`com.botwithus.bot.core`)
Runtime and communication layer:
- **`pipe/PipeClient`** — Windows named pipe client (`\\.\pipe\BotWithUs`), length-prefixed messages
- **`rpc/RpcClient`** — Synchronous JSON-RPC over pipe with MessagePack serialization
- **`runtime/ScriptLoader`** — Discovers script JARs in `scripts/` dir, creates child `ModuleLayer` per script
- **`runtime/ScriptRunner`** — Runs individual script on a virtual thread
- **`impl/ActionDebugger`** — Singleton interceptor for recording/blocking/selective-blocking game actions

### cli (`com.botwithus.bot.cli`)
ImGui-based GUI application:
- **Main class**: `com.botwithus.bot.cli.gui.ImGuiApp`
- **GUI panels**: Console, Logs, Scripts, Script UI (where Xapi renders)

### xapi-script (`com.botwithus.bot.scripts.xapi`)
The main Xapi debugger script with 5 tabs: Actions, Variables, Chat, Entities, Interfaces. Build auto-copies JAR to `scripts/`.

## Key Patterns

**Threading safety**: NO RPC calls on the ImGui render thread. All data for display must be pre-collected into thread-safe collections (CopyOnWriteArrayList). Event handlers and onLoop() are RPC-safe.

**Script SPI**: Scripts must be Java modules that `provides com.botwithus.bot.api.BotScript with <ClassName>` in their `module-info.java`.

**Communication flow**: `BotScript -> GameAPI -> RpcClient -> PipeClient -> Game Server`

**Script installation**: Script JARs go in the `scripts/` directory at project root. The `xapi-script` build task does this automatically via `installScript`.

**Logging**: Use `private static final Logger log = LoggerFactory.getLogger(ClassName.class);` (from `org.slf4j`). Never use `System.out/err.println`.
