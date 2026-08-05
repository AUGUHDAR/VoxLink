package icu.wuhui.voxlink;

public final class VoxLinkConstants {
    //runtime
    public static final String GAME_VERSION = net.minecraftforge.fml.ModList
            .getModContainerById("minecraft")
            .map(c -> c.getModInfo().getVersion().toString())
            .orElse("unknown");
    //runtime loader标识
    public static final String LOADER = "forge";
    //debounce MC版本到协议版本映射
    public static final int PROTOCOL_VERSION = getProtocolVersion(GAME_VERSION);

    private static int getProtocolVersion(String v) {
        return switch (v) {
            case "1.20", "1.20.1" -> 763;
            case "1.20.2" -> 764;
            case "1.20.3", "1.20.4" -> 765;
            case "1.20.5", "1.20.6" -> 766;
            case "1.21", "1.21.1" -> 767;
            case "1.21.2", "1.21.3" -> 768;
            case "1.21.4" -> 769;
            case "1.21.5" -> 770;
            case "1.21.6" -> 771;
            case "1.21.7", "1.21.8" -> 772;
            case "1.21.9", "1.21.10" -> 773;
            case "1.21.11" -> 774;
            case "26.1", "26.1.1", "26.1.2" -> 775;
            case "26.2" -> 776;
            default -> 0;
        };
    }

    private VoxLinkConstants() {}
}