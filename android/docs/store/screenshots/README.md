# Screenshot-Kit für Google Play und App Store

Version 16.2.2 (Build 75). Dieses Kit enthält alles, um die Store-Screenshots in allen Sprachen gleich aussehen zu lassen:

| Datei | Zweck |
|---|---|
| `demo-backup.json` | Demo-Daten zum Wiederherstellen (Android und iOS) |
| `captions.json` | Titel (≤ 30 Zeichen, hart max. 40) und Untertitel (≤ 60 Zeichen) pro Szene, 31 Sprachen (inkl. pt-BR) – Eingabe für das Rahmen-Skript; dieselben Texte als Tabellen unten in «Beschriftungen je Sprache» |
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
bleiben, Szene 02 zitiert den Alarm-Satz, Szene 03 sagt, dass Widgets den Kurs ohne Öffnen der App zeigen,
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
- Notiz bei BTC: «Ø 77 500 USDT» (sprachneutral)
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
Untertitel ≤ 60 Zeichen. Untertitel 02 ist der Alarm-Satz aus dem bisherigen Kit, Titel 04 «Was bewegt sich mit?»
entspricht dem Titel des Blatts in der App.

### ar – Arabisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | آخر سعر من المنصة | بلا حساب ولا إعلانات – المبالغ تبقى على جهازك |
| 02 | تنبيه يُعلمك مرة واحدة | «أبلغني عندما ينخفض BTC تحت 90,000 CHF» |
| 03 | الأسعار على الشاشة الرئيسية | قائمة المتابعة أو عملة واحدة كأداة – دون فتح التطبيق |
| 04 | ما الذي يتحرك معه؟ | الحجم والسوق والعقود الآجلة باختصار – بلا توقعات |
| 05 | المحفظة إن أردتها | اختياري، يُفعَّل من الإعدادات: شراء وبيع وتحويلات |

### cs-CZ – Tschechisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Poslední cena z burzy | Bez účtu a reklam – částky zůstávají v zařízení |
| 02 | Alarm, který upozorní jednou | „Upozornit, až BTC klesne pod 90 000 CHF“ |
| 03 | Ceny na ploše | Seznam nebo jedna mince jako widget – bez otevření aplikace |
| 04 | Co se hýbe s ním? | Objem, trh a futures ve zkratce – bez prognóz |
| 05 | Portfolio, když chcete | Volitelné, zapnete v nastavení: nákupy, prodeje, převody |

### da-DK – Dänisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Seneste kurs fra børsen | Ingen konto, ingen reklamer – beløb bliver på enheden |
| 02 | En alarm, der melder én gang | »Giv mig besked, når BTC falder under 90.000 CHF« |
| 03 | Kurser på startskærmen | Listen eller én coin som widget – uden at åbne appen |
| 04 | Hvad bevæger sig med? | Volumen, marked og futures kort – uden prognose |
| 05 | Portefølje, hvis du vil | Valgfrit, slås til i indstillinger: køb, salg, overførsler |

### de-DE – Deutsch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Letzter Kurs von der Börse | Ohne Konto, ohne Werbung – Beträge bleiben auf dem Gerät |
| 02 | Ein Alarm, der einmal meldet | «Sag mir Bescheid, wenn BTC unter 90 000 CHF fällt» |
| 03 | Kurse auf dem Startbildschirm | Merkliste oder ein Coin als Widget – ohne die App zu öffnen |
| 04 | Was bewegt sich mit? | Volumen, Markt und Futures kurz gesagt – ohne Prognose |
| 05 | Portfolio, wenn du willst | Optional, in den Einstellungen: Käufe, Verkäufe, Transfers |

### el-GR – Griechisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Η τιμή από το ανταλλακτήριο | Χωρίς λογαριασμό και διαφημίσεις – τα ποσά μένουν σε σένα |
| 02 | Μία ειδοποίηση, μία φορά | «Ειδοποίηση όταν το BTC πέσει κάτω από 90.000 CHF» |
| 03 | Τιμές στην αρχική οθόνη | Λίστα ή ένα νόμισμα σε widget – χωρίς άνοιγμα εφαρμογής |
| 04 | Τι κινείται μαζί; | Όγκος, αγορά και futures με λίγα λόγια – χωρίς πρόβλεψη |
| 05 | Χαρτοφυλάκιο, αν θέλεις | Προαιρετικό, από τις ρυθμίσεις: αγορές, πωλήσεις, μεταφορές |

