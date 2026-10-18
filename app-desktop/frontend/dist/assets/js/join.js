/* ============================================================
 * VoxLink Desktop — join.js
 * 加入房间：房间代码输入（6 位 VoxLink 码 / 7 位 Happy Link 互通码）/ 密码 / RoomInfo 探测 / JoinRoom
 * ============================================================ */
"use strict";

(function (VL) {
  // 字符集在这里只挡"明显不是房号"的输入；真正的放行判据在服务端与 Go 侧
  // （RoomManager.java:655-661：^[A-HJ-NP-Z2-9]{6}$ 或 RoomCodeRouter.isFedCode 的 HL[A-Z0-9]{5}$）。
  // 互通码允许 0/1/I/O，所以这里不能再用 VoxLink 那套排除字符的表。
  var CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  var VOX_LEN = 6;   // 普通 VoxLink 房号
  var FED_LEN = 7;   // 互通导入码 HL+5（RoomCodeRouter.java:8,24-26）
  var els = {};
  var probing = false;
  var probedHasPassword = false;

  function cacheEls() {
    els.allInputs = VL.$$("#code-inputs input");
    els.fedInput = VL.$("#code-fed");
    els.pwField = VL.$("#join-pw-field");
    els.pw = VL.$("#join-pw");
    els.btn = VL.$("#join-btn");
    els.error = VL.$("#join-error");
    els.preview = VL.$("#join-preview");
    els.previewName = VL.$("#join-preview-name");
    els.previewLock = VL.$("#join-preview-lock");
    syncLen();
  }

  // 当前可见的格子：普通模式 6 格，互通模式 7 格
  function visibleInputs() {
    return els.allInputs.filter(function (i) { return !i.classList.contains("hidden"); });
  }

  function getCode() {
    return visibleInputs().map(function (i) { return i.value.toUpperCase(); }).join("");
  }

  // 开头两位是 HL 才允许第 7 格存在（RoomCodeRouter 的判据形状，不是"长度不够就不停加格子"）
  function syncLen() {
    var head = (els.allInputs[0].value + els.allInputs[1].value).toUpperCase();
    var fed = head === "HL";
    if (els.fedInput) els.fedInput.classList.toggle("hidden", !fed);
    if (!fed && els.fedInput) els.fedInput.value = "";
    els.inputs = visibleInputs();
    return fed;
  }

  function isFull() {
    var code = getCode();
    return code.length === VOX_LEN || (code.length === FED_LEN && code.slice(0, 2) === "HL");
  }

  function clearError() {
    els.error.classList.add("hidden");
    els.error.textContent = "";
    els.allInputs.forEach(function (i) { i.classList.remove("shake"); });
  }

  function showError(msg) {
    els.error.textContent = msg;
    els.error.classList.remove("hidden");
    els.allInputs.forEach(function (i) {
      i.classList.add("shake");
      setTimeout(function () { i.classList.remove("shake"); }, 400);
    });
  }

  function updateFilledStyle() {
    els.allInputs.forEach(function (i) { i.classList.toggle("filled", !!i.value); });
  }

  function sanitize(v) {
    return String(v || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  }

  /* ---------------- RoomInfo 探测 ---------------- */
  async function probe() {
    var code = getCode();
    if (code.length < VOX_LEN) {
      els.preview.classList.add("hidden");
      els.pwField.classList.add("hidden");
      probedHasPassword = false;
      return;
    }
    if (probing) return;
    probing = true;
    try {
      var data = await VL.api("RoomInfo", code);
      probedHasPassword = !!(data && data.hasPassword);
      els.previewName.textContent = (data && data.name) || VL.t("voxlink.app.room.unnamed", "未命名房间");
      els.previewLock.hidden = !probedHasPassword;
      els.preview.classList.remove("hidden");
      els.pwField.classList.toggle("hidden", !probedHasPassword);
      els.error.classList.add("hidden");
      if (probedHasPassword) els.pw.focus();
    } catch (e) {
      probedHasPassword = false;
      els.preview.classList.add("hidden");
      els.pwField.classList.add("hidden");
      if (e.code === "ROOM_NOT_FOUND_OR_WRONG_PASSWORD" || e.code === "ROOM_NOT_FOUND") {
        showError(VL.t("voxlink.join_room.error.not_found", "找不到这个房间，检查一下代码？ qwq"));
      } else if (e.code !== "NO_BACKEND") {
        showError(VL.errText(e));
      }
    }
    probing = false;
  }
  var probeDebounced = VL.debounce(probe, 350);

  /* ---------------- 加入 ---------------- */
  async function join() {
    clearError();
    var code = getCode();
    if (!isFull()) {
      showError(code.slice(0, 2) === "HL"
        ? VL.t("voxlink.app.join.fed_len", "互通房间代码是 7 位（HL + 5 位）")
        : VL.t("voxlink.app.join.vox_len", "要先输入完整的 6 位房间代码哦"));
      var focusIdx = els.inputs.map(function (i) { return i.value ? 0 : 1; }).indexOf(1);
      els.inputs[Math.max(0, focusIdx)].focus();
      return;
    }
    var password = els.pw.value;
    setBusy(true);
    try {
      var data = await VL.api("JoinRoom", JSON.stringify({ code: code, password: password }));
      VL.toast(VL.t("voxlink.app.join.success", "加入成功，欢迎 awa"), "success");
      if (VL.onJoined) VL.onJoined(data);
    } catch (e) {
      if (e.code === "ROOM_NOT_FOUND_OR_WRONG_PASSWORD") {
        showError(VL.t("voxlink.app.error.room_not_found_or_pw", "找不到这个房间 qwq 或密码不对"));
        els.pwField.classList.remove("hidden");
        els.pw.classList.add("shake");
        setTimeout(function () { els.pw.classList.remove("shake"); }, 400);
        els.pw.focus();
      } else if (e.code === "ROOM_FULL") {
        showError(VL.t("voxlink.error.room_full", "房间满了，稍后再来吧 :("));
      } else if (e.code !== "NO_BACKEND") {
        showError(VL.errText(e));
      }
    }
    setBusy(false);
  }

  function setBusy(b) {
    els.btn.disabled = b;
    var label = els.btn.querySelector("span:last-child");
    if (label) {
      label.textContent = b
        ? VL.t("voxlink.attempting_join", "正在加入")
        : VL.t("voxlink.join_room", "加入房间");
    }
  }

  /* ---------------- 输入交互 ---------------- */
  function bindInputs() {
    els.allInputs.forEach(function (input, idx) {
      input.addEventListener("input", function () {
        // 粘贴整段代码时自动分配到各格
        var raw = input.value;
        if (raw.length > 1) {
          var seq = sanitize(raw);
          var max = FED_LEN; // 粘贴可能直接贴进 7 位互通码，按最长分发，格子可见性随后收敛
          var start = 0;
          for (var k = 0; k < seq.length && idx + k < max; k++) {
            if (!els.allInputs[idx + k]) break;
            els.allInputs[idx + k].value = seq[k];
            start = idx + k;
          }
          syncLen();
          var inputs = visibleInputs();
          var next = Math.min(start + 1, inputs.length - 1);
          els.allInputs.forEach(function (i) { if (i.classList.contains("hidden")) i.value = ""; });
          inputs[next].focus();
          if (inputs[next].value) inputs[next].select();
        } else {
          input.value = sanitize(raw).slice(0, 1);
          var fedJustOpened = syncLen();
          var inputs2 = visibleInputs();
          var myIdx = inputs2.indexOf(input);
          if (input.value && myIdx >= 0 && myIdx < inputs2.length - 1) inputs2[myIdx + 1].focus();
          if (fedJustOpened && input.value && myIdx === 1) {
            // 刚打出 "HL"：第 7 格才出现，焦点留在第 3 格继续打普通位序
            inputs2[Math.min(2, inputs2.length - 1)].focus();
          }
        }
        updateFilledStyle();
        clearError();
        probeDebounced();
        if (isFull() && !probedHasPassword) els.pw.focus();
      });

      input.addEventListener("keydown", function (ev) {
        var inputs = visibleInputs();
        var myIdx = inputs.indexOf(input);
        if (ev.key === "Backspace" && !input.value && myIdx > 0) {
          inputs[myIdx - 1].focus();
          inputs[myIdx - 1].value = "";
          syncLen();
          updateFilledStyle();
          ev.preventDefault();
        } else if (ev.key === "Enter") {
          join();
        } else if (ev.key === "ArrowLeft" && myIdx > 0) {
          inputs[myIdx - 1].focus();
        } else if (ev.key === "ArrowRight" && myIdx < inputs.length - 1) {
          inputs[myIdx + 1].focus();
        }
      });

      input.addEventListener("paste", function (ev) {
        ev.preventDefault();
        var text = sanitize((ev.clipboardData || window.clipboardData).getData("text"));
        if (!text) return;
        for (var k = 0; k < FED_LEN && els.allInputs[k]; k++) {
          els.allInputs[k].value = text[k] || "";
        }
        syncLen();
        var inputs = visibleInputs();
        // 隐藏格被 syncLen 清空后，把超出可见长度的残留字符丢掉，避免"看着 6 位其实 7 位"
        for (var j = inputs.length; j < els.allInputs.length; j++) els.allInputs[j].value = "";
        var last = Math.min(text.length, inputs.length) - 1;
        inputs[Math.max(0, last)].focus();
        updateFilledStyle();
        clearError();
        probeDebounced();
        if (isFull()) els.pw.focus();
      });
    });

    els.pw.addEventListener("keydown", function (ev) {
      if (ev.key === "Enter") join();
    });
    els.pw.addEventListener("input", clearError);
    els.btn.addEventListener("click", join);
  }

  /* ---------------- 初始化 ---------------- */
  VL.initJoin = function () {
    cacheEls();
    bindInputs();
    updateFilledStyle();
    // 按钮上的"正在加入/加入房间"是 setBusy 写的，换语言时按当前状态重说一次，
    // 否则静态文案套用会把进行中的那句盖回"加入房间"
    VL.onLangChange(function () { setBusy(!!els.btn.disabled); });
  };

  // 从大厅“加入”跳转时预填代码（预留）
  VL.joinPrefill = function (code) {
    var seq = sanitize(code);
    for (var k = 0; k < FED_LEN && els.allInputs[k]; k++) els.allInputs[k].value = seq[k] || "";
    syncLen();
    for (var j = visibleInputs().length; j < els.allInputs.length; j++) els.allInputs[j].value = "";
    updateFilledStyle();
    if (isFull()) probeDebounced();
  };

  // 给其他模块（大厅卡片"加入"）判断码型用；与 RoomCodeRouter.isFedCode 同判据：HL + 5 位字母数字
  VL.isFedRoomCode = function (code) {
    return /^HL[A-Z0-9]{5}$/.test(String(code || "").toUpperCase());
  };
  VL.codeChars = CODE_CHARS;
})(window.VL);
