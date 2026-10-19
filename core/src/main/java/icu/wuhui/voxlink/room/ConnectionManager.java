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
   static final int JOIN_TRANSIENT_MAX_ATTEMPTS = 3;
   static final long[] JOIN_RETRY_BACKOFF_MS = new long[]{1500L, 3000L};
   final java.util.concurrent.atomic.AtomicInteger joinAttemptGeneration = new java.util.concurrent.atomic.AtomicInteger();

   final ExecutorService punchExecutor;

   final ConcurrentHashMap<String, UdpHolePuncher> activeHolePunchers = new ConcurrentHashMap<>();

   final List<UdpForwardBridge> udpForwardBridges = new CopyOnWriteArrayList<>();

   final ConcurrentHashMap<String, ReliableUdpTransport> activeUdpTransports = new ConcurrentHashMap<>();

   final ConcurrentHashMap<String, ReliableUdpTransport> oldUdpTransports = new ConcurrentHashMap<>();






















   final Map<String, Long> failedRelayPeers = new ConcurrentHashMap<>();

   final AtomicReference<String> currentRelayPeer = new AtomicReference<>(null);

   volatile ScheduledFuture<?> relayFailoverTask = null;

   volatile int nextRelayEligibleRound = 0;

   volatile boolean manualRelayInProgress = false;

   /** 手动中继总超时：90s 到期后 tryRelay 递归立刻终止并走 failed_timeout 分桶。 */
   volatile long manualRelayDeadline = 0L;

   /** 房主收到 relay_request 时 host_notice toast 节流：避免多房客同时请求刷屏。 */
   volatile long lastHostNoticeAt = 0L;

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
   volatile boolean turnInProgress = false;
   /** 后台 P2P 升级监视器（10 分钟窗口，2026-10-05 用户规则）任务句柄与截止时刻。 */
   volatile ScheduledFuture<?> turnBgMonitorTask = null;
   volatile long turnBgDeadlineMs = 0L;
   /** 切换观测任务句柄（次路径稳定性计数）。 */
   volatile ScheduledFuture<?> turnSwitchWatchTask = null;
   final AtomicInteger turnSwitchStreak = new AtomicInteger(0);
   final AtomicBoolean turnBgPunchWon = new AtomicBoolean(false);
   /** 10 分钟窗口耗尽：本连接周期不再后台尝试 P2P，退出重进才重置。 */
   volatile boolean turnP2pGivenUp = false;
   /** 平滑切换已完成（直连已接管、TURN 已释放）。 */
   volatile boolean turnSwitchedToP2p = false;
   /** TURN 保活定时任务（teardown 必须取消，防任务泄漏）。 */
   volatile ScheduledFuture<?> turnKeepaliveTask = null;
   /** 后台监视器 tick 计数（30s/次；奇数次触发玩家中继相位 = 60s）。 */
   final AtomicInteger turnBgTicks = new AtomicInteger(0);
   /**
    * 玩家中继热备（TURN 模式下后台静默建立的 G1 备用通路）：
    * 不建桥、不动连接状态；TURN 死亡时自动转正 + 程序化重连，把"手动退出重进"变成几秒自愈。
    */
   volatile ReliableUdpTransport turnHotstandbyTransport = null;
   /** TURN 会话代次: 拆会话路径递增; 异步写回前校验, 防 teardown 先到复活孤儿会话 */
   final AtomicInteger turnSessionEpoch = new AtomicInteger(0);

   final AtomicBoolean connectionCycleActive = new AtomicBoolean(false);

   final AtomicBoolean reversePunchAttempted = new AtomicBoolean(false);

   final AtomicBoolean connectionWon = new AtomicBoolean(false);
   // turn_ready 应用门: 信令双线程(WS push/HTTP poll)并发分发时防双建桥
   final AtomicBoolean turnReadyApplied = new AtomicBoolean(false);
   // 热备保活句柄: teardown 必须取消, 否则每次热备泄漏一个15s任务
   volatile ScheduledFuture<?> hotstandbyKeepaliveTask;
   // TURN bind/桥类长任务专用池, 防止阻塞信令单线程scheduler
   static final java.util.concurrent.ExecutorService TURN_BG_EXECUTOR =
      java.util.concurrent.Executors.newCachedThreadPool(r -> {
         Thread t = new Thread(r, "VoxLink-TurnBg");
         t.setDaemon(true);
         return t;
      });
   // TCP 双向打洞在飞占位: 防同轮重复邀约
   final AtomicBoolean tcpPunchInFlight = new AtomicBoolean(false);

   final List<ConnectionFallback> activeFallbacks = new CopyOnWriteArrayList<>();


   final AtomicBoolean continuousRetryCancelled = new AtomicBoolean(false);

   final AtomicInteger continuousRetryRound = new AtomicInteger(0);

   volatile PunchFailureClassifier.FailureReason lastFailureReason;

   final AtomicInteger consecutiveFailureCount = new AtomicInteger(0);



   final AtomicInteger iceRestartAttempts = new AtomicInteger(0);

   final AtomicLong lastIceRestartTimeMs = new AtomicLong(0L);

   volatile RoomManager.RoomState savedConnectionState;

   volatile String savedConnectionFrom = "";

   volatile String hostRevPunchPeer;

   volatile String savedConnectionHostIpv6;

   volatile String savedConnectionHostIp;

   volatile int savedConnectionHostPort;

   volatile String savedConnectionHostMappedIp;

   volatile int savedConnectionHostMappedPort;

   volatile ScheduledFuture<?> connectionTimeoutFuture;

   private volatile ScheduledFuture<?> connectionCycleSafetyTimeout;

   volatile long connectionStartTimeMs;

   /** UI"使用中继"按钮计时基准：打洞首周期只设一次，持续重试轮/ICE重启不重置；0=本会话尚未开始打洞。 */
   volatile long punchUiStartMs;

   volatile int connectionTimeoutSec;

   volatile StunProbe.ProbeResult stunProbeResult;

   final AtomicReference<CompletableFuture<StunProbe.ProbeResult>> stunProbeFutureRef = new AtomicReference<>();

   volatile List<Integer> lastHostMappedPorts;
   final java.util.concurrent.ConcurrentHashMap<String, HostPunchContext> hostPunchContexts = new java.util.concurrent.ConcurrentHashMap<>();

   volatile boolean relayConnectedSignaled = false;


   volatile CompletableFuture<Void> dualVoxlinkBridgeFuture ;


   volatile boolean dualRaceActive = false;

   volatile boolean terracottaWon = false;

   volatile boolean voxlinkWon = false;

   volatile boolean voxlinkSideDisabled = false;

   volatile CompletableFuture<Void> dualResultRef;

   final AtomicInteger dualFailedCount = new AtomicInteger(0);

   final AtomicBoolean dualRaceWon = new AtomicBoolean(false);

   private static final long HANDOFF_GRACE_MS = 5000L;

   volatile long connectionEstablishedAtMs = 0L;

   volatile PunchResult lastPunchResult;

   volatile NatClass localNatClass = NatClass.UNKNOWN;

   volatile NatClass remoteNatClass = NatClass.UNKNOWN;

   volatile ScenarioTier.Tier scenarioTier = ScenarioTier.Tier.NORMAL;

   volatile PunchProfile activePunchProfile = PunchProfile.DEFAULT;

   volatile PunchParams activePunchParams ;

   volatile CompletableFuture<Void> relayPrefetchFuture ;


   static final long[] BACKOFF_DELAYS_MS = new long[]{1000L, 2000L, 4000L};

   static final int JOIN_QUEUE_RETRY_SEC = 10;

   // 零收包终态：持续重试已达该轮数且全会话从未收到对端任何UDP包时，宣布最终失败并通知对端停手。
   // 3轮（约2-3分钟）太激进，玩家感知为"无限打洞被砍"；20轮（约20分钟+）对真人会话等效无限，
   // 仅兜住真正死链（双端对称NAT且预测全空）不再无限空转。
   static final int ZERO_RECV_FINAL_ROUND_LIMIT = 20;

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




