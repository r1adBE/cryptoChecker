# Screenshot-Kit für Google Play und App Store

Version 16.2.2 (Build 91). Dieses Kit enthält alles, um die Store-Screenshots in allen Sprachen gleich aussehen zu lassen:

| Datei | Zweck |
|---|---|
| `demo-backup.json` | Demo-Daten zum Wiederherstellen (Android und iOS) |
| `captions.json` | Titel (≤ 30 Zeichen, hart max. 40) und Untertitel (≤ 60 Zeichen) pro Szene, 74 Sprachen (inkl. pt-BR, zh-TW, ms und yue) – Eingabe für das Rahmen-Skript; dieselben Texte als Tabellen unten in «Beschriftungen je Sprache» |
| `../../../tools/frame_screenshots.py` | setzt Hintergrund, Text und Screenshot zum fertigen Store-Bild zusammen |
| `raw/<locale>/<NN>.png` | deine rohen Screenshots (nicht im Repo) |
| `out/<plattform>/<locale>/<NN>.png` | fertige Bilder (nicht im Repo) |

## Die Szenen – fünf Bilder, zwei davon zentral

Die Store-Bilder zeigen, was die App im Alltag ausmacht: die **Merkliste** mit dem Kurs der gewählten Börse,
**ein Alarm, der einmal Bescheid gibt**, das **Widget** auf dem Startbildschirm und **«Was bewegt sich mit?»**
ohne Prognose. Das Portfolio ist optional und kommt höchstens als letztes Bild. Keine «Markt-Terminal»-Szene
(Börsenauswahl, Indikator-Seiten, Crypto Pulse).

| Nr. | Titel (de-DE) | Szene | Was zu sehen ist |
|---|---|---|---|
| 01 | Letzter Kurs von der Börse | Merkliste | Tab «Merkliste»: BTC/USDT, ETH/USDT, SOL/USDT (Binance), zweite Zeile «≈ … CHF», Mini-Chart, Gruppe «Layer 1», Kursfarben «Ruhig». Ruhig halten: keine offenen Blätter, keine Hinweise «Änderung: …»/«Chart: …» nötig |
| 02 | Ein Alarm, der einmal meldet | Alarm | Neuen Alarm für BTC/USDT öffnen: Satz «Wenn BTC unter 90 000 geht» (Alarmwährung CHF aus der Demo-Sicherung), Wiederholung auf «Einmal». Alternative: die Alarm-Mitteilung oben über der Merkliste (nur eine echte, ausgelöste Demo-Mitteilung, keine privaten Mitteilungen) |
| 03 | Kurse auf dem Startbildschirm | Widget | Startbildschirm mit dem Merkliste-Widget (BTC, ETH, SOL aus der Demo-Sicherung); Android: Widget «Merkliste», iOS: mittleres Merkliste-Widget. Ruhiger Hintergrund, keine anderen Apps mit Namen oder Mitteilungen im Bild |
| 04 | Was bewegt sich mit? | «Was bewegt sich mit?» | Bei BTC oder SOL «Was bewegt sich mit?» öffnen, «Kurz gesagt» (Kurzfazit) oben im Bild |
| 05 | Portfolio, wenn du willst | Portfolio (optional) | Tab «Portfolio» mit den Demo-Käufen (Gesamtwert in CHF). Nur zeigen, wenn das Portfolio eingeschaltet ist; keine Gewinn-Aussage im Untertitel |

Die Untertitel versprechen nur, was stimmt: Szene 01 «ohne Konto, ohne Werbung» und dass Beträge auf dem Gerät
bleiben, Szene 02 sagt ehrlich, dass der Alarm beim Nachsehen kommt (nicht im Moment des Kurses; seit Build 104), Szene 03 sagt, dass Widgets den Kurs ohne Öffnen der App zeigen,
Szene 04 nennt die Datenbasis (Volumen, Gesamtmarkt, Futures) und «ohne Prognose», Szene 05 sagt, dass das
Portfolio optional ist (einschaltbar in den Einstellungen). Keine Wörter wie «Echtzeit», «sofort», «garantiert»
oder «alle Daten direkt von der Börse».

Google Play verlangt mindestens 2 Bilder. Kern sind 01–04; 05 ist eine Ergänzung. Mehr als fünf Szenen bitte
nicht anhängen.

Dateinamen: `01.png` … `05.png`, pro Sprache ein Ordner, z. B. `raw/de-DE/01.png`. Als Ordnernamen gehen die Namen aus `fastlane/metadata/android` (z. B. `de-DE`, `iw-IL`, `zh-CN`) und die App-Store-Namen (z. B. `he`, `zh-Hans`, `ar-SA`); das Skript ordnet sie selbst zu. Liegt in einem Ordner noch eine alte `06.png`, meldet das Skript «keine Texte für Szene» und überspringt sie. Achtung: Die Nummern haben sich mit Build 48 geändert (früher 01 Alarm, 02 «Was bewegt sich mit?», 03 Merkliste) – alte Aufnahmen umbenennen.

## Vorbereitung

### 1. Demo-Daten wiederherstellen

1. `demo-backup.json` aufs Gerät kopieren (Android: z. B. `adb push demo-backup.json /sdcard/Download/`; iOS-Simulator: Datei ins Simulator-Fenster ziehen oder in «Dateien» ablegen).
2. In der App: Einstellungen → Sichern & Wiederherstellen → Wiederherstellen → Datei wählen.

Achtung: Die Wiederherstellung **ersetzt** Merkliste, Alarme, Favoriten, Portfolio und Einstellungen. Nur auf einem Test-Gerät oder Simulator machen.

Inhalt der Sicherung:

- Merkliste: BTC/USDT, ETH/USDT, SOL/USDT auf Binance (Börsen-Schlüssel `Binance`, Paar-Id `BTCUSDT` usw.), alle in der Gruppe «Layer 1»
- Notiz bei BTC: «Long-term hold»
- Alarm: BTC/USDT unter 90 000 CHF (Alarmwährung CHF)
- Portfolio: Kauf 0,05 BTC zu 77 500 USDT (8.4.2025), 1,2 ETH zu 2400 USDT (20.6.2025), 10 SOL zu 165 USDT (1.8.2025)
- Einstellungen: Umrechnungswährung CHF, umgerechnete Kurse an, Mini-Chart an, Portfolio an, heller Modus, Akzent Orange, Kursfarben «Ruhig» (Standard), keine dauernde Kurs-Mitteilung, Portfolio-Sperre aus, Nachtruhe aus

«Farben tauschen» ist bewusst **nicht** in der Sicherung: In China, Japan, Korea und Taiwan bleibt so die Gerätevorgabe (Rot steigend) erhalten. Wer für `ja-JP`, `ko-KR` und `zh-CN` die ostasiatischen Farben zeigen will, prüft das in den Einstellungen.

Kurse kommen live von Binance. Binance sperrt Anfragen aus den USA – Geräte oder Simulatoren mit US-IP zeigen dann keine Kurse. In dem Fall über ein Netz ausserhalb der USA aufnehmen.

### 2. Umrechnungswährung und Darstellung prüfen

- Einstellungen → Umrechnungswährung: **CHF**, «Umgerechnete Kurse» an (kommt aus der Sicherung, trotzdem kurz prüfen).
- Heller Modus (Sicherung setzt ihn; Systemeinstellung des Geräts ebenfalls hell). Alle Bilder eines Satzes im selben Modus – nie hell und dunkel gemischt.
- Akzentfarbe **Orange** (Einstellungen → Darstellung; kommt aus der Sicherung). Orange ist die Markenfarbe – siehe «Markenfarbe» unten. Im hellen Modus erscheint der Akzent in der App als **#A64827**, im dunklen als **#ED835E**.
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

