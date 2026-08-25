package icu.wuhui.voxlink.room;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import icu.wuhui.voxlink.VoxLinkConstants;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.command.LanHostRegistry;
import icu.wuhui.voxlink.compat.GeyserCompat;
import icu.wuhui.voxlink.compat.ViaCompat;
import icu.wuhui.voxlink.network.ConnectionFallback;
import icu.wuhui.voxlink.network.ConnectionHelper;
import icu.wuhui.voxlink.network.LogUploadManager;
import icu.wuhui.voxlink.network.P2PBridge;
import icu.wuhui.voxlink.network.PeerServer;
import icu.wuhui.voxlink.network.ReliableUdpTransport;
import icu.wuhui.voxlink.network.SignalingClient;
import icu.wuhui.voxlink.network.StunProbe;
import icu.wuhui.voxlink.network.TopologyClient;
import icu.wuhui.voxlink.network.UPnPManager;
import icu.wuhui.voxlink.terracotta.TerracottaManager;
import icu.wuhui.voxlink.ui.AttemptingJoinScreen;
import icu.wuhui.voxlink.ui.ChatCompat;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.UUID;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public class RoomManager {
   private final SignalingClient signalingClient;
   private final TopologyClient topologyClient;
   private final ScheduledExecutorService scheduler;
   private final ConnectionManager connectionManager;
   final AtomicReference<RoomManager.RoomState> currentRoom = new AtomicReference<>(null);
   private volatile ScheduledFuture<?> heartbeatFuture;
   private volatile ScheduledFuture<?> signalPollFuture;
   private final AtomicBoolean signalPollInFlight = new AtomicBoolean(false);
   private final AtomicInteger heartbeatFailCount = new AtomicInteger(0);
   private static final int MAX_HEARTBEAT_FAILS = 8;
   private static final long MIN_HEARTBEAT_INTERVAL = 5L;
   private static final int CREATE_ROOM_TIMEOUT_SECONDS = 120;
   private static final int JOIN_ROOM_TIMEOUT_SECONDS = 90;
   private static final int INITIAL_SIGNAL_POLL_MS = 200;
   private static final int BACKOFF_MULTIPLIER = 2;
   private static final int MAX_SIGNAL_POLL_MS = 10000;
   private static final int JOINER_SIGNAL_POLL_MS = 250;
   private static final int SIGNAL_POLL_JITTER_MS = 100;
   private static final int NAT_UPDATE_DELAY_SEC = 2;
   private static final int HOST_ALONE_HEARTBEATS = 3;
   private int hostAloneCount = 0;
   private volatile long currentHeartbeatInterval;
   private volatile long currentSignalPollInterval;
   private final AtomicLong signalPollTimestamp = new AtomicLong(0L);
   private final AtomicInteger heartbeatSeq = new AtomicInteger(0);
   private final AtomicInteger heartbeatGeneration = new AtomicInteger(0);
   final AtomicBoolean roomLostHandled = new AtomicBoolean(false);
   volatile boolean intentionalLeave = false;
   private volatile CompletableFuture<?> pendingCreateFuture;
   private final AtomicInteger pollCount = new AtomicInteger(0);
   static final RoomManager.RoomState PENDING = new RoomManager.RoomState(null);
   private static final Set<String> TRANSIENT_ERRORS = Set.of("NETWORK_ERROR", "CDN_ERROR", "RATE_LIMITED");
   private static final String GAME_VERSION = VoxLinkConstants.GAME_VERSION;
   private volatile String lastModerationStatus = "";
   private volatile String lastModeratedName = "";
   volatile Runnable roomLostCallback;
   private volatile String roomLostReason = "";
   private volatile String guestOpPolicyRoomCode = "";

   public RoomManager(SignalingClient signalingClient, TopologyClient topologyClient) {
      this.signalingClient = signalingClient;
      this.topologyClient = topologyClient;
      this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
         Thread t = new Thread(() -> {
            try {
               r.run();
            } catch (Throwable e) {
               VoxLinkMod.LOGGER.error("Scheduled task threw uncaught exception", e);
            }
         }, "VoxLink-RoomManager");
         t.setDaemon(true);
         return t;
      });
      this.connectionManager = new ConnectionManager(this, signalingClient, this.scheduler);
   }

   public void shutdown() {
      this.connectionManager.setConnectionCycleActive(false);
      ConnectionHelper.resetConnecting();
      ConnectionState.reset();
      this.connectionManager.shutdown();
      this.stopScheduledTasks();
      this.scheduler.shutdownNow();
   }

   private void cancelPendingCreate() {
      CompletableFuture<?> f = this.pendingCreateFuture;
      if (f != null && !f.isDone()) {
         f.cancel(true);
         this.pendingCreateFuture = null;
      }
   }

   public CompletableFuture<RoomInfo> createRoom(String name, String password, int maxPlayers, int hostPort, boolean visible, String authType, String category) {
      if (!this.currentRoom.compareAndSet(null, PENDING)) {
         return CompletableFuture.failedFuture(new IllegalStateException(Component.translatable("voxlink.error.already_in_room_or_pending").getString()));
      }

      try {
         TerracottaManager.shutdown();
      } catch (Exception e) {
         VoxLinkMod.LOGGER.warn("Failed to stop Terracotta before create room: {}", e.getMessage());
      }

      int protocolVersion = ViaCompat.isViaLoaded() ? ViaCompat.getServerProtocolVersion() : 0;
      int peerPort = PeerServer.getPort();
      VoxLinkMod.LOGGER.info("[createRoom] Instant create (NAT probe deferred)");
      CompletableFuture<RoomManager.NatResult> natFuture = CompletableFuture.completedFuture(new RoomManager.NatResult("unknown", hostPort, -1));
      CompletableFuture.runAsync(
         () -> {
            try {
               VoxLinkMod.LOGGER.info("[createRoom] Background NAT probe started");
               String natType = "unknown";
               int effectivePort = hostPort;
               int geyserPort = -1;
               if (VoxLinkMod.getConfig().isAutoUPnP()) {
                  UPnPManager.UPnPResult upnpResult = UPnPManager.openPort(hostPort, name);
                  UPnPManager.UPnPResult upnpUdpResult = UPnPManager.openUdpPort(hostPort, name + "-UDP");
                  if (upnpResult.success()) {
                     effectivePort = upnpResult.externalPort();
                     VoxLinkMod.LOGGER.info("[createRoom] UPnP TCP mapped, externalPort={}", effectivePort);
                  } else if (upnpUdpResult.success()) {
                     effectivePort = upnpUdpResult.externalPort();
                     VoxLinkMod.LOGGER.info("[createRoom] UPnP UDP mapped, externalPort={}", effectivePort);
                  }

                  if (GeyserCompat.isGeyserLoaded()) {
                     int bedrockPort = GeyserCompat.getBedrockPort();
                     UPnPManager.UPnPResult geyserUpnp = UPnPManager.openUdpPort(bedrockPort, name + "-Bedrock");
                     if (geyserUpnp.success()) {
                        geyserUpnp.externalPort();
                     }
                  }
               }

               try {
                  StunProbe.ProbeResult probeResult = StunProbe.probeAsync(StunDetector.getStunServerGroups()).join();
                  if (probeResult != null && probeResult.natType != null) {
                     natType = probeResult.natType.key;
                     this.connectionManager.setStunProbeResult(probeResult);
                     VoxLinkMod.LOGGER.info("[createRoom] STUN probe result saved: NAT={}, reachable={}", natType, probeResult.reachableStunUrls.size());
                  }
               } catch (Exception ex2) {
                  VoxLinkMod.LOGGER.warn("[createRoom] probeAsync failed: {}", ex2.getMessage());
               }

               VoxLinkMod.LOGGER.info("[createRoom] Background NAT probe done: natType={}, port={}", natType, effectivePort);
               RoomManager.RoomState state = this.currentRoom.get();
               if (state != null && state != PENDING && state.roomInfo != null) {
                  state.roomInfo.setNatType(natType);

                  try {
                     this.signalingClient
                        .updateRoom(
                           state.roomInfo.getCode(),
                           state.roomInfo.getToken(),
                           state.roomInfo.getName(),
                           null,
                           state.roomInfo.getMaxPlayers(),
                           state.roomInfo.isVisible(),
                           state.roomInfo.getAuthType(),
                           state.roomInfo.getCategory()
                        );
                     VoxLinkMod.LOGGER.info("[createRoom] NAT type updated: {}", natType);
                  } catch (Exception e) {
                     VoxLinkMod.LOGGER.warn("[createRoom] NAT type update failed: {}", e.getMessage());
                  }
               } else {
                  VoxLinkMod.LOGGER.info("[createRoom] Room not ready, retry NAT update in 2s");
                  String finalNatType = natType;
                  this.scheduler
                     .schedule(
                        () -> {
                           RoomManager.RoomState st = this.currentRoom.get();
                           if (st != null && st != PENDING && st.roomInfo != null) {
                              st.roomInfo.setNatType(finalNatType);
                              VoxLinkMod.LOGGER.info("[createRoom] Delayed NAT type update: {}", finalNatType);

                              try {
                                 this.signalingClient
                                    .updateRoom(
                                       st.roomInfo.getCode(),
                                       st.roomInfo.getToken(),
                                       st.roomInfo.getName(),
                                       null,
                                       st.roomInfo.getMaxPlayers(),
                                       st.roomInfo.isVisible(),
                                       st.roomInfo.getAuthType(),
                                       st.roomInfo.getCategory()
                                    );
                              } catch (Exception e) {
                                 VoxLinkMod.LOGGER.warn("[createRoom] Delayed NAT type update failed: {}", e.getMessage());
                              }
                           }
                        },
                        2L,
                        TimeUnit.SECONDS
                     );
               }
            } catch (Exception e) {
               VoxLinkMod.LOGGER.warn("[createRoom] Background NAT probe failed: {}", e.getMessage());
            }
         }
      );
      CompletableFuture<SignalingClient.ApiResponse> ipFuture = this.signalingClient.getPublicIp().exceptionally(e -> {
         VoxLinkMod.LOGGER.warn("[createRoom] Public IP fetch failed: {}, continue without IP", e.getMessage());
         return new SignalingClient.ApiResponse(false, null, null, null);
      });
      CompletableFuture<RoomInfo> future = natFuture.<SignalingClient.ApiResponse, RoomManager.CreateRoomResult>thenCombine(ipFuture, (ctx, ipResponse) -> {
            String hostIp = null;
            String hostIpv6 = null;
            if (ipResponse.success && ipResponse.data != null) {
               if (ipResponse.data.has("ip") && !ipResponse.data.get("ip").isJsonNull()) {
                  hostIp = ipResponse.data.get("ip").getAsString();
               }

               if (ipResponse.data.has("ipv6") && !ipResponse.data.get("ipv6").isJsonNull()) {
                  hostIpv6 = ipResponse.data.get("ipv6").getAsString();
               }
            }

            VoxLinkMod.LOGGER.info("[createRoom] NAT+IP ready: nat={}, ip={}, ipv6={}", new Object[]{ctx.nat, hostIp, hostIpv6});
            if ((hostIpv6 == null || hostIpv6.isEmpty()) && StunDetector.verifyIPv6Connectivity()) {
               hostIpv6 = ConnectionFallback.getLocalGlobalIpv6();
               if (hostIpv6 != null) {
                  VoxLinkMod.LOGGER.info("[createRoom] Server returned no IPv6, using local global IPv6: {} (reachability unverified)", hostIpv6);
               }
            }

            return new RoomManager.CreateRoomResult(ctx, null, hostIp, hostIpv6);
         })
         .thenCompose(
            result -> {
               String finalHostIp = result.hostIp;
               String finalHostIpv6 = result.hostIpv6;
               RoomManager.NatResult ctx = result.natResult;
               VoxLinkMod.LOGGER.info("[createRoom] Step 2: call API to create room");
               return this.signalingClient
                  .createRoom(
                     name,
                     password != null && !password.isEmpty() ? password : null,
                     maxPlayers,
                     ctx.port,
                     ctx.nat,
                     ctx.geyserPort,
                     visible,
                     authType,
                     category,
                     protocolVersion,
                     peerPort,
                     finalHostIpv6,
                     GAME_VERSION
                  )
                  .thenApply(response -> {
                     VoxLinkMod.LOGGER.info("[createRoom] Step 2 done: success={}", response.success);
                     return new RoomManager.CreateRoomResult(ctx, response, finalHostIp, finalHostIpv6);
                  });
            }
         )
         .thenApply(
            result -> {
               RoomManager.NatResult ctx = result.natResult;
               SignalingClient.ApiResponse response = result.apiResponse;
               if (!response.success) {
                  if (TRANSIENT_ERRORS.contains(response.error)) {
                     this.currentRoom.compareAndSet(PENDING, null);
                     throw new RoomManager.TransientException(response.error + ": " + response.message);
                  }

                  this.currentRoom.compareAndSet(PENDING, null);
                  String errMsg = response.error != null
                     ? response.error
                     : (response.message != null ? response.message : Component.translatable("voxlink.error.unknown").getString());
                  if (response.message != null && !response.message.equals(response.error)) {
                     errMsg = response.error + ": " + response.message;
                  }

                  if ("QUEUED".equals(response.error) && response.queuePosition > 0) {
                     errMsg = "QUEUED:" + response.queuePosition;
                  }

                  throw new RuntimeException(errMsg);
               } else {
                  if (response.data == null) {
                     this.currentRoom.compareAndSet(PENDING, null);
                     throw new RuntimeException(Component.translatable("voxlink.error.server_response_abnormal").getString());
                  }

                  String code = response.data.has("code") ? response.data.get("code").getAsString() : "";
                  String hostToken = response.data.has("hostToken") ? response.data.get("hostToken").getAsString() : "";
                  if (response.data.has("expiresIn") && !response.data.get("expiresIn").isJsonNull()) {
                     long expiresIn = response.data.get("expiresIn").getAsLong();
                     VoxLinkMod.LOGGER.info("Room created, expires in {}s", expiresIn);
                  }

                  RoomInfo roomInfo = new RoomInfo(code, name, password != null && !password.isEmpty(), maxPlayers, hostToken, true, ctx.port, ctx.nat);
                  roomInfo.setHostIp(result.hostIp);
                  roomInfo.setHostIpv6(result.hostIpv6);
                  roomInfo.setBedrockPort(ctx.geyserPort > 0 ? ctx.geyserPort : -1);
                  roomInfo.setCategory(category);
                  roomInfo.setVisible(visible);
                  RoomManager.RoomState state = new RoomManager.RoomState(roomInfo);
                  if (!this.currentRoom.compareAndSet(PENDING, state)) {
                     VoxLinkMod.LOGGER.warn("[createRoom] State cleared (timeout?), discard late result");
                     return null;
                  }

                  if (code != null && !code.isEmpty()) {
                     LogUploadManager.arm(code, true);
                  }

                  if (response.data.has("nameApproved") && !response.data.get("nameApproved").isJsonNull() && !response.data.get("nameApproved").getAsBoolean()
                     )
                   {
                     roomInfo.setNameApproved(false);
                  }

                  this.intentionalLeave = false;
                  this.roomLostHandled.set(false);
                  this.heartbeatFailCount.set(0);
                  this.startHeartbeat();
                  this.startSignalPoll();
                  String hostId = "host_" + code;
                  this.topologyClient.onRoomJoined(code, hostToken, true, hostId, 0);

                  try {
                     int bridgePort = P2PBridge.startHostBridge(ctx.port).get(5L, TimeUnit.SECONDS);
                     if (bridgePort > 0) {
                        VoxLinkMod.LOGGER.info("Host bridge started port={}, MC port: {}", bridgePort, ctx.port);
                     } else {
                        VoxLinkMod.LOGGER.warn("Host bridge start failed, client needs direct MC port {}", ctx.port);
                     }
                  } catch (Exception e) {
                     VoxLinkMod.LOGGER.warn("Host bridge start exception: {}", e.getMessage());
                  }

                  if (TerracottaManager.isBinaryReady() && VoxLinkMod.getConfig().isParallelP2P()) {
                     String tpName = Minecraft.getInstance().getUser().getName();
                     TerracottaManager.createRoom(tpName)
                        .thenAccept(
                           tc -> {
                              if (this.currentRoom.get() != state) {
                                 VoxLinkMod.LOGGER.info("Terracotta room left, closing background process");
                                 TerracottaManager.shutdown();
                              } else {
                                 roomInfo.setTerracottaCode(tc);
                                 VoxLinkMod.LOGGER.info("Terracotta code: {}", tc);
                                 this.signalingClient.updateTerracottaCode(roomInfo.getCode(), roomInfo.getToken(), tc).thenAccept(r -> {
                                    if (r.success) {
                                       VoxLinkMod.LOGGER.info("Terracotta code uploaded to server");
                                    } else {
                                       VoxLinkMod.LOGGER.warn("Terracotta code upload failed: {}", r.error);
                                    }
                                 }).exceptionally(e -> {
                                    VoxLinkMod.LOGGER.warn("Terracotta code upload exception: {}", e.getMessage());
                                    return null;
                                 });
                                 Minecraft.getInstance()
                                    .execute(
                                       () -> {
                                          Minecraft mc = Minecraft.getInstance();
                                          if (mc.player != null) {
                                             mc.player
                                                .displayClientMessage(

                                                   Component.translatable("voxlink.chat.terracotta_code_label", new Object[]{""})
                                                      .append(
                                                         Component.literal(
                                                               ChatFormatting.AQUA.toString()
                                                                  + ChatFormatting.BOLD.toString()
                                                                  + "["
                                                                  + Component.translatable("voxlink.chat.click_to_copy").getString()
                                                                  + "]"
                                                            )
                                                            .withStyle(ChatCompat.styleWithCopy(tc, Component.translatable("voxlink.chat.click_to_copy")))
                                                      )
                                                
, false);
                                          }
                                       }
                                    );
                              }
                           }
                        )
                        .exceptionally(e -> {
                           VoxLinkMod.LOGGER.warn("Terracotta create failed, using VoxLink code only: {}", e.getMessage());

                           try {
                              TerracottaManager.shutdown();
                           } catch (Exception ex) {
                              VoxLinkMod.LOGGER.debug("cleanup terracotta error: {}", ex.getMessage());
                           }

                           return null;
                        });
                  }

                  AtomicReference<Boolean> ipv4Result = new AtomicReference<>(null);
                  AtomicReference<Boolean> ipv6Result = new AtomicReference<>(null);
                  int checkCount = 0;
                  if (result.hostIp != null && !result.hostIp.isEmpty()) {
                     checkCount++;
                  }

                  if (result.hostIpv6 != null && !result.hostIpv6.isEmpty()) {
                     checkCount++;
                  }

                  int totalChecks = checkCount;
                  AtomicInteger completedChecks = new AtomicInteger(0);
                  if (result.hostIp != null && !result.hostIp.isEmpty()) {
                     roomInfo.setIpv4Status(RoomInfo.PortStatus.UNKNOWN);
                     String fIpv4 = result.hostIp;
                     this.signalingClient
                        .checkPortReachable(result.hostIp, ctx.port)
                        .thenAccept(
                           checkResp -> {
                              boolean reachable = checkResp.success
                                 && checkResp.data != null
                                 && checkResp.data.has("reachable")
                                 && checkResp.data.get("reachable").getAsBoolean();
                              roomInfo.setIpv4Status(reachable ? RoomInfo.PortStatus.REACHABLE : RoomInfo.PortStatus.UNREACHABLE);
                              VoxLinkMod.LOGGER.info("IPv4 port check: {}:{} = {}", new Object[]{fIpv4, ctx.port, reachable});
                              ipv4Result.set(reachable);
                              if (completedChecks.incrementAndGet() == totalChecks) {
                                 this.warnPortBlockedCombined(ipv4Result.get(), ipv6Result.get(), fIpv4, result.hostIpv6);
                              }
                           }
                        );
                  } else {
                     roomInfo.setIpv4Status(RoomInfo.PortStatus.NO_ADDRESS);
                  }

                  if (result.hostIpv6 != null && !result.hostIpv6.isEmpty()) {
                     roomInfo.setIpv6Status(RoomInfo.PortStatus.UNKNOWN);
                     String fIpv6 = result.hostIpv6;
                     this.signalingClient
                        .checkPortReachable(result.hostIpv6, ctx.port)
                        .thenAccept(
                           checkResp -> {
                              boolean reachable = checkResp.success
                                 && checkResp.data != null
                                 && checkResp.data.has("reachable")
                                 && checkResp.data.get("reachable").getAsBoolean();
                              roomInfo.setIpv6Status(reachable ? RoomInfo.PortStatus.REACHABLE : RoomInfo.PortStatus.UNREACHABLE);
                              VoxLinkMod.LOGGER.info("IPv6 port check: [{}]:{} = {}", new Object[]{fIpv6, ctx.port, reachable});
                              ipv6Result.set(reachable);
                              if (completedChecks.incrementAndGet() == totalChecks) {
                                 this.warnPortBlockedCombined(ipv4Result.get(), ipv6Result.get(), result.hostIp, fIpv6);
                              }
                           }
                        );
                  } else {
                     roomInfo.setIpv6Status(RoomInfo.PortStatus.NO_ADDRESS);
                  }

                  return roomInfo;
               }
            }
         )
         .orTimeout(120L, TimeUnit.SECONDS)
         .exceptionally(e -> {
            this.cleanupCreateRoomResources(hostPort);
            VoxLinkMod.LOGGER.error("[createRoom] failed: {}", e.getMessage());
            if (e instanceof RuntimeException) {
               throw (RuntimeException)e;
            } else {
               throw new RuntimeException(e);
            }
         });
      this.pendingCreateFuture = future;
      return future;
   }

   private void cleanupCreateRoomResources(int hostPort) {
      RoomManager.RoomState st = this.currentRoom.get();
      boolean wasPending = st == PENDING;
      if (wasPending) {
         this.currentRoom.compareAndSet(PENDING, null);
      } else if (st != null) {
         this.currentRoom.compareAndSet(st, null);
      }

      this.stopScheduledTasks();
      P2PBridge.disconnect();
      if (VoxLinkMod.getConfig().isAutoUPnP()) {
         UPnPManager.closePort(hostPort);
         if (GeyserCompat.isGeyserLoaded()) {
            UPnPManager.closeUdpPort(GeyserCompat.getBedrockPort());
         }
      }

      try {
         TerracottaManager.shutdown();
      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("cleanup terracotta error: {}", e.getMessage());
      }
   }

   public CompletableFuture<RoomInfo> updateRoom(
      String code, String token, String name, String password, int maxPlayers, boolean visible, String authType, String category
   ) {
      RoomManager.RoomState state = this.currentRoom.get();
      return state != null && state != PENDING && state.roomInfo != null
         ? this.signalingClient
            .updateRoom(code, token, name, password, maxPlayers, visible, authType, category)
            .thenApply(
               response -> {
                  if (!response.success) {
                     String errMsg = response.error != null
                        ? response.error
                        : (response.message != null ? response.message : Component.translatable("voxlink.error.unknown").getString());
                     if (response.message != null && !response.message.equals(response.error)) {
                        errMsg = response.error + ": " + response.message;
                     }

                     throw new RuntimeException(errMsg);
                  } else {
                     RoomInfo ri = state.roomInfo;
                     if (name != null && !name.isEmpty()) {
                        ri.setName(name);
                     }

                     if (password != null) {
                        ri.setPassword(password);
                     }

                     ri.setMaxPlayers(maxPlayers);
                     ri.setVisible(visible);
                     if (authType != null) {
                        ri.setAuthType(authType);
                     }

                     if (category != null) {
                        ri.setCategory(category);
                     }

                     if (response.data != null
                        && response.data.has("nameApproved")
                        && !response.data.get("nameApproved").isJsonNull()
                        && !response.data.get("nameApproved").getAsBoolean()) {
                        ri.setNameApproved(false);
                     } else {
                        ri.setNameApproved(true);
                     }

                     return ri;
                  }
               }
            )
            .exceptionally(e -> {
               if (e instanceof RuntimeException) {
                  throw (RuntimeException)e;
               } else {
                  throw new RuntimeException(e);
               }
            })
         : CompletableFuture.failedFuture(new IllegalStateException(Component.translatable("voxlink.error.not_in_room").getString()));
   }

   public CompletableFuture<RoomInfo> joinRoom(String code, String password) {
      if (!this.currentRoom.compareAndSet(null, PENDING)) {
         return CompletableFuture.failedFuture(new IllegalStateException(Component.translatable("voxlink.error.already_in_room_or_pending").getString()));
      }

      try {
         TerracottaManager.shutdown();
      } catch (Exception e) {
         VoxLinkMod.LOGGER.warn("Failed to stop Terracotta before join: {}", e.getMessage());
      }

      if (code != null && !code.isBlank()) {
         String normalizedCode = code.toUpperCase();
         if (!normalizedCode.matches("^[A-HJ-NP-Z2-9]{6}$")) {
            this.currentRoom.compareAndSet(PENDING, null);
            return CompletableFuture.failedFuture(new IllegalArgumentException(Component.translatable("voxlink.error.invalid_room_code").getString()));
         }

         this.connectionManager.setStunProbeResult(null);
         if (StunProbe.isNetworkChanged()) {
            VoxLinkMod.LOGGER.info("[joinRoom] Network changed, invalidate STUN cache");
            StunProbe.invalidateCache();
         }

         StunProbe.ProbeResult cachedProbe = StunProbe.getCachedResult();
         if (cachedProbe != null) {
            this.connectionManager.setStunProbeResult(cachedProbe);
            this.connectionManager.getStunProbeFutureRef().set(null);
            VoxLinkMod.LOGGER.info("[joinRoom] Using cached STUN probe: NAT={}, reachable={}", cachedProbe.natType.key, cachedProbe.reachableStunUrls.size());
         } else {
            CompletableFuture<StunProbe.ProbeResult> probeFuture = StunProbe.probeAsync(StunDetector.getStunServerGroups());
            this.connectionManager.getStunProbeFutureRef().set(probeFuture);
            probeFuture.thenAccept(result -> {
               this.connectionManager.setStunProbeResult(result);
               VoxLinkMod.LOGGER.info("[joinRoom] STUN probe done: NAT={}, reachable={}", result.natType.key, result.reachableStunUrls.size());
            }).exceptionally(e -> {
               VoxLinkMod.LOGGER.warn("[joinRoom] STUN probe failed: {}", e.getMessage());
               return null;
            });
         }

         return this.signalingClient
            .joinRoom(normalizedCode, password)
            .thenApply(
               response -> {
                  if (!response.success) {
                     if (TRANSIENT_ERRORS.contains(response.error)) {
                        this.currentRoom.compareAndSet(PENDING, null);
                        throw new RoomManager.TransientException(response.error + ": " + response.message);
                     }

                     this.currentRoom.compareAndSet(PENDING, null);
                     String errMsg = response.error != null
                        ? response.error
                        : (response.message != null ? response.message : Component.translatable("voxlink.error.unknown").getString());
                     if (response.message != null && !response.message.equals(response.error)) {
                        errMsg = response.error + ": " + response.message;
                     }

                     throw new RuntimeException(errMsg);
                  } else {
                     if (response.data == null) {
                        this.currentRoom.compareAndSet(PENDING, null);
                        throw new RuntimeException(Component.translatable("voxlink.error.server_response_abnormal").getString());
                     }

                     String clientToken = response.data.has("clientToken") ? response.data.get("clientToken").getAsString() : "";
                     String clientId = response.data.has("clientId") ? response.data.get("clientId").getAsString() : "";
                     JsonObject roomData = response.data.has("room") && response.data.get("room").isJsonObject()
                        ? response.data.getAsJsonObject("room")
                        : new JsonObject();
                     RoomInfo roomInfo = new RoomInfo(
                        roomData.has("code") ? roomData.get("code").getAsString() : "",
                        roomData.has("name") ? roomData.get("name").getAsString() : "VoxLink",
                        roomData.has("hasPassword") && roomData.get("hasPassword").getAsBoolean(),
                        roomData.has("maxPlayers") ? roomData.get("maxPlayers").getAsInt() : 20,
                        clientToken,
                        false,
                        roomData.has("hostPort") ? roomData.get("hostPort").getAsInt() : 25565,
                        roomData.has("natType") ? roomData.get("natType").getAsString() : "unknown"
                     );
                     roomInfo.setClientId(clientId);
                     if (roomData.has("bedrockPort") && !roomData.get("bedrockPort").isJsonNull()) {
                        roomInfo.setBedrockPort(roomData.get("bedrockPort").getAsInt());
                     }

                     if (roomData.has("protocolVersion") && !roomData.get("protocolVersion").isJsonNull()) {
                        roomInfo.setServerProtocolVersion(roomData.get("protocolVersion").getAsInt());
                     }

                     if (roomData.has("currentPlayers") && !roomData.get("currentPlayers").isJsonNull()) {
                        roomInfo.setCurrentPlayers(roomData.get("currentPlayers").getAsInt());
                     }

                     if (roomData.has("category") && !roomData.get("category").isJsonNull()) {
                        roomInfo.setCategory(roomData.get("category").getAsString());
                     }

                     if (roomData.has("loader") && !roomData.get("loader").isJsonNull()) {
                        roomInfo.setLoader(roomData.get("loader").getAsString());
                     }

                     if (roomData.has("authType") && !roomData.get("authType").isJsonNull()) {
                        roomInfo.setAuthType(roomData.get("authType").getAsString());
                     }

                     if (roomData.has("peerPort") && !roomData.get("peerPort").isJsonNull()) {
                        roomInfo.setPeerPort(roomData.get("peerPort").getAsInt());
                     }

                     if (roomData.has("terracottaCode") && !roomData.get("terracottaCode").isJsonNull()) {
                        String tc = roomData.get("terracottaCode").getAsString();
                        if (tc != null && !tc.isEmpty()) {
                           roomInfo.setTerracottaCode(tc);
                           VoxLinkMod.LOGGER.info("[joinRoom] Got Terracotta code: {}", tc);
                        }
                     }

                     int hostProto = roomData.has("hostProtocolVersion") && !roomData.get("hostProtocolVersion").isJsonNull()
                        ? roomData.get("hostProtocolVersion").getAsInt()
                        : 0;
                     Set<String> hostCaps = Collections.emptySet();
                     if (roomData.has("hostCapabilities") && roomData.get("hostCapabilities").isJsonArray()) {
                        hostCaps = new HashSet<>();

                        for (JsonElement c : roomData.getAsJsonArray("hostCapabilities")) {
                           if (!c.isJsonNull()) {
                              hostCaps.add(c.getAsString());
                           }
                        }
                     }

                     roomInfo.setHostCapabilities(hostProto, hostCaps);
                     if (hostProto > 0) {
                        VoxLinkMod.LOGGER.info("[joinRoom] Host capability: v{} caps={}", hostProto, hostCaps);
                     } else {
                        VoxLinkMod.LOGGER.info("[joinRoom] Host is legacy version, using direct connect mode");
                     }

                     RoomManager.RoomState state = new RoomManager.RoomState(roomInfo);
                     if (!this.currentRoom.compareAndSet(PENDING, state)) {
                        VoxLinkMod.LOGGER.warn("[joinRoom] State already cleared (timeout?), discarding late success");
                        return null;
                     } else {
                        this.intentionalLeave = false;
                        this.roomLostHandled.set(false);
                        this.heartbeatFailCount.set(0);
                        this.startHeartbeat();
                        this.startSignalPoll();
                        this.topologyClient.onRoomJoined(normalizedCode, clientToken, false, clientId, 0);
                        return roomInfo;
                     }
                  }
               }
            )
            .orTimeout(90L, TimeUnit.SECONDS)
            .exceptionally(e -> {
               if (this.currentRoom.compareAndSet(PENDING, null)) {
                  this.stopScheduledTasks();
               }

               try {
                  TerracottaManager.shutdown();
               } catch (Exception ex) {
                  VoxLinkMod.LOGGER.debug("cleanup terracotta error: {}", ex.getMessage());
               }

               if (e instanceof RuntimeException) {
                  throw (RuntimeException)e;
               } else {
                  throw new RuntimeException(e);
               }
            });
      } else {
         this.currentRoom.compareAndSet(PENDING, null);
         return CompletableFuture.failedFuture(new IllegalArgumentException(Component.translatable("voxlink.error.room_not_found").getString()));
      }
   }

   public void leaveRoom() {
      this.leaveRoom("用户主动离开");
   }

   public void leaveRoom(String detail) {
      if (this.connectionManager.isConnectionInHandoff() && "用户主动离开".equals(detail)) {
         return;
      }

      this.intentionalLeave = true;
      this.stopScheduledTasks();
      this.roomLostHandled.set(true);
      this.connectionManager.setConnectionCycleActive(false);
      this.connectionManager.setReversePunchAttempted(false);
      ConnectionHelper.resetConnecting();
      ConnectionHelper.clearConnectInitiated();
      ConnectionState.transitionTo(ConnectionState.DISCONNECTED, detail);
      this.connectionManager.setStunProbeResult(null);
      this.connectionManager.getStunProbeFutureRef().set(null);
      this.connectionManager.killDualRace();
      this.connectionManager.resetDualRaceState();
      this.connectionManager.resetContinuousRetryState();
      this.connectionManager.resetIceRestartState();
      RoomManager.RoomState state = this.currentRoom.getAndSet(null);
      if (state != null && state != PENDING) {
         this.cleanupRoomResources();

         try {
            this.performLeave(state);
         } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("Leave room error: {}", e.getMessage());
         }
      } else {
         this.cancelPendingCreate();

         try {
            TerracottaManager.shutdown();
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("cleanup terracotta error: {}", e.getMessage());
         }
      }
   }

   private void cleanupRoomResources() {
      try {
         this.connectionManager.clearActiveHolePunchers();
      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("cleanup punchers error: {}", e.getMessage());
      }

      try {
         this.connectionManager.clearActiveUdpTransports();
      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("cleanup udp transports error: {}", e.getMessage());
      }

      try {
         P2PBridge.disconnect();
      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("cleanup P2PBridge disconnect error: {}", e.getMessage());
      }

      try {
         RoomManager.RoomState state = this.currentRoom.get();
         if (state != null && state != PENDING && state.roomInfo.isHost()) {
            int bridgePort = P2PBridge.getHostPort();
            if (bridgePort > 0) {
               UPnPManager.closePort(bridgePort);
            }

            UPnPManager.closePort(state.roomInfo.getHostPort());
            if (state.roomInfo.getBedrockPort() > 0) {
               UPnPManager.closeUdpPort(state.roomInfo.getBedrockPort());
            }
         }
      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("cleanup UPnP error: {}", e.getMessage());
      }

      try {
         this.topologyClient.onRoomLeft();
      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("cleanup topology error: {}", e.getMessage());
      }

      try {
         TerracottaManager.shutdown();
      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("cleanup terracotta error: {}", e.getMessage());
      }
   }

   public void leaveRoomSync() {
      this.intentionalLeave = true;
      this.stopScheduledTasks();
      this.roomLostHandled.set(true);
      this.connectionManager.setConnectionCycleActive(false);
      this.connectionManager.setReversePunchAttempted(false);
      ConnectionHelper.resetConnecting();
      this.connectionManager.setStunProbeResult(null);
      this.connectionManager.getStunProbeFutureRef().set(null);
      this.connectionManager.killDualRace();
      this.connectionManager.resetDualRaceState();
      this.connectionManager.resetContinuousRetryState();
      this.connectionManager.resetIceRestartState();
      RoomManager.RoomState state = this.currentRoom.getAndSet(null);
      if (state != null && state != PENDING) {
         this.cleanupRoomResources();

         try {
            this.performLeave(state);
         } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("Sync leave error: {}", e.getMessage());
         }
      } else {
         this.cancelPendingCreate();

         try {
            TerracottaManager.shutdown();
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("cleanup terracotta error: {}", e.getMessage());
         }
      }
   }

   private void performLeave(RoomManager.RoomState state) {
      CompletableFuture<Void> leaveFuture;
      if (state.roomInfo.isHost()) {
         leaveFuture = this.signalingClient.leaveRoom(state.roomInfo.getCode(), state.roomInfo.getToken(), true).thenAccept(response -> {
            if (!response.success) {
               VoxLinkMod.LOGGER.warn("Server leave room failed: {}", response.error);
            }
         }).exceptionally(e -> {
            VoxLinkMod.LOGGER.warn("Server leave room failed: {}", e.getMessage());
            return null;
         });
      } else {
         leaveFuture = this.signalingClient
            .sendSignal(state.roomInfo.getCode(), state.roomInfo.getToken(), false, "disconnect", new JsonObject(), "host")
            .exceptionally(e -> {
               VoxLinkMod.LOGGER.warn("Send disconnect signal failed: {}", e.getMessage());
               return null;
            })
            .thenCompose(v -> this.signalingClient.leaveRoom(state.roomInfo.getCode(), state.roomInfo.getToken(), false).thenAccept(response -> {
               if (!response.success) {
                  VoxLinkMod.LOGGER.warn("Server leave room failed: {}", response.error);
               }
            }).exceptionally(e -> {
               VoxLinkMod.LOGGER.warn("Server leave room failed: {}", e.getMessage());
               return null;
            }));
      }

      leaveFuture.whenComplete((v, ex) -> {
         if (state.roomInfo.isHost()) {
            int bridgePort = P2PBridge.getHostPort();
            if (VoxLinkMod.getConfig().isAutoUPnP()) {
               if (bridgePort > 0) {
                  UPnPManager.closePort(bridgePort);
               }

               UPnPManager.closePort(state.roomInfo.getHostPort());
               if (state.roomInfo.getBedrockPort() > 0) {
                  UPnPManager.closeUdpPort(state.roomInfo.getBedrockPort());
               }
            }
         }

         P2PBridge.disconnect();
         this.topologyClient.onRoomLeft();
      });
   }

   public void closeRoom() {
      this.leaveRoom();
   }

   public void showRoomInfo(CommandSourceStack source) {
      RoomManager.RoomState state = this.currentRoom.get();
      if (state != null && state != PENDING) {
         RoomInfo info = state.roomInfo;
         source.sendSuccess(
            () -> Component.translatable(
               "voxlink.room_info_detail",
               new Object[]{
                  info.getName(),
                  info.getCode(),
                  info.getCurrentPlayers(),
                  info.getMaxPlayers(),
                  info.getNatType(),
                  info.isHost() ? Component.translatable("voxlink.yes").getString() : Component.translatable("voxlink.no").getString()
               }
            ),
            false
         );
      } else {
         source.sendSuccess(() -> Component.translatable("voxlink.error.not_in_room"), false);
      }
   }

   public RoomInfo getCurrentRoom() {
      RoomManager.RoomState state = this.currentRoom.get();
      return state != null && state != PENDING ? state.roomInfo : null;
   }

   public RoomInfo setupTerracottaGuestRoom(String roomCode) {
      RoomInfo roomInfo = new RoomInfo(roomCode, "Terracotta", false, 20, "", false, 0, "unknown");
      RoomManager.RoomState state = new RoomManager.RoomState(roomInfo);
      RoomManager.RoomState old = this.currentRoom.getAndSet(state);
      if (old != null && old != PENDING && old.roomInfo.isHost()) {
         try {
            this.cleanupRoomResources();
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("Terracotta takeover cleanup old resources failed: {}", e.getMessage());
         }
      }

      this.intentionalLeave = false;
      this.roomLostHandled.set(true);
      return roomInfo;
   }

   public boolean isInRoom() {
      RoomManager.RoomState state = this.currentRoom.get();
      return state != null && state != PENDING;
   }

   private synchronized void stopScheduledTasks() {
      if (this.heartbeatFuture != null) {
         this.heartbeatFuture.cancel(false);
         this.heartbeatFuture = null;
      }

      if (this.signalPollFuture != null) {
         this.signalPollFuture.cancel(false);
         this.signalPollFuture = null;
      }

      // 离开房间/关闭时清理 WS 推送监听器
      this.signalingClient.setSignalPushHandler(null);
      this.connectionManager.stopAllConnectionWork();
   }

   private synchronized void handleNameModerationUpdate(RoomManager.RoomState state, String status, String reason, String newName, boolean approved) {
      if (status != null && !status.isEmpty()) {
         if (!status.equals(this.lastModerationStatus) || newName == null || !newName.equals(this.lastModeratedName)) {
            if (!status.equals(this.lastModerationStatus) || "approved".equals(status)) {
               this.lastModerationStatus = status;
               if (newName != null) {
                  this.lastModeratedName = newName;
               }

               Minecraft mc = Minecraft.getInstance();
               if (mc != null && mc.player != null) {
                  state.roomInfo.setNameApproved(approved);
                  if (approved && newName != null && !newName.isEmpty() && !"name_pending_review".equals(newName)) {
                     state.roomInfo.setName(newName);
                  }

                  mc.execute(
                     () -> {
                        if (mc.player != null) {
                           switch (status) {
                              case "approved":
                                 mc.player.displayClientMessage(Component.translatable("voxlink.chat.name_approved"), false);
                                 if (newName != null && !newName.isEmpty()) {
                                    mc.player.displayClientMessage(Component.literal("  " + newName).withStyle(ChatFormatting.GRAY), false);
                                 }
                                 break;
                              case "rejected":
                                 String reasonText = reason != null && !reason.isEmpty()
                                    ? reason
                                    : Component.translatable("voxlink.chat.unknown_reason").getString();
                                 mc.player.displayClientMessage(Component.translatable("voxlink.chat.name_rejected_with_hint"), false);
                                 mc.player.displayClientMessage(Component.translatable("voxlink.chat.reason_label", new Object[]{reasonText}), false);
                                 break;
                              case "unavailable":
                                 mc.player.displayClientMessage(Component.translatable("voxlink.chat.name_unavailable"), false);
                                 mc.player.displayClientMessage(Component.translatable("voxlink.chat.please_retry"), false);
                           }
                        }
                     }
                  );
               }
            }
         }
      }
   }

   public void setRoomLostCallback(Runnable callback) {
      this.roomLostCallback = callback;
   }

   private void handleRoomLost() {
      this.handleRoomLost("HEARTBEAT_FAILED");
   }

   void handleRoomLost(String reason) {
      if (this.roomLostHandled.compareAndSet(false, true)) {
         this.connectionManager.setConnectionCycleActive(false);
         ConnectionHelper.resetConnecting();
         this.roomLostReason = reason;
         this.stopScheduledTasks();
         ConnectionState.reset();
         RoomManager.RoomState captured = this.currentRoom.get();

         try {
            this.scheduler
               .execute(
                  () -> {
                     RoomManager.RoomState st = this.currentRoom.get();
                     if (st != null && st != PENDING && st == captured) {
                        if (!st.roomInfo.isHost()) {
                           try {
                              this.signalingClient
                                 .sendSignal(st.roomInfo.getCode(), st.roomInfo.getToken(), st.roomInfo.isHost(), "disconnect", new JsonObject(), "host");
                           } catch (Exception e) {
                              VoxLinkMod.LOGGER.debug("Send disconnect signal failed on room lost: {}", e.getMessage());
                           }

                           try {
                              this.signalingClient.leaveRoom(st.roomInfo.getCode(), st.roomInfo.getToken(), false);
                           } catch (Exception e) {
                              VoxLinkMod.LOGGER.debug("Leave failed on room lost: {}", e.getMessage());
                           }
                        }

                        if (st.roomInfo.isHost()) {
                           try {
                              if (this.intentionalLeave) { this.signalingClient.leaveRoom(st.roomInfo.getCode(), st.roomInfo.getToken(), true); }
                           } catch (Exception e) {
                              VoxLinkMod.LOGGER.debug("Leave failed on room lost (host): {}", e.getMessage());
                           }

                           int bridgePort = P2PBridge.getHostPort();
                           if (VoxLinkMod.getConfig().isAutoUPnP()) {
                              if (bridgePort > 0) {
                                 UPnPManager.closePort(bridgePort);
                              }

                              UPnPManager.closePort(st.roomInfo.getHostPort());
                              if (st.roomInfo.getBedrockPort() > 0) {
                                 UPnPManager.closeUdpPort(st.roomInfo.getBedrockPort());
                              }
                           }
                        }

                        // host失联仅关入口/停信令, 不拆已建立数据面, 让已连玩家存活; 加入端照常断
                        if (!st.roomInfo.isHost()) {
                           P2PBridge.disconnect();
                        }

                        try {
                           TerracottaManager.shutdown();
                        } catch (Exception ex) {
                           VoxLinkMod.LOGGER.debug("handleRoomLost terracotta shutdown: {}", ex.getMessage());
                        }

                        this.topologyClient.onRoomLeft();
                        this.currentRoom.compareAndSet(captured, null);
                        this.heartbeatFailCount.set(0);
                        boolean wasIntentional = this.intentionalLeave;
                        if (this.roomLostCallback != null && !wasIntentional) {
                           this.roomLostCallback.run();
                        }

                        this.intentionalLeave = false;
                     }
                  }
               );
         } catch (RejectedExecutionException e) {
            VoxLinkMod.LOGGER.warn("Scheduler closed, sync execute room lost cleanup");
            this.currentRoom.compareAndSet(captured, null);
            if (captured != null && captured != PENDING) {
               if (!captured.roomInfo.isHost()) {
                  try {
                     this.signalingClient.sendSignal(captured.roomInfo.getCode(), captured.roomInfo.getToken(), false, "disconnect", new JsonObject(), "host");
                  } catch (Exception ex) {
                     VoxLinkMod.LOGGER.debug("Send disconnect signal failed on room lost (sync fallback): {}", ex.getMessage());
                  }

                  try {
                     this.signalingClient.leaveRoom(captured.roomInfo.getCode(), captured.roomInfo.getToken(), false);
                  } catch (Exception ex) {
                     VoxLinkMod.LOGGER.debug("Leave failed on room lost (sync fallback): {}", ex.getMessage());
                  }
               }

               if (captured.roomInfo.isHost()) {
                  try {
                     if (this.intentionalLeave) { this.signalingClient.leaveRoom(captured.roomInfo.getCode(), captured.roomInfo.getToken(), true); }
                  } catch (Exception ex) {
                     VoxLinkMod.LOGGER.debug("Leave failed on room lost (sync fallback): {}", ex.getMessage());
                  }

                  int bridgePort = P2PBridge.getHostPort();
                  if (VoxLinkMod.getConfig().isAutoUPnP()) {
                     if (bridgePort > 0) {
                        UPnPManager.closePort(bridgePort);
                     }

                     UPnPManager.closePort(captured.roomInfo.getHostPort());
                     if (captured.roomInfo.getBedrockPort() > 0) {
                        UPnPManager.closeUdpPort(captured.roomInfo.getBedrockPort());
                     }
                  }
               }
            }

            P2PBridge.disconnect();

            try {
               TerracottaManager.shutdown();
            } catch (Exception ex) {
               VoxLinkMod.LOGGER.debug("handleRoomLost sync terracotta shutdown: {}", ex.getMessage());
            }

            this.topologyClient.onRoomLeft();
            if (!this.intentionalLeave) {
               this.notifyRoomLostActionBar(reason);
               if (this.roomLostCallback != null) {
                  try {
                     this.roomLostCallback.run();
                  } catch (Exception ex) {
                     VoxLinkMod.LOGGER.debug("roomLostCallback exception (sync fallback): {}", ex.getMessage());
                  }
               }
            }
         }
      }
   }

   private void notifyRoomLostActionBar(String reason) {
      try {
         Minecraft mc = Minecraft.getInstance();
         if (mc == null || mc.player == null) {
            return;
         }

         if (mc.screen instanceof AttemptingJoinScreen ajs) {
            ajs.onRoomLost();
         }

         Component msg;
         if ("HOST_CLOSED".equals(reason) || "ROOM_CLOSED".equals(reason)) {
            msg = Component.translatable("voxlink.room_lost.host_closed");
         } else if ("HOST_DISCONNECTED".equals(reason)) {
            msg = Component.translatable("voxlink.room_lost.host_disconnected");
         } else if ("ROOM_NOT_FOUND".equals(reason)) {
            msg = Component.translatable("voxlink.room_lost.host_gone");
         } else if (!"TOKEN_INVALID".equals(reason) && !"INVALID_TOKEN".equals(reason)) {
            msg = Component.translatable("voxlink.room_lost.default");
         } else {
            msg = Component.translatable("voxlink.room_closed");
         }

         mc.player.displayClientMessage(Component.translatable("voxlink.chat.error_prefix").append(msg), false);
         mc.player.displayClientMessage(Component.translatable("voxlink.room_lost.hint"), false);
      } catch (NoClassDefFoundError | Exception e) {
         VoxLinkMod.LOGGER.debug("Show chat message failed: {}", e.getMessage());
      }
   }

   public String getRoomLostReason() {
      return this.roomLostReason;
   }

   private synchronized void startHeartbeat() {
      ScheduledFuture<?> oldHeartbeat = this.heartbeatFuture;
      this.heartbeatFuture = null;
      if (oldHeartbeat != null) {
         oldHeartbeat.cancel(false);
      }

      this.heartbeatGeneration.incrementAndGet();
      this.heartbeatFailCount.set(0);
      this.heartbeatSeq.set(0);
      long interval = Math.max(VoxLinkMod.getConfig().getHeartbeatInterval(), 5L);
      this.currentHeartbeatInterval = interval;
      this.heartbeatFuture = this.scheduler.scheduleAtFixedRate(this::heartbeatTask, interval, interval, TimeUnit.SECONDS);
   }

   private void rescheduleHeartbeat(long newInterval) {
      if (this.currentRoom.get() != null) {
         synchronized (this) {
            this.currentHeartbeatInterval = newInterval;
            ScheduledFuture<?> oldFuture = this.heartbeatFuture;
            this.heartbeatFuture = null;
            if (oldFuture != null) {
               oldFuture.cancel(false);
            }

            this.heartbeatFuture = this.scheduler.scheduleAtFixedRate(this::heartbeatTask, newInterval, newInterval, TimeUnit.SECONDS);
         }
      }
   }

   private void rescheduleSignalPoll(long newInterval) {
      if (this.currentRoom.get() != null) {
         synchronized (this) {
            this.currentSignalPollInterval = newInterval;
            ScheduledFuture<?> oldFuture = this.signalPollFuture;
            this.signalPollFuture = null;
            if (oldFuture != null) {
               oldFuture.cancel(false);
            }

            this.scheduleSignalPoll();
         }
      }
   }

   private void heartbeatTask() {
      try {
         RoomManager.RoomState state = this.currentRoom.get();
         if (state == null || state == PENDING) {
            return;
         }

         String natType = state.roomInfo.getNatType() != null ? state.roomInfo.getNatType() : "unknown";
         JsonObject peerLatency = this.topologyClient.pollAndGetPeerLatency();
         int seq = this.heartbeatSeq.incrementAndGet();
         RoomManager.RoomState capturedState = state;
         int mcPlayerCount = 0;
         if (state.roomInfo.isHost()) {
            MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null) {
               mcPlayerCount = server.getPlayerList().getPlayerCount();
            }
         }

         this.signalingClient
            .heartbeat(
               state.roomInfo.getCode(),
               state.roomInfo.getToken(),
               state.roomInfo.isHost(),
               natType,
               0.1,
               peerLatency,
               seq,
               this.topologyClient.getOverlayManager().getLocalPort(),
               mcPlayerCount
            )
            .thenAccept(
               response -> {
                  if (this.currentRoom.get() == capturedState) {
                     if (!response.success) {
                        if (!"RATE_LIMITED".equals(response.error) && !"CDN_ERROR".equals(response.error)) {
                           if ("ROOM_EVICTED".equals(response.error)) {
                              VoxLinkMod.LOGGER.warn("Kicked by server (heartbeat)");
                              this.notifyRoomEvicted();
                              return;
                           }

                           if (!"ROOM_NOT_FOUND".equals(response.error)
                              && !"ROOM_EXPIRED".equals(response.error)
                              && !"INVALID_TOKEN".equals(response.error)
                              && !"ROOM_CLOSED".equals(response.error)) {
                              int fails = this.heartbeatFailCount.incrementAndGet();
                              VoxLinkMod.LOGGER.warn("Heartbeat failed ({}/{}): {}", new Object[]{fails, 8, response.error});
                              if ("SERVER_403".equals(response.error) || "SERVER_404".equals(response.error)) {
                                 VoxLinkMod.LOGGER.warn("Server temp error ({}), not counted as heartbeat fail", response.error);
                                 this.heartbeatFailCount.decrementAndGet();
                              }

                              if (fails >= 8) {
                                 VoxLinkMod.LOGGER.error("Heartbeat failed too many times, room may be lost");
                                 this.handleRoomLost();
                              }

                              return;
                           }

                           VoxLinkMod.LOGGER.warn("Room no longer exists (heartbeat): {}", response.error);
                           this.handleRoomLost(
                              "ROOM_CLOSED".equals(response.error)
                                 ? "HOST_CLOSED"
                                 : ("INVALID_TOKEN".equals(response.error) ? "TOKEN_INVALID" : response.error)
                           );
                           return;
                        }

                        this.heartbeatFailCount.set(0);
                        long newInterval = Math.min(this.currentHeartbeatInterval * 2L, 30L);
                        if (response.retryAfter > 0) {
                           newInterval = Math.max(newInterval, response.retryAfter);
                        }

                        if (newInterval != this.currentHeartbeatInterval) {
                           VoxLinkMod.LOGGER.warn("Heartbeat {}/backoff to {}s", response.error, newInterval);
                           this.rescheduleHeartbeat(newInterval);
                        }

                        return;
                     } else {
                        this.heartbeatFailCount.set(0);
                        long baseInterval = Math.max(VoxLinkMod.getConfig().getHeartbeatInterval(), 5L);
                        if (this.currentHeartbeatInterval > baseInterval) {
                           VoxLinkMod.LOGGER.info("Heartbeat recovered, restore interval to {}s", baseInterval);
                           this.rescheduleHeartbeat(baseInterval);
                        }

                        if (response.data != null && response.data.has("topology") && !response.data.get("topology").isJsonNull()) {
                           JsonObject topoInstruction = response.data.getAsJsonObject("topology");

                           try {
                              Minecraft.getInstance().execute(() -> this.topologyClient.handleTopologyInstruction(topoInstruction));
                           } catch (NoClassDefFoundError var9) {
                           }
                        }

                        if (response.data != null && response.data.has("heartbeatInterval")) {
                           long serverInterval = response.data.get("heartbeatInterval").getAsLong();
                           long effectiveInterval = Math.max(serverInterval, 5L);
                           if (effectiveInterval != this.currentHeartbeatInterval) {
                              VoxLinkMod.LOGGER.info("Adjust heartbeat interval: server={}s, actual={}s", serverInterval, effectiveInterval);
                              this.rescheduleHeartbeat(effectiveInterval);
                           }
                        }

                        if (response.data != null && response.data.has("currentPlayers")) {
                           try {
                              int players = response.data.get("currentPlayers").getAsInt();
                              capturedState.roomInfo.setCurrentPlayers(players);
                              if (capturedState.roomInfo.isHost()
                                 && players <= 1
                                 && capturedState.roomInfo.getPeerCount() == 0
                                 && capturedState.roomInfo.hasEverHadPeer()) {
                                 if (++this.hostAloneCount >= 3) {
                                    this.hostAloneCount = 0;
                                    this.connectionManager.notifyAllPeersGone();
                                 }
                              } else {
                                 this.hostAloneCount = 0;
                              }
                           } catch (Exception var11) {
                           }
                        }

                        if (response.data != null && response.data.has("nameModerationStatus")) {
                           try {
                              String status = response.data.get("nameModerationStatus").getAsString();
                              String newName = response.data.has("name") ? response.data.get("name").getAsString() : null;
                              this.handleNameModerationUpdate(
                                 capturedState,
                                 status,
                                 response.data.has("nameModerationReason") ? response.data.get("nameModerationReason").getAsString() : null,
                                 newName,
                                 response.data.has("nameApproved") && response.data.get("nameApproved").getAsBoolean()
                              );
                           } catch (Exception var10) {
                           }
                        }
                     }
                  }
               }
            )
            .exceptionally(ex -> {
               int fails = this.heartbeatFailCount.incrementAndGet();
               VoxLinkMod.LOGGER.warn("Heartbeat exception ({}/{}): {}", new Object[]{fails, 8, ex.getMessage()});
               if (fails >= 8) {
                  this.handleRoomLost();
               }

               return null;
            });
      } catch (Exception e) {
         VoxLinkMod.LOGGER.error("Heartbeat task sync error", e);
      }
   }

   private void startSignalPoll() {
      this.signalPollTimestamp.set(System.currentTimeMillis() - 10000L);
      RoomManager.RoomState state = this.currentRoom.get();
      if (state != null && !state.roomInfo.isHost()) {
         // WS 健康时放宽到 1000ms，断开则保持 200ms 高频兜底
         this.currentSignalPollInterval = this.signalingClient.isWsConnected() ? 1000L : 200L;
      } else {
         this.currentSignalPollInterval = VoxLinkMod.getConfig().getSignalPollInterval();
      }

      // 注册 WS 推送消费：推送 data 与轮询响应 data 同构，直接复用 handleSignalPollResponse 路径
      this.registerSignalPushHandler();
      this.scheduleSignalPoll();
   }

   private void registerSignalPushHandler() {
      this.signalingClient.setSignalPushHandler(data -> {
         if (data == null) {
            return;
         }
         RoomManager.RoomState state = this.currentRoom.get();
         if (state == null || state == PENDING) {
            return;
         }
         // 切到 RoomManager 轮询用的 scheduler，与 doSignalPoll 处于同一线程安全域
         this.scheduler.execute(() -> {
            try {
               SignalingClient.ApiResponse pushResponse = new SignalingClient.ApiResponse(true, null, null, data);
               this.handleSignalPollResponse(pushResponse);
            } catch (Exception e) {
               VoxLinkMod.LOGGER.warn("Signal push handle error: {}", e.getMessage());
            }
         });
      });
   }

   private void scheduleSignalPoll() {
      this.scheduler.execute(this::doSignalPoll);
      long interval = this.currentSignalPollInterval;
      RoomManager.RoomState state = this.currentRoom.get();
      if (state != null && state != PENDING && !state.roomInfo.isHost() && interval <= 250L) {
         interval += ThreadLocalRandom.current().nextInt(100);
      }

      this.signalPollFuture = this.scheduler.scheduleAtFixedRate(this::doSignalPoll, interval, interval, TimeUnit.MILLISECONDS);
   }

   private void doSignalPoll() {
      try {
         RoomManager.RoomState state = this.currentRoom.get();
         if (state == null || state == PENDING) {
            return;
         }

         if (!this.signalPollInFlight.compareAndSet(false, true)) {
            return;
         }

         RoomManager.RoomState capturedState = state;
         int seq = this.pollCount.incrementAndGet();
         long startTime = System.currentTimeMillis();
         this.signalingClient
            .pollSignals(state.roomInfo.getCode(), state.roomInfo.getToken(), state.roomInfo.isHost(), this.signalPollTimestamp.get())
            .thenAccept(
               response -> {
                  if (this.currentRoom.get() != capturedState) {
                     this.signalPollInFlight.set(false);
                  } else {
                     long elapsed = System.currentTimeMillis() - startTime;
                     if (seq <= 5 || elapsed > 5000L || !response.success) {
                        VoxLinkMod.LOGGER
                           .info(
                              "[RoomManager] Signal poll #{}: {}ms, success={}, hasSignals={}",
                              new Object[]{
                                 seq,
                                 elapsed,
                                 response.success,
                                 response.success && response.data != null && (response.data.has("s") || response.data.has("signals"))
                              }
                           );
                     }

                     try {
                        this.handleSignalPollResponse(response);
                     } catch (Exception e) {
                        VoxLinkMod.LOGGER.warn("Signal poll response handle error: {}", e.getMessage());
                     }
                  }
               }
            )
            .exceptionally(ex -> {
               long elapsed = System.currentTimeMillis() - startTime;
               VoxLinkMod.LOGGER.warn("Signal poll #{} error ({}ms): {}", new Object[]{seq, elapsed, ex.getMessage()});
               return null;
            })
            .whenComplete((r, ex) -> this.signalPollInFlight.set(false));
      } catch (Exception e) {
         this.signalPollInFlight.set(false);
         VoxLinkMod.LOGGER.error("Signal poll sync error", e);
      }
   }

   private void handleSignalPollResponse(SignalingClient.ApiResponse response) {
      if (!response.success || response.data == null || !response.data.has("s") && !response.data.has("signals")) {
         if (response.success && response.data != null && response.data.has("ts")) {
            this.signalPollTimestamp.accumulateAndGet(response.data.get("ts").getAsLong(), Math::max);
            this.recoverSignalPollInterval();
         } else if (!response.success) {
            VoxLinkMod.LOGGER.warn("[RoomManager] Signal poll failed: {} - {}", response.error, response.message);
            if ("RATE_LIMITED".equals(response.error)
               || "CDN_ERROR".equals(response.error)
               || "SERVER_403".equals(response.error)
               || "SERVER_404".equals(response.error)) {
               this.backoffSignalPollInterval();
               return;
            }

            if ("ROOM_EVICTED".equals(response.error)) {
               VoxLinkMod.LOGGER.warn("Kicked by server (signal poll)");
               this.notifyRoomEvicted();
            } else if ("ROOM_NOT_FOUND".equals(response.error)
               || "ROOM_EXPIRED".equals(response.error)
               || "INVALID_TOKEN".equals(response.error)
               || "ROOM_CLOSED".equals(response.error)) {
               VoxLinkMod.LOGGER.warn("Room no longer exists on server: {}", response.error);
               this.handleRoomLost(
                  "ROOM_CLOSED".equals(response.error) ? "HOST_CLOSED" : ("INVALID_TOKEN".equals(response.error) ? "TOKEN_INVALID" : response.error)
               );
            }
         } else if (response.success) {
            this.recoverSignalPollInterval();
         }
      } else {
         String sigKey = response.data.has("s") ? "s" : "signals";
         String tsKey = response.data.has("ts") ? "ts" : "timestamp";
         if (!response.data.get(sigKey).isJsonArray()) {
            return;
         }

         JsonArray signals = response.data.getAsJsonArray(sigKey);
         VoxLinkMod.LOGGER.debug("[RoomManager] Signal poll: received {} signals", signals.size());

         for (JsonElement element : signals) {
            if (element.isJsonObject()) {
               JsonObject signal = element.getAsJsonObject();
               String sigType = signal.has("type") ? signal.get("type").getAsString() : "unknown";
               VoxLinkMod.LOGGER.info("[RoomManager] Signal dispatch: type={}, from={}", sigType, signal.has("from") ? signal.get("from").getAsString() : "?");
               this.handleSignal(signal);
               if (signal.has("timestamp") && !signal.get("timestamp").isJsonNull()) {
                  this.signalPollTimestamp.accumulateAndGet(signal.get("timestamp").getAsLong(), Math::max);
               }
            }
         }

         if (response.data.has(tsKey)) {
            this.signalPollTimestamp.accumulateAndGet(response.data.get(tsKey).getAsLong(), Math::max);
         }

         this.recoverSignalPollInterval();
      }
   }

   private void backoffSignalPollInterval() {
      long newInterval = Math.min(this.currentSignalPollInterval * 2L, 10000L);
      if (newInterval != this.currentSignalPollInterval) {
         VoxLinkMod.LOGGER.warn("Signal poll rate limited/CDN error, backoff to {}ms", newInterval);
         this.rescheduleSignalPoll(newInterval);
      }
   }

   private void recoverSignalPollInterval() {
      RoomManager.RoomState state = this.currentRoom.get();
      boolean isJoiner = state != null && state != PENDING && !state.roomInfo.isHost();
      // 加入方在 WS 健康时放宽到 1000ms，断开恢复 250ms；房主不变
      long normalInterval = isJoiner
         ? (this.signalingClient.isWsConnected() ? 1000L : 250L)
         : VoxLinkMod.getConfig().getSignalPollInterval();
      if (this.currentSignalPollInterval != normalInterval) {
         this.currentSignalPollInterval = normalInterval;
         this.rescheduleSignalPoll(normalInterval);
      }
   }

   private void handleSignal(JsonObject signal) {
      if (signal.has("type") && !signal.get("type").isJsonNull() && signal.has("from") && !signal.get("from").isJsonNull()) {
         String type = signal.get("type").getAsString();
         String from = signal.get("from").getAsString();
         JsonObject data = signal.has("data") && signal.get("data").isJsonObject() ? signal.getAsJsonObject("data") : new JsonObject();
         VoxLinkMod.LOGGER.debug("Received signal: type={}, from={}", type, from);
         switch (type) {
            case "join_request":
               this.connectionManager.handleJoinRequest(from, data);
               break;
            case "holepunch_offer":
               this.connectionManager.handleHolePunchOffer(from, data);
               break;
            case "holepunch_mapped":
               this.connectionManager.handleHolepunchMapped(from, data);
               break;
            case "holepunch_answer":
               this.connectionManager.handleHolePunchAnswer(from, data);
               break;
            case "connected":
               this.handleConnected(from, data);
               break;
            case "disconnect":
               this.handleDisconnect(from, data);
               break;
            case "host_closing":
               this.handleHostClosing(from, data);
               break;
            case "room_evicted":
               this.handleRoomEvicted(from, data);
               break;
            case "punch_info":
               this.connectionManager.handlePunchInfo(from, data);
               break;
            case "peer_port":
               this.connectionManager.handlePeerPort(from, data);
               break;
            case "reverse_holepunch_offer":
               this.connectionManager.handleReverseHolepunchOffer(from, data);
               break;
            case "reverse_punch_info":
               this.connectionManager.handleReversePunchInfo(from, data);
               break;
            case "tcp_simopen_request":
               this.connectionManager.handleTcpSimopenRequest(from, data);
               break;
            case "relay_request":
               this.connectionManager.handleRelayRequest(from, data);
               break;
            case "relay_accept":
               this.connectionManager.handleRelayAccept(from, data);
               break;
            case "relay_declined":
               this.connectionManager.handleRelayDeclined(from, data);
               break;
            case "relay_setup":
               this.connectionManager.handleRelaySetup(from, data);
               break;
            case "relay_notify":
               this.connectionManager.handleRelayNotify(from, data);
               break;
            case "relay_ready":
               this.connectionManager.handleRelayReady(from, data);
               break;
            case "cancel_connection":
               this.connectionManager.handleCancelConnection(from, data);
               break;
            case "ice_restart":
               this.connectionManager.handleIceRestart(from, data);
               break;
            case "room_name_approved":
               RoomManager.RoomState stxx = this.currentRoom.get();
               if (stxx != null && stxx != PENDING) {
                  String approvedName = data.has("name") ? data.get("name").getAsString() : null;
                  this.handleNameModerationUpdate(stxx, "approved", null, approvedName, true);
               }
               break;
            case "room_name_rejected":
               RoomManager.RoomState stx = this.currentRoom.get();
               if (stx != null && stx != PENDING) {
                  String rejectedName = data.has("name") ? data.get("name").getAsString() : null;
                  String rejectedReason = data.has("reason") ? data.get("reason").getAsString() : null;
                  this.handleNameModerationUpdate(stx, "rejected", rejectedReason, rejectedName, false);
               }
               break;
            case "room_name_unavailable":
               RoomManager.RoomState st = this.currentRoom.get();
               if (st != null && st != PENDING) {
                  String unavailableName = data.has("name") ? data.get("name").getAsString() : null;
                  this.handleNameModerationUpdate(st, "unavailable", null, unavailableName, false);
               }
               break;
            case "topology_optimization_done":
               this.topologyClient.handleTopologySignal(type, data);
               break;
            case "topology_change":
               this.topologyClient.handleTopologySignal(type, data);
               break;
            default:
               VoxLinkMod.LOGGER.debug("Unknown signal type: {}", type);
         }
      } else {
         VoxLinkMod.LOGGER.warn("Skip malformed signal: missing type or from");
      }
   }

   public ConnectionManager getConnectionManager() {
      return this.connectionManager;
   }

   public boolean isConnectionCycleActive() {
      return this.connectionManager.isConnectionCycleActive();
   }

   public boolean isConnectionActive() {
      return this.connectionManager.isConnectionActive();
   }

   private void handleConnected(String from, JsonObject data) {
      VoxLinkMod.LOGGER.info("Peer connected: {}", from);
      RoomManager.RoomState st = this.currentRoom.get();
      if (st != null && st != PENDING && st.roomInfo.isHost()) {
         if (from != null && this.connectionManager.hasUdpTransport(from)) {
            this.connectionManager.stopAllPunchingAfterHostBridge();
         } else {
            VoxLinkMod.LOGGER.info("[RoomManager] Peer connected but host transport not ready, keep punching");
         }
         // 访客/宿主OP基线每个房间只应用一次(以roomCode记), 避免每次有玩家连接都重跑、
         // 覆盖玩家手动 /op /deop
         String roomCode = st.roomInfo.getCode();
         if (roomCode != null && !roomCode.equals(this.guestOpPolicyRoomCode)) {
            this.guestOpPolicyRoomCode = roomCode;
            this.scheduler.schedule(() -> {
               try {
                  Minecraft mc = Minecraft.getInstance();
                  if (mc == null) {
                     return;
                  }
                  mc.execute(() -> this.applyGuestOpPolicy(st, mc));
               } catch (Exception e) {
                  VoxLinkMod.LOGGER.warn("[RoomManager] handleConnected exception: {}", e.getMessage());
               }
            }, 2L, TimeUnit.SECONDS);
         }
      }
   }

   public void applyOpPolicy(MinecraftServer server, boolean hostOp, boolean guestOp) {
      // P0 安全修复：host 判定优先用 LanHostRegistry 启动快照 UUID（离线模式下同名/同
      // 离线UUID 的攻击者不再被误判为房主）；快照为空时回退旧的 name 匹配以保底不回归。
      Set<UUID> bootstrapSnapshot = LanHostRegistry.snapshot();
      String hostName = Minecraft.getInstance().getUser().getName();
      server.execute(() -> {
         try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
               String name = player.getName().getString();
               boolean isHost;
               if (!bootstrapSnapshot.isEmpty()) {
                  isHost = bootstrapSnapshot.contains(player.getUUID());
               } else {
                  // 回退：快照尚未捕获（极早期）时维持旧行为，保证功能不回归
                  isHost = name.equals(hostName);
               }

               boolean want = isHost ? hostOp : guestOp;
               String cmd = want ? "op " + name : "deop " + name;
               server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
               VoxLinkMod.LOGGER.info("[RoomManager] {}{}: {}", new Object[]{isHost ? "Host" : "Visitor", want ? "OP" : "DEOP", name});
            }
         } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("[RoomManager] OP policy apply failed: {}", e.getMessage());
         }
      });
   }

   private void applyGuestOpPolicy(RoomManager.RoomState st, Minecraft mc) {
      try {
         IntegratedServer server = mc.getSingleplayerServer();
         if (server == null) {
            return;
         }

         this.applyOpPolicy(server, st.roomInfo.isHostOp(), st.roomInfo.isGuestOp());
      } catch (Exception e) {
         VoxLinkMod.LOGGER.warn("[RoomManager] guest OP handle failed: {}", e.getMessage());
      }
   }

   private void handleDisconnect(String from, JsonObject data) {
      VoxLinkMod.LOGGER.info("Peer disconnected: {}", from);
      RoomManager.RoomState state = this.currentRoom.get();
      if (state != null && state != PENDING && state.roomInfo.isHost() && from != null) {
         this.connectionManager.clearHostPunchingState();
         ReliableUdpTransport transport = this.connectionManager.removeUdpTransport(from);
         if (transport != null) {
            try {
               transport.close();
            } catch (Exception var7) {
            }
         }
      }

      if (state != null && state != PENDING && !state.roomInfo.isHost() && from != null && ("host".equals(from) || from.startsWith("host_"))) {
         try {
            TerracottaManager.shutdown();
         } catch (Exception ex) {
            VoxLinkMod.LOGGER.debug("handleDisconnect terracotta shutdown: {}", ex.getMessage());
         }

         this.handleRoomLost("HOST_DISCONNECTED");
      }
   }

   private void handleHostClosing(String from, JsonObject data) {
      VoxLinkMod.LOGGER.info("Host is closing room");
      this.handleRoomLost("HOST_CLOSED");
   }

   private void handleRoomEvicted(String from, JsonObject data) {
      VoxLinkMod.LOGGER.warn("Kicked by server");
      this.notifyRoomEvicted();
   }

   private void notifyRoomEvicted() {
      Minecraft mc = Minecraft.getInstance();
      if (mc != null) {
         mc.execute(() -> {
            if (mc.player != null) {
               mc.player.displayClientMessage(Component.translatable("voxlink.chat.evicted_notice"), false);
            }
         });
      }
   }

   private void warnPortBlockedCombined(Boolean ipv4Ok, Boolean ipv6Ok, String ipv4, String ipv6) {
      boolean v4Blocked = ipv4Ok != null && !ipv4Ok;
      boolean v6Blocked = ipv6Ok != null && !ipv6Ok;
      if (v4Blocked || v6Blocked) {
         Minecraft mc = Minecraft.getInstance();
         if (mc != null) {
            mc.execute(() -> {
               if (mc.player != null) {
                  MutableComponent prefix = Component.translatable("voxlink.chat.error_prefix").withStyle(ChatFormatting.RED);
                  MutableComponent msg;
                  if (v4Blocked && v6Blocked) {
                     msg = Component.translatable("voxlink.chat.both_unreachable");
                  } else if (v4Blocked) {
                     msg = Component.translatable("voxlink.chat.ipv4_unreachable");
                  } else {
                     msg = Component.translatable("voxlink.chat.ipv6_unreachable");
                  }

                  mc.player.displayClientMessage(prefix.append(msg), false);
               }
            });
         }
      }
   }

   private record CreateRoomResult(RoomManager.NatResult natResult, SignalingClient.ApiResponse apiResponse, String hostIp, String hostIpv6) {
   }

   private record NatResult(String nat, int port, int geyserPort) {
   }

   static class RoomState {
      final RoomInfo roomInfo;

      RoomState(RoomInfo roomInfo) {
         this.roomInfo = roomInfo;
      }
   }

   private static class TransientException extends RuntimeException {
      TransientException(String message) {
         super(message);
      }
   }
}