### en-US – Englisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Last price from the exchange | No account, no ads – amounts stay on your device |
| 02 | An alarm that tells you once | “Tell me when BTC falls below 90,000 CHF” |
| 03 | Prices on your home screen | Watchlist or one coin as a widget – without opening the app |
| 04 | What is moving with it? | Volume, market and futures in short – no forecast |
| 05 | Portfolio, if you want it | Optional, turn on in Settings: buys, sells, transfers |

### es-ES – Spanisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Último precio del exchange | Sin cuenta ni anuncios: los importes no salen del móvil |
| 02 | Una alarma que avisa una vez | «Avísame cuando BTC baje de 90.000 CHF» |
| 03 | Precios en tu pantalla | Tu lista o una moneda como widget, sin abrir la app |
| 04 | ¿Qué se mueve con él? | Volumen, mercado y futuros en resumen, sin pronósticos |
| 05 | Cartera, si la quieres | Opcional, en Ajustes: compras, ventas, transferencias |

### fa – Persisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | آخرین قیمت از صرافی | بدون حساب و تبلیغ – مبالغ روی دستگاه شما می‌ماند |
| 02 | هشداری که یک بار خبر می‌دهد | «وقتی BTC از ۹۰٬۰۰۰ CHF پایین‌تر رفت، خبرم کن» |
| 03 | قیمت‌ها روی صفحهٔ اصلی | واچ‌لیست یا یک کوین به‌صورت ابزارک – بدون باز کردن برنامه |
| 04 | چه چیزی همراه آن حرکت می‌کند؟ | حجم، بازار و فیوچرز به‌طور خلاصه – بدون پیش‌بینی |
| 05 | پرتفوی، اگر بخواهید | اختیاری، از تنظیمات: خرید، فروش، انتقال |

### fi-FI – Finnisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Viimeisin hinta pörssistä | Ei tiliä, ei mainoksia – summat pysyvät laitteellasi |
| 02 | Hälytys, joka ilmoittaa kerran | ”Ilmoita, kun BTC laskee alle 90 000 CHF” |
| 03 | Hinnat aloitusnäytöllä | Lista tai yksi kolikko widgetinä – avaamatta sovellusta |
| 04 | Mikä liikkuu mukana? | Volyymi, markkina ja futuurit lyhyesti – ei ennusteita |
| 05 | Salkku, jos haluat | Valinnainen, asetuksista: ostot, myynnit, siirrot |

### fr-FR – Französisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Dernier cours de la plateforme | Sans compte ni pub – les montants restent sur l’appareil |
| 02 | Une alerte, une seule fois | « Me prévenir quand BTC passe sous 90 000 CHF » |
| 03 | Cours sur l’écran d’accueil | Liste ou une crypto en widget – sans ouvrir l’app |
| 04 | Qu’est-ce qui bouge avec ? | Volume, marché et futures en bref – sans prévision |
| 05 | Portefeuille, si besoin | En option, dans les réglages : achats, ventes, transferts |

### hi-IN – Hindi

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | एक्सचेंज से आख़िरी भाव | बिना खाते, बिना विज्ञापन – रकम डिवाइस पर ही रहती है |
| 02 | एक बार बताने वाला अलार्म | “जब BTC 90,000 CHF से नीचे जाए, तो मुझे बताएँ” |
| 03 | होम स्क्रीन पर भाव | वॉचलिस्ट या एक कॉइन विजेट में – ऐप खोले बिना |
| 04 | साथ में किसमें हलचल है? | वॉल्यूम, बाज़ार और फ़्यूचर्स संक्षेप में – बिना पूर्वानुमान |
| 05 | पोर्टफ़ोलियो, अगर चाहें | वैकल्पिक, सेटिंग्स में चालू करें: ख़रीद, बिक्री, ट्रांसफ़र |

