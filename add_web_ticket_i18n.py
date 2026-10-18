# -*- coding: utf-8 -*-
"""网页端玩家工单 i18n：向 13 个语言文件注入 tk.* 键（已存在的键不覆盖）。

只做工单页用到的 25 个键；写回保持 2 空格缩进、LF、末尾换行、非 ASCII 原样。
"""
import io, json, os, sys

SUFFIX = [
    "my_title", "my_desc", "id_label", "secret_label", "backup_tip", "i_saved",
    "copy", "copied", "refresh", "detail_title", "back_list", "delete",
    "delete_confirm", "reply_label", "reply_ph", "reply_send", "unread", "replies",
    "my_empty", "you", "staff", "loading", "load_fail", "forbidden", "no_reply",
]

T = {
"zh_cn": ["我的工单", "查看官方回复并追问（单号与凭证保存在本浏览器）", "工单号", "查询凭证",
    "单号与凭证只保存在当前浏览器。换设备、换浏览器或清除浏览器数据后，将无法再打开这条工单，建议自行复制备份。",
    "我已备份，返回", "复制", "已复制", "刷新回复", "工单", "返回列表", "删除",
    "确定删除这条工单？官方仍保留记录，只是你这边不再显示。", "追问", "补充说明或追问…", "发送追问",
    "有新回复", "条回复", "这个浏览器还没有保存过工单", "我", "官方", "加载中…",
    "加载失败，请稍后再试", "缺少查询凭证，无法打开这条工单（可能清除了浏览器数据或换了设备）",
    "还没有回复，官方回复后会显示在这里"],

"zh_tw": ["我的工單", "檢視官方回覆並追問（單號與憑證儲存在此瀏覽器）", "工單號", "查詢憑證",
    "單號與憑證只儲存在目前的瀏覽器。換裝置、換瀏覽器或清除瀏覽器資料後，將無法再開啟這張工單，建議自行複製備份。",
    "我已備份，返回", "複製", "已複製", "重新整理回覆", "工單", "返回清單", "刪除",
    "確定刪除這張工單？官方仍保留記錄，只是你這邊不再顯示。", "追問", "補充說明或追問…", "送出追問",
    "有新回覆", "則回覆", "這個瀏覽器還沒有儲存過工單", "我", "官方", "載入中…",
    "載入失敗，請稍後再試", "缺少查詢憑證，無法開啟這張工單（可能清除了瀏覽器資料或換了裝置）",
    "還沒有回覆，官方回覆後會顯示在這裡"],

"zh_hk": ["我嘅工單", "睇官方回覆同追問（單號同憑證存喺呢個瀏覽器）", "工單號", "查詢憑證",
    "單號同憑證只存喺而家呢個瀏覽器。換設備、換瀏覽器或者清除瀏覽器資料之後，就無法再打開呢張工單，建議自己複製备份。",
    "我已备份，返回", "複製", "已複製", "更新回覆", "工單", "返回清單", "刪除",
    "確定刪除呢張工單？官方仍然保留記錄，只係你嗰邊唔再顯示。", "追問", "補充說明或者追問…", "發送追問",
    "有新回覆", "條回覆", "呢個瀏覽器仲未备份過工單", "我", "官方", "載入中…",
    "載入失敗，請遲啲再試", "缺少查詢憑證，無法打開呢張工單（可能清除咗瀏覽器資料或者換咗設備）",
    "仲未有回覆，官方回覆之後會顯示喺呢度"],

"en_us": ["My tickets", "Read staff replies and follow up (ID and key are saved in this browser)",
    "Ticket ID", "Lookup key",
    "The ID and lookup key live only in this browser. After switching device or clearing browser data you will not be able to open this ticket again, so please keep your own copy.",
    "I saved it, back", "Copy", "Copied", "Refresh replies", "Ticket", "Back to list", "Delete",
    "Delete this ticket? Staff keep the record; it only disappears from your list.", "Follow up",
    "Add details or ask a question...", "Send follow-up", "New reply", "replies",
    "No tickets saved in this browser yet", "You", "Staff", "Loading...",
    "Failed to load, please try again later",
    "Missing lookup key, this ticket cannot be opened (browser data cleared or device changed)",
    "No replies yet; staff answers will show up here"],

"ja_jp": ["私のチケット", "公式の返信確認と追加投稿（番号と照合キーはこのブラウザに保存されます）",
    "チケット番号", "照合キー",
    "番号と照合キーはこのブラウザにのみ保存されます。端末やブラウザを変える、またはブラウザのデータを消去すると、このチケットは再度開けません。自分で控えを残すことをおすすめします。",
    "保存しました", "コピー", "コピーしました", "返信を更新", "チケット", "一覧へ戻る", "削除",
    "このチケットを削除しますか？運営側には記録が残り、あなたの表示から消えるだけです。", "追加投稿",
    "補足や質問を入力…", "送信", "新着返信", "件の返信", "このブラウザにはまだチケットがありません",
    "自分", "運営", "読み込み中…", "読み込みに失敗しました。後でお試しください",
    "照合キーがないためこのチケットは開けません（ブラウザのデータを消去したか、端末を変更しました）",
    "まだ返信はありません。運営からの返信はここに表示されます"],

"ko_kr": ["내 티켓", "담당자 답변 확인 및 추가 문의 (번호와 조회 키는 이 브라우저에 저장됨)",
    "티켓 번호", "조회 키",
    "번호와 조회 키는 현재 브라우저에만 저장됩니다. 기기나 브라우저를 바꾸거나 브라우저 데이터를 지우면 이 티켓을 다시 열 수 없으므로 직접 백업해 두세요.",
    "백업했습니다", "복사", "복사되었습니다", "답변 새로고침", "티켓", "목록으로", "삭제",
    "이 티켓을 삭제할까요? 담당자 쪽 기록은 남고 내 목록에서만 사라집니다.", "추가 문의",
    "추가 설명이나 질문을 입력하세요…", "전송", "새 답변", "개의 답변", "이 브라우저에는 저장된 티켓이 없습니다",
    "나", "담당자", "불러오는 중…", "불러오지 못했습니다. 잠시 후 다시 시도하세요",
    "조회 키가 없어 이 티켓을 열 수 없습니다 (브라우저 데이터를 지웠거나 기기를 바꿨습니다)",
    "아직 답변이 없습니다. 담당자 답변이 여기 표시됩니다"],

"fr_fr": ["Mes tickets", "Lire les réponses et relancer (référence et clé sont enregistrées dans ce navigateur)",
    "Référence du ticket", "Clé de consultation",
    "La référence et la clé ne sont enregistrées que dans ce navigateur. Après un changement d'appareil ou l'effacement des données du navigateur, ce ticket ne pourra plus être ouvert : gardez-en une copie.",
    "J'ai sauvegardé", "Copier", "Copié", "Rafraîchir les réponses", "Ticket", "Retour à la liste", "Supprimer",
    "Supprimer ce ticket ? Le staff conserve l'historique ; il disparaît seulement de votre liste.", "Relancer",
    "Précisions ou question…", "Envoyer", "Nouvelle réponse", "réponses",
    "Aucun ticket enregistré dans ce navigateur", "Moi", "Staff", "Chargement…",
    "Échec du chargement, réessayez plus tard",
    "Clé de consultation manquante, impossible d'ouvrir ce ticket (données effacées ou appareil différent)",
    "Aucune réponse pour l'instant ; les réponses du staff s'afficheront ici"],

"de_de": ["Meine Tickets", "Antworten lesen und nachfassen (Nummer und Schlüssel sind in diesem Browser gespeichert)",
    "Ticket-Nummer", "Abfrageschlüssel",
    "Nummer und Schlüssel liegen nur in diesem Browser. Nach Geräte­wechsel oder dem Leeren der Browser-Daten lässt sich dieses Ticket nicht wieder öffnen – bitte selbst eine Kopie aufbewahren.",
    "Gesichert, zurück", "Kopieren", "Kopiert", "Antworten aktualisieren", "Ticket", "Zurück zur Liste", "Löschen",
    "Dieses Ticket löschen? Der Support behält den Verlauf, es verschwindet nur aus deiner Liste.", "Nachfassen",
    "Ergänzung oder Frage …", "Senden", "Neue Antwort", "Antworten",
    "In diesem Browser sind noch keine Tickets gespeichert", "Ich", "Support", "Lädt …",
    "Konnte nicht geladen werden, bitte später erneut versuchen",
    "Abfrageschlüssel fehlt, dieses Ticket kann nicht geöffnet werden (Browser-Daten gelöscht oder Gerät gewechselt)",
    "Noch keine Antwort; Antworten des Supports erscheinen hier"],

"es_es": ["Mis tickets", "Ver respuestas y ampliar (el número y la clave se guardan en este navegador)",
    "Número de ticket", "Clave de consulta",
    "El número y la clave solo están guardados en este navegador. Si cambias de dispositivo o borras los datos del navegador no podrás volver a abrir este ticket: guarda una copia.",
    "Ya lo guardé", "Copiar", "Copiado", "Actualizar respuestas", "Ticket", "Volver a la lista", "Eliminar",
    "¿Eliminar este ticket? Soporte conserva el historial; solo desaparece de tu lista.", "Ampliar información",
    "Añade detalles o una pregunta…", "Enviar", "Nueva respuesta", "respuestas",
    "Todavía no hay tickets guardados en este navegador", "Yo", "Soporte", "Cargando…",
    "No se pudo cargar, inténtalo más tarde",
    "Falta la clave de consulta y no se puede abrir este ticket (borraste los datos del navegador o cambiaste de dispositivo)",
    "Aún no hay respuestas; las de Soporte aparecerán aquí"],

"ru_ru": ["Мои тикеты", "Читать ответы и дополнять (номер и ключ хранятся в этом браузере)",
    "Номер тикета", "Ключ доступа",
    "Номер и ключ сохраняются только в этом браузере. После смены устройства или очистки данных браузера тикет больше не открыть — сделайте копию сами.",
    "Сохранил, назад", "Копировать", "Скопировано", "Обновить ответы", "Тикет", "К списку", "Удалить",
    "Удалить этот тикет? У поддержки запись останется, исчезнет только из вашего списка.", "Дополнить",
    "Уточнение или вопрос…", "Отправить", "Новый ответ", "ответов",
    "В этом браузере тикеты ещё не сохранены", "Я", "Поддержка", "Загрузка…",
    "Не удалось загрузить, попробуйте позже",
    "Нет ключа доступа — тикет не открыть (данные браузера очищены или устройство заменено)",
    "Ответов пока нет; ответы поддержки появятся здесь"],

"pt_br": ["Meus chamados", "Ver respostas e complemento (número e chave ficam neste navegador)",
    "Número do chamado", "Chave de consulta",
    "O número e a chave ficam salvos apenas neste navegador. Se trocar de aparelho ou limpar os dados do navegador, não será possível abrir o chamado de novo — guarde uma cópia.",
    "Já salvei, voltar", "Copiar", "Copiado", "Atualizar respostas", "Chamado", "Voltar à lista", "Excluir",
    "Excluir este chamado? A equipe mantém o registro; ele só some da sua lista.", "Complementar",
    "Acrescente detalhes ou uma pergunta…", "Enviar", "Nova resposta", "respostas",
    "Nenhum chamado salvo neste navegador ainda", "Eu", "Equipe", "Carregando…",
    "Falha ao carregar, tente mais tarde",
    "Chave de consulta ausente, não é possível abrir este chamado (dados do navegador limpos ou aparelho trocado)",
    "Ainda sem respostas; as da equipe aparecem aqui"],

"ar_sa": ["تذاكري", "قراءة الردود والمتابعة (الرقم ومفتاح الاستعلام محفوظان في هذا المتصفح)",
    "رقم التذكرة", "مفتاح الاستعلام",
    "الرقم والمفتاح محفوظان في هذا المتصفح فقط. بعد تغيير الجهاز أو مسح بيانات المتصفح لن يمكنك فتح هذه التذكرة، فالرجاء الاحتفاظ بنسخة.",
    "حفظتها، رجوع", "نسخ", "تم النسخ", "تحديث الردود", "التذكرة", "العودة إلى القائمة", "حذف",
    "حذف هذه التذكرة؟ سيحتفظ الفريق بالسجل، وستختفي من قائمتك فقط.", "متابعة",
    "أضف تفاصيل أو سؤالًا…", "إرسال", "رد جديد", "ردود",
    "لا توجد تذاكر محفوظة في هذا المتصفح", "أنا", "الفريق", "جارٍ التحميل…",
    "تعذّر التحميل، حاول لاحقًا",
    "مفتاح الاستعلام مفقود، لا يمكن فتح هذه التذكرة (مسحت بيانات المتصفح أو غيّرت الجهاز)",
    "لا توجد ردود بعد؛ ردود الفريق ستظهر هنا"],

"lzh": ["吾之工單", "觀官覆而再問（單號與憑證藏於此瀏覽器）", "工單號", "查閱之憑",
    "單號與憑證唯藏於今之瀏覽器。易器、易覽或削其存檔，則此單不可復啟，宜自錄以為備。",
    "吾已錄之，返", "摹之", "已摹", "覆問更新", "工單", "返其列", "除之",
    "果除此單乎？官仍存其檔，特吾不復見耳。", "再問", "更述或問焉…", "遣之",
    "有新覆", "條覆", "此覽未嘗藏單", "吾", "官", "載之…",
    "載之不得，少頃再試",
    "無憑證，故是單不可啟（或削瀏覽器之存，或易其器）",
    "未得覆；官覆則見於此"],
}

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "server-go", "web", "lang")
changed, skipped = [], []
for lang, values in T.items():
    if len(values) != len(SUFFIX):
        print("FAIL %s: %d values vs %d keys" % (lang, len(values), len(SUFFIX)))
        sys.exit(1)
    path = os.path.join(root, lang + ".json")
    with io.open(path, encoding="utf-8") as f:
        data = json.load(f)
    add = {}
    for suf, val in zip(SUFFIX, values):
        key = "tk." + suf
        if key not in data:
            add[key] = val
    if not add:
        skipped.append(lang)
        continue
    data.update(add)
    out = json.dumps(data, ensure_ascii=False, indent=2)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(out + "\n")
    changed.append("%s(+%d)" % (lang, len(add)))

print("updated:", ", ".join(changed) or "none")
print("skipped:", ", ".join(skipped) or "none")
