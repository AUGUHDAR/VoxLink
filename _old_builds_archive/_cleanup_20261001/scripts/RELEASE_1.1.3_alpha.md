# VoxLink 1.1.3 alpha

打洞/中继体系的本次重写。**alpha 标记**：开学测试人员不足，极端 NAT 组合的回归未全部跑过；与官方原版/NAT 桥仍可能存在边缘冲突，发现问题请带 voxlink 日志截图反馈。

---

## 重大改动

### TURN 中继池（协议规范：`SPECS/turn-protocol-v1.md`）
信令后台新增「中继」板块。管理员在 Admin 添加 TURN 节点（IP / IP:port / 域名 任意填，捐赠者部署的节点随时插拔，零客户端改动）。**单次最多下发 10 个节点**（`relay_list_max` 可调），防节点过多干爆客户端。客户端打洞 20s 后右上角出现「使用中继」按钮，点了先拉列表→应用层 UDP 测延迟（不依赖 ICMP ping，禁 ping 也无碍）→连最低延迟节点。TURN 建立后后台继续 5 分钟 P2P 打洞与玩家中继尝试，成功则平滑切换到直连并释放 TURN 容量；5 分钟未成则停止尝试，本连接周期不再重试。

### 平滑切换（make-before-break）
传输层新增 `UdpPath` 双路径抽象。TURN 期间次路径挂入并双收（去重靠序号空间天然完成，对端无感知、双方独立 promote 永不脑裂）；验证次路径连续 20 次稳定收包后切发送路径，再 10s 宽限双发兜底，稳了才 UNBIND 释放 TURN。MC 始终只连本地代理，切换全程对玩家无感。

### 玩家中继热备
TURN 期间窗口内每 60s 走现有玩家中继链路静默建立 G1 备用通路（不建桥、不动当前连接）；TURN 意外死亡时监视器自动 teardown → 热备转正 + 程序化重连，把"手动退出重进"压到几秒自愈。

### 边缘情况兜底
- 玩家点按钮瞬间原打洞恰好成功：客户端二次检查 `connectionWon` 直接放弃 TURN 并释放会话；host 侧收到 `turn_alloc` 也 CAS 拒绝防双桥
- TURN 建立后遗留的迟到 puncher 回调会重复建桥：`handleTurnReady` 置 `relayConnectedSignaled` 拦截
- TURN 保活任务所有退出路径都 cancel（之前会泄漏）
- TURN 死亡无人善本→后台监视器立即 teardown+热备转正

---

## 信令后台新增

- **TURN 中继节点卡片**：名称、地址（三种格式任填）、最大连接数、启停、密钥（点击复制）、在线状态（●绿●红）、当前会话数；行内自动保存；启用/禁用立即生效
- **新增端点**：`/relay/status` `/relay/list` `/relay/allocate` `/relay/release` `/relay/node/heartbeat`
- **新信号类型**：`turn_alloc` / `turn_release` / `turn_stby`

---

## 兼容性

- 1.1.3 与 1.1.2/1.1.1 协议完全兼容：旧版客户端不识 `turn_*` 信号会直接忽略；新版客户端向旧版信令请求 `turn_*` 端点会回 `RELAY_DISABLED`/404 优雅降级
- 依赖信令侧开启 `relay_enabled` 开关（默认关）
- 依赖服务器 UDP 37000 入站放行（云安全组 + 宝塔防火墙 + 主机 iptables 三层都要放）

---

## 已知问题 / 警告

1. **打洞期间掉线 teardown 的 5s 窗口**：极少概率 `attemptBgDirectPunch` 触发后 host 端 P2P 反弹回 guest 时本端 netty close 异常。如遇 1.1.3 偶发 "Kicked by server" / "transmit disconnect"，可回退 1.1.2 暂用
2. **APK 节点未签名打包**：本机无 Android SDK，需要本机/捐赠者自行 `gradlew assembleRelease` 后挂 frp 暴露公网 UDP
3. **alpha 测试覆盖率**：仅 25 模板编译通过 + Go/PHP 节点单元测试通过；多端实弹联调样本不足

---

## 上手指引

**玩家**：
1. 安装 mod 后照常用
2. 打洞 ≥20s 后右上角出现「使用中继」按钮时点击即可

**管理员部署 TURN 节点**（三选一部署形式，所有实现同一协议）：
- 拿一份 NodeKey（Admin 后台添加节点后自动生成），粘贴到任一实现的 `config.json`
- `TURN/go/`：Go 二进制（win/linux × amd64/arm64，dist 已附）；性能最佳
- `TURN/php/`：单文件 `php turn.php`，仅需 `php-cli + php-sockets` 扩展；适合 ≤30 会话
- `TURN/apk/`：安卓前台服务，需本机构建后挂 frp 内网穿透；适合废弃手机当免费节点

**详细规范**：见 `SPECS/turn-protocol-v1.md`

---

## 25 模板覆盖

fabric / forge / neoforge 全版本统一：1.20_1.20.1 / 1.20.2_1.20.6 / 1.21_1.21.5 / 1.21.6_1.21.8 / 1.21.9_1.21.10 / 1.21.11 / 26.1_26.1.2 / 26.2。1.21.x 聊天 API 分叉已在 25 模板中分别处理（`sendSystemMessage` / `displayClientMessage`）。
