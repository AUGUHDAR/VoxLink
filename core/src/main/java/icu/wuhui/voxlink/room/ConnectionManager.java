package icu.wuhui.voxlink.room;



import com.google.gson.JsonArray;

import com.google.gson.JsonElement;

import com.google.gson.JsonObject;

import icu.wuhui.voxlink.VoxLinkConstants;

import icu.wuhui.voxlink.platform.ChatSink;

import icu.wuhui.voxlink.platform.ClientThread;

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

import icu.wuhui.voxlink.network.PunchAuth;

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

import icu.wuhui.voxlink.network.TurnRelayClient;
import icu.wuhui.voxlink.network.StdTurnClient;

import icu.wuhui.voxlink.network.UdpPath;

import icu.wuhui.voxlink.network.UdpForwardBridge;

import icu.wuhui.voxlink.network.SignalingClient;
import icu.wuhui.voxlink.platform.Text;

import icu.wuhui.voxlink.network.StunProbe;

import icu.wuhui.voxlink.network.TcpHolePuncher;

import icu.wuhui.voxlink.network.UPnPManager;

import icu.wuhui.voxlink.network.UdpHolePuncher;

import icu.wuhui.voxlink.terracotta.RoomCodeRouter;

import icu.wuhui.voxlink.terracotta.TerracottaBinary;

import icu.wuhui.voxlink.terracotta.TerracottaManager;

import java.net.DatagramPacket;

import java.net.DatagramSocket;

import java.net.InetSocketAddress;

import java.net.Socket;

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


public class ConnectionManager {

   final RoomManager roomManager ;

   final SignalingClient signalingClient ;

   final ScheduledExecutorService scheduler ;
   private static final int JOIN_TRANSIENT_MAX_ATTEMPTS = 3;
   private static final long[] JOIN_RETRY_BACKOFF_MS = new long[]{1500L, 3000L};
   final java.util.concurrent.atomic.AtomicInteger joinAttemptGeneration = new java.util.concurrent.atomic.AtomicInteger();

   final ExecutorService punchExecutor;

   final ConcurrentHashMap<String, UdpHolePuncher> activeHolePunchers = new ConcurrentHashMap<>();

   final List<UdpForwardBridge> udpForwardBridges = new CopyOnWriteArrayList<>();

   final ConcurrentHashMap<String, ReliableUdpTransport> activeUdpTransports = new ConcurrentHashMap<>();

   final ConcurrentHashMap<String, ReliableUdpTransport> oldUdpTransports = new ConcurrentHashMap<>();






















   final Map<String, Long> failedRelayPeers = new ConcurrentHashMap<>();

   private final AtomicReference<String> currentRelayPeer = new AtomicReference<>(null);

   private volatile ScheduledFuture<?> relayFailoverTask = null;

   private volatile int nextRelayEligibleRound = 0;

   private volatile boolean manualRelayInProgress = false;

   /** 手动中继总超时：90s 到期后 tryRelay 递归立刻终止并走 failed_timeout 分桶。 */
   private volatile long manualRelayDeadline = 0L;

   /** 房主收到 relay_request 时 host_notice toast 节流：避免多房客同时请求刷屏。 */
   private volatile long lastHostNoticeAt = 0L;

   // ================= TURN 中继（协议契约：SPECS/turn-protocol-v1.md） =================
   /** 本端 TURN 会话；null=未启用。发起方(guest)=ROLE_GUEST，自动配合方(host)=ROLE_HOST。 */
   volatile TurnRelayClient.TurnSession turnSession = null;
   /** 标准 TURN 会话（RFC 5766，与 turnSession 互斥——同一会话只走一种协议栈）。 */
   volatile StdTurnClient.StdTurnSession stdTurnSession = null;
   /** TURN 通路对端标识：guest 侧恒为 "host"，host 侧为 joiner 的 clientId。 */
   volatile String turnPeerId = null;
   /** TURN 数据面 transport（guest 侧建好后等 turn_ready 再 start）。 */
   volatile ReliableUdpTransport turnTransport = null;
   /** TURN 全流程进行中（防重复触发 + 按钮置灰）。 */
   private volatile boolean turnInProgress = false;
   /** 后台 P2P 升级监视器（10 分钟窗口，2026-10-05 用户规则）任务句柄与截止时刻。 */
   private volatile ScheduledFuture<?> turnBgMonitorTask = null;
   private volatile long turnBgDeadlineMs = 0L;
   /** 切换观测任务句柄（次路径稳定性计数）。 */
   private volatile ScheduledFuture<?> turnSwitchWatchTask = null;
   private final AtomicInteger turnSwitchStreak = new AtomicInteger(0);
   private final AtomicBoolean turnBgPunchWon = new AtomicBoolean(false);
   /** 10 分钟窗口耗尽：本连接周期不再后台尝试 P2P，退出重进才重置。 */
   private volatile boolean turnP2pGivenUp = false;
   /** 平滑切换已完成（直连已接管、TURN 已释放）。 */
   private volatile boolean turnSwitchedToP2p = false;
   /** TURN 保活定时任务（teardown 必须取消，防任务泄漏）。 */
   private volatile ScheduledFuture<?> turnKeepaliveTask = null;
   /** 后台监视器 tick 计数（30s/次；奇数次触发玩家中继相位 = 60s）。 */
   private final AtomicInteger turnBgTicks = new AtomicInteger(0);
   /**
    * 玩家中继热备（TURN 模式下后台静默建立的 G1 备用通路）：
    * 不建桥、不动连接状态；TURN 死亡时自动转正 + 程序化重连，把"手动退出重进"变成几秒自愈。
    */
   private volatile ReliableUdpTransport turnHotstandbyTransport = null;
   /** TURN 会话代次: 拆会话路径递增; 异步写回前校验, 防 teardown 先到复活孤儿会话 */
   private final AtomicInteger turnSessionEpoch = new AtomicInteger(0);

   final AtomicBoolean connectionCycleActive = new AtomicBoolean(false);

   private final AtomicBoolean reversePunchAttempted = new AtomicBoolean(false);

   final AtomicBoolean connectionWon = new AtomicBoolean(false);
   // turn_ready 应用门: 信令双线程(WS push/HTTP poll)并发分发时防双建桥
   private final AtomicBoolean turnReadyApplied = new AtomicBoolean(false);
   // 热备保活句柄: teardown 必须取消, 否则每次热备泄漏一个15s任务
   private volatile ScheduledFuture<?> hotstandbyKeepaliveTask;
   // TURN bind/桥类长任务专用池, 防止阻塞信令单线程scheduler
   private static final java.util.concurrent.ExecutorService TURN_BG_EXECUTOR =
      java.util.concurrent.Executors.newCachedThreadPool(r -> {
         Thread t = new Thread(r, "VoxLink-TurnBg");
         t.setDaemon(true);
         return t;
      });
   // TCP 双向打洞在飞占位: 防同轮重复邀约
   final AtomicBoolean tcpPunchInFlight = new AtomicBoolean(false);

   private final List<ConnectionFallback> activeFallbacks = new CopyOnWriteArrayList<>();


   final AtomicBoolean continuousRetryCancelled = new AtomicBoolean(false);

   final AtomicInteger continuousRetryRound = new AtomicInteger(0);

   private volatile PunchFailureClassifier.FailureReason lastFailureReason;

   private final AtomicInteger consecutiveFailureCount = new AtomicInteger(0);



   private final AtomicInteger iceRestartAttempts = new AtomicInteger(0);

   private final AtomicLong lastIceRestartTimeMs = new AtomicLong(0L);

   private volatile RoomManager.RoomState savedConnectionState;

   volatile String savedConnectionFrom = "";

   volatile String hostRevPunchPeer;

   private volatile String savedConnectionHostIpv6;

   private volatile String savedConnectionHostIp;

   volatile int savedConnectionHostPort;

   volatile String savedConnectionHostMappedIp;

   private volatile int savedConnectionHostMappedPort;

   volatile ScheduledFuture<?> connectionTimeoutFuture;

   private volatile ScheduledFuture<?> connectionCycleSafetyTimeout;

   private volatile long connectionStartTimeMs;

   /** UI"使用中继"按钮计时基准：打洞首周期只设一次，持续重试轮/ICE重启不重置；0=本会话尚未开始打洞。 */
   private volatile long punchUiStartMs;

   private volatile int connectionTimeoutSec;

   volatile StunProbe.ProbeResult stunProbeResult;

   final AtomicReference<CompletableFuture<StunProbe.ProbeResult>> stunProbeFutureRef = new AtomicReference<>();

   volatile List<Integer> lastHostMappedPorts;
   final java.util.concurrent.ConcurrentHashMap<String, HostPunchContext> hostPunchContexts = new java.util.concurrent.ConcurrentHashMap<>();

   private volatile boolean relayConnectedSignaled = false;


   volatile CompletableFuture<Void> dualVoxlinkBridgeFuture ;


   volatile boolean dualRaceActive = false;

   volatile boolean terracottaWon = false;

   volatile boolean voxlinkWon = false;

   volatile boolean voxlinkSideDisabled = false;

   volatile CompletableFuture<Void> dualResultRef;

   final AtomicInteger dualFailedCount = new AtomicInteger(0);

   final AtomicBoolean dualRaceWon = new AtomicBoolean(false);

   private static final long HANDOFF_GRACE_MS = 5000L;

   private volatile long connectionEstablishedAtMs = 0L;

   volatile PunchResult lastPunchResult;

   volatile NatClass localNatClass = NatClass.UNKNOWN;

   volatile NatClass remoteNatClass = NatClass.UNKNOWN;

   volatile ScenarioTier.Tier scenarioTier = ScenarioTier.Tier.NORMAL;

   volatile PunchProfile activePunchProfile = PunchProfile.DEFAULT;

   volatile PunchParams activePunchParams ;

   volatile CompletableFuture<Void> relayPrefetchFuture ;


   private static final long[] BACKOFF_DELAYS_MS = new long[]{1000L, 2000L, 4000L};

   static final int JOIN_QUEUE_RETRY_SEC = 10;

   // 零收包终态：持续重试已达该轮数且全会话从未收到对端任何UDP包时，宣布最终失败并通知对端停手。
   // 3轮（约2-3分钟）太激进，玩家感知为"无限打洞被砍"；20轮（约20分钟+）对真人会话等效无限，
   // 仅兜住真正死链（双端对称NAT且预测全空）不再无限空转。
   private static final int ZERO_RECV_FINAL_ROUND_LIMIT = 20;

   final AtomicBoolean sessionPunchRecvEver = new AtomicBoolean(false);

   // PREDICTION_OFF 封顶: birthday attack 风暴(joiner 进程/网络异常导致 host 永收不到 PUNCH 时)
   // 同会话连续累计 50 次 PREDICTION_OFF 后提前终止打洞, 防止 2 秒 2520 次 PUNCH / 420 次日志烧带宽 + 刷爆日志。
   // 与 ZERO_RECV_FINAL_ROUND_LIMIT 正交: 后者管"整个会话零收包", 本常量管"PREDICTION_OFF 累计频次"。
   static final int PREDICTION_OFF_CAP = 50;

   final AtomicInteger sessionPredictionOffCount = new AtomicInteger(0);

   // 幽灵连接防护：joiner的"connected"就绪信令先于host桥建立到达时暂存，startHostUdpPunchBridge时消费
   final Set<String> peerReadyConfirmations = ConcurrentHashMap.newKeySet();

   // 打洞占用期间排队的join_request重放任务(按peer记录)，对端断开时作废
   final ConcurrentHashMap<String, List<ScheduledFuture<?>>> queuedJoinReplays = new ConcurrentHashMap<>();

   // 排队中的join_request载荷(按peer记录)，host连接获胜后立即放行
   final ConcurrentHashMap<String, JsonObject> queuedJoinPayloads = new ConcurrentHashMap<>();

   final AddressBlacklist addressBlacklist = AddressBlacklist.get();
   // punchAuthV1：当前连接周期的打洞认证密钥（applyPunchTemplate 注入每个 puncher）
   volatile byte[] activePunchAuthKey ;
   // punch_info 信号去重(防打洞重试风暴撞服务器限流): 上次已发送的 mapped 地址与时间
   private volatile String lastPunchInfoPayload = "";
   private volatile long lastPunchInfoSendMs = 0L;

   volatile UdpSocketArray cachedUdpArray ;


   private static volatile ConnectionManager instance;




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



   ConnectionFallback trackFallback(ConnectionFallback f) {

      this.activeFallbacks.add(f);

      return f;

   }



   void cancelAllFallbacks() {

      for (ConnectionFallback f : this.activeFallbacks) {

         try {

            f.cancel();

         } catch (Exception var4) {

         }

      }



      this.activeFallbacks.clear();

   }



UdpSocketArray getOrCreateUdpArray(int requiredSize, boolean isEasySym, List<String> stunUrls) {
      return TransportBridgeRegistry.getOrCreateUdpArray(this, requiredSize, isEasySym, stunUrls);
   }



   static int calculatePortDelta(List<Integer> samples) {
      return PunchProfileCtrl.calculatePortDelta(samples);
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



   PunchProfile punchProfile() {

      PunchProfile p = this.activePunchProfile;

      return p != null ? p : PunchProfile.DEFAULT;

   }



   public PunchProfile getActivePunchProfile() {
      return PunchProfileCtrl.getActivePunchProfile(this);
   }



   void applyPunchTemplate(UdpHolePuncher puncher) {
      PunchProfileCtrl.applyPunchTemplate(this, puncher);
   }



   /**

    * punchAuthV1 密钥派生（直连 host↔joiner 链路）：

    * key = SHA-256(tag || roomToken || joinerClientId)，两端可独立、无损地算出同一密钥。

    * 激活门控 = 对端能力表含 punchAuthV1（host 视角查 joiner 能力，joiner 视角查房主能力）。

    * 任一端为旧版本 → 返回 null，完全走旧格式（互操作不回归）。

    */

   byte[] derivePunchAuthKey(RoomManager.RoomState state, String peerClientId, boolean peerIsHost) {
      return PunchProfileCtrl.derivePunchAuthKey(this, state, peerClientId, peerIsHost);
   }



   /** 装载当前连接周期的打洞认证密钥（在打洞发起前的信令处理点调用）。 */

   void setupPunchAuthForPeer(RoomManager.RoomState state, String peerClientId, boolean peerIsHost) {
      PunchProfileCtrl.setupPunchAuthForPeer(this, state, peerClientId, peerIsHost);
   }



   /**
    * STUN 服务器列表按本会话探测阶段的实测可达结果重排（可达者前置）。
    * 静态表前几位是境内服务器（miwifi/hitv），海外网络不可达时 quad/兜底探测
    * 每轮先白烧 4×800ms（实测 FJXMKG 每轮拖慢约 10s）；用探测赢家前置可即答。
    */

   List<String> stunUrlsProbedFirst() {
      return StunNatClassifier.stunUrlsProbedFirst(this);
   }



   volatile long lastProfileSwitchMs = 0L;



   void switchPunchProfile(PunchProfile target, String reason) {
      PunchProfileCtrl.switchPunchProfile(this, target, reason);
   }



   private boolean punchSkipDirect() {
      return PunchProfileCtrl.punchSkipDirect(this);
   }



   public boolean isConnectionCycleActive() {

      return this.connectionCycleActive.get();

   }

   public boolean isConnectionActive() {

      return this.connectionCycleActive.get() || this.isAnyHostPunching() || this.connectionWon.get() || this.continuousRetryRound.get() > 0;

   }



   public StunProbe.ProbeResult getStunProbeResult() {
      return StunNatClassifier.getStunProbeResult(this);
   }



   public NatClass getLocalNatClass() {

      return this.localNatClass;

   }



   public NatClass getRemoteNatClass() {

      return this.remoteNatClass;

   }



   public String getConnectionDifficultyKey() {
      return PunchProfileCtrl.getConnectionDifficultyKey(this);
   }



   public void setStunProbeResult(StunProbe.ProbeResult result) {
      StunNatClassifier.setStunProbeResult(this, result);
   }



   public AtomicReference<CompletableFuture<StunProbe.ProbeResult>> getStunProbeFutureRef() {
      return StunNatClassifier.getStunProbeFutureRef(this);
   }



   public void setConnectionCycleActive(boolean value) {

      this.connectionCycleActive.set(value);

   }



   void scheduleConnectionCycleSafety(RoomManager.RoomState state) {

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



   void cancelConnectionCycleSafety() {

      if (this.connectionCycleSafetyTimeout != null) {

         this.connectionCycleSafetyTimeout.cancel(false);

         this.connectionCycleSafetyTimeout = null;

      }

   }



   public void setReversePunchAttempted(boolean value) {

      this.reversePunchAttempted.set(value);

   }



public void clearActiveHolePunchers() {
      UdpPunchEngine.clearActiveHolePunchers(this);
   }



public void clearHostPunchingState() {
      UdpPunchEngine.clearHostPunchingState(this);
   }



public void stopAllPunchingAfterHostBridge() {
      UdpPunchEngine.stopAllPunchingAfterHostBridge(this);
   }



private void stopAllPunchingForRelay() {
      UdpPunchEngine.stopAllPunchingForRelay(this);
   }



   // canShowRelayButton / cancelManualRelay 已随 2026-10-05 手动中继按钮退役删除：
   // 中继触发统一走智能自动编排器（fireAutoTurn / fireAutoPlayerRelay）。



   public void triggerManualRelay() {

      if (VoxLinkMod.getConfig().isRelayEnabled()) {

         RoomManager.RoomState state = this.roomManager.currentRoom.get();

         if (state != null && state != RoomManager.PENDING) {

            if (!this.manualRelayInProgress) {

               this.manualRelayInProgress = true;
               icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.player_relay");

               // 启动 90s 总超时：tryRelay 递归批次(8s/批) 多了会无限循环, 必须有兜底。
               this.manualRelayDeadline = System.currentTimeMillis() + 90_000L;

               int currentRound = this.continuousRetryRound.get();

               boolean isSymmetric = this.stunProbeResult != null && this.stunProbeResult.natType.isSymmetric();

               this.nextRelayEligibleRound = currentRound + (isSymmetric ? 2 : 4);

               VoxLinkMod.LOGGER.info("[Relay] Manual relay triggered at round={}, next eligible round={}", currentRound, this.nextRelayEligibleRound);

               this.stopAllPunchingForRelay();

               state.roomInfo.setConnectionMode(Text.translatable("voxlink.relay.trying"));

               this.tryRelay(state);

            }

         }

      }

   }



   public boolean isManualRelayInProgress() {

      return this.manualRelayInProgress;

   }



   /** 当前批尝试号（用于下方槽位显示「第 N 轮：M 个候选…」）。无进行中时返回 0。 */

   private final java.util.concurrent.atomic.AtomicInteger relayBatchIndex = new java.util.concurrent.atomic.AtomicInteger(0);

   /** 玩家中继 v2 子通道分配计数（chan 1..7 循环；0 保留给本端自有会话）。 */
   private final java.util.concurrent.atomic.AtomicInteger relayMuxChanCounter = new java.util.concurrent.atomic.AtomicInteger(0);

   private int allocateRelayMuxChan() {
      return 1 + (Math.abs(this.relayMuxChanCounter.getAndIncrement()) % 7);
   }



   private final java.util.concurrent.atomic.AtomicInteger relayBatchSize = new java.util.concurrent.atomic.AtomicInteger(0);



   /** 阶段文案：searching / trying_batch / null。render() 会读它显示在下方槽位。 */

   private volatile Text relayProgressText = null;



   /** 中继进行中时下方槽位显示文案（null 表示走原有的 terracotta 状态文本）。 */

   public Text getRelayProgressText() {

      return this.relayProgressText;

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

         // 失败后把 nextRelayEligibleRound 拉回当前轮: 玩家立即可再点按钮或回到直连,

         // 与 "中继失败，继续尝试直连打洞..." 的按钮文案语义对齐。

         int currentRound = this.continuousRetryRound.get();

         if (currentRound > 0) {

            this.nextRelayEligibleRound = currentRound;

         }

         this.relayProgressText = null;

         this.manualRelayDeadline = 0L;

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
      TransportBridgeRegistry.clearActiveUdpTransports(this);
   }



   public void stopAllConnectionWork() {
      JoinSession.stopAllConnectionWork(this);
   }



public UdpHolePuncher removeHolePuncher(String key) {
      return UdpPunchEngine.removeHolePuncher(this, key);
   }



   public ReliableUdpTransport removeUdpTransport(String key) {
      return TransportBridgeRegistry.removeUdpTransport(this, key);
   }



   public boolean hasUdpTransport(String key) {
      return TransportBridgeRegistry.hasUdpTransport(this, key);
   }



   public void handleJoinRequest(String from, JsonObject data) {
      JoinSession.handleJoinRequest(this, from, data);
   }



   public void handleJoinRequest(String from, JsonObject data, int retryCount) {

      RoomManager.RoomState state = this.roomManager.currentRoom.get();

      VoxLinkMod.LOGGER

         .info("[RoomManager] Received join_request from {}, state={}", from, state != null && state != RoomManager.PENDING ? "active" : "null/pending");

      // 先过期陈旧的punching占用标志，防止打洞已结束后join_request仍被无限排队(MM6SDJ)

      this.expireStaleHostPunchContexts();

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

            // punchAuthV1：joiner 声明了能力才启用打洞/数据面 MAC 认证（host 视角）

            this.setupPunchAuthForPeer(state, from, false);

         }



         // MC版本观测(S822QF)：仅记录日志供远端排障，不做任何拦截。
         // 产品决策2026-08-26：兼容性交还原版与Via——对方装了Via自然能进；
         // 没装Via时原版在登录阶段自有明确提示；mod层拦截属越俎代庖且有误伤面。

         if (data.has("gameVersion") && !data.get("gameVersion").isJsonNull()) {

            String joinerGameVersion = data.get("gameVersion").getAsString();

            int joinerProto = VoxLinkConstants.getProtocolVersion(joinerGameVersion);

            if (joinerProto > 0 && joinerProto != VoxLinkConstants.PROTOCOL_VERSION) {

               VoxLinkMod.LOGGER.info("[handleJoinRequest] joiner MC version differs from host (joiner={}, proto={}, host={}, proto={}) — proceeding, vanilla/Via will handle compatibility",
                  new Object[]{joinerGameVersion, joinerProto, VoxLinkConstants.GAME_VERSION, VoxLinkConstants.PROTOCOL_VERSION});

            }

         }



         if (!this.isAnyHostPunching() && !this.activeHolePunchers.containsKey("host")) {

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

            // 重试耗尽不再默默丢弃：挂起等打洞周期结束后重放(最多再等120s)，对端断开时作废

            VoxLinkMod.LOGGER.info("[RoomManager] join_request retry exhausted while punching, defer {} until punch cycle ends", from);

            this.scheduleDeferredJoinReplay(state, from, data, 0);

         } else if (this.activeUdpTransports.containsKey(from)) {

            VoxLinkMod.LOGGER.info("[RoomManager] Client {} has active transport, ignore duplicate join_request", from);

         } else {

            VoxLinkMod.LOGGER.info("[RoomManager] Punching in progress, queue join_request from {} (retry {}/3)", from, retryCount + 1);

            this.queuedJoinPayloads.put(from, data);

            int nextRetry = retryCount + 1;

            // H3a: 闭包创建时捕获房间码,执行时校验,避免换房后排队的旧任务
            // 错误地在新房间上重放 join_request。
            String capturedRoomCode = state.roomInfo.getCode();

            ScheduledFuture<?> queuedRetry = this.scheduler.schedule(() -> {

               this.queuedJoinPayloads.remove(from);

               RoomManager.RoomState st = this.roomManager.currentRoom.get();

               if (st != null && st != RoomManager.PENDING && st.roomInfo.isHost()) {

                  if (capturedRoomCode == null || !capturedRoomCode.equals(st.roomInfo.getCode())) {

                     VoxLinkMod.LOGGER.warn("[RoomManager] Drop queued join_request from {}: room changed (captured={}, current={})", new Object[]{from, capturedRoomCode, st.roomInfo.getCode()});

                     return;

                  }

                  if (this.activeUdpTransports.containsKey(from)) {

                     VoxLinkMod.LOGGER.info("[RoomManager] Client {} connected, skip queued retry", from);

                  } else {

                     VoxLinkMod.LOGGER.info("[RoomManager] Retry queued join_request from {}", from);

                     this.handleJoinRequest(from, data, nextRetry);

                  }

               }

            }, 10L, TimeUnit.SECONDS);

            // 登记排队任务：对端断开时作废其排队条目

            this.registerQueuedJoinReplay(from, queuedRetry);

         }

      }

   }



public void sendHolepunchOffer(RoomManager.RoomState state, String from) {
      UdpPunchEngine.sendHolepunchOffer(this, state, from);
   }



public void handleHolePunchOffer(String from, JsonObject data) {
      UdpPunchEngine.handleHolePunchOffer(this, from, data);
   }



