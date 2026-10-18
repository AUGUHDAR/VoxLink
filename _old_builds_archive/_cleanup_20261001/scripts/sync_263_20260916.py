# -*- coding: utf-8 -*-
"""26.3 适配首轮同步:
1. fabric/26.3 + neoforge/26.3 工具链配置(从26.2拷贝后改版本)
2. 全模板 VoxLinkConstants 增加 26.3->777 协议映射
3. fabric/26.2 范围收紧 <=26.99 -> <26.3 (独立JAR前提)
锚点替换 + count==1 断言, 二进制模式保BOM/行尾
"""
import os, re, sys

BASE = os.path.dirname(os.path.abspath(__file__))

def patch(path, pairs, regex_pairs=()):
    p = os.path.join(BASE, path)
    with open(p, 'rb') as f:
        data = f.read()
    changed = []
    for old, new in pairs:
        n = data.count(old)
        assert n == 1, f"{path}: anchor x{n}: {old!r}"
        data = data.replace(old, new, 1)
        changed.append(old.decode())
    for pat, rep in regex_pairs:
        ms = re.findall(pat, data)
        assert len(ms) == 1, f"{path}: regex x{len(ms)}"
        data = re.sub(pat, rep, data, count=1)
        changed.append(pat.decode())
    with open(p, 'wb') as f:
        f.write(data)
    print(f"[OK] {path}: {len(changed)} 处")

# ---------- 1. fabric/26.3 ----------
patch('fabric/26.3/gradle.properties', [
    (b'mod_version=1.1.6-26.2', b'mod_version=1.1.6-26.3'),
    (b'minecraft_version=26.2', b'minecraft_version=26.3'),
    (b'fabric_loader_version=0.19.3', b'fabric_loader_version=0.19.5'),
    (b'fabric_version=0.154.2+26.2', b'fabric_version=0.160.6+26.3'),
    (b'minecraft_version_range=>=26.2 <=26.99', b'minecraft_version_range=>=26.3 <=26.99'),
])
patch('fabric/26.3/settings.gradle', [
    (b'rootProject.name = "voxlink-26.2"', b'rootProject.name = "voxlink-26.3"'),
])

# ---------- 2. neoforge/26.3 ----------
patch('neoforge/26.3/gradle.properties', [
    (b'mod_version=1.1.6-26.2', b'mod_version=1.1.6-26.3'),
    (b'minecraft_version=26.2', b'minecraft_version=26.3'),
    (b'neo_version=26.2.0.40-beta', b'neo_version=26.3.0.1-beta'),
    (b'minecraft_version_range=[26.2,26.3)', b'minecraft_version_range=[26.3,26.4)'),
    (b'neo_version_range=[26.2,)', b'neo_version_range=[26.3,)'),
])
patch('neoforge/26.3/settings.gradle', [
    (b'rootProject.name = "voxlink-neoforge-26.2"', b'rootProject.name = "voxlink-neoforge-26.3"'),
])

# ---------- 3. fabric/26.2 范围收紧(独立JAR) ----------
patch('fabric/26.2/gradle.properties', [
    (b'minecraft_version_range=>=26.2 <=26.99', b'minecraft_version_range=>=26.2 <26.3'),
])

# ---------- 4. 全模板协议映射 26.3 -> 777 ----------
VC = 'src/main/java/icu/wuhui/voxlink/VoxLinkConstants.java'
targets = []
for loader in ('fabric', 'neoforge', 'forge'):
    ldir = os.path.join(BASE, loader)
    for d in sorted(os.listdir(ldir)):
        f = os.path.join(ldir, d, VC)
        if os.path.isfile(f):
            targets.append(f)

pat = re.compile(rb'([ \t]*)case "26\.2" -> 776;(\r?\n)')
ok = skip = 0
for f in targets:
    with open(f, 'rb') as fh:
        data = fh.read()
    if b'case "26.3" -> 777;' in data:
        skip += 1
        continue
    m = pat.search(data)
    assert m, f"no anchor in {f}"
    assert len(pat.findall(data)) == 1, f"anchor x{len(pat.findall(data))} in {f}"
    ins = m.group(1) + b'case "26.3" -> 777;' + m.group(2)
    data = data[:m.end()] + ins + data[m.end():]
    with open(f, 'wb') as fh:
        fh.write(data)
    ok += 1
    print(f"[OK] {os.path.relpath(f, BASE)}")
print(f"协议映射: 新增 {ok}, 已有跳过 {skip}, 共 {len(targets)} 模板")
print("DONE")
