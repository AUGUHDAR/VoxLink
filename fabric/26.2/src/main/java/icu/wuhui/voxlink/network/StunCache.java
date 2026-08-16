package icu.wuhui.voxlink.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import icu.wuhui.voxlink.VoxLinkMod;
import java.io.File;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import net.minecraft.client.Minecraft;

public class StunCache {
   private static final long CACHE_TTL_MS = 86400000L;
   private static final String CACHE_FILE = "stun_cache.json";
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

   private static Path getCachePath() {
      File gameDir = Minecraft.getInstance().gameDirectory;
      File voxlinkDir = new File(gameDir, "voxlink");
      if (!voxlinkDir.exists()) {
         voxlinkDir.mkdirs();
      }

      return new File(voxlinkDir, "stun_cache.json").toPath();
   }

   private static String buildNetworkFingerprint() {
      try {
         StringBuilder sb = new StringBuilder();
         Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();

         while (nis.hasMoreElements()) {
            NetworkInterface ni = nis.nextElement();
            if (!ni.isLoopback() && ni.isUp()) {
               byte[] mac = ni.getHardwareAddress();
               if (mac != null) {
                  sb.append(ni.getName());

                  for (byte b : mac) {
                     sb.append(String.format("%02x", b));
                  }
               }
            }
         }

         return sb.length() > 0 ? sb.toString() : "unknown";
      } catch (Exception e) {
         return "unknown";
      }
   }

   public static StunCache.Entry load() {
      try {
         Path path = getCachePath();
         if (!Files.exists(path)) {
            return null;
         }

         String json = Files.readString(path, StandardCharsets.UTF_8);
         StunCache.Entry entry = (StunCache.Entry)GSON.fromJson(json, StunCache.Entry.class);
         if (entry != null && !entry.isExpired()) {
            String currentFingerprint = buildNetworkFingerprint();
            if (!currentFingerprint.equals(entry.networkFingerprint)) {
               VoxLinkMod.LOGGER.info("[StunCache] Network changed, cache invalidated");
               return null;
            }

            VoxLinkMod.LOGGER
               .info(
                  "[StunCache] Hit: NAT={}, mapped={}:{}, age={}h",
                  new Object[]{entry.natType, entry.mappedIp, entry.mappedPort, (System.currentTimeMillis() - entry.timestamp) / 3600000L}
               );
            if (entry.mappedIp != null && !entry.mappedIp.isEmpty() && entry.mappedPort > 0) {
               return entry;
            }

            VoxLinkMod.LOGGER.info("[StunCache] mapped address empty, invalidate for re-probe");
            return null;
         } else {
            VoxLinkMod.LOGGER.info("[StunCache] Cache expired or invalid");
            return null;
         }
      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("[StunCache] Read failed: {}", e.getMessage());
         return null;
      }
   }

   public static void save(String natType, String mappedIp, int mappedPort, List<String> stunUrls) {
      if (mappedIp != null && !mappedIp.isEmpty() && mappedPort > 0) {
         try {
            StunCache.Entry entry = new StunCache.Entry(natType, mappedIp, mappedPort, stunUrls, buildNetworkFingerprint());
            Path path = getCachePath();
            Files.writeString(path, GSON.toJson(entry), StandardCharsets.UTF_8);
            VoxLinkMod.LOGGER.info("[StunCache] Saved: NAT={}, mapped={}:{}", new Object[]{natType, mappedIp, mappedPort});
         } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[StunCache] Save failed: {}", e.getMessage());
         }
      } else {
         VoxLinkMod.LOGGER.info("[StunCache] Skip save: mapped address empty");
      }
   }

   public static class Entry {
      public String natType;
      public String mappedIp;
      public int mappedPort;
      public List<String> stunUrls;
      public long timestamp;
      public String networkFingerprint;

      public Entry() {
      }

      public Entry(String natType, String mappedIp, int mappedPort, List<String> stunUrls, String networkFingerprint) {
         this.natType = natType;
         this.mappedIp = mappedIp;
         this.mappedPort = mappedPort;
         this.stunUrls = stunUrls;
         this.timestamp = System.currentTimeMillis();
         this.networkFingerprint = networkFingerprint;
      }

      public boolean isExpired() {
         return System.currentTimeMillis() - this.timestamp > 86400000L;
      }
   }
}
