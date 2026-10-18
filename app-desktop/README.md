## English

# VoxLink App

The standalone desktop client of VoxLink. **No mod required** — it exists for the Minecraft versions the mod doesn't cover, and for any other game that speaks TCP/UDP over a local port. Play with mod users: rooms are interoperable in both directions.

## Links

| | |
|---|---|
| Official Website | https://link.voxdev.top/ |
| VoxLink mod (GitHub) | https://github.com/AUGUHDAR/VoxLink |
| MC Wiki (mcmod.cn) | https://www.mcmod.cn/class/28295.html |
| Modrinth | https://modrinth.com/mod/voxlink |
| CurseForge | https://www.curseforge.com/minecraft/mc-mods/voxlink |
| QQ Group | https://qm.qq.com/cgi-bin/qm/qr?k=OEkk9L8m8jdFMkbGDhKZs0u2U0azLAPo&jump_from=webapi |
| Discord | https://discord.gg/XaAFxvzPDS |

Downloads live in [Releases](https://github.com/AUGUHDAR/VoxLink-app/releases) (and the Gitee mirror).

---

## Two builds

| | **UI build** | **CLI build** |
|---|---|---|
| What it is | Windowed app, click and play | Same engine, no window, made to be driven by a launcher |
| For whom | Players | Third-party launchers (PCL, KAMUCL, HMCL…), scripts, headless boxes |
| Size | full | about half of the UI build |
| Platforms | Windows / Linux / macOS | Windows / Linux / macOS |

Both are single files — no installer, nothing in your registry, no runtime to install. Same room format, same features; the CLI build is not a stripped-down client.

## How to play

**Hosting**: start Minecraft (LAN world or your own server), open the app, create a room, get a **6-digit code**, send it to your friends. No port forwarding, no router config, no server rental, you don't need to know what a public IP is.

**Joining**: type in the code you got. Once the tunnel is up the app hands you an address — `127.0.0.1:<port>` or `hostIp:port` — and you add it in Minecraft's *Multiplayer → Add Server*. That's it.

Rooms created by the mod and rooms created by the app join each other's lobbies, and since **1.2.0** rooms can also be mirrored onto **Happy Link**, so Bedrock-side and Happy Link players can drop into your vanilla room.

## Fallback & relay

When direct punching fails, VoxLink doesn't just give up — and since **1.2.0** it handles this on its own, no buttons to press:

- **TURN relay** — a standard RFC 5766 relay node run by VoxLink carries the traffic.
- **Peer relay** — a suitable Cone-NAT player currently online in the lobby forwards your packets. This traffic **never touches the server**, and the relay player barely notices.
- **Seamless lane change** — a relay that came up but is quietly dead gets detected within seconds and swapped, without dropping the bytes you already sent. After that it goes back to punching.

The app keeps trying to upgrade you to a direct P2P path in the background; when it succeeds, the switch is smooth and you keep playing. There is one switch in settings — **peer relay**: off means you won't relay for others and you can't use anyone else's either (TURN relay is unaffected). Fair enough right?

## Rooms

Room names are AI-checked before going public. Public rooms show up in the lobby with tags, game version and host info, and the lobby **never exposes anyone's real IP**. Your app rooms appear in the mod's lobby too.

## Compatibility

The app speaks the exact same wire protocol as the mod — same hole punching, same reliable UDP transport, byte for byte — so a 1.20.1 mod host and a 1.21.11 app host can play together. Mod sync is built in: the host publishes its mod list, guests get a diff with real names, real icons and direct downloads before they join.

Multilingual, same 13 languages as the mod: English, Simplified Chinese, Traditional Chinese, Japanese, Korean, French, German, Spanish, Russian, Portuguese (Brasil), Arabic, Classical Chinese (Wenyanwen), Latin.

## For launcher developers

The CLI build is meant to be embedded: every command answers with a single JSON line, long-running work (creating/joining a room) streams NDJSON events, and `voxlink-cli describe --json` hands you the machine-readable command table, protocol number and capability list so your launcher can check itself before launching. See [docs/cli-integration.md](docs/cli-integration.md).

## Found a bug?

Use the ticket system inside the app — it attaches your connection log automatically, and you can keep replying and retracting your own messages. Or open an issue at `https://github.com/AUGUHDAR/VoxLink-app/issues`.

Please say what your network looks like — home broadband? Campus WiFi? Phone hotspot? — and bring both sides' logs, host and joiner. Without logs I'm just guessing what happened on your end.

## How it works

**UDP hole punching + STUN** for NAT detection and punching, a custom **reliable UDP transport** carrying game traffic once the path is up, **standard TURN (RFC 5766)** and **peer relay** as fallbacks. The signaling server only swaps addresses during the handshake; game data stays P2P. Written in Go with a webview UI, CLI build has no GUI dependency at all. Some code generated with AI help.

Website: `https://link.voxdev.top/`

---

## 中文

# VoxLink 应用端

VoxLink 的独立桌面客户端，**不需要装模组**。它为模组覆盖不到的 Minecraft 版本准备，也给别的游戏用——只要它在本地开一个端口。和模组房双向互通，你建房模组玩家能进，模组建房你也能进。

## 相关链接

| | |
|---|---|
| 官网 | https://link.voxdev.top/ |
| VoxLink 模组（GitHub） | https://github.com/AUGUHDAR/VoxLink |
| MC百科 | https://www.mcmod.cn/class/28295.html |
| Modrinth | https://modrinth.com/mod/voxlink |
| CurseForge | https://www.curseforge.com/minecraft/mc-mods/voxlink |
| QQ群 | https://qm.qq.com/cgi-bin/qm/qr?k=OEkk9L8m8jdFMkbGDhKZs0u2U0azLAPo&jump_from=webapi |
| Discord | https://discord.gg/XaAFxvzPDS |

下载在 [Releases](https://github.com/AUGUHDAR/VoxLink-app/releases)（Gitee 有同步镜像）。

---

## 两个版本

| | **UI 版** | **CLI 版** |
|---|---|---|
| 是什么 | 有窗口，点开就用 | 同一套引擎，无窗口，给启动器调用 |
| 给谁用 | 玩家 | 第三方启动器（PCL、KAMUCL、HMCL…）、脚本、无界面机器 |
| 体积 | 完整 | 约为 UI 版的一半 |
| 平台 | Windows / Linux / macOS | Windows / Linux / macOS |

都是单文件——不用安装、不写注册表、不需要额外运行时。**联机引擎、房间格式、协议能力两边是同一份代码**；
差别只在"要动窗口"的那几件事：浏览目录的原生对话框、模组同步的选择与下载进度、逐屏语言切换，
CLI 没有对应命令（清单与边界见 `docs/cli-integration.md`）。

## 如何游玩

**建房**：开好 MC（局域网存档或你自己的服务端），打开应用端建房，拿到一个 **6 位房间码**，发给好友。无需端口映射，无需改路由器，无需服务器，你甚至不需要知道公网 IP 是什么。

**加房**：输入好友给的房号。隧道打通后应用端会给你一个地址——`127.0.0.1:<端口>` 或 `hostIp:端口`——在 MC 的「多人游戏 → 添加服务器」里填进去就完事。

应用端建的房会出现在模组大厅里，模组建的房也能在应用端大厅看到。从 **1.2.0** 起房间还能镜像到 **Happy Link**，那边（含基岩版侧）的玩家可以直接进你的原版房。

## 后备与中继

直连打洞失败时 VoxLink 不会放弃，而且从 **1.2.0** 起这件事它自己判断、自己处理，界面上没有按钮要你点：

- **TURN 中继** — 由 VoxLink 自建的标准 RFC 5766 中继节点承载流量。
- **玩家中继** — 从当前在线的公开大厅玩家里挑一个网络条件合适的 Cone-NAT 玩家做转发节点。这条流量 **绝不经过服务器**，中继的玩家本身几乎无感知。
- **无缝换道** — 中继建起来了但其实已经死了的，几秒内会被发现并立刻换到另一条道，已经发出去的数据不丢。两条道都用不了就回头继续打洞。

连上之后应用端还会在后台继续尝试升级成直连，成功了就平滑切换，你这边不用重连。设置里有一个"玩家中继"开关，管两头：关了就不为别人中继、也用不了别人的中继，挺公平（TURN 兜底不受它影响，仍然自动）。

## 房间

房间名在大厅公开展示前会经过 AI 审核。公开房间带标签、游戏版本与房主信息进大厅，大厅 **不暴露任何人的真实 IP**，全程 P2P。

## 兼容性

应用端和模组说的是同一套线上协议——打洞、可靠 UDP 传输逐字节一致——所以 1.20.1 的模组房主和 1.21.11 的应用端房主可以互相进房。模组同步内置：房主发布清单，房客进房前拿到差异对比，带真名、真图标和直链下载。

多语言，与模组同源 13 种：English、简体中文、繁體中文、日本語、한국어、Français、Deutsch、Español、Русский、Português (Brasil)、العربية、文言文、拉丁文。

## 给启动器开发者

CLI 版就是为被集成而生的：每条命令回答一行 JSON，建房/加房这类长任务用 NDJSON 逐行推事件，`voxlink-cli describe --json` 直接把机器可读的命令表、协议号和能力清单交给你，启动器可以在拉起之前自检。详见 [docs/cli-integration.md](docs/cli-integration.md)。

## 遇到 BUG 怎么办

优先用应用内的工单系统——它会自动带上你的连接日志，你可以继续追问、也能撤回自己发的最后一条。或者去 `https://github.com/AUGUHDAR/VoxLink-app/issues` 开 issue。

请说明你们的网络环境——家用宽带？校园网？手机热点？——并带上双方的日志。没有日志我只能猜你们那边发生了什么。

## 技术原理

**UDP 打洞 + STUN** 做 NAT 探测与打洞，链路建立后用自研 **可靠 UDP 传输** 承载游戏流量，失败时由 **标准 TURN（RFC 5766）** 与 **玩家中继** 兜底。信令服务器仅在握手阶段交换地址，游戏数据不经过服务器。Go 实现，界面用 webview，CLI 版零 GUI 依赖。部分代码由 AI 辅助生成。

官网：`https://link.voxdev.top/`
