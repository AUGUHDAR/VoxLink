package icu.wuhui.voxlink.fw;

// 空壳
public final class FirewallGuard {

    private FirewallGuard() {}

    public static void activate() {}
    public static void deactivate() {}
    public static void onTcpBind(int port) {}
    public static void onUdpBind(int port) {}
    public static void shutdown() {}
}
