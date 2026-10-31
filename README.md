## English
# VoxLink

Play with your friends, or find new ones in the lobby :D

## Links

| | |
|---|---|
| Official Website | https://link.voxdev.top/ |
| Standalone App | https://github.com/AUGUHDAR/VoxLink-app |
| Standalone App (Gitee mirror) | https://gitee.com/AUGUHDAR/VoxLink-app |
| GitHub | https://github.com/AUGUHDAR/VoxLink |
| Gitee | https://gitee.com/AUGUHDAR/VoxLink |
| MC Wiki (mcmod.cn) | https://www.mcmod.cn/class/28295.html |
| Modrinth | https://modrinth.com/mod/voxlink |
| CurseForge | https://www.curseforge.com/minecraft/mc-mods/voxlink |
| QQ Group | https://qm.qq.com/cgi-bin/qm/qr?k=OEkk9L8m8jdFMkbGDhKZs0u2U0azLAPo&jump_from=webapi |
| Discord | https://discord.gg/XaAFxvzPDS |

---

## How to play

Open your world, create a room, get a **6-digit code**, send it to your friend, they type it in, done. No port forwarding, no router stuff, no server, you don't even need to know what a public IP is. Install mod, make room, send code, that's it.

Starting from **1.0.4**, VoxLink works with **Terracotta** — an external P2P networking tool. It's optional and not required; you're advised to download and enable it from the settings screen. It can interop with other tools that support Terracotta, and if you enter a native VoxLink room, VoxLink's own punching runs in parallel with Terracotta.

Terracotta is developed by [burningtnt](https://github.com/burningtnt/Terracotta) under AGPL-3.0 — huge thanks! The binary is downloaded from its official releases and SHA-256 verified. Note: due to technical limits (sandboxing), Terracotta can't run on Android devices for now — we may look into ways around it later (and due to the VM limitation, it can't read the launcher's own Terracotta either).

> Out of Beta as of 1.2.1. Sometimes connects in seconds, sometimes just... doesn't. Connection success depends heavily on both sides' network: same ISP home broadband usually works fine, campus WiFi, office network, phone hotspot not so much. Both sides on symmetric NAT? Lower success rate. If it fails, both restart the game to refresh NAT ports. We're improving the punch strategies every update. You can also enable Terracotta in the mod to boost success rate.


## Fallback & Relay

**Available in VoxLink 1.0.0+**

When direct punching fails, VoxLink doesn't give up. Three tricks up its sleeve:

- **Reverse punch** — if one side's NAT is being annoying for forward punching, the other side fires back a reverse punch to sneak around it.
- **Peer relay** — if both of you just can't connect directly (like both on symmetric NAT), VoxLink picks a player with suitable network conditions from whoever's online in the public rooms right now and uses them as a relay node. Traffic flows through their client, **never through the server**. The relay player won't feel a thing.
- **TURN relay** — added in **1.1.3**. After 20 seconds of punching, a "use relay" button shows up and a public relay server carries the traffic. From **1.2.0** the switch to relay is fully automatic, no button needed. P2P keeps punching in the background for 10 minutes afterwards and switches back smoothly if it succeeds. Relay servers are a limited resource — if you've got a spare public server, donations welcome~

Relay can be toggled off anytime in settings. Turn it off and you won't relay for others, but you also can't use others as relay. Fair enough right?

Big thanks to **XIOPNM**, who joined **300+ remote multiplayer tests** out of **700+ total**.

## Grab the host's mods

Added in **1.1.2**. When joining a room, VoxLink fetches the host's mod list and offers to download the ones you're missing — you'll need to restart the game manually afterwards. Version lookups went through the Modrinth API up to 1.1.9; **1.2.0+** adds CurseForge.

Only dependency completeness is checked — local vs host mod version differences are detected, but mod-compatibility conflicts and crash risks are not. If you're joining a stranger's room full of forced client-side mods, consider joining with a clean client (just VoxLink + Fabric API) to avoid crashes. Can be turned off in the simple settings page.

## Rooms

Rooms have no player limit, offline players work too. Once the lobby is open, room names and custom tags get AI checked before being shown. Latency tested around **15-20ms** between Guangdong and Jiangxi, depends on your network.

You can set your room to public when creating it, shows up in the lobby. Lobby **doesn't leak your IP** or anything, still all P2P. Got tags to help you find the right room. Both the web panel and the in-game client can browse the room list — copy the room code from the web, or join directly from the client.

## Compatibility

Works with Simple Voice Chat (good to go!), ViaFabric (and the whole ViaVersion family), and Floodgate. It also works with the Chinese mod **古人穿越**.

Now supports **Forge, NeoForge, and Fabric 1.20+** — every version from 1.20 up, with more to come. The mod itself is multilingual — pick yours: English, Simplified Chinese, Traditional Chinese, Japanese, Korean, French, German, Spanish, Russian, Portuguese (Brasil), Arabic, and Classical Chinese (Wenyanwen).

## Standalone App

There's also a separate VoxLink app that works across **any version** — no mod installation needed. It even tunnels other games, and runs on Linux, Windows and macOS. Grab it from the links above. (Developers: there's a CLI build of the app too, welcome to use it.)

