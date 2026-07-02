package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.VoxLinkMod;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class StunProbe {

    public enum NatType {
        UNKNOWN("unknown"),
        FULL_CONE("full_cone"),
        RESTRICTED_CONE("restricted_cone"),
        PORT_RESTRICTED_CONE("port_restricted_cone"),
        SYMMETRIC_EASY_INC("symmetric_easy_inc"),
        SYMMETRIC_EASY_DEC("symmetric_easy_dec"),
        SYMMETRIC("symmetric");

        public final String key;

        NatType(String key) {
            this.key = key;
        }

        public boolean isSymmetric() {
            return this == SYMMETRIC || this == SYMMETRIC_EASY_INC || this == SYMMETRIC_EASY_DEC;
        }

        public boolean isEasySymmetric() {
            return this == SYMMETRIC_EASY_INC || this == SYMMETRIC_EASY_DEC;
        }

        // EasyTier is_hard_sym: 不可预测端口，Sym×Sym/Sym×EasySym 才真正放弃打洞
        public boolean isHardSymmetric() {
            return this == SYMMETRIC;
        }

        public boolean canHolePunch() {
            return this != SYMMETRIC;
        }
    }

    public static class StunServerResult {
        public final String url;
        public final String host;
        public final int port;
        public final boolean reachable;
        public final long latencyMs;
        public final String mappedIp;
        public final int mappedPort;

        StunServerResult(String url, String host, int port, boolean reachable, long latencyMs, String mappedIp, int mappedPort) {
            this.url = url;
            this.host = host;
            this.port = port;
            this.reachable = reachable;
            this.latencyMs = latencyMs;
            this.mappedIp = mappedIp;
            this.mappedPort = mappedPort;
        }

        StunServerResult(String url, boolean reachable, long latencyMs, String mappedIp) {
            this(url, null, 0, reachable, latencyMs, mappedIp, 0);
        }
    }

    public static class ProbeResult {
        public final NatType natType;
        public final List<StunServerResult> serverResults;
        public final List<String> reachableStunUrls;

        ProbeResult(NatType natType, List<StunServerResult> serverResults) {
            this.natType = natType;
            this.serverResults = Collections.unmodifiableList(new ArrayList<>(serverResults));
            List<String> urls = new ArrayList<>();
            for (StunServerResult r : serverResults) {
                if (r.reachable) urls.add(r.url);
            }
            this.reachableStunUrls = Collections.unmodifiableList(urls);
        }
    }

    private static class MappedAddress {
        final String ip;
        final int port;

        MappedAddress(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }
    }

    public record PublicMappedAddress(String ip, int port) {}

    public record NatCheckResult(boolean symmetric, boolean unknown, String mappedIp1, int mappedPort1, String mappedIp2, int mappedPort2) {
        public NatCheckResult(boolean symmetric) {
            this(symmetric, false, null, 0, null, 0);
        }
        public NatCheckResult(boolean symmetric, String mappedIp1, int mappedPort1, String mappedIp2, int mappedPort2) {
            this(symmetric, false, mappedIp1, mappedPort1, mappedIp2, mappedPort2);
        }
        public static NatCheckResult unknownResult() {
            return new NatCheckResult(false, true, null, 0, null, 0);
        }
    }

    public static NatCheckResult checkNatType(DatagramSocket socket, List<String> stunUrls) {
        List<ParsedStunUrl> parsed = new ArrayList<>();
        for (String url : stunUrls) {
            ParsedStunUrl p = parseStunUrl(url);
            if (p != null) parsed.add(p);
        }
        if (parsed.size() < 2) {
            VoxLinkMod.LOGGER.info("[StunProbe] STUN服务器不够({})", parsed.size());
            return NatCheckResult.unknownResult();
        }

        int originalTimeout = -1;
        try {
            originalTimeout = socket.getSoTimeout();
            socket.setSoTimeout(DISCOVER_TIMEOUT_MS);
        } catch (Exception ignored) {}

        try {
            ParsedStunUrl first = parsed.get(0);
            ParsedStunUrl second = parsed.get(1);
            InetAddress firstAddr = InetAddress.getByName(first.host);
            InetAddress secondAddr = InetAddress.getByName(second.host);

            byte[] req1 = createBindingRequest();
            byte[] req2 = createBindingRequest();

            socket.send(new DatagramPacket(req1, req1.length, firstAddr, first.port));
            socket.send(new DatagramPacket(req2, req2.length, secondAddr, second.port));

            MappedAddress mapped1 = null;
            MappedAddress mapped2 = null;

            byte[] buf = new byte[576];
            long deadline = System.currentTimeMillis() + DISCOVER_TIMEOUT_MS * 2;

            for (int i = 0; i < 2 && System.currentTimeMillis() < deadline; i++) {
                DatagramPacket recv = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(recv);
                } catch (SocketTimeoutException e) {
                    break;
                }

                byte[] respData = new byte[recv.getLength()];
                System.arraycopy(recv.getData(), 0, respData, 0, recv.getLength());

                if (mapped1 == null) {
                    MappedAddress ma = parseBindingResponse(respData, req1);
                    if (ma != null) { mapped1 = ma; continue; }
                    ma = parseBindingResponse(respData, req2);
                    if (ma != null) { mapped2 = ma; continue; }
                } else {
                    MappedAddress ma = parseBindingResponse(respData, req2);
                    if (ma != null) { mapped2 = ma; continue; }
                    ma = parseBindingResponse(respData, req1);
                    if (ma != null) { mapped1 = ma; }
                }
            }

            if (mapped1 == null || mapped2 == null) {
                VoxLinkMod.LOGGER.info("[StunProbe] 拿不到两个映射地址 ({} / {})",
                        mapped1 != null ? mapped1.ip + ":" + mapped1.port : "null",
                        mapped2 != null ? mapped2.ip + ":" + mapped2.port : "null");
                return NatCheckResult.unknownResult();
            }

            if (!mapped1.ip.equals(mapped2.ip) || mapped1.port != mapped2.port) {
                VoxLinkMod.LOGGER.info("[StunProbe] 对称NAT ({}:{} vs {}:{})",
                        mapped1.ip, mapped1.port, mapped2.ip, mapped2.port);
                return new NatCheckResult(true, mapped1.ip, mapped1.port, mapped2.ip, mapped2.port);
            }

            VoxLinkMod.LOGGER.info("[StunProbe] 非对称NAT (映射地址 {}:{})", mapped1.ip, mapped1.port);
            return new NatCheckResult(false, mapped1.ip, mapped1.port, mapped2.ip, mapped2.port);
        } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("[StunProbe] NAT检测失败: {}", e.getMessage());
            return NatCheckResult.unknownResult();
        } finally {
            try {
                if (originalTimeout >= 0) socket.setSoTimeout(originalTimeout);
            } catch (Exception ignored) {}
        }
    }

    public static PublicMappedAddress discoverMappedAddress(DatagramSocket socket, List<String> stunUrls) {
        int originalTimeout = -1;
        try {
            originalTimeout = socket.getSoTimeout();
            socket.setSoTimeout(DISCOVER_TIMEOUT_MS);
        } catch (Exception ignored) {}
        VoxLinkMod.LOGGER.info("[StunProbe] 开始探测，{}个STUN服务器, socket port={}, timeout={}ms", stunUrls.size(), socket.getLocalPort(), DISCOVER_TIMEOUT_MS);
        try {
            int attempted = 0;
            int timeouts = 0;
            int errors = 0;
            for (String url : stunUrls) {
                try {
                    ParsedStunUrl parsed = parseStunUrl(url);
                    if (parsed == null) continue;
                    attempted++;
                    InetAddress address = InetAddress.getByName(parsed.host);
                    byte[] request = createBindingRequest();
                    DatagramPacket sendPacket = new DatagramPacket(request, request.length, address, parsed.port);
                    socket.send(sendPacket);
                    byte[] receiveBuffer = new byte[576];
                    DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    long deadline = System.currentTimeMillis() + DISCOVER_TIMEOUT_MS;
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            socket.receive(receivePacket);
                        } catch (SocketTimeoutException e) {
                            timeouts++;
                            break;
                        }
                        byte[] responseData = new byte[receivePacket.getLength()];
                        System.arraycopy(receivePacket.getData(), 0, responseData, 0, receivePacket.getLength());
                        if (responseData.length >= 20) {
                            boolean transactionMatch = true;
                            for (int i = 8; i < 20; i++) {
                                if (responseData[i] != request[i]) { transactionMatch = false; break; }
                            }
                            if (!transactionMatch) {
                                continue;
                            }
                        }
                        MappedAddress mapped = parseBindingResponse(responseData, request);
                        if (mapped != null) {
                            VoxLinkMod.LOGGER.info("[StunProbe] 映射地址: {}:{}", mapped.ip, mapped.port);
                            return new PublicMappedAddress(mapped.ip, mapped.port);
                        } else {
                            errors++;
                            VoxLinkMod.LOGGER.warn("[StunProbe] {}的响应解析不了，继续等", url);
                            continue;
                        }
                    }
                } catch (SocketTimeoutException e) {
                    timeouts++;
                    VoxLinkMod.LOGGER.debug("[StunProbe] {}探测超时: {}", url, e.getMessage());
                } catch (Exception e) {
                    errors++;
                    VoxLinkMod.LOGGER.warn("[StunProbe] {}探测失败: {}", url, e.getMessage());
                }
            }
            VoxLinkMod.LOGGER.warn("[StunProbe] 映射地址探测失败: 尝试={}, 超时={}, 错误={}", attempted, timeouts, errors);
            return null;
        } finally {
            try {
                if (originalTimeout >= 0) socket.setSoTimeout(originalTimeout);
            } catch (Exception ignored) {}
        }
    }

    //并行双STUN: 同一socket同时发两个STUN请求,单receive循环匹配,省一半时间
    public static PublicMappedAddress[] discoverMappedAddressDual(DatagramSocket socket, String stunUrl1, String stunUrl2) {
        int originalTimeout = -1;
        try {
            originalTimeout = socket.getSoTimeout();
            socket.setSoTimeout(DUAL_STUN_TIMEOUT_MS);
        } catch (Exception ignored) {}
        VoxLinkMod.LOGGER.info("[StunProbe] 并行双STUN: {} + {}, socket port={}", stunUrl1, stunUrl2, socket.getLocalPort());
        PublicMappedAddress[] results = new PublicMappedAddress[2];
        try {
            ParsedStunUrl u1 = parseStunUrl(stunUrl1);
            ParsedStunUrl u2 = parseStunUrl(stunUrl2);
            if (u1 == null || u2 == null) {
                if (u1 != null) results[0] = discoverMappedAddress(socket, java.util.List.of(stunUrl1));
                if (u2 != null) results[1] = discoverMappedAddress(socket, java.util.List.of(stunUrl2));
                return results;
            }
            byte[] req1 = createBindingRequest();
            byte[] req2 = createBindingRequest();
            InetAddress addr1 = InetAddress.getByName(u1.host);
            InetAddress addr2 = InetAddress.getByName(u2.host);
            socket.send(new DatagramPacket(req1, req1.length, addr1, u1.port));
            socket.send(new DatagramPacket(req2, req2.length, addr2, u2.port));

            byte[] buf = new byte[576];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            long deadline = System.currentTimeMillis() + DUAL_STUN_TIMEOUT_MS;
            int got = 0;
            while (got < 2 && System.currentTimeMillis() < deadline) {
                try {
                    socket.receive(pkt);
                } catch (SocketTimeoutException e) {
                    break;
                }
                byte[] data = new byte[pkt.getLength()];
                System.arraycopy(pkt.getData(), 0, data, 0, pkt.getLength());
                if (data.length < 20) continue;
                //匹配req1
                if (results[0] == null && matchTransaction(data, req1)) {
                    MappedAddress m = parseBindingResponse(data, req1);
                    if (m != null) { results[0] = new PublicMappedAddress(m.ip, m.port); got++; continue; }
                }
                //匹配req2
                if (results[1] == null && matchTransaction(data, req2)) {
                    MappedAddress m = parseBindingResponse(data, req2);
                    if (m != null) { results[1] = new PublicMappedAddress(m.ip, m.port); got++; continue; }
                }
            }
            //没拿到的降级单查
            if (results[0] == null) results[0] = discoverMappedAddress(socket, java.util.List.of(stunUrl1));
            if (results[1] == null) results[1] = discoverMappedAddress(socket, java.util.List.of(stunUrl2));
        } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("[StunProbe] 并行双STUN异常: {}", e.getMessage());
        } finally {
            try { if (originalTimeout >= 0) socket.setSoTimeout(originalTimeout); } catch (Exception ignored) {}
        }
        VoxLinkMod.LOGGER.info("[StunProbe] 双STUN结果: [0]={}, [1]={}", results[0], results[1]);
        return results;
    }

    //8并发竞速: 同socket同时发所有STUN, 取前need个成功响应, 谁先返回用谁
    //不依赖服务器顺序, 单个不可达不影响, 国内外服务器都参与竞速
    public static PublicMappedAddress[] discoverMappedAddressRace(
            DatagramSocket socket, List<String> stunUrls, int need) {
        PublicMappedAddress[] results = new PublicMappedAddress[need];
        if (stunUrls == null || stunUrls.isEmpty() || need <= 0) return results;
        int originalTimeout = -1;
        try {
            originalTimeout = socket.getSoTimeout();
            socket.setSoTimeout(DUAL_STUN_TIMEOUT_MS);
        } catch (Exception ignored) {}
        int n = stunUrls.size();
        ParsedStunUrl[] parsed = new ParsedStunUrl[n];
        byte[][] reqs = new byte[n][];
        VoxLinkMod.LOGGER.info("[StunProbe] 并行竞速{}STUN取{}个, socket port={}", n, need, socket.getLocalPort());
        int got = 0;
        try {
            for (int i = 0; i < n; i++) {
                parsed[i] = parseStunUrl(stunUrls.get(i));
                if (parsed[i] == null) continue;
                reqs[i] = createBindingRequest();
                InetAddress addr = InetAddress.getByName(parsed[i].host);
                socket.send(new DatagramPacket(reqs[i], reqs[i].length, addr, parsed[i].port));
            }
            byte[] buf = new byte[576];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            long deadline = System.currentTimeMillis() + DUAL_STUN_TIMEOUT_MS;
            while (got < need && System.currentTimeMillis() < deadline) {
                try {
                    socket.receive(pkt);
                } catch (SocketTimeoutException e) {
                    break;
                }
                byte[] data = new byte[pkt.getLength()];
                System.arraycopy(pkt.getData(), 0, data, 0, pkt.getLength());
                if (data.length < 20) continue;
                for (int i = 0; i < n; i++) {
                    if (reqs[i] == null) continue;
                    if (matchTransaction(data, reqs[i])) {
                        MappedAddress m = parseBindingResponse(data, reqs[i]);
                        if (m != null) {
                            results[got] = new PublicMappedAddress(m.ip, m.port);
                            got++;
                            VoxLinkMod.LOGGER.info("[StunProbe] 竞速第{}个: {} -> {}:{}", got, stunUrls.get(i), m.ip, m.port);
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("[StunProbe] 并行竞速异常: {}", e.getMessage());
        } finally {
            try { if (originalTimeout >= 0) socket.setSoTimeout(originalTimeout); } catch (Exception ignored) {}
        }
        VoxLinkMod.LOGGER.info("[StunProbe] 竞速完成: 取到{}/{}", got, need);
        return results;
    }

    // 修复2: 4个STUN并发, 取前2个成功响应比对, 提高对称NAT检测冗余
    // 旧逻辑仅2个STUN, 任一不可达即降级单测无法判定对称; 新逻辑4个并发容错更强
    public static PublicMappedAddress[] discoverMappedAddressQuad(
            DatagramSocket socket, String stunUrl1, String stunUrl2, String stunUrl3, String stunUrl4) {
        int originalTimeout = -1;
        try {
            originalTimeout = socket.getSoTimeout();
            socket.setSoTimeout(DUAL_STUN_TIMEOUT_MS);
        } catch (Exception ignored) {}
        String[] urls = {stunUrl1, stunUrl2, stunUrl3, stunUrl4};
        PublicMappedAddress[] results = new PublicMappedAddress[4];
        VoxLinkMod.LOGGER.info("[StunProbe] 并行4STUN: {}+{}+{}+{}, socket port={}", stunUrl1, stunUrl2, stunUrl3, stunUrl4, socket.getLocalPort());
        try {
            ParsedStunUrl[] parsed = new ParsedStunUrl[4];
            byte[][] reqs = new byte[4][];
            InetAddress[] addrs = new InetAddress[4];
            int validCount = 0;
            for (int i = 0; i < 4; i++) {
                parsed[i] = parseStunUrl(urls[i]);
                if (parsed[i] == null) continue;
                reqs[i] = createBindingRequest();
                addrs[i] = InetAddress.getByName(parsed[i].host);
                socket.send(new DatagramPacket(reqs[i], reqs[i].length, addrs[i], parsed[i].port));
                validCount++;
            }
            if (validCount == 0) return results;

            byte[] buf = new byte[576];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            long deadline = System.currentTimeMillis() + DUAL_STUN_TIMEOUT_MS;
            int got = 0;
            while (got < validCount && System.currentTimeMillis() < deadline) {
                try {
                    socket.receive(pkt);
                } catch (SocketTimeoutException e) {
                    break;
                }
                byte[] data = new byte[pkt.getLength()];
                System.arraycopy(pkt.getData(), 0, data, 0, pkt.getLength());
                if (data.length < 20) continue;
                for (int i = 0; i < 4; i++) {
                    if (results[i] != null || reqs[i] == null) continue;
                    if (matchTransaction(data, reqs[i])) {
                        MappedAddress m = parseBindingResponse(data, reqs[i]);
                        if (m != null) {
                            results[i] = new PublicMappedAddress(m.ip, m.port);
                            got++;
                        }
                        break;
                    }
                }
            }
            // 没拿到的降级单查(仅前2个, 减少时间)
            for (int i = 0; i < 2; i++) {
                if (results[i] == null && parsed[i] != null) {
                    results[i] = discoverMappedAddress(socket, java.util.List.of(urls[i]));
                }
            }
        } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("[StunProbe] 并行4STUN异常: {}", e.getMessage());
        } finally {
            try { if (originalTimeout >= 0) socket.setSoTimeout(originalTimeout); } catch (Exception ignored) {}
        }
        VoxLinkMod.LOGGER.info("[StunProbe] 4STUN结果: [0]={}, [1]={}, [2]={}, [3]={}", results[0], results[1], results[2], results[3]);
        return results;
    }

    private static boolean matchTransaction(byte[] data, byte[] request) {
        if (data.length < 20) return false;
        for (int i = 8; i < 20; i++) {
            if (data[i] != request[i]) return false;
        }
        return true;
    }

    private record ParsedStunUrl(String host, int port) {
    }

    private static final int PROBE_TIMEOUT_MS = 8000;  // 国内网络需8s
    private static final int DISCOVER_TIMEOUT_MS = 2000;  // 修复10: 1000→2000, 国内网络1s偏短易返回unknown
    private static final int DUAL_STUN_TIMEOUT_MS = 1500;
    private static final int STUN_DEFAULT_PORT = 3478;
    private static final long CACHE_TTL_MS = 300000;
    private static final ExecutorService STUN_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "VoxLink-STUN");
        t.setDaemon(true);
        return t;
    });

    public static void shutdown() {
        STUN_EXECUTOR.shutdown();
        try {
            if (!STUN_EXECUTOR.awaitTermination(2, TimeUnit.SECONDS)) {
                STUN_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            STUN_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final class CacheEntry {
        final ProbeResult result;
        final long timestamp;
        CacheEntry(ProbeResult result, long timestamp) {
            this.result = result;
            this.timestamp = timestamp;
        }
    }

    private static final AtomicReference<CacheEntry> cachedEntry = new AtomicReference<>();

    public static ProbeResult getCachedResult() {
        CacheEntry entry = cachedEntry.get();
        if (entry != null && System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) {
            return entry.result;
        }
        return null;
    }

    public static void setCachedResult(ProbeResult result) {
        cachedEntry.set(new CacheEntry(result, System.currentTimeMillis()));
    }

    public static CompletableFuture<ProbeResult> probeAsync(List<List<String>> stunGroups) {
        // 1. 内存缓存（5分钟）
        ProbeResult cached = getCachedResult();
        if (cached != null) {
            VoxLinkMod.LOGGER.info("[StunProbe] 用内存缓存: NAT={}, 可达={}", cached.natType.key, cached.reachableStunUrls.size());
            return CompletableFuture.completedFuture(cached);
        }
        // 2. 磁盘缓存（24小时）
        StunCache.Entry diskCache = StunCache.load();
        if (diskCache != null) {
            NatType nat = parseNatType(diskCache.natType);
            List<StunServerResult> results = new ArrayList<>();
            for (String url : diskCache.stunUrls) {
                results.add(new StunServerResult(url, null, 0, true, -1, diskCache.mappedIp, diskCache.mappedPort));
            }
            ProbeResult result = new ProbeResult(nat, results);
            setCachedResult(result);
            VoxLinkMod.LOGGER.info("[StunProbe] 用磁盘缓存: NAT={}, mapped={}:{}", nat.key, diskCache.mappedIp, diskCache.mappedPort);
            return CompletableFuture.completedFuture(result);
        }
        return CompletableFuture.supplyAsync(() -> {
            ProbeResult result = probe(stunGroups);
            setCachedResult(result);
            // 保存到磁盘
            if (!result.serverResults.isEmpty()) {
                StunServerResult first = result.serverResults.get(0);
                List<String> urls = new ArrayList<>();
                for (StunServerResult r : result.serverResults) {
                    if (r.reachable) urls.add(r.url);
                }
                StunCache.save(result.natType.key, first.mappedIp, first.mappedPort, urls);
            }
            return result;
        }, STUN_EXECUTOR);
    }

    private static NatType parseNatType(String key) {
        for (NatType t : NatType.values()) {
            if (t.key.equals(key)) return t;
        }
        return NatType.UNKNOWN;
    }

    public static ProbeResult probe(List<List<String>> stunGroups) {
        List<CompletableFuture<StunServerResult>> futures = new ArrayList<>();
        for (List<String> group : stunGroups) {
            for (String url : group) {
                futures.add(probeSingleServerAsync(url));
            }
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(PROBE_TIMEOUT_MS + 2000L, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            VoxLinkMod.LOGGER.warn("[StunProbe] 部分探测超时: {}", e.getMessage());
        }

        List<StunServerResult> allResults = new ArrayList<>();
        for (CompletableFuture<StunServerResult> f : futures) {
            try {
                allResults.add(f.getNow(new StunServerResult("", "", 0, false, -1, null, 0)));
            } catch (Exception ignored) {
            }
        }

        NatType natType = detectNatType(allResults);

        long reachableCount = allResults.stream().filter(r -> r.reachable).count();
        VoxLinkMod.LOGGER.info("[StunProbe] NAT={}, 可达={}/{}",
                natType.key, reachableCount, allResults.size());

        return new ProbeResult(natType, allResults);
    }

    private static CompletableFuture<StunServerResult> probeSingleServerAsync(String stunUrl) {
        return CompletableFuture.supplyAsync(() -> probeSingleServer(stunUrl));
    }

    private static StunServerResult probeSingleServer(String stunUrl) {
        ParsedStunUrl parsed = parseStunUrl(stunUrl);
        if (parsed == null) return new StunServerResult(stunUrl, false, -1, null);

        DatagramSocket socket = null;
        try {
            InetAddress address = InetAddress.getByName(parsed.host);
            socket = new DatagramSocket();
            socket.setSoTimeout(PROBE_TIMEOUT_MS);

            byte[] request = createBindingRequest();
            long startTime = System.nanoTime();

            DatagramPacket sendPacket = new DatagramPacket(request, request.length, address, parsed.port);
            socket.send(sendPacket);

            byte[] receiveBuffer = new byte[576];
            DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            socket.receive(receivePacket);

            long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

            byte[] responseData = new byte[receivePacket.getLength()];
            System.arraycopy(receivePacket.getData(), 0, responseData, 0, receivePacket.getLength());

            MappedAddress mapped = parseBindingResponse(responseData, request);

            if (mapped != null) {
                VoxLinkMod.LOGGER.debug("[StunProbe] {} reachable, latency={}ms, mapped={}:{}",
                        stunUrl, latencyMs, mapped.ip, mapped.port);
                return new StunServerResult(stunUrl, parsed.host, parsed.port, true, latencyMs, mapped.ip, mapped.port);
            } else {
                VoxLinkMod.LOGGER.debug("[StunProbe] {}有响应但没映射地址", stunUrl);
                return new StunServerResult(stunUrl, parsed.host, parsed.port, true, latencyMs, null, 0);
            }
        } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[StunProbe] {}不可达: {}", stunUrl, e.getMessage());
            return new StunServerResult(stunUrl, parsed.host, parsed.port, false, -1, null, 0);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static NatType probeNatType(java.util.List<java.util.List<String>> stunGroups) {
        try {
            ProbeResult result = probe(stunGroups);
            return result != null ? result.natType : null;
        } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[StunProbe] NAT类型探测失败: {}", e.getMessage());
            return null;
        }
    }

    private static NatType detectNatType(List<StunServerResult> results) {
        List<StunServerResult> reachable = results.stream()
                .filter(r -> r.reachable && r.mappedIp != null)
                .toList();

        if (reachable.size() < 2) {
            VoxLinkMod.LOGGER.info("[StunProbe] 可达服务器不够({})，无法判断NAT类型", reachable.size());
            return reachable.isEmpty() ? NatType.UNKNOWN : NatType.PORT_RESTRICTED_CONE;
        }

        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(PROBE_TIMEOUT_MS);

            StunServerResult first = reachable.get(0);
            StunServerResult second = reachable.get(1);
            InetAddress firstAddr = InetAddress.getByName(first.host);
            InetAddress secondAddr = InetAddress.getByName(second.host);

            byte[] req1 = createBindingRequest();
            byte[] req2 = createBindingRequest();

            socket.send(new DatagramPacket(req1, req1.length, firstAddr, first.port));
            socket.send(new DatagramPacket(req2, req2.length, secondAddr, second.port));

            MappedAddress mapped1 = null;
            MappedAddress mapped2 = null;

            byte[] buf = new byte[576];
            long deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS;

            for (int i = 0; i < 2 && System.currentTimeMillis() < deadline; i++) {
                DatagramPacket recv = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(recv);
                } catch (java.net.SocketTimeoutException e) {
                    break;
                }

                byte[] respData = new byte[recv.getLength()];
                System.arraycopy(recv.getData(), 0, respData, 0, recv.getLength());

                if (mapped1 == null) {
                    MappedAddress ma = parseBindingResponse(respData, req1);
                    if (ma != null) { mapped1 = ma; continue; }
                    ma = parseBindingResponse(respData, req2);
                    if (ma != null) { mapped2 = ma; continue; }
                } else {
                    MappedAddress ma = parseBindingResponse(respData, req2);
                    if (ma != null) { mapped2 = ma; continue; }
                    ma = parseBindingResponse(respData, req1);
                    if (ma != null) { mapped1 = ma; }
                }
            }

            if (mapped1 == null || mapped2 == null) return NatType.UNKNOWN;

            if (!mapped1.ip.equals(mapped2.ip) || mapped1.port != mapped2.port) {
                VoxLinkMod.LOGGER.info("[StunProbe] 对称NAT，映射地址不同 ({}:{} vs {}:{})", mapped1.ip, mapped1.port, mapped2.ip, mapped2.port);
                // 修复1: 用同一socket发第3个STUN, 避免新建socket致basePort基准失真
                NatType easyType = detectEasySymmetric(socket, mapped1.port, reachable);
                if (easyType != null) {
                    return easyType;
                }
                // 回归修复: 两个STUN服务器返回不同端口=对称NAT(RFC 5780), 无论diff多小
                // diff阈值只区分EasySym(可预测)和HardSym(不可预测), 不区分Cone和Symmetric
                //   diff < 100 且方向一致: EasySym(Inc/Dec)
                //   diff >= 100: HardSym
                //   公网IP不同: HardSym
                int twoDiff = mapped2.port - mapped1.port;
                int absDiff = Math.abs(twoDiff);
                if (!mapped1.ip.equals(mapped2.ip)) {
                    VoxLinkMod.LOGGER.info("[StunProbe] EasySym fallback: 公网IP不同({} vs {}), 判定HardSym", mapped1.ip, mapped2.ip);
                    return NatType.SYMMETRIC;
                }
                if (absDiff > 0 && absDiff < 100) {
                    VoxLinkMod.LOGGER.info("[StunProbe] EasySym fallback(三次采样失败): {}→{} diff={}, 判定递增/递减", mapped1.port, mapped2.port, twoDiff);
                    return twoDiff > 0 ? NatType.SYMMETRIC_EASY_INC : NatType.SYMMETRIC_EASY_DEC;
                } else {
                    VoxLinkMod.LOGGER.info("[StunProbe] EasySym fallback: {}→{} diff={}>=100, 判定HardSym", mapped1.port, mapped2.port, absDiff);
                    return NatType.SYMMETRIC;
                }
            }

            VoxLinkMod.LOGGER.info("[StunProbe] 非对称NAT，映射地址相同 ({}:{})", mapped1.ip, mapped1.port);
            return NatType.PORT_RESTRICTED_CONE;
        } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[StunProbe] NAT检测失败: {}", e.getMessage());
            return NatType.UNKNOWN;
        } finally {
            if (socket != null) socket.close();
        }
    }

    private static NatType detectEasySymmetric(DatagramSocket socket, int basePort, List<StunServerResult> reachable) {
        // 修复1: 用同一socket发第3个STUN服务器, 避免新建socket致basePort基准失真
        // 旧逻辑新建extraSocket采样, 新socket本身触发NAT分配新端口, basePort来自旧socket, diff失真
        // 新逻辑: 同一socket(已发过first/second)再发third, 对称NAT会分配第3个端口, 比对差值判定方向
        if (reachable.size() < 3) {
            VoxLinkMod.LOGGER.info("[StunProbe] EasySym检测: 可达STUN不足3个({}), 跳过第三次采样", reachable.size());
            return null;
        }
        StunServerResult third = reachable.get(2);
        try {
            socket.setSoTimeout(1500);  // 短超时避免拖太久
            InetAddress thirdAddr = InetAddress.getByName(third.host);
            byte[] req = createBindingRequest();
            socket.send(new DatagramPacket(req, req.length, thirdAddr, third.port));

            byte[] buf = new byte[576];
            DatagramPacket recv = new DatagramPacket(buf, buf.length);
            socket.receive(recv);
            byte[] respData = new byte[recv.getLength()];
            System.arraycopy(recv.getData(), 0, respData, 0, recv.getLength());

            MappedAddress extraMapped = parseBindingResponse(respData, req);
            if (extraMapped != null) {
                int diff = extraMapped.port - basePort;
                int absDiff = Math.abs(diff);
                VoxLinkMod.LOGGER.info("[StunProbe] EasySym检测(同socket第3服务器): basePort={}, extraPort={}, diff={}", basePort, extraMapped.port, diff);
                // 回归修复: 已确认是对称NAT(前两个STUN端口不同), 第3个采样只判定方向
                //   diff > 0 且 < 100: EasySym递增
                //   diff < 0 且 > -100: EasySym递减
                //   absDiff >= 100: HardSym
                //   diff == 0: 罕见, NAT偶尔复用端口, 仍按EasySym处理(方向取前两次)
                if (diff > 0 && diff < 100) {
                    VoxLinkMod.LOGGER.info("[StunProbe] EasySym递增(端口+{})", diff);
                    return NatType.SYMMETRIC_EASY_INC;
                } else if (diff < 0 && diff > -100) {
                    VoxLinkMod.LOGGER.info("[StunProbe] EasySym递减(端口{})", diff);
                    return NatType.SYMMETRIC_EASY_DEC;
                } else if (absDiff >= 100) {
                    VoxLinkMod.LOGGER.info("[StunProbe] EasySym检测: diff={}>=100, 判定HardSym", absDiff);
                    return NatType.SYMMETRIC;
                } else {
                    // diff == 0, NAT复用了端口, 但前两次已确认是对称NAT, 按EasySym处理
                    VoxLinkMod.LOGGER.info("[StunProbe] EasySym检测: diff=0(NAT复用端口), 仍判定EasySym");
                    return NatType.SYMMETRIC_EASY_INC;
                }
            }
        } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[StunProbe] EasySym检测失败: {}", e.getMessage());
        }
        return null;
    }

    private static byte[] createBindingRequest() {
        byte[] request = new byte[20];
        request[0] = 0x00;
        request[1] = 0x01;
        request[2] = 0x00;
        request[3] = 0x00;
        request[4] = 0x21;
        request[5] = 0x12;
        request[6] = (byte) 0xA4;
        request[7] = 0x42;
        byte[] transactionId = new byte[12];
        ThreadLocalRandom.current().nextBytes(transactionId);
        System.arraycopy(transactionId, 0, request, 8, 12);
        return request;
    }

    private static MappedAddress parseBindingResponse(byte[] data, byte[] originalRequest) {
        if (data.length < 20) return null;

        int type = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        if (type != 0x0101) return null;

        if (data[4] != 0x21 || data[5] != 0x12 || data[6] != (byte) 0xA4 || data[7] != 0x42) return null;

        for (int i = 8; i < 20; i++) {
            if (data[i] != originalRequest[i]) return null;
        }

        int msgLen = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        int maxMsgLen = data.length - 20;
        if (msgLen > maxMsgLen) msgLen = maxMsgLen;
        VoxLinkMod.LOGGER.info("[StunProbe] 绑定响应: dataLen={}, msgLen={}", data.length, msgLen);
        int offset = 20;

        while (offset + 4 <= data.length && offset - 20 < msgLen) {
            int attrType = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
            int attrLen = ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);

            if (offset + 4 + attrLen > data.length) break;

            if (attrType == 0x0020) {
                return parseXorMappedAddress(data, offset, attrLen, originalRequest);
            } else if (attrType == 0x0001) {
                return parseMappedAddress(data, offset, attrLen);
            } else if (attrType == 0x8020) {
                return parseXorMappedAddress(data, offset, attrLen, originalRequest);
            } else if (attrType == 0x8028) {
                VoxLinkMod.LOGGER.debug("[StunProbe] 跳过FINGERPRINT");
            } else if (attrType == 0x8022) {
                VoxLinkMod.LOGGER.debug("[StunProbe] 跳过SOFTWARE(len={})", attrLen);
            } else if (attrType == 0x8029) {
                VoxLinkMod.LOGGER.debug("[StunProbe] 跳过MESSAGE-INTEGRITY(len={})", attrLen);
            } else {
                VoxLinkMod.LOGGER.warn("[StunProbe] 未知属性: 0x{}, len={}", Integer.toHexString(attrType), attrLen);
            }

            offset += 4 + attrLen;
            if (attrLen % 4 != 0) offset += (4 - attrLen % 4);
        }

        return null;
    }

    private static MappedAddress parseXorMappedAddress(byte[] data, int offset, int attrLen, byte[] originalRequest) {
        if (attrLen < 8 || offset + 8 > data.length) return null;

        byte family = data[offset + 5];
        int xorPort = ((data[offset + 6] & 0xFF) << 8) | (data[offset + 7] & 0xFF);
        int port = xorPort ^ 0x2112;

        if (family == 0x01) {
            if (offset + 12 > data.length) return null;
            int xorIp = ((data[offset + 8] & 0xFF) << 24) | ((data[offset + 9] & 0xFF) << 16) |
                    ((data[offset + 10] & 0xFF) << 8) | (data[offset + 11] & 0xFF);
            int ip = xorIp ^ 0x2112A442;
            String ipStr = ((ip >> 24) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." +
                    ((ip >> 8) & 0xFF) + "." + (ip & 0xFF);
            return new MappedAddress(ipStr, port);
        } else if (family == 0x02) {
            if (attrLen < 20 || offset + 24 > data.length) return null;
            byte[] xorKey = new byte[16];
            System.arraycopy(originalRequest, 4, xorKey, 0, 16);
            byte[] ipBytes = new byte[16];
            for (int i = 0; i < 16; i++) {
                ipBytes[i] = (byte) ((data[offset + 8 + i] & 0xFF) ^ (xorKey[i] & 0xFF));
            }
            try {
                InetAddress ipv6Addr = InetAddress.getByAddress(ipBytes);
                return new MappedAddress(ipv6Addr.getHostAddress(), port);
            } catch (Exception e) {
                return null;
            }
        }

        return null;
    }

    private static MappedAddress parseMappedAddress(byte[] data, int offset, int attrLen) {
        if (attrLen < 8 || offset + 8 > data.length) return null;

        byte family = data[offset + 5];
        int port = ((data[offset + 6] & 0xFF) << 8) | (data[offset + 7] & 0xFF);

        if (family == 0x01) {
            if (offset + 12 > data.length) return null;
            String ip = (data[offset + 8] & 0xFF) + "." + (data[offset + 9] & 0xFF) + "." +
                    (data[offset + 10] & 0xFF) + "." + (data[offset + 11] & 0xFF);
            return new MappedAddress(ip, port);
        }

        return null;
    }

    private static ParsedStunUrl parseStunUrl(String stunUrl) {
        String stripped = stunUrl.replace("stun:", "").replace("stuns:", "");
        if (stripped.startsWith("[")) {
            int bracketEnd = stripped.indexOf(']');
            if (bracketEnd > 0) {
                String host = stripped.substring(1, bracketEnd);
                int port = 0;
                if (stripped.length() > bracketEnd + 2 && stripped.charAt(bracketEnd + 1) == ':') {
                    try {
                        port = Integer.parseInt(stripped.substring(bracketEnd + 2));
                    } catch (NumberFormatException ignored) {}
                }
                return new ParsedStunUrl(host, port > 0 ? port : STUN_DEFAULT_PORT);
            }
        }
        if (stripped.contains(":")) {
            int lastColon = stripped.lastIndexOf(":");
            if (lastColon > 0 && lastColon < stripped.length() - 1) {
                String host = stripped.substring(0, lastColon);
                String portStr = stripped.substring(lastColon + 1);
                if (host.contains(":")) {
                    VoxLinkMod.LOGGER.warn("[StunProbe] IPv6没加方括号，有歧义，忽略: {}", stunUrl);
                    return null;
                }
                try {
                    int port = Integer.parseInt(portStr);
                    if (port > 0 && port <= 65535) {
                        return new ParsedStunUrl(host, port);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            return new ParsedStunUrl(stripped, STUN_DEFAULT_PORT);
        }
        return new ParsedStunUrl(stripped, STUN_DEFAULT_PORT);
    }

    /**
     * P-PRE连续采样: 从STUN服务器列表自动选第一个响应的, 固定该服务器连发N次,
     * 记录映射端口序列。对称NAT每次向同一目的地发包分配新端口, 通过序列计算增量用于端口预测。
     * 首请求1s内无响应自动切换下一个服务器; 一旦选定, 后续所有采样都用同一个服务器。
     * 
     * @param socket 已绑定的UDP socket(保持存活, 不可更换)
     * @param stunUrls STUN服务器URL列表 (如 StunDetector.getAllStunUrls())
     * @param count 采样次数(建议10, 最少5)
     * @param intervalMs 间隔毫秒(建议100)
     * @return 按时间顺序的映射端口列表, 采样不足返回空列表
     */
    public static List<Integer> samplePortsSequential(
            DatagramSocket socket, java.util.List<String> stunUrls,
            int count, int intervalMs) {
        if (stunUrls == null || stunUrls.isEmpty()) return java.util.Collections.emptyList();
        List<Integer> ports = new ArrayList<>();
        int originalTimeout = -1;
        try {
            originalTimeout = socket.getSoTimeout();
            socket.setSoTimeout(DUAL_STUN_TIMEOUT_MS);
        } catch (Exception ignored) {}

        //8并发竞速选服务器: 同时发所有STUN, 谁先返回用谁, 记录选中服务器
        String selectedHost = null;
        int selectedPort = STUN_DEFAULT_PORT;
        int n = stunUrls.size();
        ParsedStunUrl[] parsed = new ParsedStunUrl[n];
        byte[][] reqs = new byte[n][];
        try {
            for (int i = 0; i < n; i++) {
                parsed[i] = parseStunUrl(stunUrls.get(i));
                if (parsed[i] == null) continue;
                reqs[i] = createBindingRequest();
                InetAddress addr = InetAddress.getByName(parsed[i].host);
                socket.send(new DatagramPacket(reqs[i], reqs[i].length, addr, parsed[i].port));
            }
            byte[] buf = new byte[576];
            DatagramPacket recv = new DatagramPacket(buf, buf.length);
            long deadline = System.currentTimeMillis() + DUAL_STUN_TIMEOUT_MS;
            while (selectedHost == null && System.currentTimeMillis() < deadline) {
                try { socket.receive(recv); } catch (SocketTimeoutException e) { break; }
                byte[] respData = new byte[recv.getLength()];
                System.arraycopy(recv.getData(), 0, respData, 0, recv.getLength());
                if (respData.length < 20) continue;
                for (int i = 0; i < n; i++) {
                    if (reqs[i] == null) continue;
                    if (matchTransaction(respData, reqs[i])) {
                        MappedAddress ma = parseBindingResponse(respData, reqs[i]);
                        if (ma != null) {
                            selectedHost = parsed[i].host;
                            selectedPort = parsed[i].port;
                            ports.add(ma.port);
                            VoxLinkMod.LOGGER.info("[StunProbe] P-PRE竞速选定STUN: {}:{}, 首端口={}", selectedHost, selectedPort, ma.port);
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("[StunProbe] P-PRE竞速异常: {}", e.getMessage());
        }
        if (selectedHost == null) {
            VoxLinkMod.LOGGER.warn("[StunProbe] P-PRE竞速无响应, 采样失败");
            try { if (originalTimeout >= 0) socket.setSoTimeout(originalTimeout); } catch (Exception ignored) {}
            return ports;
        }

        // 恢复探测超时, 用选定的服务器完成剩余采样
        try { socket.setSoTimeout(DISCOVER_TIMEOUT_MS); } catch (Exception ignored) {}
        for (int i = 1; i < count; i++) {
            try {
                byte[] req = createBindingRequest();
                InetAddress addr = InetAddress.getByName(selectedHost);
                socket.send(new DatagramPacket(req, req.length, addr, selectedPort));
                byte[] buf = new byte[576];
                DatagramPacket recv = new DatagramPacket(buf, buf.length);
                long deadline = System.currentTimeMillis() + DISCOVER_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    try { socket.receive(recv); } catch (SocketTimeoutException e) { break; }
                    byte[] respData = new byte[recv.getLength()];
                    System.arraycopy(recv.getData(), 0, respData, 0, recv.getLength());
                    if (respData.length < 20) continue;
                    MappedAddress ma = parseBindingResponse(respData, req);
                    if (ma != null) { ports.add(ma.port); break; }
                }
                if (i < count - 1) {
                    try { Thread.sleep(intervalMs); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            } catch (Exception e) {
                VoxLinkMod.LOGGER.debug("[StunProbe] P-PRE采样#{}/{} 失败: {}", i + 1, count, e.getMessage());
            }
        }

        try { if (originalTimeout >= 0) socket.setSoTimeout(originalTimeout); } catch (Exception ignored) {}
        return ports;
    }
}
