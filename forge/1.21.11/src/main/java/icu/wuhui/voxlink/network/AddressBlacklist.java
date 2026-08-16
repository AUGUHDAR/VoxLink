package icu.wuhui.voxlink.network;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AddressBlacklist {
   private static final long UDP_BLACKLIST_MS = 3600000L;
   private static final long DIRECT_BLACKLIST_MS = 300000L;
   private static final int UDP_FAIL_THRESHOLD = 3;
   private static final long FAIL_WINDOW_MS = 600000L;
   private final Map<InetSocketAddress, Long> expireAt = new ConcurrentHashMap<>();
   private final Map<InetSocketAddress, long[]> udpFailState = new ConcurrentHashMap<>();

   public boolean isBlacklisted(InetSocketAddress addr) {
      if (addr == null) {
         return false;
      } else {
         Long exp = this.expireAt.get(addr);
         if (exp == null) {
            return false;
         } else if (System.currentTimeMillis() >= exp) {
            this.expireAt.remove(addr, exp);
            return false;
         } else {
            return true;
         }
      }
   }

   public void recordUdpFailure(InetSocketAddress addr) {
      if (addr != null) {
         long now = System.currentTimeMillis();
         this.udpFailState.compute(addr, (k, st) -> {
            if (st == null || now - st[1] > 600000L) {
               st = new long[]{0L, now};
            }

            st[0]++;
            if (st[0] >= 3L) {
               this.expireAt.put(addr, now + 3600000L);
               return null;
            } else {
               return (long[])st;
            }
         });
      }
   }

   public void recordDirectFailure(InetSocketAddress addr) {
      if (addr != null) {
         this.expireAt.put(addr, System.currentTimeMillis() + 300000L);
         this.udpFailState.remove(addr);
      }
   }
}
