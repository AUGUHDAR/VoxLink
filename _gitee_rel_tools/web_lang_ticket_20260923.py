# -*- coding: utf-8 -*-
"""web/lang 13 文件加 tk_admin.* 工单后台 keys"""
import json, os, sys

LANGDIR = r"d:\桌面\voxlink\server-go\web\lang"

TRANS = {
    "zh_cn.json": {
        "tk_admin.title": "工单 (%d)", "tk_admin.empty": "还没有工单",
        "tk_admin.tag_unread": "有新回复未读", "tk_admin.tag_viewed": "已回复·玩家已查看",
        "tk_admin.tag_deleted": "玩家端已删除", "tk_admin.tag_waiting": "待回复",
        "tk_admin.from_admin": "管理员", "tk_admin.from_player": "玩家",
        "tk_admin.created": "创建时间", "tk_admin.last_active": "最后活动",
        "tk_admin.reply_ph": "回复玩家（纯文字，玩家在游戏内可见）…", "tk_admin.reply_send": "回复",
        "tk_admin.delete": "彻底删除", "tk_admin.delete_confirm": "彻底删除工单（%s）？附件与对话将一并删除，不可恢复。",
    },
    "zh_tw.json": {
        "tk_admin.title": "工單 (%d)", "tk_admin.empty": "還沒有工單",
        "tk_admin.tag_unread": "有新回覆未讀", "tk_admin.tag_viewed": "已回覆·玩家已查看",
        "tk_admin.tag_deleted": "玩家端已刪除", "tk_admin.tag_waiting": "待回覆",
        "tk_admin.from_admin": "管理員", "tk_admin.from_player": "玩家",
        "tk_admin.created": "建立時間", "tk_admin.last_active": "最後活動",
        "tk_admin.reply_ph": "回覆玩家（純文字，玩家在遊戲內可見）…", "tk_admin.reply_send": "回覆",
        "tk_admin.delete": "徹底刪除", "tk_admin.delete_confirm": "徹底刪除工單（%s）？附件與對話將一併刪除，不可復原。",
    },
    "zh_hk.json": {
        "tk_admin.title": "工單 (%d)", "tk_admin.empty": "仲未有工單",
        "tk_admin.tag_unread": "有新回覆未讀", "tk_admin.tag_viewed": "已回覆·玩家已睇",
        "tk_admin.tag_deleted": "玩家端已刪除", "tk_admin.tag_waiting": "待回覆",
        "tk_admin.from_admin": "管理員", "tk_admin.from_player": "玩家",
        "tk_admin.created": "建立時間", "tk_admin.last_active": "最後活動",
        "tk_admin.reply_ph": "回覆玩家（純文字，玩家喺遊戲內可見）…", "tk_admin.reply_send": "回覆",
        "tk_admin.delete": "徹底刪除", "tk_admin.delete_confirm": "徹底刪除工單（%s）？附件同對話會一齊刪除，冇得復原。",
    },
    "lzh.json": {
        "tk_admin.title": "工單 (%d)", "tk_admin.empty": "尚無工單",
        "tk_admin.tag_unread": "有新答未閱", "tk_admin.tag_viewed": "已答·已閱",
        "tk_admin.tag_deleted": "玩家已刪", "tk_admin.tag_waiting": "候答",
        "tk_admin.from_admin": "司事", "tk_admin.from_player": "玩家",
        "tk_admin.created": "立單之時", "tk_admin.last_active": "末動之時",
        "tk_admin.reply_ph": "答玩家（純文）…", "tk_admin.reply_send": "答之",
        "tk_admin.delete": "永刪", "tk_admin.delete_confirm": "永刪工單（%s）？附件對話俱滅，不可復。",
    },
    "en_us.json": {
        "tk_admin.title": "Tickets (%d)", "tk_admin.empty": "No tickets yet",
        "tk_admin.tag_unread": "Unread reply", "tk_admin.tag_viewed": "Replied · viewed",
        "tk_admin.tag_deleted": "Deleted by player", "tk_admin.tag_waiting": "Awaiting reply",
        "tk_admin.from_admin": "Admin", "tk_admin.from_player": "Player",
        "tk_admin.created": "Created", "tk_admin.last_active": "Last activity",
        "tk_admin.reply_ph": "Reply to player (text only, visible in game)…", "tk_admin.reply_send": "Reply",
        "tk_admin.delete": "Delete permanently", "tk_admin.delete_confirm": "Permanently delete ticket %s? Attachments and conversation will be removed.",
    },
    "ja_jp.json": {
        "tk_admin.title": "チケット (%d)", "tk_admin.empty": "チケットはまだありません",
        "tk_admin.tag_unread": "未読の返信", "tk_admin.tag_viewed": "返信済み・閲覧済み",
        "tk_admin.tag_deleted": "プレイヤーが削除", "tk_admin.tag_waiting": "返信待ち",
        "tk_admin.from_admin": "管理者", "tk_admin.from_player": "プレイヤー",
        "tk_admin.created": "作成", "tk_admin.last_active": "最終活動",
        "tk_admin.reply_ph": "プレイヤーへ返信（テキストのみ、ゲーム内で表示）…", "tk_admin.reply_send": "返信",
        "tk_admin.delete": "完全削除", "tk_admin.delete_confirm": "チケット %s を完全に削除しますか？添付と会話も削除されます。",
    },
    "ko_kr.json": {
        "tk_admin.title": "티켓 (%d)", "tk_admin.empty": "티켓이 없습니다",
        "tk_admin.tag_unread": "안 읽은 답변", "tk_admin.tag_viewed": "답변됨 · 확인함",
        "tk_admin.tag_deleted": "플레이어가 삭제", "tk_admin.tag_waiting": "답변 대기",
        "tk_admin.from_admin": "관리자", "tk_admin.from_player": "플레이어",
        "tk_admin.created": "생성", "tk_admin.last_active": "마지막 활동",
        "tk_admin.reply_ph": "플레이어에게 답변(텍스트만, 게임 내 표시)…", "tk_admin.reply_send": "답변",
        "tk_admin.delete": "영구 삭제", "tk_admin.delete_confirm": "티켓 %s을(를) 영구 삭제할까요? 첨부와 대화도 함께 삭제됩니다.",
    },
    "ru_ru.json": {
        "tk_admin.title": "Тикеты (%d)", "tk_admin.empty": "Тикетов пока нет",
        "tk_admin.tag_unread": "Непрочитанный ответ", "tk_admin.tag_viewed": "Отвечено · прочитано",
        "tk_admin.tag_deleted": "Удалён игроком", "tk_admin.tag_waiting": "Ждёт ответа",
        "tk_admin.from_admin": "Админ", "tk_admin.from_player": "Игрок",
        "tk_admin.created": "Создан", "tk_admin.last_active": "Последняя активность",
        "tk_admin.reply_ph": "Ответ игроку (только текст, виден в игре)…", "tk_admin.reply_send": "Ответить",
        "tk_admin.delete": "Удалить навсегда", "tk_admin.delete_confirm": "Удалить тикет %s навсегда? Вложения и переписка будут удалены.",
    },
    "de_de.json": {
        "tk_admin.title": "Tickets (%d)", "tk_admin.empty": "Noch keine Tickets",
        "tk_admin.tag_unread": "Ungelesene Antwort", "tk_admin.tag_viewed": "Beantwortet · gelesen",
        "tk_admin.tag_deleted": "Vom Spieler gelöscht", "tk_admin.tag_waiting": "Wartet auf Antwort",
        "tk_admin.from_admin": "Admin", "tk_admin.from_player": "Spieler",
        "tk_admin.created": "Erstellt", "tk_admin.last_active": "Letzte Aktivität",
        "tk_admin.reply_ph": "Antwort an Spieler (nur Text, im Spiel sichtbar)…", "tk_admin.reply_send": "Antworten",
        "tk_admin.delete": "Endgültig löschen", "tk_admin.delete_confirm": "Ticket %s endgültig löschen? Anhänge und Verlauf werden entfernt.",
    },
    "fr_fr.json": {
        "tk_admin.title": "Tickets (%d)", "tk_admin.empty": "Aucun ticket",
        "tk_admin.tag_unread": "Réponse non lue", "tk_admin.tag_viewed": "Répondu · lu",
        "tk_admin.tag_deleted": "Supprimé par le joueur", "tk_admin.tag_waiting": "En attente de réponse",
        "tk_admin.from_admin": "Admin", "tk_admin.from_player": "Joueur",
        "tk_admin.created": "Créé", "tk_admin.last_active": "Dernière activité",
        "tk_admin.reply_ph": "Répondre au joueur (texte seul, visible en jeu)…", "tk_admin.reply_send": "Répondre",
        "tk_admin.delete": "Supprimer définitivement", "tk_admin.delete_confirm": "Supprimer définitivement le ticket %s ? Pièces jointes et conversation seront effacées.",
    },
    "es_es.json": {
        "tk_admin.title": "Tickets (%d)", "tk_admin.empty": "Sin tickets",
        "tk_admin.tag_unread": "Respuesta sin leer", "tk_admin.tag_viewed": "Respondido · visto",
        "tk_admin.tag_deleted": "Eliminado por el jugador", "tk_admin.tag_waiting": "Esperando respuesta",
        "tk_admin.from_admin": "Admin", "tk_admin.from_player": "Jugador",
        "tk_admin.created": "Creado", "tk_admin.last_active": "Última actividad",
        "tk_admin.reply_ph": "Responder al jugador (solo texto, visible en el juego)…", "tk_admin.reply_send": "Responder",
        "tk_admin.delete": "Eliminar permanentemente", "tk_admin.delete_confirm": "¿Eliminar permanentemente el ticket %s? Adjuntos y conversación se borrarán.",
    },
    "pt_br.json": {
        "tk_admin.title": "Tickets (%d)", "tk_admin.empty": "Nenhum ticket",
        "tk_admin.tag_unread": "Resposta não lida", "tk_admin.tag_viewed": "Respondido · visto",
        "tk_admin.tag_deleted": "Excluído pelo jogador", "tk_admin.tag_waiting": "Aguardando resposta",
        "tk_admin.from_admin": "Admin", "tk_admin.from_player": "Jogador",
        "tk_admin.created": "Criado", "tk_admin.last_active": "Última atividade",
        "tk_admin.reply_ph": "Responder ao jogador (somente texto, visível no jogo)…", "tk_admin.reply_send": "Responder",
        "tk_admin.delete": "Excluir permanentemente", "tk_admin.delete_confirm": "Excluir permanentemente o ticket %s? Anexos e conversa serão removidos.",
    },
    "ar_sa.json": {
        "tk_admin.title": "التذاكر (%d)", "tk_admin.empty": "لا توجد تذاكر بعد",
        "tk_admin.tag_unread": "رد غير مقروء", "tk_admin.tag_viewed": "تم الرد · شوهد",
        "tk_admin.tag_deleted": "حذفها اللاعب", "tk_admin.tag_waiting": "بانتظار الرد",
        "tk_admin.from_admin": "الإدارة", "tk_admin.from_player": "اللاعب",
        "tk_admin.created": "أُنشئت", "tk_admin.last_active": "آخر نشاط",
        "tk_admin.reply_ph": "الرد على اللاعب (نص فقط، يظهر في اللعبة)…", "tk_admin.reply_send": "رد",
        "tk_admin.delete": "حذف نهائي", "tk_admin.delete_confirm": "حذف التذكرة %s نهائيًا؟ ستُحذف المرفقات والمحادثة.",
    },
}

KEYS = ["tk_admin.title", "tk_admin.empty", "tk_admin.tag_unread", "tk_admin.tag_viewed",
        "tk_admin.tag_deleted", "tk_admin.tag_waiting", "tk_admin.from_admin", "tk_admin.from_player",
        "tk_admin.created", "tk_admin.last_active", "tk_admin.reply_ph", "tk_admin.reply_send",
        "tk_admin.delete", "tk_admin.delete_confirm"]

errors = []
for fname, m in TRANS.items():
    p = os.path.join(LANGDIR, fname)
    d = json.load(open(p, encoding="utf-8"))
    if "tk_admin.title" in d:
        continue
    for k in KEYS:
        d[k] = m[k]
    with open(p, "w", encoding="utf-8", newline="") as f:
        json.dump(d, f, ensure_ascii=False, indent=2)
        f.write("\n")

# 终检
for fname in TRANS:
    p = os.path.join(LANGDIR, fname)
    d = json.load(open(p, encoding="utf-8"))
    for k in KEYS:
        if k not in d:
            errors.append("%s missing %s" % (fname, k))
print("errors:", len(errors))
for e in errors:
    print("  ERR", e)
sys.exit(1 if errors else 0)
