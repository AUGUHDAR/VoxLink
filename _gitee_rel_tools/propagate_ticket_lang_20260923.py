# -*- coding: utf-8 -*-
"""工单系统 lang key 传播：27 key x 13 语言 x 28 模板，锚 join_no_response 行，CRLF 保持"""
import glob, json, os, re, sys

BASE = r"d:\桌面\voxlink"

TRANS = {
    "zh_cn.json": {
        "list_title": "我的工单", "my": "我的工单", "notify": "工单有新回复",
        "empty": "暂无工单", "count": "共 %s 个工单",
        "tag_unread": "[新回复]", "tag_viewed": "[已查看]", "tag_deleted": "[已删除]", "tag_waiting": "[等待回复]",
        "prev_page": "上一页", "next_page": "下一页", "page": "第 %s/%s 页",
        "loading": "正在加载工单…", "load_failed": "工单加载失败，请稍后重试",
        "reply_hint": "追问内容…", "send": "发送",
        "add_attach": "添加附件", "clear_attach": "清空附件", "attach_count": "已添加 %s 个附件",
        "attach_prefix": "附件: ", "delete": "删除工单",
        "err_empty": "内容不能为空", "err_failed": "发送失败", "rate_limited": "发送太频繁，请稍后再试",
        "from_admin": "[管理员]", "from_me": "[我]",
        "created": "工单已创建：%s（可在 我的工单 查看回复）",
    },
    "zh_tw.json": {
        "list_title": "我的工單", "my": "我的工單", "notify": "工單有新回覆",
        "empty": "暫無工單", "count": "共 %s 個工單",
        "tag_unread": "[新回覆]", "tag_viewed": "[已查看]", "tag_deleted": "[已刪除]", "tag_waiting": "[等待回覆]",
        "prev_page": "上一頁", "next_page": "下一頁", "page": "第 %s/%s 頁",
        "loading": "正在載入工單…", "load_failed": "工單載入失敗，請稍後重試",
        "reply_hint": "追問內容…", "send": "傳送",
        "add_attach": "新增附件", "clear_attach": "清空附件", "attach_count": "已新增 %s 個附件",
        "attach_prefix": "附件: ", "delete": "刪除工單",
        "err_empty": "內容不能為空", "err_failed": "傳送失敗", "rate_limited": "傳送太頻繁，請稍後再試",
        "from_admin": "[管理員]", "from_me": "[我]",
        "created": "工單已建立：%s（可在 我的工單 查看回覆）",
    },
    "zh_hk.json": {
        "list_title": "我嘅工單", "my": "我嘅工單", "notify": "工單有新回覆",
        "empty": "暫時冇工單", "count": "共 %s 個工單",
        "tag_unread": "[新回覆]", "tag_viewed": "[已查看]", "tag_deleted": "[已刪除]", "tag_waiting": "[等待回覆]",
        "prev_page": "上一頁", "next_page": "下一頁", "page": "第 %s/%s 頁",
        "loading": "正在載入工單…", "load_failed": "工單載入失敗，請稍後再試",
        "reply_hint": "追問內容…", "send": "傳送",
        "add_attach": "新增附件", "clear_attach": "清空附件", "attach_count": "已新增 %s 個附件",
        "attach_prefix": "附件: ", "delete": "刪除工單",
        "err_empty": "內容唔可以係空", "err_failed": "傳送失敗", "rate_limited": "傳送太頻繁，請稍後再試",
        "from_admin": "[管理員]", "from_me": "[我]",
        "created": "工單已建立：%s（可以喺 我嘅工單 查看回覆）",
    },
    "lzh.json": {
        "list_title": "吾之工單", "my": "吾之工單", "notify": "工單有新答",
        "empty": "尚無工單", "count": "凡 %s 單",
        "tag_unread": "[新答]", "tag_viewed": "[已閱]", "tag_deleted": "[已刪]", "tag_waiting": "[候答]",
        "prev_page": "前頁", "next_page": "後頁", "page": "第 %s/%s 頁",
        "loading": "方載工單…", "load_failed": "載單不成，請再試",
        "reply_hint": "續問之辭…", "send": "發之",
        "add_attach": "增附件", "clear_attach": "清附件", "attach_count": "已增附件 %s",
        "attach_prefix": "附件: ", "delete": "刪工單",
        "err_empty": "辭不可空", "err_failed": "發之不成", "rate_limited": "發之太頻，稍候再試",
        "from_admin": "[司事]", "from_me": "[吾]",
        "created": "工單已成：%s（可於 吾之工單 閱答）",
    },
    "en_us.json": {
        "list_title": "My Tickets", "my": "My Tickets", "notify": "New ticket reply",
        "empty": "No tickets yet", "count": "%s ticket(s)",
        "tag_unread": "[New reply]", "tag_viewed": "[Viewed]", "tag_deleted": "[Deleted]", "tag_waiting": "[Awaiting reply]",
        "prev_page": "Prev", "next_page": "Next", "page": "Page %s/%s",
        "loading": "Loading ticket…", "load_failed": "Failed to load ticket, try again later",
        "reply_hint": "Type your follow-up…", "send": "Send",
        "add_attach": "Add files", "clear_attach": "Clear files", "attach_count": "%s file(s) attached",
        "attach_prefix": "Files: ", "delete": "Delete ticket",
        "err_empty": "Content cannot be empty", "err_failed": "Send failed", "rate_limited": "Too frequent, try again later",
        "from_admin": "[Admin]", "from_me": "[Me]",
        "created": "Ticket created: %s (check replies in My Tickets)",
    },
    "ja_jp.json": {
        "list_title": "マイチケット", "my": "マイチケット", "notify": "チケットに新着返信",
        "empty": "チケットはまだありません", "count": "全 %s 件",
        "tag_unread": "[新着返信]", "tag_viewed": "[既読]", "tag_deleted": "[削除済み]", "tag_waiting": "[返信待ち]",
        "prev_page": "前へ", "next_page": "次へ", "page": "%s/%s ページ",
        "loading": "チケットを読み込み中…", "load_failed": "チケットの読み込みに失敗しました。後でもう一度お試しください",
        "reply_hint": "追問の内容…", "send": "送信",
        "add_attach": "ファイル追加", "clear_attach": "ファイルクリア", "attach_count": "%s 件のファイルを添付",
        "attach_prefix": "添付: ", "delete": "チケット削除",
        "err_empty": "内容が空です", "err_failed": "送信に失敗しました", "rate_limited": "送信が頻繁すぎます。後でもう一度お試しください",
        "from_admin": "[管理者]", "from_me": "[自分]",
        "created": "チケット作成：%s（マイチケットで返信を確認できます）",
    },
    "ko_kr.json": {
        "list_title": "내 티켓", "my": "내 티켓", "notify": "티켓에 새 답변",
        "empty": "티켓이 없습니다", "count": "총 %s개",
        "tag_unread": "[새 답변]", "tag_viewed": "[확인함]", "tag_deleted": "[삭제됨]", "tag_waiting": "[답변 대기]",
        "prev_page": "이전", "next_page": "다음", "page": "%s/%s 페이지",
        "loading": "티켓 불러오는 중…", "load_failed": "티켓을 불러오지 못했습니다. 나중에 다시 시도하세요",
        "reply_hint": "추가 질문 입력…", "send": "보내기",
        "add_attach": "파일 추가", "clear_attach": "파일 지우기", "attach_count": "파일 %s개 첨부됨",
        "attach_prefix": "첨부: ", "delete": "티켓 삭제",
        "err_empty": "내용을 입력하세요", "err_failed": "전송 실패", "rate_limited": "너무 자주 보냈습니다. 잠시 후 다시 시도하세요",
        "from_admin": "[관리자]", "from_me": "[나]",
        "created": "티켓 생성됨: %s (내 티켓에서 답변 확인)",
    },
    "ru_ru.json": {
        "list_title": "Мои тикеты", "my": "Мои тикеты", "notify": "Новый ответ на тикет",
        "empty": "Тикетов пока нет", "count": "Всего: %s",
        "tag_unread": "[Новый ответ]", "tag_viewed": "[Прочитано]", "tag_deleted": "[Удалено]", "tag_waiting": "[Ждёт ответа]",
        "prev_page": "Назад", "next_page": "Вперёд", "page": "Стр. %s/%s",
        "loading": "Загрузка тикета…", "load_failed": "Не удалось загрузить тикет, попробуйте позже",
        "reply_hint": "Текст вопроса…", "send": "Отправить",
        "add_attach": "Добавить файлы", "clear_attach": "Очистить файлы", "attach_count": "Файлов: %s",
        "attach_prefix": "Файлы: ", "delete": "Удалить тикет",
        "err_empty": "Содержимое не может быть пустым", "err_failed": "Ошибка отправки", "rate_limited": "Слишком часто, попробуйте позже",
        "from_admin": "[Админ]", "from_me": "[Я]",
        "created": "Тикет создан: %s (ответы — в Мои тикеты)",
    },
    "de_de.json": {
        "list_title": "Meine Tickets", "my": "Meine Tickets", "notify": "Neue Ticket-Antwort",
        "empty": "Noch keine Tickets", "count": "%s Ticket(s)",
        "tag_unread": "[Neue Antwort]", "tag_viewed": "[Gelesen]", "tag_deleted": "[Gelöscht]", "tag_waiting": "[Warte auf Antwort]",
        "prev_page": "Zurück", "next_page": "Weiter", "page": "Seite %s/%s",
        "loading": "Ticket wird geladen…", "load_failed": "Ticket konnte nicht geladen werden, später erneut versuchen",
        "reply_hint": "Nachricht eingeben…", "send": "Senden",
        "add_attach": "Dateien hinzufügen", "clear_attach": "Dateien entfernen", "attach_count": "%s Datei(en) angehängt",
        "attach_prefix": "Dateien: ", "delete": "Ticket löschen",
        "err_empty": "Inhalt darf nicht leer sein", "err_failed": "Senden fehlgeschlagen", "rate_limited": "Zu häufig, später erneut versuchen",
        "from_admin": "[Admin]", "from_me": "[Ich]",
        "created": "Ticket erstellt: %s (Antworten unter Meine Tickets)",
    },
    "fr_fr.json": {
        "list_title": "Mes tickets", "my": "Mes tickets", "notify": "Nouvelle réponse",
        "empty": "Aucun ticket", "count": "%s ticket(s)",
        "tag_unread": "[Nouvelle réponse]", "tag_viewed": "[Lu]", "tag_deleted": "[Supprimé]", "tag_waiting": "[En attente]",
        "prev_page": "Préc.", "next_page": "Suiv.", "page": "Page %s/%s",
        "loading": "Chargement du ticket…", "load_failed": "Échec du chargement, réessayez plus tard",
        "reply_hint": "Votre message…", "send": "Envoyer",
        "add_attach": "Ajouter fichiers", "clear_attach": "Vider fichiers", "attach_count": "%s fichier(s) joint(s)",
        "attach_prefix": "Fichiers : ", "delete": "Supprimer",
        "err_empty": "Le contenu ne peut pas être vide", "err_failed": "Échec de l'envoi", "rate_limited": "Trop fréquent, réessayez plus tard",
        "from_admin": "[Admin]", "from_me": "[Moi]",
        "created": "Ticket créé : %s (réponses dans Mes tickets)",
    },
    "es_es.json": {
        "list_title": "Mis tickets", "my": "Mis tickets", "notify": "Nueva respuesta",
        "empty": "Sin tickets", "count": "%s ticket(s)",
        "tag_unread": "[Nueva respuesta]", "tag_viewed": "[Visto]", "tag_deleted": "[Eliminado]", "tag_waiting": "[Esperando respuesta]",
        "prev_page": "Anterior", "next_page": "Siguiente", "page": "Página %s/%s",
        "loading": "Cargando ticket…", "load_failed": "No se pudo cargar el ticket, prueba más tarde",
        "reply_hint": "Escribe tu mensaje…", "send": "Enviar",
        "add_attach": "Añadir archivos", "clear_attach": "Quitar archivos", "attach_count": "%s archivo(s) adjunto(s)",
        "attach_prefix": "Archivos: ", "delete": "Eliminar ticket",
        "err_empty": "El contenido no puede estar vacío", "err_failed": "Error al enviar", "rate_limited": "Demasiado frecuente, prueba más tarde",
        "from_admin": "[Admin]", "from_me": "[Yo]",
        "created": "Ticket creado: %s (respuestas en Mis tickets)",
    },
    "pt_br.json": {
        "list_title": "Meus tickets", "my": "Meus tickets", "notify": "Nova resposta",
        "empty": "Nenhum ticket", "count": "%s ticket(s)",
        "tag_unread": "[Nova resposta]", "tag_viewed": "[Visto]", "tag_deleted": "[Excluído]", "tag_waiting": "[Aguardando resposta]",
        "prev_page": "Anterior", "next_page": "Próxima", "page": "Página %s/%s",
        "loading": "Carregando ticket…", "load_failed": "Falha ao carregar, tente novamente mais tarde",
        "reply_hint": "Escreva sua mensagem…", "send": "Enviar",
        "add_attach": "Adicionar arquivos", "clear_attach": "Limpar arquivos", "attach_count": "%s arquivo(s) anexado(s)",
        "attach_prefix": "Arquivos: ", "delete": "Excluir ticket",
        "err_empty": "O conteúdo não pode estar vazio", "err_failed": "Falha ao enviar", "rate_limited": "Muito frequente, tente mais tarde",
        "from_admin": "[Admin]", "from_me": "[Eu]",
        "created": "Ticket criado: %s (respostas em Meus tickets)",
    },
    "ar_sa.json": {
        "list_title": "تذاكري", "my": "تذاكري", "notify": "رد جديد على تذكرة",
        "empty": "لا توجد تذاكر", "count": "%s تذكرة/تذاكر",
        "tag_unread": "[رد جديد]", "tag_viewed": "[تمت المشاهدة]", "tag_deleted": "[محذوفة]", "tag_waiting": "[بانتظار الرد]",
        "prev_page": "السابق", "next_page": "التالي", "page": "صفحة %s/%s",
        "loading": "جارٍ تحميل التذكرة…", "load_failed": "فشل تحميل التذكرة، حاول لاحقًا",
        "reply_hint": "اكتب سؤالك…", "send": "إرسال",
        "add_attach": "إضافة ملفات", "clear_attach": "إزالة الملفات", "attach_count": "%s ملف مرفق",
        "attach_prefix": "الملفات: ", "delete": "حذف التذكرة",
        "err_empty": "لا يمكن أن يكون المحتوى فارغًا", "err_failed": "فشل الإرسال", "rate_limited": "مرات كثيرة، حاول لاحقًا",
        "from_admin": "[الإدارة]", "from_me": "[أنا]",
        "created": "تم إنشاء التذكرة: %s (الردود في تذاكري)",
    },
}

