# VoxLink 启动器集成规范（Launcher Integration）

> 本文档面向第三方启动器（如 KAMUCL）对接 VoxLink 联机服务。目标读者是 AI 助手：
> 按 §0 任务清单执行；所有对接细节以本文档为权威，参考实现一律读 §6 列出的
> GitHub 源码文件（只读参考，**不得修改 VoxLink 仓库**）。
>
> - 信令 HTTP 信封：`{"success":bool,"data":?,"error":?,"message":?}`，非 2xx 时 `error` 为机器可读错误码
> - 房间码正则：`/^[A-HJ-NP-Z2-9]{6}$/`；建议请求头携带 `X-VoxLink-Version: <启动器版本>`

## 0. AI 任务清单

1. **合并链接按钮**：启动器 VoxLink 面板现有的 3 个硬编码链接按钮 → 合并为一个「相关链接」按钮，
   点击弹出新界面，展示 §1 的 8 个链接（名称+URL 以 §1 表格为准）。
2. **逐项对比同步**：把 §2 列出的 VoxLink 1.1.7 客户端行为，逐项与启动器现有实现对比，
   VoxLink 侧更新或更强的，按启动器技术栈适配照搬或优化后照搬（§3 的 ModSync v2 是全新功能，必须实现）。
3. **服务端零改动**：本文档 §4 的服务端契约已全部就绪，启动器只需按契约调用；
   发现疑似服务端缺口时，先复查本文档与 §6 参考实现的实际行为，不要臆测接口。

## 1. 相关链接（8 个，权威来源）

来源：[`RelatedLinksScreen.java`](../fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/ui/RelatedLinksScreen.java)
（名称 i18n 键：`assets/voxlink/lang/*.json` 的 `voxlink.links.*`，13 语言齐全）

| key | 名称 | URL |
|-----|------|-----|
| site | 官网 | https://p2p.wuhui.icu/ |
| mcmod | MC百科 | https://www.mcmod.cn/class/28295.html |
| github | GitHub | https://github.com/AUGUHDAR/VoxLink |
| gitee | Gitee | https://gitee.com/AUGUHDAR/VoxLink |
| modrinth | Modrinth | https://modrinth.com/mod/voxlink |
| curseforge | CurseForge | https://www.curseforge.com/minecraft/mc-mods/voxlink |
| discord | Discord | https://discord.gg/XaAFxvzPDS |
| qq | QQ群 | https://qm.qq.com/cgi-bin/qm/qr?k=OEkk9L8m8jdFMkbGDhKZs0u2U0azLAPo&jump_from=webapi&authKey=v0dYAQniGZypAJuoPZW/7FL0bfoc32h68oIHd9lqGwOvAduzcwsJNR7Mei9/YugW |

## 2. 1.1.5 → 1.1.7 客户端行为变更（对比同步清单）

| # | 领域 | 行为 | 参考实现 |
|---|------|------|----------|
| 1 | 信令 | **WS 优先**：信令通道优先走 WebSocket（`/ws`，帧协议见该文件头注释），HTTP 轮询降级为兜底；断线自动回退与恢复 | `network/SignalingWsTransport.java`、`ws.go`（服务端 `/ws` 帧协议） |
| 2 | 打洞 | **TCP 双向 SimOpen**：UDP 对称 NAT 场景叠加 TCP 同时打开打洞 | `network/TcpHolePuncher.java`、`network/PunchStrategySelector.java` |
| 3 | 打洞 | **漂移分级**：对端端口漂移按 NAT 分级预测（`PunchProfile`），减少盲目全端口扫射 | `network/PunchProfile.java`、`network/PunchTuner.java` |
| 4 | 打洞 | **心跳闭环**：连接稳定窗口内掉线立即快传日志/退房补传/关服兜底（可观察行为，弱网自愈更快） | `room/ConnectionManager.java`（`startConnectionWatchdog` 一带） |
| 5 | 打洞 | PREDICTION_OFF 上限保护（50 次/会话），到达后自动转入中继/TURN，不再空转 | `network/UdpHolePuncher.java` |
| 6 | TURN | **TCP 兜底承载**：UDP 全丢（BIND 失败码 5=UDP 黑洞）时自动降级走同端口 TCP 长连接，帧格式=2 字节大端长度+同构报文；本地回环 UDP shim 对上层零侵入；绝无手动选择 | `network/TurnTcpChannel.java`、`network/TurnRelayClient.java`（`bindWithRetry`/`engageTcpFallback`） |
| 7 | TURN | BIND 带外层重试（3 轮×5 发）+ ROLE_CONFLICT 容忍 + 保活 15s | `network/TurnRelayClient.java` |
| 8 | 模组 | **ModSync v2 全新功能**：详见 §3 | `modsync/` 整包 |

