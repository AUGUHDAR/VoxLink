# -*- coding: utf-8 -*-
"""TURN 根因定谳与修复记忆写回：ZCode 新篇+索引 + TRAE 项目记忆"""
import io, os

ZDIR = r"C:\Users\19430\.zcode\cli\memories\projects\voxlink-9a9f46dba867d0dd\memory"
NEW_MEM = os.path.join(ZDIR, "voxlink-turn-rootcause-20260923.md")
INDEX = os.path.join(ZDIR, "MEMORY.md")
TRAE = r"C:\Users\19430\.trae-cn\memory\projects\-d----voxlink--p2-a7e51d6763a02d303c09\project_memory.md"

BODY = """---
name: voxlink-turn-rootcause-20260923
description: 2026-09-23 TURN(TRN)零成功根因定谳——双叠加bug均在Java端ReliableUdpTransport(收包从不剥TURN信封+发送帧超节点1400被静默丢)，代码+日志双证；修复=processDatagram先decode+动态分块上限1374；28模板已传播；1.1.5互操作降级实为误诊
metadata:
  node_type: memory
  type: project
  originSessionId: sess_trae_20260923
---

## TURN 从未成功的根因定谳（代码+日志双证，非推测）

**两个叠加致命 bug 都在 Java 客户端 ReliableUdpTransport**；Go 节点无罪（单测覆盖+转发正确）；
28 模板同病；TCP/UDP 双承载同病（TurnTcpChannel 是透明 shim，同格式信封）；与"服务器禁 PING"无关
（探测是应用层 UDP PING 0x01，不走 ICMP）。

### Bug 1：收包从不剥 TURN 信封
- 发送侧 rudp 帧 → TurnPathCodec.encode() 套 24 字节信封；节点 DecodeDATA→EncodeDATA 重封转发（信封保留，main.go:296）
- 接收侧 recvPathLoop→processDatagram **直接把信封当 rudp 帧解析；UdpPath.codec.decode() 全项目零调用（死代码）**
- 悲剧巧合：TURN 信封前 2 字节也是 "VL"（两协议共用 MAGIC）→ 信封骗过 rudp 魔数检查
- 认证期：MAC 只覆盖内层帧，校验算整个信封 → 必然失败 → 双侧互丢（HQ8BW3 实锤）
- 降级后：type=buf[2]=PROTOCOL_VERSION=**1**=rudp PUNCH → 数据全被误判为打洞包，数据永不进流
- **1.1.5"互操作降级"其实是治此 bug 的症状，当时误诊为密钥不一致**

### Bug 2：发送帧超节点上限被静默丢弃
- 节点 maxPayloadLen=1400（SPECS §1.1 保MTU），DecodeDATA 对超限整包丢（protocol.go:233）
- Java：MC 流分块 1400 → DATA 帧 1413/1417(+MAC) 全灭；FEC 帧 1426 全灭；VOICE 帧 1415 全灭
- 只有小控制帧(keepalive/ack 15字节)能过 → 完美解释节点侧"心跳 in/out 各 179KB 数据面近乎零"

### 症状解释（HQ8BW3 1.1.6 日志逐行吻合）
MC 连本地桥成功（"正在连接到服务器"）→ 握手帧 1413 字节死在节点 → host 零流量 →
看门狗 30s+30s 拆桥 → MC 30s 超时。双侧均有 "dropped unauthenticated packet at rudp-data" + 降级日志。

## 修复（用户已批准；不构建不发布，1.1.8 与工单系统一起）

| # | 位置 | 改动 |
| --- | --- | --- |
| 1 | processDatagram 开头 | codec!=null 先 decode 剥信封，null 丢弃；覆盖主/次双路径+TCP/UDP |
| 2 | 常量区 | 新增 TURN_MAX_SEND_CHUNK=1374（保 DATA(13头)/FEC(22头) 帧+4MAC ≤1400） |
| 3 | currentMaxSendChunk() | 按当前 primary 的 codec 动态取 1374/1400（平滑切换 promote 后自动放宽） |
| 4 | sendBytes/sendVoice | 硬编码 1400 → currentMaxSendChunk() |

传播：fabric/26.3 金标准（编译过 18s）→ 27 模板整拷保留各自 EOL（仅 fabric/26.2 是 CRLF）
→ 28/28 单簇 + 6 标记各 1 次 + 零错误；脚本 propagate_turnfix_20260923.py。

## 参考项目调研（AnySearch）
- e4mc（297星）：中继走 **QUIC**（e4mc-quiclime）——传输层原生处理分帧/MTU/可靠性
- WorldHost：自有协议显式分帧（JoinType）
- 结论：成熟项目都把"帧封装+尺寸上限"当传输层一等公民，与修复方向一致

## 待办
- 编译矩阵验证后：用户真机验证 TURN 数据面首次打通
- 桌面 App(Wails)/APK 引擎同源同病——用户裁决 mod 先修，App/APK 后续同步
- 1.1.8 = joinfix + ModSync门控可见化 + TRN双bug修复 + 工单系统，全部完成后用户裁决发布
"""

INDEX_LINE = "- [2026-09-23 TURN零成功根因定谳+修复](voxlink-turn-rootcause-20260923.md) — 双bug均Java端：收包从不剥TURN信封(decode零调用+VL魔数巧合→认证互丢→降级后type=1误判PUNCH)+发送帧1413~1426超节点1400被静默丢(心跳179KB铁证)；修复=processDatagram先decode+动态分块1374；28/28已传播；1.1.5互操作降级实为误诊；App/APK同源后续同步\n"

TRAE_ADD = """- TURN(TRN) 零成功双根因（1.1.8 已修）：①ReliableUdpTransport.processDatagram 从不调 codec.decode()——TURN 信封与 rudp 帧共用 "VL" 魔数，信封被直接当帧解析（认证期 MAC 必失败双侧互丢；降级后 type=协议版本1= PUNCH 误判，数据永不进流）②发送帧 1413~1426 超节点 maxPayloadLen=1400 被静默丢。修复=processDatagram 开头 codec 路径先 decode + TURN_MAX_SEND_CHUNK=1374 动态分块（DATA 13头/FEC 22头+4MAC≤1400）；节点/信令均无罪
- TURN 帧尺寸铁律：任何经 TURN 节点的帧（含未来新帧型）必须 ≤1400 字节（节点 DecodeDATA 超限整包丢）；新增帧型先算 头+payload+4MAC 再定分块
"""

with io.open(NEW_MEM, "w", encoding="utf-8", newline="\n") as f:
    f.write(BODY)
print("ZCode memory written")

with io.open(INDEX, "r", encoding="utf-8") as f:
    idx = f.read()
if "voxlink-turn-rootcause-20260923" not in idx:
    with io.open(INDEX, "w", encoding="utf-8", newline="") as f:
        f.write(INDEX_LINE + idx)
    print("ZCode index prepended")
else:
    print("ZCode index already has entry")

with io.open(TRAE, "r", encoding="utf-8") as f:
    tm = f.read()
if "TURN(TRN) 零成功双根因" not in tm:
    with io.open(TRAE, "a", encoding="utf-8", newline="") as f:
        if not tm.endswith("\n"):
            f.write("\n")
        f.write(TRAE_ADD)
    print("TRAE project memory appended")
else:
    print("TRAE already has entry")
print("DONE")
