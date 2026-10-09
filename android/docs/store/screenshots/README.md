# Screenshot-Kit für Google Play und App Store

Version 16.2.2 (Build 17). Dieses Kit enthält alles, um die Store-Screenshots in allen Sprachen gleich aussehen zu lassen:

| Datei | Zweck |
|---|---|
| `demo-backup.json` | Demo-Daten zum Wiederherstellen (Android und iOS) |
| `captions.json` | Titel (≤ 30 Zeichen, hart max. 40) und Untertitel (≤ 60 Zeichen) pro Szene, 31 Sprachen (inkl. pt-BR) – Eingabe für das Rahmen-Skript; dieselben Texte als Tabellen unten in «Beschriftungen je Sprache» |
| `../../../tools/frame_screenshots.py` | setzt Hintergrund, Text und Screenshot zum fertigen Store-Bild zusammen |
| `raw/<locale>/<NN>.png` | deine rohen Screenshots (nicht im Repo) |
| `out/<plattform>/<locale>/<NN>.png` | fertige Bilder (nicht im Repo) |

## Die 5 Szenen – die Geschichte

Leitidee aller Store-Texte: **«Crypto Checker erklärt dir, was im Markt passiert.»** Die Bilder erzählen
das in fünf Schritten – erst verstehen, dann eigene Auswahl, Alarme und ein geschütztes Portfolio.

| Nr. | Titel (de-DE) | Szene | Was zu sehen ist |
|---|---|---|---|
| 01 | Den Markt verstehen, ohne Lärm | Merkliste | Tab «Merkliste»: BTC/USDT, ETH/USDT, SOL/USDT (Binance), zweite Zeile «≈ … CHF», Mini-Chart in jeder Zeile, Gruppe «Layer 1» |
| 02 | Warum bewegt sich das? | «Warum bewegt sich das?» | Bei BTC oder SOL «Warum bewegt sich das?» öffnen, «Kurz gesagt» (Kurzfazit) oben im Bild |
| 03 | Deine Merkliste, deine Börsen | Seite «Paar hinzufügen» | In der Merkliste «+» neben der Lupe tippen: Börsenauswahl mit Spot und Futures (41 Märkte auf 32 Börsen, DEX-Token über DexScreener) |
| 04 | Alarme, wenn es zählt | Alarm-Editor | Neuen Alarm für BTC/USDT öffnen: Satz «Wenn BTC unter 90 000 geht», darüber die Schnell-Alarme (+1 %, −5 %, 30-Tage-Hoch …) |
| 05 | Dein Portfolio – geschützt | Portfolio mit Sperre | Vorher Einstellungen → Portfolio-Sperre einschalten (die Demo-Sicherung lässt sie aus). Dann Tab «Portfolio»: entweder der Sperr-Hinweis «Portfolio ist gesperrt» oder nach dem Entsperren 0,05 BTC, 1,2 ETH, 10 SOL mit Gesamtwert in CHF |

Hinweis zu Szene 05: Neben der **Portfolio-Sperre** (Fingerabdruck, Gesicht oder Geräte-PIN) hat das Portfolio jetzt
auch das **Auge** oben rechts («Beträge verbergen»: Beträge als «•••», Prozente bleiben; auch im Portfolio-Widget).
Szene 05 darf das Auge zeigen – etwa entsperrt mit verborgenen Beträgen. Die Untertitel nennen weiterhin nur die
Sperre und bleiben unverändert.

Crypto Pulse und die Widgets haben keine eigene Szene mehr. Wer mehr als fünf Bilder will (Play erlaubt 8,
App Store 10), kann sie als `06.png`/`07.png` anhängen und in `captions.json` eigene Texte ergänzen.

Dateinamen: `01.png` … `05.png`, pro Sprache ein Ordner, z. B. `raw/de-DE/01.png`. Als Ordnernamen gehen die Namen aus `fastlane/metadata/android` (z. B. `de-DE`, `iw-IL`, `zh-CN`) und die App-Store-Namen (z. B. `he`, `zh-Hans`, `ar-SA`); das Skript ordnet sie selbst zu.

