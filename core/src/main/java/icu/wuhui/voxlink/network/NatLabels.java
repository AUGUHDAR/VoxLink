package icu.wuhui.voxlink.network;

import icu.wuhui.voxlink.VoxLinkMod;
import net.minecraft.network.chat.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NAT 文案归一层: 把客户端探测到的细粒度 NAT 串(StunProbe.NatType.key)、
 * 服务端下发的原始 natType(含早期 API 的 open/moderate/strict)统一映射到 lang 键 voxlink.nat.对应键。
 *
 * 只做显示, 不参与任何打洞/中继判定(判定见 NatClass 与 StunDetector),
 * 所以新增一种 NAT 文案只需在这里加一行 + 13 个语言文件补同名键, 显示层各处自动跟上。
 * 未收录的原始串一律回退 voxlink.nat.unknown, 并把原始串写进日志(每个值只报一次),
 * 避免"界面显示未知、但没人知道为什么"。
 */
public final class NatLabels {
   public static final String UNKNOWN_KEY = "voxlink.nat.unknown";

   private static final Map<String, String> RAW_TO_LANG_KEY;
   private static final Map<NatClass, String> CLASS_TO_LANG_KEY;
   private static final Set<String> UNRECOGNIZED = ConcurrentHashMap.newKeySet();

   static {
      Map<String, String> raw = new LinkedHashMap<>();
      // StunProbe.NatType 的 7 种细粒度 key
      raw.put("unknown", UNKNOWN_KEY);
      raw.put("full_cone", "voxlink.nat.full_cone");
      raw.put("restricted_cone", "voxlink.nat.restricted_cone");
      raw.put("port_restricted_cone", "voxlink.nat.port_restricted_cone");
      raw.put("symmetric_easy_inc", "voxlink.nat.symmetric_easy_inc");
      raw.put("symmetric_easy_dec", "voxlink.nat.symmetric_easy_dec");
      raw.put("symmetric", "voxlink.nat.symmetric");
      // 早期 API 规范遗留值, 服务端 natTypeValid 白名单仍会透传
      raw.put("open", "voxlink.nat.open");
      raw.put("moderate", "voxlink.nat.moderate");
      raw.put("strict", "voxlink.nat.strict");
      RAW_TO_LANG_KEY = Collections.unmodifiableMap(raw);

      Map<NatClass, String> cls = new LinkedHashMap<>();
      cls.put(NatClass.CONE, "voxlink.nat.cone");
      cls.put(NatClass.EASY_SYM, "voxlink.nat.easy_sym");
      cls.put(NatClass.HARD_SYM, "voxlink.nat.hard_sym");
      cls.put(NatClass.UNKNOWN, UNKNOWN_KEY);
      CLASS_TO_LANG_KEY = Collections.unmodifiableMap(cls);
   }

   private NatLabels() {
   }

   /** 原始 NAT 串 -> lang 键; null/空/未收录一律回退 unknown(未收录值只提示一次)。 */
   public static String langKey(String rawNatType) {
      if (rawNatType == null || rawNatType.isEmpty()) {
         return UNKNOWN_KEY;
      } else {
         String normalized = rawNatType.trim().toLowerCase(Locale.ROOT);
         String key = RAW_TO_LANG_KEY.get(normalized);
         if (key == null) {
            if (UNRECOGNIZED.add(normalized)) {
               VoxLinkMod.LOGGER.warn("[NatLabels] 未收录的 NAT 类型 '{}', 界面回退为未知(请补 voxlink.nat 文案)", rawNatType);
            }

            return UNKNOWN_KEY;
         } else {
            return key;
         }
      }
   }

   /** 该原始串是否是明确的 NAT 类型(可用于展示)。 */
   public static boolean isSpecific(String rawNatType) {
      return !UNKNOWN_KEY.equals(langKey(rawNatType));
   }

   /** 探测结果里的原始 NAT 串; 未探测到返回 null。 */
   public static String rawKey(StunProbe.ProbeResult probe) {
      return probe != null && probe.natType != null ? probe.natType.key : null;
   }

   /** 显示用名称: NatClass 已归类时用粗粒度文案(带打洞语义), 未归类时回落到原始细粒度文案。 */
   public static String displayName(NatClass natClass, String rawNatType) {
      String key = natClass != null && natClass != NatClass.UNKNOWN
         ? CLASS_TO_LANG_KEY.getOrDefault(natClass, UNKNOWN_KEY)
         : langKey(rawNatType);
      return Component.translatable(key).getString();
   }

   /** 只有原始串的场合(主页状态行/大厅卡片)。 */
   public static String nameOfRaw(String rawNatType) {
      return Component.translatable(langKey(rawNatType)).getString();
   }
}
