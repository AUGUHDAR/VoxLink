package icu.wuhui.voxlink.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TopologyClient implements P2POverlayManager.PacketHandler {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-topology");
   private static final int SCHEDULE_DELAY_MS = 1000;
   private static final int SCHEDULE_PERIOD_MS = 500;
   private static final int UUID_PREFIX_LEN = 8;
   private static final ScheduledExecutorService TOPOLOGY_DELAY_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "VoxLink-Topology-Delay");
      t.setDaemon(true);
      return t;
   });
   private final P2POverlayManager overlayManager;
   private final SignalingClient signalingClient;
   private final AtomicInteger knownGeneration = new AtomicInteger(0);
   private final AtomicBoolean optimizing = new AtomicBoolean(false);
   private final AtomicBoolean reportedReady = new AtomicBoolean(false);
   private volatile String roomCode;
   private volatile String token;
   private volatile boolean isHost;
   private volatile String nodeId;
   private volatile Consumer<Boolean> onOptimizingChanged;
   private volatile Consumer<JsonObject> onDataReceived;
   private volatile String cachedUpstreamId;
   private volatile String cachedDownstreamId;

   public static void shutdown() {
      TOPOLOGY_DELAY_EXECUTOR.shutdown();

      try {
         if (!TOPOLOGY_DELAY_EXECUTOR.awaitTermination(2L, TimeUnit.SECONDS)) {
            TOPOLOGY_DELAY_EXECUTOR.shutdownNow();
         }
      } catch (InterruptedException e) {
         TOPOLOGY_DELAY_EXECUTOR.shutdownNow();
         Thread.currentThread().interrupt();
      }
   }

   public TopologyClient(SignalingClient signalingClient) {
      this.signalingClient = signalingClient;
      this.overlayManager = new P2POverlayManager(getOrCreateNodeId(), 0);
   }

   public CompletableFuture<Void> onRoomJoined(String roomCode, String token, boolean isHost, String clientId, int generation) {
      this.roomCode = roomCode;
      this.token = token;
      this.isHost = isHost;
      this.nodeId = isHost ? "host_" + roomCode : "client_" + clientId;
      this.knownGeneration.set(generation);
      this.reportedReady.set(false);

      try {
         this.overlayManager.start(this);
         this.overlayManager.setNodeId(this.nodeId);
         LOGGER.info("P2P overlay started, node {}", this.nodeId);
      } catch (IOException e) {
         LOGGER.warn("P2P overlay start failed, keep direct: {}", e.getMessage());
      }

      return CompletableFuture.completedFuture(null);
   }

   public JsonObject pollAndGetPeerLatency() {
      JsonObject peerLatency = new JsonObject();
      if (this.overlayManager.getRole() == P2POverlayManager.Role.NONE) {
         return peerLatency;
      }

      int ul = this.overlayManager.getUpstreamLatency();
      int dl = this.overlayManager.getDownstreamLatency();
      if (ul > 0 && this.cachedUpstreamId != null) {
         peerLatency.addProperty(this.cachedUpstreamId, ul);
      }

      if (dl > 0 && this.cachedDownstreamId != null) {
         peerLatency.addProperty(this.cachedDownstreamId, dl);
      }

      return peerLatency;
   }

   public void handleTopologyInstruction(JsonObject instruction) {
      if (instruction != null && !instruction.isJsonNull()) {
         String action = instruction.has("action") && !instruction.get("action").isJsonNull() ? instruction.get("action").getAsString() : "";
         int gen = instruction.has("generation") ? instruction.get("generation").getAsInt() : 0;
         if (gen > this.knownGeneration.get()) {
            this.knownGeneration.set(gen);
            this.reportedReady.set(false);
            LOGGER.info("Process topology instruction: action={}, gen={}", action, gen);
            switch (action) {
               case "become_head":
                  this.handleBecomeHead(instruction);
                  break;
               case "connect_to":
                  this.handleConnectTo(instruction);
                  break;
               case "direct_server":
                  this.handleDirectServer(instruction);
                  break;
               default:
                  LOGGER.warn("Unknown topology instruction: {}", action);
            }
         }
      }
   }

   public void handlePollInstructions(JsonObject response) {
      if (response != null) {
         if (response.has("optimizing") && response.get("optimizing").getAsBoolean()) {
            this.setOptimizing(true);
         }

         if (response.has("mode")
            && "direct_fallback".equals(response.get("mode").getAsString())
            && this.overlayManager.getRole() != P2POverlayManager.Role.NONE) {
            this.overlayManager.switchToDirectMode();
         }

         if (response.has("instructions") && response.get("instructions").isJsonArray()) {
            for (JsonElement elem : response.getAsJsonArray("instructions")) {
               if (elem.isJsonObject()) {
                  this.handleTopologyInstruction(elem.getAsJsonObject());
               }
            }
         }
      }
   }

   private void executeOnClientThread(Runnable action) {
      try {
         Minecraft.getInstance().execute(action);
      } catch (NoClassDefFoundError e) {
         action.run();
      }
   }

   public void handleTopologySignal(String type, JsonObject data) {
      switch (type) {
         case "topology_optimization_done":
            this.setOptimizing(false);
            LOGGER.info("Topology optimization completed");
            break;
         case "topology_change":
            if (this.roomCode != null && this.token != null) {
               this.signalingClient.pollTopology(this.roomCode, this.token, this.isHost, this.knownGeneration.get()).thenAccept(response -> {
                  if (response.success && response.data != null) {
                     this.executeOnClientThread(() -> this.handlePollInstructions(response.data));
                  }
               });
            }
      }
   }

   public void onRoomLeft() {
      this.overlayManager.stop();
      this.roomCode = null;
      this.token = null;
      this.nodeId = null;
      this.cachedUpstreamId = null;
      this.cachedDownstreamId = null;
      this.knownGeneration.set(0);
      this.setOptimizing(false);
      this.reportedReady.set(false);
   }

   public boolean isOptimizing() {
      return this.optimizing.get();
   }

   public void setOnOptimizingChanged(Consumer<Boolean> callback) {
      this.onOptimizingChanged = callback;
   }

   public void setOnDataReceived(Consumer<JsonObject> callback) {
      this.onDataReceived = callback;
   }

   public int getKnownGeneration() {
      return this.knownGeneration.get();
   }

   public String getNodeId() {
      return this.nodeId;
   }

   public P2POverlayManager getOverlayManager() {
      return this.overlayManager;
   }

   private void handleBecomeHead(JsonObject instruction) {
      String downstream = instruction.has("downstream") && !instruction.get("downstream").isJsonNull() ? instruction.get("downstream").getAsString() : null;
      String downstreamIp = instruction.has("downstream_ip") ? instruction.get("downstream_ip").getAsString() : null;
      int downstreamPort = instruction.has("downstream_port") ? instruction.get("downstream_port").getAsInt() : 0;
      this.cachedDownstreamId = downstream;
      this.overlayManager.becomeHead(downstream, downstreamIp, downstreamPort);
      this.setOptimizing(true);
      TOPOLOGY_DELAY_EXECUTOR.schedule(this::reportLinkReady, 1000L, TimeUnit.MILLISECONDS);
   }

   private void handleConnectTo(JsonObject instruction) {
      String upstream = instruction.has("upstream") ? instruction.get("upstream").getAsString() : "";
      String upstreamIp = instruction.has("upstream_ip") ? instruction.get("upstream_ip").getAsString() : "0.0.0.0";
      int upstreamPort = instruction.has("upstream_port") ? instruction.get("upstream_port").getAsInt() : 0;
      String downstream = instruction.has("downstream") && !instruction.get("downstream").isJsonNull() ? instruction.get("downstream").getAsString() : null;
      this.cachedUpstreamId = upstream;
      this.cachedDownstreamId = downstream;
      this.overlayManager.connectUpstream(upstream, upstreamIp, upstreamPort);
      if (downstream != null) {
         int downstreamPort = instruction.has("downstream_port") ? instruction.get("downstream_port").getAsInt() : 0;
         String downstreamIp = instruction.has("downstream_ip") ? instruction.get("downstream_ip").getAsString() : null;
         this.overlayManager.setDownstream(downstream, downstreamIp, downstreamPort);
      }

      this.setOptimizing(true);
      TOPOLOGY_DELAY_EXECUTOR.schedule(this::reportLinkReady, 500L, TimeUnit.MILLISECONDS);
   }

   private void handleDirectServer(JsonObject instruction) {
      String reason = instruction.has("reason") ? instruction.get("reason").getAsString() : "unknown";
      LOGGER.info("Switched to direct mode, reason: {}", reason);
      this.overlayManager.switchToDirectMode();
      this.setOptimizing(true);
      this.reportLinkReady();
   }

   private void reportLinkReady() {
      if (!this.reportedReady.getAndSet(true)) {
         if (this.roomCode != null && this.token != null) {
            this.signalingClient.reportLinkReady(this.roomCode, this.token, this.isHost).thenAccept(response -> {
               if (response.success) {
                  LOGGER.info("Link ready report success");
               } else {
                  LOGGER.warn("Link ready report failed: {}", response.error);
               }
            });
         }
      }
   }

   private void setOptimizing(boolean value) {
      boolean prev = this.optimizing.getAndSet(value);
      if (prev != value && this.onOptimizingChanged != null) {
         this.onOptimizingChanged.accept(value);
      }
   }

   @Override
   public void onDataReceived(String from, String priority, JsonObject payload) {
      LOGGER.debug("Received P2P data, from={}, priority={}", from, priority);
      if (this.onDataReceived != null) {
         this.onDataReceived.accept(payload);
      }
   }

   @Override
   public void onLinkReady() {
      LOGGER.info("P2P link ready");
      this.reportLinkReady();
   }

   @Override
   public void onLinkLost(String reason) {
      LOGGER.warn("P2P link broken: {}", reason);
      this.overlayManager.switchToDirectMode();
   }

   private static String getOrCreateNodeId() {
      return UUID.randomUUID().toString().substring(0, 8);
   }
}
