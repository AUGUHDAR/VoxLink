# -*- coding: utf-8 -*-
"""ModSync 门控可见化修复传播：ModSyncGuestService(3簇整拷) + AttemptingJoinScreen(2锚点) + lang(13语x28模板)"""
import glob, hashlib, json, os, re, sys

BASE = r"d:\桌面\voxlink"
GOLD = os.path.join(BASE, "fabric", "26.3")
errors = []
done = []

def load(p):
    raw = open(p, "rb").read().decode("utf-8")
    eol = "\r\n" if "\r\n" in raw else "\n"
    return raw.replace("\r\n", "\n"), eol

def save(p, t, eol):
    open(p, "wb").write(t.replace("\n", eol).encode("utf-8"))

def json_str(s):
    return json.dumps(s, ensure_ascii=False)

# ---------- 1) ModSyncGuestService ----------
MSG = os.path.join(GOLD, "src", "main", "java", "icu", "wuhui", "voxlink", "modsync", "ModSyncGuestService.java")

PATTERNS = []
def pat(old, new):
    PATTERNS.append((old, new))

pat("* 开关关闭、旧房主（不支持 modSyncV1）、空清单时零打扰直通。\n */",
    "* 开关关闭、旧房主（不支持 modSyncV1）、空清单时直通；直通原因一律落日志，\n * 旧房主与拉取失败另在日志面板留一行原因，空清单仅日志。\n */")
pat("""   /** supported 但未 ready 的最长等待：5 次 × 2s（房主 MR 解析通常数秒内完成）。 */
   private static final int READY_RETRIES = 5;""",
    """   /** supported 但未 ready 的最长等待：5 次 × 2s（房主 MR 解析通常数秒内完成）。 */
   private static final int READY_RETRIES = 5;
   /** 门控直通时的待播报提示：连接启动后由加入屏取出播报，避开 startDualP2P 的面板重置。 */
   private static volatile int pendingNoteLevel = -1;
   private static volatile String pendingNoteKey;""")
pat("""   /** 加入失败后清除门控缓存：下次再加入可重新看到下载选择窗。 */
   public static void clearGateFor(String roomCode) {
      if (roomCode != null) {
         GATED_THIS_LAUNCH.remove(roomCode);
      }
   }""",
    """   /** 加入失败后清除门控缓存：下次再加入可重新看到下载选择窗。 */
   public static void clearGateFor(String roomCode) {
      if (roomCode != null) {
         GATED_THIS_LAUNCH.remove(roomCode);
      }
   }

   /** 加入屏在连接启动后调用：播报并清掉门控直通提示（无提示时为空操作）。 */
   public static void pushPendingGateNote() {
      String key = pendingNoteKey;
      int level = pendingNoteLevel;
      pendingNoteKey = null;
      pendingNoteLevel = -1;
      if (key != null) {
         icu.wuhui.voxlink.ui.UiLogBus.push(level, key);
      }
   }""")
pat("""   public static void bypass(String roomCode) {
      if (roomCode != null) {
         BYPASSED_THIS_LAUNCH.add(roomCode);
      }
   }""",
    """   public static void bypass(String roomCode) {
      if (roomCode != null) {
         BYPASSED_THIS_LAUNCH.add(roomCode);
         ModSyncLog.info("gate: player bypassed, room={}", roomCode);
      }
   }""")