## Vorbereitung

### 1. Demo-Daten wiederherstellen

1. `demo-backup.json` aufs Gerät kopieren (Android: z. B. `adb push demo-backup.json /sdcard/Download/`; iOS-Simulator: Datei ins Simulator-Fenster ziehen oder in «Dateien» ablegen).
2. In der App: Einstellungen → Sichern & Wiederherstellen → Wiederherstellen → Datei wählen.

Achtung: Die Wiederherstellung **ersetzt** Merkliste, Alarme, Favoriten, Portfolio und Einstellungen. Nur auf einem Test-Gerät oder Simulator machen.

Inhalt der Sicherung:

- Merkliste: BTC/USDT, ETH/USDT, SOL/USDT auf Binance (Börsen-Schlüssel `Binance`, Paar-Id `BTCUSDT` usw.), alle in der Gruppe «Layer 1»
- Notiz bei BTC: «Ø 77 500 USDT» (sprachneutral)
- Alarm: BTC/USDT unter 90 000 CHF (Alarmwährung CHF)
- Portfolio: Kauf 0,05 BTC zu 77 500 USDT (8.4.2025), 1,2 ETH zu 2400 USDT (20.6.2025), 10 SOL zu 165 USDT (1.8.2025)
- Einstellungen: Umrechnungswährung CHF, umgerechnete Kurse an, Mini-Chart an, Portfolio an, heller Modus, Akzent Orange, Kursfarben Grün/Rot, keine dauernde Kurs-Mitteilung, Portfolio-Sperre aus, Nachtruhe aus

«Farben tauschen» ist bewusst **nicht** in der Sicherung: In China, Japan, Korea und Taiwan bleibt so die Gerätevorgabe (Rot steigend) erhalten. Wer für `ja-JP`, `ko-KR` und `zh-CN` die ostasiatischen Farben zeigen will, prüft das in den Einstellungen.

Kurse kommen live von Binance. Binance sperrt Anfragen aus den USA – Geräte oder Simulatoren mit US-IP zeigen dann keine Kurse. In dem Fall über ein Netz ausserhalb der USA aufnehmen.

### 2. Umrechnungswährung und Darstellung prüfen

- Einstellungen → Umrechnungswährung: **CHF**, «Umgerechnete Kurse» an (kommt aus der Sicherung, trotzdem kurz prüfen).
- Heller Modus (Sicherung setzt ihn; Systemeinstellung des Geräts ebenfalls hell).
- Akzentfarbe **Orange** (Einstellungen → Darstellung; kommt aus der Sicherung). Orange ist die Markenfarbe – siehe «Markenfarbe» unten.
- Gerätesprache auf die jeweilige Sprache stellen, App neu starten.
- Keine echten Benachrichtigungen, keine privaten Daten im Bild.

### 3. Statusleiste aufräumen

**Android (Emulator oder Gerät mit Entwickleroptionen):**

```sh
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile show -e datatype none -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
# Aufnahme:
adb exec-out screencap -p > raw/de-DE/01.png
# danach:
adb shell am broadcast -a com.android.systemui.demo -e command exit
```

**iOS-Simulator:**

```sh
xcrun simctl status_bar booted override --time 9:41 --dataNetwork wifi --wifiMode active --wifiBars 3 \
  --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100
xcrun simctl io booted screenshot raw/de-DE/01.png
xcrun simctl status_bar booted clear
```

Sprache im Simulator: `xcrun simctl spawn booted defaults write -g AppleLanguages -array de-CH` (danach App neu starten) oder in den Einstellungen des Simulators.

## Grössen

Das Skript erzeugt die Zielgrössen selbst; die rohen Screenshots sollten mindestens so scharf sein wie das Ziel (am besten direkt vom passenden Gerät/Simulator).

