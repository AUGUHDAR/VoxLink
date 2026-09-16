package icu.wuhui.voxlink.room;

import icu.wuhui.voxlink.VoxLinkMod;

public enum ConnectionState {
   IDLE("空闲"),
   STUN_PROBE("STUN探测"),
   SIGNAL_EXCHANGE("信令交换"),
   UDP_PUNCH("UDP打洞"),
   TCP_FALLBACK("TCP回退"),
   TRANSPORT_SETUP("传输建立"),
   CONNECTED("已连接"),
   DISCONNECTED("已断开"),
   FAILED("失败");

   public final String displayName;
   private static volatile ConnectionState currentState = IDLE;
   private static volatile long stateEnterTime = System.currentTimeMillis();

   ConnectionState(String displayName) {
      this.displayName = displayName;
   }

   public boolean isTerminal() {
      return this == CONNECTED || this == DISCONNECTED || this == FAILED;
   }

   public boolean canTransitionTo(ConnectionState next) {
      return switch (this) {
         case IDLE -> next == STUN_PROBE || next == DISCONNECTED || next == UDP_PUNCH || next == TRANSPORT_SETUP;
         case STUN_PROBE -> next == SIGNAL_EXCHANGE || next == UDP_PUNCH || next == TCP_FALLBACK || next == FAILED;
         case SIGNAL_EXCHANGE -> next == UDP_PUNCH || next == TCP_FALLBACK || next == FAILED || next == TRANSPORT_SETUP;
         case UDP_PUNCH -> next == TRANSPORT_SETUP || next == TCP_FALLBACK || next == FAILED || next == CONNECTED || next == IDLE || next == STUN_PROBE || next == SIGNAL_EXCHANGE;
         case TCP_FALLBACK -> next == TRANSPORT_SETUP || next == FAILED || next == CONNECTED || next == STUN_PROBE || next == UDP_PUNCH || next == IDLE;
         case TRANSPORT_SETUP -> next == CONNECTED || next == FAILED || next == UDP_PUNCH || next == IDLE;
         case CONNECTED -> next == DISCONNECTED || next == UDP_PUNCH || next == IDLE;
         case DISCONNECTED, FAILED -> next == IDLE || next == STUN_PROBE || next == UDP_PUNCH;
      };
   }

   public static synchronized void transitionTo(ConnectionState newState, String detail) {
      ConnectionState oldState = currentState;
      if (oldState != newState) {
         if (!oldState.canTransitionTo(newState)) {
            VoxLinkMod.LOGGER.warn("[ConnState] Invalid state transition: {} -> {} (detail={})", new Object[]{oldState, newState, detail});
            if (!newState.isTerminal()) {
               return;
            }
         }

         long duration = System.currentTimeMillis() - stateEnterTime;
         VoxLinkMod.LOGGER.info("[ConnState] {} -> {} ({}ms) {}", new Object[]{oldState.displayName, newState.displayName, duration, detail});
         currentState = newState;
         stateEnterTime = System.currentTimeMillis();
      }
   }

   public static ConnectionState getCurrent() {
      return currentState;
   }

   public static long getStateEnterTime() {
      return stateEnterTime;
   }

   public static long getStateDurationMs() {
      return System.currentTimeMillis() - stateEnterTime;
   }

   public static void reset() {
      currentState = IDLE;
      stateEnterTime = System.currentTimeMillis();
   }
}
