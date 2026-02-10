# RealmPulse

Packet-driven fake-player atmosphere plugin for Spigot/Paper servers.

[中文说明](README-zh_CN.md)

## Features

- Packet-only ghost players: ghosts appear in tab/chat and do not spawn real entities.
- Weighted random levels: lower levels are more common, higher levels are rarer.
- Ghost name generation with mixed language ratio (ZH/EN), random ping, and tab display simulation.
- Chat atmosphere simulation:
  - idle messages
  - welcome messages for first-time joiners
  - mention/reply interactions
  - optional English dialogue rounds
- AI dual-channel design:
  - QA channel for real-time replies
  - Summary channel for phrase learning/cleanup
- Learning pipeline with persistent stores:
  - `learned-raw.yml`
  - `learned-phrases-chat.yml`
  - `learned-phrases-qa.yml`
  - legacy compatibility file support
- Additional simulators:
  - death broadcast simulator
  - join/quit simulator for ghosts
  - advancement broadcast simulator (`advancement-events`)
- Ops presets:
  - `/rp profile <lowcost|balanced|pro>`
  - `/rp scene <peak|quiet|promo>`
  - `/rp scene auto <on|off|status>` (time-slot scheduler)
- In-game config operations:
  - `/rp get|set|list`
  - `/rp config get|set|list`
- Config auto-sync on startup and reload:
  - keeps your existing `config.yml`
  - only adds missing keys from embedded default config
  - never overwrites existing values

## Requirements

- Java 21+
- Spigot/Paper 1.20+
- Vault (required)
- A Vault Chat provider plugin (required by runtime checks)
- ProtocolLib (required by runtime checks)
- Optional: LuckPerms (default prefix lookup)

## Installation

1. Put the jar into your server `plugins/` directory.
2. Start the server once to generate the default config folder.
3. Configure `plugins/RealmPulse/config.yml` (or copy from templates in this repo).
4. Set API keys in game (recommended):

```text
/rp qakey <your_key>
/rp summarykey <your_key>
```

5. Reload plugin config:

```text
/rp reload
```

## Config Templates

The repository includes:

- `config.yml` (full commented config)
- `config.min.yml` (low-cost/small-server baseline)
- `config.pro.yml` (higher activity baseline)

## Commands

Base command: `/realmpulse` (alias: `/rp`)

### General

- `/rp help`
- `/rp reload`

### Learning

- `/rp learn status`
- `/rp learn flush`

### Bot Count

- `/rp bots`
- `/rp addbot <count>`
- `/rp removebot <count>`
- `/rp delbot <count>`
- `/rp setbot <count>`

Notes:

- Bot count is capped at 500 in command handlers.

### AI Controls

- `/rp qamodel <model>`
- `/rp summarymodel <model>`
- `/rp qaon <on|off>`
- `/rp summaryon <on|off>`
- `/rp qaapi <url>`
- `/rp summaryapi <url>`
- `/rp qakey <key>`
- `/rp summarykey <key>`

### Presets & Scenes

- `/rp profile <lowcost|balanced|pro>`
- `/rp scene <peak|quiet|promo|auto>`
- `/rp scene auto <on|off|status>`

### Advancement Simulator

- `/rp advancement <status|trigger>`
- `/rp adv <status|trigger>`

### Advanced Config

- `/rp get <path>`
- `/rp set <path> <value>`
- `/rp list [module]`
- `/rp config get <path>`
- `/rp config set <path> <value>`
- `/rp config list [module]`

## Permissions

From `plugin.yml`:

- `realmpulse.command`
- `realmpulse.bot.manage`
- `realmpulse.ai.manage`
- `realmpulse.profile.apply`
- `realmpulse.scene.apply`
- `realmpulse.scene.auto`
- `realmpulse.reload`
- `realmpulse.config`
- `realmpulse.config.set`
- `realmpulse.learn.status`
- `realmpulse.learn.flush`
- `realmpulse.advancement.status`
- `realmpulse.advancement.trigger`

## Build

```bash
mvn -DskipTests clean package
```

Output jar:

- `target/RealmPulse-<version>.jar`

## Important Behavior Notes

- Ghosts are not real entities and cannot be targeted like normal players in-world.
- TPA to ghosts is intentionally blocked by `TeleportInterceptor` and returns a deny message.
- Ghost names are added to tab-complete for common messaging/TPA commands.

## License

MIT (see `LICENSE`).