   void applyProbeResultToActiveConnection(RoomManager.RoomState state, String hostIp) {

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
         this.applyLocalNatClass(this.classifyLocalNat(), "probe_done");
         this.applyRemoteNatClass(this.classifyRemoteNatLogged(state), "probe_done");
         PunchProfile recommended = NatClass.recommendProfile(this.localNatClass, this.remoteNatClass);
         this.switchPunchProfile(recommended, "probe_done_" + this.localNatClass + "x" + this.remoteNatClass);

         VoxLinkMod.LOGGER

            .info(

               "[handleHolePunchOffer] Probe upgrade: local={} remote={} -> profile={}",

               new Object[]{this.localNatClass, this.remoteNatClass, this.punchProfile().describeInstance()}

            );

      }

   }



void finishHandleHolePunchOffer(

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
      UdpPunchEngine.finishHandleHolePunchOffer(this, state, from, finalHostIpv6, finalHostIp, finalHostPort, finalHostMappedIp, finalHostMappedPort, finalHostSymmetric, finalHostMappedPortDelta);
   }



public void handleHolepunchMapped(String from, JsonObject data) {
      UdpPunchEngine.handleHolepunchMapped(this, from, data);
   }



public void handlePunchInfo(String from, JsonObject data) {
      UdpPunchEngine.handlePunchInfo(this, from, data);
   }



public void handleHostPunchInfo(RoomManager.RoomState state, String from, JsonObject data) {
      UdpPunchEngine.handleHostPunchInfo(this, state, from, data);
   }



public void handleJoinerPunchInfo(RoomManager.RoomState state, String from, JsonObject data) {
      UdpPunchEngine.handleJoinerPunchInfo(this, state, from, data);
   }



   public void handlePeerPort(String from, JsonObject data) {
      JoinSession.handlePeerPort(this, from, data);
   }



public void handleReverseHolepunchOffer(String from, JsonObject data) {
      UdpPunchEngine.handleReverseHolepunchOffer(this, from, data);
   }



void handleReverseHolepunchOfferDelayed(String from, JsonObject data) {
      UdpPunchEngine.handleReverseHolepunchOfferDelayed(this, from, data);
   }



public void handleReversePunchInfo(String from, JsonObject data) {
      UdpPunchEngine.handleReversePunchInfo(this, from, data);
   }



public void handleTcpSimopenRequest(String from, JsonObject data) {
      UdpPunchEngine.handleTcpSimopenRequest(this, from, data);
   }



