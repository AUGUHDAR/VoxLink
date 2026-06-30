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

    private volatile String serverUrl;
    private volatile boolean autoUPnP;
    private volatile boolean offlineMode;
    private volatile boolean voiceChatCompat;
    private volatile int heartbeatInterval;
    private volatile int signalPollInterval;
    private volatile int connectionTimeout;
    private volatile int maxReconnectAttempts;

    public VoxLinkConfig() {
        this.serverUrl = DEFAULT_SERVER_URL;
        this.autoUPnP = true;
        this.offlineMode = true;
        this.voiceChatCompat = true;
        this.heartbeatInterval = 5;
        this.signalPollInterval = 3000;
        this.connectionTimeout = 20000;
        this.maxReconnectAttempts = 3;
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

        if (heartbeatInterval < 5) heartbeatInterval = 5;
        if (signalPollInterval < 3000) signalPollInterval = 3000;
        if (connectionTimeout < 10000) connectionTimeout = 10000;
        if (maxReconnectAttempts < 0) maxReconnectAttempts = 3;
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
}