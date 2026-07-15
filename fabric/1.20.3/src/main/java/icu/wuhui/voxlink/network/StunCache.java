package icu.wuhui.voxlink.network;

import com.google.gson.*;
import icu.wuhui.voxlink.VoxLinkMod;
import icu.wuhui.voxlink.room.StunDetector;
import net.minecraft.client.Minecraft;

import javax.naming.Context;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 磁盘持久化 STUN 缓存，参考 QQ stun.dat。
 * 缓存 NAT 类型和映射地址，避免每次启动都重探，节省 2-5 秒。
 * 有效期 24h，网络切换时自动失效。
 */
public class StunCache {

    private static final long CACHE_TTL_MS = 24L * 60 * 60 * 1000; // 24h
    private static final String CACHE_FILE = "stun_cache.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class Entry {
        public String natType;
        public String mappedIp;
        public int mappedPort;
        public List<String> stunUrls;
        public long timestamp;
        public String networkFingerprint; // 网络指纹，用于检测网络切换

        public Entry() {}

        public Entry(String natType, String mappedIp, int mappedPort,
                     List<String> stunUrls, String networkFingerprint) {
            this.natType = natType;
            this.mappedIp = mappedIp;
            this.mappedPort = mappedPort;
            this.stunUrls = stunUrls;
            this.timestamp = System.currentTimeMillis();
            this.networkFingerprint = networkFingerprint;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    private static Path getCachePath() {
        File gameDir = Minecraft.getInstance().gameDirectory;
        File voxlinkDir = new File(gameDir, "voxlink");
        if (!voxlinkDir.exists()) voxlinkDir.mkdirs();
        return new File(voxlinkDir, CACHE_FILE).toPath();
    }

    /** 生成网络指纹：用本地IP+网关MAC(如有)判断网络是否切换 */
    private static String buildNetworkFingerprint() {
        try {
            StringBuilder sb = new StringBuilder();
            java.util.Enumeration<java.net.NetworkInterface> nis = java.net.NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                byte[] mac = ni.getHardwareAddress();
                if (mac != null) {
                    sb.append(ni.getName());
                    for (byte b : mac) sb.append(String.format("%02x", b));
                }
            }
            return sb.length() > 0 ? sb.toString() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    public static Entry load() {
        try {
            Path path = getCachePath();
            if (!Files.exists(path)) return null;
            String json = Files.readString(path, StandardCharsets.UTF_8);
            Entry entry = GSON.fromJson(json, Entry.class);
            if (entry == null || entry.isExpired()) {
                VoxLinkMod.LOGGER.info("[StunCache] 缓存已过期或无效");
                return null;
            }
            String currentFingerprint = buildNetworkFingerprint();
            if (!currentFingerprint.equals(entry.networkFingerprint)) {
                VoxLinkMod.LOGGER.info("[StunCache] 网络环境变了，缓存失效");
                return null;
            }
            VoxLinkMod.LOGGER.info("[StunCache] 命中: NAT={}, mapped={}:{}, 年龄={}h",
                    entry.natType, entry.mappedIp, entry.mappedPort,
                    (System.currentTimeMillis() - entry.timestamp) / 3600000);
            return entry;
        } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[StunCache] 读取失败: {}", e.getMessage());
            return null;
        }
    }

    public static void save(String natType, String mappedIp, int mappedPort, List<String> stunUrls) {
        try {
            Entry entry = new Entry(natType, mappedIp, mappedPort, stunUrls, buildNetworkFingerprint());
            Path path = getCachePath();
            Files.writeString(path, GSON.toJson(entry), StandardCharsets.UTF_8);
            VoxLinkMod.LOGGER.info("[StunCache] 已保存: NAT={}, mapped={}:{}" , natType, mappedIp, mappedPort);
        } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[StunCache] 保存失败: {}", e.getMessage());
        }
    }

    public static void clear() {
        try {
            Files.deleteIfExists(getCachePath());
        } catch (Exception ignored) {}
    }

    public static List<String> getHardcodedServers() {
        return new ArrayList<>(StunDetector.getAllStunUrls());
    }

    //DNS发现
    public static List<String> discoverFromDnsTxt(String domain) {
        List<String> found = new ArrayList<>();
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
            InitialDirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes("_stun._udp." + domain, new String[]{"TXT"});
            Attribute txt = attrs.get("TXT");
            if (txt != null) {
                for (int i = 0; i < txt.size(); i++) {
                    String parsed = parseStunTxtRecord(String.valueOf(txt.get(i)));
                    if (parsed != null && !found.contains(parsed)) found.add(parsed);
                }
            }
            ctx.close();
        } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[StunCache] DNS TXT 查询失败: {}", e.getMessage());
        }
        return found;
    }

    //解析 "stun:host:port" 或 "stun=host" 为 stun:url
    private static String parseStunTxtRecord(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.isEmpty()) return null;
        int idx = s.indexOf(':');
        int eq = s.indexOf('=');
        String hostPart = null;
        if (idx >= 0 && s.substring(0, idx).equalsIgnoreCase("stun")) {
            hostPart = s.substring(idx + 1).trim();
        } else if (eq >= 0 && s.substring(0, eq).equalsIgnoreCase("stun")) {
            hostPart = s.substring(eq + 1).trim();
        }
        if (hostPart == null || hostPart.isEmpty()) return null;
        return hostPart.startsWith("stun:") ? hostPart : "stun:" + hostPart;
    }

    //合并去重
    public static List<String> getMergedStunServers() {
        List<String> result = new ArrayList<>(getHardcodedServers());
        try {
            List<String> dns = discoverFromDnsTxt("voxlink.icu");
            for (String s : dns) {
                if (!result.contains(s)) result.add(s); //去重
            }
            if (!dns.isEmpty()) {
                VoxLinkMod.LOGGER.info("[StunCache] DNS发现{}个STUN, 合并后共{}个", dns.size(), result.size());
            }
        } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("[StunCache] DNS TXT 发现失败, 仅用硬编码列表: {}", e.getMessage());
        }
        return result;
    }
}
