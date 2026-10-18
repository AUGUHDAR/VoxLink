# -*- coding: utf-8 -*-
"""2026-08-31 日志挖掘修复传播：transport 建立失败僵尸连接（connectionWon 回滚）
来源：MCZHTH 组（1.1.2, 1.21.4 joiner）"Create UDP transport failed: null" 后卡"已连接"。
注意：bridge Connection refused（CPE2F4/RWJL8K，1.1.0）已有 connectMcWithRetryWindow 修复，勿重做。
"""
import os, sys, io

ROOT = os.path.dirname(os.path.abspath(__file__))
CM = "src/main/java/icu/wuhui/voxlink/room/ConnectionManager.java"

OLD = """                                       this.scheduler.submit(() -> {

                                          try {

                                             this.establishUdpTransport(state, punchSocket, puncherRef, finalTargetAddr, "joiner", false, null);

                                          } catch (Exception e) {

                                             VoxLinkMod.LOGGER.error("[Connection] Create UDP transport failed: {}", e.getMessage());



                                             try {

                                                puncherRef.close();

                                             } catch (Exception var7x) {

                                             }



                                             this.showConnectFailed(state, "voxlink.connection.transport_failed");

                                          }

                                       });"""

NEW = """                                       this.scheduler.submit(() -> {

                                          try {

                                             this.establishUdpTransport(state, punchSocket, puncherRef, finalTargetAddr, "joiner", false, null);

                                          } catch (Exception e) {

                                             VoxLinkMod.LOGGER.error("[Connection] Create UDP transport failed: {}", e.getClass().getSimpleName() + ":" + e.getMessage());

                                             try {

                                                puncherRef.close();

                                             } catch (Exception var7x) {

                                             }



                                             // 僵尸连接修复：punch 成功回调已 CAS 置 connectionWon=true，showConnectFailed
                                             // 的 !connectionWon 守卫会整体跳过→玩家卡"已连接"但无传输（MCZHTH 实锤：
                                             // transport 建立失败后 1 分钟只剩 transport silent）。回滚已赢标记再走
                                             // 标准失败路径（持续重试或终态），不留僵尸态
                                             this.connectionWon.set(false);

                                             this.showConnectFailed(state, "voxlink.connection.transport_failed");

                                          }

                                       });"""


def apply(path):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    for eol in ("\n", "\r\n"):
        new_t = NEW.replace("\n", eol)
        if new_t in text:
            return "ALREADY"
        old_t = OLD.replace("\n", eol)
        n = text.count(old_t)
        if n == 2:
            text = text.replace(old_t, new_t)
            with io.open(path, "w", encoding="utf-8", newline="") as f:
                f.write(text)
            return "OK"
        if n > 0:
            return "BAD_COUNT=%d" % n
    return "NO_MATCH"


def main():
    fails = []
    for loader in ["fabric", "forge", "neoforge"]:
        for ver in sorted(os.listdir(os.path.join(ROOT, loader))):
            tpl = os.path.join(ROOT, loader, ver)
            p = os.path.join(tpl, CM)
            if not os.path.isfile(p):
                continue
            r = apply(p)
            print("%-6s %s/%s" % (r, loader, ver))
            if r not in ("OK", "ALREADY"):
                fails.append("%s/%s:%s" % (loader, ver, r))
    print("\n失败:", fails if fails else "无")
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
