package icu.wuhui.voxlink.ui;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class UiLogBus {
   private static final int CAPACITY = 120;
   private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
   private static final Object LOCK = new Object();
   private static final ArrayDeque<String> LINES = new ArrayDeque<>(120);
   private static final ArrayDeque<Integer> LEVELS = new ArrayDeque<>(120);
   private static volatile long version = 0L;

   private UiLogBus() {
   }

   public static void push(int level, String key, Object... args) {
      String msg;
      try {
         msg = Minecraft.getInstance() != null ? Component.translatable(key, args).getString() : key;
      } catch (Throwable var7) {
         msg = key;
      }

      String time = LocalTime.now().format(TIME_FMT);
      synchronized (LOCK) {
         if (LINES.isEmpty() || !LINES.peekLast().endsWith(msg)) {
            LINES.addLast("[" + time + "] " + msg);
            LEVELS.addLast(level);

            while (LINES.size() > 120) {
               LINES.pollFirst();
               LEVELS.pollFirst();
            }

            version++;
         }
      }
   }

   public static void attach() {
   }

   public static void reset() {
      synchronized (LOCK) {
         LINES.clear();
         LEVELS.clear();
         version++;
      }
   }

   public static void detach() {
   }

   public static long version() {
      return version;
   }

   public static String latestMessage() {
      synchronized (LOCK) {
         String s = LINES.isEmpty() ? "" : LINES.peekLast();
         int i = s.indexOf("] ");
         return i >= 0 ? s.substring(i + 2) : s;
      }
   }

   public static int latestLevel() {
      synchronized (LOCK) {
         return LEVELS.isEmpty() ? -1 : LEVELS.peekLast();
      }
   }

   public static void snapshot(List<String> lines, List<Integer> levels) {
      lines.clear();
      levels.clear();
      synchronized (LOCK) {
         lines.addAll(LINES);
         levels.addAll(LEVELS);
      }
   }
}
