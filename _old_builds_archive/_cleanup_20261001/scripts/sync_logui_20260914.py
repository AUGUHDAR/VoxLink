#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""2026-09-14 提示补全轮: 只加 UiLogBus 打点, 不动任何打洞逻辑
A) startDualP2P 后补报信令状态 (若 WS 已连)
B) TURN 节点选定播报 (延迟 ms)
C) TURN 通道建立播报 (guest path up)
D) TURN 对方无应答播报 (20s 兜底)
E) logui.lan 文案改三路如实版 + 4 个新 key x13 语言 x25 模板
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
TEMPLATES = []
for loader in ('fabric', 'forge', 'neoforge'):
    base = os.path.join(ROOT, loader)
    for d in sorted(os.listdir(base)):
        if os.path.isdir(os.path.join(base, d, 'src')):
            TEMPLATES.append(os.path.join(loader, d))

LANGS = ['ar_sa', 'de_de', 'en_us', 'es_es', 'fr_fr', 'ja_jp', 'ko_kr',
         'lzh', 'pt_br', 'ru_ru', 'zh_cn', 'zh_hk', 'zh_tw']
CANON = 'fabric/26.2'


def p(rel):
    return os.path.join(ROOT, rel.replace('/', os.sep))


def read(rel):
    with open(p(rel), 'rb') as f:
        return f.read().decode('utf-8')


def write(rel, text):
    with open(p(rel), 'wb') as f:
        f.write(text.encode('utf-8'))


def eol_of(text):
    return '\r\n' if '\r\n' in text else '\n'


ANCHOR_PREPARING = 'icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.preparing");'
ANCHOR_PICKED = 'VoxLinkMod.LOGGER.info("[Turn] guest picked node {} rtt={}ms (nodes={})", probed.get(0).node.id, probed.get(0).rttMs, nodes.size());'
ANCHOR_PATHUP = 'VoxLinkMod.LOGGER.info("[Turn] guest path up, bridge starting");'
ANCHOR_NOREADY = 'VoxLinkMod.LOGGER.warn("[Turn] guest got no turn_ready in 20s (host dead / signal lost), teardown");'


def insert_after_line(text, anchor, newline):
    """在锚点行后插入新行: 沿用锚点行尾, 字节级已验证的形态。"""
    idx = text.find(anchor)
    assert idx != -1, 'anchor missing: ' + anchor[:60]
    le = text.find('\n', idx)
    assert le != -1
    term = '\r\n' if text[le - 1:le] == '\r' else '\n'
    line_start = text.rfind('\n', 0, idx) + 1
    indent = text[line_start:idx]
    ins = newline.replace('\n', term)
    # text[:le] 已含锚点行终止符的 '\r'(若有), 补 '\n' 即成完整行尾
    return text[:le] + '\n' + indent + ins + text[le:]


def do_java():
    for t in TEMPLATES:
        rel = t + '/src/main/java/icu/wuhui/voxlink/room/ConnectionManager.java'
        text = read(rel)
        assert 'voxlink.logui.turn_node' not in text, rel + ' already patched'
        assert 'this.signalingClient' in text, rel + ' no signalingClient field'
        for a in (ANCHOR_PREPARING, ANCHOR_PICKED, ANCHOR_PATHUP, ANCHOR_NOREADY):
            assert text.count(a) == 1, rel + ' anchor != 1: ' + a[:50]
        text = insert_after_line(text, ANCHOR_PREPARING,
            'if (this.signalingClient != null && this.signalingClient.isWsConnected()) {\n'
            '         icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.signaling_ready");\n'
            '      }')
        text = insert_after_line(text, ANCHOR_PICKED,
            'icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.turn_node", probed.get(0).rttMs);')
        text = insert_after_line(text, ANCHOR_PATHUP,
            'icu.wuhui.voxlink.ui.UiLogBus.push(0, "voxlink.logui.turn_established");')
        text = insert_after_line(text, ANCHOR_NOREADY,
            'icu.wuhui.voxlink.ui.UiLogBus.push(2, "voxlink.logui.turn_no_ready");')
        assert text.count('{') == text.count('}'), rel + ' braces'
        write(rel, text)
    print('java hooks: %d templates' % len(TEMPLATES))


LAN_COPY = {
    'ar_sa': 'جارٍ محاولة الاتصال المباشر P2P (LAN / IPv6 / UDP)...',
    'de_de': 'P2P-Direktverbindung (LAN / IPv6 / UDP Hole Punching) wird versucht...',
    'en_us': 'Trying P2P direct connection (LAN / IPv6 / UDP hole punch)...',
    'es_es': 'Intentando conexion directa P2P (LAN / IPv6 / UDP)...',
    'fr_fr': 'Essai de connexion directe P2P (LAN / IPv6 / UDP)...',
    'ja_jp': 'P2P直接接続（LAN / IPv6 / UDPポート開放）を試行中…',
    'ko_kr': 'P2P 직접 연결(LAN / IPv6 / UDP 홀펀칭) 시도 중…',
    'lzh': '並試P2P直連（區網、IPv6、UDP打洞）…',
    'pt_br': 'Tentando conexao direta P2P (LAN / IPv6 / UDP)...',
    'ru_ru': 'Пробуем прямое P2P-подключение (LAN / IPv6 / UDP)...',
    'zh_cn': '正在尝试 P2P 直连（局域网 / IPv6 / UDP 打洞）…',
    'zh_hk': '正在嘗試 P2P 直連（區網 / IPv6 / UDP 打洞）…',
    'zh_tw': '正在嘗試 P2P 直連（區網 / IPv6 / UDP 打洞）…',
}

