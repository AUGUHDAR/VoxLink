# -*- coding: utf-8 -*-
"""
TURN round2 传播（2026-08-31 第二轮：竞态修复 + 玩家中继热备 + TURN死亡自愈）
前提：sync_turn_20260831.py（round1）已在 25 模板落地且各模板 TURN 块与基准逐字一致。

策略：
  Step1  network 四文件整拷（round1 已验证跨模板一致，仅基准在 round2 加了 sendKeepaliveFrame）
  Step2  ConnectionManager：
         a) TURN 方法块整体替换（round1 块文本从参照模板 fabric/1.20_1.20.1 反向提取，
            round2 块从工作区基准提取，天然逐字一致）
         b) 字段块锚点：turnSwitchedToP2p 行 → 追加 round2 新字段
  Step3  RoomManager：turn_bg_punch case 块 → 追加 turn_stby case
  Step4  lang 13×24 追加 voxlink.turn.standby（基准已手工加）
"""
import io, os

ROOT = os.path.dirname(os.path.abspath(__file__))
REL = "src/main/java/icu/wuhui/voxlink"
LANG_REL = "src/main/resources/assets/voxlink/lang"
BASE = "fabric/1.20.2_1.20.6"
REF = "fabric/1.20_1.20.1"  # round1 已同步、round2 未动的参照模板

TEMPLATES = []
for loader in ("fabric", "forge", "neoforge"):
    d = os.path.join(ROOT, loader)
    for name in sorted(os.listdir(d)):
        p = os.path.join(d, name)
        if os.path.isdir(p) and os.path.exists(os.path.join(p, "build.gradle")):
            TEMPLATES.append(loader + "/" + name)
assert len(TEMPLATES) == 25, "expect 25 templates"

BLOCK_START = "   // ================= TURN 中继状态机（发起方=房客；房主全自动配合，无 UI 无选择权） ================="
BLOCK_END = "   public void handleRelaySetup(String from, JsonObject data) {"

def read_bytes(fp):
    with io.open(fp, "rb") as f:
        return f.read()

def write_bytes(fp, data):
    with io.open(fp, "wb") as f:
        f.write(data)

def detect_eol(data):
    crlf = data.count(b"\r\n")
    lf = data.count(b"\n") - crlf
    return b"\r\n" if crlf >= lf and crlf > 0 else b"\n"

def to_lf(b):
    return b.decode("utf-8").replace("\r\n", "\n")

def extract_block(fp, start_marker, end_marker):
    text = to_lf(read_bytes(fp))
    s = text.index(start_marker)
    e = text.index(end_marker, s)
    return text[s:e]

def replace_with_eol(fp, old_lf, new_lf, label):
    data = read_bytes(fp)
    eol = detect_eol(data)
    text = to_lf(data)
    if new_lf in text:
        return "already"
    cnt = text.count(old_lf)
    if cnt == 0:
        print("  SKIP(anchor missing) %s @ %s" % (label, fp))
        return "skip"
    assert cnt == 1, "anchor not unique (%d) %s @ %s" % (cnt, label, fp)
    out = text.replace(old_lf, new_lf, 1)
    if eol == b"\r\n":
        out = out.replace("\n", "\r\n")
    write_bytes(fp, out.encode("utf-8"))
    return "applied"

def copy_plain(rel, label):
    src = os.path.join(ROOT, BASE, rel)
    n = 0
    for t in TEMPLATES:
        if t == BASE:
            continue
        dst = os.path.join(ROOT, t, rel)
        eol = detect_eol(read_bytes(dst))
        text = to_lf(read_bytes(src))
        if eol == b"\r\n":
            text = text.replace("\n", "\r\n")
        write_bytes(dst, text.encode("utf-8"))
        n += 1
    print("  %s: copied %d/24" % (label, n))

# ---- round2 内容 ----

CM_FIELD_OLD = "   private volatile boolean turnSwitchedToP2p = false;"
CM_FIELD_NEW = """   private volatile boolean turnSwitchedToP2p = false;
   /** TURN 保活定时任务（teardown 必须取消，防任务泄漏）。 */
   private volatile ScheduledFuture<?> turnKeepaliveTask = null;
   /** 后台监视器 tick 计数（30s/次；奇数次触发玩家中继相位 = 60s）。 */
   private final AtomicInteger turnBgTicks = new AtomicInteger(0);
   /**
    * 玩家中继热备（TURN 模式下后台静默建立的 G1 备用通路）：
    * 不建桥、不动连接状态；TURN 死亡时自动转正 + 程序化重连，把"手动退出重进"变成几秒自愈。
    */
   private volatile ReliableUdpTransport turnHotstandbyTransport = null;"""

RM_CASE_OLD = """            case "turn_alloc":
               this.connectionManager.handleTurnAlloc(from, data);
               break;
            case "turn_ready":
               this.connectionManager.handleTurnReady(from, data);
               break;
            case "turn_bg_punch":
               this.connectionManager.handleTurnBgPunch(from, data);
               break;"""
