package icu.wuhui.voxlink.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class UPnPManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-upnp");
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final int SEARCH_TIMEOUT = 1500;
    private static final int HTTP_TIMEOUT_MS = 3000;
    private static final ConcurrentHashMap<String, Boolean> mappedPorts = new ConcurrentHashMap<>();
    private static volatile GatewayInfo cachedGateway;
    private static volatile String cachedLocalIp;
    private static final long CACHE_DURATION = 60_000L;
    private static volatile long gatewayCacheTime = 0;
    private static volatile long localIpCacheTime = 0;

    public static UPnPResult openPort(int port, String description) {
        return openPort(port, description, "TCP");
    }

    public static UPnPResult openUdpPort(int port, String description) {
        return openPort(port, description, "UDP");
    }

    public static UPnPResult openPort(int port, String description, String protocol) {
        if (!"TCP".equals(protocol) && !"UDP".equals(protocol)) {
            throw new IllegalArgumentException("Invalid protocol: " + protocol);
        }
        try {
            GatewayInfo gateway = getCachedGateway();
            if (gateway == null) {
                LOGGER.info("没找到UPnP网关");
                return new UPnPResult(false, false, port);
            }

            String localIp = getCachedLocalIp();
            if (localIp == null) {
                LOGGER.warn("拿不到本地IP");
                return new UPnPResult(true, false, port);
            }

            String soapBody = buildAddPortMapping(port, localIp, description, protocol);
            String response = sendSoapRequest(gateway, "AddPortMapping", soapBody);

            if (response != null && !response.contains("errorCode")) {
                mappedPorts.put(port + "_" + protocol, true);
                LOGGER.info("UPnP: {}端口{}开放成功", protocol, port);
                return new UPnPResult(true, true, port);
            } else {
                LOGGER.warn("UPnP: {}端口{}开放失败", protocol, port);
                return new UPnPResult(true, false, port);
            }
        } catch (Exception e) {
            LOGGER.error("UPnP出错: {}", e.getMessage());
            return new UPnPResult(false, false, port);
        }
    }

    public static void closePort(int port) {
        closePort(port, "TCP");
    }

    public static void closeUdpPort(int port) {
        closePort(port, "UDP");
    }

    public static void closePort(int port, String protocol) {
        if (!mappedPorts.containsKey(port + "_" + protocol)) return;
        try {
            GatewayInfo gateway = getCachedGateway();
            if (gateway == null) return;

            String soapBody = buildDeletePortMapping(port, protocol);
            sendSoapRequest(gateway, "DeletePortMapping", soapBody);
            mappedPorts.remove(port + "_" + protocol);
            LOGGER.info("UPnP: {}端口{}已关闭", protocol, port);
        } catch (Exception e) {
            LOGGER.error("UPnP关闭出错: {}", e.getMessage());
        }
    }

    //主动映射, 对称NAT转FullCone
    public static boolean addPortMapping(int internalPort, int externalPort) {
        try {
            GatewayInfo gateway = getCachedGateway();
            if (gateway == null) {
                LOGGER.info("没找到UPnP网关");
                return false;
            }
            String localIp = getCachedLocalIp();
            if (localIp == null) {
                LOGGER.warn("拿不到本地IP");
                return false;
            }
            String soapBody = buildAddPortMappingLease(internalPort, externalPort, localIp, "VoxLink", "UDP", 3600);
            String response = sendSoapRequest(gateway, "AddPortMapping", soapBody);
            if (response != null && !response.contains("errorCode")) {
                mappedPorts.put(externalPort + "_UDP", true);
                return true;
            }
            LOGGER.warn("UPnP: UDP端口{}映射失败", externalPort);
            return false;
        } catch (Exception e) {
            LOGGER.error("UPnP映射出错: {}", e.getMessage());
            return false;
        }
    }

    public static boolean addPortMapping(int port) {
        return addPortMapping(port, port);
    }

    public static void deletePortMapping(int externalPort) {
        closeUdpPort(externalPort);
    }

    public static String getExternalIp() {
        try {
            GatewayInfo gateway = discoverGateway();
            if (gateway == null) return null;

            String soapBody = "<?xml version=\"1.0\"?>\n" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                    "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                    "<s:Body><m:GetExternalIPAddress xmlns:m=\"" + gateway.serviceType + "\"></m:GetExternalIPAddress></s:Body></s:Envelope>";

            String response = sendSoapRequest(gateway, "GetExternalIPAddress", soapBody);
            if (response != null) {
                int start = response.indexOf("<NewExternalIPAddress>");
                if (start < 0) start = response.indexOf(":NewExternalIPAddress>");
                int end = response.indexOf("</NewExternalIPAddress>");
                if (end < 0 && start >= 0) {
                    int nsEnd = response.indexOf(":NewExternalIPAddress>", response.indexOf('>', start) + 1);
                    if (nsEnd > 0) end = response.lastIndexOf("</", nsEnd);
                }
                if (start >= 0 && end > start) {
                    return response.substring(response.indexOf('>', start) + 1, end);
                }
            }
        } catch (Exception e) {
            LOGGER.error("UPnP获取外网IP出错: {}", e.getMessage());
        }
        return null;
    }

    private static GatewayInfo getCachedGateway() throws Exception {
        if (cachedGateway != null && System.currentTimeMillis() - gatewayCacheTime < CACHE_DURATION) {
            return cachedGateway;
        }
        GatewayInfo gateway = discoverGateway();
        if (gateway != null) {
            cachedGateway = gateway;
            gatewayCacheTime = System.currentTimeMillis();
        }
        return gateway;
    }

    private static String getCachedLocalIp() throws Exception {
        if (cachedLocalIp != null && System.currentTimeMillis() - localIpCacheTime < CACHE_DURATION) {
            return cachedLocalIp;
        }
        String ip = getLocalIp();
        if (ip != null) {
            cachedLocalIp = ip;
            localIpCacheTime = System.currentTimeMillis();
        }
        return ip;
    }

    public static void invalidateCache() {
        cachedGateway = null;
        cachedLocalIp = null;
        gatewayCacheTime = 0;
        localIpCacheTime = 0;
    }

    //优化: 启动即尝试UPnP, 预发现网关(最耗时). 开房时用缓存网关快速映射, 无延迟
    //用户原话: "UPnP启动即尝试（推荐）"
    private static volatile java.util.concurrent.Future<?> startupFuture;
    private static volatile boolean startupAttempted = false;

    public static void tryMapAtStartup() {
        if (startupAttempted) return;
        synchronized (UPnPManager.class) {
            if (startupAttempted) return;
            startupAttempted = true;
        }
        startupFuture = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                long t0 = System.currentTimeMillis();
                GatewayInfo gw = getCachedGateway();
                if (gw == null) {
                    LOGGER.info("[UPnP] 启动预发现: 未找到UPnP网关 ({}ms)", System.currentTimeMillis() - t0);
                    return;
                }
                String localIp = getCachedLocalIp();
                if (localIp == null) {
                    LOGGER.warn("[UPnP] 启动预发现: 拿不到本地IP ({}ms)", System.currentTimeMillis() - t0);
                    return;
                }
                LOGGER.info("[UPnP] 启动预发现成功: 网关={} 本地IP={} ({}ms)",
                        gw.controlUrl, localIp, System.currentTimeMillis() - t0);
            } catch (Exception e) {
                LOGGER.warn("[UPnP] 启动预发现异常: {}", e.getMessage());
            }
        });
        LOGGER.info("[UPnP] 启动即尝试UPnP预发现网关");
    }

    public static boolean isGatewayPrecached() {
        return cachedGateway != null && System.currentTimeMillis() - gatewayCacheTime < CACHE_DURATION;
    }

    private static GatewayInfo discoverGateway() throws Exception {
        List<InetAddress> localAddresses = getLocalAddresses();
        String[] searchMessages = {
                "M-SEARCH * HTTP/1.1\r\nHOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\n\r\n",
                "M-SEARCH * HTTP/1.1\r\nHOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\nST: urn:schemas-upnp-org:service:WANIPConnection:1\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\n\r\n"
        };

        java.util.concurrent.atomic.AtomicReference<GatewayInfo> found = new java.util.concurrent.atomic.AtomicReference<>(null);
        List<Thread> threads = new ArrayList<>();
        List<DatagramSocket> searchSockets = Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicBoolean socketsClosed = new java.util.concurrent.atomic.AtomicBoolean(false);

        for (InetAddress localAddr : localAddresses) {
            for (String searchMsg : searchMessages) {
                if (found.get() != null) break;
                Thread t = new Thread(() -> {
                    DatagramSocket socket = null;
                    try {
                        socket = new DatagramSocket(0, localAddr);
                        synchronized (searchSockets) {
                            if (socketsClosed.get()) {
                                socket.close();
                                return;
                            }
                            searchSockets.add(socket);
                        }
                        socket.setSoTimeout(SEARCH_TIMEOUT);
                        byte[] data = searchMsg.getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(data, data.length, InetAddress.getByName(SSDP_ADDR), SSDP_PORT));

                        byte[] buf = new byte[4096];
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        long deadline = System.currentTimeMillis() + SEARCH_TIMEOUT;

                        while (System.currentTimeMillis() < deadline && found.get() == null) {
                            try {
                                socket.receive(packet);
                                String response = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                                String location = extractHeader(response, "LOCATION");
                                if (location != null) {
                                    GatewayInfo gateway = parseGateway(location);
                                    if (gateway != null) {
                                        found.compareAndSet(null, gateway);
                                        synchronized (searchSockets) {
                                            socketsClosed.set(true);
                                            for (DatagramSocket s : searchSockets) {
                                                try { 
                                                    if (s != null && !s.isClosed()) s.close(); 
                                                } catch (Exception ignored) {}
                                            }
                                            searchSockets.clear();
                                        }
                                        return;
                                    }
                                }
                            } catch (java.net.SocketTimeoutException e) {
                                break;
                            } catch (java.net.SocketException e) {
                                if (!socketsClosed.get()) {
                                    LOGGER.debug("SSDP socket error: {}", e.getMessage());
                                }
                                break;
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (socket != null && !socket.isClosed() && !socketsClosed.get()) {
                            try { socket.close(); } catch (Exception ignored) {}
                        }
                    }
                }, "VoxLink-SSDP");
                t.setDaemon(true);
                threads.add(t);
                t.start();
            }
        }

        for (Thread t : threads) {
            t.join(SEARCH_TIMEOUT + 500);
        }

        synchronized (searchSockets) {
            socketsClosed.set(true);
            for (DatagramSocket s : searchSockets) {
                try { if (s != null && !s.isClosed()) s.close(); } catch (Exception ignored) {}
            }
            searchSockets.clear();
        }

        return found.get();
    }

    private static GatewayInfo parseGateway(String location) {
        try {
            java.net.URL url = java.net.URI.create(location).toURL();
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            String xml;
            try (java.io.InputStream is = conn.getInputStream()) {
                xml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } finally {
                conn.disconnect();
            }

            String serviceType = null;
            String controlUrl = null;

            int stIdx = xml.indexOf(":WANIPConnection:");
            if (stIdx < 0) stIdx = xml.indexOf(":WANPPPConnection:");
            if (stIdx >= 0) {
                int typeStart = xml.lastIndexOf("<serviceType>", stIdx);
                if (typeStart < 0) typeStart = xml.lastIndexOf(":serviceType>", stIdx);
                int typeEnd = xml.indexOf("</serviceType>", typeStart >= 0 ? typeStart : 0);
                if (typeEnd < 0 && typeStart >= 0) {
                    int nsTypeEnd = xml.indexOf(":serviceType>", xml.indexOf('>', typeStart) + 1);
                    if (nsTypeEnd > 0) typeEnd = xml.lastIndexOf("</", nsTypeEnd);
                }
                if (typeStart >= 0 && typeEnd > typeStart) {
                    serviceType = xml.substring(xml.indexOf('>', typeStart) + 1, typeEnd);
                }

                int urlStart = xml.indexOf("<controlURL>", typeEnd >= 0 ? typeEnd : 0);
                if (urlStart < 0) urlStart = xml.indexOf(":controlURL>", typeEnd >= 0 ? typeEnd : 0);
                int urlEnd = xml.indexOf("</controlURL>", urlStart >= 0 ? urlStart : 0);
                if (urlEnd < 0 && urlStart >= 0) {
                    int nsUrlEnd = xml.indexOf(":controlURL>", xml.indexOf('>', urlStart) + 1);
                    if (nsUrlEnd > 0) urlEnd = xml.lastIndexOf("</", nsUrlEnd);
                }
                if (urlStart >= 0 && urlEnd > urlStart) {
                    controlUrl = xml.substring(xml.indexOf('>', urlStart) + 1, urlEnd);
                }
            }

            if (serviceType != null && controlUrl != null) {
                int urlPort = url.getPort() != -1 ? url.getPort() : url.getDefaultPort();
                String baseUrl = url.getProtocol() + "://" + url.getHost() + ":" + urlPort;
                return new GatewayInfo(baseUrl, controlUrl, serviceType);
            }
        } catch (Exception e) {
            LOGGER.debug("网关解析失败: {}", e.getMessage());
        }
        return null;
    }

    private static String sendSoapRequest(GatewayInfo gateway, String action, String body) throws Exception {
        java.net.URL url = java.net.URI.create(gateway.baseUrl + gateway.controlUrl).toURL();
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(HTTP_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
        conn.setRequestProperty("SOAPAction", "\"" + gateway.serviceType + "#" + action + "\"");

        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        if (conn.getResponseCode() >= 400) {
            conn.disconnect();
            return null;
        }

        try (java.io.InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private static String buildAddPortMapping(int port, String localIp, String description, String protocol) {
        String safeDesc = description != null ? description.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;") : "VoxLink";
        return "<?xml version=\"1.0\"?>\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                "<s:Body><m:AddPortMapping xmlns:m=\"urn:schemas-upnp-org:service:WANIPConnection:1\">" +
                "<NewRemoteHost></NewRemoteHost>" +
                "<NewExternalPort>" + port + "</NewExternalPort>" +
                "<NewProtocol>" + protocol + "</NewProtocol>" +
                "<NewInternalPort>" + port + "</NewInternalPort>" +
                "<NewInternalClient>" + localIp + "</NewInternalClient>" +
                "<NewEnabled>1</NewEnabled>" +
                "<NewPortMappingDescription>" + safeDesc + "</NewPortMappingDescription>" +
                "<NewLeaseDuration>0</NewLeaseDuration>" +
                "</m:AddPortMapping></s:Body></s:Envelope>";
    }

    //内外端口可不同, 带租期(主动转FullCone用)
    private static String buildAddPortMappingLease(int internalPort, int externalPort, String localIp, String description, String protocol, int leaseDuration) {
        String safeDesc = description != null ? description.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;") : "VoxLink";
        return "<?xml version=\"1.0\"?>\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                "<s:Body><m:AddPortMapping xmlns:m=\"urn:schemas-upnp-org:service:WANIPConnection:1\">" +
                "<NewRemoteHost></NewRemoteHost>" +
                "<NewExternalPort>" + externalPort + "</NewExternalPort>" +
                "<NewProtocol>" + protocol + "</NewProtocol>" +
                "<NewInternalPort>" + internalPort + "</NewInternalPort>" +
                "<NewInternalClient>" + localIp + "</NewInternalClient>" +
                "<NewEnabled>1</NewEnabled>" +
                "<NewPortMappingDescription>" + safeDesc + "</NewPortMappingDescription>" +
                "<NewLeaseDuration>" + leaseDuration + "</NewLeaseDuration>" +
                "</m:AddPortMapping></s:Body></s:Envelope>";
    }

    private static String buildDeletePortMapping(int port, String protocol) {
        return "<?xml version=\"1.0\"?>\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                "<s:Body><m:DeletePortMapping xmlns:m=\"urn:schemas-upnp-org:service:WANIPConnection:1\">" +
                "<NewRemoteHost></NewRemoteHost>" +
                "<NewExternalPort>" + port + "</NewExternalPort>" +
                "<NewProtocol>" + protocol + "</NewProtocol>" +
                "</m:DeletePortMapping></s:Body></s:Envelope>";
    }

    private static String extractHeader(String response, String header) {
        String searchLower = header.toLowerCase() + ":";
        int idx = -1;
        String lower = response.toLowerCase();
        for (int i = 0; i < lower.length() - searchLower.length(); i++) {
            if (lower.startsWith(searchLower, i)) {
                if (i == 0 || lower.charAt(i - 1) == '\n') {
                    idx = i;
                    break;
                }
            }
        }
        if (idx < 0) return null;
        int headerEnd = idx + searchLower.length();
        int end = response.indexOf("\r\n", headerEnd);
        if (end < 0) end = response.indexOf("\n", headerEnd);
        return end > headerEnd ? response.substring(headerEnd, end).trim() : null;
    }

    private static List<InetAddress> getLocalAddresses() throws Exception {
        List<InetAddress> addresses = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) continue;
            Enumeration<InetAddress> addrEnum = ni.getInetAddresses();
            while (addrEnum.hasMoreElements()) {
                InetAddress addr = addrEnum.nextElement();
                if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                    addresses.add(addr);
                }
            }
        }
        return addresses;
    }

    private static String getLocalIp() throws Exception {
        List<InetAddress> addresses = getLocalAddresses();
        return addresses.isEmpty() ? null : addresses.get(0).getHostAddress();
    }

    public record UPnPResult(boolean available, boolean success, int externalPort) {}
    public record GatewayInfo(String baseUrl, String controlUrl, String serviceType) {}
}
