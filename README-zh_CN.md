# RealmPulse 中文说明

面向服主与管理团队的运营型假人氛围插件说明文档。

## 插件定位

RealmPulse 通过数据包模拟“在线玩家氛围”，核心目标是提升服务器聊天活跃感，而不是生成可交互实体 NPC。

当前版本重点能力：

- 假人仅在 Tab/聊天层展示（非实体）。
- 假人等级为加权随机：等级数字越大，出现概率越小。
- 支持中英语言倾向、随机名称、随机延迟、上下线状态切换。
- 聊天事件模拟：欢迎、闲聊、提及回复、英文对话轮次。
- AI 双通道：问答（QA）与总结（Summary）独立配置。
- 学习系统：从真实玩家聊天提炼短句并持久化。
- 运营场景：`profile` 一键档位、`scene` 场景切换、`scene auto` 定时调度。
- 成就广播模拟器：可自动触发，也可手动触发测试。
- 在线配置管理：支持游戏内 `get/set/list`。
- 配置自动补齐：启动和 `/rp reload` 时自动补齐新配置项，不覆盖旧值。

## 运行环境

- Java 21+
- Spigot/Paper 1.20+
- Vault（必需）
- Vault Chat 提供者（运行时必需）
- ProtocolLib（运行时必需）
- 可选：LuckPerms（用于读取默认组前缀）

## 安装与快速开始

1. 将插件 JAR 放入 `plugins/`。
2. 启动服务器生成配置目录。
3. 编辑 `plugins/RealmPulse/config.yml`。
4. 建议用命令写入 API Key：

```text
/rp qakey <your_key>
/rp summarykey <your_key>
```

5. 重载配置：

```text
/rp reload
```

## 配置模板

仓库内提供三份模板：

- `config.yml`：完整注释版
- `config.min.yml`：低成本/小服基线
- `config.pro.yml`：较高活跃度基线

## 命令总览

主命令：`/realmpulse`，别名：`/rp`

### 常用

- `/rp help`
- `/rp reload`

### 学习系统

- `/rp learn status`
- `/rp learn flush`

### 假人数量管理

- `/rp bots`
- `/rp addbot <count>`
- `/rp removebot <count>`
- `/rp delbot <count>`
- `/rp setbot <count>`

说明：命令层对假人总数有上限保护（500）。

### AI 控制

- `/rp qamodel <model>`
- `/rp summarymodel <model>`
- `/rp qaon <on|off>`
- `/rp summaryon <on|off>`
- `/rp qaapi <url>`
- `/rp summaryapi <url>`
- `/rp qakey <key>`
- `/rp summarykey <key>`

### 运营档位与场景

- `/rp profile <lowcost|balanced|pro>`
- `/rp scene <peak|quiet|promo|auto>`
- `/rp scene auto <on|off|status>`

### 成就广播模拟

- `/rp advancement <status|trigger>`
- `/rp adv <status|trigger>`

### 高级配置读写

- `/rp get <path>`
- `/rp set <path> <value>`
- `/rp list [module]`
- `/rp config get <path>`
- `/rp config set <path> <value>`
- `/rp config list [module]`

## 权限节点

与 `plugin.yml` 保持一致：

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

## 数据文件

插件运行后会在数据目录维护以下文件：

- `learned-raw.yml`
- `learned-phrases-chat.yml`
- `learned-phrases-qa.yml`
- `advancement-progress.yml`

## 重要行为说明

- 当前假人不是实体，不会在世界中像真实玩家那样移动/交互。
- 对假人的 TPA 请求会被拦截并返回拒绝提示（`messages.prevent-tpa`）。
- 常见私聊/TPA 命令支持假人名补全，提升“在线感”。

## 构建

```bash
mvn -DskipTests clean package
```

输出：`target/RealmPulse-<version>.jar`

## 许可证

MIT，详见 `LICENSE`。
