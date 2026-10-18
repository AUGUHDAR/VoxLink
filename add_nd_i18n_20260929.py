# -*- coding: utf-8 -*-
"""国庆卡片 i18n：向 13 个语言文件注入 nd.* 键（已存在的键不覆盖）。"""
import io, json, os, sys

SUFFIX = ["title", "status_countdown", "status_today", "status_holiday", "greeting",
    "more", "modal_title", "anniv", "intro_1", "intro_2", "intro_3", "intro_4", "blessing"]

T = {
"zh_cn": ["国庆节", "距国庆还有 %d 天", "今天，国庆节！", "国庆假期第 %d 天", "祝节日快乐",
    "了解国庆节", "关于国庆节", "今年是中华人民共和国成立 %d 周年。",
    "1949年10月1日，北京天安门广场举行开国大典，毛泽东主席向全世界庄严宣告中华人民共和国中央人民政府成立，并亲手升起了第一面五星红旗，三十万军民共同见证了这一历史时刻。",
    "同年12月2日，中央人民政府委员会通过决议，规定每年10月1日为国庆日。国庆节与国旗、国徽、国歌一样，是国家的象征和标志。",
    "1999年起，国庆节与前后周末调休相连，形成约七天的「黄金周」长假；每年假期的具体安排，由国务院办公厅于上一年底统一公布。",
    "每逢国庆，天安门广场都会举行隆重的升国旗仪式；逢重要周年还会举行盛大阅兵式。全国各地悬挂国旗、张灯结彩，人们与家人团聚、外出游览，共度长假。",
    "祝愿祖国繁荣昌盛，祝大家节日快乐、平安喜乐！"],

"zh_tw": ["國慶節", "距國慶還有 %d 天", "今天，國慶節！", "國慶假期第 %d 天", "祝節日快樂",
    "認識國慶節", "關於國慶節", "今年是中華人民共和國成立 %d 週年。",
    "1949年10月1日，北京天安門廣場舉行開國大典，毛澤東主席向全世界莊嚴宣告中華人民共和國中央人民政府成立，並親手升起了第一面五星紅旗，三十萬軍民共同見證了這一歷史時刻。",
    "同年12月2日，中央人民政府委員會通過決議，規定每年10月1日為國慶日。國慶節與國旗、國徽、國歌一樣，是國家的象徵和標誌。",
    "1999年起，國慶節與前後週末調休相連，形成約七天的「黃金週」長假；每年假期的具體安排，由國務院辦公廳於上一年底統一公布。",
    "每逢國慶，天安門廣場都會舉行隆重的升國旗儀式；逢重要週年還會舉行盛大閱兵式。全國各地懸掛國旗、張燈結綵，人們與家人團聚、外出遊覽，共度長假。",
    "祝願祖國繁榮昌盛，祝大家節日快樂、平安喜樂！"],

"zh_hk": ["國慶節", "距國慶仲有 %d 日", "今日，國慶節！", "國慶假期第 %d 日", "祝節日快樂",
    "認識國慶節", "關於國慶節", "今年係中華人民共和國成立 %d 週年。",
    "1949年10月1日，北京天安門廣場舉行開國大典，毛澤東主席向全世界莊嚴宣告中華人民共和國中央人民政府成立，並親手升起第一面五星紅旗，三十萬軍民共同見證呢個歷史時刻。",
    "同年12月2日，中央人民政府委員會通過決議，規定每年10月1日為國慶日。國慶節同國旗、國徽、國歌一樣，係國家嘅象徵同標誌。",
    "1999年起，國慶節同前後週末調休相連，形成大約七日嘅「黃金週」長假；每年假期嘅具體安排，由國務院辦公廳喺上一年底統一公佈。",
    "每逢國慶，天安門廣場都會舉行隆重嘅升國旗儀式；逢重要週年仲會舉行盛大閱兵式。全國各地懸掛國旗、張燈結綵，人們同屋企人團聚、出外遊覽，共度長假。",
    "祝願祖國繁榮昌盛，祝大家節日快樂、平安喜樂！"],

"en_us": ["National Day", "%d days until National Day", "Today — National Day!", "Day %d of the National Day holiday", "Happy holidays!",
    "About National Day", "About National Day", "This year marks the %dth anniversary of the founding of the People's Republic of China.",
    "On October 1, 1949, the founding ceremony was held at Tiananmen Square in Beijing, where Chairman Mao Zedong proclaimed the founding of the Central People's Government of the People's Republic of China and raised the first five-star red flag, witnessed by 300,000 people.",
    "On December 2 of the same year, the Central People's Government Committee resolved that October 1 would be the National Day each year. Like the national flag, emblem and anthem, it is a foremost symbol of the state.",
    "Since 1999, the National Day has been joined with adjacent weekends into a \"Golden Week\" holiday of about seven days; each year's exact schedule is announced by the State Council in the preceding winter.",
    "Every National Day, a grand flag-raising ceremony is held at Tiananmen Square, and grand military parades mark milestone years. Cities are decorated with flags and lanterns as people gather with family or travel during the long holiday.",
    "May the motherland prosper, and happy holidays to everyone!"],

"ja_jp": ["国慶節", "国慶節まであと %d 日", "今日は国慶節！", "国慶節連休の %d 日目", "祝日をお楽しみください",
    "国慶節について", "国慶節とは", "今年は中華人民共和国成立 %d 周年です。",
    "1949年10月1日、北京の天安門広場で開国大典が挙行され、毛沢東主席が中華人民共和国中央人民政府の成立を世界に厳かに宣言し、最初の五星紅旗を掲げました。30万人の軍民がこの歴史的瞬間を見守りました。",
    "同年12月2日、中央人民政府委員会は決議を採択し、毎年10月1日を国慶節と定めました。国旗・国章・国歌と並ぶ国家の象徴です。",
    "1999年以降、国慶節は前後の週末とあわせて約7日間の「ゴールデンウィーク」連休となります。毎年の詳細な日程は国務院弁公庁が前年末に発表します。",
    "国慶節のたびに天安門広場では荘厳な国旗掲揚式が行われ、節目の周年には大規模な軍事パレードも開催されます。各地では国旗や提灯で飾られ、人々は家族と団らんしたり旅に出たりして連休を楽しみます。",
    "祖国の繁栄を祈り、皆さまの楽しい休日をお祈りします！"],

"ko_kr": ["국경절", "국경절까지 %d일", "오늘은 국경절!", "국경절 연휴 %d일째", "즐거운 명절 보내세요",
    "국경절 알아보기", "국경절 소개", "올해는 중화인민공화국 건국 %d주년입니다.",
    "1949년 10월 1일 베이징 톈안먼 광장에서 건국 대전이 거행되었고, 마오쩌둥 주석이 중화인민공화국 중앙인민정부의 성립을 전 세계에 장엄히 선포하고 첫 오성홍기를 게양했습니다. 30만 군민이 이 역사적 순간을 함께했습니다.",
    "같은 해 12월 2일 중앙인민정부 위원회는 결의를 채택해 매년 10월 1일을 국경일로 정했습니다. 국기·국장·국가와 함께 국가의 상징입니다.",
    "1999년부터 국경절은 전후 주말과 연결되어 약 7일간의 '황금주간' 연휴가 됩니다. 매년 구체적인 일정은 국무원 판공청이 전년도 말에 발표합니다.",
    "국경절마다 톈안먼 광장에서 성대한 국기 게양식이 열리고, 의미 있는 주년에는 대규모 열병식도 열립니다. 전국 각지가 국기와 등불로 장식되고 사람들은 가족과 함께 여행하며 연휴를 보냅니다.",
    "조국의 번영을 기원하며, 모두 즐겁고 평안한 연휴 보내시길 바랍니다!"],

"fr_fr": ["Fête nationale", "%d jours avant la fête nationale", "Aujourd'hui — fête nationale !", "Jour %d des vacances de la fête nationale", "Bonnes fêtes !",
    "Découvrir la fête nationale", "À propos de la fête nationale", "Cette année marque le %de anniversaire de la fondation de la République populaire de Chine.",
    "Le 1er octobre 1949, la cérémonie de fondation s'est tenue place Tiananmen à Pékin : le président Mao Zedong a proclamé solennellement la fondation du Gouvernement populaire central de la République populaire de Chine et hissé le premier drapeau rouge aux cinq étoiles, devant 300 000 personnes.",
    "Le 2 décembre de la même année, le Comité du Gouvernement populaire central a adopté une résolution instituant le 1er octobre comme fête nationale. Avec le drapeau, l'emblème et l'hymne, c'est l'un des symboles majeurs de l'État.",
    "Depuis 1999, la fête nationale s'articule avec les week-ends voisins pour former les « vacances dorées » d'environ sept jours ; le calendrier précis de chaque année est publié par le Bureau général du Conseil des affaires d'État à la fin de l'année précédente.",
    "À chaque fête nationale, une grande cérémonie de lever du drapeau se tient place Tiananmen, et les anniversaires marquants sont célébrés par de grands défilés militaires. Partout, drapeaux et lanternes décorent les villes tandis que chacun retrouve sa famille ou part en voyage.",
    "Vive la prospérité de la patrie, et bonnes fêtes à tous !"],

"de_de": ["Nationalfeiertag", "Noch %d Tage bis zum Nationalfeiertag", "Heute ist Nationalfeiertag!", "%d. Tag der Nationalfeiertagsferien", "Frohe Feiertage!",
    "Zum Nationalfeiertag", "Über den Nationalfeiertag", "In diesem Jahr jährt sich die Gründung der Volksrepublik China zum %d. Mal.",
    "Am 1. Oktober 1949 fand auf dem Platz des Himmlischen Friedens in Peking die Staatsgründungszeremonie statt: Vorsitzender Mao Zedong verkündete feierlich die Gründung der Zentralvolksregierung der Volksrepublik China und hisste die erste rote Fahne mit fünf Sternen, Zeuge waren 300 000 Menschen.",
    "Am 2. Dezember desselben Jahres beschloss das Komitee der Zentralvolksregierung, den 1. Oktober jedes Jahres zum Nationalfeiertag zu erklären. Wie Flagge, Wappen und Hymne ist er ein wichtiges Staatssymbol.",
    "Seit 1999 verbindet der Nationalfeiertag mit den umliegenden Wochenenden zu den etwa siebentägigen „Goldenen Ferien“; der genaue Kalender jedes Jahres wird vom Generalsekretariat des Staatsrates am Ende des Vorjahres veröffentlicht.",
    "Zu jedem Nationalfeiertag findet auf dem Platz des Himmlischen Friedens eine feierliche Flaggenzeremonie statt; zu runden Jahrestagen gibt es große Militärparaden. Überall schmücken Flaggen und Laternen die Städte, und die Menschen verbringen die Ferien mit Familie oder auf Reisen.",
    "Auf Wohl und Wohlergehen des Vaterlandes — und frohe Feiertage für alle!"],

"es_es": ["Fiesta Nacional", "%d días hasta la Fiesta Nacional", "¡Hoy es la Fiesta Nacional!", "Día %d de las vacaciones de la Fiesta Nacional", "¡Felices fiestas!",
    "Conocer la Fiesta Nacional", "Sobre la Fiesta Nacional", "Este año se cumple el %d.º aniversario de la fundación de la República Popular China.",
    "El 1 de octubre de 1949 se celebró en la plaza de Tiananmén, en Pekín, la ceremonia de fundación: el presidente Mao Zedong proclamó solemnemente la fundación del Gobierno Popular Central de la República Popular China e izó la primera bandera roja de cinco estrellas, ante 300 000 personas.",
    "El 2 de diciembre de ese mismo año, el Comité del Gobierno Popular Central aprobó una resolución que estableció el 1 de octubre como fiesta nacional. Junto a la bandera, el emblema y el himno, es uno de los grandes símbolos del Estado.",
    "Desde 1999, la Fiesta Nacional se une a los fines de semana cercanos y forma las «vacaciones doradas» de unos siete días; el calendario exacto de cada año lo publica la Oficina General del Consejo de Estado a finales del año anterior.",
    "En cada Fiesta Nacional se celebra una solemne ceremonia de izado de bandera en Tiananmén, y los aniversarios señalados se conmemoran con grandes desfiles militares. Las ciudades se decoran con banderas y farolillos, y la gente disfruta de las vacaciones con su familia o viajando.",
    "¡Que prospere la patria y felices fiestas a todos!"],

"ru_ru": ["Национальный праздник", "До Национального праздника %d дн.", "Сегодня — Национальный праздник!", "%d-й день праздничных каникул", "С праздником!",
    "О Национальном празднике", "О Национальном празднике", "В этом году исполняется %d лет со дня образования Китайской Народной Республики.",
    "1 октября 1949 года на площади Тяньаньмэнь в Пекине состоялась церемония провозглашения: председатель Мао Цзэдун торжественно объявил миру об образовании Центрального народного правительства Китайской Народной Республики и поднял первое красное знамя с пятью звёздами. Свидетелями стали 300 тысяч человек.",
    "2 декабря того же года Комитет Центрального народного правительства принял резолюцию, объявившую 1 октября национальным праздником. Как и флаг, герб и гимн, он является важнейшим символом государства.",
    "С 1999 года праздник вместе с соседними выходными образует «золотую неделю» длиной около семи дней; точный график каждого года публикуется Канцелярией Госсовета в конце предыдущего года.",
    "В этот день на площади Тяньаньмэнь проходит торжественная церемония подъёма флага, а в юбилейные годы — крупные военные парады. Города украшаются флагами и фонарями, люди проводят каникулы с семьёй или в путешествиях.",
    "Процветания Родине и с праздником всех!"],

"pt_br": ["Dia Nacional", "%d dias até o Dia Nacional", "Hoje é o Dia Nacional!", "Dia %d do feriado do Dia Nacional", "Boas festas!",
    "Conheça o Dia Nacional", "Sobre o Dia Nacional", "Este ano marca o %d.º aniversário da fundação da República Popular da China.",
    "Em 1.º de outubro de 1949, foi realizada na Praça da Paz Celestial, em Pequim, a cerimônia de fundação: o presidente Mao Zedong proclamou solenemente a fundação do Governo Popular Central da República Popular da China e içou a primeira bandeira vermelha de cinco estrelas, diante de 300 mil pessoas.",
    "Em 2 de dezembro do mesmo ano, o Comitê do Governo Popular Central aprovou resolução instituindo 1.º de outubro como Dia Nacional. Assim como a bandeira, o emblema e o hino, é um dos principais símbolos do Estado.",
    "Desde 1999, o Dia Nacional se une aos fins de semana próximos, formando a “Semana Dourada” de cerca de sete dias; o calendário exato de cada ano é divulgado pela Secretaria-Geral do Conselho de Estado no fim do ano anterior.",
    "A cada Dia Nacional, realiza-se na Praça da Paz Celestial uma solene cerimônia de bandeira, e aniversários marcantes têm grandes desfiles militares. As cidades se enfeitam com bandeiras e lanternas, e as pessoas aproveitam o feriado com a família ou viajando.",
    "Que a pátria prospere, e boas festas a todos!"],

"ar_sa": ["اليوم الوطني", "%d يومًا حتى اليوم الوطني", "اليوم هو اليوم الوطني!", "اليوم %d من عطلة اليوم الوطني", "أعياد سعيدة!",
    "تعرّف على اليوم الوطني", "عن اليوم الوطني", "يصادف هذا العام الذكرى السنوية الـ%d لتأسيس جمهورية الصين الشعبية.",
    "في الأول من أكتوبر 1949 أُقيت مراسم التأسيس في ساحة تيانآنمن ببكين، حيث أعلن رئيس اللجنة ماو تسي تونغ رسميًا للعالم تأسيس الحكومة الشعبية المركزية لجمهورية الصين الشعبية ورفع أول علم أحمر بخمس نجوم، أمام ثلاثمائة ألف شخص.",
    "وفي الثاني من ديسمبر من العام نفسه، تبنّت لجنة الحكومة الشعبية المركزية قرارًا باعتماد الأول من أكتوبر يومًا وطنيًا كل عام، وهو إلى جانب العلم والشعار والنشيد من أبرز رموز الدولة.",
    "منذ عام 1999 يرتبط اليوم الوطني بعطلات نهاية الأسبوع المجاورة مكوّنًا «الأسبوع الذهبي» الذي يدوم نحو سبعة أيام؛ ويُعلن الجدول الدقيق لكل عام من المكتب العام لمجلس الدولة في نهاية العام السابق.",
    "في كل يوم وطني تُقام في ساحة تيانآنمن مراسم مهيبة لرفع العلم، وتُقام عروض عسكرية كبرى في الذكرى المهمة. وتُزين المدن بالأعلام والفوانيس، ويقضي الناس العطلة مع أسرهم أو في السفر.",
    "فلتنعم الوطن بالازدهار، وأعياد سعيدة للجميع!"],

"lzh": ["國慶之節", "距國慶尚 %d 日", "今日，國慶也！", "國慶休沐之第 %d 日", "恭賀佳節",
    "知國慶之由", "國慶節考", "今歲，中華人民共和國開國 %d 周年也。",
    "西曆一千九百四十九年十月初一日，開國大典行於北京天安門。毛澤東主席昭告天下：中華人民共和國中央人民政府立矣，親升五星紅旗，軍民三十萬共睹焉。",
    "是年十二月二日，中央人民政府委員會議決：以每年十月一日為國慶日。與國旗、國徽、國歌同為國之表徵。",
    "自西曆一千九百九十九年，國慶連前後休沐，成「黃金周」之長假，約七日；每歲安排，皆國務院先歲之杪頒之。",
    "每值國慶，天安門必行升旗之典，逢整壽則有大閱。海內懸旗結綵，士民或團圓於家，或遊歷於外，同慶休沐。",
    "願邦國興盛，諸君節日懌樂，歲歲平安！"],
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
        key = "nd." + suf
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