| Store | Plattform-Name im Skript | Grösse (px) | Pflicht? |
|---|---|---|---|
| Google Play – Telefon | `play-phone` | 1080 × 1920 (9:16, kurze Seite ≥ 1080) | ja, 2–8 Bilder |
| Google Play – Tablet 7" | `play-tablet7` | 1200 × 1920 | optional |
| Google Play – Tablet 10" | `play-tablet10` | 1600 × 2560 | optional |
| App Store – iPhone 6,9" | `ios-iphone69` | 1320 × 2868 | ja |
| App Store – iPad 13" | `ios-ipad13` | 2064 × 2752 | nein (App vorerst nur iPhone) |

Rohe Aufnahmen dafür: Android-Telefon (z. B. Pixel-Emulator 1080 × 2400), iPhone 16 Pro Max / 17 Pro Max Simulator (1320 × 2868), iPad Pro 13" Simulator (2064 × 2752). Die fertigen PNGs haben keinen Alpha-Kanal (App Store lehnt Transparenz ab).

## Rahmen setzen

```sh
cd cryptoChecker
python3 -m pip install Pillow          # einmalig; Wheels von PyPI enthalten libraqm
python3 tools/frame_screenshots.py                                    # alle Sprachen in raw/, Telefon + iPhone 6,9"
python3 tools/frame_screenshots.py --platforms play-phone play-tablet10 ios-iphone69 ios-ipad13
python3 tools/frame_screenshots.py --locales de-DE en-US ar           # nur einzelne Sprachen
python3 tools/frame_screenshots.py --font /pfad/NotoSans-Bold.ttf     # eigene Schrift
python3 tools/frame_screenshots.py --align center                     # Text zentriert statt bündig
```

- Schrift: automatisch über fontconfig – Inter (Latein, Griechisch, Kyrillisch), Noto Sans CJK (ja/ko/zh), Noto Sans Arabic/Hebrew/Devanagari/Thai, wenn installiert, sonst DejaVu Sans, FreeSans bzw. Loma. Fehlt fontconfig (macOS, Windows), `--font` angeben.
- Rechts-nach-links: Bei `ar`, `fa`, `iw-IL`/`he` steht der Text rechtsbündig und wird mit libraqm korrekt gesetzt.
- Lange Titel werden erst verkleinert, dann auf zwei Zeilen umbrochen.
- Texte ändern: `captions.json` bearbeiten (Titel ≤ 30, hart max. 40, Untertitel ≤ 60 Zeichen) und die Tabelle unten nachziehen.

## Markenfarbe