## 3. 模组同步 ModSync v2（全新）

### 3.1 角色与流程

```
房主（启动器或 mod）                     服务器                        房客（启动器或 mod）
  建房时本地构建两档清单（不上报） ──→  /room/create                    │
                                        （clientCapabilities 须含        │
                                          "modSyncV1"）                  │
                                        ←─ /room/mods/request ──────────│ 房客选档(required|all)
  ←─ 信号 mods_request{requestId,scope} ─┤ 缓存未命中时注入信号，          │
                                        │   长轮询等待 ≤12s               │
  /room/mods/answer（带 token+manifest）─→ 写缓存(TTL 600s) + 唤醒 ──────→ 返回清单
```

关键规则（每一条都有生产事故背书，不要"优化"掉）：

- 房主**建房成功后立即**后台构建清单并缓存本地——收到请求才现算大概率超 12s；
- **主动推送**：`/room/mods/answer` 允许无在途 requestId 直接调用（只写缓存不唤醒），
  建房后把两档清单都推上去最稳；
- **版本区分**：`manifest.loader` / `manifest.mcVersion` 必须与房主**当前所选实例**
  （MC 版本+加载器）一致，禁止混入其他实例的 mods；
- MR 网络差是常态：**单块查询失败只损失该块（落入 unknownMods），绝不能作废整张清单**
  （生产实证：一次 HTTP 超时曾让 121 个 jar 的清单报废）；
- 房主 token 即 `/room/create` 返回的 `hostToken`。

### 3.2 端点契约

| 路由 | 方法 | 鉴权 | 说明 |
|------|------|------|------|
| `/room/create` | POST | - | 启动器当房主时 body 须含 `clientCapabilities:["modSyncV1"]` |
| `/room/mods/publish` | POST | hostToken | 旧兼容推送；等价写 `required` 档缓存 |
| `/room/mods/request` | POST | -（按房间号） | body `{code, scope:"required"\|"all"}`；命中缓存立即返回，未命中长轮询 ≤12s |
| `/room/mods/answer` | POST | hostToken | body `{code, token, requestId, scope, manifest}`；应答+写缓存（双用） |
| `/room/mods` | POST | -（按房间号） | 旧拉取，等价 `scope=required` 缓存 |

`/room/mods/request` 响应 `data`：

```json
{
  "supported": true,          // false=房主未声明能力（老房主），静默跳过检查
  "ready": true,              // false=缓存未命中且房主 12s 未应答，可重试（总预算 12s 后放行）
  "protocolVersion": "modSync.v1",
  "loader": "fabric",
  "mcVersion": "1.20.1",
  "mods": [ { …Entry } ],
  "unknownMods": ["散装.jar"]
}
```

### 3.3 manifest 与 Entry 结构

```json
{
  "protocolVersion": "modSync.v1",
  "loader": "fabric",
  "mcVersion": "1.20.1",
  "mods": [
    {
      "projectId": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      "slug": "sodium",
      "title": "Sodium",
      "versionNumber": "0.5.3",
      "fileName": "sodium-fabric-mc1.20.1-0.5.3.jar",
      "url": "https://cdn.modrinth.com/data/…/sodium-….jar",
      "sha1": "房主实际安装文件的 sha1（房客离线 diff 用）",
      "sha512": "下载校验用",
      "size": 1024000,
      "loaders": ["fabric"],
      "gameVersions": ["1.20.1"]
    }
  ],
  "unknownMods": ["散装模组.jar"]
}
```

限制：`mods` ≤ 256 条、整个 manifest ≤ 128KB（超出截断/拒绝）、缓存 TTL 600s。

### 3.4 房主清单构建算法（严格参考 [`ModSyncManifestService.java`](../fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/modsync/ModSyncManifestService.java)）

1. 枚举所选实例 mods 目录 jar，逐个算 sha1；
2. `POST https://api.modrinth.com/v2/version_files`，body `{"hashes":[…],"algorithm":"sha1"}`，
   64 个/块 → sha1 → 版本对象；查不到的进 `unknownMods`；单块失败降级继续；
3. `GET https://api.modrinth.com/v2/projects?ids=[…]` 批量取项目元数据
   （**必须同时包含自有 mod 与依赖的 project_id**——漏掉自有 ids 曾导致必装清单恒为 0 的生产事故）；