### hu-HU – Ungarisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Utolsó ár a tőzsdéről | Fiók és reklám nélkül – az összegek az eszközön maradnak |
| 02 | Riasztás, amely egyszer szól | „Szólj, ha a BTC 90 000 CHF alá esik” |
| 03 | Árfolyamok a kezdőképernyőn | Lista vagy egy érme widgetként – az app megnyitása nélkül |
| 04 | Mi mozog vele együtt? | Forgalom, piac és futures röviden – előrejelzés nélkül |
| 05 | Portfólió, ha szeretnéd | Opcionális, a beállításokban: vétel, eladás, átutalás |

### id – Indonesisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Harga terakhir dari bursa | Tanpa akun, tanpa iklan – jumlah tetap di perangkat Anda |
| 02 | Alarm yang memberi tahu sekali | “Beri tahu saya saat BTC turun di bawah 90.000 CHF” |
| 03 | Harga di layar utama | Daftar pantau atau satu koin di widget, tanpa buka aplikasi |
| 04 | Apa yang ikut bergerak? | Volume, pasar, dan futures singkatnya – tanpa prediksi |
| 05 | Portofolio, jika mau | Opsional, aktifkan di Pengaturan: beli, jual, transfer |

### it-IT – Italienisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Ultimo prezzo dall’exchange | Senza account né pubblicità – importi solo sul dispositivo |
| 02 | Un allarme, un solo avviso | «Avvisami quando BTC scende sotto 90.000 CHF» |
| 03 | Prezzi nella schermata Home | Lista o una moneta come widget, senza aprire l’app |
| 04 | Cosa si muove insieme? | Volume, mercato e futures in breve, senza previsioni |
| 05 | Portafoglio, se vuoi | Facoltativo: acquisti, vendite e trasferimenti |

### iw-IL – Hebräisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | מחיר אחרון מהבורסה | בלי חשבון, בלי פרסומות – הסכומים נשארים במכשיר |
| 02 | התראה שמודיעה פעם אחת | ״להודיע לי כש-BTC יורד מתחת ל-90,000 CHF״ |
| 03 | מחירים במסך הבית | רשימת מעקב או מטבע אחד כווידג׳ט – בלי לפתוח את האפליקציה |
| 04 | מה זז יחד איתו? | מחזור, שוק וחוזים עתידיים בקצרה – בלי תחזית |
| 05 | תיק השקעות, אם רוצים | אופציונלי, בהגדרות: קניות, מכירות, העברות |

### ja-JP – Japanisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | 直近価格は取引所から直接 | アカウント不要・広告なし。金額は端末の外に出ません |
| 02 | 一度だけ知らせるアラーム | 「BTC が 90,000 CHF を下回ったら知らせる」 |
| 03 | ホーム画面で価格をチェック | ウォッチリストや1つのコインをウィジェットに。アプリを開かずに |
| 04 | 一緒に動いているのは？ | 出来高・市場・先物から短くまとめ。予測はしません |
| 05 | ポートフォリオは必要なときに | 任意・設定でオン：購入、売却、送金 |

### ko-KR – Koreanisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | 거래소에서 바로 온 최근 가격 | 계정·광고 없음 – 금액은 기기에만 남습니다 |
| 02 | 한 번만 알려 주는 경보 | ‘BTC가 90,000 CHF 아래로 내려가면 알려 주세요’ |
| 03 | 홈 화면에서 바로 가격 확인 | 관심 목록이나 코인 하나를 위젯으로 – 앱을 열지 않고 |
| 04 | 함께 움직이는 것은? | 거래량·시장·선물 데이터를 짧게 요약, 예측 없음 |
| 05 | 원할 때만 포트폴리오 | 선택 사항, 설정에서 켜기: 매수, 매도, 이체 |

