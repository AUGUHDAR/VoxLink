package icu.wuhui.voxlink.room;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.chat.Component;

public class RoomInfo {
   private final String code;
   private volatile String name;
   private volatile boolean hasPassword;
   private volatile int maxPlayers;
   private final String token;
   private final boolean isHost;
   private final int hostPort;
   private volatile String natType;
   private volatile String hostIp;
   private volatile String hostIpv6;
   private volatile InetSocketAddress hostMappedAddress = null;
   private volatile boolean hostSymmetric;
   private volatile boolean hostEasySym;
   private volatile int hostMappedPortDelta = 0;
   private volatile int hostMappedPortRange = 100;
   private volatile List<Integer> hostBirthdayPorts = null;
   private volatile long punchSyncTimeMs = 0L;
   private volatile long punchSyncSentAtMs = 0L;
   private volatile long joinerOfferRecvMs = 0L;
   private final AtomicInteger currentPlayers;
   private volatile String clientId;
   private volatile int bedrockPort;
   private volatile int serverProtocolVersion;
   private volatile String category;
   private volatile String authType;
   private volatile int peerPort;
   private volatile String clientType = "mod";
   private volatile String loader = "unknown";
   private volatile Component connectionMode = Component.empty();
   private volatile boolean usingRelay = false;
   private volatile boolean connectionFailed = false;
   private volatile int localBridgePort = 0;
   private volatile int hostConnectPort = 0;
   private volatile RoomInfo.PortStatus ipv4Status = RoomInfo.PortStatus.UNKNOWN;
   private volatile RoomInfo.PortStatus ipv6Status = RoomInfo.PortStatus.UNKNOWN;
   private volatile boolean nameApproved = true;
   private volatile boolean visible = true;
   private volatile boolean sameCgnat = false;
   private volatile boolean guestOp = false;
   private volatile String gameType = "survival";
   private volatile boolean hostOp = false;
   private volatile String hostLocalIp = null;
   private volatile String joinerLocalIp = null;
   private volatile String myMappedIp = null;
   private volatile int myMappedPort = 0;
   private volatile String terracottaCode = null;
   private final ConcurrentHashMap<String, RoomInfo.PeerInfo> peerMap = new ConcurrentHashMap<>();
   private volatile boolean connectionAttemptFailed = false;
   private volatile boolean everHadPeer = false;
   private volatile int hostProtocolVersion = 0;
   private volatile Set<String> hostCapabilities = Collections.emptySet();

   public void addOrUpdatePeer(String clientId, String natType, String mappedIp, int mappedPort) {
      RoomInfo.PeerInfo info = this.peerMap.computeIfAbsent(clientId, id -> new RoomInfo.PeerInfo(id));
      if (natType != null) {
         info.natType = natType;
      }

      if (mappedIp != null) {
         info.mappedIp = mappedIp;
      }

      if (mappedPort > 0) {
         info.mappedPort = mappedPort;
      }

      info.lastSeenMs = System.currentTimeMillis();
      this.everHadPeer = true;
   }

   public void addOrUpdatePeer(String clientId, String natType, String mappedIp, int mappedPort, int protocolVersion, Set<String> capabilities) {
      RoomInfo.PeerInfo info = this.peerMap.computeIfAbsent(clientId, id -> new RoomInfo.PeerInfo(id));
      if (natType != null) {
         info.natType = natType;
      }

      if (mappedIp != null) {
         info.mappedIp = mappedIp;
      }

      if (mappedPort > 0) {
         info.mappedPort = mappedPort;
      }

      if (protocolVersion > 0) {
         info.protocolVersion = protocolVersion;
      }

      if (capabilities != null && !capabilities.isEmpty()) {
         info.capabilities = capabilities;
      }

      info.lastSeenMs = System.currentTimeMillis();
      this.everHadPeer = true;
   }

   public RoomInfo.PeerInfo getPeer(String clientId) {
      return this.peerMap.get(clientId);
   }

   public void removePeer(String clientId) {
      this.peerMap.remove(clientId);
   }

   public void clearPeers() {
      this.peerMap.clear();
   }

   public Collection<RoomInfo.PeerInfo> getPeers() {
      return this.peerMap.values();
   }

   public int getPeerCount() {
      return this.peerMap.size();
   }

   public boolean hasEverHadPeer() {
      return this.everHadPeer;
   }

   public boolean hasRecentPeer(long maxAgeMs) {
      long now = System.currentTimeMillis();

      for (RoomInfo.PeerInfo p : this.peerMap.values()) {
         if (now - p.lastSeenMs < maxAgeMs) {
            return true;
         }
      }

      return false;
   }

   public boolean isConnectionAttemptFailed() {
      return this.connectionAttemptFailed;
   }

   public void setConnectionAttemptFailed(boolean v) {
      this.connectionAttemptFailed = v;
   }

   public int getHostProtocolVersion() {
      return this.hostProtocolVersion;
   }

   public Set<String> getHostCapabilities() {
      return this.hostCapabilities;
   }

   public void setHostCapabilities(int protocolVersion, Set<String> capabilities) {
      this.hostProtocolVersion = protocolVersion;
      this.hostCapabilities = capabilities != null ? capabilities : Collections.emptySet();
   }