## Found a bug?

Go to `https://github.com/AUGUHDAR/VoxLink/issues`, bring your logs, both host and joiner:

```
.minecraft/versions/<your_version>/logs/latest.log
```

**Starting from 1.1.0**, logs are automatically uploaded to help us improve punch success rates. You can disable this in settings. VoxLink never uploads or exposes your private info. You can also report or suggest things in-game — the feedback screen arrived in **1.1.7**.

Also tell us your network setup — home broadband? Campus WiFi? Hotspot? Without logs I'm just guessing what's going on on your end.

## How it works

**UDP hole punching + STUN** for NAT detection — since **1.0.9**, VoxLink probes the network environment and NAT type before punching and picks the best strategy. Punch strategy inspired by EasyTier, custom **reliable UDP transport** for MC traffic after connection. Signaling server only swaps addresses at handshake, game data is 100% P2P never touches the server. Pure Java, no native deps, runs on Windows / Linux / macOS / Android. Some code generated with AI help.

Website: `https://link.voxdev.top/`

## Server address migration

> **Note: starting November 11, 2026, versions below 1.2.1 will no longer be able to connect.** Open voxlink.json in your config folder and change the serverUrl field from `https://p2p.wuhui.icu` to `https://link.voxdev.top`, or upgrade to **1.2.1+** to keep playing. 1.2.1+ is out of Beta.

---

## 中文

# VoxLink

与好友快速联机，或在大厅寻找志同道合的伙伴 :D

## 相关链接

| | |
|---|---|
| 官网 | https://link.voxdev.top/ |
| 独立应用端 | https://github.com/AUGUHDAR/VoxLink-app |
| 独立应用端（Gitee 镜像） | https://gitee.com/AUGUHDAR/VoxLink-app |
| GitHub | https://github.com/AUGUHDAR/VoxLink |
| Gitee | https://gitee.com/AUGUHDAR/VoxLink |
| MC百科 | https://www.mcmod.cn/class/28295.html |
| Modrinth | https://modrinth.com/mod/voxlink |
| CurseForge | https://www.curseforge.com/minecraft/mc-mods/voxlink |
| QQ群 | https://qm.qq.com/cgi-bin/qm/qr?k=OEkk9L8m8jdFMkbGDhKZs0u2U0azLAPo&jump_from=webapi |
| Discord | https://discord.gg/XaAFxvzPDS |

---

## 如何游玩

打开你的存档，创建房间，获得一个 **6位房间码**，发给好友，好友输入即可。无需端口映射，无需改路由器，无需服务器，你甚至不需要知道公网IP是什么。装模组，建房间，发房间码，就这么简单。

在 **1.0.4** 及以上版本，VoxLink 兼容了 **Terracotta（陶瓦）**（非依赖，非必装）。建议在设置界面下载并启用陶瓦，可以与其他支持陶瓦的工具进行互通；如果进入的是 VoxLink 的原生房间，VoxLink 的打洞方案会和陶瓦并行运行。

