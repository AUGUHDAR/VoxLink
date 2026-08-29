package icu.wuhui.voxlink.ui;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 创建房间后台流程的唯一事实源（与具体界面完全解耦）。
 *
 * 生命周期：begin() → PREPARE_LAN（发布LAN）→ REGISTERING（信令注册中）
 *   → SUCCESS / FAILED(reason) / CANCELLED。
 *
 * 弹窗可以随时被收起甚至销毁重建：进度查询走 getPhase()/elapsedMs()，
 * 结果通知走 onTerminal 监听（保证在 MC 主线程回调）。
 *
 * 内置看门狗守护线程负责两件事（不依赖任何界面存活）：
 *   1) 30s 超时：触发 onTerminal(FAILED,"timeout") 并执行 teardownHook；
 *   2) 存档退出：单机服务器实例消失时立刻作废整个流程并执行 teardownHook，
 *      防止界面没了还在后台搞 openToLan 这类诡异残留。
 */
public final class CreateFlowState {
   public enum Phase {
      IDLE,
      /** 正在发布局域网（publishServer）。 */
      PREPARE_LAN,
      /** 已发布，正在向信令服务器注册房间。 */
      REGISTERING,
      SUCCESS,
      FAILED,
      CANCELLED
   }

   /** 终态事件载体：phase + 人类可读描述（可空）。 */
   public record Terminal(Phase phase, String detail) {
   }

   private static final AtomicReference<Phase> PHASE = new AtomicReference<>(Phase.IDLE);
   private static volatile long startMs = 0L;
   private static volatile String failDetail = "";
   private static volatile Terminal lastTerminal = null;
   private static volatile Runnable teardownHook = null;
   private static volatile Watchdog watchdog = null;
   private static volatile TerminalListener terminalListener = null;
   private static long lastNotifyKeyMs = 0L;

   private static final long TIMEOUT_MS = 30000L;
   /** 同一终态提示的最小间隔，防重刷。 */
   private static final long NOTIFY_DEDUPE_MS = 1500L;

   private CreateFlowState() {
   }

   /** 终态监听：总是在主线程收到一次成功/失败事件（界面在场与否无关）。 */
   public interface TerminalListener {
      void onTerminal(Terminal t);
   }

   public static boolean isActive() {
      Phase p = PHASE.get();
      return p == Phase.PREPARE_LAN || p == Phase.REGISTERING;
   }

   public static Phase getPhase() {
      return PHASE.get();
   }

   public static long elapsedMs() {
      return isActive() ? System.currentTimeMillis() - startMs : 0L;
   }

   public static Terminal lastTerminal() {
      return lastTerminal;
   }

   /** 开始新流程：销毁上一个未清理的流程痕迹后进入 PREPARE_LAN 并启动看门狗。 */
   public static synchronized void begin(Runnable teardownHookParam, TerminalListener listener) {
      killWatchdog();
      PHASE.set(Phase.PREPARE_LAN);
      startMs = System.currentTimeMillis();
      failDetail = "";
      lastTerminal = null;
      teardownHook = teardownHookParam;
      terminalListener = listener;
      watchdog = new Watchdog();
      Thread t = new Thread(watchdog, "VoxLink-CreateFlow-Watchdog");
      t.setDaemon(true);
      t.start();
   }

   /** LAN 已发布，转入信令注册阶段。 */
   public static void markRegistering() {
      if (PHASE.compareAndSet(Phase.PREPARE_LAN, Phase.REGISTERING)) {
         // 计时不重置：超时覆盖整个创建过程
      }
   }

   public static synchronized void finishSuccess() {
      if (!isActive()) {
         return;
      }

      Terminal t = new Terminal(Phase.SUCCESS, "");
      terminal(t);
   }

   public static synchronized void finishFailure(String detail) {
      if (!isActive()) {
         return;
      }

      Terminal t = new Terminal(Phase.FAILED, detail == null ? "" : detail);
      terminal(t);
   }

   /** 玩家主动取消：幂等，可从任意线程调用。 */
   public static synchronized boolean requestCancel() {
      Phase cur = PHASE.get();
      if (cur != Phase.PREPARE_LAN && cur != Phase.REGISTERING) {
         return false;
      }

      Terminal t = new Terminal(Phase.CANCELLED, "");
      terminal(t);
      return true;
   }

   /** 完全复位到 IDLE（离开该流程的全部界面后由 Orchestrator 调用）。 */
   public static synchronized void reset() {
      killWatchdog();
      PHASE.set(Phase.IDLE);
      failDetail = "";
   }

   private static void terminal(Terminal t) {
      PHASE.set(t.phase());
      lastTerminal = t;
      if (t.phase() == Phase.FAILED) {
         failDetail = t.detail();
      }

      killWatchdog();
      Runnable hook = teardownHook;
      TerminalListener l = terminalListener;
      Terminal fin = t.phase() == Phase.FAILED && !failDetail.isEmpty()
         ? new Terminal(Phase.FAILED, failDetail)
         : t;
      if (l != null) {
         net.minecraft.client.Minecraft.getInstance().execute(() -> l.onTerminal(fin));
      } else {
         // 没有任何监听者（理论不会发生）：teardown 也必须在主线程执行
         Runnable h = hook;
         if (h != null) {
            net.minecraft.client.Minecraft.getInstance().execute(h);
         }
      }

      lastNotifyKeyMs = System.currentTimeMillis();
   }

   /** 在主线程执行 teardown（取消/超时/退档共同出口）。供监听者内部调用。 */
   public static void runTeardownOnMainThread() {
      Runnable h = teardownHook;
      if (h != null) {
         net.minecraft.client.Minecraft.getInstance().execute(h);
      }
   }

   /** 单飞去重的动作栏通知时间戳（Watchdog 用它决定要不要弹）。 */
   public static boolean tryClaimNotifySlot(long nowMs) {
      if (nowMs - lastNotifyKeyMs < NOTIFY_DEDUPE_MS) {
         return false;
      }

      lastNotifyKeyMs = nowMs;
      return true;
   }

   private static void killWatchdog() {
      Watchdog w = watchdog;
      watchdog = null;
      if (w != null) {
        w.stop();
      }
   }

   private static final class Watchdog implements Runnable {
      private volatile boolean running = true;

      void stop() {
         this.running = false;
      }

      @Override
      public void run() {
         while (this.running) {
            try {
               Thread.sleep(250L);
            } catch (InterruptedException ie) {
               Thread.currentThread().interrupt();
               return;
            }

            Phase p = PHASE.get();
            if (p != Phase.PREPARE_LAN && p != Phase.REGISTERING) {
               return;
            }

            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            // 坑1：存档退出 → 单机服务器实例消失，必须立刻作废整个流程
            if (mc.getSingleplayerServer() == null) {
               finishFailure("WORLD_EXITED");
               return;
            }

            // 坑2：超时即便界面被收起也要触发
            if (System.currentTimeMillis() - startMs > TIMEOUT_MS) {
               finishFailure("TIMEOUT");
               return;
            }
         }
      }
   }
}
