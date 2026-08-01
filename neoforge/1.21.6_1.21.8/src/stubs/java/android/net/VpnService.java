/*
 * Android stub for PC compilation only.
 * Not packaged into jar. Real class provided by Android runtime.
 */
package android.net;

public class VpnService extends android.app.Service {
    public static class Builder {
        public Builder addAddress(java.net.InetAddress address, int prefixLength) { return this; }
        public Builder addRoute(String address, int prefixLength) { return this; }
        public Builder addDnsServer(String address) { return this; }
        public Builder addDisallowedApplication(String packageName) throws android.content.pm.PackageManager.NameNotFoundException { return this; }
        public Builder setSession(String session) { return this; }
        public android.os.ParcelFileDescriptor establish() { return null; }
    }
}
