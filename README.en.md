# RealmPulse (Packet Fake Player Atmosphere Plugin)

> A lightweight, operations-friendly plugin to make your server feel active.
> Non-entity fake players + AI chat/learning + scene-based scheduling.

[中文 README](README.md)

![Java 21](https://img.shields.io/badge/Java-21-007396?logo=java&logoColor=white)
![Spigot/Paper 1.20+](https://img.shields.io/badge/Spigot%2FPaper-1.20%2B-ED8106)
![ProtocolLib 5+](https://img.shields.io/badge/ProtocolLib-5%2B-5C2D91)
![Vault Required](https://img.shields.io/badge/Vault-required-2ea44f)
![Version 1.0](https://img.shields.io/badge/Version-1.0-blue)

## ✨ Features

- 👻 Packet-based fake players (Tab/chat layer only, no real entities).
- 🆔 Enhanced realistic ID library (large CN/EN-style pools with lower repetition bias).
- 🎚️ Weighted random levels (higher level appears less frequently).
- 🌐 Bilingual tendency support (ZH/EN template pools).
- 🧠 AI dual-channel design:
  - QA: real-time reply generation
  - Summary: phrase extraction and learning
- 🧪 Learning pipeline: collect, filter, deduplicate, summarize, persist.
- 🌏 Chinese-first admin UX with optional secondary language display.
- 🔄 Scene presets:
  - `/rp profile <lowcost|balanced|pro>`
  - `/rp scene <peak|quiet|promo|auto>`
  - `/rp scene auto <on|off|status>`
- 🏆 Advancement announcement simulation (auto/manual).
- ☠️ Death and online/offline simulation.
- 🛠️ In-game config editing (`get/set/list` and `config get/set/list`).
- 🧩 Auto config key completion on startup and reload.

## 📦 Requirements

- Java 21+
- Spigot/Paper 1.20+
- Vault (required)
- Vault Chat Provider (required at runtime)
- ProtocolLib (required at runtime)
- Optional: LuckPerms (for `default` group prefix)

## 🚀 Quick Start

1. Put the plugin JAR into `plugins/`.
2. Start the server once to generate config files.
3. Edit `plugins/RealmPulse/config.yml`.
4. Set API keys in game (recommended):

```text
/rp qakey <your_key>
/rp summarykey <your_key>
```

5. Reload config:

```text
/rp reload
```

## 🧾 Config Templates

- `config.yml`: full commented template (recommended)
- `config.min.yml`: low-cost baseline
- `config.pro.yml`: high-activity/high-quality baseline

## 🕹️ Commands

Main command: `/realmpulse`, alias: `/rp`

### 👥 Bot Management

| Command | Description |
| --- | --- |
| `/rp bots` | Show current bot count |
| `/rp addbot <count>` | Increase bot count |
| `/rp removebot <count>` | Decrease bot count |
| `/rp delbot <count>` | Alias of `removebot` |
| `/rp setbot <count>` | Set total bot count |

> Hard safety cap for total bot count: `500`.

### 🤖 AI Management

| Command | Description |
| --- | --- |
| `/rp qamodel <model>` | Set QA model |
| `/rp summarymodel <model>` | Set summary model |
| `/rp qaon <on\|off>` | Toggle QA AI |
| `/rp summaryon <on\|off>` | Toggle summary AI |
| `/rp qaapi <url>` | Set QA API URL |
| `/rp summaryapi <url>` | Set summary API URL |
| `/rp qakey <key>` | Set QA API key |
| `/rp summarykey <key>` | Set summary API key |

### 🎛️ Scene/Operation Presets

| Command | Description |
| --- | --- |
| `/rp profile lowcost` | Low-cost preset |
| `/rp profile balanced` | Balanced preset |
| `/rp profile pro` | High-quality preset |
| `/rp scene peak` | Peak-time active scene |
| `/rp scene quiet` | Quiet-time low-cost scene |
| `/rp scene promo` | Promotion scene |
| `/rp scene auto on/off/status` | Auto scene scheduler control |

### 📚 Learning and Maintenance

| Command | Description |
| --- | --- |
| `/rp learn status` | Show learning status |
| `/rp learn flush` | Force a summary cycle now |
| `/rp reload` | Reload and auto-sync config keys |
| `/rp help` | Show help |

### 🏆 Advancement Simulation

| Command | Description |
| --- | --- |
| `/rp advancement status` | Show simulator status |
| `/rp advancement trigger` | Trigger one broadcast manually |
| `/rp adv <status\|trigger>` | Alias of `advancement` |

### ⚙️ Advanced Config IO

| Command | Description |
| --- | --- |
| `/rp get <path>` | Read config value |
| `/rp set <path> <value>` | Write config value |
| `/rp list [section]` | List sections/keys |
| `/rp config get <path>` | Same feature (`config` subcommand) |
| `/rp config set <path> <value>` | Same feature |
| `/rp config list [section]` | Same feature |

## 🔐 Permissions

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

## 🗂️ Data Files

- `learned-raw.yml`
- `learned-phrases-chat.yml`
- `learned-phrases-qa.yml`
- `advancement-progress.yml`

## ⚠️ Notes

- Fake players are not physical entities in the world.
- Keep `messages.*-zh` / `messages.*-en` pools well-curated to avoid mixed-style output.
- TPA requests to fake players are intercepted and denied (`messages.prevent-tpa`).
- Common TPA/private-message commands support fake-player name completion.

## 🛠️ Build

```bash
mvn -DskipTests clean package
```

Output: `target/RealmPulse-<version>.jar`

## 📄 License

MIT (see `LICENSE`)
