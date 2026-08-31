# VoxLink TURN 中继协议 v1

> 本文档是 TURN 中继节点的**唯一权威契约**。`TURN/go`、`TURN/php`、`TURN/apk` 三个实现以及信令服务器（server-go）、客户端（VoxLink mod）均以本文为准。任何实现不得私有扩展字段；协议升级必须升 version 并出新规范。

## 0. 总览

- 传输：UDP，节点监听单端口。
- 角色：`host=0x01`，`guest=0x02`。一个会话（session）恰好两个角色，各绑定一个 UDP 源地址（ip:port）。
- 会话建立流程：信令服务器签发票据 → 两个客户端各自 `BIND` → 节点绑定双方源地址 → 之后节点在两个源地址之间转发 `DATA`。
- 部署形态：节点必须有一个**公网可达的 UDP 入口**（公网机器直接监听；内网/家用设备用 frp 等做 UDP 端口映射后，Admin 里登记映射后的公网地址）。

## 1. 报文格式

所有多字节整数**大端**（network byte order）。每个报文以 4 字节头开始：

```
offset  size  字段
0       2     magic   = 0x56 0x4C  ("VL")
2       1     version = 0x01
3       1     type
4..           payload（按 type 定义）
```

magic/version 不符的报文**静默丢弃**。

| type | 名称 | 方向 | payload |
|------|------|------|---------|
| 0x01 | PING | 客户端→节点 | `clientNonce(4) tsMs(8)` |
| 0x02 | PONG | 节点→客户端 | `clientNonce(4) tsMs(8) serverTsMs(8)`（前 12 字节原样回显） |
| 0x03 | BIND | 客户端→节点 | `sessionId(16) role(1) ticketLen(2) ticket(ticketLen 字节 ASCII)` |
| 0x04 | BIND_RESULT | 节点→客户端 | `sessionId(16) role(1) code(1)` |
| 0x05 | DATA | 双向 | `sessionId(16) fromRole(1) toRole(1) payloadLen(2) payload(payloadLen 字节)` |
| 0x06 | KEEPALIVE | 客户端→节点 | `sessionId(16) role(1)` |
| 0x07 | KEEPALIVE_ACK | 节点→客户端 | `sessionId(16)` |
| 0x08 | UNBIND | 客户端→节点 | `sessionId(16) role(1)` |
| 0x09 | UNBIND_RESULT | 节点→客户端 | `sessionId(16) code(1)` |

### 1.1 字段说明

- `sessionId`：16 字节随机数，UDP 包内用 raw 16 字节；HTTP/信令层用其 hex（32 字符小写）表示。
- `role`：`0x01`=host，`0x02`=guest。
- `payloadLen` ≤ 1400；超过 1400 的 DATA 整包丢弃（保 MTU 安全）。
- `BIND_RESULT.code`：`0`=ok，`1`=bad_ticket，`2`=ticket_expired，`3`=session_full，`4`=role_conflict（同源地址或角色已被绑），`5`=server_busy。
- `UNBIND_RESULT.code`：`0`=ok，`1`=not_found。
- 除 PING 外，所有请求报文来源若不属于该 sessionId 已绑定的源地址 → 丢弃；BIND_RESULT 永远发往发起 BIND 的源地址。

### 1.2 PING / PONG（延迟探测）

- 无需鉴权。客户端对每个候选节点发 3 次 PING（间隔 ≥200ms），RTT = 收到 PONG 时刻 − 发送时刻，取最小值。
- 节点限频：**每源 IP ≤ 10 包/秒**，超限静默丢弃。

## 2. 票据（ticket）

信令服务器为**每个角色**各签发一张票据。格式（ASCII，点分 5 段）：

```
T1.<sessionIdHex32>.<role十进制>.<expSec十位十进制>.<hmacHex64>
```

- `hmacHex64` = hex( HMAC-SHA256( nodeKey, "T1.<sessionIdHex32>.<role十进制>.<expSec十位十进制>" ) )
- `expSec` = 签发时刻 + 600（10 分钟），Unix 秒。
- `nodeKey`：64 字符 hex，Admin 添加节点时由信令生成，部署时粘贴进节点配置。**每节点独立密钥，泄露只影响该节点。**

节点验证 BIND：拆段 → 重算 HMAC → 常量时间比对 → 校验未过期 → 校验 sessionId/role 与包内一致。任一失败回对应 code。节点**不联网验票**（离线验签）。

## 3. 会话状态机（节点侧）

