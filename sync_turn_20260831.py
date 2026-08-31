# -*- coding: utf-8 -*-
"""
TURN 中继体系 25 模板传播脚本（2026-08-31）
依据：SPECS/turn-protocol-v1.md；基准模板 fabric/1.20.2_1.20.6 已手工落地并通过编译。

传播内容：
  Step1  整拷 4 个 network 文件（UdpPath/TurnRelayClient 新增；ReliableUdpTransport/SignalingClient
         跨模板归一化后与基准一致 → 直接覆盖，保留目标行尾风格）
  Step2  ConnectionManager.java 锚点×3（import / 字段块 / TURN 状态机方法块）
  Step3  RoomManager.java 锚点×3（getSignalingClient / turn 信号 case / cleanupTurnQuietly）
  Step4  AttemptingJoinScreen.java 锚点×4（字段 / init 右上角按钮 / onTurnButtonClicked / monitor 显隐）
  Step5  lang 13 文件 ×25 模板追加 4 个 voxlink.turn.* key（幂等）

工具函数沿用 sync_relay113 模式：LF 空间匹配 + count==1 断言 + 行尾还原 + 幂等短路。
"""
import io, os, subprocess, sys

ROOT = os.path.dirname(os.path.abspath(__file__))
REL = "src/main/java/icu/wuhui/voxlink"
LANG_REL = "src/main/resources/assets/voxlink/lang"
BASE = "fabric/1.20.2_1.20.6"

TEMPLATES = []
for loader in ("fabric", "forge", "neoforge"):
    d = os.path.join(ROOT, loader)
    for name in sorted(os.listdir(d)):
        p = os.path.join(d, name)
        if os.path.isdir(p) and os.path.exists(os.path.join(p, "build.gradle")):
            TEMPLATES.append(loader + "/" + name)
assert len(TEMPLATES) == 25, "expect 25 templates, got %d: %s" % (len(TEMPLATES), TEMPLATES)

# ---------- 工具 ----------

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

def replace_with_eol(fp, old_lf, new_lf, label):
    """锚点替换：count==1 断言；new 已存在则幂等跳过；返回 'applied'/'skip'/'already'"""
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

def copy_verified(rel_path, new_file, label):
    """Step1 整拷：验证目标与基准 HEAD 版本归一化一致（新文件免验）后覆盖。基准自身跳过。"""
    base_work = os.path.join(ROOT, BASE, rel_path)
    base_head = git_head_bytes(BASE + "/" + rel_path)
    applied = 0
    for t in TEMPLATES:
        if t == BASE:
            continue
        tp = os.path.join(ROOT, t, rel_path)
        if not os.path.exists(tp):
            if not new_file:
                print("  SKIP(missing) %s @ %s" % (label, t))
            else:
                _copy_with_eol(base_work, tp)
                applied += 1
            continue
        if base_head is not None:
            cur = to_lf(read_bytes(tp))
            ref = to_lf(base_head)
            if cur != ref:
                print("  SKIP(diverged) %s @ %s" % (label, t))
                continue
        _copy_with_eol(base_work, tp)
        applied += 1
    print("  %s: copied %d/24" % (label, applied))

def _copy_with_eol(src, dst):
    data = read_bytes(src)
    eol = detect_eol(read_bytes(dst)) if os.path.exists(dst) else b"\r\n"
    text = to_lf(data)
    if eol == b"\r\n":
        text = text.replace("\n", "\r\n")
    write_bytes(dst, text.encode("utf-8"))

def git_head_bytes(repo_rel):
    try:
        out = subprocess.check_output(
            ["git", "show", "HEAD:" + repo_rel], cwd=ROOT, stderr=subprocess.DEVNULL
        )
        return out
    except Exception:
        return None

# ---------- Step1 整拷清单 ----------

COPY_FILES = [
    (REL + "/network/UdpPath.java", True, "UdpPath"),
    (REL + "/network/TurnRelayClient.java", True, "TurnRelayClient"),
    (REL + "/network/ReliableUdpTransport.java", False, "ReliableUdpTransport"),
    (REL + "/network/SignalingClient.java", False, "SignalingClient"),
]

# ---------- Step2 ConnectionManager ----------

CM_IMPORT_OLD = "import icu.wuhui.voxlink.network.ReliableUdpTransport;"
CM_IMPORT_NEW = (
    "import icu.wuhui.voxlink.network.ReliableUdpTransport;\n"
    "\n"
    "import icu.wuhui.voxlink.network.TurnRelayClient;\n"
    "\n"
    "import icu.wuhui.voxlink.network.UdpPath;"
)

