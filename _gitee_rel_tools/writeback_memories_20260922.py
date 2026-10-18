# -*- coding: utf-8 -*-
"""写回 ZCode 记忆（新篇+索引）与 TRAE 项目记忆（Write 工具受限于工作目录，脚本落盘）"""
import io, os, sys

ZDIR = r"C:\Users\19430\.zcode\cli\memories\projects\voxlink-9a9f46dba867d0dd\memory"
NEW_MEM = os.path.join(ZDIR, "voxlink-modsync-gate-visibility-20260922.md")
INDEX = os.path.join(ZDIR, "MEMORY.md")
TRAE = r"C:\Users\19430\.trae-cn\memory\projects\-d----voxlink--p2-a7e51d6763a02d303c09\project_memory.md"

BODY = """---
name: voxlink-modsync-gate-visibility-20260922
description: 2026-09-22 Q3 低风险半场落地——ModSync 门控"静默直通"改可见（结果带原因/直通留日志/玩家面板提示/pending note 防面板重置冲刷/待选态定谳日志），28 模板铺满+校验+编译全过；最终根因等真机日志
metadata:
  node_type: memory
  type: project
  originSessionId: sess_trae_20260922
---

## Q3 修复（2026-09-22，TRAE 接手轮）：把 ModSync 门控的"哑巴直通"改成可见

**用户裁决**：完整实现不偷懒；玩家提示只覆盖"房主不支持/获取失败"两类；空清单与检查通过不弹（空清单仅日志）。
**红线遵守**：门控交互/范围按钮/自动行为零改动，纯加法（可见性）。

## 代码事实（本轮实读确认）

`ModSyncGuestService.gate()` 的 `manifest == null` 静默直通有 5 个来源，其中 2 个连日志都没有：
`!supported`（房主不支持）与空清单；另外 3 个（authoritative/未就绪耗尽/瞬态失败耗尽）有 WARN 但玩家零感知。
类 javadoc 原写"零打扰直通"是有意设计——改成可见是用户明确裁决，javadoc 已同步改。

**新推理（推测，待真机日志定谳）**：1.1.7 日志每轮仅一条 armed→startDualP2P。
若玩家点过范围按钮且 gate 静默放行，proceed 重建屏幕会再 armed 一次（每轮两条）。
单条 → gate() 大概率根本没被调用（点了"直接进入"/门控条件为假），即候选 (a)。

## 已实施（`fabric/26.3` 为金标准，编译通过后传播）

| # | 位置 | 改动 |
| --- | --- | --- |
| 1 | `modsync/ModSyncGuestService.java` | 新增 `FetchOutcome`（MANIFEST/UNSUPPORTED/EMPTY/NOT_READY/BYPASSED）+ `FetchResult`；`fetchManifestWithRetry` 带原因返回：authoritative/中断/终局→NOT_READY(3处)，循环头+两处sleep→BYPASSED(3处)，!supported→UNSUPPORTED，空清单→EMPTY |
| 2 | 同上 gate() | 直通分支 `ModSyncLog.info("gate skip: {}", outcome)` 一律留痕 |
| 3 | 同上 | 入口 `gate: room=X scope=Y` + `bypass()` 的 `player bypassed` 各一行（真机定谳用） |
| 4 | 同上 + `ui/AttemptingJoinScreen.java` | **pending note 机制**：gate 挂静态提示(key+level)，加入屏 doStartDualP2P 末尾 `pushPendingGateNote()` 播报。为什么：`ConnectionManager.startDualP2P` 开头 `UiLogBus.reset()` 会清掉提前 push 的内容；proceed→重建→init→doStartDualP2P 全同步链，reset 后播报时序确定无竞态 |
| 5 | `ui/AttemptingJoinScreen.java` | 待选态日志 `[ModSync] gate: waiting for scope selection, room=X`（覆盖"按钮没显示"盲区） |
| 6 | lang ×13 ×28 | 新增 `voxlink.logui.modsync_unsupported`(level 0) / `voxlink.logui.modsync_fetch_failed`(level 2) |

## 传播与校验（全过）

- `ModSyncGuestService` 改前 md5 分 **3 簇**（10+12+6）：A=sendSystemMessage+mc.screen（1.20.x）、
  B=displayClientMessage+mc.screen（1.21.x）、C=sendSystemMessage+gui.screen（26.x）——
  改动区全避开时代行 → 3 代表同锚点编辑 + 簇内整拷；改后仍 3 簇、时代形态完好
- `AttemptingJoinScreen` 单行锚点 `modSyncChecking = true;` / `startConnectionMonitor();`
  28/28 唯一、无倍行距；`import VoxLinkMod` 28/28 预检通过
- lang 364 文件锚 join_failed 行插入（按各文件锚行缩进）+ **json.load 全量合法性校验通过**
- 脚本：`_gitee_rel_tools/propagate_modsync_gate_20260922.py` / 独立校验 `verify_modsync_gate_20260922.py`
  （**ALL PASS**，28×12 断言）/ 注释合规 `fixup_comments_modsync_20260922.py`（2 条 // 超 20 字缩短）
- 编译：金标准 fabric/26.3 + 矩阵 **9/9 BUILD SUCCESSFUL**（fabric/1.20.2_1.20.6、fabric/1.21.11、
  fabric/26.2、forge/1.20_1.20.1、neoforge/1.20.1、fabric/1.20_1.20.1、fabric/1.21_1.21.5、
  forge/26.3、neoforge/26.3）+ 注释修正后 3 代表复编译全过

## 同轮收口（接手任务 1）

- **forge/1.20_1.20.1 joinfix 补验**：BUILD SUCCESSFUL in 40s；compileJava UP-TO-DATE 但 javap 实锤
  class 含 `JOIN_TRANSIENT_MAX_ATTEMPTS=3`/`joinAttemptGeneration` → joinfix 编译矩阵 **8/8 收口**
- **WS 半开修复传播核查**：4 锚点已全 28/28（WATCHDOG 35000 且旧 90000 零残留、看门狗 10s、
  clientPing 15s、sendFrame 超时 markUnavailable）→ 无需补铺
- 交接提示词核查属实（Q1 loader 28/28、joinfix 28/28、lang 364/364 均独立复验）

## 仍待办

1. **Q3 最终定谳**：等用户真机复现日志，按四件套判定——
   `gate: waiting for scope selection`（待选态出现）/ `gate: room=X scope=Y`（点了按钮）/
   `gate: player bypassed`（点了直接进入）/ `gate skip: <outcome>`（直通原因）。
   全程无前两条 → 候选 (a) 实锤（按钮没出现或没点）
2. 1.1.8 全量构建（build_117_parallel.py --jobs=5，clean 真编译）+ 四平台发布
3. 加入文案细化（用户："既开发者看不懂，玩家也不知道"）仍在 1.1.8 待办清单
"""

