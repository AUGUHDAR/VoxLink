package icu.wuhui.voxlink.command;

import icu.wuhui.voxlink.VoxLinkMod;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * LAN 房主特权判定的"启动快照"注册表。
 *
 * <p>背景（安全修复）：旧版 {@code isLanHost} 用 {@code src.getTextName().equals(本地用户名)}
 * 放行 op/ban/kick 等命令。离线模式下 UUID 由用户名派生，攻击者只要起一个与房主同名的
 * 离线档案即可通过名字比较拿到 OP。按 UUID 比较也不够（离线 UUID 同样按名字派生），
 * 因此必须引入<b>时间维度</b>：在集成服务器启动后立刻对在线玩家做一次快照。
 *
 * <p>时序保证：Open to LAN 是房主已在世界中之后手动开启的，任何远程玩家都不可能先于
 * 快照在场；后来者无论同名还是同离线 UUID，其 profile id 都不在快照中，无法获得
 * LAN 主机特权。
 *
 * <p>该类只使用 MinecraftServer/ServerPlayer 等官方映射公共 API 与纯 JDK 结构，
 * 不依赖 Fabric 专属接口，可直接平移到 Forge/NeoForge（仅需在各 loader 的
 * server starting/stopping 事件里调用 {@link #scheduleCapture}/{@link #clear}）。
 */
public final class LanHostRegistry {
   /** 集成服务器启动后等待玩家列表就绪的轮询窗口（约 10 秒，10 tick 一次）。 */
   private static final int CAPTURE_MAX_ATTEMPTS = 20;
   private static final int CAPTURE_INTERVAL_TICKS = 10;
   /**
    * 懒捕获的宽限窗：超过该毫秒数仍无快照时，允许把查询者本人（且必须等于
    * singleplayerProfile）懒登记为启动成员。
    */
   private static final long LAZY_CAPTURE_GRACE_MS = 15000L;

   /** 启动时刻在线玩家的 profile id 快照；空集表示尚未捕获。 */
   private static volatile Set<UUID> bootstrapProfiles = Collections.emptySet();
   private static volatile long serverStartMs = 0L;
   private static volatile long capturedAtMs = 0L;

   private LanHostRegistry() {
   }

   /**
    * 在集成服务器启动后安排快照捕获。立即尝试一次，随后在服务端线程上以
    * {@code server.execute} 轮询重试，直到玩家列表非空或超时（约 10 秒）。
     * 必须传入刚启动的服务器实例；重复调用以最新一次为准（旧轮询自然失效）。
    */
   public static void scheduleCapture(MinecraftServer server) {
      serverStartMs = System.currentTimeMillis();
      bootstrapProfiles = Collections.emptySet();
      capturedAtMs = 0L;
      scheduleCaptureAttempt(server, 0);
   }

   private static void scheduleCaptureAttempt(MinecraftServer server, int attempt) {
      try {
         // 服务端命令/状态读取须在服务端线程执行
         server.execute(() -> {
            if (server.isStopped()) {
               return;
            }

            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            boolean emptySnapshot = bootstrapProfiles.isEmpty();
            if (!players.isEmpty() && emptySnapshot) {
               Set<UUID> ids = new HashSet<>();
               for (ServerPlayer p : players) {
                  try {
                     ids.add(p.getUUID());
                  } catch (Exception ignored) {
                  }
               }

               if (!ids.isEmpty()) {
                  bootstrapProfiles = Collections.unmodifiableSet(ids);
                  capturedAtMs = System.currentTimeMillis();
                  VoxLinkMod.LOGGER.info("[LanHost] Bootstrap profile snapshot captured: {} player(s)", ids.size());
               }
            } else if (emptySnapshot && players.isEmpty() && attempt < CAPTURE_MAX_ATTEMPTS) {
               scheduleCaptureAttempt(server, attempt + 1);
            }
         });
      } catch (Exception e) {
         VoxLinkMod.LOGGER.warn("[LanHost] Snapshot capture schedule failed: {}", e.getMessage());
      }
   }

   /** 服务器停止时清空快照，避免跨世界残留授权。 */
   public static void clear() {
      Set<UUID> old = bootstrapProfiles;
      if (old != null && !old.isEmpty()) {
         VoxLinkMod.LOGGER.info("[LanHost] Bootstrap profile snapshot cleared");
      }

      bootstrapProfiles = Collections.emptySet();
      capturedAtMs = 0L;
      serverStartMs = 0L;
   }

   /**
    * 判定给定 profile 是否属于启动快照（即真正的房主）。
    *
    * <p>懒捕获兜底：若快照仍为空但服务器已运行超过宽限窗（说明定时捕获一直没等到
    * 非空玩家列表，例如极端卡顿），则允许把"本次查询到的、且与
    * {@code server.getSingleplayerProfile()} 完全一致"的那个 profile 登记进快照。
    * 理论竞态说明：任务书原始方案允许"懒捕获首个查询到的任意玩家"，若攻击者在
    * 宽限窗后抢先执行命令会被误登记；本实现把懒捕获锚定到 vanilla 维护的
    * singleplayerProfile（即本地玩家档案本体），同名攻击者的离线 UUID 不可能与之
    * 相等，从而消除该竞态——这是比"首个查询者"更严格且语义等价于房主本人的兜底。
    */
   public static boolean isBootstrapProfile(MinecraftServer server, UUID queriedProfileId) {
      if (queriedProfileId == null) {
         return false;
      } else {
         Set<UUID> snapshot = bootstrapProfiles;
         if (!snapshot.isEmpty()) {
            return snapshot.contains(queriedProfileId);
         } else {
            long start = serverStartMs;
            return start > 0L
               && System.currentTimeMillis() - start > LAZY_CAPTURE_GRACE_MS
               && !server.isStopped()
               && lazyCapture(server, queriedProfileId);
         }
      }
   }

   private static synchronized boolean lazyCapture(MinecraftServer server, UUID queriedProfileId) {
      // double-check：拿锁后重新读快照，防止并发重复登记
      Set<UUID> snapshot = bootstrapProfiles;
      if (!snapshot.isEmpty()) {
         return snapshot.contains(queriedProfileId);
      }

      UUID localProfileId = null;
      try {
         localProfileId = server.getSingleplayerProfile() != null ? server.getSingleplayerProfile().getId() : null;
      } catch (Exception ignored) {
      }

      if (localProfileId != null && localProfileId.equals(queriedProfileId)) {
         bootstrapProfiles = Collections.unmodifiableSet(new CopyOnWriteArraySet<>(Collections.singletonList(queriedProfileId)));
         capturedAtMs = System.currentTimeMillis();
         VoxLinkMod.LOGGER.info("[LanHost] Lazy bootstrap capture anchored to singleplayerProfile");
         return true;
      } else {
         return false;
      }
   }

   /** 当前快照（可能为空集），供 applyOpPolicy 等做批量判定与回退。 */
   public static Set<UUID> snapshot() {
      return bootstrapProfiles;
   }

   /** 仅用于测试/诊断。 */
   public static long capturedAt() {
      return capturedAtMs;
   }
}
