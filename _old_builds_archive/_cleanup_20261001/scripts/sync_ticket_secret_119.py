"""1.1.9 工单归属：mod 28 模板 TicketClient 保存并回传 ticketSecret。

配套服务端改动（server-go/ticket.go）：提交时签发一次性 secret（服务端只存哈希），
detail/reply/viewed/delete 需带凭证；签发前建的老工单 secretHash 为空 → 服务端继续放行，
因此老记录、老客户端都不会被打断。本地存 voxlink_tickets.json（游戏目录，非云）。

每处编辑都携带期望命中次数；任一锚点不匹配则整份文件不落盘。
"""
import glob, io, sys

# (old, new, 期望命中次数)
EDITS = [
    # 1) 本地记录结构加 secret
    ('''   public static final class LocalTicket {
      public String id;''',
     '''   public static final class LocalTicket {
      public String id;
      /** 提交时服务端签发的一次性归属凭证（明文只出现这一次），随查询/追问/删除回传。 */
      public String secret = "";''', 1),
    # 2) 读本地
    ('                  t.id = str(o, "id");',
     '                  t.id = str(o, "id");\n                  t.secret = str(o, "secret");', 1),
    # 3) 写本地
    ('               o.addProperty("id", t.id);',
     '               o.addProperty("id", t.id);\n               o.addProperty("secret", t.secret == null ? "" : t.secret);', 1),
    # 4) upsertLocal 收 secret：已存在的老记录可补登（首次提交后回读）
    ('''   private static void upsertLocal(String id, long timeMs) {
      load();
      synchronized (LOCAL) {
         for (LocalTicket t : LOCAL) {
            if (t.id.equals(id)) {
               return;
            }
         }''',
     '''   private static void upsertLocal(String id, long timeMs, String secret) {
      load();
      synchronized (LOCAL) {
         for (LocalTicket t : LOCAL) {
            if (t.id.equals(id)) {
               if (secret != null && !secret.isEmpty() && (t.secret == null || t.secret.isEmpty())) {
                  t.secret = secret;
                  save();
               }
               return;
            }
         }''', 1),
    ('''         t.id = id;
         t.timeMs = timeMs;''',
     '''         t.id = id;
         t.secret = secret == null ? "" : secret;
         t.timeMs = timeMs;''', 1),
    # 5) 提交响应取 ticketSecret
    ('''            String id = str(resp.root.getAsJsonObject("data"), "id");
            upsertLocal(id, System.currentTimeMillis());''',
     '''            JsonObject tkData = resp.root.getAsJsonObject("data");
            String id = str(tkData, "id");
            upsertLocal(id, System.currentTimeMillis(), str(tkData, "ticketSecret"));''', 1),
    # 6) 追问带 secret（multipart 表单项）
    ('      parts.add(textPart(boundary, "id", ticketId));',
     '      parts.add(textPart(boundary, "id", ticketId));\n      parts.add(textPart(boundary, "secret", secretOf(ticketId)));', 1),
    # 7) 详情查询带 secret（hex 值，无需转义）
    ('      String url = normalize(serverUrl, "/ticket/detail?id=" + ticketId);',
     '      String sec = secretOf(ticketId);\n      String url = normalize(serverUrl, "/ticket/detail?id=" + ticketId + (sec.isEmpty() ? "" : "&secret=" + sec));', 1),
    # 8) viewed / delete 两个 JSON body 各带 secret
    ('''      JsonObject body = new JsonObject();
      body.addProperty("id", ticketId);''',
     '''      JsonObject body = new JsonObject();
      body.addProperty("id", ticketId);
      body.addProperty("secret", secretOf(ticketId));''', 2),
]

HELPER = '''   /** 取本地保存的工单归属凭证；未知/老记录返回空串（服务端对老工单继续放行）。 */
   private static String secretOf(String ticketId) {
      load();
      if (ticketId == null) {
         return "";
      }
      synchronized (LOCAL) {
         for (LocalTicket t : LOCAL) {
            if (t.id.equals(ticketId)) {
               return t.secret == null ? "" : t.secret;
            }
         }
      }
      return "";
   }

'''

HELPER_ANCHOR = "   private static void upsertLocal("

files = sorted(glob.glob("*/*/src/main/java/icu/wuhui/voxlink/network/TicketClient.java"))
if len(files) != 28:
    print("模板数异常: %d" % len(files))
    sys.exit(1)

ok, bad = [], []
for f in files:
    s = io.open(f, encoding="utf-8").read()
    if "secretOf(" in s:
        ok.append(f + " (已打过)")
        continue
    if s.count(HELPER_ANCHOR) != 1:
        bad.append("%s: upsertLocal 锚点命中 %d 次" % (f, s.count(HELPER_ANCHOR)))
        continue
    err = None
    for i, (old, new, want) in enumerate(EDITS):
        n = s.count(old)
        if n != want:
            err = "编辑%d 命中 %d 次（应为 %d）" % (i + 1, n, want)
            break
        s = s.replace(old, new)
    if err:
        bad.append("%s: %s" % (f, err))
        continue
    s = s.replace(HELPER_ANCHOR, HELPER + HELPER_ANCHOR, 1)
    io.open(f, "w", encoding="utf-8", newline="").write(s)
    ok.append(f)

print("模板 %d，成功 %d，失败 %d" % (len(files), len(ok), len(bad)))
for b in bad:
    print("  需人工: " + b)
sys.exit(1 if bad else 0)
