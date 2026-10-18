# -*- coding: utf-8 -*-
"""补记用户裁决：26.x必须Java25运行时天坑 + 禁止热修已发布版本 + mixins.json JAVA_25 是故意的"""
import io, os

ZDIR = r"C:\Users\19430\.zcode\cli\memories\projects\voxlink-9a9f46dba867d0dd\memory"
TRAE = r"C:\Users\19430\.trae-cn\memory\projects\-d----voxlink--p2-a7e51d6763a02d303c09\project_memory.md"

BODY = """---
name: voxlink-user-rulings-jvm-and-nohotfix-20260922
description: 2026-09-22 用户亲口裁决补录——26.2+必须运行在Java25否则无日志崩溃(曾排查许久的大天坑)；已发布版本禁止热修(玩家只认新版本推送)；mixins.json forge/26.x=JAVA_25 是用户故意改的；附 mixin-0.8.7 字节码事实与待裁决事项
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_trae_20260922
---

## 用户裁决补录（2026-09-22，此前漏记，用户明确批评"记忆有点离谱"）

1. **MC 26.2+ 必须运行在 Java 25**，否则**崩溃且连日志都不留**。用户曾踩此大天坑、
   排查很久才定位。排障 26.x 任何问题时**第一步先核对启动器实际用的 JDK**。
2. **已发布版本禁止热修/替换/撤回**：玩家只有在新版本发布时才收到更新推送，
   热修已发布的 jar 玩家根本拿不到。坏 jar 留在线上，修复随下个正式版本（1.1.8）发布。
   → 1.1.7 线上的 forge-1.20.6/26.1/26.2/26.3 四个 jar **故意不动，等 1.1.8**。
3. **mixins.json forge/26.x = JAVA_25 是用户 09-21 21:58 故意改的**并重建重传，
   理由=26.2+ 运行时是 Java 25。不是外力回滚，不要再当"被 revert 的修复"处理。

## mixins.json 事件完整还原（供 1.1.8 决策）

- Forge 各版本实际 mixin（官方 installer version.json 直读）：1.20.x→0.8.5（枚举上限 JAVA_18）；
  1.21.5+/26.1/26.2/26.3→0.8.7（枚举上限 **JAVA_21**，mixin jar 字节码实锤）
- fabric/neoforge 26.x 用 sponge-mixin 0.16.5+/0.17（上限 JAVA_25），其 JAVA_25 配置自 09-16 运行无恙
- 09-21 21:40 改 JAVA_21 重建 → 21:42 实测 forge/26.3 启动成功（日志 "Compatibility level set to JAVA_21"）
- 21:47 agent 的 unify_mixin_level.py 全统一 JAVA_17；21:58 用户改回 forge/26.x=JAVA_25 + 重建 + 重传
- **注意**："JAVA_25 not recognised 崩溃"的原始引用语只出自当时 agent 的脚本注释（该 agent 被用户评为不可靠），
  原始崩溃未留 crash log（eventlog 0 字节），可能实为"26.x 用错 JDK 无日志崩"天坑。
  **JAVA_25 配置在 mixin-0.8.7 上到底崩不崩，未经实测**（voxlink.mixins.json required:false 或可降级为跳过配置）。
- 待裁决：1.1.8 的 forge/26.x mixins.json 取值（实测定音 / 保持 25 / 21 / 17）
"""

INDEX_LINE = "- [2026-09-22 用户裁决补录:Java25天坑+禁热修+mixins故意25](voxlink-user-rulings-jvm-and-nohotfix-20260922.md) — 26.2+必须Java25否则无日志崩(先查JDK)/已发布版本禁热修(玩家只认新版本推送,1.1.7四个坏jar故意等1.1.8)/forge26.x mixins=JAVA_25是用户故意改的;附0.8.7上限JAVA_21字节码事实与待实测事项\n"

NEW_MEM = os.path.join(ZDIR, "voxlink-user-rulings-jvm-and-nohotfix-20260922.md")
INDEX = os.path.join(ZDIR, "MEMORY.md")

with io.open(NEW_MEM, "w", encoding="utf-8", newline="\n") as f:
    f.write(BODY)
print("ZCode memory written")

with io.open(INDEX, "r", encoding="utf-8") as f:
    idx = f.read()
if "voxlink-user-rulings-jvm-and-nohotfix" not in idx:
    with io.open(INDEX, "w", encoding="utf-8", newline="") as f:
        f.write(INDEX_LINE + idx)
    print("ZCode index prepended")
else:
    print("ZCode index already has entry")

TRAE_ADD = """- 用户裁决：MC 26.2+ 必须运行在 Java 25，否则崩溃且无日志（大天坑，排障 26.x 第一步先核对启动器 JDK）
- 用户裁决：已发布版本禁止热修/替换/撤回——玩家只认新版本推送；1.1.7 线上坏 jar 故意留待 1.1.8 修复
- forge/26.x mixins.json=JAVA_25 是用户 09-21 21:58 故意改的（26.x 运行时 Java 25）；mixin-0.8.7 枚举上限 JAVA_21（字节码实锤）、JAVA_21 实测启动通过；JAVA_25 在 0.8.7 是否真崩未经实测，1.1.8 前需用户裁决或实测定音
"""
with io.open(TRAE, "r", encoding="utf-8") as f:
    tm = f.read()
if "禁止热修" not in tm:
    with io.open(TRAE, "a", encoding="utf-8", newline="") as f:
        if not tm.endswith("\n"):
            f.write("\n")
        f.write(TRAE_ADD)
    print("TRAE project memory appended")
else:
    print("TRAE already has entry")
print("DONE")
