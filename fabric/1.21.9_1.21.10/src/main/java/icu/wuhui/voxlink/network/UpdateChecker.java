package icu.wuhui.voxlink.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import icu.wuhui.voxlink.VoxLinkMod;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class UpdateChecker {
   private static final String RELEASES_API = "https://api.github.com/repos/AUGUHDAR/VoxLink/releases/latest";
   private static final int CONNECT_TIMEOUT_SEC = 10;
   private static final int REQUEST_TIMEOUT_SEC = 15;

   private UpdateChecker() {
   }

   public static void checkOnce() {
      if (VoxLinkMod.getConfig().isUpdateCheckEnabled()) {
         HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10L)).build();
         HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com/repos/AUGUHDAR/VoxLink/releases/latest"))
            .timeout(Duration.ofSeconds(15L))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "VoxLink-UpdateChecker")
            .GET()
            .build();
         client.sendAsync(req, BodyHandlers.ofString()).thenAccept(resp -> {
            if (resp.statusCode() != 200) {
               VoxLinkMod.LOGGER.warn("Update check HTTP status: {}", resp.statusCode());
            } else {
               try {
                  JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                  String tagName = json.get("tag_name").getAsString();
                  String latest = stripPrefix(tagName);
                  String current = stripMCVersion(VoxLinkMod.MOD_VERSION);
                  if (isNewer(latest, current)) {
                     String url = json.has("html_url") ? json.get("html_url").getAsString() : "https://github.com/AUGUHDAR/VoxLink/releases";
                     notifyUpdate(latest, url);
                  }
               } catch (Exception e) {
                  VoxLinkMod.LOGGER.warn("Update check parse failed: {}", e.getMessage());
               }
            }
         }).exceptionally(e -> {
            VoxLinkMod.LOGGER.warn("Update check failed: {}", e.getMessage());
            return null;
         });
      }
   }

   private static String stripPrefix(String tag) {
      if (tag == null) {
         return "";
      }

      String s = tag.trim();
      if (s.startsWith("v") || s.startsWith("V")) {
         s = s.substring(1);
      }

      return s;
   }

   private static String stripMCVersion(String modVersion) {
      if (modVersion == null) {
         return "";
      }

      int dash = modVersion.indexOf(45);
      return dash > 0 ? modVersion.substring(0, dash) : modVersion;
   }

   private static boolean isNewer(String latest, String current) {
      int[] l = parseSemver(latest);
      int[] c = parseSemver(current);

      for (int i = 0; i < Math.max(l.length, c.length); i++) {
         int lv = i < l.length ? l[i] : 0;
         int cv = i < c.length ? c[i] : 0;
         if (lv > cv) {
            return true;
         }

         if (lv < cv) {
            return false;
         }
      }

      return false;
   }

   private static int[] parseSemver(String v) {
      if (v != null && !v.isEmpty()) {
         String[] parts = v.split("\\.");
         int[] arr = new int[parts.length];

         for (int i = 0; i < parts.length; i++) {
            try {
               arr[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
               arr[i] = 0;
            }
         }

         return arr;
      } else {
         return new int[]{0};
      }
   }

   private static void notifyUpdate(String latest, String url) {
      Minecraft mc = Minecraft.getInstance();
      if (mc != null) {
         mc.execute(
            () -> {
               if (mc.player != null) {
                  mc.player
                     .displayClientMessage(

                        Component.translatable("voxlink.chat.error_prefix")
                           .withStyle(ChatFormatting.AQUA)
                           .append(Component.translatable("voxlink.update.available", new Object[]{latest}))
                     
, false);
                  mc.player.displayClientMessage(Component.literal(url).withStyle(new ChatFormatting[]{ChatFormatting.UNDERLINE, ChatFormatting.BLUE}), false);
               }
            }
         );
      }
   }
}
