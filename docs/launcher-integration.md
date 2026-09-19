# VoxLink 启动器集成规范（Launcher Integration）

> 面向第三方启动器（如 KAMUCL）对接 VoxLink 联机服务与「模组同步 / 相关链接」能力。
> 信令 HTTP 信封格式与全端一致：`{"success":bool,"data":?,"error":?,"message":?}`；
> 非 2xx 时 `error` 为机器可读错误码。房间码正则：`/^[A-HJ-NP-Z2-9]{6}$/`。
> 参考实现：本仓库 mod 端源码（路径见文末）；服务端为黑盒契约，本文档即权威。

## 1. 相关链接（8 个，权威来源）

来源文件：`fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/ui/RelatedLinksScreen.java`
（名称 i18n 键：`assets/voxlink/lang/*.json` 的 `voxlink.links.*`）

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

链接如有变更，以最新版仓库内 `RelatedLinksScreen.java` 为准。

## 2. 模组同步（ModSync v2）

### 2.1 角色与流程

```
房主（启动器或 mod）                    服务器                        房客（启动器或 mod）
  建房时本地构建两档清单（不上报） ──→  /room/create                    │
                                        （clientCapabilities 须含        │
                                          "modSyncV1"）                  │
                                        ←─ /room/mods/request ──────────│ 房客选档(required|all)
  ←─ 信号 mods_request{requestId,scope} ─┤ 缓存未命中时注入信号，          │
                                        │   长轮询等待 ≤12s               │
  /room/mods/answer（带 token+manifest）─→ 写缓存(TTL 600s) + 唤醒 ──────→ 返回清单
```

- 房主**必须**在收到请求前就构建好清单（建房成功后立即后台构建本地缓存），
  否则 12s 长轮询超时、房客拿不到清单（`ready:false`，房客侧重试后放行）。
- 主动推送：`/room/mods/answer` 允许无在途 requestId 直接调用（只写缓存不唤醒），
  启动器可在建房后/模组变化时主动把两档清单推到服务器缓存。
- 版本区分：`manifest.loader` / `manifest.mcVersion` 必须与房主**当前所选实例**
  （MC 版本 + 加载器）一致；服务端按房间存储、按 scope（`required`/`all`）分档缓存。

### 2.2 端点

| 路由 | 方法 | 鉴权 | 说明 |
|------|------|------|------|
| `/room/create` | POST | - | 启动器当房主时 body 须含 `clientCapabilities:["modSyncV1"]` |
| `/room/mods/publish` | POST | hostToken | 旧兼容推送；等价写 `required` 档缓存 |
| `/room/mods/request` | POST | -（按房间号） | body `{code, scope:"required"\|"all"}`；命中缓存立即返回，未命中长轮询 ≤12s |
| `/room/mods/answer` | POST | hostToken | body `{code, token, requestId, scope, manifest}`；应答 + 写缓存（双用） |
| `/room/mods` | POST | -（按房间号） | 旧拉取，等价 `scope=required` 缓存 |

`/room/mods/request` 响应 `data`：
`{supported:bool, ready:bool, protocolVersion?, loader?, mcVersion?, mods?:[], unknownMods?:[]}`
- `supported:false`：房主未声明 modSyncV1 能力（老房主），调用方应静默跳过检查。
- `ready:false`：缓存未命中且房主 12s 内未应答（可重试，建议总预算 12s 后放行）。

### 2.3 manifest 结构

```json
{
  "protocolVersion": "modSync.v1",
  "loader": "fabric",              // fabric|forge|neoforge|unknown
  "mcVersion": "1.20.1",           // 房主所选实例的 MC 版本
  "mods": [ { ...Entry } ],
  "unknownMods": ["文件名.jar"]     // MR 查不到、无法代下的模组（仅提示）
}
```

Entry 字段（与 mod 端 `ModSyncEntry.toJson` 一致）：

```json
{
  "projectId": "MR project id",
  "slug": "project slug",
  "title": "展示名",
  "versionNumber": "1.2.3",
  "fileName": "xxx.jar",
  "url": "CDN 直链（cdn.modrinth.com）",
  "sha1": "房主实际安装文件的 sha1（房客离线 diff 用）",
  "sha512": "下载校验用",
  "size": 123456,
  "loaders": ["fabric"],
  "gameVersions": ["1.20.1"]
}
```

限制：mods ≤ 256 条、整个 manifest ≤ 128KB（超出服务端截断/拒绝）、缓存 TTL 600s。

### 2.4 房主清单构建算法（参考 mod 端实现）

1. 枚举所选实例 mods 目录的 jar，逐个算 sha1；
2. `POST https://api.modrinth.com/v2/version_files`（body `{hashes:[sha1...], algorithm:"sha1"}`，
   64 个/块）→ sha1 → MR 版本对象；查不到的进 `unknownMods`；
   **单块网络失败只损失该块（落入 unknownMods），不得作废整张清单**；
3. `GET /v2/projects?ids=[...]` 批量取项目元数据（**必须含自有 mod + 依赖**两种 id）；
4. 两档清单：
   - `required`：以"client_side 非 optional/unsupported"的模组为根，沿 `dependencies`
     中 `dependency_type=="required"` 闭包（BFS，visited 按 project_id 去重），
     剔除 `server_side=="unsupported"`；
   - `all`：房主全部 MR 可识别模组（server-only 除外），不区分 client_side；
5. User-Agent 须含产品标识（mod 端为 `VoxLink/<版本> (modsync)`；429 按 Retry-After 退避）。

### 2.5 房客侧 diff 算法（参考 mod 端 `ModSyncGuestService.computeDiff`）

- 本地 mods（含 `.jar.disabled`）算 sha1 集合，与清单条目 sha1 精确对比；
- sha1 命中 = 已安装（disabled 命中 → 提示手动启用）；
- 归一化文件名相同但 sha1 不同 = 同 mod 不同版本 → 仅强提示，**绝不移动/删除用户文件**；
- 未安装且 `loaders` 含本端 loader 且 `gameVersions` 含本端 MC 版本 → 可下载
  （下载后 sha512 校验，完成后提示关闭游戏重启生效）；
- 其余 → "无法解决"提示列表。

## 3. 错误码

`CODE_REQUIRED` / `ROOM_NOT_FOUND`(404) / `INVALID_TOKEN`(403) / `INVALID_ANSWER` /
`MANIFEST_TOO_LARGE` / `MANIFEST_REQUIRED` / `PERSIST_FAILED`。
房主 token 即 `/room/create` 返回的 `hostToken`。

## 4. mod 端参考实现（本仓库路径）

| 文件 | 内容 |
|------|------|
| `fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/ui/RelatedLinksScreen.java` | 8 链接权威来源 |
| `fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/modsync/ModSyncManifestService.java` | 房主两档构建 + 本地缓存 + 按需应答 |
| `fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/modsync/ModSyncEntry.java` | manifest Entry 结构 |
| `fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/modsync/ModSyncGuestService.java` | 房客门控/diff/重试预算 |
| `fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/modsync/ModrinthClient.java` | MR API 客户端（分块降级/429/校验） |
| `fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/modsync/ModSyncSelectScreen.java` | 房客选择界面参考 |
| `fabric/1.20_1.20.1/src/main/java/icu/wuhui/voxlink/network/SignalingClient.java` | 端点路由表（HTTP 契约同源） |
