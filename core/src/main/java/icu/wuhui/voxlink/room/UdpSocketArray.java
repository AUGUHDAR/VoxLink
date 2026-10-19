package icu.wuhui.voxlink.room;

import icu.wuhui.voxlink.network.StunProbe;
import icu.wuhui.voxlink.network.UdpHolePuncher;
import java.util.ArrayList;
import java.util.List;

/** UDP socket 数组池条目(Phase4 从 ConnectionManager 提升为顶层类)。 */
final class UdpSocketArray {

      final List<UdpHolePuncher> punchers;

      final List<StunProbe.PublicMappedAddress> mappedAddrs;

      final long createTime;

      final boolean isEasySym;



      UdpSocketArray(List<UdpHolePuncher> punchers, List<StunProbe.PublicMappedAddress> mappedAddrs, boolean isEasySym) {

         this.punchers = new ArrayList<>(punchers);

         this.mappedAddrs = new ArrayList<>(mappedAddrs);

         this.createTime = System.currentTimeMillis();

         this.isEasySym = isEasySym;

      }



      boolean isReusable(int requiredSize, boolean requiredEasySym, long now) {

         if (now - this.createTime >= 30000L) {

            return false;

         }



         if (this.isEasySym != requiredEasySym) {

            return false;

         }



         if (this.punchers.size() < requiredSize) {

            return false;

         }



         for (UdpHolePuncher p : this.punchers) {

            if (p.getSocket() == null || p.getSocket().isClosed()) {

               return false;

            }



            if (p.isPunching()) {

               return false;

            }

         }



         return true;

      }



      void close() {

         for (UdpHolePuncher p : this.punchers) {

            try {

               p.close();

            } catch (Exception var4) {

            }

         }



         this.punchers.clear();

         this.mappedAddrs.clear();

      }

}
