# VoxLink

Play with your friends, or find new ones in the lobby :D

---

## How to play

Open your world, create a room, get a **6-digit code**, send it to your friend, they type it in, done. No port forwarding, no router stuff, no server, you don't even need to know what a public IP is. Install mod, make room, send code, that's it.

> Still in Beta, not gonna lie it can be pretty rough T_T Sometimes connects in seconds, sometimes just... doesn't. Really depends on your network. Same ISP home broadband usually works fine, campus WiFi, office network, phone hotspot not so much. Other side on **symmetric NAT**? Probably not happening. If it fails both restart the game, fresh NAT ports sometimes fixes it. We're improving the punch strategies every update, it'll get there >_<

Huge thanks to **XIOPNM**, 200+ remote tests out of 300+, absolute legend.

## Rooms

Rooms have no player limit, offline players work too. Room names get AI checked before going public. Latency tested around **15-20ms** between Guangdong and Jiangxi, depends on your network.

You can set your room to public when creating it, shows up in the lobby. Lobby **doesn't leak your IP** or anything, still all P2P. Got tags to help you find the right room.

## Compatibility

Works with **Simple Voice Chat**, **ViaVersion**, **ViaFabric**. **Floodgate** supported too so Bedrock players can join. Languages: English, 简体中文, 繁體中文, 日本語, 한국어, Français, Deutsch, Español, Русский, Português (Brasil), العربية, and... 文言文.

## Found a bug?

Go to [GitHub Issues](https://github.com/AUGHDR/VoxLink/issues), bring your logs, both host and joiner:

```
.minecraft/versions/<your_version>/logs/latest.log
```

Also tell us your network setup — home broadband? Campus WiFi? Hotspot? Without logs I'm just guessing what's going on on your end orz

## How it works

**UDP hole punching + STUN** for NAT detection, punch strategy inspired by EasyTier, custom **reliable UDP transport** for MC traffic after connection. Signaling server only swaps addresses at handshake, game data is 100% P2P never touches the server. Pure Java, no native deps, runs on Windows / Linux / macOS / Android. Some code generated with AI help.

**Fabric 1.21.11** only for now, more versions coming ^^
