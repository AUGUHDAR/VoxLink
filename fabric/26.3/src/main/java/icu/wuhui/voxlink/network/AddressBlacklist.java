package icu.wuhui.voxlink.network;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AddressBlacklist {
   private static final long UDP_BLACKLIST_MS = 3600000L;
   private static final long DIRECT_BLACKLIST_MS = 300000L;
   private static final int UDP_FAIL_THRESHOLD = 3;
   private static final long FAIL_WINDOW_MS = 600000L;
   /**
    * 同轮去重窗口：一次打洞轮次中多个 socket/子 puncher 会对同一目标几乎同时失败
    * （如 EasySym 25 个子 socket），只计 1 次失败，避免单轮即触发拉黑。
    */
   private static final long SAME_ROUND_DEDUPE_MS = 4000L;
   private static final AddressBlacklist INSTANCE = new AddressBlacklist();
   private final Map<InetSocketAddress, Long> expireAt = new ConcurrentHashMap<>();
   private final Map<InetSocketAddress, long[]> udpFailState = new ConcurrentHashMap<>();

   public static AddressBlacklist get() {
      return INSTANCE;
   }

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

   /** state: [0]=窗口内失败次数, [1]=窗口起始时间, [2]=最近一次计入失败的时戳 */
   public void recordUdpFailure(InetSocketAddress addr) {
      if (addr != null) {
         long now = System.currentTimeMillis();
         this.udpFailState.compute(addr, (k, st) -> {
            if (st == null || now - st[1] > FAIL_WINDOW_MS) {
               st = new long[]{0L, now, 0L};
            }

            // 同一轮次（短时间内的重复回调）只计一次，防止多 socket 扫描放大计数
            if (st.length >= 3 && now - st[2] < SAME_ROUND_DEDUPE_MS) {
               return st;
            }

            st[0]++;
            st[2] = now;
            if (st[0] >= UDP_FAIL_THRESHOLD) {
               AddressBlacklist.this.expireAt.put(addr, now + UDP_BLACKLIST_MS);
               return null;
            } else {
               return st;
            }
         });
      }
   }

   public void recordDirectFailure(InetSocketAddress addr) {
      if (addr != null) {
         this.expireAt.put(addr, System.currentTimeMillis() + DIRECT_BLACKLIST_MS);
         this.udpFailState.remove(addr);
      }
   }
}
