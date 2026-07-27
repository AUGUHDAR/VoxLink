package icu.wuhui.voxlink;

public final class VoxLinkConstants {
    //runtime
    public static final String GAME_VERSION = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getModContainer("minecraft")
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    private VoxLinkConstants() {}
}