pat("""      final String fetchScope = ModSyncManifestService.SCOPE_ALL.equals(scope)
         ? ModSyncManifestService.SCOPE_ALL : ModSyncManifestService.SCOPE_REQUIRED;
      EXECUTOR.execute(() -> {
         JsonObject manifest = fetchManifestWithRetry(roomCode, fetchScope);
         if (BYPASSED_THIS_LAUNCH.contains(roomCode)) {
            // 玩家已在获取页点"直接进入"或取消：丢弃结果，绝不弹窗打断已开始的加入
            return;
         }
         Minecraft mc = Minecraft.getInstance();
         if (manifest == null) {
            // 拉取失败、房主不支持或清单尚未发布：不打扰，直接继续正常加入流程
            mc.execute(proceed);
            return;
         }""",
    """      final String fetchScope = ModSyncManifestService.SCOPE_ALL.equals(scope)
         ? ModSyncManifestService.SCOPE_ALL : ModSyncManifestService.SCOPE_REQUIRED;
      // 每次门控重置在途提示；入口留一行，真机日志可据此判定 gate 是否被调用
      pendingNoteKey = null;
      pendingNoteLevel = -1;
      ModSyncLog.info("gate: room={} scope={}", roomCode, fetchScope);
      EXECUTOR.execute(() -> {
         FetchResult fetched = fetchManifestWithRetry(roomCode, fetchScope);
         if (BYPASSED_THIS_LAUNCH.contains(roomCode)) {
            // 玩家已在获取页点"直接进入"或取消：丢弃结果，绝不弹窗打断已开始的加入
            return;
         }
         Minecraft mc = Minecraft.getInstance();
         if (fetched.outcome != FetchOutcome.MANIFEST) {
            // 直通原因一律留痕；房主不支持/拉取失败再挂面板提示，由加入屏在连接启动后播报
            ModSyncLog.info("gate skip: {}", fetched.outcome);
            if (fetched.outcome == FetchOutcome.UNSUPPORTED) {
               pendingNoteLevel = 0;
               pendingNoteKey = "voxlink.logui.modsync_unsupported";
            } else if (fetched.outcome == FetchOutcome.NOT_READY) {
               pendingNoteLevel = 2;
               pendingNoteKey = "voxlink.logui.modsync_fetch_failed";
            }

            mc.execute(proceed);
            return;
         }

         JsonObject manifest = fetched.manifest;""")
pat("""   /** 返回 null 表示"无需处理"（不支持/拉取失败/未就绪超时/空清单）。 */
   private static JsonObject fetchManifestWithRetry(String roomCode, String scope) {""",
    """   /** 清单拉取结果原因：区分静默直通的具体路径，供日志与玩家提示使用。 */
   private enum FetchOutcome { MANIFEST, UNSUPPORTED, EMPTY, NOT_READY, BYPASSED }

   private static final class FetchResult {
      final FetchOutcome outcome;
      final JsonObject manifest;

      FetchResult(FetchOutcome outcome, JsonObject manifest) {
         this.outcome = outcome;
         this.manifest = manifest;
      }
   }

   /** 拉取结果：MANIFEST=拿到清单；其余为直通原因（不支持/空清单/未就绪或失败/玩家跳过）。 */
   private static FetchResult fetchManifestWithRetry(String roomCode, String scope) {""")
pat("""         // 玩家已点"直接进入"或关弹窗：立刻退出循环，丢弃本次结果
         if (BYPASSED_THIS_LAUNCH.contains(roomCode)) {
            return null;
         }""",
    """         // 玩家已点"直接进入"或关弹窗：立刻退出循环，丢弃本次结果
         if (BYPASSED_THIS_LAUNCH.contains(roomCode)) {
            return new FetchResult(FetchOutcome.BYPASSED, null);
         }""")
pat("""               if (authoritative) {
                  ModSyncLog.warn("getRoomMods failed (authoritative): {} {}", resp.error, resp.message);
                  return null;
               }

               sawTransient = true;
               ModSyncLog.warn("getRoomMods transient ({}/{}): {}", new Object[]{attempt + 1, maxAttempts, resp.error});
               if (!cancellableSleep(roomCode, 1500L)) {
                  return null;
               }""",
    """               if (authoritative) {
                  ModSyncLog.warn("getRoomMods failed (authoritative): {} {}", resp.error, resp.message);
                  return new FetchResult(FetchOutcome.NOT_READY, null);
               }

               sawTransient = true;
               ModSyncLog.warn("getRoomMods transient ({}/{}): {}", new Object[]{attempt + 1, maxAttempts, resp.error});
               if (!cancellableSleep(roomCode, 1500L)) {
                  return new FetchResult(FetchOutcome.BYPASSED, null);
               }""")
