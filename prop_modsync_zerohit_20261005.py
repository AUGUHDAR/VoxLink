# -*- coding: utf-8 -*-
# 2026-10-05 ModSync 坏缓存根治: MR 反查零命中不落盘 + 历史坏缓存读取防御 + jarCount 元数据
import io, os, re, sys

ROOT = r"D:\桌面\voxlink"
TEMPLATES = []
for loader in ("fabric", "forge", "neoforge"):
    d = os.path.join(ROOT, loader)
    for v in sorted(os.listdir(d)):
        p = os.path.join(d, v, "src", "main", "java", "icu", "wuhui", "voxlink", "modsync", "ModSyncManifestService.java")
        if os.path.isfile(p):
            TEMPLATES.append((loader + "/" + v, p))

BASE = "fabric/1.20.2_1.20.6"

# 锚点1: ensureCache 构建路径(直接落盘) → 前插零命中守卫
A1 = re.compile(
    r"(JsonObject built = buildBothManifests\(stateHash\);)(\s*)(cachedStateHash = stateHash;\s*cachedManifests = built;\s*writeDiskCache\(stateHash, built\);\s*return built;)"
)
GUARD = [
    "// MR 反查零命中守卫：有 jar 但两档 mods 全空 ⇒ 大概率构建时 api.modrinth.com 整体不可达，",
    "// 绝不落盘/进内存——否则坏清单被 stateHash 永久复用，房客永远\"无法自动下载\"。",
    "// 不缓存 ⇒ 下一次 mods_request 现场重试构建，网络恢复即自愈。",
    "int jarCount = built.has(\"jarCount\") && built.get(\"jarCount\").isJsonPrimitive()",
    "   ? built.get(\"jarCount\").getAsInt() : 0;",
    "if (jarCount > 0",
    "   && built.getAsJsonObject(\"required\").getAsJsonArray(\"mods\").size() == 0",
    "   && built.getAsJsonObject(\"all\").getAsJsonArray(\"mods\").size() == 0) {",
    "   ModSyncLog.warn(\"MR zero-hit for {} jars, skip caching (modrinth unreachable?), rebuild on next request\", jarCount);",
    "   return built;",
    "}",
    "",
]

# 锚点2: unknownMods 挂载尾部 → 后加 jarCount
A2 = re.compile(
    r"(required\.add\(\"unknownMods\", trimmedUnknown\);\s*all\.add\(\"unknownMods\", trimmedUnknown\);)"
)

# 锚点3: readDiskCache 直接返回 → 防御版
A3 = re.compile(
    r"(if \(root\.has\(\"required\"\) && root\.get\(\"required\"\)\.isJsonObject\(\)\s*&& root\.has\(\"all\"\) && root\.get\(\"all\"\)\.isJsonObject\(\)\)\s*\{)(\s*)(return root;)"
)
DEFENSE = [
    "// 历史坏缓存防御：两档 mods 全空=当年 MR 不可达时落盘的残废清单（jarCount 守卫",
    "// 上线前写入）。丢弃重建：网络已恢复则直接出好清单，仍不可达则构建侧守卫不落盘。",
    "if (root.getAsJsonObject(\"required\").getAsJsonArray(\"mods\").size() == 0",
    "   && root.getAsJsonObject(\"all\").getAsJsonArray(\"mods\").size() == 0) {",
    "   ModSyncLog.warn(\"disk cache has empty mods (legacy zero-hit?), discard and rebuild\");",
    "   return null;",
    "}",
    "",
]

def build(lines, lead, nl):
    return nl.join(lead + x for x in lines)

changed, skipped, failed = [], [], []
for name, p in TEMPLATES:
    with io.open(p, encoding="utf-8", newline="") as fh:
        s = fh.read()
    if name == BASE:
        skipped.append((name, "baseline"))
        continue
    if "MR zero-hit" in s:
        skipped.append((name, "already-fixed"))
        continue
    nl = "\r\n" if "\r\n" in s else "\n"
    m1 = A1.search(s)
    m2 = A2.search(s)
    m3 = A3.search(s)
    bad = []
    if not m1: bad.append("A1")
    if not m2: bad.append("A2")
    if not m3: bad.append("A3")
    if bad:
        failed.append((name, ",".join(bad)))
        continue
    # 从后往前替换免偏移: A3 > A2 > A1
    lead3 = m3.group(2).lstrip("\r\n")
    ins3 = nl + build(DEFENSE, lead3, nl) + nl + lead3
    s = s[: m3.start(2)] + ins3 + s[m3.end(2):]

    m2 = A2.search(s)
    lead2 = s[s.rfind("\n", 0, m2.start()) + 1: m2.start()]
    add2 = nl + lead2 + "out.addProperty(\"jarCount\", jars.size());"
    s = s[: m2.end(1)] + add2 + s[m2.end(1):]

    m1 = A1.search(s)
    lead1 = m1.group(2).lstrip("\r\n")
    ins1 = nl + build(GUARD, lead1, nl) + nl + lead1
    s = s[: m1.start(2)] + ins1 + s[m1.start(2):][len(m1.group(2)):] if False else s[: m1.start(2)] + ins1 + s[m1.end(2):]

    with io.open(p, "w", encoding="utf-8", newline="") as fh:
        fh.write(s)
    changed.append(name)

print("CHANGED", len(changed))
for c in changed: print("  +", c)
print("SKIPPED", len(skipped))
for c, r in skipped: print("  =", c, r)
print("FAILED", len(failed))
for c, r in failed: print("  !", c, r)
sys.exit(1 if failed else 0)
