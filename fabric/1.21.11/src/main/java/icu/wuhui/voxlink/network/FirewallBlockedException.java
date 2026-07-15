package icu.wuhui.voxlink.network;

// 防火墙阻止异常: UDP发包后无任何回包
public class FirewallBlockedException extends RuntimeException {
    public FirewallBlockedException(String message) {
        super(message);
    }
}
