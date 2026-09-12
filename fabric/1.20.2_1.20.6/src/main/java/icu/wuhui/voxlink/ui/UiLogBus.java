package icu.wuhui.voxlink.ui;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;

/**
 * UiLogBus（1.1.5 UI 重构）：把本模组的日志流接到加入界面的日志面板。
 *
 * 实现：log4j2 核心挂一个 appender 到 root logger，按 logger 名前缀 "voxlink" 过滤，
 * 零侵入（不用改几百个调用点）；存入固定容量环形缓冲，UI 每帧只读快照。
 *
 * 面板降噪（加入等待页要的是"发生了什么"不是逐包转储）：
 *   - DEBUG/TRACE 不收（打洞 Send #/Received # 等 INFO 逐包日志按前缀黑名单丢弃）
 *   - 同文本 1 秒内重复折叠为 "×N"
 *
 * 线程：append 可能来自任意线程，读写在 LOCK 内；UI 线程只做快照拷贝。
 * 生命周期：attach() 在进入加入界面时调用，detach() 离开时调用（appender 只挂一次，detach 只停收集）。
 */
public final class UiLogBus {
   private static final int CAPACITY = 300;
   private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
   private static final Object LOCK = new Object();
   private static final ArrayDeque<String> LINES = new ArrayDeque<>(CAPACITY);
   private static final ArrayDeque<Integer> LEVELS = new ArrayDeque<>(CAPACITY); // 0=info 1=warn 2=error
   /** 逐包噪音黑名单：这些前缀的 INFO 日志不进面板（log4j 层仍在，latest.log 不受影响）。 */
   private static final String[] NOISE_PREFIXES = new String[]{
      "[UdpHolePuncher] Send #", "[UdpHolePuncher] Received #",
      "sendControlMultiPort", "[PunchTuner]", "[RoomManager] Signal poll #",
      "[ReliableUdp] Retransmit seq"
   };

   private static volatile boolean attached = false;
   private static volatile boolean collecting = false;
   private static volatile long version = 0L;
   private static String lastText = "";
   private static long lastTextAt = 0L;
   private static int lastTextRepeat = 0;

   private UiLogBus() {
   }

   /** 进入界面时调用：appender 只挂一次；collecting 置 true 开始收集并清空旧内容。 */
   public static void attach() {
      synchronized (LOCK) {
         LINES.clear();
         LEVELS.clear();
         lastText = "";
         lastTextRepeat = 0;
         collecting = true;
      }
      if (attached) {
         return;
      }
      try {
         LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
         // 4 参构造器全版本可用(新 log4j 标 deprecated 但仍保留); Property[] 版 1.20.1 NeoForge 的 log4j 没有
         AbstractAppender appender = new AbstractAppender("VoxLinkUiBus", null, null, true) {
            @Override
            public void append(LogEvent event) {
               if (!collecting) {
                  return;
               }
               String loggerName = event.getLoggerName();
               if (loggerName == null || !loggerName.startsWith("voxlink")) {
                  return;
               }
               // DEBUG/TRACE 不进面板
               if (event.getLevel().intLevel() > Level.INFO.intLevel()) {
                  return;
               }
               String msg = event.getMessage() != null ? event.getMessage().getFormattedMessage() : "";
               if (msg == null || msg.isEmpty()) {
                  return;
               }
               for (String p : NOISE_PREFIXES) {
                  if (msg.startsWith(p)) {
                     return;
                  }
               }
               long now = System.currentTimeMillis();
               int level = event.getLevel() == Level.WARN ? 1 : event.getLevel() == Level.ERROR ? 2 : 0;
               synchronized (LOCK) {
                  if (msg.equals(lastText) && now - lastTextAt < 1000L) {
                     lastTextRepeat++;
                     lastTextAt = now;
                     if (!LINES.isEmpty()) {
                        LINES.pollLast();
                        LEVELS.pollLast();
                     }
                     LINES.addLast(msg + " ×" + (lastTextRepeat + 1));
                     LEVELS.addLast(level);
                     trim();
                     version++;
                     return;
                  }
                  lastText = msg;
                  lastTextAt = now;
                  lastTextRepeat = 1;
                  String time = LocalTime.now().format(TIME_FMT);
                  LINES.addLast("[" + time + "] " + msg);
                  LEVELS.addLast(level);
                  trim();
                  version++;
               }
            }

            private void trim() {
               while (LINES.size() > CAPACITY) {
                  LINES.pollFirst();
                  LEVELS.pollFirst();
               }
            }
         };
         appender.start();
         ctx.getConfiguration().addAppender(appender);
         ctx.getConfiguration().getRootLogger().addAppender(appender, Level.DEBUG, null);
         ctx.updateLoggers();
         attached = true;
      } catch (Throwable t) {
         // 任何 log4j 环境差异都不允许影响连接流程：面板静默降级为空
         attached = false;
      }
   }

   /** 离开界面时调用：停止收集（appender 留着，重进界面直接复用）。 */
   public static void detach() {
      collecting = false;
   }

   public static long version() {
      return version;
   }

   /** UI 快照：levels[i] 与 lines[i] 对应（0=info 1=warn 2=error）。 */
   public static void snapshot(List<String> lines, List<Integer> levels) {
      lines.clear();
      levels.clear();
      synchronized (LOCK) {
         lines.addAll(LINES);
         levels.addAll(LEVELS);
      }
   }
}
