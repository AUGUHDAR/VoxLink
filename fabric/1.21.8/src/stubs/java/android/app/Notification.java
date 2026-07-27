/*
 * Android stub for PC compilation only.
 */
package android.app;

import android.content.Context;

public class Notification {
    public static final String CATEGORY_SERVICE = "service";
    public static class Builder {
        public Builder(Context context, String channelId) {}
        public Builder setSmallIcon(int icon) { return this; }
        public Builder setContentTitle(String title) { return this; }
        public Builder setContentText(String text) { return this; }
        public Builder setWhen(long when) { return this; }
        public Builder setOngoing(boolean b) { return this; }
        public Builder setOnlyAlertOnce(boolean b) { return this; }
        public Builder setCategory(String c) { return this; }
        public Notification build() { return new Notification(); }
    }
}
