package icu.wuhui.voxlink.network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReliableUdpTransport implements AutoCloseable {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-rudp");
   private static final byte[] MAGIC = new byte[]{86, 76};
   private static final byte TYPE_PUNCH = 1;
   private static final byte TYPE_PUNCH_ACK = 2;
   private static final byte TYPE_DATA = 3;
   private static final byte TYPE_ACK = 4;
   private static final byte TYPE_DISCONNECT = 7;
   private static final byte TYPE_KEEPALIVE = 8;
   private static final byte TYPE_FEC_XOR = 9;
   private static final byte TYPE_RESTART = 10;
   private static final byte TYPE_VOICE = 11;
   private static final int FEC_GROUP_SIZE = 4;
   private static final int HEADER_SIZE = 11;
   private static final int PAYLOAD_LEN_SIZE = 2;
   private static final int MAX_PAYLOAD = 1400;
   // 保 FEC 帧(22头+块+4MAC)不超节点 1400
   private static final int TURN_MAX_SEND_CHUNK = 1374;
   private static final int WINDOW_SIZE = 64;
   private static final long RETRANSMIT_TIMEOUT_MS = 800L;
   private static final int KEEPALIVE_INTERVAL_S = 1;
   private static final int KEEPALIVE_TIMEOUT_S = 60;
   // 判单向死所需连续失败心跳拍数
   private static final int HEARTBEAT_FAIL_LIMIT = 5;
   // 判TX死(对端收不到我)所需连续零ACK推进拍数
   private static final int TX_NO_ACK_FAIL_LIMIT = 10;
   private static final int MAX_SILENT_RETRANSMIT_CYCLES = 30;
   private static final int UNRELIABLE_FAIL_THRESHOLD = 5;
   private static final long UNRELIABLE_SILENCE_MS = 8000L;
   private static final int MAX_FEC_GROUP_SIZE = 20;
   private static final int FEC_MAX_PACKET_SIZE = 1454;
   private static final int FEC_CLEAN_WINDOW = 10;
   private static final int SMALL_PACKET_THRESHOLD = 512;
   private static final int POLL_INTERVAL_MS = 50;
   private static final int MAX_BUFFERED_CHUNKS = 512;
   private static final long RTO_MIN_MS = 100L;
   private static final long RTO_MAX_MS = 800L;
   private static final long CLOCK_GRANULARITY_MS = 10L;
   private static final int WINDOW_MIN = 16;
   private static final int WINDOW_MAX = 64;
   private static final int LOSS_SAMPLE_LIMIT = 200;
   private static final long MAX_RETRANSMIT_TOTAL_MS = 24000L;
   private static final long RETRANSMIT_BACKOFF_MS = 250L;
   // 死对端熔断: 连续重传轮次达到 ROUNDS 且全程对端零收包、总静默超过 MIN_SILENCE_MS 时
   // 清空积压重传队列(只 WARN 一次)。MIN_SILENCE_MS=23s: 对端 keepalive 间隔仅 1s,
   // 活链接绝不可能 23s 零收包; 且贴着下方既有的 24s 强制关闭, 保证熔断绝不早于
   // 旧版行为掐断任何可能自愈的链路, 主要收益是清理对端已死后的僵尸重传。
   private static final int DEAD_PEER_BREAKER_ROUNDS = 20;
   private static final long DEAD_PEER_BREAKER_MIN_SILENCE_MS = 23000L;
   // 熔断救援: 清窗不丢数据, 转慢速重传直至 ack 覆盖或轮次耗尽
   private static final long DEAD_PEER_RESCUE_INTERVAL_MS = 6000L;
   private static final int DEAD_PEER_RESCUE_MAX_ROUNDS = 8;
   private volatile long srtt = -1L;
   private volatile long rttvar = 0L;
   private volatile long rto = 200L;
   private int lossEvents = 0;
   private int lossLostEvents = 0;
   private volatile int effectiveWindow = 64;
   private volatile int lastAckSeq = -1;
   private int dupAckCount = 0;
   private final DatagramSocket socket;
   private volatile InetSocketAddress remoteAddress;
   // 平滑切换（双路径）：primary 永远是发送路径；secondary 为切换候选/宽限期旧路径。
   // 初始 primary 与 this.socket 同引用（直连路径，无包封）。
   private volatile UdpPath primaryPath;
   private volatile UdpPath secondaryPath;
   private volatile long switchGraceUntilMs = 0L;
   private volatile boolean remoteConfirmed = false;
   private volatile boolean running = true;
   private final AtomicBoolean connected = new AtomicBoolean(false);
   private volatile long lastRecvTime = System.currentTimeMillis();
   private final AtomicBoolean closed = new AtomicBoolean(false);
   private volatile int writeState = 0;
   private static final int STATE_WRITABLE = 0;
   private static final int STATE_UNRELIABLE = 1;
   private volatile int consecutiveFailures = 0;
   private final AtomicBoolean iceRestartTriggered = new AtomicBoolean(false);
   // 死对端熔断状态(仅在 retransmitCheck 的单线程 scheduler 上访问):
   // streakStartMs 锚定到本段连续重传起点, 期间任何对端收包都会使 lastRecvTime 前移从而整体复位
   private int deadPeerRetransmitRounds = 0;
   private long deadPeerStreakStartMs = 0L;
   // 救援队列(sendLock 内访问): 熔断清出的积压。对端恢复后按其 nextExpectedSeq 无损续流;
   // 直接丢弃会让接收侧卡在 seq 空洞(keepalive 照常流动, 两条判死规则全失效), 数据面静默死亡
   private final ConcurrentSkipListMap<Integer, ReliableUdpTransport.PendingPacket> deadPeerRescueMap = new ConcurrentSkipListMap<>();
   private int deadPeerRescueLastSeq = -1;
   private int deadPeerRescueRounds = 0;
   private long deadPeerRescueLastResendMs = 0L;
   // 单向死检测(仅scheduler线程)
   private long lastHeartbeatMs = System.currentTimeMillis();
   private int heartbeatFailStreak = 0;
   private boolean heartbeatEverRx = false;
   // TX死检测(仅scheduler线程): 在途包oldest零推进
   private int txNoAckStreak = 0;
   private int txNoAckLastOldest = -1;
   // punchAuthV1：数据面认证上下文（null = 非认证模式，帧格式与旧版本逐字节一致）
   private volatile byte[] authKeyBytes;
   private volatile Mac authMac;
   private volatile Runnable onIceRestartRequested;
   private volatile java.util.function.Consumer<byte[]> onVoiceData;
   private volatile int nextSendSeq = 0;
   private volatile int oldestUnackedSeq = 0;
   private final ConcurrentSkipListMap<Integer, ReliableUdpTransport.PendingPacket> pendingAcks = new ConcurrentSkipListMap<>();
   private final Object sendLock = new Object();
   private final AtomicInteger nextExpectedSeq = new AtomicInteger(0);
   private final ConcurrentSkipListMap<Integer, byte[]> recvBuffer = new ConcurrentSkipListMap<>();
   private final Object recvLock = new Object();
   private final ReliableUdpTransport.UdpInputStream inputStream = new ReliableUdpTransport.UdpInputStream();
   private final ReliableUdpTransport.UdpOutputStream outputStream = new ReliableUdpTransport.UdpOutputStream();
   private final ConcurrentLinkedQueue<byte[]> outboundQueue = new ConcurrentLinkedQueue<>();
   private int bufferedChunks = 0;
   private Thread recvThread;
   private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "VoxLink-Retransmit");
      t.setDaemon(true);
      return t;
   });
   private ScheduledFuture<?> retransmitTask;
   private ScheduledFuture<?> keepaliveTask;
   private final List<byte[]> fecSendGroup = new ArrayList<>();
   private int fecSendGroupSeq = -1;
   private final Object fecSendLock = new Object();
   private final ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, byte[]>> fecRecvGroup = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<Integer, byte[]> fecRecvXor = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<Integer, int[]> fecRecvLengths = new ConcurrentHashMap<>();
   private volatile int minActiveGroupId = Integer.MAX_VALUE;
   private volatile int pendingRebindPort = -1;
   private volatile long lastCurrentPortRecvMs = 0L;
   private volatile long pendingRebindTime = 0L;

   // ================= 多路复用子通道（玩家中继 v2 数据面） =================
   // mux 模式下 DATA 载荷 = [1字节chan][数据]；chan0=本连接自有流（兼容旧读者），
   // 1..15=中继子通道。仅双方声明 relayMuxV1 时启用；
   // 未启用时线上字节与历史版本逐字节一致（互操作零影响）。
   // 共享同一可靠序号空间/窗口/重传：子通道获得与主流一致的可靠有序语义。
   private volatile boolean muxEnabled = false;
   private final ConcurrentHashMap<Integer, ReliableUdpTransport.SubChannel> subChannels = new ConcurrentHashMap<>();

   /** 必须在 start() 之前调用；由连接建立时的能力协商结果驱动。 */
   public void enableMux() {
      this.muxEnabled = true;
   }

   public boolean isMuxEnabled() {
      return this.muxEnabled;
   }

   public InputStream getSubstreamInput(int chan) {
      return this.subChannel(chan).getInput();
   }

   public OutputStream getSubstreamOutput(int chan) {
      return this.subChannel(chan).getOutput();
   }

   /** 子通道是否已被对端正常关闭（EOF 到达且缓冲读干）。 */
   public boolean isSubstreamEof(int chan) {
      ReliableUdpTransport.SubChannel sc = this.subChannels.get(chan);
      return sc == null || sc.eof;
   }

   private ReliableUdpTransport.SubChannel subChannel(int chan) {
      return this.subChannels.computeIfAbsent(chan, k -> new ReliableUdpTransport.SubChannel());
   }

   /**
    * 收到完整 DATA 载荷后的统一投递口：mux 模式按首字节 chan 路由，
    * 载荷长度 1（仅 chan 字节）= 该子通道对端关闭（EOF）。
    */
   private void deliverPayload(byte[] payload) {
      if (!this.muxEnabled) {
         this.inputStream.writeBuffer(payload);
         return;
      }
      if (payload.length == 0) {
         return;
      }
      int chan = payload[0] & 0xFF;
      if (payload.length == 1) {
         ReliableUdpTransport.SubChannel sc = this.subChannels.get(chan);
         if (sc != null) {
            sc.markEof();
         } else if (chan == 0) {
            this.inputStream.markEof();
         } else if (chan >= 1 && chan <= 7) {
            // 早到 EOF(对端先关而本端子通道未建): 落位标记让读侧即刻见 -1,
            // 不再把等待方挂到传输级判死; >7 视为垃圾不落位
            this.subChannel(chan).markEof();
         }
         return;
      }
      byte[] inner = java.util.Arrays.copyOfRange(payload, 1, payload.length);
      if (chan == 0) {
         this.inputStream.writeBuffer(inner);
      } else {
         this.subChannel(chan).writeBuffer(inner);
      }
   }

   private class SubChannel {
      private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> chunks = new java.util.concurrent.ConcurrentLinkedQueue<>();
      private volatile boolean eof = false;

      void writeBuffer(byte[] data) {
         this.chunks.offer(data);
      }

      void markEof() {
         this.eof = true;
      }

      InputStream getInput() {
         return new InputStream() {
            private byte[] currentChunk = null;
            private int currentPos = 0;

            @Override
            public int read() throws IOException {
               byte[] b = new byte[1];
               int n = this.read(b, 0, 1);
               return n <= 0 ? -1 : b[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
               if (len == 0) {
                  return 0;
               }
               synchronized (ReliableUdpTransport.this.recvLock) {
                  while (this.currentChunk == null || this.currentPos >= this.currentChunk.length) {
                     this.currentChunk = SubChannel.this.chunks.poll();
                     this.currentPos = 0;
                     if (this.currentChunk != null) {
                        if (ReliableUdpTransport.this.bufferedChunks > 0) {
                           ReliableUdpTransport.this.bufferedChunks--;
                        }
                     } else {
                        if (SubChannel.this.eof
                           || !ReliableUdpTransport.this.running
                           || ReliableUdpTransport.this.closed.get()) {
                           return -1;
                        }
                        try {
                           ReliableUdpTransport.this.recvLock.wait(500L);
                        } catch (InterruptedException e) {
                           Thread.currentThread().interrupt();
                           throw new IOException("Interrupted");
                        }
                     }
                  }
                  if (this.currentChunk == null) {
                     return -1;
                  }
                  int avail = this.currentChunk.length - this.currentPos;
                  int toRead = Math.min(len, avail);
                  System.arraycopy(this.currentChunk, this.currentPos, b, off, toRead);
                  this.currentPos += toRead;
                  if (this.currentPos >= this.currentChunk.length) {
                     this.currentChunk = null;
                     this.currentPos = 0;
                  }
                  return toRead;
               }
            }
         };
      }

      OutputStream getOutput() {
         return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
               this.write(new byte[]{(byte)b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
               ReliableUdpTransport.this.sendBytes(b, off, len, SubChannel.this.chanId());
            }

            @Override
            public void close() throws IOException {
               ReliableUdpTransport.this.closeSubstream(SubChannel.this.chanId());
            }
         };
      }

      int chanId() {
         for (java.util.Map.Entry<Integer, ReliableUdpTransport.SubChannel> e : ReliableUdpTransport.this.subChannels.entrySet()) {
            if (e.getValue() == this) {
               return e.getKey();
            }
         }
         return 0;
      }
   }

   /**
    * 本端主动关闭子通道：发送仅含 chan 字节的 1 字节载荷（mux 模式下即 EOF 标记），
    * 作为可靠 DATA 帧走重传保障。chan 的读侧收到 EOF 且缓冲读干后 read() 返回 -1。
    */
   public void closeSubstream(int chan) {
      if (!this.muxEnabled) {
         return;
      }
      synchronized (this.sendLock) {
         if (!this.running || this.closed.get()) {
            return;
         }
         int seq = this.nextSendSeq++;
         this.pendingAcks.put(seq, new ReliableUdpTransport.PendingPacket(new byte[]{(byte)chan}, System.currentTimeMillis(), 0));
         this.sendDataPacket(seq, new byte[]{(byte)chan}, false);
      }
   }

   public ReliableUdpTransport(DatagramSocket socket, InetSocketAddress remoteAddress) {
      this(socket, remoteAddress, null);
   }

   /** TURN 路径构造器：primary 即为 TURN 包封路径（rudp 帧套 TURN DATA 头）。 */
   public ReliableUdpTransport(DatagramSocket socket, InetSocketAddress remoteAddress, UdpPath.Codec primaryCodec) {
      this.socket = socket;
      this.remoteAddress = remoteAddress;
      this.primaryPath = new UdpPath(socket, remoteAddress, primaryCodec);

      try {
         socket.setSoTimeout(100);
      } catch (Exception var4) {
      }
   }

   /**
    * punchAuthV1：启用数据面认证。启用后所有帧（DATA/ACK/KEEPALIVE/DISCONNECT/
    * RESTART/FEC_XOR/VOICE）尾部附加 4 字节截断 HMAC-SHA256；接收侧校验失败一律丢弃，
    * maybeRebindRemote 因此只接受 MAC 有效源。须在 start() 之前调用。
    */
   public void setAuthKey(byte[] key) {
      this.authKeyBytes = key == null ? null : key.clone();
      this.authMac = PunchAuth.createMac(this.authKeyBytes);
   }

   public boolean isAuthEnabled() {
      return this.authMac != null;
   }

   // TURN 互操作降级（1.1.5）：对端为旧引擎（桌面 App 1.1.4-beta / 旧版 mod）时其 TURN
   // transport 可能未武装密钥——本端 armed 后会把对端明文帧全部丢弃, 中继建得起通不了
   // (实证 09-11 23:03: path up 同秒即互丢, 19s 后桥死)。仅 TURN 路径开启: 连续 3 帧
   // 认证失败即降级为双方明文(旧线上格式); 直连 P2P 路径保持严格认证不受影响。
   private volatile boolean allowAuthDowngrade = false;
   private int consecutiveAuthDrops = 0;

   public void allowAuthDowngradeForInterop() {
      this.allowAuthDowngrade = true;
   }

   /** 认证失败丢弃时调用：达到阈值且允许降级 → 关闭本 transport 认证（收发均回明文旧格式）。 */
   private void handleAuthDrop(String where) {
      PunchAuth.logDrop(where);
      if (!this.allowAuthDowngrade || this.authMac == null) {
         return;
      }
      if (++this.consecutiveAuthDrops < 3) {
         return;
      }
      LOGGER.warn("[ReliableUdp] peer sends unauthenticated frames x{}, TURN interop downgrade to plaintext", this.consecutiveAuthDrops);
      this.authKeyBytes = null;
      this.authMac = null;
      // 降级与心跳判死同拍竞速会差毫秒级把刚建立的明文链路判死：
      // 这里重置判死锚点，给明文通道一个完整的心跳窗口。
      this.heartbeatFailStreak = 0;
      this.lastRecvTime = System.currentTimeMillis();
   }

   /** 出帧统一出口：认证模式追加 MAC，否则原样返回（旧线上格式）。 */
   private byte[] finalizeFrame(byte[] frame) {
      Mac mac = this.authMac;
      return mac != null ? PunchAuth.appendTrailer4(mac, frame) : frame;
   }

   public InputStream getInputStream() {
      return this.inputStream;
   }

   public OutputStream getOutputStream() {
      return this.outputStream;
   }

   public void start() {
      if (this.closed.get()) {
         return;
      }
      if (this.connected.compareAndSet(false, true)) {
         try {
            byte[] data = new byte[11];
            System.arraycopy(MAGIC, 0, data, 0, 2);
            data[2] = 8;
            writeInt32(data, 3, 0);
            writeInt32(data, 7, 0);
            byte[] framed = this.finalizeFrame(data);
            this.sendOnPrimary(framed);
         } catch (IOException var2) {
         }

         this.recvThread = new Thread(this::receiveLoop, "VoxLink-UdpRecv");
         this.recvThread.setDaemon(true);
         this.recvThread.start();
         this.retransmitTask = this.scheduler.scheduleWithFixedDelay(this::retransmitCheck, 50L, 50L, TimeUnit.MILLISECONDS);
         this.scheduler.scheduleWithFixedDelay(this::flushOutbound, 50L, 50L, TimeUnit.MILLISECONDS);
         this.keepaliveTask = this.scheduler.scheduleWithFixedDelay(this::heartbeatTick, (long)KEEPALIVE_INTERVAL_S, (long)KEEPALIVE_INTERVAL_S, TimeUnit.SECONDS);
      }
   }

   public boolean isConnected() {
      return this.connected.get() && this.running;
   }

   /** 最近收到对端数据的时刻，投递保险回执用。 */
   public long getLastRecvTime() {
      return this.lastRecvTime;
   }

   /** 载具热切排干用：是否还有未被确认的出口数据（含积压重传队列）。 */
   public boolean hasPendingOutbound() {
      return !this.pendingAcks.isEmpty();
   }

   /** 热备线路保活用：对外暴露 keepalive 帧发送（不改变连接状态机）。 */
   public void sendKeepaliveFrame() {
      this.sendKeepalive();
   }

   public void setOnIceRestartRequested(Runnable r) {
      this.onIceRestartRequested = r;
   }

   public void setOnVoiceData(java.util.function.Consumer<byte[]> c) {
      this.onVoiceData = c;
   }

   /** 按当前主路径取发送分块上限 */
   private int currentMaxSendChunk() {
      UdpPath pri = this.primaryPath;
      return pri != null && pri.codec != null ? TURN_MAX_SEND_CHUNK : MAX_PAYLOAD;
   }

   public void sendVoice(byte[] payload) {
      // 构造 socket 检查放宽: TURN->直连平滑切换后旧 TURN socket 已关闭,
      // 但 primaryPath 已 promote 到直连 socket——只看 this.socket 会让语音永久哑掉(审计 P2-4)
      UdpPath primary = this.primaryPath;
      boolean anySendable = (this.socket != null && !this.socket.isClosed())
         || (primary != null && primary.socket != null && !primary.socket.isClosed());
      if (payload == null || !anySendable) return;
      if (payload.length > this.currentMaxSendChunk()) {
         LOGGER.debug("[ReliableUdp] Voice payload too large ({}), dropped", payload.length);
         return;
      }
      byte[] data = new byte[11 + payload.length];
      System.arraycopy(MAGIC, 0, data, 0, 2);
      data[2] = TYPE_VOICE;
      System.arraycopy(payload, 0, data, 11, payload.length);
      byte[] framed = this.finalizeFrame(data);
      try {
         this.sendOnPrimary(framed);
      } catch (IOException e) {
         LOGGER.debug("[ReliableUdp] Voice send failed: {}", e.getMessage());
      }
   }

   private void handleVoice(byte[] buf, int len) {
      java.util.function.Consumer<byte[]> c = this.onVoiceData;
      if (c != null && len > 11) {
         try {
            c.accept(java.util.Arrays.copyOfRange(buf, 11, len));
         } catch (Throwable t) {
            // 语音桥异常绝不能影响 MC 数据面
            LOGGER.warn("[ReliableUdp] voice handler exception: {}", t.getMessage());
         }
      }
   }

   public void requestIceRestart() {
      this.triggerIceRestart();
   }

   private void triggerIceRestart() {
      if (this.closed.get()) {
         return;
      }
      if (this.iceRestartTriggered.compareAndSet(false, true)) {
         Runnable r = this.onIceRestartRequested;
         if (r != null) {
            try {
               r.run();
            } catch (Exception e) {
               LOGGER.warn("[ReliableUdp] ICE Restart callback exception: {}", e.getMessage());
            }
         }
      }
   }

   private void sendRestart() {
      try {
         byte[] data = new byte[11];
         System.arraycopy(MAGIC, 0, data, 0, 2);
         data[2] = 10;
         writeInt32(data, 3, 0);
         writeInt32(data, 7, 0);
         byte[] framed = this.finalizeFrame(data);
         this.sendOnPrimary(framed);
      } catch (IOException var2) {
      }
   }

   private void sendPunchAck(UdpPath path, SocketAddress from) {
      try {
         byte[] data = new byte[]{MAGIC[0], MAGIC[1], 2, 0, 0};
         byte[] framed = this.finalizeFrame(data);
         path.socket.send(new DatagramPacket(framed, framed.length, from));
      } catch (IOException var3) {
      }
   }

   /**
    * 地址漂移重绑：仅对无包封的直连路径生效（TURN 路径对端固定为节点，不漂移）。
    * 更新的是该路径自身的 remoteAddress；primary 路径的漂移同步到 this.remoteAddress 以兼容旧读取方。
    */
   private void maybeRebindRemote(DatagramPacket packet, UdpPath path) {
      if (path.codec != null) {
         return;
      }

      InetSocketAddress cur = path.remoteAddress;
      if (cur == null) {
         return;
      }

      boolean isPrimary = path == this.primaryPath;
      boolean fromCurrent = packet.getPort() == cur.getPort() && packet.getAddress().equals(cur.getAddress());
      if (fromCurrent) {
         if (isPrimary) {
            this.remoteConfirmed = true;
            this.lastCurrentPortRecvMs = System.currentTimeMillis();
         }

         this.pendingRebindPort = -1;
      } else if (packet.getAddress().equals(cur.getAddress())) {
         long now = System.currentTimeMillis();
         // 1.0.0兼容(其remoteAddress为final无rebind): 当前端口仍存活(6s内收到过其有效包)时绝不rebind。
         // 连接初期对端其它打洞socket的噪声包会把发送目标切到即将关闭的死映射, ACK全进黑洞
         // (1.1.0-2实测: host重传1130次后70s断链)。仅当前端口真死(超时无包)才允许切换, 保留真漂移兜底。
         boolean currentAlive = isPrimary && this.lastCurrentPortRecvMs > 0L && now - this.lastCurrentPortRecvMs < 6000L;
         if (!currentAlive) {
            if (packet.getPort() == this.pendingRebindPort && now - this.pendingRebindTime < 5000L) {
               path.remoteAddress = new InetSocketAddress(cur.getAddress(), packet.getPort());
               if (isPrimary) {
                  this.remoteAddress = path.remoteAddress;
                  this.remoteConfirmed = true;
                  this.lastCurrentPortRecvMs = now;
               }

               this.pendingRebindPort = -1;
               LOGGER.info("[ReliableUdp] Remote port drifted {} -> {}, rebind to actual peer socket (symmetric NAT)", cur.getPort(), packet.getPort());
            } else {
               this.pendingRebindPort = packet.getPort();
               this.pendingRebindTime = now;
            }
         }
      }
   }

   private void receiveLoop() {
      this.recvPathLoop(this.primaryPath);
   }

   /** 单路径接收循环：主/次路径各跑一份（平滑切换双收），包处理统一进 processDatagram。 */
   private void recvPathLoop(UdpPath path) {
      byte[] buf = new byte[1454];
      DatagramPacket packet = new DatagramPacket(buf, buf.length);

      while (this.running && !path.socket.isClosed()) {
         try {
            path.socket.receive(packet);
            LogUploadManager.onTransportActivity();
            this.processDatagram(buf, packet.getLength(), packet, path);
         } catch (SocketTimeoutException var11) {
         } catch (IOException e) {
            if (path.socket.isClosed() || !this.running) {
               if (path == this.primaryPath) {
                  this.running = false;
               }
               break;
            }

            LOGGER.warn("[ReliableUdp] Receive error: {}", e.getMessage());
         } catch (Throwable t) {
            // 致命异常只允许主路径线程关闭整条连接；次路径线程退出即可（平滑切换候选失败不连累主路径）
            LOGGER.error("[ReliableUdp] receiveLoop died with exception: {}", t.getMessage(), t);
            if (path != this.primaryPath) {
               break;
            }

            this.running = false;
            this.connected.set(false);
            synchronized (this.recvLock) {
               this.recvLock.notifyAll();
            }

            synchronized (this.sendLock) {
               this.sendLock.notifyAll();
            }

            try {
               this.scheduler.execute(this::close);
            } catch (Exception var8) {
            }
            break;
         }
      }
   }

   private void processDatagram(byte[] rx, int rxLen, DatagramPacket packet, UdpPath path) {
      // TURN 路径先剥外层信封再解析
      byte[] buf = rx;
      int packetLen = rxLen;
      if (path.codec != null) {
         byte[] frame = path.codec.decode(rx, rxLen);
         if (frame == null) {
            return;
         }

         buf = frame;
         packetLen = frame.length;
      }

      if (packetLen >= 3 && buf[0] == MAGIC[0] && buf[1] == MAGIC[1]) {
         // punchAuthV1：认证模式下所有帧必须携带有效截断 MAC，失败一律丢弃；
         // 因此 maybeRebindRemote / 状态机只可能被认证源驱动
         Mac rxMac = this.authMac;
         if (rxMac != null) {
            boolean macOk = packetLen >= 9 && PunchAuth.verifyTrailer4(rxMac, buf, packetLen);
            if (!macOk) {
               this.handleAuthDrop("rudp-data");
               return;
            }
            this.consecutiveAuthDrops = 0;
         }

         byte type = buf[2];
         // 源地址重绑只看 packet.getAddress()/getPort()，不碰帧体，所以放在长度门之外：
         // 原来它跟着 frameMinLen 走，11~12B 的 DATA、11~13B 的 FEC_XOR 就不再更新对端地址，
         // 玩家中途被 CGNAT 重映射端口时会白等一轮超时。
         this.maybeRebindRemote(packet, path);
         if (type != 1 && type != 2) {
            // 帧长下界按类型取：DATA 含 payloadLen(需 13B)、FEC_XOR 还含 count(需 14B)。
            // 一律用 11B 会让短帧越界读，TURN 路径抛异常即被判死整条隧道（11 字节裸包=远程拆链）。
            int frameMinLen = type == 9 ? 14 : (type == 3 ? 13 : 11);
            if (packetLen >= frameMinLen) {
               int seq = readInt32(buf, 3);
               int ack = readInt32(buf, 7);
               switch (type) {
                  case 3:
                     this.handleData(seq, ack, buf, packetLen);
                     path.rxCount++;
                     path.lastRxMs = System.currentTimeMillis();
                     break;
                  case 4:
                     this.handleAck(ack);
                     this.lastRecvTime = System.currentTimeMillis();
                     path.rxCount++;
                     path.lastRxMs = System.currentTimeMillis();
                     // M4: 纯 ACK 帧视为对端活性证据, 与 case 8 同样复位连续失败计数
                     // 并在 writeState 降级时恢复 (不放 ACK 回包, 避免放大流量)
                     this.consecutiveFailures = 0;
                     if (this.writeState != 0) {
                        this.writeState = 0;
                        LOGGER.info("[ReliableUdp] writeState restored to WRITABLE (via ACK)");
                     }

                     break;
                  case 5:
                  case 6:
                  default:
                     break;
                  case 7:
                     this.handleDisconnect();
                     break;
                  case 8:
                     this.lastRecvTime = System.currentTimeMillis();
                     this.consecutiveFailures = 0;
                     path.rxCount++;
                     path.lastRxMs = System.currentTimeMillis();
                     if (this.writeState != 0) {
                        this.writeState = 0;
                        LOGGER.info("[ReliableUdp] writeState restored to WRITABLE");
                     }

                     this.sendKeepalive();
                     break;
                  case 9:
                     this.handleFecXor(readInt32(buf, 3), buf, packetLen);
                     break;
                  case 10:
                     this.lastRecvTime = System.currentTimeMillis();
                     LOGGER.info("[ReliableUdp] Received peer RESTART signal, trigger ICE Restart");
                     this.triggerIceRestart();
                     break;
                  case 11:
                     this.handleVoice(buf, packetLen);
                     break;
               }
            }
         } else {
            // 这里原来还有一次 maybeRebindRemote：上一步已经把它提到长度门之外、
            // 对每种帧型统一调一次，留着就是同一包重绑两次。
            this.lastRecvTime = System.currentTimeMillis();
            path.rxCount++;
            path.lastRxMs = System.currentTimeMillis();
            if (type == 1) {
               this.sendPunchAck(path, packet.getSocketAddress());
            }
         }
      }
   }

   private void handleData(int seq, int ack, byte[] buf, int packetLen) {
      this.lastRecvTime = System.currentTimeMillis();
      this.processAck(ack);
      byte[] payload = this.copyPayload(buf, packetLen);
      if (payload == null) {
         this.sendAck();
      } else {
         int expected = this.nextExpectedSeq.get();
         // punchAuthV1：seq 重置（对端重启识别）仅对认证流生效——
         // 非认证流上伪造的"落后 seq"可被用来重置接收状态（重放窗口攻击面）
         if (this.authMac != null && seqAfter(expected, seq) && seqDiff(expected, seq) > 128) {
            LOGGER.warn("[ReliableUdp] Peer restarted (seq {} far behind expected {}), reset receive state", seq, expected);
            this.nextExpectedSeq.set(seq);
            this.recvBuffer.clear();
            this.fecRecvGroup.clear();
            this.fecRecvXor.clear();
            this.fecRecvLengths.clear();
            this.minActiveGroupId = Integer.MAX_VALUE;
         }

         int groupId = seq / 4;
         this.fecRecvGroup.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>()).put(seq, payload);
         if (groupId < this.minActiveGroupId) {
            this.minActiveGroupId = groupId;
         }

         if (this.fecRecvGroup.size() > 20) {
            int cutoff = this.minActiveGroupId + 10;
            this.fecRecvGroup.keySet().removeIf(g -> g < cutoff);
            this.fecRecvXor.keySet().removeIf(g -> g < cutoff);
            this.fecRecvLengths.keySet().removeIf(g -> g < cutoff);
            if (!this.fecRecvGroup.isEmpty()) {
               this.minActiveGroupId = this.fecRecvGroup.keySet().stream().mapToInt(Integer::intValue).min().orElse(this.minActiveGroupId);
            }
         }

         synchronized (this.recvLock) {
            int expectedSeq = this.nextExpectedSeq.get();
            if (seq != expectedSeq) {
               if (!seqAfter(seq, expectedSeq) || seqDiff(seq, expectedSeq) >= 128) {
                  this.sendAck();
                  return;
               }

               this.recvBuffer.putIfAbsent(seq, payload);
            } else {
               if (this.bufferedChunks >= 512) {
                  return;
               }

               this.deliverPayload(payload);
               this.bufferedChunks++;
               this.nextExpectedSeq.getAndIncrement();

               while (this.recvBuffer.containsKey(this.nextExpectedSeq.get()) && this.bufferedChunks < 512) {
                  byte[] cached = this.recvBuffer.remove(this.nextExpectedSeq.get());
                  this.deliverPayload(cached);
                  this.bufferedChunks++;
                  this.nextExpectedSeq.getAndIncrement();
               }

               this.recvLock.notifyAll();
            }
         }

         this.sendAck();
         this.tryFecRecovery(groupId);
      }
   }

   private byte[] copyPayload(byte[] buf, int packetLen) {
      int payloadLen = readUint16(buf, 11);
      if (13 + payloadLen > packetLen) {
         return null;
      }

      byte[] payload = new byte[payloadLen];
      System.arraycopy(buf, 13, payload, 0, payloadLen);
      return payload;
   }

   private void handleFecXor(int groupId, byte[] buf, int packetLen) {
      this.lastRecvTime = System.currentTimeMillis();
      int xorPayloadLen = readUint16(buf, 11);
      int count = buf[13] & 255;
      int lengthsOffset = 14;
      int xorOffset = lengthsOffset + count * 2;
      if (xorOffset + xorPayloadLen <= packetLen) {
         int[] originalLengths = new int[count];

         for (int i = 0; i < count; i++) {
            originalLengths[i] = readUint16(buf, lengthsOffset + i * 2);
         }

         byte[] xorPayload = new byte[xorPayloadLen];
         System.arraycopy(buf, xorOffset, xorPayload, 0, xorPayloadLen);
         this.fecRecvXor.put(groupId, xorPayload);
         this.fecRecvLengths.put(groupId, originalLengths);
         this.tryFecRecovery(groupId);
      }
   }

   private void tryFecRecovery(int groupId) {
      Map<Integer, byte[]> groupData = this.fecRecvGroup.get(groupId);
      byte[] xorPayload = this.fecRecvXor.get(groupId);
      int[] originalLengths = this.fecRecvLengths.get(groupId);
      if (groupData != null && xorPayload != null && originalLengths != null) {
         int startSeq = groupId * 4;
         int missingSeq = -1;
         int missingIndex = -1;
         int receivedCount = 0;

         for (int i = 0; i < 4; i++) {
            int s = startSeq + i;
            if (groupData.containsKey(s)) {
               receivedCount++;
            } else if (seqAfter(this.nextExpectedSeq.get(), s)) {
               receivedCount++;
            } else {
               missingSeq = s;
               missingIndex = i;
            }
         }

         if (receivedCount == 3 && missingSeq >= 0) {
            byte[] recovered = (byte[])xorPayload.clone();

            for (Entry<Integer, byte[]> e : groupData.entrySet()) {
               byte[] p = e.getValue();
               int len = Math.min(recovered.length, p.length);

               for (int i = 0; i < len; i++) {
                  recovered[i] ^= p[i];
               }
            }

            int origLen = missingIndex < originalLengths.length ? originalLengths[missingIndex] : recovered.length;
            if (origLen < recovered.length) {
               byte[] trimmed = new byte[origLen];
               System.arraycopy(recovered, 0, trimmed, 0, origLen);
               recovered = trimmed;
            }

            synchronized (this.recvLock) {
               if (this.bufferedChunks < 512) {
                  this.recvBuffer.put(missingSeq, recovered);
               }

               while (this.bufferedChunks < 512 && this.recvBuffer.containsKey(this.nextExpectedSeq.get())) {
                  byte[] cached = this.recvBuffer.remove(this.nextExpectedSeq.get());
                  this.deliverPayload(cached);
                  this.bufferedChunks++;
                  this.nextExpectedSeq.getAndIncrement();
               }

               this.recvLock.notifyAll();
            }

            LOGGER.debug("[ReliableUdp] FEC recovered seq {}", missingSeq);
            this.fecRecvGroup.remove(groupId);
            this.fecRecvXor.remove(groupId);
            this.fecRecvLengths.remove(groupId);
         } else if (receivedCount == 4) {
            this.fecRecvGroup.remove(groupId);
            this.fecRecvXor.remove(groupId);
            this.fecRecvLengths.remove(groupId);
         }
      }
   }

   private static byte[] computeXorPayload(List<byte[]> payloads) {
      int maxLen = 0;

      for (byte[] p : payloads) {
         if (p.length > maxLen) {
            maxLen = p.length;
         }
      }

      byte[] xor = new byte[maxLen];

      for (byte[] p : payloads) {
         for (int i = 0; i < p.length; i++) {
            xor[i] ^= p[i];
         }
      }

      return xor;
   }

   private void handleAck(int ack) {
      this.processAck(ack);
   }

   private void processAck(int ack) {
      synchronized (this.sendLock) {
         if (ack == this.lastAckSeq) {
            this.dupAckCount++;
            if (this.dupAckCount >= 3 && !this.pendingAcks.isEmpty()) {
               ReliableUdpTransport.PendingPacket oldest = this.pendingAcks.get(this.oldestUnackedSeq);
               if (oldest != null) {
                  oldest.sendTime = System.currentTimeMillis();
                  oldest.retries++;
                  this.sendDataPacket(this.oldestUnackedSeq, oldest.data, false);
                  this.recordLoss(true);
               }

               this.dupAckCount = 0;
            }
         } else {
            this.lastAckSeq = ack;
            this.dupAckCount = 0;
         }

         while (!this.pendingAcks.isEmpty() && seqAfter(ack, this.oldestUnackedSeq)) {
            ReliableUdpTransport.PendingPacket pp = this.pendingAcks.get(this.oldestUnackedSeq);
            if (pp != null && pp.retries == 0) {
               this.updateRto(System.currentTimeMillis() - pp.sendTime);
            }

            this.pendingAcks.remove(this.oldestUnackedSeq);
            this.oldestUnackedSeq++;
            this.consecutiveFailures = 0;
            this.recordLoss(false);
         }

         // 救援完成: 累计 ack 越过救援段末尾=整段已被接收, 停止救援重传
         if (!this.deadPeerRescueMap.isEmpty() && this.deadPeerRescueLastSeq >= 0 && seqAfter(ack, this.deadPeerRescueLastSeq)) {
            this.deadPeerRescueMap.clear();
            LOGGER.info("[ReliableUdp] rescue queue fully acked, stop rescue retransmit");
         }

         if (this.writeState == 1) {
            this.writeState = 0;
            LOGGER.info("[ReliableUdp] writeState UNRELIABLE->WRITABLE (ack received)");
         }

         this.sendLock.notifyAll();
      }
   }

   private void updateRto(long sample) {
      if (sample > 0L) {
         if (this.srtt < 0L) {
            this.srtt = sample;
            this.rttvar = sample / 2L;
         } else {
            this.rttvar = (3L * this.rttvar + Math.abs(this.srtt - sample)) / 4L;
            this.srtt = (7L * this.srtt + sample) / 8L;
         }

         this.rto = Math.max(100L, Math.min(800L, this.srtt + Math.max(10L, 4L * this.rttvar)));
      }
   }

   private void recordLoss(boolean lost) {
      if (lost) {
         this.lossLostEvents++;
      }

      this.lossEvents++;
      if (this.lossEvents >= 200) {
         this.lossLostEvents = (this.lossLostEvents + 1) / 2;
         this.lossEvents = (this.lossEvents + 1) / 2;
      }

      double rate = (double)this.lossLostEvents / Math.max(this.lossEvents, 1);
      int w;
      if (rate < 0.03) {
         w = 64;
      } else if (rate < 0.08) {
         w = 48;
      } else if (rate < 0.15) {
         w = 32;
      } else {
         w = 16;
      }

      if (w != this.effectiveWindow) {
         this.effectiveWindow = w;
         LOGGER.debug("[ReliableUdp] congestion window {} (loss={}%)", w, (int)(rate * 100.0));
      }
   }

   public long getRtoMs() {
      return this.rto;
   }

   private void handleDisconnect() {
      LOGGER.warn("[ReliableUdp] Received DISCONNECT packet");
      this.running = false;
      this.connected.set(false);
      synchronized (this.recvLock) {
         this.recvLock.notifyAll();
      }

      synchronized (this.sendLock) {
         this.sendLock.notifyAll();
      }
   }

   private void sendAck() {
      try {
         byte[] data = new byte[11];
         System.arraycopy(MAGIC, 0, data, 0, 2);
         data[2] = 4;
         writeInt32(data, 3, 0);
         writeInt32(data, 7, this.nextExpectedSeq.get());
         this.enqueueSend(this.finalizeFrame(data));
      } catch (Exception e) {
         LOGGER.debug("[ReliableUdp] ACK build failed: {}", e.getMessage());
      }
   }

   private void enqueueSend(byte[] data) {
      if (this.running && !this.closed.get()) {
         this.outboundQueue.offer(data);
      }
   }

   private void flushOutbound() {
      if (this.running && !this.closed.get()) {
         byte[] data;
         while ((data = this.outboundQueue.poll()) != null) {
            try {
               this.sendOnPrimary(data);
            } catch (IOException e) {
               LOGGER.debug("[ReliableUdp] Outbound send failed: {}", e.getMessage());
               break;
            }
         }
      } else {
         this.outboundQueue.clear();
      }
   }

   private void sendDataPacket(int seq, byte[] payload, boolean interleave) {
      try {
         byte[] data = new byte[13 + payload.length];
         System.arraycopy(MAGIC, 0, data, 0, 2);
         data[2] = 3;
         writeInt32(data, 3, seq);
         writeInt32(data, 7, this.nextExpectedSeq.get());
         writeUint16(data, 11, payload.length);
         System.arraycopy(payload, 0, data, 13, payload.length);
         byte[] framed = this.finalizeFrame(data);
         this.enqueueSend(framed);
         if (interleave && payload.length < 512 && this.running && !this.closed.get()) {
            byte[] dup = (byte[])framed.clone();
            this.scheduler.schedule(() -> this.enqueueSend(dup), 50L, TimeUnit.MILLISECONDS);
         }
      } catch (Exception e) {
         LOGGER.debug("[ReliableUdp] Data build failed: {}", e.getMessage());
      }
   }

   private void sendFecPacket(int groupId, byte[] xorPayload, int[] originalLengths) {
      try {
         int count = originalLengths.length;
         int bodyOffset = 14 + count * 2;
         byte[] data = new byte[bodyOffset + xorPayload.length];
         System.arraycopy(MAGIC, 0, data, 0, 2);
         data[2] = 9;
         writeInt32(data, 3, groupId);
         writeInt32(data, 7, 0);
         writeUint16(data, 11, xorPayload.length);
         data[13] = (byte)count;

         for (int i = 0; i < count; i++) {
            writeUint16(data, 14 + i * 2, originalLengths[i]);
         }

         System.arraycopy(xorPayload, 0, data, bodyOffset, xorPayload.length);
         byte[] framed = this.finalizeFrame(data);
         this.sendOnPrimary(framed);
      } catch (IOException e) {
         LOGGER.debug("[ReliableUdp] FEC send failed: {}", e.getMessage());
      }
   }

   private void sendKeepalive() {
      if (this.running && this.connected.get()) {
         try {
            byte[] data = new byte[11];
            System.arraycopy(MAGIC, 0, data, 0, 2);
            data[2] = 8;
            writeInt32(data, 3, 0);
            writeInt32(data, 7, this.nextExpectedSeq.get());
            byte[] framed = this.finalizeFrame(data);
            this.sendOnPrimary(framed);
         } catch (IOException var2) {
         }
      }
   }

   /** 心跳拍: 连续5拍零入站判单向死 */
   private void heartbeatTick() {
      if (!this.running || !this.connected.get()) {
         return;
      }

      long now = System.currentTimeMillis();
      if (this.lastRecvTime > this.lastHeartbeatMs) {
         this.heartbeatFailStreak = 0;
         this.heartbeatEverRx = true;
      } else if (this.heartbeatEverRx && ++this.heartbeatFailStreak >= HEARTBEAT_FAIL_LIMIT) {
         LOGGER.warn("[ReliableUdp] One-way dead: {} heartbeats no rx, restart & re-punch", this.heartbeatFailStreak);
         LogUploadManager.onLinkDead();
         this.sendRestart();
         this.triggerIceRestart();
         this.close();
         return;
      }

      // TX死对称检测: 对端心跳活着但我DATA零ACK推进
      if (this.heartbeatEverRx && !this.pendingAcks.isEmpty()) {
         int oldest = this.oldestUnackedSeq;
         if (oldest == this.txNoAckLastOldest) {
            if (++this.txNoAckStreak >= TX_NO_ACK_FAIL_LIMIT) {
               LOGGER.warn("[ReliableUdp] TX dead: {} heartbeats no ack progress, peer not receiving our data", this.txNoAckStreak);
               LogUploadManager.onLinkDead();
               this.sendRestart();
               this.triggerIceRestart();
               this.close();
               return;
            }
         } else {
            this.txNoAckStreak = 0;
            this.txNoAckLastOldest = oldest;
         }
      } else {
         this.txNoAckStreak = 0;
         this.txNoAckLastOldest = -1;
      }

      this.lastHeartbeatMs = now;
      this.sendKeepalive();
   }

   private void retransmitCheck() {
      if (this.running) {
         long now = System.currentTimeMillis();
         long silenceMs = now - this.lastRecvTime;
         if (this.writeState == 0 && !this.pendingAcks.isEmpty()) {
            boolean tooManyFailures = this.consecutiveFailures >= 5;
            boolean tooLongNoResp = silenceMs > 8000L;
            if (tooManyFailures && tooLongNoResp) {
               this.writeState = 1;
               LOGGER.warn("[ReliableUdp] writeState WRITABLE->UNRELIABLE (failures={}, silence={}ms)", this.consecutiveFailures, silenceMs);
            }
         }

         if (!this.closed.get() && silenceMs > 60000L) {
            LOGGER.warn("[ReliableUdp] No data received for {}s, connection dead", 60);
            LogUploadManager.onLinkDead();
            this.sendRestart();
            this.triggerIceRestart();
            this.close();
         } else if (!this.closed.get() && !this.pendingAcks.isEmpty() && silenceMs > 24000L) {
            LOGGER.warn("[ReliableUdp] No packets received for {}ms, {} packets pending, peer probably dead", 24000L, this.pendingAcks.size());
            LogUploadManager.onLinkDead();
            this.sendRestart();
            this.triggerIceRestart();
            this.close();
         } else {
            synchronized (this.sendLock) {
               long maxRetransmits = Math.max(10L, Math.min(120L, 24000L / Math.max(this.rto, 100L)));

               // 死对端熔断锚点: 本段连续重传期间一旦收到过对端任何包(lastRecvTime 前移),
               // 计数自然复位; 只有完全零收包的死路径才会持续累积。
               if (this.lastRecvTime > this.deadPeerStreakStartMs) {
                  this.deadPeerStreakStartMs = this.lastRecvTime;
                  this.deadPeerRetransmitRounds = 0;
               }

               boolean didRetransmit = false;

               for (Entry<Integer, ReliableUdpTransport.PendingPacket> entry : this.pendingAcks.entrySet()) {
                  ReliableUdpTransport.PendingPacket pp = entry.getValue();
                  long backoff = this.rto + Math.min(pp.retries, 3) * 250L;
                  if (now - pp.sendTime > backoff) {
                     if (pp.retries >= maxRetransmits) {
                        LOGGER.warn(
                           "[ReliableUdp] seq {} retry {} times exceeded limit (unacked:{})",
                           new Object[]{entry.getKey(), maxRetransmits, this.pendingAcks.size()}
                        );
                        LogUploadManager.onLinkDead();
                        this.close();
                        return;
                     }

                     if (pp.retries == 0) {
                        LOGGER.debug("[ReliableUdp] Retransmit seq {} (pending={})", entry.getKey(), this.pendingAcks.size());
                     }

                     pp.sendTime = now;
                     pp.retries++;
                     this.consecutiveFailures++;
                     this.recordLoss(true);
                     this.sendDataPacket(entry.getKey(), pp.data, false);
                     didRetransmit = true;
                  }
               }

               // 熔断判定: 连续重传轮次达标且总静默超阈值(期间对端零收包), 清空积压重传队列,
               // 只打一次 WARN。收到对端包后 lastRecvTime 前移, 状态自然复位恢复正常传输。
               if (didRetransmit) {
                  this.deadPeerRetransmitRounds++;
                  long silenceNowMs = now - this.lastRecvTime;
                  if (silenceNowMs > DEAD_PEER_BREAKER_MIN_SILENCE_MS && this.deadPeerRetransmitRounds >= DEAD_PEER_BREAKER_ROUNDS) {
                     LOGGER.warn(
                        "[ReliableUdp] Peer unresponsive ({}ms no rx, {} retransmits), rescuing {} pending packets",
                        silenceNowMs, this.deadPeerRetransmitRounds, this.pendingAcks.size()
                     );
                     // 熔断不清数据: 积压转入救援队列慢速重传(见 deadPeerRescueMap 注释)
                     this.deadPeerRescueMap.clear();
                     this.deadPeerRescueMap.putAll(this.pendingAcks);
                     this.deadPeerRescueLastSeq = this.nextSendSeq - 1;
                     this.deadPeerRescueRounds = 0;
                     this.deadPeerRescueLastResendMs = 0L;
                     // 队列清空后必须推进基序号, 否则 sendBytes 的窗口判定(oldestUnackedSeq)
                     // 永远阻塞, 写侧 30s 后必然 transport stuck
                     this.pendingAcks.clear();
                     this.oldestUnackedSeq = this.nextSendSeq;
                     this.consecutiveFailures = 0;
                     this.dupAckCount = 0;
                     this.deadPeerRetransmitRounds = 0;
                     this.deadPeerStreakStartMs = 0L;
                  }
               }

               // 救援慢速重传: 对端恢复即无损续流(ack 覆盖救援段末尾即停); 真死则数轮后放弃,
               // 收敛行为与旧"直接丢弃"一致。首轮随熔断即发, 后续每 6s 一轮直至 ack 覆盖或 8 轮耗尽。
               // 必须放在 didRetransmit 块之外: 熔断清空 pendingAcks 后该块不再进入,
               // 放在里面会让轮次 2..8 成为死代码(行为测试 pcap 实证), 救援只剩一次机会
               if (!this.deadPeerRescueMap.isEmpty() && this.deadPeerRescueRounds < DEAD_PEER_RESCUE_MAX_ROUNDS
                  && now - this.deadPeerRescueLastResendMs >= DEAD_PEER_RESCUE_INTERVAL_MS) {
                  this.deadPeerRescueLastResendMs = now;
                  this.deadPeerRescueRounds++;
                  for (Entry<Integer, ReliableUdpTransport.PendingPacket> rescueEntry : this.deadPeerRescueMap.entrySet()) {
                     this.sendDataPacket(rescueEntry.getKey(), rescueEntry.getValue().data, false);
                  }
                  LOGGER.info("[ReliableUdp] rescue retransmit round {} ({} packets)", this.deadPeerRescueRounds, this.deadPeerRescueMap.size());
               }
            }
         }
      }
   }

   private void sendBytes(byte[] data, int offset, int length) throws IOException {
      this.sendBytes(data, offset, length, 0);
   }

   /** mux 模式下每个分块载荷前缀 chan 字节；非 mux 模式 chan 忽略（线上格式不变）。 */
   private void sendBytes(byte[] data, int offset, int length, int chan) throws IOException {
      if (this.running && this.connected.get()) {
         int pos = offset;

         while (pos < offset + length) {
            int chunkLen = Math.min(this.currentMaxSendChunk(), offset + length - pos);
            byte[] chunk;
            if (this.muxEnabled) {
               chunk = new byte[chunkLen + 1];
               chunk[0] = (byte)chan;
               System.arraycopy(data, pos, chunk, 1, chunkLen);
            } else {
               chunk = new byte[chunkLen];
               System.arraycopy(data, pos, chunk, 0, chunkLen);
            }
            pos += chunkLen;
            synchronized (this.sendLock) {
               long stuckStartMs = -1L;
               int lastUnacked = this.oldestUnackedSeq;

               while (this.running && this.connected.get() && seqDiff(this.nextSendSeq, this.oldestUnackedSeq) >= this.effectiveWindow) {
                  try {
                     if (stuckStartMs < 0L) {
                        stuckStartMs = System.currentTimeMillis();
                        lastUnacked = this.oldestUnackedSeq;
                     } else if (this.oldestUnackedSeq != lastUnacked) {
                        stuckStartMs = System.currentTimeMillis();
                        lastUnacked = this.oldestUnackedSeq;
                     }

                     this.sendLock.wait(1000L);
                     long stuckMs = System.currentTimeMillis() - stuckStartMs;
                     if (stuckMs >= 30000L) {
                        throw new IOException("transport stuck: " + stuckMs / 1000L + "s no progress");
                     }
                  } catch (InterruptedException e) {
                     Thread.currentThread().interrupt();
                     throw new IOException("Transport closed or interrupted");
                  }
               }

               if (!this.running || !this.connected.get()) {
                  throw new IOException("Transport closed");
               }

               int seq = this.nextSendSeq++;
               ReliableUdpTransport.PendingPacket pp = new ReliableUdpTransport.PendingPacket(chunk, System.currentTimeMillis(), 0);
               this.pendingAcks.put(seq, pp);
               this.sendDataPacket(seq, chunk, true);
               synchronized (this.fecSendLock) {
                  int groupId = seq / 4;
                  if (groupId != this.fecSendGroupSeq) {
                     this.fecSendGroup.clear();
                     this.fecSendGroupSeq = groupId;
                  }

                  this.fecSendGroup.add((byte[])chunk.clone());
                  if (this.fecSendGroup.size() == 4) {
                     byte[] xorPayload = computeXorPayload(this.fecSendGroup);
                     int[] lengths = new int[4];

                     for (int i = 0; i < 4; i++) {
                        lengths[i] = this.fecSendGroup.get(i).length;
                     }

                     this.sendFecPacket(groupId, xorPayload, lengths);
                     this.fecSendGroup.clear();
                  }
               }
            }
         }

         return;
      } else {
         throw new IOException("Transport closed");
      }
   }

   // ================= 平滑切换（双路径，make-before-break） =================

   /**
    * 出包统一出口：走主路径；路径切换后 10s 宽限内新旧路径双发，切换瞬间在途包零丢失。
    * 保留 throws IOException 声明以兼容既有调用点的 catch 结构。
    */
   private void sendOnPrimary(byte[] framed) throws IOException {
      UdpPath p = this.primaryPath;
      if (p == null) {
         return;
      }

      try {
         p.send(framed);
      } catch (IOException e) {
         LOGGER.debug("[ReliableUdp] send failed: {}", e.getMessage());
      }

      UdpPath s = this.secondaryPath;
      if (s != null && s != p && System.currentTimeMillis() < this.switchGraceUntilMs) {
         try {
            s.send(framed);
         } catch (IOException e) {
         }
      }
   }

   /**
    * 挂载第二条路径作为平滑切换候选，即刻启动其接收线程（双收）。
    * 次路径收包喂同一序号空间，重复包由 nextExpectedSeq 天然去重——因此对端无感知，
    * 双方无需协商切换时机（各自独立 promote，永不脑裂）。
    */
   public synchronized void addSecondaryPath(DatagramSocket sock, InetSocketAddress addr, UdpPath.Codec codec) {
      if (sock == null || addr == null || this.closed.get() || this.secondaryPath != null) {
         return;
      }

      try {
         sock.setSoTimeout(100);
      } catch (Exception e) {
      }

      UdpPath path = new UdpPath(sock, addr, codec);
      this.secondaryPath = path;
      Thread t = new Thread(() -> this.recvPathLoop(path), "VoxLink-UdpRecv-Alt");
      t.setDaemon(true);
      t.start();
      LOGGER.info("[ReliableUdp] secondary path added: {} (codec={})", addr, codec != null ? "turn" : "raw");
   }

   public synchronized UdpPath getSecondaryPath() {
      return this.secondaryPath;
   }

   public UdpPath getPrimaryPath() {
      return this.primaryPath;
   }

   /** 次路径累计有效收包数（切发前的健康观测指标）。 */
   public long getSecondaryRxCount() {
      UdpPath s = this.secondaryPath;
      return s == null ? 0L : s.rxCount;
   }

   /** 次路径最近一次有效收包时刻（0=从未）。 */
   public long getSecondaryLastRxMs() {
      UdpPath s = this.secondaryPath;
      return s == null ? 0L : s.lastRxMs;
   }

   /**
    * 提升 secondary 为 primary（切发送路径），旧路径降级为宽限备胎（10s 双发后可 drop）。
    * 只影响本端发送方向——对端继续双收直到它自己也完成切换。
    */
   public synchronized void promoteSecondaryPath() {
      UdpPath sec = this.secondaryPath;
      UdpPath pri = this.primaryPath;
      if (sec == null || sec == pri) {
         return;
      }

      this.primaryPath = sec;
      this.secondaryPath = pri;
      this.switchGraceUntilMs = System.currentTimeMillis() + 10000L;
      LOGGER.info("[ReliableUdp] path promoted: {} -> {} (grace dual-send 10s)", pri.remoteAddress, sec.remoteAddress);
   }

   /** 宽限期结束收尾：关闭并移除非主路径的 socket（其接收线程随 socket 关闭退出）。 */
   public synchronized void dropInactivePath() {
      UdpPath sec = this.secondaryPath;
      if (sec == null) {
         return;
      }

      this.secondaryPath = null;
      this.switchGraceUntilMs = 0L;

      try {
         sec.socket.close();
      } catch (Exception e) {
      }

      LOGGER.info("[ReliableUdp] inactive path dropped");
   }

   @Override
   public void close() {
      if (this.closed.compareAndSet(false, true)) {
         this.running = false;
         this.connected.set(false);

         try {
            byte[] data = new byte[11];
            System.arraycopy(MAGIC, 0, data, 0, 2);
            data[2] = 7;
            writeInt32(data, 3, 0);
            writeInt32(data, 7, 0);
            byte[] framed = this.finalizeFrame(data);
            this.sendOnPrimary(framed);
         } catch (IOException var7) {
         }

         if (this.retransmitTask != null) {
            this.retransmitTask.cancel(false);
         }

         if (this.keepaliveTask != null) {
            this.keepaliveTask.cancel(false);
         }

         this.scheduler.shutdownNow();
         synchronized (this.recvLock) {
            this.recvLock.notifyAll();
         }

         synchronized (this.sendLock) {
            this.sendLock.notifyAll();
         }

         if (this.recvThread != null) {
            this.recvThread.interrupt();
         }

         try {
            if (this.primaryPath != null && this.primaryPath.socket != null && !this.primaryPath.socket.isClosed()) {
               this.primaryPath.socket.close();
            }
         } catch (Exception var4) {
         }

         UdpPath sec = this.secondaryPath;
         if (sec != null) {
            try {
               sec.socket.close();
            } catch (Exception e) {
            }
            this.secondaryPath = null;
         }
      }
   }

   private static int readInt32(byte[] buf, int offset) {
      return (buf[offset] & 0xFF) << 24 | (buf[offset + 1] & 0xFF) << 16 | (buf[offset + 2] & 0xFF) << 8 | buf[offset + 3] & 0xFF;
   }

   private static boolean seqAfter(int a, int b) {
      return a > b && a - b < 1073741823 || a < b && b - a > 1073741823;
   }

   private static int seqDiff(int newer, int older) {
      long diff = (long)newer - older & 4294967295L;
      return (int)diff;
   }

   private static int readUint16(byte[] buf, int offset) {
      return (buf[offset] & 0xFF) << 8 | buf[offset + 1] & 0xFF;
   }

   private static void writeInt32(byte[] buf, int offset, int value) {
      buf[offset] = (byte)(value >> 24);
      buf[offset + 1] = (byte)(value >> 16);
      buf[offset + 2] = (byte)(value >> 8);
      buf[offset + 3] = (byte)value;
   }

   private static void writeUint16(byte[] buf, int offset, int value) {
      buf[offset] = (byte)(value >> 8);
      buf[offset + 1] = (byte)value;
   }

   private static class PendingPacket {
      final byte[] data;
      long sendTime;
      int retries;

      PendingPacket(byte[] data, long sendTime, int retries) {
         this.data = data;
         this.sendTime = sendTime;
         this.retries = retries;
      }
   }

   private class UdpInputStream extends InputStream {
      private final ConcurrentLinkedQueue<byte[]> chunks = new ConcurrentLinkedQueue<>();
      private byte[] currentChunk = null;
      private int currentPos = 0;
      private volatile boolean eof = false;

      void writeBuffer(byte[] data) {
         this.chunks.offer(data);
      }

      void markEof() {
         this.eof = true;
      }

      @Override
      public int read() throws IOException {
         byte[] b = new byte[1];
         int n = this.read(b, 0, 1);
         return n <= 0 ? -1 : b[0] & 0xFF;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
         if (len == 0) {
            return 0;
         }

         synchronized (ReliableUdpTransport.this.recvLock) {
            while (this.currentChunk == null || this.currentPos >= this.currentChunk.length) {
               this.currentChunk = this.chunks.poll();
               this.currentPos = 0;
               if (this.currentChunk != null) {
                  if (ReliableUdpTransport.this.bufferedChunks > 0) {
                     ReliableUdpTransport.this.bufferedChunks--;
                  }
               } else {
                  if ((!ReliableUdpTransport.this.running && this.chunks.isEmpty()) || this.eof) {
                     return -1;
                  }

                  try {
                     ReliableUdpTransport.this.recvLock.wait(500L);
                  } catch (InterruptedException e) {
                     Thread.currentThread().interrupt();
                     throw new IOException("Interrupted");
                  }
               }
            }

            int avail = this.currentChunk.length - this.currentPos;
            int toRead = Math.min(len, avail);
            System.arraycopy(this.currentChunk, this.currentPos, b, off, toRead);
            this.currentPos += toRead;
            if (this.currentPos >= this.currentChunk.length) {
               this.currentChunk = null;
               this.currentPos = 0;
            }

            return toRead;
         }
      }

      @Override
      public int available() {
         synchronized (ReliableUdpTransport.this.recvLock) {
            int total = 0;
            if (this.currentChunk != null) {
               total += this.currentChunk.length - this.currentPos;
            }

            for (byte[] chunk : this.chunks.toArray(new byte[0][])) {
               total += chunk.length;
            }

            return total;
         }
      }
   }

   private class UdpOutputStream extends OutputStream {
      @Override
      public void write(int b) throws IOException {
         this.write(new byte[]{(byte)b}, 0, 1);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
         try {
            ReliableUdpTransport.this.sendBytes(b, off, len);
         } catch (IOException e) {
            ReliableUdpTransport.LOGGER.debug("[ReliableUdp] Write failed: {}", e.getMessage());
            throw e;
         }
      }

      @Override
      public void flush() {
      }
   }
}