CM_FIELD_OLD = "   private volatile long lastHostNoticeAt = 0L;"
CM_FIELD_NEW = """   private volatile long lastHostNoticeAt = 0L;

   // ================= TURN 中继（协议契约：SPECS/turn-protocol-v1.md） =================
   /** 本端 TURN 会话；null=未启用。发起方(guest)=ROLE_GUEST，自动配合方(host)=ROLE_HOST。 */
   private volatile TurnRelayClient.TurnSession turnSession = null;
   /** TURN 通路对端标识：guest 侧恒为 "host"，host 侧为 joiner 的 clientId。 */
   private volatile String turnPeerId = null;
   /** TURN 数据面 transport（guest 侧建好后等 turn_ready 再 start）。 */
   private volatile ReliableUdpTransport turnTransport = null;
   /** TURN 全流程进行中（防重复触发 + 按钮置灰）。 */
   private volatile boolean turnInProgress = false;
   /** 后台 P2P 升级监视器（5 分钟窗口）任务句柄与截止时刻。 */
   private volatile ScheduledFuture<?> turnBgMonitorTask = null;
   private volatile long turnBgDeadlineMs = 0L;
   /** 切换观测任务句柄（次路径稳定性计数）。 */
   private volatile ScheduledFuture<?> turnSwitchWatchTask = null;
   private final AtomicInteger turnSwitchStreak = new AtomicInteger(0);
   private final AtomicBoolean turnBgPunchWon = new AtomicBoolean(false);
   /** 5 分钟窗口耗尽：本连接周期不再后台尝试 P2P，退出重进才重置。 */
   private volatile boolean turnP2pGivenUp = false;
   /** 平滑切换已完成（直连已接管、TURN 已释放）。 */
   private volatile boolean turnSwitchedToP2p = false;"""

def cm_methods_block():
    """从基准工作区提取已落地的 TURN 方法块（保证与基准逐字一致）。"""
    text = to_lf(read_bytes(os.path.join(ROOT, BASE, REL, "room/ConnectionManager.java")))
    start = text.index("   // ================= TURN 中继状态机（发起方=房客；房主全自动配合，无 UI 无选择权） =================")
    end = text.index("   public void handleRelaySetup(String from, JsonObject data) {", start)
    return text[start:end]

CM_METHODS_ANCHOR = "   public void handleRelaySetup(String from, JsonObject data) {"

# ---------- Step3 RoomManager ----------

RM_GETTER_ANCHOR = "   public RoomManager(SignalingClient signalingClient, TopologyClient topologyClient) {"
RM_GETTER_NEW = """   public SignalingClient getSignalingClient() {
      return this.signalingClient;
   }

   public RoomManager(SignalingClient signalingClient, TopologyClient topologyClient) {"""

RM_CASE_OLD = "            case \"relay_ready\":\n               this.connectionManager.handleRelayReady(from, data);\n               break;"
RM_CASE_NEW = RM_CASE_OLD + """
            case "turn_alloc":
               this.connectionManager.handleTurnAlloc(from, data);
               break;
            case "turn_ready":
               this.connectionManager.handleTurnReady(from, data);
               break;
            case "turn_bg_punch":
               this.connectionManager.handleTurnBgPunch(from, data);
               break;"""

RM_DISC_ANCHOR = "   private void handleDisconnect(String from, JsonObject data) {\n      VoxLinkMod.LOGGER.info(\"Peer disconnected: {}\", from);"
RM_DISC_NEW = "   private void handleDisconnect(String from, JsonObject data) {\n      VoxLinkMod.LOGGER.info(\"Peer disconnected: {}\", from);\n      this.connectionManager.cleanupTurnQuietly();"

# ---------- Step4 AttemptingJoinScreen ----------

AJ_FIELD_OLD = "   private volatile boolean lastManualRelayInProgress = false;"
AJ_FIELD_NEW = """   private volatile boolean lastManualRelayInProgress = false;
   /** TURN"使用中继"按钮当前显隐（monitor 线程计算，init 消费）。 */
   private volatile boolean turnButtonVisible = false;
   /** 服务端 TURN 开关缓存（打洞 ≥20s 时查询一次，30s 刷新）。 */
   private volatile boolean turnServerEnabled = false;
   private volatile boolean turnStatusChecked = false;
   private volatile long turnStatusCheckedAt = 0L;"""

AJ_INIT_ANCHOR = "      if (!this.joinApiDone) {\n         this.joinApiDone = true;"
AJ_INIT_NEW = """      // TURN"使用中继"：右上角小按钮。显隐由 monitor 线程按 20s 计时 + 服务端开关计算。
      if (!bridgeReady && this.active && this.turnButtonVisible) {
         this.addRenderableWidget(
            Button.builder(Component.translatable("voxlink.turn.use"), button -> this.onTurnButtonClicked())
               .bounds(this.width - 104, 4, 100, 20)
               .build()
         );
      }

      if (!this.joinApiDone) {
         this.joinApiDone = true;"""

