package icu.wuhui.voxlink.ui;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import icu.wuhui.voxlink.VoxLinkMod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class ChatCompat {
    private ChatCompat() {}

    private static Constructor<?> copyToClipboardCtor;
    private static Constructor<?> oldClickEventCtor;
    private static Object copyAction;
    private static boolean copyChecked = false;

    private static Constructor<?> showTextCtor;
    private static Constructor<?> oldHoverEventCtor;
    private static Object showTextAction;
    private static boolean hoverChecked = false;

    public static ClickEvent copyToClipboard(String value) {
        checkClickEvent();
        try {
            if (copyToClipboardCtor != null) {
                return (ClickEvent) copyToClipboardCtor.newInstance(value);
            }
            if (oldClickEventCtor != null && copyAction != null) {
                return (ClickEvent) oldClickEventCtor.newInstance(copyAction, value);
            }
        } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("ChatCompat反射失败", e);
        }
        return null;
    }

    public static HoverEvent showText(Component text) {
        checkHoverEvent();
        try {
            if (showTextCtor != null) {
                return (HoverEvent) showTextCtor.newInstance(text);
            }
            if (oldHoverEventCtor != null && showTextAction != null) {
                return (HoverEvent) oldHoverEventCtor.newInstance(showTextAction, text);
            }
        } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("ChatCompat反射失败", e);
        }
        return null;
    }

    public static Style styleWithCopy(String copyValue, Component hoverText) {
        Style style = Style.EMPTY;
        ClickEvent click = copyToClipboard(copyValue);
        if (click != null) style = style.withClickEvent(click);
        HoverEvent hover = showText(hoverText);
        if (hover != null) style = style.withHoverEvent(hover);
        return style;
    }

    private static void checkClickEvent() {
        if (copyChecked) return;
        copyChecked = true;
        for (Class<?> inner : ClickEvent.class.getDeclaredClasses()) {
            try {
                Constructor<?> c = inner.getDeclaredConstructor(String.class);
                if (ClickEvent.class.isAssignableFrom(inner)) {
                    copyToClipboardCtor = c;
                    copyToClipboardCtor.setAccessible(true);
                    return;
                }
            } catch (NoSuchMethodException e) {
                VoxLinkMod.LOGGER.debug("ChatCompat反射失败", e);
            }
        }
        try {
            Class<?> actionClass = null;
            for (Class<?> inner : ClickEvent.class.getDeclaredClasses()) {
                if (inner.isEnum()) {
                    actionClass = inner;
                    break;
                }
            }
            if (actionClass != null) {
                Object action = tryByName(actionClass, "copy_to_clipboard");
                if (action == null) {
                    for (Object e : actionClass.getEnumConstants()) {
                        String s = e.toString();
                        if (s.contains("COPY") || s.contains("copy")) {
                            action = e;
                            break;
                        }
                    }
                }
                if (action == null && actionClass.getEnumConstants().length >= 6) {
                    // 1.20.1 顺序: OPEN_URL, OPEN_FILE, RUN_COMMAND, SUGGEST_COMMAND, CHANGE_PAGE, COPY_TO_CLIPBOARD
                    action = actionClass.getEnumConstants()[5];
                }
                if (action != null) {
                    copyAction = action;
                    oldClickEventCtor = ClickEvent.class.getDeclaredConstructor(actionClass, String.class);
                    oldClickEventCtor.setAccessible(true);
                }
            }
        } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("ChatCompat反射失败", e);
        }
    }

    private static void checkHoverEvent() {
        if (hoverChecked) return;
        hoverChecked = true;
        for (Class<?> inner : HoverEvent.class.getDeclaredClasses()) {
            try {
                Constructor<?> c = inner.getDeclaredConstructor(Component.class);
                if (HoverEvent.class.isAssignableFrom(inner)) {
                    showTextCtor = c;
                    showTextCtor.setAccessible(true);
                    return;
                }
            } catch (NoSuchMethodException ignored) {}
        }
        try {
            Class<?> actionClass = null;
            for (Class<?> inner : HoverEvent.class.getDeclaredClasses()) {
                if (inner.isEnum()) {
                    actionClass = inner;
                    break;
                }
            }
            if (actionClass != null) {
                Object action = tryByName(actionClass, "show_text");
                if (action == null) {
                    for (Object e : actionClass.getEnumConstants()) {
                        String s = e.toString();
                        if (s.contains("SHOW_TEXT") || s.contains("show_text")) {
                            action = e;
                            break;
                        }
                    }
                }
                if (action == null && actionClass.getEnumConstants().length >= 1) {
                    // 1.20.1 顺序: SHOW_TEXT, SHOW_ITEM, SHOW_ENTITY
                    action = actionClass.getEnumConstants()[0];
                }
                if (action != null) {
                    showTextAction = action;
                    try {
                        oldHoverEventCtor = HoverEvent.class.getDeclaredConstructor(actionClass, Component.class);
                    } catch (NoSuchMethodException e) {
                        oldHoverEventCtor = HoverEvent.class.getDeclaredConstructor(actionClass, Object.class);
                    }
                    oldHoverEventCtor.setAccessible(true);
                }
            }
        } catch (Exception e) {
            VoxLinkMod.LOGGER.debug("ChatCompat反射失败", e);
        }
    }

    private static Object tryByName(Class<?> actionClass, String name) {
        try {
            Method byName = actionClass.getMethod("byName", String.class);
            return byName.invoke(null, name);
        } catch (Exception e) {
            return null;
        }
    }
}
