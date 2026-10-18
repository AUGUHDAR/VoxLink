# VoxLink 互通中继执行面（server-hl，Go）

这是 VoxLink 与 HappyLink 双向互通所用的**合并服务端**（Go 实现），运行在 HappyLink 的中继节点机器上，
替代/并存原 Node 版 `server/`（控制面已上收至 VoxLink 主服，本机纯执行）。

## 三平面

| 平面 | 端口（默认） | 说明 |
|------|--------------|------|
| 中继执行（隧道+房间端口） | TCP 9000 + 23400-23900 | 协议逐语句对齐本仓 `server/worker.js`（HELLO/DATA/CONNECT/PING） |
| 租户通道 + 大厅代理 | 出站 WSS → VoxLink 主服；本机 8080 反代 | 房间管理权在主服，本机只执行；8080 把 HappyLink 客户端的 `/api/*` 代理给主服 |
| VoxLink TURN 节点 | UDP 3478 + 37000 + 37100-37999 | 与原 TURN 节点同款（`hlturn/`） |

## 构建与部署

```bash
# 本机或交叉编译（Linux amd64）
GOOS=linux GOARCH=amd64 go build -o server-hl .
./server-hl -config config.json
```

`config.json` 关键段：`hl`(httpPort/tunnelPort/portFrom/portTo/publicHost/bindHost) + `turn`（沿用原 TURN 配置）。
systemd 建议 `Restart=always`；**改配置重启即可，控制面在主服侧，重启不丢房间管理权**（在途隧道会断，挑低峰做）。

## 防火墙放行

TCP 8080 / 9000 / 23400-23900，UDP 3478 / 37000 / 37100-37999（云安全组 + 系统防火墙两边都要）。

## 与 VoxLink 主服的对接

- 租户 WS：`wss://<主服>/api/v1/hl/tenant/ws`，鉴权 key 由 VoxLink 侧下发（`hl_tenant_key`）。
- 大厅代理注入 `X-HL-Tenant` / `X-HL-Client-IP`，主服按其做限额与统计。
- 协议细节见 VoxLink 私有仓 `docs/happylink-interop.md`（本仓不含）。
