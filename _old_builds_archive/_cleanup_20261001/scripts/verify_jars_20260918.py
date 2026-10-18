# -*- coding: utf-8 -*-
# 构建后类完整性校验: 27个jar的PunchProfile <clinit>顺序 + RoomManager 安全网常量内联
import glob
import os
import subprocess
import sys
import tempfile
import zipfile

JAVAP = r'C:\Program Files\Java\jdk-25.0.2\bin\javap.exe'
JARS = (glob.glob(r'd:\桌面\voxlink\成品\Fabric\*.jar')
        + glob.glob(r'd:\桌面\voxlink\成品\NeoForge\*.jar')
        + glob.glob(r'd:\桌面\voxlink\成品\Forge\*.jar'))


def line_no(lines, needle, exclude='$'):
    for i, l in enumerate(lines):
        if needle in l and exclude not in l.split(needle)[0]:
            return i
    return -1


def check_jar(jar):
    z = zipfile.ZipFile(jar)
    names = z.namelist()
    must = ['icu/wuhui/voxlink/network/PunchProfile.class',
            'icu/wuhui/voxlink/network/PunchProfile$SymParams.class',
            'icu/wuhui/voxlink/network/PunchParams.class',
            'icu/wuhui/voxlink/room/RoomManager.class',
            'icu/wuhui/voxlink/network/UdpHolePuncher.class']
    for n in must:
        assert n in names, '%s missing %s' % (os.path.basename(jar), n)
    tmp = tempfile.mkdtemp()
    z.extract('icu/wuhui/voxlink/network/PunchProfile.class', tmp)
    z.extract('icu/wuhui/voxlink/room/RoomManager.class', tmp)
    r = subprocess.run([JAVAP, '-p', '-c', os.path.join(tmp, 'icu/wuhui/voxlink/network/PunchProfile.class')],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    lines = [l for l in r.stdout.splitlines() if 'class icu/wuhui/voxlink/network/PunchProfile' in l or 'Field RECIPE' in l or 'Field DEFAULT' in l]
    recipe = line_no(lines, 'Field RECIPE')
    first_inst = line_no(lines, 'class icu/wuhui/voxlink/network/PunchProfile')
    assert 0 <= recipe < first_inst, '%s clinit order wrong (%d vs %d)' % (os.path.basename(jar), recipe, first_inst)
    r2 = subprocess.run([JAVAP, '-p', '-c', os.path.join(tmp, 'icu/wuhui/voxlink/room/RoomManager.class')],
                        capture_output=True, text=True, encoding='utf-8', errors='replace')
    ok = False
    body = r2.stdout.splitlines()
    for i, l in enumerate(body):
        # ++this.wsSafetyTicks < 6 常量内联: bipush 6 + if_icmpge(小于则休眠return)
        if 'bipush' in l and l.split('bipush')[1].strip().startswith('6'):
            for j in range(i + 1, min(i + 4, len(body))):
                if 'if_icmpge' in body[j]:
                    ok = True
    assert ok, '%s safety-net tick missing' % os.path.basename(jar)


def main():
    ver = sys.argv[1] if len(sys.argv) > 1 else '1.1.7'
    jars = [j for j in JARS if ver in os.path.basename(j)]
    print('jars:', len(jars))
    for j in sorted(jars):
        check_jar(j)
        print('OK', os.path.basename(j))
    print('ALL JARS VERIFIED')


if __name__ == '__main__':
    main()
