# -*- coding: utf-8 -*-
# ModSync v2 追加：房主"按需应答"受 hostModSyncPublish 开关控制；重拷 ManifestService
import glob, os, shutil

REF = os.path.join('fabric', '1.20_1.20.1', 'src', 'main', 'java', 'icu', 'wuhui', 'voxlink')
p = os.path.join(REF, 'modsync', 'ModSyncManifestService.java')
src = open(p, encoding='utf-8', newline='').read()
old = '''   public static void onModsRequest(JsonObject data) {
      String requestId = data.has("requestId") && data.get("requestId").isJsonPrimitive()'''
new = '''   public static void onModsRequest(JsonObject data) {
      if (!VoxLinkMod.getConfig().isHostModSyncPublish()) {
         ModSyncLog.info("host mod-sync disabled, ignore mods_request");
         return;
      }
      String requestId = data.has("requestId") && data.get("requestId").isJsonPrimitive()'''
if src.count(old) == 1:
    src = src.replace(old, new)
    open(p, 'w', encoding='utf-8', newline='').write(src)
    print('OK ref')
elif 'ignore mods_request' in src:
    print('SKIP ref (already gated)')
else:
    raise SystemExit('anchor missing in reference')

ref_eol = '\r\n' if '\r\n' in src else '\n'
ref_new = src if ref_eol == '\n' else src  # 参考文件本身即最新

count = 0
for tpl in sorted(glob.glob(os.path.join('*', '*', 'src', 'main', 'java', 'icu', 'wuhui', 'voxlink', 'modsync', 'ModSyncManifestService.java'))):
    norm = tpl.replace(os.sep, '/')
    if norm.startswith('fabric/1.20_1.20.1/'):
        continue
    old_raw = open(tpl, encoding='utf-8', newline='').read()
    eol = '\r\n' if '\r\n' in old_raw else '\n'
    content = ref_new if eol == '\n' else ref_new.replace('\n', '\r\n')
    open(tpl, 'w', encoding='utf-8', newline='').write(content)
    count += 1
    print('OK', norm.split('/src/')[0])
print('recopied:', count)
