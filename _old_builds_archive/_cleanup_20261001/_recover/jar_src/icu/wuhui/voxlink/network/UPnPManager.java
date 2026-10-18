package icu.wuhui.voxlink.network;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UPnPManager {
   private static final Logger LOGGER = LoggerFactory.getLogger("voxlink-upnp");
   private static final String SSDP_ADDR = "239.255.255.250";
   private static final int SSDP_PORT = 1900;
   private static final int SEARCH_TIMEOUT = 1500;
   private static final int HTTP_TIMEOUT_MS = 3000;
   private static final ConcurrentHashMap<String, Boolean> mappedPorts = new ConcurrentHashMap<>();
   private static volatile UPnPManager.GatewayInfo cachedGateway;
   private static volatile String cachedLocalIp;
   private static final long CACHE_DURATION = 60000L;
   private static volatile long gatewayCacheTime = 0L;
   private static volatile long localIpCacheTime = 0L;
   private static volatile Future<?> startupFuture;
   private static volatile boolean startupAttempted = false;
   private static final long RENEW_LEAD_MS = 3000000L;
   private static final ScheduledExecutorService RENEW_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "VoxLink-UPnPRenew");
      t.setDaemon(true);
      return t;
   });
   private static final ConcurrentHashMap<String, ScheduledFuture<?>> renewTasks = new ConcurrentHashMap<>();
   private static volatile boolean stopped = false;

   public static UPnPManager.UPnPResult openPort(int port, String description) {
      return openPort(port, description, "TCP");
   }

   public static UPnPManager.UPnPResult openUdpPort(int port, String description) {
      return openPort(port, description, "UDP");
   }

   public static UPnPManager.UPnPResult openPort(int port, String description, String protocol) {
      if (!"TCP".equals(protocol) && !"UDP".equals(protocol)) {
         throw new IllegalArgumentException("Invalid protocol: " + protocol);
      } else {
         try {
            UPnPManager.GatewayInfo gateway = getCachedGateway();
            if (gateway == null) {
               LOGGER.info("No UPnP gateway found");
               return new UPnPManager.UPnPResult(false, false, port);
            } else {
               String localIp = getCachedLocalIp();
               if (localIp == null) {
                  LOGGER.warn("Cannot get local IP");
                  return new UPnPManager.UPnPResult(true, false, port);
               } else {
                  String soapBody = buildAddPortMapping(port, localIp, description, protocol);
                  String response = sendSoapRequest(gateway, "AddPortMapping", soapBody);
                  if (response != null && !response.contains("errorCode")) {
                     mappedPorts.put(port + "_" + protocol, true);
                     LOGGER.info("UPnP: {} port {} opened successfully", protocol, port);
                     return new UPnPManager.UPnPResult(true, true, port);
                  } else {
                     LOGGER.warn("UPnP: {} port {} open failed", protocol, port);
                     return new UPnPManager.UPnPResult(true, false, port);
                  }
               }
            }
         } catch (Exception var7) {
            LOGGER.error("UPnP error: {}", var7.getMessage());
            return new UPnPManager.UPnPResult(false, false, port);
         }
      }
   }

   public static void closePort(int port) {
      closePort(port, "TCP");
   }

   public static void closeUdpPort(int port) {
      closePort(port, "UDP");
   }

   public static void closePort(int port, String protocol) {
      if (mappedPorts.containsKey(port + "_" + protocol)) {
         try {
            UPnPManager.GatewayInfo gateway = getCachedGateway();
            if (gateway == null) {
               return;
            }

            String soapBody = buildDeletePortMapping(port, protocol);
            sendSoapRequest(gateway, "DeletePortMapping", soapBody);
            mappedPorts.remove(port + "_" + protocol);
            LOGGER.info("UPnP: {} port {} closed", protocol, port);
         } catch (Exception var4) {
            LOGGER.error("UPnP close error: {}", var4.getMessage());
         }
      }

      renewTasks.keySet().removeIf(k -> k.endsWith("_" + protocol) && k.contains("_" + port + "_"));
   }

   public static void stop() {
      if (!stopped) {
         stopped = true;

         for (ScheduledFuture<?> f : renewTasks.values()) {
            f.cancel(false);
         }

         renewTasks.clear();
         RENEW_SCHEDULER.shutdownNow();
      }
   }

   public static boolean addPortMapping(int internalPort, int externalPort) {
      String key = internalPort + "_" + externalPort + "_UDP";

      try {
         UPnPManager.GatewayInfo gateway = getCachedGateway();
         if (gateway == null) {
            LOGGER.info("No UPnP gateway found");
            return false;
         } else {
            String localIp = getCachedLocalIp();
            if (localIp == null) {
               LOGGER.warn("Cannot get local IP");
               return false;
            } else {
               String soapBody = buildAddPortMappingLease(internalPort, externalPort, localIp, "VoxLink", "UDP", 3600);
               String response = sendSoapRequest(gateway, "AddPortMapping", soapBody);
               if (response != null && !response.contains("errorCode")) {
                  mappedPorts.put(externalPort + "_UDP", true);
                  scheduleRenewal(key, internalPort, externalPort);
                  return true;
               } else {
                  LOGGER.warn("UPnP: UDP port {} mapping failed", externalPort);
                  return false;
               }
            }
         }
      } catch (Exception var7) {
         LOGGER.error("UPnP mapping error: {}", var7.getMessage());
         return false;
      }
   }

   private static void scheduleRenewal(String key, int internalPort, int externalPort) {
      if (!stopped) {
         ScheduledFuture<?> old = renewTasks.remove(key);
         if (old != null) {
            old.cancel(false);
         }

         ScheduledFuture<?> f = RENEW_SCHEDULER.scheduleWithFixedDelay(() -> {
            if (!stopped && mappedPorts.containsKey(externalPort + "_UDP")) {
               try {
                  UPnPManager.GatewayInfo gw = getCachedGateway();
                  String ip = getCachedLocalIp();
                  if (gw == null || ip == null) {
                     LOGGER.warn("UPnP renew {}:{} skipped, no gateway/localIp", internalPort, externalPort);
                     return;
                  }

                  String body = buildAddPortMappingLease(internalPort, externalPort, ip, "VoxLink", "UDP", 3600);
                  String resp = sendSoapRequest(gw, "AddPortMapping", body);
                  if (resp != null && !resp.contains("errorCode")) {
                     LOGGER.info("UPnP renew {}:{} ok", internalPort, externalPort);
                  } else {
                     LOGGER.warn("UPnP renew {}:{} failed, dropping mapping", internalPort, externalPort);
                     mappedPorts.remove(externalPort + "_UDP");
                     ScheduledFuture<?> cur = renewTasks.remove(key);
                     if (cur != null) {
                        cur.cancel(false);
                     }
                  }
               } catch (Exception var8) {
                  LOGGER.warn("UPnP renew {}:{} exception: {}", new Object[]{internalPort, externalPort, var8.getMessage()});
               }
            } else {
               ScheduledFuture<?> cur = renewTasks.remove(key);
               if (cur != null) {
                  cur.cancel(false);
               }
            }
         }, 3000000L, 3000000L, TimeUnit.MILLISECONDS);
         renewTasks.put(key, f);
      }
   }

   private static void cancelRenewal(String key) {
      ScheduledFuture<?> f = renewTasks.remove(key);
      if (f != null) {
         f.cancel(false);
      }
   }

   public static boolean addPortMapping(int port) {
      return addPortMapping(port, port);
   }

   private static UPnPManager.GatewayInfo getCachedGateway() throws Exception {
      if (cachedGateway != null && System.currentTimeMillis() - gatewayCacheTime < 60000L) {
         return cachedGateway;
      } else {
         UPnPManager.GatewayInfo gateway = discoverGateway();
         if (gateway != null) {
            cachedGateway = gateway;
            gatewayCacheTime = System.currentTimeMillis();
         }

         return gateway;
      }
   }

   private static String getCachedLocalIp() throws Exception {
      if (cachedLocalIp != null && System.currentTimeMillis() - localIpCacheTime < 60000L) {
         return cachedLocalIp;
      } else {
         String ip = getLocalIp();
         if (ip != null) {
            cachedLocalIp = ip;
            localIpCacheTime = System.currentTimeMillis();
         }

         return ip;
      }
   }

   public static void tryMapAtStartup() {
      if (!startupAttempted) {
         synchronized (UPnPManager.class) {
            if (startupAttempted) {
               return;
            }

            startupAttempted = true;
         }

         startupFuture = CompletableFuture.runAsync(
            () -> {
               try {
                  long t0 = System.currentTimeMillis();
                  UPnPManager.GatewayInfo gw = getCachedGateway();
                  if (gw == null) {
                     LOGGER.info("[UPnP] Startup pre-discovery: no UPnP gateway found ({}ms)", System.currentTimeMillis() - t0);
                     return;
                  }

                  String localIp = getCachedLocalIp();
                  if (localIp == null) {
                     LOGGER.warn("[UPnP] Startup pre-discovery: cannot get local IP ({}ms)", System.currentTimeMillis() - t0);
                     return;
                  }

                  LOGGER.info(
                     "[UPnP] Startup pre-discovery success: gateway={} local IP={} ({}ms)",
                     new Object[]{gw.controlUrl, localIp, System.currentTimeMillis() - t0}
                  );
               } catch (Exception var4) {
                  LOGGER.warn("[UPnP] Startup pre-discovery exception: {}", var4.getMessage());
               }
            }
         );
         LOGGER.info("[UPnP] Attempting UPnP gateway pre-discovery at startup");
      }
   }

   private static UPnPManager.GatewayInfo discoverGateway() throws Exception {
      List<InetAddress> localAddresses = getLocalAddresses();
      String[] searchMessages = new String[]{
         "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\n\r\n",
         "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nST: urn:schemas-upnp-org:service:WANIPConnection:1\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\n\r\n"
      };
      AtomicReference<UPnPManager.GatewayInfo> found = new AtomicReference<>(null);
      List<Thread> threads = new ArrayList<>();
      List<DatagramSocket> searchSockets = Collections.synchronizedList(new ArrayList<>());
      AtomicBoolean socketsClosed = new AtomicBoolean(false);

      for (InetAddress localAddr : localAddresses) {
         for (String searchMsg : searchMessages) {
            if (found.get() != null) {
               break;
            }

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

                  socket.setSoTimeout(1500);
                  byte[] data = searchMsg.getBytes(StandardCharsets.UTF_8);
                  socket.send(new DatagramPacket(data, data.length, InetAddress.getByName("239.255.255.250"), 1900));
                  byte[] buf = new byte[4096];
                  DatagramPacket packet = new DatagramPacket(buf, buf.length);
                  long deadline = System.currentTimeMillis() + 1500L;

                  while (true) {
                     if (System.currentTimeMillis() >= deadline || found.get() != null) {
                        return;
                     }

                     try {
                        socket.receive(packet);
                        String response = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                        String location = extractHeader(response, "LOCATION");
                        if (location != null) {
                           UPnPManager.GatewayInfo gateway = parseGateway(location);
                           if (gateway != null) {
                              found.compareAndSet(null, gateway);
                              synchronized (searchSockets) {
                                 socketsClosed.set(true);

                                 for (DatagramSocket s : searchSockets) {
                                    try {
                                       if (s != null && !s.isClosed()) {
                                          s.close();
                                       }
                                    } catch (Exception var36) {
                                    }
                                 }

                                 searchSockets.clear();
                                 return;
                              }
                           }
                        }
                     } catch (SocketTimeoutException var39) {
                        return;
                     } catch (SocketException var40) {
                        if (!socketsClosed.get()) {
                           LOGGER.debug("SSDP socket error: {}", var40.getMessage());
                        }
                        break;
                     }
                  }
               } catch (Exception var41) {
                  return;
               } finally {
                  if (socket != null && !socket.isClosed() && !socketsClosed.get()) {
                     try {
                        socket.close();
                     } catch (Exception var35) {
                     }
                  }
               }
            }, "VoxLink-SSDP");
            t.setDaemon(true);
            threads.add(t);
            t.start();
         }
      }

      for (Thread t : threads) {
         t.join(2000L);
      }

      synchronized (searchSockets) {
         socketsClosed.set(true);

         for (DatagramSocket s : searchSockets) {
            try {
               if (s != null && !s.isClosed()) {
                  s.close();
               }
            } catch (Exception var14) {
            }
         }

         searchSockets.clear();
      }

      return found.get();
   }

   private static UPnPManager.GatewayInfo parseGateway(String location) {
      try {
         URL url = URI.create(location).toURL();
         HttpURLConnection conn = (HttpURLConnection)url.openConnection();
         conn.setConnectTimeout(3000);
         conn.setReadTimeout(3000);

         String xml;
         try (InputStream is = conn.getInputStream()) {
            xml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
         } finally {
            conn.disconnect();
         }

         String var201 = null;
         String controlUrl = null;
         int stIdx = xml.indexOf(":WANIPConnection:");
         if (stIdx < 0) {
            stIdx = xml.indexOf(":WANPPPConnection:");
         }

         if (stIdx >= 0) {
            int typeStart = xml.lastIndexOf("<serviceType>", stIdx);
            if (typeStart < 0) {
               typeStart = xml.lastIndexOf(":serviceType>", stIdx);
            }

            int typeEnd = xml.indexOf("</serviceType>", typeStart >= 0 ? typeStart : 0);
            if (typeEnd < 0 && typeStart >= 0) {
               int nsTypeEnd = xml.indexOf(":serviceType>", xml.indexOf(62, typeStart) + 1);
               if (nsTypeEnd > 0) {
                  typeEnd = xml.lastIndexOf("</", nsTypeEnd);
               }
            }

            if (typeStart >= 0 && typeEnd > typeStart) {
               var201 = xml.substring(xml.indexOf(62, typeStart) + 1, typeEnd);
            }

            int urlStart = xml.indexOf("<controlURL>", typeEnd >= 0 ? typeEnd : 0);
            if (urlStart < 0) {
               urlStart = xml.indexOf(":controlURL>", typeEnd >= 0 ? typeEnd : 0);
            }

            int urlEnd = xml.indexOf("</controlURL>", urlStart >= 0 ? urlStart : 0);
            if (urlEnd < 0 && urlStart >= 0) {
               int nsUrlEnd = xml.indexOf(":controlURL>", xml.indexOf(62, urlStart) + 1);
               if (nsUrlEnd > 0) {
                  urlEnd = xml.lastIndexOf("</", nsUrlEnd);
               }
            }

            if (urlStart >= 0 && urlEnd > urlStart) {
               controlUrl = xml.substring(xml.indexOf(62, urlStart) + 1, urlEnd);
            }
         }

         if (var201 != null && controlUrl != null) {
            int urlPort = url.getPort() != -1 ? url.getPort() : url.getDefaultPort();
            String baseUrl = url.getProtocol() + "://" + url.getHost() + ":" + urlPort;
            return new UPnPManager.GatewayInfo(baseUrl, controlUrl, var201);
         }
      } catch (Exception var19) {
         LOGGER.debug("Gateway parse failed: {}", var19.getMessage());
      }

      return null;
   }

   private static String sendSoapRequest(UPnPManager.GatewayInfo gateway, String action, String body) throws Exception {
      URL url = URI.create(gateway.baseUrl + gateway.controlUrl).toURL();
      HttpURLConnection conn = (HttpURLConnection)url.openConnection();
      conn.setRequestMethod("POST");
      conn.setDoOutput(true);
      conn.setConnectTimeout(3000);
      conn.setReadTimeout(3000);
      conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
      conn.setRequestProperty("SOAPAction", "\"" + gateway.serviceType + "#" + action + "\"");

      try (OutputStream os = conn.getOutputStream()) {
         os.write(body.getBytes(StandardCharsets.UTF_8));
      }

      if (conn.getResponseCode() >= 400) {
         conn.disconnect();
         return null;
      } else {
         String var6;
         try (InputStream is = conn.getInputStream()) {
            var6 = new String(is.readAllBytes(), StandardCharsets.UTF_8);
         } finally {
            conn.disconnect();
         }

         return var6;
      }
   }

   private static String buildAddPortMapping(int port, String localIp, String description, String protocol) {
      String safeDesc = description != null
         ? description.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
         : "VoxLink";
      return "<?xml version=\"1.0\"?>\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n<s:Body><m:AddPortMapping xmlns:m=\"urn:schemas-upnp-org:service:WANIPConnection:1\"><NewRemoteHost></NewRemoteHost><NewExternalPort>"
         + port
         + "</NewExternalPort><NewProtocol>"
         + protocol
         + "</NewProtocol><NewInternalPort>"
         + port
         + "</NewInternalPort><NewInternalClient>"
         + localIp
         + "</NewInternalClient><NewEnabled>1</NewEnabled><NewPortMappingDescription>"
         + safeDesc
         + "</NewPortMappingDescription><NewLeaseDuration>0</NewLeaseDuration></m:AddPortMapping></s:Body></s:Envelope>";
   }

   private static String buildAddPortMappingLease(int internalPort, int externalPort, String localIp, String description, String protocol, int leaseDuration) {
      String safeDesc = description != null
         ? description.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
         : "VoxLink";
      return "<?xml version=\"1.0\"?>\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n<s:Body><m:AddPortMapping xmlns:m=\"urn:schemas-upnp-org:service:WANIPConnection:1\"><NewRemoteHost></NewRemoteHost><NewExternalPort>"
         + externalPort
         + "</NewExternalPort><NewProtocol>"
         + protocol
         + "</NewProtocol><NewInternalPort>"
         + internalPort
         + "</NewInternalPort><NewInternalClient>"
         + localIp
         + "</NewInternalClient><NewEnabled>1</NewEnabled><NewPortMappingDescription>"
         + safeDesc
         + "</NewPortMappingDescription><NewLeaseDuration>"
         + leaseDuration
         + "</NewLeaseDuration></m:AddPortMapping></s:Body></s:Envelope>";
   }

   private static String buildDeletePortMapping(int port, String protocol) {
      return "<?xml version=\"1.0\"?>\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n<s:Body><m:DeletePortMapping xmlns:m=\"urn:schemas-upnp-org:service:WANIPConnection:1\"><NewRemoteHost></NewRemoteHost><NewExternalPort>"
         + port
         + "</NewExternalPort><NewProtocol>"
         + protocol
         + "</NewProtocol></m:DeletePortMapping></s:Body></s:Envelope>";
   }

   private static String extractHeader(String response, String header) {
      String searchLower = header.toLowerCase() + ":";
      int idx = -1;
      String lower = response.toLowerCase();

      for (int i = 0; i < lower.length() - searchLower.length(); i++) {
         if (lower.startsWith(searchLower, i) && (i == 0 || lower.charAt(i - 1) == '\n')) {
            idx = i;
            break;
         }
      }

      if (idx < 0) {
         return null;
      } else {
         int headerEnd = idx + searchLower.length();
         int end = response.indexOf("\r\n", headerEnd);
         if (end < 0) {
            end = response.indexOf("\n", headerEnd);
         }

         return end > headerEnd ? response.substring(headerEnd, end).trim() : null;
      }
   }

   private static List<InetAddress> getLocalAddresses() throws Exception {
      List<InetAddress> addresses = new ArrayList<>();
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

      while (interfaces.hasMoreElements()) {
         NetworkInterface ni = interfaces.nextElement();
         if (!ni.isLoopback() && !ni.isVirtual() && ni.isUp()) {
            Enumeration<InetAddress> addrEnum = ni.getInetAddresses();

            while (addrEnum.hasMoreElements()) {
               InetAddress addr = addrEnum.nextElement();
               if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                  addresses.add(addr);
               }
            }
         }
      }

      return addresses;
   }

   private static String getLocalIp() throws Exception {
      List<InetAddress> addresses = getLocalAddresses();
      return addresses.isEmpty() ? null : addresses.get(0).getHostAddress();
   }

   public static record GatewayInfo(String baseUrl, String controlUrl, String serviceType) {
   }

   public static record UPnPResult(boolean available, boolean success, int externalPort) {
   }
}
