# Android(Cone NAT) → Windows(Symmetric NAT) 连接失败根因分析

## 测试环境
- **Host (Windows)**: Symmetric NAT (easy_inc), mapped=39.158.48.127, MC端口=25565
- **Joiner (Android)**: Cone NAT, mapped=115.44.65.249

---

## 根因 #1 (致命): holepunch_mapped 生日端口处理方向错误

### 现象
Joiner 收到 host 的 `holepunch_mapped`（含75+个生日端口）后，创建 25 个**新 socket** 向每个 host 端口打洞。所有 25 个 socket 均 FirewallBlocked。

### 代码位置
`ConnectionManager.java:2140-2187` — `tryUdpPunch()` 中 birthday port 分支

```java
// 第2148-2155行：每个生日端口创建新socket
for (int i = 0; i < usePorts; i++) {
    UdpHolePuncher bp = new UdpHolePuncher();
    try { bp.createSocket(); } catch (Exception e) { continue; }  // ← 新socket！新mapped port！
    bp.punch(fTargetIp, port)... // 打向host的生日端口
}
```

### 根本错误
Joiner 的 Cone NAT 给每个新 socket 分配**不同的 mapped port**。但 Host 端 Symmetric NAT 的每个 birthday socket mapping（如 `host:63344 → joiner:37041 → 46276`）**只允许 joiner 的原始 mapped port (37041) 回包**。

Host birthday socket#0 的 NAT mapping：
```
(host, 63344, joiner_ip, 37041) → 39.158.48.127:46276
  ↑ 只接受来自 joiner:37041 的包
```

Joiner 用新 socket 57588 打到 host:46276，但 Cone NAT 将其映射为 `115.44.65.249:XXXXX`（非 37041），Host NAT **直接丢弃**。

### 正确做法
Birthday attack 的正确方向是：
1. **Host 的 84 个 birthday socket 打向 joiner:37041**（已正确实现，line 1177）
2. **Joiner 在 socket 37041 上收到包后回包**——不需要创建新 socket

Joiner 端应该做的事情：**复用同一个 socket，对 host 的每个 birthday port 发回包**，而不是创建新 socket。

更简单的做法：在 host birthday socket 的 `onPeerPunchReceived` 收到回包后直接 establish transport（line 1139-1148 已设置回调）。Joiner 端只需要在收到 host birthday punch 时**从同一socket回包**。

### 日志证据
```
# Joiner 端
[11:33:46] 收到主机映射: 39.158.48.127:46276 ports=[46276,46278,...] (75 ports)
[11:33:46] 多端口puncher#1: 39.158.48.127:46278 → socket 57588 (新端口!)
[11:33:46] 多端口puncher#2: 39.158.48.127:46280 → socket 55312 (新端口!)
...
→ 全部 FirewallBlocked

# Host 端
[11:33:43] 84个socket并行打洞到 115.44.65.249:37041 range=±0
[11:33:53] 防火墙检测: 发送50轮/10037ms无回包 ×84
→ host 的 punch 也没到达 joiner
```

**双方都在单向盲打，但映射端口不匹配，谁也收不到谁。**

---

## 根因 #2: 反向打洞 socket 跨周期不复用

### 现象
Joiner 反向打洞创建 socket 37521 → mapped 37521，通知 host 打到这个端口。
周期1失败后，周期2"复用打洞socket"用的是 port=37041（另一个socket），mapped address 变为 37041。

### 日志证据
```
周期1 (11:33:31):
  Joiner映射地址: 115.44.65.249:37521
  打洞到 host:46170 (socket=37521)

周期2 (11:33:43):
  复用打洞socket(port=37041)  ← 不同端口！
  我的映射地址: 115.44.65.249:37041  ← mapped 变了
```

### 后果
Host 端 Symmetric NAT mapping：`(host, 54205, joiner_ip, 37521) → 46299` — 只接受 `joiner:37521` 的包。
周期2 的包来自 `joiner:37041` → 被 host NAT 丢弃。

### 代码位置
`ConnectionManager.java:1948-1967` — `tryUdpPunch()` 中 socket 复用逻辑

反向打洞的 socket（"hostRev"）和普通打洞的复用 socket（"joiner_reuse"）是两个独立的管理路径，前者在周期失败后被关闭，后者跨周期复用。

---

## 根因 #3: 非法状态转换

### 现象
```
TCP_FALLBACK -> STUN_PROBE  (周期2/4)
TCP_FALLBACK -> UDP_PUNCH   (周期2/4)
```

状态机允许从 TCP_FALLBACK 跳回 STUN_PROBE/UDP_PUNCH，这是错误的。

### 代码位置
`ConnectionManager.java` — 连接状态机中 `advanceToNextCycle` 逻辑

---

## 根因 #4: TCP SimOpen 端口绑定失败

### 现象
```
TCP SimOpen: 从端口53025连到39.158.48.127:53025
TCP SimOpen第1次失败: Cannot assign requested address ×5
TCP SimOpen第1次失败: Connect timed out
```

### 原因
Android 无法 bind 到特定端口 53025（可能被占用或权限不足）。这是已知限制，但错误恢复不完善——每次失败都重试 5 次同样的端口。

---

## 非 Bug 确认: Birthday Socket STUN "-1 vs XXXX" 日志

### 现象
Host 日志中 420 条 `Socket#N STUN: -1 vs 46276`。

### 确认：无 Bug
代码 line 1069-1074：
```java
StunProbe.PublicMappedAddress a1 = fp.discoverMappedAddress(List.of(stunServer[0]));
StunProbe.PublicMappedAddress a2 = fp.discoverMappedAddress(List.of(stunServer[1]));
log("Socket#{} STUN: {} vs {}", idx, a1!=null?a1.port():-1, a2!=null?a2.port():-1);
```

代码 line 1086-1093：
```java
if (addrs != null && addrs[1] != null) {  // 只用第二个STUN结果！
    mappedAddrs.add(addrs[1]);
}
```

第一个 STUN 服务器 (`stun.miwifi.com`) 超时 → a1=null → 日志显示 -1。但代码**实际使用的是 a2**（第二个STUN服务器成功结果）。日志误导但不影响功能。

---

## 修复方案

### 方案 A: 修复 birthday port 方向（优先）
移除 `ConnectionManager.java:2140-2187` 中 joiner 创建 25 个新 socket 的逻辑。
改为：joiner 的复用 socket（"joiner_reuse"）在收到 host birthday punch 时，从**同一 socket** 回包到 source address。
Host 端 `setOnPeerPunchReceived` 已有回包逻辑（line 1139-1148），只需确保 joiner 端也能从同一个 socket 回复。

### 方案 B: 统一 socket 复用机制
将反向打洞 socket 纳入 `joiner_reuse` 管理，确保跨周期不换 mapped port。

### 方案 C: 修复状态机
禁止 `TCP_FALLBACK → STUN_PROBE` 和 `TCP_FALLBACK → UDP_PUNCH` 转换。

### 方案 D: TCP SimOpen 端口自适应
当 "Cannot assign requested address" 时，自动尝试下一个端口而非重复同一端口 5 次。