4. 两档：
   - `required`：以 client_side 非 `optional`/`unsupported` 的模组为根，沿 `dependencies` 中
     `dependency_type=="required"` 闭包（BFS，visited 按 project_id 去重），剔除 server-only；
   - `all`：全部 MR 可识别模组（server-only 除外），不区分 client_side；
5. User-Agent 带产品标识（如 `KAMUCL-App/<版本>`）；HTTP 429 按 `Retry-After` 退避；
   4xx 永久失败不重试；5xx/网络错误重试 3 次（1s/3s 退避）。

### 3.5 房客侧 diff（严格参考 [`ModSyncGuestService.java`](../fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/modsync/ModSyncGuestService.java)）

- 本地 mods（含 `.jar.disabled`）算 sha1 集合与清单对比；
- sha1 命中=已安装；disabled 命中=已装但被禁用（提示手动启用）；
- 归一化文件名相同、sha1 不同=同 mod 不同版本 → **仅强提示，绝不移动/删除用户文件**；
- 未安装且 `loaders` 含本端 loader、`gameVersions` 含本端 MC 版本 → 可下载
  （CDN 直链 + sha512 校验，完成后提示关闭游戏重启生效）；
- 其余 → "无法解决"提示列表（unknownMods 亦归此类）；
- `supported:false` / 12s 超时 / 空清单 → 静默放行进房，绝不打断。

## 4. 服务端契约补充

- 服务端为黑盒，本节即权威；服务端已就绪，**无需等待任何服务端改动**。
- 信令：`/signal/send`（POST `{code,token,isHost,type,data,to}`）+ `/signal/poll` 轮询
  或 `/ws` 推送；房主身份在订阅侧固定为 `"host"`（`mods_request` 即发往 `to:"host"`）。
- 房主能力声明：`/room/create` 的 `clientCapabilities` 数组（字符串 ≤32 字符）会透传为
  房间的 `hostCapabilities`，ModSync v1 需含 `"modSyncV1"`。
- 错误码：`CODE_REQUIRED` / `ROOM_NOT_FOUND`(404) / `INVALID_TOKEN`(403) /
  `INVALID_ANSWER` / `MANIFEST_TOO_LARGE` / `MANIFEST_REQUIRED` / `PERSIST_FAILED`。

## 5. 常见坑（全部生产实证）

1. MR `/v2/projects?ids=` 只查依赖 id 漏自有 id → 必装清单恒为空（VoxLink 1.1.2~1.1.6 线上事故）；
2. MR 单块超时整单作废 → 房客拿空清单；
3. 房主不预构建清单、收到请求才现算 → 12s 长轮询超时，房客拿不到；
4. 对 `versionDiff` 擅自动用户已装文件 → 用户资产受损（禁止）；
5. TURN UDP 全丢时反复重试 UDP → 应按 §2#6 降级 TCP；
6. 老房主（无 modSyncV1 能力）≠ 异常：`supported:false` 时必须静默直通。

## 6. 参考实现索引（GitHub 可直接打开）

| 文件 | 内容 |
|------|------|
| [`ui/RelatedLinksScreen.java`](../fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/ui/RelatedLinksScreen.java) | 8 链接权威来源 |
| [`modsync/ModSyncManifestService.java`](../fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/modsync/ModSyncManifestService.java) | 房主两档构建+本地缓存+按需应答 |
| [`modsync/ModSyncEntry.java`](../fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/modsync/ModSyncEntry.java) | manifest Entry 结构 |
| [`modsync/ModSyncGuestService.java`](../fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/modsync/ModSyncGuestService.java) | 房客门控/diff/重试预算 |
| [`modsync/ModSyncSelectScreen.java`](../fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/modsync/ModSyncSelectScreen.java) | 房客选择界面参考 |
| [`modsync/ModrinthClient.java`](../fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/modsync/ModrinthClient.java) | MR API 客户端（分块降级/429/校验） |
| [`network/SignalingClient.java`](../fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/network/SignalingClient.java) | 端点路由表（HTTP 契约同源） |
| [`network/SignalingWsTransport.java`](../fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/network/SignalingWsTransport.java) | WS 优先信令传输 |
| [`network/TurnTcpChannel.java`](../fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/network/TurnTcpChannel.java) | TURN TCP 兜底 shim |
| [`network/TurnRelayClient.java`](../fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/network/TurnRelayClient.java) | TURN 客户端（BIND/保活/TCP 降级） |
| [`network/TcpHolePuncher.java`](../fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/network/TcpHolePuncher.java) | TCP 双向 SimOpen 打洞 |
| [`network/PunchProfile.java`](../fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/network/PunchProfile.java) | NAT 分级/漂移预测 |
