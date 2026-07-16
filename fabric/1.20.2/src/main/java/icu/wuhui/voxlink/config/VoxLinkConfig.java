package icu.wuhui.voxlink.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class VoxLinkConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String DEFAULT_SERVER_URL = "https://p2p.wuhui.icu";
    public static final int VOICE_CHAT_PORT = 24454;
    private static final int DEFAULT_HEARTBEAT_INTERVAL = 5;
    private static final int DEFAULT_SIGNAL_POLL_INTERVAL = 3000;
    private static final int DEFAULT_CONNECTION_TIMEOUT = 20000;
    private static final int DEFAULT_MAX_RECONNECT = 3;
    private static final int MIN_HEARTBEAT_INTERVAL = 5;
    private static final int MIN_SIGNAL_POLL_INTERVAL = 3000;
    private static final int MIN_CONNECTION_TIMEOUT = 10000;
    private static final int MIN_MAX_RECONNECT = 3;

    private volatile String serverUrl;
    private volatile boolean autoUPnP;
    private volatile boolean offlineMode;
    private volatile boolean voiceChatCompat;
    private volatile int heartbeatInterval;
    private volatile int signalPollInterval;
    private volatile int connectionTimeout;
    private volatile int maxReconnectAttempts;
    private volatile boolean relayEnabled = true;
    private volatile boolean parallelP2P = true;
    private volatile boolean updateCheckEnabled = true;

    public VoxLinkConfig() {
        this.serverUrl = DEFAULT_SERVER_URL;
        this.autoUPnP = true;
        this.offlineMode = true;
        this.voiceChatCompat = true;
        this.heartbeatInterval = DEFAULT_HEARTBEAT_INTERVAL;
        this.signalPollInterval = DEFAULT_SIGNAL_POLL_INTERVAL;
        this.connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
        this.maxReconnectAttempts = DEFAULT_MAX_RECONNECT;
        this.relayEnabled = true;
        this.parallelP2P = true;
        this.updateCheckEnabled = true;
    }

    public static VoxLinkConfig load() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("voxlink.json");
        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                VoxLinkConfig config = GSON.fromJson(json, VoxLinkConfig.class);
                if (config == null) {
                    LOGGER.warn("配置文件空或无效，用默认值");
                    config = new VoxLinkConfig();
                }
                if (config.serverUrl == null || config.serverUrl.isEmpty()) {
                    config.serverUrl = DEFAULT_SERVER_URL;
                }
                if (config.serverUrl.contains("index.php")) {
                    LOGGER.warn("检测到旧版URL格式: {}，重置为默认", config.serverUrl);
                    config.serverUrl = DEFAULT_SERVER_URL;
                }
                config.validate();
                config.save();
                LOGGER.info("配置加载完成");
                return config;
            } catch (Exception e) {
                LOGGER.warn("配置加载失败，用默认值: {}", e.getMessage());
            }
        }
        VoxLinkConfig config = new VoxLinkConfig();
        config.save();
        return config;
    }

    public synchronized void save() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("voxlink.json");
        Path tmpPath = FabricLoader.getInstance().getConfigDir().resolve("voxlink.json.tmp");
        try {
            Files.writeString(tmpPath, GSON.toJson(this));
            try {
                Files.move(tmpPath, configPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmpPath, configPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("配置保存失败: {}", e.getMessage());
        } finally {
            try { Files.deleteIfExists(tmpPath); } catch (Exception ignored) {}
        }
    }

    private void validate() {
        if (serverUrl != null) {
            serverUrl = serverUrl.replaceAll("/+$", "");
        }

        if (heartbeatInterval < MIN_HEARTBEAT_INTERVAL) heartbeatInterval = MIN_HEARTBEAT_INTERVAL;
        if (signalPollInterval < MIN_SIGNAL_POLL_INTERVAL) signalPollInterval = MIN_SIGNAL_POLL_INTERVAL;
        if (connectionTimeout < MIN_CONNECTION_TIMEOUT) connectionTimeout = MIN_CONNECTION_TIMEOUT;
        if (maxReconnectAttempts < 0) maxReconnectAttempts = MIN_MAX_RECONNECT;
    }

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }
    public boolean isAutoUPnP() { return autoUPnP; }
    public void setAutoUPnP(boolean autoUPnP) { this.autoUPnP = autoUPnP; }
    public boolean isOfflineMode() { return offlineMode; }
    public void setOfflineMode(boolean offlineMode) { this.offlineMode = offlineMode; }
    public boolean isVoiceChatCompat() { return voiceChatCompat; }
    public void setVoiceChatCompat(boolean voiceChatCompat) { this.voiceChatCompat = voiceChatCompat; }
    public int getHeartbeatInterval() { return heartbeatInterval; }
    public int getSignalPollInterval() { return signalPollInterval; }
    public int getConnectionTimeout() { return connectionTimeout; }
    public int getMaxReconnectAttempts() { return maxReconnectAttempts; }
    public boolean isRelayEnabled() { return relayEnabled; }
    public void setRelayEnabled(boolean relayEnabled) { this.relayEnabled = relayEnabled; }
    public boolean isParallelP2P() { return parallelP2P; }
    public void setParallelP2P(boolean v) { this.parallelP2P = v; }
    public boolean isUpdateCheckEnabled() { return updateCheckEnabled; }
    public void setUpdateCheckEnabled(boolean v) { this.updateCheckEnabled = v; }
}