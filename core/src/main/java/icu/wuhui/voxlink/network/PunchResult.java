package icu.wuhui.voxlink.network;

import java.net.DatagramSocket;

public final class PunchResult {
   public final DatagramSocket successSocket;
   public final int socketsTried;
   public final int socketsReceivedPunch;
   public final int socketsReceivedAck;
   public final int predictionDelta;
   public final long elapsedMs;
   public final boolean firewallDetected;
   public final boolean portPredictionActive;
   public final PunchFailureClassifier.FailureReason reason;

   private PunchResult(
      DatagramSocket socket,
      int tried,
      int recvPunch,
      int recvAck,
      int delta,
      long elapsed,
      boolean firewall,
      boolean portPrediction,
      PunchFailureClassifier.FailureReason reason
   ) {
      this.successSocket = socket;
      this.socketsTried = tried;
      this.socketsReceivedPunch = recvPunch;
      this.socketsReceivedAck = recvAck;
      this.predictionDelta = delta;
      this.elapsedMs = elapsed;
      this.firewallDetected = firewall;
      this.portPredictionActive = portPrediction;
      this.reason = reason;
   }

   public static PunchResult success(DatagramSocket socket, int tried, int recvPunch, int recvAck, int delta, long elapsed) {
      return new PunchResult(socket, tried, recvPunch, recvAck, delta, elapsed, false, false, null);
   }

   public static PunchResult failure(int tried, int recvPunch, int recvAck, int delta, long elapsed, boolean firewall) {
      return new PunchResult(null, tried, recvPunch, recvAck, delta, elapsed, firewall, false, null);
   }

   public boolean isSuccess() {
      return this.successSocket != null;
   }

   public DatagramSocket getSuccessSocket() {
      return this.successSocket;
   }

   public PunchResult withPortPrediction() {
      return new PunchResult(
         null,
         this.socketsTried,
         this.socketsReceivedPunch,
         this.socketsReceivedAck,
         this.predictionDelta,
         this.elapsedMs,
         this.firewallDetected,
         true,
         this.reason
      );
   }

   public PunchResult withReason(PunchFailureClassifier.FailureReason newReason) {
      return new PunchResult(
         this.successSocket,
         this.socketsTried,
         this.socketsReceivedPunch,
         this.socketsReceivedAck,
         this.predictionDelta,
         this.elapsedMs,
         this.firewallDetected,
         this.portPredictionActive,
         newReason
      );
   }
}
