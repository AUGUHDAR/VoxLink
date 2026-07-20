package icu.wuhui.voxlink.room;

import net.minecraft.network.chat.Component;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

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
    //debounce 合并为不可变快照 防止ip/port分裂读取导致打洞包发往错误目标
    private volatile InetSocketAddress hostMappedAddress = null;
    private volatile boolean hostSymmetric;
    private volatile boolean hostEasySym;
    private volatile int hostMappedPortDelta = 0;
    private volatile int hostMappedPortRange = 100;
    private volatile java.util.List<Integer> hostBirthdayPorts = null;
    private volatile long punchSyncTimeMs = 0;
    private final AtomicInteger currentPlayers;
    private volatile String clientId;
    private volatile int bedrockPort;
    private volatile int serverProtocolVersion;
    private volatile String category;
    private volatile String authType;
    private volatile int peerPort;
    private volatile String clientType = "mod";
    private volatile Component connectionMode = Component.empty();
    //debounce 显式标记当前是否走中继 避免UI层字符串匹配翻译文本
    private volatile boolean usingRelay = false;
    private volatile boolean connectionFailed = false;
    private volatile int localBridgePort = 0;
    private volatile int hostConnectPort = 0;
    private volatile PortStatus ipv4Status = PortStatus.UNKNOWN;
    private volatile PortStatus ipv6Status = PortStatus.UNKNOWN;
    private volatile boolean nameApproved = true;
    private volatile boolean visible = true;
    private volatile boolean sameCgnat = false;
    private volatile boolean guestOp = false;
    private volatile String gameType = "survival";
    private volatile boolean allowCheats = false;
    private volatile String hostLocalIp = null;
    private volatile String joinerLocalIp = null;
    private volatile String myMappedIp = null;
    private volatile int myMappedPort = 0;
    private volatile String terracottaCode = null;

    private final java.util.concurrent.ConcurrentHashMap<String, PeerInfo> peerMap = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean connectionAttemptFailed = false;

    public static class PeerInfo {
        public final String clientId;
        public volatile String natType = "unknown";
        public volatile boolean relayEnabled = true;
        public volatile String mappedIp;
        public volatile int mappedPort;
        public PeerInfo(String clientId) { this.clientId = clientId; }
    }

    public void addOrUpdatePeer(String clientId, String natType, String mappedIp, int mappedPort) {
        PeerInfo info = peerMap.computeIfAbsent(clientId, id -> new PeerInfo(id));
        if (natType != null) info.natType = natType;
        if (mappedIp != null) info.mappedIp = mappedIp;
        if (mappedPort > 0) info.mappedPort = mappedPort;
    }
    public PeerInfo getPeer(String clientId) { return peerMap.get(clientId); }
    public void removePeer(String clientId) { peerMap.remove(clientId); }
    public java.util.Collection<PeerInfo> getPeers() { return peerMap.values(); }
    public int getPeerCount() { return peerMap.size(); }
    public boolean isConnectionAttemptFailed() { return connectionAttemptFailed; }
    public void setConnectionAttemptFailed(boolean v) { this.connectionAttemptFailed = v; }

    public enum PortStatus {
        UNKNOWN(Component.translatable("voxlink.port_status.unknown")),
        REACHABLE(Component.translatable("voxlink.port_status.reachable")),
        UNREACHABLE(Component.translatable("voxlink.port_status.unreachable")),
        NO_ADDRESS(Component.translatable("voxlink.port_status.no_address"));

        public final Component label;
        PortStatus(Component label) { this.label = label; }
    }

    public RoomInfo(String code, String name, boolean hasPassword, int maxPlayers,
                    String token, boolean isHost, int hostPort, String natType) {
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

    public String getCode() { return code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean hasPassword() { return hasPassword; }
    public void setPassword(String password) { this.hasPassword = password != null && !password.isEmpty(); }
    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }
    public String getToken() { return token; }
    public boolean isHost() { return isHost; }
    public int getHostPort() { return hostPort; }
    public String getNatType() { return natType; }
    public void setNatType(String natType) { this.natType = natType; }
    public String getHostIp() { return hostIp; }
    public void setHostIp(String hostIp) { this.hostIp = hostIp; }
    public String getHostIpv6() { return hostIpv6; }
    public void setHostIpv6(String hostIpv6) { this.hostIpv6 = hostIpv6; }
    public String getHostMappedIp() {
        InetSocketAddress a = hostMappedAddress;
        return a != null ? a.getHostString() : null;
    }
    public int getHostMappedPort() {
        InetSocketAddress a = hostMappedAddress;
        return a != null ? a.getPort() : 0;
    }
    public InetSocketAddress getHostMappedAddress() { return hostMappedAddress; }
    public void setHostMappedAddress(String hostMappedIp, int hostMappedPort) {
        if (hostMappedIp == null || hostMappedIp.isEmpty()) {
            this.hostMappedAddress = null;
        } else {
            this.hostMappedAddress = new InetSocketAddress(hostMappedIp, hostMappedPort);
        }
    }
    public boolean isHostSymmetric() { return hostSymmetric; }
    public void setHostSymmetric(boolean hostSymmetric) { this.hostSymmetric = hostSymmetric; }
    public boolean isHostEasySym() { return hostEasySym; }
    public void setHostEasySym(boolean hostEasySym) { this.hostEasySym = hostEasySym; }
    public int getHostMappedPortDelta() { return hostMappedPortDelta; }
    public void setHostMappedPortDelta(int delta) { this.hostMappedPortDelta = delta; }
    public int getHostMappedPortRange() { return hostMappedPortRange; }
    public void setHostMappedPortRange(int range) { this.hostMappedPortRange = range; }
    public java.util.List<Integer> getHostBirthdayPorts() { return hostBirthdayPorts; }
    public void setHostBirthdayPorts(java.util.List<Integer> ports) { this.hostBirthdayPorts = ports; }
    public long getPunchSyncTimeMs() { return punchSyncTimeMs; }
    public void setPunchSyncTimeMs(long ms) { this.punchSyncTimeMs = ms; }
    public int getCurrentPlayers() { return currentPlayers.get(); }
    public void setCurrentPlayers(int currentPlayers) { this.currentPlayers.set(currentPlayers); }
    public void incrementCurrentPlayers() { this.currentPlayers.incrementAndGet(); }
    public void decrementCurrentPlayers() { this.currentPlayers.getAndUpdate(v -> Math.max(0, v - 1)); }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public int getBedrockPort() { return bedrockPort; }
    public void setBedrockPort(int bedrockPort) { this.bedrockPort = bedrockPort; }
    public int getServerProtocolVersion() { return serverProtocolVersion; }
    public void setServerProtocolVersion(int serverProtocolVersion) { this.serverProtocolVersion = serverProtocolVersion; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }
    public int getPeerPort() { return peerPort; }
    public void setPeerPort(int peerPort) { this.peerPort = peerPort; }
    public String getClientType() { return clientType; }
    public void setClientType(String clientType) { this.clientType = clientType; }
    public Component getConnectionMode() { return connectionMode; }
    public void setConnectionMode(Component connectionMode) {
        this.connectionMode = connectionMode;
        this.usingRelay = false;
    }
    public void setConnectionMode(Component connectionMode, boolean failed) {
        this.connectionMode = connectionMode;
        this.connectionFailed = failed;
        this.usingRelay = false;
    }
    public boolean isUsingRelay() { return usingRelay; }
    public void setUsingRelay(boolean usingRelay) { this.usingRelay = usingRelay; }
    public int getLocalBridgePort() { return localBridgePort; }
    public void setLocalBridgePort(int localBridgePort) { this.localBridgePort = localBridgePort; }
    public int getHostConnectPort() { return hostConnectPort; }
    public void setHostConnectPort(int hostConnectPort) { this.hostConnectPort = hostConnectPort; }
    public PortStatus getIpv4Status() { return ipv4Status; }
    public void setIpv4Status(PortStatus ipv4Status) { this.ipv4Status = ipv4Status; }
    public PortStatus getIpv6Status() { return ipv6Status; }
    public void setIpv6Status(PortStatus ipv6Status) { this.ipv6Status = ipv6Status; }

    public boolean isConnectionFailed() {
        return connectionFailed;
    }

    public void setConnectionFailed(boolean failed) {
        this.connectionFailed = failed;
    }

    public boolean isNameApproved() { return nameApproved; }
    public void setNameApproved(boolean nameApproved) { this.nameApproved = nameApproved; }
    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }
    public boolean isSameCgnat() { return sameCgnat; }
    public void setSameCgnat(boolean sameCgnat) { this.sameCgnat = sameCgnat; }
    public boolean isGuestOp() { return guestOp; }
    public void setGuestOp(boolean guestOp) { this.guestOp = guestOp; }
    public String getGameType() { return gameType; }
    public void setGameType(String gameType) { this.gameType = gameType; }
    public boolean isAllowCheats() { return allowCheats; }
    public void setAllowCheats(boolean allowCheats) { this.allowCheats = allowCheats; }
    public String getHostLocalIp() { return hostLocalIp; }
    public void setHostLocalIp(String hostLocalIp) { this.hostLocalIp = hostLocalIp; }
    public String getJoinerLocalIp() { return joinerLocalIp; }
    public void setJoinerLocalIp(String joinerLocalIp) { this.joinerLocalIp = joinerLocalIp; }
    public String getMyMappedIp() { return myMappedIp; }
    public void setMyMappedIp(String myMappedIp) { this.myMappedIp = myMappedIp; }
    public int getMyMappedPort() { return myMappedPort; }
    public void setMyMappedPort(int myMappedPort) { this.myMappedPort = myMappedPort; }
    public String getTerracottaCode() { return terracottaCode; }
    public void setTerracottaCode(String terracottaCode) { this.terracottaCode = terracottaCode; }
}