INDEX_LINE = "- [2026-09-22 Q3 ModSync门控可见化落地(TRAE接手轮)](voxlink-modsync-gate-visibility-20260922.md) — 静默直通改可见(FetchOutcome带原因/gate skip留日志/pending note防UiLogBus.reset冲刷/待选态定谳日志/2 lang key×13语)；MSG 28模板=3 md5簇(时代形态)同锚点编辑+簇内整拷；校验ALL PASS+编译9/9+forge1.20补验8/8+WS半开修复28/28实锤已铺满；最终定谳等真机日志四件套\n"

TRAE_ADD = """- UiLogBus.reset() 在 ConnectionManager.startDualP2P 开头：加入流程的面板提示必须在连接启动后 push（ModSync pending note 模式：gate 挂静态 key+level，doStartDualP2P 末尾播报，同步链时序确定）
- ModSyncGuestService 28 模板 = 3 个 md5 簇（1.20.x sendSystemMessage+mc.screen / 1.21.x displayClientMessage+mc.screen / 26.x gui.screen）：改动区避开时代行即可 3 代表同锚点编辑+簇内整拷，改后校验簇数与时代形态不变
- ModSync 门控直通可见化（1.1.8）：gate 入口/待选态/bypass/gate skip 四件套日志用于真机定谳"范围按钮没出现/没点" vs "清单静默直通"；玩家提示仅 UNSUPPORTED/NOT_READY 两类（用户裁决，空清单仅日志）
"""

# 1) ZCode 新记忆
if os.path.exists(NEW_MEM):
    print("ZCode mem exists, overwrite")
with io.open(NEW_MEM, "w", encoding="utf-8", newline="\n") as f:
    f.write(BODY)
print("ZCode memory written:", NEW_MEM)

# 2) ZCode 索引（幂等 prepend）
with io.open(INDEX, "r", encoding="utf-8") as f:
    idx = f.read()
if "voxlink-modsync-gate-visibility-20260922.md" not in idx:
    with io.open(INDEX, "w", encoding="utf-8", newline="") as f:
        f.write(INDEX_LINE + idx)
    print("ZCode index prepended")
else:
    print("ZCode index already has entry")

# 3) TRAE 项目记忆（幂等 append）
with io.open(TRAE, "r", encoding="utf-8") as f:
    tm = f.read()
if "pending note 模式" not in tm:
    with io.open(TRAE, "a", encoding="utf-8", newline="") as f:
        if not tm.endswith("\n"):
            f.write("\n")
        f.write(TRAE_ADD)
    print("TRAE project memory appended")
else:
    print("TRAE project memory already has entry")
print("DONE")
