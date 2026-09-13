package icu.wuhui.voxlink.ui;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * UiLogBus（1.1.5）：加入界面日志面板的内容总线。
 *
 * 设计原则（玩家向）：面板是给玩家看的进行时叙述（"正在尝试直连…""正在使用 TURN 中继…"），
 * 不是开发者日志——内容一律由流程节点通过 {@link #push} 打点注入（translatable 键，随玩家语言），
 * 绝不直接转发原始 log4j 日志。
 *
 * 环形缓冲 + 版本号供 UI 贴底跟随判断；线程安全（打点可能来自任意线程）。
 */
public final class UiLogBus {
   private static final int CAPACITY = 120;
   private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
   private static final Object LOCK = new Object();
   private static final ArrayDeque<String> LINES = new ArrayDeque<>(CAPACITY);
   private static final ArrayDeque<Integer> LEVELS = new ArrayDeque<>(CAPACITY); // 0=normal 1=success 2=warn 3=error
   private static volatile long version = 0L;

   private UiLogBus() {
   }

   /** 流程打点入口：key 为 voxlink.logui.* 翻译键，args 为占位参数。level: 0=普通 1=成功 2=警告 3=失败 */
   public static void push(int level, String key, Object... args) {
      String msg;
      try {
         msg = net.minecraft.client.Minecraft.getInstance() != null
            ? net.minecraft.network.chat.Component.translatable(key, args).getString()
            : key;
      } catch (Throwable t) {
         msg = key;
      }
      String time = LocalTime.now().format(TIME_FMT);
      synchronized (LOCK) {
         // 相邻重复去重：同一句话连打只记一条（打洞轮次等周期打点的噪音防线）
         if (!LINES.isEmpty() && LINES.peekLast().endsWith(msg)) {
            return;
         }
         LINES.addLast("[" + time + "] " + msg);
         LEVELS.addLast(level);
         while (LINES.size() > CAPACITY) {
            LINES.pollFirst();
            LEVELS.pollFirst();
         }
         version++;
      }
   }

   /** 进入界面时调用：清空上一局内容。 */
   public static void attach() {
      synchronized (LOCK) {
         LINES.clear();
         LEVELS.clear();
         version++;
      }
   }

   /** 离开界面时调用。 */
   public static void detach() {
   }

   public static long version() {
      return version;
   }

   /** UI 快照：levels[i] 与 lines[i] 对应（0=普通 1=成功 2=警告 3=失败）。 */
   public static void snapshot(List<String> lines, List<Integer> levels) {
      lines.clear();
      levels.clear();
      synchronized (LOCK) {
         lines.addAll(LINES);
         levels.addAll(LEVELS);
      }
   }
}