AJ_METHOD_ANCHOR = "   private void startJoin() {"
AJ_METHOD_NEW = """   /** TURN"使用中继"：交由 ConnectionManager 完成测延迟/选节点/BIND 全流程；置 inProgress 后按钮自动消失。 */
   private void onTurnButtonClicked() {
      VoxLinkMod.getRoomManager().getConnectionManager().triggerTurnRelay();
      this.clearOurWidgets();
      this.init();
   }

   private void startJoin() {"""

AJ_MONITOR_ANCHOR = """                  boolean shouldShowRelay = AttemptingJoinScreen.this.active && VoxLinkMod.getRoomManager().getConnectionManager().canShowRelayButton();
                  if (shouldShowRelay != AttemptingJoinScreen.this.relayButtonVisible) {
                     mc.execute(() -> {
                        if (mc.screen == AttemptingJoinScreen.this) {
                           AttemptingJoinScreen.this.clearOurWidgets();
                           AttemptingJoinScreen.this.init();
                        }
                     });
                  }"""
AJ_MONITOR_NEW = AJ_MONITOR_ANCHOR + """

                  // TURN"使用中继"显隐：打洞 ≥20s 先查一次服务端开关（30s 缓存），开启且未在使用/未放弃才显示。
                  // TURN 中途失败 teardown 后 turnInProgress/turnActive 复位，本条件自然重新成立（按钮重现）。
                  icu.wuhui.voxlink.room.ConnectionManager cmTurn = VoxLinkMod.getRoomManager().getConnectionManager();
                  long punchMs = System.currentTimeMillis() - cmTurn.getConnectionStartTimeMs();
                  if (punchMs >= 20000L
                     && (!AttemptingJoinScreen.this.turnStatusChecked
                        || System.currentTimeMillis() - AttemptingJoinScreen.this.turnStatusCheckedAt > 30000L)) {
                     AttemptingJoinScreen.this.turnStatusChecked = true;
                     AttemptingJoinScreen.this.turnStatusCheckedAt = System.currentTimeMillis();
                     icu.wuhui.voxlink.network.TurnRelayClient
                        .fetchStatus(VoxLinkMod.getRoomManager().getSignalingClient())
                        .thenAccept(ok -> AttemptingJoinScreen.this.turnServerEnabled = ok);
                  }

                  boolean shouldShowTurn = AttemptingJoinScreen.this.active
                     && roomInfo != null
                     && !roomInfo.isHost()
                     && punchMs >= 20000L
                     && AttemptingJoinScreen.this.turnServerEnabled
                     && !cmTurn.isTurnActive()
                     && !cmTurn.isTurnInProgress()
                     && !cmTurn.isTurnP2pGivenUp()
                     && !cmTurn.isManualRelayInProgress();
                  if (shouldShowTurn != AttemptingJoinScreen.this.turnButtonVisible) {
                     AttemptingJoinScreen.this.turnButtonVisible = shouldShowTurn;
                     mc.execute(() -> {
                        if (mc.screen == AttemptingJoinScreen.this) {
                           AttemptingJoinScreen.this.clearOurWidgets();
                           AttemptingJoinScreen.this.init();
                        }
                     });
                  }"""

# ---------- Step5 lang ----------

TURN_I18N = {
    "voxlink.turn.use": {
        "zh_cn": "使用中继", "zh_tw": "使用中繼", "zh_hk": "使用中繼", "lzh": "使用中繼",
        "en_us": "Use Relay", "ja_jp": "中継を使う", "ko_kr": "중계 사용",
        "ru_ru": "Исп. ретранслятор", "de_de": "Relais nutzen", "fr_fr": "Utiliser relais",
        "es_es": "Usar relé", "pt_br": "Usar retransmissão", "ar_sa": "استخدم الترحيل"},
    "voxlink.turn.connecting": {
        "zh_cn": "正在连接中继节点…", "zh_tw": "正在連線中繼節點…", "zh_hk": "正在連線中繼節點…", "lzh": "正在連線中繼之節…",
        "en_us": "Connecting to relay node...", "ja_jp": "中継ノードに接続中…", "ko_kr": "중계 노드 연결 중...",
        "ru_ru": "Подключение к узлу ретрансляции...", "de_de": "Verbinde mit Relaisknoten...", "fr_fr": "Connexion au nœud relais...",
        "es_es": "Conectando al nodo de relé...", "pt_br": "Conectando ao nó de retransmissão...", "ar_sa": "جاري الاتصال بعقدة الترحيل..."},
    "voxlink.turn.failed": {
        "zh_cn": "中继不可用，继续尝试直连", "zh_tw": "中繼不可用，繼續嘗試直連", "zh_hk": "中繼不可用，繼續嘗試直連", "lzh": "中繼不可用，續試直連",
        "en_us": "Relay unavailable, retrying direct", "ja_jp": "中継不可用、直接接続を継続", "ko_kr": "중계 불가, 직접 연결 재시도",
        "ru_ru": "Ретранслятор недоступен, прямое соединение", "de_de": "Relais nicht verfügbar, Direktverbindung läuft", "fr_fr": "Relais indisponible, direct en cours",
        "es_es": "Relé no disponible, reintentando directo", "pt_br": "Retransmissão indisponível, tentando direto", "ar_sa": "الترحيل غير متوفر، استئناف الاتصال المباشر"},
    "voxlink.turn.switched_p2p": {
        "zh_cn": "已平滑切换至P2P直连", "zh_tw": "已平滑切換至P2P直連", "zh_hk": "已平滑切換至P2P直連", "lzh": "已平滑切換至P2P直連",
        "en_us": "Smoothly switched to P2P direct", "ja_jp": "P2P直接接続へ平滑切替済み", "ko_kr": "P2P 직접 연결로 전환됨",
        "ru_ru": "Плавно переключено на P2P", "de_de": "Nahtlos zu P2P gewechselt", "fr_fr": "Bascule fluide vers P2P",
        "es_es": "Cambiado a P2P directo", "pt_br": "Alternado para P2P direto", "ar_sa": "تم التبديل إلى الاتصال المباشر"},
}

