package icu.wuhui.voxlink.ui;
/** 本地实验桩: 只记录推送 */
public final class UiLogBus {
   public static final java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
   public static void push(int level, String key, Object... args) { lines.add(level + "|" + key); }
   public static void reset() { lines.clear(); }
   public static String latestMessage() { return lines.isEmpty() ? "" : lines.get(lines.size() - 1); }
   public static int latestLevel() { return 0; }
   public static void attach() {}
   public static void detach() {}
}
