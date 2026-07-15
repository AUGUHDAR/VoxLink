package icu.wuhui.voxlink.terracotta;

//陶瓦未就绪异常: 表示陶瓦二进制未下载/未初始化, 调用方应降级到 VoxLink P2P
public final class TerracottaNotReadyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TerracottaNotReadyException(String message) {
        super(message);
    }

    public TerracottaNotReadyException(String message, Throwable cause) {
        super(message, cause);
    }
}