Orange ist die Erkennungsfarbe von Crypto Checker: App-Icon (Glocke), Standard-Akzent bei neuen Installationen, Rahmen-Hintergrund von `frame_screenshots.py` (#DD6F48 → #BE532C) und die Feature-Grafiken in `docs/store/`. **Alle Marketing-Bilder** (Screenshots, Feature-Grafik, Promo-Bilder) zeigen deshalb den Akzent Orange und das orange Icon.

Blau, Grün, Rot und Marrs Green sind optionale Themes, die man in der App wählen kann. Sie können im Beschreibungstext erwähnt werden, gehören aber nicht in die Store-Bilder. Die alten blauen Globus-Grafiken sind nicht mehr Teil der Marke und dürfen nicht verwendet werden.

## Inhaltliche Regeln

- **Keine Börsen-Logos** und keine Geräterahmen echter Marken. Börsennamen nur als Text, so wie die App sie zeigt.
- Nur Funktionen zeigen, die der Build wirklich hat. Vor der Aufnahme prüfen, ob Crypto Pulse, «Alarm testen», das Kurzfazit bei «Warum bewegt sich das?» und (iOS) die Live-Aktivität in genau diesem Build vorhanden sind – sonst Szene weglassen oder ersetzen.
- Beobachtend formulieren: keine «Kaufsignale», «Trading-Chancen» oder Gewinnversprechen. Crypto Pulse und der Markt-Tab zeigen den Markt, sie sagen nichts voraus.
- Keine Spenden-Adressen, keine echten Bestände, keine persönlichen Mitteilungen im Bild.

## Beschriftungen je Sprache

Gleicher Inhalt wie `captions.json` (bei Änderungen beide Stellen anpassen). Titel ≤ 30 Zeichen,
Untertitel ≤ 60 Zeichen. Untertitel 01, 02 und 04 stammen aus dem bisherigen Kit (Merkliste, Kurzfazit, Alarm-Satz).

### ar – Arabisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | افهم السوق بلا ضجيج | مباشرةً من المنصة، وبالفرنك السويسري، مع مخطط مصغّر |
| 02 | لماذا يتحرك هذا؟ | الحجم والسوق ككل والعقود الآجلة – باختصار |
| 03 | قائمتك ومنصاتك | 41 سوقًا على 32 منصة، ومعها رموز DEX |
| 04 | تنبيهات عندما يهم الأمر | «أبلغني عندما ينخفض BTC تحت 90,000 CHF» |
| 05 | محفظتك – محمية | قفل بالبصمة أو الوجه أو رمز PIN – بلا حساب |

### cs-CZ – Tschechisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Pochopit trh bez šumu | Přímo z burzy, i v CHF, s minigrafem |
| 02 | Proč se to hýbe? | Objem, celý trh a futures – ve zkratce |
| 03 | Váš seznam, vaše burzy | 41 trhů na 32 burzách a k tomu DEX tokeny |
| 04 | Alarmy, když na tom záleží | „Upozornit, až BTC klesne pod 90 000 CHF“ |
| 05 | Vaše portfolio – chráněné | Zámek otiskem prstu, obličejem nebo PIN – bez účtu |

### da-DK – Dänisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Forstå markedet uden støj | Direkte fra børsen, også i CHF, med minigraf |
| 02 | Hvorfor bevæger den sig? | Volumen, hele markedet og futures – kort fortalt |
| 03 | Din liste, dine børser | 41 markeder på 32 børser, plus DEX-tokens |
| 04 | Alarmer, når det gælder | »Giv mig besked, når BTC falder under 90.000 CHF« |
| 05 | Din portefølje – beskyttet | Lås med fingeraftryk, ansigt eller PIN – uden konto |

### de-DE – Deutsch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Den Markt verstehen, ohne Lärm | Direkt von der Börse, auch in CHF – mit Mini-Chart |
| 02 | Warum bewegt sich das? | Volumen, Gesamtmarkt und Futures – kurz gesagt |
| 03 | Deine Merkliste, deine Börsen | 41 Märkte auf 32 Börsen, dazu DEX-Token |
| 04 | Alarme, wenn es zählt | «Wenn BTC unter 90 000 geht» + Schnell-Alarme |
| 05 | Dein Portfolio – geschützt | Sperre mit Fingerabdruck, Gesicht oder PIN – ohne Konto |

### el-GR – Griechisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Η αγορά, χωρίς θόρυβο | Από το ανταλλακτήριο, και σε CHF, με μίνι γράφημα |
| 02 | Γιατί κινείται; | Όγκος, συνολική αγορά και futures – με λίγα λόγια |
| 03 | Η λίστα σου, οι αγορές σου | 41 αγορές σε 32 ανταλλακτήρια, μαζί με DEX tokens |
| 04 | Ειδοποιήσεις όταν μετράει | «Ειδοποίηση όταν το BTC πέσει κάτω από 90.000 CHF» |
| 05 | Το χαρτοφυλάκιό σου – ασφαλές | Κλείδωμα με δακτυλικό αποτύπωμα, πρόσωπο ή PIN |

### en-US – Englisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | The market, without the noise | Straight from the exchange, also in CHF, with mini charts |
| 02 | Why is this moving? | Volume, overall market and futures – in short |
| 03 | Your watchlist, your exchanges | 41 markets on 32 exchanges, plus DEX tokens |
| 04 | Alarms when it matters | “Tell me when BTC falls below 90,000 CHF” |
| 05 | Your portfolio – protected | Lock with fingerprint, face or PIN – no account |

### es-ES – Spanisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Entiende el mercado sin ruido | Directos del exchange, también en CHF, con minigráfico |
| 02 | ¿Por qué se mueve? | Volumen, mercado global y futuros, en resumen |
| 03 | Tu lista, tus exchanges | 41 mercados en 32 exchanges, y tokens DEX |
| 04 | Alarmas cuando importa | «Avísame cuando BTC baje de 90.000 CHF» |
| 05 | Tu cartera, protegida | Bloqueo con huella, rostro o PIN, sin cuenta |

### fa – Persisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | بازار را بی‌هیاهو بفهمید | مستقیم از صرافی، به CHF هم، با نمودار کوچک |
| 02 | چرا این حرکت می‌کند؟ | حجم، کل بازار و فیوچرز – به‌طور خلاصه |
| 03 | فهرست شما، صرافی‌های شما | ۴۱ بازار در ۳۲ صرافی، به‌همراه توکن‌های DEX |
| 04 | هشدار، وقتی مهم است | «وقتی BTC از ۹۰٬۰۰۰ CHF پایین‌تر رفت، خبرم کن» |
| 05 | پرتفوی شما – محافظت‌شده | قفل با اثر انگشت، چهره یا PIN – بدون حساب |

### fi-FI – Finnisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Ymmärrä markkinat ilman hälyä | Suoraan pörssistä, myös CHF:nä, minikaaviolla |
| 02 | Miksi tämä liikkuu? | Volyymi, koko markkina ja futuurit – lyhyesti |
| 03 | Sinun listasi, sinun pörssisi | 41 markkinaa 32 pörssissä sekä DEX-tokenit |
| 04 | Hälytykset, kun sillä on väliä | ”Ilmoita, kun BTC laskee alle 90 000 CHF” |
| 05 | Salkkusi – suojattu | Lukitus sormenjäljellä, kasvoilla tai PIN-koodilla |

### fr-FR – Französisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Comprendre le marché au calme | Direct des plateformes, aussi en CHF, avec mini-graphique |
| 02 | Pourquoi ça bouge ? | Volume, marché global et futures – en bref |
| 03 | Votre liste, vos plateformes | 41 marchés sur 32 plateformes, et les tokens DEX |
| 04 | Des alertes quand ça compte | « Me prévenir quand BTC passe sous 90 000 CHF » |
| 05 | Votre portefeuille, protégé | Verrou par empreinte, visage ou code – sans compte |

### hi-IN – Hindi

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | बाज़ार को समझें, बिना शोर के | सीधे एक्सचेंज से, CHF में भी, मिनी चार्ट के साथ |
| 02 | यह क्यों हिल रहा है? | वॉल्यूम, पूरा बाज़ार और फ़्यूचर्स – संक्षेप में |
| 03 | आपकी सूची, आपके एक्सचेंज | 32 एक्सचेंज पर 41 मार्केट, साथ में DEX टोकन |
| 04 | अलार्म, जब ज़रूरी हो | “जब BTC 90,000 CHF से नीचे जाए, तो मुझे बताएँ” |
| 05 | आपका पोर्टफ़ोलियो – सुरक्षित | फ़िंगरप्रिंट, चेहरे या PIN से लॉक – बिना खाते के |

### hu-HU – Ungarisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Értsd a piacot, zaj nélkül | Közvetlenül a tőzsdéről, CHF-ben is, minigrafikonnal |
| 02 | Miért mozog? | Forgalom, teljes piac és határidős adatok – röviden |
| 03 | A te listád, a te tőzsdéid | 41 piac 32 tőzsdén, plusz DEX tokenek |
| 04 | Riasztás, amikor számít | „Szólj, ha a BTC 90 000 CHF alá esik” |
| 05 | A portfóliód – védve | Zár ujjlenyomattal, arccal vagy PIN-nel – fiók nélkül |

### id – Indonesisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Pahami pasar tanpa kebisingan | Langsung dari bursa, juga dalam CHF, dengan grafik mini |
| 02 | Kenapa ini bergerak? | Volume, pasar umum, dan futures – singkatnya |
| 03 | Daftar Anda, bursa Anda | 41 pasar di 32 bursa, plus token DEX |
| 04 | Alarm saat penting | “Beri tahu saya saat BTC turun di bawah 90.000 CHF” |
| 05 | Portofolio Anda – terlindungi | Kunci dengan sidik jari, wajah, atau PIN – tanpa akun |

### it-IT – Italienisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Capire il mercato senza rumore | Diretti dall'exchange, anche in CHF, con mini grafico |
| 02 | Perché si muove? | Volume, mercato generale e futures, in breve |
| 03 | La tua lista, i tuoi exchange | 41 mercati su 32 exchange, più i token DEX |
| 04 | Allarmi quando conta | «Avvisami quando BTC scende sotto 90.000 CHF» |
| 05 | Il tuo portafoglio, protetto | Blocco con impronta, volto o PIN, senza account |

### iw-IL – Hebräisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | להבין את השוק, בלי רעש | ישירות מהבורסה, גם ב-CHF, עם גרף קטן |
| 02 | למה זה זז? | מחזור מסחר, השוק כולו וחוזים עתידיים – בקצרה |
| 03 | הרשימה שלך, הבורסות שלך | 41 שווקים ב-32 בורסות, וגם טוקנים של DEX |
| 04 | התראות כשזה חשוב | ״להודיע לי כש-BTC יורד מתחת ל-90,000 CHF״ |
| 05 | תיק ההשקעות שלך – מוגן | נעילה בטביעת אצבע, פנים או PIN – בלי חשבון |

### ja-JP – Japanisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | ノイズなしで市場を理解 | 取引所から直接、CHF 表示やミニチャートも |
| 02 | なぜ動いている？ | 出来高・市場全体・先物から短くまとめます |
| 03 | あなたのリスト、あなたの取引所 | 32 の取引所で 41 の市場、DEX トークンも |
| 04 | 大事なときにアラーム | 「BTC が 90,000 CHF を下回ったら知らせる」 |
| 05 | ポートフォリオを保護 | 指紋・顔・PIN でロック。アカウント不要 |

### ko-KR – Koreanisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | 소음 없이 시장 이해하기 | 거래소에서 바로, CHF 표시와 미니 차트까지 |
| 02 | 왜 움직이나요? | 거래량, 시장 전체, 선물 데이터를 짧게 요약 |
| 03 | 나의 목록, 나의 거래소 | 32개 거래소의 41개 마켓, DEX 토큰까지 |
| 04 | 중요할 때 울리는 경보 | ‘BTC가 90,000 CHF 아래로 내려가면 알려 주세요’ |
| 05 | 포트폴리오 – 보호됨 | 지문, 얼굴 또는 PIN으로 잠금 – 계정 불필요 |

### nl-NL – Niederländisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | De markt begrijpen zonder ruis | Rechtstreeks van de beurs, ook in CHF, met minigrafiek |
| 02 | Waarom beweegt dit? | Volume, totale markt en futures – kort samengevat |
| 03 | Jouw lijst, jouw beurzen | 41 markten op 32 beurzen, plus DEX-tokens |
| 04 | Alarmen als het telt | ‘Laat het me weten als BTC onder 90.000 CHF daalt’ |
| 05 | Je portfolio – beschermd | Vergrendeling met vingerafdruk, gezicht of pincode |

### no-NO – Norwegisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Forstå markedet uten støy | Rett fra børsen, også i CHF, med minigraf |
| 02 | Hvorfor beveger den seg? | Volum, hele markedet og futures – kort fortalt |
| 03 | Din liste, dine børser | 41 markeder på 32 børser, pluss DEX-tokens |
| 04 | Alarmer når det gjelder | «Gi meg beskjed når BTC faller under 90 000 CHF» |
| 05 | Porteføljen din – beskyttet | Lås med fingeravtrykk, ansikt eller PIN – uten konto |

### pl-PL – Polnisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Zrozum rynek bez szumu | Prosto z giełdy, także w CHF, z minimalnym wykresem |
| 02 | Dlaczego to się rusza? | Wolumen, cały rynek i futures – w skrócie |
| 03 | Twoja lista, twoje giełdy | 41 rynków na 32 giełdach, do tego tokeny DEX |
| 04 | Alarmy, gdy to ważne | „Powiadom mnie, gdy BTC spadnie poniżej 90 000 CHF” |
| 05 | Twój portfel – chroniony | Blokada odciskiem palca, twarzą lub PIN – bez konta |

### pt-BR – Portugiesisch (Brasilien)

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Entenda o mercado sem ruído | Direto da exchange, também em CHF, com minigráfico |
| 02 | Por que está se movendo? | Volume, mercado geral e futuros, em resumo |
| 03 | Sua lista, suas exchanges | 41 mercados em 32 exchanges, além de tokens DEX |
| 04 | Alarmes quando importa | “Me avise quando o BTC cair abaixo de 90.000 CHF” |
| 05 | Seu portfólio, protegido | Bloqueio por digital, rosto ou PIN, sem conta |

### pt-PT – Portugiesisch (Portugal)

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Perceber o mercado sem ruído | Diretos da exchange, também em CHF, com minigráfico |
| 02 | Porque está a mexer? | Volume, mercado global e futuros, em resumo |
| 03 | A sua lista, as suas exchanges | 41 mercados em 32 exchanges, e ainda tokens DEX |
| 04 | Alarmes quando importa | «Avisar-me quando BTC descer abaixo de 90 000 CHF» |
| 05 | O seu portefólio, protegido | Bloqueio por impressão digital, rosto ou PIN |

### ro – Rumänisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Înțelege piața, fără zgomot | Direct de la bursă, și în CHF, cu minigrafic |
| 02 | De ce se mișcă? | Volum, piața generală și futures – pe scurt |
| 03 | Lista ta, bursele tale | 41 de piețe pe 32 de burse, plus tokenuri DEX |
| 04 | Alarme când contează | „Anunță-mă când BTC coboară sub 90.000 CHF” |
| 05 | Portofoliul tău – protejat | Blocare cu amprentă, față sau PIN – fără cont |

### ru-RU – Russisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Понимать рынок без шума | Напрямую с биржи, и в CHF, с мини-графиком |
| 02 | Почему это движется? | Объём, рынок в целом и фьючерсы – коротко |
| 03 | Ваш список, ваши биржи | 41 рынок на 32 биржах, плюс токены DEX |
| 04 | Оповещения, когда это важно | «Сообщить, когда BTC опустится ниже 90 000 CHF» |
| 05 | Ваш портфель под защитой | Блокировка отпечатком, лицом или PIN – без аккаунта |

### sq – Albanisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Kupto tregun pa zhurmë | Drejt nga bursa, edhe në CHF, me mini-grafik |
| 02 | Pse po lëviz? | Vëllimi, tregu i përgjithshëm dhe futures – shkurt |
| 03 | Lista jote, bursat e tua | 41 tregje në 32 bursa, plus tokenë DEX |
| 04 | Alarme kur ka rëndësi | «Më njofto kur BTC të bjerë nën 90 000 CHF» |
| 05 | Portofoli yt – i mbrojtur | Kyçje me gjurmë gishti, fytyrë ose PIN – pa llogari |

### sv-SE – Schwedisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Förstå marknaden utan brus | Direkt från börsen, även i CHF, med minidiagram |
| 02 | Varför rör den sig? | Volym, hela marknaden och terminer – kort sagt |
| 03 | Din lista, dina börser | 41 marknader på 32 börser, plus DEX-tokens |
| 04 | Larm när det gäller | ”Meddela mig när BTC sjunker under 90 000 CHF” |
| 05 | Din portfölj – skyddad | Lås med fingeravtryck, ansikte eller PIN – utan konto |

### th – Thailändisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | เข้าใจตลาด ไม่มีเสียงรบกวน | ตรงจากกระดานเทรด แสดงเป็น CHF ได้ พร้อมกราฟเล็ก |
| 02 | ทำไมถึงเคลื่อนไหว? | วอลุ่ม ตลาดโดยรวม และฟิวเจอร์ส สรุปสั้น ๆ |
| 03 | รายการของคุณ กระดานเทรดของคุณ | 41 ตลาดบน 32 กระดานเทรด รวมโทเคน DEX |
| 04 | แจ้งเตือนเมื่อสำคัญ | “แจ้งฉันเมื่อ BTC ลงต่ำกว่า 90,000 CHF” |
| 05 | พอร์ตของคุณ – ปลอดภัย | ล็อกด้วยลายนิ้วมือ ใบหน้า หรือ PIN – ไม่ต้องมีบัญชี |

### tr-TR – Türkisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Piyasayı gürültüsüz anla | Doğrudan borsadan, CHF olarak da, mini grafikle |
| 02 | Bu neden hareket ediyor? | Hacim, genel piyasa ve vadeli işlemler – kısaca |
| 03 | Senin listen, senin borsaların | 32 borsada 41 piyasa, ayrıca DEX tokenleri |
| 04 | Önemli anda alarm | “BTC 90.000 CHF altına düşünce bana haber ver” |
| 05 | Portföyün – korunuyor | Parmak izi, yüz veya PIN ile kilit – hesap yok |

### uk – Ukrainisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Розуміти ринок без шуму | Напряму з біржі, і в CHF, з міні-графіком |
| 02 | Чому це рухається? | Обсяг, ринок загалом і ф’ючерси – коротко |
| 03 | Ваш список, ваші біржі | 41 ринок на 32 біржах, а ще токени DEX |
| 04 | Сигнали, коли це важливо | «Повідомити, коли BTC опуститься нижче 90 000 CHF» |
| 05 | Ваш портфель під захистом | Блокування відбитком, обличчям або PIN – без акаунта |

### vi – Vietnamesisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Hiểu thị trường, không ồn ào | Trực tiếp từ sàn, cả bằng CHF, kèm biểu đồ mini |
| 02 | Vì sao nó biến động? | Khối lượng, toàn thị trường và futures – ngắn gọn |
| 03 | Danh sách của bạn, sàn của bạn | 41 thị trường trên 32 sàn, cùng token DEX |
| 04 | Cảnh báo khi cần | “Báo cho tôi khi BTC giảm dưới 90.000 CHF” |
| 05 | Danh mục của bạn – được bảo vệ | Khóa bằng vân tay, khuôn mặt hoặc PIN – không tài khoản |

### zh-CN – Chinesisch (vereinfacht)

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | 看懂市场，远离噪音 | 直接来自交易所，也可显示 CHF，附迷你图表 |
| 02 | 为什么在波动？ | 成交量、大盘和合约数据，一句话总结 |
| 03 | 你的列表，你的交易所 | 32 家交易所的 41 个市场，另含 DEX 代币 |
| 04 | 关键时刻才提醒 | “当 BTC 跌破 90,000 CHF 时提醒我” |
| 05 | 你的投资组合，受保护 | 指纹、面容或 PIN 锁定，无需账户 |
