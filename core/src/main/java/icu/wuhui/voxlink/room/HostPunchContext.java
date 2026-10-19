package icu.wuhui.voxlink.room;

/** 房主侧单个对端的打洞上下文(Phase4 从 ConnectionManager 提升为顶层类)。 */
final class HostPunchContext {
      volatile String punchInfoId = "";
      volatile long punchStartMs = 0L;
      volatile boolean punching = false;
}
