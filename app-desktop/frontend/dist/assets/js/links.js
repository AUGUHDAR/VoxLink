/* ============================================================
 * VoxLink Desktop — links.js
 * 相关链接卡片（点击通过 OpenInBrowser 打开）
 * ============================================================ */
"use strict";

(function (VL) {
  // 前 8 条逐条对齐模组 RelatedLinksScreen.java 的 LINKS 表（顺序与 URL 都取原文，
  // QQ 群那条连 authKey 参数也照抄：那是二维码绑定的固定值，不是会话令牌）。
  // 后 2 条是本应用端自己的仓库：模组仓库和应用端仓库不是一回事，玩家在两者之间
  // 反馈问题时需要能一眼分清，所以名称里带「模组」/「应用端」。
  // name/desc 都是 [语言键, 中文原文]：品牌名那条用的是模组已有的键（各语言同一写法），
  // 带「（模组）/（应用端）」后缀的两条和所有说明文字是应用端自有文案，lang 里还没有对应键。
  var LINKS = [
    {
      name: ["voxlink.links.mcmod", "MC百科"],
      desc: ["voxlink.app.links.mcmod_desc", "模组介绍、使用教程与常见问题"],
      url: "https://www.mcmod.cn/class/28295.html"
    },
    {
      name: ["voxlink.app.links.github_mod", "GitHub（模组）"],
      desc: ["voxlink.app.links.github_mod_desc", "VoxLink 模组的源码仓库，点个 Star 支持一下 qwq"],
      url: "https://github.com/AUGUHDAR/VoxLink"
    },
    {
      name: ["voxlink.app.links.gitee_mod", "Gitee（模组）"],
      desc: ["voxlink.app.links.gitee_mod_desc", "模组源码仓库的国内镜像，克隆更快"],
      url: "https://gitee.com/AUGUHDAR/VoxLink"
    },
    {
      name: ["voxlink.links.modrinth", "Modrinth"],
      desc: ["voxlink.app.links.modrinth_desc", "在 Modrinth 下载 VoxLink 模组"],
      url: "https://modrinth.com/mod/voxlink"
    },
    {
      name: ["voxlink.links.curseforge", "CurseForge"],
      desc: ["voxlink.app.links.curseforge_desc", "在 CurseForge 下载 VoxLink 模组"],
      url: "https://www.curseforge.com/minecraft/mc-mods/voxlink"
    },
    {
      name: ["voxlink.links.site", "官网"],
      desc: ["voxlink.app.links.site_desc", "VoxLink 官方网站，下载与说明都在上面"],
      url: "https://link.voxdev.top/"
    },
    {
      name: ["voxlink.links.discord", "Discord"],
      desc: ["voxlink.app.links.discord_desc", "加入 Discord 服务器，反馈与更新通知都在这里"],
      url: "https://discord.gg/XaAFxvzPDS"
    },
    {
      name: ["voxlink.links.qq", "QQ群"],
      desc: ["voxlink.app.links.qq_desc", "进 QQ 群一起联机、报问题、提建议"],
      url: "https://qm.qq.com/cgi-bin/qm/qr?k=OEkk9L8m8jdFMkbGDhKZs0u2U0azLAPo&jump_from=webapi&authKey=v0dYAQniGZypAJuoPZW/7FL0bfoc32h68oIHd9lqGwOvAduzcwsJNR7Mei9/YugW"
    },
    {
      name: ["voxlink.app.links.github_app", "GitHub（应用端）"],
      desc: ["voxlink.app.links.github_app_desc", "VoxLink 桌面应用端的源码仓库（就是这个程序本身）"],
      url: "https://github.com/AUGUHDAR/VoxLink-app"
    },
    {
      name: ["voxlink.app.links.gitee_app", "Gitee（应用端）"],
      desc: ["voxlink.app.links.gitee_app_desc", "应用端源码仓库的国内镜像"],
      url: "https://gitee.com/AUGUHDAR/VoxLink-app"
    }
  ];

  function renderLinks() {
    var grid = VL.$("#links-grid");
    if (!grid) return;
    grid.innerHTML = LINKS.map(function (l, i) {
      return (
        '<div class="card card-hoverable link-card">' +
        '  <div class="link-name"><span class="ico">' + VL.iconSvg("link") + "</span>" +
        VL.esc(VL.t(l.name[0], l.name[1])) + "</div>" +
        '  <div class="link-desc">' + VL.esc(VL.t(l.desc[0], l.desc[1])) + "</div>" +
        '  <div class="link-url">' + VL.esc(l.url) + "</div>" +
        '  <button class="btn btn-ghost btn-sm" data-url="' + VL.esc(l.url) + '" data-open="' + i + '">' +
        '    <span class="ico">' + VL.iconSvg("external") + "</span>" +
        VL.esc(VL.t("voxlink.app.links.open", "打开")) +
        "  </button>" +
        "</div>"
      );
    }).join("");
  }

  VL.initLinks = function () {
    renderLinks();
    var grid = VL.$("#links-grid");
    if (!grid) return;

    grid.addEventListener("click", function (ev) {
      var btn = ev.target.closest("[data-url]");
      if (!btn) return;
      var url = btn.getAttribute("data-url");
      // 统一走后端浏览器打开，不在网页内跳转
      VL.api("OpenInBrowser", url).catch(function (e) {
        if (e.code !== "NO_BACKEND") VL.toast(VL.errText(e), "error");
      });
    });

    VL.onLangChange(renderLinks);
  };
})(window.VL);
