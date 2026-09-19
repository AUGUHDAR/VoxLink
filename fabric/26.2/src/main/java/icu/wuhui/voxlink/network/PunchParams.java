package icu.wuhui.voxlink.network;

public final class PunchParams {
   public int portRange;
   public int timeoutMs;
   public int sendInterval;
   public int ackRetries;
   public boolean skipDirectPunch;
   public boolean reuseSuccessfulSockets;
   public int[] successfulPortRange;
   public int sendMinRounds;
   public int sendMinPass;
   // 对称NAT分级开关
   public boolean hardSymSpray;
   public int sprayPortCountMin;
   public int sprayPortCountMax;
   public int sprayPacketsPerPort;
   public int sprayPortIntervalMs;
   public int sprayDecayNumerator;
   public int sprayDecayFloor;
   public boolean easySymBomb;
   public int bombWindow;
   public int bombRoundIntervalMs;
   public int bombDurationMs;

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
      this.sendMinRounds = 0;
      this.sendMinPass = 0;
   }

   // 拷贝构造: 防共享实例被改写
   public PunchParams(PunchParams src) {
      this.portRange = src.portRange;
      this.timeoutMs = src.timeoutMs;
      this.sendInterval = src.sendInterval;
      this.ackRetries = src.ackRetries;
      this.skipDirectPunch = src.skipDirectPunch;
      this.reuseSuccessfulSockets = src.reuseSuccessfulSockets;
      this.successfulPortRange = src.successfulPortRange;
      this.sendMinRounds = src.sendMinRounds;
      this.sendMinPass = src.sendMinPass;
      this.hardSymSpray = src.hardSymSpray;
      this.sprayPortCountMin = src.sprayPortCountMin;
      this.sprayPortCountMax = src.sprayPortCountMax;
      this.sprayPacketsPerPort = src.sprayPacketsPerPort;
      this.sprayPortIntervalMs = src.sprayPortIntervalMs;
      this.sprayDecayNumerator = src.sprayDecayNumerator;
      this.sprayDecayFloor = src.sprayDecayFloor;
      this.easySymBomb = src.easySymBomb;
      this.bombWindow = src.bombWindow;
      this.bombRoundIntervalMs = src.bombRoundIntervalMs;
      this.bombDurationMs = src.bombDurationMs;
   }

   public static PunchParams fromProfile(PunchProfile profile) {
      PunchParams p = new PunchParams(profile.portPredictionMaxRange, profile.punchTimeoutMs, profile.send.intervalMs, 1, false, false, null);
      PunchProfile.SymParams s = profile.sym;
      if (s != null) {
       p.sprayPortCountMin = s.hardSymSprayPortMin;       p.sprayPortCountMax = s.hardSymSprayPortMax;       p.sprayPacketsPerPort = s.hardSymPacketsPerPort;       p.sprayPortIntervalMs = s.hardSymPortIntervalMs;       p.sprayDecayNumerator = s.hardSymDecayNumerator;       p.sprayDecayFloor = s.hardSymDecayFloor;       p.bombWindow = s.easySymBombWindow;       p.bombRoundIntervalMs = s.easySymRoundIntervalMs;       p.bombDurationMs = s.easySymBombDurationMs;      }
      if (profile == PunchProfile.HARDSYM) {
         p.hardSymSpray = true;
      }

      if (profile == PunchProfile.EASY_SYM_DUAL) {
         p.easySymBomb = true;
      }

      return p;
   }
}