### nl-NL – Niederländisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Laatste koers van de beurs | Geen account, geen reclame – bedragen blijven op je toestel |
| 02 | Een alarm dat één keer meldt | ‘Laat het me weten als BTC onder 90.000 CHF daalt’ |
| 03 | Koersen op je beginscherm | Volglijst of één munt als widget – zonder de app te openen |
| 04 | Wat beweegt mee? | Volume, markt en futures kort samengevat – geen voorspelling |
| 05 | Portfolio, als je wilt | Optioneel, via instellingen: aankopen, verkopen, transfers |

### no-NO – Norwegisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Siste kurs fra børsen | Ingen konto, ingen reklame – beløp blir på enheten |
| 02 | En alarm som sier fra én gang | «Gi meg beskjed når BTC faller under 90 000 CHF» |
| 03 | Kurser på startskjermen | Listen eller én mynt som widget – uten å åpne appen |
| 04 | Hva beveger seg med? | Volum, marked og futures kort fortalt – uten prognose |
| 05 | Portefølje, om du vil | Valgfritt, slås på i innstillinger: kjøp, salg, overføringer |

### pl-PL – Polnisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Ostatnia cena z giełdy | Bez konta i reklam – kwoty zostają na urządzeniu |
| 02 | Alarm, który powiadamia raz | „Powiadom mnie, gdy BTC spadnie poniżej 90 000 CHF” |
| 03 | Ceny na ekranie głównym | Lista lub moneta jako widżet – bez otwierania aplikacji |
| 04 | Co rusza się razem? | Wolumen, rynek i futures w skrócie – bez prognoz |
| 05 | Portfel, jeśli chcesz | Opcjonalnie, w ustawieniach: kupno, sprzedaż, transfery |

### pt-BR – Portugiesisch (Brasilien)

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Último preço da exchange | Sem conta, sem anúncios – os valores ficam no aparelho |
| 02 | Um alarme que avisa uma vez | “Me avise quando o BTC cair abaixo de 90.000 CHF” |
| 03 | Preços na tela inicial | Lista ou uma moeda como widget, sem abrir o app |
| 04 | O que se move junto? | Volume, mercado e futuros em resumo, sem previsões |
| 05 | Portfólio, se você quiser | Opcional, ative nos Ajustes: compras, vendas, transferências |

### pt-PT – Portugiesisch (Portugal)

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Último preço da exchange | Sem conta nem anúncios – os montantes ficam no dispositivo |
| 02 | Um alarme que avisa uma vez | «Avisar-me quando BTC descer abaixo de 90 000 CHF» |
| 03 | Preços no ecrã principal | Lista ou uma moeda como widget, sem abrir a app |
| 04 | O que se mexe com ele? | Volume, mercado e futuros em resumo, sem previsões |
| 05 | Portefólio, se quiser | Opcional, nas Definições: compras, vendas, transferências |

### ro – Rumänisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Ultimul preț de la bursă | Fără cont, fără reclame – sumele rămân pe dispozitiv |
| 02 | O alarmă care anunță o dată | „Anunță-mă când BTC coboară sub 90.000 CHF” |
| 03 | Prețuri pe ecranul principal | Lista sau o monedă ca widget – fără să deschizi aplicația |
| 04 | Ce se mișcă odată cu el? | Volum, piață și futures pe scurt – fără prognoze |
| 05 | Portofoliu, dacă vrei | Opțional, din setări: cumpărări, vânzări, transferuri |

### ru-RU – Russisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Последняя цена с биржи | Без аккаунта и рекламы – суммы остаются на устройстве |
| 02 | Одно оповещение – один раз | «Сообщить, когда BTC опустится ниже 90 000 CHF» |
| 03 | Цены на главном экране | Список или одна монета в виджете – без открытия приложения |
| 04 | Что движется вместе? | Объём, рынок и фьючерсы коротко – без прогнозов |
| 05 | Портфель – по желанию | По желанию, в настройках: покупки, продажи, переводы |

### sq – Albanisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Çmimi i fundit nga bursa | Pa llogari, pa reklama – shumat mbeten në pajisje |
| 02 | Një alarm që njofton një herë | «Më njofto kur BTC të bjerë nën 90 000 CHF» |
| 03 | Çmimet në ekranin kryesor | Lista ose një monedhë si widget – pa hapur aplikacionin |
| 04 | Çfarë lëviz bashkë? | Vëllimi, tregu dhe futures shkurt – pa parashikime |
| 05 | Portofol, nëse do | Opsionale, nga cilësimet: blerje, shitje, transferime |