pat("""            boolean supported = resp.data.has("supported") && resp.data.get("supported").getAsBoolean();
            if (!supported) {
               return null;
            }""",
    """            boolean supported = resp.data.has("supported") && resp.data.get("supported").getAsBoolean();
            if (!supported) {
               return new FetchResult(FetchOutcome.UNSUPPORTED, null);
            }""")
pat("""               if (m.getAsJsonArray("mods").size() == 0 && m.getAsJsonArray("unknownMods").size() == 0) {
                  return null;
               }

               return m;
            }

            sawNotReady = true;
            if (!cancellableSleep(roomCode, 2000L)) {
               return null;
            }""",
    """               if (m.getAsJsonArray("mods").size() == 0 && m.getAsJsonArray("unknownMods").size() == 0) {
                  return new FetchResult(FetchOutcome.EMPTY, null);
               }

               return new FetchResult(FetchOutcome.MANIFEST, m);
            }

            sawNotReady = true;
            if (!cancellableSleep(roomCode, 2000L)) {
               return new FetchResult(FetchOutcome.BYPASSED, null);
            }""")
pat("""两者都为空才是真正的空清单, 才 return null。""",
    """两者都为空才是真正的空清单, 才直通。""")
pat("""         } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
         } catch (Exception e) {""",
    """         } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new FetchResult(FetchOutcome.NOT_READY, null);
         } catch (Exception e) {""")
pat("""         ModSyncLog.warn("manifest never became ready, giving up gate");
         GATED_THIS_LAUNCH.remove(roomCode);
      }

      return null;
   }""",
    """         ModSyncLog.warn("manifest never became ready, giving up gate");
         GATED_THIS_LAUNCH.remove(roomCode);
      }

      return new FetchResult(FetchOutcome.NOT_READY, null);
   }""")

def apply_patterns(path, label):
    t, eol = load(path)
    if "pushPendingGateNote" in t:
        done.append(label + ": already done, skip")
        return True
    ok = True
    for i, (old, new) in enumerate(PATTERNS):
        n = t.count(old)
        if n != 1:
            errors.append("%s: pattern P%d count=%d" % (label, i + 1, n))
            ok = False
    if not ok:
        return False
    for old, new in PATTERNS:
        t = t.replace(old, new, 1)
    save(path, t, eol)
    done.append(label + ": edited")
    return True

msg_files = sorted(glob.glob(os.path.join(BASE, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "modsync", "ModSyncGuestService.java")))
assert len(msg_files) == 28, len(msg_files)
clusters = {}
for f in msg_files:
    h = hashlib.md5(open(f, "rb").read()).hexdigest()
    clusters.setdefault(h, []).append(f)
gold_key = None
for h, lst in clusters.items():
    if MSG in lst:
        gold_key = h
assert gold_key is not None, "gold not in any cluster"
for h, lst in clusters.items():
    rep = MSG if MSG in lst else lst[0]
    if not apply_patterns(rep, "MSG-rep " + os.path.relpath(rep, BASE)):
        continue
    data = open(rep, "rb").read()
    for f in lst:
        if f == rep:
            continue
        open(f, "wb").write(data)
        done.append("MSG copy -> " + os.path.relpath(f, BASE))

# ---------- 2) AttemptingJoinScreen ----------
A1_OLD = "         this.modSyncChecking = true;"
A1_NEW = """         this.modSyncChecking = true;
         VoxLinkMod.LOGGER.info("[ModSync] gate: waiting for scope selection, room={}", this.roomCode);"""
A2_OLD = "      this.startConnectionMonitor();"
A2_NEW = """      this.startConnectionMonitor();
      // 门控直通提示在面板重置后播报
      icu.wuhui.voxlink.modsync.ModSyncGuestService.pushPendingGateNote();"""

ajs_files = sorted(glob.glob(os.path.join(BASE, "*", "*", "src", "main", "java", "icu", "wuhui", "voxlink", "ui", "AttemptingJoinScreen.java")))
assert len(ajs_files) == 28, len(ajs_files)
for f in ajs_files:
    label = "AJS " + os.path.relpath(f, BASE)
    t, eol = load(f)
    if "pushPendingGateNote" in t:
        done.append(label + ": already done, skip")
        continue
    if t.count(A1_OLD) != 1 or t.count(A2_OLD) != 1:
        errors.append("%s: anchor A1=%d A2=%d" % (label, t.count(A1_OLD), t.count(A2_OLD)))
        continue
    if "import icu.wuhui.voxlink.VoxLinkMod;" not in t:
        errors.append(label + ": missing VoxLinkMod import")
        continue
    t = t.replace(A1_OLD, A1_NEW, 1).replace(A2_OLD, A2_NEW, 1)
    save(f, t, eol)
    done.append(label + ": edited")

