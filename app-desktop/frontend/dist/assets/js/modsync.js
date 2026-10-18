/* ============================================================
 * VoxLink Desktop — modsync.js（新增文件，不改 room.js/index.html/settings.js）
 *
 * 模组同步（ModSync）的应用端界面层：Java 侧 ModSyncSelectScreen /
 * ModSyncRestartScreen / 配置开关的 HTML 等价物。
 * 后端逻辑全在 modsync_*.go，这里只负责：
 *   1) 加入前门控（选实例 + scope 选择 → checking → 差异卡片 → 下载/直接加入）
 *   2) 下载进度与"需重启实例"屏
 *   3) 房主侧清单面板（实例状态 / 重建 / 上报开关）
 *   4) 设置页的 MC 实例与两个开关
 *
 * 接线方式（保持零侵入）：
 *   - 包装 VL.api：JoinRoom 之前先跑 ModSyncGate（对应 Java 在 join_room/打洞
 *     之前调用 gate()，ModSyncGuestService.java:98-102）
 *   - 包装 VL.enterRoomPage：进房后 flush 门控直通提示（对应 pushPendingGateNote，
 *     ModSyncGuestService.java:60-68 —— 必须晚于房间页的日志面板重置）
 *   - 运行期向 #mods-body 所在卡片、#page-settings 追加 DOM，不动 index.html
 * 需要的一条 hunk（见交付报告）：index.html 里在 room.js 之后、app.js 之前
 *   <script src="assets/js/modsync.js"></script>
 * ============================================================ */
"use strict";

