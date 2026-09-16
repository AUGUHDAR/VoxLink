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
   /**
    * 扫描 mods 目录的硬上限。绝大多数整合包在此范围内；超过则截断并 LOGGER.warn 提示，
    * 避免极少数巨型整合包静默丢校验。仅防御 .jar / .jar.disabled 数量；.part 等不入扫描。
    */
   private static final int MAX_JARS = 1024;
   private static final int HASH_BUF = 64 * 1024;

   private ModSyncFileHasher() {
   }

   /**
    * 列出 mods 目录下的 .jar（含玩家主动禁用的 .jar.disabled），按文件名排序、数量封顶，
    * 跳过 .part 与子目录。返回 disabled 文件名集合供调用方判定"安装了但被禁用"。
    */
   public static ListModJarsResult listModJarsWithDisabled() {
      List<Path> jars = new ArrayList<>();
      java.util.Set<String> disabled = new java.util.HashSet<>();
      Path dir = ModSyncEnv.getModsDir();
      if (Files.isDirectory(dir)) {
         try (Stream<Path> stream = Files.list(dir)) {
            List<Path> all = stream
               .filter(Files::isRegularFile)
               .filter(p -> {
                  String name = p.getFileName().toString();
                  String low = name.toLowerCase(java.util.Locale.ROOT);
                  if (low.endsWith(".part")) {
                     return false;
                  }

                  if (low.endsWith(".jar.disabled")) {
                     return true;
                  }

                  if (low.endsWith(".disabled")) {
                     // 其他扩展名的 .disabled 跳过（不是 jar 衍生）
                     return false;
                  }

                  return low.endsWith(".jar");
               })
               .sorted(Comparator.comparing(pp -> pp.getFileName().toString()))
               .collect(java.util.stream.Collectors.toList());
            for (Path p : all) {
               String name = p.getFileName().toString();
               if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar.disabled")) {
                  // 记下原始 .jar 文件名（去掉 .disabled 末尾）便于与清单比对
                  String jarName = name.substring(0, name.length() - ".disabled".length());
                  disabled.add(jarName);
               } else {
                  jars.add(p);
               }
            }
         } catch (IOException e) {
            ModSyncLog.warn("listModJars failed: {}", e.getMessage());
         }
      }

      if (jars.size() + disabled.size() > MAX_JARS) {
         ModSyncLog.warn(
            "mods 数量超过 {}，超出部分未参与校验 (jars={}, disabled={})",
            new Object[]{MAX_JARS, jars.size(), disabled.size()}
         );
         // 仅截断活跃 jar（disabled 不参与哈希），保留 disabled 信息
         if (jars.size() > MAX_JARS) {
            jars = new ArrayList<>(jars.subList(0, MAX_JARS));
         }
      }

      return new ListModJarsResult(jars, disabled);
   }

   /** 旧接口：仅返回 .jar 列表（不包含 .jar.disabled），保留向后兼容。 */
   public static List<Path> listModJars() {
      return listModJarsWithDisabled().jars;
   }

   /** 扫描结果：活跃 .jar 列表 + 玩家主动禁用的 jar 文件名集合（原始 .jar 命名）。 */
   public static final class ListModJarsResult {
      public final List<Path> jars;
      public final java.util.Set<String> disabled;

      public ListModJarsResult(List<Path> jars, java.util.Set<String> disabled) {
         this.jars = jars;
         this.disabled = disabled;
      }
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
