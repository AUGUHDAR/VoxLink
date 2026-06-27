# VoxLink

Play with your friends, or find new ones in the lobby :D

和好友联机，或在大厅找到新朋友 :D

---

## How to play / 使用方法

Open your world, create a room, get a **6-digit code**, share with your friend, they enter it, done. No port forwarding, no router config, no public IP needed.

房主创建存档后点击"创建房间"，系统生成**6位房间码**。将房间码分享给好友，好友输入后等待连接即可。无需端口映射、路由器配置或公网IP知识。

> Beta — success depends heavily on your network. Same ISP home broadband works well. Campus WiFi, office network, mobile hotspot less reliable. If one side is **symmetric NAT**, it's tough. Both restart the game if it fails, fresh NAT ports sometimes help.
>
> 当前为Beta版本，连接稳定性受网络环境影响。同运营商家用宽带成功率较高；校园网、企业网、手机热点成功率较低。双方对称NAT时连接困难。首次失败建议双方重启刷新NAT端口。

Huge thanks to **XIOPNM**, 200+ remote tests out of 300+, absolute legend / 特别感谢XIOPNM在300多次测试中参与异地测试200多次。

## Rooms / 房间

No player limit, offline players supported. Room names get AI checked before going public. Latency ~15-20ms Guangdong to Jiangxi (depends on your network). Public rooms show in the lobby without exposing IPs — all traffic stays P2P. Tags help find the right room.

无人数限制，支持离线玩家。房间名需AI审核后展示。经测试广东至江西约15-20ms延迟。大厅不暴露IP，所有连接P2P直连。按标签分类便于查找。

## Compatibility / 兼容性

Works with **Simple Voice Chat**, **ViaVersion**, **ViaFabric**, **Floodgate** (Bedrock). 12 languages: English, 简体中文, 繁體中文, 日本語, 한국어, Français, Deutsch, Español, Русский, Português (Brasil), العربية, 文言文.

## Bugs? / 问题反馈

[GitHub Issues](https://github.com/AUGUHDAR/VoxLink/issues) — bring both sides' logs:
```
.minecraft/versions/<version>/logs/latest.log
```
Describe your network setup (home broadband / campus / hotspot). Both logs or I'm guessing.

提交Issue请附带双方日志，描述双方网络环境（家庭宽带/校园网/手机热点）。

## How it works / 技术实现

**UDP hole punching + STUN** NAT detection. Punch strategy references EasyTier, Tailscale, libp2p DCUtR, P-PRE. Custom **reliable UDP transport** for MC traffic. Signaling server only swaps addresses at handshake — game data is 100% P2P, never touches the server. Pure Java, no native deps. Runs on **Windows / Linux / macOS / Android**.

采用UDP打洞结合STUN协议探测NAT类型，打洞策略参考EasyTier/Tailscale/libp2p DCUtR/P-PRE。连接建立后使用自研可靠UDP传输承载Minecraft流量。信令服务器仅在握手阶段交换地址，游戏数据全程P2P直连。纯Java实现，无native依赖，支持全平台。

**Fabric 1.21.11** only for now. Some code generated with AI assistance / 部分代码由AI辅助生成。
