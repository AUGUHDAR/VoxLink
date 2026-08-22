package icu.wuhui.voxlink.room;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.network.AddressBlacklist;
import icu.wuhui.voxlink.network.ConnectionFallback;
import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.network.LogUploadManager;
import icu.wuhui.voxlink.network.FirewallBlockedException;
import icu.wuhui.voxlink.network.NatClass;
import icu.wuhui.voxlink.network.P2PBridge;
import icu.wuhui.voxlink.network.PortPredictor;
import icu.wuhui.voxlink.network.ProtocolNegotiator;
import icu.wuhui.voxlink.network.PunchFailureClassifier;
import icu.wuhui.voxlink.network.PunchParams;
import icu.wuhui.voxlink.network.PunchProfile;
import icu.wuhui.voxlink.network.PunchResult;
import icu.wuhui.voxlink.network.PunchStrategy;
import icu.wuhui.voxlink.network.PunchStrategySelector;
import icu.wuhui.voxlink.network.PunchTuner;
import icu.wuhui.voxlink.network.ScenarioTier;
import icu.wuhui.voxlink.network.ScenarioTier;
import icu.wuhui.voxlink.network.RelayBridge;
import icu.wuhui.voxlink.network.ReliableUdpTransport;
import icu.wuhui.voxlink.network.UdpForwardBridge;
import icu.wuhui.voxlink.network.SignalingClient;
import icu.wuhui.voxlink.network.StunProbe;
import icu.wuhui.voxlink.network.UPnPManager;
import icu.wuhui.voxlink.network.UdpHolePuncher;
import icu.wuhui.voxlink.terracotta.RoomCodeRouter;
import icu.wuhui.voxlink.terracotta.TerracottaBinary;
import icu.wuhui.voxlink.terracotta.TerracottaManager;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class ConnectionManager {
   private final RoomManager roomManager;
   private final SignalingClient signalingClient;
   private final ScheduledExecutorService scheduler;
   private final ExecutorService punchExecutor;
   private final ConcurrentHashMap<String, UdpHolePuncher> activeHolePunchers = new ConcurrentHashMap<>();
   private final List<UdpForwardBridge> udpForwardBridges = new CopyOnWriteArrayList<>();
   private final ConcurrentHashMap<String, ReliableUdpTransport> activeUdpTransports = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<String, ReliableUdpTransport> oldUdpTransports = new ConcurrentHashMap<>();
   private static final int ICE_POOL_RETAIN_SECONDS = 12;
   private static final int TCP_CONNECT_TIMEOUT_MS = 5000;
   private static final int SHORT_SLEEP_MS = 100;
   private static final int EXTRA_TIMEOUT_SEC = 5;
   private static final int AWAIT_TIMEOUT_SEC = 20;
   private static final int RELAY_GRACE_MS = 3000;
   private static final int MAX_DELAY_MS = 6000;
   private static final int PROBE_SOCKET_TIMEOUT_MS = 1000;
   private static final int MAX_FALLBACK_LOOPS = 200;
   private static final int FALLBACK_SLEEP_MS = 300;
   private static final int MAX_RELAY_CANDIDATES = 3;
   private static final int SHORT_TIMEOUT_SEC = 8;
   private static final int RELAY_SETUP_TIMEOUT_SEC = 15;
   private static final int POLL_INTERVAL_MS = 500;
   private static final int AWAIT_TERM_SEC = 2;
   private static final int REVERSE_PUNCH_TIMEOUT_SEC = 20;
   private static final int STUN_PROBE_TIMEOUT_SEC = 10;
   private static final int RTT_SYNC_MAX_DELAY_MS = 8000;
   private static final int HOST_MAPPED_RESYNC_MS = 3000;
   private static final byte PUNCH_ACK_TYPE = 2;
   private static final long RELAY_FAILURE_WINDOW_MS = 60000L;
   private final Map<String, Long> failedRelayPeers = new ConcurrentHashMap<>();
   private final AtomicReference<String> currentRelayPeer = new AtomicReference<>(null);
   private volatile ScheduledFuture<?> relayFailoverTask = null;
   private volatile int nextRelayEligibleRound = 0;
   private volatile boolean manualRelayInProgress = false;
   private final AtomicBoolean connectionCycleActive = new AtomicBoolean(false);
   private final AtomicBoolean reversePunchAttempted = new AtomicBoolean(false);
   private final AtomicBoolean connectionWon = new AtomicBoolean(false);
   private final List<ConnectionFallback> activeFallbacks = new CopyOnWriteArrayList<>();
   private static final int CONTINUOUS_RETRY_MAX_ROUNDS = Integer.MAX_VALUE;
   private final AtomicBoolean continuousRetryCancelled = new AtomicBoolean(false);
   private final AtomicInteger continuousRetryRound = new AtomicInteger(0);
   private volatile PunchFailureClassifier.FailureReason lastFailureReason;
   private final AtomicInteger consecutiveFailureCount = new AtomicInteger(0);
   private static final int ICE_RESTART_MAX_ATTEMPTS = 3;
   private static final long ICE_RESTART_COOLDOWN_MS = 5000L;
   private final AtomicInteger iceRestartAttempts = new AtomicInteger(0);
   private final AtomicLong lastIceRestartTimeMs = new AtomicLong(0L);
   private volatile RoomManager.RoomState savedConnectionState;
   private volatile String savedConnectionFrom = "";
   private volatile String savedConnectionHostIpv6;
   private volatile String savedConnectionHostIp;
   private volatile int savedConnectionHostPort;
   private volatile String savedConnectionHostMappedIp;
   private volatile int savedConnectionHostMappedPort;
   private volatile ScheduledFuture<?> connectionTimeoutFuture;
   private volatile ScheduledFuture<?> connectionCycleSafetyTimeout;
   private volatile long connectionStartTimeMs;
   private volatile int connectionTimeoutSec;
   private volatile StunProbe.ProbeResult stunProbeResult;
   private final AtomicReference<CompletableFuture<StunProbe.ProbeResult>> stunProbeFutureRef = new AtomicReference<>();
   private volatile String lastPunchInfoId = "";
   private volatile List<Integer> lastHostMappedPorts;
   private volatile boolean hostPunching = false;
   private static final int TARGET_CHANGE_IGNORE_MS = 3000;
   private volatile long lastPunchStartMs = 0L;
   private volatile boolean relayConnectedSignaled = false;
   private static final int RELAY_REGISTRATION_RENEWAL_SEC = 60;
   private volatile CompletableFuture<Void> dualVoxlinkBridgeFuture;
   private static final int DUAL_VOXLINK_BRIDGE_TIMEOUT_SEC = 120;
   private volatile boolean dualRaceActive = false;
   private volatile boolean terracottaWon = false;
   private volatile boolean voxlinkWon = false;
   private volatile boolean voxlinkSideDisabled = false;
   private volatile CompletableFuture<Void> dualResultRef;
   private final AtomicInteger dualFailedCount = new AtomicInteger(0);
   private final AtomicBoolean dualRaceWon = new AtomicBoolean(false);
   private static final long HANDOFF_GRACE_MS = 5000L;
   private volatile long connectionEstablishedAtMs = 0L;
   private volatile PunchResult lastPunchResult;
   private volatile NatClass localNatClass = NatClass.UNKNOWN;
   private volatile NatClass remoteNatClass = NatClass.UNKNOWN;
   private volatile ScenarioTier.Tier scenarioTier = ScenarioTier.Tier.NORMAL;
   private volatile PunchProfile activePunchProfile = PunchProfile.DEFAULT;
   private volatile PunchParams activePunchParams;
   private volatile CompletableFuture<Void> relayPrefetchFuture;
   private static final int PUNCH_INFO_WAIT_TIMEOUT_S = 15;
   private static final long[] BACKOFF_DELAYS_MS = new long[]{1000L, 2000L, 4000L};
   private static final int JOIN_QUEUE_RETRY_SEC = 10;
   private final AddressBlacklist addressBlacklist = new AddressBlacklist();
   private volatile ConnectionManager.UdpSocketArray cachedUdpArray;
   private static final long UDP_ARRAY_REUSE_WINDOW_MS = 30000L;
   private static volatile ConnectionManager instance;
   private static final long PEER_SIGNAL_FRESH_MS = 60000L;

   private boolean isRelayPeerFailed(String clientId) {
      Long t = this.failedRelayPeers.get(clientId);
      if (t == null) {
         return false;
      } else if (System.currentTimeMillis() - t > 60000L) {
         this.failedRelayPeers.remove(clientId, t);
         return false;
      } else {
         return true;
      }
   }

   private ConnectionFallback trackFallback(ConnectionFallback f) {
      this.activeFallbacks.add(f);
      return f;
   }

   private void cancelAllFallbacks() {
      for (ConnectionFallback f : this.activeFallbacks) {
         try {
            f.cancel();
         } catch (Exception var4) {
         }
      }

      this.activeFallbacks.clear();
   }

   private ConnectionManager.UdpSocketArray getOrCreateUdpArray(int requiredSize, boolean isEasySym, List<String> stunUrls) {
      long now = System.currentTimeMillis();
      if (this.cachedUdpArray != null) {
         if (this.cachedUdpArray.isReusable(requiredSize, isEasySym, now)) {
            VoxLinkMod.LOGGER
               .info(
                  "[BirthdayPunch] Reuse cached socket array: {} sockets, age={}ms", this.cachedUdpArray.punchers.size(), now - this.cachedUdpArray.createTime
               );
            return this.cachedUdpArray;
         }

         this.cachedUdpArray.close();
         this.cachedUdpArray = null;
      }

      List<UdpHolePuncher> punchers = new ArrayList<>();
      List<StunProbe.PublicMappedAddress> addrs = new ArrayList<>();
      List<CompletableFuture<Object[]>> futures = new ArrayList<>();
      int socketStunCount = Math.max(1, Math.min(this.punchProfile().socketStunCount, stunUrls.size()));
      List<String> raceUrls = stunUrls.size() > socketStunCount ? new ArrayList<>(stunUrls.subList(0, socketStunCount)) : stunUrls;
      int createIntervalMs = Math.max(0, this.punchProfile().socketCreateIntervalMs);

      for (int i = 0; i < requiredSize; i++) {
         int idx = i;
         UdpHolePuncher puncher = new UdpHolePuncher();
         this.applyPunchTemplate(puncher);

         try {
            puncher.createSocket();
         } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("[BirthdayPunch] Create socket #{} failed: {}", idx, e.getMessage());

            try {
               puncher.close();
            } catch (Exception var27) {
            }
            continue;
         }

         futures.add(CompletableFuture.supplyAsync(() -> {
            try {
               StunProbe.PublicMappedAddress[] race = StunProbe.discoverMappedAddressRace(puncher.getSocket(), raceUrls, 1);
               StunProbe.PublicMappedAddress addrx = race != null && race.length > 0 ? race[0] : null;
               if (addrx != null) {
                  return new Object[]{puncher, addrx};
               }

               try {
                  puncher.close();
               } catch (Exception var6x) {
               }

               return null;
            } catch (Exception e) {
               try {
                  puncher.close();
               } catch (Exception var5) {
               }

               return null;
            }
         }));
         if (createIntervalMs > 0) {
            try {
               Thread.sleep(createIntervalMs);
            } catch (InterruptedException ignored) {
               Thread.currentThread().interrupt();
               break;
            }
         }
      }

      int minRequired = Math.min(this.punchProfile().birthdaySocketCount, requiredSize);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger doneCount = new AtomicInteger(0);
      CompletableFuture<Void> gate = new CompletableFuture<>();

      for (CompletableFuture<Object[]> f : futures) {
         f.whenComplete((result, ex) -> {
            doneCount.incrementAndGet();
            if (result != null) {
               successCount.incrementAndGet();
            }

            if (successCount.get() >= minRequired || doneCount.get() == futures.size()) {
               gate.complete(null);
            }
         });
      }

      long gateTimeout = Math.max(5000L, (long)requiredSize * createIntervalMs + 3000L);

      try {
         gate.get(gateTimeout, TimeUnit.MILLISECONDS);
      } catch (TimeoutException var24) {
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
      } catch (ExecutionException var26) {
      }

      for (int i = 0; i < futures.size(); i++) {
         try {
            Object[] result = futures.get(i).getNow(null);
            if (result != null) {
               UdpHolePuncher puncher = (UdpHolePuncher)result[0];
               StunProbe.PublicMappedAddress addr = (StunProbe.PublicMappedAddress)result[1];
               if (puncher.getSocket() != null && !puncher.getSocket().isClosed() && addr != null) {
                  punchers.add(puncher);
                  addrs.add(addr);
               } else if (puncher.getSocket() != null && !puncher.getSocket().isClosed()) {
                  try {
                     puncher.close();
                  } catch (Exception var23) {
                  }
               }
            }
         } catch (Exception var28) {
         }
      }

      for (CompletableFuture<Object[]> f : futures) {
         if (!f.isDone()) {
            f.cancel(true);
         }
      }

      if (punchers.isEmpty()) {
         return null;
      }

      ConnectionManager.UdpSocketArray array = new ConnectionManager.UdpSocketArray(punchers, addrs, isEasySym);
      this.cachedUdpArray = array;
      VoxLinkMod.LOGGER.info("[BirthdayPunch] Create new socket array: {} sockets, easySym={}", punchers.size(), isEasySym);
      return array;
   }

   static int calculatePortDelta(List<Integer> samples) {
      if (samples != null && samples.size() >= 2) {
         List<Integer> deltas = new ArrayList<>();

         for (int i = 1; i < samples.size(); i++) {
            deltas.add(samples.get(i) - samples.get(i - 1));
         }

         Collections.sort(deltas);
         int trim = deltas.size() / 4;
         List<Integer> trimmed = deltas.subList(trim, deltas.size() - trim);
         double ema = trimmed.get(0).intValue();
         double alpha = 0.4;

         for (int i = 1; i < trimmed.size(); i++) {
            ema += alpha * (trimmed.get(i).intValue() - ema);
         }

         int result = (int)Math.round(ema);
         return result > 0 ? result : 1;
      } else {
         return 1;
      }
   }

   public static ConnectionManager getInstance() {
      return instance;
   }

   public ConnectionManager(RoomManager roomManager, SignalingClient signalingClient, ScheduledExecutorService scheduler) {
      this.roomManager = roomManager;
      this.signalingClient = signalingClient;
      this.scheduler = scheduler;
      instance = this;
      this.punchExecutor = Executors.newFixedThreadPool(8, r -> {
         Thread t = new Thread(r, "VoxLink-HostPunch");
         t.setDaemon(true);
         return t;
      });
   }

   private PunchProfile punchProfile() {
      PunchProfile p = this.activePunchProfile;
      return p != null ? p : PunchProfile.DEFAULT;
   }

   public PunchProfile getActivePunchProfile() {
      return this.punchProfile();
   }

   private void applyPunchTemplate(UdpHolePuncher puncher) {
      if (puncher != null) {
         puncher.setProfile(this.punchProfile());
         puncher.setPunchParams(this.activePunchParams);
      }
   }

   private volatile long lastProfileSwitchMs = 0L;

   private void switchPunchProfile(PunchProfile target, String reason) {
      if (target != null && target != this.activePunchProfile) {
         long now = System.currentTimeMillis();
         if (this.lastProfileSwitchMs != 0L && now - this.lastProfileSwitchMs < 20000L) {
            return;
         }

         this.lastProfileSwitchMs = now;
         PunchProfile old = this.activePunchProfile;
         this.activePunchProfile = target;
         VoxLinkMod.LOGGER.info("[PunchProfile] Instance switch: {} -> {} reason: {}", old, target.name, reason);
      }
   }

   private int punchTimeoutMs() {
      PunchParams p = this.activePunchParams;
      return p != null && p.timeoutMs > 0 ? p.timeoutMs : this.punchProfile().punchTimeoutMs;
   }

   private boolean punchSkipDirect() {
      PunchParams p = this.activePunchParams;
      return p != null && p.skipDirectPunch;
   }

   public boolean isConnectionCycleActive() {
      return this.connectionCycleActive.get();
   }
   public boolean isConnectionActive() {
      return this.connectionCycleActive.get() || this.hostPunching || this.connectionWon.get() || this.continuousRetryRound.get() > 0;
   }

   public StunProbe.ProbeResult getStunProbeResult() {
      return this.stunProbeResult;
   }

   public NatClass getLocalNatClass() {
      return this.localNatClass;
   }

   public NatClass getRemoteNatClass() {
      return this.remoteNatClass;
   }

   public String getConnectionDifficultyKey() {
      NatClass l = this.localNatClass;
      NatClass r = this.remoteNatClass;
      // 有一方未知: 按已知侧最坏推断, 难度后由UI加(存疑)
      if (l == NatClass.UNKNOWN || r == NatClass.UNKNOWN) {
         NatClass known = l == NatClass.UNKNOWN ? r : l;
         if (known == NatClass.CONE) {
            return "voxlink.nat.diff_medium";
         } else if (known.isSymmetric()) {
            return "voxlink.nat.diff_hell";
         } else {
            return "voxlink.nat.diff_medium";
         }
      }

      if (l == NatClass.CONE && r == NatClass.CONE) {
         return "voxlink.nat.diff_easy";
      } else if (l == NatClass.HARD_SYM && r == NatClass.HARD_SYM) {
         return "voxlink.nat.diff_hell";
      } else if (l == NatClass.HARD_SYM || r == NatClass.HARD_SYM) {
         return "voxlink.nat.diff_hard";
      } else if (l.isSymmetric() && r.isSymmetric()) {
         return "voxlink.nat.diff_hard";
      } else if (l.isSymmetric() || r.isSymmetric()) {
         return "voxlink.nat.diff_medium";
      } else {
         return "voxlink.nat.diff_medium";
      }
   }

   public void setStunProbeResult(StunProbe.ProbeResult result) {
      this.stunProbeResult = result;
   }

   public AtomicReference<CompletableFuture<StunProbe.ProbeResult>> getStunProbeFutureRef() {
      return this.stunProbeFutureRef;
   }

   public void setConnectionCycleActive(boolean value) {
      this.connectionCycleActive.set(value);
   }

   private void scheduleConnectionCycleSafety(RoomManager.RoomState state) {
      if (this.connectionCycleSafetyTimeout != null) {
         this.connectionCycleSafetyTimeout.cancel(false);
      }

      this.connectionCycleSafetyTimeout = this.scheduler.schedule(() -> {
         if (this.connectionCycleActive.get() && !this.connectionWon.get() && this.roomManager.currentRoom.get() == state) {
            if (this.isPersistentRetrying()) {
               VoxLinkMod.LOGGER.info("[Connection] Safety timeout skipped: persistent retrying round={}", this.continuousRetryRound.get());
               return;
            }

            VoxLinkMod.LOGGER.warn("[Connection] Safety timeout (120s), connection cycle stuck, auto-reset");
            this.connectionCycleActive.set(false);
            this.showConnectFailed(state, "voxlink.connection.cycle_safety_timeout");
         }
      }, 120L, TimeUnit.SECONDS);
   }

   private void cancelConnectionCycleSafety() {
      if (this.connectionCycleSafetyTimeout != null) {
         this.connectionCycleSafetyTimeout.cancel(false);
         this.connectionCycleSafetyTimeout = null;
      }
   }

   public void setReversePunchAttempted(boolean value) {
      this.reversePunchAttempted.set(value);
   }

   public void clearActiveHolePunchers() {
      for (String key : new ArrayList<>(this.activeHolePunchers.keySet())) {
         UdpHolePuncher p = this.activeHolePunchers.remove(key);
         if (p != null) {
            try {
               p.close();
            } catch (Exception e) {
               VoxLinkMod.LOGGER.debug("cleanup puncher close error: {}", e.getMessage());
            }
         }
      }

      this.activeHolePunchers.clear();
   }

   public void clearHostPunchingState() {
      this.hostPunching = false;
      UdpHolePuncher hp = this.activeHolePunchers.remove("host");
      if (hp != null) {
         try {
            hp.close();
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("host puncher close error: {}", e.getMessage());
         }
      }
   }

   public void stopAllPunchingAfterHostBridge() {
      this.continuousRetryCancelled.set(true);
      this.connectionCycleActive.set(false);
      this.connectionWon.set(true);
      this.cancelAllFallbacks();
      if (this.connectionTimeoutFuture != null) {
         this.connectionTimeoutFuture.cancel(false);
         this.connectionTimeoutFuture = null;
      }

      for (UdpHolePuncher puncher : this.activeHolePunchers.values()) {
         try {
            puncher.cancel();
         } catch (Exception var6) {
         }

         try {
            puncher.stopPunch();
         } catch (Exception var5) {
         }

         try {
            puncher.close();
         } catch (Exception var4) {
         }
      }

      this.activeHolePunchers.clear();
      this.hostPunching = false;
      this.lastPunchInfoId = "";
      if (this.dualRaceActive && !this.terracottaWon) {
         this.killAllConnectionAttempts("terracotta");
      }
   }

   private void stopAllPunchingForRelay() {
      this.connectionCycleActive.set(false);
      if (this.connectionTimeoutFuture != null) {
         this.connectionTimeoutFuture.cancel(false);
         this.connectionTimeoutFuture = null;
      }

      for (UdpHolePuncher puncher : this.activeHolePunchers.values()) {
         try {
            puncher.cancel();
         } catch (Exception var6) {
         }

         try {
            puncher.stopPunch();
         } catch (Exception var5) {
         }

         try {
            puncher.close();
         } catch (Exception var4) {
         }
      }

      this.activeHolePunchers.clear();
      this.hostPunching = false;
      this.lastPunchInfoId = "";
   }

   public boolean canShowRelayButton() {
      if (!VoxLinkMod.getConfig().isRelayEnabled()) {
         return false;
      }

      if (this.manualRelayInProgress) {
         return false;
      }

      if (this.isLegacyPeer()) {
         return false;
      }

      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state != null && state != RoomManager.PENDING) {
         int round = this.continuousRetryRound.get();
         if (round < 2) {
            return false;
         }

         if (this.nextRelayEligibleRound == 0) {
            boolean isSymmetric = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric();
            this.nextRelayEligibleRound = isSymmetric ? 2 : 3;
         }

         return round >= this.nextRelayEligibleRound;
      } else {
         return false;
      }
   }

   public void triggerManualRelay() {
      if (VoxLinkMod.getConfig().isRelayEnabled()) {
         RoomManager.RoomState state = this.roomManager.currentRoom.get();
         if (state != null && state != RoomManager.PENDING) {
            if (!this.manualRelayInProgress) {
               this.manualRelayInProgress = true;
               int currentRound = this.continuousRetryRound.get();
               boolean isSymmetric = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric();
               this.nextRelayEligibleRound = currentRound + (isSymmetric ? 2 : 4);
               VoxLinkMod.LOGGER.info("[Relay] Manual relay triggered at round={}, next eligible round={}", currentRound, this.nextRelayEligibleRound);
               this.stopAllPunchingForRelay();
               state.roomInfo.setConnectionMode(Component.translatable("voxlink.relay.trying"));
               this.tryRelay(state);
            }
         }
      }
   }

   public boolean isManualRelayInProgress() {
      return this.manualRelayInProgress;
   }

   public int getContinuousRetryRound() {
      return this.continuousRetryRound.get();
   }

   public boolean isPersistentRetrying() {
      return this.continuousRetryRound.get() > 0 && !this.continuousRetryCancelled.get();
   }

   public boolean isDualRaceActive() {
      return this.dualRaceActive;
   }

   private void notifyRelayFailed() {
      if (this.manualRelayInProgress) {
         this.manualRelayInProgress = false;
         VoxLinkMod.LOGGER.info("[Relay] Manual relay failed, resume hole punching");
      }
   }

   public void stopRelay() {
      VoxLinkMod.LOGGER.info("[Relay] stopRelay: cleanup relay state");
      this.manualRelayInProgress = false;
      this.clearRelayTracking();
      RelayBridge.getInstance(this.scheduler).stopAllRelays();
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state != null && state != RoomManager.PENDING && state.roomInfo.isUsingRelay()) {
         state.roomInfo.setUsingRelay(false);
         ReliableUdpTransport t = this.activeUdpTransports.remove("relay_cone");
         if (t != null) {
            try {
               t.close();
            } catch (Exception var4) {
            }
         }

         P2PBridge.disconnect();
      }
   }

   public void clearActiveUdpTransports() {
      for (ReliableUdpTransport t : this.activeUdpTransports.values()) {
         try {
            t.close();
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("cleanup udp transport close error: {}", e.getMessage());
         }
      }

      this.activeUdpTransports.clear();
   }

   public void stopAllConnectionWork() {
      this.hostPunching = false;
      this.lastPunchInfoId = "";
      this.cancelAllFallbacks();

      for (UdpHolePuncher puncher : this.activeHolePunchers.values()) {
         try {
            puncher.cancel();
            puncher.close();
         } catch (Exception var5) {
         }
      }

      this.activeHolePunchers.clear();

      for (ReliableUdpTransport transport : this.activeUdpTransports.values()) {
         try {
            transport.close();
         } catch (Exception var4) {
         }
      }

      this.activeUdpTransports.clear();
   }

   public UdpHolePuncher removeHolePuncher(String key) {
      return this.activeHolePunchers.remove(key);
   }

   public ReliableUdpTransport removeUdpTransport(String key) {
      return this.activeUdpTransports.remove(key);
   }

   public boolean hasUdpTransport(String key) {
      return key != null && this.activeUdpTransports.containsKey(key);
   }

   public void handleJoinRequest(String from, JsonObject data) {
      this.handleJoinRequest(from, data, 0);
   }

   public void handleJoinRequest(String from, JsonObject data, int retryCount) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      VoxLinkMod.LOGGER
         .info("[RoomManager] Received join_request from {}, state={}", from, state != null && state != RoomManager.PENDING ? "active" : "null/pending");
      if (state != null && state != RoomManager.PENDING && state.roomInfo.isHost()) {
         if (data.has("clientProtocolVersion") && !data.get("clientProtocolVersion").isJsonNull()) {
            int joinerProto = data.get("clientProtocolVersion").getAsInt();
            Set<String> joinerCaps = Collections.emptySet();
            if (data.has("clientCapabilities") && data.get("clientCapabilities").isJsonArray()) {
               joinerCaps = new HashSet<>();

               for (JsonElement c : data.getAsJsonArray("clientCapabilities")) {
                  if (!c.isJsonNull()) {
                     joinerCaps.add(c.getAsString());
                  }
               }
            }

            state.roomInfo.addOrUpdatePeer(from, null, null, 0, joinerProto, joinerCaps);
            VoxLinkMod.LOGGER.info("[handleJoinRequest] joiner={} capability: v{} caps={}", new Object[]{from, joinerProto, joinerCaps});
         }

         if (!this.hostPunching && !this.activeHolePunchers.containsKey("host")) {
            String hostIp = state.roomInfo.getHostIp();
            String hostIpv6 = state.roomInfo.getHostIpv6();
            boolean needIp = hostIp == null || hostIp.isEmpty();
            boolean needIpv6 = hostIpv6 == null || hostIpv6.isEmpty();
            if (!needIp && !needIpv6) {
               this.sendHolepunchOffer(state, from);
            } else {
               this.signalingClient
                  .getPublicIp()
                  .thenAccept(
                     ipResponse -> {
                        if (ipResponse.success && ipResponse.data != null) {
                           RoomManager.RoomState st = this.roomManager.currentRoom.get();
                           if (st != null && st != RoomManager.PENDING && st.roomInfo.isHost()) {
                              if (ipResponse.data.has("ip")
                                 && !ipResponse.data.get("ip").isJsonNull()
                                 && (st.roomInfo.getHostIp() == null || st.roomInfo.getHostIp().isEmpty())) {
                                 st.roomInfo.setHostIp(ipResponse.data.get("ip").getAsString());
                              }

                              if (ipResponse.data.has("ipv6")
                                 && !ipResponse.data.get("ipv6").isJsonNull()
                                 && (st.roomInfo.getHostIpv6() == null || st.roomInfo.getHostIpv6().isEmpty())) {
                                 st.roomInfo.setHostIpv6(ipResponse.data.get("ipv6").getAsString());
                              }

                              if ((st.roomInfo.getHostIpv6() == null || st.roomInfo.getHostIpv6().isEmpty()) && StunDetector.verifyIPv6Connectivity()) {
                                 String localIpv6 = ConnectionFallback.getLocalGlobalIpv6();
                                 if (localIpv6 != null) {
                                    st.roomInfo.setHostIpv6(localIpv6);
                                    VoxLinkMod.LOGGER.info("[handleJoinRequest] API returned no IPv6, using local IPv6: {}", localIpv6);
                                 }
                              }

                              this.sendHolepunchOffer(st, from);
                           } else {
                              VoxLinkMod.LOGGER.warn("[RoomManager] Room state changed during IP query, use original state for offer");
                              this.sendHolepunchOffer(state, from);
                           }
                        } else {
                           this.sendHolepunchOffer(state, from);
                        }
                     }
                  )
                  .exceptionally(e -> {
                     VoxLinkMod.LOGGER.warn("[RoomManager] Get public IP failed in handleJoinRequest: {}", e.getMessage());
                     this.sendHolepunchOffer(state, from);
                     return null;
                  });
            }
         } else if (retryCount >= 3) {
            VoxLinkMod.LOGGER.warn("[RoomManager] join_request retry exhausted, discard {}", from);
         } else if (this.activeUdpTransports.containsKey(from)) {
            VoxLinkMod.LOGGER.info("[RoomManager] Client {} has active transport, ignore duplicate join_request", from);
         } else {
            VoxLinkMod.LOGGER.info("[RoomManager] Punching in progress, queue join_request from {} (retry {}/3)", from, retryCount + 1);
            int nextRetry = retryCount + 1;
            this.scheduler.schedule(() -> {
               RoomManager.RoomState st = this.roomManager.currentRoom.get();
               if (st != null && st != RoomManager.PENDING && st.roomInfo.isHost()) {
                  if (this.activeUdpTransports.containsKey(from)) {
                     VoxLinkMod.LOGGER.info("[RoomManager] Client {} connected, skip queued retry", from);
                  } else {
                     VoxLinkMod.LOGGER.info("[RoomManager] Retry queued join_request from {}", from);
                     this.handleJoinRequest(from, data, nextRetry);
                  }
               }
            }, 10L, TimeUnit.SECONDS);
         }
      }
   }

   public void sendHolepunchOffer(RoomManager.RoomState state, String from) {
      this.connectionWon.set(false);
      JsonObject offerData = new JsonObject();
      if (state.roomInfo.getHostIp() != null && !state.roomInfo.getHostIp().isEmpty()) {
         offerData.addProperty("hostIp", state.roomInfo.getHostIp());
      }

      if (state.roomInfo.getHostIpv6() != null && !state.roomInfo.getHostIpv6().isEmpty()) {
         offerData.addProperty("hostIpv6", state.roomInfo.getHostIpv6());
      }

      int bridgePort = P2PBridge.getHostPort();
      int connectPort = bridgePort > 0 ? bridgePort : state.roomInfo.getHostPort();
      offerData.addProperty("hostPort", connectPort);
      String localIp = StunDetector.getLocalIpAddress();
      if (localIp != null && !localIp.isEmpty()) {
         offerData.addProperty("hostLocalIp", localIp);
         state.roomInfo.setHostLocalIp(localIp);
         VoxLinkMod.LOGGER.info("[RoomManager] Include host LAN IP: {}", localIp);
      }

      UdpHolePuncher hostPuncher = this.activeHolePunchers.get("host");
      if (hostPuncher != null && hostPuncher.getSocket() != null && !hostPuncher.getSocket().isClosed()) {
         hostPuncher.stopPunch();
         VoxLinkMod.LOGGER.info("[RoomManager] Reuse existing host punch socket (localPort={})", hostPuncher.getSocket().getLocalPort());
      } else {
         hostPuncher = new UdpHolePuncher();
         this.applyPunchTemplate(hostPuncher);

         try {
            int mcPort = state.roomInfo.getHostPort();
            hostPuncher.createSocket(mcPort);
            this.activeHolePunchers.put("host", hostPuncher);
         } catch (Exception e) {
            try {
               hostPuncher.createSocket();
               this.activeHolePunchers.put("host", hostPuncher);
            } catch (Exception e2) {
               VoxLinkMod.LOGGER.warn("[RoomManager] Create host punch socket failed: {}", e2.getMessage());
               hostPuncher = null;
            }
         }
      }

      String natType = state.roomInfo.getNatType();
      boolean isSymmetricOrUnknown = StunDetector.isNatTypeSymmetric(natType);
      if (this.stunProbeResult != null && state.roomInfo.getClientId() != null) {
         String hostMappedIp = null;
         int hostMappedPort = 0;

         for (StunProbe.StunServerResult sr : this.stunProbeResult.serverResults) {
            if (sr.reachable && sr.mappedIp != null && sr.mappedPort > 0) {
               hostMappedIp = sr.mappedIp;
               hostMappedPort = sr.mappedPort;
               break;
            }
         }

         if (hostMappedIp != null && hostMappedPort > 0) {
            boolean relayOk = VoxLinkMod.getConfig().isRelayEnabled();
            this.signalingClient
               .registerRelayPeer(
                  state.roomInfo.getClientId(), state.roomInfo.getCode(), this.stunProbeResult.natType.key, hostMappedIp, hostMappedPort, relayOk
               );
            this.scheduleRelayRegistrationRenewal(state, this.stunProbeResult.natType.key, hostMappedIp, hostMappedPort);
         }
      }

      UdpHolePuncher fHostPuncher = hostPuncher;
      boolean fIsSymmetricOrUnknown = isSymmetricOrUnknown;
      String fNatType = natType;
      JsonObject fOfferData = offerData;
      RoomManager.RoomState fState = state;
      String fFrom = from;
      int fConnectPort = connectPort;
      CompletableFuture.<Object[]>supplyAsync(() -> {
            StunProbe.PublicMappedAddress m1 = null;
            StunProbe.PublicMappedAddress m2 = null;
            List<StunProbe.PublicMappedAddress> birthdayAddrs = null;
            if (fHostPuncher != null) {
               try {
                  List<String> allStun = StunDetector.getAllStunUrls();
                  VoxLinkMod.LOGGER.info("[RoomManager] Host NAT: {} — 8 concurrent STUN ({} servers)", fNatType != null ? fNatType : "null", allStun.size());
                  StunProbe.PublicMappedAddress[] top2 = StunProbe.discoverMappedAddressRace(fHostPuncher.getSocket(), allStun, 2);
                  m1 = top2[0];
                  m2 = top2[1];
               } catch (Exception e) {
                  VoxLinkMod.LOGGER.warn("[RoomManager] Punch socket STUN failed: {}", e.getMessage());
               }

               if (fIsSymmetricOrUnknown && m1 != null && m2 != null) {
                  int birthdayCount = PunchProfile.HARDSYM.hardSymSocketCount;
                  VoxLinkMod.LOGGER.info("[RoomManager] Symmetric NAT, pre-create {} birthday sockets into holepunch_offer", birthdayCount);
                  birthdayAddrs = new ArrayList<>();
                  List<CompletableFuture<StunProbe.PublicMappedAddress>> bFutures = new ArrayList<>();

                  for (int i = 0; i < birthdayCount; i++) {
                     int idx = i;
                     bFutures.add(CompletableFuture.supplyAsync(() -> {
                        UdpHolePuncher bp = new UdpHolePuncher();
                        this.applyPunchTemplate(bp);

                        try {
                           bp.createSocket();
                        } catch (Exception e) {
                           return null;
                        }

                        StunProbe.PublicMappedAddress[] race = StunProbe.discoverMappedAddressRace(bp.getSocket(), StunDetector.getAllStunUrls(), 1);
                        StunProbe.PublicMappedAddress addr = race[0];
                        if (addr != null) {
                           String key = "host_birthday_" + idx;
                           this.activeHolePunchers.put(key, bp);
                           return addr;
                        }

                        try {
                           bp.close();
                        } catch (Exception var6x) {
                        }

                        return null;
                     }));
                  }

                  try {
                     CompletableFuture.allOf(bFutures.toArray(new CompletableFuture[0])).get(5L, TimeUnit.SECONDS);
                  } catch (Exception e) {
                     VoxLinkMod.LOGGER.warn("[RoomManager] Birthday socket create partial timeout: {}", e.getMessage());
                  }

                  for (CompletableFuture<StunProbe.PublicMappedAddress> f : bFutures) {
                     try {
                        StunProbe.PublicMappedAddress a = f.getNow(null);
                        if (a != null) {
                           birthdayAddrs.add(a);
                        }
                     } catch (Exception var12) {
                     }
                  }

                  VoxLinkMod.LOGGER.info("[RoomManager] Pre-create {} birthday sockets done, {} valid", birthdayCount, birthdayAddrs.size());
               }
            }

            return new Object[]{m1, m2, birthdayAddrs};
         })
         .thenAccept(
            result -> {
               StunProbe.PublicMappedAddress mapped1 = (StunProbe.PublicMappedAddress)result[0];
               StunProbe.PublicMappedAddress mapped2 = (StunProbe.PublicMappedAddress)result[1];
               List<StunProbe.PublicMappedAddress> birthdayPorts = (List<StunProbe.PublicMappedAddress>)result[2];
               StunProbe.PublicMappedAddress mapped = null;
               boolean punchSocketSymmetric = false;
               boolean symOrUnknown = fIsSymmetricOrUnknown;
               if (mapped1 != null && mapped2 != null) {
                  if (mapped1.port() != mapped2.port()) {
                     punchSocketSymmetric = true;
                     VoxLinkMod.LOGGER.info("[RoomManager] Punch socket STUN: symmetric NAT ({} vs {})", mapped1.port(), mapped2.port());
                  } else if (symOrUnknown && !StunDetector.isNatTypeSymmetric(fNatType)) {
                     VoxLinkMod.LOGGER.info("[RoomManager] Punch socket STUN: same port {}, override isSymmetricOrUnknown (was {})", mapped1.port(), fNatType);
                     symOrUnknown = false;
                  }

                  mapped = mapped2;
               } else {
                  mapped = mapped1 != null ? mapped1 : mapped2;
               }

               if (punchSocketSymmetric) {
                  symOrUnknown = true;
               }

               if (mapped != null) {
                  fOfferData.addProperty("hostMappedIp", mapped.ip());
                  fOfferData.addProperty("hostMappedPort", mapped.port());
               } else if (this.stunProbeResult != null && !this.stunProbeResult.serverResults.isEmpty()) {
                  for (StunProbe.StunServerResult sr : this.stunProbeResult.serverResults) {
                     if (sr.reachable && sr.mappedIp != null && sr.mappedPort > 0) {
                        fOfferData.addProperty("hostMappedIp", sr.mappedIp);
                        fOfferData.addProperty("hostMappedPort", sr.mappedPort);
                        mapped = new StunProbe.PublicMappedAddress(sr.mappedIp, sr.mappedPort);
                        VoxLinkMod.LOGGER.info("[RoomManager] MC port STUN failed, fallback to NAT probe mapped address: {}:{}", sr.mappedIp, sr.mappedPort);
                        break;
                     }
                  }
               }

               if (mapped != null) {
                  if (symOrUnknown) {
                     fOfferData.addProperty("hostSymmetric", true);
                  }

                  boolean hostEasySym = punchSocketSymmetric
                     && mapped1 != null
                     && mapped2 != null
                     && Math.abs(mapped2.port() - mapped1.port()) <= 100;
                  if (hostEasySym) {
                     fOfferData.addProperty("hostEasySym", true);
                  }

                  if (mapped1 != null && mapped2 != null && mapped1.port() != mapped2.port()) {
                     int delta = mapped2.port() - mapped1.port();
                     int portRange = this.punchProfile().maxPortRange;
                     if (fHostPuncher != null && fHostPuncher.getSocket() != null) {
                        List<Integer> samples = StunProbe.samplePortsSequential(fHostPuncher.getSocket(), StunDetector.getAllStunUrls(), 10, 100);
                        if (samples.size() >= 5) {
                           PortPredictor.PredictResult pr = PortPredictor.predict(samples);
                           int reliableDelta = PortPredictor.deltaPredict(samples) - samples.get(samples.size() - 1);
                           if (reliableDelta <= 0) {
                              reliableDelta = calculatePortDelta(samples);
                           }

                           portRange = pr.range;
                           VoxLinkMod.LOGGER
                              .info(
                                 "[RoomManager] P-PRE samples: {} times -> sequence={}, combined predict port={}, range=±{}, delta={}",
                                 new Object[]{samples.size(), samples, pr.predictedPort, pr.range, reliableDelta}
                              );
                           delta = reliableDelta;
                        } else {
                           VoxLinkMod.LOGGER.warn("[RoomManager] P-PRE insufficient samples ({}), fallback to 2-sample delta={}", samples.size(), delta);
                        }
                     }

                     fOfferData.addProperty("hostMappedPortDelta", delta);
                     fOfferData.addProperty("hostMappedPortRange", portRange);
                     VoxLinkMod.LOGGER.info("[RoomManager] Symmetric NAT port delta: delta={}, range=±{}", delta, portRange);
                  }

                  fState.roomInfo.setHostMappedAddress(mapped.ip(), mapped.port());
                  fState.roomInfo.setHostEasySym(hostEasySym);
                  VoxLinkMod.LOGGER
                     .info(
                        "[RoomManager] Punch socket STUN: ip={}, port={}, symmetric={}, easySym={}",
                        new Object[]{mapped.ip(), mapped.port(), symOrUnknown, hostEasySym}
                     );
               }

               if (birthdayPorts != null && !birthdayPorts.isEmpty()) {
                  JsonArray portsArr = new JsonArray();

                  for (StunProbe.PublicMappedAddress bp : birthdayPorts) {
                     portsArr.add(bp.port());
                  }

                  fOfferData.add("hostBirthdayPorts", portsArr);
                  VoxLinkMod.LOGGER.info("[RoomManager] holepunch_offer with {} birthday ports", birthdayPorts.size());
               }

               long syncTime = System.currentTimeMillis() + 3000L;
               fOfferData.addProperty("punchSyncTimeMs", syncTime);
               fOfferData.addProperty("punchSyncSentAtMs", System.currentTimeMillis());
               fState.roomInfo.setPunchSyncSentAtMs(System.currentTimeMillis());
               fState.roomInfo.setPunchSyncTimeMs(syncTime);
               VoxLinkMod.LOGGER.info("[RoomManager] RTT sync: punchSyncTimeMs={}", syncTime);
               VoxLinkMod.LOGGER
                  .info(
                     "[RoomManager] Send holepunch_offer to {} (hostIp={}, hostIpv6={}, port={}, mappedIp={}, mappedPort={})",
                     new Object[]{
                        fFrom,
                        fState.roomInfo.getHostIp() != null ? fState.roomInfo.getHostIp() : "none",
                        fState.roomInfo.getHostIpv6() != null ? fState.roomInfo.getHostIpv6() : "none",
                        fConnectPort,
                        mapped != null ? mapped.ip() : "none",
                        mapped != null ? mapped.port() : 0
                     }
                  );
               this.signalingClient
                  .sendSignal(fState.roomInfo.getCode(), fState.roomInfo.getToken(), true, "holepunch_offer", fOfferData, fFrom)
                  .thenAccept(response -> {
                     if (!response.success) {
                        VoxLinkMod.LOGGER.error("[RoomManager] Send holepunch_offer failed: {} - {}", response.error, response.message);
                     }
                  })
                  .exceptionally(e -> {
                     VoxLinkMod.LOGGER.error("[RoomManager] Send holepunch_offer network error: {}", e.getMessage());
                     return null;
                  });
               String waitClientId = fFrom;
               this.scheduler.schedule(() -> {
                  if (!this.hostPunching && this.activeHolePunchers.containsKey("host")) {
                     UdpHolePuncher hp = this.activeHolePunchers.remove("host");
                     if (hp != null) {
                        try {
                           hp.close();
                        } catch (Exception var4x) {
                        }
                     }

                     this.activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith("host_"));
                     this.lastPunchInfoId = "";
                     VoxLinkMod.LOGGER.info("[RoomManager] Wait punch_info timeout ({}s), cleanup host socket client={}", 15, waitClientId);
                  }
               }, 15L, TimeUnit.SECONDS);
            }
         );
   }

   public void handleHolePunchOffer(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      VoxLinkMod.LOGGER.info("[RoomManager] Received holepunch_offer, state={}", state != null && state != RoomManager.PENDING ? "active" : "null/pending");
      if (state != null && state != RoomManager.PENDING && !state.roomInfo.isHost()) {
         if (this.voxlinkSideDisabled || this.terracottaWon) {
            VoxLinkMod.LOGGER.info("[DualP2P] VoxLink disabled or Terracotta won, ignore holepunch_offer");
         } else if (this.connectionWon.get() && P2PBridge.isRunning()) {
            VoxLinkMod.LOGGER.info("[RoomManager] Already connected, ignore holepunch_offer");
         } else if (ConnectionHelper.isConnecting() && this.connectionCycleActive.get()) {
            VoxLinkMod.LOGGER.info("[RoomManager] Already connecting with active cycle, ignore holepunch_offer");
         } else if (!this.connectionCycleActive.compareAndSet(false, true)) {
            VoxLinkMod.LOGGER.info("[RoomManager] Connection cycle in progress, ignore duplicate holepunch_offer");
         } else {
            this.connectionWon.set(false);
            this.scheduleConnectionCycleSafety(state);
            if (P2PBridge.isRunning()) {
               int existingPort = P2PBridge.getJoinerPort();
               String bridgeHostIp = data.has("hostIp") && !data.get("hostIp").isJsonNull() ? data.get("hostIp").getAsString() : state.roomInfo.getHostIp();
               if (existingPort > 0 && P2PBridge.isTargetMatch(bridgeHostIp, state.roomInfo.getHostPort())) {
                  VoxLinkMod.LOGGER.info("[RoomManager] Bridge running with same target, ignore duplicate holepunch_offer");
                  this.connectionCycleActive.set(false);
                  ConnectionHelper.resetConnecting();
                  return;
               }

               P2PBridge.disconnect();
            }

            String hostIp = null;
            if (data.has("hostIp") && !data.get("hostIp").isJsonNull() && !data.get("hostIp").getAsString().isEmpty()) {
               hostIp = data.get("hostIp").getAsString();
            }

            String hostIpv6 = null;
            int connectPort = state.roomInfo.getHostPort();
            if (data.has("hostPort") && !data.get("hostPort").isJsonNull()) {
               connectPort = data.get("hostPort").getAsInt();
            }

            if (data.has("hostIpv6") && !data.get("hostIpv6").isJsonNull() && !data.get("hostIpv6").getAsString().isEmpty()) {
               hostIpv6 = data.get("hostIpv6").getAsString();
            }

            String hostMappedIp = null;
            int hostMappedPort = 0;
            if (data.has("hostMappedIp") && !data.get("hostMappedIp").isJsonNull()) {
               hostMappedIp = data.get("hostMappedIp").getAsString();
            }

            if (data.has("hostMappedPort") && !data.get("hostMappedPort").isJsonNull()) {
               hostMappedPort = data.get("hostMappedPort").getAsInt();
            }

            if (hostMappedIp != null && !hostMappedIp.isEmpty() && hostMappedPort > 0) {
               state.roomInfo.setHostMappedAddress(hostMappedIp, hostMappedPort);
               VoxLinkMod.LOGGER.info("[RoomManager] Update roomInfo hostMapped={}:{}", hostMappedIp, hostMappedPort);
            }

            String hostLocalIp = null;
            if (data.has("hostLocalIp") && !data.get("hostLocalIp").isJsonNull()) {
               hostLocalIp = data.get("hostLocalIp").getAsString();
               state.roomInfo.setHostLocalIp(hostLocalIp);
               VoxLinkMod.LOGGER.info("[RoomManager] Host provided LAN IP: {}", hostLocalIp);
            }

            String finalHostIp = hostIp;
            String finalHostIpv6 = hostIpv6;
            int finalHostPort = connectPort;
            String finalHostMappedIp = hostMappedIp;
            int finalHostMappedPort = hostMappedPort;
            boolean finalHostSymmetric = data.has("hostSymmetric") && !data.get("hostSymmetric").isJsonNull() && data.get("hostSymmetric").getAsBoolean();
            boolean finalHostEasySym = data.has("hostEasySym") && !data.get("hostEasySym").isJsonNull() && data.get("hostEasySym").getAsBoolean();
            int finalHostMappedPortDelta = data.has("hostMappedPortDelta") && !data.get("hostMappedPortDelta").isJsonNull()
               ? data.get("hostMappedPortDelta").getAsInt()
               : 0;
            int finalHostMappedPortRange = data.has("hostMappedPortRange") && !data.get("hostMappedPortRange").isJsonNull()
               ? data.get("hostMappedPortRange").getAsInt()
               : 100;
            if (finalHostMappedPortRange != 100) {
               state.roomInfo.setHostMappedPortRange(finalHostMappedPortRange);
            }

            long punchSyncTime = data.has("punchSyncTimeMs") ? data.get("punchSyncTimeMs").getAsLong() : 0L;
            if (punchSyncTime > 0L) {
               state.roomInfo.setPunchSyncTimeMs(punchSyncTime);
               VoxLinkMod.LOGGER.info("[RoomManager] RTT sync: punchSyncTimeMs={} ({}ms ago)", punchSyncTime, punchSyncTime - System.currentTimeMillis());
            }

            if (data.has("punchSyncSentAtMs")) {
               state.roomInfo.setJoinerOfferRecvMs(System.currentTimeMillis());
            }

            List<Integer> hostBirthdayPorts = null;
            if (data.has("hostBirthdayPorts") && data.get("hostBirthdayPorts").isJsonArray()) {
               hostBirthdayPorts = new ArrayList<>();

               for (JsonElement elem : data.getAsJsonArray("hostBirthdayPorts")) {
                  hostBirthdayPorts.add(elem.getAsInt());
               }

               VoxLinkMod.LOGGER.info("[RoomManager] holepunch_offer contains {} birthday ports", hostBirthdayPorts.size());
            }

            List<Integer> fHostBirthdayPorts = hostBirthdayPorts;
            if (hostIp != null && !hostIp.isEmpty()) {
               state.roomInfo.setHostIp(hostIp);
            }

            if (hostIpv6 != null && !hostIpv6.isEmpty()) {
               state.roomInfo.setHostIpv6(hostIpv6);
            }

            if (connectPort > 0) {
               state.roomInfo.setHostConnectPort(connectPort);
            }

            if (finalHostEasySym) {
               state.roomInfo.setHostEasySym(true);
            }

            if (fHostBirthdayPorts != null) {
               state.roomInfo.setHostBirthdayPorts(fHostBirthdayPorts);
            }

            CompletableFuture<StunProbe.ProbeResult> probeFuture = this.stunProbeFutureRef.get();
            if (probeFuture != null && this.stunProbeResult == null && !probeFuture.isDone()) {
               RoomManager.RoomState fState = state;
               String fFrom = from;
               String fIpv6 = finalHostIpv6;
               String fIp = finalHostIp;
               int fPort = finalHostPort;
               String fMappedIp = finalHostMappedIp;
               int fMappedPort = finalHostMappedPort;
               boolean fSym = finalHostSymmetric;
               int fDelta = finalHostMappedPortDelta;
               probeFuture.thenAccept(
                  result -> {
                     this.stunProbeResult = result;
                     this.applyProbeResultToActiveConnection(fState, fIp);
                     this.extendConnectionTimeoutIfNeeded(fState);
                  }
               );
            }

            this.finishHandleHolePunchOffer(
               state, from, finalHostIpv6, finalHostIp, finalHostPort, finalHostMappedIp, finalHostMappedPort, finalHostSymmetric, finalHostMappedPortDelta
            );
         }
      }
   }

   private void applyProbeResultToActiveConnection(RoomManager.RoomState state, String hostIp) {
      if (this.stunProbeResult == null) {
         return;
      }

      VoxLinkMod.LOGGER
         .info(
            "[handleHolePunchOffer] Probe done: NAT={}, reachable STUN={}, apply to active punch",
            this.stunProbeResult.natType.key,
            this.stunProbeResult.reachableStunUrls.size()
         );
      String clientPublicIp = null;

      for (StunProbe.StunServerResult r : this.stunProbeResult.serverResults) {
         if (r.reachable && r.mappedIp != null) {
            clientPublicIp = r.mappedIp;
            break;
         }
      }

      if (clientPublicIp != null && hostIp != null && clientPublicIp.equals(hostIp)) {
         VoxLinkMod.LOGGER.warn("[handleHolePunchOffer] Same public IP ({}): both behind same CGNAT, P2P direct unlikely", clientPublicIp);
         state.roomInfo.setSameCgnat(true);
      }

      if (clientPublicIp != null) {
         int clientMappedPort = 0;

         for (StunProbe.StunServerResult r : this.stunProbeResult.serverResults) {
            if (r.reachable && r.mappedPort > 0) {
               clientMappedPort = r.mappedPort;
               break;
            }
         }

         state.roomInfo.setMyMappedIp(clientPublicIp);
         state.roomInfo.setMyMappedPort(clientMappedPort);
      }

      if (this.connectionWon.get() || this.connectionCycleActive.get()) {
         this.localNatClass = this.classifyLocalNat();
         this.remoteNatClass = this.classifyRemoteNat(state);
         PunchProfile recommended = NatClass.recommendProfile(this.localNatClass, this.remoteNatClass, this.scenarioTier);
         this.switchPunchProfile(recommended, "probe_done_" + this.localNatClass + "x" + this.remoteNatClass);
         VoxLinkMod.LOGGER
            .info(
               "[handleHolePunchOffer] Probe upgrade: local={} remote={} -> profile={}",
               new Object[]{this.localNatClass, this.remoteNatClass, this.punchProfile().describeInstance()}
            );
      }
   }

   private void finishHandleHolePunchOffer(
      RoomManager.RoomState state,
      String from,
      String finalHostIpv6,
      String finalHostIp,
      int finalHostPort,
      String finalHostMappedIp,
      int finalHostMappedPort,
      boolean finalHostSymmetric,
      int finalHostMappedPortDelta
   ) {
      if (this.stunProbeResult != null) {
         if (this.stunProbeResult.natType.isSymmetric()) {
            state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.probing"));
         }

         VoxLinkMod.LOGGER
            .info(
               "[handleHolePunchOffer] Use probe result: NAT={}, reachable STUN={}",
               this.stunProbeResult.natType.key,
               this.stunProbeResult.reachableStunUrls.size()
            );
         String clientPublicIp = null;

         for (StunProbe.StunServerResult r : this.stunProbeResult.serverResults) {
            if (r.reachable && r.mappedIp != null) {
               clientPublicIp = r.mappedIp;
               break;
            }
         }

         if (clientPublicIp != null && finalHostIp != null && clientPublicIp.equals(finalHostIp)) {
            VoxLinkMod.LOGGER.warn("[handleHolePunchOffer] Same public IP ({}): both behind same CGNAT, P2P direct unlikely", clientPublicIp);
            state.roomInfo.setSameCgnat(true);
         }

         if (clientPublicIp != null) {
            int clientMappedPort = 0;

            for (StunProbe.StunServerResult r : this.stunProbeResult.serverResults) {
               if (r.reachable && r.mappedPort > 0) {
                  clientMappedPort = r.mappedPort;
                  break;
               }
            }

            state.roomInfo.setMyMappedIp(clientPublicIp);
            state.roomInfo.setMyMappedPort(clientMappedPort);
         }
      }

      if (finalHostSymmetric) {
         state.roomInfo.setHostSymmetric(true);
      }

      if (finalHostMappedPortDelta != 0) {
         state.roomInfo.setHostMappedPortDelta(finalHostMappedPortDelta);
      }

      String effectiveMappedIp = finalHostMappedIp;
      int effectiveMappedPort = finalHostMappedPort;
      if (effectiveMappedIp == null || effectiveMappedPort <= 0) {
         String cachedMappedIp = state.roomInfo.getHostMappedIp();
         int cachedMappedPort = state.roomInfo.getHostMappedPort();
         if (cachedMappedIp != null && cachedMappedPort > 0) {
            effectiveMappedIp = cachedMappedIp;
            effectiveMappedPort = cachedMappedPort;
            VoxLinkMod.LOGGER.info("[handleHolePunchOffer] Use first holepunch_mapped: {}:{}", effectiveMappedIp, effectiveMappedPort);
         }
      }

      if (effectiveMappedIp != null && effectiveMappedPort > 0) {
         this.runConnectionCycle(state, from, finalHostIpv6, finalHostIp, finalHostPort, effectiveMappedIp, effectiveMappedPort, 0);
      } else {
         this.connectionCycleActive.set(false);
         VoxLinkMod.LOGGER.info("[handleHolePunchOffer] offer has no mapped address, wait for holepunch_mapped...");
         this.scheduler.schedule(() -> {
            if (this.connectionCycleActive.compareAndSet(false, true)) {
               String mappedIp = state.roomInfo.getHostMappedIp();
               int mappedPort = state.roomInfo.getHostMappedPort();
               if (mappedIp == null || mappedPort <= 0) {
                  VoxLinkMod.LOGGER.warn("[handleHolePunchOffer] holepunch_mapped timeout (12s), start without mapped address");
                  this.runConnectionCycle(state, from, finalHostIpv6, finalHostIp, finalHostPort, null, 0, 0);
               }
            } else {
               VoxLinkMod.LOGGER.debug("[handleHolePunchOffer] CAS failed, new offer in progress");
            }
         }, 12L, TimeUnit.SECONDS);
      }
   }

   public void handleHolepunchMapped(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state != null && state != RoomManager.PENDING && !state.roomInfo.isHost()) {
         String hostMappedIp = null;
         int hostMappedPort = 0;
         if (data.has("hostMappedIp") && !data.get("hostMappedIp").isJsonNull()) {
            hostMappedIp = data.get("hostMappedIp").getAsString();
         }

         if (data.has("hostMappedPort") && !data.get("hostMappedPort").isJsonNull()) {
            hostMappedPort = data.get("hostMappedPort").getAsInt();
         }

         boolean hostSymmetric = data.has("hostSymmetric") && !data.get("hostSymmetric").isJsonNull() && data.get("hostSymmetric").getAsBoolean();
         boolean hostEasySym = data.has("hostEasySym") && !data.get("hostEasySym").isJsonNull() && data.get("hostEasySym").getAsBoolean();
         int hostMappedPortDelta = data.has("hostMappedPortDelta") && !data.get("hostMappedPortDelta").isJsonNull()
            ? data.get("hostMappedPortDelta").getAsInt()
            : 0;
         int hostMappedPortRange = data.has("hostMappedPortRange") && !data.get("hostMappedPortRange").isJsonNull()
            ? data.get("hostMappedPortRange").getAsInt()
            : 100;
         List<Integer> hostMappedPorts = new ArrayList<>();
         boolean samePorts = false;
         if (data.has("hostMappedPorts") && data.get("hostMappedPorts").isJsonArray()) {
            for (JsonElement elem : data.getAsJsonArray("hostMappedPorts")) {
               hostMappedPorts.add(elem.getAsInt());
            }
         }

         if (hostMappedIp != null && (hostMappedPort > 0 || !hostMappedPorts.isEmpty())) {
            if (hostMappedPorts.isEmpty()) {
               hostMappedPorts.add(hostMappedPort);
            }

            samePorts = this.lastHostMappedPorts != null && this.lastHostMappedPorts.equals(hostMappedPorts);
            if (samePorts) {
               VoxLinkMod.LOGGER.debug("[RoomManager] host mapped ports unchanged, skip rebuild");
            }

            VoxLinkMod.LOGGER
               .info(
                  "[RoomManager] Received host mapped: {}:{} ports={} (sym={}, delta={})",
                  new Object[]{hostMappedIp, hostMappedPort, hostMappedPorts, hostSymmetric, hostMappedPortDelta}
               );
            state.roomInfo.setHostMappedAddress(hostMappedIp, hostMappedPort);
            this.lastHostMappedPorts = new ArrayList<>(hostMappedPorts);
            if (hostSymmetric) {
               state.roomInfo.setHostSymmetric(true);
               NatClass newRemote = hostEasySym ? NatClass.EASY_SYM : NatClass.HARD_SYM;
               this.remoteNatClass = newRemote;
               VoxLinkMod.LOGGER.info("[handleHolepunchMapped] Update remoteNat {} -> {} (sym=true)", NatClass.CONE, newRemote);
               ScenarioTier.Tier prevTier = this.scenarioTier;
               int rReach = this.stunProbeResult != null ? this.stunProbeResult.reachableStunUrls.size() : 0;
               this.scenarioTier = ScenarioTier.classify(
                  this.localNatClass.isSymmetric(), this.remoteNatClass.isSymmetric(),
                  rReach > 0 && rReach <= 3, false, false);
               if (this.scenarioTier != prevTier) {
                  VoxLinkMod.LOGGER.info("[handleHolepunchMapped] tier {} -> {} (host symmetric known)", ScenarioTier.key(prevTier), ScenarioTier.key(this.scenarioTier));
               }
               PunchProfile recommended = NatClass.recommendProfile(this.localNatClass, this.remoteNatClass, this.scenarioTier);
               if (recommended != this.punchProfile()) {
                  this.switchPunchProfile(recommended, "nat_matrix_" + this.localNatClass + "x" + this.remoteNatClass + "_after_mapped");
               }
            }

            if (hostEasySym) {
               state.roomInfo.setHostEasySym(true);
            }

            if (hostMappedPortDelta != 0) {
               state.roomInfo.setHostMappedPortDelta(hostMappedPortDelta);
            }

            if (hostMappedPortRange != 100) {
               state.roomInfo.setHostMappedPortRange(hostMappedPortRange);
            }

            if (hostMappedIp != null && hostMappedPort > 0) {
               String hostNatType = hostSymmetric ? (hostEasySym ? "symmetric_easy_inc" : "symmetric") : "full_cone";
               state.roomInfo.addOrUpdatePeer(from, hostNatType, hostMappedIp, hostMappedPort);
            }

            if (data.has("hostLocalIp") && !data.get("hostLocalIp").isJsonNull()) {
               String receivedHostLocalIp = data.get("hostLocalIp").getAsString();
               if (receivedHostLocalIp != null && !receivedHostLocalIp.isEmpty()) {
                  String existingLocalIp = state.roomInfo.getHostLocalIp();
                  if (existingLocalIp == null || existingLocalIp.isEmpty()) {
                     state.roomInfo.setHostLocalIp(receivedHostLocalIp);
                     VoxLinkMod.LOGGER.info("[handleHolepunchMapped] Received host LAN IP: {}", receivedHostLocalIp);
                     if (this.connectionCycleActive.get() && state.roomInfo.isSameCgnat() && !this.connectionWon.get()) {
                        int connectPort = state.roomInfo.getHostConnectPort() > 0 ? state.roomInfo.getHostConnectPort() : state.roomInfo.getHostPort();
                        int mcPort = state.roomInfo.getHostPort();
                        VoxLinkMod.LOGGER.info("[handleHolepunchMapped] CGNAT: also try hostLocalIp {}:{}", receivedHostLocalIp, connectPort);
                        ConnectionFallback localFallback = this.trackFallback(new ConnectionFallback());
                        localFallback.tryIpv4Direct(receivedHostLocalIp, connectPort).thenAccept(result -> {
                           if (this.roomManager.currentRoom.get() == state && result.success && this.connectionWon.compareAndSet(false, true)) {
                              VoxLinkMod.LOGGER.info("[handleHolepunchMapped] CGNAT hostLocalIp direct connect won");
                              this.connectViaBridge(state, result);
                           }
                        });
                        ConnectionFallback mcLocalFallback = this.trackFallback(new ConnectionFallback());
                        mcLocalFallback.tryIpv4Direct(receivedHostLocalIp, mcPort).thenAccept(result -> {
                           if (this.roomManager.currentRoom.get() == state && result.success && this.connectionWon.compareAndSet(false, true)) {
                              VoxLinkMod.LOGGER.info("[handleHolepunchMapped] CGNAT hostLocalIp MC port won");
                              this.connectViaBridge(state, result);
                           }
                        });
                     }
                  }
               }
            }

            UdpHolePuncher joinerPuncher = this.activeHolePunchers.get("joiner");
            if (joinerPuncher != null && this.connectionCycleActive.get()) {
               int updatePort = hostMappedPorts.get(0);
               VoxLinkMod.LOGGER
                  .info(
                     "[RoomManager] Punch host mapped ports raw={} (delta={} skipped: 同socket双STUN差值非跨socket步长), target={}:{}",
                     new Object[]{hostMappedPorts, hostMappedPortDelta, hostMappedIp, updatePort}
                  );
               joinerPuncher.updateTarget(hostMappedIp, updatePort);
               if (!samePorts && hostMappedPorts.size() > 1) {
                  List<String> staleExtra = new ArrayList<>();

                  for (String k : this.activeHolePunchers.keySet()) {
                     if (k.startsWith("joiner_extra_")) {
                        staleExtra.add(k);
                     }
                  }

                  if (!staleExtra.isEmpty()) {
                     VoxLinkMod.LOGGER.info("[RoomManager] Drift resync: remove {} stale extra punchers, rebuild with latest ports", staleExtra.size());

                     for (String k : staleExtra) {
                        UdpHolePuncher sp = this.activeHolePunchers.remove(k);
                        if (sp != null) {
                           try {
                              sp.cancel();
                              sp.close();
                           } catch (Exception var22) {
                           }
                        }
                     }
                  }
               }

               int maxExtra = Math.min(hostMappedPorts.size(), 6);

               for (int i = 1; i < maxExtra; i++) {
                  int fIdx = i;
                  int extraPort = hostMappedPorts.get(i);
                  String key = "joiner_extra_" + fIdx;
                  if (!this.activeHolePunchers.containsKey(key)) {
                     UdpHolePuncher extraPuncher = new UdpHolePuncher();
                     this.applyPunchTemplate(extraPuncher);

                     try {
                        extraPuncher.createSocket();
                        this.activeHolePunchers.put(key, extraPuncher);
                        int fExtraPort = extraPort;
                        String fHostMappedIp = hostMappedIp;
                        VoxLinkMod.LOGGER.info("[RoomManager] Multi-port puncher#{}: {}:{}", new Object[]{i, fHostMappedIp, fExtraPort});
                        int extraRange = this.punchProfile().joinerMultiPortRange;
                        extraPuncher.punchWithPortPrediction(fHostMappedIp, fExtraPort, extraRange)
                           .thenAccept(
                              result -> {
                                 if (!result.isSuccess()) {
                                    PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                                    this.lastPunchResult = result.withReason(reason);
                                    this.activePunchParams = 
                                       PunchTuner.nextParams(
                                          this.punchProfile(), this.localNatClass, this.remoteNatClass, 1, 8, reason, this.lastPunchResult
                                       )
                                    ;
                                    VoxLinkMod.LOGGER
                                       .info(
                                          "[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}",
                                          new Object[]{reason, result.socketsReceivedPunch, result.socketsReceivedAck}
                                       );
                                 } else {
                                    DatagramSocket socket = result.getSuccessSocket();
                                    if (!this.connectionWon.compareAndSet(false, true)) {
                                       try {
                                          extraPuncher.close();
                                       } catch (Exception var9x) {
                                       }
                                    } else {
                                       extraPuncher.markSocketTransferred();
                                       this.stopAllPunchingAfterHostBridge();
                                       extraPuncher.stopPunch();
                                       DatagramSocket winSocket = socket;
                                       UdpHolePuncher winPuncher = extraPuncher;
                                       this.scheduler
                                          .submit(
                                             () -> {
                                                try {
                                                   this.establishUdpTransport(
                                                      state, winSocket, winPuncher, new InetSocketAddress(fHostMappedIp, fExtraPort), "joiner", false, null
                                                   );
                                                } catch (Exception ex) {
                                                   VoxLinkMod.LOGGER.error("[RoomManager] Multi-port transport failed: {}", ex.getMessage());
                                                   winPuncher.close();
                                                }
                                             }
                                          );
                                    }
                                 }
                              }
                           )
                           .exceptionally(ex -> {
                              VoxLinkMod.LOGGER.debug("[RoomManager] Multi-port puncher#{} punch failed: {}", fIdx, ex.getMessage());
                              this.activeHolePunchers.remove(key);

                              try {
                                 extraPuncher.close();
                              } catch (Exception var6x) {
                              }

                              return null;
                           });
                     } catch (Exception e) {
                        VoxLinkMod.LOGGER.warn("[RoomManager] Create multi-port puncher#{} failed: {}", fIdx, e.getMessage());
                     }
                  }
               }
            } else if (!this.connectionCycleActive.get() && !ConnectionHelper.isConnecting()) {
               VoxLinkMod.LOGGER.info("[RoomManager] Start connection cycle with mapped address");
               if (this.connectionCycleActive.compareAndSet(false, true)) {
                  String hostIp = state.roomInfo.getHostIp();
                  String hostIpv6 = state.roomInfo.getHostIpv6();
                  int hostPort = state.roomInfo.getHostConnectPort() > 0 ? state.roomInfo.getHostConnectPort() : state.roomInfo.getHostPort();
                  int cyclePort = hostMappedPort;
                  if (hostMappedPortDelta != 0) {
                     int predicted = hostMappedPort + hostMappedPortDelta;
                     if (predicted > 0 && predicted <= 65535) {
                        cyclePort = predicted;
                     }
                  }

                  this.runConnectionCycle(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, cyclePort, 0);
               }
            }
         }
      }
   }

   public void handlePunchInfo(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state != null && state != RoomManager.PENDING) {
         if (state.roomInfo.isHost()) {
            this.handleHostPunchInfo(state, from, data);
         } else {
            this.handleJoinerPunchInfo(state, from, data);
         }
      }
   }

   public void handleHostPunchInfo(RoomManager.RoomState state, String from, JsonObject data) {
      this.activePunchParams = null;
      String joinerMappedIp = data.has("joinerMappedIp") ? data.get("joinerMappedIp").getAsString() : null;
      int joinerMappedPort = data.has("joinerMappedPort") ? data.get("joinerMappedPort").getAsInt() : 0;
      int joinerMappedPortDelta = data.has("joinerMappedPortDelta") && !data.get("joinerMappedPortDelta").isJsonNull()
         ? data.get("joinerMappedPortDelta").getAsInt()
         : 0;
      String joinerLocalIp = null;
      if (data.has("joinerLocalIp") && !data.get("joinerLocalIp").isJsonNull()) {
         joinerLocalIp = data.get("joinerLocalIp").getAsString();
         state.roomInfo.setJoinerLocalIp(joinerLocalIp);
         VoxLinkMod.LOGGER.info("[HostPunchInfo] Joiner LAN IP: {}", joinerLocalIp);
      }

      if (joinerMappedIp != null) {
         String myPublicIp = state.roomInfo.getHostMappedIp();
         if (myPublicIp != null && myPublicIp.equals(joinerMappedIp)) {
            state.roomInfo.setSameCgnat(true);
            VoxLinkMod.LOGGER.warn("[HostPunchInfo] Same public IP ({}): both behind same CGNAT", joinerMappedIp);
         }
      }

      boolean requestHostLocalIp = data.has("requestHostLocalIp") && data.get("requestHostLocalIp").getAsBoolean();
      if (joinerMappedPortDelta != 0 && joinerMappedPort > 0) {
         VoxLinkMod.LOGGER
            .info(
               "[HostPunchInfo] Punch joiner raw port={} (delta={} skipped: 同socket双STUN差值非跨socket步长)",
               new Object[]{joinerMappedPort, joinerMappedPortDelta}
            );
      }

      boolean isActive = false;

      for (UdpHolePuncher existing : this.activeHolePunchers.values()) {
         if (existing != null && existing.isPunching()) {
            isActive = true;
            break;
         }
      }

      if (!isActive) {
         long punchAgeMs = System.currentTimeMillis() - this.lastPunchStartMs;
         boolean hostGroupAlive = false;

         for (String key : this.activeHolePunchers.keySet()) {
            if (key.startsWith("host_")) {
               hostGroupAlive = true;
               break;
            }
         }

         if (punchAgeMs >= 0L && punchAgeMs < 120000L && hostGroupAlive && this.lastPunchInfoId != null && !this.lastPunchInfoId.isEmpty()) {
            isActive = true;
         }
      }

      this.hostPunching = isActive;
      VoxLinkMod.LOGGER
         .info(
            "[HostPunchInfo] called: joinerMapped={}:{}, delta={}, hostPunching={}, bridgeRunning={}, hostSym={}",
            new Object[]{
               joinerMappedIp,
               joinerMappedPort,
               joinerMappedPortDelta,
               this.hostPunching,
               P2PBridge.isRunning(),
               state.roomInfo.isHostSymmetric()
            }
         );
      if (joinerMappedIp == null || joinerMappedPort == 0) {
         VoxLinkMod.LOGGER.warn("[RoomManager] Invalid punch_info from {}: no mapped address", from);
      } else if (this.connectionWon.get()) {
         VoxLinkMod.LOGGER.debug("[HostPunchInfo] already connected, ignoring punch_info");
      } else {
         String punchInfoId = joinerMappedIp + ":" + joinerMappedPort;
         if (this.hostPunching) {
            if (punchInfoId.equals(this.lastPunchInfoId)) {
               VoxLinkMod.LOGGER.debug("[RoomManager] Already punching same target, ignore duplicate punch_info");
               return;
            }

            if (System.currentTimeMillis() - this.lastPunchStartMs < 3000L) {
               VoxLinkMod.LOGGER
                  .info(
                     "[RoomManager] Already punching, target changed ({} -> {}) within {}ms, ignore to avoid CGNAT IP switch false restart",
                     new Object[]{this.lastPunchInfoId, punchInfoId, 3000}
                  );
               return;
            }

            VoxLinkMod.LOGGER
               .info(
                  "[RoomManager] Punching {}ms no success, target changed ({} -> {}), restart with new target",
                  new Object[]{System.currentTimeMillis() - this.lastPunchStartMs, this.lastPunchInfoId, punchInfoId}
               );

            String[] lastParts = this.lastPunchInfoId.split(":");
            if (lastParts.length == 2 && lastParts[0].equals(joinerMappedIp)) {
               this.lastPunchInfoId = punchInfoId;

               for (UdpHolePuncher hp : this.activeHolePunchers.values()) {
                  if (hp != null && hp.getSocket() != null && !hp.getSocket().isClosed()) {
                     hp.updateTarget(joinerMappedIp, joinerMappedPort);
                  }
               }

               VoxLinkMod.LOGGER
                  .info(
                     "[RoomManager] joiner mapped port drift ({} -> {}), update target, keep socket group stable",
                     new Object[]{lastParts[1], joinerMappedPort}
                  );
               return;
            }

            this.hostPunching = false;
            this.activeHolePunchers.entrySet().removeIf(e -> {
               if (e.getKey().startsWith("host_")) {
                  UdpHolePuncher p = e.getValue();
                  if (p != null) {
                     try {
                        p.stopPunch();
                     } catch (Exception var5x) {
                     }

                     try {
                        p.close();
                     } catch (Exception var4x) {
                     }
                  }

                  return true;
               } else {
                  return false;
               }
            });
         }

         this.lastPunchInfoId = punchInfoId;
         LogUploadManager.schedulePunchUpload();
         VoxLinkMod.LOGGER.info("[RoomManager] Received punch_info from {}: {}:{}", new Object[]{from, joinerMappedIp, joinerMappedPort});
         boolean isHostSym = StunDetector.isNatTypeSymmetric(state.roomInfo.getNatType());
         boolean isHostHardSym = StunDetector.isHardSymmetric(state.roomInfo.getNatType());
         boolean joinerSym = data.has("joinerSymmetric") && data.get("joinerSymmetric").getAsBoolean();
         boolean joinerEasySym = data.has("joinerEasySym") && !data.get("joinerEasySym").isJsonNull() && data.get("joinerEasySym").getAsBoolean();
         if (joinerMappedIp != null && joinerMappedPort > 0) {
            String peerNatType = joinerSym ? (joinerEasySym ? "symmetric_easy_inc" : "symmetric") : "full_cone";
            state.roomInfo.addOrUpdatePeer(from, peerNatType, joinerMappedIp, joinerMappedPort);
         }

         boolean joinerHardSym = joinerSym && !joinerEasySym;
         if (isHostSym
            && joinerSym
            && (isHostHardSym || joinerHardSym)
            && !"unknown".equals(state.roomInfo.getNatType())
            && state.roomInfo.getNatType() != null) {
            int upnpPort = state.roomInfo.getHostPort() > 0 ? state.roomInfo.getHostPort() : 51600;
            UPnPManager.UPnPResult upnpResult = UPnPManager.openUdpPort(upnpPort, "VoxLink-HardSym");
            if (upnpResult.success()) {
               VoxLinkMod.LOGGER
                  .warn(
                     "[HostPunchInfo] Both symmetric NAT with HardSym (hostHard={}, joinerHard={}), UPnP UDP port {} mapped, continue UDP punch",
                     new Object[]{isHostHardSym, joinerHardSym, upnpPort}
                  );
            } else {
               VoxLinkMod.LOGGER
                  .warn(
                     "[HostPunchInfo] Both symmetric NAT with HardSym(hostHard={}, joinerHard={}), UPnP failed, continue Birthday Attack+port prediction (fallback to Relay on failure)",
                     isHostHardSym,
                     joinerHardSym
                  );
            }
         }

         if (isHostSym && joinerSym) {
            VoxLinkMod.LOGGER.info("[HostPunchInfo] Both EasySym (port predictable), continue UDP punch (EasyTier both_easy_sym)");
         }

         RoomInfo.PortStatus portStatus = state.roomInfo.getIpv4Status();
         boolean portUnreachable = portStatus == RoomInfo.PortStatus.UNREACHABLE || portStatus == RoomInfo.PortStatus.UNKNOWN;
         if (portUnreachable && !isHostSym) {
            VoxLinkMod.LOGGER.info("[HostPunchInfo] Host port status={}, upgrade to 20 socket birthday attack", portStatus);
         }

         int HOST_MULTI_COUNT;
         if (isHostSym && joinerSym) {
            HOST_MULTI_COUNT = PunchProfile.HARDSYM.hardSymSocketCount;
            this.switchPunchProfile(PunchProfile.HARDSYM, "Sym×Sym");
         } else if (isHostSym) {
            // 硬档(对称)用生日攻击全量socket(84)撞端口, 普通档仍走各自模板配置
            boolean hardTier = this.scenarioTier == ScenarioTier.Tier.HARD_DUAL_SYM
               || this.scenarioTier == ScenarioTier.Tier.HARD_ONE_SYM;
            HOST_MULTI_COUNT = hardTier
               ? PunchProfile.HARDSYM.hardSymSocketCount
               : Math.max(PunchProfile.HARDSYM.hostMultiMinSocketCount, this.punchProfile().hostMultiSocketCount);
         } else if (portUnreachable) {
            HOST_MULTI_COUNT = this.punchProfile().hostMultiSocketCount;
         } else {
            HOST_MULTI_COUNT = this.punchProfile().hostMultiBaseSocketCount;
         }

         RoomManager.RoomState fState = state;
         String fFrom = from;
         JsonObject fData = data;
         boolean fRequestHostLocalIp = requestHostLocalIp;
         int fHostMultiCount = HOST_MULTI_COUNT;
         String fJoinerMappedIp = joinerMappedIp;
         int fJoinerMappedPort = joinerMappedPort;
         CompletableFuture.runAsync(
            () -> {
               List<UdpHolePuncher> hostPunchers = new ArrayList<>();
               List<StunProbe.PublicMappedAddress> mappedAddrs = new ArrayList<>();
               boolean hostPunchSocketSymmetric = false;
               int hostPunchSocketDelta = 0;
               UdpHolePuncher oldHost = this.activeHolePunchers.remove("host");
               if (oldHost != null) {
                  try {
                     oldHost.close();
                  } catch (Exception var50) {
                  }
               }

               List<CompletableFuture<StunProbe.PublicMappedAddress[]>> stunFutures = new ArrayList<>();
               int createdCount = 0;

               for (int i = 0; i < fHostMultiCount; i++) {
                  UdpHolePuncher p = new UdpHolePuncher();
                  this.applyPunchTemplate(p);
                  PunchParams hostRoundParams = PunchParams.fromProfile(this.punchProfile());
                  hostRoundParams.timeoutMs = Math.min(hostRoundParams.timeoutMs, this.punchProfile().hostRoundTimeoutMs);
                  p.setPunchParams(hostRoundParams);

                  try {
                     if (i == 0) {
                        p.createSocket(fState.roomInfo.getHostPort());
                     } else {
                        p.createSocket();
                     }
                  } catch (Exception e) {
                     try {
                        p.createSocket();
                     } catch (Exception e2) {
                        continue;
                     }
                  }

                  hostPunchers.add(p);
                  this.activeHolePunchers.put("host_" + i, p);
                  createdCount++;
                  UdpHolePuncher fp = p;
                  int idx = i;
                  stunFutures.add(
                     CompletableFuture.supplyAsync(
                        () -> {
                           StunProbe.PublicMappedAddress[] dual = StunProbe.discoverMappedAddressDual(
                              fp.getSocket(), StunDetector.getAllStunUrls().get(0), StunDetector.getAllStunUrls().get(1)
                           );
                           VoxLinkMod.LOGGER
                              .info(
                                 "[HostPunchInfo] Socket#{} STUN(dual): {} vs {} (localPort={})",
                                 new Object[]{idx, dual[0] != null ? dual[0].port() : -1, dual[1] != null ? dual[1].port() : -1, fp.getSocket().getLocalPort()}
                              );
                           return dual;
                        }
                     )
                  );
                  int createIntervalMs = Math.max(0, this.punchProfile().socketCreateIntervalMs);
                  if (createIntervalMs > 0) {
                     try {
                        Thread.sleep(createIntervalMs);
                     } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                     }
                  }
               }

               VoxLinkMod.LOGGER.info("[HostPunchInfo] Parallel start {}/{} socket+dual STUN tasks (no sleep)", createdCount, fHostMultiCount);
               int minRequired = Math.min(this.punchProfile().birthdaySocketCount, fHostMultiCount);
               long stunDeadline = System.currentTimeMillis()
                  + Math.max(5000L, (long)fHostMultiCount * Math.max(0, this.punchProfile().socketCreateIntervalMs) + 3000L);

               while (System.currentTimeMillis() < stunDeadline) {
                  int done = 0;
                  int success = 0;

                  for (CompletableFuture<StunProbe.PublicMappedAddress[]> f : stunFutures) {
                     if (f.isDone()) {
                        done++;

                        try {
                           StunProbe.PublicMappedAddress[] r = f.getNow(null);
                           if (r != null && (r[0] != null || r[1] != null)) {
                              success++;
                           }
                        } catch (Exception var54) {
                        }
                     }
                  }

                  if (success >= minRequired || done == stunFutures.size()) {
                     break;
                  }

                  try {
                     Thread.sleep(100L);
                  } catch (InterruptedException ie) {
                     Thread.currentThread().interrupt();
                     break;
                  }
               }

               String mappedIp = null;

               for (int i = 0; i < stunFutures.size(); i++) {
                  try {
                     StunProbe.PublicMappedAddress[] addrs = stunFutures.get(i).getNow(null);
                     if (addrs != null && (addrs[0] != null || addrs[1] != null)) {
                        // 双栈时优先通告 IPv4 映射(常规访客为IPv4), 避免把IPv6映射通告给IPv4访客导致打不中
                        StunProbe.PublicMappedAddress chosen = addrs[0] != null && addrs[0].ip().indexOf(58) < 0
                           ? addrs[0]
                           : (addrs[1] != null && addrs[1].ip().indexOf(58) < 0 ? addrs[1] : (addrs[0] != null ? addrs[0] : addrs[1]));
                        if (chosen != null) {
                           mappedAddrs.add(chosen);
                           if (mappedIp == null) {
                              mappedIp = chosen.ip();
                           }
                        }

                        if (addrs[0] != null && addrs[1] != null && sameIpFamily(addrs[0].ip(), addrs[1].ip()) && addrs[0].port() != addrs[1].port()) {
                           hostPunchSocketSymmetric = true;
                           if (hostPunchSocketDelta == 0) {
                              hostPunchSocketDelta = addrs[1].port() - addrs[0].port();
                           }
                        }
                     }
                  } catch (Exception var49) {
                  }
               }

               if (mappedAddrs.isEmpty()) {
                  VoxLinkMod.LOGGER.error("[HostPunchInfo] All STUN queries failed");
               } else if (this.connectionWon.get()) {
                  VoxLinkMod.LOGGER.info("[HostPunchInfo] Connection established, discard late 84-socket punch task");

                  for (UdpHolePuncher p : hostPunchers) {
                     try {
                        p.close();
                     } catch (Exception var45) {
                     }
                  }
               } else {
                   if (hostPunchSocketSymmetric && !fState.roomInfo.isHostSymmetric()) {
                      fState.roomInfo.setHostSymmetric(true);
                      boolean hostPunchEasySym = Math.abs(hostPunchSocketDelta) <= 100;
                      NatClass newLocal = hostPunchEasySym ? NatClass.EASY_SYM : NatClass.HARD_SYM;
                      if (this.localNatClass != newLocal) {
                         VoxLinkMod.LOGGER.info("[HostPunchInfo] Update localNat {} -> {} (multi-socket symmetric detected, delta={})", new Object[]{this.localNatClass, newLocal, hostPunchSocketDelta});
                         this.localNatClass = newLocal;
                         this.remoteNatClass = this.classifyRemoteNat(fState);
                         // 用新 NAT 类重算硬场景档位，确保硬档仍走 V100 而非被降级为 AGGRESSIVE/DEFAULT
                         ScenarioTier.Tier prevTier = this.scenarioTier;
                         this.scenarioTier = ScenarioTier.classify(
                            this.localNatClass.isSymmetric(), this.remoteNatClass.isSymmetric(), true, true, true);
                         if (this.scenarioTier != prevTier) {
                            VoxLinkMod.LOGGER.info("[HostPunchInfo] tier {} -> {} (host symmetric known)", ScenarioTier.key(prevTier), ScenarioTier.key(this.scenarioTier));
                         }
                         PunchProfile recommended = NatClass.recommendProfile(this.localNatClass, this.remoteNatClass, this.scenarioTier);
                         this.switchPunchProfile(recommended, "host_punch_sym_" + this.localNatClass + "x" + this.remoteNatClass);
                         VoxLinkMod.LOGGER
                            .info("[HostPunchInfo] Probe upgrade: local={} remote={} -> profile={}",
                               new Object[]{this.localNatClass, this.remoteNatClass, this.punchProfile().describeInstance()});
                      }

                      if (hostPunchEasySym && !"symmetric_easy_inc".equals(fState.roomInfo.getNatType())) {
                         fState.roomInfo.setNatType("symmetric_easy_inc");
                         VoxLinkMod.LOGGER.info("[HostPunchInfo] Override cached NAT {} -> symmetric_easy_inc (multi-socket dual STUN detected)", "cached");
                      }
                   }

                  // 对称host生日攻击需足量多源端口(第八/九轮实证84可撞锥端), 探测出对称后补够再统一开打
                  if (hostPunchSocketSymmetric && hostPunchers.size() < PunchProfile.HARDSYM.hardSymSocketCount) {
                     int before = hostPunchers.size();
                     int targetCount = PunchProfile.HARDSYM.hardSymSocketCount;

                     for (int extra = hostPunchers.size(); extra < targetCount; extra++) {
                        try {
                           UdpHolePuncher ep = new UdpHolePuncher();
                           this.applyPunchTemplate(ep);
                           PunchParams eparams = PunchParams.fromProfile(this.punchProfile());
                           eparams.timeoutMs = Math.min(eparams.timeoutMs, this.punchProfile().hostRoundTimeoutMs);
                           ep.setProfile(this.punchProfile());
                           ep.setPunchParams(eparams);
                           ep.createSocket();
                           hostPunchers.add(ep);
                           this.activeHolePunchers.put("host_" + (hostPunchers.size() - 1), ep);
                        } catch (Exception e) {
                           VoxLinkMod.LOGGER.debug("[HostPunchInfo] extra socket create failed: {}", e.getMessage());
                        }
                     }

                     VoxLinkMod.LOGGER.info("[HostPunchInfo] Symmetric host, extend birthday attack {} -> {} sockets", before, hostPunchers.size());
                  }

                  JsonObject symData = new JsonObject();
                  if (hostPunchSocketSymmetric
                     || StunDetector.isNatTypeSymmetric(fState.roomInfo.getNatType())) {
                     symData.addProperty("hostSymmetric", true);
                  }

                  boolean hostEasySymMapped = hostPunchSocketSymmetric && Math.abs(hostPunchSocketDelta) <= 100;
                  if (hostEasySymMapped) {
                     symData.addProperty("hostEasySym", true);
                  }
                  if (hostPunchSocketSymmetric && hostPunchSocketDelta != 0) {
                     symData.addProperty("hostMappedPortDelta", hostPunchSocketDelta);
                  }

                  symData.addProperty("hostMappedIp", mappedIp);
                  symData.addProperty("hostMappedPort", mappedAddrs.get(0).port());
                  if (fRequestHostLocalIp || fState.roomInfo.isSameCgnat()) {
                     String myLocalIp = StunDetector.getLocalIpAddress();
                     if (myLocalIp != null && !myLocalIp.isEmpty()) {
                        symData.addProperty("hostLocalIp", myLocalIp);
                        VoxLinkMod.LOGGER.info("[HostPunchInfo] CGNAT scenario include hostLocalIp: {}", myLocalIp);
                     }
                  }

                  JsonArray portsArray = new JsonArray();

                  for (StunProbe.PublicMappedAddress a : mappedAddrs) {
                     portsArray.add(a.port());
                  }

                  symData.add("hostMappedPorts", portsArray);
                  VoxLinkMod.LOGGER
                     .info("[HostPunchInfo] holepunch_mapped: {} ports={} (symmetric={})", new Object[]{mappedIp, portsArray, hostPunchSocketSymmetric});
                  this.signalingClient
                     .sendSignal(fState.roomInfo.getCode(), fState.roomInfo.getToken(), true, "holepunch_mapped", symData, fFrom)
                     .exceptionally(e -> {
                        VoxLinkMod.LOGGER.debug("holepunch_mapped send failed: {}", e.getMessage());
                        return null;
                     });
                  this.hostPunching = true;
                  this.lastPunchStartMs = System.currentTimeMillis();
                  String clientId = fFrom;
                  ConnectionState.transitionTo(ConnectionState.UDP_PUNCH, "Host开始打洞 client=" + clientId);
                  AtomicBoolean hostPunchWon = new AtomicBoolean(false);

                  for (UdpHolePuncher p : hostPunchers) {
                     p.setOnPeerPunchReceived(addr -> {
                        String code = fState.roomInfo.getCode();
                        String token = fState.roomInfo.getToken();
                        JsonObject portData = new JsonObject();
                        portData.addProperty("peer_ip", addr.getAddress().getHostAddress());
                        portData.addProperty("peer_port", addr.getPort());
                        this.signalingClient.sendSignal(code, token, true, "peer_port", portData, fFrom).exceptionally(e -> {
                           VoxLinkMod.LOGGER.debug("peer_port signal failed: {}", e.getMessage());
                           return null;
                        });
                     });
                  }

                  ScheduledFuture<?> punchTimeout = this.scheduler.schedule(() -> {
                     if (this.hostPunching) {
                        VoxLinkMod.LOGGER.info("[HostPunchInfo] 120s fallback cleanup host socket client={}", clientId);
                        this.hostPunching = false;
                        this.lastPunchInfoId = "";
                        this.activeHolePunchers.remove("host");
                        this.activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith("host_"));

                        for (UdpHolePuncher px : hostPunchers) {
                           try {
                              px.cancel();
                              px.close();
                           } catch (Exception var6x) {
                           }
                        }
                     }
                  }, 120L, TimeUnit.SECONDS);
                  boolean joinerSymmetric = fData.has("joinerSymmetric") && fData.get("joinerSymmetric").getAsBoolean();
                  // EasyTier方式(1.0.0兼容): 对称端多socket只发对端已知端口, 不扫范围。
                  // 对称CGNAT上扫射会炸出 socket数×端口数 个未知映射并触发洪泛保护, 双向全丢(GBNPLE实证);
                  // 生日攻击靠多个源端口撞对端cone已知端口, 不靠扫射。
                  // 双对称时对端已知端口只是其对STUN的假映射, 必须扫其上报端口±带宽; joinerSym标记可能晚到, 以双方NAT为准
                  boolean hostDoubleSym = this.localNatClass.isSymmetric() && this.remoteNatClass.isSymmetric();
                  int hostPortRange = joinerSymmetric || hostDoubleSym ? this.punchProfile().joinerMultiPortRange : 0;

                  // 探测升级(如多socket双STUN检出对称NAT切换V100/HARDSYM)后刷新整组模板,
                  // 避免socket创建时套用的旧DEFAULT参数贯穿全部轮次
                  for (UdpHolePuncher rp : hostPunchers) {
                     if (rp == null || rp.getSocket() == null || rp.getSocket().isClosed()) {
                        continue;
                     }

                     rp.setProfile(this.punchProfile());
                     PunchParams roundParams = PunchParams.fromProfile(this.punchProfile());
                     roundParams.timeoutMs = Math.min(roundParams.timeoutMs, this.punchProfile().hostRoundTimeoutMs);
                     if (hostPortRange > 0) {
                        roundParams.sendMinRounds = 1;
                        roundParams.sendMinPass = 1;
                     }

                     rp.setPunchParams(roundParams);
                  }

                  VoxLinkMod.LOGGER
                     .info(
                        "[HostPunchInfo] {} sockets parallel punch to {}:{} range=±{} (joinerSym={})",
                        new Object[]{hostPunchers.size(), fJoinerMappedIp, fJoinerMappedPort, hostPortRange, joinerSymmetric}
                     );
                  boolean hostDriftSync = hostPunchSocketSymmetric || fState.roomInfo.isHostSymmetric();
                  long driftMaxDurMs = 120000L;
                  long driftStartMs = System.currentTimeMillis();
                  long driftLastSentMs = 0L;

                  if (!this.connectionWon.get() && this.roomManager.currentRoom.get() == fState) {
                     long punchGroupDeadline = System.currentTimeMillis() + 120000L;

                     while (!this.connectionWon.get() && this.roomManager.currentRoom.get() == fState && System.currentTimeMillis() < punchGroupDeadline) {
                        if (hostDriftSync && this.hostPunching && !mappedAddrs.isEmpty()) {
                           long nowMs = System.currentTimeMillis();
                           long driftElapsed = nowMs - driftStartMs;
                           if (nowMs - driftLastSentMs >= 3000L && driftElapsed < driftMaxDurMs) {
                              driftLastSentMs = nowMs;

                              try {
                                 JsonObject rsync = new JsonObject();
                                 rsync.addProperty("hostMappedIp", mappedIp);
                                 rsync.addProperty("hostMappedPort", mappedAddrs.get(0).port());
                                 rsync.addProperty("hostSymmetric", true);
                                 JsonArray rsyncPorts = new JsonArray();

                                 for (StunProbe.PublicMappedAddress a : mappedAddrs) {
                                    rsyncPorts.add(a.port());
                                 }

                                 rsync.add("hostMappedPorts", rsyncPorts);
                                 if (hostEasySymMapped) {
                                    rsync.addProperty("hostEasySym", true);
                                 }

                                 this.signalingClient
                                    .sendSignal(fState.roomInfo.getCode(), fState.roomInfo.getToken(), true, "holepunch_mapped", rsync, fFrom)
                                    .exceptionally(e -> {
                                       VoxLinkMod.LOGGER.debug("holepunch_mapped resync send failed: {}", e.getMessage());
                                       return null;
                                    });
                                 VoxLinkMod.LOGGER
                                    .info(
                                       "[HostPunchInfo] drift resync: resend holepunch_mapped {}:{} ports={} (elapsed={}ms)",
                                       new Object[]{mappedIp, mappedAddrs.get(0).port(), rsyncPorts, driftElapsed}
                                    );
                              } catch (Exception var52) {
                              }
                           }
                        }

                        boolean anyAlive = false;
                        List<CompletableFuture<?>> roundFutures = new ArrayList<>();

                        for (int i = 0; i < hostPunchers.size(); i++) {
                           UdpHolePuncher mp = hostPunchers.get(i);
                           if (mp.isSocketTransferred() || mp.getSocket() == null || mp.getSocket().isClosed()) {
                              continue;
                           }

                           anyAlive = true;
                           int idx = i;
                           roundFutures.add(
                              mp.punchWithPortPrediction(fJoinerMappedIp, fJoinerMappedPort, hostPortRange)
                                 .thenAccept(
                                    result -> {
                                       if (!result.isSuccess()) {
                                          PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                                          this.lastPunchResult = result.withReason(reason);
                                          this.activePunchParams = 
                                             PunchTuner.nextParams(
                                                this.punchProfile(), this.localNatClass, this.remoteNatClass, 1, 8, reason, this.lastPunchResult
                                             )
                                          ;
                                          VoxLinkMod.LOGGER
                                             .info(
                                                "[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}",
                                                new Object[]{reason, result.socketsReceivedPunch, result.socketsReceivedAck}
                                             );
                                       } else {
                                          DatagramSocket socket = result.getSuccessSocket();
                                          if (!hostPunchWon.compareAndSet(false, true)) {
                                             try {
                                                mp.close();
                                             } catch (Exception var13x) {
                                             }
                                          } else if (this.roomManager.currentRoom.get() == fState && this.connectionWon.compareAndSet(false, true)) {
                                             VoxLinkMod.LOGGER.info("[HostPunchInfo] Socket#{} punch success!", idx);
                                             mp.markSocketTransferred();
                                             this.stopAllPunchingAfterHostBridge();
                                             mp.stopPunch();
                                             DatagramSocket winSocket = socket;
                                             UdpHolePuncher winPuncher = mp;
                                             this.scheduler
                                                .submit(
                                                   () -> {
                                                      try {
                                                         this.establishUdpTransport(
                                                            fState,
                                                            winSocket,
                                                            winPuncher,
                                                            new InetSocketAddress(fJoinerMappedIp, fJoinerMappedPort),
                                                            clientId,
                                                            true,
                                                            clientId
                                                         );
                                                      } catch (Exception e) {
                                                         VoxLinkMod.LOGGER.error("[HostPunchInfo] Transport create failed: {}", e.getMessage());
                                                         winPuncher.close();
                                                      }
                                                   }
                                                );
                                          } else {
                                             try {
                                                mp.close();
                                             } catch (Exception var14x) {
                                             }
                                          }
                                       }
                                    }
                                 )
                                 .exceptionally(e -> {
                                    VoxLinkMod.LOGGER.debug("[HostPunchInfo] Socket#{} punch failed: {}", idx, e.getMessage());
                                    return null;
                                 })
                           );
                        }

                        if (!anyAlive) {
                           break;
                        }

                        try {
                           CompletableFuture.allOf(roundFutures.toArray(new CompletableFuture[0])).join();
                        } catch (Exception var15x) {
                        }

                        if (this.connectionWon.get() || this.roomManager.currentRoom.get() != fState) {
                           break;
                        }

                        try {
                           Thread.sleep(300L);
                        } catch (InterruptedException ie) {
                           Thread.currentThread().interrupt();
                           break;
                        }
                     }
                  } else {
                     VoxLinkMod.LOGGER.info("[HostPunchInfo] Connected or room changed during lazy, abort punch");

                     for (UdpHolePuncher p : hostPunchers) {
                        try {
                           p.close();
                        } catch (Exception var47) {
                        }
                     }
                  }
               }
            },
            this.punchExecutor
         );
      }
   }

   public void handleJoinerPunchInfo(RoomManager.RoomState state, String from, JsonObject data) {
      String hostMappedIp = null;
      int hostMappedPort = 0;
      if (data.has("hostMappedIp") && !data.get("hostMappedIp").isJsonNull()) {
         hostMappedIp = data.get("hostMappedIp").getAsString();
      }

      if (data.has("hostMappedPort") && !data.get("hostMappedPort").isJsonNull()) {
         hostMappedPort = data.get("hostMappedPort").getAsInt();
      }

      if (hostMappedIp != null && hostMappedPort > 0) {
         VoxLinkMod.LOGGER.info("[RoomManager] Host mapped address in punch_info: {}:{}", hostMappedIp, hostMappedPort);
         state.roomInfo.setHostMappedAddress(hostMappedIp, hostMappedPort);
         UdpHolePuncher joinerPuncher = this.activeHolePunchers.get("joiner");
         if (joinerPuncher != null && this.connectionCycleActive.get()) {
            VoxLinkMod.LOGGER.info("[RoomManager] Update joiner punch target to {}:{}", hostMappedIp, hostMappedPort);
            joinerPuncher.updateTarget(hostMappedIp, hostMappedPort);
         }
      }
   }

   public void handlePeerPort(String from, JsonObject data) {
      String peerIp = data.has("peer_ip") ? data.get("peer_ip").getAsString() : null;
      int peerPort = data.has("peer_port") ? data.get("peer_port").getAsInt() : 0;
      if (peerIp != null && peerPort > 0) {
         UdpHolePuncher puncher = this.activeHolePunchers.get(from.contains("host") ? "joiner" : "host");
         if (puncher != null) {
            VoxLinkMod.LOGGER.info("[RoomManager] Received peer_port signal: update target to {}:{}", peerIp, peerPort);
            puncher.updateTarget(peerIp, peerPort);
         }
      }
   }

   public void handleReverseHolepunchOffer(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state != null && state != RoomManager.PENDING && state.roomInfo.isHost()) {
         long syncTime = state.roomInfo.getPunchSyncTimeMs();
         if (syncTime > 0L) {
            long delay = syncTime - System.currentTimeMillis();
            if (delay > 0L && delay < 8000L) {
               VoxLinkMod.LOGGER.info("[ReversePunch] RTT sync wait: start host punch after {}ms", delay);
               this.scheduler.schedule(() -> {
                  if (this.roomManager.currentRoom.get() == state && !this.connectionWon.get()) {
                     this.handleReverseHolepunchOfferDelayed(from, data);
                  }
               }, delay, TimeUnit.MILLISECONDS);
               return;
            }
         }

         this.handleReverseHolepunchOfferDelayed(from, data);
      }
   }

   private void handleReverseHolepunchOfferDelayed(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state != null && state != RoomManager.PENDING && state.roomInfo.isHost()) {
         if (this.activeUdpTransports.containsKey(from)) {
            VoxLinkMod.LOGGER.info("[ReversePunch] Active transport to {}, ignore reverse_holepunch_offer", from);
         } else {
            String joinerMappedIp = data.has("joinerMappedIp") ? data.get("joinerMappedIp").getAsString() : null;
            int joinerMappedPort = data.has("joinerMappedPort") ? data.get("joinerMappedPort").getAsInt() : 0;
            boolean joinerSymmetric = data.has("joinerSymmetric") && data.get("joinerSymmetric").getAsBoolean();
            int joinerMappedPortDelta = data.has("joinerMappedPortDelta") && !data.get("joinerMappedPortDelta").isJsonNull()
               ? data.get("joinerMappedPortDelta").getAsInt()
               : 0;
            if (joinerMappedIp != null && joinerMappedPort > 0) {
               String joinerNatType = joinerSymmetric ? "symmetric" : "full_cone";
               state.roomInfo.addOrUpdatePeer(from, joinerNatType, joinerMappedIp, joinerMappedPort);
            }

            if (joinerMappedPortDelta != 0 && joinerMappedPort > 0) {
               VoxLinkMod.LOGGER
                  .info("[ReversePunch] Punch joiner raw port={} (delta={} skipped: 同socket双STUN差值非跨socket步长)", new Object[]{joinerMappedPort, joinerMappedPortDelta});
            }

            if (joinerMappedIp != null && joinerMappedPort != 0) {
               VoxLinkMod.LOGGER
                  .info(
                     "[ReversePunch] Host received reverse_holepunch_offer from {}: {}:{} (joinerSym={})",
                     new Object[]{from, joinerMappedIp, joinerMappedPort, joinerSymmetric}
                  );
               UdpHolePuncher existingReverse = this.activeHolePunchers.get("hostRev");
               if (existingReverse != null && existingReverse.isPunching()) {
                  VoxLinkMod.LOGGER.info("[ReversePunch] already reverse punching, update target to {}:{}", joinerMappedIp, joinerMappedPort);
                  existingReverse.updateTarget(joinerMappedIp, joinerMappedPort);
               } else {
                  UdpHolePuncher puncher = new UdpHolePuncher();
                  this.applyPunchTemplate(puncher);

                  try {
                     puncher.createSocket();
                  } catch (Exception e) {
                     VoxLinkMod.LOGGER.error("[ReversePunch] create socket failed: {}", e.getMessage());
                     return;
                  }

                  VoxLinkMod.LOGGER.info("[ReversePunch] Host reverse punch socket: localPort={}", puncher.getSocket().getLocalPort());
                  this.activeHolePunchers.put("hostRev", puncher);
                  RoomManager.RoomState fState = state;
                  String fFrom = from;
                  JsonObject fData = data;
                  UdpHolePuncher fPuncher = puncher;
                  String fJoinerMappedIp = joinerMappedIp;
                  int fJoinerMappedPort = joinerMappedPort;
                  boolean fJoinerSymmetric = joinerSymmetric;
                  this.punchExecutor
                     .execute(
                        () -> {
                           VoxLinkMod.LOGGER.info("[ReversePunch] dual STUN on reverse socket...");
                           StunProbe.PublicMappedAddress m1 = null;
                           StunProbe.PublicMappedAddress m2 = null;

                           try {
                              m1 = fPuncher.discoverMappedAddress(List.of(StunDetector.getAllStunUrls().get(0)));
                              VoxLinkMod.LOGGER
                                 .info(
                                    "[ReversePunch] Host reverse STUN #1: ip={}, port={} (localPort={})",
                                    new Object[]{m1 != null ? m1.ip() : "null", m1 != null ? m1.port() : -1, fPuncher.getSocket().getLocalPort()}
                                 );
                              m2 = fPuncher.discoverMappedAddress(List.of(StunDetector.getAllStunUrls().get(1)));
                              VoxLinkMod.LOGGER
                                 .info(
                                    "[ReversePunch] Host reverse STUN #2: ip={}, port={} (localPort={})",
                                    new Object[]{m2 != null ? m2.ip() : "null", m2 != null ? m2.port() : -1, fPuncher.getSocket().getLocalPort()}
                                 );
                           } catch (Exception e) {
                              VoxLinkMod.LOGGER.warn("[ReversePunch] Dual STUN failed: {}", e.getMessage());
                           }

                           StunProbe.PublicMappedAddress hostMapped1 = m1;
                           StunProbe.PublicMappedAddress hostMapped2 = m2;
                           boolean hostPunchSocketSymmetric = false;
                           int hostRevDelta = 0;
                           StunProbe.PublicMappedAddress hostMapped = null;
                           if (hostMapped1 != null && hostMapped2 != null) {
                              if (sameIpFamily(hostMapped1.ip(), hostMapped2.ip()) && hostMapped1.port() != hostMapped2.port()) {
                                 hostPunchSocketSymmetric = true;
                                 hostRevDelta = hostMapped2.port() - hostMapped1.port();
                                 VoxLinkMod.LOGGER
                                    .info("[ReversePunch] Host punch socket STUN: symmetric detected ({} vs {}, delta={})", new Object[]{hostMapped1.port(), hostMapped2.port(), hostRevDelta});
                              }

                              hostMapped = hostMapped2;
                           } else {
                              hostMapped = hostMapped1 != null ? hostMapped1 : hostMapped2;
                           }

                           if (hostPunchSocketSymmetric && !fState.roomInfo.isHostSymmetric()) {
                              fState.roomInfo.setHostSymmetric(true);
                              boolean hostRevEasySym = Math.abs(hostRevDelta) <= 100;
                              NatClass newLocal = hostRevEasySym ? NatClass.EASY_SYM : NatClass.HARD_SYM;
                              if (this.localNatClass != newLocal) {
                                 VoxLinkMod.LOGGER
                                    .info("[ReversePunch] Update localNat {} -> {} (reverse socket symmetric detected)", this.localNatClass, newLocal);
                                 this.localNatClass = newLocal;
                                 this.remoteNatClass = this.classifyRemoteNat(fState);
                                 // 用新 NAT 类重算硬场景档位，确保硬档仍走 V100 而非被降级为 AGGRESSIVE/DEFAULT
                                 ScenarioTier.Tier prevTier = this.scenarioTier;
                                 this.scenarioTier = ScenarioTier.classify(
                                    this.localNatClass.isSymmetric(), this.remoteNatClass.isSymmetric(), true, true, true);
                                 if (this.scenarioTier != prevTier) {
                                    VoxLinkMod.LOGGER.info("[ReversePunch] tier {} -> {} (reverse socket symmetric known)", ScenarioTier.key(prevTier), ScenarioTier.key(this.scenarioTier));
                                 }
                                 PunchProfile recommended = NatClass.recommendProfile(this.localNatClass, this.remoteNatClass, this.scenarioTier);
                                 this.switchPunchProfile(recommended, "reverse_socket_" + this.localNatClass + "x" + this.remoteNatClass);
                              }
                           }

                           JsonObject punchData = new JsonObject();
                           if (hostMapped != null) {
                              punchData.addProperty("hostMappedIp", hostMapped.ip());
                              punchData.addProperty("hostMappedPort", hostMapped.port());
                           }

                           boolean hostSym = hostPunchSocketSymmetric || fState.roomInfo.isHostSymmetric() || StunDetector.isNatTypeSymmetric(fState.roomInfo.getNatType());
                           if (hostSym) {
                              punchData.addProperty("hostSymmetric", true);
                           }

                           boolean hostEasySymRev = hostPunchSocketSymmetric && Math.abs(hostRevDelta) <= 100;
                           if (hostEasySymRev) {
                              punchData.addProperty("hostEasySym", true);
                           }

                           this.signalingClient
                              .sendSignal(fState.roomInfo.getCode(), fState.roomInfo.getToken(), true, "reverse_punch_info", punchData, fFrom)
                              .exceptionally(e -> {
                                 VoxLinkMod.LOGGER.debug("[ReversePunch] reverse_punch_info send failed: {}", e.getMessage());
                                 return null;
                              });
                           fPuncher.setOnPeerPunchReceived(addr -> {
                              String code = fState.roomInfo.getCode();
                              String token = fState.roomInfo.getToken();
                              JsonObject portData = new JsonObject();
                              portData.addProperty("peer_ip", addr.getAddress().getHostAddress());
                              portData.addProperty("peer_port", addr.getPort());
                              this.signalingClient.sendSignal(code, token, true, "peer_port", portData, fFrom).exceptionally(e -> {
                                 VoxLinkMod.LOGGER.debug("peer_port signal failed: {}", e.getMessage());
                                 return null;
                              });
                           });
                           String hostNat = fState.roomInfo.getNatType();
                           boolean hostSymmetric = hostPunchSocketSymmetric || StunDetector.isNatTypeSymmetric(hostNat) || fState.roomInfo.isHostSymmetric();
                           List<Integer> allJoinerPorts = new ArrayList<>();
                           if (fData.has("joinerMappedPorts") && fData.get("joinerMappedPorts").isJsonArray()) {
                              for (JsonElement elem : fData.getAsJsonArray("joinerMappedPorts")) {
                                 allJoinerPorts.add(elem.getAsInt());
                              }
                           }

                           if (allJoinerPorts.isEmpty()) {
                              allJoinerPorts.add(fJoinerMappedPort);
                           }

                           int hostPortRange = this.punchProfile().defaultPortRange;
                           if (hostSymmetric && fJoinerSymmetric) {
                              hostPortRange = 0;
                           } else if (fJoinerSymmetric && allJoinerPorts.size() > 1) {
                              hostPortRange = 0;
                              VoxLinkMod.LOGGER
                                 .info("[ReversePunch] Birthday attack mode: host punch {} joiner ports: {}", allJoinerPorts.size(), allJoinerPorts);
                            } else if (fJoinerSymmetric) {
                               hostPortRange = this.punchProfile().widePortRange;
                            } else if (hostSymmetric) {
                               hostPortRange = this.punchProfile().defaultPortRange;
                           } else if (!"moderate".equals(hostNat) && !"port_restricted_cone".equals(hostNat)) {
                               hostPortRange = this.punchProfile().defaultPortRange;
                            } else {
                               hostPortRange = this.punchProfile().portPredictionMaxRange;
                            }

                           VoxLinkMod.LOGGER
                              .info(
                                 "[ReversePunch] Host punch to joiner {} (range=±{}, hostNat={}, joinerSym={}, birthdayPorts={})",
                                 new Object[]{fJoinerMappedIp, hostPortRange, hostNat, fJoinerSymmetric, allJoinerPorts.size() > 1 ? allJoinerPorts : "no"}
                              );
                           String clientId = fFrom;
                           UdpHolePuncher currentPuncher = fPuncher;
                           String fRevJoinerMappedIp = fJoinerMappedIp;
                           int fRevJoinerMappedPort = fJoinerMappedPort;
                           // 刷新reverse puncher模板(创建时可能还是旧profile)并保底12s,
                           // 与joiner反向socket的纠偏后窗口充分重叠
                           currentPuncher.setProfile(this.punchProfile());
                           PunchParams hostRevParams = PunchParams.fromProfile(this.punchProfile());
                           hostRevParams.timeoutMs = Math.max(hostRevParams.timeoutMs, 12000);
                           currentPuncher.setPunchParams(hostRevParams);
                           ScheduledFuture<?> punchTimeout = this.scheduler.schedule(() -> {
                              if (this.activeHolePunchers.get("hostRev") == currentPuncher) {
                                 VoxLinkMod.LOGGER.warn("[ReversePunch] Host reverse punch timeout: {}", clientId);
                                 currentPuncher.cancel();
                                 currentPuncher.close();
                                 this.activeHolePunchers.remove("hostRev");
                              }
                           }, this.punchProfile().reverseWindowSec, TimeUnit.SECONDS);
                           CompletableFuture<PunchResult> punchFuture;
                           if (allJoinerPorts.size() > 1) {
                              Set<Integer> expandedPorts = new LinkedHashSet<>();

                              for (int port : allJoinerPorts) {
                                 for (int offset = -this.punchProfile().defaultPortRange; offset <= this.punchProfile().defaultPortRange; offset++) {
                                    int p = port + offset;
                                    if (p > 0 && p <= 65535) {
                                       expandedPorts.add(p);
                                    }
                                 }
                              }

                              List<Integer> portList = new ArrayList<>(expandedPorts);
                              VoxLinkMod.LOGGER
                                 .info(
                                    "[ReversePunch] Birthday attack: {} ports expanded to {} ports (range {}-{})",
                                    new Object[]{
                                       allJoinerPorts.size(),
                                       portList.size(),
                                       allJoinerPorts.get(0) - this.punchProfile().defaultPortRange,
                                       allJoinerPorts.get(allJoinerPorts.size() - 1) + this.punchProfile().defaultPortRange
                                    }
                                 );
                              punchFuture = currentPuncher.punchMultiPort(fRevJoinerMappedIp, portList);
                           } else {
                              punchFuture = currentPuncher.punchWithPortPrediction(fRevJoinerMappedIp, fRevJoinerMappedPort, hostPortRange, true);
                           }

                           punchFuture.thenAccept(
                                 result -> {
                                    if (!result.isSuccess()) {
                                       PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                                       this.lastPunchResult = result.withReason(reason);
                                       this.activePunchParams = 
                                          PunchTuner.nextParams(
                                             this.punchProfile(), this.localNatClass, this.remoteNatClass, 1, 8, reason, this.lastPunchResult
                                          )
                                       ;
                                       VoxLinkMod.LOGGER
                                          .info(
                                             "[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}",
                                             new Object[]{reason, result.socketsReceivedPunch, result.socketsReceivedAck}
                                          );
                                    } else {
                                       DatagramSocket socket = result.getSuccessSocket();
                                       if (this.roomManager.currentRoom.get() == fState && this.connectionWon.compareAndSet(false, true)) {
                                          VoxLinkMod.LOGGER
                                             .info(
                                                "[ReversePunch] Host reverse punch success, connected to joiner {}:{}",
                                                fRevJoinerMappedIp,
                                                fRevJoinerMappedPort
                                             );
                                          currentPuncher.markSocketTransferred();
                                          this.stopAllPunchingAfterHostBridge();
                                          currentPuncher.stopPunch();
                                          DatagramSocket hostPunchSocket = socket;
                                          UdpHolePuncher hostPuncherRef = currentPuncher;
                                          this.scheduler
                                             .submit(
                                                () -> {
                                                   try {
                                                      this.establishUdpTransport(
                                                         fState,
                                                         hostPunchSocket,
                                                         hostPuncherRef,
                                                         new InetSocketAddress(fRevJoinerMappedIp, fRevJoinerMappedPort),
                                                         clientId,
                                                         true,
                                                         clientId
                                                      );
                                                   } catch (Exception e) {
                                                      VoxLinkMod.LOGGER.error("[ReversePunch] Host UDP transport create failed: {}", e.getMessage());
                                                      hostPuncherRef.close();
                                                   }
                                                }
                                             );
                                       } else {
                                          currentPuncher.close();
                                       }
                                    }
                                 }
                              )
                              .exceptionally(e -> {
                                 punchTimeout.cancel(false);
                                 VoxLinkMod.LOGGER.warn("[ReversePunch] Host reverse punch failed {}: {}", clientId, e.getMessage());
                                 currentPuncher.cancel();
                                 currentPuncher.close();
                                 this.activeHolePunchers.remove("hostRev");
                                 return null;
                              });
                        }
                     );
               }
            } else {
               VoxLinkMod.LOGGER.warn("[ReversePunch] Invalid reverse_holepunch_offer: no mapped address");
            }
         }
      }
   }

   public void handleReversePunchInfo(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state != null && state != RoomManager.PENDING && !state.roomInfo.isHost()) {
         if (!this.connectionCycleActive.get()) {
            VoxLinkMod.LOGGER.info("[ReversePunch] Not in connection cycle, ignore reverse_punch_info");
         } else {
            String hostMappedIp = null;
            int hostMappedPort = 0;
            if (data.has("hostMappedIp") && !data.get("hostMappedIp").isJsonNull()) {
               hostMappedIp = data.get("hostMappedIp").getAsString();
            }

            if (data.has("hostMappedPort") && !data.get("hostMappedPort").isJsonNull()) {
               hostMappedPort = data.get("hostMappedPort").getAsInt();
            }

            boolean hostSymmetric = data.has("hostSymmetric") && data.get("hostSymmetric").getAsBoolean();
            boolean hostEasySym = data.has("hostEasySym") && !data.get("hostEasySym").isJsonNull() && data.get("hostEasySym").getAsBoolean();
            if (hostSymmetric) {
               state.roomInfo.setHostSymmetric(true);
            }

            if (hostEasySym) {
               state.roomInfo.setHostEasySym(true);
            }

            UdpHolePuncher existingPuncher = this.activeHolePunchers.get("joiner_reverse");
            if (existingPuncher != null && existingPuncher.isPunching()) {
               int updatePort = hostMappedPort;
               int delta = state.roomInfo.getHostMappedPortDelta();
               if (hostMappedPort > 0 && delta != 0) {
                  int predicted = hostMappedPort + delta;
                  if (predicted > 0 && predicted <= 65535) {
                     updatePort = predicted;
                  }
               }

               VoxLinkMod.LOGGER.info("[ReversePunch] Already reverse punching, update target to {}:{}", hostMappedIp, updatePort);
               if (hostMappedIp != null && updatePort > 0) {
                  existingPuncher.updateTarget(hostMappedIp, updatePort);
               }

               UdpHolePuncher joinerPuncher = this.activeHolePunchers.get("joiner");
               if (joinerPuncher != null && joinerPuncher.isPunching() && hostMappedIp != null && updatePort > 0) {
                  VoxLinkMod.LOGGER.info("[ReversePunch] Sync update main joiner punch target to {}:{}", hostMappedIp, updatePort);
                  joinerPuncher.updateTarget(hostMappedIp, updatePort);
               }
            } else {
               if (hostMappedIp == null || hostMappedPort <= 0) {
                  hostMappedIp = state.roomInfo.getHostMappedIp();
                  hostMappedPort = state.roomInfo.getHostMappedPort();
               }

               if (hostMappedIp != null && hostMappedPort > 0) {
                  if (hostSymmetric) {
                     state.roomInfo.setHostSymmetric(true);
                  }

                  VoxLinkMod.LOGGER
                     .info("[ReversePunch] Joiner received reverse_punch_info: {}:{} (hostSym={})", new Object[]{hostMappedIp, hostMappedPort, hostSymmetric});
                  List<UdpHolePuncher> birthdayPunchers = new ArrayList<>();
                  List<String> birthdayKeys = new ArrayList<>();

                  for (Entry<String, UdpHolePuncher> entry : this.activeHolePunchers.entrySet()) {
                     if (entry.getKey().startsWith("joiner_birthday_")) {
                        birthdayPunchers.add(entry.getValue());
                        birthdayKeys.add(entry.getKey());
                     }
                  }

                  if (!birthdayPunchers.isEmpty()) {
                     boolean anyPunching = birthdayPunchers.stream().anyMatch(UdpHolePuncher::isPunching);
                     if (anyPunching) {
                        VoxLinkMod.LOGGER
                           .info(
                              "[BirthdayPunch] Already punching, update target to {}:{}, {} sockets total",
                              new Object[]{hostMappedIp, hostMappedPort, birthdayPunchers.size()}
                           );

                        for (UdpHolePuncher p : birthdayPunchers) {
                           if (p.isPunching()) {
                              p.updateTarget(hostMappedIp, hostMappedPort);
                           }
                        }
                     } else {
                        VoxLinkMod.LOGGER
                           .info(
                              "[BirthdayPunch] Start birthday attack {} sockets punch to {}:{}",
                              new Object[]{birthdayPunchers.size(), hostMappedIp, hostMappedPort}
                           );
                        this.startBirthdayPunchPhase2(state, birthdayPunchers, birthdayKeys, hostMappedIp, hostMappedPort, hostSymmetric, false);
                     }
                  } else {
                     UdpHolePuncher puncher = this.activeHolePunchers.get("joiner_reverse");
                     if (puncher != null && puncher.getSocket() != null && !puncher.getSocket().isClosed()) {
                        VoxLinkMod.LOGGER
                           .info(
                              "[ReversePunch] Joiner reverse punch state: localPort={}, punching={}", puncher.getSocket().getLocalPort(), puncher.isPunching()
                           );
                        puncher.setOnPeerPunchReceived(
                           addr -> {
                              VoxLinkMod.LOGGER
                                 .info(
                                    "[ReversePunch] Joiner received peer punch packet {}:{} — send peer_port signal",
                                    addr.getAddress().getHostAddress(),
                                    addr.getPort()
                                 );
                              String code = state.roomInfo.getCode();
                              String token = state.roomInfo.getToken();
                              JsonObject portData = new JsonObject();
                              portData.addProperty("peer_ip", addr.getAddress().getHostAddress());
                              portData.addProperty("peer_port", addr.getPort());
                              this.signalingClient.sendSignal(code, token, false, "peer_port", portData, "host").exceptionally(e -> {
                                 VoxLinkMod.LOGGER.debug("peer_port signal send failed: {}", e.getMessage());
                                 return null;
                              });
                           }
                        );
                        boolean joinerIsSymmetric = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric();
                        int portRange = this.punchProfile().defaultPortRange;
                        if (joinerIsSymmetric) {
                           portRange = this.punchProfile().defaultPortRange;
                           VoxLinkMod.LOGGER.info("[ReversePunch] Joiner is symmetric NAT — use small range (±30) to open NAT mapping");
                        } else if (hostSymmetric) {
                           portRange = this.punchProfile().portPredictionMaxRange;
                        } else {
                           portRange = this.punchProfile().defaultPortRange;
                        }

                        VoxLinkMod.LOGGER
                           .info(
                              "[ReversePunch] Joiner punch to host {}:{} (range=±{}, joinerSym={}, hostSym={})",
                              new Object[]{hostMappedIp, hostMappedPort, portRange, joinerIsSymmetric, hostSymmetric}
                           );
                        UdpHolePuncher finalPuncher = puncher;
                        String fHostMappedIp = hostMappedIp;
                        int fHostMappedPort = hostMappedPort;
                        puncher.punchWithPortPrediction(hostMappedIp, hostMappedPort, portRange, true)
                           .thenAccept(
                              result -> {
                                 if (!result.isSuccess()) {
                                    PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                                    this.lastPunchResult = result.withReason(reason);
                                    this.activePunchParams = 
                                       PunchTuner.nextParams(
                                          this.punchProfile(), this.localNatClass, this.remoteNatClass, 1, 8, reason, this.lastPunchResult
                                       )
                                    ;
                                    VoxLinkMod.LOGGER
                                       .info(
                                          "[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}",
                                          new Object[]{reason, result.socketsReceivedPunch, result.socketsReceivedAck}
                                       );
                                 } else {
                                    DatagramSocket socket = result.getSuccessSocket();
                                    if (this.roomManager.currentRoom.get() == state && this.connectionWon.compareAndSet(false, true)) {
                                       VoxLinkMod.LOGGER.info("[ReversePunch] Joiner reverse punch success {}:{}", fHostMappedIp, fHostMappedPort);
                                       finalPuncher.markSocketTransferred();
                                       this.stopAllPunchingAfterHostBridge();
                                       finalPuncher.stopPunch();
                                       DatagramSocket punchSocket = socket;
                                       UdpHolePuncher puncherRef = finalPuncher;
                                       InetSocketAddress actualAddr = puncherRef.getActualRemoteAddress();
                                       if (actualAddr == null) {
                                          actualAddr = new InetSocketAddress(fHostMappedIp, fHostMappedPort);
                                       }

                                       InetSocketAddress finalTargetAddr = actualAddr;
                                       VoxLinkMod.LOGGER
                                          .info(
                                             "[ReversePunch] Actual target address: {} (STUN mapping: {}:{})",
                                             new Object[]{finalTargetAddr, fHostMappedIp, fHostMappedPort}
                                          );
                                       this.scheduler.submit(() -> {
                                          try {
                                             this.establishUdpTransport(state, punchSocket, puncherRef, finalTargetAddr, "joiner", false, null);
                                          } catch (Exception e) {
                                             VoxLinkMod.LOGGER.error("[ReversePunch] Joiner UDP transport create failed: {}", e.getMessage());

                                             try {
                                                puncherRef.close();
                                             } catch (Exception var7x) {
                                             }

                                             this.showConnectFailed(state, "voxlink.connection.transport_failed");
                                          }
                                       });
                                    } else {
                                       try {
                                          finalPuncher.close();
                                       } catch (Exception var11) {
                                       }
                                    }
                                 }
                              }
                           )
                           .exceptionally(e -> {
                              VoxLinkMod.LOGGER.warn("[ReversePunch] Joiner reverse punch failed: {}", e.getMessage());
                              finalPuncher.cancel();
                              finalPuncher.close();
                              this.activeHolePunchers.remove("joiner_reverse");
                              this.showConnectFailed(state, "voxlink.connection.reverse_punch_failed");
                              return null;
                           });
                     } else {
                        VoxLinkMod.LOGGER
                           .warn(
                              "[ReversePunch] No joiner_reverse puncher available (puncher={}, socket={}, closed={})",
                              new Object[]{
                                 puncher != null,
                                 puncher != null ? puncher.getSocket() != null : false,
                                 puncher != null && puncher.getSocket() != null ? puncher.getSocket().isClosed() : false
                              }
                           );
                        this.showConnectFailed(state, "voxlink.connection.reverse_punch_failed");
                     }
                  }
               } else {
                  VoxLinkMod.LOGGER.warn("[ReversePunch] No host mapped address in reverse_punch_info");
               }
            }
         }
      }
   }

   public void handleTcpSimopenRequest(String from, JsonObject data) {
      String joinerMappedIp = data.has("joinerMappedIp") ? data.get("joinerMappedIp").getAsString() : null;
      int joinerMappedPort = data.has("joinerMappedPort") ? data.get("joinerMappedPort").getAsInt() : 0;
      if (joinerMappedIp != null && joinerMappedPort != 0) {
         if (this.connectionWon.get()) {
            VoxLinkMod.LOGGER.info("[TcpSimOpen] already connected, ignoring tcp_simopen_request");
         } else {
            int hostPort = P2PBridge.getHostPort() > 0 ? P2PBridge.getHostPort() : 25565;
            if (P2PBridge.getHostPort() > 0) {
               VoxLinkMod.LOGGER.info("[TcpSimOpen] Host bridge listening port={}, skip SimOpen", hostPort);
            } else {
               VoxLinkMod.LOGGER
                  .info("[TcpSimOpen] Host received joiner {} request, try TCP connect {}:{}", new Object[]{from, joinerMappedIp, joinerMappedPort});
               ConnectionFallback hostSimFallback = this.trackFallback(new ConnectionFallback());
               hostSimFallback.tryTcpSimultaneousOpen(joinerMappedIp, joinerMappedPort, hostPort).thenAccept(result -> {
                  if (result.success && this.connectionWon.compareAndSet(false, true)) {
                     VoxLinkMod.LOGGER.info("[TcpSimOpen] Host connected to joiner via TCP SimOpen!");
                     RoomManager.RoomState st = this.roomManager.currentRoom.get();
                     if (st != null) {
                        this.connectViaBridge(st, result);
                     }
                  } else if (result.success) {
                     VoxLinkMod.LOGGER.info("[TcpSimOpen] TCP SimOpen success but connection occupied, ignore");
                  } else {
                     VoxLinkMod.LOGGER.info("[TcpSimOpen] Host TCP SimOpen failed: {}", result.failureReason);
                  }
               });
            }
         }
      }
   }

   public void handleHolePunchAnswer(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state != null && state != RoomManager.PENDING && state.roomInfo.isHost()) {
         VoxLinkMod.LOGGER.info("Received punch ack from joiner {}", from);
      }
   }

   public int getEffectiveMaxCycles() {
      if (this.stunProbeResult != null) {
         int reachable = this.stunProbeResult.reachableStunUrls.size();
         return this.stunProbeResult.natType.isSymmetric()
            ? Math.max(2, Math.min(reachable * 2 / 2, this.punchProfile().maxSymCycles))
            : Math.max(1, Math.min(reachable, this.punchProfile().maxCycles));
      } else {
         return this.punchProfile().fallbackCycles;
      }
   }

   private static boolean sameIpFamily(String a, String b) {
      if (a == null || b == null) {
         return false;
      } else {
         return a.indexOf(58) >= 0 == b.indexOf(58) >= 0;
      }
   }

   private NatClass classifyLocalNat() {
      return this.stunProbeResult != null && this.stunProbeResult.natType != null
         ? NatClass.fromStunProbeResult(this.stunProbeResult.natType)
         : NatClass.UNKNOWN;
   }

   private NatClass classifyRemoteNat(RoomManager.RoomState state) {
      if (state != null && state.roomInfo != null) {
         boolean hostSym = state.roomInfo.isHostSymmetric();
         boolean hostEasySym = state.roomInfo.isHostEasySym();
         if (hostSym) {
            return hostEasySym ? NatClass.EASY_SYM : NatClass.HARD_SYM;
         } else {
            return NatClass.UNKNOWN;
         }
      } else {
         return NatClass.UNKNOWN;
      }
   }

   private boolean shouldPrefetchRelay(NatClass local, NatClass remote) {
      return local.isSymmetric() || remote.isSymmetric();
   }

   private CompletableFuture<Void> prefetchRelayCandidates(RoomManager.RoomState state) {
      if (state != null && state.roomInfo != null) {
         String code = state.roomInfo.getCode();
         String token = state.roomInfo.getToken();
         return code != null && token != null
            ? this.signalingClient
               .pollTopology(code, token, false, 0)
               .thenAccept(resp -> VoxLinkMod.LOGGER.info("[Connection] Layer2 relay pre-check topology done"))
               .exceptionally(e -> {
                  VoxLinkMod.LOGGER.warn("[Connection] Layer2 relay pre-check topology failed: {}", e.getMessage());
                  return null;
               })
            : CompletableFuture.completedFuture(null);
      } else {
         return CompletableFuture.completedFuture(null);
      }
   }

   private boolean isLegacyPeer() {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      return state != null && state.roomInfo != null ? !state.roomInfo.hostSupportsRelay() : false;
   }

   public void runConnectionCycle(
      RoomManager.RoomState state, String from, String hostIpv6, String hostIp, int hostPort, String hostMappedIp, int hostMappedPort, int cycle
   ) {
      this.savedConnectionState = state;
      this.savedConnectionFrom = from != null ? from : "";
      this.savedConnectionHostIpv6 = hostIpv6;
      this.savedConnectionHostIp = hostIp;
      this.savedConnectionHostPort = hostPort;
      this.savedConnectionHostMappedIp = hostMappedIp;
      this.savedConnectionHostMappedPort = hostMappedPort;
      if (this.connectionWon.get()) {
         VoxLinkMod.LOGGER.info("[Connection] Connected, skip cycle {}", cycle + 1);
      } else {
         int maxCycles = this.getEffectiveMaxCycles();
         if (cycle >= maxCycles) {
            if (!this.connectionWon.get()) {
               if (this.shouldContinuousRetry(state)) {
                  int round = this.continuousRetryRound.incrementAndGet();
                  this.escalateProfileForRound(round);
                  VoxLinkMod.LOGGER
                     .info(
                        "[Connection] Cycle {} done, both support persistent retry (round={}, level={}), reset cycle from 0",
                        new Object[]{maxCycles, round, this.punchProfile().describeInstance()}
                     );
                  ConnectionState.transitionTo(ConnectionState.STUN_PROBE, "持续重试 round " + round);
                  this.connectionStartTimeMs = System.currentTimeMillis();
                  if (this.connectionTimeoutFuture != null) {
                     this.connectionTimeoutFuture.cancel(false);
                     this.connectionTimeoutFuture = null;
                  }

                  state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.retry_round", new Object[]{round}));
                  this.tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, 0, 1, maxCycles, 0);
               } else {
                  ConnectionState.transitionTo(ConnectionState.FAILED, "超过最大周期" + maxCycles);
                  this.showConnectFailed(state, "voxlink.connection.max_cycles_exceeded");
               }
            }
         } else {
            this.connectionWon.set(false);
            ConnectionState.transitionTo(ConnectionState.STUN_PROBE, "周期" + (cycle + 1) + "/" + maxCycles);
            if (cycle == 0) {
               this.activePunchParams = null;
               this.localNatClass = this.classifyLocalNat();
               this.remoteNatClass = this.classifyRemoteNat(state);
               PunchProfile recommended;
               int tierReach = this.stunProbeResult != null ? this.stunProbeResult.reachableStunUrls.size() : 0;
               boolean tierLowReach = tierReach > 0 && tierReach <= 3;
               boolean tierLocalSym = this.localNatClass.isSymmetric();
               boolean tierRemoteSym = this.remoteNatClass.isSymmetric() || state.roomInfo.isHostSymmetric();
               this.scenarioTier = ScenarioTier.classify(tierLocalSym, tierRemoteSym, tierLowReach, false, false);
               recommended = NatClass.recommendProfile(this.localNatClass, this.remoteNatClass, this.scenarioTier);
               this.switchPunchProfile(recommended, "nat_matrix_" + this.localNatClass + "x" + this.remoteNatClass);
               VoxLinkMod.LOGGER
                  .info(
                     "[Connection] Layer1 NAT classification: local={} remote={} tier={} -> profile={}",
                     new Object[]{this.localNatClass, this.remoteNatClass, ScenarioTier.key(this.scenarioTier), this.punchProfile().describeInstance()}
                  );
               if (this.shouldPrefetchRelay(this.localNatClass, this.remoteNatClass) && this.relayPrefetchFuture == null) {
                  this.relayPrefetchFuture = this.prefetchRelayCandidates(state).exceptionally(e -> {
                     VoxLinkMod.LOGGER.warn("[Connection] Layer2 relay pre-check failed: {}", e.getMessage());
                     return null;
                  });
               }

               this.connectionStartTimeMs = System.currentTimeMillis();
               int timeoutSec = this.punchProfile().connectionTimeoutSec;
               boolean joinerSym = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric();
               boolean hostSym = state.roomInfo.isHostSymmetric();
               if (joinerSym || hostSym) {
                  timeoutSec = this.punchProfile().symmetricConnectionTimeoutSec;
                  VoxLinkMod.LOGGER
                     .info(
                        "[Connection] One side symmetric NAT (joinerSym={}, hostSym={}), global timeout extended to {}s",
                        new Object[]{joinerSym, hostSym, timeoutSec}
                     );
               }

               this.connectionTimeoutSec = timeoutSec;
               if (this.continuousRetryRound.get() == 0) {
                  this.scheduleConnectionTimeout(state, timeoutSec);
               }
            }

            int displayCycle = cycle + 1;
            if (cycle == 0 && this.stunProbeResult == null) {
               state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.probing"));
               RoomManager.RoomState probingState = state;
               this.scheduler.schedule(() -> {
                  if (this.roomManager.currentRoom.get() == probingState && !this.connectionWon.get()) {
                     Component current = probingState.roomInfo.getConnectionMode();
                     if (current != null && current.getString().equals(Component.translatable("voxlink.connection.probing").getString())) {
                        probingState.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.punching"));
                     }
                  }
               }, 15L, TimeUnit.SECONDS);
            } else {
               state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.punching"));
            }

            this.tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 0);
         }
      }
   }

   private void scheduleConnectionTimeout(RoomManager.RoomState state, int timeoutSec) {
      if (this.connectionTimeoutFuture != null) {
         this.connectionTimeoutFuture.cancel(false);
      }

      int finalTimeoutSec = timeoutSec;
      this.connectionTimeoutFuture = this.scheduler.schedule(() -> {
         if (!this.connectionWon.get()) {
            if (this.connectionCycleActive.get() && this.roomManager.currentRoom.get() == state) {
               VoxLinkMod.LOGGER.warn("[Connection] Global timeout ({}s), enter failure handling (persistent retry/relay/final)", finalTimeoutSec);
               this.showConnectFailed(state, "voxlink.connection.global_timeout");
            }
         }
      }, finalTimeoutSec, TimeUnit.SECONDS);
   }

   private void extendConnectionTimeoutIfNeeded(RoomManager.RoomState state) {
      if (this.connectionStartTimeMs != 0L && this.stunProbeResult != null) {
         boolean localSym = this.stunProbeResult.natType.isSymmetric();
         boolean hostSym = state.roomInfo.isHostSymmetric();
         if (localSym || hostSym) {
            if (this.connectionTimeoutSec < this.punchProfile().symmetricConnectionTimeoutSec) {
               long elapsedMs = System.currentTimeMillis() - this.connectionStartTimeMs;
               long remainingMs = this.punchProfile().symmetricConnectionTimeoutSec * 1000L - elapsedMs;
               if (remainingMs > 0L) {
                  this.connectionTimeoutSec = this.punchProfile().symmetricConnectionTimeoutSec;
                  VoxLinkMod.LOGGER
                     .info("[Connection] NAT probe found symmetric NAT (local or remote), global timeout extended to {}s ({}ms left)", 75, remainingMs);
                  this.scheduleConnectionTimeout(state, (int)(remainingMs / 1000L) + 1);
               }
            }
         }
      }
   }

   public void tryConnectionStep(
      RoomManager.RoomState state,
      String from,
      String hostIpv6,
      String hostIp,
      int hostPort,
      String hostMappedIp,
      int hostMappedPort,
      int cycle,
      int displayCycle,
      int maxCycles,
      int step
   ) {
      if (this.roomManager.currentRoom.get() == state) {
         if (this.connectionWon.get()) {
            VoxLinkMod.LOGGER.info("[Connection] Connected, skip Wave step (cycle={}, step={})", cycle + 1, step);
         } else {
            switch (step) {
               case 0:
                  VoxLinkMod.LOGGER.info("[Connection] Wave 1: LAN+IPv6+UDP parallel (cycle {}/{})", displayCycle, maxCycles);
                  state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.punching"));
                  AtomicBoolean wave1Settled = new AtomicBoolean(false);
                  List<CompletableFuture<?>> wave1Futures = new ArrayList<>();
                  String hostLocalIp = state.roomInfo.getHostLocalIp();
                  boolean sameLan = hostLocalIp != null && !hostLocalIp.isEmpty() && StunDetector.isSameLan(hostLocalIp);
                  boolean sameCgnat = state.roomInfo.isSameCgnat();
                  if (hostLocalIp != null && !hostLocalIp.isEmpty() && (sameLan || sameCgnat)) {
                     String reason = sameLan ? "LAN" : "CGNAT同公网IP";
                     VoxLinkMod.LOGGER.info("[Connection] Wave 1: Detected {} (localIp={}), try direct connect", reason, hostLocalIp);
                     ConnectionFallback lanFallback = this.trackFallback(new ConnectionFallback());
                     wave1Futures.add(lanFallback.tryIpv4Direct(hostLocalIp, hostPort).thenAccept(result -> {
                        if (this.roomManager.currentRoom.get() == state && result.success && this.connectionWon.compareAndSet(false, true)) {
                           VoxLinkMod.LOGGER.info("[Connection] Wave 1: {} direct connect won", reason);
                           wave1Settled.set(true);
                           this.connectViaBridge(state, result);
                        } else if (this.roomManager.currentRoom.get() == state && result != null && !result.success) {
                           this.addressBlacklist.recordDirectFailure(new InetSocketAddress(hostLocalIp, hostPort));
                        }
                     }));
                     if (sameCgnat && !sameLan) {
                        int mcPort = state.roomInfo.getHostPort();
                        VoxLinkMod.LOGGER.info("[Connection] Wave 1: CGNAT also try localIp MC port {}:{}", hostLocalIp, mcPort);
                        ConnectionFallback mcFallback = this.trackFallback(new ConnectionFallback());
                        wave1Futures.add(mcFallback.tryIpv4Direct(hostLocalIp, mcPort).thenAccept(result -> {
                           if (this.roomManager.currentRoom.get() == state && result.success && this.connectionWon.compareAndSet(false, true)) {
                              VoxLinkMod.LOGGER.info("[Connection] Wave 1: CGNAT localIp MC port won");
                              wave1Settled.set(true);
                              this.connectViaBridge(state, result);
                           }
                        }));
                     }
                  }

                  if (hostIpv6 != null && !hostIpv6.isEmpty() && StunDetector.verifyIPv6Connectivity()) {
                     VoxLinkMod.LOGGER.info("[Connection] Wave 1: Parallel try IPv6 direct connect");
                     ConnectionFallback ipv6Fallback = this.trackFallback(new ConnectionFallback());
                     wave1Futures.add(ipv6Fallback.tryIpv6Direct(hostIpv6, hostPort).thenAccept(result -> {
                        if (this.roomManager.currentRoom.get() == state && result.success && this.connectionWon.compareAndSet(false, true)) {
                           VoxLinkMod.LOGGER.info("[Connection] Wave 1: IPv6 direct connect won");
                           wave1Settled.set(true);
                           this.connectViaBridge(state, result);
                        }
                     }));
                  } else if (hostIpv6 != null && !hostIpv6.isEmpty()) {
                     VoxLinkMod.LOGGER.info("[Connection] Wave 1: Skip IPv6 (no local IPv6 connectivity)");
                  }

                  PunchStrategy strategy = PunchStrategySelector.select(this.localNatClass, this.remoteNatClass, cycle, this.isLegacyPeer());
                  VoxLinkMod.LOGGER
                     .info(
                        "[Connection] Layer4 smart schedule cycle={} strategy={} localNat={} remoteNat={}",
                        new Object[]{cycle, strategy, this.localNatClass, this.remoteNatClass}
                     );
                  if (this.punchSkipDirect()) {
                     VoxLinkMod.LOGGER.info("[Connection] Wave 1: Skip UDP punch (tuner FIREWALL_DETECTED)");
                  } else {
                     switch (strategy) {
                        case DIRECT_ONLY:
                           VoxLinkMod.LOGGER.info("[Connection] Wave 1: Try UDP punch (DIRECT_ONLY)");
                           this.tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles);
                           // 1.0.0兼容: cycle 0 即并行启动反向打洞。锥形joiner的reverse socket是
                           // 对称host场景的赢面主路径(1.0.0秒进实证), 拖到cycle>=1时host对称NAT
                           // 端口池已漂移(7CSAJA: 25271→25526), ±100窗口必然脱靶且CGNAT已限流
                           if (cycle == 0 && this.reversePunchAttempted.compareAndSet(false, true)) {
                              VoxLinkMod.LOGGER.info("[Connection] Wave 1: cycle0 parallel reverse punch (1.0.0 compat)");
                              this.startReversePunch(state);
                           }
                           break;
                        case DIRECT_WITH_REVERSE_PARALLEL:
                           VoxLinkMod.LOGGER.info("[Connection] Wave 1: Try UDP punch (DIRECT_WITH_REVERSE_PARALLEL cycle={})", cycle);
                           this.tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles);
                           if (cycle >= 1 && this.reversePunchAttempted.compareAndSet(false, true)) {
                              VoxLinkMod.LOGGER.info("[Connection] Wave 1: cycle{} start reverse punch (parallel)", cycle);
                              this.startReversePunch(state);
                           }
                           break;
                        case REVERSE_FIRST:
                           if (cycle == 0 && this.reversePunchAttempted.compareAndSet(false, true)) {
                              VoxLinkMod.LOGGER.info("[Connection] Wave 1: REVERSE_FIRST start reverse punch first");
                              this.startReversePunch(state);
                           }

                           VoxLinkMod.LOGGER.info("[Connection] Wave 1: Try UDP punch (REVERSE_FIRST cycle={})", cycle);
                           this.tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles);
                           break;
                        case RELAY_FALLBACK_FAST:
                           if (cycle < 2) {
                              VoxLinkMod.LOGGER.info("[Connection] Wave 1: Try UDP punch (RELAY_FALLBACK_FAST cycle={})", cycle);
                              this.tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles);
                              if (cycle >= 1 && this.reversePunchAttempted.compareAndSet(false, true)) {
                                 VoxLinkMod.LOGGER.info("[Connection] Wave 1: RELAY_FALLBACK_FAST cycle{} start reverse birthday", cycle);
                                 this.startReversePunch(state);
                              }
                           } else {
                              VoxLinkMod.LOGGER.info("[Connection] Layer4 RELAY_FALLBACK_FAST cycle{}>=2 keep punch (relay manual only)", cycle);
                              if (this.reversePunchAttempted.compareAndSet(false, true)) {
                                 this.startReversePunch(state);
                              }

                              this.tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles);
                           }
                           break;
                        case REVERSE_ONLY:
                           if (this.reversePunchAttempted.compareAndSet(false, true)) {
                              VoxLinkMod.LOGGER.info("[Connection] Wave 1: REVERSE_ONLY start reverse punch (skip forward)");
                              this.startReversePunch(state);
                           }
                           break;
                        case PARALLEL_FROM_START:
                           VoxLinkMod.LOGGER.info("[Connection] Wave 1: PARALLEL_FROM_START forward+reverse (cycle={})", cycle);
                           if (this.reversePunchAttempted.compareAndSet(false, true)) {
                              VoxLinkMod.LOGGER.info("[Connection] Wave 1: PARALLEL_FROM_START start reverse punch");
                              this.startReversePunch(state);
                           }

                           this.tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles);
                           break;
                        case REVERSE_THEN_FORWARD:
                           if (this.reversePunchAttempted.compareAndSet(false, true)) {
                              VoxLinkMod.LOGGER.info("[Connection] Wave 1: REVERSE_THEN_FORWARD start reverse punch");
                              this.startReversePunch(state);
                           }

                           VoxLinkMod.LOGGER.info("[Connection] Wave 1: REVERSE_THEN_FORWARD forward+reverse (cycle={})", cycle);
                           this.tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles);
                     }
                  }

                  if (!wave1Futures.isEmpty()) {
                     CompletableFuture.allOf(wave1Futures.toArray(new CompletableFuture[0])).thenAccept(v -> {
                        if (this.roomManager.currentRoom.get() == state) {
                           if (!wave1Settled.get()) {
                              VoxLinkMod.LOGGER.info("[Connection] Wave 1 TCP all failed, enter Wave 2");
                              this.tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
                           }
                        }
                     });
                  } else {
                     this.scheduler.schedule(() -> {
                        if (this.roomManager.currentRoom.get() == state && this.connectionCycleActive.get()) {
                           this.tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
                        }
                     }, 5L, TimeUnit.SECONDS);
                  }

                  return;
               case 1:
                  VoxLinkMod.LOGGER.info("[Connection] Wave 2: TCP fallback parallel (cycle{}/{})", displayCycle, maxCycles);
                  ConnectionState.transitionTo(ConnectionState.TCP_FALLBACK, "Wave 2 TCP兜底");
                  state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.punching"));
                  new AtomicBoolean(false);
                  List<CompletableFuture<ConnectionFallback.ConnectResult>> wave2Futures = new ArrayList<>();
                  if (hostMappedIp != null && !hostMappedIp.isEmpty() && hostMappedPort > 0) {
                     ConnectionFallback tcpSimFallback = this.trackFallback(new ConnectionFallback());
                     int simLocalPort = P2PBridge.getHostPort() > 0 ? P2PBridge.getHostPort() : hostPort;
                     String myMappedIp = state.roomInfo.getMyMappedIp();
                     int myMappedPort = state.roomInfo.getMyMappedPort();
                     if (myMappedIp != null && myMappedPort > 0 && this.signalingClient != null) {
                        JsonObject simReq = new JsonObject();
                        simReq.addProperty("joinerMappedIp", myMappedIp);
                        simReq.addProperty("joinerMappedPort", myMappedPort);
                        this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "tcp_simopen_request", simReq, "host");
                        VoxLinkMod.LOGGER.info("[Connection] Wave 2: Send tcp_simopen_request to host ({}:{})", myMappedIp, myMappedPort);
                     }

                     int tcpTargetPort = hostPort > 0 ? hostPort : hostMappedPort;
                     wave2Futures.add(tcpSimFallback.tryTcpSimultaneousOpen(hostMappedIp, tcpTargetPort, simLocalPort));
                  }

                  if (hostMappedIp != null && !hostMappedIp.isEmpty() && hostMappedPort > 0) {
                     ConnectionFallback tcpMappedFallback = this.trackFallback(new ConnectionFallback());
                     int tcpDirectPort = hostPort > 0 ? hostPort : hostMappedPort;
                     wave2Futures.add(tcpMappedFallback.tryIpv4Direct(hostMappedIp, tcpDirectPort));
                  }

                  if (hostIp != null && !hostIp.isEmpty()) {
                     ConnectionFallback ipv4Fallback = this.trackFallback(new ConnectionFallback());
                     String fDirectIp = hostIp;
                     int fDirectPort = hostPort;
                     wave2Futures.add(ipv4Fallback.tryIpv4Direct(hostIp, hostPort).whenComplete((result, ex) -> {
                        if (ex == null && result != null && !result.success && this.roomManager.currentRoom.get() == state) {
                           this.addressBlacklist.recordDirectFailure(new InetSocketAddress(fDirectIp, fDirectPort));
                        }
                     }));
                  }

                  if (state.roomInfo.isSameCgnat()) {
                     String hostLocalIp2 = state.roomInfo.getHostLocalIp();
                     if (hostLocalIp2 != null && !hostLocalIp2.isEmpty()) {
                        VoxLinkMod.LOGGER.info("[Connection] Wave 2: CGNAT scenario try hostLocalIp {}:{}", hostLocalIp2, hostPort);
                        ConnectionFallback localFallback = this.trackFallback(new ConnectionFallback());
                        wave2Futures.add(localFallback.tryIpv4Direct(hostLocalIp2, hostPort));
                        int mcPort = state.roomInfo.getHostPort();
                        VoxLinkMod.LOGGER.info("[Connection] Wave 2: CGNAT scenario try localIp MC port {}:{}", hostLocalIp2, mcPort);
                        ConnectionFallback mcLocalFallback = this.trackFallback(new ConnectionFallback());
                        wave2Futures.add(mcLocalFallback.tryIpv4Direct(hostLocalIp2, mcPort));
                     }
                  }

                  if (hostIpv6 != null && !hostIpv6.isEmpty() && StunDetector.verifyIPv6Connectivity()) {
                     VoxLinkMod.LOGGER.info("[Connection] Wave 2: Try IPv6 direct connection");
                     ConnectionFallback ipv6Fallback2 = this.trackFallback(new ConnectionFallback());
                     wave2Futures.add(ipv6Fallback2.tryIpv6Direct(hostIpv6, hostPort));
                  }

                  if (wave2Futures.isEmpty()) {
                     VoxLinkMod.LOGGER.info("[Connection] Wave 2: No TCP fallback available, enter next cycle");
                     this.advanceToNextCycle(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, maxCycles);
                     return;
                  }

                  for (CompletableFuture<ConnectionFallback.ConnectResult> future : wave2Futures) {
                     future.thenAccept(result -> {
                        if (this.roomManager.currentRoom.get() == state && result.success && this.connectionWon.compareAndSet(false, true)) {
                           VoxLinkMod.LOGGER.info("[Connection] Wave 2: {} won", result.errorCode);
                           this.connectViaBridge(state, result);
                        }
                     });
                  }

                  CompletableFuture.allOf(wave2Futures.toArray(new CompletableFuture[0])).thenAccept(v -> {
                     if (this.roomManager.currentRoom.get() == state) {
                        if (!this.connectionWon.get()) {
                           VoxLinkMod.LOGGER.info("[Connection] Wave 2: All TCP fallbacks failed, enter next cycle");
                           this.advanceToNextCycle(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, maxCycles);
                        }
                     }
                  });
                  return;
               default:
                  this.advanceToNextCycle(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, maxCycles);
            }
         }
      }
   }

   public void tryUdpPunch(
      RoomManager.RoomState state,
      String from,
      String hostIpv6,
      String hostIp,
      int hostPort,
      String hostMappedIp,
      int hostMappedPort,
      int cycle,
      int displayCycle,
      int maxCycles
   ) {
      this.tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
   }

   public void tryUdpPunch(
      RoomManager.RoomState state,
      String from,
      String hostIpv6,
      String hostIp,
      int hostPort,
      String hostMappedIp,
      int hostMappedPort,
      int cycle,
      int displayCycle,
      int maxCycles,
      int attempt
   ) {
      if (this.connectionWon.get()) {
         VoxLinkMod.LOGGER.info("[Connection] Already connected, skip UDP punch (cycle={}, attempt={})", cycle + 1, attempt);
      } else {
         state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connecting"));
         VoxLinkMod.LOGGER.info("[Connection] UDP punch cycle{}/{}, attempt{}/{}", new Object[]{displayCycle, maxCycles, attempt, 3});
         boolean joinerSym = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric();
         boolean joinerHardSym = this.stunProbeResult != null && this.stunProbeResult.natType.isHardSymmetric();
         boolean hostSym = state.roomInfo.isHostSymmetric();
         boolean hostHardSym = hostSym && !state.roomInfo.isHostEasySym();
         if (joinerSym && hostSym) {
            if (!joinerHardSym && !hostHardSym) {
               VoxLinkMod.LOGGER.info("[Connection] Both EasySym (port predictable), continue UDP punch (EasyTier both_easy_sym)");
            } else {
               VoxLinkMod.LOGGER
                  .info(
                     "[Connection] Both symmetric NAT with HardSym (joinerHard={}, hostHard={}), try Birthday Attack+port prediction first, fallback to Relay on failure",
                     joinerHardSym,
                     hostHardSym
                  );
            }
         }

         UdpHolePuncher prev = this.activeHolePunchers.get("joiner");
         UdpHolePuncher puncher = this.activeHolePunchers.get("joiner_reuse");
         if (puncher != null && puncher.getSocket() != null && !puncher.getSocket().isClosed()) {
            this.activeHolePunchers.put("joiner", puncher);
            VoxLinkMod.LOGGER.info("[Connection] Reuse punch socket (port={})", puncher.getSocket().getLocalPort());
         } else {
            if (prev != null) {
               try {
                  prev.close();
               } catch (Exception var53) {
               }
            }

            this.activeHolePunchers.remove("joiner_reuse");
            puncher = new UdpHolePuncher();
            this.applyPunchTemplate(puncher);

            try {
               puncher.createSocket();
            } catch (Exception e) {
               VoxLinkMod.LOGGER.error("[Connection] Failed to create UDP punch socket: {}", e.getMessage());
               this.tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
               return;
            }

            int upnpLocal = puncher.getSocket().getLocalPort();
            this.scheduler.execute(() -> {
               try {
                  if (UPnPManager.addPortMapping(upnpLocal)) {
                     VoxLinkMod.LOGGER.info("UPnP mapping success {} -> {}", upnpLocal, upnpLocal);
                  }
               } catch (Exception e) {
                  VoxLinkMod.LOGGER.warn("UPnP mapping failed, fallback to original punch flow: {}", e.getMessage());
               }
            });

            this.activeHolePunchers.put("joiner", puncher);
            this.activeHolePunchers.put("joiner_reuse", puncher);
         }

         StunProbe.PublicMappedAddress myMappedAddr = null;
         boolean joinerPunchSocketSymmetric = false;
         int joinerMappedPortDelta = 0;
         List<String> quadStun = StunDetector.getAllStunUrls();
         StunProbe.PublicMappedAddress[] quadResult = StunProbe.discoverMappedAddressQuad(
            puncher.getSocket(), quadStun.get(0), quadStun.get(1), quadStun.get(2), quadStun.get(3)
         );
         StunProbe.PublicMappedAddress myMapped1 = quadResult[0] != null ? quadResult[0] : (quadResult[2] != null ? quadResult[2] : quadResult[3]);
         StunProbe.PublicMappedAddress myMapped2 = quadResult[1] != null ? quadResult[1] : (quadResult[3] != null ? quadResult[3] : quadResult[2]);
         if (myMapped1 != null && myMapped2 != null) {
            if (sameIpFamily(myMapped1.ip(), myMapped2.ip()) && myMapped1.port() != myMapped2.port()) {
               joinerPunchSocketSymmetric = true;
               joinerMappedPortDelta = myMapped2.port() - myMapped1.port();
               VoxLinkMod.LOGGER
                  .info(
                     "[Connection] Joiner punch socket STUN: symmetric NAT ({} vs {}, delta={})",
                     new Object[]{myMapped1.port(), myMapped2.port(), joinerMappedPortDelta}
                  );
            }

            myMappedAddr = myMapped2;
         } else {
            myMappedAddr = myMapped1 != null ? myMapped1 : myMapped2;
         }

         if (joinerPunchSocketSymmetric && this.localNatClass != NatClass.HARD_SYM && this.localNatClass != NatClass.EASY_SYM) {
            NatClass newLocal = this.stunProbeResult != null && this.stunProbeResult.natType.isEasySymmetric() ? NatClass.EASY_SYM : NatClass.HARD_SYM;
            VoxLinkMod.LOGGER.info("[Connection] Update localNat {} -> {} (punch socket symmetric detected)", this.localNatClass, newLocal);
            this.localNatClass = newLocal;
            this.remoteNatClass = this.classifyRemoteNat(state);
            PunchProfile recommended = NatClass.recommendProfile(this.localNatClass, this.remoteNatClass, this.scenarioTier);
            this.switchPunchProfile(recommended, "punch_socket_sym_" + this.localNatClass + "x" + this.remoteNatClass);
            VoxLinkMod.LOGGER
               .info("[Connection] Probe upgrade: local={} remote={} -> profile={}",
                  new Object[]{this.localNatClass, this.remoteNatClass, this.punchProfile().describeInstance()});
         }

         if (myMappedAddr == null) {
            myMappedAddr = puncher.discoverMappedAddress(StunDetector.getAllStunUrls());
         }

         if (myMappedAddr == null) {
            VoxLinkMod.LOGGER.warn("[Connection] Punch socket STUN failed, try temp socket fallback (attempt{})", attempt);
            DatagramSocket tmp = null;

            try {
               tmp = new DatagramSocket();
               tmp.setSoTimeout(1000);
               myMappedAddr = StunProbe.discoverMappedAddress(tmp, StunDetector.getAllStunUrls());
            } catch (Exception e) {
               VoxLinkMod.LOGGER.warn("[Connection] Temp socket STUN also failed: {}", e.getMessage());
            } finally {
               if (tmp != null && !tmp.isClosed()) {
                  tmp.close();
               }
            }
         }

         if (myMappedAddr != null) {
            VoxLinkMod.LOGGER.info("[Connection] My mapped address: {}:{} (attempt{})", new Object[]{myMappedAddr.ip(), myMappedAddr.port(), attempt});
            if (state.roomInfo.getClientId() != null && attempt == 1) {
               String myNatType = this.stunProbeResult != null ? this.stunProbeResult.natType.key : "unknown";
               boolean relayOk = VoxLinkMod.getConfig().isRelayEnabled();
               this.signalingClient
                  .registerRelayPeer(state.roomInfo.getClientId(), state.roomInfo.getCode(), myNatType, myMappedAddr.ip(), myMappedAddr.port(), relayOk);
               this.scheduleRelayRegistrationRenewal(state, myNatType, myMappedAddr.ip(), myMappedAddr.port());
            }

            JsonObject punchData = new JsonObject();
            punchData.addProperty("joinerMappedIp", myMappedAddr.ip());
            punchData.addProperty("joinerMappedPort", myMappedAddr.port());
            boolean joinerSymmetric = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric() || joinerPunchSocketSymmetric;
            if (joinerSymmetric) {
               punchData.addProperty("joinerSymmetric", true);
            }

            boolean joinerEasySym = this.stunProbeResult != null && this.stunProbeResult.natType.isEasySymmetric() || joinerPunchSocketSymmetric;
            if (joinerEasySym) {
               punchData.addProperty("joinerEasySym", true);
            }

            if (joinerMappedPortDelta != 0) {
               punchData.addProperty("joinerMappedPortDelta", joinerMappedPortDelta);
               VoxLinkMod.LOGGER.info("[Connection] punch_info with port offset: delta={}", joinerMappedPortDelta);
            }

            if (joinerPunchSocketSymmetric) {
               VoxLinkMod.LOGGER.info("[Connection] Override joinerSymmetric=true with punch socket dual STUN result");
            }

            String joinerLocalIp = StunDetector.getLocalIpAddress();
            if (joinerLocalIp != null && !joinerLocalIp.isEmpty()) {
               punchData.addProperty("joinerLocalIp", joinerLocalIp);
               VoxLinkMod.LOGGER.info("[Connection] punch_info includes joiner LAN IP: {}", joinerLocalIp);
            }

            if (state.roomInfo.isSameCgnat() && (state.roomInfo.getHostLocalIp() == null || state.roomInfo.getHostLocalIp().isEmpty())) {
               punchData.addProperty("requestHostLocalIp", true);
               VoxLinkMod.LOGGER.info("[Connection] CGNAT scenario request host to send LAN IP");
            }

            long joinerOfferRecvMs = state.roomInfo.getJoinerOfferRecvMs();
            if (joinerOfferRecvMs > 0L) {
               punchData.addProperty("joinerOfferRecvMs", joinerOfferRecvMs);
            }

            this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "punch_info", punchData, "host");
            if (this.connectionWon.get()) {
               VoxLinkMod.LOGGER.info("[Connection] Connection succeeded during STUN probe, abort attempt{}", attempt);
               puncher.close();
            } else {
               this.activeHolePunchers.put("joiner", puncher);
               puncher.setOnPeerPunchReceived(addr -> {
                  String code = state.roomInfo.getCode();
                  String token = state.roomInfo.getToken();
                  JsonObject portData = new JsonObject();
                  portData.addProperty("peer_ip", addr.getAddress().getHostAddress());
                  portData.addProperty("peer_port", addr.getPort());
                  this.signalingClient.sendSignal(code, token, false, "peer_port", portData, "host").exceptionally(e -> {
                     VoxLinkMod.LOGGER.debug("peer_port signal send failed: {}", e.getMessage());
                     return null;
                  });
               });
               String effectiveMappedIp = state.roomInfo.getHostMappedIp();
               int effectiveMappedPort = state.roomInfo.getHostMappedPort();
               if (effectiveMappedIp == null || effectiveMappedPort <= 0) {
                  effectiveMappedIp = hostMappedIp;
                  effectiveMappedPort = hostMappedPort;
               }

               String targetIp = effectiveMappedIp != null ? effectiveMappedIp : hostIp;
               int targetPort = effectiveMappedPort > 0 ? effectiveMappedPort : hostPort;
               int hostMappedPortDelta = state.roomInfo.getHostMappedPortDelta();
               if (state.roomInfo.isHostSymmetric() && state.roomInfo.getHostMappedIp() != null) {
                  VoxLinkMod.LOGGER
                     .info(
                        "[Connection] Punch target keeps host mapped port {} (delta={} skipped: 同socket双STUN差值非跨socket步长)",
                        new Object[]{targetPort, hostMappedPortDelta}
                     );
               }

               String fTargetIp = targetIp;
               int fTargetPort = targetPort;
               VoxLinkMod.LOGGER
                  .info(
                     "[Connection] UDP punch target: {}:{} (hostMappedIp={}, hostMappedPort={}, delta={}, hostIp={}, hostPort={}, attempt{})",
                     new Object[]{fTargetIp, fTargetPort, hostMappedIp, hostMappedPort, hostMappedPortDelta, hostIp, hostPort, attempt}
                  );
               ConnectionState.transitionTo(ConnectionState.UDP_PUNCH, "尝试" + attempt + "/3 目标" + fTargetIp + ":" + fTargetPort);
               if (targetIp != null && !targetIp.isEmpty()) {
                  UdpHolePuncher finalPuncher = puncher;
                  InetSocketAddress punchTargetAddr = new InetSocketAddress(fTargetIp, fTargetPort);
                  if (this.addressBlacklist.isBlacklisted(punchTargetAddr)) {
                     VoxLinkMod.LOGGER.info("[Connection] Target {}:{} in blacklist, skip UDP punch", fTargetIp, fTargetPort);
                     finalPuncher.close();
                     this.activeHolePunchers.remove("joiner");
                     this.tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
                  } else if (this.stunProbeResult != null && this.stunProbeResult.natType.isEasySymmetric() && state.roomInfo.isHostEasySym()) {
                     int dualSocketCount = this.continuousRetryRound.get() > 0 ? this.punchProfile().easySymMutualRetrySocketCount : this.punchProfile().easySymMutualSocketCount;
                     VoxLinkMod.LOGGER.info("Both EasySym, start mutual punch ({} socket x +/-20)", dualSocketCount);
                     UdpHolePuncher dualPuncher = finalPuncher;
                     dualPuncher.punchEasySymDual(fTargetIp, fTargetPort, this.stunProbeResult.natType, StunProbe.NatType.SYMMETRIC_EASY_INC, dualSocketCount)
                        .thenAccept(
                           result -> {
                              if (!result.isSuccess()) {
                                 PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                                 this.lastPunchResult = result.withReason(reason);
                                 VoxLinkMod.LOGGER
                                    .info(
                                       "[Connection] cycle={} EasySym mutual punch failed reason={} recvPunch={} recvAck={}",
                                       new Object[]{cycle, reason, result.socketsReceivedPunch, result.socketsReceivedAck}
                                    );
                                 this.activePunchParams = 
                                    PunchTuner.nextParams(
                                       this.punchProfile(), this.localNatClass, this.remoteNatClass, cycle + 1, maxCycles, reason, this.lastPunchResult
                                    )
                                 ;
                                 dualPuncher.stopPunch();
                                 if (this.roomManager.currentRoom.get() != state) {
                                    this.connectionCycleActive.set(false);
                                    ConnectionHelper.resetConnecting();
                                 } else {
                                    this.tryConnectionStep(
                                       state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1
                                    );
                                 }
                              } else {
                                 DatagramSocket socket = result.getSuccessSocket();
                                 if (this.roomManager.currentRoom.get() == state && this.connectionWon.compareAndSet(false, true)) {
                                    dualPuncher.markSocketTransferred();
                                    this.stopAllPunchingAfterHostBridge();
                                    dualPuncher.stopPunch();
                                    InetSocketAddress dualAddr = dualPuncher.getActualRemoteAddress();
                                    InetSocketAddress fallbackAddr = dualAddr != null ? dualAddr : punchTargetAddr;
                                    this.scheduler.submit(() -> {
                                       try {
                                          this.establishUdpTransport(state, socket, dualPuncher, fallbackAddr, "joiner", false, null);
                                       } catch (Exception e) {
                                          VoxLinkMod.LOGGER.error("[Connection] EasySym mutual punch transport failed: {}", e.getMessage());
                                          dualPuncher.close();
                                          this.showConnectFailed(state, "voxlink.connection.transport_failed");
                                       }
                                    });
                                 } else {
                                    try {
                                       dualPuncher.close();
                                    } catch (Exception var17x) {
                                    }
                                 }
                              }
                           }
                        )
                        .exceptionally(e -> {
                           VoxLinkMod.LOGGER.warn("[Connection] EasySym mutual punch failed: {}", e.getMessage());
                           dualPuncher.stopPunch();
                           if (this.roomManager.currentRoom.get() != state) {
                              this.connectionCycleActive.set(false);
                              ConnectionHelper.resetConnecting();
                              return null;
                           } else {
                              this.tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
                              return null;
                           }
                        });
                  } else {
                     boolean joinerIsSymmetric = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric() || joinerPunchSocketSymmetric;
                     boolean hostConfirmedNonSymmetric = !state.roomInfo.isHostSymmetric()
                        && (
                           "moderate".equals(state.roomInfo.getNatType())
                              || "port_restricted_cone".equals(state.roomInfo.getNatType())
                              || "restricted_cone".equals(state.roomInfo.getNatType())
                              || "full_cone".equals(state.roomInfo.getNatType())
                        );
                     int portRange = this.punchProfile().defaultPortRange;
                     int hostMappedPortRange = state.roomInfo.getHostMappedPortRange();
                     if (joinerIsSymmetric && hostConfirmedNonSymmetric) {
                        portRange = 0;
                     } else if (state.roomInfo.isHostSymmetric()) {
                        if (hostMappedPortDelta != 0) {
                           portRange = Math.max(hostMappedPortRange > 0 ? hostMappedPortRange : this.punchProfile().widePortRange, this.punchProfile().maxPortRange);
                        } else if (cycle == 0) {
                           portRange = this.punchProfile().maxPortRange;
                        } else {
                           portRange = this.punchProfile().portPredictionMaxRange;
                        }
                     } else if (cycle == 0) {
                        portRange = this.punchProfile().defaultPortRange;
                     } else if (cycle == 1) {
                        portRange = this.punchProfile().widePortRange;
                     } else {
                        portRange = this.punchProfile().maxPortRange;
                     }

                     VoxLinkMod.LOGGER
                        .info(
                           "[Connection] Punch mode: cycle={}, portRange={} (hostSym={}, joinerSym={})",
                           new Object[]{cycle, portRange, state.roomInfo.isHostSymmetric(), joinerIsSymmetric}
                        );
                     if (portRange > 0) {
                        VoxLinkMod.LOGGER
                           .info(
                              "[Connection] Port prediction (range=+/-{}) (attempt{}, hostSym={}, joinerSym={}, hostNat={})",
                              new Object[]{portRange, attempt, state.roomInfo.isHostSymmetric(), joinerIsSymmetric, state.roomInfo.getNatType()}
                           );
                     }

                     int socketCount = 0;
                     if (joinerIsSymmetric) {
                        socketCount = state.roomInfo.isHostSymmetric()
                           ? this.punchProfile().hardSymSocketCount
                           : this.punchProfile().joinerSymSocketCount;
                        VoxLinkMod.LOGGER.info("[Connection] Joiner symmetric NAT, create {} multi-socket punches", socketCount);
                     }

                     List<UdpHolePuncher> multiSockets = new ArrayList<>();
                     AtomicBoolean multiWon = new AtomicBoolean(false);

                     for (int si = 0; si < socketCount; si++) {
                        UdpHolePuncher sp = new UdpHolePuncher();
                        this.applyPunchTemplate(sp);

                        try {
                           sp.createSocket();
                        } catch (Exception e) {
                           continue;
                        }

                        sp.setOnPeerPunchReceived(addr -> {
                           String code = state.roomInfo.getCode();
                           String token = state.roomInfo.getToken();
                           JsonObject portData = new JsonObject();
                           portData.addProperty("peer_ip", addr.getAddress().getHostAddress());
                           portData.addProperty("peer_port", addr.getPort());
                           this.signalingClient.sendSignal(code, token, false, "peer_port", portData, "host").exceptionally(e -> {
                              VoxLinkMod.LOGGER.debug("peer_port signal send failed: {}", e.getMessage());
                              return null;
                           });
                        });
                        multiSockets.add(sp);
                        this.activeHolePunchers.put("joiner_ms_" + si, sp);
                     }

                     if (multiSockets.isEmpty()) {
                        VoxLinkMod.LOGGER.info("[Connection] Cone side reuse STUN socket punch (port={})", puncher.getSocket().getLocalPort());
                        puncher.setOnPeerPunchReceived(addr -> {
                           String code = state.roomInfo.getCode();
                           String token = state.roomInfo.getToken();
                           JsonObject portData = new JsonObject();
                           portData.addProperty("peer_ip", addr.getAddress().getHostAddress());
                           portData.addProperty("peer_port", addr.getPort());
                           this.signalingClient.sendSignal(code, token, false, "peer_port", portData, "host").exceptionally(e -> {
                              VoxLinkMod.LOGGER.debug("peer_port signal send failed: {}", e.getMessage());
                              return null;
                           });
                        });
                        puncher.punchWithPortPrediction(fTargetIp, fTargetPort, portRange)
                           .thenAccept(
                              result -> {
                                 if (!result.isSuccess()) {
                                    PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                                    this.lastPunchResult = result.withReason(reason);
                                    VoxLinkMod.LOGGER
                                       .info(
                                          "[Connection] cycle={} UDP punch failed (attempt{}/{}) reason={} recvPunch={} recvAck={}",
                                          new Object[]{cycle, attempt, 3, reason, result.socketsReceivedPunch, result.socketsReceivedAck}
                                       );
                                    this.activePunchParams = 
                                       PunchTuner.nextParams(
                                          this.punchProfile(), this.localNatClass, this.remoteNatClass, cycle + 1, maxCycles, reason, this.lastPunchResult
                                       )
                                    ;
                                    finalPuncher.stopPunch();
                                    if (this.roomManager.currentRoom.get() != state) {
                                       try {
                                          finalPuncher.close();
                                       } catch (Exception var23x) {
                                       }

                                       this.activeHolePunchers.remove("joiner");
                                       this.connectionCycleActive.set(false);
                                       ConnectionHelper.resetConnecting();
                                    } else if (this.activeHolePunchers.get("joiner") != finalPuncher) {
                                       VoxLinkMod.LOGGER.info("[Connection] Puncher replaced, no retry");
                                    } else {
                                       if (result.firewallDetected) {
                                          VoxLinkMod.LOGGER.warn("[Connection] Firewall blocked UDP, skip retry enter Wave 2 TCP fallback");

                                          try {
                                             finalPuncher.close();
                                          } catch (Exception var24x) {
                                          }

                                          this.activeHolePunchers.remove("joiner");
                                          this.tryConnectionStep(
                                             state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1
                                          );
                                       } else {
                                          if (attempt < this.punchProfile().punchMaxAttempts) {
                                             long delay = this.punchProfile().punchRetryDelayMs * (1L << Math.min(attempt - 1, 4));
                                             VoxLinkMod.LOGGER
                                                .info("[Connection] Retry UDP punch after {}ms (attempt {}/{})", new Object[]{delay, attempt + 1, 3});
                                             this.scheduler
                                                .schedule(
                                                   () -> {
                                                      if (this.roomManager.currentRoom.get() == state
                                                         && this.connectionCycleActive.get()
                                                         && this.activeHolePunchers.get("joiner") == finalPuncher) {
                                                         this.tryUdpPunch(
                                                            state,
                                                            from,
                                                            hostIpv6,
                                                            hostIp,
                                                            hostPort,
                                                            hostMappedIp,
                                                            hostMappedPort,
                                                            cycle,
                                                            displayCycle,
                                                            maxCycles,
                                                            attempt + 1
                                                         );
                                                      }
                                                   },
                                                   delay,
                                                   TimeUnit.MILLISECONDS
                                                );
                                          } else {
                                             this.tryConnectionStep(
                                                state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1
                                             );
                                          }
                                       }
                                    }
                                 } else {
                                    DatagramSocket socket = result.getSuccessSocket();
                                    if (this.roomManager.currentRoom.get() == state && this.connectionWon.compareAndSet(false, true)) {
                                       String logTarget = fTargetIp != null && fTargetIp.contains(":")
                                          ? "[" + fTargetIp + "]:" + fTargetPort
                                          : fTargetIp + ":" + fTargetPort;
                                       VoxLinkMod.LOGGER.info("[Connection] UDP punch success {} (attempt {})", logTarget, attempt);
                                       finalPuncher.markSocketTransferred();
                                       this.stopAllPunchingAfterHostBridge();
                                       finalPuncher.stopPunch();
                                       DatagramSocket punchSocket = socket;
                                       UdpHolePuncher puncherRef = finalPuncher;
                                       InetSocketAddress actualAddr = puncherRef.getActualRemoteAddress();
                                       if (actualAddr == null) {
                                          actualAddr = new InetSocketAddress(fTargetIp, fTargetPort);
                                       }

                                       InetSocketAddress finalTargetAddr = actualAddr;
                                       this.scheduler.submit(() -> {
                                          try {
                                             this.establishUdpTransport(state, punchSocket, puncherRef, finalTargetAddr, "joiner", false, null);
                                          } catch (Exception e) {
                                             VoxLinkMod.LOGGER.error("[Connection] Create UDP transport failed: {}", e.getMessage());

                                             try {
                                                puncherRef.close();
                                             } catch (Exception var7x) {
                                             }

                                             this.showConnectFailed(state, "voxlink.connection.transport_failed");
                                          }
                                       });
                                    } else {
                                       try {
                                          finalPuncher.close();
                                       } catch (Exception var25x) {
                                       }
                                    }
                                 }
                              }
                           )
                           .exceptionally(
                              e -> {
                                 VoxLinkMod.LOGGER
                                    .warn(
                                       "[Connection] UDP punch failed (cycle {}/{}, attempt {}/{}): {}",
                                       new Object[]{displayCycle, maxCycles, attempt, 3, e.getMessage()}
                                    );
                                 finalPuncher.stopPunch();
                                 if (this.roomManager.currentRoom.get() != state) {
                                    try {
                                       finalPuncher.close();
                                    } catch (Exception var17x) {
                                    }

                                    this.activeHolePunchers.remove("joiner");
                                    this.connectionCycleActive.set(false);
                                    ConnectionHelper.resetConnecting();
                                    return null;
                                 } else {
                                    if ("punch stopped".equals(e.getMessage())) {
                                       VoxLinkMod.LOGGER.info("[Connection] Punch actively stopped, no retry");
                                       return null;
                                    }

                                    if (this.activeHolePunchers.get("joiner") != finalPuncher) {
                                       VoxLinkMod.LOGGER.info("[Connection] Puncher replaced, no retry");
                                       return null;
                                    }

                                    if (!(e.getCause() instanceof FirewallBlockedException) && !(e instanceof FirewallBlockedException)) {
                                       if (attempt < this.punchProfile().punchMaxAttempts) {
                                          long delay = this.punchProfile().punchRetryDelayMs * (1L << Math.min(attempt - 1, 4));
                                          VoxLinkMod.LOGGER
                                             .info("[Connection] Retry UDP punch after {}ms (attempt {}/{})", new Object[]{delay, attempt + 1, 3});
                                          this.scheduler
                                             .schedule(
                                                () -> {
                                                   if (this.roomManager.currentRoom.get() == state
                                                      && this.connectionCycleActive.get()
                                                      && this.activeHolePunchers.get("joiner") == finalPuncher) {
                                                      this.tryUdpPunch(
                                                         state,
                                                         from,
                                                         hostIpv6,
                                                         hostIp,
                                                         hostPort,
                                                         hostMappedIp,
                                                         hostMappedPort,
                                                         cycle,
                                                         displayCycle,
                                                         maxCycles,
                                                         attempt + 1
                                                      );
                                                   }
                                                },
                                                delay,
                                                TimeUnit.MILLISECONDS
                                             );
                                       } else {
                                          this.tryConnectionStep(
                                             state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1
                                          );
                                       }

                                       return null;
                                    } else {
                                       VoxLinkMod.LOGGER.warn("[Connection] Firewall blocked UDP, skip retry enter Wave 2 TCP fallback");

                                       try {
                                          finalPuncher.close();
                                       } catch (Exception var18x) {
                                       }

                                       this.activeHolePunchers.remove("joiner");
                                       this.tryConnectionStep(
                                          state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1
                                       );
                                       return null;
                                    }
                                 }
                              }
                           );
                     } else {
                        VoxLinkMod.LOGGER
                           .info("[Connection] EasyTier method: {} sockets punch to {}:{}", new Object[]{multiSockets.size(), fTargetIp, fTargetPort});
                        UdpHolePuncher leadPuncher = multiSockets.get(0);
                        this.activeHolePunchers.put("joiner", leadPuncher);
                        leadPuncher.setSkipFirewallDetection(this.localNatClass.isSymmetric() || this.remoteNatClass.isSymmetric());
                        // 双对称: 对端(EASY侧)真实映射在其上报端口±窄带, 把84颗分布到带宽上扫(EASY×HARD破局点)
                        boolean joinDoubleSym = this.localNatClass.isSymmetric() && this.remoteNatClass.isSymmetric();
                        int joinSweep = joinDoubleSym ? this.punchProfile().joinerMultiPortRange : 0;
                        leadPuncher.punchMultiSocket(fTargetIp, fTargetPort, multiSockets, multiWon, joinSweep)
                           .thenAccept(
                              result -> {
                                 if (!result.isSuccess()) {
                                    PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                                    this.lastPunchResult = result.withReason(reason);
                                    VoxLinkMod.LOGGER
                                       .info(
                                          "[Connection] cycle={} multi-socket punch failed reason={} recvPunch={} recvAck={}",
                                          new Object[]{cycle, reason, result.socketsReceivedPunch, result.socketsReceivedAck}
                                       );
                                    this.activePunchParams = 
                                       PunchTuner.nextParams(
                                          this.punchProfile(), this.localNatClass, this.remoteNatClass, cycle + 1, maxCycles, reason, this.lastPunchResult
                                       )
                                    ;

                                    for (UdpHolePuncher spxxxx : multiSockets) {
                                       try {
                                          spxxxx.stopPunch();
                                       } catch (Exception var24x) {
                                       }
                                    }

                                    for (UdpHolePuncher spxx : multiSockets) {
                                       try {
                                          spxx.close();
                                       } catch (Exception var23x) {
                                       }
                                    }

                                    for (int si = 0; si < multiSockets.size(); si++) {
                                       this.activeHolePunchers.remove("joiner_ms_" + si);
                                    }

                                    this.activeHolePunchers.remove("joiner");
                                    if (this.roomManager.currentRoom.get() != state) {
                                       this.connectionCycleActive.set(false);
                                       ConnectionHelper.resetConnecting();
                                    } else if (result.firewallDetected) {
                                       VoxLinkMod.LOGGER.warn("[Connection] Firewall blocked UDP (multi-socket), skip retry enter Wave 2 TCP fallback");
                                       this.tryConnectionStep(
                                          state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1
                                       );
                                    } else {
                                       if (attempt < this.punchProfile().punchMaxAttempts) {
                                          long delay = this.punchProfile().punchRetryDelayMs * (1L << Math.min(attempt - 1, 4));
                                          VoxLinkMod.LOGGER
                                             .info("[Connection] Retry UDP punch after {}ms (attempt {}/{})", new Object[]{delay, attempt + 1, 3});
                                          this.scheduler
                                             .schedule(
                                                () -> {
                                                   if (this.roomManager.currentRoom.get() == state && this.connectionCycleActive.get()) {
                                                      this.tryUdpPunch(
                                                         state,
                                                         from,
                                                         hostIpv6,
                                                         hostIp,
                                                         hostPort,
                                                         hostMappedIp,
                                                         hostMappedPort,
                                                         cycle,
                                                         displayCycle,
                                                         maxCycles,
                                                         attempt + 1
                                                      );
                                                   }
                                                },
                                                delay,
                                                TimeUnit.MILLISECONDS
                                             );
                                       } else {
                                          this.tryConnectionStep(
                                             state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1
                                          );
                                       }
                                    }
                                 } else {
                                    DatagramSocket socket = result.getSuccessSocket();
                                    if (this.roomManager.currentRoom.get() == state && this.connectionWon.compareAndSet(false, true)) {
                                       String logTarget = fTargetIp != null && fTargetIp.contains(":")
                                          ? "[" + fTargetIp + "]:" + fTargetPort
                                          : fTargetIp + ":" + fTargetPort;
                                       VoxLinkMod.LOGGER.info("[Connection] Multi-socket punch success {} (attempt {})", logTarget, attempt);
                                       leadPuncher.markSocketTransferred();
                                       this.stopAllPunchingAfterHostBridge();

                                       for (UdpHolePuncher spxxx : multiSockets) {
                                          if (spxxx.getSocket() != socket) {
                                             try {
                                                spxxx.stopPunch();
                                                spxxx.close();
                                             } catch (Exception var25x) {
                                             }
                                          }
                                       }

                                       DatagramSocket punchSocket = socket;
                                       UdpHolePuncher puncherRef = leadPuncher;
                                       InetSocketAddress actualAddr = puncherRef.getActualRemoteAddress();
                                       if (actualAddr == null) {
                                          actualAddr = new InetSocketAddress(fTargetIp, fTargetPort);
                                       }

                                       InetSocketAddress finalTargetAddr = actualAddr;
                                       this.scheduler.submit(() -> {
                                          try {
                                             this.establishUdpTransport(state, punchSocket, puncherRef, finalTargetAddr, "joiner", false, null);
                                          } catch (Exception e) {
                                             VoxLinkMod.LOGGER.error("[Connection] Create UDP transport failed: {}", e.getMessage());

                                             try {
                                                puncherRef.close();
                                             } catch (Exception var7x) {
                                             }

                                             this.showConnectFailed(state, "voxlink.connection.transport_failed");
                                          }
                                       });
                                    } else {
                                       for (UdpHolePuncher spx : multiSockets) {
                                          try {
                                             spx.close();
                                          } catch (Exception var26x) {
                                          }
                                       }
                                    }
                                 }
                              }
                           )
                           .exceptionally(
                              e -> {
                                 VoxLinkMod.LOGGER
                                    .warn(
                                       "[Connection] Multi-socket punch failed (cycle {}/{}, attempt {}/{}): {}",
                                       new Object[]{displayCycle, maxCycles, attempt, 3, e.getMessage()}
                                    );

                                 for (UdpHolePuncher spxxx : multiSockets) {
                                    try {
                                       spxxx.stopPunch();
                                    } catch (Exception var19x) {
                                    }
                                 }

                                 if (this.roomManager.currentRoom.get() != state) {
                                    for (UdpHolePuncher spx : multiSockets) {
                                       try {
                                          spx.close();
                                       } catch (Exception var17x) {
                                       }
                                    }

                                    for (int si = 0; si < multiSockets.size(); si++) {
                                       this.activeHolePunchers.remove("joiner_ms_" + si);
                                    }

                                    this.activeHolePunchers.remove("joiner");
                                    this.connectionCycleActive.set(false);
                                    ConnectionHelper.resetConnecting();
                                    return null;
                                 } else {
                                    if ("punch stopped".equals(e.getMessage())) {
                                       VoxLinkMod.LOGGER.info("[Connection] Punch actively stopped, no retry");
                                       return null;
                                    }

                                    if (!(e.getCause() instanceof FirewallBlockedException) && !(e instanceof FirewallBlockedException)) {
                                       if (attempt < this.punchProfile().punchMaxAttempts) {
                                          long delay = this.punchProfile().punchRetryDelayMs * (1L << Math.min(attempt - 1, 4));
                                          VoxLinkMod.LOGGER
                                             .info("[Connection] Retry UDP punch after {}ms (attempt {}/{})", new Object[]{delay, attempt + 1, 3});
                                          this.scheduler
                                             .schedule(
                                                () -> {
                                                   if (this.roomManager.currentRoom.get() == state && this.connectionCycleActive.get()) {
                                                      this.tryUdpPunch(
                                                         state,
                                                         from,
                                                         hostIpv6,
                                                         hostIp,
                                                         hostPort,
                                                         hostMappedIp,
                                                         hostMappedPort,
                                                         cycle,
                                                         displayCycle,
                                                         maxCycles,
                                                         attempt + 1
                                                      );
                                                   }
                                                },
                                                delay,
                                                TimeUnit.MILLISECONDS
                                             );
                                       } else {
                                          this.tryConnectionStep(
                                             state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1
                                          );
                                       }

                                       return null;
                                    } else {
                                       VoxLinkMod.LOGGER.warn("[Connection] Firewall blocked UDP (multi-socket), skip retry enter Wave 2 TCP fallback");

                                       for (UdpHolePuncher spxx : multiSockets) {
                                          try {
                                             spxx.close();
                                          } catch (Exception var18x) {
                                          }
                                       }

                                       for (int si = 0; si < multiSockets.size(); si++) {
                                          this.activeHolePunchers.remove("joiner_ms_" + si);
                                       }

                                       this.activeHolePunchers.remove("joiner");
                                       this.tryConnectionStep(
                                          state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1
                                       );
                                       return null;
                                    }
                                 }
                              }
                           );
                     }
                  }
               } else {
                  VoxLinkMod.LOGGER.warn("[Connection] No target IP, UDP punch cannot proceed");
                  puncher.close();
                  this.activeHolePunchers.remove("joiner");
                  this.tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
               }
            }
         } else {
            VoxLinkMod.LOGGER.warn("[Connection] STUN binding failed, no mapped address (attempt{})", attempt);
            puncher.close();
            if (attempt < this.punchProfile().punchMaxAttempts) {
               long delay = this.punchProfile().punchRetryDelayMs * (1L << Math.min(attempt - 1, 4));
               VoxLinkMod.LOGGER.info("[Connection] Retry UDP punch after {}ms (attempt {}/{})", new Object[]{delay, attempt + 1, 3});
               this.scheduler.schedule(() -> {
                  if (this.roomManager.currentRoom.get() == state && this.connectionCycleActive.get()) {
                     this.tryUdpPunch(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, attempt + 1);
                  }
               }, delay, TimeUnit.MILLISECONDS);
            } else {
               this.tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 1);
            }
         }
      }
   }

   public void advanceToNextCycle(
      RoomManager.RoomState state, String from, String hostIpv6, String hostIp, int hostPort, String hostMappedIp, int hostMappedPort, int cycle, int maxCycles
   ) {
      if (this.connectionWon.get()) {
         VoxLinkMod.LOGGER.info("[Connection] Connected, skip next cycle");
      } else if (cycle + 1 >= maxCycles) {
         if (!this.enterContinuousRetryRound(state)) {
            this.showConnectFailed(state, "voxlink.connection.max_cycles_exceeded");
         }
      } else {
         for (Entry<String, UdpHolePuncher> entry : this.activeHolePunchers.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("joiner")) {
               UdpHolePuncher p = entry.getValue();

               try {
                  p.stopPunch();
               } catch (Exception var16) {
               }

               if (key.startsWith("joiner_ms_")) {
                  try {
                     p.close();
                  } catch (Exception var15) {
                  }
               }
            }
         }

         if (cycle == 0 && this.punchProfile() == PunchProfile.DEFAULT) {
            boolean hostSym = state.roomInfo.isHostSymmetric();
            boolean localSym = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric();
            if (hostSym || localSym) {
               this.switchPunchProfile(PunchProfile.AGGRESSIVE, "首轮失败+" + (hostSym ? "HostSym" : "LocalSym"));
               VoxLinkMod.LOGGER.info("[Connection] Detected extreme symmetric NAT, switch to aggressive level next round: {}", this.punchProfile().describeInstance());
            }
         }

         if (this.lastPunchResult != null && this.lastPunchResult.reason != null) {
            if (this.lastPunchResult.reason == this.lastFailureReason) {
               int count = this.consecutiveFailureCount.incrementAndGet();
               if (count >= 3) {
                  VoxLinkMod.LOGGER.warn("[Connection] Consecutive {} failures with same reason ({}), switch strategy", count, this.lastFailureReason);
                  switch (this.lastFailureReason) {
                     case NO_RESPONSE:
                        this.switchPunchProfile(PunchProfile.HARDSYM, "consecutive_no_response");
                     case FIREWALL_DETECTED:
                        break;
                     default:
                        this.switchPunchProfile(PunchProfile.AGGRESSIVE, "consecutive_" + this.lastFailureReason);
                  }

                  this.consecutiveFailureCount.set(0);
               }
            } else {
               this.lastFailureReason = this.lastPunchResult.reason;
               this.consecutiveFailureCount.set(1);
            }
         }

         int delayIdx = Math.min(cycle, BACKOFF_DELAYS_MS.length - 1);
         long delay = BACKOFF_DELAYS_MS[delayIdx];
         VoxLinkMod.LOGGER
            .info(
               "[Connection] Cycle {}/{} failed, retry in {}s (backoff level={})", new Object[]{cycle + 1, maxCycles, delay / 1000L, this.punchProfile().describeInstance()}
            );
         this.scheduler.schedule(() -> {
            if (this.roomManager.currentRoom.get() == state && state != RoomManager.PENDING) {
               this.runConnectionCycle(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle + 1);
            }
         }, delay, TimeUnit.MILLISECONDS);
      }
   }

   public List<String> selectReachableStunGroup(int cycle) {
      if (this.stunProbeResult != null && !this.stunProbeResult.reachableStunUrls.isEmpty()) {
         List<String> reachable = this.stunProbeResult.reachableStunUrls;
         int index = cycle % reachable.size();
         return List.of(reachable.get(index));
      } else {
         return StunDetector.getStunGroup(cycle % StunDetector.getStunGroupCount());
      }
   }

   public void startReversePunch(RoomManager.RoomState state) {
      VoxLinkMod.LOGGER.info("[ReversePunch] Parallel start reverse punch");
      state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.punching"));
      this.connectionCycleActive.set(true);
      boolean isSymmetric = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric();
      boolean isEasySym = this.stunProbeResult != null && this.stunProbeResult.natType.isEasySymmetric();
      boolean hostSym = state.roomInfo != null && (state.roomInfo.isHostSymmetric() || StunDetector.isNatTypeSymmetric(state.roomInfo.getNatType()));
      if (!isSymmetric) {
         this.startSimpleReversePunch(state);
      } else {
         this.switchPunchProfile(PunchProfile.V100, "sym_birthday");
         int socketCount = isEasySym ? this.punchProfile().birthdaySocketCount : this.punchProfile().hardSymSocketCount;
         VoxLinkMod.LOGGER
            .info(
               "[ReversePunch] Detected local symmetric (local={}, host={}) — birthday attack {} sockets (easySym={})",
               new Object[]{isSymmetric, hostSym, socketCount, isEasySym}
            );
         this.startBirthdayPunch(state, socketCount, isEasySym);
      }
   }

   public void startBirthdayPunch(RoomManager.RoomState state) {
      this.startBirthdayPunch(state, this.punchProfile().birthdaySocketCount, false);
   }

   public void startBirthdayPunch(RoomManager.RoomState state, int socketCount, boolean isEasySym) {
      String hostMappedIp = state.roomInfo.getHostMappedIp();
      int hostMappedPort = state.roomInfo.getHostMappedPort();
      if (hostMappedIp == null || hostMappedPort <= 0) {
         hostMappedIp = state.roomInfo.getHostIp();
         hostMappedPort = state.roomInfo.getHostPort() > 0 ? state.roomInfo.getHostPort() : 51600;
      }

      String fHostMappedIp = hostMappedIp;
      int fHostMappedPort = hostMappedPort;
      VoxLinkMod.LOGGER
         .info("[BirthdayPunch] Parallel STUN {} sockets (target={}:{}, easySym={})", new Object[]{socketCount, fHostMappedIp, fHostMappedPort, isEasySym});
      CompletableFuture.<ConnectionManager.UdpSocketArray>supplyAsync(() -> this.getOrCreateUdpArray(socketCount, isEasySym, StunDetector.getAllStunUrls()))
         .thenAccept(
            udpArray -> {
               if (this.roomManager.currentRoom.get() == state) {
                  if (udpArray != null && !udpArray.punchers.isEmpty()) {
                     List<UdpHolePuncher> birthdayPunchers = udpArray.punchers;
                     List<StunProbe.PublicMappedAddress> mappedAddresses = udpArray.mappedAddrs;
                     List<String> mappedPortList = new ArrayList<>();
                     List<String> birthdayKeys = new ArrayList<>();

                     for (int i = 0; i < birthdayPunchers.size(); i++) {
                        String key = "joiner_birthday_" + i;
                        birthdayKeys.add(key);
                        this.activeHolePunchers.put(key, birthdayPunchers.get(i));
                        StunProbe.PublicMappedAddress addr = i < mappedAddresses.size() ? mappedAddresses.get(i) : null;
                        mappedPortList.add(addr != null ? String.valueOf(addr.port()) : "0");
                     }

                     StunProbe.PublicMappedAddress primaryAddr = null;

                     for (StunProbe.PublicMappedAddress addr : mappedAddresses) {
                        if (addr != null && addr.port() > 0) {
                           primaryAddr = addr;
                           break;
                        }
                     }

                     if (primaryAddr == null) {
                        VoxLinkMod.LOGGER.error("[BirthdayPunch] No valid mappedAddr");
                        this.showConnectFailed(state, "voxlink.connection.no_mapped_addr");
                     } else {
                        VoxLinkMod.LOGGER.info("[BirthdayPunch] Prepare {} sockets, mapped ports: {}", birthdayPunchers.size(), mappedPortList);
                        JsonObject offerData = new JsonObject();
                        offerData.addProperty("joinerMappedIp", primaryAddr.ip());
                        offerData.addProperty("joinerMappedPort", primaryAddr.port());
                        offerData.addProperty("joinerSymmetric", true);
                        if (isEasySym) {
                           offerData.addProperty("joinerEasySym", true);
                        }

                        offerData.add("joinerMappedPorts", new JsonArray());

                        for (StunProbe.PublicMappedAddress addr : mappedAddresses) {
                           if (addr != null && addr.port() > 0) {
                              offerData.getAsJsonArray("joinerMappedPorts").add(addr.port());
                           }
                        }

                        this.signalingClient
                           .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "reverse_holepunch_offer", offerData, "host")
                           .thenAccept(response -> {
                              if (!response.success) {
                                 VoxLinkMod.LOGGER.error("[BirthdayPunch] Send reverse_holepunch_offer failed: {}", response.error);
                              }
                           })
                           .exceptionally(e -> {
                              VoxLinkMod.LOGGER.error("[BirthdayPunch] Send reverse_holepunch_offer failed: {}", e.getMessage());
                              return null;
                           });
                        this.startBirthdayPunchPhase2(
                           state, birthdayPunchers, birthdayKeys, fHostMappedIp, fHostMappedPort, state.roomInfo.isHostSymmetric(), isEasySym
                        );
                        VoxLinkMod.LOGGER
                           .info("[BirthdayPunch] Start punch to {}:{} immediately (no wait for reverse_punch_info)", fHostMappedIp, fHostMappedPort);
                        this.scheduler.schedule(() -> {
                           if (this.connectionCycleActive.get() && this.roomManager.currentRoom.get() == state) {
                              VoxLinkMod.LOGGER.warn("[BirthdayPunch] Timeout (no connection established)");

                              for (UdpHolePuncher p : birthdayPunchers) {
                                 try {
                                    p.cancel();
                                    p.close();
                                 } catch (Exception var6x) {
                                 }
                              }

                              this.activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith("joiner_birthday_"));
                              this.showConnectFailed(state, "voxlink.connection.punch_timeout");
                           }
                        }, this.punchProfile().reverseWindowSec, TimeUnit.SECONDS);
                     }
                  } else {
                     VoxLinkMod.LOGGER.error("[BirthdayPunch] All STUN queries failed");
                     this.showConnectFailed(state, "voxlink.connection.stun_failed");
                  }
               }
            }
         );
   }

   public void startSimpleReversePunch(RoomManager.RoomState state) {
      UdpHolePuncher puncher = new UdpHolePuncher();
      this.applyPunchTemplate(puncher);

      try {
         puncher.createSocket();
      } catch (Exception e) {
         VoxLinkMod.LOGGER.error("[ReversePunch] Create punch socket failed: {}", e.getMessage());
         return;
      }

      this.activeHolePunchers.put("joiner_reverse", puncher);
      List<String> quadStun = StunDetector.getAllStunUrls();
      StunProbe.PublicMappedAddress[] quadResult = StunProbe.discoverMappedAddressQuad(
         puncher.getSocket(), quadStun.get(0), quadStun.get(1), quadStun.get(2), quadStun.get(3)
      );
      StunProbe.PublicMappedAddress myMapped1 = quadResult[0] != null ? quadResult[0] : (quadResult[2] != null ? quadResult[2] : quadResult[3]);
      StunProbe.PublicMappedAddress myMapped2 = quadResult[1] != null ? quadResult[1] : (quadResult[3] != null ? quadResult[3] : quadResult[2]);
      boolean joinerSymmetric = false;
      int joinerMappedPortDelta = 0;
      StunProbe.PublicMappedAddress myMappedAddr = null;
      if (myMapped1 != null && myMapped2 != null) {
         if (sameIpFamily(myMapped1.ip(), myMapped2.ip()) && myMapped1.port() != myMapped2.port()) {
            joinerSymmetric = true;
            joinerMappedPortDelta = myMapped2.port() - myMapped1.port();
            VoxLinkMod.LOGGER
               .info(
                  "[ReversePunch] Joiner punch socket STUN: detected symmetric ({} vs {}, delta={})",
                  new Object[]{myMapped1.port(), myMapped2.port(), joinerMappedPortDelta}
               );
         }

         myMappedAddr = myMapped2;
      } else {
         myMappedAddr = myMapped1 != null ? myMapped1 : myMapped2;
      }

      if (myMappedAddr == null) {
         myMappedAddr = puncher.discoverMappedAddress(StunDetector.getAllStunUrls());
      }

      if (myMappedAddr == null) {
         VoxLinkMod.LOGGER.warn("[ReversePunch] STUN failed, cannot reverse punch");
         puncher.close();
         this.activeHolePunchers.remove("joiner_reverse");
      } else {
         VoxLinkMod.LOGGER
            .info("[ReversePunch] Joiner mapped address: {}:{} (symmetric={})", new Object[]{myMappedAddr.ip(), myMappedAddr.port(), joinerSymmetric});
         if (joinerSymmetric) {
            VoxLinkMod.LOGGER.info("[ReversePunch] Reverse socket STUN detected symmetric, upgrade to birthday attack");

            try {
               puncher.close();
            } catch (Exception var17) {
            }

            this.activeHolePunchers.remove("joiner_reverse");
            this.startBirthdayPunch(state, this.punchProfile().hardSymSocketCount, false);
         } else {
            JsonObject offerData = new JsonObject();
            offerData.addProperty("joinerMappedIp", myMappedAddr.ip());
            offerData.addProperty("joinerMappedPort", myMappedAddr.port());
            if (joinerSymmetric || this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric()) {
               offerData.addProperty("joinerSymmetric", true);
            }

            if (this.stunProbeResult != null && this.stunProbeResult.natType.isEasySymmetric() || joinerSymmetric) {
               offerData.addProperty("joinerEasySym", true);
            }

            if (joinerMappedPortDelta != 0) {
               offerData.addProperty("joinerMappedPortDelta", joinerMappedPortDelta);
            }

            this.signalingClient
               .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "reverse_holepunch_offer", offerData, "host")
               .thenAccept(response -> {
                  if (!response.success) {
                     VoxLinkMod.LOGGER.error("[ReversePunch] Failed to send reverse_holepunch_offer: {}", response.error);
                  }
               })
               .exceptionally(e -> {
                  VoxLinkMod.LOGGER.error("[ReversePunch] Failed to send reverse_holepunch_offer: {}", e.getMessage());
                  return null;
               });
            String hostMappedIp = state.roomInfo.getHostMappedIp();
            int hostMappedPort = state.roomInfo.getHostMappedPort();
            if (hostMappedIp == null || hostMappedPort <= 0) {
               hostMappedIp = state.roomInfo.getHostIp();
               hostMappedPort = state.roomInfo.getHostPort() > 0 ? state.roomInfo.getHostPort() : 51600;
            }

            if (hostMappedIp != null && !hostMappedIp.isEmpty()) {
               String fHostMappedIp = hostMappedIp;
               int fHostMappedPort = hostMappedPort;
               UdpHolePuncher finalPuncher = puncher;
               int portRange = state.roomInfo.isHostSymmetric() || joinerSymmetric
                  ? this.punchProfile().portPredictionMaxRange
                  : this.punchProfile().portPredictionMaxRange;
               // 反向socket起始中心可能是陈旧offer端口, 要等reverse_punch_info/holepunch_mapped纠偏后
               // 才落在新鲜窗口; V100默认8s超时会被纠偏耗掉一半, 延长到15s保证纠偏后有充足轮次
               PunchParams simpleRevParams = PunchParams.fromProfile(this.punchProfile());
               simpleRevParams.timeoutMs = Math.max(simpleRevParams.timeoutMs, 15000);
               finalPuncher.setPunchParams(simpleRevParams);
               VoxLinkMod.LOGGER.info("[ReversePunch] Punch to {}:{} immediately (range=±{})", new Object[]{fHostMappedIp, fHostMappedPort, portRange});
               puncher.setOnPeerPunchReceived(addr -> {
                  String code = state.roomInfo.getCode();
                  String token = state.roomInfo.getToken();
                  JsonObject portData = new JsonObject();
                  portData.addProperty("peer_ip", addr.getAddress().getHostAddress());
                  portData.addProperty("peer_port", addr.getPort());
                  this.signalingClient.sendSignal(code, token, false, "peer_port", portData, "host").exceptionally(e -> {
                     VoxLinkMod.LOGGER.debug("peer_port signal send failed: {}", e.getMessage());
                     return null;
                  });
               });
               puncher.punchWithPortPrediction(fHostMappedIp, fHostMappedPort, portRange, true)
                  .thenAccept(
                     result -> {
                        if (!result.isSuccess()) {
                           PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                           this.lastPunchResult = result.withReason(reason);
                           this.activePunchParams = 
                              PunchTuner.nextParams(this.punchProfile(), this.localNatClass, this.remoteNatClass, 1, 8, reason, this.lastPunchResult)
                           ;
                           VoxLinkMod.LOGGER
                              .info(
                                 "[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}",
                                 new Object[]{reason, result.socketsReceivedPunch, result.socketsReceivedAck}
                              );
                        } else {
                           DatagramSocket socket = result.getSuccessSocket();
                           if (this.roomManager.currentRoom.get() == state && this.connectionWon.compareAndSet(false, true)) {
                              VoxLinkMod.LOGGER.info("[ReversePunch] Joiner reverse punch success {}:{}", fHostMappedIp, fHostMappedPort);
                              finalPuncher.markSocketTransferred();
                              this.stopAllPunchingAfterHostBridge();
                              finalPuncher.stopPunch();
                              DatagramSocket punchSocket = socket;
                              UdpHolePuncher puncherRef = finalPuncher;
                              InetSocketAddress actualAddr = puncherRef.getActualRemoteAddress();
                              if (actualAddr == null) {
                                 actualAddr = new InetSocketAddress(fHostMappedIp, fHostMappedPort);
                              }

                              InetSocketAddress finalTargetAddr = actualAddr;
                              VoxLinkMod.LOGGER
                                 .info(
                                    "[ReversePunch] Actual target address: {} (STUN mapping: {}:{})",
                                    new Object[]{finalTargetAddr, fHostMappedIp, fHostMappedPort}
                                 );
                              this.scheduler.submit(() -> {
                                 try {
                                    this.establishUdpTransport(state, punchSocket, puncherRef, finalTargetAddr, "joiner", false, null);
                                 } catch (Exception e) {
                                    VoxLinkMod.LOGGER.error("[ReversePunch] Joiner UDP transport create failed: {}", e.getMessage());

                                    try {
                                       puncherRef.close();
                                    } catch (Exception var7x) {
                                    }

                                    this.showConnectFailed(state, "voxlink.connection.transport_failed");
                                 }
                              });
                           } else {
                              try {
                                 finalPuncher.close();
                              } catch (Exception var11x) {
                              }
                           }
                        }
                     }
                  )
                  .exceptionally(
                     e -> {
                        VoxLinkMod.LOGGER
                           .debug("[ReversePunch] Joiner reverse punch failed (wait for reverse_punch_info to update target then retry): {}", e.getMessage());
                        return null;
                     }
                  );
               this.scheduler.schedule(() -> {
                  if (this.connectionCycleActive.get() && this.roomManager.currentRoom.get() == state && !this.connectionWon.get()) {
                     VoxLinkMod.LOGGER.warn("[ReversePunch] Reverse punch timeout");
                     UdpHolePuncher rp = this.activeHolePunchers.remove("joiner_reverse");
                     if (rp != null) {
                        rp.cancel();
                        rp.close();
                     }
                  }
               }, 20L, TimeUnit.SECONDS);
            } else {
               VoxLinkMod.LOGGER.warn("[ReversePunch] No host address, cannot punch immediately");
            }
         }
      }
   }

   public void startBirthdayPunchPhase2(
      RoomManager.RoomState state,
      List<UdpHolePuncher> birthdayPunchers,
      List<String> birthdayKeys,
      String hostMappedIp,
      int hostMappedPort,
      boolean hostSymmetric,
      boolean isEasySym
   ) {
      AtomicBoolean won = new AtomicBoolean(false);

      for (int i = 0; i < birthdayPunchers.size(); i++) {
         UdpHolePuncher puncher = birthdayPunchers.get(i);
         if (puncher.getSocket() != null && !puncher.getSocket().isClosed()) {
            int idx = i;
            puncher.setOnPeerPunchReceived(
               addr -> {
                  if (!won.get()) {
                     VoxLinkMod.LOGGER
                        .info("[BirthdayPunch] Socket #{} received peer punch to {}:{}", new Object[]{idx, addr.getAddress().getHostAddress(), addr.getPort()});
                  }
               }
            );
            int portRange = this.punchProfile().defaultPortRange;
            if (isEasySym) {
               portRange = this.punchProfile().easySymPortRange;
            } else if (hostSymmetric) {
               portRange = this.punchProfile().defaultPortRange;
            } else {
               portRange = this.punchProfile().minPortRange;
            }

            puncher.punchWithPortPrediction(hostMappedIp, hostMappedPort, portRange, true)
               .thenAccept(
                  result -> {
                     if (!result.isSuccess()) {
                        PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                        this.lastPunchResult = result.withReason(reason);
                        this.activePunchParams = 
                           PunchTuner.nextParams(this.punchProfile(), this.localNatClass, this.remoteNatClass, 1, 8, reason, this.lastPunchResult)
                        ;
                        VoxLinkMod.LOGGER
                           .info(
                              "[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}",
                              new Object[]{reason, result.socketsReceivedPunch, result.socketsReceivedAck}
                           );
                     } else {
                        DatagramSocket socket = result.getSuccessSocket();
                        if (!this.connectionWon.compareAndSet(false, true)) {
                           try {
                              puncher.close();
                           } catch (Exception var13x) {
                           }
                        } else if (!won.compareAndSet(false, true)) {
                           try {
                              puncher.close();
                           } catch (Exception var14) {
                           }
                        } else {
                           VoxLinkMod.LOGGER.info("[BirthdayPunch] Socket #{} won! Connected to {}:{}", new Object[]{idx, hostMappedIp, hostMappedPort});
                           puncher.markSocketTransferred();
                           this.stopAllPunchingAfterHostBridge();

                           for (UdpHolePuncher sp : birthdayPunchers) {
                              if (sp != puncher) {
                                 try {
                                    sp.stopPunch();
                                    sp.close();
                                 } catch (Exception var15) {
                                 }
                              }
                           }

                           puncher.stopPunch();
                           DatagramSocket punchSocket = socket;
                           UdpHolePuncher puncherRef = puncher;
                           this.scheduler
                              .submit(
                                 () -> {
                                    try {
                                       this.establishUdpTransport(
                                          state, punchSocket, puncherRef, new InetSocketAddress(hostMappedIp, hostMappedPort), "joiner", false, null
                                       );
                                    } catch (Exception e) {
                                       VoxLinkMod.LOGGER.error("[BirthdayPunch] Transport create failed: {}", e.getMessage());

                                       try {
                                          puncherRef.close();
                                       } catch (Exception var8x) {
                                       }

                                       this.showConnectFailed(state, "voxlink.connection.transport_failed");
                                    }
                                 }
                              );
                        }
                     }
                  }
               )
               .exceptionally(e -> {
                  VoxLinkMod.LOGGER.debug("[BirthdayPunch] Socket #{} failed: {}", idx, e.getMessage());
                  return null;
               });
         }
      }

      this.scheduler.schedule(() -> {
         if (!won.get() && this.connectionCycleActive.get() && this.roomManager.currentRoom.get() == state) {
            VoxLinkMod.LOGGER.warn("[BirthdayPunch] All {} sockets timeout", birthdayPunchers.size());

            for (int ix = 0; ix < birthdayPunchers.size(); ix++) {
               try {
                  birthdayPunchers.get(ix).cancel();
                  birthdayPunchers.get(ix).close();
               } catch (Exception var7x) {
               }

               this.activeHolePunchers.remove(birthdayKeys.get(ix));
            }

            this.showConnectFailed(state, "voxlink.connection.punch_timeout");
         }
      }, this.punchProfile().reverseWindowSec, TimeUnit.SECONDS);
   }

   public void connectViaBridge(RoomManager.RoomState state, ConnectionFallback.ConnectResult result) {
      if (this.dualRaceActive) {
         this.killAllConnectionAttempts("terracotta");
      }

      this.killAllConnectionAttempts();
      P2PBridge.cancelPendingUdpTimeouts();

      for (ReliableUdpTransport transport : this.activeUdpTransports.values()) {
         try {
            transport.close();
         } catch (Exception var6) {
         }
      }

      this.activeUdpTransports.clear();
      state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.punching"));
      String remoteHost = result.remoteHost;
      int remotePort = result.remotePort;
      if (result.mode == ConnectionFallback.ConnectionMode.IPV6_DIRECT) {
         P2PBridge.connectToHostIpv6(remoteHost, remotePort).thenAccept(localPort -> {
            if (localPort > 0) {
               if (this.dualRaceActive) {
                  this.claimVoxlinkDualWin();
               }

               this.connectionWon.set(true);
               this.markConnectionEstablished();

               this.connectionCycleActive.set(false);
               this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "connected", new JsonObject(), "host");
               state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.bridge_setup"));
               ConnectionHelper.connectToServer(localPort, state.roomInfo);
               this.notifyDualVoxlinkBridge(true);
            } else {
               this.connectionCycleActive.set(false);
               ConnectionHelper.resetConnecting();
               state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.bridge_start_failed"), true);
               this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "disconnect", new JsonObject(), "host");
               if (!this.dualRaceActive) {
                  this.handleConnectViaBridgeFailed(state);
               }

               this.notifyDualVoxlinkBridge(false);
            }
         });
      } else {
         P2PBridge.connectToHost(remoteHost, remotePort).thenAccept(localPort -> {
            if (localPort > 0) {
               if (this.dualRaceActive) {
                  this.claimVoxlinkDualWin();
               }

               this.connectionWon.set(true);
               this.markConnectionEstablished();

               this.connectionCycleActive.set(false);
               this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "connected", new JsonObject(), "host");
               state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.bridge_setup"));
               ConnectionHelper.connectToServer(localPort, state.roomInfo);
               this.notifyDualVoxlinkBridge(true);
            } else {
               this.connectionCycleActive.set(false);
               ConnectionHelper.resetConnecting();
               state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.bridge_start_failed"), true);
               this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "disconnect", new JsonObject(), "host");
               if (!this.dualRaceActive) {
                  this.handleConnectViaBridgeFailed(state);
               }

               this.notifyDualVoxlinkBridge(false);
            }
         });
      }
   }

   public void handleConnectViaBridgeFailed(RoomManager.RoomState state) {
      this.leaveRoomOnFailure(state);
   }

   public void startFallbackMonitor(ConnectionFallback fallback, RoomManager.RoomState state) {
      Thread t = new Thread(() -> {
         int loopCount = 0;

         while (!fallback.isSettled() && !fallback.isCancelled()) {
            if (loopCount >= 200) {
               VoxLinkMod.LOGGER.warn("Fallback monitor timeout (about 60s)");
               break;
            }

            Component status = fallback.getStatusText();
            if (status != null) {
               state.roomInfo.setConnectionMode(status);
            }

            loopCount++;

            try {
               Thread.sleep(300L);
            } catch (InterruptedException e) {
               return;
            }
         }

         Component finalStatus = fallback.getStatusText();
         if (finalStatus != null) {
            state.roomInfo.setConnectionMode(finalStatus);
         }
      }, "VoxLink-FallbackMonitor");
      t.setDaemon(true);
      t.start();
   }

   private boolean enterContinuousRetryRound(RoomManager.RoomState state) {
      if (this.connectionWon.get()) {
         return false;
      }

      if (!this.shouldContinuousRetry(state)) {
         return false;
      }

      if (this.savedConnectionState != state) {
         VoxLinkMod.LOGGER.warn("[Connection] Persistent retry aborted: saved params mismatch current room");
         return false;
      }

      int round = this.continuousRetryRound.incrementAndGet();
      this.escalateProfileForRound(round);
      VoxLinkMod.LOGGER.info("[Connection] Enter persistent retry round={}, level={}, reset cycle from 0", round, this.punchProfile().describeInstance());
      ConnectionState.transitionTo(ConnectionState.STUN_PROBE, "持续重试 round " + round);
      if (this.connectionTimeoutFuture != null) {
         this.connectionTimeoutFuture.cancel(false);
         this.connectionTimeoutFuture = null;
      }

      state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.retry_round", new Object[]{round}));
      int maxCycles = this.getEffectiveMaxCycles();
      this.tryConnectionStep(
         state,
         this.savedConnectionFrom,
         this.savedConnectionHostIpv6,
         this.savedConnectionHostIp,
         this.savedConnectionHostPort,
         this.savedConnectionHostMappedIp,
         this.savedConnectionHostMappedPort,
         0,
         1,
         maxCycles,
         0
      );
      return true;
   }

   public void showConnectFailed(RoomManager.RoomState state) {
      this.showConnectFailed(state, "voxlink.connection.all_failed");
   }

   public void showConnectFailed(RoomManager.RoomState state, String reasonKey) {
      if (this.roomManager.currentRoom.get() == state && state != RoomManager.PENDING) {
         if (!this.connectionWon.get()) {
            if (reasonKey != null && reasonKey.contains("relay")) {
               if (state != null && state.roomInfo != null) {
                  state.roomInfo.setConnectionMode(Component.translatable(reasonKey), true);
               }

               Minecraft mc = Minecraft.getInstance();
               if (mc != null) {
                  mc.execute(() -> {
                     if (mc.player != null) {
                        mc.player.sendSystemMessage(Component.translatable("voxlink.chat.error_prefix").append(Component.translatable(reasonKey)));
                     }
                  });
               }
            }

            if (!this.enterContinuousRetryRound(state)) {
               this.showConnectFailedFinal(state, reasonKey);
            }
         }
      }
   }

   private boolean hasRelayCandidateAvailable(RoomManager.RoomState state) {
      if (!VoxLinkMod.getConfig().isRelayEnabled()) {
         return false;
      }

      if (state != null && state.roomInfo != null) {
         for (RoomInfo.PeerInfo p : state.roomInfo.getPeers()) {
            String nt = p.natType;
            if (nt != null
               && p.mappedIp != null
               && p.mappedPort > 0
               && !this.isRelayPeerFailed(p.clientId)
               && !nt.contains("sym")
               && !nt.contains("strict")
               && !nt.equals("unknown")
               && ProtocolNegotiator.supportsRelay(p)) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   private void tryRelay(RoomManager.RoomState state) {
      if (!VoxLinkMod.getConfig().isRelayEnabled()) {
         VoxLinkMod.LOGGER.info("[Relay] Relay disabled by config, skip tryRelay");
         this.notifyRelayFailed();
         this.showConnectFailed(state, "voxlink.connection.relay_unavailable");
      } else if (this.currentRelayPeer.get() != null) {
         VoxLinkMod.LOGGER.info("[Relay] Relay already trying (current={}), skip duplicate", this.currentRelayPeer.get());
      } else {
         if (!state.roomInfo.isHost()) {
            if (this.currentRelayPeer.get() != null) {
               return;
            }

            this.currentRelayPeer.set("joiner_requesting");
            JsonObject data = new JsonObject();
            data.addProperty("clientId", state.roomInfo.getClientId());
            this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "relay_request", data, "host");
            this.relayFailoverTask = this.scheduler.schedule(() -> {
               if (!this.connectionWon.get()) {
                  this.currentRelayPeer.set(null);
                  this.relayFailoverTask = null;
                  this.notifyRelayFailed();
                  this.showConnectFailed(state, "voxlink.connection.relay_failed");
               }
            }, 20L, TimeUnit.SECONDS);
         } else {
            List<RoomInfo.PeerInfo> candidates = new ArrayList<>();

            for (RoomInfo.PeerInfo p : state.roomInfo.getPeers()) {
               String nt = p.natType;
               if (nt != null
                  && p.mappedIp != null
                  && p.mappedPort > 0
                  && !this.isRelayPeerFailed(p.clientId)
                  && this.activeUdpTransports.get(p.clientId) != null
                  && !nt.contains("sym")
                  && !nt.contains("strict")
                  && !nt.equals("unknown")) {
                  if (!ProtocolNegotiator.supportsRelay(p)) {
                     VoxLinkMod.LOGGER.info("[Relay] Candidate {} is legacy, skip", p.clientId);
                  } else {
                     candidates.add(p);
                  }
               }
            }

            if (candidates.isEmpty()) {
               VoxLinkMod.LOGGER.info("[Relay] No available Cone relay node (excluded failed={})", this.failedRelayPeers.size());
               this.failedRelayPeers.clear();
               this.notifyRelayFailed();
               this.showConnectFailed(state, "voxlink.connection.relay_unavailable");
               return;
            }

            RelayBridge relayBridge = RelayBridge.getInstance(this.scheduler);
            candidates.sort((a, b) -> {
               int loadA = relayBridge.getRelayCountForPeer(a.clientId);
               int loadB = relayBridge.getRelayCountForPeer(b.clientId);
               if (loadA != loadB) {
                  return Integer.compare(loadA, loadB);
               }

               ReliableUdpTransport ta = this.activeUdpTransports.get(a.clientId);
               ReliableUdpTransport tb = this.activeUdpTransports.get(b.clientId);
               return Long.compare(ta != null ? ta.getRtoMs() : Long.MAX_VALUE, tb != null ? tb.getRtoMs() : Long.MAX_VALUE);
            });
            int parallelN = Math.min(3, candidates.size());
            List<RoomInfo.PeerInfo> relayCandidates = candidates.subList(0, parallelN);
            RoomInfo.PeerInfo symPeer = null;
            Iterator i = state.roomInfo.getPeers().iterator();

            while (true) {
               if (i.hasNext()) {
                  RoomInfo.PeerInfo p = (RoomInfo.PeerInfo)i.next();
                  String nt = p.natType;
                  if (nt == null || !nt.contains("sym") && !nt.contains("strict") && !nt.equals("unknown")) {
                     continue;
                  }

                  if (!ProtocolNegotiator.supportsRelay(p)) {
                     VoxLinkMod.LOGGER.info("[Relay] Target Sym player {} is legacy, skip relay", p.clientId);
                     continue;
                  }

                  symPeer = p;
               }

               if (symPeer == null || symPeer.mappedIp == null) {
                  VoxLinkMod.LOGGER.warn("[Relay] No symmetric NAT player needs relay");
                  this.notifyRelayFailed();
                  this.showConnectFailed(state, "voxlink.connection.relay_unavailable");
                  return;
               }

               this.currentRelayPeer.set(relayCandidates.get(0).clientId);
               VoxLinkMod.LOGGER.info("[Relay] Parallel try {} Cone relays, target Sym={}", parallelN, symPeer.clientId);

               for (int ix = 0; ix < parallelN; ix++) {
                  RoomInfo.PeerInfo relay = relayCandidates.get(ix);
                  JsonObject setup = new JsonObject();
                  setup.addProperty("targetClientId", symPeer.clientId);
                  setup.addProperty("targetIp", symPeer.mappedIp);
                  setup.addProperty("targetPort", symPeer.mappedPort);
                  this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_setup", setup, relay.clientId);
                  JsonObject notify = new JsonObject();
                  notify.addProperty("relayIp", relay.mappedIp);
                  notify.addProperty("relayPort", relay.mappedPort);
                  this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_notify", notify, symPeer.clientId);
                  VoxLinkMod.LOGGER
                     .info(
                        "[Relay] Concurrent #{} relay={} (load={}) {}:{}",
                        new Object[]{ix + 1, relay.clientId, relayBridge.getRelayCountForPeer(relay.clientId), relay.mappedIp, relay.mappedPort}
                     );
               }

               this.relayFailoverTask = this.scheduler.schedule(() -> {
                  if (!this.connectionWon.get() && this.roomManager.currentRoom.get() == state) {
                     VoxLinkMod.LOGGER.warn("[Relay] Parallel relay all timeout, mark {} failed", parallelN);

                     for (RoomInfo.PeerInfo r : relayCandidates) {
                        this.failedRelayPeers.put(r.clientId, System.currentTimeMillis());
                     }

                     this.currentRelayPeer.set(null);
                     this.relayFailoverTask = null;
                     this.tryRelay(state);
                  }
               }, 8L, TimeUnit.SECONDS);
               break;
            }
         }
      }
   }

   private void clearRelayTracking() {
      this.currentRelayPeer.set(null);
      if (this.relayFailoverTask != null) {
         this.relayFailoverTask.cancel(false);
         this.relayFailoverTask = null;
      }
   }

   public void handleRelayRequest(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state != null && state != RoomManager.PENDING) {
         String requestingClientId = data.has("clientId") ? data.get("clientId").getAsString() : from;
         VoxLinkMod.LOGGER.info("[Relay] Received relay_request, requester={}", requestingClientId);
         RoomInfo.PeerInfo requestingPeerCheck = state.roomInfo.getPeer(requestingClientId);
         if (requestingPeerCheck != null && !ProtocolNegotiator.supportsRelay(requestingPeerCheck)) {
            VoxLinkMod.LOGGER.info("[Relay] Requester {} is legacy, no relay_request response", requestingClientId);
         } else {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
               mc.player.sendSystemMessage(Component.translatable("voxlink.relay.host_notice"));
            }

            RoomInfo.PeerInfo requestingPeer = state.roomInfo.getPeer(requestingClientId);
            if (requestingPeer != null && requestingPeer.mappedIp != null && requestingPeer.mappedPort > 0) {
               List<RoomInfo.PeerInfo> candidates = new ArrayList<>();

               for (RoomInfo.PeerInfo p : state.roomInfo.getPeers()) {
                  if (!p.clientId.equals(requestingClientId)
                     && p.mappedIp != null
                     && p.mappedPort > 0
                     && this.activeUdpTransports.get(p.clientId) != null
                     && !this.isRelayPeerFailed(p.clientId)) {
                     String nt = p.natType;
                     if (nt != null && !nt.contains("sym") && !nt.contains("strict") && !nt.equals("unknown") && ProtocolNegotiator.supportsRelay(p)) {
                        candidates.add(p);
                     }
                  }
               }

               if (candidates.isEmpty()) {
                  this.fetchGlobalRelayCandidates(state, requestingClientId, requestingPeer);
               } else {
                  this.dispatchRelaySetup(state, candidates, requestingClientId, requestingPeer);
               }
            } else {
               this.signalingClient
                  .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "relay_declined", new JsonObject(), requestingClientId);
            }
         }
      }
   }

   private void fetchGlobalRelayCandidates(RoomManager.RoomState state, String requestingClientId, RoomInfo.PeerInfo requestingPeer) {
      this.signalingClient
         .getRelayCandidates()
         .thenAccept(
            resp -> {
               if (this.roomManager.currentRoom.get() != state || this.connectionWon.get()) {
                  return;
               }

               if (resp == null || !resp.success || resp.data == null || !resp.data.has("candidates")) {
                  this.signalingClient
                     .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "relay_declined", new JsonObject(), requestingClientId);
                  return;
               }

               JsonArray cands = resp.data.getAsJsonArray("candidates");
               List<RoomInfo.PeerInfo> global = new ArrayList<>();

               for (int i = 0; i < cands.size(); i++) {
                  JsonObject c = cands.get(i).getAsJsonObject();
                  String cid = c.has("clientId") ? c.get("clientId").getAsString() : null;
                  String ip = c.has("mappedIp") ? c.get("mappedIp").getAsString() : null;
                  int port = c.has("mappedPort") ? c.get("mappedPort").getAsInt() : 0;
                  String nt = c.has("natType") ? c.get("natType").getAsString() : "unknown";
                  if (cid == null || cid.equals(requestingClientId) || cid.equals(state.roomInfo.getClientId())) {
                     continue;
                  }

                  if (ip == null || port <= 0 || nt.contains("sym") || nt.contains("strict") || nt.equals("unknown")) {
                     continue;
                  }

                  if (this.isRelayPeerFailed(cid)) {
                     continue;
                  }

                  RoomInfo.PeerInfo pi = new RoomInfo.PeerInfo(cid);
                  pi.mappedIp = ip;
                  pi.mappedPort = port;
                  pi.natType = nt;
                  pi.capabilities = java.util.Collections.singleton(ProtocolNegotiator.CAP_RELAY);
                  global.add(pi);
               }

               if (global.isEmpty()) {
                  this.signalingClient
                     .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "relay_declined", new JsonObject(), requestingClientId);
               } else {
                  this.dispatchRelaySetup(state, global, requestingClientId, requestingPeer);
               }
            }
         )
         .exceptionally(e -> {
            VoxLinkMod.LOGGER.debug("[Relay] Fetch global candidates failed: {}", e.getMessage());
            this.signalingClient
               .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "relay_declined", new JsonObject(), requestingClientId);
            return null;
         });
   }

   private void dispatchRelaySetup(RoomManager.RoomState state, List<RoomInfo.PeerInfo> candidates, String requestingClientId, RoomInfo.PeerInfo requestingPeer) {
      RelayBridge relayBridge = RelayBridge.getInstance(this.scheduler);
      candidates.sort((a, b) -> {
         int loadA = relayBridge.getRelayCountForPeer(a.clientId);
         int loadB = relayBridge.getRelayCountForPeer(b.clientId);
         return Integer.compare(loadA, loadB);
      });
      RoomInfo.PeerInfo relay = candidates.get(0);
      JsonObject setup = new JsonObject();
      setup.addProperty("targetClientId", requestingClientId);
      setup.addProperty("targetIp", requestingPeer.mappedIp);
      setup.addProperty("targetPort", requestingPeer.mappedPort);
      this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_setup", setup, relay.clientId);
      JsonObject notify = new JsonObject();
      notify.addProperty("relayIp", relay.mappedIp);
      notify.addProperty("relayPort", relay.mappedPort);
      this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_notify", notify, requestingClientId);
   }

   public void handleRelayAccept(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state != null && state != RoomManager.PENDING) {
         String forClientId = data.has("forClientId") ? data.get("forClientId").getAsString() : null;
         if (forClientId != null) {
            JsonObject notify = new JsonObject();
            notify.addProperty("connected", true);
            this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_notify", notify, forClientId);
         }
      }
   }

   public void handleRelayNotify(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state != null && state != RoomManager.PENDING) {
         if (data.has("connected") && data.get("connected").getAsBoolean()) {
            VoxLinkMod.LOGGER.info("[Relay] Received relay_notify(connected), relay ready");
            this.relayConnectedSignaled = true;
            this.clearRelayTracking();
            this.connectionWon.set(true);
            state.roomInfo.setConnectionMode(Component.translatable("voxlink.relay.connected_via").withStyle(ChatFormatting.YELLOW));
            state.roomInfo.setUsingRelay(true);
         } else if (!VoxLinkMod.getConfig().isRelayEnabled()) {
            VoxLinkMod.LOGGER.info("[Relay] Relay disabled by config, reject relay_notify");
         } else {
            String relayIp = data.has("relayIp") ? data.get("relayIp").getAsString() : null;
            int relayPort = data.has("relayPort") ? data.get("relayPort").getAsInt() : 0;
            if (relayIp != null && relayPort > 0) {
               VoxLinkMod.LOGGER.info("[Relay] Received relay_notify, punch to Cone {}:{}", relayIp, relayPort);
               state.roomInfo.setConnectionMode(Component.translatable("voxlink.relay.trying"));
               List<UdpHolePuncher> relayPunchers = new ArrayList<>();
               AtomicBoolean relayWon = new AtomicBoolean(false);
               String fRelayIp = relayIp;
               int fRelayPort = relayPort;

               for (int i = 0; i < this.punchProfile().relaySocketCount; i++) {
                  UdpHolePuncher rp = new UdpHolePuncher();
                  this.applyPunchTemplate(rp);

                  try {
                     rp.createSocket();
                  } catch (Exception e) {
                     continue;
                  }

                  relayPunchers.add(rp);
                  this.activeHolePunchers.put("relay_to_cone_" + i, rp);
                  int idx = i;
                  rp.punch(fRelayIp, fRelayPort)
                     .orTimeout(15L, TimeUnit.SECONDS)
                     .thenAccept(
                        result -> {
                           if (!result.isSuccess()) {
                              PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                              this.lastPunchResult = result.withReason(reason);
                              this.activePunchParams = 
                                 PunchTuner.nextParams(this.punchProfile(), this.localNatClass, this.remoteNatClass, 1, 8, reason, this.lastPunchResult)
                              ;
                              VoxLinkMod.LOGGER
                                 .info(
                                    "[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}",
                                    new Object[]{reason, result.socketsReceivedPunch, result.socketsReceivedAck}
                                 );
                           } else {
                              DatagramSocket socket = result.getSuccessSocket();
                              if (!relayWon.compareAndSet(false, true)) {
                                 try {
                                    rp.close();
                                 } catch (Exception var13x) {
                                 }
                              } else if (this.connectionWon.compareAndSet(false, true)
                                 || this.relayConnectedSignaled && !this.activeUdpTransports.containsKey("relay_cone")) {
                                 VoxLinkMod.LOGGER.info("[Relay] Sym->Cone socket#{} punch success", idx);
                                 rp.markSocketTransferred();
                                 this.stopAllPunchingAfterHostBridge();
                                 rp.stopPunch();

                                 for (UdpHolePuncher op : relayPunchers) {
                                    if (op != rp) {
                                       try {
                                          op.cancel();
                                          op.close();
                                       } catch (Exception var15) {
                                       }
                                    }
                                 }

                                 ReliableUdpTransport transport = new ReliableUdpTransport(socket, new InetSocketAddress(fRelayIp, fRelayPort));
                                 this.activeUdpTransports.put("relay_cone", transport);
                                 transport.start();
                                 state.roomInfo.setConnectionMode(Component.translatable("voxlink.relay.connected_via"));
                                 state.roomInfo.setUsingRelay(true);
                                 this.startUdpPunchBridge(state, transport);
                                 JsonObject readyData = new JsonObject();
                                 readyData.addProperty("clientId", state.roomInfo.getClientId());
                                 this.signalingClient
                                    .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "relay_ready", readyData, "host")
                                    .exceptionally(e -> {
                                       VoxLinkMod.LOGGER.debug("relay_ready send failed: {}", e.getMessage());
                                       return null;
                                    });
                                 this.scheduler.schedule(() -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    if (mc.player != null) {
                                       mc.player.sendSystemMessage(Component.translatable("voxlink.relay.connected_via"));
                                    }
                                 }, 2L, TimeUnit.SECONDS);
                              } else {
                                 try {
                                    rp.close();
                                 } catch (Exception var14) {
                                 }
                              }
                           }
                        }
                     )
                     .exceptionally(e -> {
                        if (!relayWon.get()) {
                           try {
                              rp.close();
                           } catch (Exception var6x) {
                           }

                           this.activeHolePunchers.remove("relay_to_cone_" + idx);
                        }

                        return null;
                     });
               }

               this.scheduler.schedule(() -> {
                  if (!relayWon.get() && !this.connectionWon.get()) {
                     VoxLinkMod.LOGGER.warn("[Relay] Sym->Cone relay punch timeout (8s)");

                     for (UdpHolePuncher op : relayPunchers) {
                        try {
                           op.cancel();
                           op.close();
                        } catch (Exception var7x) {
                        }
                     }

                     this.activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith("relay_to_cone_"));
                     this.showConnectFailed(state, "voxlink.connection.relay_failed");
                  }
               }, 8L, TimeUnit.SECONDS);
            }
         }
      }
   }

   public void handleRelayDeclined(String from, JsonObject data) {
      this.clearRelayTracking();
      this.notifyRelayFailed();
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state != null && state != RoomManager.PENDING) {
         this.showConnectFailed(state, "voxlink.connection.relay_failed");
      }
   }

   private void scheduleRelayRegistrationRenewal(RoomManager.RoomState state, String natType, String ip, int port) {
      this.scheduler
         .schedule(
            () -> {
               if (this.roomManager.currentRoom.get() == state && !this.connectionWon.get() && state.roomInfo.getClientId() != null) {
                  if (ip != null && port > 0) {
                     boolean relayOk = VoxLinkMod.getConfig().isRelayEnabled();
                     this.signalingClient
                        .registerRelayPeer(state.roomInfo.getClientId(), state.roomInfo.getCode(), natType, ip, port, relayOk)
                        .thenRun(() -> this.scheduleRelayRegistrationRenewal(state, natType, ip, port))
                        .exceptionally(e -> {
                           VoxLinkMod.LOGGER.debug("relay renew failed: {}", e.getMessage());
                           return null;
                        });
                  }
               }
            },
            60L,
            TimeUnit.SECONDS
         );
   }

   public void handleRelayReady(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state != null && state != RoomManager.PENDING) {
         VoxLinkMod.LOGGER.info("[Relay] Received relay_ready from joiner {}, mark relay connected", from);
         this.clearRelayTracking();
         this.stopAllPunchingAfterHostBridge();
         this.activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith("host_"));
         if (this.connectionWon.compareAndSet(false, true)) {
            state.roomInfo.setConnectionMode(Component.translatable("voxlink.relay.connected_via").withStyle(ChatFormatting.YELLOW));
            state.roomInfo.setUsingRelay(true);
         }
      }
   }

   public void handleRelaySetup(String from, JsonObject data) {
      if (!VoxLinkMod.getConfig().isRelayEnabled()) {
         RoomManager.RoomState state = this.roomManager.currentRoom.get();
         if (state != null && state != RoomManager.PENDING) {
            this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "relay_declined", new JsonObject(), from);
         }
      } else {
         RoomManager.RoomState state0 = this.roomManager.currentRoom.get();
         if (state0 != null && state0 != RoomManager.PENDING) {
            String targetClientId = data.has("targetClientId") ? data.get("targetClientId").getAsString() : null;
            String targetIp = data.has("targetIp") ? data.get("targetIp").getAsString() : null;
            int targetPort = data.has("targetPort") ? data.get("targetPort").getAsInt() : 0;
            if (targetIp != null && targetPort > 0) {
               RoomManager.RoomState state = this.roomManager.currentRoom.get();
               ReliableUdpTransport hostTransport = this.activeUdpTransports.get("joiner");
               if (hostTransport == null || !hostTransport.isConnected()) {
                  for (Entry<String, ReliableUdpTransport> entry : this.activeUdpTransports.entrySet()) {
                     if (entry.getValue().isConnected()) {
                        hostTransport = entry.getValue();
                        break;
                     }
                  }
               }

               if (hostTransport != null) {
                  List<UdpHolePuncher> conePunchers = new ArrayList<>();
                  AtomicBoolean coneWon = new AtomicBoolean(false);
                  String fTargetIp = targetIp;
                  int fTargetPort = targetPort;
                  String fTargetClientId = targetClientId;
                  ReliableUdpTransport fHostTransport = hostTransport;

                  for (int i = 0; i < this.punchProfile().relaySocketCount; i++) {
                     UdpHolePuncher cp = new UdpHolePuncher();
                     this.applyPunchTemplate(cp);

                     try {
                        cp.createSocket();
                     } catch (Exception e) {
                        continue;
                     }

                     conePunchers.add(cp);
                     this.activeHolePunchers.put("relay_to_sym_" + i, cp);
                     int idx = i;
                     cp.punchWithPortPrediction(fTargetIp, fTargetPort, this.punchProfile().coneBackupPortRange)
                        .orTimeout(15L, TimeUnit.SECONDS)
                        .thenAccept(
                           result -> {
                              if (!result.isSuccess()) {
                                 PunchFailureClassifier.FailureReason reason = PunchFailureClassifier.classify(result);
                                 this.lastPunchResult = result.withReason(reason);
                                 this.activePunchParams = 
                                    PunchTuner.nextParams(this.punchProfile(), this.localNatClass, this.remoteNatClass, 1, 8, reason, this.lastPunchResult)
                                 ;
                                 VoxLinkMod.LOGGER
                                    .info(
                                       "[ConnectionManager] Punch failed reason={} recvPunch={} recvAck={}",
                                       new Object[]{reason, result.socketsReceivedPunch, result.socketsReceivedAck}
                                    );
                              } else {
                                 DatagramSocket socket = result.getSuccessSocket();
                                 if (!coneWon.compareAndSet(false, true)) {
                                    try {
                                       cp.close();
                                    } catch (Exception var15x) {
                                    }
                                 } else {
                                    VoxLinkMod.LOGGER.info("[Relay] Cone->Sym socket#{} punch success", idx);
                                    cp.markSocketTransferred();

                                    for (UdpHolePuncher op : conePunchers) {
                                       if (op != cp) {
                                          try {
                                             op.cancel();
                                             op.close();
                                          } catch (Exception var16x) {
                                          }
                                       }
                                    }

                                    ReliableUdpTransport peerTransport = new ReliableUdpTransport(socket, new InetSocketAddress(fTargetIp, fTargetPort));
                                    peerTransport.start();
                                    this.activeUdpTransports.put(fTargetClientId != null ? fTargetClientId : "sym_relayed", peerTransport);
                                    RelayBridge.getInstance(this.scheduler)
                                       .startRelay("host", fTargetClientId != null ? fTargetClientId : "sym", fHostTransport, peerTransport);
                                    JsonObject reply = new JsonObject();
                                    reply.addProperty("forClientId", fTargetClientId != null ? fTargetClientId : "sym");
                                    this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "relay_accept", reply, "host");
                                 }
                              }
                           }
                        )
                        .exceptionally(e -> {
                           if (!coneWon.get()) {
                              try {
                                 cp.close();
                              } catch (Exception var6x) {
                              }

                              this.activeHolePunchers.remove("relay_to_sym_" + idx);
                           }

                           return null;
                        });
                  }

                  this.scheduler.schedule(() -> {
                     if (!coneWon.get()) {
                        VoxLinkMod.LOGGER.warn("[Relay] Cone->Sym relay punch timeout (8s)");

                        for (UdpHolePuncher op : conePunchers) {
                           try {
                              op.cancel();
                              op.close();
                           } catch (Exception var7x) {
                           }
                        }

                        this.activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith("relay_to_sym_"));
                        this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "relay_declined", new JsonObject(), "host");
                     }
                  }, 8L, TimeUnit.SECONDS);
               }
            }
         }
      }
   }

   public void onRelayDisconnected(String peerA, String peerB) {
      VoxLinkMod.LOGGER.warn("[Relay] Relay disconnect notify: {}<->{}", peerA, peerB);
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state != null && state != RoomManager.PENDING && !this.connectionWon.get()) {
         if (this.currentRelayPeer.get() != null) {
            this.failedRelayPeers.put(this.currentRelayPeer.get(), System.currentTimeMillis());
         }

         this.clearRelayTracking();
         this.scheduler.schedule(() -> {
            if (this.roomManager.currentRoom.get() == state && !this.connectionWon.get()) {
               VoxLinkMod.LOGGER.info("[Relay] Auto switch to backup relay...");
               this.tryRelay(state);
            }
         }, 500L, TimeUnit.MILLISECONDS);
      }
   }

   public void showConnectFailedFinal(RoomManager.RoomState state) {
      this.showConnectFailedFinal(state, "voxlink.connection.all_failed");
   }

   public void showConnectFailedFinal(RoomManager.RoomState state, String reasonKey) {
      this.clearRelayTracking();
      this.failedRelayPeers.clear();
      this.cancelConnectionCycleSafety();
      int bridgePort = state != null && state.roomInfo != null ? state.roomInfo.getLocalBridgePort() : -1;
      if (this.connectionWon.get() || this.voxlinkWon || this.terracottaWon || bridgePort > 0) {
         VoxLinkMod.LOGGER
            .info(
               "[Connection] Bridge established/won, ignore showConnectFailedFinal (connWon={} voxlinkWon={} terracottaWon={} bridgePort={})",
               new Object[]{this.connectionWon.get(), this.voxlinkWon, this.terracottaWon, bridgePort}
            );
      } else if (this.continuousRetryRound.get() > 0 && !this.continuousRetryCancelled.get()) {
         if (state != null && state.roomInfo != null) {
            state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.retrying"));
         }
      } else if (this.dualRaceActive && !this.terracottaWon) {
         if (state != null && state.roomInfo != null) {
            state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.cannot_establish"));
         }

         this.voxlinkSideDisabled = true;
         if (this.dualResultRef != null && this.dualFailedCount.incrementAndGet() >= 2) {
            this.dualResultRef.completeExceptionally(new RuntimeException("所有连接方式失败"));
         }
      } else if (this.connectionWon.get()) {
         VoxLinkMod.LOGGER.info("[Connection] Connected, ignore showConnectFailedFinal");
      } else {
         this.connectionCycleActive.set(false);
         this.connectionWon.set(false);
         ConnectionState.transitionTo(ConnectionState.FAILED, "所有连接方式失败");
         if (this.connectionTimeoutFuture != null) {
            this.connectionTimeoutFuture.cancel(false);
            this.connectionTimeoutFuture = null;
         }

         ConnectionHelper.resetConnecting();
         state.roomInfo.setConnectionMode(Component.translatable(reasonKey), true);
         this.sendDisconnectOnFailure(state);
         P2PBridge.disconnect();
         P2PBridge.cancelPendingUdpTimeouts();

         for (UdpHolePuncher puncher : this.activeHolePunchers.values()) {
            puncher.cancel();
            puncher.close();
         }

         this.activeHolePunchers.clear();

         for (ReliableUdpTransport transport : this.activeUdpTransports.values()) {
            try {
               transport.close();
            } catch (Exception var7) {
            }
         }

         this.activeUdpTransports.clear();
         Minecraft mc = Minecraft.getInstance();
         if (mc != null) {
            mc.execute(() -> {
               if (mc.player != null) {
                  mc.player.sendSystemMessage(Component.translatable("voxlink.chat.error_prefix").append(Component.translatable(reasonKey)));
                  if (state.roomInfo.isSameCgnat()) {
                     mc.player.sendSystemMessage(Component.translatable("voxlink.chat.same_cgnat_warning"));
                  }
               }

               this.leaveRoomOnFailure(state);
            });
         }
      }
   }

   private void leaveRoomOnFailure(RoomManager.RoomState state) {
      if (this.roomManager.currentRoom.get() == state && state != RoomManager.PENDING) {
         if (state.roomInfo != null && state.roomInfo.isHost()) {
            try {
               this.scheduler.execute(() -> this.roomManager.leaveRoom("连接失败"));
            } catch (RejectedExecutionException ex) {
               VoxLinkMod.LOGGER.warn("Scheduler closed, sync execute leaveRoom");
               this.roomManager.leaveRoom("连接失败");
            }
         }
      }
   }

   public void killAllConnectionAttempts() {
      boolean alreadyWon = this.connectionWon.get();
      if (!alreadyWon && !this.continuousRetryCancelled.getAndSet(true) && this.continuousRetryRound.get() > 0) {
         this.sendCancelConnectionSignal();
      } else {
         this.continuousRetryCancelled.set(true);
      }

      this.connectionCycleActive.set(false);
      this.connectionWon.set(true);
      if (!alreadyWon) {
         ConnectionState.reset();
      }

      if (this.connectionTimeoutFuture != null) {
         this.connectionTimeoutFuture.cancel(false);
         this.connectionTimeoutFuture = null;
      }

      this.cancelConnectionCycleSafety();

      for (UdpHolePuncher puncher : this.activeHolePunchers.values()) {
         try {
            puncher.cancel();
         } catch (Exception var9) {
         }

         try {
            puncher.stopPunch();
         } catch (Exception var8) {
         }

         try {
            puncher.close();
         } catch (Exception var7) {
         }
      }

      this.activeHolePunchers.clear();

      for (ReliableUdpTransport t : this.activeUdpTransports.values()) {
         try {
            t.close();
         } catch (Exception var6) {
         }
      }

      this.activeUdpTransports.clear();

      for (ReliableUdpTransport t : this.oldUdpTransports.values()) {
         try {
            t.close();
         } catch (Exception var5) {
         }
      }

      this.oldUdpTransports.clear();
      this.cancelAllFallbacks();
      this.hostPunching = false;
      this.lastPunchInfoId = "";
      this.closeVoiceForwardBridges();
   }

   public void killAllConnectionAttempts(String reason) {
      if (reason == null) {
         this.killAllConnectionAttempts();
      } else {
         switch (reason) {
            case "voxlink":
               this.killAllConnectionAttempts();
               P2PBridge.disconnect();
               break;
            case "terracotta":
               TerracottaManager.setIdle().orTimeout(3L, TimeUnit.SECONDS).whenComplete((r, e) -> {
                  if (e != null) {
                     VoxLinkMod.LOGGER.warn("[DualP2P] Terracotta setIdle timeout: {}", e.getMessage());
                  }

                  TerracottaManager.clearLastState();
               });
               break;
            default:
               this.killAllConnectionAttempts();
         }

         VoxLinkMod.LOGGER.info("[DualP2P] Abort {} side connection attempt", reason);
      }
   }

   public void killDualRace() {
      this.dualRaceActive = false;
      this.voxlinkSideDisabled = true;
      this.killAllConnectionAttempts("voxlink");
      this.killAllConnectionAttempts("terracotta");
      CompletableFuture<Void> bf = this.dualVoxlinkBridgeFuture;
      if (bf != null && !bf.isDone()) {
         bf.completeExceptionally(new RuntimeException("用户取消"));
      }

      this.dualVoxlinkBridgeFuture = null;
      this.dualResultRef = null;
      this.dualFailedCount.set(0);
      this.terracottaWon = false;
      this.voxlinkWon = false;
      this.dualRaceWon.set(false);
   }

   public void resetDualRaceState() {
      this.dualRaceActive = false;
      this.terracottaWon = false;
      this.voxlinkWon = false;
      this.voxlinkSideDisabled = false;
      this.dualFailedCount.set(0);
      this.dualResultRef = null;
      this.dualVoxlinkBridgeFuture = null;
      this.dualRaceWon.set(false);
   }

   public void resetContinuousRetryState() {
      this.continuousRetryCancelled.set(false);
      this.continuousRetryRound.set(0);
      this.lastFailureReason = null;
      this.consecutiveFailureCount.set(0);
   }

   // 连接级重置: 清掉上一个房间/上一次连接的NAT分类、tier、动态profile与打洞参数缓存,
   // 避免换房间/换网络后仍用旧分类去recommendProfile选错模板。
   private void resetConnectionStateForNextP2P() {
      this.localNatClass = NatClass.UNKNOWN;
      this.remoteNatClass = NatClass.UNKNOWN;
      this.scenarioTier = ScenarioTier.Tier.NORMAL;
      this.activePunchParams = null;
      this.lastPunchResult = null;
      this.lastHostMappedPorts = null;
      for (UdpHolePuncher p : this.activeHolePunchers.values()) {
         try { p.cancel(); } catch (Exception ignored) {}
         try { p.close(); } catch (Exception ignored) {}
      }
      this.activeHolePunchers.clear();
      PunchProfile.switchToDefault("new_connection");
      PunchProfile.clearDynamicParams();
      StunDetector.clearLocalCaches();
   }

   private boolean shouldContinuousRetry(RoomManager.RoomState state) {
      if (this.continuousRetryCancelled.get()) {
         VoxLinkMod.LOGGER.info("[Connection] Player cancelled persistent retry");
         return false;
      } else if (state == null || state == RoomManager.PENDING || state.roomInfo == null) {
         return false;
      } else if (state.roomInfo.isHost()) {
         return state.roomInfo.getPeers().isEmpty() ? false : state.roomInfo.getPeers().stream().allMatch(ProtocolNegotiator::supportsContinuousRetry);
      } else {
         return state.roomInfo.isHostLegacy() ? false : state.roomInfo.getHostCapabilities().contains("continuous_retry");
      }
   }

   private void escalateProfileForRound(int round) {
      PunchProfile current = this.punchProfile();
      if (current != PunchProfile.HARDSYM) {
         PunchProfile target;
         if (current == PunchProfile.AGGRESSIVE) {
            target = PunchProfile.HARDSYM;
         } else {
            target = PunchProfile.AGGRESSIVE;
         }

         this.switchPunchProfile(target, "continuous_retry_round_" + round);
      }
   }

   public void handleCancelConnection(String from, JsonObject data) {
      VoxLinkMod.LOGGER.info("[Connection] Received cancel_connection signal from peer ({}), abort persistent retry", from);
      this.continuousRetryCancelled.set(true);
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state != null && state != RoomManager.PENDING) {
         state.roomInfo.removePeer(from);
         this.showConnectFailedFinal(state, "voxlink.connection.peer_cancelled");
         Minecraft mc = Minecraft.getInstance();
         if (mc != null && mc.player != null) {
            mc.execute(
               () -> mc.player
                  .sendSystemMessage(Component.translatable("voxlink.chat.error_prefix").append(Component.translatable("voxlink.connection.peer_cancelled")))
            );
         }
      }
   }

   public void notifyAllPeersGone() {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state != null && state != RoomManager.PENDING && state.roomInfo.isHost()) {
         if (state.roomInfo.hasRecentPeer(60000L)) {
            VoxLinkMod.LOGGER.info("[Connection] Peers still active in signaling, skip all-peers-gone");
         } else if (!state.roomInfo.hasEverHadPeer()) {
            VoxLinkMod.LOGGER.info("[Connection] No peer ever joined, keep waiting");
         } else if (ConnectionState.getCurrent() == ConnectionState.IDLE) {
            VoxLinkMod.LOGGER.info("[Connection] IDLE state, clear stale flags and keep waiting");
            this.connectionCycleActive.set(false);
            this.continuousRetryRound.set(0);
         } else {
            boolean inConnection = this.connectionCycleActive.get() || this.continuousRetryRound.get() > 0;
            this.continuousRetryCancelled.set(true);
            VoxLinkMod.LOGGER.info("[Connection] All peers gone (host alone), inConnection={}, abort persistent retry", inConnection);
            state.roomInfo.clearPeers();
            if (!inConnection) {
               this.connectionCycleActive.set(false);
               ConnectionState.transitionTo(ConnectionState.IDLE, "对方离开,回等待");
            } else {
               this.showConnectFailedFinal(state, "voxlink.connection.peer_left");
            }
         }
      }
   }

   private void sendCancelConnectionSignal() {
      try {
         RoomManager.RoomState state = this.roomManager.currentRoom.get();
         if (state == null || state == RoomManager.PENDING || state.roomInfo == null) {
            return;
         }

         String target = state.roomInfo.isHost() ? "all" : "host";
         this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "cancel_connection", new JsonObject(), target);
         VoxLinkMod.LOGGER.info("[Connection] Sent cancel_connection signal to peer ({})", target);
      } catch (Exception e) {
         VoxLinkMod.LOGGER.warn("[Connection] Send cancel_connection signal failed: {}", e.getMessage());
      }
   }

   public void resetIceRestartState() {
      this.iceRestartAttempts.set(0);
      this.lastIceRestartTimeMs.set(0L);
      this.savedConnectionState = null;
   }

   public void handleIceRestart(String from, JsonObject data) {
      VoxLinkMod.LOGGER.info("[Connection] Received ice_restart signal from peer ({})", from);
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state != null && state != RoomManager.PENDING && state.roomInfo != null) {
         this.performIceRestart(state, "收到对端ice_restart信号");
      } else {
         VoxLinkMod.LOGGER.info("[Connection] Received ice_restart but left room, ignore");
      }
   }

   public void triggerIceRestart() {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state == null || state == RoomManager.PENDING || state.roomInfo == null) {
         VoxLinkMod.LOGGER.info("[Connection] Transport disconnected but left room, no ICE Restart");
      } else if (!this.shouldIceRestart(state)) {
         VoxLinkMod.LOGGER.info("[Connection] Peer does not support ice_restart, go original disconnect logic");
      } else {
         this.performIceRestart(state, "传输层断开");
         this.sendIceRestartSignal(state);
      }
   }

   private boolean shouldIceRestart(RoomManager.RoomState state) {
      if (state == null || state == RoomManager.PENDING || state.roomInfo == null) {
         return false;
      } else if (state.roomInfo.isHost()) {
         return state.roomInfo.getPeers().isEmpty() ? false : state.roomInfo.getPeers().stream().allMatch(ProtocolNegotiator::supportsIceRestart);
      } else {
         return state.roomInfo.isHostLegacy() ? false : state.roomInfo.getHostCapabilities().contains("ice_restart");
      }
   }

   private void performIceRestart(RoomManager.RoomState state, String reason) {
      long now = System.currentTimeMillis();
      long last = this.lastIceRestartTimeMs.get();
      if (now - last < 5000L) {
         VoxLinkMod.LOGGER.info("[Connection] ICE Restart cooldown (within {}ms), ignore trigger ({})", 5000L, reason);
      } else {
         int attempt = this.iceRestartAttempts.incrementAndGet();
         if (attempt > 3) {
            VoxLinkMod.LOGGER.warn("[Connection] ICE Restart reached max {}, give up", 3);
         } else {
            this.lastIceRestartTimeMs.set(now);
            VoxLinkMod.LOGGER.info("[Connection] ICE Restart trigger ({}/{}): {}", new Object[]{attempt, 3, reason});
            this.connectionWon.set(false);
            this.connectionCycleActive.set(false);
            this.reversePunchAttempted.set(false);
            this.dualRaceActive = false;
            this.terracottaWon = false;
            this.voxlinkWon = false;
            this.voxlinkSideDisabled = false;
            this.dualVoxlinkBridgeFuture = null;
            if (this.connectionTimeoutFuture != null) {
               this.connectionTimeoutFuture.cancel(false);
               this.connectionTimeoutFuture = null;
            }

            this.stopAllConnectionWork();
            this.clearRelayTracking();
            ConnectionState.transitionTo(ConnectionState.STUN_PROBE, "ICE Restart " + attempt + "/3");
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
               mc.execute(
                  () -> mc.player
                     .sendSystemMessage(Component.translatable("voxlink.chat.error_prefix").append(Component.translatable("voxlink.connection.ice_restart")))
               );
            }

            RoomManager.RoomState savedState = this.savedConnectionState;
            if (savedState != null && savedState == state && this.savedConnectionHostIp != null) {
               this.connectionStartTimeMs = System.currentTimeMillis();
               int timeoutSec = this.punchProfile().connectionTimeoutSec;
               this.connectionTimeoutSec = timeoutSec;
               state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connecting"));
               this.scheduleConnectionTimeout(state, timeoutSec);
               this.scheduler
                  .schedule(
                     () -> {
                        if (this.roomManager.currentRoom.get() == savedState) {
                           this.runConnectionCycle(
                              savedState,
                              this.savedConnectionFrom,
                              this.savedConnectionHostIpv6,
                              this.savedConnectionHostIp,
                              this.savedConnectionHostPort,
                              this.savedConnectionHostMappedIp,
                              this.savedConnectionHostMappedPort,
                              0
                           );
                        }
                     },
                     500L,
                     TimeUnit.MILLISECONDS
                  );
            } else {
               VoxLinkMod.LOGGER.warn("[Connection] ICE Restart no saved params, cannot re-trigger");
            }
         }
      }
   }

   private void sendIceRestartSignal(RoomManager.RoomState state) {
      try {
         if (state.roomInfo == null) {
            return;
         }

         String target = state.roomInfo.isHost() ? "all" : "host";
         this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "ice_restart", new JsonObject(), target);
         VoxLinkMod.LOGGER.info("[Connection] Sent ice_restart signal to peer ({})", target);
      } catch (Exception e) {
         VoxLinkMod.LOGGER.warn("[Connection] Send ice_restart signal failed: {}", e.getMessage());
      }
   }

   public void markConnectionEstablished() {
      this.connectionEstablishedAtMs = System.currentTimeMillis();
   }

   public boolean isConnectionInHandoff() {
      return this.connectionEstablishedAtMs > 0L
         && System.currentTimeMillis() - this.connectionEstablishedAtMs < HANDOFF_GRACE_MS
         && !ConnectionHelper.isMcTrulyConnected();
   }

   public void notifyDualVoxlinkBridge(boolean success) {
      CompletableFuture<Void> f = this.dualVoxlinkBridgeFuture;
      if (f != null) {
         if (success) {
            f.complete(null);
         } else {
            f.completeExceptionally(new RuntimeException("VoxLink桥建立失败"));
         }
      }
   }

   private boolean claimVoxlinkDualWin() {
      if (!this.dualRaceActive) {
         return true;
      } else if (this.dualRaceWon.compareAndSet(false, true)) {
         this.voxlinkWon = true;
         return true;
      } else {
         return false;
      }
   }

   public CompletableFuture<Void> startDualP2P(String roomCode, String playerName, String password, BiConsumer<String, String> statusCallback) {
      this.resetConnectionStateForNextP2P();
      this.resetDualRaceState();
      this.resetContinuousRetryState();
      this.connectionWon.set(false);
      this.connectionWon.set(false);
      this.connectionEstablishedAtMs = 0L;
      VoxLinkMod.LOGGER
         .info(
            "[DualP2P] startDualP2P roomCode={} parallel={} isTerracotta={} isVoxLink={}",
            new Object[]{roomCode, VoxLinkMod.getConfig().isParallelP2P(), RoomCodeRouter.isTerracottaCode(roomCode), RoomCodeRouter.isVoxLinkCode(roomCode)}
         );
      if (RoomCodeRouter.isTerracottaCode(roomCode)) {
         VoxLinkMod.LOGGER.info("[DualP2P] Go pure Terracotta path");

         try {
            TerracottaManager.shutdown();
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("pre-cleanup terracotta error: {}", e.getMessage());
         }

         statusCallback.accept("terracotta", "voxlink.attempting_join.joining");
         return TerracottaManager.joinRoom(roomCode, playerName).thenAccept(connectUrl -> {
            this.connectTerracottaToMC(connectUrl, roomCode);
            statusCallback.accept("terracotta", "voxlink.connection.bridge_setup");
         });
      } else if (!RoomCodeRouter.isVoxLinkCode(roomCode)) {
         return CompletableFuture.failedFuture(new IllegalArgumentException(Component.translatable("voxlink.error.invalid_room_code").getString()));
      } else if (!VoxLinkMod.getConfig().isParallelP2P()) {
         statusCallback.accept("voxlink", "voxlink.connection.joining");
         return this.startVoxLinkP2P(roomCode, password);
      } else {
         return TerracottaManager.waitForDownload().thenCompose(ready -> {
            VoxLinkMod.LOGGER.info("[DualP2P] waitForDownload returned ready={} binaryReady={}", ready, TerracottaBinary.isReady());
            if (!ready) {
               statusCallback.accept("voxlink", "voxlink.connection.joining");
               return this.startVoxLinkP2P(roomCode, password);
            } else {
               return this.runDualP2PRace(roomCode, playerName, password, statusCallback);
            }
         });
      }
   }

   private CompletableFuture<Void> runDualP2PRace(String roomCode, String playerName, String password, BiConsumer<String, String> statusCallback) {
      VoxLinkMod.LOGGER.info("[DualP2P] Dual P2P race started roomCode={}", roomCode);
      statusCallback.accept("voxlink", "voxlink.connection.joining");
      statusCallback.accept("terracotta", "voxlink.attempting_join.joining");
      this.dualRaceActive = true;
      this.terracottaWon = false;
      this.voxlinkWon = false;
      this.voxlinkSideDisabled = false;
      this.dualFailedCount.set(0);
      this.dualRaceWon.set(false);
      CompletableFuture<Void> dualResult = new CompletableFuture<>();
      this.dualResultRef = dualResult;
      this.dualVoxlinkBridgeFuture = new CompletableFuture<>();
      CompletableFuture<Void> bridgeFuture = this.dualVoxlinkBridgeFuture;
      this.scheduler.schedule(() -> {
         if (!bridgeFuture.isDone() && !this.isPersistentRetrying()) {
            bridgeFuture.completeExceptionally(new RuntimeException("VoxLink桥建立超时"));
         }
      }, 120L, TimeUnit.SECONDS);
      CompletableFuture<Void> joinFuture = this.startVoxLinkP2P(roomCode, password);
      joinFuture.whenComplete((v, joinErr) -> {
         if (joinErr != null) {
            VoxLinkMod.LOGGER.warn("[DualP2P] VoxLink joinRoom failed: {}", joinErr.getMessage());
            statusCallback.accept("voxlink", "voxlink.dual.channel_failed");
         }
      });
      joinFuture.<Void>thenCompose(v -> bridgeFuture).whenComplete((r, e) -> {
         this.dualVoxlinkBridgeFuture = null;
         if (e == null) {
            if (this.voxlinkWon) {
               this.killAllConnectionAttempts("terracotta");
               statusCallback.accept("voxlink", "voxlink.dual.p2p_established");
               statusCallback.accept("terracotta", "voxlink.dual.status_cancelled");
               dualResult.complete(null);
               this.resetDualRaceState();
            } else {
               this.voxlinkSideDisabled = true;
               this.killAllConnectionAttempts("voxlink");
               P2PBridge.disconnect();
               statusCallback.accept("voxlink", "voxlink.dual.status_cancelled");
            }
         } else if (!this.dualRaceWon.get()) {
            if (this.isPersistentRetrying()) {
               VoxLinkMod.LOGGER.info("[DualP2P] VoxLink bridge failed but persistent retrying, keep retrying (round={})", this.continuousRetryRound.get());
               return;
            }

            statusCallback.accept("voxlink", "voxlink.dual.channel_failed");
            this.voxlinkSideDisabled = true;
            if (this.dualFailedCount.incrementAndGet() >= 2) {
               dualResult.completeExceptionally(e);
            }
         }
      });
      joinFuture.<String>thenCompose(v -> {
         RoomInfo ri = this.roomManager.getCurrentRoom();
         String tc = ri != null ? ri.getTerracottaCode() : null;
         VoxLinkMod.LOGGER.info("[DualP2P] Terracotta branch triggered terracottaCode={} binaryReady={}", tc, TerracottaBinary.isReady());
         return tc != null && !tc.isEmpty() ? TerracottaManager.joinRoom(tc, playerName) : CompletableFuture.failedFuture(new RuntimeException("host未上传陶瓦房间号"));
      }).whenComplete((connectUrl, e) -> {
         if (e == null) {
            if (this.dualRaceWon.compareAndSet(false, true)) {
               this.terracottaWon = true;
               if (this.connectionWon.get()) {
                  VoxLinkMod.LOGGER.warn("[DualP2P] Terracotta won CAS but VoxLink bridge built, skip takeover");
                  return;
               }

               this.killAllConnectionAttempts("voxlink");
               this.voxlinkSideDisabled = true;
               if (bridgeFuture != null && !bridgeFuture.isDone()) {
                  bridgeFuture.completeExceptionally(new RuntimeException("Terracotta已赢 VoxLink放弃"));
               }

               try {
                  this.connectTerracottaToMC(connectUrl, roomCode);
                  statusCallback.accept("terracotta", "voxlink.dual.p2p_established");
                  statusCallback.accept("voxlink", "voxlink.dual.status_cancelled");
                  dualResult.complete(null);
                  this.resetDualRaceState();
               } catch (Exception ex) {
                  VoxLinkMod.LOGGER.error("[DualP2P] Terracotta connect MC failed: {}", ex.getMessage());
                  statusCallback.accept("terracotta", "voxlink.dual.channel_failed");
                  dualResult.completeExceptionally(ex);
               }
            }
         } else if (!this.dualRaceWon.get()) {
            Throwable cause = e;

            while (cause.getCause() != null && cause.getCause() != cause) {
               cause = cause.getCause();
            }

            VoxLinkMod.LOGGER.warn("[DualP2P] Terracotta side failed: {}", cause.getMessage());
            statusCallback.accept("terracotta", "voxlink.dual.channel_failed");
            if (this.dualFailedCount.incrementAndGet() >= 2) {
               dualResult.completeExceptionally(e);
            }
         }
      });
      return dualResult;
   }

   private void connectTerracottaToMC(String connectUrl, String roomCode) {
      if (connectUrl != null && !connectUrl.isEmpty()) {
         int localPort = parsePortFromUrl(connectUrl);
         if (localPort <= 0) {
            throw new RuntimeException("陶瓦connectUrl解析端口失败: " + connectUrl);
         }

         RoomInfo roomInfo = this.roomManager.getCurrentRoom();
         if (roomInfo == null) {
            roomInfo = this.roomManager.setupTerracottaGuestRoom(roomCode);
         }

         ConnectionState.transitionTo(ConnectionState.TRANSPORT_SETUP, "陶瓦guest-ok port=" + localPort);
         roomInfo.setConnectionMode(Component.translatable("voxlink.connection.bridge_setup"));
         ConnectionHelper.connectToServer(localPort, roomInfo);
         VoxLinkMod.LOGGER.info("[DualP2P] Terracotta connect MC port={}", localPort);
      } else {
         throw new RuntimeException("陶瓦connectUrl为空 无法连接MC");
      }
   }

   private static int parsePortFromUrl(String url) {
      if (url == null) {
         return -1;
      }

      try {
         URI u = URI.create(url.contains("://") ? url : "tcp://" + url);
         int port = u.getPort();
         return port > 0 ? port : 25565;
      } catch (Exception e) {
         return -1;
      }
   }

   private CompletableFuture<Void> startVoxLinkP2P(String roomCode, String password) {
      return this.roomManager.joinRoom(roomCode, password).thenAccept(r -> {});
   }

   public void sendDisconnectOnFailure(RoomManager.RoomState state) {
      try {
         this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "disconnect", new JsonObject(), "host");
      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("Send disconnect failed on connection failure: {}", e.getMessage());
      }
   }

   public void startUdpPunchBridge(RoomManager.RoomState state, ReliableUdpTransport transport) {
      if (!this.terracottaWon && !this.voxlinkSideDisabled) {
         int localPort = P2PBridge.startUdpJoinerBridge(transport);
         if (localPort > 0) {
            if (this.dualRaceActive) {
               this.claimVoxlinkDualWin();
            }

            this.stopAllPunchingAfterHostBridge();
            this.connectionCycleActive.set(false);
            ConnectionState.transitionTo(ConnectionState.CONNECTED, "Joiner桥接建立 port=" + localPort);
            this.markConnectionEstablished();
            state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.bridge_setup"));
            ConnectionHelper.connectToServer(localPort, state.roomInfo);
            this.notifyDualVoxlinkBridge(true);
         } else {
            this.connectionCycleActive.set(false);
            ConnectionHelper.resetConnecting();
            ConnectionState.transitionTo(ConnectionState.FAILED, "桥接启动失败");
            state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.bridge_start_failed"), true);
            this.sendDisconnectOnFailure(state);
            this.notifyDualVoxlinkBridge(false);
            if (!this.dualRaceActive) {
               this.leaveRoomOnFailure(state);
            }
         }
      } else {
         try {
            transport.close();
         } catch (Exception var6) {
         }

         this.notifyDualVoxlinkBridge(false);
      }
   }

   public void startHostUdpPunchBridge(RoomManager.RoomState state, String clientId, ReliableUdpTransport transport) {
      int mcPort = state.roomInfo.getHostPort();
      ConnectionState.transitionTo(ConnectionState.CONNECTED, "Host桥接建立 client=" + clientId);
      state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.connected"));
      this.stopAllPunchingAfterHostBridge();
      P2PBridge.startUdpHostBridgeForClient(clientId, transport, mcPort, () -> {
         ReliableUdpTransport t = this.activeUdpTransports.remove(clientId);
         if (t != null) {
            try {
               t.close();
            } catch (Exception var5) {
            }
         }

         if (this.roomManager.currentRoom.get() == state && state != RoomManager.PENDING) {
            VoxLinkMod.LOGGER.warn("[HostBridge] client={} bridge disconnected, reset connectionWon for host re-punch", clientId);
            LogUploadManager.onDisconnected();
            this.connectionWon.set(false);
            this.connectionCycleActive.set(false);
            this.hostPunching = false;
            this.lastPunchInfoId = "";
            ConnectionState.transitionTo(ConnectionState.IDLE, "Host桥断开 client=" + clientId);
            state.roomInfo.setConnectionMode(Component.translatable("voxlink.connection.punching"));
         }
      });
   }

   public void putTransportWithIcePool(String key, ReliableUdpTransport transport) {
      ReliableUdpTransport old = this.activeUdpTransports.put(key, transport);
      if (old != null) {
         String oldKey = key + "_old";
         this.oldUdpTransports.put(oldKey, old);
         this.scheduler.schedule(() -> {
            ReliableUdpTransport t = this.oldUdpTransports.remove(oldKey);
            if (t != null) {
               try {
                  t.close();
               } catch (Exception var4x) {
               }
            }
         }, 12L, TimeUnit.SECONDS);
      }
   }

   public void sendConfirmPackets(DatagramSocket socket, InetSocketAddress addr) {
      try {
         byte[] data = new byte[]{86, 76, 2};
         DatagramPacket pkt = new DatagramPacket(data, data.length, addr.getAddress(), addr.getPort());
         socket.send(pkt);
         socket.send(pkt);
         socket.send(pkt);
      } catch (Exception e) {
         VoxLinkMod.LOGGER.warn("[Transport] Ack packet send failed: {}", e.getMessage());
      }
   }

   public void establishUdpTransport(
      RoomManager.RoomState state,
      DatagramSocket socket,
      UdpHolePuncher puncher,
      InetSocketAddress fallbackAddr,
      String transportKey,
      boolean isHost,
      String clientId
   ) throws Exception {
      puncher.waitForRecvThreadExit();
      InetSocketAddress remoteAddr = puncher.getActualRemoteAddress();
      if (remoteAddr == null) {
         remoteAddr = fallbackAddr;
      }

      this.sendConfirmPackets(socket, remoteAddr);
      ReliableUdpTransport transport = new ReliableUdpTransport(socket, remoteAddr);
      transport.setOnIceRestartRequested(this::triggerIceRestart);
      this.putTransportWithIcePool(transportKey, transport);
      // 连接成功事件: 稳定(2分钟)后不上传; 窗口内掉线仍会上传(失败诊断)
      LogUploadManager.onConnected();
      if (!isHost) {
         this.connectionCycleActive.set(false);
         ConnectionHelper.resetConnecting();
      }

      transport.start();
      if (isHost) {
         ConnectionState.transitionTo(ConnectionState.TRANSPORT_SETUP, "Host ReliableUdp启动 client=" + clientId);
         this.startHostUdpPunchBridge(state, clientId, transport);
      } else {
         ConnectionState.transitionTo(ConnectionState.TRANSPORT_SETUP, "Joiner ReliableUdp启动");
         this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "connected", new JsonObject(), "host");
         this.startUdpPunchBridge(state, transport);
      }

      this.startVoiceForwardBridge(transport, isHost);
   }

   private void startVoiceForwardBridge(ReliableUdpTransport transport, boolean isHost) {
      try {
         // host 侧本机语音/服务器已占端口，用出站中继；joiner 侧绑端口收本地客户端
         UdpForwardBridge bridge = new UdpForwardBridge(transport, isHost, 24454, 25565);
         if (bridge.isActive()) {
            this.udpForwardBridges.add(bridge);
         } else {
            bridge.close();
         }
      } catch (Exception e) {
         VoxLinkMod.LOGGER.warn("[UdpForward] voice relay failed to start: {}", e.getMessage());
      }
   }

   private void closeVoiceForwardBridges() {
      for (UdpForwardBridge b : this.udpForwardBridges) {
         try {
            b.close();
         } catch (Exception e) {
         }
      }
      this.udpForwardBridges.clear();
   }

   public void shutdown() {
      if (this.connectionTimeoutFuture != null) {
         this.connectionTimeoutFuture.cancel(false);
         this.connectionTimeoutFuture = null;
      }

      if (this.cachedUdpArray != null) {
         try {
            this.cachedUdpArray.close();
         } catch (Exception var3) {
         }

         this.cachedUdpArray = null;
      }

      this.stopAllConnectionWork();
      if (this.punchExecutor != null && !this.punchExecutor.isShutdown()) {
         this.punchExecutor.shutdown();

         try {
            this.punchExecutor.awaitTermination(2L, TimeUnit.SECONDS);
         } catch (InterruptedException var2) {
         }
      }

      this.clearRelayTracking();
      this.failedRelayPeers.clear();
   }

   private static class UdpSocketArray {
      final List<UdpHolePuncher> punchers;
      final List<StunProbe.PublicMappedAddress> mappedAddrs;
      final long createTime;
      final boolean isEasySym;

      UdpSocketArray(List<UdpHolePuncher> punchers, List<StunProbe.PublicMappedAddress> mappedAddrs, boolean isEasySym) {
         this.punchers = new ArrayList<>(punchers);
         this.mappedAddrs = new ArrayList<>(mappedAddrs);
         this.createTime = System.currentTimeMillis();
         this.isEasySym = isEasySym;
      }

      boolean isReusable(int requiredSize, boolean requiredEasySym, long now) {
         if (now - this.createTime >= 30000L) {
            return false;
         }

         if (this.isEasySym != requiredEasySym) {
            return false;
         }

         if (this.punchers.size() < requiredSize) {
            return false;
         }

         for (UdpHolePuncher p : this.punchers) {
            if (p.getSocket() == null || p.getSocket().isClosed()) {
               return false;
            }

            if (p.isPunching()) {
               return false;
            }
         }

         return true;
      }

      void close() {
         for (UdpHolePuncher p : this.punchers) {
            try {
               p.close();
            } catch (Exception var4) {
            }
         }

         this.punchers.clear();
         this.mappedAddrs.clear();
      }
   }
}
