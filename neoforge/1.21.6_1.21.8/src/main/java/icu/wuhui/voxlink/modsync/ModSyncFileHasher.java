package icu.wuhui.voxlink.modsync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** mods 目录扫描与文件哈希（纯 JDK，无加载器 API）。 */
public final class ModSyncFileHasher {
   private static final int MAX_JARS = 256;
   private static final int HASH_BUF = 64 * 1024;

   private ModSyncFileHasher() {
   }

   /** 列出 mods 目录下的 .jar（按文件名排序、数量封顶），跳过 .part/.disabled 与子目录。 */
   public static List<Path> listModJars() {
      List<Path> out = new ArrayList<>();
      Path dir = ModSyncEnv.getModsDir();
      if (!Files.isDirectory(dir)) {
         return out;
      }

      try (Stream<Path> stream = Files.list(dir)) {
         stream
            .filter(Files::isRegularFile)
            .filter(p -> {
               String name = p.getFileName().toString().toLowerCase();
               return name.endsWith(".jar") && !name.endsWith(".disabled") && !name.endsWith(".part");
            })
            .sorted(Comparator.comparing(p -> p.getFileName().toString()))
            .limit(MAX_JARS)
            .forEach(out::add);
      } catch (IOException e) {
         ModSyncLog.warn("listModJars failed: {}", e.getMessage());
      }

      return out;
   }

   public static String sha1(Path file) throws IOException {
      return hash(file, "SHA-1");
   }

   public static String sha512(Path file) throws IOException {
      return hash(file, "SHA-512");
   }

   private static String hash(Path file, String algorithm) throws IOException {
      MessageDigest digest;
      try {
         digest = MessageDigest.getInstance(algorithm);
      } catch (Exception e) {
         throw new IOException("digest unavailable: " + algorithm, e);
      }

      try (var in = Files.newInputStream(file)) {
         byte[] buf = new byte[HASH_BUF];
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
}