KEY_ORDER = ["list_title", "my", "notify", "empty", "count", "tag_unread", "tag_viewed", "tag_deleted",
             "tag_waiting", "prev_page", "next_page", "page", "loading", "load_failed", "reply_hint",
             "send", "add_attach", "clear_attach", "attach_count", "attach_prefix", "delete",
             "err_empty", "err_failed", "rate_limited", "from_admin", "from_me", "created"]

files = sorted(glob.glob(os.path.join(BASE, "*", "*", "src", "main", "resources", "assets", "voxlink", "lang", "*.json")))
assert len(files) == 364, len(files)

errors = []
done = 0
LANG_RE = re.compile(r'^([ \t]*)"voxlink\.connection\.join_no_response":.*$', re.M)

for f in files:
    name = os.path.basename(f)
    if name not in TRANS:
        errors.append("%s: no translations" % os.path.relpath(f, BASE))
        continue
    raw = open(f, "rb").read()
    eol = "\r\n" if b"\r\n" in raw else "\n"
    t = raw.decode("utf-8")
    if '"voxlink.ticket.list_title"' in t:
        done += 1
        continue
    m = LANG_RE.search(t)
    if not m or t.count('"voxlink.connection.join_no_response"') != 1:
        errors.append("%s: anchor missing/not-unique" % os.path.relpath(f, BASE))
        continue
    indent = m.group(1)
    lines = []
    for k in KEY_ORDER:
        v = TRANS[name][k]
        lines.append('%s"voxlink.ticket.%s": %s' % (indent, k, json.dumps(v, ensure_ascii=False)))
    ins = (",\n" + indent).join(lines)
    t = t[:m.end()] + ",\n" + ins + t[m.end():]
    # 原锚行原本可能无尾逗号（最后一行）——上面统一加了逗号在锚行后，锚行若为最后一行需闭合检查
    try:
        json.loads(t)
    except Exception as e:
        errors.append("%s: JSON broken: %s" % (os.path.relpath(f, BASE), e))
        continue
    open(f, "wb").write(t.replace("\n", eol).encode("utf-8"))
    done += 1

print("done:", done, "errors:", len(errors))
for e in errors:
    print("  ERR", e)

# 终检：全 364 文件 27 key + JSON 合法
miss = 0
for f in files:
    try:
        d = json.load(open(f, encoding="utf-8"))
    except Exception as e:
        print("JSONERR", f, e)
        miss += 1
        continue
    for k in KEY_ORDER:
        if ("voxlink.ticket." + k) not in d:
            print("MISS", os.path.relpath(f, BASE), k)
            miss += 1
print("final check:", "ALL PASS" if miss == 0 and not errors else "%d issues" % (miss + len(errors)))
sys.exit(0 if miss == 0 and not errors else 1)
