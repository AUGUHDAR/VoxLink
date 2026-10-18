package icu.wuhui.voxlink.ui;

import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;

public final class CreateFlowState {
   private static final AtomicReference<CreateFlowState.Phase> PHASE = new AtomicReference<>(CreateFlowState.Phase.IDLE);
   private static volatile long startMs = 0L;
   private static volatile String failDetail = "";
   private static volatile CreateFlowState.Terminal lastTerminal = null;
   private static volatile Runnable teardownHook = null;
   private static volatile CreateFlowState.Watchdog watchdog = null;
   private static volatile CreateFlowState.TerminalListener terminalListener = null;
   private static long lastNotifyKeyMs = 0L;
   private static final long TIMEOUT_MS = 30000L;
   private static final long NOTIFY_DEDUPE_MS = 1500L;

   private CreateFlowState() {
   }

   public static boolean isActive() {
      CreateFlowState.Phase p = PHASE.get();
      return p == CreateFlowState.Phase.PREPARE_LAN || p == CreateFlowState.Phase.REGISTERING;
   }

   public static CreateFlowState.Phase getPhase() {
      return PHASE.get();
   }

   public static long elapsedMs() {
      return isActive() ? System.currentTimeMillis() - startMs : 0L;
   }

   public static CreateFlowState.Terminal lastTerminal() {
      return lastTerminal;
   }

   public static synchronized void begin(Runnable teardownHookParam, CreateFlowState.TerminalListener listener) {
      killWatchdog();
      PHASE.set(CreateFlowState.Phase.PREPARE_LAN);
      startMs = System.currentTimeMillis();
      failDetail = "";
      lastTerminal = null;
      teardownHook = teardownHookParam;
      terminalListener = listener;
      watchdog = new CreateFlowState.Watchdog();
      Thread t = new Thread(watchdog, "VoxLink-CreateFlow-Watchdog");
      t.setDaemon(true);
      t.start();
   }

   public static void markRegistering() {
      if (PHASE.compareAndSet(CreateFlowState.Phase.PREPARE_LAN, CreateFlowState.Phase.REGISTERING)) {
      }
   }

   public static synchronized void finishSuccess() {
      if (isActive()) {
         CreateFlowState.Terminal t = new CreateFlowState.Terminal(CreateFlowState.Phase.SUCCESS, "");
         terminal(t);
      }
   }

   public static synchronized void finishFailure(String detail) {
      if (isActive()) {
         CreateFlowState.Terminal t = new CreateFlowState.Terminal(CreateFlowState.Phase.FAILED, detail == null ? "" : detail);
         terminal(t);
      }
   }

   public static synchronized boolean requestCancel() {
      CreateFlowState.Phase cur = PHASE.get();
      if (cur != CreateFlowState.Phase.PREPARE_LAN && cur != CreateFlowState.Phase.REGISTERING) {
         return false;
      } else {
         CreateFlowState.Terminal t = new CreateFlowState.Terminal(CreateFlowState.Phase.CANCELLED, "");
         terminal(t);
         return true;
      }
   }

   public static synchronized void reset() {
      killWatchdog();
      PHASE.set(CreateFlowState.Phase.IDLE);
      failDetail = "";
   }

   private static void terminal(CreateFlowState.Terminal t) {
      PHASE.set(t.phase());
      lastTerminal = t;
      if (t.phase() == CreateFlowState.Phase.FAILED) {
         failDetail = t.detail();
      }

      killWatchdog();
      Runnable hook = teardownHook;
      CreateFlowState.TerminalListener l = terminalListener;
      CreateFlowState.Terminal fin = t.phase() == CreateFlowState.Phase.FAILED && !failDetail.isEmpty()
         ? new CreateFlowState.Terminal(CreateFlowState.Phase.FAILED, failDetail)
         : t;
      if (l != null) {
         Minecraft.getInstance().execute(() -> l.onTerminal(fin));
      } else if (hook != null) {
         Minecraft.getInstance().execute(hook);
      }

      lastNotifyKeyMs = System.currentTimeMillis();
   }

   public static void runTeardownOnMainThread() {
      Runnable h = teardownHook;
      if (h != null) {
         Minecraft.getInstance().execute(h);
      }
   }

   public static boolean tryClaimNotifySlot(long nowMs) {
      if (nowMs - lastNotifyKeyMs < 1500L) {
         return false;
      } else {
         lastNotifyKeyMs = nowMs;
         return true;
      }
   }

   private static void killWatchdog() {
      CreateFlowState.Watchdog w = watchdog;
      watchdog = null;
      if (w != null) {
         w.stop();
      }
   }

   public static enum Phase {
      IDLE,
      PREPARE_LAN,
      REGISTERING,
      SUCCESS,
      FAILED,
      CANCELLED;
   }

   public static record Terminal(CreateFlowState.Phase phase, String detail) {
   }

   public interface TerminalListener {
      void onTerminal(CreateFlowState.Terminal var1);
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
            } catch (InterruptedException var3) {
               Thread.currentThread().interrupt();
               return;
            }

            CreateFlowState.Phase p = CreateFlowState.PHASE.get();
            if (p != CreateFlowState.Phase.PREPARE_LAN && p != CreateFlowState.Phase.REGISTERING) {
               return;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc.getSingleplayerServer() == null) {
               CreateFlowState.finishFailure("WORLD_EXITED");
               return;
            }

            if (System.currentTimeMillis() - CreateFlowState.startMs > 30000L) {
               CreateFlowState.finishFailure("TIMEOUT");
               return;
            }
         }
      }
   }
}
