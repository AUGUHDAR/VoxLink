/*
 * Android stub for PC compilation only.
 * Not packaged into jar. Real class provided by Android runtime.
 */
package android.app;

import android.content.Context;
import android.content.Intent;

public class Service extends Context {
    public static final int START_NOT_STICKY = 2;
    public static final int START_STICKY = 1;
    public void startForeground(int id, Notification notification) {}
    public void stopForeground(boolean b) {}
    public void stopSelf() {}
    public int onStartCommand(Intent intent, int flags, int startId) { return START_NOT_STICKY; }
    public void onRevoke() {}
    public void onDestroy() {}
}
