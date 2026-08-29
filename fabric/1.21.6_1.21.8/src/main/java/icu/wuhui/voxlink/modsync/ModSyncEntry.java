package icu.wuhui.voxlink.modsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** 清单条目：一个房主侧必装 mod 的可下载描述（跨 WS/HTTP JSON 传递）。 */
public final class ModSyncEntry {
   public String projectId = "";
   public String slug = "";
   public String title = "";
   public String versionNumber = "";
   public String fileName = "";
   public String downloadUrl = "";
   /** 房主侧实际安装文件/主文件的 sha1（房客离线 diff 用，客户端零 Modrinth API 调用的关键）。 */
   public String sha1 = "";
   public String sha512 = "";
   public long size = 0L;
   /** 该版本支持的加载器与 MC 版本（供房客侧判断能否直接用该文件）。 */
   public java.util.List<String> loaders = new java.util.ArrayList<>();
   public java.util.List<String> gameVersions = new java.util.ArrayList<>();

   public static ModSyncEntry fromVersion(JsonObject version, JsonObject project) {
      ModSyncEntry e = new ModSyncEntry();
      if (version == null) {
         return e;
      }

      e.projectId = str(version, "project_id");
      e.versionNumber = str(version, "version_number");
      e.loaders = strList(version, "loaders");
      e.gameVersions = strList(version, "game_versions");
      if (project != null) {
         e.slug = str(project, "slug");
         e.title = str(project, "title");
      }

      JsonObject file = ModrinthClient.primaryFile(version);
      if (file != null) {
         e.fileName = str(file, "filename");
         e.downloadUrl = str(file, "url");
         e.size = file.has("size") && file.get("size").isJsonPrimitive() ? file.get("size").getAsLong() : 0L;
         if (file.has("hashes") && file.getAsJsonObject("hashes").isJsonObject()) {
            JsonObject hashes = file.getAsJsonObject("hashes");
            if (hashes.has("sha1") && !hashes.get("sha1").isJsonNull()) {
               e.sha1 = hashes.get("sha1").getAsString();
            }

            if (hashes.has("sha512") && !hashes.get("sha512").isJsonNull()) {
               e.sha512 = hashes.get("sha512").getAsString();
            }
         }
      }

      if (e.title == null || e.title.isBlank()) {
         String guess = guessTitleFromFileName(e.fileName);
         e.title = !guess.isEmpty() ? guess : (!e.slug.isEmpty() ? e.slug : e.projectId);
      }

      return e;
   }

   /** 从文件名推断可读标题：去 .jar、掐掉版本号段与加载器后缀、连字符转空格。 */
   static String guessTitleFromFileName(String fileName) {
      if (fileName == null || fileName.isEmpty()) {
         return "";
      }

      String s = fileName;
      int i = s.toLowerCase(java.util.Locale.ROOT).lastIndexOf(".jar");
      if (i > 0) {
         s = s.substring(0, i);
      }

      s = s.replaceAll("(?i)[-_. ](mc)?[0-9]+([._-][0-9]+)+.*$", "");
      s = s.replaceAll("(?i)[-_. ]?(fabric|forge|neoforge|fml|quilt)([-_. ].*)?$", "");
      s = s.replace('-', ' ').replace('_', ' ').trim();
      return s;
   }

   public JsonObject toJson() {
      JsonObject o = new JsonObject();
      o.addProperty("projectId", projectId);
      o.addProperty("slug", slug);
      o.addProperty("title", title);
      o.addProperty("versionNumber", versionNumber);
      o.addProperty("fileName", fileName);
      o.addProperty("url", downloadUrl);
      o.addProperty("sha1", sha1);
      o.addProperty("sha512", sha512);
      o.addProperty("size", size);
      JsonArray ld = new JsonArray();
      loaders.forEach(ld::add);
      o.add("loaders", ld);
      JsonArray gv = new JsonArray();
      gameVersions.forEach(gv::add);
      o.add("gameVersions", gv);
      return o;
   }

   public static ModSyncEntry fromJson(JsonObject o) {
      ModSyncEntry e = new ModSyncEntry();
      e.projectId = str(o, "projectId");
      e.slug = str(o, "slug");
      e.title = str(o, "title");
      if (e.title.isBlank()) {
         e.title = e.slug.isEmpty() ? e.projectId : e.slug;
      }

      e.versionNumber = str(o, "versionNumber");
      e.fileName = str(o, "fileName");
      e.downloadUrl = str(o, "url");
      e.sha1 = str(o, "sha1");
      e.sha512 = str(o, "sha512");
      e.size = o.has("size") && o.get("size").isJsonPrimitive() ? o.get("size").getAsLong() : 0L;
      e.loaders = strList(o, "loaders");
      e.gameVersions = strList(o, "gameVersions");
      return e;
   }

   private static String str(JsonObject o, String key) {
      return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
   }

   private static java.util.List<String> strList(JsonObject o, String key) {
      java.util.List<String> out = new java.util.ArrayList<>();
      if (o.has(key) && o.get(key).isJsonArray()) {
         for (com.google.gson.JsonElement el : o.getAsJsonArray(key)) {
            if (el.isJsonPrimitive()) {
               out.add(el.getAsString());
            }
         }
      }

      return out;
   }
}