```
收到 BIND(role=A) → 验票 → 建会话{A=srcAddr, bindDeadline=now+120s}
收到 BIND(role=B) → 验票 → 会话存在且未满 → B=srcAddr → 会话 ACTIVE
ACTIVE: DATA(A→B 互转)，双向计字节数
回收条件（任一）：
  a) bindDeadline 到期仍未凑齐两角色      → 整会话删除（防占坑）
  b) 单角色 90s 无任何包(KEEPALIVE/DATA)  → 踢该角色；若另一角色还在 → 回到等 BIND？否——直接删除整会话
  c) ACTIVE 会话 60s 无任何包             → 整会话删除
  d) UNBIND 任一角色                      → 整会话删除（切换成功释放）
```

- `session_full`：会话已有两个角色时第三个 BIND（含同角色重复 BIND）→ code=3；同 role 重复 BIND 且源地址相同 → 视为重连，刷新时间戳回 ok。
- 角色源地址 = 收到该角色首包（BIND）的 UDP 源地址；后续该角色所有包必须来自同一源地址，否则丢弃。
- `maxSessions`（节点配置）：同时 ACTIVE+等待中会话数上限，满时新 BIND 回 code=3。

## 4. 心跳上报（节点 → 信令）

节点每 `heartbeatIntervalSec`（默认 10）秒向信令 POST：

```
POST {heartbeatUrl}          # 例: https://sig.example.com/api/v1/relay/node/heartbeat
Content-Type: application/json

{"key":"<nodeKey>","sessions":<当前会话数>,"bytesIn":<累计>,"bytesOut":<累计>,"uptimeSec":<运行秒数>}
```

期望响应 `{"ok":true}`（HTTP 200）。失败不打断转发，仅记日志；信令侧 30s 无心跳判节点离线（信令职责）。累计字节数从进程启动计，溢出前足够大（uint64）。

## 5. HTTP API（信令服务器实现；客户端消费）

信令 = 总控，节点表存于信令 `data.json["relay_nodes"]`，每条：
`{id, name, host, port, maxSessions, enabled, key, lastSeen, hbSessions, hbBytesIn, hbBytesOut, addedAt}`。地址三种合法输入：`IP`、`IP:port`、`域名`（可含端口），归一化为 host + port（缺省端口用默认 37000）。

| 端点 | 方法 | 说明 |
|------|------|------|
| `/relay/status` | GET | `{"enabled":bool}`（DS 键 `relay_enabled`，Admin 开关） |
| `/relay/list` | GET | `{"nodes":[{id,name,host,port,sessions,maxSessions}]}`；过滤 enabled + 在线(lastSeen≤30s) + 有余量(sessions < maxSessions)；按负载比升序；**硬上限 `relay_list_max`（默认 10）条** |
| `/relay/allocate` | POST | body `{roomCode,clientId,token,nodeId}`；房间 token 鉴权 + 节点在线且有余量校验 → 生成 sessionId → 记录会话 → 返回 `{sessionId,host,port,hostTicket,guestTicket,expire}` |
| `/relay/release` | POST | body `{sessionId,token}` → 标记会话已释放（容量归还）；allocate 后 15 分钟未 release 自动回收 |
| `/relay/node/heartbeat` | POST | 见 §4；按 key 匹配节点更新 lastSeen/负载数据 |

- `/relay/allocate`、`/relay/release` 受频控（每 ip+clientId 每分钟 ≤6 次）。
- 负载显示值 `sessions = max(未释放票据会话数, hbSessions)`。

## 6. 客户端义务（VoxLink mod）

1. 打洞持续 20s → GET `/relay/status`；enabled 才显示"使用中继"。
2. 点击 → GET `/relay/list` → 对每节点 PING×3 测延迟（§1.2）→ 选 RTT 最小者。
3. POST `/relay/allocate` → 自己 BIND(guestTicket)（发 KEEPALIVE 每 15s）→ 用信令把 `turn_alloc{sessionId,host,port,ticket(hostTicket),expire}` 发给 host。
4. host 收到 `turn_alloc` **自动** BIND(hostTicket)（房主无 UI、无同意步骤），并向 guest 回执（复用现有 relay 信号语义）。
5. 双方在 TURN 路径上跑 ReliableUdpTransport（DATA payload = 传输层帧）。
6. 后台 5 分钟内并行尝试直连 P2P 与玩家中继；任一路径验证稳定（连续 20 次探测）→ 平滑切换（双收 + 切发 + 10s 宽限回退）→ 宽限过：双方 UNBIND + POST `/relay/release`。
7. 5 分钟都失败 → 停止尝试（本连接周期内）；退出重进重置。

## 7. 默认端口

节点默认 UDP 端口 **37000**（Admin 登记缺省端口时的 fallback；节点配置可改，登记时须一致）。
