package icu.wuhui.voxlink.fw;

// 空壳
public final class PortDiscovery {
    private PortDiscovery() {}
    public static java.util.Set<PortInfo> scanCurrentProcess() { return java.util.Collections.emptySet(); }
    public static class PortInfo {
        public final String protocol;
        public final int localPort;
        public final String remoteAddress;
        public final int remotePort;
        public final String state;
        public PortInfo(String protocol, int localPort, String remoteAddress, int remotePort, String state) {
            this.protocol = protocol; this.localPort = localPort;
            this.remoteAddress = remoteAddress; this.remotePort = remotePort; this.state = state;
        }
    }
}
