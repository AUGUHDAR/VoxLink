# -*- coding: utf-8 -*-
# 2026-09-19 ModSync v2 传播：bug 修复(必装清单恒为0) + 两档清单 + 按需拉取 + 本地缓存
# 策略：ManifestService 整拷（已无 MC API 依赖）；其余锚点编辑（EOL 自适应）；
#       ModSyncEnv 按各模板自身 getModsDir 的 gameDir 表达式生成 getCacheFile；
#       lang 15 文件整拷。任何锚点失配只记录不中断，结尾汇总人工处理。
import os, glob, json, re, shutil

BASE = os.path.dirname(os.path.abspath(__file__))
NET = "src/main/java/icu/wuhui/voxlink"
REF = os.path.join(BASE, "fabric", "1.20_1.20.1", NET)

def read(p):
    return open(p, encoding='utf-8', newline='').read()

def write(p, s):
    open(p, 'w', encoding='utf-8', newline='').write(s)

def edit(src, pairs, path):
    eol = '\r\n' if '\r\n' in src else '\n'
    for old, new in pairs:
        o = old.replace('\n', eol); n = new.replace('\n', eol)
        if src.count(o) != 1:
            raise AssertionError(f"{path}: anchor x{src.count(o)}: {old[:70]!r}")
        src = src.replace(o, n)
    return src

def load(pairs_tpl):
    return pairs_tpl

# ---- 锚点定义 ----
HASH_ANCHORS = [(
'''   public static String sha512(Path file) throws IOException {
      return hash(file, "SHA-512");
   }''',
'''   public static String sha512(Path file) throws IOException {
      return hash(file, "SHA-512");
   }

   /** 字节数组的 sha1（modsync 本地缓存状态键用）。 */
   public static String sha1OfBytes(byte[] data) {
      try {
         MessageDigest digest = MessageDigest.getInstance("SHA-1");
         StringBuilder sb = new StringBuilder(digest.getDigestLength() * 2);
         for (byte b : digest.digest(data)) {
            sb.append(Character.forDigit(b >> 4 & 15, 16)).append(Character.forDigit(b & 15, 16));
         }

         return sb.toString();
      } catch (Exception e) {
         return String.valueOf(data == null ? 0 : data.length);
      }
   }''')]

GUEST_ANCHORS = [(
'''   /**
    * 加入流程入口门控（在 join_room / 打洞之前调用）。
    * onProceed/onCancel 都会被调度回主线程执行。
    */
   public static void gate(String roomCode, Runnable onProceed, Runnable onCancel) {''',
'''   /**
    * 加入流程入口门控（在 join_room / 打洞之前调用）。
    * scope=required（仅必装）| all（房主全部可识别模组）。
    * onProceed/onCancel 都会被调度回主线程执行。
    */
   public static void gate(String roomCode, String scope, Runnable onProceed, Runnable onCancel) {'''),
('''      EXECUTOR.execute(() -> {
         JsonObject manifest = fetchManifestWithRetry(roomCode);''',
'''      final String fetchScope = ModSyncManifestService.SCOPE_ALL.equals(scope)
         ? ModSyncManifestService.SCOPE_ALL : ModSyncManifestService.SCOPE_REQUIRED;
      EXECUTOR.execute(() -> {
         JsonObject manifest = fetchManifestWithRetry(roomCode, fetchScope);'''),
('''   /** 返回 null 表示"无需处理"（不支持/拉取失败/未就绪超时/空清单）。 */
   private static JsonObject fetchManifestWithRetry(String roomCode) {''',
'''   /** 返回 null 表示"无需处理"（不支持/拉取失败/未就绪超时/空清单）。 */
   private static JsonObject fetchManifestWithRetry(String roomCode, String scope) {'''),
('''            var resp = VoxLinkMod.getSignalingClient()
               .getRoomMods(roomCode)
               .get(5L, TimeUnit.SECONDS);''',
'''            var resp = VoxLinkMod.getSignalingClient()
               .requestRoomMods(roomCode, scope)
               .get(8L, TimeUnit.SECONDS);''')]

