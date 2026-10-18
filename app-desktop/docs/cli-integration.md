# VoxLink CLI 调用说明

> 面向启动器与脚本作者。CLI 版与 UI 版**编译同一份联机引擎**（`conn_engine.go`、`punch*.go`、
> `stun*.go`、`rudp*.go`、`relay.go`、`bridge.go`、`std_turn.go`、`auto_relay.go`、`session.go`
> 全部无构建标签，两个产物都编进去），打洞、TURN、中继、互通的行为逐语句一致。
> **功能面不是完全一致**：按 2026-10-12 逐方法核对，UI 侧被前端调用的方法 34 个、CLI 命令 25 条，
> UI 有而 CLI 没有 14 项（原生目录选择、工单附件选择框、逐屏语言切换、开浏览器，
> 以及**模组同步全套 10 个方法**），CLI 有而 UI 没有 3 项（`relay status` / `relay list` / `ticket viewed`）。
> 特别注意：**`room join` 在 CLI 下不会走"必装模组差异门控"**（UI 是前端在 JoinRoom 之前调
> `ModSyncGate`，JoinRoom 自身不含这道门）。启动器若要这条检查，目前只能自己比对房主清单。
> 目标是**尽量向后兼容**：命令与字段以本文与 `describe --json` 为准，我们会避免破坏性改动；
> 若确有需要调整，会在同一版本线上保留兼容路径，并在发版说明里标注。

## 基本形态

```
voxlink-cli <域> <动作> [参数]        # 一次性命令，stdout 输出一行 JSON 后退出
voxlink-cli <域> <动作> --events ndjson  # 长任务：先一行结果，随后逐行推送事件流
voxlink-cli describe --json          # 机器可读：命令表 + 协议号 + 能力清单 + 常量
voxlink-cli --version                # 版本与协议号
```

## 统一信封

除事件流外，所有输出都是一行 JSON：

```json
{"ok":true,"data":{…}}
{"ok":false,"error":"ROOM_NOT_FOUND","code":"ROOM_NOT_FOUND","message":"房号不存在或密码不对"}
```

`code` 是机器可读错误码（与 VoxLink 服务端同源），启动器按码分支即可，`message` 是按 `--lang` 渲染的人话，不要拿去匹配。

## 事件流

`--events ndjson` 时，每行一个事件对象：

```json
{"event":"state","phase":"p2p","status":"trying","ts":…}
{"event":"state","phase":"relay","status":"success","address":"127.0.0.1:51234","ts":…}
{"event":"log","level":"info","msg":"…","ts":…}
```

`phase` 取 `p2p` / `direct` / `relay`，`status` 取 `trying` / `success` / `failed`。拿到 `address` 就写进 MC 服务器地址。

## 典型集成流程

```
voxlink-cli room create --name "我的服" --port 25565 --events ndjson
voxlink-cli room join --code AB12CD --events ndjson
voxlink-cli room leave
```

## 让别人看出这是你的启动器（--client-tag）

`room create` 可以带一个来源标签，服务器后台与大厅的"标签"行会原样显示它——
模组房显示的是 `forge` / `neoforge` 这类加载器名，你的启动器房显示的就是你给的这个词：

```
voxlink-cli room create --name "我的服" --port 25565 --client-tag "星光启动器"
```

规则（`describe --json` 里也能查到）：

- 最长 **10 个字**，中文一个字算一个；超了直接报 `INVALID_PARAMS`，不会静默丢掉。
- 允许：字母、数字、下划线、连字符、中文、单个空格；大小写不敏感（存的是小写形式）。
- 引号、尖括号、控制字符会被剔掉，剔完什么都不剩则视为没传。
- 只想长期固定一个标签：`voxlink-cli settings save --client-tag pcl2`；
  建房时带 `--client-tag` 则以当次的为准，不影响持久值。
- 不带这个参数时，应用端房在后台显示为 "APP"（按客户端类型自动标的）。