NEW_KEYS = {
    'voxlink.logui.signaling_ready': {
        'ar_sa': 'تم الاتصال بخدمة الإشارة', 'de_de': 'Signalserver verbunden',
        'en_us': 'Signaling server connected', 'es_es': 'Servidor de senales conectado',
        'fr_fr': 'Serveur de signalisation connecté', 'ja_jp': 'シグナリングサーバーに接続済み',
        'ko_kr': '시그널링 서버 연결됨', 'lzh': '信臺已通', 'pt_br': 'Servidor de sinalizacao conectado',
        'ru_ru': 'Сигнальный сервер подключен', 'zh_cn': '信令服务器已连接',
        'zh_hk': '信號伺服器已連線', 'zh_tw': '信號伺服器已連線'},
    'voxlink.logui.turn_node': {
        'ar_sa': 'تم اختيار عقدة الترحيل (%s ms)', 'de_de': 'Relay-Knoten ausgewählt (%s ms)',
        'en_us': 'Relay node selected (%s ms)', 'es_es': 'Nodo de relevo seleccionado (%s ms)',
        'fr_fr': 'Nœud de relais sélectionné (%s ms)', 'ja_jp': '中継ノードを選択（遅延 %s ms）',
        'ko_kr': '중계 노드 선택됨(지연 %s ms)', 'lzh': '擇中繼（遲 %s ms）',
        'pt_br': 'No de relevo selecionado (%s ms)', 'ru_ru': 'Выбран узел ретрансляции (%s ms)',
        'zh_cn': '已选中中继节点（延迟 %s ms）', 'zh_hk': '已選中繼節點（延遲 %s ms）',
        'zh_tw': '已選中繼節點（延遲 %s ms）'},
    'voxlink.logui.turn_established': {
        'ar_sa': 'تم إنشاء قناة الترحيل', 'de_de': 'Relay-Kanal hergestellt',
        'en_us': 'Relay channel established', 'es_es': 'Canal de relevo establecido',
        'fr_fr': 'Canal de relais établi', 'ja_jp': '中継チャネル確立',
        'ko_kr': '중계 채널 연결됨', 'lzh': '中繼道已成', 'pt_br': 'Canal de relevo estabelecido',
        'ru_ru': 'Канал ретрансляции установлен', 'zh_cn': '中继通道已建立',
        'zh_hk': '中繼通道已建立', 'zh_tw': '中繼通道已建立'},
    'voxlink.logui.turn_no_ready': {
        'ar_sa': 'لم يؤكد الطرف الآخر الترحيل، جارٍ متابعة الاتصال المباشر...',
        'de_de': 'Gegenstelle hat Relay nicht bestätigt, weiter mit Direktversuch...',
        'en_us': 'Peer relay not confirmed, continuing direct attempts...',
        'es_es': 'Relevo del otro jugador no confirmado, continuando conexion directa...',
        'fr_fr': "Relais du correspondant non confirmé, nouvelle tentative directe...",
        'ja_jp': '相手の中継確認なし、直接接続を継続…',
        'ko_kr': '상대 중계 미확인, 직접 연결 계속…', 'lzh': '彼未確認中繼，再試直連',
        'pt_br': 'Relevo do outro jogador nao confirmado, continuando conexao direta...',
        'ru_ru': 'Ретрансляция не подтверждена, продолжаем прямое подключение...',
        'zh_cn': '对方未确认中继通道，继续尝试直连',
        'zh_hk': '對方未確認中繼通道，繼續嘗試直連', 'zh_tw': '對方未確認中繼通道，繼續嘗試直連'},
}


def patch_lang(text, lang):
    pat = re.compile('("voxlink\\.logui\\.lan": )"[^"]*"')
    assert len(pat.findall(text)) == 1, 'lan @' + lang
    text = pat.sub(lambda m: m.group(1) + json.dumps(LAN_COPY[lang], ensure_ascii=False), text)
    lines = ['    "%s": %s,' % (k, json.dumps(v[lang], ensure_ascii=False))
             for k, v in NEW_KEYS.items()]
    m = re.search(r'^(\s*"voxlink\.logui\.turn_allocating": "[^"]*",\s*)$', text, re.M)
    assert m, 'turn_allocating line @' + lang
    text = text[:m.end()] + '\n' + '\n'.join(lines) + text[m.end():]
    json.loads(text)
    return text


def do_lang():
    canon_dir = CANON + '/src/main/resources/assets/voxlink/lang'
    import hashlib
    for lang in LANGS:
        rel = canon_dir + '/' + lang + '.json'
        h = hashlib.md5(read(rel).encode('utf-8')).hexdigest()
        for t in TEMPLATES:
            other = read(t + '/src/main/resources/assets/voxlink/lang/' + lang + '.json')
            assert hashlib.md5(other.encode('utf-8')).hexdigest() == h, 'lang %s differs @%s' % (lang, t)
    for lang in LANGS:
        rel = canon_dir + '/' + lang + '.json'
        text = read(rel)
        assert 'voxlink.logui.turn_node' not in text, 'already patched ' + lang
        write(rel, patch_lang(text, lang))
    for lang in LANGS:
        src = read(canon_dir + '/' + lang + '.json')
        json.loads(src)
        for t in TEMPLATES:
            write(t + '/src/main/resources/assets/voxlink/lang/' + lang + '.json', src)
    print('lang: %d langs x %d templates' % (len(LANGS), len(TEMPLATES)))


def main():
    only = sys.argv[1:] or ['java', 'lang']
    if 'java' in only:
        do_java()
    if 'lang' in only:
        do_lang()
    print('ALL DONE')


if __name__ == '__main__':
    main()
