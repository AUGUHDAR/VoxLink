package icu.wuhui.voxlink.modsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ModSyncEntry {
   public String projectId = "";
   public String slug = "";
   public String title = "";
   public String versionNumber = "";
   public String fileName = "";
   public String downloadUrl = "";
   public String sha1 = "";
   public String sha512 = "";
   public long size = 0L;
   public List<String> loaders = new ArrayList<>();
   public List<String> gameVersions = new ArrayList<>();

   public static ModSyncEntry fromVersion(JsonObject version, JsonObject project) {
      ModSyncEntry e = new ModSyncEntry();
      if (version == null) {
         return e;
      } else {
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
   }

   static String guessTitleFromFileName(String fileName) {
      if (fileName != null && !fileName.isEmpty()) {
         String s = fileName;
         int i = fileName.toLowerCase(Locale.ROOT).lastIndexOf(".jar");
         if (i > 0) {
            s = fileName.substring(0, i);
         }

         s = s.replaceAll("(?i)[-_. ](mc)?[0-9]+([._-][0-9]+)+.*$", "");
         s = s.replaceAll("(?i)[-_. ]?(fabric|forge|neoforge|fml|quilt)([-_. ].*)?$", "");
         return s.replace('-', ' ').replace('_', ' ').trim();
      } else {
         return "";
      }
   }

   public JsonObject toJson() {
      JsonObject o = new JsonObject();
      o.addProperty("projectId", this.projectId);
      o.addProperty("slug", this.slug);
      o.addProperty("title", this.title);
      o.addProperty("versionNumber", this.versionNumber);
      o.addProperty("fileName", this.fileName);
      o.addProperty("url", this.downloadUrl);
      o.addProperty("sha1", this.sha1);
      o.addProperty("sha512", this.sha512);
      o.addProperty("size", this.size);
      JsonArray ld = new JsonArray();
      this.loaders.forEach(ld::add);
      o.add("loaders", ld);
      JsonArray gv = new JsonArray();
      this.gameVersions.forEach(gv::add);
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

   private static List<String> strList(JsonObject o, String key) {
      List<String> out = new ArrayList<>();
      if (o.has(key) && o.get(key).isJsonArray()) {
         for (JsonElement el : o.getAsJsonArray(key)) {
            if (el.isJsonPrimitive()) {
               out.add(el.getAsString());
            }
         }
      }

      return out;
   }
}
