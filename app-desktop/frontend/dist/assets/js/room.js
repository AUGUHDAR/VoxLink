/* ============================================================
 * VoxLink Desktop — room.js
 * 房间页（hosting / in_room 共用）：
 * 头部 / 信令事件流 / 房间设置(host) / ModSync / 连接日志 / 连接卡（房客）
 *
 * 应用端不再展示中继节点；P2P 打洞改为无限重试（前端不再走 p2p failed 终态）。
 * 2026-10-05：中继入口改为纯自动编排——连接卡没有任何手动按钮，只显示阶段叙述
 * 与"通道没连上"的 3 秒瞬态提示（对齐模组 1.2.0 的 AttemptingJoinScreen）。
 * ============================================================ */
"use strict";

(function (VL) {
  var MAX_SIGNALS = 100;

  var els = {};
  var session = {
    state: "idle",          // idle | hosting | in_room
    code: "",
    name: "",
    isHost: false,
    currentPlayers: 0,
    maxPlayers: 0,
    gameVersion: "",
    loader: "",
    clientType: ""          // 房间所属客户端类型（"app" / "mod" / ""）
  };
  var signals = [];         // {type, from, data, timestamp}
  var leaveArmed = false;   // 二次确认状态

  /* ---------------- 信令事件渲染映射 ---------------- */
  function renderSignalItem(s) {
    var type = String(s.type || "");
    var cls = "t-dim";
    var text = type;
    var detail = "";

    switch (type) {
      case "join_request":
        cls = "t-info"; text = VL.t("voxlink.app.signal.join_request", "玩家请求加入");
        detail = [s.data && s.data.playerName, s.data && s.data.gameVersion]
          .filter(Boolean).join(" · ");
        break;
      case "connected":
        cls = "t-good"; text = VL.t("voxlink.connection.connected", "已连接 awa");
        if (s.data && s.data.playerName) detail = s.data.playerName;
        break;
      case "disconnect":
        cls = "t-warn"; text = VL.t("voxlink.app.signal.player_left", "玩家断开");
        if (s.data && s.data.playerName) detail = s.data.playerName;
        break;
      case "host_closing":
        cls = "t-bad"; text = VL.t("voxlink.room_lost.host_closed", "房主把房间关了 :(");
        break;
      case "room_evicted":
        cls = "t-bad"; text = VL.t("voxlink.room_lost.room_evicted", "房间太多了，你的已被移除，但已在房间里的玩家不受影响");
        break;
      case "turn_ready":
      case "turn_alloc":
      case "turn_bg_punch":
        cls = "t-dim"; text = VL.t("voxlink.app.signal.turn", "TURN 中继活动");
        if (s.data) {
          var d = s.data;
          var bits = [d.node || d.relay || d.host, d.state || d.phase].filter(Boolean);
          if (bits.length) detail = bits.join(" · ");
        }
        break;
      default:
        cls = "t-dim"; text = type || "unknown";
    }

    return (
      '<div class="signal-item">' +
      '  <span class="signal-item-type ' + cls + '">' + VL.esc(text) + "</span>" +
      (detail ? '<span class="signal-item-detail">' + VL.esc(detail) + "</span>" : "") +
      '  <span class="signal-item-time">' + VL.esc(VL.relTime(s.timestamp)) + "</span>" +
      "</div>"
    );
  }

  function renderSignals() {
    var list = VL.$("#signal-list");
    var empty = VL.$("#signal-empty");
    var count = VL.$("#signal-count");
    if (!list) return;
    count.textContent = VL.t("voxlink.app.room.signal_count", "%s 条", signals.length);
    empty.classList.toggle("hidden", signals.length > 0);
    list.innerHTML = signals.map(renderSignalItem).join("");
  }

  function pushSignal(s) {
    signals.unshift({
      type: s.type || "",
      from: s.from || "",
      data: s.data || {},
      timestamp: s.timestamp || Date.now()
    });
    if (signals.length > MAX_SIGNALS) signals.length = MAX_SIGNALS;
    renderSignals();
  }

  /* ---------------- 连接日志（含 IP/端口 过滤） ---------------- */
  // 含 IP 或端口样式的行视为敏感（前端兜底过滤，后端也会同步收敛）。
  // 目的：避免日志面板泄漏 host/端口等中间网络信息。
  var IP_PORT_RE = /(?:\d{1,3}\.){3}\d{1,3}|127\.0\.0\.1|\b[a-z0-9-]+(?:\.[a-z0-9-]+)+:\d{2,5}\b|:\d{2,5}\b/i;

  function pushNetLog(entry) {
    var body = VL.$("#netlog-body");
    if (!body) return;
    if (body.querySelector(".netlog-faint")) body.innerHTML = "";
    var level = entry.level || "info";
    var msg = String(entry.msg || "");
    // 含 IP / 端口的行：直接丢弃
    if (IP_PORT_RE.test(msg)) return;

    var line = document.createElement("div");
    line.className = "netlog-line netlog-" + level;
    var ts = entry.ts ? VL.relTime(entry.ts) : VL.relTime(Date.now());
    line.innerHTML =
      '<span class="netlog-ts">' + VL.esc(ts) + "</span>" + VL.esc(msg);
    body.insertBefore(line, body.firstChild);
    while (body.children.length > 200) body.removeChild(body.lastChild);
  }

  /* ---------------- ModSync ---------------- */
  function renderMods(data) {
    var body = VL.$("#mods-body");
    if (!body) return;
    data = data || {};

    if (data.supported === false) {
      body.innerHTML = '<div class="mini-note">' +
        VL.esc(VL.t("voxlink.app.mods.unsupported", "房主未开启 ModSync 或当前版本不支持必装清单 qwq")) + "</div>";
      return;
    }
    if (!data.ready) {
      body.innerHTML = '<div class="mini-note">' +
        VL.esc(VL.t("voxlink.app.mods.not_published", "房主未发布必装清单，先进游戏等一等吧 (・ω・)")) + "</div>";
      return;
    }

    var mods = data.mods || [];
    if (!mods.length) {
      body.innerHTML = '<div class="mini-note">' +
        VL.esc(VL.t("voxlink.app.mods.empty", "房主发布了空清单——不需要额外 Mod，直接进 :D")) + "</div>";
      return;
    }

    var rows = mods.map(function (m) {
      var m1 = m || {};
      return (
        "<tr>" +
        '<td class="strong" title="' + VL.esc(m1.title || "") + '">' +
        VL.esc(m1.title || m1.fileName || VL.t("voxlink.app.mods.unknown_mod", "未知 Mod")) + "</td>" +
        '<td title="' + VL.esc(m1.fileName || "") + '">' + VL.esc(m1.fileName || "-") + "</td>" +
        "<td>" + VL.esc(m1.versionNumber || "-") + "</td>" +
        "<td>" + VL.esc(VL.fmtSize(m1.size)) + "</td>" +
        "</tr>"
      );
    }).join("");

    var head = "";
    if (data.loader || data.mcVersion) {
      head = '<div class="mini-note" style="text-align:left;padding-top:0">' +
        VL.esc([data.loader, data.mcVersion].filter(Boolean).join(" · ")) + "</div>";
    }

    body.innerHTML =
      head +
      '<table class="mini-table"><thead><tr>' +
      "<th>" + VL.esc(VL.t("voxlink.app.mods.col_name", "名称")) + "</th>" +
      "<th>" + VL.esc(VL.t("voxlink.app.mods.col_file", "文件")) + "</th>" +
      "<th>" + VL.esc(VL.t("voxlink.app.mods.col_version", "版本")) + "</th>" +
      "<th>" + VL.esc(VL.t("voxlink.app.mods.col_size", "大小")) + "</th>" +
      "</tr></thead><tbody>" + rows + "</tbody></table>";
  }

  async function loadMods() {
    if (!session.code) return;
    try {
      var data = await VL.api("GetRoomMods", session.code);
      renderMods(data);
    } catch (e) {
      renderMods(null);
      if (e.code !== "NO_BACKEND") VL.toast(VL.errText(e), "error");
    }
  }

  /* ---------------- 房间设置（host） ---------------- */
  function fillHostSettings() {
    var card = VL.$("#host-settings-card");
    if (!card) return;
    card.classList.toggle("hidden", !session.isHost);
    if (!session.isHost) return;
    VL.$("#hs-name").value = session.name || "";
    var catSel = VL.$("#hs-category");
    if (catSel && session.category !== undefined) {
      // 当前分类不在列表里（如自定义分类）时动态补一个选项，保证能正常回显
      var cur = session.category || "";
      if (cur && !catSel.querySelector('option[value="' + cur.replace(/"/g, '\\"') + '"]')) {
        var opt = document.createElement("option");
        opt.value = cur;
        opt.textContent = cur + VL.t("voxlink.app.category.custom_suffix", "（自定义）");
        catSel.appendChild(opt);
      }
      catSel.value = cur;
    }
    VL.$("#hs-visible").checked = !!session.visible;
    VL.$("#hs-pw").value = "";
  }

  async function saveHostSettings() {
    var req = {
      name: VL.$("#hs-name").value.trim() || undefined,
      category: VL.$("#hs-category").value || "",
      visible: VL.$("#hs-visible").checked
    };
    var pw = VL.$("#hs-pw").value;
    if (pw) req.password = pw;

    var btn = VL.$("#hs-save");
    btn.disabled = true;
    try {
      await VL.api("SendRoomUpdate", JSON.stringify(req));
      VL.toast(VL.t("voxlink.app.action.saved", "已保存"), "success");
      VL.$("#hs-pw").value = "";
    } catch (e) {
      if (e.code !== "NO_BACKEND") VL.toast(VL.errText(e), "error");
    }
    btn.disabled = false;
  }

  /* ---------------- 离开 / 关房 ---------------- */
  async function leaveOrClose() {
    var btn = VL.$("#room-leave");
    if (!leaveArmed) {
      leaveArmed = true;
      btn.classList.add("armed");
      var label = btn.querySelector("span:last-child");
      label.textContent = session.isHost
        ? VL.t("voxlink.app.room.confirm_close", "确认关闭？")
        : VL.t("voxlink.app.room.confirm_leave", "确认离开？");
      setTimeout(function () {
        leaveArmed = false;
        btn.classList.remove("armed");
        label.textContent = session.isHost
          ? VL.t("voxlink.app.room.close", "关闭房间")
          : VL.t("voxlink.app.room.leave", "离开房间");
      }, 3000);
      return;
    }
    leaveArmed = false;
    btn.disabled = true;
    try {
      await VL.api("LeaveRoom");
    } catch (e) {
      if (e.code !== "NO_BACKEND") VL.toast(VL.errText(e), "error");
    }
    btn.disabled = false;
  }

  /* ---------------- 连接流程（房客专用） ----------------
   * 2026-10-05 与模组 1.2.0 对齐：手动"尝试直连 / 使用中继 / 使用 TURN 中继"三个入口
   * 已退役，中继由引擎侧智能自动编排器触发（证据分级 + TURN 优先 + 真连接验证 + 静默换道）。
   * 前端只做呈现，没有任何可点的中继入口，也没有"倒计时解锁按钮"：
   *   - 阶段行 = conn:state 的 (phase,status) 叙述：p2p / direct / turn / prelay / fed
   *   - 通道"进行中→未连上"这条沿 → 3 秒瞬态提示（对应模组 AttemptingJoinScreen 的
   *     relayFailedMsgTime / turnFailedMsgTime 沿检测与 elapsed < 3000 可见规则）
   *   - 两条中继道本轮都失败且仍未连上 → 阶段行改说"中继均不可用，继续打洞…"
   *   - 任何阶段 success 到达 → 直接切成功卡
   *   - 成功卡地址：一律用事件里的 address（引擎给的是本机桥口 127.0.0.1:port，
   *     不是房主地址：应用端 MC 是独立进程，它只会照着地址真连）
   */

  // 阶段文案：turn/prelay 取自 lang 键 voxlink.auto_relay.turn / .player（lang/zh_cn.json:464-466），
  // 模组在编排器发起该通道时把同一句写进 connectionMode（ConnectionManager:12834 / :12843 / :12879）。
  // 每一项都是 [语言键, 中文原文]：文案要在渲染时查表，不能在文件加载时定死，
  // 否则中途换语言就得重开程序才生效。
  function txt(pair) { return pair ? VL.t(pair[0], pair[1]) : ""; }

  var CONN_TEXT = {
    p2p: {
      label: ["voxlink.app.conn.p2p", "P2P 打洞"],
      trying: ["voxlink.app.conn.p2p_trying", "P2P 打洞进行中…"]
    },
    direct: {
      label: ["voxlink.app.conn.direct", "直连"],
      trying: ["voxlink.app.conn.direct_trying", "正在尝试直连…"],
      failed: ["voxlink.app.conn.direct_failed", "连接失败，对方可能是其他游戏、不兼容的版本或网络不可达"]
    },
    prelay: {
      label: ["voxlink.app.conn.player_relay", "玩家中继"],
      trying: ["voxlink.auto_relay.player", "自动使用玩家中继…"],
      failed: ["voxlink.relay.failed_retry_punch", "中继失败，继续尝试直连打洞..."]
    },
    turn: {
      label: ["voxlink.app.conn.turn_relay", "TURN 中继"],
      trying: ["voxlink.auto_relay.turn", "直连未通，自动使用中继…"],
      failed: ["voxlink.turn.failed", "中继不可用，继续尝试直连"]
    },
    // 互通房（Happy Link 镜像）：lang 键 voxlink.connection.fed_setup / fed_failed 同文案
    fed: {
      label: ["voxlink.app.conn.fed", "互通中继"],
      trying: ["voxlink.connection.fed_setup", "正在通过互通中继连接房间..."],
      failed: ["voxlink.connection.fed_failed", "互通房间连接失败"]
    }
  };

  // 连接卡软提示按房间类型区分：mod 房（必是 MC）→ 版本不兼容；app 房 → 其他游戏/版本。
  var SOFT_HINT_APP = ["voxlink.app.conn.soft_app", "若无法进入，对方可能是其他游戏或不兼容的版本"];
  var SOFT_HINT_MOD = ["voxlink.app.conn.soft_mod", "若无法进入，可能是 MC 版本不兼容"];
  var FAILED_HINT_MOD = ["voxlink.app.conn.failed_mod", "连接失败，可能是版本不兼容或网络不可达"];

  // 3 秒瞬态提示文案（沿检测专用），逐条对应模组 lang 键：
  var CONN_HINT = {
    // voxlink.relay.failed_retry_punch（lang/zh_cn.json:241）
    // 模组沿检测：玩家中继"进行中→空闲且未连上"= 自动尝试失败（AttemptingJoinScreen:645-648, :697-704）
    relayFailed: ["voxlink.relay.failed_retry_punch", "中继失败，继续尝试直连打洞..."],
    // voxlink.turn.failed（lang/zh_cn.json:386）
    // 模组沿检测：TURN"忙→空闲且未连上"（AttemptingJoinScreen:651-658, :706-713）
    turnFailed: ["voxlink.turn.failed", "中继不可用，继续尝试直连"],
    // voxlink.logui.auto_relay_dead（lang/zh_cn.json:469，ConnectionManager:12958）
    // 同一条沿，但这条道曾经承载过流量 → 是"建起来却哑了"，不是建不起来
    relayDead: ["voxlink.logui.auto_relay_dead", "中继通道无响应，自动换道"]
  };
  // 可见时长：模组常量 RELAY_FAILED_MSG_MS = 3000（AttemptingJoinScreen:40）
  var CONN_HINT_MS = 3000;
  // 中继两道的 exhausted 兜底文案：voxlink.auto_relay.exhausted（lang/zh_cn.json:466）
  var CONN_EXHAUSTED = ["voxlink.auto_relay.exhausted", "中继均不可用，继续打洞…"];

  var conn = null;

  function connClearTimers() {
    if (!conn) return;
    if (conn.tick) clearInterval(conn.tick);
    if (conn.hintTimer) clearTimeout(conn.hintTimer);
    conn.tick = conn.hintTimer = null;
  }

  function connTickRender() {
    if (!conn || conn.status !== "trying") return;
    var sec = Math.max(0, Math.floor((Date.now() - conn.startedAt) / 1000));
    VL.$("#conn-timer").textContent = VL.t("voxlink.app.room.elapsed", "已用时 %ss", sec);
  }

  function connStartTick() {
    if (conn.tick) clearInterval(conn.tick);
    connTickRender();
    conn.tick = setInterval(connTickRender, 500);
  }

  function connStopTick() {
    if (conn && conn.tick) {
      clearInterval(conn.tick);
      conn.tick = null;
    }
  }

  // 显示一条 3 秒瞬态提示；到期即消失（与模组 render 里 elapsed < 3000 的写法等价）。
  function connShowHint(text) {
    if (!conn || !text) return;
    conn.hint = text;
    if (conn.hintTimer) clearTimeout(conn.hintTimer);
    conn.hintTimer = setTimeout(function () {
      if (!conn) return;
      conn.hint = "";
      conn.hintTimer = null;
      connRender();
    }, CONN_HINT_MS);
  }

  // 沿检测：某条中继道"进行中 → 没连上就收尾"= 自动编排这一次没接通
  // （编排器已经换道或回头继续打洞，玩家不需要、也没法手工再点一次）。
  function connLaneClosed(phase) {
    if (!conn) return;
    if (phase !== "prelay" && phase !== "turn") return;
    conn.lanes[phase] = true;
    connShowHint(conn.address ? txt(CONN_HINT.relayDead)
      : (phase === "prelay" ? txt(CONN_HINT.relayFailed) : txt(CONN_HINT.turnFailed)));
  }

  function connRender() {
    var card = VL.$("#conn-card");
    if (!conn) {
      card.classList.add("hidden");
      return;
    }
    card.classList.remove("hidden");
    var t = CONN_TEXT[conn.phase] || CONN_TEXT.p2p;
    VL.$("#conn-phase-label").textContent = txt(t.label);

    var box = VL.$("#conn-phase-box");
    var spin = VL.$("#conn-spinner");
    var text = VL.$("#conn-phase-text");
    var timer = VL.$("#conn-timer");
    var detail = VL.$("#conn-detail");
    var hint = VL.$("#conn-hint");
    var success = VL.$("#conn-success");
    var appNote = VL.$("#conn-app-note");
    var natBox = VL.$("#conn-nat");
    if (natBox) {
      var lines = [];
      var n = conn.nat;
      if (n && conn.status !== "success") {
        lines.push(VL.t(n.labelOpponentKey, "对方") + ": " + VL.t(n.opponentKey, "未知"));
        lines.push(VL.t(n.labelMineKey, "我方") + ": " + VL.t(n.mineKey, "未知"));
        var diff = VL.t(n.difficultyKey, "中等");
        if (n.doubt) diff += VL.t("voxlink.nat.doubt", "（存疑）");
        lines.push(VL.t(n.labelDifficultyKey, "难度") + ": " + diff);
        var hv = n.hostModVersion || VL.t(n.hostModVersionKey || "voxlink.host_version_unknown", "未知");
        lines.push(VL.t(n.labelHostVersionKey, "对方版本") + ": " + hv);
      }
      natBox.textContent = "";
      natBox.classList.toggle("hidden", lines.length === 0);
      for (var li = 0; li < lines.length; li++) {
        var row = document.createElement("span");
        row.className = "conn-nat-line";
        row.textContent = lines[li];
        natBox.appendChild(row);
      }
    }

    // 连接卡顶部小字：对方为应用端房间
    if (appNote) appNote.classList.toggle("hidden", !(session.clientType === "app"));

    // 两条中继道本轮都试过且都没连上 → exhausted 兜底文案。它不是终态：
    // 打洞仍在继续（模组里这句就是"继续打洞"），所以不给红色失败样式、保留 spinner。
    var exhausted = !!(conn.lanes && conn.lanes.prelay && conn.lanes.turn);
    var failed = conn.status === "failed" && !exhausted;
    box.classList.remove("hidden");
    box.classList.toggle("is-failed", failed);
    // failed 文案按房间 clientType 区分：mod 房（必是 MC）→ 版本不兼容；app 房 → 其他游戏/版本。
    var failedText = t.failed ? txt(t.failed) : VL.t("voxlink.connection.failed", "连接失败");
    if (failed && conn.phase !== "p2p" && session.clientType === "mod") {
      failedText = txt(FAILED_HINT_MOD);
    }
    text.textContent = exhausted ? txt(CONN_EXHAUSTED) : (failed ? failedText : txt(t.trying));
    spin.classList.toggle("hidden", failed);
    timer.classList.toggle("hidden", failed);
    if (!failed) connTickRender();

    detail.classList.toggle("hidden", !conn.detail);
    detail.textContent = conn.detail || "";

    // 瞬态提示行：只在沿检测后的 3 秒窗口内出现
    hint.classList.toggle("hidden", !conn.hint);
    hint.textContent = conn.hint || "";

    success.classList.add("hidden");
    if (conn.status === "success") {
      box.classList.add("hidden");
      detail.classList.add("hidden");
      // 瞬态提示行不受成功卡影响：已连上之后某条承载过的道掉了（无缝换道）仍要看得见
      success.classList.remove("hidden");
      VL.$("#conn-addr").textContent = conn.address || "-";
      var isTunnel = (conn.address || "").indexOf("127.0.0.1") === 0;
      VL.$("#conn-success-extra").textContent = isTunnel
        ? VL.t("voxlink.app.room.local_map_note", "（本地映射端口，保持本窗口开启）") : "";
      // 直接连成功卡淡色小字（兼容提示，不点破）
      // mod 房（必是 MC）→ 强调"可能是 MC 版本不兼容"；app 房保留"对方可能是其他游戏/版本"。
      var soft = VL.$("#conn-success-soft");
      if (soft) {
        soft.textContent = (session.clientType === "mod") ? txt(SOFT_HINT_MOD) : txt(SOFT_HINT_APP);
        soft.classList.remove("hidden");
      }
    }
  }

  // 进入房间（房客）：自动开始 P2P 打洞；后续通道一律由引擎侧自动编排器推进
  function connStart() {
    if (session.isHost) return;
    if (conn && conn.code === session.code) return; // 同一房间已在进行中
    connClearTimers();
    conn = {
      code: session.code,
      phase: "p2p",
      status: "trying",
      address: "",
      detail: "",
      hint: "",
      startedAt: Date.now(),
      tick: null,
      hintTimer: null,
      // 本轮"试过且没连上"的中继道：两条都失败 → 显示 exhausted 兜底文案
      lanes: { prelay: false, turn: false }
    };
    connStartTick();
    connRender();
  }

  function connReset() {
    connClearTimers();
    conn = null;
    connRender();
    VL.$("#conn-addr").textContent = "";
    VL.$("#conn-success-extra").textContent = "";
    var soft = VL.$("#conn-success-soft");
    if (soft) soft.classList.add("hidden");
    var hint = VL.$("#conn-hint");
    if (hint) { hint.textContent = ""; hint.classList.add("hidden"); }
    var appNote = VL.$("#conn-app-note");
    if (appNote) appNote.classList.add("hidden");
  }

  function connSuccess(address) {
    if (!conn) return;
    connClearTimers();
    conn.status = "success";
    conn.address = address;
    conn.detail = "";
    conn.hint = "";
    connRender();
  }

  // 供 app.js 转发 conn:state 事件：本函数只做呈现，不触发任何通道（无手动中继入口）。
  VL.onConnStateEvent = function (payload) {
    payload = payload || {};
    if (!conn) return;
    conn.nat = payload.nat || conn.nat || null;
    var status = payload.status;
    var phase = CONN_TEXT[payload.phase] ? payload.phase : null;

    if (status === "success") {
      // 任何阶段 success 到达 → 立即切成功卡
      connSuccess(payload.address || "");
      return;
    }

    // 已经连上之后又来通道级事件：成功卡不回收（自动换道是无缝的，本地地址照常可玩），
    // 只按同一条沿检测给 3 秒提示——"建起来过又掉了"= 中继通道无响应。
    if (conn.status === "success") {
      if (status === "failed") {
        connLaneClosed(phase || conn.phase);
        connRender();
      } else if (status === "trying" && payload.detail === "link_lost") {
        // 隧道被拆、本地端口已随链路失效：成功卡必须收回，退回"连接中"（自动换道不发这条）。
        conn.status = "trying";
        conn.phase = phase || conn.phase || "p2p";
        conn.address = "";
        conn.detail = "";
        connLaneClosed(phase || conn.phase);
        connRender();
      }
      return;
    }

    if (status === "failed") {
      var deadPhase = phase || conn.phase;
      conn.phase = deadPhase;
      conn.status = "failed";
      conn.detail = payload.detail || "";
      connStopTick();
      connLaneClosed(deadPhase);
      connRender();
      return;
    }

    if (status === "trying") {
      if (!phase) return;
      if (phase === conn.phase && conn.status === "trying") return; // 同通道重复播报：不打断计时
      // 上一通道还没连上就被换掉 → 走与模组一致的"进行中→空闲且未连上"沿检测
      if (conn.status === "trying") connLaneClosed(conn.phase);
      conn.phase = phase;
      conn.status = "trying";
      conn.detail = "";
      conn.startedAt = Date.now();
      if (phase === "p2p" || phase === "direct") {
        // 编排器回头继续打洞：新一轮，exhausted 判定清零
        conn.lanes = { prelay: false, turn: false };
      } else {
        conn.lanes[phase] = false; // 这条道正在被重新尝试，本轮不计失败
      }
      connStartTick();
      connRender();
    }
  };

  /* ---------------- 会话状态同步 ---------------- */
  function applySession(s) {
    s = s || {};
    session.state = s.state || "idle";
    var room = s.room || {};
    session.code = room.code || session.code || "";
    session.name = room.name || session.name || "";
    session.isHost = !!room.isHost;
    session.currentPlayers = room.currentPlayers || 0;
    session.maxPlayers = room.maxPlayers || 0;
    if (room.category !== undefined) session.category = room.category;
    if (room.visible !== undefined) session.visible = room.visible;
    if (room.gameVersion !== undefined) session.gameVersion = room.gameVersion || "";
    if (room.loader !== undefined) session.loader = room.loader || "";
    if (room.clientType !== undefined) session.clientType = room.clientType || "";
    renderRoomHeader();
  }

  function renderRoomHeader() {
    VL.$("#room-name").textContent = session.name || VL.t("voxlink.app.room.title", "房间");
    VL.$("#room-code").textContent = session.code || "------";
    VL.$("#room-players").innerHTML =
      '<span class="ico">' + VL.iconSvg("users") + "</span>" +
      VL.esc(VL.t("voxlink.app.room.players_count", "%s / %s 人",
        session.currentPlayers || 0, session.maxPlayers || 0));

    var pill = VL.$("#room-status-pill");
    var leaveText = VL.$("#room-leave-text");
    if (session.isHost) {
      pill.textContent = VL.t("voxlink.app.room.waiting", "等待玩家");
      pill.className = "pill pill-accent";
      leaveText.textContent = VL.t("voxlink.app.room.close", "关闭房间");
    } else {
      pill.textContent = VL.t("voxlink.app.room.connected", "已连接");
      pill.className = "pill pill-success";
      leaveText.textContent = VL.t("voxlink.app.room.leave", "离开房间");
    }
    leaveArmed = false;
  }

  function enterRoomRoomData(room) {
    if (room) {
      if (room.name) session.name = room.name;
      if (room.code) session.code = room.code;
      if (room.maxPlayers) session.maxPlayers = room.maxPlayers;
      if (room.currentPlayers !== undefined) session.currentPlayers = room.currentPlayers;
      if (room.isHost !== undefined) {
        session.isHost = !!room.isHost;
        // CreateRoom/JoinRoom 成功回调里没有 state 字段，这里同步推导，
        // 避免 conn:state 等事件早于 session:state 到达时被误判为不在房间
        session.state = room.isHost ? "hosting" : "in_room";
      }
      if (room.category !== undefined) session.category = room.category;
      if (room.visible !== undefined) session.visible = room.visible;
      if (room.gameVersion !== undefined) session.gameVersion = room.gameVersion || "";
      if (room.loader !== undefined) session.loader = room.loader || "";
      if (room.clientType !== undefined) session.clientType = room.clientType || "";
    }
    renderRoomHeader();
    fillHostSettings();
  }

  function resetRoomPage() {
    signals = [];
    renderSignals();
    VL.$("#netlog-body").innerHTML = '<div class="netlog-line netlog-faint">' +
      VL.esc(VL.t("voxlink.app.room.netlog_empty", "暂无日志")) + "</div>";
    VL.$("#mods-body").innerHTML = '<div class="mini-note">' +
      VL.esc(VL.t("voxlink.app.mods.loading", "正在加载必装清单…")) + "</div>";
    leaveArmed = false;
    var btn = VL.$("#room-leave");
    btn.classList.remove("armed");
    connReset();
  }

  // 进入房间（CreateRoom / JoinRoom 成功 或 GetSessionState 检测到）
  VL.enterRoomPage = function (room, opts) {
    opts = opts || {};
    enterRoomRoomData(room);
    resetRoomPage();
    VL.showPage("room");
    loadMods();
    // 房客：加入成功即自动进入 P2P 打洞阶段
    connStart();
    if (!opts.silent) {
      // 首次进入给个信令欢迎条目
      pushSignal({ type: "connected", data: {}, timestamp: Date.now() });
    }
  };

  VL.exitRoomToLobby = function () {
    session = {
      state: "idle", code: "", name: "", isHost: false,
      currentPlayers: 0, maxPlayers: 0,
      gameVersion: "", loader: "", clientType: ""
    };
    resetRoomPage();
    if (VL.resetCreateForm) VL.resetCreateForm();
    VL.showPage("lobby");
    if (VL.lobbyRefresh) VL.lobbyRefresh();
  };

  VL.roomInSession = function () {
    return session.state === "hosting" || session.state === "in_room";
  };

  /* ---------------- 事件绑定 / 初始化 ---------------- */
  VL.initRoom = function () {
    VL.$("#room-copy").addEventListener("click", function () {
      if (session.code) VL.copy(session.code);
    });
    VL.$("#room-code").addEventListener("click", function () {
      if (session.code) VL.copy(session.code);
    });
    VL.$("#room-leave").addEventListener("click", leaveOrClose);
    VL.$("#hs-save").addEventListener("click", saveHostSettings);
    VL.$("#mods-refresh").addEventListener("click", loadMods);

    // 连接卡（房客）：自动编排模型下这里只有"复制地址"，没有任何中继入口
    VL.$("#conn-copy").addEventListener("click", function () {
      if (conn && conn.address) VL.copy(conn.address);
    });

    VL.$("#netlog-toggle").addEventListener("click", function () {
      VL.$("#netlog-body").classList.toggle("collapsed");
      VL.$("#netlog-arrow").classList.toggle("collapsed");
    });

    // 换语言后重画房间页：头部、信令流、连接卡都按现有会话数据重说一遍。
    // 不重跑 fillHostSettings：它会拿 session 里的旧值盖掉房主正在输入框里改到一半的房间名；
    // 连接日志的行是后端推来的原文，也不在这儿重写（等于改写已经发生过的事实）。
    VL.onLangChange(function () {
      if (VL.roomInSession()) renderRoomHeader();
      renderSignals();
      connRender();
    });

    // 启动时同步一次会话状态
    (async function () {
      try {
        var data = await VL.api("GetSessionState");
        if (data && (data.state === "hosting" || data.state === "in_room")) {
          applySession(data);
        }
      } catch (e) { /* 无后端等场景忽略 */ }
    })();
  };

  /* ---------------- 供 app.js 调用的事件入口 ---------------- */
  VL.onSessionStateEvent = function (payload) {
    payload = payload || {};
    var st = payload.state;
    if (st === "hosting" || st === "in_room") {
      session.state = st;
      enterRoomRoomData(payload.room || {});
      var page = VL.currentPage && VL.currentPage();
      if (page !== "room") VL.enterRoomPage(payload.room || {}, { silent: true });
    } else if (st === "closed") {
      var msg = payload.message || (session.isHost
        ? VL.t("voxlink.room_closed", "房间已关闭")
        : VL.t("voxlink.app.room.left", "已离开房间"));
      VL.toast(msg, "info");
      VL.exitRoomToLobby();
    } else if (st === "error") {
      VL.toast(payload.message || VL.t("voxlink.connection.monitor_error", "连接出了点问题 :("), "error");
    } else if (st === "idle") {
      // LeaveRoom 成功后的回执
      if (VL.roomInSession()) VL.exitRoomToLobby();
    }
  };

  VL.onSignalEvent = function (payload) {
    if (!VL.roomInSession()) return;
    pushSignal(payload || {});
  };

  VL.onNetLogEvent = function (payload) {
    if (!VL.roomInSession()) return;
    pushNetLog(payload || {});
  };
})(window.VL);