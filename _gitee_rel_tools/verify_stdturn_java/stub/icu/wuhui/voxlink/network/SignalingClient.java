package icu.wuhui.voxlink.network;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

// 桩：验证编译用（生产为完整信令客户端）
public class SignalingClient {
   public static class ApiResponse {
      public boolean success;
      public JsonObject data;
   }

   public CompletableFuture<ApiResponse> relayStdTurnCred(JsonObject body) {
      return CompletableFuture.completedFuture(null);
   }
}
