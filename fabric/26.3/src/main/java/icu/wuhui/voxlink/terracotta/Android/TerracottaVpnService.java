package icu.wuhui.voxlink.terracotta.Android;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.VpnService;
import android.net.VpnService.Builder;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.IOException;

@SuppressLint("VpnServicePolicy")
public class TerracottaVpnService extends VpnService {
   private static final String TAG = "VoxLinkTerracottaVPN";
   private static final String CHANNEL_ID = "voxlink_terracotta_vpn";
   private static final int VPN_NOTIFICATION_ID = 1;
   public static final String ACTION_START = "icu.wuhui.voxlink.terracotta.action.START";
   public static final String ACTION_STOP = "icu.wuhui.voxlink.terracotta.action.STOP";
   public static final String ACTION_UPDATE_STATE = "icu.wuhui.voxlink.terracotta.action.UPDATE_STATE";
   public static final String EXTRA_STATE_TEXT = "terracotta_state_text";
   private NotificationManager notificationManager;
   private String currentStateText = null;
   private volatile boolean isStopping = false;
   private ParcelFileDescriptor vpnInterface;
   private static boolean running = false;

   public static boolean isRunning() {
      return running;
   }

   public int onStartCommand(Intent intent, int flags, int startId) {
      running = true;
      String action = intent != null ? intent.getAction() : null;
      Log.d("VoxLinkTerracottaVPN", "onStartCommand action=" + action);
      if (this.notificationManager == null) {
         this.notificationManager = (NotificationManager)this.getSystemService("notification");
      }

      if ("icu.wuhui.voxlink.terracotta.action.STOP".equals(action)) {
         this.isStopping = true;
         this.cleanup();
         this.stopForeground(true);
         this.stopSelf();
         return 2;
      }

      if ("icu.wuhui.voxlink.terracotta.action.UPDATE_STATE".equals(action)) {
         if (intent.hasExtra("terracotta_state_text")) {
            this.currentStateText = intent.getStringExtra("terracotta_state_text");
         }

         if (!this.isStopping && this.notificationManager != null) {
            Notification n = this.buildVpnNotification();
            if (n != null) {
               this.notificationManager.notify(1, n);
            }
         }

         return 1;
      } else {
         this.isStopping = false;
         this.createNotificationChannelIfNeeded();
         Notification notification = this.buildVpnNotification();
         if (notification == null) {
            return 2;
         }

         this.startForeground(1, notification);
         Builder vpnBuilder = new Builder().setSession("VoxLink Terracotta");

         try {
            vpnBuilder.addDisallowedApplication(this.getPackageName());
         } catch (NameNotFoundException var9) {
         }

         try {
            int fd = TerracottaAndroidBridge.submitVpnFdBlocking(vpnBuilder);
            if (fd < 0) {
               Log.e("VoxLinkTerracottaVPN", "establish VPN returned invalid fd");
               this.cleanup();
               this.stopForeground(true);
               this.stopSelf();
               return 2;
            } else {
               return 1;
            }
         } catch (Throwable t) {
            Log.e("VoxLinkTerracottaVPN", "establish VPN failed: " + t.getMessage());
            TerracottaAndroidBridge.rejectVpn();
            this.cleanup();
            this.stopForeground(true);
            this.stopSelf();
            return 2;
         }
      }
   }

   public void onRevoke() {
      Log.w("VoxLinkTerracottaVPN", "onRevoke: VPN revoked");
      this.isStopping = true;

      try {
         TerracottaAndroidBridge.setWaiting();
      } catch (Throwable var2) {
      }

      this.cleanup();
      this.stopForeground(true);
      this.stopSelf();
   }

   public void onDestroy() {
      Log.d("VoxLinkTerracottaVPN", "onDestroy");
      this.isStopping = true;

      try {
         TerracottaAndroidBridge.setWaiting();
      } catch (Throwable var2) {
      }

      this.cleanup();
      super.onDestroy();
   }

   private void createNotificationChannelIfNeeded() {
      if (this.notificationManager != null) {
         NotificationChannel channel = new NotificationChannel("voxlink_terracotta_vpn", "VoxLink Terracotta VPN", 2);
         channel.setDescription("Terracotta VPN state");
         channel.setShowBadge(false);
         this.notificationManager.createNotificationChannel(channel);
      }
   }

   private Notification buildVpnNotification() {
      String title = "VoxLink Terracotta";
      String contentText = this.currentStateText != null ? this.currentStateText : "Running";
      android.app.Notification.Builder builder = new android.app.Notification.Builder(this, "voxlink_terracotta_vpn");
      builder.setSmallIcon(17301577)
         .setContentTitle(title)
         .setContentText(contentText)
         .setWhen(System.currentTimeMillis())
         .setOngoing(true)
         .setOnlyAlertOnce(true)
         .setCategory("service");
      return builder.build();
   }

   private void cleanup() {
      Log.d("VoxLinkTerracottaVPN", "cleanup");
      if (this.notificationManager != null) {
         this.notificationManager.cancel(1);
      }

      if (this.vpnInterface != null) {
         try {
            this.vpnInterface.close();
         } catch (IOException var2) {
         }

         this.vpnInterface = null;
      }

      running = false;
   }
}