RM_CASE_NEW = RM_CASE_OLD + """
            case "turn_stby":
               this.connectionManager.handleTurnStby(from, data);
               break;"""

# handleRelayNotify 的 TURN 热备分支（原始代码锚点，handleRelayNotify 在 TURN 方法块之外需单独处理）
HRN_OLD = """            if (relayIp != null && relayPort > 0) {

               VoxLinkMod.LOGGER.info("[Relay] Received relay_notify, punch to Cone {}:{}", relayIp, relayPort);"""

HRN_NEW = """            if (relayIp != null && relayPort > 0) {

               // TURN 已在承载：玩家中继仅作热备——punch 通中继玩家后只存不用，
               // 不进入下方建桥流程（防止与 TURN 桥双桥冲突）
               if (this.isTurnActive() && this.turnHotstandbyTransport == null) {
                  this.establishHotstandby(state, relayIp, relayPort);
                  return;
               }

               VoxLinkMod.LOGGER.info("[Relay] Received relay_notify, punch to Cone {}:{}", relayIp, relayPort);"""

STANDBY_I18N = {
    "zh_cn": "该房客已建立玩家中继备用线路", "zh_tw": "該房客已建立玩家中繼備用線路", "zh_hk": "該房客已建立玩家中繼備用線路", "lzh": "該房客已立玩家中繼備用之線",
    "en_us": "A guest established a player-relay standby link", "ja_jp": "参加者がプレイヤー中継の予備経路を確立しました", "ko_kr": "참가자가 플레이어 중비 경로를 설정했습니다",
    "ru_ru": "Гость создал резервный маршрут через игрока", "de_de": "Ein Gast hat eine Spieler-Relais-Reserveleitung aufgebaut", "fr_fr": "Un invité a établi une liaison de relais de secours",
    "es_es": "Un invitado estableció un enlace de relé de respaldo", "pt_br": "Um convidado estabeleceu enlace de retransmissão de reserva", "ar_sa": "أنشأ أحد الضيوف مسار ترحيل احتياطي"
}

def main():
    print("== Step1 copy network files ==")
    for rel in (REL + "/network/UdpPath.java", REL + "/network/TurnRelayClient.java",
                REL + "/network/ReliableUdpTransport.java", REL + "/network/SignalingClient.java"):
        copy_plain(rel, os.path.basename(rel))

    print("== Step2 ConnectionManager ==")
    # round1 块（参照模板）与 round2 块（工作区基准）提取
    r1_block = extract_block(os.path.join(ROOT, REF, REL, "room/ConnectionManager.java"), BLOCK_START, BLOCK_END)
    r2_block = extract_block(os.path.join(ROOT, BASE, REL, "room/ConnectionManager.java"), BLOCK_START, BLOCK_END)
    assert r1_block != r2_block, "round2 block should differ from round1"
    cm_rel = REL + "/room/ConnectionManager.java"
    for t in TEMPLATES:
        fp = os.path.join(ROOT, t, cm_rel)
        if t == BASE:
            continue
        replace_with_eol(fp, r1_block, r2_block, "cm-block")
        replace_with_eol(fp, CM_FIELD_OLD, CM_FIELD_NEW, "cm-field")

    print("== Step3 RoomManager ==")
    rm_rel = REL + "/room/RoomManager.java"
    for t in TEMPLATES:
        if t == BASE:
            continue
        replace_with_eol(os.path.join(ROOT, t, rm_rel), RM_CASE_OLD, RM_CASE_NEW, "rm-stby")

    print("== Step3b handleRelayNotify standby branch ==")
    cm_rel2 = REL + "/room/ConnectionManager.java"
    for t in TEMPLATES:
        if t == BASE:
            continue
        replace_with_eol(os.path.join(ROOT, t, cm_rel2), HRN_OLD, HRN_NEW, "hrn-standby")

    print("== Step4 lang ==")
    for t in TEMPLATES:
        if t == BASE:
            continue
        for lang, text in STANDBY_I18N.items():
            fp = os.path.join(ROOT, t, LANG_REL, lang + ".json")
            data = read_bytes(fp)
            txt = to_lf(data)
            if "voxlink.turn.standby" in txt:
                continue
            body = txt.rstrip()
            assert body.endswith("}"), fp
            inner = body[:-1].rstrip()
            sep = "" if inner.endswith(",") else ","
            nb = inner + sep + "\n    \"%s\": \"%s\"\n}\n" % ("voxlink.turn.standby", text)
            eol = detect_eol(data)
            if eol == b"\r\n":
                nb = nb.replace("\n", "\r\n")
            write_bytes(fp, nb.encode("utf-8"))

    print("DONE round2")

if __name__ == "__main__":
    main()
