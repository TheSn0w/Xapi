# Xapi

A comprehensive action debugger and reverse-engineering toolkit for the BotWithUs game scripting framework. Record, analyze, and generate code from in-game interactions.

## Features

### Action Debugger
- **Record** all game actions with full parameter logging and resolved entity/option names
- **Block** actions from reaching the game while still recording what would have been sent
- **Selective blocking** — block only specific action categories (NPC, Object, Walk, etc.)
- **Game tick alignment** — see which actions fire on the same tick, with tick deltas
- **Player state snapshots** — position, animation, movement captured per action
- **Copy-paste code generation** — 3-line output per action:
  - Human-readable summary (yellow): `Attack -> Hill Giant`
  - High-level API call (green): `npcs.query().name("Hill Giant").first().interact("Attack")`
  - Raw GameAction (gray): `api.queueAction(new GameAction(9, 5227, 0, 0))`
- **Context-aware copy** — click any line to copy all 3, with clicked line as active code and others commented

### Varbit/Varp Monitor
- Live tracking of all game variable changes (varbits and varps)
- Watchlist filtering — track only specific variable IDs
- Correlation highlighting — var changes on the same tick as an action are visually linked
- Click variable ID to copy `api.getVarbit(id)` code

### Chat Message Log
- Captures all chat messages with player names and message types
- Same-tick-as-action highlighting for dialogue correlation
- Text filter search

### Entity Inspector
- Browse all nearby NPCs, Players, and Objects in real-time
- Shows name, typeId, serverIndex, position, movement state
- Click to copy full entity query code

### Interface/Component Inspector
- Live view of all open game interfaces
- Expand interfaces to browse component trees with text, options, itemId, spriteId
- Click to copy component query code — generates complete working snippets

### Session Management
- **Export** recording sessions to JSON for sharing and offline analysis
- **Import** previously saved sessions
- **Script generation** — generate a complete runnable BotScript from a recording session
- **Action replay** — replay recorded sessions as a macro with adjustable speed (0.25x-4x)

### Inventory Tracking
- Real-time inventory diff display showing items gained/lost

## Quick Start

```bash
# Build all modules
./gradlew build

# Run the GUI application
./gradlew :cli:run
```

Start the Xapi script from the Scripts panel. The debugger UI appears in the Script UI tab.

## Module Architecture

```
api             — Public interfaces, models, query builders, code generation
core            — RPC client, pipe transport, script runtime, action interceptor
cli             — ImGui-based GUI application
xapi-script     — The Xapi debugger script (auto-installed to scripts/)
```

## Build Commands

```bash
./gradlew build                  # Build all modules (installs xapi-script to scripts/)
./gradlew clean build            # Clean and rebuild
./gradlew :cli:run               # Run the GUI application
./gradlew :xapi-script:build     # Build and install xapi script only
./gradlew test                   # Run all tests
```

## Requirements

- Java 21+
- Windows (named pipe transport)
- Gradle 8.14+ (included via wrapper)
