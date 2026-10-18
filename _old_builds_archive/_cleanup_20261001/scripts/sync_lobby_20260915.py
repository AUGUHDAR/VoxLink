#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""2026-09-15 房间列表事件化轮:
A) 模组: 新增 LobbyPush 总线 + WS lobby 帧转发, 浏览器屏订阅事件刷新(删5s轮询+删刷新按钮)
B) 网页: 删刷新按钮 + renderRooms 改 keyed 就地更新(消闪屏) + 补 roomData.modVersion 漏字段
服务器端零改动(WSHubBroadcastLobby 本就广播全部会话)。
"""
import os
import re

ROOT = os.path.dirname(os.path.abspath(__file__))
TEMPLATES = []
for loader in ('fabric', 'forge', 'neoforge'):
    base = os.path.join(ROOT, loader)
    for d in sorted(os.listdir(base)):
        if os.path.isdir(os.path.join(base, d, 'src')):
            TEMPLATES.append(os.path.join(loader, d))

ERRORS = []


def p(rel):
    return os.path.join(ROOT, rel.replace('/', os.sep))


def read(rel):
    with open(p(rel), 'rb') as f:
        return f.read().decode('utf-8')


def write(rel, text):
    with open(p(rel), 'wb') as f:
        f.write(text.encode('utf-8'))


def insert_after_line(text, anchor, newline):
    """锚点行后插入新行, 沿用锚点行尾(字节级已验证形态)。"""
    idx = text.find(anchor)
    assert idx != -1, 'anchor missing: ' + anchor[:60]
    le = text.find('\n', idx)
    assert le != -1
    term = '\r\n' if text[le - 1:le] == '\r' else '\n'
    line_start = text.rfind('\n', 0, idx) + 1
    indent = text[line_start:idx]
    ins = newline.replace('\n', term)
    return text[:le] + '\n' + indent + ins + text[le:]


def insert_block_before(text, anchor, block):
    """在锚点行(含缩进)之前插入整块文本。"""
    idx = text.find(anchor)
    assert idx != -1, 'anchor missing: ' + anchor[:60]
    ls = text.rfind('\n', 0, idx) + 1
    indent = text[ls:idx]
    term = '\r\n' if (ls >= 1 and text[ls - 1] == '\r') else '\n'
    block = block.replace('\n', term)
    return text[:ls] + block + text[ls:]


def remove_line_containing(text, needle):
    idx = text.find(needle)
    assert idx != -1, 'needle missing: ' + needle[:60]
    ls = text.rfind('\n', 0, idx) + 1
    le = text.find('\n', idx)
    le = le + 1 if le != -1 else len(text)
    return text[:ls] + text[le:]


# ---------------------------------------------------------------- A) 模组
LOBBY_PUSH_JAVA = '''package icu.wuhui.voxlink.ui;

/**
 * 服务器 lobby 变更事件总线: WS 收到 push=lobby 时 fire,
 * 打开中的房间浏览器监听并做事件驱动刷新(无轮询)。
 */
public final class LobbyPush {
   private static volatile Runnable listener;

   private LobbyPush() {
   }

   public static void set(Runnable r) {
      listener = r;
   }

   public static void fire() {
      Runnable r = listener;
      if (r != null) {
         r.run();
      }
   }
}
'''

WS_ANCHOR = 'if (id == 0L && obj.has("push")) {'
WS_INSERT = ('if ("lobby".equals(obj.get("push").getAsString())) {\n'
             '               icu.wuhui.voxlink.ui.LobbyPush.fire();\n'
             '            }')

BROWSER_ANCHORS = {
    'btn': 'Component.translatable("voxlink.refresh"), b -> this.fetchRooms()',
    'field': 'private long lastAutoRefreshMs = System.currentTimeMillis();',
    'fieldc': '上次自动刷新时刻（5s 节流）',
    'init': 'protected void init() {',
    'removed': 'this.removed = true;',
    'fetch': 'protected void fetchRooms() {',
    'poll1': 'long now = System.currentTimeMillis();',
    'poll2': 'if (now - this.lastAutoRefreshMs >= 5000L && !this.loadingMore && this.currentPage <= 1) {',
}

METHODS = '''   private void onLobbyPush() {
      Minecraft.getInstance().execute(this::refreshInPlace);
   }

   // 事件驱动刷新: 响应到达后原子替换, 不清屏不动滚动
   protected void refreshInPlace() {
      if (this.removed || this.loadingMore || this.currentPage > 1) {
         return;
      }

      this.loadingMore = true;
      String category = "all".equals(this.selectedCategory) ? null : this.selectedCategory;
      VoxLinkMod.getSignalingClient()
         .listRooms(1, 20, category, this.selectedLoader)
         .thenAccept(
            apiResponse -> Minecraft.getInstance().execute(() -> {
               this.loadingMore = false;
               if (this.removed || apiResponse == null || apiResponse.data == null) {
                  return;
               }

               try {
                  JsonObject data = apiResponse.data;
                  this.totalRooms = data.has("total") ? data.get("total").getAsInt() : this.totalRooms;
                  List<RoomBrowserScreenBase.RoomEntry> fresh = new ArrayList<>();
                  if (data.has("rooms") && data.get("rooms").isJsonArray()) {
                     for (JsonElement e : data.getAsJsonArray("rooms")) {
                        JsonObject r = e.getAsJsonObject();
                        String code = r.has("code") ? r.get("code").getAsString() : "";
                        String roomClientType = r.has("clientType") ? r.get("clientType").getAsString() : "mod";
                        if (!code.isEmpty() && "mod".equals(roomClientType)) {
                           fresh.add(new RoomBrowserScreenBase.RoomEntry(code,
                              r.has("name") ? r.get("name").getAsString() : Component.translatable("voxlink.unknown").getString(),
                              r.has("category") ? r.get("category").getAsString() : "other",
                              r.has("loader") ? r.get("loader").getAsString() : "unknown",
                              r.has("currentPlayers") ? r.get("currentPlayers").getAsInt() : 0,
                              r.has("maxPlayers") ? r.get("maxPlayers").getAsInt() : 20,
                              r.has("hasPassword") && r.get("hasPassword").getAsBoolean(),
                              r.has("natType") ? r.get("natType").getAsString() : "unknown",
                              r.has("protocolVersion") ? r.get("protocolVersion").getAsInt() : 0,
                              r.has("gameVersion") ? r.get("gameVersion").getAsString() : ""));
                        }
                     }
                  }

                  this.allRooms.clear();
                  this.allRooms.addAll(fresh);
                  this.currentPage = 1;
                  this.fetchP2PDetails();
               } catch (Exception ex) {
                  this.statusMsg = Component.translatable("voxlink.browser.load_rooms_failed").getString();
                  this.statusColor = VoxLinkColors.ERROR;
               }
            })
         )
         .exceptionally(e -> {
            Minecraft.getInstance().execute(() -> {
               this.loadingMore = false;
            });
            return null;
         });
   }

'''


def do_mod():
    for t in TEMPLATES:
        ui = t + '/src/main/java/icu/wuhui/voxlink/ui'
        net = t + '/src/main/java/icu/wuhui/voxlink/network'
        lprel = ui + '/LobbyPush.java'
        if not os.path.exists(p(lprel)):
            write(lprel, LOBBY_PUSH_JAVA)

        wsrel = net + '/SignalingWsTransport.java'
        ws = read(wsrel)
        if 'LobbyPush.fire()' not in ws:
            assert ws.count(WS_ANCHOR) == 1, wsrel + ' ws anchor != 1'
            ws = insert_after_line(ws, WS_ANCHOR, WS_INSERT)
            assert ws.count('{') == ws.count('}'), wsrel + ' braces'
            write(wsrel, ws)

        brel = ui + '/RoomBrowserScreenBase.java'
        b = read(brel)
        if 'refreshInPlace' in b:
            continue
        a = BROWSER_ANCHORS
        for k in ('btn', 'field', 'init', 'removed', 'fetch', 'poll2'):
            assert b.count(a[k]) == 1, brel + ' anchor ' + k + ' != 1'
        # 1) 删刷新按钮行
        b = remove_line_containing(b, a['btn'])
        # 2) 删轮询块(now 行 + if 3 行)
        m = re.search(r'[ \t]*long now = System\.currentTimeMillis\(\);\r?\n'
                      r'[ \t]*if \(now - this\.lastAutoRefreshMs >= 5000L && !this\.loadingMore && this\.currentPage <= 1\) \{\r?\n'
                      r'[ \t]*this\.lastAutoRefreshMs = now;\r?\n'
                      r'[ \t]*this\.fetchRooms\(\);\r?\n'
                      r'[ \t]*\}\r?\n', b)
        assert m, brel + ' poll block not found'
        b = b[:m.start()] + b[m.end():]
        # 3) 删字段与注释(注释文案跨模板有变体, 按"自动刷新"特征匹配)
        m = re.search(r'[ \t]*/\*\*[^*]*自动刷新[^*]*\*/\r?\n'
                      r'[ \t]*private long lastAutoRefreshMs = System\.currentTimeMillis\(\);\r?\n', b)
        assert m, brel + ' field block not found'
        b = b[:m.start()] + b[m.end():]
        # 4) init 注册监听 + 预热 WS
        b = insert_after_line(b, a['init'],
            '      LobbyPush.set(this::onLobbyPush);\n'
            '      VoxLinkMod.getSignalingClient().preconnectWebSocket();')
        # 5) removed 解注册
        b = insert_after_line(b, a['removed'], 'LobbyPush.set(null);')
        # 6) 新方法
        b = insert_block_before(b, a['fetch'], METHODS)
        assert b.count('{') == b.count('}'), brel + ' braces'
        write(brel, b)
    print('A) mod: %d templates' % len(TEMPLATES))


# ---------------------------------------------------------------- B) 网页
RENDER_OLD_START = 'function renderRooms(rooms){'
RENDER_NEW = '''function renderRooms(rooms){
    const grid=document.getElementById('roomGrid');
    if(!grid)return;
    if(rooms.length===0){
        grid.innerHTML='<p class="muted" style="text-align:center;padding:40px 0;grid-column:1/-1">{{T "js.no_rooms"}}</p>';
        return;
    }
    const existing={};
    Array.from(grid.children).forEach(c=>{
        if(c.dataset&&c.dataset.code){existing[c.dataset.code]=c}else{c.remove()}
    });
    let anchor=grid.firstChild;
    rooms.forEach(r=>{
        const displayName=r.nameApproved?r.name:'{{T "room.name_not_approved"}}';
        const sig=[r.players,r.maxPlayers,r.modVersion,r.region,r.isp,r.clientType,r.clientTag,r.gameVersion,r.loader,r.category,displayName].join('|');
        let card=existing[r.code]||null;
        const isnew=!card;
        if(isnew){
            card=document.createElement('div');
            card.className='room-card';
            card.dataset.code=r.code;
            card.onclick=()=>selectRoom(r);
        }
        if(isnew||card.dataset.sig!==sig){
            card.dataset.sig=sig;
            card.dataset.cat=r.category;
            card.dataset.name=displayName;
            card.dataset.players=r.players;
            card.innerHTML=
                ((r.region||r.isp)?'<div class="room-card-region" title="{{T "browser.region"}}">'+(r.isp?'<span class="room-card-isp">'+esc(r.isp)+'</span> ':'')+esc(r.region||'')+'</div>':'')+
                (r.clientType==='app'?'<div class="room-card-app-badge" title="{{T "room.app_tag"}}">{{T "room.app_tag"}}</div>':'')+
                (r.clientTag==='kamucl'?'<div class="room-card-kamucl-badge" title="KAMUCL 启动器创建">KAMUCL 启动器创建</div>':'')+
                '<div class="room-card-cat">'+esc(catLabel(r.category))+'</div>'+
                '<div class="room-card-name">'+esc(displayName)+'</div>'+
                '<div class="room-card-code">'+esc(r.code)+'</div>'+
                '<div class="room-card-info">'+
                    '<span><i class="fa fa-users"></i> '+esc(String(r.players))+'/'+esc(String(r.maxPlayers))+'</span>'+
                    (r.gameVersion?'<span class="room-card-ver" title="{{T "browser.mc_version"}}"><i class="fa fa-cube"></i> '+esc(r.gameVersion)+'</span>':'')+
                    (r.loader&&r.loader!=='unknown'?'<span class="room-card-ver" title="Loader"><i class="fa fa-cube"></i> '+esc(r.loader)+'</span>':'')+
                    (r.modVersion?'<span class="room-card-ver" title="VoxLink"><i class="fa fa-headphones"></i> '+esc(r.modVersion)+'</span>':'')+
                '</div>';
        }
        delete existing[r.code];
        if(anchor!==card){grid.insertBefore(card,anchor)}
        anchor=card.nextSibling;
    });
    Object.keys(existing).forEach(k=>{const c=existing[k];if(c)c.remove()});
    renderLoadMore(grid);
}

'''

BTN_HTML = '<button class="btn btn-ghost" onclick="refreshRooms()" title="{{T "refresh"}}"><i class="fa fa-refresh"></i></button>'
MODVER_OLD = "clientTag:r.clientTag||''"
LIVE_OLD = '// 玩家手动刷新按钮保留，自动刷新只是补充。'
LIVE_NEW = '// 事件驱动：仅在收到服务器推送时刷新，无手动按钮。'


def do_web():
    rel = 'server-go/web/index.html'
    text = read(rel)
    assert text.count(BTN_HTML) == 1, 'web button != 1'
    text = remove_line_containing(text, BTN_HTML)
    s = text.find(RENDER_OLD_START)
    e = text.find('function renderLoadMore', s)
    assert s != -1 and e != -1, 'renderRooms span not found'
    text = text[:s] + RENDER_NEW + text[e:]
    assert text.count(MODVER_OLD) == 1, 'roomData mapping != 1'
    text = text.replace(MODVER_OLD, MODVER_OLD + ",modVersion:r.modVersion||''")
    assert text.count(BTN_HTML) == 0 and 'renderLoadMore(grid);\n}' in text
    write(rel, text)

    live = 'server-go/web/assets/lobby-live.js'
    t2 = read(live)
    assert t2.count(LIVE_OLD) == 1, 'live comment != 1'
    write(live, t2.replace(LIVE_OLD, LIVE_NEW))
    print('B) web: index.html + lobby-live.js')


def main():
    do_mod()
    do_web()
    print('ALL DONE')


if __name__ == '__main__':
    main()
