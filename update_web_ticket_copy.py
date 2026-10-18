# -*- coding: utf-8 -*-
"""网页站内反馈改走工单后，同步更新 5 条既有文案（13 语言）。

原写"管理员会在后台看到"，现在提交会返回工单号+查询凭证、并可回来看回复与追问，
文案不改就是骗人。只覆盖这 5 个键，其它键不动。
"""
import io, json, os, sys

KEYS = ["fb.site_title", "fb.site_desc", "fb.form_title", "fb.success_msg", "fb.rate_note"]

V = {
"zh_cn": ["提交工单",
 "直接提交描述与附件（任意文件，单次共 500MB 以内），提交后会拿到工单号与查询凭证，官方回复可在「我的工单」查看并追问。",
 "提交工单", "已生成工单，官方回复后会显示在「我的工单」里。",
 "提示：为防灌水，同一 IP 每 10 分钟只能提交或追问一次。"],
"zh_tw": ["提交工單",
 "直接提交說明與附件（任意檔案，單次共 500MB 以內），提交後會拿到工單號與查詢憑證，官方回覆可在「我的工單」檢視並追問。",
 "提交工單", "已產生工單，官方回覆後會顯示在「我的工單」。",
 "提示：為防灌水，同一 IP 每 10 分鐘只能提交或追問一次。"],
"zh_hk": ["提交工單",
 "直接交說明同附件（任何檔案，單次最多 500MB），交完會俾工單號同查詢憑證，官方回覆可以去「我嘅工單」睇同追問。",
 "提交工單", "已經開咗工單，官方回覆會喺「我嘅工單」顯示。",
 "提示：為防灌水，同一 IP 每 10 分鐘淨係可以提交或追問一次。"],
"en_us": ["Submit a ticket",
 "Send a description and attachments (any files, up to 500MB total per submission). You get a ticket ID and a lookup key; staff replies can be read and followed up under \"My tickets\".",
 "Submit a ticket", "Your ticket is open. Staff replies will show up under \"My tickets\".",
 "Note: to prevent spam, each IP may submit or follow up once every 10 minutes."],
"ja_jp": ["チケットを投稿",
 "説明と添付ファイル（任意のファイル、1回あたり合計500MBまで）をそのまま送れます。投稿後にチケット番号と照合キーが表示され、「私のチケット」で返信の確認や追加投稿ができます。",
 "チケットを投稿", "チケットを作成しました。運営の返信は「私のチケット」に表示されます。",
 "注意：不正利用防止のため、同一IPでは10分に1回しか投稿・追加送信ができません。"],
"ko_kr": ["티켓 제출",
 "설명과 첨부 파일(임의 파일, 회당 총 500MB)을 바로 보낼 수 있습니다. 제출 후 티켓 번호와 조회 키가 표시되며 \"내 티켓\"에서 답변 확인과 추가 문의가 가능합니다.",
 "티켓 제출", "티켓이 생성되었습니다. 담당자 답변은 \"내 티켓\"에 표시됩니다.",
 "참고: 도배 방지를 위해 동일 IP는 10분마다 한 번만 제출·추가 문의할 수 있습니다."],
"fr_fr": ["Soumettre un ticket",
 "Envoyez description et pièces jointes (fichiers libres, 500 Mo au total par envoi). Vous recevrez une référence et une clé de consultation ; les réponses se lisent et se relancent dans « Mes tickets ».",
 "Soumettre un ticket", "Votre ticket est créé. Les réponses du staff s'afficheront dans « Mes tickets ».",
 "Remarque : anti-spam, un seul envoi ou relance par IP toutes les 10 minutes."],
"de_de": ["Ticket erstellen",
 "Beschreibung und Anhänge (beliebige Dateien, insgesamt bis 500 MB) direkt mitschicken. Du bekommst Nummer und Schlüssel; Antworten liegen unter „Meine Tickets“, wo du auch nachfassen kannst.",
 "Ticket erstellen", "Dein Ticket ist angelegt. Antworten des Supports erscheinen unter „Meine Tickets“.",
 "Hinweis: Spam-Schutz – pro IP nur alle 10 Minuten eine Nachricht oder Rückfrage."],
"es_es": ["Enviar un ticket",
 "Envía la descripción y los archivos (cualquier archivo, 500 MB en total por envío). Recibirás número y clave; las respuestas se ven y se amplían en «Mis tickets».",
 "Enviar un ticket", "Ticket creado. Las respuestas de Soporte aparecerán en «Mis tickets».",
 "Nota: anti-spam, cada IP puede enviar o ampliar una vez cada 10 minutos."],
"ru_ru": ["Создать тикет",
 "Отправьте описание и вложения (любые файлы, до 500 МБ суммарно). Вы получите номер и ключ; ответы видны в «Мои тикеты», там же можно дополнить.",
 "Создать тикет", "Тикет создан. Ответы поддержки появятся в разделе «Мои тикеты».",
 "Примечание: от спама — с одного IP не чаще одного сообщения в 10 минут."],
"pt_br": ["Abrir um chamado",
 "Envie a descrição e os arquivos (qualquer arquivo, até 500 MB por envio). Você recebe número e chave; as respostas ficam em \"Meus chamados\", onde também pode complementar.",
 "Abrir um chamado", "Chamado criado. As respostas da equipe aparecem em \"Meus chamados\".",
 "Nota: anti-spam, cada IP pode enviar ou complementar uma vez a cada 10 minutos."],
"ar_sa": ["إرسال تذكرة",
 "أرسل الوصف والمرفقات (أي ملفات، حتى 500 ميجابايت لكل إرسال). ستحصل على رقم ومفتاح، وستظهر الردود في «تذاكري» حيث يمكنك المتابعة.",
 "إرسال تذكرة", "تم إنشاء التذكرة. ردود الفريق ستظهر في «تذاكري».",
 "ملاحظة: لمنع الإزعاج، يمكن لكل عنوان إرسال رسالة أو متابعة كل 10 دقائق."],
"lzh": ["獻其工單",
 "具言其事，附以文牒（任意之檔，一獻毋過五百兆）；既獻則賜單號與憑，官覆可於「吾之工單」觀之，且可復問。",
 "獻其工單", "單已立矣，官覆則見於「吾之工單」。",
 "注：防濫其文，同一之址每十分鐘得獻或問一次。"],
}

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "server-go", "web", "lang")
for lang, vals in V.items():
    if len(vals) != len(KEYS):
        print("FAIL %s count" % lang); sys.exit(1)
    path = os.path.join(root, lang + ".json")
    with io.open(path, encoding="utf-8") as f:
        data = json.load(f)
    before = [data.get(k) for k in KEYS]
    for k, v in zip(KEYS, vals):
        if k not in data:
            print("FAIL %s 缺键 %s（应先存在再覆盖）" % (lang, k)); sys.exit(1)
        data[k] = v
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    print("%s: %d 条已更新" % (lang, sum(1 for a, b in zip(before, vals) if a != b)))
