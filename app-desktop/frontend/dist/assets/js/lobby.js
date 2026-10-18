/* ============================================================
 * VoxLink Desktop — lobby.js
 * 大厅：搜索 / 分类 / 加载器筛选 / 版本筛选 / 房间卡片网格 / 分页 / 公网 IP
 * ============================================================ */
"use strict";

(function (VL) {
  var state = {
    page: 1,
    size: 12,
    category: "",
    loader: "",
    search: "",
    version: "",      // 版本筛选："" = 全部版本；"__none__" = 未指定；其余为规范化后的版本键
    total: 0,
    categories: {},   // key -> 中文名
    loading: false,
    excludeHL: false  // 「排除 Happy Link」开关，只过滤当前这一页，不写盘也不改服务端查询
  };

  var els = {};

  function cacheEls() {
    els.grid = VL.$("#lobby-grid");
    els.empty = VL.$("#lobby-empty");
    els.pager = VL.$("#lobby-pager");
    els.nums = VL.$("#pager-nums");
    els.prev = VL.$("#pager-prev");
    els.next = VL.$("#pager-next");
    els.search = VL.$("#lobby-search");
    els.category = VL.$("#lobby-category");
    els.loaders = VL.$("#lobby-loaders");
    els.excludeHL = VL.$("#lobby-exclude-hl");
    els.refresh = VL.$("#lobby-refresh");
    els.ip = VL.$("#lobby-ip");
  }

  /* ---------------- 分类 ---------------- */
  async function loadCategories() {
    try {
      var data = await VL.api("GetCategories");
      // 后端 data 直接就是 {survival:"生存",...} 这样的 map（老代码误当成
      // {categories:{...}} 取了一层不存在的 key，导致下拉永远只有未分类）。
      // 这里做兼容：两种形状都能解析。
      var map = (data && typeof data === "object" && !Array.isArray(data)) ? data : {};
      if (map.categories && typeof map.categories === "object" && !Array.isArray(map.categories)) {
        map = map.categories;
      }
      state.categories = map;
    } catch (e) {
      state.categories = {};
    }
    var cats = Object.keys(state.categories);
    // 大厅筛选下拉
    var allCats = VL.t("voxlink.app.lobby.category_all", "全部分类");
    var html = '<option value="">' + allCats + "</option>";
    cats.forEach(function (key) {
      html += '<option value="' + VL.esc(key) + '">' + VL.esc(state.categories[key] || key) + "</option>";
    });
    els.category.innerHTML = html;
    // 创建页 / 房间设置的下拉（若有）；创建页额外带“自定义”选项
    var opts = '<option value="">' + VL.t("voxlink.app.category.none", "未分类") + "</option>" +
      html.replace('<option value="">' + allCats + "</option>", "");
    var createEl = VL.$("#create-category");
    if (createEl) createEl.innerHTML = opts + '<option value="__custom__">' +
      VL.t("voxlink.app.category.custom_more", "自定义…") + "</option>";
    var hsEl = VL.$("#hs-category");
    if (hsEl) hsEl.innerHTML = opts;
    if (VL.onCategoriesLoaded) VL.onCategoriesLoaded();
  }

  function catName(key) {
    if (!key) return VL.t("voxlink.app.category.none", "未分类");
    return state.categories[key] || key;
  }

  // 只改"应用端自己写的那几个选项"的文字（全部分类 / 未分类 / 自定义…）。
  // 换语言时不能靠重建 innerHTML 来刷新：那会把玩家正选着的筛选条件一起抹掉。
  function setOptionLabel(sel, value, key, cn) {
    if (!sel) return;
    var opt = sel.querySelector('option[value="' + value + '"]');
    if (opt) opt.textContent = VL.t(key, cn);
  }

  function refreshOptionLabels() {
    setOptionLabel(els.category, "", "voxlink.app.lobby.category_all", "全部分类");
    var createEl = VL.$("#create-category");
    setOptionLabel(createEl, "", "voxlink.app.category.none", "未分类");
    setOptionLabel(createEl, "__custom__", "voxlink.app.category.custom_more", "自定义…");
    setOptionLabel(VL.$("#hs-category"), "", "voxlink.app.category.none", "未分类");
  }

  /* ---------------- 房间列表 ---------------- */
  function skeleton(count) {
    var html = "";
    for (var i = 0; i < count; i++) {
      html +=
        '<div class="card skel-card">' +
        '  <div class="skel" style="height:20px;width:70%"></div>' +
        '  <div class="skel" style="height:14px;width:45%"></div>' +
        '  <div class="skel" style="height:14px;width:90%"></div>' +
        "</div>";
    }
    return html;
  }

  function roomCard(r) {
    var players = (r.currentPlayers !== undefined ? r.currentPlayers : "?") +
      " / " + (r.maxPlayers !== undefined ? r.maxPlayers : "?");
    var regionIsp = [r.region, r.isp].filter(Boolean).join(" · ");

    var tags = "";
    tags += '<span class="pill pill-accent">' + VL.esc(catName(r.category)) + "</span>";
    if (r.loader) tags += '<span class="pill">' + VL.esc(r.loader) + "</span>";
    if (r.gameVersion) tags += '<span class="pill">' + VL.esc(r.gameVersion) + "</span>";
    // 来源标签：CLI `room create --client-tag` 传了什么就显示什么（≤10 字，可为中文）。
    // 没传标签时回落原口径：应用端房显示"应用端"，模组房显示它的 clientType。
    if (r.clientTag) {
      tags += '<span class="pill pill-app" title="' + VL.esc(VL.t("voxlink.app.room.tag_creator_title", "该房间的创建端")) + '">' +
        VL.esc(r.clientTag) + "</span>";
    } else if (r.clientType === "app") {
      tags += '<span class="pill pill-app" title="' +
        VL.esc(VL.t("voxlink.app.room.tag_app_title", "该房间由 VoxLink 应用端创建")) + '">' +
        VL.esc(VL.t("voxlink.app.room.tag_app", "应用端")) + "</span>";
    } else if (r.clientType) {
      tags += '<span class="pill">' + VL.esc(r.clientType) + "</span>";
    }
    if (r.protocolVersion) {
      tags += '<span class="pill">' + VL.esc(VL.t("voxlink.app.room.protocol", "协议 v%s", r.protocolVersion)) + "</span>";
    }
    // 互通：Happy Link 来源徽标（模组 RoomBrowserScreenBase.java:697-703，
    // 品牌名 "Happy Link" 全语言不翻译，判据是 r.source === "happylink" 精确相等）
    if (r.source === "happylink") {
      tags += '<span class="pill pill-accent" title="' +
        VL.esc(VL.t("voxlink.app.room.hl_title", "Happy Link 互通房间")) + '">Happy Link</span>';
    }

    return (
      '<div class="card card-hoverable room-card">' +
      '  <div class="room-card-top">' +
      '    <div class="room-name" title="' + VL.esc(r.name || "") + '">' +
      VL.esc(r.name || VL.t("voxlink.app.room.unnamed", "未命名房间")) +
      (r.hasPassword ? '<span class="ico" title="' + VL.esc(VL.t("voxlink.app.join.need_password", "需要密码")) +
        '">' + VL.iconSvg("lock") + "</span>" : "") +
      (r.nameApproved ? '<span class="pill pill-success" title="' +
        VL.esc(VL.t("voxlink.app.room.name_approved_title", "房名已审核")) + '">' +
        VL.esc(VL.t("voxlink.app.room.name_approved", "已审核")) + "</span>" : "") +
      "    </div>" +
      '    <button class="code-chip" data-code="' + VL.esc(r.code || "") + '" title="' +
      VL.esc(VL.t("voxlink.app.room.code_copy_title", "点击复制房间代码")) + '">' +
      VL.esc(r.code || "------") + '<span class="ico">' + VL.iconSvg("copy") + "</span></button>" +
      "  </div>" +
      '  <div class="room-tags">' + tags + "</div>" +
      '  <div class="room-card-bottom">' +
      '    <div class="room-card-meta">' +
      '      <span class="room-players-mini"><span class="ico">' + VL.iconSvg("users") + "</span>" + VL.esc(players) + "</span>" +
      '      <span class="room-region">' + VL.esc(regionIsp || "") + "</span>" +
      "    </div>" +
      '    <button class="btn btn-primary btn-sm" data-join="' + VL.esc(r.code || "") + '" data-has-pw="' +
      (r.hasPassword ? "1" : "0") + '">' + VL.esc(VL.t("voxlink.app.action.join", "加入")) + "</button>" +
      "  </div>" +
      "</div>"
    );
  }

  // 「排除 Happy Link」：纯前端过滤当前这一页，与网页端同名开关同一口径
  // （web/index.html 的 excludeHL 也不写 localStorage、不改服务端查询）。
  // 因此开着它时同一页可能少几张卡，分页总数仍按服务端返回算——网页端同样如此。
  // 版本筛选走同一套路：只在前端过当前这一页，不碰 ListRooms 的参数。
  function visibleRooms() {
    var rooms = state.rooms || [];
    if (state.excludeHL) {
      rooms = rooms.filter(function (r) { return r.source !== "happylink"; });
    }
    if (state.version) {
      rooms = rooms.filter(function (r) { return versionMatches(r.gameVersion, state.version); });
    }
    return rooms;
  }

  /* ---------------- 版本筛选 ---------------- */
  // 口径对齐网页端（server-go/web/index.html 的 filterRooms / versionFilter）：
  // 1) 候选不查服务端，全部从"当前已取到的那一页房间"的 gameVersion 里取，按语义
  //    （忽略首尾空格与大小写）去重、保留首次出现的写法与顺序；
  // 2) "全部"用空串表示，跟网页端 versionFilter 留空、分类下拉 value="" 同一套写法；
  // 3) 与搜索 / 分类 / 加载器 / 排除 Happy Link 是"与"关系，全部条件同时成立才保留。
  // 网页端的版本框是自由文本，判据是 rv===v || rv.startsWith(v+".")（小写比较）；
  // 下拉里每个候选就是完整版本号，所以规范化后精确相等即可，是网页端口径的退化形式。
  // gameVersion 为空或 "unknown" 归到单独一项「未指定」：这些房间照常出现在网格里，
  // 不给候选项就永远筛不到它们；把它们混进真实版本又会破坏"某版本"筛选的准确性。
  var VERSION_NONE = "__none__";

  // 版本筛选做成输入框，与网页端一致：输 "1.20" 要能命中 "1.20.1"（网页端判据
  // rv === v || rv.startsWith(v + ".")，小写比较），做成下拉就只能整版本精确选，
  // 玩家想"看所有 1.20.x"就点不出来。
  function versionMatches(roomVersion, query) {
    var rv = String(roomVersion == null ? "" : roomVersion).trim().toLowerCase();
    var q = query.trim().toLowerCase();
    if (!q) return true;
    return rv === q || rv.indexOf(q + ".") === 0;
  }

  // 动态创建输入框，插到分类下拉右侧（同一 toolbar 行内），省掉改 index.html
  function ensureVersionFilter() {
    els.version = VL.$("#lobby-version");
    if (els.version) return;
    if (!els.category || !els.category.parentNode) return;
    var inp = document.createElement("input");
    inp.type = "text";
    inp.className = "input";
    inp.id = "lobby-version";
    inp.maxLength = 16;
    inp.autocomplete = "off";
    inp.placeholder = VL.t("voxlink.app.lobby.version_ph", "版本");
    inp.title = VL.t("voxlink.app.lobby.version_title", "按 Minecraft 版本筛选，输 1.20 可命中 1.20.1");
    inp.value = state.version || "";
    els.category.parentNode.insertBefore(inp, els.category.nextSibling);
    els.version = inp;
  }

  function render() {
    if (state.loading) {
      els.grid.innerHTML = skeleton(8);
      els.empty.classList.add("hidden");
      els.pager.classList.add("hidden");
      return;
    }
    var rooms = visibleRooms();
    if (!rooms.length) {
      els.grid.innerHTML = "";
      els.empty.classList.remove("hidden");
      els.pager.classList.add("hidden");
      return;
    }
    els.empty.classList.add("hidden");
    els.grid.innerHTML = rooms.map(roomCard).join("");

    // 分页
    var totalPages = Math.max(1, Math.ceil((state.total || 0) / state.size));
    if (totalPages > 1) {
      els.pager.classList.remove("hidden");
      els.prev.disabled = state.page <= 1;
      els.next.disabled = state.page >= totalPages;
      els.nums.innerHTML = pageNumbers(state.page, totalPages)
        .map(function (p) {
          if (p === "...") return '<span class="pager-num ellipsis">…</span>';
          return '<button class="pager-num' + (p === state.page ? " active" : "") + '" data-page="' + p + '">' + p + "</button>";
        })
        .join("");
    } else {
      els.pager.classList.add("hidden");
    }
  }

  function pageNumbers(cur, total) {
    var out = [];
    var win = 2; // 当前页两侧各显示 2 页
    for (var i = 1; i <= total; i++) {
      if (i === 1 || i === total || (i >= cur - win && i <= cur + win)) {
        out.push(i);
      } else if (out[out.length - 1] !== "...") {
        out.push("...");
      }
    }
    return out;
  }

  async function fetchRooms() {
    state.loading = true;
    render();
    try {
      var data = await VL.api("ListRooms", JSON.stringify({
        page: state.page,
        size: state.size,
        category: state.category,
        loader: state.loader,
        search: state.search
      }));
      state.rooms = (data && data.rooms) || [];
      state.total = (data && data.total) || 0;
      var totalPages = Math.max(1, Math.ceil(state.total / state.size));
      if (state.page > totalPages) { state.page = totalPages; return fetchRooms(); }
    } catch (e) {
      state.rooms = [];
      state.total = 0;
      VL.toast(VL.errText(e), "error");
    }
    state.loading = false;
    render();
  }

  function goPage(p) {
    state.page = p;
    fetchRooms();
    var main = VL.$("#main");
    if (main) main.scrollTop = 0;
  }

  /* ---------------- 公网 IP ---------------- */
  async function loadIp() {
    try {
      var data = await VL.api("GetStun");
      var parts = [];
      if (data && data.ip) parts.push("IPv4: " + data.ip);
      if (data && data.ipv6) parts.push("IPv6: " + data.ipv6);
      els.ip.textContent = parts.length
        ? VL.t("voxlink.app.lobby.my_ip", "你的公网 IP　%s", parts.join("　"))
        : VL.t("voxlink.app.lobby.ip_failed", "暂时拿不到你的公网 IP qwq");
    } catch (e) {
      els.ip.textContent = VL.t("voxlink.app.lobby.ip_failed", "暂时拿不到你的公网 IP qwq");
    }
  }

  /* ---------------- 大厅快捷加入 ---------------- */
  function closeJoinPops() {
    VL.$$(".join-pop", els.grid).forEach(function (p) { p.remove(); });
  }

  function lobbyJoin(code, password) {
    if (!code) return;
    VL.api("JoinRoom", JSON.stringify({ code: code, password: password || "" }))
      .then(function (data) {
        closeJoinPops();
        VL.toast(VL.t("voxlink.app.join.success", "加入成功，欢迎 awa"), "success");
        // 走统一入口：跳房间页，房客连接卡自动开始 P2P 打洞阶段
        if (VL.onJoined) VL.onJoined(data);
      })
      .catch(function (e) {
        if (e.code === "ROOM_NOT_FOUND_OR_WRONG_PASSWORD") {
          VL.toast(VL.t("voxlink.app.error.room_not_found_or_pw", "找不到这个房间 qwq 或密码不对"), "error");
        } else if (e.code !== "NO_BACKEND") {
          VL.toast(VL.errText(e), "error");
        }
      });
  }

  // 有密码的房间：内联密码输入小浮层
  function openJoinPop(card, code) {
    closeJoinPops();
    var pop = document.createElement("div");
    pop.className = "join-pop";
    pop.innerHTML =
      '<input type="password" class="input" placeholder="' +
      VL.esc(VL.t("voxlink.app.join.pw_ph", "这个房间设置了密码")) + '" autocomplete="off">' +
      '<button class="btn btn-primary btn-sm">' +
      VL.esc(VL.t("voxlink.app.join.confirm", "确认加入")) + "</button>";
    card.appendChild(pop);
    var input = pop.querySelector("input");
    var btn = pop.querySelector("button");
    var go = function () { lobbyJoin(code, input.value); };
    btn.addEventListener("click", go);
    input.addEventListener("keydown", function (ev) {
      if (ev.key === "Enter") go();
      if (ev.key === "Escape") closeJoinPops();
    });
    input.focus();
  }

  /* ---------------- 初始化 ---------------- */
  VL.initLobby = function () {
    cacheEls();
    ensureVersionFilter();

    els.refresh.addEventListener("click", function () {
      fetchRooms();
      loadIp();
    });

    els.search.addEventListener("input", VL.debounce(function () {
      state.search = els.search.value.trim();
      state.page = 1;
      fetchRooms();
    }, 400));

    els.category.addEventListener("change", function () {
      state.category = els.category.value;
      state.page = 1;
      fetchRooms();
    });

    els.loaders.addEventListener("click", function (ev) {
      var btn = ev.target.closest(".chip");
      if (!btn) return;
      VL.$$(".chip", els.loaders).forEach(function (c) { c.classList.remove("active"); });
      btn.classList.add("active");
      state.loader = btn.getAttribute("data-loader") || "";
      state.page = 1;
      fetchRooms();
    });

    if (els.version) {
      els.version.addEventListener("input", function () {
        state.version = els.version.value;
        // 纯前端过滤当前这一页：不发请求、不重置页码（与排除 Happy Link 开关同一口径）
        render();
      });
    }

    if (els.excludeHL) {
      els.excludeHL.addEventListener("click", function () {
        state.excludeHL = !state.excludeHL;
        els.excludeHL.classList.toggle("active", state.excludeHL);
        render();
      });
    }

    els.prev.addEventListener("click", function () { if (state.page > 1) goPage(state.page - 1); });
    els.next.addEventListener("click", function () { goPage(state.page + 1); });

    els.nums.addEventListener("click", function (ev) {
      var btn = ev.target.closest(".pager-num[data-page]");
      if (btn) goPage(Number(btn.getAttribute("data-page")));
    });

    // 点击代码 chip 复制；点击“加入”走快捷加入流程
    els.grid.addEventListener("click", function (ev) {
      var chip = ev.target.closest(".code-chip");
      if (chip && chip.getAttribute("data-code")) {
        VL.copy(chip.getAttribute("data-code"));
        return;
      }
      var joinBtn = ev.target.closest("[data-join]");
      if (joinBtn) {
        var code = joinBtn.getAttribute("data-join") || "";
        var card = joinBtn.closest(".room-card");
        if (joinBtn.getAttribute("data-has-pw") === "1" && card) {
          openJoinPop(card, code);
        } else {
          closeJoinPops();
          lobbyJoin(code, "");
        }
        return;
      }
      if (!ev.target.closest(".join-pop")) closeJoinPops();
    });

    loadCategories();
    fetchRooms();
    loadIp();

    // 房间卡片、版本下拉和公网 IP 那行都是 JS 拼出来的文本：换语言后重画一次。
    // 不重发 ListRooms（卡片内容没变，重发会打断玩家正在看的分页位置），IP 那行本来就会被后端缓存。
    VL.onLangChange(function () {
      refreshOptionLabels();
      render();
      loadIp();
    });
  };

  // 供 app.js 在回大厅时刷新列表
  VL.lobbyRefresh = function () {
    state.page = 1;
    fetchRooms();
  };

  // 供 join 页共用
  VL.lobbyCategoryName = catName;
  VL.lobbyCategories = function () { return state.categories; };
})(window.VL);