### sv-SE – Schwedisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Senaste kursen från börsen | Inget konto, ingen reklam – beloppen stannar på enheten |
| 02 | Larm som meddelar en gång | ”Meddela mig när BTC sjunker under 90 000 CHF” |
| 03 | Kurser på hemskärmen | Listan eller ett mynt som widget – utan att öppna appen |
| 04 | Vad rör sig med? | Volym, marknad och terminer kort sagt – utan prognos |
| 05 | Portfölj, om du vill | Valfritt, i inställningar: köp, försäljning, överföringar |

### th – Thailändisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | ราคาล่าสุดจากกระดานเทรด | ไม่ต้องมีบัญชี ไม่มีโฆษณา จำนวนเงินอยู่บนเครื่องคุณ |
| 02 | แจ้งเตือนครั้งเดียว | “แจ้งฉันเมื่อ BTC ลงต่ำกว่า 90,000 CHF” |
| 03 | ราคาบนหน้าจอหลัก | รายการติดตามหรือเหรียญเดียวเป็นวิดเจ็ต ไม่ต้องเปิดแอป |
| 04 | อะไรเคลื่อนไหวไปด้วย? | วอลุ่ม ตลาด และฟิวเจอร์ส สรุปสั้น ๆ ไม่พยากรณ์ |
| 05 | พอร์ตโฟลิโอ ถ้าต้องการ | ไม่บังคับ เปิดในการตั้งค่า: ซื้อ ขาย โอน |

### tr-TR – Türkisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Borsadan son fiyat | Hesap yok, reklam yok – tutarlar cihazında kalır |
| 02 | Bir kez haber veren alarm | “BTC 90.000 CHF altına düşünce bana haber ver” |
| 03 | Ana ekranda fiyatlar | Liste veya tek coin widget olarak – uygulamayı açmadan |
| 04 | Onunla birlikte ne hareket ediyor? | Hacim, piyasa ve vadeli kısaca – tahmin yok |
| 05 | İstersen portföy | İsteğe bağlı, ayarlardan: alım, satım, transfer |

### uk – Ukrainisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Остання ціна з біржі | Без акаунта й реклами – суми лишаються на пристрої |
| 02 | Сигнал, що спрацьовує раз | «Повідомити, коли BTC опуститься нижче 90 000 CHF» |
| 03 | Ціни на головному екрані | Список або одна монета у віджеті – без відкриття застосунку |
| 04 | Що рухається разом? | Обсяг, ринок і ф’ючерси коротко – без прогнозів |
| 05 | Портфель – за бажанням | За бажанням, у налаштуваннях: купівлі, продажі, перекази |

### vi – Vietnamesisch

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | Giá gần nhất từ sàn | Không tài khoản, không quảng cáo – số tiền ở lại máy bạn |
| 02 | Cảnh báo chỉ báo một lần | “Báo cho tôi khi BTC giảm dưới 90.000 CHF” |
| 03 | Giá ngay trên màn hình chính | Danh sách hoặc một coin dạng widget – không cần mở app |
| 04 | Cái gì biến động cùng? | Khối lượng, thị trường, futures – ngắn gọn, không dự đoán |
| 05 | Danh mục, nếu bạn muốn | Tùy chọn, bật trong Cài đặt: mua, bán, chuyển |

### zh-CN – Chinesisch (vereinfacht)

| Nr. | Titel | Untertitel |
|---|---|---|
| 01 | 最新价来自交易所 | 无需账户，没有广告，金额不离开设备 |
| 02 | 只通知一次的提醒 | “当 BTC 跌破 90,000 CHF 时提醒我” |
| 03 | 主屏幕上看价格 | 自选列表或单个币种做成小组件，无需打开应用 |
| 04 | 哪些在同步波动？ | 成交量、大盘和合约数据，简短总结，不做预测 |
| 05 | 需要时再用投资组合 | 可选，在设置中开启：买入、卖出、转账 |