SIGNAL_ANCHORS = [(
'''      Map.entry("publish_mod_manifest", "/room/mods/publish"),
      Map.entry("get_room_mods", "/room/mods")''',
'''      Map.entry("publish_mod_manifest", "/room/mods/publish"),
      Map.entry("get_room_mods", "/room/mods"),
      Map.entry("request_room_mods", "/room/mods/request"),
      Map.entry("answer_room_mods", "/room/mods/answer")''')]

SIGNAL_ANCHORS2 = [(
'''   public CompletableFuture<SignalingClient.ApiResponse> getRoomMods(String code) {
      JsonObject body = new JsonObject();
      body.addProperty("code", code != null ? code.toUpperCase() : "");
      body.addProperty("action", "get_room_mods");
      return this.postNoRetry(this.buildPath("get_room_mods"), body);
   }''',
'''   public CompletableFuture<SignalingClient.ApiResponse> getRoomMods(String code) {
      JsonObject body = new JsonObject();
      body.addProperty("code", code != null ? code.toUpperCase() : "");
      body.addProperty("action", "get_room_mods");
      return this.postNoRetry(this.buildPath("get_room_mods"), body);
   }

   /** ModSync v2：房客按需请求清单（scope=required|all）；服务器缓存未命中时经信号向房主取。 */
   public CompletableFuture<SignalingClient.ApiResponse> requestRoomMods(String code, String scope) {
      JsonObject body = new JsonObject();
      body.addProperty("code", code != null ? code.toUpperCase() : "");
      body.addProperty("scope", scope != null && !scope.isEmpty() ? scope : "required");
      body.addProperty("action", "request_room_mods");
      return this.postNoRetry(this.buildPath("request_room_mods"), body);
   }

   /** ModSync v2：房主应答按需请求（requestId 关联），服务器落缓存并唤醒房客长轮询。 */
   public CompletableFuture<SignalingClient.ApiResponse> answerRoomMods(String code, String token, String requestId, String scope, JsonObject manifest) {
      JsonObject body = new JsonObject();
      body.addProperty("code", code != null ? code.toUpperCase() : "");
      body.addProperty("token", token != null ? token : "");
      body.addProperty("requestId", requestId != null ? requestId : "");
      body.addProperty("scope", scope != null && !scope.isEmpty() ? scope : "required");
      body.add("manifest", manifest != null ? manifest : new JsonObject());
      body.addProperty("action", "answer_room_mods");
      return this.postNoRetry(this.buildPath("answer_room_mods"), body);
   }''')]

ROOM_ANCHORS = [(
'''            case "join_request":
               this.connectionManager.handleJoinRequest(from, data);
               break;''',
'''            case "join_request":
               this.connectionManager.handleJoinRequest(from, data);
               break;
            case "mods_request":
               icu.wuhui.voxlink.modsync.ModSyncManifestService.onModsRequest(data);
               break;''')]

