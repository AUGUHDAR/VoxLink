# -*- coding: utf-8 -*-
"""26.3 API 变更修复:
1. publishServer 4参->3参(GameType移除), 改用 setDefaultGameType 前置设置
2. KeyEvent.scancode() 移除(SDL3), 改用 keycode()
"""
import os, re

BASE = os.path.dirname(os.path.abspath(__file__))

FIX_PUBLISH = (
    re.compile(rb'(\r?\n)([ \t]*)boolean published = server\.publishServer\(MultiplayerScope\.LAN, selectedGameType, allowCommands, mcPort\);'),
    rb'\1\2server.setDefaultGameType(selectedGameType);\1\2boolean published = server.publishServer(MultiplayerScope.LAN, allowCommands, mcPort);',
)
FIX_SCANCODE = (
    re.compile(rb'event\.scancode\(\)'),
    rb'event.keycode()',
)

for loader in ('fabric', 'neoforge'):
    crs = os.path.join(BASE, loader, '26.3', 'src/main/java/icu/wuhui/voxlink/ui/CreateRoomScreen.java')
    rbs = os.path.join(BASE, loader, '26.3', 'src/main/java/icu/wuhui/voxlink/ui/RoomBrowserScreenBase.java')
    with open(crs, 'rb') as f:
        d = f.read()
    pat, rep = FIX_PUBLISH
    assert len(pat.findall(d)) == 1, f"{loader} publishServer anchor"
    d = pat.sub(rep, d, count=1)
    with open(crs, 'wb') as f:
        f.write(d)
    print(f"[OK] {loader} CreateRoomScreen publishServer+setDefaultGameType")
    with open(rbs, 'rb') as f:
        d = f.read()
    pat, rep = FIX_SCANCODE
    assert len(pat.findall(d)) == 1, f"{loader} scancode anchor"
    d = pat.sub(rep, d, count=1)
    with open(rbs, 'wb') as f:
        f.write(d)
    print(f"[OK] {loader} RoomBrowserScreenBase scancode->keycode")
print("DONE")
