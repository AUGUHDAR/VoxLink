package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.VoxLinkMod;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class StunProbe {
   private static final int PROBE_TIMEOUT_MS = 8000;
   private static final int DISCOVER_TIMEOUT_MS = 800;
   private static final int DUAL_STUN_TIMEOUT_MS = 800;
   private static final int STUN_INITIAL_RTO_MS = 200;
   private static final int STUN_MAX_RETRANSMISSIONS = 2;
   private static final int STUN_MAX_RTO_MS = 800;
   private static final double STUN_JITTER_FACTOR = 0.2;
   private static final int STUN_RECV_POLL_MS = 100;
   private static final int STUN_DEFAULT_PORT = 3478;
   private static final int EASY_SYM_PORT_DELTA_THRESHOLD = 100;
   private static final int EASY_SYM_PORT_DELTA_FALLBACK = 200;
   private static final int SYM_GRADE_SPAN_EASY_MAX = 15;
   private static final int SYM_GRADE_SAMPLE_SERVERS = 4;
   private static final int ATTR_CHANGE_REQUEST = 3;
   private static final int CHANGE_IP_FLAG = 4;
   private static final int CHANGE_PORT_FLAG = 2;
   private static final int RFC5780_MAX_TRIES = 3;
   private static final long CACHE_TTL_MS = 300000L;
   private static final long WARM_REUSE_TTL_MS = 20000L;
   private static final int TIMEOUT_MULTIPLIER = 2;
   private static final int SYM_DETECT_TIMEOUT_MS = 1500;
   private static volatile ExecutorService STUN_EXECUTOR = createExecutor();
   private static final AtomicReference<StunProbe.CacheEntry> cachedEntry = new AtomicReference<>();
   private static volatile String lastProbeLocalIp;

   public static StunProbe.PublicMappedAddress discoverMappedAddress(DatagramSocket socket, List<String> stunUrls) {
      int originalTimeout = -1;

      try {
         originalTimeout = socket.getSoTimeout();
         socket.setSoTimeout(100);
      } catch (Exception var38) {
      }

      VoxLinkMod.LOGGER
         .info("[StunProbe] Start probing, {} STUN servers, socket port={}, timeout={}ms", new Object[]{stunUrls.size(), socket.getLocalPort(), 800});

      try {
         List<byte[]> requests = new ArrayList<>();
         List<InetSocketAddress> targets = new ArrayList<>();
         int attempted = 0;

         for (String url : stunUrls) {
            try {
               StunProbe.ParsedStunUrl parsed = parseStunUrl(url);
               if (parsed != null) {
                  attempted++;
                  InetAddress address = InetAddress.getByName(parsed.host);
                  requests.add(createBindingRequest());
                  targets.add(new InetSocketAddress(address, parsed.port));
               }
            } catch (Exception var37) {
               VoxLinkMod.LOGGER.warn("[StunProbe] {} resolve failed: {}", url, var37.getMessage());
            }
         }

         if (requests.isEmpty()) {
            VoxLinkMod.LOGGER.warn("[StunProbe] Mapped address probe failed: attempted=0, timeouts=0, errors=0");
            return null;
         } else {
            long startTime = System.currentTimeMillis();
            long deadline = startTime + 800L;
            int[] sendCounts = new int[requests.size()];
            long[] nextResend = new long[requests.size()];

            for (int i = 0; i < requests.size(); i++) {
               try {
                  socket.send(new DatagramPacket(requests.get(i), requests.get(i).length, targets.get(i)));
               } catch (Exception var36) {
                  nextResend[i] = 4611686018427387903L;
               }

               sendCounts[i] = 1;
               nextResend[i] = startTime + (long)resendDelay(1);
            }

            byte[] receiveBuffer = new byte[576];
            DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);

            while (System.currentTimeMillis() < deadline) {
               long now = System.currentTimeMillis();

               for (int i = 0; i < requests.size(); i++) {
                  if (sendCounts[i] <= 2 && now >= nextResend[i]) {
                     try {
                        socket.send(new DatagramPacket(requests.get(i), requests.get(i).length, targets.get(i)));
                     } catch (Exception var35) {
                     }

                     sendCounts[i]++;
                     nextResend[i] = sendCounts[i] <= 2 ? now + (long)resendDelay(sendCounts[i]) : 4611686018427387903L;
                  }
               }

               try {
                  socket.receive(receivePacket);
               } catch (SocketTimeoutException var39) {
                  continue;
               } catch (IOException var40) {
                  return null;
               }

               byte[] responseData = new byte[receivePacket.getLength()];
               System.arraycopy(receivePacket.getData(), 0, responseData, 0, receivePacket.getLength());
               byte[] matchedRequest = null;

               for (byte[] request : requests) {
                  if (responseData.length >= 20) {
                     boolean match = true;

                     for (int k = 8; k < 20; k++) {
                        if (responseData[k] != request[k]) {
                           match = false;
                           break;
                        }
                     }

                     if (match) {
                        matchedRequest = request;
                        break;
                     }
                  }
               }

               if (matchedRequest != null) {
                  StunProbe.MappedAddress mapped = parseBindingResponse(responseData, matchedRequest);
                  if (mapped != null) {
                     VoxLinkMod.LOGGER.info("[StunProbe] Mapped address: {}:{} (sends=1)", mapped.ip, mapped.port);
                     return new StunProbe.PublicMappedAddress(mapped.ip, mapped.port);
                  }
               }
            }

            VoxLinkMod.LOGGER.warn("[StunProbe] Mapped address probe failed: attempted={}, timeouts={}, errors=0", attempted, requests.size());
            return null;
         }
      } finally {
         try {
            if (originalTimeout >= 0) {
               socket.setSoTimeout(originalTimeout);
            }
         } catch (Exception var34) {
         }
      }
   }

   public static StunProbe.PublicMappedAddress[] discoverMappedAddressDual(DatagramSocket socket, String stunUrl1, String stunUrl2) {
      int originalTimeout = -1;

      try {
         originalTimeout = socket.getSoTimeout();
         socket.setSoTimeout(100);
      } catch (Exception var34) {
      }

      VoxLinkMod.LOGGER.info("[StunProbe] Parallel dual STUN: {} + {}, socket port={}", new Object[]{stunUrl1, stunUrl2, socket.getLocalPort()});
      StunProbe.PublicMappedAddress[] results = new StunProbe.PublicMappedAddress[2];

      label209: {
         StunProbe.PublicMappedAddress[] req1;
         try {
            StunProbe.ParsedStunUrl u1 = parseStunUrl(stunUrl1);
            StunProbe.ParsedStunUrl u2 = parseStunUrl(stunUrl2);
            if (u1 != null && u2 != null) {
               byte[] req1x = createBindingRequest();
               byte[] req2 = createBindingRequest();
               InetAddress addr1 = InetAddress.getByName(u1.host);
               InetAddress addr2 = InetAddress.getByName(u2.host);
               socket.send(new DatagramPacket(req1x, req1x.length, addr1, u1.port));
               socket.send(new DatagramPacket(req2, req2.length, addr2, u2.port));
               byte[] buf = new byte[576];
               DatagramPacket pkt = new DatagramPacket(buf, buf.length);
               long startTime = System.currentTimeMillis();
               long deadline = startTime + 800L;
               long nextResendTime = startTime + (long)resendDelay(1);
               int got = 0;

               while (true) {
                  while (true) {
                     if (got >= 2 || System.currentTimeMillis() >= deadline) {
                        if (results[0] == null) {
                           results[0] = discoverMappedAddress(socket, List.of(stunUrl1));
                        }

                        if (results[1] == null) {
                           results[1] = discoverMappedAddress(socket, List.of(stunUrl2));
                        }
                        break label209;
                     }

                     long now = System.currentTimeMillis();
                     if (now >= nextResendTime) {
                        if (results[0] == null) {
                           socket.send(new DatagramPacket(req1x, req1x.length, addr1, u1.port));
                        }

                        if (results[1] == null) {
                           socket.send(new DatagramPacket(req2, req2.length, addr2, u2.port));
                        }

                        nextResendTime = 4611686018427387903L;
                     }

                     try {
                        socket.receive(pkt);
                        break;
                     } catch (SocketTimeoutException var35) {
                     }
                  }

                  byte[] data = new byte[pkt.getLength()];
                  System.arraycopy(pkt.getData(), 0, data, 0, pkt.getLength());
                  if (data.length >= 20) {
                     if (results[0] == null && matchTransaction(data, req1x)) {
                        StunProbe.MappedAddress m = parseBindingResponse(data, req1x);
                        if (m != null) {
                           results[0] = new StunProbe.PublicMappedAddress(m.ip, m.port);
                           got++;
                           continue;
                        }
                     }

                     if (results[1] == null && matchTransaction(data, req2)) {
                        StunProbe.MappedAddress m = parseBindingResponse(data, req2);
                        if (m != null) {
                           results[1] = new StunProbe.PublicMappedAddress(m.ip, m.port);
                           got++;
                        }
                     }
                  }
               }
            }

            if (u1 != null) {
               results[0] = discoverMappedAddress(socket, List.of(stunUrl1));
            }

            if (u2 != null) {
               results[1] = discoverMappedAddress(socket, List.of(stunUrl2));
            }

            req1 = results;
         } catch (Exception var36) {
            VoxLinkMod.LOGGER.warn("[StunProbe] Parallel dual STUN exception: {}", var36.getMessage());
            break label209;
         } finally {
            try {
               if (originalTimeout >= 0) {
                  socket.setSoTimeout(originalTimeout);
               }
            } catch (Exception var33) {
            }
         }

         return req1;
      }

      VoxLinkMod.LOGGER.info("[StunProbe] Dual STUN results: [0]={}, [1]={}", results[0], results[1]);
      return results;
   }

   public static StunProbe.PublicMappedAddress[] discoverMappedAddressRace(DatagramSocket socket, List<String> stunUrls, int need) {
      StunProbe.PublicMappedAddress[] results = new StunProbe.PublicMappedAddress[need];
      if (stunUrls != null && !stunUrls.isEmpty() && need > 0) {
         int originalTimeout = -1;

         try {
            originalTimeout = socket.getSoTimeout();
            socket.setSoTimeout(100);
         } catch (Exception var32) {
         }

         int n = stunUrls.size();
         StunProbe.ParsedStunUrl[] parsed = new StunProbe.ParsedStunUrl[n];
         byte[][] reqs = new byte[n][];
         VoxLinkMod.LOGGER.info("[StunProbe] Parallel race {} STUN take {} , socket port={}", new Object[]{n, need, socket.getLocalPort()});
         int got = 0;

         try {
            InetAddress[] addrs = new InetAddress[n];
            boolean[] answered = new boolean[n];

            for (int i = 0; i < n; i++) {
               parsed[i] = parseStunUrl(stunUrls.get(i));
               if (parsed[i] != null) {
                  reqs[i] = createBindingRequest();
                  addrs[i] = InetAddress.getByName(parsed[i].host);
                  socket.send(new DatagramPacket(reqs[i], reqs[i].length, addrs[i], parsed[i].port));
               }
            }

            byte[] buf = new byte[576];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            long startTime = System.currentTimeMillis();
            long deadline = startTime + 800L;
            long nextResendTime = startTime + (long)resendDelay(1);

            label198:
            while (true) {
               while (true) {
                  if (got >= need || System.currentTimeMillis() >= deadline) {
                     break label198;
                  }

                  long now = System.currentTimeMillis();
                  if (now >= nextResendTime) {
                     for (int ix = 0; ix < n; ix++) {
                        if (reqs[ix] != null && !answered[ix]) {
                           socket.send(new DatagramPacket(reqs[ix], reqs[ix].length, addrs[ix], parsed[ix].port));
                        }
                     }

                     nextResendTime = 4611686018427387903L;
                  }

                  try {
                     socket.receive(pkt);
                     break;
                  } catch (SocketTimeoutException var33) {
                  }
               }

               byte[] data = new byte[pkt.getLength()];
               System.arraycopy(pkt.getData(), 0, data, 0, pkt.getLength());
               if (data.length >= 20) {
                  for (int ixx = 0; ixx < n; ixx++) {
                     if (reqs[ixx] != null && !answered[ixx] && matchTransaction(data, reqs[ixx])) {
                        StunProbe.MappedAddress m = parseBindingResponse(data, reqs[ixx]);
                        if (m != null) {
                           answered[ixx] = true;
                           results[got] = new StunProbe.PublicMappedAddress(m.ip, m.port);
                           VoxLinkMod.LOGGER.info("[StunProbe] Race #{}: {} -> {}:{}", new Object[]{++got, stunUrls.get(ixx), m.ip, m.port});
                        }
                        break;
                     }
                  }
               }
            }
         } catch (Exception var34) {
            VoxLinkMod.LOGGER.warn("[StunProbe] Parallel race exception: {}", var34.getMessage());
         } finally {
            try {
               if (originalTimeout >= 0) {
                  socket.setSoTimeout(originalTimeout);
               }
            } catch (Exception var31) {
            }
         }

         VoxLinkMod.LOGGER.info("[StunProbe] Race done: got {}/{}", got, need);
         return results;
      } else {
         return results;
      }
   }

   public static StunProbe.PublicMappedAddress[] discoverMappedAddressQuad(
      DatagramSocket socket, String stunUrl1, String stunUrl2, String stunUrl3, String stunUrl4
   ) {
      int originalTimeout = -1;

      try {
         originalTimeout = socket.getSoTimeout();
         socket.setSoTimeout(100);
      } catch (Exception var35) {
      }

      String[] urls = new String[]{stunUrl1, stunUrl2, stunUrl3, stunUrl4};
      StunProbe.PublicMappedAddress[] results = new StunProbe.PublicMappedAddress[4];
      VoxLinkMod.LOGGER
         .info("[StunProbe] Parallel 4 STUN: {}+{}+{}+{}, socket port={}", new Object[]{stunUrl1, stunUrl2, stunUrl3, stunUrl4, socket.getLocalPort()});

      label241: {
         StunProbe.PublicMappedAddress[] var39;
         try {
            StunProbe.ParsedStunUrl[] parsed = new StunProbe.ParsedStunUrl[4];
            byte[][] reqs = new byte[4][];
            InetAddress[] addrs = new InetAddress[4];
            int validCount = 0;

            for (int i = 0; i < 4; i++) {
               parsed[i] = parseStunUrl(urls[i]);
               if (parsed[i] != null) {
                  reqs[i] = createBindingRequest();
                  addrs[i] = InetAddress.getByName(parsed[i].host);
                  socket.send(new DatagramPacket(reqs[i], reqs[i].length, addrs[i], parsed[i].port));
                  validCount++;
               }
            }

            if (validCount != 0) {
               byte[] buf = new byte[576];
               DatagramPacket pkt = new DatagramPacket(buf, buf.length);
               long startTime = System.currentTimeMillis();
               long deadline = startTime + 800L;
               long nextResendTime = startTime + (long)resendDelay(1);
               int got = 0;

               while (true) {
                  while (true) {
                     if (got >= validCount || System.currentTimeMillis() >= deadline) {
                        int ix = 0;

                        while (true) {
                           if (ix >= 2) {
                              break label241;
                           }

                           if (results[ix] == null && parsed[ix] != null) {
                              results[ix] = discoverMappedAddress(socket, List.of(urls[ix]));
                           }

                           ix++;
                        }
                     }

                     long now = System.currentTimeMillis();
                     if (now >= nextResendTime) {
                        for (int ix = 0; ix < 4; ix++) {
                           if (reqs[ix] != null && results[ix] == null) {
                              socket.send(new DatagramPacket(reqs[ix], reqs[ix].length, addrs[ix], parsed[ix].port));
                           }
                        }

                        nextResendTime = 4611686018427387903L;
                     }

                     try {
                        socket.receive(pkt);
                        break;
                     } catch (SocketTimeoutException var36) {
                     }
                  }

                  byte[] data = new byte[pkt.getLength()];
                  System.arraycopy(pkt.getData(), 0, data, 0, pkt.getLength());
                  if (data.length >= 20) {
                     for (int ixx = 0; ixx < 4; ixx++) {
                        if (results[ixx] == null && reqs[ixx] != null && matchTransaction(data, reqs[ixx])) {
                           StunProbe.MappedAddress m = parseBindingResponse(data, reqs[ixx]);
                           if (m != null) {
                              results[ixx] = new StunProbe.PublicMappedAddress(m.ip, m.port);
                              got++;
                           }
                           break;
                        }
                     }
                  }
               }
            }

            var39 = results;
         } catch (Exception var37) {
            VoxLinkMod.LOGGER.warn("[StunProbe] Parallel 4 STUN exception: {}", var37.getMessage());
            break label241;
         } finally {
            try {
               if (originalTimeout >= 0) {
                  socket.setSoTimeout(originalTimeout);
               }
            } catch (Exception var34) {
            }
         }

         return var39;
      }

      VoxLinkMod.LOGGER.info("[StunProbe] 4 STUN results: [0]={}, [1]={}, [2]={}, [3]={}", new Object[]{results[0], results[1], results[2], results[3]});
      return results;
   }

   private static boolean matchTransaction(byte[] data, byte[] request) {
      if (data.length < 20) {
         return false;
      } else {
         for (int i = 8; i < 20; i++) {
            if (data[i] != request[i]) {
               return false;
            }
         }

         return true;
      }
   }

   private static int resendDelay(int retransmissions) {
      int rto = 200 << retransmissions;
      rto = Math.min(rto, 800);
      int jitter = (int)((double)rto * 0.2);
      return rto - jitter + ThreadLocalRandom.current().nextInt(0, 2 * jitter + 1);
   }

   public static void shutdown() {
      ExecutorService e = STUN_EXECUTOR;
      if (e != null) {
         e.shutdown();

         try {
            if (!e.awaitTermination(2L, TimeUnit.SECONDS)) {
               e.shutdownNow();
            }
         } catch (InterruptedException var2) {
            e.shutdownNow();
            Thread.currentThread().interrupt();
         }
      }
   }

   private static ExecutorService createExecutor() {
      return Executors.newCachedThreadPool(r -> {
         Thread t = new Thread(r, "VoxLink-STUN");
         t.setDaemon(true);
         return t;
      });
   }

   private static ExecutorService executor() {
      ExecutorService e = STUN_EXECUTOR;
      if (e == null || e.isShutdown()) {
         synchronized (StunProbe.class) {
            if (STUN_EXECUTOR == null || STUN_EXECUTOR.isShutdown()) {
               STUN_EXECUTOR = createExecutor();
            }
         }
      }

      return STUN_EXECUTOR;
   }

   public static String getLastProbeLocalIp() {
      return lastProbeLocalIp;
   }

   public static void invalidateCache() {
      cachedEntry.set(null);
      StunCache.clear();
   }

   public static boolean isNetworkChanged() {
      String last = lastProbeLocalIp;
      String current = detectLocalIp();
      return last != null && !"unknown".equals(last) ? !last.equals(current) : !"unknown".equals(current);
   }

   private static String detectLocalIp() {
      try {
         String var1;
         try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 53);
            var1 = s.getLocalAddress().getHostAddress();
         }

         return var1;
      } catch (Exception var5) {
         return "unknown";
      }
   }

   public static StunProbe.ProbeResult getCachedResult() {
      StunProbe.CacheEntry entry = cachedEntry.get();
      return entry != null && System.currentTimeMillis() - entry.timestamp < 20000L ? entry.result : null;
   }

   private static void setCachedResult(StunProbe.ProbeResult result) {
      cachedEntry.set(new StunProbe.CacheEntry(result, System.currentTimeMillis()));
   }

   public static CompletableFuture<StunProbe.ProbeResult> probeAsync(List<List<String>> stunGroups) {
      return CompletableFuture.supplyAsync(() -> {
         StunProbe.ProbeResult result = probe(stunGroups);
         setCachedResult(result);
         if (!result.serverResults.isEmpty()) {
            StunProbe.StunServerResult saveResult = null;
            List<String> urls = new ArrayList<>();

            for (StunProbe.StunServerResult r : result.serverResults) {
               if (r.reachable) {
                  urls.add(r.url);
                  if (saveResult == null && r.mappedIp != null && r.mappedPort > 0) {
                     saveResult = r;
                  }
               }
            }

            if (saveResult != null) {
               StunCache.save(result.natType.key, saveResult.mappedIp, saveResult.mappedPort, urls);
            }
         }

         return result;
      }, executor());
   }

   private static StunProbe.NatType parseNatType(String key) {
      for (StunProbe.NatType t : StunProbe.NatType.values()) {
         if (t.key.equals(key)) {
            return t;
         }
      }

      return StunProbe.NatType.UNKNOWN;
   }

   public static StunProbe.ProbeResult probe(List<List<String>> stunGroups) {
      lastProbeLocalIp = detectLocalIp();
      List<CompletableFuture<StunProbe.StunServerResult>> futures = new ArrayList<>();

      for (List<String> group : stunGroups) {
         for (String url : group) {
            futures.add(probeSingleServerAsync(url));
         }
      }

      CountDownLatch twoSuccessLatch = new CountDownLatch(2);

      for (CompletableFuture<StunProbe.StunServerResult> f : futures) {
         f.whenComplete((result, ex) -> {
            if (result != null && result.reachable) {
               twoSuccessLatch.countDown();
            }
         });
      }

      try {
         twoSuccessLatch.await(10000L, TimeUnit.MILLISECONDS);
      } catch (InterruptedException var8) {
         Thread.currentThread().interrupt();
      }

      List<StunProbe.StunServerResult> allResults = new ArrayList<>();

      for (CompletableFuture<StunProbe.StunServerResult> f : futures) {
         try {
            allResults.add(f.getNow(new StunProbe.StunServerResult("", "", 0, false, -1L, null, 0)));
         } catch (Exception var7) {
         }
      }

      StunProbe.NatType natType = detectNatType(allResults);
      long reachableCount = allResults.stream().filter(r -> r.reachable).count();
      VoxLinkMod.LOGGER.info("[StunProbe] NAT={}, reachable={}/{}", new Object[]{natType.key, reachableCount, allResults.size()});
      return new StunProbe.ProbeResult(natType, allResults);
   }

   private static CompletableFuture<StunProbe.StunServerResult> probeSingleServerAsync(String stunUrl) {
      return CompletableFuture.supplyAsync(() -> probeSingleServer(stunUrl), executor());
   }

   private static StunProbe.StunServerResult probeSingleServer(String stunUrl) {
      StunProbe.ParsedStunUrl parsed = parseStunUrl(stunUrl);
      if (parsed == null) {
         return new StunProbe.StunServerResult(stunUrl, false, -1L, null);
      } else {
         DatagramSocket socket = null;

         StunProbe.StunServerResult var14;
         try {
            InetAddress address = InetAddress.getByName(parsed.host);
            socket = new DatagramSocket();
            socket.setSoTimeout(8000);
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
            StunProbe.MappedAddress mapped = parseBindingResponse(responseData, request);
            if (mapped == null) {
               VoxLinkMod.LOGGER.debug("[StunProbe] {} has response but no mapped address", stunUrl);
               return new StunProbe.StunServerResult(stunUrl, parsed.host, parsed.port, true, latencyMs, null, 0);
            }

            VoxLinkMod.LOGGER.debug("[StunProbe] {} reachable, latency={}ms, mapped={}:{}", new Object[]{stunUrl, latencyMs, mapped.ip, mapped.port});
            var14 = new StunProbe.StunServerResult(stunUrl, parsed.host, parsed.port, true, latencyMs, mapped.ip, mapped.port);
         } catch (Exception var25) {
            VoxLinkMod.LOGGER.debug("[StunProbe] {} unreachable: {}", stunUrl, var25.getMessage());
            return new StunProbe.StunServerResult(stunUrl, parsed.host, parsed.port, false, -1L, null, 0);
         } finally {
            if (socket != null) {
               try {
                  socket.close();
               } catch (Exception var24) {
               }
            }
         }

         return var14;
      }
   }

   public static StunProbe.NatType probeNatType(List<List<String>> stunGroups) {
      try {
         StunProbe.ProbeResult result = probe(stunGroups);
         return result != null ? result.natType : null;
      } catch (Exception var2) {
         VoxLinkMod.LOGGER.debug("[StunProbe] NAT type probe failed: {}", var2.getMessage());
         return null;
      }
   }

   private static byte[] createBindingRequestWithChange(boolean changeIp, boolean changePort) {
      int flags = (changeIp ? 4 : 0) | (changePort ? 2 : 0);
      byte[] request = new byte[28];
      request[0] = 0;
      request[1] = 1;
      request[2] = 0;
      request[3] = 8;
      request[4] = 33;
      request[5] = 18;
      request[6] = -92;
      request[7] = 66;
      byte[] tid = new byte[12];
      ThreadLocalRandom.current().nextBytes(tid);
      System.arraycopy(tid, 0, request, 8, 12);
      request[20] = 0;
      request[21] = 3;
      request[22] = 0;
      request[23] = 4;
      request[24] = 0;
      request[25] = 0;
      request[26] = 0;
      request[27] = (byte)flags;
      return request;
   }

   private static InetSocketAddress sendChangeRequest(DatagramSocket socket, String stunHost, int stunPort, boolean changeIp, boolean changePort) {
      try {
         InetAddress addr = InetAddress.getByName(stunHost);
         byte[] request = createBindingRequestWithChange(changeIp, changePort);
         socket.send(new DatagramPacket(request, request.length, addr, stunPort));
         byte[] buf = new byte[576];
         DatagramPacket recv = new DatagramPacket(buf, buf.length);

         try {
            socket.receive(recv);
         } catch (SocketTimeoutException var11) {
            return null;
         }

         byte[] resp = new byte[recv.getLength()];
         System.arraycopy(recv.getData(), 0, resp, 0, recv.getLength());
         if (resp.length >= 20 && matchTransaction(resp, request)) {
            int type = (resp[0] & 255) << 8 | resp[1] & 255;
            if (type == 273) {
               VoxLinkMod.LOGGER.info("[StunProbe] STUN error response, RFC5780 not supported");
               return null;
            } else {
               return type != 257 ? null : (InetSocketAddress)recv.getSocketAddress();
            }
         } else {
            return null;
         }
      } catch (Exception var12) {
         VoxLinkMod.LOGGER.debug("[StunProbe] CHANGE-REQUEST failed: {}", var12.getMessage());
         return null;
      }
   }

   private static StunProbe.NatType detectNatTypeWithRfc5780(DatagramSocket socket, String stunHost, int stunPort, int altStunPort) {
      int originalTimeout = -1;

      try {
         originalTimeout = socket.getSoTimeout();
         socket.setSoTimeout(800);
      } catch (Exception var37) {
      }

      Object var18;
      try {
         InetAddress addr = InetAddress.getByName(stunHost);
         byte[] req1 = createBindingRequest();
         socket.send(new DatagramPacket(req1, req1.length, addr, stunPort));
         byte[] buf = new byte[576];
         DatagramPacket recv = new DatagramPacket(buf, buf.length);

         try {
            socket.receive(recv);
         } catch (SocketTimeoutException var38) {
            VoxLinkMod.LOGGER.info("[StunProbe] RFC5780 Step1 no response, fallback");
            return null;
         }

         byte[] resp1 = new byte[recv.getLength()];
         System.arraycopy(recv.getData(), 0, resp1, 0, recv.getLength());
         StunProbe.MappedAddress m1 = parseBindingResponse(resp1, req1);
         if (m1 == null) {
            VoxLinkMod.LOGGER.info("[StunProbe] RFC5780 Step1 parse failed, fallback");
            return null;
         }

         InetSocketAddress changeResp = sendChangeRequest(socket, stunHost, stunPort, false, true);
         if (changeResp != null) {
            byte[] req3 = createBindingRequest();
            socket.send(new DatagramPacket(req3, req3.length, addr, altStunPort));
            DatagramPacket recv3 = new DatagramPacket(buf, buf.length);

            try {
               socket.receive(recv3);
               VoxLinkMod.LOGGER.info("[StunProbe] RFC5780 determined FullCone (CHANGE-REQUEST success + altPort response)");
               return StunProbe.NatType.FULL_CONE;
            } catch (SocketTimeoutException var39) {
               VoxLinkMod.LOGGER.info("[StunProbe] RFC5780 determined RestrictedCone (CHANGE-REQUEST success + altPort no response)");
               return StunProbe.NatType.RESTRICTED_CONE;
            }
         }

         byte[] reqSym = createBindingRequest();
         socket.send(new DatagramPacket(reqSym, reqSym.length, addr, altStunPort));
         DatagramPacket recvSym = new DatagramPacket(buf, buf.length);
         StunProbe.MappedAddress mSym = tryRecvMapped(socket, recvSym, reqSym);
         if (mSym != null && mSym.port != m1.port) {
            VoxLinkMod.LOGGER.info("[StunProbe] RFC5780 detected symmetric NAT ({} vs {}), fallback to delta method", m1.port, mSym.port);
            return null;
         }

         byte[] reqRe = createBindingRequest();
         socket.send(new DatagramPacket(reqRe, reqRe.length, addr, stunPort));
         DatagramPacket recvRe = new DatagramPacket(buf, buf.length);
         StunProbe.MappedAddress mRe = tryRecvMapped(socket, recvRe, reqRe);
         if (mRe == null || mRe.port == m1.port || mRe.port == 0) {
            VoxLinkMod.LOGGER.info("[StunProbe] RFC5780 determined PortRestricted (CHANGE-REQUEST no response, non-symmetric)");
            return StunProbe.NatType.PORT_RESTRICTED_CONE;
         }

         VoxLinkMod.LOGGER.info("[StunProbe] RFC5780 re-sample main port: {} -> {}, symmetric NAT, fallback to delta method", m1.port, mRe.port);
         var18 = null;
      } catch (Exception var40) {
         VoxLinkMod.LOGGER.debug("[StunProbe] RFC5780 exception: {}", var40.getMessage());
         return null;
      } finally {
         try {
            if (originalTimeout >= 0) {
               socket.setSoTimeout(originalTimeout);
            }
         } catch (Exception var36) {
         }
      }

      return (StunProbe.NatType)var18;
   }

   private static StunProbe.NatType detectNatType(List<StunProbe.StunServerResult> results) {
      List<StunProbe.StunServerResult> reachable = results.stream().filter(r -> r.reachable && r.mappedIp != null).toList();
      if (reachable.size() < 2) {
         VoxLinkMod.LOGGER.info("[StunProbe] Not enough reachable servers ({}), cannot determine NAT type", reachable.size());
         return StunProbe.NatType.UNKNOWN;
      } else {
         DatagramSocket socket = null;

         StunProbe.NatType var22;
         try {
            socket = new DatagramSocket();
            socket.setSoTimeout(8000);
            int rfc5780Tries = 0;

            for (StunProbe.StunServerResult s : reachable) {
               if (rfc5780Tries >= 3) {
                  break;
               }

               rfc5780Tries++;
               StunProbe.NatType rfc5780 = detectNatTypeWithRfc5780(socket, s.host, s.port, s.port + 1);
               if (rfc5780 != null) {
                  VoxLinkMod.LOGGER.info("[StunProbe] RFC5780 determined: {} -> {}", s.url, rfc5780.key);
                  return rfc5780;
               }
            }

            VoxLinkMod.LOGGER.info("[StunProbe] RFC5780 all failed ({} tried), fallback to two-server delta method", rfc5780Tries);
            StunProbe.StunServerResult first = reachable.get(0);
            StunProbe.StunServerResult second = reachable.get(1);
            InetAddress firstAddr = InetAddress.getByName(first.host);
            InetAddress secondAddr = InetAddress.getByName(second.host);
            byte[] req1 = createBindingRequest();
            byte[] req2 = createBindingRequest();
            socket.send(new DatagramPacket(req1, req1.length, firstAddr, first.port));
            socket.send(new DatagramPacket(req2, req2.length, secondAddr, second.port));
            StunProbe.MappedAddress mapped1 = null;
            StunProbe.MappedAddress mapped2 = null;
            byte[] buf = new byte[576];
            long deadline = System.currentTimeMillis() + 8000L;

            for (int i = 0; i < 2 && System.currentTimeMillis() < deadline; i++) {
               DatagramPacket recv = new DatagramPacket(buf, buf.length);

               try {
                  socket.receive(recv);
               } catch (SocketTimeoutException var29) {
                  break;
               }

               byte[] respData = new byte[recv.getLength()];
               System.arraycopy(recv.getData(), 0, respData, 0, recv.getLength());
               if (mapped1 == null) {
                  StunProbe.MappedAddress ma = parseBindingResponse(respData, req1);
                  if (ma != null) {
                     mapped1 = ma;
                  } else {
                     ma = parseBindingResponse(respData, req2);
                     if (ma != null) {
                        mapped2 = ma;
                     }
                  }
               } else {
                  StunProbe.MappedAddress ma = parseBindingResponse(respData, req2);
                  if (ma != null) {
                     mapped2 = ma;
                  } else {
                     ma = parseBindingResponse(respData, req1);
                     if (ma != null) {
                        mapped1 = ma;
                     }
                  }
               }
            }

            if (mapped1 == null || mapped2 == null) {
               return StunProbe.NatType.UNKNOWN;
            }

            if (mapped1.ip.equals(mapped2.ip) && mapped1.port == mapped2.port) {
               VoxLinkMod.LOGGER.info("[StunProbe] Non-symmetric NAT, mapped addresses same ({}:{})", mapped1.ip, mapped1.port);
               return StunProbe.NatType.PORT_RESTRICTED_CONE;
            }

            VoxLinkMod.LOGGER
               .info(
                  "[StunProbe] Mapped differs across servers ({}:{} vs {}:{}), confirm symmetric via same-server alt-port",
                  new Object[]{mapped1.ip, mapped1.port, mapped2.ip, mapped2.port}
               );
            boolean symConfirmed = false;

            for (int alt = 1; alt <= 3 && !symConfirmed; alt++) {
               int altPort = first.port + alt;
               if (altPort > 65535) {
                  break;
               }

               try {
                  byte[] reqAlt = createBindingRequest();
                  socket.send(new DatagramPacket(reqAlt, reqAlt.length, firstAddr, altPort));
                  DatagramPacket recvAlt = new DatagramPacket(buf, buf.length);

                  try {
                     socket.receive(recvAlt);
                  } catch (SocketTimeoutException var30) {
                     continue;
                  }

                  byte[] respAlt = new byte[recvAlt.getLength()];
                  System.arraycopy(recvAlt.getData(), 0, respAlt, 0, recvAlt.getLength());
                  StunProbe.MappedAddress mAlt = parseBindingResponse(respAlt, reqAlt);
                  if (mAlt != null) {
                     VoxLinkMod.LOGGER.info("[StunProbe] Same-server alt-port {}:{} mapped {}:{}", new Object[]{first.host, altPort, mAlt.ip, mAlt.port});
                     if (mAlt.port != mapped1.port) {
                        symConfirmed = true;
                     }
                  }
               } catch (Exception var31) {
                  break;
               }
            }

            if (!symConfirmed) {
               VoxLinkMod.LOGGER.info("[StunProbe] Same-server alt-port not confirmed, treat as PortRestricted (per-destination mapping stable)");
               return StunProbe.NatType.PORT_RESTRICTED_CONE;
            }

            VoxLinkMod.LOGGER.info("[StunProbe] Confirmed symmetric NAT (same-server alt-port differs), determine direction");
            StunProbe.NatType graded = gradeSymmetricSeverity(socket, reachable, mapped1.port);
            if (graded != null) {
               return graded;
            }

            StunProbe.NatType easyType = detectEasySymmetric(socket, mapped1.port, reachable);
            if (easyType != null) {
               return easyType;
            }

            int twoDiff = mapped2.port - mapped1.port;
            int absDiff = Math.abs(twoDiff);
            if (!mapped1.ip.equals(mapped2.ip)) {
               VoxLinkMod.LOGGER.info("[StunProbe] EasySym fallback: public IP differs ({} vs {}), determined HardSym", mapped1.ip, mapped2.ip);
               return StunProbe.NatType.SYMMETRIC;
            }

            int threshold = mapped1.port > 0 && mapped2.port > 0 ? 100 : 200;
            if (absDiff <= 0 || absDiff >= threshold) {
               VoxLinkMod.LOGGER
                  .info(
                     "[StunProbe] EasySym fallback: {}->{} diff={}>={}, threshold={}, determined HardSym",
                     new Object[]{mapped1.port, mapped2.port, absDiff, threshold, threshold}
                  );
               VoxLinkMod.LOGGER.debug("Port delta {} -> {}", twoDiff, StunProbe.NatType.SYMMETRIC.key);
               return StunProbe.NatType.SYMMETRIC;
            }

            StunProbe.NatType easyFallback = twoDiff > 0 ? StunProbe.NatType.SYMMETRIC_EASY_INC : StunProbe.NatType.SYMMETRIC_EASY_DEC;
            VoxLinkMod.LOGGER
               .info(
                  "[StunProbe] EasySym fallback (3-sample failed): {}->{} diff={}, threshold={}, determined {}",
                  new Object[]{mapped1.port, mapped2.port, twoDiff, threshold, easyFallback.key}
               );
            VoxLinkMod.LOGGER.debug("Port delta {} -> {}", twoDiff, easyFallback.key);
            var22 = easyFallback;
         } catch (Exception var32) {
            VoxLinkMod.LOGGER.debug("[StunProbe] NAT detection failed: {}", var32.getMessage());
            return StunProbe.NatType.UNKNOWN;
         } finally {
            if (socket != null) {
               socket.close();
            }
         }

         return var22;
      }
   }

   private static StunProbe.NatType gradeSymmetricSeverity(DatagramSocket socket, List<StunProbe.StunServerResult> reachable, int refPort) {
      try {
         List<String> urls = new ArrayList<>();

         for (StunProbe.StunServerResult r : reachable) {
            urls.add(r.url);
         }

         int n = Math.min(4, urls.size());
         if (n < 2) {
            return null;
         } else {
            StunProbe.PublicMappedAddress[] samples = discoverMappedAddressRace(socket, urls.subList(0, n), n);
            List<Integer> ports = new ArrayList<>();

            for (StunProbe.PublicMappedAddress s : samples) {
               if (s != null && s.port > 0) {
                  ports.add(s.port);
               }
            }

            if (ports.size() < 2) {
               VoxLinkMod.LOGGER.info("[StunProbe] Sym grade: only {}/{} samples, fallback to legacy path", ports.size(), n);
               return null;
            } else {
               int min = Integer.MAX_VALUE;
               int max = Integer.MIN_VALUE;

               for (int p : ports) {
                  if (p < min) {
                     min = p;
                  }

                  if (p > max) {
                     max = p;
                  }
               }

               int span = max - min;
               VoxLinkMod.LOGGER.info("[StunProbe] Sym grade: samples={}, span={} (easy<={})", new Object[]{ports, span, 15});
               if (span > 15) {
                  return StunProbe.NatType.HARD_SYM;
               } else {
                  StunProbe.PublicMappedAddress extra = discoverMappedAddress(socket, List.of(urls.get(0)));
                  if (extra != null && extra.port > 0) {
                     int delta = extra.port - refPort;
                     VoxLinkMod.LOGGER
                        .info("[StunProbe] Sym grade extra bind: ref={}, extra={}, delta={}, threshold={}", new Object[]{refPort, extra.port, delta, 100});
                     if (delta > 0 && delta < 100) {
                        return StunProbe.NatType.EASY_SYM_INC;
                     } else if (delta < 0 && delta > -100) {
                        return StunProbe.NatType.EASY_SYM_DEC;
                     } else {
                        return delta == 0 ? StunProbe.NatType.EASY_SYM_INC : StunProbe.NatType.HARD_SYM;
                     }
                  } else {
                     VoxLinkMod.LOGGER.info("[StunProbe] Sym grade: extra bind failed, determined HardSym");
                     return StunProbe.NatType.HARD_SYM;
                  }
               }
            }
         }
      } catch (Exception var12) {
         VoxLinkMod.LOGGER.debug("[StunProbe] Sym grade failed: {}", var12.getMessage());
         return null;
      }
   }

   private static StunProbe.NatType detectEasySymmetric(DatagramSocket socket, int basePort, List<StunProbe.StunServerResult> reachable) {
      StunProbe.StunServerResult third;
      if (reachable.size() >= 3) {
         third = reachable.get(2);
      } else {
         if (reachable.size() < 1) {
            VoxLinkMod.LOGGER.info("[StunProbe] EasySym detection: no STUN available, skip 3rd sample");
            return null;
         }

         third = reachable.get(0);
         VoxLinkMod.LOGGER
            .info("[StunProbe] EasySym detection: only {} reachable STUN, reuse {}:{} for 3rd sample", new Object[]{reachable.size(), third.host, third.port});
      }

      try {
         socket.setSoTimeout(1500);
         InetAddress thirdAddr = InetAddress.getByName(third.host);
         byte[] req = createBindingRequest();
         socket.send(new DatagramPacket(req, req.length, thirdAddr, third.port));
         byte[] buf = new byte[576];
         DatagramPacket recv = new DatagramPacket(buf, buf.length);
         socket.receive(recv);
         byte[] respData = new byte[recv.getLength()];
         System.arraycopy(recv.getData(), 0, respData, 0, recv.getLength());
         StunProbe.MappedAddress extraMapped = parseBindingResponse(respData, req);
         if (extraMapped != null) {
            int diff = extraMapped.port - basePort;
            int absDiff = Math.abs(diff);
            int threshold = basePort > 0 && extraMapped.port > 0 ? 100 : 200;
            VoxLinkMod.LOGGER
               .info(
                  "[StunProbe] EasySym detection (same socket 3rd server): basePort={}, extraPort={}, diff={}, threshold={}",
                  new Object[]{basePort, extraMapped.port, diff, threshold}
               );
            if (diff > 0 && diff < threshold) {
               VoxLinkMod.LOGGER.info("[StunProbe] EasySym increment (port+{}, threshold={})", diff, threshold);
               VoxLinkMod.LOGGER.debug("Port delta {} -> {}", diff, StunProbe.NatType.SYMMETRIC_EASY_INC.key);
               return StunProbe.NatType.SYMMETRIC_EASY_INC;
            }

            if (diff < 0 && diff > -threshold) {
               VoxLinkMod.LOGGER.info("[StunProbe] EasySym decrement (port{}, threshold={})", diff, threshold);
               VoxLinkMod.LOGGER.debug("Port delta {} -> {}", diff, StunProbe.NatType.SYMMETRIC_EASY_DEC.key);
               return StunProbe.NatType.SYMMETRIC_EASY_DEC;
            }

            if (absDiff >= threshold) {
               VoxLinkMod.LOGGER.info("[StunProbe] EasySym detection: diff={}>={}, determined HardSym", absDiff, threshold);
               VoxLinkMod.LOGGER.debug("Port delta {} -> {}", diff, StunProbe.NatType.SYMMETRIC.key);
               return StunProbe.NatType.SYMMETRIC;
            }

            VoxLinkMod.LOGGER.info("[StunProbe] EasySym detection: diff=0 (NAT reuses port), still EasySym");
            return StunProbe.NatType.SYMMETRIC_EASY_INC;
         }
      } catch (Exception var13) {
         VoxLinkMod.LOGGER.debug("[StunProbe] EasySym detection failed: {}", var13.getMessage());
      }

      return null;
   }

   private static byte[] createBindingRequest() {
      byte[] request = new byte[20];
      request[0] = 0;
      request[1] = 1;
      request[2] = 0;
      request[3] = 0;
      request[4] = 33;
      request[5] = 18;
      request[6] = -92;
      request[7] = 66;
      byte[] transactionId = new byte[12];
      ThreadLocalRandom.current().nextBytes(transactionId);
      System.arraycopy(transactionId, 0, request, 8, 12);
      return request;
   }

   private static StunProbe.MappedAddress parseBindingResponse(byte[] data, byte[] originalRequest) {
      if (data.length < 20) {
         return null;
      } else {
         int type = (data[0] & 255) << 8 | data[1] & 255;
         if (type != 257) {
            return null;
         } else if (data[4] == 33 && data[5] == 18 && data[6] == -92 && data[7] == 66) {
            for (int i = 8; i < 20; i++) {
               if (data[i] != originalRequest[i]) {
                  return null;
               }
            }

            int msgLen = (data[2] & 255) << 8 | data[3] & 255;
            int maxMsgLen = data.length - 20;
            if (msgLen > maxMsgLen) {
               msgLen = maxMsgLen;
            }

            VoxLinkMod.LOGGER.debug("[StunProbe] Binding response: dataLen={}, msgLen={}", data.length, msgLen);
            int offset = 20;

            while (offset + 4 <= data.length && offset - 20 < msgLen) {
               int attrType = (data[offset] & 255) << 8 | data[offset + 1] & 255;
               int attrLen = (data[offset + 2] & 255) << 8 | data[offset + 3] & 255;
               if (offset + 4 + attrLen > data.length) {
                  break;
               }

               if (attrType == 32) {
                  return parseXorMappedAddress(data, offset, attrLen, originalRequest);
               }

               if (attrType == 1) {
                  return parseMappedAddress(data, offset, attrLen);
               }

               if (attrType == 32800) {
                  return parseXorMappedAddress(data, offset, attrLen, originalRequest);
               }

               if (attrType == 32808) {
                  VoxLinkMod.LOGGER.debug("[StunProbe] Skip FINGERPRINT");
               } else if (attrType == 32802) {
                  VoxLinkMod.LOGGER.debug("[StunProbe] Skip SOFTWARE(len={})", attrLen);
               } else if (attrType == 32809) {
                  VoxLinkMod.LOGGER.debug("[StunProbe] Skip MESSAGE-INTEGRITY(len={})", attrLen);
               } else {
                  VoxLinkMod.LOGGER.warn("[StunProbe] Unknown attribute: 0x{}, len={}", Integer.toHexString(attrType), attrLen);
               }

               offset += 4 + attrLen;
               if (attrLen % 4 != 0) {
                  offset += 4 - attrLen % 4;
               }
            }

            return null;
         } else {
            return null;
         }
      }
   }

   private static StunProbe.MappedAddress parseXorMappedAddress(byte[] data, int offset, int attrLen, byte[] originalRequest) {
      if (attrLen >= 8 && offset + 8 <= data.length) {
         byte family = data[offset + 5];
         int xorPort = (data[offset + 6] & 255) << 8 | data[offset + 7] & 255;
         int port = xorPort ^ 8466;
         if (family == 1) {
            if (offset + 12 > data.length) {
               return null;
            } else {
               int xorIp = (data[offset + 8] & 255) << 24 | (data[offset + 9] & 255) << 16 | (data[offset + 10] & 255) << 8 | data[offset + 11] & 255;
               int ip = xorIp ^ 554869826;
               String ipStr = (ip >> 24 & 0xFF) + "." + (ip >> 16 & 0xFF) + "." + (ip >> 8 & 0xFF) + "." + (ip & 0xFF);
               return new StunProbe.MappedAddress(ipStr, port);
            }
         } else if (family != 2) {
            return null;
         } else if (attrLen >= 20 && offset + 24 <= data.length) {
            byte[] xorKey = new byte[16];
            System.arraycopy(originalRequest, 4, xorKey, 0, 16);
            byte[] ipBytes = new byte[16];

            for (int i = 0; i < 16; i++) {
               ipBytes[i] = (byte)(data[offset + 8 + i] & 255 ^ xorKey[i] & 255);
            }

            try {
               InetAddress ipv6Addr = InetAddress.getByAddress(ipBytes);
               return new StunProbe.MappedAddress(ipv6Addr.getHostAddress(), port);
            } catch (Exception var10) {
               return null;
            }
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   private static StunProbe.MappedAddress parseMappedAddress(byte[] data, int offset, int attrLen) {
      if (attrLen >= 8 && offset + 8 <= data.length) {
         byte family = data[offset + 5];
         int port = (data[offset + 6] & 255) << 8 | data[offset + 7] & 255;
         if (family != 1) {
            return null;
         } else if (offset + 12 > data.length) {
            return null;
         } else {
            String ip = (data[offset + 8] & 0xFF) + "." + (data[offset + 9] & 0xFF) + "." + (data[offset + 10] & 0xFF) + "." + (data[offset + 11] & 0xFF);
            return new StunProbe.MappedAddress(ip, port);
         }
      } else {
         return null;
      }
   }

   private static StunProbe.ParsedStunUrl parseStunUrl(String stunUrl) {
      String stripped = stunUrl.replace("stun:", "").replace("stuns:", "");
      if (stripped.startsWith("[")) {
         int bracketEnd = stripped.indexOf(93);
         if (bracketEnd > 0) {
            String host = stripped.substring(1, bracketEnd);
            int port = 0;
            if (stripped.length() > bracketEnd + 2 && stripped.charAt(bracketEnd + 1) == ':') {
               try {
                  port = Integer.parseInt(stripped.substring(bracketEnd + 2));
               } catch (NumberFormatException var6) {
               }
            }

            return new StunProbe.ParsedStunUrl(host, port > 0 ? port : 3478);
         }
      }

      if (stripped.contains(":")) {
         int lastColon = stripped.lastIndexOf(":");
         if (lastColon > 0 && lastColon < stripped.length() - 1) {
            String host = stripped.substring(0, lastColon);
            String portStr = stripped.substring(lastColon + 1);
            if (host.contains(":")) {
               VoxLinkMod.LOGGER.warn("[StunProbe] IPv6 without brackets, ambiguous, ignore: {}", stunUrl);
               return null;
            }

            try {
               int port = Integer.parseInt(portStr);
               if (port > 0 && port <= 65535) {
                  return new StunProbe.ParsedStunUrl(host, port);
               }
            } catch (NumberFormatException var7) {
            }
         }

         return new StunProbe.ParsedStunUrl(stripped, 3478);
      } else {
         return new StunProbe.ParsedStunUrl(stripped, 3478);
      }
   }

   public static List<Integer> samplePortsSequential(DatagramSocket socket, List<String> stunUrls, int count, int intervalMs) {
      if (stunUrls != null && !stunUrls.isEmpty()) {
         List<Integer> ports = new ArrayList<>();
         int originalTimeout = -1;

         try {
            originalTimeout = socket.getSoTimeout();
            socket.setSoTimeout(100);
         } catch (Exception var31) {
         }

         String selectedHost = null;
         int selectedPort = 3478;
         int n = stunUrls.size();
         StunProbe.ParsedStunUrl[] parsed = new StunProbe.ParsedStunUrl[n];
         byte[][] reqs = new byte[n][];

         try {
            InetAddress[] addrs = new InetAddress[n];

            for (int i = 0; i < n; i++) {
               parsed[i] = parseStunUrl(stunUrls.get(i));
               if (parsed[i] != null) {
                  reqs[i] = createBindingRequest();
                  addrs[i] = InetAddress.getByName(parsed[i].host);
                  socket.send(new DatagramPacket(reqs[i], reqs[i].length, addrs[i], parsed[i].port));
               }
            }

            byte[] buf = new byte[576];
            DatagramPacket recv = new DatagramPacket(buf, buf.length);
            long raceStartTime = System.currentTimeMillis();
            long deadline = raceStartTime + 800L;
            long nextResendTime = raceStartTime + (long)resendDelay(1);

            label172:
            while (true) {
               while (true) {
                  if (selectedHost != null || System.currentTimeMillis() >= deadline) {
                     break label172;
                  }

                  long now = System.currentTimeMillis();
                  if (now >= nextResendTime) {
                     for (int ix = 0; ix < n; ix++) {
                        if (reqs[ix] != null) {
                           socket.send(new DatagramPacket(reqs[ix], reqs[ix].length, addrs[ix], parsed[ix].port));
                        }
                     }

                     nextResendTime = 4611686018427387903L;
                  }

                  try {
                     socket.receive(recv);
                     break;
                  } catch (SocketTimeoutException var35) {
                  }
               }

               byte[] respData = new byte[recv.getLength()];
               System.arraycopy(recv.getData(), 0, respData, 0, recv.getLength());
               if (respData.length >= 20) {
                  for (int ixx = 0; ixx < n; ixx++) {
                     if (reqs[ixx] != null && matchTransaction(respData, reqs[ixx])) {
                        StunProbe.MappedAddress ma = parseBindingResponse(respData, reqs[ixx]);
                        if (ma != null) {
                           selectedHost = parsed[ixx].host;
                           selectedPort = parsed[ixx].port;
                           ports.add(ma.port);
                           VoxLinkMod.LOGGER
                              .info("[StunProbe] P-PRE race selected STUN: {}:{}, first port={}", new Object[]{selectedHost, selectedPort, ma.port});
                        }
                        break;
                     }
                  }
               }
            }
         } catch (Exception var36) {
            VoxLinkMod.LOGGER.debug("[StunProbe] P-PRE race exception: {}", var36.getMessage());
         }

         if (selectedHost == null) {
            VoxLinkMod.LOGGER.warn("[StunProbe] P-PRE race no response, sampling failed");

            try {
               if (originalTimeout >= 0) {
                  socket.setSoTimeout(originalTimeout);
               }
            } catch (Exception var28) {
            }

            return ports;
         } else {
            try {
               socket.setSoTimeout(100);
            } catch (Exception var30) {
            }

            for (int ixxx = 1; ixxx < count; ixxx++) {
               try {
                  byte[] req = createBindingRequest();
                  InetAddress addr = InetAddress.getByName(selectedHost);
                  DatagramPacket sendPkt = new DatagramPacket(req, req.length, addr, selectedPort);
                  socket.send(sendPkt);
                  byte[] buf = new byte[576];
                  DatagramPacket recv = new DatagramPacket(buf, buf.length);
                  long sampleStartTime = System.currentTimeMillis();
                  long deadline = sampleStartTime + 800L;
                  int sendCount = 1;
                  long nextResendTime = sampleStartTime + (long)resendDelay(1);

                  while (System.currentTimeMillis() < deadline) {
                     long now = System.currentTimeMillis();
                     if (now >= nextResendTime && sendCount <= 2) {
                        socket.send(sendPkt);
                        sendCount++;
                        nextResendTime = sendCount <= 2 ? now + (long)resendDelay(sendCount) : 4611686018427387903L;
                     }

                     try {
                        socket.receive(recv);
                     } catch (SocketTimeoutException var33) {
                        continue;
                     }

                     byte[] respData = new byte[recv.getLength()];
                     System.arraycopy(recv.getData(), 0, respData, 0, recv.getLength());
                     if (respData.length >= 20) {
                        StunProbe.MappedAddress ma = parseBindingResponse(respData, req);
                        if (ma != null) {
                           ports.add(ma.port);
                           break;
                        }
                     }
                  }

                  if (ixxx < count - 1) {
                     try {
                        Thread.sleep((long)intervalMs);
                     } catch (InterruptedException var32) {
                        Thread.currentThread().interrupt();
                        break;
                     }
                  }
               } catch (Exception var34) {
                  VoxLinkMod.LOGGER.debug("[StunProbe] P-PRE sample #{}/{} failed: {}", new Object[]{ixxx + 1, count, var34.getMessage()});
               }
            }

            try {
               if (originalTimeout >= 0) {
                  socket.setSoTimeout(originalTimeout);
               }
            } catch (Exception var29) {
            }

            return ports;
         }
      } else {
         return Collections.emptyList();
      }
   }

   private static StunProbe.MappedAddress tryRecvMapped(DatagramSocket socket, DatagramPacket recv, byte[] originalRequest) {
      try {
         socket.setSoTimeout(800);
         socket.receive(recv);
         byte[] resp = new byte[recv.getLength()];
         System.arraycopy(recv.getData(), 0, resp, 0, recv.getLength());

         try {
            socket.setSoTimeout(8000);
         } catch (Exception var5) {
         }

         return parseBindingResponse(resp, originalRequest);
      } catch (Exception var6) {
         return null;
      }
   }

   private static final class CacheEntry {
      final StunProbe.ProbeResult result;
      final long timestamp;

      CacheEntry(StunProbe.ProbeResult result, long timestamp) {
         this.result = result;
         this.timestamp = timestamp;
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

   public static enum NatType {
      UNKNOWN("unknown"),
      FULL_CONE("full_cone"),
      RESTRICTED_CONE("restricted_cone"),
      PORT_RESTRICTED_CONE("port_restricted_cone"),
      SYMMETRIC_EASY_INC("symmetric_easy_inc"),
      SYMMETRIC_EASY_DEC("symmetric_easy_dec"),
      SYMMETRIC("symmetric"),
      EASY_SYM_INC("symmetric_easy_inc"),
      EASY_SYM_DEC("symmetric_easy_dec"),
      HARD_SYM("symmetric");

      public final String key;

      private NatType(String key) {
         this.key = key;
      }

      public boolean isSymmetric() {
         return this == SYMMETRIC
            || this == SYMMETRIC_EASY_INC
            || this == SYMMETRIC_EASY_DEC
            || this == EASY_SYM_INC
            || this == EASY_SYM_DEC
            || this == HARD_SYM;
      }

      public boolean isEasySymmetric() {
         return this == SYMMETRIC_EASY_INC || this == SYMMETRIC_EASY_DEC || this == EASY_SYM_INC || this == EASY_SYM_DEC;
      }

      public boolean isHardSymmetric() {
         return this == SYMMETRIC || this == HARD_SYM;
      }

      public boolean canHolePunch() {
         return this != SYMMETRIC && this != HARD_SYM;
      }
   }

   private static record ParsedStunUrl(String host, int port) {
   }

   public static class ProbeResult {
      public final StunProbe.NatType natType;
      public final List<StunProbe.StunServerResult> serverResults;
      public final List<String> reachableStunUrls;

      ProbeResult(StunProbe.NatType natType, List<StunProbe.StunServerResult> serverResults) {
         this.natType = natType;
         this.serverResults = Collections.unmodifiableList(new ArrayList<>(serverResults));
         List<String> urls = new ArrayList<>();

         for (StunProbe.StunServerResult r : serverResults) {
            if (r.reachable) {
               urls.add(r.url);
            }
         }

         this.reachableStunUrls = Collections.unmodifiableList(urls);
      }
   }

   public static record PublicMappedAddress(String ip, int port) {
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
}
