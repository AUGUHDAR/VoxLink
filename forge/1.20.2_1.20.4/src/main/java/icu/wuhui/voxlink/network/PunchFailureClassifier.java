package icu.wuhui.voxlink.network;

public final class PunchFailureClassifier {
   private PunchFailureClassifier() {
   }

   public static PunchFailureClassifier.FailureReason classify(PunchResult result) {
      if (result == null) {
         return PunchFailureClassifier.FailureReason.NO_RESPONSE;
      } else if (result.portPredictionActive && result.socketsReceivedPunch == 0 && result.socketsReceivedAck == 0 && !result.firewallDetected) {
         return PunchFailureClassifier.FailureReason.PREDICTION_OFF;
      } else if (result.predictionDelta > 100) {
         return PunchFailureClassifier.FailureReason.PREDICTION_OFF;
      } else if (result.socketsReceivedPunch == 0 && result.socketsReceivedAck == 0) {
         return result.firewallDetected ? PunchFailureClassifier.FailureReason.FIREWALL_DETECTED : PunchFailureClassifier.FailureReason.NO_RESPONSE;
      } else if (result.socketsReceivedPunch > 0 && result.socketsReceivedAck == 0) {
         return PunchFailureClassifier.FailureReason.RESPONSE_NO_ACK;
      } else if (result.socketsReceivedAck > 0 && result.successSocket == null) {
         return PunchFailureClassifier.FailureReason.ACK_TIMEOUT;
      } else {
         return result.socketsReceivedPunch > 0 && result.socketsReceivedPunch < result.socketsTried
            ? PunchFailureClassifier.FailureReason.PARTIAL_SUCCESS
            : PunchFailureClassifier.FailureReason.NO_RESPONSE;
      }
   }

   public enum FailureReason {
      NO_RESPONSE,
      RESPONSE_NO_ACK,
      ACK_TIMEOUT,
      PARTIAL_SUCCESS,
      PREDICTION_OFF,
      FIREWALL_DETECTED;
   }
}