日志、工单、中继开关等其余命令请用 `describe --json` 枚举——那一份是实时从程序里出来的，永远比文档准。

## 退出码（2026-10-11 逐条实测）

| 退出码 | 含义 | 例子 |
|---|---|---|
| `0` | `{"ok":true,…}` | 正常返回 |
| `1` | 业务错误：参数值不合法、当前状态做不了这件事、服务端拒绝 | `INVALID_PARAMS`、`NO_SESSION`、`TICKET_NOT_FOUND`、`TICKET_MSG_NOT_FOUND`、`ROOM_NOT_FOUND` |
| `2` | 用法错误：命令本身不存在或命令行解析失败 | `USAGE`（`voxlink-cli room nope`） |

**别把 `1` 和 `2` 合并处理**：`2` 说明你拼错了命令，升级 CLI 时会静默失效；
`1` 说明命令是对的、只是这次不该做。两种都要在启动器里显示不同的话术。

## 一次性调用 ≠ 能操作别人的会话（最容易踩的一条）

会话（房号、token、信令连接、打洞状态）**活在那个进程里**，进程之间没有 IPC。所以：

```bash
voxlink-cli room create --name 房名 --port 25565            # 打印结果后立刻退出
voxlink-cli room status                                     # → {"room":null,"state":"idle"}
voxlink-cli room update --name 新名                          # → NO_SESSION
```

上面那三条不会报错，但也不会生效——因为每条都开了一个新进程、各自没有会话。
**要持续操作一个房间，就必须在带 `--events ndjson` 的那个进程存活期间操作它**；
进程退出后房间不会立刻消失，而是靠服务端心跳 TTL（约 300 秒）自然过期，
所以别指望"再开一个进程去改名/退房"。

- 让进程活着：`--events ndjson`（结果行之后持续推事件）+ `--timeout 0`（不限时长），
  收 `SIGINT`/`SIGTERM` 会优雅离场并发 `/room/leave`。
- 只要一次性快照（查大厅、查房、查工单）：不带 `--events` 就够，退出即结束。

## 事件流字段口径（NDJSON）

| 事件 | 用途 | 要注意的字段 |
|---|---|---|
| `session:state` | 会话/房间状态变化 | `state`=`idle`/`creating`/`hosting`/`joining`/`in_room`；`room.*` 是服务端回包 |
| `conn:state` | 连接流程（打洞/中继） | **`address` 是本机桥接口**（`127.0.0.1:<随机端口>`），MC 客户端要连这个地址，不是房主公网地址；`phase`=`p2p`/`turn`/`prelay`/`direct`/`fed`，`status`=`trying`/`success`/`failed` |
| `net:log` | 逐行诊断日志 | `level`/`msg`；打洞失败原因、NAT 分级、中继决策都在这里 |
| `signal` | 原始信令（调试用） | `type`/`from`/`data` |

`room create`/`room join` 的**结果行**先出，事件随后；解析时按行 JSON 读，不要按"第一行是结果"写死偏移。

## 参数校验的边界（实测）

- `--client-tag`：≤10 个字（中文一字算一个），超了 `INVALID_PARAMS` 并告诉你当前几个字。
- `settings save --theme`：只接受 `light`/`dark`，其它值 `INVALID_PARAMS`（不会静默落盘）。
- `settings save --language`：只接受 `auto` 或 `settings get` 返回的 `languages` 里的代码。
- `serverUrl`：**不可改**，永远等于编译期内置的生产地址；`settings.json` 里存的历史自定义值读进来会被丢弃。
- `net stun`：返回**服务端看到的公网 IP**（不含 NAT 分级）。要看 NAT 分级，读驻留进程事件流里的
  `stun_probe_done nat=…`（或 `net stun` 之后的 `room create` 日志）。
- `net ports`：只列 **MC 的 java 进程**在监听的端口（按命令行特征识别），别的程序占着 25565 不会出现在这里。

