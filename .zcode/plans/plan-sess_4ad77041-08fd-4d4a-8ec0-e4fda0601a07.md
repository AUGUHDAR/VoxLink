# VoxLink 自动化联机实验室 · 今晚开工计划（目录已改为 D:\桌面\voxlink\voxlink-lab）

## 新目录（仓库内、整目录自排除 git）
`D:\桌面\voxlink\voxlink-lab\`
```
voxlink-lab/
  .gitignore        ← 内容为 *，自排除：密钥/实例副本/JDK/归档永进不了公共仓库
  README.md
  servers/server-go/   ← 本地信令（复用预编译 exe，全新 VOXLINK_DATA_DIR + 密钥数据）
  servers/turn-node/   ← 本地 TURN 节点（config.json 含 lab 生成 nodeKey）
  instances/A|B(/C)/   ← 模板副本（gradlew runClient，Loom 离线会话免登录）
  jdk/jdkHost, jdkGuest ← JDK 副本（防火墙规则按程序路径区分实例）
  labctl/              ← python 编排器：拓扑起停/场景运行/日志判读/报告
  scenarios/  runs/    ← 场景定义 / 运行归档（log+pcap+结论）
```
git 纪律：`voxlink-lab/.gitignore` 内容 `*` 使整个目录（含 server-go 本地数据/节点密钥/JDK 副本）对 git 不可见，仅该 .gitignore 文件本身保持 untracked，绝不 git add——沿用"服务端物不入公共仓库"最高纪律。

## Workstream B：mod 测试钩子（参考模板 fabric/1.20.2_1.20.6，唯一动 mod 的部分）
按侦察插入点实施，全部双门控（`-Dvoxlink.test=1` 且 gameDir 有 `voxlink-test.json`，缺一不生效；无命令）：
- 新文件 `lab/LabHook.java`：读门控+配置 `{mode:create|join, roomCode, password, maxRunSec, expect}`；END_CLIENT_TICK 内 `mc.screen instanceof TitleScreen` 触发；create=复制 CreateRoomScreen:560-594 核心序列（publishServer+createRoom），join=`startDualP2P(code, mc.getUser().getName(), pass, cb)`；maxRunSec 到点 `leaveRoomSync()`+`mc.stop()`
- `[VoxTest] event=...` 标记行：ConnectionState.transitionTo（状态机咽喉）+ 侦察缺口三处（信令连上 CM:14487、TURN path up CM:12089/11892、room_joined/created RM:820/386、left RM:880、mc_connected=ClientPlayConnectionEvents.JOIN）
- build.gradle：loom.runs 块接 `-Plab_run_dir/-Plab_username/-Plab_vmargs`（默认值=现行为，不改其他 27 模板）
- 完成后编译验证 BUILD SUCCESSFUL

## Workstream A：lab 基础设施 + 本地服务端
- servers/server-go：预编译 exe + 全新 DATA_DIR；一次性初始化脚本（白名单 ipdata.json → admin 密码 → data.json 写 relay_enabled=true + relay_nodes=[127.0.0.1:37000, 生成 key]）→ 起服 → 验证 /relay/list 可见节点
- servers/turn-node：go build TURN/GO（或预编译 exe）；config.json nodeKey 与信令一致 + heartbeatUrl；起服 → 验证 WS 在线
- 实例制备：模板副本 A(房主)/B(房客)，JDK 副本 jdkHost/jdkGuest（防火墙按程序路径区分实例）

## Workstream C：编排器 + 今晚场景矩阵
- labctl（python）：起停 server-go/TURN → 写实例配置 → `gradlew runClient -Plab_*` 启动实例 → 日志 watcher（`[VoxTest]`/`[AutoRelay]`/`[ReliableUdp]`/`rescue`）→ 超时杀进程 → 归档 runs/<场景>/
- 场景（Windows 原生可跑）：
  - M1 直连成功（验证不误触发自动中继）
  - M2' 强制中继（防火墙：阻断 jdkGuest 的 UDP 远端端口 1-36999+37001-65535，只放 37000 → 打洞全死 → 兜底 45s → auto TURN → 进世界）
  - M4' F9 真实版（M2' 会话中 `kill` TURN 节点进程 → 观察 24s 熔断+救援重传+ICE restart+重连，日志+pcap 双证据）
  - M7 退出重进×2（无残留）
  - M11 回归冒烟（加入/ModSync/退出）
  - M6' kill 房主（预期=房客掉线，量化现状）
- 每场景产出：PASS/FAIL + 证据摘录 + pcap（抓包可选）

## 分工（3 子代理并行 + 我总装）
- 子代理1：Workstream B（mod 钩子+编译）
- 子代理2：Workstream A（lab 目录+服务端起停脚本+节点在线验证）
- 子代理3：Workstream C（labctl+场景脚本）
- 我：总装、跑全量矩阵、判读日志/pcap、修集成问题、出报告

## 边界与后续
- 今晚=Windows 原生矩阵；**真·对称NAT/丢包率/多实例玩家中继矩阵需 WSL2+netns（二期）**——WSL2 未安装且 C 盘仅 9.6GB，需要你亲自 `wsl --install`（管理员+重启），装好后我二轮补全
- [VoxTest] 钩子先只进参考模板（门控默认关闭、无行为差异）；1.2.0 发布重建 28 jar 时决定是否随锚点管线传播（惰性代码）
- 防火墙规则由 labctl 创建/删除、只针对 lab JDK 副本路径，跑完自动清理
- 1.2.0 的 28 jar 重建仍待你指令，与本实验室互不阻塞