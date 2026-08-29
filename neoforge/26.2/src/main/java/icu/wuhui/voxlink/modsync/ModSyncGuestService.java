package icu.wuhui.voxlink.modsync;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.terracotta.RoomCodeRouter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;

/**
 * 房客侧门控：打洞前从信令服务器拉取房主必装清单 → 与本地 mods 离线 diff
 * （sha1 集合对比，客户端全程零 Modrinth API 调用）→ 有缺失时弹选择界面，
 * 下载走清单内 CDN 直链，完成后进入强制重启屏。
 * 开关关闭、旧房主（不支持 modSyncV1）、空清单时零打扰直通。
 */
public final class ModSyncGuestService {
   private static final java.util.Set<String> GATED_THIS_LAUNCH = java.util.concurrent.ConcurrentHashMap.newKeySet();
   /** 本次启动内玩家已选择跳过检查的房间：在途清单回调静默丢弃，绝不弹窗打断加入。 */
   private static final java.util.Set<String> BYPASSED_THIS_LAUNCH = java.util.concurrent.ConcurrentHashMap.newKeySet();
   /**
    * 本次启动内弹窗已展示过且用户明确选择（onProceed 或 onCancel）触发的房间。
    * 与 GATED_THIS_LAUNCH 的区别：GATED 是"在途/已展示"标记，玩家直接关闭弹窗
    * （不走任何按钮的 vanilla onClose）时不会清理，导致本启动内该房间检查被永久跳过。
    * USER_CHOSE 才是"放行依据"——只有用户真正点了按钮才置位，下次重试允许重新走门控。
    */
   private static final java.util.Set<String> USER_CHOSE_THIS_LAUNCH = java.util.concurrent.ConcurrentHashMap.newKeySet();
   /** 本次会话内用户已选择"跳过"的版本冲突键（projectId@versionNumber），不再重复打扰。 */
   private static final java.util.Set<String> SKIPPED_THIS_SESSION = java.util.concurrent.ConcurrentHashMap.newKeySet();
   private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "VoxLink-ModSync-Guest");
      t.setDaemon(true);
      return t;
   });
   /** supported 但未 ready 的最长等待：5 次 × 2s（房主 MR 解析通常数秒内完成）。 */
   private static final int READY_RETRIES = 5;

   private ModSyncGuestService() {
   }

   /** 加入失败后清除门控缓存：下次再加入可重新看到下载选择窗。 */
   public static void clearGateFor(String roomCode) {
      if (roomCode != null) {
         GATED_THIS_LAUNCH.remove(roomCode);
      }
   }

   /** 玩家在弹窗点了"直接加入"：本会话记住这些版本冲突，不再重复提示。 */
   public static void markSkipped(java.util.List<String> keys) {
      if (keys != null) {
         SKIPPED_THIS_SESSION.addAll(keys);
      }
   }

   /** 玩家在获取清单页点"直接进入"或取消加入：本次启动内该房间跳过检查，在途回调丢弃。 */
   public static void bypass(String roomCode) {
      if (roomCode != null) {
         BYPASSED_THIS_LAUNCH.add(roomCode);
      }
   }

   /**
    * 是否跳过门控：只认玩家在获取清单页明确点过的"跳过检查"（BYPASSED_THIS_LAUNCH）。
    * 不再做任何"本次启动已弹过就不再弹"的记忆——房主随时可能增删必装 mod，
    * 每次加入都重新拉清单重新判定；弹窗是否展示由 diff 结果决定（无差异不弹，无感知）。
    */
   public static boolean shouldSkipGate(String roomCode) {
      return roomCode != null && BYPASSED_THIS_LAUNCH.contains(roomCode);
   }

   public static boolean isEnabled() {
      return VoxLinkMod.getConfig().isJoinRequiredModsCheck();
   }

   /**
    * 加入流程入口门控（在 join_room / 打洞之前调用）。
    * onProceed/onCancel 都会被调度回主线程执行。
    */
   public static void gate(String roomCode, Runnable onProceed, Runnable onCancel) {
      if (!isEnabled() || !RoomCodeRouter.isVoxLinkCode(roomCode)) {
         onProceed.run();
         return;
      }

      // 玩家已选择跳过检查：直接放行，不再拉清单
      if (BYPASSED_THIS_LAUNCH.contains(roomCode)) {
         onProceed.run();
         return;
      }

      // 每次加入都重新门控（不做会话级记忆）；GATED_THIS_LAUNCH 仅作"在途"标记，
      // 供取消/超时路径清理，不作为放行依据。
      GATED_THIS_LAUNCH.add(roomCode);

      // 包装 onProceed/onCancel：清理在途标记。
      // 不再置位任何"已选择"记忆——shouldSkipGate 只认 BYPASSED_THIS_LAUNCH。
      Runnable proceed = () -> {
         GATED_THIS_LAUNCH.remove(roomCode);
         onProceed.run();
      };
      Runnable cancel = () -> {
         GATED_THIS_LAUNCH.remove(roomCode);
         onCancel.run();
      };


      EXECUTOR.execute(() -> {
         JsonObject manifest = fetchManifestWithRetry(roomCode);
         if (BYPASSED_THIS_LAUNCH.contains(roomCode)) {
            // 玩家已在获取页点"直接进入"或取消：丢弃结果，绝不弹窗打断已开始的加入
            return;
         }
         Minecraft mc = Minecraft.getInstance();
         if (manifest == null) {
            // 拉取失败、房主不支持或清单尚未发布：不打扰，直接继续正常加入流程
            mc.execute(proceed);
            return;
         }

         try {
            DiffResult diff = computeDiff(manifest);
            // MC 版本检测：房主与房客的 MC 不同时，清单只能参考，无法保证可用
            String hostMc = manifest.has("mcVersion") && manifest.get("mcVersion").isJsonPrimitive()
               ? manifest.get("mcVersion").getAsString() : "";
            if (!hostMc.isEmpty() && !hostMc.equals(ModSyncEnv.GAME_VERSION)) {
               ModSyncLog.warn("MC version mismatch: host={} guest={}", new Object[]{hostMc, ModSyncEnv.GAME_VERSION});
               diff.unresolvable.add(0,
                  Component.translatable("voxlink.modsync.mc_mismatch",
                     new Object[]{hostMc, ModSyncEnv.GAME_VERSION}).getString());
            }

            ModSyncLog.info(
               "guest diff: downloadable={} versionDiff={} unresolvable={} titles={}",
               new Object[]{diff.downloadable.size(), diff.versionDiff.size(), diff.unresolvable.size(),
                  ModSyncSelectScreen.clip(joinTitles(diff), 200)}
            );
            if (diff.downloadable.isEmpty() && diff.unresolvable.isEmpty() && diff.versionDiff.isEmpty()) {
               mc.execute(proceed);
               return;
            }

            mc.execute(() -> {
               if (mc.gui.screen() != null) {
                  // 当前一般是 AttemptingJoinScreen：切到选择屏；继续时由调用方重建加入流程
                  Minecraft.getInstance().gui.setScreen(
                     new ModSyncSelectScreen(
                        roomCode,
                        diff.downloadable,
                        diff.unresolvable,
                        diff.versionDiff,
                        diff.skipKeys,
                        proceed,
                        cancel
                     )
                  );
               } else {
                  cancel.run();
               }
            });
         } catch (Throwable t) {
            ModSyncLog.warn("guest diff failed, proceed without gate: {}", t.toString());
            // 静默放行有风险：玩家没看到弹窗就被放行进了房间，可能与房主 Mod 不一致
            // 仍出问题。先在聊天栏给个黄色强提示，由玩家自行判断。
            mc.execute(() -> {
               try {
                  if (mc.player != null) {
                     mc.player.sendSystemMessage(
                        Component.translatable("voxlink.modsync.gate_failed_warn")
                           .withStyle(ChatFormatting.YELLOW)
                     );
                  }
               } catch (Exception ignored) {
               }

               proceed.run();
            });
         }
      });
   }

   /**
    * 拆粒度可中断睡眠：100ms 一查 BYPASSED_THIS_LAUNCH/线程中断。
    * 原 1.5s/2s 的 Thread.sleep 期间玩家点"直接进入"会等到下一次循环才被外层处理，
    * 关弹窗体验僵硬；现在最坏 100ms 就能感知取消。
    * 返回 true 表示"睡满"，false 表示"被打断（取消/中断）"。
    */
   private static boolean cancellableSleep(String roomCode, long millis) {
      long end = System.currentTimeMillis() + millis;
      while (millis > 0) {
         if (BYPASSED_THIS_LAUNCH.contains(roomCode)) {
            return false;
         }

         if (Thread.currentThread().isInterrupted()) {
            Thread.currentThread().interrupt();
            return false;
         }

         long step = Math.min(100L, millis);
         try {
            Thread.sleep(step);
         } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
         }

         millis -= step;
      }

      return !BYPASSED_THIS_LAUNCH.contains(roomCode);
   }

   /** 返回 null 表示"无需处理"（不支持/拉取失败/未就绪超时/空清单）。 */
   private static JsonObject fetchManifestWithRetry(String roomCode) {
      boolean sawNotReady = false;
      boolean sawTransient = false;
      // 弱网实测：信令 8 秒超时很常见。超时/网络类失败必须与"未就绪"一样重试，
      // 否则一次抖动就放弃检查，占位文案僵住后莫名放行。
      int maxAttempts = READY_RETRIES * 2;
      // 总时限兜底：无论瞬态失败还是清单迟迟未就绪，最多等 12 秒，
      // 绝不让玩家对着"正在获取"页面无限等待（每次尝试 5s 超时）
      long deadline = System.currentTimeMillis() + 12000L;
      for (int attempt = 0; attempt < maxAttempts && System.currentTimeMillis() < deadline; attempt++) {
         // 玩家已点"直接进入"或关弹窗：立刻退出循环，丢弃本次结果
         if (BYPASSED_THIS_LAUNCH.contains(roomCode)) {
            return null;
         }

         try {
            var resp = VoxLinkMod.getSignalingClient()
               .getRoomMods(roomCode)
               .get(5L, TimeUnit.SECONDS);
            if (!resp.success || resp.data == null) {
               String err = resp.error != null ? resp.error.toUpperCase(java.util.Locale.ROOT) : "";
               boolean authoritative = err.contains("ROOM_NOT_FOUND") || err.contains("ROOM_EXPIRED")
                  || err.contains("INVALID_TOKEN") || err.contains("ROOM_CLOSED") || err.contains("ROOM_EVICTED")
                  // 老部署服务器没有 /room/mods 路由：房主必不支持清单，立即直通不重试
                  || err.contains("UNKNOWN_ENDPOINT") || err.contains("INVALID_ENDPOINT");
               if (authoritative) {
                  ModSyncLog.warn("getRoomMods failed (authoritative): {} {}", resp.error, resp.message);
                  return null;
               }

               sawTransient = true;
               ModSyncLog.warn("getRoomMods transient ({}/{}): {}", new Object[]{attempt + 1, maxAttempts, resp.error});
               if (!cancellableSleep(roomCode, 1500L)) {
                  return null;
               }

               continue;
            }

            boolean supported = resp.data.has("supported") && resp.data.get("supported").getAsBoolean();
            if (!supported) {
               return null;
            }

            boolean ready = resp.data.has("ready") && resp.data.get("ready").getAsBoolean();
            if (ready) {
               JsonObject m = new JsonObject();
               m.addProperty("loader", resp.data.has("loader") ? resp.data.get("loader").getAsString() : "unknown");
               m.addProperty("mcVersion", resp.data.has("mcVersion") ? resp.data.get("mcVersion").getAsString() : "");
               m.add("mods", resp.data.has("mods") && resp.data.get("mods").isJsonArray()
                  ? resp.data.getAsJsonArray("mods")
                  : new com.google.gson.JsonArray());
               if (m.getAsJsonArray("mods").size() == 0) {
                  return null;
               }

               return m;
            }

            sawNotReady = true;
            if (!cancellableSleep(roomCode, 2000L)) {
               return null;
            }
         } catch (java.util.concurrent.TimeoutException te) {
            sawTransient = true;
            ModSyncLog.warn("getRoomMods timeout ({}/{})", new Object[]{attempt + 1, maxAttempts});
         } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
         } catch (Exception e) {
            sawTransient = true;
            ModSyncLog.warn("manifest fetch error: {}", e.toString());
         }
      }

      if (sawNotReady || sawTransient) {
         // 房主清单一直没发布（MR 解析慢/失败）：不算完成门控，撤销本次缓存允许下次重试
         ModSyncLog.warn("manifest never became ready, giving up gate");
         GATED_THIS_LAUNCH.remove(roomCode);
      }

      return null;
   }

   private static String joinTitles(DiffResult diff) {
      StringBuilder sb = new StringBuilder("[");
      int n = 0;
      for (ModSyncEntry e : diff.downloadable) {
         if (n++ > 0) {
            sb.append(", ");
         }

         sb.append(e.title).append('@').append(e.versionNumber);
      }

      for (String s : diff.versionDiff) {
         if (n++ > 0) {
            sb.append(", ");
         }

         sb.append('~').append(s);
      }

      for (String s : diff.unresolvable) {
         if (n++ > 0) {
            sb.append(", ");
         }

         sb.append('?').append(s);
      }

      return sb.append(']').toString();
   }

   public static final class DiffResult {
      public final List<ModSyncEntry> downloadable = new ArrayList<>();
      public final List<String> unresolvable = new ArrayList<>();
      /** 同类模组但版本与房主不同：仅强提示，绝不移动/删除用户已装文件（运行中的 jar 被占用且属用户资产）。 */
      public final List<String> versionDiff = new ArrayList<>();
      /** 与 versionDiff 对应的会话跳过键（直接加入时记入会话记忆）。 */
      public final List<String> skipKeys = new ArrayList<>();
   }

   /**
    * 离线 diff：清单条目自带房主文件的 sha1（VoxLink 1.1.3+ 清单），
    * 与本地 mods 目录的 sha1 集合直接对比——客户端全程零 Modrinth API 调用，
    * 仅按清单里的 CDN 直链下载（sha512 校验）。旧清单无 sha1 时退化为精确文件名匹配。
    *
    * 玩家主动禁用的 jar（xxx.jar.disabled）也参与 sha1 匹配：sha1 一致视为"已装但被禁用"，
    * 转入 unresolvable 提示玩家手动启用；sha1 不一致则按正常缺失走后续分支。
    */
   private static DiffResult computeDiff(JsonObject manifest) throws Exception {
      DiffResult out = new DiffResult();

      ModSyncFileHasher.ListModJarsResult scan = ModSyncFileHasher.listModJarsWithDisabled();
      List<java.nio.file.Path> localJars = scan.jars;
      java.util.Set<String> disabledFileNames = scan.disabled;
      java.util.Set<String> localSha1s = new java.util.HashSet<>();
      java.util.Set<String> localFileNames = new java.util.HashSet<>();
      for (var jar : localJars) {
         try {
            localSha1s.add(ModSyncFileHasher.sha1(jar));
         } catch (Exception ignored) {
         }

         localFileNames.add(jar.getFileName().toString());
      }

      // 把 .jar.disabled 也参与 sha1 匹配（文件内容就是 jar，只是被禁用）
      // 仅在"无活跃 jar 命中 sha1"时有用，避免重复添加；我们最终在"installed"判定里区分状态。
      java.util.Set<String> disabledSha1s = new java.util.HashSet<>();
      if (!disabledFileNames.isEmpty()) {
         java.nio.file.Path modsDir = ModSyncEnv.getModsDir();
         for (String disabledName : disabledFileNames) {
            java.nio.file.Path p = modsDir.resolve(disabledName + ".disabled");
            try {
               if (java.nio.file.Files.isRegularFile(p)) {
                  disabledSha1s.add(ModSyncFileHasher.sha1(p));
               }
            } catch (Exception ignored) {
            }
         }
      }

      // 本地 jar 的归一化基名（去目录词干/版本号）→ 原始文件名；用于识别"装了但版本不同"
      Map<String, String> normBaseToLocal = new HashMap<>();
      Map<String, Long> normSizeByBase = new HashMap<>();
      for (var jar : localJars) {
         String nb = normalizeFileName(jar.getFileName().toString());
         if (!nb.isEmpty()) {
            normBaseToLocal.putIfAbsent(nb, jar.getFileName().toString());
            if (!normSizeByBase.containsKey(nb)) {
               try {
                  normSizeByBase.put(nb, java.nio.file.Files.size(jar));
               } catch (Exception ignored) {
               }
            }
         }
      }

      // 未知模组：房主侧在 MR 查不到的 jar，仅提示（我们没有任何渠道让房客下到它们）
      if (manifest.has("unknownMods") && manifest.get("unknownMods").isJsonArray()) {
         for (var el : manifest.getAsJsonArray("unknownMods")) {
            if (el.isJsonPrimitive()) {
               out.unresolvable.add(el.getAsString());
            }
         }
      }

      for (var el : manifest.getAsJsonArray("mods")) {
         if (!el.isJsonObject()) {
            continue;
         }

         ModSyncEntry entry = ModSyncEntry.fromJson(el.getAsJsonObject());
         if (entry.projectId.isEmpty()) {
            continue;
         }

         // 精确 sha1 匹配：先看活跃 jar，再看 disabled jar
         boolean installed = false;
         boolean installedButDisabled = false;
         if (!entry.sha1.isEmpty()) {
            if (localSha1s.contains(entry.sha1)) {
               installed = true;
            } else if (disabledSha1s.contains(entry.sha1)) {
               installed = true;
               installedButDisabled = true;
            }
         } else if (!entry.fileName.isEmpty() && localFileNames.contains(entry.fileName)) {
            installed = true;
         } else if (disabledFileNames.contains(entry.fileName)) {
            installed = true;
            installedButDisabled = true;
         }
         if (!installed) {
            // 旧清单兼容兜底：归一化名相同且文件大小一致 ⇒ 视为已安装（同版本文件字节级一致）
            String nb2 = normalizeFileName(entry.fileName);
            Long localSize = nb2.isEmpty() ? null : normSizeByBase.get(nb2);
            if (localSize != null && localSize.longValue() == entry.size) {
               installed = true;
            }
         }
         if (installed) {
            if (installedButDisabled) {
               // 玩家主动禁用：不算 missing，提示让其手动启用
               out.unresolvable.add(
                  Component.translatable(
                     "voxlink.modsync.disabled_hint",
                     new Object[]{entry.title, entry.fileName}
                  ).getString()
               );
            }

            continue;
         }

         String normBase = normalizeFileName(entry.fileName);
         if (!normBase.isEmpty() && normBaseToLocal.containsKey(normBase)) {
            // 同类模组但与房主版本不同：绝不擅自动用户的文件，给强提示让玩家自己决定。
            // 本次会话只提示一次（选择"跳过"后记住），避免每次加入都唠叨。
            String skipKey = entry.projectId + "@" + entry.versionNumber;
            if (SKIPPED_THIS_SESSION.add(skipKey)) {
               out.versionDiff.add(
                  Component.translatable("voxlink.modsync.diff_line", e_title(entry)).getString()
               );
               out.skipKeys.add(skipKey);
            }
            continue;
         }

         boolean hostFileCompatible = entry.loaders.contains(ModSyncEnv.LOADER)
            && entry.gameVersions.contains(ModSyncEnv.GAME_VERSION)
            && !entry.downloadUrl.isEmpty();
         if (hostFileCompatible) {
            out.downloadable.add(entry);
         } else {
            // 房主的构建版本与本端 MC/加载器不一致且不允许再查 MR —— 转手动安装提示
            out.unresolvable.add(entry.title + " (" + entry.versionNumber + ")");
         }
      }

      return out;
   }

   private static String e_title(ModSyncEntry e) {
      return e.title;
   }

   /** 归一化文件名基名：小写、去 .jar、只留字母。用于粗判"同一 mod 的不同版本"。 */
   static String normalizeFileName(String fileName) {
      if (fileName == null) {
         return "";
      }

      String s = fileName.toLowerCase(java.util.Locale.ROOT);
      if (s.endsWith(".jar")) {
         s = s.substring(0, s.length() - 4);
      }

      // 词元化并剔除加载器/MC 等噪音词，防"同 mod 不同加载器/版本段"被误判为缺失
      java.util.List<String> stop = java.util.Arrays.asList(
         "fabric", "forge", "neoforge", "quilt", "fml", "mc", "all", "for", "the", "with");
      StringBuilder sb = new StringBuilder(s.length());
      for (String tok : s.split("[^a-z]+")) {
         if (tok.isEmpty() || stop.contains(tok)) {
            continue;
         }

         sb.append(tok);
      }

      return sb.toString();
   }
}