(function (VL) {
  /* ---------------- 语言表：逐字取自模组 zh_cn.json:318-378,425-426 ---------------- */
  var LANG = {
    "voxlink.modsync.title": "必装模组检查",
    "voxlink.modsync.title_all": "房主模组同步检查（全部）",
    "voxlink.modsync.subtitle": "加入房间 %s：以下为房主要求的必装模组",
    "voxlink.modsync.subtitle_all": "加入房间 %s：以下为与房主相差的模组",
    "voxlink.modsync.back": "返回",
    "voxlink.modsync.cancel_download": "取消下载",
    "voxlink.modsync.cancelling": "正在取消...",
    "voxlink.modsync.join_anyway": "仍要直接加入（版本不一致可能崩溃）",
    "voxlink.modsync.download_failed": "Modrinth下载失败（%s）",
    "voxlink.modsync.copy": "复制",
    "voxlink.modsync.copied": "已复制",
    "voxlink.modsync.resolving": "正在匹配模组（Modrinth / CurseForge）…",
    "voxlink.modsync.restart_title": "必须重启游戏",
    "voxlink.modsync.restart_line1": "已下载 %s 个模组到 mods 文件夹。",
    "voxlink.modsync.restart_line2": "重启游戏后生效，请退出并重新启动游戏。",
    "voxlink.modsync.exit_game": "退出游戏",
    "voxlink.modsync.toggle.join": "加入前必装Mod检查",
    "voxlink.modsync.checking": "正在获取房主必装清单...",
    "voxlink.modsync.checking_all": "正在获取房主全部模组...",
    "voxlink.modsync.scope_hint": "选择要同步的模组范围：",
    "voxlink.modsync.scope_required": "仅检查必装模组",
    "voxlink.modsync.scope_all": "获取房主全部模组",
    "voxlink.modsync.skip_check": "直接进入",
    "voxlink.modsync.diff_line": "%s（与房主版本不一致，可能造成崩溃）",
    "voxlink.modsync.mc_mismatch": "房主MC版本 %s 与你 (%s) 不同：清单仅供参考，请自行核对兼容性",
    "voxlink.modsync.toggle.publish": "上报必装Mod给房客: %s",
    "voxlink.modsync.download_mods": "下载模组（%s · %s）",
    "voxlink.modsync.join_directly": "直接加入",
    "voxlink.modsync.missing_header": "缺失的必装模组（%s）：",
    "voxlink.modsync.missing_header_all": "缺失的模组（%s）：",
    "voxlink.modsync.notes_header": "提示：",
    "voxlink.modsync.notfound_header": "以下模组在 Modrinth 和 CurseForge 上都找不到（%s）：",
    "voxlink.modsync.incompatible_header": "以下模组没有适配你版本的文件（%s）：",
    "voxlink.modsync.entry_line": "• %s %s（%s）",
    "voxlink.modsync.progress_title": "正在下载模组",
    "voxlink.modsync.progress_overall": "总进度：%s",
    "voxlink.modsync.progress_completed": "已完成：%s/%s",
    "voxlink.modsync.progress_current": "当前：%s",
    "voxlink.modsync.progress_remaining": "剩余模组：%s",
    "voxlink.modsync.progress_eta": "预计剩余时间：%s",
    "voxlink.modsync.page_ind": "%s/%s",
    "voxlink.modsync.prev_page": "< 上一页",
    "voxlink.modsync.next_page": "下一页 >",
    "voxlink.modsync.retry_hint": "可再次点击下载重试。",
    "voxlink.modsync.esc_block_downloading": "下载中无法关闭，请先取消下载。",
    "voxlink.modsync.esc_block_selecting": "请使用返回按钮退出此页面。",
    "voxlink.modsync.disabled_hint": "%s 已安装但被禁用（*.jar.disabled），如需请手动启用",
    "voxlink.modsync.gate_failed_warn": "[VoxLink] 模组检查失败，已直接加入，请自行确认 Mod 一致",
    "voxlink.modsync.download_cancelled": "已取消下载。",
    "voxlink.modsync.loader_mismatch": "房主加载器 %s 与你 (%s) 不同：mod 无法运行，请安装匹配的加载器",
    "voxlink.logui.modsync_unsupported": "房主未启用模组清单，已跳过检查",
    "voxlink.logui.modsync_fetch_failed": "无法获取房主模组清单，已跳过检查",
    // 应用端专属（无 MC 对应物）
    "voxlink.app.modsync.instance": "MC 实例（mods 目录）",
    "voxlink.app.modsync.instance_hint": "模组同步只读写这一个 mods 目录；留空则不启用检查与上报",
    "voxlink.app.modsync.host_panel": "本端清单（房主）",
    "voxlink.app.modsync.rebuild": "重建并上报清单",
    "voxlink.app.modsync.not_configured": "未配置 MC 实例：已跳过模组同步",
    "voxlink.app.modsync.join_cancelled": "已取消加入（模组检查页返回）",
    "voxlink.app.modsync.restart_app_hint": "请在关闭 Minecraft 后重新启动它，新模组装载后才生效。",
    // 加入侧「选实例」那一步（只加入、从不建房的玩家也得能选定往哪装模组）
    "voxlink.app.modsync.pick_instance": "模组装到哪个实例？",
    "voxlink.app.modsync.pick_instance_hint": "比对本端模组、写下载文件都只认这一个实例的 mods 目录（加载器 / MC 版本也按它算）",
    "voxlink.app.modsync.target_dir": "写入目录：%s",
    "voxlink.app.modsync.target_unnamed": "还没选定实例：本次会用第一个，或自己填一个",
    "voxlink.app.modsync.no_instance": "还没有任何 MC 实例：填一个 mods 目录，或者点「直接进入」跳过检查",
    "voxlink.app.modsync.instance_name_ph": "实例名（例如 1.20.1 科技服）",
    "voxlink.app.modsync.instance_name_default": "MC 实例",
    "voxlink.app.modsync.browse": "浏览",
    "voxlink.app.modsync.mods_dir_ph": "mods 目录（例如 C:/Users/You/AppData/Roaming/.minecraft/mods）",
    "voxlink.app.modsync.gv_ph": "MC 版本（例如 1.20.1，可留空）",
    "voxlink.app.modsync.mods_dir_hint": "粘贴放 *.jar 的那个 mods 文件夹完整路径即可，之后一直用它",
    "voxlink.app.modsync.need_dir": "要填 mods 目录才能下载模组；不想装就点「直接进入」",
    "voxlink.app.modsync.saved_instance": "已保存实例「%s」"
  };

  // 查表交给 VL.t（后端语言包优先），这张 LANG 表原样留着当"中文兜底 + 应用端自有文案"：
  // voxlink.modsync.* / voxlink.logui.modsync_* 这些键在模组 lang 里都有，切语言时会自动换成
  // 对应语言；voxlink.app.modsync.* 是应用端自己的键，lang 里没有，于是继续用下面这句中文。
  function t(key) {
    var args = Array.prototype.slice.call(arguments, 1);
    return VL.t.apply(null, [key, LANG[key]].concat(args));
  }
  // LANG 表里没有的键（例如与房间页同款的"未知 Mod"）也走 VL.t：兜底中文就写在调用处
  function t2(key, cn) {
    var args = Array.prototype.slice.call(arguments, 2);
    return VL.t.apply(null, [key, cn].concat(args));
  }
  // 后端返回的是 {key,args}（语言键不硬编码进 Go），这里统一渲染
  function line(obj) {
    if (!obj) return "";
    if (typeof obj === "string") return obj;
    var args = obj.args || [];
    return t.apply(null, [obj.key].concat(args));
  }
  VL.modsyncText = t;
  VL.modsyncLine = line;

  /* ---------------- 状态 ---------------- */
  var settings = { configured: false, joinRequiredModsCheck: true, hostModSyncPublish: true };
  var msView = null;       // GetModSyncSettings 的原始回包（instances / activeInstance / 开关 / 生效四元组）
  var modal = null;        // {root, body}
  var gateState = null;    // 当前门控会话（scope / 分组 / 订阅状态）
  var pendingNote = null;  // 门控直通提示（进房后 flush）

  /* ---------------- 实例数据读写（与 create.js / settings.js 同一份全局设置） ----------------
   * 实例（name/loader/gameVersion/modsDir）原本只能在「创建房间」页选，只加入不建房
   * 的玩家因此没有任何实例可用：ModSyncGate 拿默认探测目录做比对、ModSyncDownload
   * 也往那儿写（后端两个都只读 activeInstance，没有参数可传）。加入侧必须自带一份
   * 「选实例 / 就地新建」，见下面 askScope。
   * SaveModSyncSettings 是整体回写：少带任何一个字段就等于把它删掉，
   * 所以任何写入都先 GetModSyncSettings 读现状再合并（与 create.js 同构）。 */
  var LOADERS = ["fabric", "forge", "neoforge"];

  function msInstances() {
    return (msView && msView.instances) || [];
  }

  function msInstanceByName(name) {
    if (!name) return null;
    var list = msInstances();
    for (var i = 0; i < list.length; i++) {
      if (list[i] && list[i].name === name) return list[i];
    }
    return null;
  }

  function msLabel(it) {
    return it ? [it.loader, it.gameVersion].filter(Boolean).join(" · ") : "";
  }

  function applySettingsView(v) {
    if (!v) return;
    msView = v;
    settings = v;
  }

  // 现在真正生效的写入目标（后端按 activeInstance 解析，无实例时回落探测到的默认目录）
  function targetLabel() {
    var it = msInstanceByName((msView && msView.activeInstance) || "");
    var dir = (it && it.modsDir) || (msView && msView.modsDir) || "";
    if (!dir) return "";
    var name = it ? it.name : "";
    return t("voxlink.app.modsync.target_dir", name ? name + " · " + dir : dir);
  }

  function loadSettings() {
    return rawApi("GetModSyncSettings").then(function (v) {
      applySettingsView(v);
    }, function () { /* 后端不可用（浏览器演示模式）：保持上一次读到的现状 */ });
  }

  // 永不 reject：返回 {ok}，失败原因已经 toast 过，调用方只需决定要不要放行玩家。
  function saveModSync(patch) {
    return rawApi("GetModSyncSettings").then(function (cur) {
      if (cur) applySettingsView(cur);
      var base = msView || {};
      var payload = {
        instances: msInstances(),
        activeInstance: base.activeInstance || "",
        joinRequiredModsCheck: !(base.joinRequiredModsCheck === false),
        hostModSyncPublish: !(base.hostModSyncPublish === false)
      };
      Object.keys(patch || {}).forEach(function (k) { payload[k] = patch[k]; });
      return rawApi("SaveModSyncSettings", JSON.stringify(payload));
    }).then(function (v) {
      if (v) applySettingsView(v);
      return { ok: true };
    }, function (e) {
      if (e && e.code === "NO_BACKEND") return { ok: true }; // 没有后端就别拦人
      if (e) VL.toast(VL.errText(e), "error");
      return { ok: false };
    });
  }

  /* ---------------- 通用弹层 ---------------- */
  function ensureModal() {
    if (modal) return modal;
    var root = document.createElement("div");
    root.className = "modsync-overlay hidden";
    root.id = "modsync-overlay";
    root.innerHTML =
      '<div class="modsync-panel" role="dialog" aria-modal="true">' +
      '  <div class="modsync-head"><span class="modsync-title" id="modsync-title"></span></div>' +
      '  <div class="modsync-body" id="modsync-body"></div>' +
      '  <div class="modsync-foot" id="modsync-foot"></div>' +
      "</div>";
    document.body.appendChild(root);
    var style = document.createElement("style");
    style.textContent = [
      ".modsync-overlay{position:fixed;inset:0;background:rgba(0,0,0,.45);display:flex;",
      "align-items:center;justify-content:center;z-index:90}",
      ".modsync-overlay.hidden{display:none}",
      ".modsync-panel{width:min(760px,92vw);max-height:86vh;overflow:auto;background:var(--bg-elev,#fff);",
      "border-radius:12px;box-shadow:0 12px 40px rgba(0,0,0,.3);padding:16px 18px}",
      ".modsync-head{margin-bottom:8px}.modsync-title{font-weight:700;font-size:15px}",
      ".modsync-sub{color:var(--text-dim,#666);font-size:12px;margin:2px 0 10px}",
      ".modsync-h{font-size:13px;margin:12px 0 6px;font-weight:600}",
      ".modsync-card{display:flex;gap:8px;align-items:flex-start;padding:6px 4px;border-bottom:1px dashed rgba(128,128,128,.25)}",
      ".modsync-card img{width:28px;height:28px;border-radius:5px;object-fit:cover;background:rgba(128,128,128,.15)}",
      ".modsync-ph{width:28px;height:28px;border-radius:5px;display:flex;align-items:center;justify-content:center;",
      "font-size:13px;color:#fff;background:#6b7fd7}",
      ".modsync-note{font-size:12px;color:var(--text-dim,#777);margin:3px 0}",
      ".modsync-warn{font-size:12px;color:#c0392b;margin:3px 0}",
      ".modsync-bar{height:8px;border-radius:4px;background:rgba(128,128,128,.25);overflow:hidden;margin:6px 0}",
      ".modsync-bar>i{display:block;height:100%;background:var(--accent,#4a7cf7);width:0%}",
      ".modsync-foot{display:flex;gap:10px;flex-wrap:wrap;justify-content:flex-end;margin-top:14px}",
      ".modsync-card .btn{padding:2px 8px;font-size:12px}"
    ].join("");
    document.head.appendChild(style);
    modal = { root: root };
    return modal;
  }

  function openModal(titleKey, subtitle, bodyHTML, footHTML) {
    var m = ensureModal();
    m.root.classList.remove("hidden");
    VL.$("#modsync-title").textContent = titleKey;
    VL.$("#modsync-body").innerHTML =
      (subtitle ? '<div class="modsync-sub">' + VL.esc(subtitle) + "</div>" : "") + bodyHTML;
    VL.$("#modsync-foot").innerHTML = footHTML || "";
  }
  function closeModal() {
    var m = ensureModal();
    m.root.classList.add("hidden");
    modalView = null; // 换语言时只重画还开着的那一屏
  }

  // 当前弹窗停在哪一屏、以及重画那一屏所需的数据：换语言时能纯靠已有数据重说的才重说。
  // 「选实例」那一屏带着玩家填到一半的输入框，重跑一次等于把他的输入清空，所以不重画。
  var modalView = null;
  var lastProgress = null;
  var lastRestart = null;

  /* ---------------- 条目卡片（对应 SelectScreen.addEntryCard :156-169） ---------------- */
  function entryCard(e, showCopy) {
    var sub = VL.esc(e.fileName || "");
    if (e.versionNumber) sub = VL.esc(e.versionNumber) + " · " + sub;
    if (e.size > 0) sub = sub + " · " + VL.fmtSize(e.size);
    var icon = e.iconUrl
      ? '<img src="' + VL.esc(e.iconUrl) + '" alt="" loading="lazy">'
      : '<span class="modsync-ph">' + VL.esc((e.title || "?").slice(0, 1)) + "</span>";
    return (
      '<div class="modsync-card">' + icon +
      '<div style="flex:1"><div>' + VL.esc(e.title || e.fileName || t2("voxlink.app.mods.unknown_mod", "未知 Mod")) +
      (showCopy !== false ? ' <button class="btn btn-ghost btn-sm" data-copy="' +
        VL.esc(e.title || e.fileName || "") + '">' + t("voxlink.modsync.copy") + "</button>" : "") +
      '</div><div class="modsync-note">' + sub + "</div></div></div>"
    );
  }

  function bindCopyButtons(scopeEl) {
    VL.$$("button[data-copy]", scopeEl).forEach(function (b) {
      b.addEventListener("click", function () {
        VL.copy(b.getAttribute("data-copy"));
        b.textContent = t("voxlink.modsync.copied");
        b.disabled = true;
        setTimeout(function () {
          b.textContent = t("voxlink.modsync.copy");
          b.disabled = false;
        }, 1600);
      });
    });
  }

  /* ---------------- 分组渲染（对应 buildLines :118-153） ---------------- */
  function renderDiff() {
    var g = gateState;
    var allScope = g.scope === "all";
    var html = "";

    // 把「装到哪儿」摆在最前面：之前比对完了才知道没处写 / 写错目录，就是因为这一行不存在。
    var tgt = targetLabel();
    if (tgt) html += '<div class="modsync-note">' + VL.esc(tgt) + "</div>";

    if (g.downloadable.length) {
      html += '<div class="modsync-h">' + VL.esc(
        t(allScope ? "voxlink.modsync.missing_header_all" : "voxlink.modsync.missing_header",
          g.downloadable.length)) + "</div>";
      html += g.downloadable.map(function (e) { return entryCard(e); }).join("");
    }
    if (g.incompatible.length) {
      html += '<div class="modsync-h">' + VL.esc(
        t("voxlink.modsync.incompatible_header", g.incompatible.length)) + "</div>";
      html += g.incompatible.map(function (e) { return entryCard(e); }).join("");
    }
    if (g.notfound.length) {
      html += '<div class="modsync-h">' + VL.esc(
        t("voxlink.modsync.notfound_header", g.notfound.length)) + "</div>";
      html += g.notfound.map(function (e) { return entryCard(e); }).join("");
    }
    if (g.unresolvable.length || g.versionDiff.length) {
      html += '<div class="modsync-h">' + VL.esc(t("voxlink.modsync.notes_header")) + "</div>";
      g.unresolvable.forEach(function (n) { html += '<div class="modsync-warn">' + VL.esc(line(n)) + "</div>"; });
      g.versionDiff.forEach(function (n) { html += '<div class="modsync-warn">' + VL.esc(line(n)) + "</div>"; });
    }
    if (g.resolving) {
      html += '<div class="modsync-note">' + VL.esc(t("voxlink.modsync.resolving")) + "</div>";
    }
    if (g.status) {
      html += '<div class="modsync-warn">' + VL.esc(g.status) + "</div>";
    }

    var bytes = 0;
    g.downloadable.forEach(function (e) { bytes += (e.size || 0); });
    var foot = '<div style="flex:1;text-align:left;color:var(--text-dim,#777);font-size:12px">' +
      (g.statusText || "") + "</div>";
    if (g.downloadable.length) {
      foot += '<button class="btn btn-primary" id="modsync-dl">' + VL.esc(
        t("voxlink.modsync.download_mods", String(g.downloadable.length), VL.fmtSize(bytes))) + "</button>";
    }
    // 有版本冲突时按钮文案换成"仍要直接加入"（SelectScreen:198-268）
    foot += '<button class="btn btn-ghost" id="modsync-join">' + VL.esc(
      g.versionDiff.length ? t("voxlink.modsync.join_anyway") : t("voxlink.modsync.join_directly")) + "</button>";
    foot += '<button class="btn btn-ghost" id="modsync-back">' + VL.esc(t("voxlink.modsync.back")) + "</button>";

    openModal(
      t(allScope ? "voxlink.modsync.title_all" : "voxlink.modsync.title"),
      t(allScope ? "voxlink.modsync.subtitle_all" : "voxlink.modsync.subtitle", g.code),
      html, foot);
    modalView = "diff";
    bindCopyButtons(VL.$("#modsync-body"));
    var dl = VL.$("#modsync-dl");
    if (dl) dl.addEventListener("click", startDownload);
    VL.$("#modsync-join").addEventListener("click", function () { finishJoin(true); });
    VL.$("#modsync-back").addEventListener("click", function () { cancelJoin(); });
  }

  /* ---------------- 下载进度（对应 renderProgress :636-664） ---------------- */
  function fmtEta(sec) {
    if (!sec || sec < 0) return "--";
    var m = Math.floor(sec / 60), s = sec % 60;
    return m > 0
      ? t2("voxlink.app.modsync.eta_m_s", "%s 分 %s 秒", m, s)
      : t2("voxlink.app.modsync.eta_s", "%s 秒", s);
  }

  function renderProgress(p) {
    var body = VL.$("#modsync-body");
    if (!body || !gateState) return;
    modalView = "progress";
    lastProgress = p;
    var total = p.total || 0, done = p.done || 0;
    var html =
      '<div class="modsync-h">' + VL.esc(t("voxlink.modsync.progress_title")) + "</div>" +
      '<div class="modsync-bar"><i style="width:' + (p.overallPct || 0) + '%"></i></div>' +
      '<div class="modsync-note">' + VL.esc(t("voxlink.modsync.progress_overall", (p.overallPct || 0) + "%")) + "</div>" +
      '<div class="modsync-note">' + VL.esc(t("voxlink.modsync.progress_completed", done, total)) + "</div>" +
      '<div class="modsync-note">' + VL.esc(t("voxlink.modsync.progress_current", p.current || "-")) + "</div>" +
      '<div class="modsync-note">' + VL.esc(t("voxlink.modsync.progress_remaining", p.remaining || 0)) + "</div>" +
      '<div class="modsync-note">' + VL.esc(t("voxlink.modsync.progress_eta", fmtEta(p.etaSec))) + "</div>";
    body.innerHTML = html;
    var foot = VL.$("#modsync-foot");
    foot.innerHTML = '<button class="btn btn-ghost" id="modsync-cancel-dl">' +
      VL.esc(gateState.cancelling ? t("voxlink.modsync.cancelling") : t("voxlink.modsync.cancel_download")) +
      "</button>";
    VL.$("#modsync-cancel-dl").addEventListener("click", function () {
      gateState.cancelling = true;
      this.disabled = true;
      this.textContent = t("voxlink.modsync.cancelling");
      VL.api("ModSyncCancelDownload").catch(function () { });
    });
  }

  /* ---------------- 下载终局（对应 abortThrough / failThrough / RestartScreen） ---------------- */
  function renderDownloadDone(res) {
    var g = gateState || {};
    if (res.outcome === "done") {
      // RestartScreen：全屏遮罩、唯一出口=退出游戏。应用端等价：告知必须重启 MC 实例，
      // 然后由玩家决定何时进入房间（不自动放行，避免装完 mod 却没重启就崩）。
      modalView = "restart";
      lastRestart = res;
      openModal(t("voxlink.modsync.restart_title"), "",
        '<div class="modsync-h">' + VL.esc(line({ key: "voxlink.modsync.restart_line1", args: [res.downloaded] })) + "</div>" +
        '<div class="modsync-note">' + VL.esc(t("voxlink.modsync.restart_line2")) + "</div>" +
        '<div class="modsync-note">' + VL.esc(t("voxlink.app.modsync.restart_app_hint")) + "</div>",
        '<button class="btn btn-primary" id="modsync-after-restart">' +
        VL.esc(t("voxlink.modsync.join_directly")) + "</button>" +
        '<button class="btn btn-ghost" id="modsync-after-back">' + VL.esc(t("voxlink.modsync.back")) + "</button>");
      VL.$("#modsync-after-restart").addEventListener("click", function () { finishJoin(false); });
      VL.$("#modsync-after-back").addEventListener("click", function () { cancelJoin(); });
      return;
    }
    if (res.outcome === "cancelled") {
      g.status = line(res.note);
      g.statusText = "";
      g.cancelling = false;
      renderDiff();
      return;
    }
    // failed：回选择态 + 追加重试提示（failThrough :404-412）
    g.status = line(res.note) + " " + line(res.hint || { key: "voxlink.modsync.retry_hint" });
    g.statusText = g.status;
    g.cancelling = false;
    if (res.downloaded) {
      (res.restartKeys || []).forEach(function () { });
    }
    renderDiff();
  }

  function startDownload() {
    var g = gateState;
    if (!g || !g.downloadable.length) return;
    var payload = JSON.stringify({ entries: g.downloadable });
    VL.api("ModSyncDownload", payload).catch(function (e) {
      if (e.code !== "NO_BACKEND") VL.toast(VL.errText(e), "error");
    });
  }

  /* ---------------- 事件订阅 ---------------- */
  VL.on("modsync:progress", function (p) { renderProgress(p || {}); });
  VL.on("modsync:download", function (r) { renderDownloadDone(r || {}); });
  VL.on("modsync:resolve", function (d) {
    if (!gateState) return;
    gateState.resolving = !!(d && d.resolving);
    if (!gateState.resolving) {
      // 解析结束刷新一次（Java 的 onFinished 只清标志）
    }
    renderDiff();
  });
  // applyOne（SelectScreen:578-591）：可下载→移出"找不到/不兼容"并进可下载组；
  // 不兼容→从"找不到"移到"不兼容"；找不到→原地只更新名/图标。
  VL.on("modsync:resolved", function (d) {
    var g = gateState;
    if (!g) return;
    d = d || {};
    var bucket = d.bucket;
    var orig = d.original, res = d.resolved;
    function keyOf(e) { return e ? (e.fileName || e.projectId || "") : ""; }
    function removeWhere(list, e) {
      if (!e) return;
      var k = keyOf(e);
      for (var i = list.length - 1; i >= 0; i--) {
        if (keyOf(list[i]) === k) list.splice(i, 1);
      }
    }
    if (bucket === 2 && res) {
      removeWhere(g.notfound, orig);
      removeWhere(g.incompatible, orig);
      var dup = (keyOf(res) && g.downloadable.some(function (x) { return keyOf(x) === keyOf(res); })) ||
        (res.projectId && g.downloadable.some(function (x) { return x.projectId === res.projectId; }));
      if (!dup) g.downloadable.push(res);
    } else if (bucket === 1 && orig) {
      removeWhere(g.notfound, orig);
      var hasIncompat = g.incompatible.some(function (x) { return keyOf(x) === keyOf(orig); });
      if (!hasIncompat) g.incompatible.push(orig);
    }
    if (bucket === 0 && orig && res) {
      // NOT_FOUND：CF 可能补到真名/真图标，原地更新（applyMeta 的等价）
      g.notfound.concat(g.incompatible).forEach(function (x) {
        if (keyOf(x) === keyOf(orig)) {
          if (res.title) x.title = res.title;
          if (res.iconUrl) x.iconUrl = res.iconUrl;
          if (res.slug) x.slug = res.slug;
          if (!x.projectId) x.projectId = res.projectId;
        }
      });
    }
    renderDiff();
  });

  /* ---------------- 加入流程接线 ---------------- */
  var joinFlow = null; // {code, password, resolve, reject}

  function finishJoin(markSkipped) {
    var g = gateState, jf = joinFlow;
    closeModal();
    var done = function () {
      if (jf && jf.resolve) jf.resolve();
      joinFlow = null;
    };
    if (markSkipped && g && g.skipKeys && g.skipKeys.length) {
      VL.api("ModSyncMarkSkipped", JSON.stringify(g.skipKeys)).then(done, done);
    } else {
      done();
    }
  }

  function cancelJoin() {
    var g = gateState, jf = joinFlow;
    if (g && g.code) VL.api("ModSyncBypass", g.code).catch(function () { });
    closeModal();
    if (jf && jf.reject) jf.reject(modsyncCancelError());
    joinFlow = null;
  }

  function modsyncCancelError() {
    var e = new Error(t("voxlink.app.modsync.join_cancelled"));
    e.code = "MODSYNC_CANCELLED";
    return e;
  }

  // scope 选择页（对应 AttemptingJoinScreen 的三个按钮 :154-183,264-318），
  // 并把「装到哪个实例」并进同一屏 —— 检查之前必须先落定写入目标：
  //   后端 ModSyncGate（比对用 loader / MC 版本）和 ModSyncDownload（写文件）都只读
  //   activeInstance 解析出来的那一份 env，不收实例参数，所以只能在这一屏里先把它写对。
  // 同一屏而不是多开一步的理由：已配置实例的玩家下拉里就预先选中了当前实例，
  // 点击次数和以前一模一样（没被多问一步）；而没实例的玩家本来就要填目录，
  // 填完直接点 scope 就走完，拆成两屏反而每人多一次点击。
  function askScope(code) {
    return new Promise(function (resolve) {
      var list = msInstances();
      var active = (msView && msView.activeInstance) || "";
      var picked = msInstanceByName(active) ? active : (list.length ? list[0].name : "");
      var needNew = !list.length;

      function instanceBlock() {
        var html = '<div class="modsync-h">' + VL.esc(t("voxlink.app.modsync.pick_instance")) + "</div>" +
          '<div class="modsync-note">' + VL.esc(t("voxlink.app.modsync.pick_instance_hint")) + "</div>";
        if (needNew) {
          html += '<div class="modsync-warn">' + VL.esc(t("voxlink.app.modsync.no_instance")) + "</div>" +
            '<div style="display:flex;gap:8px;margin:6px 0">' +
            '<input class="input" id="ms-new-name" maxlength="24" style="flex:1 1 55%" placeholder="' +
            VL.esc(t("voxlink.app.modsync.instance_name_ph")) + '" value="' +
            VL.esc(t("voxlink.app.modsync.instance_name_default")) + '">' +
            '<select class="select" id="ms-new-loader" style="flex:1 1 45%">' +
            LOADERS.map(function (l) { return '<option value="' + l + '">' + l + "</option>"; }).join("") +
            "</select></div>" +
            '<div style="margin:6px 0"><input class="input" id="ms-new-dir" style="width:100%" placeholder="' +
            VL.esc(t("voxlink.app.modsync.mods_dir_ph")) + '" value="' +
            VL.esc((msView && msView.modsDir) || "") + '">' +
            '<button type="button" class="btn btn-ghost btn-sm" data-browse-dir>' +
            VL.esc(t("voxlink.app.modsync.browse")) + "</button></div>" +
            '<div style="margin:6px 0"><input class="input" id="ms-new-gv" maxlength="16" style="width:100%" placeholder="' +
            VL.esc(t("voxlink.app.modsync.gv_ph")) + '"></div>' +
            '<div class="modsync-note">' + VL.esc(t("voxlink.app.modsync.mods_dir_hint")) + "</div>";
          return html;
        }
        return html + '<div style="margin:6px 0"><select class="select" id="ms-inst" style="width:100%">' +
          list.map(function (it) {
            return '<option value="' + VL.esc(it.name) + '"' + (it.name === picked ? " selected" : "") + ">" +
              VL.esc(it.name) + (msLabel(it) ? "（" + VL.esc(msLabel(it)) + "）" : "") + "</option>";
          }).join("") + "</select>" +
          '<div class="modsync-note" id="ms-target-note"></div></div>';
      }

      function val(id) {
        var el = VL.$(id);
        return el ? String(el.value || "").trim() : "";
      }

      function paintTarget() {
        var note = VL.$("#ms-target-note");
        if (!note) return;
        var it = msInstanceByName(picked);
        note.textContent = (it && it.modsDir)
          ? t("voxlink.app.modsync.target_dir", it.name + " · " + it.modsDir)
          : t("voxlink.app.modsync.target_unnamed");
      }

      function showErr(msg) {
        var el = VL.$("#ms-inst-err");
        if (el) el.textContent = msg || "";
      }

      // 选中的实例就是后端当前生效的那个 → 不必为此回写一次全局设置。
      // （activeInstance 对不上任何实例时，后端 env() 用的就是 instances[0]，
      //  所以下拉预选落在第一个并不改变目标。）
      function alreadyTarget() {
        if (picked === active) return true;
        return !msInstanceByName(active) && list.length > 0 && picked === list[0].name;
      }

      // 把选定的实例落成后端那份 activeInstance；落不成（没填目录 / 保存失败）就留在本页。
      function ensureTarget() {
        if (!needNew) {
          if (alreadyTarget()) return Promise.resolve(true);
          return saveModSync({ activeInstance: picked }).then(function (r) { return r.ok; });
        }
        var dir = val("#ms-new-dir");
        if (!dir) {
          showErr(t("voxlink.app.modsync.need_dir"));
          return Promise.resolve(false);
        }
        var name = val("#ms-new-name") || t("voxlink.app.modsync.instance_name_default");
        var next = msInstances().filter(function (it) { return it.name !== name; });
        next.push({
          name: name,
          loader: val("#ms-new-loader") || "fabric",
          gameVersion: val("#ms-new-gv").slice(0, 16),
          modsDir: dir
        });
        return saveModSync({ instances: next, activeInstance: name }).then(function (r) {
          if (!r.ok) return false;
          needNew = false;
          active = name;
          picked = name;
          VL.toast(t("voxlink.app.modsync.saved_instance", name), "success");
          return true;
        });
      }

      function choose(scope) {
        showErr("");
        ensureTarget().then(function (ok) {
          if (!ok) return;
          closeModal();
          resolve(scope);
        });
      }

      openModal(t("voxlink.modsync.title"),
        t("voxlink.modsync.subtitle", code),
        instanceBlock() +
        '<div class="modsync-h">' + VL.esc(t("voxlink.modsync.scope_hint")) + "</div>" +
        '<div class="modsync-warn" id="ms-inst-err"></div>',
        '<button class="btn btn-primary" id="ms-required">' + VL.esc(t("voxlink.modsync.scope_required")) + "</button>" +
        '<button class="btn btn-ghost" id="ms-all">' + VL.esc(t("voxlink.modsync.scope_all")) + "</button>" +
        '<button class="btn btn-ghost" id="ms-skip">' + VL.esc(t("voxlink.modsync.skip_check")) + "</button>");
      modalView = "scope";

      var sel = VL.$("#ms-inst");
      if (sel) {
        sel.addEventListener("change", function () { picked = sel.value; paintTarget(); });
        paintTarget();
      }
      VL.$("#ms-required").addEventListener("click", function () { choose("required"); });
      VL.$("#ms-all").addEventListener("click", function () { choose("all"); });
      VL.$("#ms-skip").addEventListener("click", function () {
        VL.api("ModSyncBypass", code).catch(function () { });
        closeModal();
        resolve(null);
      });
    });
  }

  function runGate(code) {
    // askScope 里已经把「装到哪个实例」落成了 activeInstance：
    // 随后的 ModSyncGate（比对）与 ModSyncDownload（写入）都走它解析出的那一套
    // loader / gameVersion / modsDir，所以这里不需要再传任何参数。
    return askScope(code).then(function (scope) {
      if (!scope) return { action: "proceed" };
      return new Promise(function (resolve, reject) {
        joinFlow = { code: code, resolve: resolve, reject: reject };
        // 玩家在这个弹窗里先做了选择（直接进入 / 返回）之后，后端那份清单结果
        // 到达时不能再把窗弹回来 —— 这正是"点了直接进入窗还在、再点就重复加入"的成因。
        var settled = false;
        openModal(t(scope === "all" ? "voxlink.modsync.title_all" : "voxlink.modsync.title"),
          t(scope === "all" ? "voxlink.modsync.subtitle_all" : "voxlink.modsync.subtitle", code),
          '<div class="modsync-note">' + VL.esc(
            t(scope === "all" ? "voxlink.modsync.checking_all" : "voxlink.modsync.checking")) + "</div>",
          '<button class="btn btn-ghost" id="ms-skip2">' + VL.esc(t("voxlink.modsync.skip_check")) + "</button>" +
          '<button class="btn btn-ghost" id="ms-cancel2">' + VL.esc(t("voxlink.modsync.back")) + "</button>");
        modalView = "checking";
        VL.$("#ms-skip2").addEventListener("click", function () {
          if (settled) return;
          settled = true;
          VL.api("ModSyncBypass", code).catch(function () { });
          // finishJoin 负责关窗并放行加入；原来这里只发了一个 Bypass，
          // 窗不关、加入流程还挂着，玩家只能反复点。
          finishJoin(false);
        });
        VL.$("#ms-cancel2").addEventListener("click", function () {
          if (settled) return;
          settled = true;
          cancelJoin();
        });
        VL.api("ModSyncGate", JSON.stringify({ code: code, scope: scope })).then(function (res) {
          if (settled) return;
          res = res || {};
          if (res.pendingNote) pendingNote = { level: res.noteLevel, key: res.pendingNote.key || "" };
          if (res.action !== "choose") { resolve(res); return; }
          gateState = {
            code: res.code, scope: res.scope,
            downloadable: res.downloadable || [], incompatible: res.incompatible || [],
            notfound: res.notfound || [], unresolvable: res.unresolvable || [],
            versionDiff: res.versionDiff || [], skipKeys: res.skipKeys || [],
            // 后端只在"找不到/不兼容"非空时才启动兜底解析（modsync_guest.go），
            // 这里同步该条件，避免一直挂着"正在匹配模组"
            resolving: (res.notfound || []).length > 0 || (res.incompatible || []).length > 0,
            status: "", statusText: "", cancelling: false
          };
          renderDiff();
        }).catch(function (e) {
          // 门控自身失败绝不拦人：留一行强提示后放行（对应 gate_failed_warn）
          if (e.code !== "NO_BACKEND") pendingNote = { level: 2, key: "voxlink.modsync.gate_failed_warn" };
          resolve({ action: "proceed", outcome: "GATE_ERROR" });
        });
      });
    });
  }

  /* ---------------- 包装 VL.api：JoinRoom 前先过门控 ---------------- */
  var rawApi = VL.api;

  // 前置校验（2026-10-11 裁决）：房主没开启 mod 清单上传、或清单本来就是空的，
  // 就不该问玩家"检查范围/装到哪个实例"。后端 modSyncRoomSupportsManifest 那道同类判定
  // 跑在 ModSyncGate 里，也就是玩家答完弹窗之后，等于没有前置——Happy Link 房与空清单房
  // 因此照样弹窗。这里在弹之前先问一次 /room/mods。
  // 查询失败一律按"需要检查"处理：宁可多问一句，不擅自跳过玩家显式打开的开关。
  function roomNeedsModSync(code) {
    return rawApi("GetRoomMods", code).then(function (res) {
      if (!res || res.ok === false) return true;
      var d = res.data || {};
      if (d.supported === false) return false; // 房主未上传清单（含 Happy Link 镜像房）
      // 与后端门控同一条规则（modsync_guest.go:295）：必装清单与未知清单都空 = 无可同步内容
      var mods = d.mods || [], unknown = d.unknownMods || [];
      return !(mods.length === 0 && unknown.length === 0);
    }).catch(function () { return true; });
  }

  VL.api = async function (name) {
    var args = Array.prototype.slice.call(arguments, 1);
    if (name === "JoinRoom") {
      // 实例与两个开关是「创建房间 / 设置」页写的，本文件缓存的那份会过期
      // （以前只在启动时读一次：刚配好实例就去加入的玩家照样被判成"没配置"，
      //  于是门控整段被跳过）。加入前重新读一遍，顺带给 askScope 选实例备好数据。
      await loadSettings();
      if (settings.configured && settings.joinRequiredModsCheck) {
        var code = "";
        try { code = (JSON.parse(args[0] || "{}").code || "").toUpperCase(); } catch (e) { code = ""; }
        if (code && await roomNeedsModSync(code)) {
          VL.api("ModSyncClearGate", code).catch(function () { });
          await runGate(code); // 玩家取消 → 仍继续原有 JoinRoom 流程由上层按钮状态决定
        }
      }
    }
    return rawApi.apply(VL, [name].concat(args));
  };

  /* ---------------- 房间页：门控直通提示 flush + 房主清单面板 ---------------- */
  var hostPanelRoom = null; // 进房那次的 room：换语言时拿它重画一次房主面板，不重新问后端要会话
  var prevEnter = VL.enterRoomPage;
  VL.enterRoomPage = function (room, opts) {
    if (prevEnter) prevEnter.apply(VL, arguments);
    flushPendingNote();
    hostPanelRoom = room || null;
    renderHostPanel(room);
  };

  function flushPendingNote() {
    if (!pendingNote) return;
    var n = pendingNote;
    pendingNote = null;
    var body = VL.$("#netlog-body");
    if (!body) return;
    var level = n.level === 2 ? "warn" : "info";
    var lineEl = document.createElement("div");
    lineEl.className = "netlog-line netlog-" + level;
    lineEl.innerHTML = '<span class="netlog-ts">' + VL.esc(VL.relTime(Date.now())) + "</span>" +
      VL.esc(n.key ? t(n.key) : "");
    body.insertBefore(lineEl, body.firstChild);
  }

  async function renderHostPanel(room) {
    var host = room && room.isHost;
    var card = VL.$("#mods-body");
    if (!card) return;
    var holder = card.closest(".card");
    if (!holder) return;
    var box = VL.$("#modsync-host-panel", holder);
    if (!box) {
      box = document.createElement("div");
      box.id = "modsync-host-panel";
      box.className = "mini-note";
      box.style.cssText = "text-align:left;padding-top:0";
      holder.appendChild(box);
    }
    if (!host) { box.innerHTML = ""; return; }
    var html = "";
    try {
      var st = await rawApi("ModSyncHostStatus");
      if (!st || !st.configured) {
        html = '<div class="modsync-note">' + VL.esc(t("voxlink.app.modsync.not_configured")) + "</div>";
      } else {
        html = '<div class="modsync-h">' + VL.esc(t("voxlink.app.modsync.host_panel")) + "</div>" +
          '<div class="modsync-note">' + VL.esc([
            st.loader, st.mcVersion,
            (st.jarCount || 0) + " jar",
            t2("voxlink.app.modsync.required_count", "必装 %s", st.required || 0),
            t2("voxlink.app.modsync.all_count", "全部 %s", st.all || 0)
          ].filter(Boolean).join(" · ")) + "</div>" +
          '<div class="modsync-note">' + VL.esc(st.modsDir || "") + "</div>" +
          '<button class="btn btn-ghost btn-sm" id="modsync-rebuild">' +
          VL.esc(t("voxlink.app.modsync.rebuild")) + "</button>";
      }
    } catch (e) {
      html = '<div class="modsync-note">' + VL.esc(VL.errText(e)) + "</div>";
    }
    box.innerHTML = html;
    var btn = VL.$("#modsync-rebuild", box);
    if (btn) btn.addEventListener("click", function () {
      btn.disabled = true;
      rawApi("ModSyncRebuild").then(function () { VL.toast(t2("voxlink.app.modsync.rebuild_queued", "清单重建已排队"), "success"); },
        function (e) { if (e.code !== "NO_BACKEND") VL.toast(VL.errText(e), "error"); });
      btn.disabled = false;
    });
  }

  /* ---------------- 启动 ---------------- */
  function boot() {
    loadSettings();
    // MC 实例与两个必装开关都在「创建房间 / 设置」页配置（create.js / settings.js），
    // 加入侧只在门控前读一份现状；从没建过房的玩家由 askScope 就地补一个实例。

    // 换语言时把弹窗正停着的那一屏按已有数据重说一遍。「选实例」「检查中」两屏不动：
    // 前者的输入框里有玩家填到一半的东西，后者的按钮回调关在那一屏的闭包里，
    // 重跑一次要么清空输入要么丢掉回调；这两屏等后端下一条消息自然会用新语言重画。
    VL.onLangChange(function () {
      if (modalView === "diff" && gateState) renderDiff();
      else if (modalView === "progress" && lastProgress) renderProgress(lastProgress);
      else if (modalView === "restart" && lastRestart) renderDownloadDone(lastRestart);
      if (hostPanelRoom) renderHostPanel(hostPanelRoom);
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})(window.VL);