   public boolean isHostLegacy() {
      return this.hostProtocolVersion == 0 || this.hostCapabilities.isEmpty();
   }

   public boolean hostSupportsRelay() {
      return this.hostCapabilities.contains("relay");
   }

   public RoomInfo(String code, String name, boolean hasPassword, int maxPlayers, String token, boolean isHost, int hostPort, String natType) {
      this.code = code;
      this.name = name;
      this.hasPassword = hasPassword;
      this.maxPlayers = maxPlayers;
      this.token = token;
      this.isHost = isHost;
      this.hostPort = hostPort;
      this.natType = natType;
      this.currentPlayers = new AtomicInteger(isHost ? 1 : 0);
      this.bedrockPort = -1;
      this.serverProtocolVersion = 0;
      this.category = "other";
   }

   public String getCode() {
      return this.code;
   }

   public String getName() {
      return this.name;
   }

   public void setName(String name) {
      this.name = name;
   }

   public boolean hasPassword() {
      return this.hasPassword;
   }

   public void setPassword(String password) {
      this.hasPassword = password != null && !password.isEmpty();
   }

   public int getMaxPlayers() {
      return this.maxPlayers;
   }

   public void setMaxPlayers(int maxPlayers) {
      this.maxPlayers = maxPlayers;
   }

   public String getToken() {
      return this.token;
   }

   public boolean isHost() {
      return this.isHost;
   }

   public int getHostPort() {
      return this.hostPort;
   }

   public String getNatType() {
      return this.natType;
   }

   public void setNatType(String natType) {
      this.natType = natType;
   }

   public String getHostIp() {
      return this.hostIp;
   }

   public void setHostIp(String hostIp) {
      this.hostIp = hostIp;
   }

   public String getHostIpv6() {
      return this.hostIpv6;
   }

   public void setHostIpv6(String hostIpv6) {
      this.hostIpv6 = hostIpv6;
   }

   public String getHostMappedIp() {
      InetSocketAddress a = this.hostMappedAddress;
      return a != null ? a.getHostString() : null;
   }

   public int getHostMappedPort() {
      InetSocketAddress a = this.hostMappedAddress;
      return a != null ? a.getPort() : 0;
   }

   public InetSocketAddress getHostMappedAddress() {
      return this.hostMappedAddress;
   }

   public void setHostMappedAddress(String hostMappedIp, int hostMappedPort) {
      if (hostMappedIp != null && !hostMappedIp.isEmpty()) {
         this.hostMappedAddress = new InetSocketAddress(hostMappedIp, hostMappedPort);
      } else {
         this.hostMappedAddress = null;
      }
   }

   public boolean isHostSymmetric() {
      return this.hostSymmetric;
   }

   public void setHostSymmetric(boolean hostSymmetric) {
      this.hostSymmetric = hostSymmetric;
   }

   public boolean isHostEasySym() {
      return this.hostEasySym;
   }

   public void setHostEasySym(boolean hostEasySym) {
      this.hostEasySym = hostEasySym;
   }

   public int getHostMappedPortDelta() {
      return this.hostMappedPortDelta;
   }

   public void setHostMappedPortDelta(int delta) {
      this.hostMappedPortDelta = delta;
   }

   public int getHostMappedPortRange() {
      return this.hostMappedPortRange;
   }

   public void setHostMappedPortRange(int range) {
      this.hostMappedPortRange = range;
   }

   public List<Integer> getHostBirthdayPorts() {
      return this.hostBirthdayPorts;
   }

   public void setHostBirthdayPorts(List<Integer> ports) {
      this.hostBirthdayPorts = ports;
   }

   public long getPunchSyncTimeMs() {
      return this.punchSyncTimeMs;
   }

   public void setPunchSyncTimeMs(long ms) {
      this.punchSyncTimeMs = ms;
   }

   public long getPunchSyncSentAtMs() {
      return this.punchSyncSentAtMs;
   }

   public void setPunchSyncSentAtMs(long ms) {
      this.punchSyncSentAtMs = ms;
   }

   public long getJoinerOfferRecvMs() {
      return this.joinerOfferRecvMs;
   }

   public void setJoinerOfferRecvMs(long ms) {
      this.joinerOfferRecvMs = ms;
   }

   public int getCurrentPlayers() {
      return this.currentPlayers.get();
   }

   public void setCurrentPlayers(int currentPlayers) {
      this.currentPlayers.set(currentPlayers);
   }

   public void incrementCurrentPlayers() {
      this.currentPlayers.incrementAndGet();
   }

   public void decrementCurrentPlayers() {
      this.currentPlayers.getAndUpdate(v -> Math.max(0, v - 1));
   }

   public String getClientId() {
      return this.clientId;
   }

   public void setClientId(String clientId) {
      this.clientId = clientId;
   }

   public int getBedrockPort() {
      return this.bedrockPort;
   }

   public void setBedrockPort(int bedrockPort) {
      this.bedrockPort = bedrockPort;
   }

   public int getServerProtocolVersion() {
      return this.serverProtocolVersion;
   }

