# -*- coding: utf-8 -*-
# 2026-10-01: web 大厅 13 语言包补 seo.title/seo.desc/links.* (SEO 标题+更多链接弹窗)
import json, collections, io, os

BASE = r'D:\桌面\voxlink\server-go\web\lang'

T = {
 'zh_cn': {
  'seo.title': 'VoxLink - 我的世界联机模组 | 免公网IP 免端口映射 联机大厅',
  'seo.desc': 'VoxLink 是一款 Minecraft P2P 联机模组：无需公网IP、免端口映射，建房获得6位房间码发给好友即可联机，支持 Fabric/Forge/NeoForge 1.20+ 与 ViaVersion。',
  'links.more': '更多链接', 'links.title': '相关链接', 'links.mcmod': 'MC百科', 'links.site': '官方网站',
 },
 'zh_tw': {
  'seo.title': 'VoxLink - 我的世界連線模組 | 免公網IP 免連接埠映射 連線大廳',
  'seo.desc': 'VoxLink 是一款 Minecraft P2P 連線模組：無需公網IP、免連接埠映射，建房取得6位房間碼傳給好友即可連線，支援 Fabric/Forge/NeoForge 1.20+ 與 ViaVersion。',
  'links.more': '更多連結', 'links.title': '相關連結', 'links.mcmod': 'MC百科', 'links.site': '官方網站',
 },
 'zh_hk': {
  'seo.title': 'VoxLink - 我的世界連線模組 | 免公網IP 免端口轉發 連線大廳',
  'seo.desc': 'VoxLink 是一款 Minecraft P2P 連線模組：無需公網IP、免端口轉發，建房取得6位房間碼傳給好友即可連線，支援 Fabric/Forge/NeoForge 1.20+ 與 ViaVersion。',
  'links.more': '更多連結', 'links.title': '相關連結', 'links.mcmod': 'MC百科', 'links.site': '官方網站',
 },
 'lzh': {
  'seo.title': 'VoxLink - Minecraft 聯機之模 | 免公網IP 免端口映射 諸室之堂',
  'seo.desc': 'VoxLink 者，Minecraft P2P 聯機之模也。毋需公網IP，毋需端口映射，得六位房間之碼以授友，即可同遊。兼備 Fabric/Forge/NeoForge 1.20+ 與 ViaVersion。',
  'links.more': '諸站之鏈', 'links.title': '諸站之鏈', 'links.mcmod': 'MC百科', 'links.site': '官網',
 },
 'en_us': {
  'seo.title': 'VoxLink - Minecraft Multiplayer Mod | No Port Forwarding, Play with Friends',
  'seo.desc': 'VoxLink is a P2P Minecraft multiplayer mod: no port forwarding, no public IP needed. Create a room, share the 6-digit code, and play. Supports Fabric/Forge/NeoForge 1.20+ and ViaVersion.',
  'links.more': 'More Links', 'links.title': 'Links', 'links.mcmod': 'MC Wiki (mcmod.cn)', 'links.site': 'Official Website',
 },
 'ja_jp': {
  'seo.title': 'VoxLink - マインクラフト マルチプレイMod | ポート開放不要で仲間と遊ぶ',
  'seo.desc': 'VoxLinkはMinecraftのP2PマルチプレイMod。ポート開放もグローバルIPも不要。部屋を作り6桁コードを共有するだけで仲間と遊べます。Fabric/Forge/NeoForge 1.20+とViaVersionに対応。',
  'links.more': 'その他のリンク', 'links.title': '関連リンク', 'links.mcmod': 'MC Wiki (mcmod.cn)', 'links.site': '公式サイト',
 },
 'ko_kr': {
  'seo.title': 'VoxLink - 마인크래프트 멀티플레이 모드 | 포트포워딩 없이 친구와 플레이',
  'seo.desc': 'VoxLink는 Minecraft P2P 멀티플레이 모드입니다. 포트포워딩도 공인 IP도 없이, 방을 만들고 6자리 코드만 공유하면 됩니다. Fabric/Forge/NeoForge 1.20+ 및 ViaVersion 지원.',
  'links.more': '더 보기', 'links.title': '관련 링크', 'links.mcmod': 'MC 위키 (mcmod.cn)', 'links.site': '공식 웹사이트',
 },
 'fr_fr': {
  'seo.title': 'VoxLink - Mod multijoueur Minecraft | Sans redirection de port',
  'seo.desc': "VoxLink est un mod multijoueur P2P pour Minecraft : ni redirection de port ni IP publique. Créez un salon, partagez le code à 6 chiffres et jouez. Compatible Fabric/Forge/NeoForge 1.20+ et ViaVersion.",
  'links.more': 'Plus de liens', 'links.title': 'Liens', 'links.mcmod': 'MC Wiki (mcmod.cn)', 'links.site': 'Site officiel',
 },
 'de_de': {
  'seo.title': 'VoxLink - Minecraft Multiplayer Mod | Ohne Portfreigabe spielen',
  'seo.desc': 'VoxLink ist ein P2P-Multiplayer-Mod für Minecraft: keine Portfreigabe, keine öffentliche IP. Raum erstellen, 6-stelligen Code teilen und losspielen. Unterstützt Fabric/Forge/NeoForge 1.20+ und ViaVersion.',
  'links.more': 'Weitere Links', 'links.title': 'Links', 'links.mcmod': 'MC Wiki (mcmod.cn)', 'links.site': 'Offizielle Website',
 },
 'es_es': {
  'seo.title': 'VoxLink - Mod multijugador de Minecraft | Sin abrir puertos',
  'seo.desc': 'VoxLink es un mod multijugador P2P para Minecraft: sin abrir puertos ni IP pública. Crea una sala, comparte el código de 6 dígitos y juega. Compatible con Fabric/Forge/NeoForge 1.20+ y ViaVersion.',
  'links.more': 'Más enlaces', 'links.title': 'Enlaces', 'links.mcmod': 'MC Wiki (mcmod.cn)', 'links.site': 'Sitio web oficial',
 },
 'pt_br': {
  'seo.title': 'VoxLink - Mod multijogador de Minecraft | Sem redirecionamento de porta',
  'seo.desc': 'VoxLink é um mod multijogador P2P para Minecraft: sem redirecionamento de porta nem IP público. Crie uma sala, compartilhe o código de 6 dígitos e jogue. Suporta Fabric/Forge/NeoForge 1.20+ e ViaVersion.',
  'links.more': 'Mais links', 'links.title': 'Links', 'links.mcmod': 'MC Wiki (mcmod.cn)', 'links.site': 'Site oficial',
 },
 'ru_ru': {
  'seo.title': 'VoxLink - Мод для мультиплеера Minecraft | Без проброса портов',
  'seo.desc': 'VoxLink — P2P-мод для мультиплеера Minecraft: без проброса портов и белого IP. Создайте комнату, отправьте 6-значный код и играйте. Поддержка Fabric/Forge/NeoForge 1.20+ и ViaVersion.',
  'links.more': 'Ещё ссылки', 'links.title': 'Ссылки', 'links.mcmod': 'MC Wiki (mcmod.cn)', 'links.site': 'Официальный сайт',
 },
 'ar_sa': {
  'seo.title': 'VoxLink - مود ماينكرافت للعب الجماعي | بدون فتح المنافذ',
  'seo.desc': 'VoxLink مود P2P للعب الجماعي في ماينكرافت: بدون فتح منافذ أو عنوان IP عام. أنشئ غرفة وشارك رمزًا من 6 أرقام والعب. يدعم Fabric/Forge/NeoForge 1.20+ وViaVersion.',
  'links.more': 'المزيد من الروابط', 'links.title': 'الروابط', 'links.mcmod': 'MC Wiki (mcmod.cn)', 'links.site': 'الموقع الرسمي',
 },
}

ORDER = ['seo.title', 'seo.desc', 'links.more', 'links.title', 'links.mcmod', 'links.site']
ANCHOR = 'nav.discord'

for lang, add in T.items():
    path = os.path.join(BASE, lang + '.json')
    with io.open(path, encoding='utf-8') as f:
        d = json.load(f, object_pairs_hook=collections.OrderedDict)
    if 'seo.title' in d:
        print('SKIP(已存在)', lang); continue
    out = collections.OrderedDict()
    inserted = False
    for k, v in d.items():
        out[k] = v
        if k == ANCHOR:
            for nk in ORDER:
                out[nk] = add[nk]
            inserted = True
    if not inserted:
        for nk in ORDER:
            out[nk] = add[nk]
    with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
        f.write('\n')
    print('OK', lang)
