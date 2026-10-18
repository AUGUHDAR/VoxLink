#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
sync_modsync113_20260830.py

Propagate ModSync 1.1.3 hardening changes from the master template
(fabric/1.21.11) to all 25 templates (fabric/forge/neoforge x MC versions):

  Fix1: ModSyncEnv.LOADER — single-line anchor per template (fabric / forge /
        neoforge). Current state already has correct values; script is a
        defensive idempotent write so future drift is fixed in one go.

  Fix2: ModSyncGuestService.fetchManifestWithRetry — carry `unknownMods`
        from server response into manifest builder; do NOT silently return
        null when mods is empty but unknownMods has entries (those need to
        reach computeDiff so the panel can show "unrecognised" hint).

  Fix3: ModSyncGuestService.gate — after the existing `mc_mismatch` check,
        add a same-shape `loader_mismatch` check using manifest's loader
        field vs ModSyncEnv.LOADER. Empty host loader skips the check.

  Fix4: ModSyncManifestService —
        4a) Add UNKNOWN_MODS_CAP = 64 constant + truncate unknownMods before
            adding to manifest (signal server has a 48KB per-message cap;
            1024 jar filenames could blow it up).
        4b) Add 3 imports (ChatFormatting, Minecraft, Component).
        4c) Insert truncation block before `manifest.add("unknownMods", ...)`.
        4d) After successful publish, call notifyHostFromManifest(manifest) to
            emit host-side chat hints:
              - unknownMods non-empty -> voxlink.modsync.host_unknown (YELLOW)
              - mods non-empty       -> voxlink.modsync.host_published (GREEN)
            Publish signature is unchanged; notifyHostFromManifest inspects the
            already-built manifest, so call sites stay untouched.

  i18n: 14 lang files under assets/voxlink/lang/ — append 3 keys
    (loader_mismatch, host_published, host_unknown) before the closing `}`.

Strategy (per repo convention: single-line anchor preferred, count==1
asserted, newline='' for I/O, CRLF tolerated via LF-normalized match):
  * Each anchor is a small block (1-5 lines), the LF-normalized count
    must equal exactly 1 to assert idempotency safely.
  * On second run all anchors are absent (already applied) and the script
    is a no-op.

For each fix point the script asserts count==1 before replacement
(idempotent: a second run finds zero matches and is treated as a no-op).

