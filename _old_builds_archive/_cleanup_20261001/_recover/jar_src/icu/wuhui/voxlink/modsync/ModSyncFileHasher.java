package icu.wuhui.voxlink.modsync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ModSyncFileHasher {
   private static final int MAX_JARS = 1024;
   private static final int HASH_BUF = 65536;

   private ModSyncFileHasher() {
   }

   public static ModSyncFileHasher.ListModJarsResult listModJarsWithDisabled() {
      List<Path> jars = new ArrayList<>();
      Set<String> disabled = new HashSet<>();
      Path dir = ModSyncEnv.getModsDir();
      if (Files.isDirectory(dir)) {
         try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : stream.filter(x$0 -> Files.isRegularFile(x$0)).filter(px -> {
               String namex = px.getFileName().toString();
               String low = namex.toLowerCase(Locale.ROOT);
               if (low.endsWith(".part")) {
                  return false;
               } else if (low.endsWith(".jar.disabled")) {
                  return true;
               } else {
                  return low.endsWith(".disabled") ? false : low.endsWith(".jar");
               }
            }).sorted(Comparator.comparing(pp -> pp.getFileName().toString())).collect(Collectors.toList())) {
               String name = p.getFileName().toString();
               if (name.toLowerCase(Locale.ROOT).endsWith(".jar.disabled")) {
                  String jarName = name.substring(0, name.length() - ".disabled".length());
                  disabled.add(jarName);
               } else {
                  jars.add(p);
               }
            }
         } catch (IOException var11) {
            ModSyncLog.warn("listModJars failed: {}", var11.getMessage());
         }
      }

      if (jars.size() + disabled.size() > 1024) {
         ModSyncLog.warn("mods 数量超过 {}，超出部分未参与校验 (jars={}, disabled={})", 1024, jars.size(), disabled.size());
         if (jars.size() > 1024) {
            jars = new ArrayList<>(jars.subList(0, 1024));
         }
      }

      return new ModSyncFileHasher.ListModJarsResult(jars, disabled);
   }

   public static List<Path> listModJars() {
      return listModJarsWithDisabled().jars;
   }

   public static String sha1(Path file) throws IOException {
      return hash(file, "SHA-1");
   }

   public static String sha512(Path file) throws IOException {
      return hash(file, "SHA-512");
   }

   public static String sha1OfBytes(byte[] data) {
      try {
         MessageDigest digest = MessageDigest.getInstance("SHA-1");
         StringBuilder sb = new StringBuilder(digest.getDigestLength() * 2);

         for (byte b : digest.digest(data)) {
            sb.append(Character.forDigit(b >> 4 & 15, 16)).append(Character.forDigit(b & 15, 16));
         }

         return sb.toString();
      } catch (Exception var7) {
         return String.valueOf(data == null ? 0 : data.length);
      }
   }

   private static String hash(Path file, String algorithm) throws IOException {
      MessageDigest digest;
      try {
         digest = MessageDigest.getInstance(algorithm);
      } catch (Exception var9) {
         throw new IOException("digest unavailable: " + algorithm, var9);
      }

      try (InputStream in = Files.newInputStream(file)) {
         byte[] buf = new byte[65536];

         int n;
         while ((n = in.read(buf)) > 0) {
            digest.update(buf, 0, n);
         }
      }

      StringBuilder sb = new StringBuilder(digest.getDigestLength() * 2);

      for (byte b : digest.digest()) {
         sb.append(Character.forDigit(b >> 4 & 15, 16)).append(Character.forDigit(b & 15, 16));
      }

      return sb.toString();
   }

   public static final class ListModJarsResult {
      public final List<Path> jars;
      public final Set<String> disabled;

      public ListModJarsResult(List<Path> jars, Set<String> disabled) {
         this.jars = jars;
         this.disabled = disabled;
      }
   }
}