Orange ist die Erkennungsfarbe von Crypto Checker: App-Icon (Glocke), Standard-Akzent bei neuen Installationen, Rahmen-Hintergrund von `frame_screenshots.py` (Verlauf #FC9F79 → #ED835E → #C96444) und die Feature-Grafiken in `docs/store/`. **Alle Marketing-Bilder** (Screenshots, Feature-Grafik, Promo-Bilder) zeigen deshalb den Akzent Orange und das orange Icon.

**Ein Akzent, ein Modus:** Die Screenshots zeigen genau einen Akzent (Orange), und zwar so, wie die App ihn zeichnet –
entweder **hell mit #A64827** (Standard, Demo-Sicherung) oder **dunkel mit #ED835E**. Ein Orange: #ED835E (Icon, dunkler Knopf), #A64827 ist nur die dunklere Stufe desselben Orange für hellen Hintergrund. Kein Bild mit anderem Akzent oder im anderen Modus beimischen.

Blau, Grün, Rot und Marrs Green sind optionale Themes, die man in der App wählen kann. Sie können im Beschreibungstext erwähnt werden, gehören aber nicht in die Store-Bilder. Die alten blauen Globus-Grafiken sind nicht mehr Teil der Marke und dürfen nicht verwendet werden.

## Inhaltliche Regeln

- **Keine Börsen-Logos** und keine Geräterahmen echter Marken. Börsennamen nur als Text, so wie die App sie zeigt.
- Nur Funktionen zeigen, die der Build wirklich hat. Vor der Aufnahme prüfen, ob der Alarm-Satz mit «Einmal»/«Jedes Mal» und das Kurzfazit bei «Was bewegt sich mit?» in genau diesem Build vorhanden sind – sonst Szene weglassen oder ersetzen.
- Beobachtend formulieren: keine «Kaufsignale», «Trading-Chancen» oder Gewinnversprechen. Crypto Pulse und der Markt-Tab zeigen den Markt, sie sagen nichts voraus.
- Der Markt-Tab ist kein zweites Produkt: nicht im ersten Bild und nicht in den ersten Zeilen der Store-Texte. Bild 01 zeigt die Merkliste, Bild 02 den Alarm.
- Keine Begriffe aus den Steuer-Vorlagen (z. B. «Arbeitsunterlage», «Steuerjahr») in Bildern, Untertiteln oder Store-Texten; das Portfolio höchstens als optionales Bild 05.
- Nichts versprechen, was die App nicht hält: keine «Echtzeit»-, «Sofort-Alarm»- oder «garantiert»-Aussagen. Der letzte Kurs kommt von der Börse des Paars; %-Änderung und Chart können von Binance/Coinbase stammen, und bei geschlossener App entscheidet das Telefon, wann Alarme geprüft werden.
- Keine Spenden-Adressen, keine echten Bestände, keine persönlichen Mitteilungen im Bild.

## Beschriftungen je Sprache

Gleicher Inhalt wie `captions.json` (bei Änderungen beide Stellen anpassen). Titel ≤ 30 Zeichen,
Untertitel ≤ 60 Zeichen. Untertitel 02 sagt seit Build 104 ehrlich, wann der Alarm kommt (beim Nachsehen, nicht im Moment des Kurses), Titel 04 «Was bewegt sich mit?»
entspricht dem Titel des Blatts in der App.

### af – Afrikaans

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Jongste prys van die beurs | Geen rekening, geen reklame – bedrae bly op jou toestel |
| 02 | ’n Alarm wat jou een keer sê | Laat weet wanneer ’n kontrole jou vlak bereik vind |
| 03 | Pryse op jou tuisskerm | Volglys of een munt as legstuk – sonder om die app te open |
| 04 | Wat beweeg saam? | Volume, mark en futures kortliks – geen voorspelling |
| 05 | Portefeulje, as jy wil | Opsioneel, skakel aan in Instellings: koop, verkoop, oordrag |

### am – Amharisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | የመጨረሻ ዋጋ ከልውውጡ | መለያ የለም፣ ማስታወቂያ የለም – መጠኖች በመሣሪያዎ ላይ ይቆያሉ |
| 02 | አንድ ጊዜ የሚያሳውቅ ማንቂያ | ፍተሻ ገደብዎ መድረሱን ሲያገኝ ያሳውቅዎታል |
| 03 | ዋጋዎች በመነሻ ማያዎ ላይ | የክትትል ዝርዝር ወይም አንድ ሳንቲም እንደ ዊጀት – መተግበሪያውን ሳይከፍቱ |
| 04 | አብሮ ምን እየተንቀሳቀሰ ነው? | መጠን፣ ገበያ እና ፊውቸርስ በአጭሩ – ትንበያ የለም |
| 05 | ፖርትፎሊዮ፣ ከፈለጉ | አማራጭ፣ በቅንብሮች ውስጥ ያብሩ፦ ግዢ፣ ሽያጭ፣ ዝውውር |

### ar – Arabisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | آخر سعر من المنصة | بلا حساب ولا إعلانات – المبالغ تبقى على جهازك |
| 02 | تنبيه يُعلمك مرة واحدة | يُعلمك عندما يجد الفحص أن مستواك قد تحقق |
| 03 | الأسعار على الشاشة الرئيسية | قائمة المتابعة أو عملة واحدة كأداة – دون فتح التطبيق |
| 04 | ما الذي يتحرك معه؟ | الحجم والسوق والعقود الآجلة باختصار – بلا توقعات |
| 05 | المحفظة إن أردتها | اختياري، يُفعَّل من الإعدادات: شراء وبيع وتحويلات |

### az-AZ – Aserbaidschanisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Birjadan son qiymət | Hesab yox, reklam yox – məbləğlər cihazında qalır |
| 02 | Bir dəfə xəbər verən alarm | Yoxlama səviyyənə çatıldığını görəndə xəbər verir |
| 03 | Qiymətlər ana ekranında | İzləmə siyahısı və ya bir koin vidcet kimi – tətbiqi açmadan |
| 04 | Onunla nə hərəkət edir? | Həcm, bazar və fyuçerslər qısaca – proqnoz yoxdur |
| 05 | Portfel, istəsən | İstəyə bağlı, Ayarlarda aç: alış, satış, köçürmə |

### bg – Bulgarisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Последен курс от борсата | Без акаунт, без реклами – сумите остават на устройството ти |
| 02 | Аларма, която казва веднъж | Съобщава, когато проверка установи, че прагът е достигнат |
| 03 | Курсове на началния екран | Списък или монета като уиджет – без да отваряш приложението |
| 04 | Какво се движи заедно? | Обем, пазар и фючърси накратко – без прогноза |
| 05 | Портфолио, ако искаш | По избор, в Настройки: покупки, продажби, прехвърляния |

### bn-BD – Bengalisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | এক্সচেঞ্জ থেকে সর্বশেষ দাম | অ্যাকাউন্ট নেই, বিজ্ঞাপন নেই – পরিমাণ আপনার ডিভাইসেই থাকে |
| 02 | একবারই জানায় এমন অ্যালার্ট | যাচাইয়ে আপনার মাত্রা ছোঁয়া গেলে জানায় |
| 03 | হোম স্ক্রিনে দাম | ওয়াচলিস্ট বা একটি কয়েন উইজেটে – অ্যাপ না খুলেই |
| 04 | এর সঙ্গে আর কী নড়ছে? | ভলিউম, বাজার ও ফিউচার্স সংক্ষেপে – কোনো পূর্বাভাস নয় |
| 05 | পোর্টফোলিও, চাইলে | ঐচ্ছিক, সেটিংসে চালু করুন: কেনা, বেচা, ট্রান্সফার |

### bs – Bosnisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Zadnja cijena s berze | Bez računa, bez reklama – iznosi ostaju na tvom uređaju |
| 02 | Alarm koji javlja jednom | Javlja kada provjera pokaže da je tvoj nivo dostignut |
| 03 | Cijene na početnom ekranu | Spisak praćenja ili jedan coin kao widget – bez otvaranja |
| 04 | Šta se kreće s njom? | Volumen, tržište i futures ukratko – bez prognoze |
| 05 | Portfolio, ako želiš | Opcionalno, u Postavkama: kupovine, prodaje, prijenosi |

### ca – Katalanisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Últim preu de l’exchange | Sense compte ni anuncis – els imports queden al dispositiu |
| 02 | Una alarma que avisa un cop | T’avisa quan una comprovació troba el teu nivell assolit |
| 03 | Preus a la pantalla d’inici | Llista o una moneda com a widget – sense obrir l’app |
| 04 | Què es mou amb ell? | Volum, mercat i futurs en resum – sense previsions |
| 05 | Cartera, si la vols | Opcional, a Configuració: compres, vendes, transferències |

### cs-CZ – Tschechisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Poslední cena z burzy | Bez účtu a reklam – částky zůstávají v zařízení |
| 02 | Alarm, který upozorní jednou | Ozve se, když kontrola zjistí, že je hranice dosažena |
| 03 | Ceny na ploše | Seznam nebo jedna mince jako widget – bez otevření aplikace |
| 04 | Co se hýbe s ním? | Objem, trh a futures ve zkratce – bez prognóz |
| 05 | Portfolio, když chcete | Volitelné, zapnete v nastavení: nákupy, prodeje, převody |

### da-DK – Dänisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Seneste kurs fra børsen | Ingen konto, ingen reklamer – beløb bliver på enheden |
| 02 | En alarm, der melder én gang | Giver besked, når et tjek viser, at niveauet er nået |
| 03 | Kurser på startskærmen | Listen eller én coin som widget – uden at åbne appen |
| 04 | Hvad bevæger sig med? | Volumen, marked og futures kort – uden prognose |
| 05 | Portefølje, hvis du vil | Valgfrit, slås til i indstillinger: køb, salg, overførsler |

### de-DE – Deutsch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Letzter Kurs von der Börse | Ohne Konto, ohne Werbung – Beträge bleiben auf dem Gerät |
| 02 | Ein Alarm, der einmal meldet | Meldet sich, wenn beim Nachsehen deine Marke erreicht ist |
| 03 | Kurse auf dem Startbildschirm | Merkliste oder ein Coin als Widget – ohne die App zu öffnen |
| 04 | Was bewegt sich mit? | Volumen, Markt und Futures kurz gesagt – ohne Prognose |
| 05 | Portfolio, wenn du willst | Optional, in den Einstellungen: Käufe, Verkäufe, Transfers |

### el-GR – Griechisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Η τιμή από το ανταλλακτήριο | Χωρίς λογαριασμό και διαφημίσεις – τα ποσά μένουν σε σένα |
| 02 | Μία ειδοποίηση, μία φορά | Σε ειδοποιεί όταν ένας έλεγχος βρει το όριό σου |
| 03 | Τιμές στην αρχική οθόνη | Λίστα ή ένα νόμισμα σε widget – χωρίς άνοιγμα εφαρμογής |
| 04 | Τι κινείται μαζί; | Όγκος, αγορά και futures με λίγα λόγια – χωρίς πρόβλεψη |
| 05 | Χαρτοφυλάκιο, αν θέλεις | Προαιρετικό, από τις ρυθμίσεις: αγορές, πωλήσεις, μεταφορές |

### en-US – Englisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Last price from the exchange | No account, no ads – amounts stay on your device |
| 02 | An alarm that tells you once | Notifies you when a check finds your level reached |
| 03 | Prices on your home screen | Watchlist or one coin as a widget – without opening the app |
| 04 | What is moving with it? | Volume, market and futures in short – no forecast |
| 05 | Portfolio, if you want it | Optional, turn on in Settings: buys, sells, transfers |

### es-ES – Spanisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Último precio del exchange | Sin cuenta ni anuncios: los importes no salen del móvil |
| 02 | Una alarma que avisa una vez | Avisa cuando una comprobación ve tu nivel alcanzado |
| 03 | Precios en tu pantalla | Tu lista o una moneda como widget, sin abrir la app |
| 04 | ¿Qué se mueve con él? | Volumen, mercado y futuros en resumen, sin pronósticos |
| 05 | Cartera, si la quieres | Opcional, en Ajustes: compras, ventas, transferencias |

### et – Estnisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Viimane hind otse börsilt | Ilma kontota, ilma reklaamita – summad jäävad seadmesse |
| 02 | Hoiatus, mis teatab korra | Annab teada, kui kontroll leiab, et su lävi on saavutatud |
| 03 | Hinnad avaekraanil | Jälgimisloend või üks münt vidinana – rakendust avamata |
| 04 | Mis liigub kaasa? | Maht, turg ja futuurid lühidalt – ilma prognoosita |
| 05 | Portfell, kui soovid | Valikuline, lülita sisse seadetes: ostud, müügid, ülekanded |

### eu-ES – Baskisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Azken prezioa burtsatik | Konturik, iragarkirik ez – zenbatekoak gailuan geratzen dira |
| 02 | Behin abisatzen duen alarma | Egiaztapenak zure muga iritsita aurkitzean abisatzen du |
| 03 | Prezioak hasierako pantailan | Zerrenda edo txanpon bat widget gisa – aplikazioa ireki gabe |
| 04 | Zer mugitzen da harekin? | Bolumena, merkatua eta futuroak laburki – iragarpenik gabe |
| 05 | Zorroa, nahi baduzu | Aukerakoa (Ezarpenak): erosketak, salmentak, transferentziak |

### fa – Persisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | آخرین قیمت از صرافی | بدون حساب و تبلیغ – مبالغ روی دستگاه شما می‌ماند |
| 02 | هشداری که یک بار خبر می‌دهد | وقتی بررسی ببیند سطح تو رسیده، خبرت می‌کند |
| 03 | قیمت‌ها روی صفحهٔ اصلی | واچ‌لیست یا یک کوین به‌صورت ابزارک – بدون باز کردن برنامه |
| 04 | چه چیزی همراه آن حرکت می‌کند؟ | حجم، بازار و فیوچرز به‌طور خلاصه – بدون پیش‌بینی |
| 05 | پرتفوی، اگر بخواهید | اختیاری، از تنظیمات: خرید، فروش، انتقال |

### fi-FI – Finnisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Viimeisin hinta pörssistä | Ei tiliä, ei mainoksia – summat pysyvät laitteellasi |
| 02 | Hälytys, joka ilmoittaa kerran | Ilmoittaa, kun tarkistus huomaa rajasi saavutetuksi |
| 03 | Hinnat aloitusnäytöllä | Lista tai yksi kolikko widgetinä – avaamatta sovellusta |
| 04 | Mikä liikkuu mukana? | Volyymi, markkina ja futuurit lyhyesti – ei ennusteita |
| 05 | Salkku, jos haluat | Valinnainen, asetuksista: ostot, myynnit, siirrot |

### fil – Filipino

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Huling presyo mula sa exchange | Walang account, walang ad – nasa device mo ang mga halaga |
| 02 | Alertong isang beses lang | Nag-aalerto kapag nakitang naabot na ang level mo |
| 03 | Presyo sa home screen mo | Watchlist o coin bilang widget – hindi na bubuksan ang app |
| 04 | Ano ang gumagalaw kasabay nito? | Volume, market at futures nang maikli – walang hula |
| 05 | Portfolio, kung gusto mo | Opsyonal, i-on sa Mga Setting: bili, benta, transfer |

### fr-FR – Französisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Dernier cours de la plateforme | Sans compte ni pub – les montants restent sur l’appareil |
| 02 | Une alerte, une seule fois | Te prévient quand une vérification voit ton seuil atteint |
| 03 | Cours sur l’écran d’accueil | Liste ou une crypto en widget – sans ouvrir l’app |
| 04 | Qu’est-ce qui bouge avec ? | Volume, marché et futures en bref – sans prévision |
| 05 | Portefeuille, si besoin | En option, dans les réglages : achats, ventes, transferts |

### ga – Irisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Praghas is déanaí ón malartán | Gan chuntas, gan fógraí – fanann méideanna ar do ghléas |
| 02 | Foláireamh a deir uair amháin | Cuireann sé in iúl nuair a aimsíonn seiceáil do leibhéal |
| 03 | Praghsanna ar do scáileán baile | Liosta faire nó bonn mar ghiuirléid – gan an aip a oscailt |
| 04 | Cad atá ag bogadh leis? | Toirt, margadh agus todhchaíochtaí go gearr – gan tuar |
| 05 | Punann, más mian leat | Roghnach, cas air i Socruithe: ceannach, díol, aistrithe |

### gl-ES – Galicisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Último prezo do exchange | Sen conta nin anuncios – os importes quedan no dispositivo |
| 02 | Unha alarma que avisa unha vez | Avísate cando unha comprobación atopa o teu nivel alcanzado |
| 03 | Prezos na pantalla de inicio | Lista ou unha moeda como widget – sen abrir a app |
| 04 | Que se move con el? | Volume, mercado e futuros en resumo – sen previsións |
| 05 | Carteira, se a queres | Opcional, en Configuración: compras, vendas, transferencias |

### gu – Gujarati

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | એક્સચેન્જ પરથી તાજો ભાવ | એકાઉન્ટ નહીં, જાહેરાત નહીં – રકમ તમારા ઉપકરણ પર જ રહે છે |
| 02 | એક જ વાર જણાવતો એલર્ટ | તપાસમાં તમારું સ્તર પહોંચેલું દેખાય ત્યારે જણાવે છે |
| 03 | હોમ સ્ક્રીન પર ભાવ | વૉચલિસ્ટ કે એક કૉઇન વિજેટ તરીકે – ઍપ ખોલ્યા વગર |
| 04 | સાથે શું હલી રહ્યું છે? | વૉલ્યુમ, બજાર અને ફ્યુચર્સ ટૂંકમાં – કોઈ આગાહી નહીં |
| 05 | પોર્ટફોલિયો, ઇચ્છો તો | વૈકલ્પિક, સેટિંગ્સમાં ચાલુ કરો: ખરીદી, વેચાણ, ટ્રાન્સફર |

### hi-IN – Hindi

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | एक्सचेंज से आख़िरी भाव | बिना खाते, बिना विज्ञापन – रकम डिवाइस पर ही रहती है |
| 02 | एक बार बताने वाला अलार्म | जाँच में तुम्हारा स्तर पहुँचा मिले तो बताता है |
| 03 | होम स्क्रीन पर भाव | वॉचलिस्ट या एक कॉइन विजेट में – ऐप खोले बिना |
| 04 | साथ में किसमें हलचल है? | वॉल्यूम, बाज़ार और फ़्यूचर्स संक्षेप में – बिना पूर्वानुमान |
| 05 | पोर्टफ़ोलियो, अगर चाहें | वैकल्पिक, सेटिंग्स में चालू करें: ख़रीद, बिक्री, ट्रांसफ़र |

### hr – Kroatisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Zadnja cijena s burze | Bez računa, bez oglasa – iznosi ostaju na tvom uređaju |
| 02 | Alarm koji javlja jednom | Javlja kada provjera pokaže da je tvoja razina dosegnuta |
| 03 | Cijene na početnom zaslonu | Popis praćenja ili jedan coin kao widget – bez otvaranja |
| 04 | Što se kreće s njom? | Volumen, tržište i futures ukratko – bez prognoze |
| 05 | Portfelj, ako želiš | Neobavezno, uključi u Postavkama: kupnje, prodaje, prijenosi |

### hu-HU – Ungarisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Utolsó ár a tőzsdéről | Fiók és reklám nélkül – az összegek az eszközön maradnak |
| 02 | Riasztás, amely egyszer szól | Szól, ha egy ellenőrzéskor az ár eléri a szintedet |
| 03 | Árfolyamok a kezdőképernyőn | Lista vagy egy érme widgetként – az app megnyitása nélkül |
| 04 | Mi mozog vele együtt? | Forgalom, piac és futures röviden – előrejelzés nélkül |
| 05 | Portfólió, ha szeretnéd | Opcionális, a beállításokban: vétel, eladás, átutalás |

### hy-AM – Armenisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Վերջին գինը բորսայից | Առանց հաշվի, առանց գովազդի – գումարները մնում են սարքում |
| 02 | Մեկ անգամ հայտնող ահազանգ | Տեղեկացնում է, երբ ստուգումը գտնում է, որ մակարդակդ հասել է |
| 03 | Գները հիմնական էկրանին | Դիտացանկ կամ մեկ մետաղադրամ վիջեթով – առանց բացելու |
| 04 | Ի՞նչն է շարժվում դրա հետ | Ծավալ, շուկա և ֆյուչերսներ համառոտ – առանց կանխատեսման |
| 05 | Պորտֆել, եթե ուզում ես | Ըստ ցանկության, Կարգավորումներում՝ գնում, վաճառք, փոխանցում |

### id – Indonesisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Harga terakhir dari bursa | Tanpa akun, tanpa iklan – jumlah tetap di perangkat Anda |
| 02 | Alarm yang memberi tahu sekali | Memberi tahu saat pemeriksaan mendapati levelmu tercapai |
| 03 | Harga di layar utama | Daftar pantau atau satu koin di widget, tanpa buka aplikasi |
| 04 | Apa yang ikut bergerak? | Volume, pasar, dan futures singkatnya – tanpa prediksi |
| 05 | Portofolio, jika mau | Opsional, aktifkan di Pengaturan: beli, jual, transfer |

### is-IS – Isländisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Nýjasta verð frá kauphöllinni | Án aðgangs og auglýsinga – upphæðir haldast í tækinu |
| 02 | Viðvörun sem lætur vita einu sinni | Lætur vita þegar athugun finnur að markinu er náð |
| 03 | Verð á heimaskjánum | Vaktlisti eða ein mynt sem græja – án þess að opna forritið |
| 04 | Hvað hreyfist með? | Velta, markaður og framvirkt í stuttu máli – engin spá |
| 05 | Eignasafn, ef þú vilt | Valfrjálst, kveiktu í Stillingum: kaup, sala, millifærslur |

### it-IT – Italienisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Ultimo prezzo dall’exchange | Senza account né pubblicità – importi solo sul dispositivo |
| 02 | Un allarme, un solo avviso | Ti avvisa quando un controllo trova raggiunto il livello |
| 03 | Prezzi nella schermata Home | Lista o una moneta come widget, senza aprire l’app |
| 04 | Cosa si muove insieme? | Volume, mercato e futures in breve, senza previsioni |
| 05 | Portafoglio, se vuoi | Facoltativo: acquisti, vendite e trasferimenti |

### iw-IL – Hebräisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | מחיר אחרון מהבורסה | בלי חשבון, בלי פרסומות – הסכומים נשארים במכשיר |
| 02 | התראה שמודיעה פעם אחת | מודיע כשבדיקה מגלה שהרמה שלך הושגה |
| 03 | מחירים במסך הבית | רשימת מעקב או מטבע אחד כווידג׳ט – בלי לפתוח את האפליקציה |
| 04 | מה זז יחד איתו? | מחזור, שוק וחוזים עתידיים בקצרה – בלי תחזית |
| 05 | תיק השקעות, אם רוצים | אופציונלי, בהגדרות: קניות, מכירות, העברות |

### ja-JP – Japanisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | 直近価格は取引所から直接 | アカウント不要・広告なし。金額は端末の外に出ません |
| 02 | 一度だけ知らせるアラーム | 確認のときに設定した水準に達していればお知らせ |
| 03 | ホーム画面で価格をチェック | ウォッチリストや1つのコインをウィジェットに。アプリを開かずに |
| 04 | 一緒に動いているのは？ | 出来高・市場・先物から短くまとめ。予測はしません |
| 05 | ポートフォリオは必要なときに | 任意・設定でオン：購入、売却、送金 |

### ka-GE – Georgisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | ბოლო ფასი ბირჟიდან | ანგარიშისა და რეკლამის გარეშე – თანხები მოწყობილობაზე რჩება |
| 02 | განგაში, რომელიც ერთხელ გეტყვის | გაცნობებს, როცა შემოწმება აჩვენებს, რომ შენი დონე მიღწეულია |
| 03 | ფასები მთავარ ეკრანზე | დაკვირვების სია ან ერთი მონეტა ვიჯეტად – აპის გახსნის გარეშე |
| 04 | რა მოძრაობს მასთან ერთად? | მოცულობა, ბაზარი და ფიუჩერსები მოკლედ – პროგნოზის გარეშე |
| 05 | პორტფელი, თუ გინდა | არჩევითი, ჩართე პარამეტრებში: ყიდვა, გაყიდვა, გადარიცხვა |

### kk – Kasachisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Биржадан соңғы баға | Тіркелгі мен жарнама жоқ – сомалар құрылғыңызда қалады |
| 02 | Бір рет хабарлайтын ескерту | Тексеру шегіңізге жеткенін тапқанда хабарлайды |
| 03 | Бағалар басты экранда | Бақылау тізімі не бір монета виджетте – қолданбаны ашпай |
| 04 | Онымен бірге не қозғалады? | Көлем, нарық және фьючерстер қысқаша – болжамсыз |
| 05 | Портфель, қаласаңыз | Қосымша, Параметрлерде қосыңыз: сатып алу, сату, аударым |

### km-KH – Khmer

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | តម្លៃចុងក្រោយពីផ្សារប្តូរ | ឥតគណនី ឥតការផ្សាយ – ចំនួនទឹកប្រាក់នៅលើឧបករណ៍របស់អ្នក |
| 02 | ការដាស់តឿនដែលប្រាប់តែម្តង | ប្រាប់អ្នកនៅពេលការពិនិត្យរកឃើញថាកម្រិតរបស់អ្នកត្រូវបានឈានដល់ |
| 03 | តម្លៃនៅលើអេក្រង់ដើម | បញ្ជីតាមដាន ឬកាក់មួយជាវិដជិត – ដោយមិនបើកកម្មវិធី |
| 04 | អ្វីកំពុងផ្លាស់ទីជាមួយវា? | បរិមាណ ទីផ្សារ និង futures ដោយសង្ខេប – គ្មានការព្យាករ |
| 05 | ផលប័ត្រ ប្រសិនបើអ្នកចង់ | ជាជម្រើស បើកក្នុងការកំណត់៖ ទិញ លក់ ផ្ទេរ |

### kn-IN – Kannada

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | ಎಕ್ಸ್‌ಚೇಂಜ್‌ನಿಂದ ಇತ್ತೀಚಿನ ಬೆಲೆ | ಖಾತೆ ಇಲ್ಲ, ಜಾಹೀರಾತು ಇಲ್ಲ – ಮೊತ್ತಗಳು ನಿಮ್ಮ ಸಾಧನದಲ್ಲೇ |
| 02 | ಒಮ್ಮೆ ತಿಳಿಸುವ ಅಲರ್ಟ್ | ಪರಿಶೀಲನೆಯಲ್ಲಿ ನಿಮ್ಮ ಮಟ್ಟ ತಲುಪಿದಾಗ ತಿಳಿಸುತ್ತದೆ |
| 03 | ಹೋಮ್ ಸ್ಕ್ರೀನ್‌ನಲ್ಲಿ ಬೆಲೆಗಳು | ವಾಚ್‌ಲಿಸ್ಟ್ ಅಥವಾ ಒಂದು ಕಾಯಿನ್ ವಿಜೆಟ್ ಆಗಿ – ಆ್ಯಪ್ ತೆರೆಯದೆ |
| 04 | ಜೊತೆಗೆ ಏನು ಚಲಿಸುತ್ತಿದೆ? | ವಾಲ್ಯೂಮ್, ಮಾರುಕಟ್ಟೆ, ಫ್ಯೂಚರ್ಸ್ ಕಿರುನೋಟ – ಮುನ್ಸೂಚನೆ ಇಲ್ಲ |
| 05 | ಬೇಕಿದ್ದರೆ ಪೋರ್ಟ್‌ಫೋಲಿಯೊ | ಐಚ್ಛಿಕ, ಸೆಟ್ಟಿಂಗ್‌ಗಳಲ್ಲಿ ಆನ್ ಮಾಡಿ: ಖರೀದಿ, ಮಾರಾಟ, ವರ್ಗಾವಣೆ |

### ko-KR – Koreanisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | 거래소에서 바로 온 최근 가격 | 계정·광고 없음 – 금액은 기기에만 남습니다 |
| 02 | 한 번만 알려 주는 경보 | 확인할 때 기준에 도달해 있으면 알려 줍니다 |
| 03 | 홈 화면에서 바로 가격 확인 | 관심 목록이나 코인 하나를 위젯으로 – 앱을 열지 않고 |
| 04 | 함께 움직이는 것은? | 거래량·시장·선물 데이터를 짧게 요약, 예측 없음 |
| 05 | 원할 때만 포트폴리오 | 선택 사항, 설정에서 켜기: 매수, 매도, 이체 |

### lo-LA – Laotisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | ລາຄາລ່າສຸດຈາກກະດານແລກປ່ຽນ | ບໍ່ມີບັນຊີ ບໍ່ມີໂຄສະນາ – ຈຳນວນຢູ່ໃນອຸປະກອນຂອງທ່ານ |
| 02 | ສັນຍານເຕືອນທີ່ແຈ້ງເທື່ອດຽວ | ແຈ້ງເມື່ອການກວດພົບວ່າຮອດລະດັບຂອງທ່ານແລ້ວ |
| 03 | ລາຄາໃນໜ້າຈໍຫຼັກ | ລາຍການຕິດຕາມ ຫຼື ຫຼຽນດຽວເປັນວິດເຈັດ – ບໍ່ຕ້ອງເປີດແອັບ |
| 04 | ມີຫຍັງເໜັງຕີງນຳ? | ປະລິມານ ຕະຫຼາດ ແລະ futures ໂດຍຫຍໍ້ – ບໍ່ມີການຄາດຄະເນ |
| 05 | ພອດໂຟລິໂອ ຖ້າທ່ານຕ້ອງການ | ທາງເລືອກ ເປີດໃນການຕັ້ງຄ່າ: ຊື້ ຂາຍ ໂອນ |

### lt – Litauisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Paskutinė kaina iš biržos | Be paskyros, be reklamų – sumos lieka tavo įrenginyje |
| 02 | Įspėjimas, pranešantis kartą | Praneša, kai tikrinimas randa tavo ribą pasiektą |
| 03 | Kainos pagrindiniame ekrane | Stebimi ar viena moneta valdiklyje – neatidarius programėlės |
| 04 | Kas juda kartu? | Apimtis, rinka ir ateities sandoriai trumpai – be prognozių |
| 05 | Portfelis, jei nori | Įjungiamas nustatymuose: pirkimai, pardavimai, pervedimai |

### lv – Lettisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Pēdējā cena no biržas | Bez konta, bez reklāmām – summas paliek tavā ierīcē |
| 02 | Brīdinājums, kas ziņo vienreiz | Paziņo, kad pārbaude konstatē, ka tavs slieksnis sasniegts |
| 03 | Cenas sākuma ekrānā | Vērojamie vai viena monēta logrīkā – neatverot lietotni |
| 04 | Kas kustas līdzi? | Apjoms, tirgus un nākotnes līgumi īsumā – bez prognozēm |
| 05 | Portfelis, ja vēlies | Pēc izvēles, iestatījumos: pirkumi, pārdošana, pārvedumi |

### mk-MK – Mazedonisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Последна цена од берзата | Без сметка, без реклами – износите остануваат на уредот |
| 02 | Аларм што те известува еднаш | Те известува кога проверка ќе ја најде границата достигната |
| 03 | Цени на почетниот екран | Листа за следење или една монета како виџет – без отворање |
| 04 | Што се движи заедно? | Волумен, пазар и фјучерси накратко – без прогноза |
| 05 | Портфолио, ако сакаш | Опционално, во Поставки: купувања, продажби, трансфери |

### ml-IN – Malayalam

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | എക്സ്ചേഞ്ചിൽനിന്ന് പുതിയ വില | അക്കൗണ്ടില്ല, പരസ്യമില്ല – തുകകൾ നിങ്ങളുടെ ഉപകരണത്തിൽ |
| 02 | ഒരിക്കൽ അറിയിക്കുന്ന അലേർട്ട് | പരിശോധനയിൽ നിങ്ങളുടെ നില എത്തിയാൽ അറിയിക്കുന്നു |
| 03 | ഹോം സ്ക്രീനിൽ വിലകൾ | വാച്ച്‌ലിസ്റ്റോ ഒരു കോയിനോ വിജറ്റായി – ആപ്പ് തുറക്കാതെ |
| 04 | ഒപ്പം എന്താണ് നീങ്ങുന്നത്? | വോളിയം, മാർക്കറ്റ്, ഫ്യൂച്ചേഴ്സ് ചുരുക്കത്തിൽ – പ്രവചനമില്ല |
| 05 | വേണമെങ്കിൽ പോർട്ട്ഫോളിയോ | ഐച്ഛികം, ക്രമീകരണത്തിൽ ഓണാക്കുക: വാങ്ങൽ, വിൽപ്പന, കൈമാറ്റം |

### mn-MN – Mongolisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Биржийн сүүлийн ханш | Бүртгэлгүй, зар сурталчилгаагүй – дүн төхөөрөмжид үлдэнэ |
| 02 | Нэг удаа мэдэгддэг дохио | Шалгалтаар таны босгод хүрснийг илрүүлэхэд мэдэгдэнэ |
| 03 | Ханш үндсэн дэлгэц дээр | Ажиглах жагсаалт эсвэл нэг зоос виджетээр – апп нээлгүй |
| 04 | Түүнтэй хамт юу хөдөлж байна? | Хэмжээ, зах зээл, фьючерс товчоор – таамаглалгүй |
| 05 | Багц, хүсвэл | Тохиргоонд асаана: худалдан авалт, борлуулалт, шилжүүлэг |

### mr-IN – Marathi

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | एक्सचेंजवरून ताजी किंमत | खाते नाही, जाहिराती नाहीत – रक्कम तुमच्या डिव्हाइसवरच राहते |
| 02 | एकदाच सांगणारा अलर्ट | तपासणीत तुमची पातळी गाठलेली दिसली की कळवतो |
| 03 | होम स्क्रीनवर किमती | वॉचलिस्ट किंवा एक कॉइन विजेट म्हणून – ॲप न उघडता |
| 04 | सोबत काय हलत आहे? | व्हॉल्यूम, बाजार आणि फ्युचर्स थोडक्यात – अंदाज नाही |
| 05 | पोर्टफोलिओ, हवे असल्यास | पर्यायी, सेटिंग्जमध्ये सुरू करा: खरेदी, विक्री, हस्तांतरण |

### ms – Malaiisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Harga terakhir dari bursa | Tanpa akaun, tanpa iklan – amaun kekal pada peranti anda |
| 02 | Amaran yang memberitahu sekali | Memberitahu apabila semakan mendapati paras anda dicapai |
| 03 | Harga pada skrin utama | Senarai pantau atau satu koin dalam widget, tanpa buka apl |
| 04 | Apa yang bergerak bersama? | Volum, pasaran dan niaga hadapan ringkasnya – tanpa ramalan |
| 05 | Portfolio, jika anda mahu | Pilihan, hidupkan dalam Tetapan: belian, jualan, pindahan |

### my-MM – Birmanisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | လဲလှယ်ရေးဌာနမှ နောက်ဆုံးဈေး | အကောင့်မလို၊ ကြော်ငြာမဲ့ – ပမာဏများ သင့်စက်ပေါ်တွင်သာ |
| 02 | တစ်ကြိမ်သာ အသိပေးသော သတိပေးချက် | စစ်ဆေးမှုက သင့်အဆင့်ရောက်ကြောင်း တွေ့သောအခါ အသိပေးသည် |
| 03 | ပင်မစခရင်ပေါ်တွင် ဈေးနှုန်း | စောင့်ကြည့်စာရင်း (သို့) ဒင်္ဂါး ဝီဂျက် – အက်ပ်မဖွင့်ဘဲ |
| 04 | ၎င်းနှင့်အတူ ဘာ လှုပ်ရှားနေသလဲ။ | ပမာဏ၊ ဈေးကွက်နှင့် futures အတိုချုပ် – ခန့်မှန်းချက်မပါ |
| 05 | လိုလျှင် ပေါ့ဖိုလီယို | ရွေးချယ်နိုင်၊ ဆက်တင်တွင် ဖွင့်ပါ- ဝယ်၊ ရောင်း၊ လွှဲပြောင်း |

### ne-NP – Nepali

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | एक्सचेन्जबाट पछिल्लो मूल्य | खाता छैन, विज्ञापन छैन – रकम तपाईंकै यन्त्रमा रहन्छ |
| 02 | एक पटक मात्र जानकारी दिने अलर्ट | जाँचमा तपाईंको सीमा पुगेको भेटिँदा जानकारी दिन्छ |
| 03 | होम स्क्रिनमा मूल्य | वाचलिस्ट वा एउटा कोइन विजेटको रूपमा – एप नखोली |
| 04 | यससँगै के चलिरहेको छ? | भोल्युम, बजार र फ्युचर्स छोटकरीमा – कुनै पूर्वानुमान छैन |
| 05 | पोर्टफोलियो, चाहेमा | ऐच्छिक, सेटिङहरूमा: खरिद, बिक्री, स्थानान्तरण |

### nl-NL – Niederländisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Laatste koers van de beurs | Geen account, geen reclame – bedragen blijven op je toestel |
| 02 | Een alarm dat één keer meldt | Meldt zich als een controle ziet dat je grens is bereikt |
| 03 | Koersen op je beginscherm | Volglijst of één munt als widget – zonder de app te openen |
| 04 | Wat beweegt mee? | Volume, markt en futures kort samengevat – geen voorspelling |
| 05 | Portfolio, als je wilt | Optioneel, via instellingen: aankopen, verkopen, transfers |

### no-NO – Norwegisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Siste kurs fra børsen | Ingen konto, ingen reklame – beløp blir på enheten |
| 02 | En alarm som sier fra én gang | Gir beskjed når en sjekk viser at nivået ditt er nådd |
| 03 | Kurser på startskjermen | Listen eller én mynt som widget – uten å åpne appen |
| 04 | Hva beveger seg med? | Volum, marked og futures kort fortalt – uten prognose |
| 05 | Portefølje, om du vil | Valgfritt, slås på i innstillinger: kjøp, salg, overføringer |

### pa – Panjabi

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | ਐਕਸਚੇਂਜ ਤੋਂ ਤਾਜ਼ਾ ਕੀਮਤ | ਨਾ ਖਾਤਾ, ਨਾ ਇਸ਼ਤਿਹਾਰ – ਰਕਮਾਂ ਡਿਵਾਈਸ 'ਤੇ ਹੀ ਰਹਿੰਦੀਆਂ ਹਨ |
| 02 | ਇੱਕ ਵਾਰ ਦੱਸਣ ਵਾਲਾ ਅਲਰਟ | ਜਾਂਚ ਵਿੱਚ ਤੁਹਾਡਾ ਪੱਧਰ ਪੂਰਾ ਹੋਣ 'ਤੇ ਦੱਸਦਾ ਹੈ |
| 03 | ਹੋਮ ਸਕ੍ਰੀਨ 'ਤੇ ਕੀਮਤਾਂ | ਵਾਚਲਿਸਟ ਜਾਂ ਇੱਕ ਕੌਇਨ ਵਿਜੇਟ ਵਜੋਂ – ਐਪ ਖੋਲ੍ਹੇ ਬਿਨਾਂ |
| 04 | ਨਾਲ ਹੋਰ ਕੀ ਹਿੱਲ ਰਿਹਾ ਹੈ? | ਵਾਲੀਅਮ, ਬਜ਼ਾਰ ਅਤੇ ਫਿਊਚਰਜ਼ ਸੰਖੇਪ ਵਿੱਚ – ਕੋਈ ਭਵਿੱਖਬਾਣੀ ਨਹੀਂ |
| 05 | ਪੋਰਟਫੋਲੀਓ, ਜੇ ਚਾਹੋ | ਵਿਕਲਪਿਕ, ਸੈਟਿੰਗਾਂ ਵਿੱਚ ਚਾਲੂ ਕਰੋ: ਖਰੀਦ, ਵਿਕਰੀ, ਟ੍ਰਾਂਸਫਰ |

### pl-PL – Polnisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Ostatnia cena z giełdy | Bez konta i reklam – kwoty zostają na urządzeniu |
| 02 | Alarm, który powiadamia raz | Daje znać, gdy sprawdzenie wykaże osiągnięty poziom |
| 03 | Ceny na ekranie głównym | Lista lub moneta jako widżet – bez otwierania aplikacji |
| 04 | Co rusza się razem? | Wolumen, rynek i futures w skrócie – bez prognoz |
| 05 | Portfel, jeśli chcesz | Opcjonalnie, w ustawieniach: kupno, sprzedaż, transfery |

### pt-BR – Portugiesisch (Brasilien)

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Último preço da exchange | Sem conta, sem anúncios – os valores ficam no aparelho |
| 02 | Um alarme que avisa uma vez | Avisa quando uma verificação encontra seu nível atingido |
| 03 | Preços na tela inicial | Lista ou uma moeda como widget, sem abrir o app |
| 04 | O que se move junto? | Volume, mercado e futuros em resumo, sem previsões |
| 05 | Portfólio, se você quiser | Opcional, ative nos Ajustes: compras, vendas, transferências |

### pt-PT – Portugiesisch (Portugal)

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Último preço da exchange | Sem conta nem anúncios – os montantes ficam no dispositivo |
| 02 | Um alarme que avisa uma vez | Avisa quando uma verificação encontra o teu nível atingido |
| 03 | Preços no ecrã principal | Lista ou uma moeda como widget, sem abrir a app |
| 04 | O que se mexe com ele? | Volume, mercado e futuros em resumo, sem previsões |
| 05 | Portefólio, se quiser | Opcional, nas Definições: compras, vendas, transferências |

### ro – Rumänisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Ultimul preț de la bursă | Fără cont, fără reclame – sumele rămân pe dispozitiv |
| 02 | O alarmă care anunță o dată | Te anunță când o verificare găsește nivelul atins |
| 03 | Prețuri pe ecranul principal | Lista sau o monedă ca widget – fără să deschizi aplicația |
| 04 | Ce se mișcă odată cu el? | Volum, piață și futures pe scurt – fără prognoze |
| 05 | Portofoliu, dacă vrei | Opțional, din setări: cumpărări, vânzări, transferuri |

### ru-RU – Russisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Последняя цена с биржи | Без аккаунта и рекламы – суммы остаются на устройстве |
| 02 | Одно оповещение – один раз | Сообщает, когда проверка видит, что уровень достигнут |
| 03 | Цены на главном экране | Список или одна монета в виджете – без открытия приложения |
| 04 | Что движется вместе? | Объём, рынок и фьючерсы коротко – без прогнозов |
| 05 | Портфель – по желанию | По желанию, в настройках: покупки, продажи, переводы |

### si-LK – Singhalesisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | හුවමාරුවෙන් නවතම මිල | ගිණුමක් නැත, දැන්වීම් නැත – මුදල් ඔබේ උපාංගයේම රැඳේ |
| 02 | එක් වරක් දන්වන ඇඟවීම | පරීක්ෂාවකදී ඔබේ සීමාවට ළඟා වූ විට දන්වයි |
| 03 | මුල් තිරයේ මිල ගණන් | නිරීක්ෂණ ලැයිස්තුව හෝ කාසියක් විජට් ලෙස – යෙදුම නොවිවෘත කර |
| 04 | ඒ සමඟ චලනය වන්නේ කුමක්ද? | පරිමාව, වෙළඳපොළ සහ ෆියුචර්ස් කෙටියෙන් – අනාවැකි නැත |
| 05 | කැමති නම් කළඹ | විකල්ප, සැකසීම් තුළ: මිලදී ගැනීම්, විකිණීම්, මාරු |

### sk – Slowakisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Posledný kurz z burzy | Bez účtu, bez reklám – sumy zostávajú v tvojom zariadení |
| 02 | Alarm, ktorý dá vedieť raz | Ozve sa, keď kontrola zistí, že je hranica dosiahnutá |
| 03 | Kurzy na domovskej obrazovke | Zoznam alebo jedna minca ako miniaplikácia – bez otvorenia |
| 04 | Čo sa hýbe spolu? | Objem, trh a futures v skratke – bez prognózy |
| 05 | Portfólio, ak chceš | Voliteľné, zapni v Nastaveniach: nákupy, predaje, presuny |

### sl – Slowenisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Zadnji tečaj z borze | Brez računa, brez oglasov – zneski ostanejo v tvoji napravi |
| 02 | Alarm, ki sporoči enkrat | Sporoči, ko preverjanje ugotovi, da je tvoja meja dosežena |
| 03 | Tečaji na začetnem zaslonu | Seznam ali en kovanec kot pripomoček – brez odpiranja |
| 04 | Kaj se giblje zraven? | Volumen, trg in futures na kratko – brez napovedi |
| 05 | Portfelj, če želiš | Izbirno, vklopi v Nastavitvah: nakupi, prodaje, prenosi |

### sq – Albanisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Çmimi i fundit nga bursa | Pa llogari, pa reklama – shumat mbeten në pajisje |
| 02 | Një alarm që njofton një herë | Të njofton kur një kontroll gjen nivelin të arritur |
| 03 | Çmimet në ekranin kryesor | Lista ose një monedhë si widget – pa hapur aplikacionin |
| 04 | Çfarë lëviz bashkë? | Vëllimi, tregu dhe futures shkurt – pa parashikime |
| 05 | Portofol, nëse do | Opsionale, nga cilësimet: blerje, shitje, transferime |

### sr – Serbisch (kyrillisch)

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Последња цена с берзе | Без налога, без огласа – износи остају на твом уређају |
| 02 | Аларм који јавља једном | Јавља када провера покаже да је твој ниво достигнут |
| 03 | Цене на почетном екрану | Списак праћења или један коин као виџет – без отварања |
| 04 | Шта се креће с њом? | Волумен, тржиште и фјучерс укратко – без прогнозе |
| 05 | Портфолио, ако желиш | Опционо, укључи у Подешавањима: куповине, продаје, преноси |

### sv-SE – Schwedisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Senaste kursen från börsen | Inget konto, ingen reklam – beloppen stannar på enheten |
| 02 | Larm som meddelar en gång | Meddelar när en kontroll visar att din nivå är nådd |
| 03 | Kurser på hemskärmen | Listan eller ett mynt som widget – utan att öppna appen |
| 04 | Vad rör sig med? | Volym, marknad och terminer kort sagt – utan prognos |
| 05 | Portfölj, om du vill | Valfritt, i inställningar: köp, försäljning, överföringar |

### sw – Suaheli

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Bei ya mwisho kutoka jukwaa | Bila akaunti wala matangazo – kiasi kinabaki kwenye kifaa |
| 02 | Kengele inayojulisha mara moja | Hukujulisha ukaguzi unapokuta kiwango chako kimefikiwa |
| 03 | Bei kwenye skrini ya mwanzo | Ufuatiliaji au sarafu kama wijeti – bila kufungua programu |
| 04 | Nini kinasonga pamoja nayo? | Kiasi, soko na futures kwa ufupi – bila utabiri |
| 05 | Jalada, ukipenda | Hiari, washa kwenye Mipangilio: kununua, kuuza, uhamisho |

### ta-IN – Tamil

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | எக்ஸ்சேஞ்சிலிருந்து புதிய விலை | கணக்கு இல்லை, விளம்பரம் இல்லை – தொகைகள் உங்கள் சாதனத்தில் |
| 02 | ஒருமுறை சொல்லும் விழிப்பூட்டல் | சரிபார்ப்பில் உங்கள் அளவு எட்டியதும் தெரிவிக்கும் |
| 03 | முகப்புத் திரையில் விலைகள் | பட்டியல் அல்லது ஒரு காயின் விட்ஜெட்டாக – ஆப் திறக்காமல் |
| 04 | கூடவே எது நகர்கிறது? | வால்யூம், சந்தை, ஃபியூச்சர்ஸ் சுருக்கமாக – கணிப்பு இல்லை |
| 05 | வேண்டுமானால் போர்ட்ஃபோலியோ | விருப்பம், அமைப்புகளில்: வாங்குதல், விற்பனை, பரிமாற்றம் |

### te-IN – Telugu

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | ఎక్స్ఛేంజ్ నుండి తాజా ధర | ఖాతా లేదు, ప్రకటనలు లేవు – మొత్తాలు మీ పరికరంలోనే |
| 02 | ఒక్కసారి చెప్పే అలర్ట్ | తనిఖీలో మీ స్థాయి చేరినట్లు తేలితే తెలియజేస్తుంది |
| 03 | హోమ్ స్క్రీన్‌పై ధరలు | వాచ్‌లిస్ట్ లేదా ఒక కాయిన్ విడ్జెట్‌గా – యాప్ తెరవకుండానే |
| 04 | దానితో పాటు ఏది కదులుతోంది? | వాల్యూమ్, మార్కెట్, ఫ్యూచర్స్ క్లుప్తంగా – అంచనా లేదు |
| 05 | కావాలంటే పోర్ట్‌ఫోలియో | ఐచ్ఛికం, సెట్టింగ్‌లలో: కొనుగోళ్లు, అమ్మకాలు, బదిలీలు |

### th – Thailändisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | ราคาล่าสุดจากกระดานเทรด | ไม่ต้องมีบัญชี ไม่มีโฆษณา จำนวนเงินอยู่บนเครื่องคุณ |
| 02 | แจ้งเตือนครั้งเดียว | แจ้งเมื่อการตรวจพบว่าราคาถึงระดับที่ตั้งไว้ |
| 03 | ราคาบนหน้าจอหลัก | รายการติดตามหรือเหรียญเดียวเป็นวิดเจ็ต ไม่ต้องเปิดแอป |
| 04 | อะไรเคลื่อนไหวไปด้วย? | วอลุ่ม ตลาด และฟิวเจอร์ส สรุปสั้น ๆ ไม่พยากรณ์ |
| 05 | พอร์ตโฟลิโอ ถ้าต้องการ | ไม่บังคับ เปิดในการตั้งค่า: ซื้อ ขาย โอน |

### tr-TR – Türkisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Borsadan son fiyat | Hesap yok, reklam yok – tutarlar cihazında kalır |
| 02 | Bir kez haber veren alarm | Bir kontrol seviyene ulaşıldığını görünce haber verir |
| 03 | Ana ekranda fiyatlar | Liste veya tek coin widget olarak – uygulamayı açmadan |
| 04 | Onunla birlikte ne hareket ediyor? | Hacim, piyasa ve vadeli kısaca – tahmin yok |
| 05 | İstersen portföy | İsteğe bağlı, ayarlardan: alım, satım, transfer |

### uk – Ukrainisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Остання ціна з біржі | Без акаунта й реклами – суми лишаються на пристрої |
| 02 | Сигнал, що спрацьовує раз | Повідомляє, коли перевірка бачить, що рівень досягнуто |
| 03 | Ціни на головному екрані | Список або одна монета у віджеті – без відкриття застосунку |
| 04 | Що рухається разом? | Обсяг, ринок і ф’ючерси коротко – без прогнозів |
| 05 | Портфель – за бажанням | За бажанням, у налаштуваннях: купівлі, продажі, перекази |

### ur – Urdu

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | ایکسچینج سے تازہ ترین قیمت | نہ اکاؤنٹ، نہ اشتہار – رقوم آپ کے آلے پر ہی رہتی ہیں |
| 02 | ایک بار بتانے والا الرٹ | جانچ میں آپ کی سطح چھو جانے پر بتاتا ہے |
| 03 | ہوم اسکرین پر قیمتیں | واچ لسٹ یا ایک کوائن بطور ویجیٹ – ایپ کھولے بغیر |
| 04 | ساتھ اور کیا حرکت کر رہا ہے؟ | والیوم، مارکیٹ اور فیوچرز مختصراً – کوئی پیش گوئی نہیں |
| 05 | پورٹ فولیو، اگر آپ چاہیں | اختیاری، ترتیبات میں آن کریں: خرید، فروخت، ٹرانسفر |

### uz – Usbekisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Birjadan soʻnggi narx | Akkauntsiz, reklamasiz – summalar qurilmangizda qoladi |
| 02 | Bir marta xabar beruvchi signal | Tekshiruv darajangizga yetilganini topganda xabar beradi |
| 03 | Narxlar bosh ekranda | Kuzatuv roʻyxati yoki tanga vidjetda – ilovani ochmasdan |
| 04 | U bilan nima harakatlanmoqda? | Hajm, bozor va fyucherslar qisqacha – bashoratsiz |
| 05 | Portfel, xohlasangiz | Ixtiyoriy, Sozlamalarda: xaridlar, sotuvlar, oʻtkazmalar |

### vi – Vietnamesisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Giá gần nhất từ sàn | Không tài khoản, không quảng cáo – số tiền ở lại máy bạn |
| 02 | Cảnh báo chỉ báo một lần | Báo khi lần kiểm tra thấy mức của bạn đã đạt |
| 03 | Giá ngay trên màn hình chính | Danh sách hoặc một coin dạng widget – không cần mở app |
| 04 | Cái gì biến động cùng? | Khối lượng, thị trường, futures – ngắn gọn, không dự đoán |
| 05 | Danh mục, nếu bạn muốn | Tùy chọn, bật trong Cài đặt: mua, bán, chuyển |

### yue – Kantonesisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | 交易所嘅最新價 | 唔使帳戶、冇廣告，金額留喺你部裝置 |
| 02 | 只通知一次嘅提示 | 檢查時發現到咗你設定嘅水平就通知你 |
| 03 | 主畫面睇價 | 自選清單或者單一幣種做小工具，唔使開 app |
| 04 | 有乜嘢一齊郁？ | 成交量、大市同期貨，簡短總結，唔做預測 |
| 05 | 想用先開投資組合 | 可選，喺設定開啟：買入、賣出、轉賬 |

### zh-CN – Chinesisch (vereinfacht)

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | 最新价来自交易所 | 无需账户，没有广告，金额不离开设备 |
| 02 | 只通知一次的提醒 | 检查时发现达到你设定的水平就通知你 |
| 03 | 主屏幕上看价格 | 自选列表或单个币种做成小组件，无需打开应用 |
| 04 | 哪些在同步波动？ | 成交量、大盘和合约数据，简短总结，不做预测 |
| 05 | 需要时再用投资组合 | 可选，在设置中开启：买入、卖出、转账 |

### zh-TW – Chinesisch (traditionell)

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | 最新價來自交易所 | 無需帳戶，沒有廣告，金額不離開裝置 |
| 02 | 只通知一次的提醒 | 檢查時發現達到你設定的水準就通知你 |
| 03 | 主畫面上看價格 | 自選清單或單個幣種做成小工具，無需開啟應用程式 |
| 04 | 哪些在同步波動？ | 成交量、大盤和合約資料，簡短總結，不做預測 |
| 05 | 需要時再用投資組合 | 可選，在設定中開啟：買入、賣出、轉帳 |

### zu – Zulu

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Intengo yokugcina ye-exchange | Ayikho i-akhawunti noma izikhangiso – amanani asedivayisini |
| 02 | I-alamu ekwazisa kanye | Ikwazisa uma ukuhlola kuthola izinga lakho lifinyelelwe |
| 03 | Izintengo esikrinini sasekhaya | Uhlu lokubuka noma i-coin njengewijethi – ungavuli i-app |
| 04 | Yini enyakaza kanye nayo? | Ivolumu, imakethe nama-futures kafushane – akukho kubikezela |
| 05 | I-portfolio, uma uthanda | Ku-Izilungiselelo: ukuthenga, ukuthengisa, ukudlulisa |
