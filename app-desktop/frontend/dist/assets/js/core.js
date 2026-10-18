/* ============================================================
 * VoxLink Desktop — core.js
 * api 封装 / toast / 图标 / 通用工具
 * ============================================================ */
"use strict";

window.VL = window.VL || {};

(function (VL) {
  /* ---------------- 图标（内联 SVG，stroke: currentColor） ---------------- */
  var ICONS = {
    home: '<path d="M3 11l9-8 9 8"/><path d="M5 9.5V20a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9.5"/>',
    join: '<rect x="3" y="4" width="18" height="16" rx="3"/><path d="M8 12h8m0 0-3.5-3.5M16 12l-3.5 3.5"/>',
    create: '<circle cx="12" cy="12" r="9"/><path d="M12 8v8M8 12h8"/>',
    room: '<rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/>',
    link: '<path d="M10 14a5 5 0 0 0 7.07 0l1.9-1.9a5 5 0 0 0-7.07-7.07l-1.06 1.06"/><path d="M14 10a5 5 0 0 0-7.07 0l-1.9 1.9a5 5 0 0 0 7.07 7.07l1.06-1.06"/>',
    settings: '<path d="M4 7h16M4 12h16M4 17h16"/><circle cx="9" cy="7" r="2" fill="var(--bg-elev)"/><circle cx="15" cy="12" r="2" fill="var(--bg-elev)"/><circle cx="7" cy="17" r="2" fill="var(--bg-elev)"/>',
    refresh: '<path d="M20 12a8 8 0 1 1-2.34-5.66"/><path d="M20 4v4.5h-4.5"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="m20.5 20.5-4.2-4.2"/>',
    copy: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/>',
    users: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c.8-3.3 3.4-5 6.5-5s5.7 1.7 6.5 5"/><circle cx="17" cy="9" r="2.5"/><path d="M16.2 15.3c2.4.4 4.4 1.9 5.1 4.7"/>',
    lock: '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
    signal: '<path d="M4.9 19.1a10 10 0 0 1 0-14.2M19.1 4.9a10 10 0 0 1 0 14.2M7.8 16.2a6 6 0 0 1 0-8.4M16.2 7.8a6 6 0 0 1 0 8.4"/><circle cx="12" cy="12" r="1.5"/>',
    log: '<path d="m5 7 4 5-4 5"/><path d="M12 17h7"/>',
    mods: '<rect x="3" y="3" width="8" height="8" rx="1.5"/><rect x="13" y="3" width="8" height="8" rx="1.5"/><rect x="3" y="13" width="8" height="8" rx="1.5"/><path d="M17 13.5v7M13.5 17h7"/>',
    relay: '<circle cx="12" cy="18" r="2"/><path d="M12 16v-6"/><path d="M8.5 5.5a6 6 0 0 1 7 0M7 9a9 9 0 0 1 10 0"/>',
    leave: '<path d="M15 4h4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-4"/><path d="M10 8l-4 4 4 4M6 12h10"/>',
    ticket: '<path d="M3 9V6.5A1.5 1.5 0 0 1 4.5 5h15A1.5 1.5 0 0 1 21 6.5V9a2.2 2.2 0 0 0 0 6v2.5a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5V15a2.2 2.2 0 0 0 0-6Z"/><path d="M13 8v8" stroke-dasharray="2 2"/>',
    external: '<path d="M14 4h6v6"/><path d="M20 4 11 13"/><path d="M19 13.5V19a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h5.5"/>',
    check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>'
  };

  VL.iconSvg = function (name) {
    var p = ICONS[name] || ICONS.link;
    return (
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + p + "</svg>"
    );
  };

  VL.hydrateIcons = function (root) {
    var nodes = (root || document).querySelectorAll("[data-ico]");
    for (var i = 0; i < nodes.length; i++) {
      nodes[i].innerHTML = VL.iconSvg(nodes[i].getAttribute("data-ico"));
    }
  };

  /* ---------------- DOM / 字符串工具 ---------------- */
  VL.$ = function (sel, root) { return (root || document).querySelector(sel); };
  VL.$$ = function (sel, root) {
    return Array.prototype.slice.call((root || document).querySelectorAll(sel));
  };

  VL.esc = function (s) {
    if (s === undefined || s === null) return "";
    return String(s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  };

  // 目录选择：任何带 data-browse-dir 的按钮，点击后把选中的路径写回它前面那个输入框，
  // 并补发一次 change，让既有的联动（校验/回写）照常跑。后端没有对话框能力时返回空串，
  // 此时什么都不改，玩家填的原值保持不动。
  document.addEventListener("click", function (ev) {
    var btn = ev.target && ev.target.closest ? ev.target.closest("[data-browse-dir]") : null;
    if (!btn) return;
    ev.preventDefault();
    var input = btn.previousElementSibling;
    if (!input || input.tagName !== "INPUT") return;
    VL.api("SelectModsDir").then(function (res) {
      var path = (res && res.path) || "";
      if (!path) return;
      input.value = path;
      input.dispatchEvent(new Event("change", { bubbles: true }));
      input.dispatchEvent(new Event("input", { bubbles: true }));
    }, function () { /* 无后端/无对话框能力：静默，别弹错误打扰玩家 */ });
  });

  VL.fmtSize = function (bytes) {
    var n = Number(bytes);
    if (!isFinite(n) || n <= 0) return "-";
    if (n < 1024) return n + " B";
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
    return (n / 1024 / 1024).toFixed(2) + " MB";
  };

  VL.relTime = function (ts) {
    var t = Number(ts);
    if (!isFinite(t) || t <= 0) return "";
    var diff = Math.max(0, Date.now() - t);
    if (diff < 10 * 1000) return VL.t("voxlink.app.time.just", "刚刚");
    if (diff < 60 * 1000) return VL.t("voxlink.app.time.seconds_ago", "%s 秒前", Math.floor(diff / 1000));
    if (diff < 60 * 60 * 1000) return VL.t("voxlink.app.time.minutes_ago", "%s 分钟前", Math.floor(diff / 60000));
    if (diff < 24 * 60 * 60 * 1000) return VL.t("voxlink.app.time.hours_ago", "%s 小时前", Math.floor(diff / 3600000));
    return VL.t("voxlink.app.time.days_ago", "%s 天前", Math.floor(diff / 86400000));
  };

  VL.debounce = function (fn, wait) {
    var timer = null;
    return function () {
      var args = arguments, self = this;
      clearTimeout(timer);
      timer = setTimeout(function () { fn.apply(self, args); }, wait);
    };
  };

  /* ---------------- 后端调用封装 ---------------- */
  // window.go.main.App.X(参数) 全部返回 Promise<string>(JSON)。
  // 统一解析 {ok:true,data} / {ok:false,error,message}。
  VL.api = async function (name) {
    var bridge = window.go && window.go.main && window.go.main.App;
    if (!bridge || typeof bridge[name] !== "function") {
      var err = new Error(VL.t("voxlink.app.error.no_backend", "请在桌面 App 中运行"));
      err.code = "NO_BACKEND";
      VL.toast(err.message, "error");
      throw err;
    }
    var args = Array.prototype.slice.call(arguments, 1);
    var raw;
    try {
      raw = await bridge[name].apply(bridge, args);
    } catch (e) {
      var callErr = new Error((e && e.message) || VL.t("voxlink.app.error.bridge_failed", "调用后端失败"));
      callErr.code = "BRIDGE_ERROR";
      throw callErr;
    }
    var res;
    try {
      res = JSON.parse(raw);
    } catch (e) {
      return raw; // 非 JSON（理论上不会发生），原样返回
    }
    if (res && res.ok === false) {
      var apiErr = new Error(res.message || res.error || VL.t("voxlink.app.error.request_failed", "请求失败"));
      apiErr.code = res.error || "UNKNOWN";
      throw apiErr;
    }
    return res && res.ok === true ? res.data : res;
  };

  // 订阅后端事件（无 runtime 时静默忽略）
  VL.on = function (event, cb) {
    if (window.runtime && typeof window.runtime.EventsOn === "function") {
      window.runtime.EventsOn(event, cb);
    }
  };

  /* ---------------- 多语言（i18n） ----------------
   * 语言包只由后端解析：GetSettings 给"用户选了哪种"（auto 或具体代码），
   * GetLangPack 给那份语言的扁平文案表，目标语言→zh_cn→en_us 的回落链在 Go 侧
   * （langui.go:121-142），前端不再重算一遍，只负责"取回来 + 查表 + 套用"。
   * 之所以在前端缓存整张表而不是逐键问后端：界面每帧要查几十个键，桥接调用是异步的。 */
  var I18N_ATTRS = [
    ["data-i18n", "text"],
    ["data-i18n-placeholder", "placeholder"],
    ["data-i18n-title", "title"],
    ["data-i18n-aria", "aria-label"]
  ];

  // 语言名一律用该语言自己的写法（"简体中文"而不是"Chinese"）：这类专有名称不随
  // 界面语言变化，任何语言下都应显示成本族文字，所以内联在这里而不是走文案表。
  var LANG_NAMES = {
    ar_sa: "العربية",
    de_de: "Deutsch",
    en_us: "English",
    es_es: "Español",
    fr_fr: "Français",
    ja_jp: "日本語",
    ko_kr: "한국어",
    lzh: "文言文",
    pt_br: "Português (Brasil)",
    ru_ru: "Русский",
    zh_cn: "简体中文",
    zh_hk: "繁體中文（香港）",
    zh_tw: "繁體中文（台灣）"
  };
  VL.langNames = LANG_NAMES;
  VL.langName = function (code) { return LANG_NAMES[code] || code; };

  VL.lang = { code: "zh_cn", setting: "auto", strings: {}, available: [] };

  VL.hasKey = function (key) {
    return typeof VL.lang.strings[key] === "string";
  };

  // %s / %d / %1$s / %% —— 与模组 lang 文件里的占位符写法同一套（Java String.format 的子集）。
  // 位置参数用 %n$s，顺序参数与位置参数允许混用（顺序计数只在无前缀时前进）。
  function fmtPlaceholders(str, args) {
    if (!args.length || str.indexOf("%") < 0) return str;
    var seq = 0;
    return str.replace(/%%|%(?:(\d+)\$)?([sd])/g, function (all, idx, type) {
      if (all === "%%") return "%";
      var at = idx ? Number(idx) - 1 : seq++;
      var v = args[at];
      if (v === undefined || v === null) return "";
      if (type === "d") {
        var n = Number(v);
        return isFinite(n) ? String(Math.round(n)) : String(v);
      }
      return String(v);
    });
  }

  /**
   * VL.t(key, 中文原文, ...参数)
   * 第二个参数是"这个键在当前界面上原本显示的那句中文"：语言包缺键（应用端自有文案、
   * 或后端不可用）时直接回落到它。选择"调用处传兜底"而不是内联一整份中文表，是因为
   * 兜底文本就写在用它的地方，不会和 HTML/JS 里的原文两处失配，也不必把 470 键抄进 JS。
   */
  VL.t = function (key, fallback) {
    var args = Array.prototype.slice.call(arguments, 2);
    var s = VL.lang.strings[key];
    if (s === undefined) s = fallback;
    if (s === undefined) s = key; // 连兜底都没给：保持原样显示键名，至少能定位问题
    if (typeof s !== "string") s = String(s);
    return fmtPlaceholders(s, args);
  };

  // 静默版桥接调用：语言包拿不到时界面必须照常显示中文，不能像 VL.api 那样弹 toast。
  async function bridgeJson(name, arg) {
    var bridge = window.go && window.go.main && window.go.main.App;
    if (!bridge || typeof bridge[name] !== "function") return null;
    try {
      var raw = arg === undefined ? await bridge[name]() : await bridge[name](arg);
      var res = typeof raw === "string" ? JSON.parse(raw) : raw;
      if (res && res.ok === false) return null;
      return res && res.ok === true ? res.data : res;
    } catch (e) {
      return null;
    }
  }

  function systemLangTags() {
    var list = (navigator.languages && navigator.languages.length)
      ? Array.prototype.slice.call(navigator.languages)
      : [navigator.language || navigator.userLanguage || ""];
    return list.filter(Boolean);
  }

  // 后端只会"按传来的这一个标签"解析，无法告知它是否真命中；返回的 code 就是它最终用的包，
  // 拿它的语言段与标签的主语言段一比，就知道该不该继续试下一个系统语言。
  function packMatchesTag(code, tag) {
    var base = String(tag).toLowerCase().replace(/_/g, "-").split("-")[0];
    return String(code || "").toLowerCase().split(/[_-]/)[0] === base;
  }

  VL.reloadLang = async function () {
    var auto = !VL.lang.setting || VL.lang.setting === "auto";
    var tags = auto ? systemLangTags() : [VL.lang.setting];
    var last = null;
    for (var i = 0; i < tags.length && i < 4; i++) {
      var pack = await bridgeJson("GetLangPack", tags[i]);
      if (!pack) break;
      last = pack;
      if (!auto || packMatchesTag(pack.code, tags[i])) break;
    }
    if (last) {
      VL.lang.code = last.code || VL.lang.code;
      VL.lang.strings = last.strings || {};
    }
    applyDocumentDirection(VL.lang.code);
    return VL.lang;
  };

  // RTL 语言要镜像版面（与主站 index.html 的 dir 口径一致）：
  // 只把文字反排而标签仍靠左，阿拉伯语读起来是"半拉子 RTL"，标题与内容对不上边。
  function applyDocumentDirection(code) {
    var el = document.documentElement;
    if (!el) return;
    el.dir = /^ar_/i.test(code || "") ? "rtl" : "ltr";
    if (code) el.lang = String(code).replace(/_/g, "-").toLowerCase();
  }

  // 启动时先取语言设置再取包；无后端时两者都静默失败，strings 留空 → 全部显示兜底中文。
  VL.initLang = async function () {
    var settings = await bridgeJson("GetSettings");
    if (settings) {
      VL.lang.setting = settings.language || "auto";
      if (Array.isArray(settings.languages)) VL.lang.available = settings.languages;
    }
    await VL.reloadLang();
    return VL.lang;
  };

  // 换语言：以 SaveSettings 的返回值为准（非法代码会被后端原样退回，不能信刚传进去的），
  // 然后重取语言包、重刷静态文案，再逐个回调各页重画自己渲染出来的那部分。
  VL.setLanguage = async function (code) {
    var saved = await bridgeJson("SaveSettings", JSON.stringify({ language: code }));
    if (saved && saved.language) {
      VL.lang.setting = saved.language;
      if (Array.isArray(saved.languages)) VL.lang.available = saved.languages;
    }
    await VL.reloadLang();
    VL.applyI18n(document);
    notifyLangChange();
    return VL.lang.setting;
  };

  var langHooks = [];
  VL.onLangChange = function (fn) {
    if (typeof fn === "function") langHooks.push(fn);
  };
  function notifyLangChange() {
    for (var i = 0; i < langHooks.length; i++) {
      try { langHooks[i](); } catch (e) { /* 某一页重画失败不该拖垮其它页 */ }
    }
  }

  // 只改文本节点/属性，绝不新建或包裹节点：按钮里"图标 + 裸文字"这种写法很常见，
  // 直接赋 textContent 会把图标 span 一起抹掉。
  function setElText(el, text) {
    var nodes = [];
    for (var n = el.firstChild; n; n = n.nextSibling) {
      if (n.nodeType === 3 && n.nodeValue.trim()) nodes.push(n);
    }
    if (nodes.length === 1) { nodes[0].nodeValue = text; return; }
    if (!nodes.length && !el.children.length) { el.textContent = text; return; }
    if (!el.children.length) el.textContent = text; // 多段裸文本：整体替换（当前页面里没有这种）
  }

  VL.applyI18n = function (root) {
    var box = root || document;
    for (var a = 0; a < I18N_ATTRS.length; a++) {
      var attr = I18N_ATTRS[a][0], kind = I18N_ATTRS[a][1];
      var nodes = box.querySelectorAll("[" + attr + "]");
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        var key = el.getAttribute(attr);
        if (!key) continue;
        var s = VL.lang.strings[key];
        if (typeof s !== "string") continue; // 缺键：保持 HTML 里原有的中文，别写空白
        if (kind === "text") setElText(el, s);
        else el.setAttribute(kind, s);
      }
    }
  };

  /* ---------------- 错误文案 ---------------- */
  // 每条 = [语言键, 当前界面原本的中文]，见 VL.t 的兜底约定。
  var ERROR_TEXT = {
    RATE_LIMITED: ["voxlink.join_room.error.rate_limited", "请求太快啦，休息一下再试 qwq"],
    ROOM_NOT_FOUND_OR_WRONG_PASSWORD: ["voxlink.app.error.room_not_found_or_pw", "找不到这个房间 qwq 或密码不对"],
    ROOM_FULL: ["voxlink.error.room_full", "房间满了，稍后再来吧 :("],
    NO_BACKEND: ["voxlink.app.error.no_backend", "请在桌面 App 中运行"],
    NETWORK_ERROR: ["voxlink.error.network_error", "连不上服务器，检查一下网络或服务器地址吧"]
  };

  VL.errText = function (err) {
    if (!err) return VL.t("voxlink.error.unknown", "出了点小问题");
    if (err.code && ERROR_TEXT[err.code]) return VL.t(ERROR_TEXT[err.code][0], ERROR_TEXT[err.code][1]);
    return err.message || VL.t("voxlink.error.unknown", "出了点小问题");
  };

  /* ---------------- Toast ---------------- */
  var toastBox = null;
  var lastToast = { msg: "", t: 0 };

  VL.toast = function (msg, type) {
    if (!toastBox) toastBox = document.getElementById("toast-box");
    if (!toastBox) return;
    // 2 秒内相同消息去重，避免无后端时重复刷屏
    var now = Date.now();
    if (msg === lastToast.msg && now - lastToast.t < 2000) return;
    lastToast = { msg: msg, t: now };

    var el = document.createElement("div");
    el.className = "toast" + (type ? " toast-" + type : "");
    el.textContent = msg;
    toastBox.appendChild(el);
    setTimeout(function () {
      el.classList.add("out");
      setTimeout(function () { el.remove(); }, 300);
    }, 2600);
  };

  /* ---------------- 剪贴板 ---------------- */
  VL.copy = async function (text) {
    var ok = false;
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(text);
        ok = true;
      }
    } catch (e) { /* 走降级 */ }
    if (!ok) {
      try {
        var ta = document.createElement("textarea");
        ta.value = text;
        ta.style.position = "fixed";
        ta.style.opacity = "0";
        document.body.appendChild(ta);
        ta.select();
        ok = document.execCommand("copy");
        ta.remove();
      } catch (e) { ok = false; }
    }
    VL.toast(
      ok ? VL.t("voxlink.chat.copied_to_clipboard", "已复制: %s", text)
         : VL.t("voxlink.app.copy.failed", "复制失败了 qwq"),
      ok ? "success" : "error"
    );
    return ok;
  };

  /* ---------------- 主题 ---------------- */
  VL.applyTheme = function (theme) {
    var t = theme === "dark" ? "dark" : "light";
    document.documentElement.setAttribute("data-theme", t);
    return t;
  };

  VL.themes = { current: "light" };
})(window.VL);