   public void setServerProtocolVersion(int serverProtocolVersion) {
      this.serverProtocolVersion = serverProtocolVersion;
   }

   public String getCategory() {
      return this.category;
   }

   public void setCategory(String category) {
      this.category = category;
   }

   public String getAuthType() {
      return this.authType;
   }

   public void setAuthType(String authType) {
      this.authType = authType;
   }

   public int getPeerPort() {
      return this.peerPort;
   }

   public void setPeerPort(int peerPort) {
      this.peerPort = peerPort;
   }

   public String getClientType() {
      return this.clientType;
   }

   public void setClientType(String clientType) {
      this.clientType = clientType;
   }

   public String getLoader() {
      return this.loader;
   }

   public void setLoader(String loader) {
      this.loader = loader;
   }

   public Component getConnectionMode() {
      return this.connectionMode;
   }

   public void setConnectionMode(Component connectionMode) {
      this.connectionMode = connectionMode;
      this.usingRelay = false;
   }

   public void setConnectionMode(Component connectionMode, boolean failed) {
      this.connectionMode = connectionMode;
      this.connectionFailed = failed;
      this.usingRelay = false;
   }

   public boolean isUsingRelay() {
      return this.usingRelay;
   }

   public void setUsingRelay(boolean usingRelay) {
      this.usingRelay = usingRelay;
   }

   public int getLocalBridgePort() {
      return this.localBridgePort;
   }

   public void setLocalBridgePort(int localBridgePort) {
      this.localBridgePort = localBridgePort;
   }

   public int getHostConnectPort() {
      return this.hostConnectPort;
   }

   public void setHostConnectPort(int hostConnectPort) {
      this.hostConnectPort = hostConnectPort;
   }

   public RoomInfo.PortStatus getIpv4Status() {
      return this.ipv4Status;
   }

   public void setIpv4Status(RoomInfo.PortStatus ipv4Status) {
      this.ipv4Status = ipv4Status;
   }

   public RoomInfo.PortStatus getIpv6Status() {
      return this.ipv6Status;
   }

   public void setIpv6Status(RoomInfo.PortStatus ipv6Status) {
      this.ipv6Status = ipv6Status;
   }

   public boolean isConnectionFailed() {
      return this.connectionFailed;
   }

   public void setConnectionFailed(boolean failed) {
      this.connectionFailed = failed;
   }

   public boolean isNameApproved() {
      return this.nameApproved;
   }

   public void setNameApproved(boolean nameApproved) {
      this.nameApproved = nameApproved;
   }

   public boolean isVisible() {
      return this.visible;
   }

   public void setVisible(boolean visible) {
      this.visible = visible;
   }

   public boolean isSameCgnat() {
      return this.sameCgnat;
   }

   public void setSameCgnat(boolean sameCgnat) {
      this.sameCgnat = sameCgnat;
   }

   public boolean isGuestOp() {
      return this.guestOp;
   }

   public void setGuestOp(boolean guestOp) {
      this.guestOp = guestOp;
   }

   public String getGameType() {
      return this.gameType;
   }

   public void setGameType(String gameType) {
      this.gameType = gameType;
   }

   public boolean isHostOp() {
      return this.hostOp;
   }

   public void setHostOp(boolean hostOp) {
      this.hostOp = hostOp;
   }

   public String getHostLocalIp() {
      return this.hostLocalIp;
   }

   public void setHostLocalIp(String hostLocalIp) {
      this.hostLocalIp = hostLocalIp;
   }

   public String getJoinerLocalIp() {
      return this.joinerLocalIp;
   }

   public void setJoinerLocalIp(String joinerLocalIp) {
      this.joinerLocalIp = joinerLocalIp;
   }

   public String getMyMappedIp() {
      return this.myMappedIp;
   }

   public void setMyMappedIp(String myMappedIp) {
      this.myMappedIp = myMappedIp;
   }

   public int getMyMappedPort() {
      return this.myMappedPort;
   }

   public void setMyMappedPort(int myMappedPort) {
      this.myMappedPort = myMappedPort;
   }

   public String getTerracottaCode() {
      return this.terracottaCode;
   }

   public void setTerracottaCode(String terracottaCode) {
      this.terracottaCode = terracottaCode;
   }

   public static class PeerInfo {
      public final String clientId;
      public volatile String natType = "unknown";
      public volatile boolean relayEnabled = true;
      public volatile String mappedIp;
      public volatile int mappedPort;
      public volatile int protocolVersion = 0;
      public volatile Set<String> capabilities = Collections.emptySet();
      public volatile long lastSeenMs = System.currentTimeMillis();

      public PeerInfo(String clientId) {
         this.clientId = clientId;
      }
   }

   public enum PortStatus {
      UNKNOWN(Component.translatable("voxlink.port_status.unknown")),
      REACHABLE(Component.translatable("voxlink.port_status.reachable")),
      UNREACHABLE(Component.translatable("voxlink.port_status.unreachable")),
      NO_ADDRESS(Component.translatable("voxlink.port_status.no_address"));

      public final Component label;

      PortStatus(Component label) {
         this.label = label;
      }
   }
}
