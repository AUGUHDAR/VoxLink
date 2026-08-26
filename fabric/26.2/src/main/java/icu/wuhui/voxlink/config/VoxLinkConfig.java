package icu.wuhui.voxlink.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
   private static final int MAX_HEARTBEAT_INTERVAL = 60;
   private static final int MAX_SIGNAL_POLL_INTERVAL = 10000;
   private static final int MAX_CONNECTION_TIMEOUT = 60000;
   private static final int MAX_MAX_RECONNECT = 10;
   private static final int CURRENT_CONFIG_VERSION = 1;
   private volatile int configVersion = 1;
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
   /** 显式允许 http:// 信令服务器（默认拒绝，validate 中强制回退默认 https 地址） */
   private volatile boolean allowInsecureServerUrl = false;

   public VoxLinkConfig() {
      this.serverUrl = "https://p2p.wuhui.icu";
      this.autoUPnP = true;
      this.offlineMode = true;
      this.heartbeatInterval = 5;
      this.signalPollInterval = 3000;
      this.connectionTimeout = 20000;
      this.maxReconnectAttempts = 3;
      this.relayEnabled = true;
      this.parallelP2P = true;
      this.updateCheckEnabled = true;
      this.useWebSocket = true;
      this.logUploadEnabled = true;
      this.joinRequiredModsCheck = true;
      this.allowInsecureServerUrl = false;
   }

   public static VoxLinkConfig load() {
      Path configPath = FabricLoader.getInstance().getConfigDir().resolve("voxlink.json");
      if (Files.exists(configPath)) {
         try {
            String json = Files.readString(configPath);
            VoxLinkConfig config = new VoxLinkConfig();
            JsonObject root = JsonParser.parseString(json).isJsonObject() ? JsonParser.parseString(json).getAsJsonObject() : new JsonObject();
            if (root.has("configVersion")) {
               config.configVersion = root.get("configVersion").getAsInt();
            }

            config.serverUrl = root.has("serverUrl") && !root.get("serverUrl").isJsonNull() ? root.get("serverUrl").getAsString() : "https://p2p.wuhui.icu";
            config.autoUPnP = root.has("autoUPnP") ? root.get("autoUPnP").getAsBoolean() : true;
            config.offlineMode = root.has("offlineMode") ? root.get("offlineMode").getAsBoolean() : true;
            config.heartbeatInterval = root.has("heartbeatInterval") ? root.get("heartbeatInterval").getAsInt() : 5;
            config.signalPollInterval = root.has("signalPollInterval") ? root.get("signalPollInterval").getAsInt() : 3000;
            config.connectionTimeout = root.has("connectionTimeout") ? root.get("connectionTimeout").getAsInt() : 20000;
            config.maxReconnectAttempts = root.has("maxReconnectAttempts") ? root.get("maxReconnectAttempts").getAsInt() : 3;
            config.relayEnabled = root.has("relayEnabled") ? root.get("relayEnabled").getAsBoolean() : true;
            config.parallelP2P = root.has("parallelP2P") ? root.get("parallelP2P").getAsBoolean() : true;
            config.updateCheckEnabled = root.has("updateCheckEnabled") ? root.get("updateCheckEnabled").getAsBoolean() : true;
            config.useWebSocket = root.has("useWebSocket") ? root.get("useWebSocket").getAsBoolean() : true;
            config.logUploadEnabled = root.has("logUploadEnabled") ? root.get("logUploadEnabled").getAsBoolean() : true;
            config.joinRequiredModsCheck = root.has("joinRequiredModsCheck") ? root.get("joinRequiredModsCheck").getAsBoolean() : true;
            config.allowInsecureServerUrl = root.has("allowInsecureServerUrl") && root.get("allowInsecureServerUrl").getAsBoolean();
            if (config.serverUrl == null || config.serverUrl.isEmpty()) {
               config.serverUrl = "https://p2p.wuhui.icu";
            }

            if (config.serverUrl.contains("index.php")) {
               LOGGER.warn("Detected legacy URL format: {}, reset to default", config.serverUrl);
               config.serverUrl = "https://p2p.wuhui.icu";
            }

            int fileVer = root.has("configVersion") ? root.get("configVersion").getAsInt() : 0;
            if (fileVer < 1) {
               LOGGER.info("Legacy config migration: respect relayEnabled={}", config.relayEnabled);
            }

            config.configVersion = 1;
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
      Path configPath = FabricLoader.getInstance().getConfigDir().resolve("voxlink.json");
      Path tmpPath = FabricLoader.getInstance().getConfigDir().resolve("voxlink.json.tmp");

      try {
         Files.writeString(tmpPath, GSON.toJson(this));

         try {
            Files.move(tmpPath, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
         } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmpPath, configPath, StandardCopyOption.REPLACE_EXISTING);
         }
      } catch (IOException e) {
         LOGGER.error("Config save failed: {}", e.getMessage());
      } finally {
         try {
            Files.deleteIfExists(tmpPath);
         } catch (Exception var12) {
         }
      }
   }

   private void validate() {
      if (this.serverUrl != null) {
         this.serverUrl = this.serverUrl.replaceAll("/+$", "");
      }

      // 安全修复：明文 http:// 信令默认拒绝（凭据/房间令牌会明文出网）；
      // 显式设置 allowInsecureServerUrl=true 可放行自建 http 服务器。
      if (this.serverUrl != null && this.serverUrl.toLowerCase().startsWith("http://") && !this.allowInsecureServerUrl) {
         this.serverUrl = "https://p2p.wuhui.icu";
      }

      if (this.heartbeatInterval < 5) {
         this.heartbeatInterval = 5;
      }

      if (this.signalPollInterval < 3000) {
         this.signalPollInterval = 3000;
      }

      if (this.connectionTimeout < 10000) {
         this.connectionTimeout = 10000;
      }

      if (this.maxReconnectAttempts < 3) {
         this.maxReconnectAttempts = 3;
      }

      if (this.heartbeatInterval > 60) {
         this.heartbeatInterval = 60;
      }

      if (this.signalPollInterval > 10000) {
         this.signalPollInterval = 10000;
      }

      if (this.connectionTimeout > 60000) {
         this.connectionTimeout = 60000;
      }

      if (this.maxReconnectAttempts > 10) {
         this.maxReconnectAttempts = 10;
      }
   }

   public String getServerUrl() {
      return this.serverUrl;
   }

   public void setServerUrl(String serverUrl) {
      this.serverUrl = serverUrl;
   }

   public boolean isAutoUPnP() {
      return this.autoUPnP;
   }

   public void setAutoUPnP(boolean autoUPnP) {
      this.autoUPnP = autoUPnP;
   }

   public boolean isOfflineMode() {
      return this.offlineMode;
   }

   public void setOfflineMode(boolean offlineMode) {
      this.offlineMode = offlineMode;
   }

   public int getHeartbeatInterval() {
      return this.heartbeatInterval;
   }

   public int getSignalPollInterval() {
      return this.signalPollInterval;
   }

   public int getConnectionTimeout() {
      return this.connectionTimeout;
   }

   public int getMaxReconnectAttempts() {
      return this.maxReconnectAttempts;
   }

   public boolean isRelayEnabled() {
      return this.relayEnabled;
   }

   public void setRelayEnabled(boolean relayEnabled) {
      this.relayEnabled = relayEnabled;
   }

   public boolean isParallelP2P() {
      return this.parallelP2P;
   }

   public void setParallelP2P(boolean v) {
      this.parallelP2P = v;
   }

   public boolean isUpdateCheckEnabled() {
      return this.updateCheckEnabled;
   }

   public void setUpdateCheckEnabled(boolean v) {
      this.updateCheckEnabled = v;
   }
   public boolean isUseWebSocket() {
      return this.useWebSocket;
   }

   public void setUseWebSocket(boolean v) {
      this.useWebSocket = v;
   }

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

   public boolean isAllowInsecureServerUrl() {
      return this.allowInsecureServerUrl;
   }

   public void setAllowInsecureServerUrl(boolean v) {
      this.allowInsecureServerUrl = v;
   }

   public int getConfigVersion() {
      return this.configVersion;
   }
}
