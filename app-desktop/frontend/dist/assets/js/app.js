/* ============================================================
 * VoxLink Desktop — app.js
 * 启动引导：导航路由 / 主题 / 后端事件订阅 / 会话恢复
 * ============================================================ */
"use strict";

(function (VL) {
  var currentPage = "lobby";

  VL.currentPage = function () { return currentPage; };

  /* ---------------- 页面路由 ---------------- */
  VL.showPage = function (name) {
    currentPage = name;
    VL.$$(".page").forEach(function (p) {
      p.classList.toggle("active", p.id === "page-" + name);
    });
    VL.$$(".nav-item").forEach(function (n) {
      n.classList.toggle("active", n.getAttribute("data-page") === name);
    });
    var main = VL.$("#main");
    if (main) main.scrollTop = 0;
    // 设置页里有个开关写在另一份文件（modsync.json），可能被创建房间页改过，
    // 切过来时刷新一次，避免显示旧值。
    if (name === "settings" && VL.refreshSettings) VL.refreshSettings();
  };

  function bindNav() {
    VL.$("#nav").addEventListener("click", function (ev) {
      var item = ev.target.closest(".nav-item");
      if (!item) return;
      var page = item.getAttribute("data-page");
      if (page === "room" && !VL.roomInSession()) {
        VL.toast(VL.t("voxlink.app.nav.not_in_room", "现在不在房间里哦，先加入或创建一个吧 qwq"), "warn");
        return;
      }
      VL.showPage(page);
      // 进工单页时静默拉一次回复状态（角标与列表同步）
      if (page === "ticket" && VL.ticketBoot) VL.ticketBoot();
    });

    // 侧栏底部当前房间胶囊
    VL.$("#room-pill").addEventListener("click", function () {
      if (VL.roomInSession()) VL.showPage("room");
    });
  }

  function updateSessionUI(room) {
    var inSession = !!(room && room.code);
    var pill = VL.$("#room-pill");
    pill.classList.toggle("hidden", !inSession);
    if (inSession) {
      VL.$("#room-pill-code").textContent = room.code;
      VL.$("#room-pill").title = VL.t("voxlink.app.room.current_title", "当前房间：%s", room.name || room.code);
      VL.$(".nav-room").classList.remove("hidden");
      // 会话进行中：隐藏大厅/加入/创建三项（只剩"当前房间/相关链接/设置 + 房间胶囊"）
      VL.$$(".nav-hide-on-session").forEach(function (n) { n.classList.add("hidden"); });
    } else {
      VL.$(".nav-room").classList.add("hidden");
      VL.$$(".nav-hide-on-session").forEach(function (n) { n.classList.remove("hidden"); });
      if (currentPage === "room") VL.showPage("lobby");
    }
  }

  /* ---------------- 建房 / 加入成功的入口（create.js / join.js 回调） ---------------- */
  VL.onHosted = function (data) {
    var room = Object.assign({}, data || {}, { isHost: true });
    updateSessionUI(room);
    VL.enterRoomPage(room);
  };

  VL.onJoined = function (data) {
    var room = Object.assign({}, (data && data.room) || {}, { isHost: false });
    updateSessionUI(room);
    VL.enterRoomPage(room);
  };

  /* ---------------- 后端事件 ---------------- */
  function bindEvents() {
    VL.on("session:state", function (payload) {
      payload = payload || {};
      var st = payload.state;
      if (st === "hosting" || st === "in_room") {
        updateSessionUI(payload.room || {});
      } else if (st === "closed" || st === "idle") {
        updateSessionUI(null);
      }
      VL.onSessionStateEvent(payload);
    });

    VL.on("signal", function (payload) {
      VL.onSignalEvent(payload);
    });

    VL.on("net:log", function (payload) {
      VL.onNetLogEvent(payload);
    });

    // 房客连接流程状态（p2p / direct / prelay）
    VL.on("conn:state", function (payload) {
      if (VL.onConnStateEvent) VL.onConnStateEvent(payload);
    });
  }

  /* ---------------- 启动流程 ---------------- */
  async function boot() {
    // 语言包必须在各页首次渲染之前到位：否则列表会先用兜底中文画一遍，
    // 换语言时又要整体重画，玩家会看到一次明显的文字跳动。
    await VL.initLang();
    VL.hydrateIcons(document);
    VL.applyI18n(document);
    VL.initLobby();
    VL.initJoin();
    VL.initCreate();
    VL.initRoom();
    VL.initTicket();
    VL.initLinks();
    VL.initSettings();
    bindNav();
    bindEvents();

    VL.showPage("lobby");

    // 启动时读取设置：主题 + 服务器地址；并恢复会话
    try {
      var settings = await VL.api("GetSettings");
      settings = settings || {};
      VL.applyTheme(settings.theme || "light");
      var themeSel = VL.$("#set-theme");
      if (themeSel) themeSel.value = VL.themes.current;
    } catch (e) { /* 无后端：保持默认亮色 */ }

    try {
      var st = await VL.api("GetSessionState");
      st = st || {};
      if (st.state === "hosting" || st.state === "in_room") {
        // 会话恢复：直接进入房间页
        updateSessionUI(st.room || {});
        VL.enterRoomPage(st.room || {}, { silent: true });
        return;
      }
      updateSessionUI(null);
    } catch (e) { /* 无后端 */ }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})(window.VL);