陶瓦由 [burningtnt](https://github.com/burningtnt/Terracotta) 开发，遵循 AGPL-3.0 协议，特此致谢！其二进制仅从官方 release 下载并做 SHA-256 校验。注意：陶瓦因为技术限制（沙箱），目前暂时无法在安卓设备上运行，后续可能会寻找一些方法（因为虚拟机限制，也无法直接读取启动器的陶瓦）。

> 1.2.1 起已结束 Beta。有时几秒就能连上，有时就是连不上。连接成功率与双方网络环境密切相关：同运营商家用宽带通常没问题，校园网、企业网、手机热点成功率较低。双方都是对称 NAT？成功率会更低。连接失败时建议双方重启游戏刷新 NAT 端口。我们每个版本都在优化打洞策略。你也可以在模组内启用陶瓦来提升成功率。

## 后备与中继

**VoxLink 1.0.0+ 可用**

当直连打洞失败时，VoxLink 不会放弃。还有三招：

- **逆向打洞** — 如果一方的 NAT 不利于正向打洞，另一方向反向发起连接请求绕过去。
- **玩家中继** — 如果双方就是直连不了（比如都是对称 NAT），VoxLink 会从当前在线的公开房间玩家中找一个网络条件合适的玩家做中继节点。流量经过对方客户端转发，**绝不经过服务器**。中继玩家本身几乎无感知。
- **TURN 中继** — **1.1.3** 加入。打洞 20 秒后会出现"使用中继"按钮，点击后由拥有公网的服务器中继。**1.2.0 起**全自动切换中继，无需手动点击。使用中继后 P2P 打洞会在后台继续运行 10 分钟，成功则平滑切回。服务器资源有限 — 有闲置公网服务器的话，欢迎捐赠~

中继可以在设置中随时关闭。关了就不为别人中继，但也用不了别人的中继。挺公平的对吧。

感谢 XIOPNM 在 700+ 次测试中，参与异地测试 300+ 次。

## 获取房主模组

**1.1.2** 加入。加入房间时，VoxLink 会拉取房主的模组清单，可选下载缺失的模组 — 下载后需手动重启游戏。1.1.9 及以前的版本检测与下载均使用 Modrinth API，**1.2.0+ 接入 CurseForge**。

本功能仅校验待下载模组的前置依赖完整性，可识别本地模组与房主模组的版本差异，但无法检测模组间的兼容性冲突及崩溃风险。若要加入装有客户端强制模组的陌生人房间，建议使用仅安装 VoxLink、Fabric API 的纯净客户端进入，避免游戏异常崩溃。可在"简单配置"页面关闭此功能。

## 房间

房间没有人数上限，离线玩家也能用。大厅开放时，房间名和自定义标签会经 AI 审核后才展示。广东到江西延迟约 **15-20ms**（仅供参考，具体取决于双方网络）。

创建房间时可以选择公开，会出现在大厅列表中。大厅 **不暴露玩家真实 IP**，全程 P2P 直连。支持标签分类方便查找。网页端和客户端均可显示房间列表，网页端可复制房间码加入，客户端可直接加入。

## 兼容性

兼容 Simple Voice Chat（直接可用！）、ViaFabric（及 ViaVersion 全家桶）、Floodgate，也兼容了古人穿越。

现已支持 **Forge、NeoForge 和 Fabric 1.20+** — 1.20 以上所有版本，后续会兼容更多版本。模组本身是多语言的，选你的语言：English、简体中文、繁體中文、日本語、한국어、Français、Deutsch、Español、Русский、Português (Brasil)、العربية、文言文。

## 独立应用端

另有独立的 VoxLink 应用端，**无视版本**直接联机，甚至可以给其他游戏联机。支持 Linux、Windows、macOS，下载请看上方链接。（如果你是开发者，VoxLink 还提供了 CLI 版本的应用端，欢迎使用。）

## 遇到 BUG 怎么办

去 `https://github.com/AUGUHDAR/VoxLink/issues`，带上双方的日志：

```
.minecraft/versions/你的版本名/logs/latest.log
```

**1.1.0+** 版本会自动将日志上传至服务器，可在设置中关闭，开启后可为 VoxLink 提供日志参考，提高后期的打洞成功率。VoxLink 不会上传或暴露你的隐私信息。也可以在游戏内直接反馈 — **1.1.7** 起增加了游戏内反馈功能。

也请说明你们的网络环境 — 家用宽带？校园网？手机热点？没有日志我只能猜你们那边发生了什么。

## 技术原理

**1.0.9+** 会在打洞前探测网络环境以及 NAT 类型，采用最优的打洞方式（**UDP 打洞 + STUN**），打洞策略参考 EasyTier，连接建立后使用自研 **可靠 UDP 传输** 承载 Minecraft 流量。信令服务器仅在握手阶段交换地址，游戏数据 100% P2P 直连，不经过服务器。纯 Java 实现，无 native 依赖，支持 Windows / Linux / macOS / Android。部分代码由 AI 辅助生成。

官网：`https://link.voxdev.top/`

## 服务器地址迁移

> **注意：自 2026 年 11 月 11 日起，低于 1.2.1 的版本将无法直接使用。** 请打开 config 文件夹内的 voxlink.json，将 serverUrl 字段从 `https://p2p.wuhui.icu` 改为 `https://link.voxdev.top`；或升级至 1.2.1 及以上版本，方可继续使用。1.2.1+ 已结束 Beta。