boolean isRelayPeerFailed(String clientId) {
      return RelayOrchestrator.isRelayPeerFailed(this, clientId);
   }



ConnectionFallback trackFallback(ConnectionFallback f) {
      return RelayOrchestrator.trackFallback(this, f);
   }



void cancelAllFallbacks() {
      RelayOrchestrator.cancelAllFallbacks(this);
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
      return PunchCycleDirector.punchProfile(this);
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



   boolean punchSkipDirect() {
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



void stopAllPunchingForRelay() {
      UdpPunchEngine.stopAllPunchingForRelay(this);
   }



   // canShowRelayButton / cancelManualRelay 已随 2026-10-05 手动中继按钮退役删除：
   // 中继触发统一走智能自动编排器（fireAutoTurn / fireAutoPlayerRelay）。



public void triggerManualRelay() {
      RelayOrchestrator.triggerManualRelay(this);
   }



public boolean isManualRelayInProgress() {
      return RelayOrchestrator.isManualRelayInProgress(this);
   }



   /** 当前批尝试号（用于下方槽位显示「第 N 轮：M 个候选…」）。无进行中时返回 0。 */

   final java.util.concurrent.atomic.AtomicInteger relayBatchIndex = new java.util.concurrent.atomic.AtomicInteger(0);

   /** 玩家中继 v2 子通道分配计数（chan 1..7 循环；0 保留给本端自有会话）。 */
   final java.util.concurrent.atomic.AtomicInteger relayMuxChanCounter = new java.util.concurrent.atomic.AtomicInteger(0);

int allocateRelayMuxChan() {
      return RelayOrchestrator.allocateRelayMuxChan(this);
   }



   final java.util.concurrent.atomic.AtomicInteger relayBatchSize = new java.util.concurrent.atomic.AtomicInteger(0);



   /** 阶段文案：searching / trying_batch / null。render() 会读它显示在下方槽位。 */

   volatile Text relayProgressText = null;



   /** 中继进行中时下方槽位显示文案（null 表示走原有的 terracotta 状态文本）。 */

public Text getRelayProgressText() {
      return RelayOrchestrator.getRelayProgressText(this);
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



void notifyRelayFailed() {
      RelayOrchestrator.notifyRelayFailed(this);
   }



public void stopRelay() {
      RelayOrchestrator.stopRelay(this);
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
      return PunchCycleDirector.getEffectiveMaxCycles(this);
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
 
   NatClass classifyLocalNat() {
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



   boolean shouldPrefetchRelay(NatClass local, NatClass remote, RoomManager.RoomState state) {
      return PunchProfileCtrl.shouldPrefetchRelay(this, local, remote, state);
   }



   CompletableFuture<Void> prefetchRelayCandidates(RoomManager.RoomState state) {
      return PunchProfileCtrl.prefetchRelayCandidates(this, state);
   }



   boolean isLegacyPeer() {

      RoomManager.RoomState state = this.roomManager.currentRoom.get();

      return state != null && state.roomInfo != null ? !state.roomInfo.hostSupportsRelay() : false;

   }



public void runConnectionCycle(

      RoomManager.RoomState state, String from, String hostIpv6, String hostIp, int hostPort, String hostMappedIp, int hostMappedPort, int cycle

   ) {
      PunchCycleDirector.runConnectionCycle(this, state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle);
   }



   // PREDICTION_OFF 封顶记账: 每次 punch 失败调用此方法, 若 reason=PREDICTION_OFF 则自增会话级计数。
   // 原始日志保留, 维持可观测性。abort 由调用方在每个 site 决定(本方法只负责计数 + 撞线日志)。
   void recordPunchFailure(PunchFailureClassifier.FailureReason reason, int recvPunch, int recvAck) {
      PunchProfileCtrl.recordPunchFailure(this, reason, recvPunch, recvAck);
   }



   void scheduleConnectionTimeout(RoomManager.RoomState state, int timeoutSec) {

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
      PunchCycleDirector.tryConnectionStep(this, state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles, step);
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
      PunchCycleDirector.tryUdpPunch(this, state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, displayCycle, maxCycles);
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
      PunchCycleDirector.advanceToNextCycle(this, state, from, hostIpv6, hostIp, hostPort, hostMappedIp, hostMappedPort, cycle, maxCycles);
   }



   public List<String> selectReachableStunGroup(int cycle) {
      return StunNatClassifier.selectReachableStunGroup(this, cycle);
   }



public void startReversePunch(RoomManager.RoomState state) {
      PunchCycleDirector.startReversePunch(this, state);
   }



public void startBirthdayPunch(RoomManager.RoomState state) {
      PunchCycleDirector.startBirthdayPunch(this, state);
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
      PunchCycleDirector.startSimpleReversePunch(this, state);
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
      PunchCycleDirector.startBirthdayPunchPhase2(this, state, birthdayPunchers, birthdayKeys, hostMappedIp, hostMappedPort, hostSymmetric, isEasySym);
   }



public void connectViaBridge(RoomManager.RoomState state, ConnectionFallback.ConnectResult result) {
      RelayOrchestrator.connectViaBridge(this, state, result);
   }



public void connectFed(RoomManager.RoomState state) {
      RelayOrchestrator.connectFed(this, state);
   }

public void handleConnectViaBridgeFailed(RoomManager.RoomState state) {
      RelayOrchestrator.handleConnectViaBridgeFailed(this, state);
   }



public void startFallbackMonitor(ConnectionFallback fallback, RoomManager.RoomState state) {
      RelayOrchestrator.startFallbackMonitor(this, fallback, state);
   }



   /**
    * 持续重试轮次的自动中继触发（1.1.5）：仅 joiner 侧发起（host 被动响应 turn_alloc）。
    * 前置：中继开关开启、未在 TURN/手动中继中、未连上；60s 节流。
    * 失败无害：teardownTurn 后继续走重试循环。
    */

   boolean enterContinuousRetryRound(RoomManager.RoomState state) {

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



void tryRelay(RoomManager.RoomState state) {
      RelayOrchestrator.tryRelay(this, state);
   }



void clearRelayTracking() {
      RelayOrchestrator.clearRelayTracking(this);
   }



public void handleRelayRequest(String from, JsonObject data) {
      RelayOrchestrator.handleRelayRequest(this, from, data);
   }



void dispatchRelaySetup(RoomManager.RoomState state, List<RoomInfo.PeerInfo> candidates, String requestingClientId, RoomInfo.PeerInfo requestingPeer) {
      RelayOrchestrator.dispatchRelaySetup(this, state, candidates, requestingClientId, requestingPeer);
   }



public void handleRelayAccept(String from, JsonObject data) {
      RelayOrchestrator.handleRelayAccept(this, from, data);
   }



public void handleRelayNotify(String from, JsonObject data) {
      RelayOrchestrator.handleRelayNotify(this, from, data);
   }



public void handleRelayDeclined(String from, JsonObject data) {
      RelayOrchestrator.handleRelayDeclined(this, from, data);
   }



   /**
    * 本端在 relay_peers 里的身份。房客=自己的 clientId；房主没有 clientId，
    * 由服务端验过 hostToken 后派生 host-&lt;房间号&gt;（客户端自报该形态一律被拒，冒充不了别人）。
    */
String relayPeerId(RoomManager.RoomState state) {
      return RelayOrchestrator.relayPeerId(this, state);
   }

void scheduleRelayRegistrationRenewal(RoomManager.RoomState state, String natType, String ip, int port) {
      RelayOrchestrator.scheduleRelayRegistrationRenewal(this, state, natType, ip, port);
   }



public void handleRelayReady(String from, JsonObject data) {
      RelayOrchestrator.handleRelayReady(this, from, data);
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
      RelayOrchestrator.triggerTurnRelay(this);
   }

   /**
    * 标准 TURN guest 流程（RFC 5766）：签 cred → Allocate → 发 turn_alloc（带本端 relay 地址）
    * → 等 host turn_ready（handleStdTurnReady 里 ChannelBind + 建 transport + 桥接）。
    */
CompletableFuture<Boolean> startStdTurnGuestFlow(RoomManager.RoomState state, SignalingClient sc, TurnRelayClient.NodeInfo node) {
      return TurnCarrier.startStdTurnGuestFlow(this, state, sc, node);
   }

   /** host 收 turn_alloc：自动 BIND hostTicket → 建 transport → 起 host 桥 → 回 turn_ready。房主无任何 UI。 */
public void handleTurnAlloc(String from, JsonObject data) {
      TurnCarrier.handleTurnAlloc(this, from, data);
   }

   /**
    * host 收 stdTurn turn_alloc（RFC 5766）：签 cred → Allocate → ChannelBind(guest relay)
    * → 建 transport → 起 host 桥 → 回 turn_ready（带本端 relay 地址）。
    * 与自定义协议 handleTurnAlloc 平行；任一步失败回 turn_nack，guest 兜底超时自拆。
    */
private void handleStdTurnAlloc(RoomManager.RoomState state, String from, JsonObject data) {
      TurnCarrier.handleStdTurnAlloc(this, state, from, data);
   }

   /**
    * turn_ready 投递保险 v2: 每 5s 重发, 至多 12 次/60s(有界≤90s)。
    * 停止条件: 会话更替/拆除, 或 guest 数据面回包(getLastRecvTime 前移=已应用 turn_ready)。
    * 重复投递由 guest 侧 turnReadyApplied CAS 幂等吞掉, 不会双建桥。
    */
private void scheduleTurnReadyResend(
      RoomManager.RoomState state, String from, JsonObject ready, Object sessionRef, boolean stdTurn, long pathUpMs, int round
   ) {
      TurnCarrier.scheduleTurnReadyResend(this, state, from, ready, sessionRef, stdTurn, pathUpMs, round);
   }

   /**
    * turn_ready 兜底看门狗 v2: round0 于 20s 触发, 先强制补拉一次(覆盖 WS 半开/安全网
    * 节拍慢于看门狗的漏收)再于 +5s 复查; 仍无且本端信令不健康(WS 断/降级/轮询退避)时
    * 每轮补拉+延寿 20s, 至多 2 轮, 65s 封顶后诚实 teardown。信令健康则 25s 即判死,
    * 死 host 的失败路径仅多等 5s。
    */
void turnReadyWatchdog(RoomManager.RoomState state, int round) {
      TurnCarrier.turnReadyWatchdog(this, state, round);
   }

   /** guest 收 turn_ready：host 数据面就绪 → start 自己的 TURN transport → 桥接进 MC。 */
public void handleTurnReady(String from, JsonObject data) {
      TurnCarrier.handleTurnReady(this, from, data);
   }

   /** guest 收 turn_nack：提前拆线。 */
   /** guest 收 stdTurn turn_ready：ChannelBind(host relay) → 建 transport → 桥接进 MC。 */
private void handleStdTurnReady(String from, JsonObject data) {
      TurnCarrier.handleStdTurnReady(this, from, data);
   }

public void handleTurnNack(String from, JsonObject data) {
      TurnCarrier.handleTurnNack(this, from, data);
   }

   void sendTurnNack(RoomManager.RoomState state, String from, String reason) {
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
void startTurnKeepalive(TurnRelayClient.TurnSession session) {
      TurnCarrier.startTurnKeepalive(this, session);
   }

   /**
    * 标准 TURN 保活：240s 周期 Refresh(allocation 600s) + ChannelBind 重发(channel 10min，
    * permission 随刷)。全部"发了就算"（幂等，丢一次下周补；socket 已交 RUDP，
    * 响应被 codec 丢弃属预期，见 StdTurnClient.refreshQuiet/channelBindQuiet）。
    */
private void startStdTurnKeepalive(StdTurnClient.StdTurnSession session) {
      TurnCarrier.startStdTurnKeepalive(this, session);
   }

private void cancelTurnKeepalive() {
      TurnCarrier.cancelTurnKeepalive(this);
   }

private void cancelHotstandbyKeepalive() {
      TurnCarrier.cancelHotstandbyKeepalive(this);
   }

   /**
    * 后台 P2P 升级监视器：TURN 建立后跑 10 分钟窗口，每 30s 双方协同低频打洞直连。
    * 成功 → 次路径挂入 → 连续 20s 稳定收包 → promote 平滑切换 → 宽限后释放 TURN。
    * 窗口耗尽未成功 → turnP2pGivenUp=true（本连接周期不再尝试，退出重进重置）。
    */
private void startTurnBgMonitor(RoomManager.RoomState state) {
      TurnCarrier.startTurnBgMonitor(this, state);
   }

   /** 玩家中继热备尝试：走既有 relay_request 链路，host 正常派发；打通后仅存热备不建桥。 */
private void attemptBgPlayerRelay(RoomManager.RoomState state) {
      TurnCarrier.attemptBgPlayerRelay(this, state);
   }

private void cancelTurnBgMonitor() {
      TurnCarrier.cancelTurnBgMonitor(this);
   }

   /** 一轮后台直连协同打洞：本端 punch 对端 mapped 地址，同时用信号唤醒对端 punch 本端。 */
private void attemptBgDirectPunch(RoomManager.RoomState state) {
      TurnCarrier.attemptBgDirectPunch(this, state);
   }

   /** 5s 短窗 puncher：成功即把直连 socket 作为次路径挂入 TURN transport。 */
private void startBgDirectPuncher(RoomManager.RoomState state, String ip, int port) {
      TurnCarrier.startBgDirectPuncher(this, state, ip, port);
   }

   /** host 收 turn_bg_punch：拿 guest 最新 mapped 地址，起本端 puncher 完成双向协同。 */
public void handleTurnBgPunch(String from, JsonObject data) {
      TurnCarrier.handleTurnBgPunch(this, from, data);
   }

   /** 直连通了：挂次路径（双收）→ 观测 20s 稳定 → promote 切发送 → 宽限后释放 TURN。 */
private void onBgDirectPath(RoomManager.RoomState state, DatagramSocket socket, InetSocketAddress addr) {
      TurnCarrier.onBgDirectPath(this, state, socket, addr);
   }

   /** TURN 模式下的玩家中继热备：punch 通中继玩家后仅保存备用线路，不建桥、不动连接状态。 */
void establishHotstandby(RoomManager.RoomState state, String relayIp, int relayPort) {
      TurnCarrier.establishHotstandby(this, state, relayIp, relayPort);
   }

   /** host 收 turn_stby：仅提示"该房客已建立玩家中继备用线路"（无动作，桥本就存在）。 */
public void handleTurnStby(String from, JsonObject data) {
      TurnCarrier.handleTurnStby(this, from, data);
   }

private void cancelTurnSwitchWatch() {
      TurnCarrier.cancelTurnSwitchWatch(this);
   }

   /** 切换完成收尾：UNBIND + 通知信令释放容量 + UI 状态回直连；热备一并退役（直连已是最佳路径）。 */
private void finishTurnRelease(RoomManager.RoomState state) {
      TurnCarrier.finishTurnRelease(this, state);
   }

   /** TURN 失败/超时清理：unbind + release + 状态复位；TURN 断开时热备转正（程序化重连自愈）。 */
void teardownTurn(RoomManager.RoomState state, String failKey) {
      TurnCarrier.teardownTurn(this, state, failKey);
   }

   /** 房间销毁/断开时的静默清理（服务端 TTL 兜底之外的礼貌释放）。 */
public void cleanupTurnQuietly() {
      TurnCarrier.cleanupTurnQuietly(this);
   }

   // ================= END TURN 中继状态机 =================

   // ================= 智能自动中继编排器（2026-10-05：手动中继按钮退役） =================
   // 证据分级触发（非盲轮数）：死局NAT组合15s / 证据累计30s / 兜底45s，全部要求零收包。
   // TURN 优先（建立快）；中继传输建立 ≠ 成功——25s 真连接验证窗盯 MC 会话下行首包，
   // 验证失败判该道"建立但死"，无缝换道（joiner 本地桥/MC TCP 全程不动，只换承载 +
   // 重放上行日志；host 侧旧桥随旧传输死亡自拆，新桥已由 relay_accept 预先武装）。
   volatile ScheduledFuture<?> autoRelayDirectorTask;
   /** 房间态未就绪时的挂载复查任务(就绪/会话终结/退房即自取消) */
   volatile ScheduledFuture<?> autoRelayArmWaiterTask;
   volatile ScheduledFuture<?> relayVerifyWatchdogTask;
   /** 验证后下行保鲜窗: 持续零下行判该道停滞 */
   static final long RELAY_DOWNLINK_FRESH_MS = 6000L;
   volatile boolean autoRelayTurnAttempted;
   volatile boolean autoRelayTurnUnhealthy;
   volatile boolean autoRelayPlayerAttempted;
   /** 自动尝试进行中：中继失败路径走换道/回打洞而非失败屏。 */
   volatile boolean autoRelayMode;
   /** 触发时的持续重试轮（轮次推进→重置两条道的尝试位，新一轮打洞=新中继机会）。 */
   volatile int autoRelayArmedRound = -1;
   /** 无缝换道挂起中的新承载（换道确认后热切，此前由旧道继续扛）。 */
   volatile ReliableUdpTransport pendingCarrierSwapTransport;
   /** pendingCarrierSwapTransport 的旧道形态（"turn"/"player"），换道成功后据此拆旧。 */
   volatile String carrierSwapFrom;

public void startAutoRelayDirector(RoomManager.RoomState state) {
      RelayOrchestrator.startAutoRelayDirector(this, state);
   }

public void stopAutoRelayDirector() {
      RelayOrchestrator.stopAutoRelayDirector(this);
   }

   boolean isDeadNatCombo(RoomManager.RoomState state) {
      return StunNatClassifier.isDeadNatCombo(this, state);
   }

   boolean isSamePublicIpAsHost(RoomManager.RoomState state) {
      return StunNatClassifier.isSamePublicIpAsHost(this, state);
   }

void fireAutoRelay(RoomManager.RoomState state) {
      RelayOrchestrator.fireAutoRelay(this, state);
   }

void fireAutoTurn(RoomManager.RoomState state) {
      RelayOrchestrator.fireAutoTurn(this, state);
   }

void fireAutoPlayerRelay(RoomManager.RoomState state) {
      RelayOrchestrator.fireAutoPlayerRelay(this, state);
   }

   /** 中继失败路径统一口：自动模式走换道/回打洞，手动(旧版对端触发等)保持失败屏。 */
void relayFailOrAuto(RoomManager.RoomState state, String reasonKey) {
      RelayOrchestrator.relayFailOrAuto(this, state, reasonKey);
   }

void autoRelayFailoverOrEnd(RoomManager.RoomState state, String reasonKey) {
      RelayOrchestrator.autoRelayFailoverOrEnd(this, state, reasonKey);
   }

   /**
    * 真连接验证窗：中继桥就绪后 25s 内必须见到 MC 会话下行数据（服务器真回了包）
    * 或 isMcTrulyConnected。超窗=该道"建立但死"，静默换道。
    * 连接期上行字节被 P2PBridge 记账（journal），换道后重放——MC 服务器侧旧会话
    * 本就未完成登录，重放一条完整握手无副作用。
    */
void armRelayVerifyWatchdog(RoomManager.RoomState state, boolean turnPath) {
      RelayOrchestrator.armRelayVerifyWatchdog(this, state, turnPath);
   }

void cancelRelayVerifyWatchdog() {
      RelayOrchestrator.cancelRelayVerifyWatchdog(this);
   }

   /** 验证失败：该道"建立但死"。挂起无缝换道（新道建好→热切+重放→拆旧道）。 */
void onRelayVerifyDead(RoomManager.RoomState state, boolean turnPath) {
      RelayOrchestrator.onRelayVerifyDead(this, state, turnPath);
   }

   /**
    * startUdpPunchBridge 的换道分支：carrierSwapFrom 置位（旧道验证死）且本地桥在跑时，
    * 新到传输不新建桥，而是热切承载 + 重放上行日志 + 拆旧道。host 侧无需换道信令——
    * 旧传输关闭后 host 旧桥自拆，新桥已由 relay_accept/turn_alloc 流程预先武装；
    * 早到的字节会在子通道接收缓冲里排队，host 新桥就绪即排干。
    */
boolean tryCarrierSwap(RoomManager.RoomState state, ReliableUdpTransport newTransport) {
      return RelayOrchestrator.tryCarrierSwap(this, state, newTransport);
   }

   /** 换道成功后拆旧 TURN（不再触发失败文案/重连，桥已由新道承载）。 */
void teardownTurnQuietly() {
      TurnCarrier.teardownTurnQuietly(this);
   }

public void stopAutoRelayAll() {
      RelayOrchestrator.stopAutoRelayAll(this);
   }

   /** startDualP2P 早于房间态就绪时：每秒复查挂载, 直到就绪/会话终结/退房(修 joinRoom>1s 整场落空)。 */
void scheduleAutoRelayDirectorArm() {
      RelayOrchestrator.scheduleAutoRelayDirectorArm(this);
   }

void cancelAutoRelayDirectorArm() {
      RelayOrchestrator.cancelAutoRelayDirectorArm(this);
   }

   /**
    * 玩家中继模式后台直连探测（10 分钟窗口，30s 一次）：
    * 满足"自动中继后后台持续 10 分钟打洞"。本轮为探测+日志形态——
    * host 侧为被中继会话热切直连载具需要跨房虚拟加入同一套配套，下迭代落地；
    * TURN 模式不受此限（startTurnBgMonitor 全功能协同+平滑切换）。
    */
   volatile ScheduledFuture<?> relayBgProbeTask;

void startRelayBgProbe(RoomManager.RoomState state) {
      RelayOrchestrator.startRelayBgProbe(this, state);
   }

void cancelRelayBgProbe() {
      RelayOrchestrator.cancelRelayBgProbe(this);
   }

public void handleRelaySetup(String from, JsonObject data) {
      RelayOrchestrator.handleRelaySetup(this, from, data);
   }



public void onRelayDisconnected(String peerA, String peerB) {
      RelayOrchestrator.onRelayDisconnected(this, peerA, peerB);
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

   void resetConnectionStateForNextP2P() {

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



   boolean shouldContinuousRetry(RoomManager.RoomState state) {
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



void escalateProfileForRound(int round) {
      PunchCycleDirector.escalateProfileForRound(this, round);
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
      TurnCarrier.resetIceRestartState(this);
   }



public void handleIceRestart(String from, JsonObject data) {
      TurnCarrier.handleIceRestart(this, from, data);
   }



public void triggerIceRestart() {
      TurnCarrier.triggerIceRestart(this);
   }



private boolean shouldIceRestart(RoomManager.RoomState state) {
      return TurnCarrier.shouldIceRestart(this, state);
   }



private void performIceRestart(RoomManager.RoomState state, String reason) {
      TurnCarrier.performIceRestart(this, state, reason);
   }



private void sendIceRestartSignal(RoomManager.RoomState state) {
      TurnCarrier.sendIceRestartSignal(this, state);
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
      return PunchCycleDirector.claimVoxlinkDualWin(this);
   }



public CompletableFuture<Void> startDualP2P(String roomCode, String playerName, String password, BiConsumer<String, String> statusCallback) {
      return PunchCycleDirector.startDualP2P(this, roomCode, playerName, password, statusCallback);
   }



CompletableFuture<Void> runDualP2PRace(String roomCode, String playerName, String password, BiConsumer<String, String> statusCallback) {
      return PunchCycleDirector.runDualP2PRace(this, roomCode, playerName, password, statusCallback);
   }



   void connectTerracottaToMC(String connectUrl, String roomCode) {
      TransportBridgeRegistry.connectTerracottaToMC(this, connectUrl, roomCode);
   }



   static int parsePortFromUrl(String url) {
      return JoinSession.parsePortFromUrl(url);
   }



CompletableFuture<Void> startVoxLinkP2P(String roomCode, String password) {
      return PunchCycleDirector.startVoxLinkP2P(this, roomCode, password);
   }

CompletableFuture<Void> startVoxLinkP2PAttempt(String roomCode, String password, int attempt, int gen) {
      return PunchCycleDirector.startVoxLinkP2PAttempt(this, roomCode, password, attempt, gen);
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

   void declareZeroRecvFinalFailure(RoomManager.RoomState state) {
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

