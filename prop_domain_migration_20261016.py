"""域名迁移 20261016：p2p.wuhui.icu -> link.voxdev.top，并在 VoxLinkConfig.validate()
前插一条"落盘地址仍是 wuhui.icu 就改写为内置默认"的迁移规则。只动 src，不动 build/ 产物。
"""
import os
import re
import sys
import json

BASE = r"d:\桌面\voxlink"
LOADERS = ["fabric", "forge", "neoforge"]
OLD = "p2p.wuhui.icu"
NEW = "link.voxdev.top"
MARKER = 'contains("wuhui.icu")'
ANCHOR = "// 安全修复：明文"

V = "src/main/java/icu/wuhui/voxlink"
JAVA_FILES = [
    V + "/config/VoxLinkConfig.java",
    V + "/network/TicketClient.java",
    V + "/network/FeedbackUploader.java",
    V + "/ui/RelatedLinksScreen.java",
]

APPLY = "--apply" in sys.argv


def templates():
    for loader in LOADERS:
        for ver in sorted(os.listdir(os.path.join(BASE, loader))):
            d = os.path.join(BASE, loader, ver)
            if os.path.isdir(d):
                yield loader, ver, d


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def write(p, s):
    with open(p, "w", encoding="utf-8", newline="") as f:
        f.write(s)


def swap(path, rel, report):
    """把裸域名替换成新域名；返回替换次数。语言 JSON 额外做结构校验。"""
    if not os.path.isfile(path):
        return 0
    src = read(path)
    n = src.count(OLD)
    if n == 0:
        return 0
    if not APPLY:
        report.append((rel, "swap x%d" % n))
        return n
    out = src.replace(OLD, NEW)
    if path.endswith(".json") and "lang" in path:
        try:
            json.loads(out)
        except Exception as e:
            raise SystemExit("ABORT %s 改后 JSON 非法: %s" % (rel, e))
    write(path, out)
    report.append((rel, "swap x%d" % n))
    return n


def insert_migration(path, rel, report):
    src = read(path)
    if MARKER in src:
        report.append((rel, "migrate block 已存在，跳过"))
        return False
    lines = src.split("\n")
    hits = [i for i, l in enumerate(lines) if ANCHOR in l]
    if len(hits) != 1:
        raise SystemExit("ABORT %s 锚点数=%d，不插入" % (rel, len(hits)))
    ai = hits[0]
    ind = re.match(r"[ \t]*", lines[ai]).group(0)
    body = ind
    for l in lines[ai + 1:ai + 6]:
        if l.strip().startswith("this.serverUrl ="):
            inner = re.match(r"[ \t]*", l).group(0)
            if len(inner) > len(ind):
                body = ind + inner[len(ind):]
            break
    block = [
        ind + u"// 域名迁移：旧域名落盘时改写为默认",
        ind + 'if (this.serverUrl != null && this.serverUrl.contains("wuhui.icu")) {',
        body + "LOGGER.warn(\"Deprecated serverUrl {} migrated to {}\", this.serverUrl, DEFAULT_SERVER_URL);",
        body + "this.serverUrl = DEFAULT_SERVER_URL;",
        ind + "}",
        "",
    ]
    if not APPLY:
        report.append((rel, "migrate block @L%d indent=%r body=%r" % (ai + 1, ind, body)))
        return True
    lines[ai:ai] = block
    write(path, "\n".join(lines))
    report.append((rel, "migrate block 已插入 @L%d" % (ai + 1)))
    return True


grand = {}
for loader, ver, d in templates():
    rel_t = "%s/%s" % (loader, ver)
    cnt = 0
    rows = []
    for rel in JAVA_FILES:
        cnt += swap(os.path.join(d, rel), rel.split("/")[-1], rows)
    for langdir in ("src/main/resources/assets/voxlink/lang",):
        p = os.path.join(d, langdir)
        if os.path.isdir(p):
            for fn in sorted(os.listdir(p)):
                if fn.endswith(".json"):
                    cnt += swap(os.path.join(p, fn), "lang/" + fn, rows)
    manifest = os.path.join(d, "src/main/resources/fabric.mod.json")
    cnt += swap(manifest, "fabric.mod.json", rows)
    cnt += swap(os.path.join(d, "src/main/resources/META-INF/mods.toml"), "mods.toml", rows)
    if insert_migration(os.path.join(d, JAVA_FILES[0]), "VoxLinkConfig.java", rows):
        pass
    grand[rel_t] = (cnt, rows)

tot = 0
print("%-26s %6s  %s" % ("模板", "命中", "明细"))
for t, (cnt, rows) in sorted(grand.items()):
    tot += cnt
    lang = sum(int(re.search(r"x(\d+)", r[1]).group(1)) for r in rows if r[0].startswith("lang/"))
    other = ["%s:%s" % (os.path.basename(r[0]), r[1]) for r in rows
             if not r[0].startswith("lang/") and "x" in r[1]]
    print("%-26s %6d  lang=%d  %s | %s" % (
        t, cnt, lang,
        " ".join(other),
        next((r[1] for r in rows if "migrate" in r[1]), "-")))
print("\n合计命中 %d 处 / %d 模板   模式=%s" % (tot, len(grand), "APPLY" if APPLY else "DRY-RUN"))
