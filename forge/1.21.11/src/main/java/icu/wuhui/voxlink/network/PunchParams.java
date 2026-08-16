package icu.wuhui.voxlink.network;

public final class PunchParams {
   public int portRange;
   public int timeoutMs;
   public int sendInterval;
   public int ackRetries;
   public boolean skipDirectPunch;
   public boolean reuseSuccessfulSockets;
   public int[] successfulPortRange;

   public PunchParams(
      int portRange, int timeoutMs, int sendInterval, int ackRetries, boolean skipDirectPunch, boolean reuseSuccessfulSockets, int[] successfulPortRange
   ) {
      this.portRange = portRange;
      this.timeoutMs = timeoutMs;
      this.sendInterval = sendInterval;
      this.ackRetries = ackRetries;
      this.skipDirectPunch = skipDirectPunch;
      this.reuseSuccessfulSockets = reuseSuccessfulSockets;
      this.successfulPortRange = successfulPortRange;
   }

   public static PunchParams fromProfile(PunchProfile profile) {
      return new PunchParams(profile.portPredictionMaxRange, profile.punchTimeoutMs, profile.send.intervalMs, 1, false, false, null);
   }
}
