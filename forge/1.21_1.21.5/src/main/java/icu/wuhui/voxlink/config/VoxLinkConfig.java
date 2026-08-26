package icu.wuhui.voxlink.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;
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
    private static final int DEFAULT_HEARTBEAT_INTERVAL = 5;
    private static final int DEFAULT_SIGNAL_POLL_INTERVAL = 3000;
    private static final int DEFAULT_CONNECTION_TIMEOUT = 20000;
    private static final int DEFAULT_MAX_RECONNECT = 3;
    private static final int MIN_HEARTBEAT_INTERVAL = 5;
    private static final int MIN_SIGNAL_POLL_INTERVAL = 3000;
    private static final int MIN_CONNECTION_TIMEOUT = 10000;
    private static final int MIN_MAX_RECONNECT = 3;
    //debounce 上限常量 防止玩家设过大值导致性能问题
    private static final int MAX_HEARTBEAT_INTERVAL = 60;
    private static final int MAX_SIGNAL_POLL_INTERVAL = 10000;
    private static final int MAX_CONNECTION_TIMEOUT = 60000;
    private static final int MAX_MAX_RECONNECT = 10;
    private static final int CURRENT_CONFIG_VERSION = 1;

    private volatile int configVersion = CURRENT_CONFIG_VERSION;
    private volatile String serverUrl;
    private volatile boolean autoUPnP;
    private volatile boolean offlineMode;
    private volatile int heartbeatInterval;
    private volatile int signalPollInterval;
    private volatile int connectionTimeout;
    private volatile int maxReconnectAttempts;
    private volatile boolean relayEnabled = true;
    private volatile boolean parallelP2P = true;
    private volatile boolean updateCheckEnabled = true;
    private volatile boolean useWebSocket = true;
    /** 日志上传默认开启（产品决策：打洞体验与远程排障），GUI 开关写入并持久化到此字段 */
    private volatile boolean logUploadEnabled = true;
   /** 加入前请求房主必装 Mod 清单并引导下载/重启（ModSync）；关闭后跳过整套流程，能否进房由服务器决定 */
   private volatile boolean joinRequiredModsCheck = true;
   /** 房主是否向信令服务器上报必装 Mod 清单（ModSync）；关闭后房客拉不到清单、直接进洞 */
   private volatile boolean hostModSyncPublish = true;
    /** 显式允许 http:// 信令服务器（默认拒绝，validate 中强制回退默认 https 地址） */
    private volatile boolean allowInsecureServerUrl = false;

    public VoxLinkConfig() {
        this.serverUrl = DEFAULT_SERVER_URL;
        this.autoUPnP = true;
        this.offlineMode = true;
        this.heartbeatInterval = DEFAULT_HEARTBEAT_INTERVAL;
        this.signalPollInterval = DEFAULT_SIGNAL_POLL_INTERVAL;
        this.connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
        this.maxReconnectAttempts = DEFAULT_MAX_RECONNECT;
        this.relayEnabled = true;
        this.parallelP2P = true;
        this.updateCheckEnabled = true;
        this.useWebSocket = true;
        this.logUploadEnabled = true;
      this.joinRequiredModsCheck = true;
      this.hostModSyncPublish = true;
        this.allowInsecureServerUrl = false;
    }

    //debounce Gson默认绕过构造器+字段初始化器 旧配置缺字段会读成false
    //手动JsonObject逐字段读 缺失用默认值 满足relayEnabled默认true约束
    public static VoxLinkConfig load() {
        Path configPath = FMLPaths.CONFIGDIR.get().resolve("voxlink.json");
        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                VoxLinkConfig config = new VoxLinkConfig();
                JsonObject root = JsonParser.parseString(json).isJsonObject()
                        ? JsonParser.parseString(json).getAsJsonObject() : new JsonObject();
                if (root.has("configVersion")) config.configVersion = root.get("configVersion").getAsInt();
                config.serverUrl = root.has("serverUrl") && !root.get("serverUrl").isJsonNull()
                        ? root.get("serverUrl").getAsString() : DEFAULT_SERVER_URL;
                config.autoUPnP = root.has("autoUPnP") ? root.get("autoUPnP").getAsBoolean() : true;
                config.offlineMode = root.has("offlineMode") ? root.get("offlineMode").getAsBoolean() : true;
                config.heartbeatInterval = root.has("heartbeatInterval") ? root.get("heartbeatInterval").getAsInt() : DEFAULT_HEARTBEAT_INTERVAL;
                config.signalPollInterval = root.has("signalPollInterval") ? root.get("signalPollInterval").getAsInt() : DEFAULT_SIGNAL_POLL_INTERVAL;
                config.connectionTimeout = root.has("connectionTimeout") ? root.get("connectionTimeout").getAsInt() : DEFAULT_CONNECTION_TIMEOUT;
                config.maxReconnectAttempts = root.has("maxReconnectAttempts") ? root.get("maxReconnectAttempts").getAsInt() : DEFAULT_MAX_RECONNECT;
                //debounce 关键默认值true 旧配置缺这些字段会被Gson置false 此处强制补true
                config.relayEnabled = root.has("relayEnabled") ? root.get("relayEnabled").getAsBoolean() : true;
                config.parallelP2P = root.has("parallelP2P") ? root.get("parallelP2P").getAsBoolean() : true;
                config.updateCheckEnabled = root.has("updateCheckEnabled") ? root.get("updateCheckEnabled").getAsBoolean() : true;
                config.useWebSocket = root.has("useWebSocket") ? root.get("useWebSocket").getAsBoolean() : true;
                config.logUploadEnabled = root.has("logUploadEnabled") ? root.get("logUploadEnabled").getAsBoolean() : true;
            config.joinRequiredModsCheck = root.has("joinRequiredModsCheck") ? root.get("joinRequiredModsCheck").getAsBoolean() : true;
            config.hostModSyncPublish = root.has("hostModSyncPublish") ? root.get("hostModSyncPublish").getAsBoolean() : true;
                config.allowInsecureServerUrl = root.has("allowInsecureServerUrl") && root.get("allowInsecureServerUrl").getAsBoolean();
                if (config.serverUrl == null || config.serverUrl.isEmpty()) {
                    config.serverUrl = DEFAULT_SERVER_URL;
                }
                if (config.serverUrl.contains("index.php")) {
                    LOGGER.warn("Detected legacy URL format: {}, reset to default", config.serverUrl);
                    config.serverUrl = DEFAULT_SERVER_URL;
                }
                //debounce 旧版配置(无configVersion或<1)强制relayEnabled=true 覆盖显式false
                int fileVer = root.has("configVersion") ? root.get("configVersion").getAsInt() : 0;
                if (fileVer < 1) {
                    config.relayEnabled = true;
                    LOGGER.info("Legacy config migration: relayEnabled forced to true");
                }
                config.configVersion = CURRENT_CONFIG_VERSION;
                config.validate();
                config.save();
                LOGGER.info("Config loaded");
                return config;
            } catch (Exception e) {
                LOGGER.warn("Config load failed, using defaults: {}", e.getMessage());
            }
        }
        VoxLinkConfig config = new VoxLinkConfig();
        config.save();
        return config;
    }

    public synchronized void save() {
        Path configPath = FMLPaths.CONFIGDIR.get().resolve("voxlink.json");
        Path tmpPath = FMLPaths.CONFIGDIR.get().resolve("voxlink.json.tmp");
        try {
            Files.writeString(tmpPath, GSON.toJson(this));
            try {
                Files.move(tmpPath, configPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmpPath, configPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("Config save failed: {}", e.getMessage());
        } finally {
            try { Files.deleteIfExists(tmpPath); } catch (Exception ignored) {}
        }
    }

    private void validate() {
        if (serverUrl != null) {
            serverUrl = serverUrl.replaceAll("/+$", "");
        }

        // 安全修复：明文 http:// 信令默认拒绝（凭据/房间令牌会明文出网）；
        // 显式设置 allowInsecureServerUrl=true 可放行自建 http 服务器。
        if ((this.serverUrl != null && this.serverUrl.toLowerCase().startsWith("http://")) && !this.allowInsecureServerUrl) {
           this.serverUrl = "https://p2p.wuhui.icu";
        }
        if (heartbeatInterval < MIN_HEARTBEAT_INTERVAL) heartbeatInterval = MIN_HEARTBEAT_INTERVAL;
        if (signalPollInterval < MIN_SIGNAL_POLL_INTERVAL) signalPollInterval = MIN_SIGNAL_POLL_INTERVAL;
        if (connectionTimeout < MIN_CONNECTION_TIMEOUT) connectionTimeout = MIN_CONNECTION_TIMEOUT;
        //debounce 与其他三个字段对齐 用MIN_MAX_RECONNECT而非0
        if (maxReconnectAttempts < MIN_MAX_RECONNECT) maxReconnectAttempts = MIN_MAX_RECONNECT;
        //debounce 加上限 防止过大值
        if (heartbeatInterval > MAX_HEARTBEAT_INTERVAL) heartbeatInterval = MAX_HEARTBEAT_INTERVAL;
        if (signalPollInterval > MAX_SIGNAL_POLL_INTERVAL) signalPollInterval = MAX_SIGNAL_POLL_INTERVAL;
        if (connectionTimeout > MAX_CONNECTION_TIMEOUT) connectionTimeout = MAX_CONNECTION_TIMEOUT;
        if (maxReconnectAttempts > MAX_MAX_RECONNECT) maxReconnectAttempts = MAX_MAX_RECONNECT;
    }

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }
    public boolean isAutoUPnP() { return autoUPnP; }
    public void setAutoUPnP(boolean autoUPnP) { this.autoUPnP = autoUPnP; }
    public boolean isOfflineMode() { return offlineMode; }
    public void setOfflineMode(boolean offlineMode) { this.offlineMode = offlineMode; }
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
    public boolean isUseWebSocket() { return useWebSocket; }
    public void setUseWebSocket(boolean v) { this.useWebSocket = v; }
    public boolean isLogUploadEnabled() {
       return this.logUploadEnabled;
    }

    public void setLogUploadEnabled(boolean v) {
       this.logUploadEnabled = v;
    }

   public boolean isJoinRequiredModsCheck() {
      return this.joinRequiredModsCheck;
   }

   public void setJoinRequiredModsCheck(boolean v) {
      this.joinRequiredModsCheck = v;
   }

   public boolean isHostModSyncPublish() {
      return this.hostModSyncPublish;
   }

   public void setHostModSyncPublish(boolean v) {
      this.hostModSyncPublish = v;
   }

    public boolean isAllowInsecureServerUrl() {
       return this.allowInsecureServerUrl;
    }

    public void setAllowInsecureServerUrl(boolean v) {
       this.allowInsecureServerUrl = v;
    }

    public int getConfigVersion() { return configVersion; }
}
