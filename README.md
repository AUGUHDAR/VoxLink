## English
# VoxLink

Play with your friends, or find new ones in the lobby :D

---

## How to play

Open your world, create a room, get a **6-digit code**, send it to your friend, they type it in, done. No port forwarding, no router stuff, no server, you don't even need to know what a public IP is. Install mod, make room, send code, that's it.

Starting from **1.0.4**, VoxLink integrates **Terracotta** — an external P2P networking tool. It is optional and can be downloaded directly within the mod. Enabling Terracotta significantly improves connection success rate, and you can also use it to play multiplayer with other Terracotta users.

> Still in Beta. Sometimes connects in seconds, sometimes just... doesn't. Connection success depends heavily on both sides' network: same ISP home broadband usually works fine, campus WiFi, office network, phone hotspot not so much. Both sides on symmetric NAT? Lower success rate. If it fails, both restart the game to refresh NAT ports. We're improving the punch strategies every update. You can also enable Terracotta in the mod to boost success rate.

Huge thanks to XIOPNM, he joined over 200 remote tests himself. We ran more than 300 total.

## Fallback & Relay

**Available in VoxLink 1.0.0+**

When direct punching fails, VoxLink doesn't give up. Two tricks up its sleeve:

- **Reverse punch** — if one side's NAT is being annoying for forward punching, the other side fires back a reverse punch to sneak around it.
- **Peer relay** — if both of you just can't connect directly (like both on symmetric NAT), VoxLink grabs a suitable Cone-NAT player from whoever's online in the public lobby right now and uses them as a relay node. Traffic flows through their client, **never through the server**. The relay player won't feel a thing.

Relay can be toggled off anytime in settings. Turn it off and you won't relay for others, but you also can't use others as relay. Fair enough right?

## Rooms

Rooms have no player limit, offline players work too. Room names get AI checked before going public. Latency tested around **15-20ms** between Guangdong and Jiangxi, depends on your network.

You can set your room to public when creating it, shows up in the lobby. Lobby **doesn't leak your IP** or anything, still all P2P. Got tags to help you find the right room.

## Compatibility

Works with Simple Voice Chat (good to go!), and we've also got support for ViaVersion, ViaFabric, and Floodgate.

Now supports **Fabric 1.20+** — all versions from 1.20 to 26.2. The mod itself is multilingual — pick yours: English, Simplified Chinese, Traditional Chinese, Japanese, Korean, French, German, Spanish, Russian, Portuguese (Brasil), Arabic, Classical Chinese (Wenyanwen), and Latin.

## Found a bug?

Go to `https://github.com/AUGUHDAR/VoxLink/issues`, bring your logs, both host and joiner:

```
.minecraft/versions/<your_version>/logs/latest.log
```

Also tell us your network setup — home broadband? Campus WiFi? Hotspot? Without logs I'm just guessing what's going on on your end.

## How it works

**UDP hole punching + STUN** for NAT detection, punch strategy inspired by EasyTier, custom **reliable UDP transport** for MC traffic after connection. Signaling server only swaps addresses at handshake, game data is 100% P2P never touches the server. Pure Java, no native deps, runs on Windows / Linux / macOS / Android. Some code generated with AI help.

Website: `https://p2p.wuhui.icu/`

---

## 中文

# VoxLink

与好友快速联机，或在大厅寻找志同道合的伙伴 :D

---

## 如何游玩

打开你的存档，创建房间，获得一个 **6位房间码**，发给好友，好友输入即可。无需端口映射，无需改路由器，无需服务器，你甚至不需要知道公网IP是什么。装模组，建房间，发房间码，就这么简单。

从 **1.0.4** 起，VoxLink 集成了 **Terracotta（陶瓦）** — 一个外部 P2P 联机工具。陶瓦为可选功能，可在模组内直接下载。启用陶瓦后可大幅提升连接成功率，并可以使用陶瓦与其他陶瓦玩家进行联机。

> 仍为 Beta 版本。有时几秒就能连上，有时就是连不上。连接成功率与双方网络环境密切相关：同运营商家用宽带通常没问题，校园网、企业网、手机热点成功率较低。双方都是对称 NAT？成功率会更低。连接失败时建议双方重启游戏刷新 NAT 端口。我们每个版本都在优化打洞策略。你也可以在模组内启用陶瓦来提升成功率。

特别感谢 XIOPNM，他独自参与了 200 多次异地测试，我们总共跑了 300 多次。

## 后备与中继

**VoxLink 1.0.0+ 可用**

当直连打洞失败时，VoxLink 不会放弃。还有两招：

- **逆向打洞** — 如果一方的 NAT 不利于正向打洞，另一方向反向发起连接请求绕过去。
- **玩家中继** — 如果双方就是直连不了（比如都是对称 NAT），VoxLink 会从当前在线的公开大厅玩家中找一个网络条件合适的 Cone-NAT 玩家做中继节点。流量经过对方客户端转发，**绝不经过服务器**。中继玩家本身几乎无感知。

中继可以在设置中随时关闭。关了就不为别人中继，但也用不了别人的中继。挺公平的对吧。

## 房间

房间没有人数上限，离线玩家也能用。房间名在大厅公开展示前会经过 AI 审核。广东到江西延迟约 **15-20ms**，具体取决于双方网络。

创建房间时可以选择公开，会出现在大厅列表中。大厅 **不暴露玩家真实 IP**，全程 P2P 直连。支持标签分类方便查找。

## 兼容性

兼容 Simple Voice Chat（直接可用！），也对 ViaVersion、ViaFabric、Floodgate 提供了支持。

现已支持 **Fabric 1.20+** — 从 1.20 到 26.2 的所有版本。模组本身是多语言的，选你的语言：English、简体中文、繁體中文、日本語、한국어、Français、Deutsch、Español、Русский、Português (Brasil)、العربية、文言文，还有拉丁文。

## 遇到 BUG 怎么办

去 `https://github.com/AUGUHDAR/VoxLink/issues`，带上双方的日志：

```
.minecraft/versions/你的版本名/logs/latest.log
```

也请说明你们的网络环境 — 家用宽带？校园网？手机热点？没有日志我只能猜你们那边发生了什么。

## 技术原理

**UDP 打洞 + STUN** 进行 NAT 探测，打洞策略参考 EasyTier，连接建立后使用自研 **可靠 UDP 传输** 承载 Minecraft 流量。信令服务器仅在握手阶段交换地址，游戏数据 100% P2P 直连，不经过服务器。纯 Java 实现，无 native 依赖，支持 Windows / Linux / macOS / Android。部分代码由 AI 辅助生成。

官网：`https://p2p.wuhui.icu/`
