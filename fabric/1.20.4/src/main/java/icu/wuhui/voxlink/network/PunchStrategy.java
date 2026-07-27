package icu.wuhui.voxlink.network;

//debounce 打洞策略枚举 决定正向/逆向/relay的触发时机
public enum PunchStrategy {
    DIRECT_ONLY,                    // 仅正向打洞 (Cone×Cone / Unknown首轮 / 老版本对端)
    DIRECT_WITH_REVERSE_PARALLEL,    // 正向失败1轮后并行逆向 (Sym×Cone / HardSym×HardSym cycle<2)
    REVERSE_FIRST,                   // 首轮先逆向再正向 (EasySym×HardSym / HardSym×EasySym)
    RELAY_FALLBACK_FAST              // cycle≥2 立即切 relay (HardSym×HardSym cycle>=2)
}