public void handleHolePunchAnswer(String from, JsonObject data) {
      UdpPunchEngine.handleHolePunchAnswer(this, from, data);
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



   static boolean sameIpFamily(String a, String b) {
      return PunchProfileCtrl.sameIpFamily(a, b);
   }


String peerPunchPrefix(String peerId) {
      return UdpPunchEngine.peerPunchPrefix(this, peerId);
   }

HostPunchContext hostPunchContext(String peerId) {
      return UdpPunchEngine.hostPunchContext(this, peerId);
   }

boolean isAnyHostPunching() {
      return UdpPunchEngine.isAnyHostPunching(this);
   }

void clearHostPunchContexts() {
      UdpPunchEngine.clearHostPunchContexts(this);
   }
 
   static int natRank(NatClass c) {
      return StunNatClassifier.natRank(c);
   }

   boolean applyLocalNatClass(NatClass next, String reason) {
      if (next == null || natRank(next) <= natRank(this.localNatClass)) {
         return false;
      } else {
         VoxLinkMod.LOGGER.info("[Connection] Update localNat {} -> {} ({})", this.localNatClass, next, reason);
         this.localNatClass = next;
         // NAT认知升级后旧调参作废, 重学避免错误基线贯穿
         this.activePunchParams = null;
         this.lastPunchResult = null;
         return true;
      }
   }

   boolean applyRemoteNatClass(NatClass next, String reason) {
      if (next == null || natRank(next) <= natRank(this.remoteNatClass)) {
         return false;
      } else {
         VoxLinkMod.LOGGER.info("[Connection] Update remoteNat {} -> {} ({})", this.remoteNatClass, next, reason);
         this.remoteNatClass = next;
         this.activePunchParams = null;
         this.lastPunchResult = null;
         return true;
      }
   }
 
   private NatClass classifyLocalNat() {
      return StunNatClassifier.classifyLocalNat(this);
   }



   // 对端"自报的 NAT 串"与"我们自己观测到的 NatClass"不一致时留一条痕（只记录，不参与任何判定）。
   // 为什么不直接把自报串接进 classifyRemoteNat：那是别人客户端给的数，一旦谎报 full_cone，
   // recommendProfile 就会从 AGGRESSIVE 掉到 FAST_LANE（6s 超时 / 15 轮 / range 20）—— 实打实的连通率退步。
   // 先让 1.1.9 自己量出"报对 / 报错"的比例，再决定要不要按"观测优先、自报只兜底"接进去。
   final Set<String> remoteNatMismatchLogged = new HashSet<>();
 
   NatClass classifyRemoteNatLogged(RoomManager.RoomState state) {
      return StunNatClassifier.classifyRemoteNatLogged(this, state);
   }

   // 只认无歧义的探测值。open/moderate/strict 这类早期 API 串一律算 UNKNOWN：
   // moderate 既可能是受限锥也可能是对称，猜错方向的代价由打洞策略承担，不在这里替它下结论。 
   static NatClass reportedNatClass(String raw) {
      return StunNatClassifier.reportedNatClass(raw);
   }
 
   NatClass classifyRemoteNat(RoomManager.RoomState state) {
      return StunNatClassifier.classifyRemoteNat(this, state);
   }



   private boolean shouldPrefetchRelay(NatClass local, NatClass remote, RoomManager.RoomState state) {
      return PunchProfileCtrl.shouldPrefetchRelay(this, local, remote, state);
   }



   private CompletableFuture<Void> prefetchRelayCandidates(RoomManager.RoomState state) {
      return PunchProfileCtrl.prefetchRelayCandidates(this, state);
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

         // PREDICTION_OFF 封顶快速失败: birthday attack 风暴下避免无限空转 + 日志被刷爆。
         // 与 ZERO_RECV_FINAL_ROUND_LIMIT 正交, 此处用相同终态结论确保对端停手 + 进入 fallback。
         if (this.sessionPredictionOffCount.get() >= PREDICTION_OFF_CAP && !this.sessionPunchRecvEver.get()) {
            VoxLinkMod.LOGGER.warn("[UdpHolePuncher] PREDICTION_OFF cap reached ({}), abort punch", this.sessionPredictionOffCount.get());
            ConnectionState.transitionTo(ConnectionState.FAILED, "PREDICTION_OFF 封顶");
            this.showConnectFailed(state, "voxlink.connection.max_cycles_exceeded");
         } else {

         int maxCycles = this.getEffectiveMaxCycles();

         if (cycle >= maxCycles) {

            if (!this.connectionWon.get()) {

               if (this.continuousRetryRound.get() >= ZERO_RECV_FINAL_ROUND_LIMIT && !this.sessionPunchRecvEver.get()) {

               // 持续重试已打满且全会话从未收到对端任何UDP包：给出终态结论并让对端也停手(WB4V72/MTNFW4)

               this.declareZeroRecvFinalFailure(state);

            } else if (this.shouldContinuousRetry(state)) {

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



                  state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.retry_round", new Object[]{round}));

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
               if (this.continuousRetryRound.get() == 0) {
                  this.activePunchParams = null;
               }

               this.applyLocalNatClass(this.classifyLocalNat(), "cycle_start");
               this.applyRemoteNatClass(this.classifyRemoteNatLogged(state), "cycle_start");

               PunchProfile recommended;

               int tierReach = this.stunProbeResult != null ? this.stunProbeResult.reachableStunUrls.size() : 0;

               boolean tierLowReach = tierReach > 0 && tierReach <= 3;

               boolean tierLocalSym = this.localNatClass.isSymmetric();

               boolean tierRemoteSym = this.remoteNatClass.isSymmetric() || state.roomInfo.isHostSymmetric();

               this.scenarioTier = ScenarioTier.classify(tierLocalSym, tierRemoteSym, tierLowReach, false, false);

               recommended = NatClass.recommendProfile(this.localNatClass, this.remoteNatClass);

               this.switchPunchProfile(recommended, "nat_matrix_" + this.localNatClass + "x" + this.remoteNatClass);

               icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.probing");
               VoxLinkMod.LOGGER

                  .info(

                     "[Connection] Layer1 NAT classification: local={} remote={} tier={} -> profile={}",

                     new Object[]{this.localNatClass, this.remoteNatClass, ScenarioTier.key(this.scenarioTier), this.punchProfile().describeInstance()}

                  );

               if (this.shouldPrefetchRelay(this.localNatClass, this.remoteNatClass, state) && this.relayPrefetchFuture == null) {

                  this.relayPrefetchFuture = this.prefetchRelayCandidates(state).exceptionally(e -> {

                     VoxLinkMod.LOGGER.warn("[Connection] Layer2 relay pre-check failed: {}", e.getMessage());

                     return null;

                  });

               }



               this.connectionStartTimeMs = System.currentTimeMillis();

               if (this.punchUiStartMs == 0L) {

                  this.punchUiStartMs = this.connectionStartTimeMs;

               }

               icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.punching", cycle + 1);
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

               state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.probing"));

               RoomManager.RoomState probingState = state;

               this.scheduler.schedule(() -> {

                  if (this.roomManager.currentRoom.get() == probingState && !this.connectionWon.get()) {

                     Text current = probingState.roomInfo.getConnectionMode();

                     if (current != null && current.toPlainString().equals(Text.translatable("voxlink.connection.probing").toPlainString())) {

                        probingState.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.punching"));

                     }

                  }

               }, 15L, TimeUnit.SECONDS);

            } else {

               state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.punching"));

            }



            this.tryConnectionStep(state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, 0);

         }

         }

      }

   }



   // PREDICTION_OFF 封顶记账: 每次 punch 失败调用此方法, 若 reason=PREDICTION_OFF 则自增会话级计数。
   // 原始日志保留, 维持可观测性。abort 由调用方在每个 site 决定(本方法只负责计数 + 撞线日志)。
   void recordPunchFailure(PunchFailureClassifier.FailureReason reason, int recvPunch, int recvAck) {
      PunchProfileCtrl.recordPunchFailure(this, reason, recvPunch, recvAck);
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



   void extendConnectionTimeoutIfNeeded(RoomManager.RoomState state) {

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

            // 连接成功后取消 85s 全局超时计时器, 防止干扰后续重连 / 下一轮
            if (this.connectionTimeoutFuture != null) { this.connectionTimeoutFuture.cancel(false); this.connectionTimeoutFuture = null; }

         } else {

            switch (step) {

               case 0:

                  icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.lan");
                  VoxLinkMod.LOGGER.info("[Connection] Wave 1: LAN+IPv6+UDP parallel (cycle {}/{})", displayCycle, maxCycles);

                  state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.punching"));

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

                  icu.wuhui.voxlink.ui.UiLogBus.push(2, "voxlink.logui.tcp_fallback");
                  VoxLinkMod.LOGGER.info("[Connection] Wave 2: TCP fallback parallel (cycle{}/{})", displayCycle, maxCycles);

                  ConnectionState.transitionTo(ConnectionState.TCP_FALLBACK, "Wave 2 TCP兜底");

                  state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.punching"));

                  new AtomicBoolean(false);

                  List<CompletableFuture<ConnectionFallback.ConnectResult>> wave2Futures = new ArrayList<>();

                  // TCP 双向打洞附加尝试: UDP 败一轮后才启动

                  this.tryTcpHolePunch(state, hostMappedIp, cycle);

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

         // 连接成功后取消 85s 全局超时计时器, 防止干扰后续重连 / 下一轮
         if (this.connectionTimeoutFuture != null) { this.connectionTimeoutFuture.cancel(false); this.connectionTimeoutFuture = null; }

      } else {

         state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.connecting"));

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

         List<String> quadStun = this.stunUrlsProbedFirst();

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
            if (this.applyLocalNatClass(newLocal, "punch_socket_sym")) {
               this.applyRemoteNatClass(this.classifyRemoteNatLogged(state), "punch_socket_sym");
            }

            PunchProfile recommended = NatClass.recommendProfile(this.localNatClass, this.remoteNatClass);

            this.switchPunchProfile(recommended, "punch_socket_sym_" + this.localNatClass + "x" + this.remoteNatClass);

            VoxLinkMod.LOGGER

               .info("[Connection] Probe upgrade: local={} remote={} -> profile={}",

                  new Object[]{this.localNatClass, this.remoteNatClass, this.punchProfile().describeInstance()});

         }



         if (myMappedAddr == null) {

            myMappedAddr = puncher.discoverMappedAddress(this.stunUrlsProbedFirst());

         }



         if (myMappedAddr == null) {

            VoxLinkMod.LOGGER.warn("[Connection] Punch socket STUN failed, try temp socket fallback (attempt{})", attempt);

            DatagramSocket tmp = null;



            try {

               tmp = new DatagramSocket();

               tmp.setSoTimeout(1000);

               myMappedAddr = StunProbe.discoverMappedAddress(tmp, this.stunUrlsProbedFirst());

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

            if ((state.roomInfo.getClientId() != null || state.roomInfo.isHost()) && attempt == 1) {

               String myNatType = this.stunProbeResult != null ? this.stunProbeResult.natType.key : "unknown";

               boolean relayOk = VoxLinkMod.getConfig().isRelayEnabled();

               this.signalingClient

                  .registerRelayPeer(relayPeerId(state), state.roomInfo.getCode(), myNatType, myMappedAddr.ip(), myMappedAddr.port(), relayOk);

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



            // punch_info 信号去重: 打洞重试轮次里 mapped 地址未变时不再重发——
            // 实测多socket×每cycle重发会撞服务器限流(RATE_LIMITED), 且连带把关键
            // 的 relay/turn 注册一起限流死(日志实证: 打洞失败的同时 relay/register 连续被拒)
            String punchInfoPayload = myMappedAddr.ip() + ":" + myMappedAddr.port();
            long nowMs = System.currentTimeMillis();
            boolean skipDuplicatePunchInfo = punchInfoPayload.equals(this.lastPunchInfoPayload)
               && nowMs - this.lastPunchInfoSendMs < 3000L;
            if (skipDuplicatePunchInfo) {
               VoxLinkMod.LOGGER.debug("[Connection] punch_info unchanged ({}), skip duplicate send", punchInfoPayload);
            } else {
               this.lastPunchInfoPayload = punchInfoPayload;
               this.lastPunchInfoSendMs = nowMs;
               this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "punch_info", punchData, "host");
            }

            if (this.connectionWon.get()) {

               VoxLinkMod.LOGGER.info("[Connection] Connection succeeded during STUN probe, abort attempt{}", attempt);

               puncher.close();

            } else {

               this.activeHolePunchers.put("joiner", puncher);

               puncher.setOnPeerPunchReceived(addr -> {

                  this.sessionPunchRecvEver.set(true);

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

                                    UdpHolePuncher.observeBlacklistedTarget(punchTargetAddr.getAddress(), punchTargetAddr.getPort());

                  if (this.stunProbeResult != null && this.stunProbeResult.natType.isEasySymmetric() && state.roomInfo.isHostEasySym()) {

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

                           this.sessionPunchRecvEver.set(true);

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

                           this.sessionPunchRecvEver.set(true);

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

                                          new Object[]{cycle, Math.min(attempt, this.punchProfile().punchMaxAttempts), this.punchProfile().punchMaxAttempts, reason, result.socketsReceivedPunch, result.socketsReceivedAck}

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

                                                .info("[Connection] Retry UDP punch after {}ms (attempt {}/{})", new Object[]{delay, Math.min(attempt + 1, this.punchProfile().punchMaxAttempts), this.punchProfile().punchMaxAttempts});

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

                                             VoxLinkMod.LOGGER.error("[Connection] Create UDP transport failed: {}", e.getClass().getSimpleName() + ":" + e.getMessage());

                                             try {

                                                puncherRef.close();

                                             } catch (Exception var7x) {

                                             }



                                             // 僵尸连接修复：punch 成功回调已 CAS 置 connectionWon=true，showConnectFailed
                                             // 的 !connectionWon 守卫会整体跳过→玩家卡"已连接"但无传输（MCZHTH 实锤：
                                             // transport 建立失败后 1 分钟只剩 transport silent）。回滚已赢标记再走
                                             // 标准失败路径（持续重试或终态），不留僵尸态
                                             this.connectionWon.set(false);

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

                                       new Object[]{displayCycle, maxCycles, Math.min(attempt, this.punchProfile().punchMaxAttempts), this.punchProfile().punchMaxAttempts, e.getMessage()}

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

                                             .info("[Connection] Retry UDP punch after {}ms (attempt {}/{})", new Object[]{delay, Math.min(attempt + 1, this.punchProfile().punchMaxAttempts), this.punchProfile().punchMaxAttempts});

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

                                             .info("[Connection] Retry UDP punch after {}ms (attempt {}/{})", new Object[]{delay, Math.min(attempt + 1, this.punchProfile().punchMaxAttempts), this.punchProfile().punchMaxAttempts});

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

                                             VoxLinkMod.LOGGER.error("[Connection] Create UDP transport failed: {}", e.getClass().getSimpleName() + ":" + e.getMessage());

                                             try {

                                                puncherRef.close();

                                             } catch (Exception var7x) {

                                             }



                                             // 僵尸连接修复：punch 成功回调已 CAS 置 connectionWon=true，showConnectFailed
                                             // 的 !connectionWon 守卫会整体跳过→玩家卡"已连接"但无传输（MCZHTH 实锤：
                                             // transport 建立失败后 1 分钟只剩 transport silent）。回滚已赢标记再走
                                             // 标准失败路径（持续重试或终态），不留僵尸态
                                             this.connectionWon.set(false);

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

                                       new Object[]{displayCycle, maxCycles, Math.min(attempt, this.punchProfile().punchMaxAttempts), this.punchProfile().punchMaxAttempts, e.getMessage()}

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

                                             .info("[Connection] Retry UDP punch after {}ms (attempt {}/{})", new Object[]{delay, Math.min(attempt + 1, this.punchProfile().punchMaxAttempts), this.punchProfile().punchMaxAttempts});

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

               VoxLinkMod.LOGGER.info("[Connection] Retry UDP punch after {}ms (attempt {}/{})", new Object[]{delay, Math.min(attempt + 1, this.punchProfile().punchMaxAttempts), this.punchProfile().punchMaxAttempts});

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
      return StunNatClassifier.selectReachableStunGroup(this, cycle);
   }



   public void startReversePunch(RoomManager.RoomState state) {

      icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.reverse");
      VoxLinkMod.LOGGER.info("[ReversePunch] Parallel start reverse punch");

      state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.punching"));

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

      CompletableFuture.<UdpSocketArray>supplyAsync(() -> this.getOrCreateUdpArray(socketCount, isEasySym, this.stunUrlsProbedFirst()))

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

      List<String> quadStun = this.stunUrlsProbedFirst();

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

         myMappedAddr = puncher.discoverMappedAddress(this.stunUrlsProbedFirst());

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

                  this.sessionPunchRecvEver.set(true);

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

                           this.recordPunchFailure(reason, result.socketsReceivedPunch, result.socketsReceivedAck);

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

                  this.sessionPunchRecvEver.set(true);

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

                        this.recordPunchFailure(reason, result.socketsReceivedPunch, result.socketsReceivedAck);

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

      state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.punching"));

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

               state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.bridge_setup"));

               ConnectionHelper.connectToServer(localPort, state.roomInfo);

               this.notifyDualVoxlinkBridge(true);

            } else {

               this.connectionCycleActive.set(false);

               ConnectionHelper.resetConnecting();

               state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.bridge_start_failed"), true);

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

               state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.bridge_setup"));

               ConnectionHelper.connectToServer(localPort, state.roomInfo);

               this.notifyDualVoxlinkBridge(true);

            } else {

               this.connectionCycleActive.set(false);

               ConnectionHelper.resetConnecting();

               state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.bridge_start_failed"), true);

               this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "disconnect", new JsonObject(), "host");

               if (!this.dualRaceActive) {

                  this.handleConnectViaBridgeFailed(state);

               }



               this.notifyDualVoxlinkBridge(false);

            }

         });

      }

   }



   public void connectFed(RoomManager.RoomState state) {
      String addr = state.roomInfo.getFedAddress();
      int sep = addr == null ? -1 : addr.lastIndexOf(':');
      if (sep <= 0 || sep == addr.length() - 1) {
         state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.fed_failed"), true);
         return;
      }
      String host = addr.substring(0, sep);
      int port;
      try {
         port = Integer.parseInt(addr.substring(sep + 1));
      } catch (NumberFormatException e) {
         state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.fed_failed"), true);
         return;
      }
      state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.fed_setup"));
      P2PBridge.connectToHost(host, port).thenAccept(localPort -> {
         if (localPort > 0) {
            this.connectionWon.set(true);
            this.markConnectionEstablished();
            ConnectionHelper.connectToServer(localPort, state.roomInfo);
         } else {
            ConnectionHelper.resetConnecting();
            state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.fed_failed"), true);
         }
      });
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



            Text status = fallback.getStatusText();

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



         Text finalStatus = fallback.getStatusText();

         if (finalStatus != null) {

            state.roomInfo.setConnectionMode(finalStatus);

         }

      }, "VoxLink-FallbackMonitor");

      t.setDaemon(true);

      t.start();

   }



   /**
    * 持续重试轮次的自动中继触发（1.1.5）：仅 joiner 侧发起（host 被动响应 turn_alloc）。
    * 前置：中继开关开启、未在 TURN/手动中继中、未连上；60s 节流。
    * 失败无害：teardownTurn 后继续走重试循环。
    */

   private boolean enterContinuousRetryRound(RoomManager.RoomState state) {

      if (this.connectionWon.get()) {

         return false;

      }



      if (!this.shouldContinuousRetry(state)) {

         return false;

      }

      if (this.continuousRetryRound.get() >= ZERO_RECV_FINAL_ROUND_LIMIT && !this.sessionPunchRecvEver.get()) {

         // 持续重试已打满且全会话双向零收包：宣布终态，不再进入新一轮(返回true避免调用方覆盖终态UI)

         this.declareZeroRecvFinalFailure(state);

         return true;

      }



      if (this.savedConnectionState != state) {

         VoxLinkMod.LOGGER.warn("[Connection] Persistent retry aborted: saved params mismatch current room");

         return false;

      }



      int round = this.continuousRetryRound.incrementAndGet();

      this.escalateProfileForRound(round);

      icu.wuhui.voxlink.ui.UiLogBus.push(2, "voxlink.logui.retry_round", round);
      VoxLinkMod.LOGGER.info("[Connection] Enter persistent retry round={}, level={}, reset cycle from 0", round, this.punchProfile().describeInstance());

      ConnectionState.transitionTo(ConnectionState.STUN_PROBE, "持续重试 round " + round);

      if (this.connectionTimeoutFuture != null) {

         this.connectionTimeoutFuture.cancel(false);

         this.connectionTimeoutFuture = null;

      }



      state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.retry_round", new Object[]{round}));

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
      JoinSession.showConnectFailed(this, state);
   }



   public void showConnectFailed(RoomManager.RoomState state, String reasonKey) {

      if (this.roomManager.currentRoom.get() == state && state != RoomManager.PENDING) {

         if (!this.connectionWon.get()) {

            if (reasonKey != null && reasonKey.contains("relay")) {

               if (state != null && state.roomInfo != null) {

                  state.roomInfo.setConnectionMode(Text.translatable(reasonKey), true);

               }



                              icu.wuhui.voxlink.ui.UiLogBus.push(3, reasonKey);
               ClientThread.runOnClientThread(() -> {

                  ChatSink.chat(Text.translatable("voxlink.chat.error_prefix").append(Text.translatable(reasonKey)));

               });

            }



            if (!this.enterContinuousRetryRound(state)) {

               this.showConnectFailedFinal(state, reasonKey);

            }

         }

      }

   }



   private void tryRelay(RoomManager.RoomState state) {

      // 总超时: 90s 内没成功就停递归, 走 failed_timeout 分桶; 房客 20s 等待不受影响。
      if (this.manualRelayInProgress && this.manualRelayDeadline > 0L
         && System.currentTimeMillis() >= this.manualRelayDeadline) {
         VoxLinkMod.LOGGER.warn("[Relay] Manual relay overall timeout (90s) reached, abort recursive tryRelay");
         this.manualRelayDeadline = 0L;
         this.notifyRelayFailed();
         this.relayFailOrAuto(state, "voxlink.relay.failed_timeout");
         return;
      }

      if (!VoxLinkMod.getConfig().isRelayEnabled()) {

         VoxLinkMod.LOGGER.info("[Relay] Relay disabled by config, skip tryRelay");

         this.notifyRelayFailed();

         this.relayFailOrAuto(state, "voxlink.connection.relay_unavailable");

      } else if (this.currentRelayPeer.get() != null) {

         VoxLinkMod.LOGGER.info("[Relay] Relay already trying (current={}), skip duplicate", this.currentRelayPeer.get());

      } else {

         if (!state.roomInfo.isHost()) {

            if (this.currentRelayPeer.get() != null) {

               return;

            }



            this.currentRelayPeer.set("joiner_requesting");

            // 房客发起 relay_request 后下方槽位: "正在寻找适合中继的玩家…"
            this.relayProgressText = Text.translatable("voxlink.relay.searching");

            JsonObject data = new JsonObject();

            data.addProperty("clientId", state.roomInfo.getClientId());

            this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "relay_request", data, "host");

            this.relayFailoverTask = this.scheduler.schedule(() -> {

               if (!this.connectionWon.get()) {

                  this.currentRelayPeer.set(null);

                  this.relayFailoverTask = null;

                  this.notifyRelayFailed();

                  this.relayFailOrAuto(state, "voxlink.relay.failed_host_no_response");

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

                  } else if (!ProtocolNegotiator.supportsRelayMux(p) || !this.activeUdpTransports.get(p.clientId).isMuxEnabled()) {

                     // v2 数据面门槛：候选者必须声明 relayMuxV1 且其传输已 mux 化，
                     // 否则走不了子通道（v1 裸流转发已被实证为串流污染，绝不降级回去）
                     VoxLinkMod.LOGGER.info("[Relay] Candidate {} lacks relayMuxV1/mux transport, skip", p.clientId);

                  } else {

                     candidates.add(p);

                  }

               }

            }



            if (candidates.isEmpty()) {

               VoxLinkMod.LOGGER.info("[Relay] No available Cone relay node (excluded failed={})", this.failedRelayPeers.size());

               this.failedRelayPeers.clear();

               this.notifyRelayFailed();

               this.relayFailOrAuto(state, "voxlink.relay.failed_no_candidates");

               return;

            }
            // 阶段文本: 筛选到候选, 即将并行尝试 → "正在寻找适合中继的玩家…" 闪一瞬, 然后立刻被 trying_batch 覆盖。
            this.relayProgressText = Text.translatable("voxlink.relay.searching");



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

                  this.relayFailOrAuto(state, "voxlink.connection.relay_unavailable");

                  return;

               }



               this.currentRelayPeer.set(relayCandidates.get(0).clientId);

               int batchNo = this.relayBatchIndex.incrementAndGet();

               this.relayBatchSize.set(parallelN);

               // 阶段文案: 房主分支每次开始新批次时显示「第 N 轮：M 个候选…」, 失败/成功时清空。
               this.relayProgressText = Text.translatable("voxlink.relay.trying_batch",

                  new Object[]{Integer.toString(batchNo), Integer.toString(parallelN)});

               VoxLinkMod.LOGGER.info("[Relay] Parallel try {} Cone relays, target Sym={}", parallelN, symPeer.clientId);



               for (int ix = 0; ix < parallelN; ix++) {

                  RoomInfo.PeerInfo relay = relayCandidates.get(ix);

                  JsonObject setup = new JsonObject();

                  setup.addProperty("targetClientId", symPeer.clientId);

                  setup.addProperty("targetIp", symPeer.mappedIp);

                  setup.addProperty("targetPort", symPeer.mappedPort);
                  setup.addProperty("replyHostId", relayPeerId(state));

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



   void clearRelayTracking() {

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

            // 仅房主向玩家展示中继提示；房客收到 relay_request 不弹"房主"提示
            if (state.roomInfo.isHost()) {

               // 信令线程不可直调 GUI：包一层主线程调度

               // 节流: 多房客同时发 relay_request 时, 房主 toast 不再每请求一次弹一次, 改为 10s 一次。
               long nowMs = System.currentTimeMillis();
               boolean shouldShowNotice = (nowMs - this.lastHostNoticeAt) >= 10_000L;
               if (shouldShowNotice) {
                  this.lastHostNoticeAt = nowMs;
               }

               if (shouldShowNotice) {

                  ClientThread.runOnClientThread(() -> {

                     ChatSink.chat(Text.translatable("voxlink.relay.host_notice"));

                  });

               }

            }



            RoomInfo.PeerInfo requestingPeer = state.roomInfo.getPeer(requestingClientId);

            if (requestingPeer != null && requestingPeer.mappedIp != null && requestingPeer.mappedPort > 0) {

               // 玩家中继 v2 候选池（2026-10-05 用户裁决修订）：
               // 优先本房间内已连接的非对称 NAT 玩家（mux 数据面要求候选者与请求方 host
               // 之间有已建立传输）；跨房全局池需要"虚拟加入"对洞机制，下迭代接入同一套数据面。
               // v1 全局池路径（fetchGlobalRelayCandidates）已被实证为跨房间数据注入（候选者
               // 复用其连往自己房间 host 的传输转发），永久停用。
               this.tryRelay(state);

            } else {

               this.signalingClient

                  .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_declined", new JsonObject(), requestingClientId);

            }

         }

      }

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
      setup.addProperty("replyHostId", relayPeerId(state));

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

         // 玩家中继 v2：relay_accept 携带候选者在其 joiner 传输上分配的子通道号。
         // host 侧把该子通道桥到一个全新 MC socket（被中继房客的独立会话）。
         int muxChan = data.has("chan") ? data.get("chan").getAsInt() : 0;

         if (forClientId != null && muxChan > 0) {

            ReliableUdpTransport candidateTransport = this.activeUdpTransports.get(from);

            if (candidateTransport != null && candidateTransport.isMuxEnabled()) {

               int mcPort = state.roomInfo.getHostPort();

               P2PBridge.startUdpHostBridgeForRelayedClient(forClientId, candidateTransport, muxChan, mcPort, () ->

                  VoxLinkMod.LOGGER.info("[Relay] relayed session {} via {} chan{} closed", forClientId, from, muxChan)

               );

               VoxLinkMod.LOGGER.info("[Relay] relay_accept from {} for {} chan={}, subchannel bridge armed", from, forClientId, muxChan);

               JsonObject notify = new JsonObject();

               notify.addProperty("connected", true);

               this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "relay_notify", notify, forClientId);

            } else {

               // 候选者传输缺失/未 mux 化（如候选者刚好退出）：不通知 connected，
               // sym 侧 8s 超时自然走 failover 下一候选
               VoxLinkMod.LOGGER.warn("[Relay] relay_accept from {} but carrier transport missing/not mux, ignore", from);

            }

         } else if (forClientId != null) {

            // 旧版候选者（无 chan 字段）：v1 数据面已被实证为跨流污染，绝不接旧线

            VoxLinkMod.LOGGER.warn("[Relay] relay_accept without chan (legacy candidate {}), ignore (v1 relay data plane retired)", from);

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

            // 成功路径补: 不重置 manualRelayInProgress 会让 AttemptingJoinScreen 误闪 3s 失败文字。
            this.manualRelayInProgress = false;

            this.relayProgressText = null;

            this.manualRelayDeadline = 0L;

            state.roomInfo.setConnectionMode(Text.translatable("voxlink.relay.connected_via").withStyle(Text.Style.builder().color(Text.ChatFormat.YELLOW).build()));

            state.roomInfo.setUsingRelay(true);

         } else if (!VoxLinkMod.getConfig().isRelayEnabled()) {

            VoxLinkMod.LOGGER.info("[Relay] Relay disabled by config, reject relay_notify");

         } else {

            String relayIp = data.has("relayIp") ? data.get("relayIp").getAsString() : null;

            int relayPort = data.has("relayPort") ? data.get("relayPort").getAsInt() : 0;

            if (relayIp != null && relayPort > 0) {

               // TURN 已在承载：玩家中继仅作热备——punch 通中继玩家后只存不用，
               // 不进入下方建桥流程（防止与 TURN 桥双桥冲突）
               if (this.isTurnActive() && this.turnHotstandbyTransport == null) {
                  this.establishHotstandby(state, relayIp, relayPort);
                  return;
               }

               VoxLinkMod.LOGGER.info("[Relay] Received relay_notify, punch to Cone {}:{}", relayIp, relayPort);

               state.roomInfo.setConnectionMode(Text.translatable("voxlink.relay.trying"));

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

                              this.recordPunchFailure(reason, result.socketsReceivedPunch, result.socketsReceivedAck);

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

                                 state.roomInfo.setConnectionMode(Text.translatable("voxlink.relay.connected_via"));

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

                                    // 网络线程不可直调 GUI：包一层主线程调度

                                    ClientThread.runOnClientThread(() -> {

                                       ChatSink.chat(Text.translatable("voxlink.relay.connected_via"));

                                    });

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

                     // 漏了这两步会让 manualRelayInProgress/currentRelayPeer 悬空:
                     // 导演每拍被 manualRelayInProgress 挡住, 下一次 fireAutoTurn 被静默吞
                     this.clearRelayTracking();
                     this.notifyRelayFailed();
                     this.relayFailOrAuto(state, "voxlink.connection.relay_failed");

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

         this.relayFailOrAuto(state, "voxlink.connection.relay_failed");

      }

   }



   /**
    * 本端在 relay_peers 里的身份。房客=自己的 clientId；房主没有 clientId，
    * 由服务端验过 hostToken 后派生 host-&lt;房间号&gt;（客户端自报该形态一律被拒，冒充不了别人）。
    */
   String relayPeerId(RoomManager.RoomState state) {
      String cid = state != null ? state.roomInfo.getClientId() : null;
      if (cid != null && !cid.isEmpty()) {
         return cid;
      }
      return state != null && state.roomInfo.isHost() ? "host-" + state.roomInfo.getCode() : null;
   }

   void scheduleRelayRegistrationRenewal(RoomManager.RoomState state, String natType, String ip, int port) {

      this.scheduler

         .schedule(

            () -> {

               if (this.roomManager.currentRoom.get() == state && !this.connectionWon.get() && relayPeerId(state) != null) {

                  if (ip != null && port > 0) {

                     boolean relayOk = VoxLinkMod.getConfig().isRelayEnabled();

                     this.signalingClient

                        .registerRelayPeer(relayPeerId(state), state.roomInfo.getCode(), natType, ip, port, relayOk)

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

         // 成功路径补: 与 handleRelayNotify 同步, 避免 AttemptingJoinScreen 误闪失败文字。
         this.manualRelayInProgress = false;

         this.relayProgressText = null;

         this.manualRelayDeadline = 0L;

         this.stopAllPunchingAfterHostBridge();

         this.activeHolePunchers.entrySet().removeIf(e -> e.getKey().startsWith("hp_"));
         if (this.connectionWon.compareAndSet(false, true)) {
            state.roomInfo.setConnectionMode(Text.translatable("voxlink.relay.connected_via").withStyle(Text.Style.builder().color(Text.ChatFormat.YELLOW).build()));

            state.roomInfo.setUsingRelay(true);

         }

      }

   }



   // ================= TURN 中继状态机（发起方=房客；房主全自动配合，无 UI 无选择权） =================

   public boolean isTurnInProgress() {
      return this.turnInProgress;
   }

   /** TURN 通路是否活跃（未切换到 P2P 前都算，供按钮隐藏/状态显示）。 */
   public boolean isTurnActive() {
      return (this.turnSession != null || this.stdTurnSession != null) && !this.turnSwitchedToP2p;
   }

   public boolean isTurnP2pGivenUp() {
      return this.turnP2pGivenUp;
   }

   /** from 是否为本端当前 TURN 对端（guest 侧恒为 "host"，host 侧为 joiner clientId）。多房客时防误杀他人会话。 */
   public boolean isTurnPeer(String from) {
      return from != null && (this.turnSession != null || this.stdTurnSession != null) && from.equals(this.turnPeerId);
   }

   /**
    * 加入屏下方状态行的 TURN 段：null=无 TURN 状态（行槽让给玩家中继/Terracotta）。
    * 与打洞状态行（上方）分离，互不覆盖——中继有没有开始/到哪步/失败与否在此行全程可见。
    */
   public Text getTurnStatusText() {
      if (this.turnInProgress) {
         return Text.translatable("voxlink.turn.connecting");
      } else if (this.turnSession == null) {
         return null;
      } else {
         ReliableUdpTransport t = this.turnTransport;
         if (t != null && t.isConnected() && !this.turnSwitchedToP2p) {
            return Text.translatable("voxlink.relay.connected_via");
         } else {
            return this.turnSwitchedToP2p
               ? Text.translatable("voxlink.turn.switched_p2p")
               : Text.translatable("voxlink.turn.connecting");
         }
      }
   }

   /** 打洞开始时刻（UI 的 20 秒"使用中继"按钮计时基准）。 */
   public long getConnectionStartTimeMs() {
      return this.connectionStartTimeMs;
   }

   /** UI 按钮专用打洞计时基准：会话首周期设一次、轮次/重启不复位；0=尚未开始（防 0/旧值误判成已超 20s）。 */
   public long getPunchUiStartMs() {
      return this.punchUiStartMs;
   }

   /**
    * 房客点击"使用中继"：拉节点列表 → UDP 应用层测延迟 → 选最低延迟 → allocate → BIND →
    * 发 turn_alloc 交 host 票据 → 等 host turn_ready。全程异步，失败走 teardownTurn。
    */
   public void triggerTurnRelay() {
      this.turnReadyApplied.set(false);
      if (this.turnInProgress || this.turnSession != null || this.manualRelayInProgress) {
         return;
      }
      // 新 TURN 会话复位旧标记: turnSwitchedToP2p=true 会短路 20s turn_ready 兜底、
      // 自cancel 后台升级监视器、让按钮永久失效(1.1.5 TURN 审计 P1-3)
      this.turnSwitchedToP2p = false;

      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state == null || state == RoomManager.PENDING || state.roomInfo.isHost()) {
         return;
      }

      // 边缘情况：玩家点按钮的瞬间原打洞恰好打通——直连已赢，TURN 没有必要，直接放弃
      if (this.connectionWon.get()) {
         VoxLinkMod.LOGGER.info("[Turn] direct connection already won, skip TURN request");
         return;
      }

      this.turnInProgress = true;
      final int turnEpoch = this.turnSessionEpoch.get();
      icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.turn_relay");
      state.roomInfo.setConnectionMode(Text.translatable("voxlink.turn.connecting"));
      SignalingClient sc = this.signalingClient;
      CompletableFuture
         .supplyAsync(() -> {
            List<TurnRelayClient.NodeInfo> nodes = TurnRelayClient.fetchNodeList(sc).join();
            if (nodes.isEmpty()) {
               throw new IllegalStateException("NO_NODES");
            }

            List<TurnRelayClient.ProbeResult> probed = TurnRelayClient.probeNodes(nodes).join();
            if (probed.isEmpty()) {
               throw new IllegalStateException("UNREACHABLE");
            }

            // 探测全超时不直接判死（弱网丢包下探测包全丢很常见）：照常 allocate+bind，
            // bind 自带重试，网络缓过来即可成功；真不通会以 BIND_FAILED_* 落到同一失败分支
            if (probed.get(0).rttMs < 0) {
               VoxLinkMod.LOGGER.warn("[Turn] all {} node probes timed out, attempting bind anyway (lossy network?)", probed.size());
            } else {
               VoxLinkMod.LOGGER.info("[Turn] guest picked node {} rtt={}ms (nodes={})", probed.get(0).node.id, probed.get(0).rttMs, nodes.size());
               icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.turn_node", probed.get(0).rttMs);
            }
            return probed.get(0).node;
         })
         .thenCompose(node -> {
            // 标准 TURN（双方 stdTurnV1 + 节点双栈 stdTurnPort>0）→ RFC 5766 流程；
            // 任一端旧版/节点未启用 → 自定义协议 v1（现有路径，零行为变化）
            if (node.stdTurnPort > 0 && ProtocolNegotiator.hostSupportsStdTurn(state.roomInfo)) {
               VoxLinkMod.LOGGER.info("[Turn] node {} stdTurnPort={}, host stdTurnV1: using standard TURN (RFC 5766)", node.id, node.stdTurnPort);
               return this.startStdTurnGuestFlow(state, sc, node).thenApply(ok -> (Object)ok);
            }
            icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.turn_allocating");
            return TurnRelayClient.allocate(sc, state.roomInfo.getCode(), state.roomInfo.getClientId(), state.roomInfo.getToken(), node.id)
               .thenCompose(alloc -> {
            if (alloc == null) {
               throw new IllegalStateException("ALLOC_FAILED");
            }

            // 竞态二次检查：列表+测延迟+allocate 耗费数秒，期间直连可能已打通
            if (this.connectionWon.get() && this.turnSession == null) {
               TurnRelayClient
                  .release(sc, state.roomInfo.getCode(), state.roomInfo.getClientId(), state.roomInfo.getToken(), alloc.sessionIdHex)
                  .exceptionally(e -> null);
               throw new IllegalStateException("P2P_WON");
            }

            byte[] sid = new byte[16];

            for (int i = 0; i < 16; i++) {
               sid[i] = (byte)Integer.parseInt(alloc.sessionIdHex.substring(i * 2, i * 2 + 2), 16);
            }

            TurnRelayClient.TurnSession session =
               new TurnRelayClient.TurnSession(alloc.sessionIdHex, sid, alloc.host, alloc.port, TurnRelayClient.ROLE_GUEST, alloc.guestTicket, alloc.expireSec);
                        // 3 轮重试(每轮5发): 弱网单轮全丢很常见(实证 09-11 22:42 guest 5发全丢)
            int code = TurnRelayClient.bindWithRetry(session, 3);
            // code=4 对齐 host 容忍: 首发响应丢失重放被判冲突, 会话实际已绑定
            if (code == TurnRelayClient.BIND_ROLE_CONFLICT) {
               code = TurnRelayClient.BIND_OK;
               VoxLinkMod.LOGGER.info("[Turn] guest bind ROLE_CONFLICT tolerated (session already bound)");
            }
            if (code != TurnRelayClient.BIND_OK) {
               session.unbind();
               throw new IllegalStateException("BIND_FAILED_" + code);
            }

            // 1.1.5 写回屏障: 35s 总超时触发的 teardown 已把 turnInProgress 置 false 时,
            // 异步链在此中止写回(否则"复活"出无看门狗的孤儿会话, 审计 P1-1)
            if (!this.turnInProgress || turnEpoch != this.turnSessionEpoch.get()) {
               session.unbind();
               VoxLinkMod.LOGGER.warn("[Turn] guest flow cancelled during bind (timeout teardown won), discard session");
               throw new IllegalStateException("TURN_CANCELLED");
            }

            this.turnSession = session;
            this.startTurnKeepalive(session);
            VoxLinkMod.LOGGER.info("[Turn] guest session {} bound via {}:{}, sending turn_alloc", alloc.sessionIdHex.substring(0, 8), alloc.host, alloc.port);
            UdpPath.Codec codec = new TurnRelayClient.TurnPathCodec(sid, TurnRelayClient.ROLE_GUEST, TurnRelayClient.ROLE_HOST);
            ReliableUdpTransport transport = new ReliableUdpTransport(session.socket, session.endpoint(), codec);
            // TURN 路径密钥现场重派生, 不沿用打洞阶段的 activePunchAuthKey:
            // 打洞阶段密钥可能陈旧(重进房/join_request 被 defer), 沿用会与 host 互丢
            // (实证 RBW6HX 1.1.4: TURN path up 后双方互丢 rudp-data, 30s 内链路死亡)
            byte[] turnAuthKey = this.derivePunchAuthKey(state, null, true);
            if (turnAuthKey != null) {
               transport.setAuthKey(turnAuthKey);
               this.activePunchAuthKey = turnAuthKey;
               VoxLinkMod.LOGGER.info("[PunchAuth] TURN transport auth enabled (guest side)");
            } else {
               VoxLinkMod.LOGGER.info("[PunchAuth] TURN transport without auth (host caps missing)");
            }
            // 互操作降级开关: 对端为旧引擎(未武装密钥)时连续认证失败自动回明文, 不再互丢致死
            transport.allowAuthDowngradeForInterop();
            // TURN载具补接ICE重启回调
            transport.setOnIceRestartRequested(this::triggerIceRestart);

            this.turnTransport = transport;
            this.turnPeerId = "host";

            JsonObject data = new JsonObject();
            data.addProperty("sessionId", alloc.sessionIdHex);
            data.addProperty("host", alloc.host);
            data.addProperty("port", alloc.port);
            data.addProperty("ticket", alloc.hostTicket);
            data.addProperty("expire", alloc.expireSec);
            data.addProperty("clientId", state.roomInfo.getClientId());
            // 显式声明本端 punchAuthV1 能力: host 侧 TURN 建链时据此直接派生密钥,
            // 不再依赖 host 本地 peer 表(join_request 被 defer 时表项可能缺失/陈旧)
            data.addProperty("punchAuth", ProtocolNegotiator.selfSupports(ProtocolNegotiator.CAP_PUNCH_AUTH_V1));
            return sc
               .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "turn_alloc", data, "host")
               .thenApply(r -> session);
               })
               .thenApply(s -> (Object)s);
         })
                  // 35s: bind 升级为 3 轮重试后最长 ~16s, 加上列表/探测/allocate 需要更大总预算
         .orTimeout(35L, TimeUnit.SECONDS)
         .whenComplete((session, ex) -> {
            if (ex != null || session == null) {
               VoxLinkMod.LOGGER.warn("[Turn] guest flow failed: {}", ex != null ? ex.getMessage() : "null");
               this.teardownTurn(state, "voxlink.turn.failed");
            } else {
               // host 数据面就绪回执有 20s 兜底（handleTurnReady 到达即 start+桥接）
               // 保险 v2: 20s 先强制补拉一次再判死; 信令不健康时延寿重查, 总窗≤65s(≤90s 有界)
               this.scheduler.schedule(() -> this.turnReadyWatchdog(state, 0), 20L, TimeUnit.SECONDS);
            }
         });
   }

   /**
    * 标准 TURN guest 流程（RFC 5766）：签 cred → Allocate → 发 turn_alloc（带本端 relay 地址）
    * → 等 host turn_ready（handleStdTurnReady 里 ChannelBind + 建 transport + 桥接）。
    */
   private CompletableFuture<Boolean> startStdTurnGuestFlow(RoomManager.RoomState state, SignalingClient sc, TurnRelayClient.NodeInfo node) {
      icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.turn_allocating");
      return CompletableFuture.supplyAsync(() -> {
         final int turnEpoch = this.turnSessionEpoch.get();
         StdTurnClient.StdCred cred = StdTurnClient
            .fetchCred(sc, state.roomInfo.getCode(), state.roomInfo.getClientId(), state.roomInfo.getToken(), node.id)
            .join();
         if (cred == null) {
            throw new IllegalStateException("STD_CRED_FAILED");
         }

         // 竞态二次检查：列表+探测+cred 耗费数秒，期间直连可能已打通
         if (this.connectionWon.get() && this.stdTurnSession == null) {
            throw new IllegalStateException("P2P_WON");
         }

         StdTurnClient.StdTurnSession session = StdTurnClient.allocate(cred.host, cred.port, cred.username, cred.password, 8000);
         if (session == null) {
            throw new IllegalStateException("STD_ALLOC_FAILED");
         }

         // 写回屏障：35s 总超时的 teardown 先到时中止（对齐自定义协议 1.1.5 屏障语义）
         if (!this.turnInProgress || turnEpoch != this.turnSessionEpoch.get()) {
            StdTurnClient.close(session);
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn guest flow cancelled during allocate (timeout teardown won), discard session");
            throw new IllegalStateException("TURN_CANCELLED");
         }

         this.stdTurnSession = session;
         this.startStdTurnKeepalive(session);
         this.turnPeerId = "host";
         VoxLinkMod.LOGGER.info("[Turn] stdTurn guest allocated relay={} via {}:{}, sending turn_alloc", session.relayAddr, cred.host, cred.port);
         return session;
      }, TURN_BG_EXECUTOR).thenCompose(session -> {
         JsonObject data = new JsonObject();
         data.addProperty("stdTurn", true);
         data.addProperty("nodeId", node.id);
         data.addProperty("nodeHost", node.host);
         data.addProperty("stdTurnPort", node.stdTurnPort);
         data.addProperty("relayHost", session.relayAddr.getAddress().getHostAddress());
         data.addProperty("relayPort", session.relayAddr.getPort());
         data.addProperty("clientId", state.roomInfo.getClientId());
         data.addProperty("punchAuth", ProtocolNegotiator.selfSupports(ProtocolNegotiator.CAP_PUNCH_AUTH_V1));
         return sc
            .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "turn_alloc", data, "host")
            .thenApply(r -> Boolean.TRUE);
      });
   }

   /** host 收 turn_alloc：自动 BIND hostTicket → 建 transport → 起 host 桥 → 回 turn_ready。房主无任何 UI。 */
   public void handleTurnAlloc(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state == null || state == RoomManager.PENDING || !state.roomInfo.isHost()) {
         return;
      }

      // 标准 TURN 分支（guest 声明 stdTurn）：RFC 5766 流程，独立处理
      if (data.has("stdTurn") && !data.get("stdTurn").isJsonNull() && data.get("stdTurn").getAsBoolean()) {
         this.handleStdTurnAlloc(state, from, data);
         return;
      }

      // 边缘情况：guest 点中继期间直连恰好打通——host 桥已按直连建立，
      // 此时再建 TURN 桥会同 clientId 双桥冲突，静默忽略（guest 兜底超时自动释放会话）
      if (this.connectionWon.get() && !this.isTurnActive()) {
         VoxLinkMod.LOGGER.info("[Turn] host already connected directly, ignore turn_alloc from {}", from);
         this.sendTurnNack(state, from, "direct_won");
         return;
      }

      if (this.turnSession != null || !data.has("sessionId") || !data.has("ticket")) {
         return;
      }

      String sessionIdHex = data.get("sessionId").getAsString();
      String host = data.has("host") ? data.get("host").getAsString() : "";
      int port = data.has("port") ? data.get("port").getAsInt() : 0;
      String ticket = data.get("ticket").getAsString();
      long expire = data.has("expire") ? data.get("expire").getAsLong() : 0L;
      if (sessionIdHex.length() != 32 || host.isEmpty() || port <= 0) {
         this.sendTurnNack(state, from, "bad_alloc");
         return;
      }

      byte[] sid = new byte[16];

      // 1.1.5 TURN 可靠性: bind(3轮×5发, 弱网最长~16s)移出信令分发线程——
      // 单线程 scheduler 被阻塞期间房主收不到任何信令(其他房客 join/disconnect 全排队, 审计 P1-2)
      TURN_BG_EXECUTOR.execute(() -> {
          for (int i = 0; i < 16; i++) {
             sid[i] = (byte)Integer.parseInt(sessionIdHex.substring(i * 2, i * 2 + 2), 16);
          }

          TurnRelayClient.TurnSession session =
             new TurnRelayClient.TurnSession(sessionIdHex, sid, host, port, TurnRelayClient.ROLE_HOST, ticket, expire);
          final int allocEpoch = this.turnSessionEpoch.get();
                // 3 轮重试(每轮5发, 总预算~16s < guest 的 20s turn_ready 等待):
          // 实证 09-11 23:28 host 单轮 5 发无一到达 turn01, guest 同会话一次即中——纯弱网丢包
          int code = TurnRelayClient.bindWithRetry(session, 3);
          // code=4 ROLE_CONFLICT: 首发bind已被节点接受但响应丢失, 重放被判冲突——会话已绑定, 视为成功
          if (code == TurnRelayClient.BIND_ROLE_CONFLICT) {
             code = TurnRelayClient.BIND_OK;
             VoxLinkMod.LOGGER.info("[Turn] host bind ROLE_CONFLICT (first bind accepted, response lost), proceeding");
          }
          if (code != TurnRelayClient.BIND_OK) {
             VoxLinkMod.LOGGER.warn("[Turn] host bind failed code={} sid={} endpoint={}:{}", code, sessionIdHex.substring(0, 8), host, port);
             this.sendTurnNack(state, from, "bind_failed");
             session.unbind();
             return;
          }

          // 写回屏障: bind 异步窗口内 teardown/清理可能先到(ICE restart/对端取消/退房), 防孤儿复活
          if (allocEpoch != this.turnSessionEpoch.get() || this.turnSession != null
             || this.roomManager.currentRoom.get() != state) {
             session.unbind();
             VoxLinkMod.LOGGER.warn("[Turn] host alloc stale (teardown/room changed during bind), discard orphan session");
             return;
          }

          this.turnSession = session;
          this.turnPeerId = from;
          this.startTurnKeepalive(session);
          UdpPath.Codec codec = new TurnRelayClient.TurnPathCodec(sid, TurnRelayClient.ROLE_HOST, TurnRelayClient.ROLE_GUEST);
          ReliableUdpTransport transport = new ReliableUdpTransport(session.socket, session.endpoint(), codec);
          // TURN 路径密钥现场重派生, 不沿用 activePunchAuthKey:
          // ①打洞阶段密钥可能陈旧(重进房/join_request 被 defer 未重派);
          // ②turn_alloc 携带 joiner 当前权威 clientId 与显式 punchAuth 声明,
          //   据此派生与 guest 侧对称一致(实证互丢 bug: RBW6HX 1.1.4)
          String turnPeerId = data.has("clientId") ? data.get("clientId").getAsString() : null;
          byte[] turnAuthKey;
          if (data.has("punchAuth") && !data.get("punchAuth").isJsonNull() && data.get("punchAuth").getAsBoolean()) {
             turnAuthKey = turnPeerId != null && !turnPeerId.isEmpty() && state.roomInfo.getCode() != null
                ? PunchAuth.deriveDirectKey(state.roomInfo.getCode(), turnPeerId)
                : null;
          } else {
             // 旧版 guest 未声明: 回退 peer 表判定(保持旧行为)
             turnAuthKey = this.derivePunchAuthKey(state, turnPeerId, false);
          }
          if (turnAuthKey != null) {
             transport.setAuthKey(turnAuthKey);
             this.activePunchAuthKey = turnAuthKey;
             VoxLinkMod.LOGGER.info("[PunchAuth] TURN transport auth enabled (host side, peer {})", turnPeerId);
          } else {
             VoxLinkMod.LOGGER.info("[PunchAuth] TURN transport without auth (peer {} legacy/caps missing)", turnPeerId);
          }
          // 互操作降级开关: 旧引擎 guest(桌面 App/旧版 mod)无密钥时连续认证失败自动回明文
          // (实证 09-11 23:03: host armed 而对端明文, path up 同秒互丢, 19s 后桥死)
          transport.allowAuthDowngradeForInterop();
          // TURN载具补接ICE重启回调
          transport.setOnIceRestartRequested(this::triggerIceRestart);

          this.turnTransport = transport;
          transport.start();
          this.activeUdpTransports.put(from, transport);
          this.startHostUdpPunchBridge(state, from, transport);

          JsonObject ready = new JsonObject();
          ready.addProperty("clientId", state.roomInfo.getClientId());
          this.signalingClient
             .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "turn_ready", ready, from)
             .exceptionally(e -> {
                // turn_ready 发不出去 guest 必然 20s 兜底 teardown：必须 WARN 可见（曾因白名单缺失被 debug 吞掉）
                VoxLinkMod.LOGGER.warn("[Turn] turn_ready send failed: {}", e.getMessage());
                return null;
             });
             // 投递保险 v2: 5s×12 有界重发, 回执即停(单次补发盖不住 30s 级限流退避)
             this.scheduleTurnReadyResend(state, from, ready, session, false, System.currentTimeMillis(), 0);
          this.startTurnBgMonitor(state);
          VoxLinkMod.LOGGER.info("[Turn] host path up via {}:{} (peer={})", host, port, from);
          icu.wuhui.voxlink.ui.UiLogBus.push(1, "voxlink.logui.success");
      });
   }

   /**
    * host 收 stdTurn turn_alloc（RFC 5766）：签 cred → Allocate → ChannelBind(guest relay)
    * → 建 transport → 起 host 桥 → 回 turn_ready（带本端 relay 地址）。
    * 与自定义协议 handleTurnAlloc 平行；任一步失败回 turn_nack，guest 兜底超时自拆。
    */
   private void handleStdTurnAlloc(RoomManager.RoomState state, String from, JsonObject data) {
      // 边缘情况：guest 点中继期间直连恰好打通——同自定义协议语义，静默忽略+nack
      if (this.connectionWon.get() && !this.isTurnActive()) {
         VoxLinkMod.LOGGER.info("[Turn] host already connected directly, ignore stdTurn turn_alloc from {}", from);
         this.sendTurnNack(state, from, "direct_won");
         return;
      }
      if (this.turnSession != null || this.stdTurnSession != null) {
         return;
      }
      if (!data.has("relayHost") || !data.has("relayPort") || !data.has("nodeId") || !data.has("nodeHost") || !data.has("stdTurnPort")) {
         this.sendTurnNack(state, from, "bad_alloc");
         return;
      }
      String guestRelayHost = data.get("relayHost").getAsString();
      int guestRelayPort = data.get("relayPort").getAsInt();
      String nodeId = data.get("nodeId").getAsString();
      int stdTurnPort = data.get("stdTurnPort").getAsInt();
      if (guestRelayHost.isEmpty() || guestRelayPort <= 0 || nodeId.isEmpty() || stdTurnPort <= 0) {
         this.sendTurnNack(state, from, "bad_alloc");
         return;
      }

      // cred+allocate+ChannelBind 全是同步网络事务：必须离开信令分发线程（审计 P1-2）
      TURN_BG_EXECUTOR.execute(() -> {
         final int allocEpoch = this.turnSessionEpoch.get();
         StdTurnClient.StdCred cred;
         try {
            cred = StdTurnClient
               .fetchCred(this.signalingClient, state.roomInfo.getCode(), state.roomInfo.getClientId(), state.roomInfo.getToken(), nodeId)
               .join();
         } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn host cred fetch (node={}) exception: {}", nodeId, String.valueOf(e));
            cred = null;
         }
         if (cred == null) {
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn host cred fetch failed (node={})", nodeId);
            this.sendTurnNack(state, from, "std_cred_failed");
            return;
         }
         StdTurnClient.StdTurnSession session = StdTurnClient.allocate(cred.host, cred.port, cred.username, cred.password, 8000);
         if (session == null) {
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn host allocate failed via {}:{}", cred.host, cred.port);
            this.sendTurnNack(state, from, "std_alloc_failed");
            return;
         }
         InetSocketAddress peer = new InetSocketAddress(guestRelayHost, guestRelayPort);
         if (peer.getAddress() == null) {
            // 不可解析地址会让 putXorAddress NPE 越过 IOException-only catch 杀掉池线程
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn host guest relay host unresolvable: {}", guestRelayHost);
            StdTurnClient.close(session);
            this.sendTurnNack(state, from, "std_bind_failed");
            return;
         }
         if (!StdTurnClient.channelBind(session, peer, StdTurnClient.CHANNEL_BASE, 8000)) {
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn host ChannelBind to guest relay {} failed", peer);
            StdTurnClient.close(session);
            this.sendTurnNack(state, from, "std_bind_failed");
            return;
         }
         // 竞态写回屏障：异步期间可能已直连获胜/会话被拆/teardown 先到(置null后旧检查查不出)
         if (this.turnSession != null || this.stdTurnSession != null
            || allocEpoch != this.turnSessionEpoch.get()
            || this.roomManager.currentRoom.get() != state) {
            StdTurnClient.close(session);
            return;
         }

         this.stdTurnSession = session;
         this.turnPeerId = from;
         this.startStdTurnKeepalive(session);
         UdpPath.Codec codec = new StdTurnClient.StdTurnPathCodec(StdTurnClient.CHANNEL_BASE);
         ReliableUdpTransport transport = new ReliableUdpTransport(session.socket, session.endpoint(), codec);
         // TURN 路径密钥派生：与自定义协议 handleTurnAlloc 完全同口径
         // （turn_alloc 携带 joiner 当前权威 clientId 与显式 punchAuth 声明）
         String turnPeerId = data.has("clientId") ? data.get("clientId").getAsString() : null;
         byte[] turnAuthKey;
         if (data.has("punchAuth") && !data.get("punchAuth").isJsonNull() && data.get("punchAuth").getAsBoolean()) {
            turnAuthKey = turnPeerId != null && !turnPeerId.isEmpty() && state.roomInfo.getCode() != null
               ? PunchAuth.deriveDirectKey(state.roomInfo.getCode(), turnPeerId)
               : null;
         } else {
            turnAuthKey = this.derivePunchAuthKey(state, turnPeerId, false);
         }
         if (turnAuthKey != null) {
            transport.setAuthKey(turnAuthKey);
            this.activePunchAuthKey = turnAuthKey;
            VoxLinkMod.LOGGER.info("[PunchAuth] stdTurn transport auth enabled (host side, peer {})", turnPeerId);
         } else {
            VoxLinkMod.LOGGER.info("[PunchAuth] stdTurn transport without auth (peer {} legacy/caps missing)", turnPeerId);
         }
         transport.allowAuthDowngradeForInterop();
         // TURN载具补接ICE重启回调
         transport.setOnIceRestartRequested(this::triggerIceRestart);

         this.turnTransport = transport;
         transport.start();
         this.activeUdpTransports.put(from, transport);
         this.startHostUdpPunchBridge(state, from, transport);

         JsonObject ready = new JsonObject();
         ready.addProperty("stdTurn", true);
         ready.addProperty("relayHost", session.relayAddr.getAddress().getHostAddress());
         ready.addProperty("relayPort", session.relayAddr.getPort());
         ready.addProperty("clientId", state.roomInfo.getClientId());
         this.signalingClient
            .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "turn_ready", ready, from)
            .exceptionally(e -> {
               VoxLinkMod.LOGGER.warn("[Turn] stdTurn turn_ready send failed: {}", e.getMessage());
               return null;
            });
         // 投递保险 v2: 5s×12 有界重发, 回执即停
         this.scheduleTurnReadyResend(state, from, ready, session, true, System.currentTimeMillis(), 0);
         this.startTurnBgMonitor(state);
         VoxLinkMod.LOGGER.info("[Turn] stdTurn host path up (channel 0x4000 ↔ {}, peer={})", peer, from);
         icu.wuhui.voxlink.ui.UiLogBus.push(1, "voxlink.logui.success");
      });
   }

   /**
    * turn_ready 投递保险 v2: 每 5s 重发, 至多 12 次/60s(有界≤90s)。
    * 停止条件: 会话更替/拆除, 或 guest 数据面回包(getLastRecvTime 前移=已应用 turn_ready)。
    * 重复投递由 guest 侧 turnReadyApplied CAS 幂等吞掉, 不会双建桥。
    */
   private void scheduleTurnReadyResend(
      RoomManager.RoomState state, String from, JsonObject ready, Object sessionRef, boolean stdTurn, long pathUpMs, int round
   ) {
      this.scheduler.schedule(() -> {
         try {
            boolean sessionCurrent = stdTurn
               ? this.stdTurnSession == sessionRef && this.turnPeerId != null && this.turnPeerId.equals(from)
               : this.turnSession == sessionRef && this.turnPeerId == from;
            if (!sessionCurrent) {
               return;
            }
            ReliableUdpTransport t = this.turnTransport;
            if (t != null && t.getLastRecvTime() > pathUpMs) {
               return;
            }
            if (round >= 12) {
               VoxLinkMod.LOGGER.warn("[Turn] turn_ready insurance exhausted ({} resends/60s), stop", round);
               return;
            }
            this.signalingClient
               .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "turn_ready", ready, from)
               .exceptionally(e -> {
                  VoxLinkMod.LOGGER.debug("[Turn] turn_ready resend failed: {}", e.getMessage());
                  return null;
               });
            VoxLinkMod.LOGGER.info("[Turn] turn_ready resent {}/12 (delivery insurance)", round + 1);
            this.scheduleTurnReadyResend(state, from, ready, sessionRef, stdTurn, pathUpMs, round + 1);
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[Turn] turn_ready insurance error: {}", e.getMessage());
         }
      }, 5L, java.util.concurrent.TimeUnit.SECONDS);
   }

   /**
    * turn_ready 兜底看门狗 v2: round0 于 20s 触发, 先强制补拉一次(覆盖 WS 半开/安全网
    * 节拍慢于看门狗的漏收)再于 +5s 复查; 仍无且本端信令不健康(WS 断/降级/轮询退避)时
    * 每轮补拉+延寿 20s, 至多 2 轮, 65s 封顶后诚实 teardown。信令健康则 25s 即判死,
    * 死 host 的失败路径仅多等 5s。
    */
   private void turnReadyWatchdog(RoomManager.RoomState state, int round) {
      boolean noReady = !this.turnReadyApplied.get()
         && (this.turnTransport == null || !this.turnTransport.isConnected());
      if (!noReady || this.turnSwitchedToP2p || this.roomManager.currentRoom.get() != state) {
         return;
      }
      long waited = round == 0 ? 20L : 25L + (round - 1) * 20L;
      if (round == 0) {
         VoxLinkMod.LOGGER.warn("[Turn] no turn_ready in 20s, force catch-up poll, recheck in 5s");
         this.roomManager.catchUpSignalOnce();
         this.scheduler.schedule(() -> this.turnReadyWatchdog(state, 1), 5L, TimeUnit.SECONDS);
         return;
      }
      boolean signalUnhealthy = !this.signalingClient.isWsConnected()
         || this.roomManager.isSignalingDegraded()
         || this.roomManager.isSignalPollBackedOff();
      if (signalUnhealthy && round < 3) {
         VoxLinkMod.LOGGER.warn("[Turn] still no turn_ready at {}s, signal degraded, extend wait", waited);
         this.roomManager.catchUpSignalOnce();
         this.scheduler.schedule(() -> this.turnReadyWatchdog(state, round + 1), 20L, TimeUnit.SECONDS);
         return;
      }
      VoxLinkMod.LOGGER.warn("[Turn] guest got no turn_ready in {}s (host dead / signal lost), teardown", waited);
      icu.wuhui.voxlink.ui.UiLogBus.push(2, "voxlink.logui.turn_no_ready");
      this.teardownTurn(state, "voxlink.turn.failed");
   }

   /** guest 收 turn_ready：host 数据面就绪 → start 自己的 TURN transport → 桥接进 MC。 */
   public void handleTurnReady(String from, JsonObject data) {
      // 标准 TURN 分支：ChannelBind(host relay) → 建 transport → 桥接
      if (data.has("stdTurn") && !data.get("stdTurn").isJsonNull() && data.get("stdTurn").getAsBoolean()) {
         this.handleStdTurnReady(from, data);
         return;
      }
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      ReliableUdpTransport transport = this.turnTransport;
      if (state == null || state == RoomManager.PENDING || transport == null || transport.isConnected()) {
         return;
      }

      if (this.connectionWon.get() && !this.isTurnActive()) {
         this.teardownTurn(state, "voxlink.turn.failed");
         return;
      }

      if (!this.turnReadyApplied.compareAndSet(false, true)) {
         return;
      }

      this.turnInProgress = false;
      transport.start();
      this.activeUdpTransports.put("turn_host", transport);
      // 置已连接信号标志：TURN 建立前遗留的 in-flight 打洞回调若迟到成功，
      // 会被 handleRelayNotify 的 CAS+该标志组合拦下，防止重复建桥
      this.relayConnectedSignaled = true;
      this.connectionWon.set(true);
      this.manualRelayInProgress = false;
      this.relayProgressText = null;
      this.manualRelayDeadline = 0L;
      state.roomInfo.setConnectionMode(Text.translatable("voxlink.relay.connected_via").withStyle(Text.Style.builder().color(Text.ChatFormat.YELLOW).build()));
      state.roomInfo.setUsingRelay(true);
      this.startUdpPunchBridge(state, transport);
      this.startTurnBgMonitor(state);
      VoxLinkMod.LOGGER.info("[Turn] guest path up, bridge starting");
      icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.turn_established");
      icu.wuhui.voxlink.ui.UiLogBus.push(1, "voxlink.logui.success");
   }

   /** guest 收 turn_nack：提前拆线。 */
   /** guest 收 stdTurn turn_ready：ChannelBind(host relay) → 建 transport → 桥接进 MC。 */
   private void handleStdTurnReady(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      StdTurnClient.StdTurnSession session = this.stdTurnSession;
      if (state == null || state == RoomManager.PENDING || session == null) {
         return;
      }

      if (this.connectionWon.get() && !this.isTurnActive()) {
         this.teardownTurn(state, "voxlink.turn.failed");
         return;
      }

      if (!this.turnReadyApplied.compareAndSet(false, true)) {
         return;
      }
      if (!data.has("relayHost") || !data.has("relayPort")) {
         this.teardownTurn(state, "voxlink.turn.failed");
         return;
      }
      String relayHost = data.get("relayHost").getAsString();
      int relayPort = data.get("relayPort").getAsInt();

      // ChannelBind 同步事务（socket 读）必须离开信令分发线程（对齐 host bind 的 P1-2 教训）
      TURN_BG_EXECUTOR.execute(() -> {
         InetSocketAddress peer = new InetSocketAddress(relayHost, relayPort);
         if (peer.getAddress() == null) {
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn guest relay host unresolvable: {}", relayHost);
            this.teardownTurn(state, "voxlink.turn.failed");
            return;
         }
         if (!StdTurnClient.channelBind(session, peer, StdTurnClient.CHANNEL_BASE, 8000)) {
            VoxLinkMod.LOGGER.warn("[Turn] stdTurn guest ChannelBind to {} failed", peer);
            this.teardownTurn(state, "voxlink.turn.failed");
            return;
         }
         // 竞态屏障：ChannelBind 期间 teardown 可能已拆会话
         if (this.stdTurnSession != session) {
            return;
         }

         this.turnInProgress = false;
         UdpPath.Codec codec = new StdTurnClient.StdTurnPathCodec(StdTurnClient.CHANNEL_BASE);
         ReliableUdpTransport transport = new ReliableUdpTransport(session.socket, session.endpoint(), codec);
         // TURN 路径密钥现场重派生（同自定义协议：不沿用打洞阶段 activePunchAuthKey）
         byte[] turnAuthKey = this.derivePunchAuthKey(state, null, true);
         if (turnAuthKey != null) {
            transport.setAuthKey(turnAuthKey);
            this.activePunchAuthKey = turnAuthKey;
            VoxLinkMod.LOGGER.info("[PunchAuth] stdTurn transport auth enabled (guest side)");
         } else {
            VoxLinkMod.LOGGER.info("[PunchAuth] stdTurn transport without auth (host caps missing)");
         }
         transport.allowAuthDowngradeForInterop();
         // TURN载具补接ICE重启回调
         transport.setOnIceRestartRequested(this::triggerIceRestart);

         this.turnTransport = transport;
         transport.start();
         this.activeUdpTransports.put("turn_host", transport);
         this.relayConnectedSignaled = true;
         this.connectionWon.set(true);
         this.manualRelayInProgress = false;
         this.relayProgressText = null;
         this.manualRelayDeadline = 0L;
         state.roomInfo.setConnectionMode(Text.translatable("voxlink.relay.connected_via").withStyle(Text.Style.builder().color(Text.ChatFormat.YELLOW).build()));
         state.roomInfo.setUsingRelay(true);
         this.startUdpPunchBridge(state, transport);
         this.startTurnBgMonitor(state);
         VoxLinkMod.LOGGER.info("[Turn] stdTurn guest path up (channel 0x4000 ↔ {}), bridge starting", peer);
         icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.turn_established");
      });
   }

   public void handleTurnNack(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state == null || state == RoomManager.PENDING) {
         return;
      }
      ReliableUdpTransport transport = this.turnTransport;
      if (transport != null && transport.isConnected()) {
         return;
      }
      if (transport == null && !this.turnInProgress) {
         return;
      }
      String reason = data.has("reason") ? data.get("reason").getAsString() : "unknown";
      VoxLinkMod.LOGGER.warn("[Turn] host nack ({}), tear down TURN early", reason);
      this.teardownTurn(state, "voxlink.turn.failed");
   }

   private void sendTurnNack(RoomManager.RoomState state, String from, String reason) {
      try {
         JsonObject nack = new JsonObject();
         nack.addProperty("clientId", state.roomInfo.getClientId());
         nack.addProperty("reason", reason);
         this.signalingClient
            .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), true, "turn_nack", nack, from)
            .exceptionally(e -> {
               VoxLinkMod.LOGGER.debug("[Turn] turn_nack send failed: {}", e.getMessage());
               return null;
            });
      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("[Turn] turn_nack error: {}", String.valueOf(e));
      }
   }

   /** TURN 会话保活：15s 一次 KEEPALIVE（节点 90s 无包踢角色）。句柄存字段，teardown 取消。 */
   private void startTurnKeepalive(TurnRelayClient.TurnSession session) {
      this.cancelTurnKeepalive();
      this.turnKeepaliveTask = this.scheduler.scheduleAtFixedRate(() -> {
         try {
            session.sendKeepalive();
         } catch (Exception e) {
         }
      }, 15L, 15L, TimeUnit.SECONDS);
   }

   /**
    * 标准 TURN 保活：240s 周期 Refresh(allocation 600s) + ChannelBind 重发(channel 10min，
    * permission 随刷)。全部"发了就算"（幂等，丢一次下周补；socket 已交 RUDP，
    * 响应被 codec 丢弃属预期，见 StdTurnClient.refreshQuiet/channelBindQuiet）。
    */
   private void startStdTurnKeepalive(StdTurnClient.StdTurnSession session) {
      this.cancelTurnKeepalive();
      this.turnKeepaliveTask = this.scheduler.scheduleAtFixedRate(() -> {
         try {
            StdTurnClient.refreshQuiet(session, StdTurnClient.DEFAULT_LIFETIME_SEC);
            StdTurnClient.channelBindQuiet(session);
         } catch (Exception e) {
         }
      }, 240L, 240L, TimeUnit.SECONDS);
   }

   private void cancelTurnKeepalive() {
      ScheduledFuture<?> f = this.turnKeepaliveTask;
      if (f != null) {
         f.cancel(false);
         this.turnKeepaliveTask = null;
      }
   }

   private void cancelHotstandbyKeepalive() {
      ScheduledFuture<?> f = this.hotstandbyKeepaliveTask;
      if (f != null) {
         f.cancel(false);
         this.hotstandbyKeepaliveTask = null;
      }
   }

   /**
    * 后台 P2P 升级监视器：TURN 建立后跑 10 分钟窗口，每 30s 双方协同低频打洞直连。
    * 成功 → 次路径挂入 → 连续 20s 稳定收包 → promote 平滑切换 → 宽限后释放 TURN。
    * 窗口耗尽未成功 → turnP2pGivenUp=true（本连接周期不再尝试，退出重进重置）。
    */
   private void startTurnBgMonitor(RoomManager.RoomState state) {
      if (this.turnBgMonitorTask != null || this.turnP2pGivenUp) {
         return;
      }

      this.turnBgDeadlineMs = System.currentTimeMillis() + 600_000L;
      this.turnBgMonitorTask = this.scheduler.scheduleAtFixedRate(() -> {
         try {
            if (this.turnSwitchedToP2p) {
               this.cancelTurnBgMonitor();
               return;
            }

            if (System.currentTimeMillis() > this.turnBgDeadlineMs) {
               VoxLinkMod.LOGGER.info("[Turn] 10min bg P2P window exhausted, give up until rejoin");
               this.turnP2pGivenUp = true;
               this.cancelTurnBgMonitor();
               return;
            }

            ReliableUdpTransport t = this.turnTransport;
            if (t == null || !t.isConnected()) {
               // TURN 意外死亡（60s 静默熔断等）：自动 teardown（热备转正+程序化重连），不再等 MC 层超时
               this.cancelTurnBgMonitor();
               this.teardownTurn(state, null);
               return;
            }

            int tick = this.turnBgTicks.incrementAndGet();
            // 玩家中继热备相位：60s 一次（奇数 tick），与 30s 直连打洞错开
            // 热备探测门: TURN 数据面活着才值得做玩家中继热备(会话已死时空转打洞+刷日志, 实测30s一次)
            boolean turnCarrying = this.turnTransport != null && this.turnTransport.isConnected();
            if (tick % 2 == 1 && turnCarrying && this.turnHotstandbyTransport == null) {
               this.attemptBgPlayerRelay(state);
            }

            this.attemptBgDirectPunch(state);
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[Turn] bg monitor tick error: {}", e.getMessage());
         }
      }, 30L, 30L, TimeUnit.SECONDS);
   }

   /** 玩家中继热备尝试：走既有 relay_request 链路，host 正常派发；打通后仅存热备不建桥。 */
   private void attemptBgPlayerRelay(RoomManager.RoomState state) {
      JsonObject data = new JsonObject();
      data.addProperty("clientId", state.roomInfo.getClientId());
      this.signalingClient
         .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "relay_request", data, "host")
         .exceptionally(e -> null);
      VoxLinkMod.LOGGER.info("[Turn] bg player-relay standby probe requested");
   }

   private void cancelTurnBgMonitor() {
      ScheduledFuture<?> f = this.turnBgMonitorTask;
      if (f != null) {
         f.cancel(false);
         this.turnBgMonitorTask = null;
      }
   }

   /** 一轮后台直连协同打洞：本端 punch 对端 mapped 地址，同时用信号唤醒对端 punch 本端。 */
   private void attemptBgDirectPunch(RoomManager.RoomState state) {
      boolean isHost = state.roomInfo.isHost();
      String myIp = state.roomInfo.getMyMappedIp();
      int myPort = state.roomInfo.getMyMappedPort();
      String peerIp = isHost ? null : state.roomInfo.getHostMappedIp();
      int peerPort = isHost ? 0 : state.roomInfo.getHostMappedPort();
      if (!isHost && (peerIp == null || peerPort <= 0)) {
         return;
      }

      if (!isHost && myIp != null && myPort > 0) {
         JsonObject data = new JsonObject();
         data.addProperty("ip", myIp);
         data.addProperty("port", myPort);
         this.signalingClient
            .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "turn_bg_punch", data, "host")
            .exceptionally(e -> null);
      }

      if (isHost) {
         // host 端等 guest 的 turn_bg_punch 信号带来 guest 地址后由 handleTurnBgPunch 发起 punch；
         // host 主动侧第一轮无 guest 地址可用（join 请求映射散在上下文中），依赖 guest 周期信号驱动。
         return;
      }

      this.startBgDirectPuncher(state, peerIp, peerPort);
   }

   /** 5s 短窗 puncher：成功即把直连 socket 作为次路径挂入 TURN transport。 */
   private void startBgDirectPuncher(RoomManager.RoomState state, String ip, int port) {
      UdpHolePuncher puncher = new UdpHolePuncher();
      this.applyPunchTemplate(puncher);

      try {
         puncher.createSocket();
      } catch (Exception e) {
         return;
      }

      puncher
         .punch(ip, port)
         .orTimeout(5L, TimeUnit.SECONDS)
         .thenAccept(result -> {
            if (result.isSuccess()) {
               this.onBgDirectPath(state, result.getSuccessSocket(), new InetSocketAddress(ip, port));
            } else {
               puncher.close();
            }
         })
         .exceptionally(e -> {
            puncher.close();
            return null;
         });
   }

   /** host 收 turn_bg_punch：拿 guest 最新 mapped 地址，起本端 puncher 完成双向协同。 */
   public void handleTurnBgPunch(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state == null || state == RoomManager.PENDING || !state.roomInfo.isHost()) {
         return;
      }

      if (this.turnBgMonitorTask == null || this.turnP2pGivenUp || this.turnSwitchedToP2p) {
         return;
      }

      String ip = data.has("ip") ? data.get("ip").getAsString() : null;
      int port = data.has("port") ? data.get("port").getAsInt() : 0;
      if (ip != null && port > 0) {
         this.startBgDirectPuncher(state, ip, port);
      }
   }

   /** 直连通了：挂次路径（双收）→ 观测 20s 稳定 → promote 切发送 → 宽限后释放 TURN。 */
   private void onBgDirectPath(RoomManager.RoomState state, DatagramSocket socket, InetSocketAddress addr) {
      if (!this.turnBgPunchWon.compareAndSet(false, true)) {
         try {
            socket.close();
         } catch (Exception e) {
         }
         return;
      }

      ReliableUdpTransport t = this.turnTransport;
      if (t == null || !t.isConnected()) {
         try {
            socket.close();
         } catch (Exception e) {
         }
         return;
      }

      VoxLinkMod.LOGGER.info("[Turn] bg direct path established: {}, observing stability", addr);
      t.addSecondaryPath(socket, addr, null);
      this.turnSwitchStreak.set(0);
      this.turnSwitchWatchTask = this.scheduler.scheduleAtFixedRate(() -> {
         try {
            ReliableUdpTransport transport = this.turnTransport;
            if (transport == null) {
               this.cancelTurnSwitchWatch();
               return;
            }

            long lastRx = transport.getSecondaryLastRxMs();
            boolean alive = lastRx > 0L && System.currentTimeMillis() - lastRx < 2000L;
            if (alive) {
               int streak = this.turnSwitchStreak.incrementAndGet();
               if (streak >= 20) {
                  this.cancelTurnSwitchWatch();
                  transport.promoteSecondaryPath();
                  this.scheduler.schedule(() -> {
                     try {
                        transport.dropInactivePath();
                     } catch (Exception e) {
                     }

                     this.finishTurnRelease(state);
                  }, 11L, TimeUnit.SECONDS);
                  VoxLinkMod.LOGGER.info("[Turn] smooth switch to direct P2P done, releasing TURN in 11s");
               }
            } else {
               this.turnSwitchStreak.set(0);
            }
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[Turn] switch watch error: {}", e.getMessage());
         }
      }, 1L, 1L, TimeUnit.SECONDS);
   }

   /** TURN 模式下的玩家中继热备：punch 通中继玩家后仅保存备用线路，不建桥、不动连接状态。 */
   private void establishHotstandby(RoomManager.RoomState state, String relayIp, int relayPort) {
      if (this.turnHotstandbyTransport != null) {
         return;
      }

      UdpHolePuncher puncher = new UdpHolePuncher();
      this.applyPunchTemplate(puncher);

      try {
         puncher.createSocket();
      } catch (Exception e) {
         return;
      }

      puncher
         .punch(relayIp, relayPort)
         .orTimeout(10L, TimeUnit.SECONDS)
         .thenAccept(result -> {
            if (result.isSuccess() && this.isTurnActive() && this.turnHotstandbyTransport == null) {
               puncher.markSocketTransferred();
               ReliableUdpTransport standby = new ReliableUdpTransport(result.getSuccessSocket(), new InetSocketAddress(relayIp, relayPort));
               standby.start();
               this.turnHotstandbyTransport = standby;
               VoxLinkMod.LOGGER.info("[Turn] player-relay hot standby established via {}:{}", relayIp, relayPort);
               // 热备保活：中继玩家桥与沿途 NAT 都需要持续流量
               this.cancelHotstandbyKeepalive();
               this.hotstandbyKeepaliveTask = this.scheduler.scheduleAtFixedRate(() -> {
                  try {
                     ReliableUdpTransport hs = this.turnHotstandbyTransport;
                     if (hs != null && hs.isConnected()) {
                        hs.sendKeepaliveFrame();
                     }
                  } catch (Exception e) {
                  }
               }, 15L, 15L, TimeUnit.SECONDS);
               // host 知情（仅提示；host 侧 joiner 桥本就存在，无需动作）
               JsonObject stby = new JsonObject();
               stby.addProperty("clientId", state.roomInfo.getClientId());
               this.signalingClient
                  .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "turn_stby", stby, "host")
                  .exceptionally(e -> null);
            } else {
               puncher.close();
            }
         })
         .exceptionally(e -> {
            puncher.close();
            return null;
         });
   }

   /** host 收 turn_stby：仅提示"该房客已建立玩家中继备用线路"（无动作，桥本就存在）。 */
   public void handleTurnStby(String from, JsonObject data) {
      RoomManager.RoomState state = this.roomManager.currentRoom.get();
      if (state == null || state == RoomManager.PENDING || !state.roomInfo.isHost()) {
         return;
      }

      ClientThread.runOnClientThread(() -> {
         ChatSink.chat(Text.translatable("voxlink.turn.standby"));
      });
   }

   private void cancelTurnSwitchWatch() {
      ScheduledFuture<?> f = this.turnSwitchWatchTask;
      if (f != null) {
         f.cancel(false);
         this.turnSwitchWatchTask = null;
      }
   }

   /** 切换完成收尾：UNBIND + 通知信令释放容量 + UI 状态回直连；热备一并退役（直连已是最佳路径）。 */
   private void finishTurnRelease(RoomManager.RoomState state) {
      this.turnSessionEpoch.incrementAndGet();
      this.turnSwitchedToP2p = true;
      this.cancelTurnBgMonitor();
      this.cancelTurnKeepalive();
      this.cancelHotstandbyKeepalive();
      TurnRelayClient.TurnSession session = this.turnSession;
      this.turnSession = null;
      if (session != null) {
         session.unbind();
      }

      ReliableUdpTransport standby = this.turnHotstandbyTransport;
      this.turnHotstandbyTransport = null;
      if (standby != null) {
         try {
            standby.close();
         } catch (Exception e) {
         }
      }

      try {
         TurnRelayClient
            .release(
               this.signalingClient,
               state.roomInfo.getCode(),
               state.roomInfo.getClientId(),
               state.roomInfo.getToken(),
               session != null ? session.sessionIdHex : "")
            .exceptionally(e -> null);
      } catch (Exception e) {
      }

      state.roomInfo.setUsingRelay(false);
      state.roomInfo.setConnectionMode(Text.translatable("voxlink.turn.switched_p2p"));
   }

   /** TURN 失败/超时清理：unbind + release + 状态复位；TURN 断开时热备转正（程序化重连自愈）。 */
   void teardownTurn(RoomManager.RoomState state, String failKey) {
      this.turnSessionEpoch.incrementAndGet();
      this.turnReadyApplied.set(false);
      ScheduledFuture<?> hsk = this.hotstandbyKeepaliveTask;
      if (hsk != null) {
         hsk.cancel(false);
         this.hotstandbyKeepaliveTask = null;
      }
      VoxLinkMod.LOGGER
         .info("[Turn] teardown: session={}, carrying={}, failKey={}", this.turnSession != null, this.turnTransport != null && this.turnTransport.isConnected(), failKey);
      this.turnInProgress = false;
      this.cancelTurnBgMonitor();
      this.cancelTurnSwitchWatch();
      this.cancelTurnKeepalive();
      TurnRelayClient.TurnSession session = this.turnSession;
      this.turnSession = null;
      if (session != null) {
         session.unbind();
      }
      StdTurnClient.StdTurnSession stdSession = this.stdTurnSession;
      this.stdTurnSession = null;
      if (stdSession != null) {
         StdTurnClient.close(stdSession);
      }

      ReliableUdpTransport transport = this.turnTransport;
      this.turnTransport = null;
      boolean turnWasCarrying = transport != null && transport.isConnected();

      // 热备转正：TURN 断开必然伴随 MC 断线（桥的流绑定 transport，架构性约束），
      // 此刻把备用 G1 通路转正建桥并程序化重连——把"手动退出重进"压到几秒自愈。
      // 仅房客侧（发起方）；host 侧 joiner 桥本就存在，guest 重连的流量自然经中继玩家桥回 host。
      if (turnWasCarrying
         && state != null
         && !state.roomInfo.isHost()
         && this.turnHotstandbyTransport != null
         && this.turnHotstandbyTransport.isConnected()) {
         ReliableUdpTransport standby = this.turnHotstandbyTransport;
         this.turnHotstandbyTransport = null;
         if (transport != null) {
            try {
               transport.close();
            } catch (Exception e) {
            }
         }

         if (state != null && session != null) {
            try {
               TurnRelayClient
                  .release(
                     this.signalingClient,
                     state.roomInfo.getCode(),
                     state.roomInfo.getClientId(),
                     state.roomInfo.getToken(),
                     session.sessionIdHex)
                  .exceptionally(e -> null);
            } catch (Exception e) {
            }
         }

         VoxLinkMod.LOGGER.info("[Turn] TURN dead, promoting player-relay hot standby and reconnecting");
         this.connectionWon.set(false);
         this.relayConnectedSignaled = false;
         this.turnPeerId = null;
         this.turnBgPunchWon.set(false);
         this.activeUdpTransports.remove("turn_host");
         this.activeUdpTransports.put("relay_cone", standby);
         state.roomInfo.setUsingRelay(true);
         state.roomInfo.setConnectionMode(Text.translatable("voxlink.relay.connected_via"));
         // startUdpJoinerBridge 检测旧桥 transport 已死会重建；随后 connectToServer 程序化重连
         this.startUdpPunchBridge(state, standby);
         this.connectionWon.set(true);
         return;
      }

      if (transport != null) {
         try {
            transport.close();
         } catch (Exception e) {
         }
      }

      if (this.turnPeerId != null) {
         this.activeUdpTransports.remove("turn_host");
         if (state != null && state.roomInfo.isHost() && !"host".equals(this.turnPeerId)) {
            ReliableUdpTransport t = this.activeUdpTransports.get(this.turnPeerId);
            if (t != null && t == transport) {
               this.activeUdpTransports.remove(this.turnPeerId);
            }
         }
      }

      this.turnPeerId = null;
      this.turnBgPunchWon.set(false);
      if (state != null && session != null) {
         try {
            TurnRelayClient
               .release(
                  this.signalingClient,
                  state.roomInfo.getCode(),
                  state.roomInfo.getClientId(),
                  state.roomInfo.getToken(),
                  session.sessionIdHex)
               .exceptionally(e -> null);
         } catch (Exception e) {
         }
      }

      if (failKey != null && state != null) {
         // 自动中继模式：TURN 失败不弹失败屏，交编排器换道（玩家中继）或回打洞
         if (this.autoRelayMode) {
            this.autoRelayFailoverOrEnd(state, failKey);
         } else {
            this.showConnectFailed(state, failKey);
         }
      }
   }

   /** 房间销毁/断开时的静默清理（服务端 TTL 兜底之外的礼貌释放）。 */
   public void cleanupTurnQuietly() {
      try {
         this.turnSessionEpoch.incrementAndGet();
         this.cancelTurnBgMonitor();
         this.cancelTurnSwitchWatch();
         this.cancelTurnKeepalive();
         this.cancelHotstandbyKeepalive();
         TurnRelayClient.TurnSession session = this.turnSession;
         this.turnSession = null;
         if (session != null) {
            session.unbind();
         }

         // 对齐 teardownTurnQuietly: 置 null 不 close 会让传输内部重传/心跳任务继续空转
         ReliableUdpTransport staleTransport = this.turnTransport;
         this.turnTransport = null;
         if (staleTransport != null) {
            try {
               staleTransport.close();
            } catch (Exception e) {
            }
         }
         StdTurnClient.StdTurnSession stdSession = this.stdTurnSession;
         this.stdTurnSession = null;
         if (stdSession != null) {
            StdTurnClient.close(stdSession);
         }
         ReliableUdpTransport standby = this.turnHotstandbyTransport;
         this.turnHotstandbyTransport = null;
         if (standby != null) {
            try {
               standby.close();
            } catch (Exception e) {
            }
         }

         this.turnInProgress = false;
         this.turnPeerId = null;
         // 会话终结即复位"本周期放弃 P2P 升级"标记（"退出重进重置"的实际落点）
         this.turnP2pGivenUp = false;
         this.turnSwitchedToP2p = false;
         // 漏复位会让下一会话的后台 P2P 升级 CAS 恒失败, 直连升级永久失效(审计 P1-4)
         this.turnBgPunchWon.set(false);
      } catch (Exception e) {
      }
   }

   // ================= END TURN 中继状态机 =================

   // ================= 智能自动中继编排器（2026-10-05：手动中继按钮退役） =================
   // 证据分级触发（非盲轮数）：死局NAT组合15s / 证据累计30s / 兜底45s，全部要求零收包。
   // TURN 优先（建立快）；中继传输建立 ≠ 成功——25s 真连接验证窗盯 MC 会话下行首包，
   // 验证失败判该道"建立但死"，无缝换道（joiner 本地桥/MC TCP 全程不动，只换承载 +
   // 重放上行日志；host 侧旧桥随旧传输死亡自拆，新桥已由 relay_accept 预先武装）。
   private volatile ScheduledFuture<?> autoRelayDirectorTask;
   /** 房间态未就绪时的挂载复查任务(就绪/会话终结/退房即自取消) */
   private volatile ScheduledFuture<?> autoRelayArmWaiterTask;
   private volatile ScheduledFuture<?> relayVerifyWatchdogTask;
   /** 验证后下行保鲜窗: 持续零下行判该道停滞 */
   private static final long RELAY_DOWNLINK_FRESH_MS = 6000L;
   private volatile boolean autoRelayTurnAttempted;
   private volatile boolean autoRelayTurnUnhealthy;
   private volatile boolean autoRelayPlayerAttempted;
   /** 自动尝试进行中：中继失败路径走换道/回打洞而非失败屏。 */
   private volatile boolean autoRelayMode;
   /** 触发时的持续重试轮（轮次推进→重置两条道的尝试位，新一轮打洞=新中继机会）。 */
   private volatile int autoRelayArmedRound = -1;
   /** 无缝换道挂起中的新承载（换道确认后热切，此前由旧道继续扛）。 */
   private volatile ReliableUdpTransport pendingCarrierSwapTransport;
   /** pendingCarrierSwapTransport 的旧道形态（"turn"/"player"），换道成功后据此拆旧。 */
   private volatile String carrierSwapFrom;

   public void startAutoRelayDirector(RoomManager.RoomState state) {
      this.stopAutoRelayDirector();
      this.autoRelayDirectorTask = this.scheduler.scheduleAtFixedRate(() -> {
         try {
            if (this.connectionWon.get() || this.roomManager.currentRoom.get() != state) {
               this.stopAutoRelayDirector();
               return;
            }

            // 已有任何中继在途/在跑：不重复触发
            if (this.isTurnActive() || this.isTurnInProgress() || this.manualRelayInProgress) {
               return;
            }

            long punchMs = this.punchUiStartMs > 0L ? System.currentTimeMillis() - this.punchUiStartMs : 0L;
            if (punchMs < 10_000L) {
               return;
            }

            // 零收包是所有档位的硬前提：收过对端包说明直连还有戏
            if (this.sessionPunchRecvEver.get()) {
               return;
            }

            boolean tierA = this.isDeadNatCombo(state) && punchMs >= 15_000L;
            boolean tierB = (this.sessionPredictionOffCount.get() >= 10 || this.isSamePublicIpAsHost(state)) && punchMs >= 30_000L;
            boolean tierC = punchMs >= 45_000L;
            if (!(tierA || tierB || tierC)) {
               return;
            }

            // 轮次推进时重置尝试位
            int round = this.continuousRetryRound.get();
            if (round != this.autoRelayArmedRound) {
               this.autoRelayArmedRound = round;
               this.autoRelayTurnAttempted = false;
               this.autoRelayPlayerAttempted = false;
            }

            VoxLinkMod.LOGGER.info(
               "[AutoRelay] evidence tier hit (punchMs={}, predOff={}, deadCombo={}, sameIp={}), auto relay",
               punchMs, this.sessionPredictionOffCount.get(), this.isDeadNatCombo(state), this.isSamePublicIpAsHost(state)
            );
            this.fireAutoRelay(state);
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[AutoRelay] director tick error: {}", e.getMessage());
         }
      }, 1L, 1L, TimeUnit.SECONDS);
   }

   public void stopAutoRelayDirector() {
      ScheduledFuture<?> f = this.autoRelayDirectorTask;
      if (f != null) {
         f.cancel(false);
         this.autoRelayDirectorTask = null;
      }
   }

   private boolean isDeadNatCombo(RoomManager.RoomState state) {
      return StunNatClassifier.isDeadNatCombo(this, state);
   }

   private boolean isSamePublicIpAsHost(RoomManager.RoomState state) {
      return StunNatClassifier.isSamePublicIpAsHost(this, state);
   }

   private void fireAutoRelay(RoomManager.RoomState state) {
      if (!this.autoRelayTurnUnhealthy && !this.autoRelayTurnAttempted) {
         this.fireAutoTurn(state);
      } else if (!this.autoRelayPlayerAttempted) {
         this.fireAutoPlayerRelay(state);
      }
   }

   private void fireAutoTurn(RoomManager.RoomState state) {
      this.autoRelayTurnAttempted = true;
      this.autoRelayMode = true;
      icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.auto_turn");
      state.roomInfo.setConnectionMode(Text.translatable("voxlink.auto_relay.turn"));
      VoxLinkMod.LOGGER.info("[AutoRelay] auto TURN fired");
      this.triggerTurnRelay();
   }

   private void fireAutoPlayerRelay(RoomManager.RoomState state) {
      this.autoRelayPlayerAttempted = true;
      this.autoRelayMode = true;
      icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.auto_player_relay");
      state.roomInfo.setConnectionMode(Text.translatable("voxlink.auto_relay.player"));
      VoxLinkMod.LOGGER.info("[AutoRelay] auto player relay fired");
      this.triggerManualRelay();
   }

   /** 中继失败路径统一口：自动模式走换道/回打洞，手动(旧版对端触发等)保持失败屏。 */
   private void relayFailOrAuto(RoomManager.RoomState state, String reasonKey) {
      if (this.autoRelayMode) {
         this.autoRelayFailoverOrEnd(state, reasonKey);
      } else {
         this.showConnectFailed(state, reasonKey);
      }
   }

   private void autoRelayFailoverOrEnd(RoomManager.RoomState state, String reasonKey) {
      if (state == null || state == RoomManager.PENDING) {
         state = this.roomManager.currentRoom.get();
      }
      if (state == null || state == RoomManager.PENDING) {
         this.autoRelayMode = false;
         return;
      }

      if (!this.autoRelayTurnUnhealthy && !this.autoRelayTurnAttempted) {
         this.fireAutoTurn(state);
         return;
      }
      if (!this.autoRelayPlayerAttempted) {
         this.fireAutoPlayerRelay(state);
         return;
      }

      // 两条道都尽：不弹失败屏，交回打洞，诚实文案
      this.autoRelayMode = false;
      VoxLinkMod.LOGGER.warn("[AutoRelay] both relay paths exhausted ({}), back to punching", reasonKey);
      icu.wuhui.voxlink.ui.UiLogBus.push(2, "voxlink.logui.auto_relay_exhausted");
      state.roomInfo.setConnectionMode(Text.translatable("voxlink.auto_relay.exhausted"));

      // 旧注释声称"持续重试仍活着"——实际首桥建立时 stopAllPunchingAfterHostBridge 已杀打洞,
      // 轮次随之冻结, 两道耗尽后会话静默挂死(无打洞/无中继/无失败屏)。房客侧诚实复位并复活:
      // 桥已拆/从未建立才复位 connectionWon; 再走与打洞失败同一条 enterContinuousRetryRound 链
      // (零收包终局/能力缺失由其内部分流); 复活不了至少给终态失败屏, 不再无声挂死
      if (!state.roomInfo.isHost()) {
         if (this.connectionWon.get() && !this.isTurnActive() && !this.isManualRelayInProgress()
            && (P2PBridge.getJoinerCarrier() == null || !P2PBridge.getJoinerCarrier().isConnected())) {
            this.connectionWon.set(false);
         }

         if (!this.connectionWon.get() && !this.isTurnActive() && !this.isTurnInProgress()
            && !this.isManualRelayInProgress() && this.activeHolePunchers.isEmpty()
            && !this.connectionCycleActive.get()) {
            this.continuousRetryCancelled.set(false);
            try {
               if (!this.enterContinuousRetryRound(state)) {
                  this.showConnectFailedFinal(state, reasonKey);
               }
            } catch (Exception reviveEx) {
               VoxLinkMod.LOGGER.warn("[AutoRelay] revive punching failed: {}", String.valueOf(reviveEx));
               this.showConnectFailedFinal(state, reasonKey);
            }
         }
      }
   }

   /**
    * 真连接验证窗：中继桥就绪后 25s 内必须见到 MC 会话下行数据（服务器真回了包）
    * 或 isMcTrulyConnected。超窗=该道"建立但死"，静默换道。
    * 连接期上行字节被 P2PBridge 记账（journal），换道后重放——MC 服务器侧旧会话
    * 本就未完成登录，重放一条完整握手无副作用。
    */
   void armRelayVerifyWatchdog(RoomManager.RoomState state, boolean turnPath) {
      this.cancelRelayVerifyWatchdog();
      P2PBridge.resetJoinerDownlink();
      final long armMs = System.currentTimeMillis();
      this.relayVerifyWatchdogTask = this.scheduler.scheduleAtFixedRate(() -> {
         try {
            if (ConnectionHelper.isMcTrulyConnected()) {
               this.cancelRelayVerifyWatchdog();
               this.autoRelayMode = false;
               P2PBridge.stopJoinerJournal();
               if (!turnPath) {
                  // 玩家中继模式：起 10 分钟后台直连探测（本轮仅探测+日志，
                  // 无缝升级需 host 侧载具热切配套，随跨房虚拟加入迭代落地）
                  this.startRelayBgProbe(state);
               }
               VoxLinkMod.LOGGER.info("[AutoRelay] relay verified ({} path, {}ms)", turnPath ? "turn" : "player", System.currentTimeMillis() - armMs);
               return;
            }

            if (!P2PBridge.isRunning() || this.roomManager.currentRoom.get() != state) {
               this.cancelRelayVerifyWatchdog();
               return;
            }

            if (P2PBridge.hasJoinerDownlink()) {
               // 验证后转下行保鲜盯守: 停滞即换道
               if (!P2PBridge.isJoinerDownlinkFresh(RELAY_DOWNLINK_FRESH_MS)) {
                  VoxLinkMod.LOGGER.warn("[AutoRelay] relay downlink stalled {}ms after first byte, failover", RELAY_DOWNLINK_FRESH_MS);
                  this.cancelRelayVerifyWatchdog();
                  this.autoRelayMode = false;
                  P2PBridge.stopJoinerJournal();
                  this.onRelayVerifyDead(state, turnPath);
               }
               return;
            }

            if (System.currentTimeMillis() - armMs >= 25_000L) {
               this.cancelRelayVerifyWatchdog();
               this.onRelayVerifyDead(state, turnPath);
            }
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[AutoRelay] verify watchdog error: {}", e.getMessage());
         }
      }, 1L, 1L, TimeUnit.SECONDS);
   }

   private void cancelRelayVerifyWatchdog() {
      ScheduledFuture<?> f = this.relayVerifyWatchdogTask;
      if (f != null) {
         f.cancel(false);
         this.relayVerifyWatchdogTask = null;
      }
   }

   /** 验证失败：该道"建立但死"。挂起无缝换道（新道建好→热切+重放→拆旧道）。 */
   private void onRelayVerifyDead(RoomManager.RoomState state, boolean turnPath) {
      VoxLinkMod.LOGGER.warn("[AutoRelay] relay path verified DEAD ({}), seamless failover starting", turnPath ? "turn" : "player");
      icu.wuhui.voxlink.ui.UiLogBus.push(2, "voxlink.logui.auto_relay_dead");
      this.carrierSwapFrom = turnPath ? "turn" : "player";
      if (turnPath) {
         this.autoRelayTurnUnhealthy = true;
         if (!this.autoRelayPlayerAttempted) {
            this.fireAutoPlayerRelay(state);
         } else {
            // 玩家中继也试过：诚实失败
            this.teardownTurn(state, "voxlink.turn.failed");
         }
      } else {
         if (!this.autoRelayTurnUnhealthy && !this.autoRelayTurnAttempted) {
            this.fireAutoTurn(state);
         } else {
            this.stopRelay();
            this.autoRelayFailoverOrEnd(state, "voxlink.relay.failed");
         }
      }
   }

   /**
    * startUdpPunchBridge 的换道分支：carrierSwapFrom 置位（旧道验证死）且本地桥在跑时，
    * 新到传输不新建桥，而是热切承载 + 重放上行日志 + 拆旧道。host 侧无需换道信令——
    * 旧传输关闭后 host 旧桥自拆，新桥已由 relay_accept/turn_alloc 流程预先武装；
    * 早到的字节会在子通道接收缓冲里排队，host 新桥就绪即排干。
    */
   boolean tryCarrierSwap(RoomManager.RoomState state, ReliableUdpTransport newTransport) {
      String from = this.carrierSwapFrom;
      if (from == null || !P2PBridge.isRunning() || P2PBridge.getJoinerCarrier() == null) {
         return false;
      }

      this.carrierSwapFrom = null;
      byte[] uplinkJournal = P2PBridge.takeJoinerUplinkJournal();
      boolean swapped = P2PBridge.swapJoinerCarrier(newTransport, uplinkJournal);
      if (!swapped) {
         VoxLinkMod.LOGGER.warn("[AutoRelay] carrier swap failed (no local bridge?), falling back to fresh bridge");
         return false;
      }

      if (uplinkJournal != null && uplinkJournal.length > 0) {
         VoxLinkMod.LOGGER.info("[AutoRelay] uplink journal seeded {} bytes onto new carrier", uplinkJournal.length);
      }

      // 拆旧道
      if ("turn".equals(from)) {
         this.teardownTurnQuietly();
      } else if ("player".equals(from)) {
         this.stopRelay();
      }

      this.armRelayVerifyWatchdog(state, !"player".equals(from));
      icu.wuhui.voxlink.ui.UiLogBus.push(1, "voxlink.logui.auto_relay_swapped");
      return true;
   }

   /** 换道成功后拆旧 TURN（不再触发失败文案/重连，桥已由新道承载）。 */
   private void teardownTurnQuietly() {
      try {
         this.turnSessionEpoch.incrementAndGet();
         this.cancelTurnBgMonitor();
         this.cancelTurnKeepalive();
         this.cancelHotstandbyKeepalive();
         TurnRelayClient.TurnSession session = this.turnSession;
         this.turnSession = null;
         if (session != null) {
            session.unbind();
         }

         ReliableUdpTransport old = this.turnTransport;
         this.turnTransport = null;
         if (old != null) {
            old.close();
         }
         StdTurnClient.StdTurnSession stdSession = this.stdTurnSession;
         this.stdTurnSession = null;
         if (stdSession != null) {
            StdTurnClient.close(stdSession);
         }

         ReliableUdpTransport standby = this.turnHotstandbyTransport;
         this.turnHotstandbyTransport = null;
         if (standby != null) {
            standby.close();
         }

         this.turnInProgress = false;
         this.turnPeerId = null;
         this.turnBgPunchWon.set(false);
         RoomManager.RoomState state = this.roomManager.currentRoom.get();
         if (state != null && state != RoomManager.PENDING) {
            state.roomInfo.setConnectionMode(Text.translatable("voxlink.auto_relay.player"));
         }
      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("[AutoRelay] teardown old turn error: {}", e.getMessage());
      }
   }

   public void stopAutoRelayAll() {
      this.cancelAutoRelayDirectorArm();
      this.stopAutoRelayDirector();
      this.cancelRelayVerifyWatchdog();
      this.cancelRelayBgProbe();
      this.pendingCarrierSwapTransport = null;
      this.carrierSwapFrom = null;
      this.autoRelayMode = false;
      this.autoRelayTurnAttempted = false;
      this.autoRelayTurnUnhealthy = false;
      this.autoRelayPlayerAttempted = false;
      this.autoRelayArmedRound = -1;
   }

   /** startDualP2P 早于房间态就绪时：每秒复查挂载, 直到就绪/会话终结/退房(修 joinRoom>1s 整场落空)。 */
   private void scheduleAutoRelayDirectorArm() {
      ScheduledFuture<?> old = this.autoRelayArmWaiterTask;
      if (old != null) {
         old.cancel(false);
      }

      this.autoRelayArmWaiterTask = this.scheduler.scheduleWithFixedDelay(() -> {
         try {
            RoomManager.RoomState state = this.roomManager.currentRoom.get();
            if (state == null || this.connectionWon.get()) {
               this.cancelAutoRelayDirectorArm();
               return;
            }

            if (state != RoomManager.PENDING) {
               this.cancelAutoRelayDirectorArm();
               if (!state.roomInfo.isHost()) {
                  this.startAutoRelayDirector(state);
               }
            }
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[AutoRelay] arm waiter error: {}", e.getMessage());
         }
      }, 1L, 1L, TimeUnit.SECONDS);
   }

   private void cancelAutoRelayDirectorArm() {
      ScheduledFuture<?> f = this.autoRelayArmWaiterTask;
      if (f != null) {
         f.cancel(false);
         this.autoRelayArmWaiterTask = null;
      }
   }

   /**
    * 玩家中继模式后台直连探测（10 分钟窗口，30s 一次）：
    * 满足"自动中继后后台持续 10 分钟打洞"。本轮为探测+日志形态——
    * host 侧为被中继会话热切直连载具需要跨房虚拟加入同一套配套，下迭代落地；
    * TURN 模式不受此限（startTurnBgMonitor 全功能协同+平滑切换）。
    */
   private volatile ScheduledFuture<?> relayBgProbeTask;

   private void startRelayBgProbe(RoomManager.RoomState state) {
      this.cancelRelayBgProbe();
      final long deadlineMs = System.currentTimeMillis() + 600_000L;
      this.relayBgProbeTask = this.scheduler.scheduleAtFixedRate(() -> {
         try {
            if (this.roomManager.currentRoom.get() != state || System.currentTimeMillis() > deadlineMs) {
               this.cancelRelayBgProbe();
               return;
            }

            ReliableUdpTransport relayLeg = this.activeUdpTransports.get("relay_cone");
            if (relayLeg == null || !relayLeg.isConnected()) {
               this.cancelRelayBgProbe();
               return;
            }

            String ip = state.roomInfo.getHostMappedIp();
            int port = state.roomInfo.getHostMappedPort();
            if (ip == null || port <= 0) {
               return;
            }

            UdpHolePuncher probe = new UdpHolePuncher();
            this.applyPunchTemplate(probe);
            try {
               probe.createSocket();
            } catch (Exception e) {
               return;
            }

            probe
               .punch(ip, port)
               .orTimeout(5L, TimeUnit.SECONDS)
               .thenAccept(r -> {
                  if (r.isSuccess()) {
                     VoxLinkMod.LOGGER.info("[Relay] bg direct probe: host answered — direct viable (seamless upgrade lands with cross-room iteration)");
                  }
                  probe.close();
               })
               .exceptionally(e -> {
                  probe.close();
                  return null;
               });
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[Relay] bg probe tick error: {}", e.getMessage());
         }
      }, 30L, 30L, TimeUnit.SECONDS);
   }

   private void cancelRelayBgProbe() {
      ScheduledFuture<?> f = this.relayBgProbeTask;
      if (f != null) {
         f.cancel(false);
         this.relayBgProbeTask = null;
      }
   }

   public void handleRelaySetup(String from, JsonObject data) {
      // 候选者若本身就是房主，它没有"自家房主"可回执：只能按 relay_setup 里的 replyHostId
      // 直投请求方房主（服务端把 to=host-<CODE> 落到对方房间并归一成该房房主的 pollerID）
      RoomManager.RoomState setupState = this.roomManager.currentRoom.get();
      final boolean setupAsHost = setupState != null
         && setupState != RoomManager.PENDING
         && setupState.roomInfo.isHost();
      final String setupReplyTo = setupAsHost && data.has("replyHostId")
         ? data.get("replyHostId").getAsString()
         : "host";
      if (setupAsHost && "host".equals(setupReplyTo)) {
         VoxLinkMod.LOGGER.warn("[Relay] 被选为候选但对方未留 replyHostId（旧版房主），回执可能落到本房房主");
      }


      if (!VoxLinkMod.getConfig().isRelayEnabled()) {

         RoomManager.RoomState state = this.roomManager.currentRoom.get();

         if (state != null && state != RoomManager.PENDING) {

            this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), setupAsHost, "relay_declined", new JsonObject(), setupAsHost ? setupReplyTo : from);

         }

      } else {

         RoomManager.RoomState state0 = this.roomManager.currentRoom.get();

         if (state0 != null && state0 != RoomManager.PENDING) {

            String targetClientId = data.has("targetClientId") ? data.get("targetClientId").getAsString() : null;

            String targetIp = data.has("targetIp") ? data.get("targetIp").getAsString() : null;

            int targetPort = data.has("targetPort") ? data.get("targetPort").getAsInt() : 0;

            if (targetIp != null && targetPort > 0) {

               RoomManager.RoomState state = this.roomManager.currentRoom.get();

               // 玩家中继 v2（2026-10-05）：被中继会话必须走本端"joiner"传输的 mux 子通道。
               // v1 曾在此处复用任意已连接传输转发裸流——包括连到别的房间 host 的传输，
               // 两条 MC 会话字节交错+InputStream 双读者互抢，从未真正可用（本地实验实证）。
               // 旧版 host（未声明 relayMuxV1）或本端传输未 mux 化：一律婉拒，绝不走 v1 坏接线。
               ReliableUdpTransport hostTransport = this.activeUdpTransports.get("joiner");

               boolean muxUsable = hostTransport != null && hostTransport.isConnected() && hostTransport.isMuxEnabled();

               if (!muxUsable) {

                  if (!ProtocolNegotiator.hostSupportsRelayMux(state0.roomInfo)) {

                     VoxLinkMod.LOGGER.info("[Relay] host lacks relayMuxV1, decline relay_setup (v1 relay data plane was broken, never wire it)");

                  } else {

                     VoxLinkMod.LOGGER.warn("[Relay] own transport to host not mux-enabled, decline relay_setup");

                  }

                  this.signalingClient.sendSignal(state0.roomInfo.getCode(), state0.roomInfo.getToken(), setupAsHost, "relay_declined", new JsonObject(), setupAsHost ? setupReplyTo : from);

                  return;

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

                                 this.recordPunchFailure(reason, result.socketsReceivedPunch, result.socketsReceivedAck);

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

                                    // v2：被中继会话挂到本端 joiner 传输的 mux 子通道（chan 1..7），
                                    // 与自己（chan 0）的 MC 会话字节级隔离；任一腿 EOF/死亡由 RelayBridge 双向收尾
                                    final int muxChan = this.allocateRelayMuxChan();
                                    // key 带 clientId: chan 1..7 循环复用, 纯 chan key 会撞残留会话
                                    final String relayKey = "muxrelay-" + muxChan + "-" + (fTargetClientId != null ? fTargetClientId : "sym");
                                    RelayBridge.getInstance(this.scheduler).startRelay(
                                       relayKey,
                                       "host-chan" + muxChan,
                                       fTargetClientId != null ? fTargetClientId : "sym",
                                       new RelayBridge.RelayLeg() {
                                          @Override
                                          public java.io.InputStream input() {
                                             return fHostTransport.getSubstreamInput(muxChan);
                                          }

                                          @Override
                                          public java.io.OutputStream output() {
                                             return fHostTransport.getSubstreamOutput(muxChan);
                                          }

                                          @Override
                                          public void close() {
                                             try {
                                                fHostTransport.closeSubstream(muxChan);
                                             } catch (Exception e) {
                                             }
                                          }
                                       },
                                       RelayBridge.legOf(peerTransport)
                                    );

                                    JsonObject reply = new JsonObject();

                                    reply.addProperty("forClientId", fTargetClientId != null ? fTargetClientId : "sym");

                                    reply.addProperty("chan", muxChan);

                                    this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), setupAsHost, "relay_accept", reply, setupReplyTo);

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

                        this.signalingClient.sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), setupAsHost, "relay_declined", new JsonObject(), setupReplyTo);

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
      JoinSession.showConnectFailedFinal(this, state);
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

            state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.retrying"));

         }

      } else if (this.dualRaceActive && !this.terracottaWon) {

         if (state != null && state.roomInfo != null) {

            state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.cannot_establish"));

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
         icu.wuhui.voxlink.ui.UiLogBus.push(3, reasonKey);

         // 日志上传加强: 到达真终态即触发失败样本快传(5s), 不再硬等 90s 定时器
         LogUploadManager.onTerminalFailure();

         if (this.connectionTimeoutFuture != null) {

            this.connectionTimeoutFuture.cancel(false);

            this.connectionTimeoutFuture = null;

         }



         ConnectionHelper.resetConnecting();

         state.roomInfo.setConnectionMode(Text.translatable(reasonKey), true);

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

         ClientThread.runOnClientThread(() -> {

            ChatSink.chat(Text.translatable("voxlink.chat.error_prefix").append(Text.translatable(reasonKey)));

            if (state.roomInfo.isSameCgnat()) {

               ChatSink.chat(Text.translatable("voxlink.chat.same_cgnat_warning"));

            }



            this.leaveRoomOnFailure(state);

         });

      }

   }



