package icu.wuhui.voxlink.network;

import java.util.concurrent.ScheduledExecutorService;

// APP端空壳：无中继，方法做空操作
public class RelayBridge {
    private static volatile RelayBridge instance;

    public static RelayBridge getInstance(ScheduledExecutorService scheduler) {
        if (instance == null) {
            synchronized (RelayBridge.class) {
                if (instance == null) {
                    instance = new RelayBridge();
                }
            }
        }
        return instance;
    }

    public void startRelay(String peerAId, String peerBId,
                           Object transportA, Object transportB) {
        // APP端无中继
    }

    public void stopRelay(String peerAId, String peerBId) {
        // APP端无中继
    }

    public void stopAll() {
        // APP端无中继
    }

    public int getActiveRelayCount() {
        return 0;
    }

    public boolean isRelayingFor(String peerId) {
        return false;
    }

    public int getRelayCountForPeer(String peerId) {
        return 0;
    }
}
