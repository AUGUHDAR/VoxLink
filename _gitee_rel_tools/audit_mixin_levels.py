"""从本地所有 Mixin 实现 jar 中读出它们支持的 CompatibilityLevel 集合。

为什么不靠推测：Mixin 的 CompatibilityLevel 是枚举，写死在各实现里。
上游 Mixin（Forge 用）历史上停在 JAVA_21；Fabric 的 sponge-mixin 与 NeoForge 的
fork 更新，可能支持 JAVA_25。要让 28 个 jar 的 compatibilityLevel 都不越界，
就得知道每个加载器实际拿到的是哪个实现、上限多少。
"""
import os
import re
import zipfile

ROOTS = [
    r"D:\.gradle\caches\modules-2\files-2.1\org.spongepowered",
    r"D:\.gradle\caches\modules-2\files-2.1\net.fabricmc",
    r"D:\.gradle\caches\modules-2\files-2.1\net.neoforged",
    r"D:\.gradle\caches\minecraftforge",
    r"D:\.gradle\caches\forge_gradle",
    r"D:\.gradle\caches\neoformruntime",
    r"D:\.gradle\caches\fabric-loom",
    r"D:\桌面\voxlink\客户端测试\libraries\org\spongepowered",
    r"D:\桌面\voxlink\客户端测试\libraries\net\fabricmc",
    r"D:\桌面\voxlink\客户端测试\libraries\net\neoforged",
    r"D:\桌面\voxlink\客户端测试\libraries\cpw\mods",
]

LEVEL_RE = re.compile(rb"JAVA_\d+")


def find_mixin_jars():
    hits = []
    for root in ROOTS:
        if not os.path.isdir(root):
            continue
        for dirpath, _dirs, files in os.walk(root):
            if dirpath.count(os.sep) - root.count(os.sep) > 7:
                continue
            for f in files:
                if not f.endswith(".jar"):
                    continue
                if "sources" in f or "javadoc" in f:
                    continue
                if re.match(r"^(mixin|sponge-mixin)[-_]", f, re.I):
                    hits.append(os.path.join(dirpath, f))
    return sorted(set(hits))


def levels_of(jar):
    """从 jar 里 MixinEnvironment$CompatibilityLevel 读枚举常量"""
    try:
        with zipfile.ZipFile(jar) as z:
            target = None
            for n in z.namelist():
                if n.endswith("MixinEnvironment$CompatibilityLevel.class"):
                    target = n
                    break
            if target is None:
                return None
            data = z.read(target)
            return sorted({m.decode() for m in LEVEL_RE.findall(data)},
                          key=lambda s: int(s.split("_")[1]))
    except Exception as e:
        return f"(读取失败: {e})"


def ver_of(jar):
    m = re.search(r"(mixin|sponge-mixin)[-_]([\d.]+(?:[-+][\w.]+)?)", os.path.basename(jar), re.I)
    return m.group(2) if m else "?"


rows = []
for j in find_mixin_jars():
    lv = levels_of(j)
    rows.append((ver_of(j), os.path.basename(j), lv, len(j)))

print(f"发现 Mixin 实现 {len(rows)} 个\n")
print(f"{'版本':<24}{'jar':<46}{'支持的最高等级':<12}  全部等级")
print("-" * 120)
for ver, name, lv, _ in rows:
    if isinstance(lv, list) and lv:
        print(f"{ver:<24}{name[:44]:<46}{lv[-1]:<12}  {', '.join(lv)}")
    else:
        print(f"{ver:<24}{name[:44]:<46}{'(无枚举)':<12}  {lv}")