# ---------- 3) lang ----------
TRANS = {
    "zh_cn.json": ("房主未启用模组清单，已跳过检查", "无法获取房主模组清单，已跳过检查"),
    "zh_tw.json": ("房主未啟用模組清單，已跳過檢查", "無法取得房主模組清單，已跳過檢查"),
    "zh_hk.json": ("房主未啟用模組清單，已跳過檢查", "無法獲取房主模組清單，已跳過檢查"),
    "lzh.json": ("房主未啟模組清單，逕越其檢。", "取房主模組清單不成，逕越其檢。"),
    "en_us.json": ("Host hasn't enabled their mod list, check skipped", "Couldn't fetch host's mod list, check skipped"),
    "ja_jp.json": ("ホストがModリストを有効化していないため、チェックをスキップしました", "ホストのModリストを取得できなかったため、チェックをスキップしました"),
    "ko_kr.json": ("호스트가 모드 목록을 사용하지 않아 검사를 건너뜁니다", "호스트의 모드 목록을 가져오지 못해 검사를 건너뜁니다"),
    "ru_ru.json": ("У хоста не включён список модов, проверка пропущена", "Не удалось получить список модов хоста, проверка пропущена"),
    "de_de.json": ("Mod-Liste des Hosts ist nicht aktiviert, Prüfung übersprungen", "Mod-Liste des Hosts konnte nicht abgerufen werden, Prüfung übersprungen"),
    "fr_fr.json": ("Liste de mods de l'hôte désactivée, vérification ignorée", "Impossible de récupérer la liste de mods de l'hôte, vérification ignorée"),
    "es_es.json": ("La lista de mods del host no está activada, comprobación omitida", "No se pudo obtener la lista de mods del host, comprobación omitida"),
    "pt_br.json": ("A lista de mods do host não está ativada, checagem ignorada", "Não foi possível obter a lista de mods do host, checagem ignorada"),
    "ar_sa.json": ("قائمة مودات المضيف غير مُفعّلة، تم تخطي الفحص", "تعذّر جلب قائمة مودات المضيف، تم تخطي الفحص"),
}

lang_files = sorted(glob.glob(os.path.join(BASE, "*", "*", "src", "main", "resources", "assets", "voxlink", "lang", "*.json")))
assert len(lang_files) == 364, len(lang_files)
LANG_RE = re.compile(r'^([ \t]*)"voxlink\.logui\.join_failed":.*$', re.M)
for f in lang_files:
    name = os.path.basename(f)
    label = "LANG " + os.path.relpath(f, BASE)
    if name not in TRANS:
        errors.append(label + ": no translation entry")
        continue
    uns, fail = TRANS[name]
    t, eol = load(f)
    if '"voxlink.logui.modsync_unsupported"' in t:
        done.append(label + ": already done, skip")
        continue
    if t.count('"voxlink.logui.join_failed"') != 1 or '"voxlink.connection.join_no_response"' not in t:
        errors.append(label + ": join_failed anchor bad")
        continue
    m = LANG_RE.search(t)
    if not m:
        errors.append(label + ": join_failed line not found")
        continue
    indent = m.group(1)
    ins = '%s"voxlink.logui.modsync_unsupported": %s,\n%s"voxlink.logui.modsync_fetch_failed": %s,' % (
        indent, json_str(uns), indent, json_str(fail))
    t = t[:m.end()] + "\n" + ins + t[m.end():]
    save(f, t, eol)
    done.append(label + ": edited")

print("done:", len(done))
for d in done:
    print("  ", d)
print("errors:", len(errors))
for e in errors:
    print("  ERR", e)
sys.exit(1 if errors else 0)
