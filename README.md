Play with your friends, or find new ones in the lobby :D

---

## How to play

Open your world, create a room, get a **6-digit code**, send it to your friend, they type it in, done. No port forwarding, no router stuff, no server, you don't even need to know what a public IP is. Install mod, make room, send code, that's it.

Starting from **1.0.4**, VoxLink integrates **Terracotta** P2P. You can now play with other Terracotta users too, and the connection success rate is significantly improved. You can download and enable Terracotta right inside the mod settings.

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
