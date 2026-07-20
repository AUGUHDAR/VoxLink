package icu.wuhui.voxlink;

public final class VoxLinkConstants {
    //debounce 运行时从Fabric Loader读minecraft mod版本 23版本同步无需改此文件
    public static final String GAME_VERSION = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getModContainer("minecraft")
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    private VoxLinkConstants() {}
}
