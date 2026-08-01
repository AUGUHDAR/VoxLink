package icu.wuhui.voxlink.terracotta.Android;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        String action = intent != null ? intent.getAction() : null;
        Log.d(TAG, "onStartCommand action=" + action);

        if (notificationManager == null) {
            notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        }

        if (ACTION_STOP.equals(action)) {
            isStopping = true;
            cleanup();
            stopForeground(true);
            stopSelf();
            return Service.START_NOT_STICKY;
        }

        if (ACTION_UPDATE_STATE.equals(action)) {
            if (intent.hasExtra(EXTRA_STATE_TEXT)) {
                currentStateText = intent.getStringExtra(EXTRA_STATE_TEXT);
            }
            if (!isStopping && notificationManager != null) {
                Notification n = buildVpnNotification();
                if (n != null) notificationManager.notify(VPN_NOTIFICATION_ID, n);
            }
            return Service.START_STICKY;
        }

        isStopping = false;
        createNotificationChannelIfNeeded();

        Notification notification = buildVpnNotification();
        if (notification == null) return Service.START_NOT_STICKY;
        startForeground(VPN_NOTIFICATION_ID, notification);

        Builder vpnBuilder = new Builder().setSession("VoxLink Terracotta");
        try {
            vpnBuilder.addDisallowedApplication(getPackageName());
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        try {
            int fd = TerracottaAndroidBridge.submitVpnFdBlocking(vpnBuilder);
            if (fd < 0) {
                Log.e(TAG, "establish VPN returned invalid fd");
                cleanup();
                stopForeground(true);
                stopSelf();
                return Service.START_NOT_STICKY;
            }
        } catch (Throwable t) {
            Log.e(TAG, "establish VPN failed: " + t.getMessage());
            TerracottaAndroidBridge.rejectVpn();
            cleanup();
            stopForeground(true);
            stopSelf();
            return Service.START_NOT_STICKY;
        }

        return Service.START_STICKY;
    }

    @Override
    public void onRevoke() {
        Log.w(TAG, "onRevoke: VPN revoked");
        isStopping = true;
        try { TerracottaAndroidBridge.setWaiting(); } catch (Throwable ignored) {}
        cleanup();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        isStopping = true;
        try { TerracottaAndroidBridge.setWaiting(); } catch (Throwable ignored) {}
        cleanup();
        super.onDestroy();
    }

    private void createNotificationChannelIfNeeded() {
        if (notificationManager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "VoxLink Terracotta VPN",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Terracotta VPN state");
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildVpnNotification() {
        String title = "VoxLink Terracotta";
        String contentText = currentStateText != null ? currentStateText : "Running";
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(contentText)
                .setWhen(System.currentTimeMillis())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE);
        return builder.build();
    }

    private void cleanup() {
        Log.d(TAG, "cleanup");
        if (notificationManager != null) {
            notificationManager.cancel(VPN_NOTIFICATION_ID);
        }
        if (vpnInterface != null) {
            try { vpnInterface.close(); } catch (IOException ignored) {}
            vpnInterface = null;
        }
        running = false;
    }
}