void leaveRoomOnFailure(RoomManager.RoomState state) {
         JoinSession.leaveRoomOnFailure(this, state);
      }



   public void killAllConnectionAttempts() {
      JoinSession.killAllConnectionAttempts(this);
   }



   public void killAllConnectionAttempts(String reason) {
      this.joinAttemptGeneration.incrementAndGet();


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
      JoinSession.killDualRace(this);
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

      // 新会话：零收包证据/对端就绪暂存/排队重放全部作废

      this.sessionPunchRecvEver.set(false);
      this.sessionPredictionOffCount.set(0);

      this.peerReadyConfirmations.clear();

      this.queuedJoinReplays.clear();

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
      this.hostPunchContexts.clear();
      this.sessionPunchRecvEver.set(false);
      this.sessionPredictionOffCount.set(0);
      this.peerReadyConfirmations.clear();
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
         return !state.roomInfo.getPeers().isEmpty()
            && state.roomInfo.getPeers().stream().allMatch(p -> p.capabilities.isEmpty() || p.capabilities.contains("continuous_retry"));
      } else {
         Set<String> caps = state.roomInfo.getHostCapabilities();
         return caps.isEmpty() || caps.contains("continuous_retry");
      }
   }



   private void escalateProfileForRound(int round) {
      PunchProfileCtrl.escalateProfileForRound(this, round);
   }



   public void handleCancelConnection(String from, JsonObject data) {
      JoinSession.handleCancelConnection(this, from, data);
   }



   public void notifyAllPeersGone() {
      JoinSession.notifyAllPeersGone(this);
   }



   void sendCancelConnectionSignal() {
      JoinSession.sendCancelConnectionSignal(this);
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
      // ICE重启将停所有传输：TURN会话先静默拆净，防stdTurnSession残留锁死自动中继导演
      if (this.turnSession != null || this.stdTurnSession != null) {
         this.teardownTurnQuietly();
      }

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

            ClientThread.runOnClientThread(

               () -> ChatSink.chat(Text.translatable("voxlink.chat.error_prefix").append(Text.translatable("voxlink.connection.ice_restart")))

            );



            RoomManager.RoomState savedStateRef = this.savedConnectionState;
            if ((savedStateRef == null || savedStateRef != state || this.savedConnectionHostIp == null)
               && state.roomInfo != null && !state.roomInfo.isHost()
               && state.roomInfo.getHostIp() != null && !state.roomInfo.getHostIp().isEmpty()) {
               this.savedConnectionState = state;
               if (this.savedConnectionFrom == null || this.savedConnectionFrom.isEmpty()) {
                  this.savedConnectionFrom = "host";
               }

               this.savedConnectionHostIpv6 = state.roomInfo.getHostIpv6();
               this.savedConnectionHostIp = state.roomInfo.getHostIp();
               this.savedConnectionHostPort = state.roomInfo.getHostPort();
               this.savedConnectionHostMappedIp = state.roomInfo.getHostMappedIp();
               this.savedConnectionHostMappedPort = state.roomInfo.getHostMappedPort();
               savedStateRef = state;
               VoxLinkMod.LOGGER.info("[Connection] ICE Restart rebuild saved params from room info");
            }

            final RoomManager.RoomState savedState = savedStateRef;
            if (savedState != null && savedState == state && this.savedConnectionHostIp != null) {
               this.connectionStartTimeMs = System.currentTimeMillis();
               int timeoutSec = this.punchProfile().connectionTimeoutSec;
               this.connectionTimeoutSec = timeoutSec;
               state.roomInfo.setConnectionMode(Text.translatable("voxlink.connection.connecting"));
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
            } else if (state.roomInfo != null && state.roomInfo.isHost()) {
               VoxLinkMod.LOGGER.info("[Connection] ICE Restart (host): state cleaned, wait for joiner punch_info re-trigger");
            } else {
               VoxLinkMod.LOGGER.warn("[Connection] ICE Restart no saved params, cannot re-trigger");
            }

            // 重挂自动中继导演: stopAutoRelayAll 已随 stopAllConnectionWork 拆掉它,
            // 不重挂则重打洞失败后只剩无中继的无限打洞(手动按钮已退役)
            if (!state.roomInfo.isHost() && !this.connectionWon.get()) {
               this.startAutoRelayDirector(state);
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
      ConnectionState.transitionTo(ConnectionState.CONNECTED, "直连通路建立");

   }



   public boolean isConnectionInHandoff() {

      return this.connectionEstablishedAtMs > 0L

         && System.currentTimeMillis() - this.connectionEstablishedAtMs < HANDOFF_GRACE_MS

         && !ConnectionHelper.isMcTrulyConnected();

   }



   public void notifyDualVoxlinkBridge(boolean success) {
      TransportBridgeRegistry.notifyDualVoxlinkBridge(this, success);
   }



   boolean claimVoxlinkDualWin() {

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
      icu.wuhui.voxlink.ui.UiLogBus.reset();
      icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.preparing");
      if (this.signalingClient != null && this.signalingClient.isWsConnected()) {
         icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.signaling_ready");
      }

      this.resetDualRaceState();

      this.resetContinuousRetryState();

      // 新连接会话：清 UI 按钮计时基准，防止上一会话残留值让"使用中继"按钮立即闪现
      this.punchUiStartMs = 0L;

      this.connectionWon.set(false);

      this.connectionWon.set(false);

      this.connectionEstablishedAtMs = 0L;

      // 智能自动中继：新会话复位编排器状态（房客侧打洞期间持续评估触发条件）
      this.stopAutoRelayAll();

      RoomManager.RoomState directorState = this.roomManager.currentRoom.get();
      if (directorState == null || directorState == RoomManager.PENDING) {
         // joinRoom 在本调用之后才发起, 房间态可能数秒后才就绪: 一次性+1s 挂载会整场落空
         this.scheduleAutoRelayDirectorArm();
      } else {
         this.startAutoRelayDirector(directorState);
      }

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

      } else if (RoomCodeRouter.isFedCode(roomCode)) {

         // 互通房：join回包即带fedAddress，connectFed直连HL中继，不吃陶瓦竞速

         statusCallback.accept("voxlink", "voxlink.connection.joining");

         return this.startVoxLinkP2P(roomCode, password);

      } else if (!RoomCodeRouter.isVoxLinkCode(roomCode)) {

         return CompletableFuture.failedFuture(new IllegalArgumentException(Text.translatable("voxlink.error.invalid_room_code").toPlainString()));

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

                  // 此处不得复位竞态状态：terracottaWon/voxlinkSideDisabled 一旦被清，
                  // 随后到达的 turn_ready 会绕过互斥守卫再发起一次 connectToServer，
                  // 表现为"闪一下（25565 refused）又连上（TURN 端口）"两层叠加。
                  // 竞态字段统一由 leaveRoom 与新会话入口清理。

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
      TransportBridgeRegistry.connectTerracottaToMC(this, connectUrl, roomCode);
   }



   static int parsePortFromUrl(String url) {
      return JoinSession.parsePortFromUrl(url);
   }



   private CompletableFuture<Void> startVoxLinkP2P(String roomCode, String password) {
      int gen = this.joinAttemptGeneration.incrementAndGet();
      return this.startVoxLinkP2PAttempt(roomCode, password, 1, gen);
   }

   private CompletableFuture<Void> startVoxLinkP2PAttempt(String roomCode, String password, int attempt, int gen) {
      long t0 = System.currentTimeMillis();
      return this.roomManager.joinRoom(roomCode, password).handle((r, e) -> {
         if (e == null) {
            VoxLinkMod.LOGGER.info("[joinRoom] attempt {}/{} OK in {}ms", new Object[]{attempt, JOIN_TRANSIENT_MAX_ATTEMPTS, System.currentTimeMillis() - t0});
            return CompletableFuture.<Void>completedFuture(null);
         }

         Throwable cause = e;
         while ((cause instanceof java.util.concurrent.CompletionException || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
         }

         boolean retryable = cause instanceof RoomManager.TransientException;
         VoxLinkMod.LOGGER.warn("[joinRoom] attempt {}/{} failed in {}ms: {} — {}", new Object[]{attempt, JOIN_TRANSIENT_MAX_ATTEMPTS, System.currentTimeMillis() - t0, cause.getClass().getSimpleName(), cause.getMessage()});
         if (!retryable || attempt >= JOIN_TRANSIENT_MAX_ATTEMPTS || gen != this.joinAttemptGeneration.get()) {
            icu.wuhui.voxlink.ui.UiLogBus.push(2, "voxlink.logui.join_failed", String.valueOf(cause.getMessage()));
            return CompletableFuture.<Void>failedFuture(cause);
         }

         long delay = JOIN_RETRY_BACKOFF_MS[Math.min(attempt - 1, JOIN_RETRY_BACKOFF_MS.length - 1)];
         icu.wuhui.voxlink.ui.UiLogBus.push(1, "voxlink.logui.join_retry", new Object[]{attempt + 1, JOIN_TRANSIENT_MAX_ATTEMPTS});
         VoxLinkMod.LOGGER.warn("[joinRoom] retrying in {}ms (attempt {}/{})", new Object[]{delay, attempt + 1, JOIN_TRANSIENT_MAX_ATTEMPTS});
         CompletableFuture<Void> next = new CompletableFuture<>();
         this.scheduler.schedule(() -> this.startVoxLinkP2PAttempt(roomCode, password, attempt + 1, gen).whenComplete((v2, e2) -> {
            if (e2 != null) {
               next.completeExceptionally(e2);
            } else {
               next.complete(null);
            }
         }), delay, TimeUnit.MILLISECONDS);
         return next;
      }).thenCompose(f -> f);
   }



   public void sendDisconnectOnFailure(RoomManager.RoomState state) {
      JoinSession.sendDisconnectOnFailure(this, state);
   }



   public void startUdpPunchBridge(RoomManager.RoomState state, ReliableUdpTransport transport) {
      TransportBridgeRegistry.startUdpPunchBridge(this, state, transport);
   }



   public void startHostUdpPunchBridge(RoomManager.RoomState state, String clientId, ReliableUdpTransport transport) {
      TransportBridgeRegistry.startHostUdpPunchBridge(this, state, clientId, transport);
   }



   // ===== 幽灵连接防护：host侧进入CONNECTED需要对端就绪确认(joiner桥建立后发送的"connected"信令) =====

   public void onPeerBridgeReady(RoomManager.RoomState state, String from) {
      TransportBridgeRegistry.onPeerBridgeReady(this, state, from);
   }

   void finishHostBridgeConnect(RoomManager.RoomState state, String clientId) {
      TransportBridgeRegistry.finishHostBridgeConnect(this, state, clientId);
   }



   // ===== 对端取消不停手修复：打洞进行中收到对端disconnect信号立即中止本轮打洞与持续重试 =====

   public void abortPunchOnPeerDisconnect(String from) {
      JoinSession.abortPunchOnPeerDisconnect(this, from);
   }



   // ===== punching标志复位修复：打洞组已撤销但ctx.punching未清时及时过期，避免join_request被永久排队饿死(MM6SDJ) =====

void expireStaleHostPunchContexts() {
      UdpPunchEngine.expireStaleHostPunchContexts(this);
   }

   void registerQueuedJoinReplay(String from, ScheduledFuture<?> future) {
      JoinSession.registerQueuedJoinReplay(this, from, future);
   }

   void cancelQueuedJoinReplays(String from) {
      JoinSession.cancelQueuedJoinReplays(this, from);
   }

   // host任一对端获胜后立即放行排队join_request(打洞已停，busy必为false)
   public void flushQueuedJoinRequestsAfterWin() {
      JoinSession.flushQueuedJoinRequestsAfterWin(this);
   }

   // 打洞占用期结束后的join_request重放：不再默默丢弃，等打洞一停立即处理

   void scheduleDeferredJoinReplay(RoomManager.RoomState state, String from, JsonObject data, int waitCount) {
      JoinSession.scheduleDeferredJoinReplay(this, state, from, data, waitCount);
   }



   // ===== 零收包终态广播：持续重试耗尽且全会话双向零收包时给出明确结论(WB4V72/MTNFW4) =====

   private void declareZeroRecvFinalFailure(RoomManager.RoomState state) {
      JoinSession.declareZeroRecvFinalFailure(this, state);
   }

   void notifyPeerGiveUpOnZeroRecv(RoomManager.RoomState state) {
      JoinSession.notifyPeerGiveUpOnZeroRecv(this, state);
   }



   // ===== joiner侧：收到host的"incompatible"信令(MC版本不匹配)，明确提示并终止加入流程(S822QF) =====

   public void handleIncompatibleVersion(String from, JsonObject data) {
      JoinSession.handleIncompatibleVersion(this, from, data);
   }



   // ===== 地址族过滤：本端无全局IPv6时丢弃IPv6打洞候选(S822QF/QWA6D3)，结果有60s缓存不会反复探测 =====

   String filterAddressFamilyCandidate(String ip, int port, String tag) {
      return StunNatClassifier.filterAddressFamilyCandidate(this, ip, port, tag);
   }



   /** host 视角查某个 joiner 的声明能力（找不到 peer 或无能力表 = false）。 */
boolean peerSupportsCapability(RoomManager.RoomState state, String clientId, String capability) {
      return JoinSession.peerSupportsCapability(this, state, clientId, capability);
   }

   public void putTransportWithIcePool(String key, ReliableUdpTransport transport) {
      TransportBridgeRegistry.putTransportWithIcePool(this, key, transport);
   }



   public void sendConfirmPackets(DatagramSocket socket, InetSocketAddress addr) {
      TransportBridgeRegistry.sendConfirmPackets(this, socket, addr);
   }



   public void establishUdpTransport(

      RoomManager.RoomState state,

      DatagramSocket socket,

      UdpHolePuncher puncher,

      InetSocketAddress fallbackAddr,

      String transportKey,

      boolean isHost,

      String clientId

   ) {
      TransportBridgeRegistry.establishUdpTransport(this, state, socket, puncher, fallbackAddr, transportKey, isHost, clientId);
   }



void startVoiceForwardBridge(ReliableUdpTransport transport, boolean isHost) {
      TransportBridgeRegistry.startVoiceForwardBridge(this, transport, isHost);
   }



void closeVoiceForwardBridges() {
      TransportBridgeRegistry.closeVoiceForwardBridges(this);
   }



   public void shutdown() {
      JoinSession.shutdown(this);
   }





   public void handleTcpPunchInfo(String from, JsonObject data) {
      TcpPunchEngine.handleTcpPunchInfo(this, from, data);
   }



   public void handleTcpPunchGo(String from, JsonObject data) {
      TcpPunchEngine.handleTcpPunchGo(this, from, data);
   }



void tryTcpHolePunch(RoomManager.RoomState state, String hostMappedIp, int cycle) {
      TcpPunchEngine.tryTcpHolePunch(this, state, hostMappedIp, cycle);
   }



void onTcpPunchSocket(RoomManager.RoomState state, Socket socket, boolean isHost) {
      TcpPunchEngine.onTcpPunchSocket(this, state, socket, isHost);
   }



void connectViaPunchedTcp(RoomManager.RoomState state, Socket punched) {
      TcpPunchEngine.connectViaPunchedTcp(this, state, punched);
   }

}

