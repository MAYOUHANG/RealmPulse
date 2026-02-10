# RealmPulse 中文运营手册

> 让服务器“看起来一直有人玩”的轻量氛围插件。
> 非实体假人 + AI 问答/学习 + 场景化运营调度。

[🌍 English README](README.md)

![Java 21](https://img.shields.io/badge/Java-21-007396?logo=java&logoColor=white)
![Spigot/Paper 1.20+](https://img.shields.io/badge/Spigot%2FPaper-1.20%2B-ED8106)
![ProtocolLib 5+](https://img.shields.io/badge/ProtocolLib-5%2B-5C2D91)
![Vault Required](https://img.shields.io/badge/Vault-required-2ea44f)
![Version 2.5](https://img.shields.io/badge/Version-2.5-blue)

## ✨ 插件能力概览

- 👻 **非实体假人（Packet 模拟）**：只在 Tab/聊天层出现。
- 🎚️ **等级加权随机**：等级越高越稀有，分布更接近真实玩家群体。
- 🌐 **中英双语倾向**：支持英文 Bot 比例与语言模板池。
- 💬 **聊天氛围系统**：欢迎语、闲聊、@提及回复、英文对话轮次。
- 🧠 **AI 双通道设计**：
  - QA：即时问答
  - Summary：语料提炼
- 📚 **学习系统**：原始语料入库、过滤、去重、提炼并持久化。
- ⚙️ **运营档位**：`profile` 一键套用低成本/均衡/高质量参数。
- 🕒 **自动场景调度**：`scene auto` 基于时段自动切换策略。
- 🏆 **成就广播模拟**：自动/手动触发、冷却与频控。
- 🔌 **上下线模拟** 与 ☠️ **死亡广播模拟**。
- 🧩 **配置自动补齐**：更新版本后，旧配置不删，自动补充新增项。

## 📦 运行环境

- Java 21+
- Spigot/Paper 1.20+
- Vault（必需）
- Vault Chat Provider（运行时检查必需）
- ProtocolLib（运行时检查必需）
- 可选：LuckPerms（读取 default 组前缀）

## 🚀 快速上手

1. 把插件 JAR 放到 `plugins/`。
2. 启动服务器生成默认配置。
3. 按需调整 `plugins/RealmPulse/config.yml`。
4. 建议在游戏内写入密钥（避免明文复制流转）：

```text
/rp qakey <your_key>
/rp summarykey <your_key>
```

5. 重载配置：

```text
/rp reload
```

## 🧾 配置模板

仓库自带三套模板：

- `config.yml`：完整注释引导版（推荐）
- `config.min.yml`：低成本/小服
- `config.pro.yml`：高活跃/高质量

## 🕹️ 常用命令

主命令：`/realmpulse`，别名：`/rp`

### 👥 假人管理

| 指令 | 说明 |
| --- | --- |
| `/rp bots` | 查看当前假人数量 |
| `/rp addbot <count>` | 增加假人 |
| `/rp removebot <count>` | 减少假人 |
| `/rp delbot <count>` | `removebot` 别名 |
| `/rp setbot <count>` | 直接设置总数 |

> 命令层上限保护：`500`。

### 🤖 AI 配置

| 指令 | 说明 |
| --- | --- |
| `/rp qamodel <model>` | 设置 QA 模型 |
| `/rp summarymodel <model>` | 设置 Summary 模型 |
| `/rp qaon <on\|off>` | QA 开关 |
| `/rp summaryon <on\|off>` | Summary 开关 |
| `/rp qaapi <url>` | QA 接口地址 |
| `/rp summaryapi <url>` | Summary 接口地址 |
| `/rp qakey <key>` | QA Key |
| `/rp summarykey <key>` | Summary Key |

### 🎛️ 运营预设与场景

| 指令 | 说明 |
| --- | --- |
| `/rp profile lowcost` | 低成本档 |
| `/rp profile balanced` | 均衡档 |
| `/rp profile pro` | 高质量档 |
| `/rp scene peak` | 高峰活跃场景 |
| `/rp scene quiet` | 低峰省算力场景 |
| `/rp scene promo` | 活动宣传场景 |
| `/rp scene auto on/off/status` | 自动场景控制 |

### 📚 学习与维护

| 指令 | 说明 |
| --- | --- |
| `/rp learn status` | 查看学习状态 |
| `/rp learn flush` | 立即触发总结 |
| `/rp reload` | 重载并自动补齐配置 |
| `/rp help` | 查看帮助 |

### 🏆 成就广播模拟

| 指令 | 说明 |
| --- | --- |
| `/rp advancement status` | 查看状态 |
| `/rp advancement trigger` | 手动触发一次 |
| `/rp adv <status\|trigger>` | `advancement` 别名 |

### ⚙️ 高级配置读写

| 指令 | 说明 |
| --- | --- |
| `/rp get <path>` | 读取配置 |
| `/rp set <path> <value>` | 写入配置 |
| `/rp list [module]` | 列出模块或键 |
| `/rp config get <path>` | 同功能（config 子命令） |
| `/rp config set <path> <value>` | 同功能 |
| `/rp config list [module]` | 同功能 |

## 🔐 权限节点

与 `plugin.yml` 对齐：

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

## 🗂️ 运行数据文件

插件目录会维护：

- `learned-raw.yml`
- `learned-phrases-chat.yml`
- `learned-phrases-qa.yml`
- `advancement-progress.yml`

## ⚠️ 关键行为说明

- 当前假人不是实体，不能像 NPC 一样走路、受击、交互。
- 对假人发起 TPA 会被拦截并拒绝（可改 `messages.prevent-tpa` 文案）。
- 常见私聊/TPA 指令会出现假人名补全，增强在线体验。

## 🛠️ 构建

```bash
mvn -DskipTests clean package
```

输出：`target/RealmPulse-<version>.jar`

## 📄 许可证

MIT（见 `LICENSE`）
