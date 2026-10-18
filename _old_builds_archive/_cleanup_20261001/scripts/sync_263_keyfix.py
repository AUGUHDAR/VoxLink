# -*- coding: utf-8 -*-
"""26.3 SDL键码修复: GLFW 257(Enter) 失效, key()已改为SDL scancode空间
用 InputConstants.KEY_RETURN(=40) 常量替代魔数, 仅26.3模板(旧版仍为GLFW空间不动)
"""
import os

BASE = os.path.dirname(os.path.abspath(__file__))

for loader in ('fabric', 'neoforge'):
    f = os.path.join(BASE, loader, '26.3', 'src/main/java/icu/wuhui/voxlink/ui/RoomBrowserScreenBase.java')
    with open(f, 'rb') as fh:
        d = fh.read()
    old = b'keyCode == 257'
    assert d.count(old) == 1, f"{loader} keycode anchor"
    d = d.replace(old, b'keyCode == InputConstants.KEY_RETURN', 1)
    imp_anchor = b'import icu.wuhui.voxlink.VoxLinkConstants;'
    assert d.count(imp_anchor) == 1, f"{loader} import anchor"
    nl = b'\r\n' if b'\r\n' in d[:2000] else b'\n'
    d = d.replace(imp_anchor, b'import com.mojang.blaze3d.platform.InputConstants;' + nl + imp_anchor, 1)
    with open(f, 'wb') as fh:
        fh.write(d)
    print(f"[OK] {loader} RoomBrowserScreenBase KEY_RETURN常量化")
print("DONE")
