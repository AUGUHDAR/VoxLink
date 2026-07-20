package icu.wuhui.voxlink.terracotta;

import com.google.gson.JsonObject;

//debounce 陶瓦状态机 对齐HMCL设计 简化版 普通抽象类避免23版本sealed兼容性差异
public abstract class TerracottaState {

    //debounce 状态名取自Terracotta /state响应的state字段 单调index防旧响应覆盖新状态
    public abstract String name();

    //启动期占位 单例
    public static final class Bootstrap extends TerracottaState {
        public static final Bootstrap INSTANCE = new Bootstrap();
        @Override public String name() { return "bootstrap"; }
        @Override public String toString() { return "Bootstrap"; }
    }

    //进程已拉起 等端口文件
    public static final class Launching extends TerracottaState {
        public static final Launching INSTANCE = new Launching();
        @Override public String name() { return "launching"; }
        @Override public String toString() { return "Launching"; }
    }

    //未安装
    public static final class Uninitialized extends TerracottaState {
        public final boolean hasLegacy;
        public Uninitialized(boolean hasLegacy) { this.hasLegacy = hasLegacy; }
        @Override public String name() { return "uninitialized"; }
        @Override public String toString() { return "Uninitialized[legacy=" + hasLegacy + "]"; }
    }

    //已知端口的所有状态基类
    public abstract static class PortSpecific extends TerracottaState {
        public int port;
    }

    //端口已知 未拉到首份state
    public static final class Unknown extends PortSpecific {
        @Override public String name() { return "unknown"; }
        @Override public String toString() { return "Unknown[port=" + port + "]"; }
    }

    //已就绪状态基类 带单调index
    public abstract static class Ready extends PortSpecific {
        public int index;
        public String state;
        //debounce index=-1表示UI占位假状态 真状态由守护线程异步替换
        public boolean isUIFakeState() { return index == -1; }
    }

    //空闲
    public static final class Waiting extends Ready {
        @Override public String name() { return "waiting"; }
        @Override public String toString() { return "Waiting[index=" + index + "]"; }
    }

    //扫描中继节点
    public static final class HostScanning extends Ready {
        @Override public String name() { return "host-scanning"; }
        @Override public String toString() { return "HostScanning[index=" + index + "]"; }
    }

    //主机启动中
    public static final class HostStarting extends Ready {
        @Override public String name() { return "host-starting"; }
        @Override public String toString() { return "HostStarting[index=" + index + "]"; }
    }

    //主机就绪
    public static final class HostOK extends Ready {
        public String code;
        @Override public String name() { return "host-ok"; }
        @Override public String toString() { return "HostOK[code=" + code + ",index=" + index + "]"; }
    }

    //客人连接中
    public static final class GuestConnecting extends Ready {
        @Override public String name() { return "guest-connecting"; }
        @Override public String toString() { return "GuestConnecting[index=" + index + "]"; }
    }

    //客人启动中
    public static final class GuestStarting extends Ready {
        public String difficulty;
        @Override public String name() { return "guest-starting"; }
        @Override public String toString() { return "GuestStarting[difficulty=" + difficulty + ",index=" + index + "]"; }
    }

    //客人就绪
    public static final class GuestOK extends Ready {
        public String url;
        @Override public String name() { return "guest-ok"; }
        @Override public String toString() { return "GuestOK[url=" + url + ",index=" + index + "]"; }
    }

    //Terracotta报告的可恢复异常
    public static final class Exception extends Ready {
        public String type;
        @Override public String name() { return "exception"; }
        @Override public String toString() { return "Exception[type=" + type + ",index=" + index + "]"; }
    }

    //不可恢复致命错误
    public static final class Fatal extends TerracottaState {
        public enum Type { OS, NETWORK, INSTALL, TERRACOTTA, UNKNOWN }
        public final Type type;
        public Fatal(Type type) { this.type = type; }
        public boolean isRecoverable() { return type != Type.UNKNOWN; }
        @Override public String name() { return "fatal"; }
        @Override public String toString() { return "Fatal[" + type + "]"; }
    }

    //从JsonObject解析为具体Ready子类 对齐HMCL parseState
    public static Ready parseFromState(JsonObject json, int port) {
        if (json == null) {
            Waiting w = new Waiting();
            w.index = 0;
            w.state = "waiting";
            w.port = port;
            return w;
        }
        String stateName = json.has("state") && !json.get("state").isJsonNull()
            ? json.get("state").getAsString() : "unknown";
        int index = json.has("index") && !json.get("index").isJsonNull()
            ? json.get("index").getAsInt() : 0;

        Ready state;
        switch (stateName) {
            case "waiting":
            case "idle":
                state = new Waiting();
                break;
            case "host-scanning":
                state = new HostScanning();
                break;
            case "host-starting":
                state = new HostStarting();
                break;
            case "host-ok":
                HostOK h = new HostOK();
                h.code = json.has("room") && !json.get("room").isJsonNull()
                    ? json.get("room").getAsString() : null;
                state = h;
                break;
            case "guest-connecting":
                state = new GuestConnecting();
                break;
            case "guest-starting":
                GuestStarting gs = new GuestStarting();
                gs.difficulty = json.has("difficulty") && !json.get("difficulty").isJsonNull()
                    ? json.get("difficulty").getAsString() : "UNKNOWN";
                state = gs;
                break;
            case "guest-ok":
                GuestOK g = new GuestOK();
                g.url = json.has("url") && !json.get("url").isJsonNull()
                    ? json.get("url").getAsString() : null;
                state = g;
                break;
            case "exception":
                Exception e = new Exception();
                e.type = json.has("type") && !json.get("type").isJsonNull()
                    ? json.get("type").getAsString() : "UNKNOWN";
                state = e;
                break;
            default:
                //debounce 未知状态 当Waiting处理 但保留原始state字符串
                Waiting w = new Waiting();
                state = w;
                break;
        }
        state.index = index;
        state.state = stateName;
        state.port = port;
        return state;
    }
}
