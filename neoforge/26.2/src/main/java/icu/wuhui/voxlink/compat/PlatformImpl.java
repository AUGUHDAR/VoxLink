package icu.wuhui.voxlink.compat;

import icu.wuhui.voxlink.VoxLinkConstants;
import icu.wuhui.voxlink.platform.Platform;
import icu.wuhui.voxlink.platform.Text;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** 平台宿主实现: 客户端初始化时经 Platform.install 注入一次。 */
public final class PlatformImpl {
    private PlatformImpl() {
    }

    public static void install() {
        Platform.install(new Platform.Host() {
            @Override
            public Platform.ClientThreadFn clientThread() {
                return new Platform.ClientThreadFn() {
                    @Override
                    public boolean isOnClientThread() {
                        try {
                            Minecraft mc = Minecraft.getInstance();
                            return mc != null && mc.isSameThread();
                        } catch (Throwable t) {
                            return true;
                        }
                    }

                    @Override
                    public void runOnClientThread(Runnable r) {
                        if (r == null) {
                            return;
                        }
                        try {
                            Minecraft mc = Minecraft.getInstance();
                            if (mc != null && !mc.isSameThread()) {
                                mc.execute(r);
                                return;
                            }
                        } catch (Throwable ignored) {
                        }
                        r.run();
                    }
                };
            }

            @Override
            public Platform.ChatSinkFn chatSink() {
                return new Platform.ChatSinkFn() {
                    @Override
                    public void chat(Text msg) {
                        PlatformImpl.runOnClientThread(() -> {
                            try {
                                Minecraft mc = Minecraft.getInstance();
                                if (mc != null && mc.player != null) {
                                    Component c = PlatformImpl.render(msg);
                                    if (c != null) {
                                        mc.player.sendSystemMessage(c);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        });
                    }

                    @Override
                    public void actionBar(Text msg) {
                        PlatformImpl.runOnClientThread(() -> {
                            try {
                                Minecraft mc = Minecraft.getInstance();
                                if (mc != null && mc.player != null) {
                                    Component c = PlatformImpl.render(msg);
                                    if (c != null) {
                                        mc.player.sendOverlayMessage(c);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        });
                    }
                };
            }

            @Override
            public Platform.ClientInfoFn clientInfo() {
                return new Platform.ClientInfoFn() {
                    @Override
                    public String userName() {
                        try {
                            Minecraft mc = Minecraft.getInstance();
                            if (mc != null && mc.getUser() != null && mc.getUser().getName() != null) {
                                return mc.getUser().getName();
                            }
                        } catch (Throwable ignored) {
                        }
                        return "";
                    }

                    @Override
                    public String gameVersion() {
                        return VoxLinkConstants.GAME_VERSION;
                    }

                    @Override
                    public File gameDir() {
                        try {
                            return FMLPaths.GAMEDIR.get().toFile();
                        } catch (Throwable t) {
                            return new File(".");
                        }
                    }

@Override
                    public List<Map<String, String>> installedMods() {
                        List<Map<String, String>> mods = new ArrayList<>();
                        // 反射枚举模组: 各 loader/时代 API 形态不一, 统一走反射避免分叉
                        try {
                            Object list = null;
                            for (Class<?> c : new Class<?>[]{
                                tryClass("net.fabricmc.loader.api.FabricLoader"),
                                tryClass("net.neoforged.fml.ModList"),
                                tryClass("net.minecraftforge.fml.ModList")}) {
                                if (c == null) {
                                    continue;
                                }
                                list = c == null ? null : reflectMods(c);
                                if (list != null) {
                                    break;
                                }
                            }
                            if (list instanceof Iterable) {
                                for (Object mi : (Iterable<?>) list) {
                                    Map<String, String> entry = new LinkedHashMap<>();
                                    Object id = call(mi, "getModId");
                                    if (id == null) {
                                        id = call(call(mi, "getMetadata"), "getId");
                                    }
                                    Object ver = call(mi, "getVersion");
                                    if (ver == null) {
                                        ver = call(call(mi, "getMetadata"), "getVersion");
                                    }
                                    if (ver != null) {
                                        Object friendly = call(ver, "getFriendlyString");
                                        ver = friendly != null ? friendly : ver;
                                    }
                                    entry.put("id", id != null ? String.valueOf(id) : "");
                                    entry.put("version", ver != null ? String.valueOf(ver) : "");
                                    mods.add(entry);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return mods;
                    }

                    private Class<?> tryClass(String name) {
                        try {
                            return Class.forName(name);
                        } catch (Throwable t) {
                            return null;
                        }
                    }

                    private Object call(Object target, String method) {
                        if (target == null) {
                            return null;
                        }
                        try {
                            return target.getClass().getMethod(method).invoke(target);
                        } catch (Throwable t) {
                            return null;
                        }
                    }

                    /** 依次尝试 instance.getMods() / static getMods() / getAllMods(). */
                    private Object reflectMods(Class<?> c) {
                        try {
                            Object inst = null;
                            try {
                                inst = c.getMethod("get").invoke(null);
                            } catch (Throwable ignored) {
                            }
                            if (inst != null) {
                                Object v = call(inst, "getMods");
                                if (v != null) {
                                    return v;
                                }
                            }
                            try {
                                return c.getMethod("getMods").invoke(null);
                            } catch (Throwable ignored) {
                            }
                            try {
                                Object fl = c.getMethod("getInstance").invoke(null);
                                return call(fl, "getAllMods");
                            } catch (Throwable ignored) {
                            }
                        } catch (Throwable ignored) {
                        }
                        return null;
                    }
                };
            }

            @Override
            public Platform.TextRendererFn textRenderer() {
                return new Platform.TextRendererFn() {
                    @Override
                    public Object render(Text text) {
                        return text == null ? null : toComponent(text);
                    }
                };
            }

            @Override
            public Platform.GameConnectionFn gameConnection() {
                return Platform.GameConnectionFn.EMPTY;
            }

            @Override
            public Platform.ServerHandleFn serverHandle() {
                return Platform.ServerHandleFn.EMPTY;
            }

            @Override
            public Platform.RoomLostUiHookFn roomLostUiHook() {
                return Platform.RoomLostUiHookFn.NOOP;
            }
        });
    }

    private static void runOnClientThread(Runnable r) {
        if (r == null) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && !mc.isSameThread()) {
                mc.execute(r);
                return;
            }
        } catch (Throwable ignored) {
        }
        r.run();
    }

    private static Component render(Text text) {
        return text == null ? null : toComponent(text);
    }

    /** Text→Component 全语义映射: 翻译/字面量/样式/点击/悬停。 */
    static Component toComponent(Text text) {
        MutableComponent base = text.isLiteral() ? Component.literal(text.getLiteral()) : Component.translatable(text.getKey(), text.getArgs());
        return withStyle(base, text.getStyle());
    }

    private static MutableComponent withStyle(MutableComponent c, Text.Style s) {
        if (s == null) {
            return c;
        }
        Style st = Style.EMPTY;
        if (s.color() != null) {
            ChatFormatting f = formatOf(s.color());
            if (f != null) {
                st = st.withColor(f);
            }
        }
        if (s.bold() != null) {
            st = st.withBold(s.bold());
        }
        if (s.italic() != null) {
            st = st.withItalic(s.italic());
        }
        if (s.underLine() != null) {
            st = st.withUnderlined(s.underLine());
        }
        if (s.strikethrough() != null) {
            st = st.withStrikethrough(s.strikethrough());
        }
        if (s.obfuscated() != null) {
            st = st.withObfuscated(s.obfuscated());
        }
        if (s.clickAction() != null && s.clickValue() != null) {
            try {
                st = st.withClickEvent(toClickEvent(s.clickAction(), s.clickValue()));
            } catch (Throwable ignored) {
            }
        }
        if (s.hoverText() != null) {
            st = st.withHoverEvent(new HoverEvent.ShowText(toComponent(s.hoverText())));
        }
        return c.withStyle(st);
    }

    private static ClickEvent toClickEvent(String action, String value) {
        ClickEvent.Action a = ClickEvent.Action.valueOf(action);
        return switch (a) {
            case OPEN_URL -> new ClickEvent.OpenUrl(URI.create(value));
            case RUN_COMMAND -> new ClickEvent.RunCommand(value);
            case SUGGEST_COMMAND -> new ClickEvent.SuggestCommand(value);
            case COPY_TO_CLIPBOARD -> new ClickEvent.CopyToClipboard(value);
            default -> null;
        };
    }

    private static ChatFormatting formatOf(Text.ChatFormat cf) {
        try {
            return ChatFormatting.valueOf(cf.name());
        } catch (Throwable t) {
            return null;
        }
    }
}