def patch_lang(lang_dir):
    ok = 0
    for lang in TURN_I18N["voxlink.turn.use"]:
        fp = os.path.join(lang_dir, lang + ".json")
        if not os.path.exists(fp):
            print("  LANG missing", fp)
            continue
        data = read_bytes(fp)
        text = to_lf(data)
        if "voxlink.turn.use" in text:
            ok += 1
            continue
        body = text.rstrip()
        assert body.endswith("}"), fp
        inner = body[:-1].rstrip()
        lines = ["    \"%s\": \"%s\"" % (k, d[lang]) for k, d in TURN_I18N.items()]
        sep = "" if inner.endswith(",") else ","
        new_body = inner + sep + "\n" + ",\n".join(lines) + "\n}\n"
        eol = detect_eol(data)
        if eol == b"\r\n":
            new_body = new_body.replace("\n", "\r\n")
        write_bytes(fp, new_body.encode("utf-8"))
        ok += 1
    return ok

# ---------- main ----------

def main():
    print("== Step1 copy network files ==")
    for rel, newf, label in COPY_FILES:
        copy_verified(rel, newf, label)

    print("== Step2 ConnectionManager ==")
    cm_rel = REL + "/room/ConnectionManager.java"
    cm_block = cm_methods_block()
    for t in TEMPLATES:
        fp = os.path.join(ROOT, t, cm_rel)
        r1 = replace_with_eol(fp, CM_IMPORT_OLD, CM_IMPORT_NEW, "cm-import")
        r2 = replace_with_eol(fp, CM_FIELD_OLD, CM_FIELD_NEW, "cm-field")
        r3 = replace_with_eol(fp, CM_METHODS_ANCHOR, cm_block + CM_METHODS_ANCHOR, "cm-methods")
        if "skip" in (r1, r2, r3):
            print("  !! anchor problem at", t, (r1, r2, r3))

    print("== Step3 RoomManager ==")
    rm_rel = REL + "/room/RoomManager.java"
    for t in TEMPLATES:
        fp = os.path.join(ROOT, t, rm_rel)
        replace_with_eol(fp, RM_GETTER_ANCHOR, RM_GETTER_NEW, "rm-getter")
        replace_with_eol(fp, RM_CASE_OLD, RM_CASE_NEW, "rm-case")
        replace_with_eol(fp, RM_DISC_ANCHOR, RM_DISC_NEW, "rm-disc")

    print("== Step4 AttemptingJoinScreen ==")
    aj_rel = REL + "/ui/AttemptingJoinScreen.java"
    for t in TEMPLATES:
        fp = os.path.join(ROOT, t, aj_rel)
        replace_with_eol(fp, AJ_FIELD_OLD, AJ_FIELD_NEW, "aj-field")
        replace_with_eol(fp, AJ_INIT_ANCHOR, AJ_INIT_NEW, "aj-init")
        replace_with_eol(fp, AJ_METHOD_ANCHOR, AJ_METHOD_NEW, "aj-method")
        replace_with_eol(fp, AJ_MONITOR_ANCHOR, AJ_MONITOR_NEW, "aj-monitor")

    print("== Step5 lang ==")
    for t in TEMPLATES:
        n = patch_lang(os.path.join(ROOT, t, LANG_REL))
        if n != 13:
            print("  !! lang incomplete at", t, n)

    print("DONE 25 templates")

if __name__ == "__main__":
    main()
