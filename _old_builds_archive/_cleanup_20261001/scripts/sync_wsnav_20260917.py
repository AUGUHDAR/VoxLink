# -*- coding: utf-8 -*-
"""09-17 波次2传播: WS优先改造+页面流转+TURN节点加固 -> 26模板
整拷: TurnRelayClient/ConnectionHelper/SignalingWsTransport(HEAD同源校验)
锚点: RoomManager×2(WS休眠) + AttemptingJoinScreen×1(entryScreen getter)
"""
import os, subprocess

BASE = r'D:\桌面\voxlink'
T262 = os.path.join(BASE, 'fabric', '26.2', 'src', 'main', 'java', 'icu', 'wuhui', 'voxlink')

def norm(b): return b.replace(b'\r\r\n', b'\n').replace(b'\r\n', b'\n').replace(b'\r', b'\n')
def git_show(p): return subprocess.run(['git', 'show', 'HEAD:' + p.replace('\\', '/')], capture_output=True).stdout

applied, failed = [], []

WHOLECOPY = ['network/TurnRelayClient.java', 'network/ConnectionHelper.java', 'network/SignalingWsTransport.java']

ANCHORS = {
 'room/RoomManager.java': [
  ("R1 WS休眠节拍",
   "      RoomManager.RoomState state = this.currentRoom.get();\n      if (state != null && !state.roomInfo.isHost()) {\n         // WS 健康时放宽到 1000ms，断开则保持 200ms 高频兜底\n         this.currentSignalPollInterval = this.signalingClient.isWsConnected() ? 1000L : 200L;\n      } else {",
   "      RoomManager.RoomState state = this.currentRoom.get();\n      if (this.signalingClient.isWsConnected()) {\n         // WS 健康：轮询静默休眠，5s 空转节拍\n         this.currentSignalPollInterval = 5000L;\n      } else if (state != null && !state.roomInfo.isHost()) {\n         this.currentSignalPollInterval = 200L;\n      } else {"),
  ("R2 doSignalPoll休眠",
   "         RoomManager.RoomState state = this.currentRoom.get();\n         if (state == null || state == PENDING) {\n            return;\n         }\n\n         if (!this.signalPollInFlight.compareAndSet(false, true)) {",
   "         RoomManager.RoomState state = this.currentRoom.get();\n         if (state == null || state == PENDING) {\n            return;\n         }\n\n         // WS 健康即休眠：零请求，仅校正节拍\n         if (this.signalingClient.isWsConnected()) {\n            this.recoverSignalPollInterval();\n            return;\n         }\n\n         if (!this.signalPollInFlight.compareAndSet(false, true)) {"),
 ],
 'ui/AttemptingJoinScreen.java': [
  ("A1 entryScreen getter",
   "      this.modSyncChecked = modSyncChecked;\n   }",
   "      this.modSyncChecked = modSyncChecked;\n   }\n\n   public Screen entryScreen() {\n      return this.parent;\n   }"),
 ],
}

for rel in WHOLECOPY:
    ref = norm(git_show('fabric/26.2/src/main/java/icu/wuhui/voxlink/' + rel))
    fixed = norm(open(os.path.join(T262, rel.replace('/', os.sep)), 'rb').read())
    assert fixed != ref
    for loader in ('fabric', 'neoforge', 'forge'):
        for d in sorted(os.listdir(os.path.join(BASE, loader))):
            if loader == 'fabric' and d == '26.2':
                continue
            p = os.path.join(BASE, loader, d, 'src', 'main', 'java', 'icu', 'wuhui', 'voxlink', rel.replace('/', os.sep))
            if not os.path.isfile(p):
                failed.append(f"{loader}/{d}: 缺 {rel}"); continue
            cur = norm(open(p, 'rb').read())
            if cur == fixed:
                continue
            if cur == ref:
                open(p, 'wb').write(fixed)
                applied.append(f"{loader}/{d}: {rel.split('/')[-1]}整拷")
            else:
                failed.append(f"{loader}/{d}: {rel.split('/')[-1]}基准不匹配")

for rel, pairs in ANCHORS.items():
    for loader in ('fabric', 'neoforge', 'forge'):
        for d in sorted(os.listdir(os.path.join(BASE, loader))):
            if loader == 'fabric' and d == '26.2':
                continue
            p = os.path.join(BASE, loader, d, 'src', 'main', 'java', 'icu', 'wuhui', 'voxlink', rel.replace('/', os.sep))
            if not os.path.isfile(p):
                failed.append(f"{loader}/{d}: 缺 {rel}"); continue
            raw = open(p, 'rb').read()
            dcur = norm(raw)
            eol = b'\r\n' if b'\r\n' in raw[:4000] else b'\n'
            cnt = 0
            for name, old, new in pairs:
                ob, nb = old.encode(), new.encode()
                if nb in dcur:
                    continue
                n = dcur.count(ob)
                if n != 1:
                    failed.append(f"{loader}/{d}: [{name}] x{n}"); continue
                dcur = dcur.replace(ob, nb, 1); cnt += 1
            if cnt:
                open(p, 'wb').write(dcur if eol == b'\n' else dcur.replace(b'\n', b'\r\n'))
                applied.append(f"{loader}/{d}: {rel.split('/')[-1]} {cnt}/{len(pairs)}")

print("=== 成功", len(applied), "===")
print("\n".join(applied))
print("=== 失败", len(failed), "===")
print("\n".join(failed) if failed else "(无)")