After replacement, every file is overwritten preserving original line
endings.
"""

import io
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))

TEMPLATES = sorted(
    [os.path.join("fabric", v) for v in os.listdir(os.path.join(ROOT, "fabric"))]
    + [os.path.join("forge", v) for v in os.listdir(os.path.join(ROOT, "forge"))]
    + [os.path.join("neoforge", v) for v in os.listdir(os.path.join(ROOT, "neoforge"))]
)
assert len(TEMPLATES) == 25, f"expect 25 templates, got {len(TEMPLATES)}"

# All 14 language file basenames (confirmed via os.listdir in master template).
LANG_BASENAMES = [
    "ar_sa.json",
    "de_de.json",
    "en_us.json",
    "es_es.json",
    "fr_fr.json",
    "ja_jp.json",
    "ko_kr.json",
    "lzh.json",
    "pt_br.json",
    "ru_ru.json",
    "zh_cn.json",
    "zh_hk.json",
    "zh_tw.json",
    # Per-task spec listed exactly these 14 (zh 系简繁 + en + 日韩法德西俄葡阿 + 文言 lzh).
    # If a 15th lang file appears in a future template, the script will skip it
    # (it iterates only the canonical list above); admin can extend this list.
]


def read_file_bytes(rel_path: str) -> bytes:
    fp = os.path.join(ROOT, rel_path)
    with io.open(fp, "rb") as f:
        return f.read()


def write_file_bytes(rel_path: str, data: bytes) -> None:
    fp = os.path.join(ROOT, rel_path)
    with io.open(fp, "wb") as f:
        f.write(data)


def detect_eol(data: bytes) -> bytes:
    """Return b'\\r\\n' if file uses CRLF predominantly, else b'\\n'."""
    crlf = data.count(b"\r\n")
    lf = data.count(b"\n") - crlf
    return b"\r\n" if crlf >= lf else b"\n"


def replace_with_eol(rel_path: str, old_lines: str, new_lines: str, label: str) -> bool:
    """Replace `old_lines` (joined with \\n) with `new_lines` (joined with \\n),
    in a file that may use CRLF or LF. count==1 must hold. Idempotent: if the
    new block already exists (in either form), returns False (no-op)."""
    data = read_file_bytes(rel_path)
    eol = detect_eol(data)

    # Normalize current file to LF for matching
    text = data.decode("utf-8")
    text_lf = text.replace("\r\n", "\n")

    old_lf = old_lines.replace("\r\n", "\n")
    new_lf = new_lines.replace("\r\n", "\n")

    if new_lf in text_lf:
        return False
    cnt = text_lf.count(old_lf)
    if cnt == 0:
        print(f"  SKIP (no anchor)  {label}  ::  {rel_path}")
        return False
    assert cnt == 1, f"{rel_path}: anchor count={cnt} for {label}"

    # Replace in LF-normalized text, then convert back to original EOL
    text_lf_new = text_lf.replace(old_lf, new_lf, 1)
    if eol == b"\r\n":
        text_new = text_lf_new.replace("\n", "\r\n")
    else:
        text_new = text_lf_new

    write_file_bytes(rel_path, text_new.encode("utf-8"))
    return True


def loader_for(template: str) -> str:
    """Return the canonical LOADER value for a given template dir name."""
    if template.startswith("fabric"):
        return "fabric"
    if template.startswith("forge"):
        return "forge"
    if template.startswith("neoforge"):
        return "neoforge"
    raise AssertionError(f"unknown loader family: {template}")


# ----------------------------------------------------------------------
# Fix1: ModSyncEnv.LOADER per-template anchor.
# Single-line: count==1 within each template file because the only place
# `public static final String LOADER = "..."` appears is the line we want.
# The current "fabric" value matches only fabric/* templates; forge and
# neoforge already have their correct values but the script writes them
# defensively in case any template drifts to "fabric".
# ----------------------------------------------------------------------

def fix1_loader(tmpl: str) -> bool:
    rel = f"{tmpl}/src/main/java/icu/wuhui/voxlink/modsync/ModSyncEnv.java"
    target = loader_for(tmpl.split(os.sep)[0] if os.sep in tmpl else tmpl.split("/")[0])
    # Three per-loader anchors; only one matches each template (idempotent).
    anchors = {
        "fabric": ('public static final String LOADER = "fabric";',
                   'public static final String LOADER = "fabric";'),
        "forge": ('public static final String LOADER = "fabric";',
                  'public static final String LOADER = "forge";'),
        "neoforge": ('public static final String LOADER = "fabric";',
                     'public static final String LOADER = "neoforge";'),
    }
    old, new = anchors[target]
    return replace_with_eol(rel, old, new, f"fix1 LOADER={target}")


# ----------------------------------------------------------------------
# Fix2: ModSyncGuestService — carry unknownMods + do not silently null
# when mods empty but unknownMods present.
# Anchor includes the closing braces of the `if (ready)` block so the
# replacement is structurally complete.
# ----------------------------------------------------------------------

ANCHOR_FIX2 = (
    "m.add(\"mods\", resp.data.has(\"mods\") && resp.data.get(\"mods\").isJsonArray()\n"
    "                  ? resp.data.getAsJsonArray(\"mods\")\n"
    "                  : new com.google.gson.JsonArray());\n"
    "               if (m.getAsJsonArray(\"mods\").size() == 0) {\n"
    "                  return null;\n"
    "               }\n"
    "\n"
    "               return m;\n"
)

REPL_FIX2 = (
    "m.add(\"mods\", resp.data.has(\"mods\") && resp.data.get(\"mods\").isJsonArray()\n"
    "                  ? resp.data.getAsJsonArray(\"mods\")\n"
    "                  : new com.google.gson.JsonArray());\n"
    "               m.add(\"unknownMods\", resp.data.has(\"unknownMods\") && resp.data.get(\"unknownMods\").isJsonArray()\n"
    "                  ? resp.data.getAsJsonArray(\"unknownMods\")\n"
    "                  : new com.google.gson.JsonArray());\n"
    "               // mods 为空时不再一律静默: 若 unknownMods 非空, 仍返回 m 让 computeDiff 展示给玩家,\n"
    "               // 面板会提示\"无法识别/需手动安装\"的 mod 列表; 两者都为空才是真正的空清单, 才 return null。\n"
    "               if (m.getAsJsonArray(\"mods\").size() == 0 && m.getAsJsonArray(\"unknownMods\").size() == 0) {\n"
    "                  return null;\n"
    "               }\n"
    "\n"
    "               return m;\n"
)


# ----------------------------------------------------------------------
# Fix3: ModSyncGuestService — loader_mismatch right after mc_mismatch.
# ----------------------------------------------------------------------

ANCHOR_FIX3 = (
    "if (!hostMc.isEmpty() && !hostMc.equals(ModSyncEnv.GAME_VERSION)) {\n"
    "               ModSyncLog.warn(\"MC version mismatch: host={} guest={}\", new Object[]{hostMc, ModSyncEnv.GAME_VERSION});\n"
    "               diff.unresolvable.add(0,\n"
    "                  Component.translatable(\"voxlink.modsync.mc_mismatch\",\n"
    "                     new Object[]{hostMc, ModSyncEnv.GAME_VERSION}).getString());\n"
    "            }\n"
    "\n"
    "            ModSyncLog.info(\n"
)

REPL_FIX3 = (
    "if (!hostMc.isEmpty() && !hostMc.equals(ModSyncEnv.GAME_VERSION)) {\n"
    "               ModSyncLog.warn(\"MC version mismatch: host={} guest={}\", new Object[]{hostMc, ModSyncEnv.GAME_VERSION});\n"
    "               diff.unresolvable.add(0,\n"
    "                  Component.translatable(\"voxlink.modsync.mc_mismatch\",\n"
    "                     new Object[]{hostMc, ModSyncEnv.GAME_VERSION}).getString());\n"
    "            }\n"
    "            // 加载器检测: 房主与房客的 loader 不同时, 清单里的 mod 文件本端无法直接运行, 仅作参考\n"
    "            String hostLoader = manifest.has(\"loader\") && manifest.get(\"loader\").isJsonPrimitive()\n"
    "               ? manifest.get(\"loader\").getAsString() : \"\";\n"
    "            if (!hostLoader.isEmpty() && !hostLoader.equalsIgnoreCase(ModSyncEnv.LOADER)) {\n"
    "               ModSyncLog.warn(\"Loader mismatch: host={} guest={}\", new Object[]{hostLoader, ModSyncEnv.LOADER});\n"
    "               diff.unresolvable.add(0,\n"
    "                  Component.translatable(\"voxlink.modsync.loader_mismatch\",\n"
    "                     new Object[]{hostLoader, ModSyncEnv.LOADER}).getString());\n"
    "            }\n"
    "\n"
    "            ModSyncLog.info(\n"
)


def fix23_guest(tmpl: str) -> tuple:
    """Return (count_fix2, count_fix3)."""
    rel = f"{tmpl}/src/main/java/icu/wuhui/voxlink/modsync/ModSyncGuestService.java"
    a = replace_with_eol(rel, ANCHOR_FIX2, REPL_FIX2, "fix2 mods/unknownMods gate")
    b = replace_with_eol(rel, ANCHOR_FIX3, REPL_FIX3, "fix3 loader_mismatch")
    return (1 if a else 0, 1 if b else 0)


# ----------------------------------------------------------------------
# Fix4: ModSyncManifestService — UNKNOWN_MODS_CAP, truncation, notifyHost.
# ----------------------------------------------------------------------

# Anchor 4a: imports. Single-line anchor — the line right after RoomInfo import.
ANCHOR_FIX4A = (
    "import icu.wuhui.voxlink.room.RoomInfo;\n"
    "import java.io.IOException;\n"
)

REPL_FIX4A = (
    "import icu.wuhui.voxlink.room.RoomInfo;\n"
    "import net.minecraft.ChatFormatting;\n"
    "import net.minecraft.client.Minecraft;\n"
    "import net.minecraft.network.chat.Component;\n"
    "import java.io.IOException;\n"
)


# Anchor 4b: UNKNOWN_MODS_CAP constant inserted right after MAX_DEP_DEPTH.
ANCHOR_FIX4B = (
    "/** 依赖闭包最大深度（防脏元数据成环/超深；visited 已防环，此为双保险）。 */\n"
    "   private static final int MAX_DEP_DEPTH = 8;\n"
)

REPL_FIX4B = (
    "/** 依赖闭包最大深度（防脏元数据成环/超深；visited 已防环，此为双保险）。 */\n"
    "   private static final int MAX_DEP_DEPTH = 8;\n"
    "   /** unknownMods 清单上限（信令单条消息 48KB 上限，1024 个 jar 文件名可能撑爆）。 */\n"
    "   private static final int UNKNOWN_MODS_CAP = 64;\n"
)


# Anchor 4c: truncation block inserted right before `manifest.add("unknownMods", ...)`.
ANCHOR_FIX4C = (
    "manifest.add(\"unknownMods\", unknownMods);\n"
)

REPL_FIX4C = (
    "// 保护清单大小: 信令服务器单条消息有 48KB 上限, 1024 个 jar 的文件名可能撑爆。\n"
    "      // unknownMods 只用于提示, 截断到 64 条对玩家提示已足够, 损失信息仅\"多 N 个 mod 无法识别\"。\n"
    "      if (unknownMods.size() > UNKNOWN_MODS_CAP) {\n"
    "         JsonArray trimmed = new JsonArray();\n"
    "         for (int i = 0; i < UNKNOWN_MODS_CAP; i++) {\n"
    "            trimmed.add(unknownMods.get(i));\n"
    "         }\n"
    "         unknownMods = trimmed;\n"
    "      }\n"
    "      manifest.add(\"unknownMods\", unknownMods);\n"
)


# Anchor 4d: insert notifyHostFromManifest() call after successful publish.
ANCHOR_FIX4D = (
    "if (resp.success) {\n"
    "         ModSyncLog.info(\"manifest published for room {}\", room.getCode());\n"
    "      } else {\n"
)

REPL_FIX4D = (
    "if (resp.success) {\n"
    "         ModSyncLog.info(\"manifest published for room {}\", room.getCode());\n"
    "         // 给房主本地聊天提示: 从已发布的 manifest 里读 mods / unknownMods,\n"
    "         // 各自为空则不发; 不修改 publish 签名, 避免调用方一连串改动。\n"
    "         notifyHostFromManifest(manifest);\n"
    "      } else {\n"
)


# Anchor 4e: insert notifyHostFromManifest() helper at the end of class (before closing `}`).
ANCHOR_FIX4E = (
    "         ModSyncLog.warn(\"manifest publish retry success={} error={}\", retry.success, retry.error);\n"
    "      }\n"
    "   }\n"
    "}\n"
)

REPL_FIX4E = (
    "         ModSyncLog.warn(\"manifest publish retry success={} error={}\", retry.success, retry.error);\n"
    "      }\n"
    "   }\n"
    "\n"
    "   /**\n"
    "    * 房主本地聊天提示：unknownMods / 必装条目各自为空则不发；最多取前 3 个文件名做列表,\n"
    "    * 超出加省略号。EXECUTOR 后台线程 → 用 Minecraft.execute 切到主线程调用 displayClientMessage。\n"
    "    */\n"
    "   private static void notifyHostFromManifest(JsonObject manifest) {\n"
    "      int requiredCount = manifest.has(\"mods\") && manifest.get(\"mods\").isJsonArray()\n"
    "         ? manifest.getAsJsonArray(\"mods\").size() : 0;\n"
    "      JsonArray unknownMods = manifest.has(\"unknownMods\") && manifest.get(\"unknownMods\").isJsonArray()\n"
    "         ? manifest.getAsJsonArray(\"unknownMods\") : null;\n"
    "      boolean hasUnknown = unknownMods != null && unknownMods.size() > 0;\n"
    "      boolean hasRequired = requiredCount > 0;\n"
    "      if (!hasUnknown && !hasRequired) {\n"
    "         return;\n"
    "      }\n"
    "\n"
    "      Minecraft mc = Minecraft.getInstance();\n"
    "      if (mc == null) {\n"
    "         return;\n"
    "      }\n"
    "\n"
    "      final int rc = requiredCount;\n"
    "      final JsonArray um = unknownMods;\n"
    "      mc.execute(() -> {\n"
    "         try {\n"
    "            Minecraft m = Minecraft.getInstance();\n"
    "            if (m.player == null) {\n"
    "               return;\n"
    "            }\n"
    "\n"
    "            if (um != null && um.size() > 0) {\n"
    "               int total = um.size();\n"
    "               StringBuilder names = new StringBuilder();\n"
    "               int shown = Math.min(3, total);\n"
    "               for (int i = 0; i < shown; i++) {\n"
    "                  if (i > 0) {\n"
    "                     names.append(\", \");\n"
    "                  }\n"
    "                  names.append(um.get(i).getAsString());\n"
    "               }\n"
    "               if (total > shown) {\n"
    "                  names.append(\"...\");\n"
    "               }\n"
    "               m.player.displayClientMessage(\n"
    "                  Component.translatable(\"voxlink.modsync.host_unknown\",\n"
    "                     new Object[]{total, names.toString()})\n"
    "                     .withStyle(ChatFormatting.YELLOW)\n"
    "               , false);\n"
    "            }\n"
    "            if (rc > 0) {\n"
    "               m.player.displayClientMessage(\n"
    "                  Component.translatable(\"voxlink.modsync.host_published\",\n"
    "                     new Object[]{rc})\n"
    "                     .withStyle(ChatFormatting.GREEN)\n"
    "               , false);\n"
    "            }\n"
    "         } catch (Exception ignored) {\n"
    "         }\n"
    "      });\n"
    "   }\n"
    "}\n"
)


def fix4_manifest(tmpl: str) -> int:
    """Return count of fixes applied."""
    rel = f"{tmpl}/src/main/java/icu/wuhui/voxlink/modsync/ModSyncManifestService.java"
    cnt = 0
    if replace_with_eol(rel, ANCHOR_FIX4A, REPL_FIX4A, "fix4a imports"):
        cnt += 1
    if replace_with_eol(rel, ANCHOR_FIX4B, REPL_FIX4B, "fix4b UNKNOWN_MODS_CAP"):
        cnt += 1
    if replace_with_eol(rel, ANCHOR_FIX4C, REPL_FIX4C, "fix4c truncation"):
        cnt += 1
    if replace_with_eol(rel, ANCHOR_FIX4D, REPL_FIX4D, "fix4d notifyHost call"):
        cnt += 1
    if replace_with_eol(rel, ANCHOR_FIX4E, REPL_FIX4E, "fix4e notifyHost method"):
        cnt += 1
    return cnt


# ----------------------------------------------------------------------
# i18n: append 3 keys to each of 14 lang files in fabric/1.21.11.
# Each lang file ends with a "download_cancelled" line followed by `}`.
# We use the line that immediately precedes `}` as a single-line anchor
# and replace it with itself + 3 new lines + `}`.
# ----------------------------------------------------------------------

# Per-language download_cancelled lines (verbatim from the canonical files).
# Each anchor is the single line before `}`. count==1 holds because
# download_cancelled appears only once in each lang file.
LANG_DOWNLOAD_CANCELLED = {
    "ar_sa.json": '"voxlink.modsync.download_cancelled": "تم إلغاء التنزيل."',
    "de_de.json": '"voxlink.modsync.download_cancelled": "Download abgebrochen."',
    "en_us.json": '"voxlink.modsync.download_cancelled": "Download cancelled."',
    "es_es.json": '"voxlink.modsync.download_cancelled": "Descarga cancelada."',
    "fr_fr.json": '"voxlink.modsync.download_cancelled": "Téléchargement annulé."',
    "ja_jp.json": '"voxlink.modsync.download_cancelled": "ダウンロードをキャンセルしました。"',
    "ko_kr.json": '"voxlink.modsync.download_cancelled": "다운로드가 취소되었습니다."',
    "lzh.json": '"voxlink.modsync.download_cancelled": "已罷下載。"',
    "pt_br.json": '"voxlink.modsync.download_cancelled": "Download cancelado."',
    "ru_ru.json": '"voxlink.modsync.download_cancelled": "Загрузка отменена."',
    "zh_cn.json": '"voxlink.modsync.download_cancelled": "已取消下载。"',
    "zh_hk.json": '"voxlink.modsync.download_cancelled": "已取消下載。"',
    "zh_tw.json": '"voxlink.modsync.download_cancelled": "已取消下載。"',
}

# Per-language new 3 keys (loader_mismatch, host_published, host_unknown).
# Empty strings for languages the spec didn't cover are intentional: we use
# en_us values as fallback if the lang file is missing a translation; here
# we provide explicit translations for the 14 supported langs.
LANG_NEW_KEYS = {
    "ar_sa.json": [
        '"voxlink.modsync.loader_mismatch": "مُحمِّل المضيف %s مقابل محمِّلك (%s): المودات لن تعمل، ثبّت المُحمِّل المطابق"',
        '"voxlink.modsync.host_published": "تم نشر %d مود مطلوب، يمكن للضيوف تنزيلها."',
        '"voxlink.modsync.host_unknown": "%d مود غير مُعرَّف، على الضيوف تثبيتها يدوياً: %s"',
    ],
    "de_de.json": [
        '"voxlink.modsync.loader_mismatch": "Host-Loader %s vs. deiner (%s): Mods laufen nicht, installiere den passenden Loader"',
        '"voxlink.modsync.host_published": "%d Pflicht-Mod(s) veröffentlicht, Gäste können sie herunterladen."',
        '"voxlink.modsync.host_unknown": "%d Mod(s) unbekannt, Gäste müssen sie manuell installieren: %s"',
    ],
    "en_us.json": [
        '"voxlink.modsync.loader_mismatch": "Host loader %s vs yours (%s): mods won\'t run, install the matching loader"',
        '"voxlink.modsync.host_published": "Published %d required mod(s) for guests to download."',
        '"voxlink.modsync.host_unknown": "%d mod(s) unrecognised, guests must install manually: %s"',
    ],
    "es_es.json": [
        '"voxlink.modsync.loader_mismatch": "Loader del host %s vs. el tuyo (%s): los mods no funcionarán, instala el loader correspondiente"',
        '"voxlink.modsync.host_published": "Publicado(s) %d mod(s) obligatorio(s), los invitados pueden descargarlos."',
        '"voxlink.modsync.host_unknown": "%d mod(s) no reconocido(s), los invitados deben instalarlos manualmente: %s"',
    ],
    "fr_fr.json": [
        '"voxlink.modsync.loader_mismatch": "Loader de l\'hôte %s vs. le tien (%s) : les mods ne tourneront pas, installe le loader correspondant"',
        '"voxlink.modsync.host_published": "%d mod(s) requis publiés, les invités peuvent les télécharger."',
        '"voxlink.modsync.host_unknown": "%d mod(s) non reconnu(s), les invités doivent les installer manuellement : %s"',
    ],
    "ja_jp.json": [
        '"voxlink.modsync.loader_mismatch": "ホストのローダー %s とあなたの (%s) が異なります：modは動作しません、一致するローダーを導入してください"',
        '"voxlink.modsync.host_published": "必須mod %d 件を公開しました。ゲストがダウンロードできます。"',
        '"voxlink.modsync.host_unknown": "%d 件のmodが識別できません。ゲストは手動で導入してください：%s"',
    ],
    "ko_kr.json": [
        '"voxlink.modsync.loader_mismatch": "호스트 로더 %s와 당신 (%s)이(가) 다릅니다: mod가 실행되지 않습니다. 일치하는 로더를 설치하세요"',
        '"voxlink.modsync.host_published": "필수 mod %d개를 게스트가 다운로드할 수 있도록 게시했습니다."',
        '"voxlink.modsync.host_unknown": "%d개의 mod를 인식할 수 없습니다. 게스트가 수동으로 설치해야 합니다: %s"',
    ],
    "lzh.json": [
        '"voxlink.modsync.loader_mismatch": "房主載具 %s 與汝 (%s) 異：mod 不可行，請裝同款載具"',
        '"voxlink.modsync.host_published": "已發 %d 項必裝 mod，房客可取。"',
        '"voxlink.modsync.host_unknown": "%d 款 mod 未能識，房客當手裝：%s"',
    ],
    "pt_br.json": [
        '"voxlink.modsync.loader_mismatch": "Loader do host %s vs. o seu (%s): os mods não rodam, instale o loader correspondente"',
        '"voxlink.modsync.host_published": "%d mod(s) obrigatório(s) publicado(s), convidados podem baixar."',
        '"voxlink.modsync.host_unknown": "%d mod(s) não reconhecido(s), convidados precisam instalar manualmente: %s"',
    ],
    "ru_ru.json": [
        '"voxlink.modsync.loader_mismatch": "Загрузчик хоста %s против вашего (%s): моды не запустятся, установите подходящий загрузчик"',
        '"voxlink.modsync.host_published": "Опубликовано %d обязательных мод(ов), гости могут скачать."',
        '"voxlink.modsync.host_unknown": "%d мод(ов) не распознано, гости должны установить вручную: %s"',
    ],
    "zh_cn.json": [
        '"voxlink.modsync.loader_mismatch": "房主加载器 %s 与你 (%s) 不同：mod 无法运行，请安装匹配的加载器"',
        '"voxlink.modsync.host_published": "已发布 %d 项必装 mod，房客可下载。"',
        '"voxlink.modsync.host_unknown": "%d 个 mod 无法识别，房客需手动安装：%s"',
    ],
    "zh_hk.json": [
        '"voxlink.modsync.loader_mismatch": "房主載入器 %s 同你 (%s) 唔同：mod 跑唔到，請裝返同款載入器"',
        '"voxlink.modsync.host_published": "已發佈 %d 項必裝 mod，房客可下載。"',
        '"voxlink.modsync.host_unknown": "%d 個 mod 認唔到，房客要手動裝：%s"',
    ],
    "zh_tw.json": [
        '"voxlink.modsync.loader_mismatch": "房主載入器 %s 與你 (%s) 不同：mod 無法執行，請安裝相符的載入器"',
        '"voxlink.modsync.host_published": "已發佈 %d 項必裝 mod，房客可下載。"',
        '"voxlink.modsync.host_unknown": "%d 個 mod 無法識別，房客須手動安裝：%s"',
    ],
}


def i18n_apply(tmpl: str) -> int:
    """Apply i18n keys to all14 lang files for a given template. Returns count."""
    cnt = 0
    for basename in LANG_BASENAMES:
        rel = f"{tmpl}/src/main/resources/assets/voxlink/lang/{basename}"
        if not os.path.exists(os.path.join(ROOT, rel)):
            continue  # lang file not present in this template -> skip
        old = LANG_DOWNLOAD_CANCELLED[basename] + "\n}\n"
        keys = LANG_NEW_KEYS[basename]
        new = (
            LANG_DOWNLOAD_CANCELLED[basename] + ",\n"
            + "    " + keys[0] + ",\n"
            + "    " + keys[1] + ",\n"
            + "    " + keys[2] + "\n"
            + "}\n"
        )
        if replace_with_eol(rel, old, new, f"i18n {basename}"):
            cnt += 1
    return cnt


def main():
    summary = []  # (template, label, action)

    for tmpl in TEMPLATES:
        # Fix1: LOADER per template
        if fix1_loader(tmpl):
            summary.append((tmpl, "fix1 LOADER", "applied"))

        # Fix2 + Fix3
        c2, c3 = fix23_guest(tmpl)
        if c2:
            summary.append((tmpl, "fix2 empty-mods gate", "applied"))
        if c3:
            summary.append((tmpl, "fix3 loader_mismatch", "applied"))

        # Fix4: manifest service (5 sub-anchors)
        c4 = fix4_manifest(tmpl)
        if c4:
            summary.append((tmpl, f"fix4 manifest ({c4}/5)", "applied"))

        # i18n: 14 lang files
        ci = i18n_apply(tmpl)
        if ci:
            summary.append((tmpl, f"i18n ({ci}/14)", "applied"))

    print(f"\n=== Summary: {len(summary)} replacements across 25 templates ===")
    for t, lbl, action in summary:
        print(f"  {t:<30}  {lbl:<28}  {action}")
    print(f"\ntotal applied: {len(summary)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())