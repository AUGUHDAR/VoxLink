# -*- coding: utf-8 -*-
# RECIPE静态初始化顺序修复 + WS安全网统一(26.2/26.3的<1坏实现回归<6常量化+首拍真拉)
# 铁律: 幂等/count==1断言/norm行尾/写入后校验
import glob
import re

ROOT = r'd:\桌面\voxlink'

PP_DEL_OLD = ('   public static final SymParams RECIPE = new SymParams(25, 20, 100, 5000, 600, 800, 3, 1, 2, 180, 3000);\n'
              '\n'
              '   private static volatile PunchProfile current = DEFAULT;')
PP_DEL_NEW = '   private static volatile PunchProfile current = DEFAULT;'
PP_INS_OLD = ('   public final SymParams sym;\n'
              '   private static final PunchProfile.SendParams SEND_DEFAULT')
PP_INS_NEW = ('   public final SymParams sym;\n'
              '   // 必须先于所有静态profile实例声明,否则构造时sym读到null\n'
              '   public static final SymParams RECIPE = new SymParams(25, 20, 100, 5000, 600, 800, 3, 1, 2, 180, 3000);\n'
              '   private static final PunchProfile.SendParams SEND_DEFAULT')

RM_A_OLD = ('   private int wsSafetyTicks = 0;\n'
            '\n'
            '   private void startSignalPoll() {\n'
            '      this.signalPollTimestamp.set(System.currentTimeMillis() - 10000L);')
RM_A_NEW = ('   // WS健康期轮询静默休眠,第6拍(30s)安全网补拉,防推送漏收与半开黑洞\n'
            '   private static final int WS_SAFETY_NET_TICKS = 6;\n'
            '   private int wsSafetyTicks = 0;\n'
            '\n'
            '   private void startSignalPoll() {\n'
            '      this.signalPollTimestamp.set(System.currentTimeMillis() - 10000L);\n'
            '      // 首拍真拉清积压(WS订阅前写入的信号推送不会送达)\n'
            '      this.forcePollOnce.set(true);')
RM_NOTE_OLD = '// WS健康:第6拍安全网补拉(防推送漏收)'
RM_NOTE_NEW = '// WS健康:休眠5拍后第6拍安全网补拉(防推送漏收/半开黑洞)'
RM_B_RE = re.compile(r'if \(\+\+this\.wsSafetyTicks < (?:1|6)\) \{')


def norm(b):
    return b.replace(b'\r\r\n', b'\n').replace(b'\r\n', b'\n').replace(b'\r', b'\n')


def is_crlf(raw):
    crlf = raw.count(b'\r\n')
    lone = norm(raw).count(b'\n') - crlf
    return crlf > lone


def main():
    pps = sorted(glob.glob(ROOT + r'\*\*\src\main\java\icu\wuhui\voxlink\network\PunchProfile.java'))
    rms = sorted(glob.glob(ROOT + r'\*\*\src\main\java\icu\wuhui\voxlink\room\RoomManager.java'))
    assert len(pps) == 27 and len(rms) == 27, 'template count %d/%d' % (len(pps), len(rms))

    for path in pps:
        raw = open(path, 'rb').read()
        crlf = is_crlf(raw)
        text = norm(raw).decode('utf-8')
        tag = '/'.join(path.replace('\\', '/').split('/')[3:5])
        if text.find('SymParams RECIPE') < text.find('PunchProfile DEFAULT'):
            print('%s PP already ok' % tag)
            continue
        assert text.count(PP_DEL_OLD) == 1, '%s PP del anchor' % tag
        assert text.count(PP_INS_OLD) == 1, '%s PP ins anchor' % tag
        text = text.replace(PP_DEL_OLD, PP_DEL_NEW).replace(PP_INS_OLD, PP_INS_NEW)
        ri, di = text.find('SymParams RECIPE'), text.find('PunchProfile DEFAULT')
        assert 0 < ri < di and text.count('SymParams RECIPE') == 1, '%s PP verify' % tag
        out = text.replace('\n', '\r\n') if crlf else text
        data = out.encode('utf-8')
        assert len(data) > 1000
        open(path, 'wb').write(data)
        print('%s PP patched (%s)' % (tag, 'CRLF' if crlf else 'LF'))

    for path in rms:
        raw = open(path, 'rb').read()
        crlf = is_crlf(raw)
        text = norm(raw).decode('utf-8')
        tag = '/'.join(path.replace('\\', '/').split('/')[3:5])
        if 'WS_SAFETY_NET_TICKS' in text:
            print('%s RM already ok' % tag)
            continue
        assert text.count(RM_A_OLD) == 1, '%s RM-A anchor' % tag
        text = text.replace(RM_A_OLD, RM_A_NEW)
        if RM_NOTE_OLD in text:
            text = text.replace(RM_NOTE_OLD, RM_NOTE_NEW)
        m = RM_B_RE.findall(text)
        assert len(m) == 1, '%s RM-B anchor %d' % (tag, len(m))
        text = RM_B_RE.sub('if (++this.wsSafetyTicks < WS_SAFETY_NET_TICKS) {', text)
        assert text.count('WS_SAFETY_NET_TICKS') == 2, '%s RM const count' % tag
        assert text.count('this.forcePollOnce.set(true);') == 2, '%s RM force count' % tag
        assert not re.search(r'wsSafetyTicks < (?:1|6)\)', text), '%s RM old tick left' % tag
        out = text.replace('\n', '\r\n') if crlf else text
        data = out.encode('utf-8')
        assert len(data) > 1000
        open(path, 'wb').write(data)
        print('%s RM patched (%s)' % (tag, 'CRLF' if crlf else 'LF'))
    print('ALL DONE')


if __name__ == '__main__':
    main()
