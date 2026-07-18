package icu.wuhui.voxlink.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import icu.wuhui.voxlink.VoxLinkMod;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UpdateChecker {
    private static final String RELEASES_API = "https://api.github.com/repos/AUGUHDAR/VoxLink/releases/latest";
    private static final int CONNECT_TIMEOUT_SEC = 10;
    private static final int REQUEST_TIMEOUT_SEC = 15;
    private static final AtomicBoolean checked = new AtomicBoolean(false);

    private UpdateChecker() {}

    //进世界时调用一次
    public static void checkOnce() {
        if (!VoxLinkMod.getConfig().isUpdateCheckEnabled()) return;
        if (!checked.compareAndSet(false, true)) return;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(RELEASES_API))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "VoxLink-UpdateChecker")
                .GET()
                .build();

        client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() != 200) {
                        VoxLinkMod.LOGGER.warn("更新检查HTTP状态: {}", resp.statusCode());
                        return;
                    }
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
                        VoxLinkMod.LOGGER.warn("更新检查解析失败: {}", e.getMessage());
                    }
                })
                .exceptionally(e -> {
                    VoxLinkMod.LOGGER.warn("更新检查失败: {}", e.getMessage());
                    return null;
                });
    }

    private static String stripPrefix(String tag) {
        if (tag == null) return "";
        String s = tag.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        return s;
    }

    //1.0.4-1.20 -> 1.0.4
    private static String stripMCVersion(String modVersion) {
        if (modVersion == null) return "";
        int dash = modVersion.indexOf('-');
        return dash > 0 ? modVersion.substring(0, dash) : modVersion;
    }

    //语义版本比较: latest > current 返回true
    private static boolean isNewer(String latest, String current) {
        int[] l = parseSemver(latest);
        int[] c = parseSemver(current);
        for (int i = 0; i < Math.max(l.length, c.length); i++) {
            int lv = i < l.length ? l[i] : 0;
            int cv = i < c.length ? c[i] : 0;
            if (lv > cv) return true;
            if (lv < cv) return false;
        }
        return false;
    }

    private static int[] parseSemver(String v) {
        if (v == null || v.isEmpty()) return new int[]{0};
        String[] parts = v.split("\\.");
        int[] arr = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { arr[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException e) { arr[i] = 0; }
        }
        return arr;
    }

    private static void notifyUpdate(String latest, String url) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (mc.player == null) return;
            mc.player.sendSystemMessage(
                    Component.literal("[VoxLink] ").withStyle(ChatFormatting.AQUA)
                            .append(Component.translatable("voxlink.update.available", latest)));
            mc.player.sendSystemMessage(
                    Component.literal(url).withStyle(ChatFormatting.UNDERLINE, ChatFormatting.BLUE));
        });
    }
}
