# -*- coding: utf-8 -*-
"""2026-08-31 TURN 修复第二轮传播：bind 3次重发 + host bind 失败日志带上下文 + 点击按钮即时反馈"""
import os, sys, io

ROOT = os.path.dirname(os.path.abspath(__file__))
LOADERS = ["fabric", "forge", "neoforge"]
TRC = "src/main/java/icu/wuhui/voxlink/network/TurnRelayClient.java"
CM = "src/main/java/icu/wuhui/voxlink/room/ConnectionManager.java"
AJS = "src/main/java/icu/wuhui/voxlink/ui/AttemptingJoinScreen.java"

OLD_BIND = """      try {
         session.socket.send(new DatagramPacket(p, p.length, session.endpoint()));
      } catch (IOException e) {
         return BIND_SERVER_BUSY;
      }

      long deadline = System.currentTimeMillis() + (long)BIND_TIMEOUT_MS;
      byte[] buf = new byte[64];
      DatagramPacket resp = new DatagramPacket(buf, buf.length);

      while (System.currentTimeMillis() < deadline) {
         try {
            session.socket.receive(resp);
         } catch (SocketTimeoutException e) {
            break;
         } catch (IOException e) {
            break;
         }

         if (resp.getLength() >= 22 && buf[0] == MAGIC[0] && buf[1] == MAGIC[1] && buf[3] == TYPE_BIND_RESULT) {
            byte[] sid = new byte[16];
            System.arraycopy(buf, 4, sid, 0, 16);
            if (java.util.Arrays.equals(sid, session.sessionId) && buf[20] == session.role) {
               int code = buf[21] & 0xFF;
               session.bound = code == BIND_OK;
               return code;
            }
         }
      }

      return BIND_SERVER_BUSY;
   }"""

NEW_BIND = """      // UDP 单发丢包即败（2026-08-31 生产：移动→节点首包丢失，host bind 超时中继失败）。
      // 0/700/1400ms 三次重发，总窗口 2.6s；发送失败（DNS 解析等）记 WARN 后继续重试。
      long deadline = System.currentTimeMillis() + 2600L;
      byte[] buf = new byte[64];
      DatagramPacket resp = new DatagramPacket(buf, buf.length);
      int sent = 0;
      long nextSendAt = 0L;

      while (System.currentTimeMillis() < deadline) {
         long now = System.currentTimeMillis();
         if (sent < 3 && now >= nextSendAt) {
            try {
               session.socket.send(new DatagramPacket(p, p.length, session.endpoint()));
               sent++;
            } catch (IOException e) {
               LOGGER.warn("[TurnRelay] bind send fail #{} to {}:{}: {}", sent + 1, session.host, session.port, e.getMessage());
            }

            nextSendAt = now + 700L;
         }

         resp.setLength(buf.length);
         try {
            session.socket.receive(resp);
         } catch (SocketTimeoutException e) {
            continue;
         } catch (IOException e) {
            break;
         }

         if (resp.getLength() >= 22 && buf[0] == MAGIC[0] && buf[1] == MAGIC[1] && buf[3] == TYPE_BIND_RESULT) {
            byte[] sid = new byte[16];
            System.arraycopy(buf, 4, sid, 0, 16);
            if (java.util.Arrays.equals(sid, session.sessionId) && buf[20] == session.role) {
               int code = buf[21] & 0xFF;
               session.bound = code == BIND_OK;
               if (sent > 1) {
                  LOGGER.info("[TurnRelay] bind ok after {} sends (role={})", sent, session.role);
               }
               return code;
            }
         }
      }

      return BIND_SERVER_BUSY;
   }"""

OLD_HB = """      int code = TurnRelayClient.bind(session);
      if (code != TurnRelayClient.BIND_OK) {
         VoxLinkMod.LOGGER.warn("[Turn] host bind failed code={}", code);
         session.unbind();
         return;
      }"""

NEW_HB = """      int code = TurnRelayClient.bind(session);
      if (code != TurnRelayClient.BIND_OK) {
         VoxLinkMod.LOGGER.warn("[Turn] host bind failed code={} sid={} endpoint={}:{}", code, sessionIdHex.substring(0, 8), host, port);
         session.unbind();
         return;
      }"""

OLD_CLICK = """   private void onTurnButtonClicked() {
      VoxLinkMod.getRoomManager().getConnectionManager().triggerTurnRelay();
      this.clearOurWidgets();
      this.init();
   }"""

NEW_CLICK = """   private void onTurnButtonClicked() {
      VoxLinkMod.getRoomManager().getConnectionManager().triggerTurnRelay();
      // 即时反馈：不等 500ms monitor + 2s 抑制窗口，点击当场切换状态文本
      this.voxlinkStatusText = Component.translatable("voxlink.turn.connecting").getString();
      this.voxlinkStatusColor = VoxLinkColors.WARNING;
      this.voxlinkStatusLastUpdate = System.currentTimeMillis();
      this.clearOurWidgets();
      this.init();
   }"""


def apply(path, pairs, report, tag):
    if not os.path.isfile(path):
        report.append(("MISSING", path))
        return False
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    eol = "\r\n" if "\r\n" in text else "\n"
    ok = True
    for i, (old, new) in enumerate(pairs, 1):
        old_t = old.replace("\n", eol)
        new_t = new.replace("\n", eol)
        n = text.count(old_t)
        if n != 1:
            report.append(("ANCHOR%d count=%d" % (i, n), path))
            ok = False
            continue
        if new_t in text:
            continue
        text = text.replace(old_t, new_t)
    if ok:
        with io.open(path, "w", encoding="utf-8", newline="") as f:
            f.write(text)
    return ok


def main():
    report = []
    done = fail = 0
    for loader in LOADERS:
        base = os.path.join(ROOT, loader)
        for ver in sorted(os.listdir(base)):
            tpl = os.path.join(base, ver)
            if not os.path.isdir(os.path.join(tpl, "src")):
                continue
            if loader == "fabric" and ver == "26.2":
                continue
            tag = "%s/%s" % (loader, ver)
            r = apply(os.path.join(tpl, TRC), [(OLD_BIND, NEW_BIND)], report, tag)
            r = apply(os.path.join(tpl, CM), [(OLD_HB, NEW_HB)], report, tag) and r
            r = apply(os.path.join(tpl, AJS), [(OLD_CLICK, NEW_CLICK)], report, tag) and r
            print(("OK   " if r else "FAIL ") + tag)
            done, fail = (done + 1, fail) if r else (done, fail + 1)
    print("\n==== 完成 %d, 失败 %d ====" % (done, fail))
    for kind, path in report:
        print("  [%s] %s" % (kind, os.path.relpath(path, ROOT)))
    sys.exit(1 if fail else 0)


if __name__ == "__main__":
    main()
