package icu.wuhui.voxlink.room;
/** 本地实验桩: P2PBridge 只读 getCurrent() 比对 CONNECTED */
public enum ConnectionState {
   IDLE, STUN_PROBE, SIGNAL_EXCHANGE, UDP_PUNCH, TCP_FALLBACK, TRANSPORT_SETUP, CONNECTED, DISCONNECTED, FAILED;
   private static volatile ConnectionState current = CONNECTED;
   public static ConnectionState getCurrent() { return current; }
   public static void transitionTo(ConnectionState s, String why) { current = s; }
}