SCREEN_ANCHORS = [(
'''         } else if (gatePending) {
            this.addRenderableWidget(
               Button.builder(Component.translatable("voxlink.modsync.skip_check"), button -> this.onSkipCheckClicked())
                  .bounds(centerX - 100, btnY, 200, 20)
                  .build()
            );
            this.addRenderableWidget(
               Button.builder(Component.translatable("voxlink.cancel"), button -> this.cancelJoin()).bounds(centerX - 100, btnY + 24, 200, 20).build()
            );
         } else {''',
'''         } else if (gatePending) {
            // ModSync v2: 两档范围选择（必装 / 房主全部可识别），跳过/取消保留
            this.addRenderableWidget(
               Button.builder(Component.translatable("voxlink.modsync.scope_required"), button -> this.onScopePicked("required"))
                  .bounds(centerX - 100, btnY - 26, 200, 20)
                  .build()
            );
            this.addRenderableWidget(
               Button.builder(Component.translatable("voxlink.modsync.scope_all"), button -> this.onScopePicked("all"))
                  .bounds(centerX - 100, btnY - 2, 200, 20)
                  .build()
            );
            this.addRenderableWidget(
               Button.builder(Component.translatable("voxlink.modsync.skip_check"), button -> this.onSkipCheckClicked())
                  .bounds(centerX - 100, btnY + 22, 200, 20)
                  .build()
            );
            this.addRenderableWidget(
               Button.builder(Component.translatable("voxlink.cancel"), button -> this.cancelJoin()).bounds(centerX - 100, btnY + 46, 200, 20).build()
            );
         } else {'''),
('''      // ModSync 门控：开关开启且为 VoxLink 房间号时，先拉必装清单再进入连接流程；
      // 已门控过/已跳过的房间直接放行；放行回调一律以 modSyncChecked=true 重建本屏，
      // 绝不再入 gate()——否则快速通道会在本屏上空转，卡死在"正在获取清单"
      if (!this.modSyncChecked
         && icu.wuhui.voxlink.modsync.ModSyncGuestService.isEnabled()
         && icu.wuhui.voxlink.terracotta.RoomCodeRouter.isVoxLinkCode(this.roomCode)
         && !icu.wuhui.voxlink.modsync.ModSyncGuestService.shouldSkipGate(this.roomCode)) {
         Minecraft mc0 = Minecraft.getInstance();
         AttemptingJoinScreen self = this;
         this.modSyncChecking = true;
         this.voxlinkStatusText = Component.translatable("voxlink.modsync.checking").getString();
         this.voxlinkStatusColor = VoxLinkColors.WARNING;
         icu.wuhui.voxlink.modsync.ModSyncGuestService.gate(
            this.roomCode,
            () -> mc0.execute(() -> mc0.setScreen(new AttemptingJoinScreen(self.parent, self.roomCode, self.password, true))),
            () -> mc0.execute(() -> {
               if (VoxLinkMod.getRoomManager().getCurrentRoom() != null) {
                  VoxLinkMod.getRoomManager().leaveRoom();
               }

               mc0.setScreen(self.parent);
            })
         );
         return;
      }

      this.doStartDualP2P();''',
'''      // ModSync v2 门控：进入"选择范围"待选态，由玩家点按钮决定必装/全部/跳过；
      // 已跳过的房间直接放行；放行回调一律以 modSyncChecked=true 重建本屏。
      if (!this.modSyncChecked
         && icu.wuhui.voxlink.modsync.ModSyncGuestService.isEnabled()
         && icu.wuhui.voxlink.terracotta.RoomCodeRouter.isVoxLinkCode(this.roomCode)
         && !icu.wuhui.voxlink.modsync.ModSyncGuestService.shouldSkipGate(this.roomCode)) {
         this.modSyncChecking = true;
         this.voxlinkStatusText = Component.translatable("voxlink.modsync.scope_hint").getString();
         this.voxlinkStatusColor = VoxLinkColors.WARNING;
         this.clearOurWidgets();
         this.init();
         return;
      }

      this.doStartDualP2P();'''),
('''   /** 获取清单页"直接进入"：跳过必装检查立刻开始连接；在途清单结果作废（bypass）。 */
   private void onSkipCheckClicked() {''',
'''   /** 玩家选定同步范围：按 scope 走门控拉清单（继续/取消回调与原门控一致）。 */
   private void onScopePicked(String scope) {
      if (!this.modSyncChecking) {
         return;
      }

      this.voxlinkStatusText = Component.translatable("voxlink.modsync.checking").getString();
      this.voxlinkStatusColor = VoxLinkColors.WARNING;
      Minecraft mc0 = Minecraft.getInstance();
      AttemptingJoinScreen self = this;
      icu.wuhui.voxlink.modsync.ModSyncGuestService.gate(
         this.roomCode,
         scope,
         () -> mc0.execute(() -> mc0.setScreen(new AttemptingJoinScreen(self.parent, self.roomCode, self.password, true))),
         () -> mc0.execute(() -> {
            if (VoxLinkMod.getRoomManager().getCurrentRoom() != null) {
               VoxLinkMod.getRoomManager().leaveRoom();
            }

            mc0.setScreen(self.parent);
         })
      );
   }

   /** 获取清单页"直接进入"：跳过必装检查立刻开始连接；在途清单结果作废（bypass）。 */
   private void onSkipCheckClicked() {''')]

