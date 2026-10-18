#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""2026-09-14 轮: 1.1.6
A) CreateRoomScreen 端口实测修复 x25
B) RelatedLinksScreen 相关链接页 x25 + VoxLinkScreen 按钮改跳转
C) lang: P2P 文案 + links keys (13语言 x25 模板整拷)
D) mod_version 1.1.5 -> 1.1.6
"""
import hashlib
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

ERRORS = []


def p(rel):
    return os.path.join(ROOT, rel.replace('/', os.sep))


def read(rel):
    with open(p(rel), 'rb') as f:
        return f.read().decode('utf-8')


def write(rel, text):
    data = text.encode('utf-8')
    with open(p(rel), 'wb') as f:
        f.write(data)


def eol_of(text):
    return '\r\n' if '\r\n' in text else '\n'


def count(text, sub):
    return text.count(sub)


def remove_method(text, signature):
    """按大括号配平删除整个方法, 连同其上方一个空行。"""
    idx = text.find(signature)
    assert idx != -1, 'signature missing: ' + signature
    ls = text.rfind('\n', 0, idx) + 1
    if text[ls - 2:ls] == '\n\n':
        ls -= 1
    i = text.find('{', idx)
    depth = 0
    j = i
    while j < len(text):
        c = text[j]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                break
        j += 1
    assert depth == 0, 'unbalanced braces at: ' + signature
    end = j + 1
    while end < len(text) and text[end] in '\r\n':
        end += 1
        break
    return text[:ls] + text[end:]


# ---------------------------------------------------------------- A) 端口修复
IMPORT_ANCHOR = 'import java.lang.reflect.Field;'
IMPORTS_NEW = ('import java.io.IOException;\n'
               'import java.lang.reflect.Field;\n'
               'import java.net.InetSocketAddress;\n'
               'import java.net.Socket;')
EFF_OLD = 'int effectivePort = server.getPort() > 0 ? server.getPort() : mcPort;'
EFF_NEW = 'int effectivePort = resolveLanPort(server, mcPort);'
CLOSE_ANCHOR = '   private void closeLan() {'

HELPERS = '''   private static boolean waitPortListening(int port, long timeoutMs) {
      long deadline = System.currentTimeMillis() + timeoutMs;
      while (System.currentTimeMillis() < deadline) {
         try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
         } catch (IOException e) {
            try {
               Thread.sleep(250L);
            } catch (InterruptedException ie) {
               Thread.currentThread().interrupt();
               return false;
            }
         }
      }

      return false;
   }

   private static int findBoundLanPort(IntegratedServer server) {
      try {
         ServerConnectionListener conn = server.getConnection();
         if (conn == null) {
            return -1;
         }

         Field channelsField = conn.getClass().getDeclaredField("channels");
         channelsField.setAccessible(true);
         List<?> channels = (List<?>)channelsField.get(conn);
         if (channels == null) {
            return -1;
         }

         synchronized (channels) {
            for (Object entry : channels) {
               if (entry instanceof ChannelFuture future
                  && future.channel().localAddress() instanceof InetSocketAddress addr) {
                  return addr.getPort();
               }
            }
         }
      } catch (Exception e) {
         VoxLinkMod.LOGGER.debug("lan channels probe failed: {}", e.getMessage());
      }

      return -1;
   }

   // 部分版本忽略指定端口，实测为准
   private static int resolveLanPort(IntegratedServer server, int requested) {
      if (waitPortListening(requested, 3000L)) {
         return requested;
      }

      int apiPort = server.getPort();
      if (apiPort > 0 && apiPort != requested && waitPortListening(apiPort, 1200L)) {
         VoxLinkMod.LOGGER.warn("[CreateRoom] MC port {} not listening, using {}", requested, apiPort);
         return apiPort;
      }

      int bound = findBoundLanPort(server);
      if (bound > 0 && bound != requested && waitPortListening(bound, 1200L)) {
         VoxLinkMod.LOGGER.warn("[CreateRoom] MC port {} not listening, using {}", requested, bound);
         return bound;
      }

      VoxLinkMod.LOGGER.warn("[CreateRoom] LAN port probe failed, fallback {}", requested);
      return requested;
   }

'''

def do_port_fix():
    for t in TEMPLATES:
        rel = t + '/src/main/java/icu/wuhui/voxlink/ui/CreateRoomScreen.java'
        text = read(rel)
        eol = eol_of(text)
        assert count(text, EFF_OLD) == 1, rel + ' eff anchor != 1'
        assert count(text, IMPORT_ANCHOR) == 1, rel + ' import anchor != 1'
        assert count(text, CLOSE_ANCHOR) == 1, rel + ' closeLan anchor != 1'
        assert 'resolveLanPort' not in text, rel + ' already patched'
        imports_new = IMPORTS_NEW.replace('\n', eol)
        text = text.replace(IMPORT_ANCHOR, imports_new)
        text = text.replace(EFF_OLD, EFF_NEW)
        text = text.replace(CLOSE_ANCHOR, HELPERS.replace('\n', eol) + CLOSE_ANCHOR, 1)
        assert text.count('{') == text.count('}'), rel + ' braces'
        write(rel, text)
    print('A) port fix: %d templates' % len(TEMPLATES))


# ---------------------------------------------------------------- B) 链接页
QQ_URL = ('https://qm.qq.com/cgi-bin/qm/qr?k=OEkk9L8m8jdFMkbGDhKZs0u2U0azLAPo'
          '&jump_from=webapi&authKey=v0dYAQniGZypAJuoPZW/7FL0bfoc32h68oIHd9lqGwOvAduzcwsJNR7Mei9/YugW')

LINKS_TABLE = (
    '   private static final String[][] LINKS = {\n'
    '      {"voxlink.links.mcmod", "https://www.mcmod.cn/class/28295.html"},\n'
    '      {"voxlink.links.github", "https://github.com/AUGUHDAR/VoxLink"},\n'
    '      {"voxlink.links.modrinth", "https://modrinth.com/mod/voxlink"},\n'
    '      {"voxlink.links.curseforge", "https://www.curseforge.com/minecraft/mc-mods/voxlink"},\n'
    '      {"voxlink.links.site", "https://p2p.wuhui.icu/"},\n'
    '      {"voxlink.links.discord", "https://discord.gg/XaAFxvzPDS"},\n'
    '      {"voxlink.links.qq", "%s"},\n'
    '      {"voxlink.links.gitee", "https://gitee.com/AUGUHDAR/VoxLink"}\n'
    '   };\n'
) % QQ_URL

SCREEN_COMMON = '''   private final Screen parent;

   public RelatedLinksScreen(Screen parent) {
      super(Component.translatable("voxlink.links.title"));
      this.parent = parent;
   }

   protected void init() {
      super.init();
      int centerX = this.width / 2;
      int top = Math.max(46, this.height / 2 - 58);
      for (int i = 0; i < LINKS.length; i++) {
         int x = (i %% 2 == 0) ? centerX - 100 : centerX + 2;
         int y = top + (i / 2) * 24;
         String url = LINKS[i][1];
         this.addRenderableWidget(
            Button.builder(Component.translatable(LINKS[i][0]), button -> FeedbackScreen.openBrowser(url))
               .bounds(x, y, 98, 20)
               .build()
         );
      }

      this.addRenderableWidget(
         Button.builder(Component.translatable("voxlink.back"), button -> this.onClose())
            .bounds(centerX - 100, top + 4 * 24 + 6, 200, 20)
            .build()
      );
   }
'''

SCREEN_HEAD = '''package icu.wuhui.voxlink.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class RelatedLinksScreen extends VoxLinkScreenBase {
'''

SCREEN_LEGACY_TAIL = '''   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      super.render(graphics, mouseX, mouseY, partialTick);
      this.drawTitle(graphics, 20);
   }

   public void onClose() {
      Minecraft.getInstance().setScreen(this.parent);
   }
}
'''

SCREEN_MODERN_TAIL = '''   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      super.extractRenderState(graphics, mouseX, mouseY, partialTick);
      this.drawTitle(graphics, 20);
   }

   public void onClose() {
      Minecraft.getInstance().gui.setScreen(this.parent);
   }
}
'''

def make_screen(modern):
    table = LINKS_TABLE
    body = SCREEN_COMMON.replace('%%', '%')
    head = SCREEN_HEAD
    if modern:
        head = head.replace(
            'import net.minecraft.client.gui.components.Button;',
            'import net.minecraft.client.gui.GuiGraphicsExtractor;\nimport net.minecraft.client.gui.components.Button;')
        tail = SCREEN_MODERN_TAIL
    else:
        head = head.replace(
            'import net.minecraft.client.gui.components.Button;',
            'import net.minecraft.client.gui.GuiGraphics;\nimport net.minecraft.client.gui.components.Button;')
        tail = SCREEN_LEGACY_TAIL
    return head + '\n' + table + '\n' + body + '\n' + tail

# 26.1 混合形态：extractRenderState 新渲染 + 老式 mc.setScreen（记忆 voxlink-mc-api-eras）
BTN_OLD = 'button -> this.openWebsite()'
BTN_LEGACY = 'button -> Minecraft.getInstance().setScreen(new RelatedLinksScreen(this))'
BTN_MODERN = 'button -> Minecraft.getInstance().gui.setScreen(new RelatedLinksScreen(this))'

def do_links_page():
    for t in TEMPLATES:
        ui = t + '/src/main/java/icu/wuhui/voxlink/ui'
        modern = '26.1' in t or '26.2' in t
        screen_rel = ui + '/RelatedLinksScreen.java'
        assert not os.path.exists(p(screen_rel)), screen_rel + ' exists'
        write(screen_rel, make_screen(modern))

        vrel = ui + '/VoxLinkScreen.java'
        text = read(vrel)
        assert count(text, BTN_OLD) == 1, vrel + ' btn anchor != 1'
        text = text.replace(BTN_OLD, BTN_MODERN if modern else BTN_LEGACY)
        text = remove_method(text, 'private void openWebsite()')
        assert 'openWebsite' not in text, vrel + ' openWebsite residue'
        assert text.count('{') == text.count('}'), vrel + ' braces'
        write(vrel, text)
    print('B) links page: %d templates' % len(TEMPLATES))


# ---------------------------------------------------------------- C) lang
REPL = {
    'voxlink.logui.lan': {
        'ar_sa': 'جارٍ محاولة الاتصال المباشر P2P (ثقب UDP)...',
        'de_de': 'P2P-Direktverbindung (UDP Hole Punching) wird versucht...',
        'en_us': 'Trying P2P direct connection (UDP hole punch)...',
        'es_es': 'Intentando conexion directa P2P (perforacion UDP)...',
        'fr_fr': "Essai de connexion directe P2P (perforation UDP)...",
        'ja_jp': 'P2P直接接続（UDPポート開放）を試行中…',
        'ko_kr': 'P2P 직접 연결(UDP 홀펀칭) 시도 중…',
        'lzh': '正試P2P直連…',
        'pt_br': 'Tentando conexao direta P2P (UDP)...',
        'ru_ru': 'Пробуем прямое P2P-подключение (UDP-пробивка)...',
        'zh_cn': '正在尝试 P2P 直连（UDP 打洞）…',
        'zh_hk': '正在嘗試 P2P 直連（UDP 打洞）…',
        'zh_tw': '正在嘗試 P2P 直連（UDP 打洞）…',
    },
    'voxlink.logui.punching': {
        'ar_sa': 'جارٍ محاولة الاتصال المباشر P2P (%s)...',
        'de_de': 'P2P-Direktverbindung wird versucht (%s)...',
        'en_us': 'Trying P2P direct connection (attempt %s)...',
        'es_es': 'Intentando conexion directa P2P (%s)...',
        'fr_fr': 'Tentative de connexion directe P2P (%s)...',
        'ja_jp': 'P2P直接接続を試行中（%s 回目）…',
        'ko_kr': 'P2P 직접 연결 시도 중(%s회)…',
        'lzh': '正試P2P直連（第 %s 回）…',
        'pt_br': 'Tentando conexao direta P2P (%s)...',
        'ru_ru': 'Попытка прямого P2P-подключения (%s)...',
        'zh_cn': '正在尝试 P2P 直连（第 %s 次）…',
        'zh_hk': '正在嘗試 P2P 直連（第 %s 次）…',
        'zh_tw': '正在嘗試 P2P 直連（第 %s 次）…',
    },
    'voxlink.logui.tcp_fallback': {
        'ar_sa': 'الاتصال المباشر P2P ضعيف، جارٍ تجربة قناة TCP بديلة...',
        'de_de': 'P2P-Direktverbindung stockt, versuche TCP-Ersatzkanal...',
        'en_us': 'P2P direct connection struggling, trying TCP fallback...',
        'es_es': 'Conexion directa P2P dificultosa, probando canal alternativo TCP...',
        'fr_fr': "Connexion directe P2P difficile, essai d'un canal de secours TCP...",
        'ja_jp': 'P2P直通が不安定なためTCP代替経路を試行中…',
        'ko_kr': 'P2P 직접 연결 불안정, TCP 대체 경로 시도 중…',
        'lzh': 'P2P直連不暢，正試備道…',
        'pt_br': 'Conexao direta P2P instavel, tentando canal alternativo TCP...',
        'ru_ru': 'Прямое P2P-подключение нестабильно, пробуем запасной TCP-канал...',
        'zh_cn': 'P2P 直连不畅，正在尝试 TCP 备用通道…',
        'zh_hk': 'P2P 直連不暢，正在嘗試 TCP 備用通道…',
        'zh_tw': 'P2P 直連不暢，正在嘗試 TCP 備用通道…',
    },
    'voxlink.website': {
        'ar_sa': 'روابط ذات صلة',
        'de_de': 'Weiterführende Links',
        'en_us': 'Related Links',
        'es_es': 'Enlaces relacionados',
        'fr_fr': 'Liens utiles',
        'ja_jp': '関連リンク',
        'ko_kr': '관련 링크',
        'lzh': '相關連結',
        'pt_br': 'Links relacionados',
        'ru_ru': 'Полезные ссылки',
        'zh_cn': '相关链接',
        'zh_hk': '相關連結',
        'zh_tw': '相關連結',
    },
}

LINKS_KEYS = {
    'voxlink.links.title': {
        'ar_sa': 'روابط ذات صلة', 'de_de': 'Weiterführende Links', 'en_us': 'Related Links',
        'es_es': 'Enlaces relacionados', 'fr_fr': 'Liens utiles', 'ja_jp': '関連リンク',
        'ko_kr': '관련 링크', 'lzh': '相關連結', 'pt_br': 'Links relacionados',
        'ru_ru': 'Полезные ссылки', 'zh_cn': '相关链接', 'zh_hk': '相關連結', 'zh_tw': '相關連結'},
    'voxlink.links.site': {
        'ar_sa': 'الموقع الرسمي', 'de_de': 'Offizielle Website', 'en_us': 'Official Website',
        'es_es': 'Sitio oficial', 'fr_fr': 'Site officiel', 'ja_jp': '公式サイト',
        'ko_kr': '공식 웹사이트', 'lzh': '官網', 'pt_br': 'Site oficial',
        'ru_ru': 'Официальный сайт', 'zh_cn': '官网', 'zh_hk': '官方網站', 'zh_tw': '官方網站'},
    'voxlink.links.mcmod': {
        'ar_sa': 'MCMod Wiki', 'de_de': 'MCMod Wiki', 'en_us': 'MCMod Wiki',
        'es_es': 'MCMod Wiki', 'fr_fr': 'MCMod Wiki', 'ja_jp': 'MC百科',
        'ko_kr': 'MC百科', 'lzh': 'MC百科', 'pt_br': 'MCMod Wiki',
        'ru_ru': 'MCMod Wiki', 'zh_cn': 'MC百科', 'zh_hk': 'MC百科', 'zh_tw': 'MC百科'},
    'voxlink.links.github': {k: 'GitHub' for k in LANGS},
    'voxlink.links.gitee': {k: 'Gitee' for k in LANGS},
    'voxlink.links.modrinth': {k: 'Modrinth' for k in LANGS},
    'voxlink.links.curseforge': {k: 'CurseForge' for k in LANGS},
    'voxlink.links.discord': {k: 'Discord' for k in LANGS},
    'voxlink.links.qq': {
        'ar_sa': 'مجموعة QQ', 'de_de': 'QQ-Gruppe', 'en_us': 'QQ Group',
        'es_es': 'Grupo QQ', 'fr_fr': 'Groupe QQ', 'ja_jp': 'QQグループ',
        'ko_kr': 'QQ 그룹', 'lzh': 'QQ群', 'pt_br': 'Grupo QQ',
        'ru_ru': 'Группа QQ', 'zh_cn': 'QQ群', 'zh_hk': 'QQ群', 'zh_tw': 'QQ群'},
}


def patch_lang_text(text, lang):
    for key, trans in REPL.items():
        pat = re.compile('("%s": )"[^"]*"' % re.escape(key))
        assert len(pat.findall(text)) == 1, key + ' @' + lang + ' != 1'
        text = pat.sub(lambda m: m.group(1) + json.dumps(trans[lang], ensure_ascii=False), text)
    lines = ['    "voxlink.links.%s": %s,' % (k.rsplit('.', 1)[1],
                                             json.dumps(v[lang], ensure_ascii=False))
             for k, v in LINKS_KEYS.items()]
    m = re.search(r'^(\s*"voxlink\.website": "[^"]*",\s*)$', text, re.M)
    assert m, 'website line @' + lang
    text = text[:m.end()] + '\n' + '\n'.join(lines) + text[m.end():]
    json.loads(text)
    return text


def do_lang():
    canon_dir = CANON + '/src/main/resources/assets/voxlink/lang'
    originals = {}
    for lang in LANGS:
        rel = canon_dir + '/' + lang + '.json'
        data = read(rel)
        h = hashlib.md5(data.encode('utf-8')).hexdigest()
        originals[lang] = h
        for t in TEMPLATES:
            other = read(t + '/src/main/resources/assets/voxlink/lang/' + lang + '.json')
            oh = hashlib.md5(other.encode('utf-8')).hexdigest()
            assert oh == h, 'lang %s differs in %s' % (lang, t)
    for lang in LANGS:
        rel = canon_dir + '/' + lang + '.json'
        text = read(rel)
        assert '"voxlink.links.title"' not in text, 'links keys already in ' + lang
        write(rel, patch_lang_text(text, lang))
    for lang in LANGS:
        src = read(canon_dir + '/' + lang + '.json')
        json.loads(src)
        for t in TEMPLATES:
            dst = t + '/src/main/resources/assets/voxlink/lang/' + lang + '.json'
            write(dst, src)
    print('C) lang: %d langs x %d templates' % (len(LANGS), len(TEMPLATES)))


# ---------------------------------------------------------------- D) 版本
def do_version():
    for t in TEMPLATES:
        rel = t + '/gradle.properties'
        text = read(rel)
        assert count(text, 'mod_version=1.1.5-') == 1, rel
        text = text.replace('mod_version=1.1.5-', 'mod_version=1.1.6-')
        write(rel, text)
    print('D) version 1.1.5 -> 1.1.6: %d templates' % len(TEMPLATES))


def main():
    steps = [('A', do_port_fix), ('B', do_links_page), ('C', do_lang), ('D', do_version)]
    only = sys.argv[1:] or [s for s, _ in steps]
    for name, fn in steps:
        if name in only:
            fn()
    print('ALL DONE')


if __name__ == '__main__':
    main()
