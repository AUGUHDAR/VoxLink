package icu.wuhui.voxlink.app;

// APP配置，替代mod端VoxLinkConfig（去掉MC专用项如autoUPnP/voiceChatCompat）
public class AppConfig {
    private static final String DEFAULT_SERVER_URL = "https://p2p.wuhui.icu";

    private volatile String serverUrl;
    private volatile int heartbeatInterval;
    private volatile int signalPollInterval;
    private volatile int connectionTimeout;
    private volatile int maxReconnectAttempts;
    private volatile boolean relayEnabled;

    public AppConfig() {
        this.serverUrl = DEFAULT_SERVER_URL;
        this.heartbeatInterval = 5;
        this.signalPollInterval = 200;
        this.connectionTimeout = 10000;
        this.maxReconnectAttempts = 3;
        this.relayEnabled = true;
    }

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }
    public int getHeartbeatInterval() { return heartbeatInterval; }
    public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    public int getSignalPollInterval() { return signalPollInterval; }
    public void setSignalPollInterval(int signalPollInterval) { this.signalPollInterval = signalPollInterval; }
    public int getConnectionTimeout() { return connectionTimeout; }
    public void setConnectionTimeout(int connectionTimeout) { this.connectionTimeout = connectionTimeout; }
    public int getMaxReconnectAttempts() { return maxReconnectAttempts; }
    public void setMaxReconnectAttempts(int maxReconnectAttempts) { this.maxReconnectAttempts = maxReconnectAttempts; }
    public boolean isRelayEnabled() { return relayEnabled; }
    public void setRelayEnabled(boolean relayEnabled) { this.relayEnabled = relayEnabled; }
}