def patch_env(path, problems):
    src = read(path)
    if 'getCacheFile' in src:
        return
    eol = '\r\n' if '\r\n' in src else '\n'
    m = re.search(r'public static Path getModsDir\(\) \{\s*return (.+?);\s*\}', src, re.S)
    if not m:
        problems.append(f"{path}: getModsDir pattern not found")
        return
    expr = m.group(1)
    method = (
        f"public static Path getModsDir() {{\n      return {expr};\n   }}",
    )
    old_method = m.group(0).replace('\r\n', '\n')
    new_method = old_method + eol + eol + (
        "   /** modsync 本地清单缓存文件（游戏目录/voxlink 下，不进 mods 目录）。 */\n"
        "   public static Path getCacheFile() {\n"
        f"      return {expr}.resolve(\"voxlink\").resolve(\"modsync_cache.json\");\n"
        "   }").replace('\n', eol)
    if src.count(m.group(0)) != 1:
        problems.append(f"{path}: getModsDir body x{src.count(m.group(0))}")
        return
    write(path, src.replace(m.group(0), new_method))

manifests_new = read(os.path.join(REF, 'modsync', 'ModSyncManifestService.java'))
langs = sorted(glob.glob(os.path.join(REF, 'src/main/resources/assets/voxlink/lang', '*.json')))

problems = []
count = 0
for tpl_dir in sorted(glob.glob(os.path.join(BASE, '*', '*'))):
    if not os.path.isdir(os.path.join(tpl_dir, 'src', 'main', 'java')):
        continue
    if os.path.normpath(tpl_dir) == os.path.normpath(os.path.join(BASE, 'fabric', '1.20_1.20.1')):
        continue
    count += 1
    j = os.path.join(tpl_dir, NET)
    tag = tpl_dir.replace(BASE, '').strip('\\/')
    try:
        # 1) ManifestService 整拷（保留该模板原 EOL）
        dst = os.path.join(j, 'modsync', 'ModSyncManifestService.java')
        old_eol = '\r\n' if '\r\n' in read(dst) else '\n'
        content = manifests_new if old_eol == '\n' else manifests_new.replace('\n', '\r\n')
        write(dst, content)

        # 2) ModSyncFileHasher
        p = os.path.join(j, 'modsync', 'ModSyncFileHasher.java')
        write(p, edit(read(p), HASH_ANCHORS, p))

        # 3) ModSyncEnv（按模板自身 gameDir 表达式）
        patch_env(os.path.join(j, 'modsync', 'ModSyncEnv.java'), problems)

        # 4) GuestService
        p = os.path.join(j, 'modsync', 'ModSyncGuestService.java')
        write(p, edit(read(p), GUEST_ANCHORS, p))

        # 5) SignalingClient
        p = os.path.join(j, 'network', 'SignalingClient.java')
        s = edit(read(p), SIGNAL_ANCHORS, p)
        s = edit(s, SIGNAL_ANCHORS2, p)
        write(p, s)

        # 6) RoomManager
        p = os.path.join(j, 'room', 'RoomManager.java')
        write(p, edit(read(p), ROOM_ANCHORS, p))

        # 7) AttemptingJoinScreen
        p = os.path.join(j, 'ui', 'AttemptingJoinScreen.java')
        write(p, edit(read(p), SCREEN_ANCHORS, p))

        # 8) lang 整拷
        for lf in langs:
            shutil.copyfile(lf, os.path.join(tpl_dir, 'src/main/resources/assets/voxlink/lang', os.path.basename(lf)))
        print('OK', tag)
    except AssertionError as e:
        problems.append(str(e))
        print('FAIL', tag, e)

print('----')
print('templates:', count, 'problems:', len(problems))
for p in problems:
    print('TODO:', p)
