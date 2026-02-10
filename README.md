# RealmPulse（智能氛围组）

> 一个轻量、可运营、可调参的 **Packet 假人氛围插件**：
> 用非实体 Bot 营造在线感，叠加 AI 问答/学习与场景调度。

[📘 中文运营手册](README-zh_CN.md)

![Java 21](https://img.shields.io/badge/Java-21-007396?logo=java&logoColor=white)
![Spigot/Paper 1.20+](https://img.shields.io/badge/Spigot%2FPaper-1.20%2B-ED8106)
![ProtocolLib 5+](https://img.shields.io/badge/ProtocolLib-5%2B-5C2D91)
![Vault Required](https://img.shields.io/badge/Vault-required-2ea44f)
![Version 2.5](https://img.shields.io/badge/Version-2.5-blue)

## ✨ 核心特性（Features）

- 👻 **Packet 假人**：只出现在 Tab/聊天层，不生成真实实体。
- 🎚️ **等级加权随机**：等级数字越大，出现概率越低（低等级更常见）。
- 🌐 **双语倾向**：支持中英 Bot 比例、语言匹配聊天模板。
- 🧠 **AI 双通道**：
  - QA：实时问答回复
  - Summary：语料提炼/学习
- 🧪 **学习系统**：聊天语料去重、过滤、总结并持久化。
- 🔄 **运营调度**：
  - `/rp profile <lowcost|balanced|pro>`
  - `/rp scene <peak|quiet|promo|auto>`
  - `/rp scene auto <on|off|status>`
- 🏆 **成就广播模拟**：支持自动触发与手动触发。
- ☠️ **死亡广播模拟** + 🔌 **上下线模拟**。
- 🛠️ **游戏内改参**：`get / set / list` 与 `config get / set / list`。
- 🧩 **配置自动补齐**：启动与 `/rp reload` 自动补全新键，保留你已有值。

## 📦 运行要求（Requirements）

- Java 21+
- Spigot/Paper 1.20+
- Vault（必需）
- Vault Chat Provider（运行时校验需要）
- ProtocolLib（运行时校验需要）
- 可选：LuckPerms（读取 default 组前缀）

## 🚀 快速开始（Getting Started）

1. 将插件 JAR 放入 `plugins/`。
2. 首次启动服务器，生成配置目录。
3. 选择并编辑配置：`plugins/RealmPulse/config.yml`。
4. 建议在游戏内设置 API Key：

```text
/rp qakey <your_key>
/rp summarykey <your_key>
```

5. 重载配置：

```text
/rp reload
```

## 🧾 配置模板（Repository Templates）

- `config.yml`：完整注释版（推荐）
- `config.min.yml`：低成本/小服基线
- `config.pro.yml`：高活跃/高质量基线

## 🕹️ 命令速查（Commands）

主命令：`/realmpulse`，别名：`/rp`

### 👥 Bot 管理

| Command | Description |
| --- | --- |
| `/rp bots` | 查看当前 Bot 数量 |
| `/rp addbot <count>` | 增加 Bot 数量 |
| `/rp removebot <count>` | 减少 Bot 数量 |
| `/rp delbot <count>` | `removebot` 别名 |
| `/rp setbot <count>` | 设置 Bot 总数 |

> 注：命令层对 Bot 总数上限保护为 `500`。

### 🤖 AI 管理

| Command | Description |
| --- | --- |
| `/rp qamodel <model>` | 设置问答模型 |
| `/rp summarymodel <model>` | 设置总结模型 |
| `/rp qaon <on\|off>` | 问答 AI 开关 |
| `/rp summaryon <on\|off>` | 总结 AI 开关 |
| `/rp qaapi <url>` | 问答 API 地址 |
| `/rp summaryapi <url>` | 总结 API 地址 |
| `/rp qakey <key>` | 问答 API Key |
| `/rp summarykey <key>` | 总结 API Key |

### 🎛️ 运营预设

| Command | Description |
| --- | --- |
| `/rp profile lowcost` | 低成本方案 |
| `/rp profile balanced` | 均衡方案 |
| `/rp profile pro` | 高质量方案 |
| `/rp scene peak` | 高峰活跃场景 |
| `/rp scene quiet` | 低峰省算力场景 |
| `/rp scene promo` | 活动宣传场景 |
| `/rp scene auto on/off/status` | 自动场景调度控制 |

### 📚 学习与运维

| Command | Description |
| --- | --- |
| `/rp learn status` | 学习状态 |
| `/rp learn flush` | 立即触发学习总结 |
| `/rp reload` | 重载并自动补齐配置 |
| `/rp help` | 帮助信息 |

### 🏆 成就模拟

| Command | Description |
| --- | --- |
| `/rp advancement status` | 查看成就模拟器状态 |
| `/rp advancement trigger` | 手动触发一次广播 |
| `/rp adv <status\|trigger>` | `advancement` 别名 |

### ⚙️ 高级配置读写

| Command | Description |
| --- | --- |
| `/rp get <path>` | 读取配置值 |
| `/rp set <path> <value>` | 写入配置值 |
| `/rp list [module]` | 列出模块/键 |
| `/rp config get <path>` | 同上（config 子命令） |
| `/rp config set <path> <value>` | 同上 |
| `/rp config list [module]` | 同上 |

## 🔐 权限节点（Permissions）

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

## 🗂️ 数据文件（Data Files）

插件运行后会维护：

- `learned-raw.yml`
- `learned-phrases-chat.yml`
- `learned-phrases-qa.yml`
- `advancement-progress.yml`

## ⚠️ 重要说明（Behavior Notes）

- 当前 Bot 不是实体，不会在世界中真实走路/交互。
- 玩家对 Bot 的 TPA 请求会被拦截并拒绝（`messages.prevent-tpa`）。
- 常见 TPA/私聊命令支持 Bot 名自动补全，提高“在线感”。

## 🛠️ 构建（Build）

```bash
mvn -DskipTests clean package
```

输出：`target/RealmPulse-<version>.jar`

## 📄 License

MIT（详见 `LICENSE`）

