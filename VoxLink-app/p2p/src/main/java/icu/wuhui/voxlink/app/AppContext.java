package icu.wuhui.voxlink.app;

import icu.wuhui.voxlink.network.SignalingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// APP全局访问点，替代mod端VoxLinkMod
public final class AppContext {
    public static final String MOD_ID = "voxlink-app";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final String MOD_VERSION = "0.1.0";

    private static volatile AppConfig config;
    private static volatile SignalingClient signalingClient;

    private AppContext() {}

    public static AppConfig getConfig() {
        if (config == null) {
            synchronized (AppContext.class) {
                if (config == null) {
                    config = new AppConfig();
                }
            }
        }
        return config;
    }

    public static SignalingClient getSignalingClient() {
        if (signalingClient == null) {
            synchronized (AppContext.class) {
                if (signalingClient == null) {
                    signalingClient = new SignalingClient(getConfig());
                }
            }
        }
        return signalingClient;
    }
}
