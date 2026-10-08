# Crypto Checker im Apple App Store veröffentlichen

Schritt-für-Schritt-Anleitung für die iOS-App, Version **16.2.2 (Build 17)**,
Bundle-ID `com.cryptochecker.app`, Entwickler r1AD (Schweiz).

Alles, was du in App Store Connect einfügen musst, liegt bereits im Projekt:

| Was | Wo |
|---|---|
| Datenschutzerklärung (Android **und** iOS) | `../android/docs/privacy/index.html` |
| Store-Texte in 29 Sprachen (fastlane-*deliver*-Format) | `fastlane/metadata/<sprache>/` |
| Notizen für die App-Prüfung (Englisch) | `fastlane/metadata/review_information/notes.txt` |
| Copyright, Kategorien | `fastlane/metadata/copyright.txt`, `primary_category.txt`, `secondary_category.txt` |
| Icon 1024 × 1024 ohne Alphakanal (nur Referenz) | `fastlane/metadata/app_icon.png` |
| Anleitung Screenshots | `fastlane/screenshots/README.md` (5 Szenen; Beschriftungen in allen Sprachen: `../android/docs/store/screenshots/captions.json` und Kit-README) |

> **⚠️ Vor dem Einreichen ausfüllen:** Der Prüfungs-Kontakt enthält noch Platzhalter –
> `fastlane/metadata/review_information/first_name.txt` (**VORNAME**),
> `last_name.txt` (**NACHNAME**) und `phone_number.txt` (**+41 00 000 00 00**).
> Echte Werte eintragen (nur für Apple sichtbar). Solange die Platzhalter drinstehen,
> bricht `fastlane deliver` ab (Prüfung in `fastlane/Deliverfile`).

**Markenfarbe Orange:** Orange ist die Erkennungsfarbe (App-Icon, Standard-Akzent
bei neuen Installationen). Alle Marketing-Bilder – Screenshots, Promo-Bilder –
zeigen den Akzent Orange und das orange Icon. Blau, Grün, Rot und Marrs Green sind optionale
Themes (alternative App-Icons); sie dürfen im Text erwähnt werden, gehören aber
nicht in die Store-Bilder. Die alten blauen Globus-Grafiken sind entfernt.

Je Sprachordner:

| Datei | Feld in App Store Connect | Grenze |
|---|---|---|
| `name.txt` | Name | 30 Zeichen |
| `subtitle.txt` | Untertitel | 30 Zeichen |
| `keywords.txt` | Keywords (Kommas ohne Leerzeichen) | 100 Zeichen |
| `promotional_text.txt` | Werbetext (jederzeit änderbar, ohne neue Prüfung) | 170 Zeichen |
| `description.txt` | Beschreibung | 4000 Zeichen |
| `release_notes.txt` | Neuerungen in dieser Version | 4000 Zeichen |
| `privacy_url.txt` | Datenschutzrichtlinie-URL | – |
| `support_url.txt` | Support-URL | – |
| `marketing_url.txt` | Marketing-URL (leer = keine) | – |

Alle Längen sind mit einem Skript geprüft (siehe Abschnitt 13).

**Leitidee:** «Crypto Checker erklärt dir, was im Markt passiert.» Untertitel, Werbetext und die ersten
Zeilen der Beschreibung führen in jeder Sprache mit diesem Satz bzw. seiner Kurzform (Untertitel z. B.
«Erklärt, was im Markt passiert», en-US «The crypto market, explained»). Beobachtend formuliert: keine
Prognosen, keine Anlageberatung.

> Apple ändert Formulare und Regeln regelmässig. Die Angaben entsprechen dem
> Stand Oktober 2026 – im Zweifel die aktuelle Hilfe von App Store Connect prüfen.

---

## 0. Bevor du anfängst – fünf Punkte, die sonst zur Ablehnung führen

Diese Punkte betrafen den Code bzw. das Projekt. Alle sind **erledigt**; sie
stehen zur Kontrolle hier.

1. ~~Version im Projekt ist noch 16.0.0 (Build 16).~~ **Erledigt:** 16.2.2 / 17
   in `tools/gen_xcodeproj.py` und `project.yml` (Abschnitt 4.3).
2. ~~⚡ Ungewöhnliche Aktivität und 💡 «Warum bewegt sich das?» fehlen im
   iOS-Code.~~ **Erledigt:** `Shared/Activity/`, `App/Features/Watchlist/WatchlistActivity.swift`.
   Ebenso im Build und in den Store-Texten: «≈ Umrechnung», Alarme in deiner
   Währung, Stichtag-Export (CSV), Kursfarben für Farbenblinde, Mini-Chart.
   Richtlinie 2.3.1 bleibt: Texte nur mit Funktionen, die der Build hat.
3. ~~Datenschutz-Manifest fehlt.~~ **Erledigt:** `App/PrivacyInfo.xcprivacy`,
   vom Generator beiden Targets als Ressource zugeordnet (Abschnitt 4.4).
4. ~~Link zur Datenschutzerklärung in der App.~~ **Erledigt:** Zeile unter
   Einstellungen › Über; `AppLinks.privacyPolicy` und alle `privacy_url.txt` /
   `support_url.txt` zeigen auf `https://r1adbe.github.io/cryptoChecker/`.
   Vor der Einreichung prüfen, dass GitHub Pages dort wirklich läuft.
5. ~~Markt-Tab lädt Kursdaten von `api.binance.com`.~~ **Erledigt:** Charts,
   Volumen und Zyklus laden jetzt von `data-api.binance.vision` (öffentlicher
   Marktdaten-Spiegel von Binance, ohne US-Sperre). Nur die Futures-Daten
   (`fapi.binance.com`) können aus den USA gesperrt sein; dann bleibt dieser
   Abschnitt einfach leer, die App funktioniert sonst normal.

Die **Spendenadressen** sind seit 16.2.2 aus der App entfernt (Abschnitt 11.1).

---

## 1. Apple Developer Program

1. Apple-Account mit **Zwei-Faktor-Authentifizierung** bereithalten.
2. Auf <https://developer.apple.com/programs/enroll/> (oder in der App
   **Apple Developer** auf dem iPhone) beitreten.
   * Typ: **Einzelperson** (Individual). Für eine Organisation bräuchte es eine
     eingetragene Firma mit D-U-N-S-Nummer.
   * Kosten: **99 USD pro Jahr** (in der Schweiz in CHF abgerechnet). Läuft die
     Mitgliedschaft aus, verschwindet die App aus dem Store.
   * Ausweis-Prüfung durch Apple, Freischaltung meist innerhalb von 1–2 Tagen.
3. Wichtig: Bei einem Einzelpersonen-Konto zeigt der App Store als Anbieter
   deinen **bürgerlichen Namen**, nicht «r1AD». «r1AD» erscheint nur im
   Copyright-Vermerk und in der Beschreibung.
4. In App Store Connect unter **Business** (früher «Agreements, Tax, and
   Banking») ist für Gratis-Apps nur der **Free Apps Agreement** nötig – er ist
   automatisch aktiv. Bank- und Steuerangaben braucht es erst für kostenpflichtige
   Apps oder In-App-Käufe.
5. **EU-Händlerstatus (Digital Services Act):** App Store Connect fragt, ob du
   «Trader» (gewerblich) bist. Als Hobby-Entwickler ohne Einnahmen ist das in der
   Regel «Kein Händler». Bist du Händler, werden Adresse, Telefon und E-Mail in den
   EU-Storefronts öffentlich angezeigt. Die App enthält keine Zahlungs- oder Spendenfunktion;
   im Zweifel rechtlich abklären. Ohne Angabe wird
   die App in der EU nicht angeboten.

---

## 2. Identifiers, App Group und Capabilities

Am einfachsten lässt du das **Xcode** erledigen (automatische Signierung,
Abschnitt 4). Xcode registriert dann App-IDs und App Group selbst. Wer es von
Hand machen will, unter <https://developer.apple.com/account/resources/>:

1. **Identifiers › + › App Groups**: `group.com.cryptochecker.app`
2. **Identifiers › + › App IDs › App**:
   * Bundle ID (explicit): `com.cryptochecker.app`
   * Capabilities: **App Groups** (→ `group.com.cryptochecker.app` zuweisen)
     und **Time Sensitive Notifications**
   * *Push Notifications* wird **nicht** gebraucht (die App erzeugt Mitteilungen
     nur lokal).
3. Zweite App-ID für die Widgets: `com.cryptochecker.app.widgets`, Capability
   **App Groups** (gleiche Gruppe).

Die Entitlements im Projekt passen bereits dazu:

* `App/CryptoChecker.entitlements`: App Group + `com.apple.developer.usernotifications.time-sensitive`
* `Widgets/CryptoCheckerWidgets.entitlements`: App Group

Ist `com.cryptochecker.app` schon von jemand anderem belegt, siehe `README.md`
(«Ist die Bundle-ID bereits vergeben …») – dann ändern sich App-ID, Widget-ID und
App-Group-ID überall.

---

## 3. App-Eintrag in App Store Connect anlegen

<https://appstoreconnect.apple.com> › **Apps** › **+** › **Neue App**:

| Feld | Eingabe |
|---|---|
| Plattformen | **iOS** |
| Name | `Crypto Checker: Price Alerts` (aus `en-US/name.txt`) |
| Primäre Sprache | **Englisch (USA)** |
| Bundle-ID | `com.cryptochecker.app` (erscheint erst, wenn die App-ID registriert ist – ggf. zuerst einmal in Xcode signieren) |
| SKU | z. B. `cryptochecker-ios` (interne Kennung, frei wählbar, nicht änderbar, nicht öffentlich) |
| Benutzerzugriff | Uneingeschränkter Zugriff |

Der **App-Name muss im ganzen App Store eindeutig** sein. Ist er belegt, meldet
App Store Connect das sofort. Dann eine Variante wählen (z. B. «Crypto Checker –
Kursalarm») und `name.txt` in allen Sprachordnern anpassen.

---

## 4. Xcode: Signieren, Version, Manifest

### 4.1 Signieren

1. `CryptoChecker.xcodeproj` öffnen.
2. Für **beide** Targets (`CryptoChecker` und `CryptoCheckerWidgetsExtension`):
   **Signing & Capabilities** › *Automatically manage signing* ✓ › **Team**
   = dein Developer-Team (nicht «Personal Team»).
3. Kontrollieren, dass bei beiden Targets **App Groups** mit
   `group.com.cryptochecker.app` angehakt ist und beim App-Target
   **Time Sensitive Notifications** erscheint. Ein rotes Ausrufezeichen heisst:
   Capability fehlt bei der App-ID – auf «Try Again» klicken, Xcode legt sie an.

### 4.2 Gerätefamilie

**Entscheid für die erste Einreichung: nur iPhone.** `TARGETED_DEVICE_FAMILY = "1"`
bei allen Targets (in `tools/gen_xcodeproj.py` und `project.yml` gesetzt). Apple
verlangt deshalb **keine iPad-Screenshots**; auf dem iPad läuft die iPhone-Version
im Kompatibilitätsmodus. iPad lässt sich später nachrüsten (`"1,2"`, dann
iPad-Screenshots 13" nötig) – umgekehrt geht es nach der Veröffentlichung nicht mehr.

### 4.3 Version 16.2.2, Build 17

Bei **beiden** Targets gleich setzen (sonst Upload-Fehler, weil die Version der
Widget-Erweiterung zur App passen muss):

* *General › Identity › Version* = `16.2.2` → Build-Einstellung `MARKETING_VERSION`
* *General › Identity › Build* = `17` → Build-Einstellung `CURRENT_PROJECT_VERSION`

Damit ein späteres `python3 tools/gen_xcodeproj.py` die Werte nicht zurücksetzt,
auch oben in `tools/gen_xcodeproj.py` (`MARKETING_VERSION`,
`CURRENT_PROJECT_VERSION`) und in `project.yml` nachführen.

Regeln von Apple: Jeder Upload braucht eine **neue Build-Nummer** (17, 18, …).
Die Version (16.2.2) darf gleich bleiben, bis sie veröffentlicht ist; danach muss
die nächste Version höher sein (z. B. 16.2.3).

### 4.4 Datenschutz-Manifest hinzufügen (Pflicht)

Bereits vorhanden: `App/PrivacyInfo.xcprivacy`. `tools/gen_xcodeproj.py` ordnet
die Datei **beiden** Targets als Ressource zu (App und Widget-Erweiterung brauchen
je ein Manifest, weil beide `UserDefaults` nutzen). Inhalt:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>NSPrivacyTracking</key>
	<false/>
	<key>NSPrivacyTrackingDomains</key>
	<array/>
	<key>NSPrivacyCollectedDataTypes</key>
	<array/>
	<key>NSPrivacyAccessedAPITypes</key>
	<array>
		<dict>
			<key>NSPrivacyAccessedAPIType</key>
			<string>NSPrivacyAccessedAPICategoryUserDefaults</string>
			<key>NSPrivacyAccessedAPITypeReasons</key>
			<array>
				<string>CA92.1</string>
				<string>1C8F.1</string>
			</array>
		</dict>
	</array>
</dict>
</plist>
```

* `CA92.1` = Daten nur für die App selbst, `1C8F.1` = Daten für Mitglieder derselben
  App Group (App ↔ Widgets).
* Weitere «Required Reason APIs» (Datei-Zeitstempel, Systemlaufzeit,
  Speicherplatz, Tastaturen) nutzt der Code derzeit nicht. Kommen solche hinzu,
  das Manifest ergänzen.
* In Xcode prüfen: Datei anklicken › *File Inspector* › *Target Membership* –
  beide Targets angehakt.

### 4.5 Exportkontrolle – bereits erledigt

In `App/Info.plist` steht (geprüft):

```xml
<key>ITSAppUsesNonExemptEncryption</key>
<false/>
```

Die App nutzt nur das HTTPS von iOS (`URLSession`), keine eigene
Verschlüsselung. Damit fragt App Store Connect beim Upload nicht nach und es
braucht keine Dokumente. Details in Abschnitt 10.

---

## 5. Archivieren und hochladen

1. Oben in Xcode Schema **CryptoChecker** und Ziel **Any iOS Device (arm64)**
   wählen (kein Simulator).
2. **Product › Archive**. Nach dem Build öffnet sich der **Organizer**.
3. Archiv auswählen › **Distribute App** › **App Store Connect** ›
   **Upload** › Optionen so lassen (Symbole hochladen ✓, automatische
   Signierung) › **Upload**.
4. Nach 5–30 Minuten erscheint der Build in App Store Connect unter
   **TestFlight** (Status «Verarbeitung»); Apple schickt eine E-Mail, sobald er
   bereit ist – oder eine E-Mail mit Fehlern.

Häufige Upload-Fehler:

| Meldung | Ursache / Lösung |
|---|---|
| ITMS-91053 Missing API declaration | Datenschutz-Manifest fehlt (4.4) |
| Invalid Bundle – CFBundleShortVersionString of extension … does not match | Version/Build bei App und Widget verschieden (4.3) |
| Redundant Binary Upload / build already exists | Build-Nummer erhöhen |
| Invalid large app icon … alpha channel | Icon mit Transparenz – das vorhandene `icon-1024.png` ist RGB ohne Alpha (geprüft) |
| Missing entitlement / provisioning profile | Team bei beiden Targets setzen, Capabilities aktivieren (4.1) |

Alternative ohne Xcode-Oberfläche: App **Transporter** aus dem Mac App Store
(nimmt eine exportierte `.ipa`).

---

## 6. TestFlight

* **Interne Tester** (bis 100 Personen mit Zugang zu deinem App-Store-Connect-Team):
  sofort testbar, keine Prüfung. *TestFlight › Interne Tests › +* → Gruppe
  anlegen, Build hinzufügen. Tester installieren die App **TestFlight** und nehmen
  die Einladung an.
* **Externe Tester** (bis 10 000, per E-Mail oder öffentlichem Link): braucht
  eine kurze **Beta-Prüfung** durch Apple. Dafür unter *Testinformationen*
  ausfüllen: Beschreibung, Feedback-E-Mail `riad.work@outlook.com`,
  Kontaktdaten, «Anmeldung erforderlich: Nein» und die Notizen aus
  `review_information/notes.txt`.
* TestFlight-Builds laufen nach 90 Tagen ab.

Vor dem Einreichen auf einem echten iPhone prüfen: Widgets (Home und
Sperrbildschirm), Alarm-Mitteilung (auch bei Fokus), Icon-Wechsel,
Sichern/Wiederherstellen über «Dateien», Markt-Tab.

---

## 7. Produktseite ausfüllen

### 7.1 Adressen

`privacy_url.txt` und `support_url.txt` sind gesetzt:

```
https://r1adbe.github.io/cryptoChecker/privacy/
https://r1adbe.github.io/cryptoChecker/
```

Die Seiten entstehen über GitHub Pages (Einrichtung: siehe
`../android/docs/PLAY_STORE.md`, Abschnitt 1).

**Vor dem Einreichen ausfüllen:** in `review_information/` Vorname, Nachname und
Telefonnummer eintragen (`first_name.txt`, `last_name.txt`, `phone_number.txt` – nur
für Apple sichtbar). Dort stehen noch die Platzhalter `VORNAME`, `NACHNAME` und
`+41 00 000 00 00`; `fastlane/Deliverfile` bricht den Upload ab, solange sie drinstehen.

Apple verlangt als Support-URL eine Webseite (keine `mailto:`-Adresse). Die
Startseite `../android/docs/index.html` genügt: Sie nennt Android und
iPhone und verlinkt Datenschutzerklärung, GitHub-Issues (Fragen und Wünsche)
und die Kontakt-E-Mail.

### 7.2 Von Hand oder mit fastlane

**Von Hand:** In App Store Connect › App › Version 16.2.2 je Sprache die Texte
aus `fastlane/metadata/<sprache>/` einfügen. Weitere Sprachen über das
Sprach-Menü oben rechts hinzufügen.

**Mit fastlane** (lädt alle 29 Sprachen auf einmal): Einen API-Schlüssel unter
*Benutzer und Zugriff › Integrationen › App Store Connect API* erstellen
(Rolle «App Manager», `.p8`-Datei herunterladen) und eine JSON-Datei dazu anlegen
(siehe fastlane-Doku «App Store Connect API»). Dann im Ordner `ios`:

```sh
fastlane deliver \
  --api_key_path ~/keys/asc_api_key.json \
  --app_identifier com.cryptochecker.app \
  --app_version 16.2.2 \
  --metadata_path fastlane/metadata \
  --screenshots_path fastlane/screenshots \
  --skip_binary_upload \
  --run_precheck_before_submit false \
  --force
```

Hinweise:

* **Erste Version:** Bei der allerersten Version zeigt Apple das Feld
  «Neuerungen in dieser Version» nicht an. Meldet deliver dazu einen Fehler, die
  `release_notes.txt`-Dateien für diesen Durchgang vorübergehend wegschieben. Für
  spätere Updates werden sie gebraucht.
* `marketing_url.txt` ist leer – das ist erlaubt.
* `app_icon.png` wird nicht benötigt (das Icon kommt aus dem Build).

### 7.3 Sprachen

29 Sprachordner (App-Store-Codes): `ar-SA cs da de-DE el en-US es-ES fi fr-FR he
hi hu id it ja ko nl-NL no pl pt-BR pt-PT ro ru sv th tr uk vi zh-Hans`.

**Ohne App-Store-Sprache:** Die App selbst spricht 31 Sprachen (Portugiesisch
als `pt-PT` und `pt-BR`), aber Apple bietet für **Persisch (fa)** und
**Albanisch (sq)** keine Produktseiten-Sprache an.
Nutzer mit diesen Sprachen sehen die englische Produktseite; in der App
funktionieren beide Sprachen trotzdem.

Optional kannst du Kopien für weitere Varianten anlegen, z. B. `es-MX` (aus
`es-ES`), `en-GB`/`en-AU`/`en-CA` (aus `en-US`),
`fr-CA` (aus `fr-FR`) oder `zh-Hant` (Übersetzung nötig). Jede Sprache bekommt
eigene 100 Zeichen Keywords – das verbessert die Auffindbarkeit.

Die Keywords enthalten absichtlich **keine Börsen- oder Markennamen** (Binance,
Coinbase …): Fremde Marken in Keywords sind ein häufiger Ablehnungsgrund
(Richtlinie 2.3.7). In der Beschreibung dürfen die Börsen als Datenquellen
genannt werden. Wörter aus Name und Untertitel werden von Apple ohnehin
durchsucht und stehen deshalb nicht noch einmal in den Keywords.

### 7.4 Allgemeine App-Informationen

*App-Informationen* (gilt für alle Versionen):

* Kategorie: **Primär: Finanzen** (`FINANCE`), **Sekundär: Dienstprogramme**
  (`UTILITIES`)
* Inhaltsrechte: «Enthält, zeigt oder greift auf Inhalte Dritter zu?» → **Ja**
  (öffentliche Kursdaten von Börsen-APIs) – und bestätigen, dass du die Rechte
  dazu hast bzw. die Daten öffentlich und frei abrufbar sind.
* Copyright (je Version): `2026 r1AD`

### 7.5 Preis und Verfügbarkeit

* Preis: **Gratis**
* Länder: alle – **Empfehlung: China (Festland) abwählen.** Für den chinesischen
  Store verlangt Apple eine ICP-Registrierung, und Krypto-Apps werden dort
  regelmässig entfernt. Die chinesische Produktseite (`zh-Hans`) erscheint
  trotzdem in Singapur, Malaysia usw.

---

## 8. App-Datenschutz (Fragebogen «App Privacy»)

App Store Connect › App › **App-Datenschutz**:

1. **Datenschutzrichtlinie-URL:** `https://<benutzer>.github.io/<repo>/privacy/`
2. **Datenerfassung › Beginnen**: «Erfassen Sie oder Ihre Drittanbieter-Partner
   Daten von dieser App?» → **Nein, wir erfassen keine Daten von dieser App.**
3. Speichern → **Veröffentlichen**. Auf der Produktseite steht dann
   **«Keine Daten erfasst» / «Data Not Collected»**.

Warum «Nein» korrekt ist: Apple zählt als «erfasst» nur Daten, die vom Gerät
übertragen werden und danach für dich oder Partner länger als für die sofortige
Bearbeitung der Anfrage zugänglich sind. Die App schickt nichts an dich, enthält
kein Analyse-, Werbe- oder Crash-SDK, liest keine Werbe-ID, und die Abfragen an
die Börsen-APIs dienen nur der sofortigen Kursanzeige. Alles andere (Merkliste,
Alarme, Bestände) bleibt auf dem Gerät.

Kein Tracking → **keine** App-Tracking-Transparency-Abfrage nötig und
`NSPrivacyTracking = false` im Manifest.

Wird später ein SDK für Analyse, Absturzberichte oder Werbung eingebaut, müssen
Fragebogen, Manifest und Datenschutzerklärung **vor** dem Update angepasst werden.

---

**Portfolio-Sperre (Face ID):** ändert am Fragebogen nichts – iOS prüft selbst, die App
erhält keine biometrischen Daten. Pflicht ist nur `NSFaceIDUsageDescription` in
`App/Info.plist` (lokalisiert über `Shared/Resources/InfoPlist.xcstrings`); fehlt
der Text, beendet iOS die App beim ersten Face-ID-Aufruf.

### 8.1 Barrierefreiheit (Angaben zur Barrierefreiheit)

App Store Connect › App › **Barrierefreiheit**: Angaben je Gerät (iPhone, iPad).
Nur angeben, was die häufigsten Aufgaben (Kurs ansehen, Paar hinzufügen,
Alarm setzen) vollständig abdecken. Vorher mit echtem Gerät testen.

| Merkmal | Angabe | Grundlage |
|---|---|---|
| VoiceOver | **Ja**, nach Test | Zeilen, Charts, Skalen und Widgets als ein Satz (`Shared/Util/A11y.swift`), Symbol-Knöpfe beschriftet |
| Dunkle Darstellung | **Ja** | Hell/Dunkel/System in den Einstellungen |
| Ohne Farbe unterscheidbar | **Ja** | Veränderung immer mit +/− (Merkliste zusätzlich Pfeil), Blau/Orange-Modus |
| Ausreichender Kontrast | **Ja** | Kursfarben ≥ 4,5:1, «Hoher Kontrast» ≥ 7:1 inkl. Akzent; folgt «Kontrast erhöhen» |
| Weniger Bewegung | **Nein** angeben | nicht geprüft |
| Grössere Schrift | **Ja**, nach Test | Schriften folgen Dynamic Type (`ScaledFont`), Pillen/Symbole mit Obergrenze; Widgets fest |
| Sprachsteuerung, Untertitel, Audiodeskription | **Nein** / entfällt | nicht geprüft bzw. keine Videos |

## 9. Altersfreigabe

App Store Connect › App-Informationen › **Altersfreigabe** › Bearbeiten.
Wahrheitsgemäss beantworten:

| Frage (sinngemäss) | Antwort |
|---|---|
| Gewalt (Cartoon, realistisch, grafisch), Horror/Angst | Keine |
| Sexuelle Inhalte, Nacktheit | Keine |
| Vulgäre Sprache, derber Humor | Keine |
| Alkohol, Tabak, Drogen | Keine |
| Medizinische oder Wellness-Themen | Keine |
| Simuliertes Glücksspiel | Keine |
| **Glücksspiel mit Echtgeld** | **Nein** – die App zeigt nur Kurse; man kann nichts kaufen, verkaufen oder setzen |
| Wettbewerbe | Nein |
| **Uneingeschränkter Webzugang** (eingebauter Browser) | **Nein** – die App hat keinen Browser und öffnet keine beliebigen Webseiten |
| Nutzergenerierte Inhalte, Nachrichten/Chat zwischen Nutzern | Nein |
| Werbung | Nein |
| Loot-Boxen, In-App-Käufe | Nein |
| Kindersicherung / Altersprüfung in der App | Nein |

Ergebnis: **4+**. Dass die App Finanz- bzw. Kursinformationen zeigt, ändert
die Altersstufe nicht – Apple stuft nach Inhalten ein, nicht nach Zielgruppe.
Möchtest du (wie bei Google Play, dort «18+») bewusst höher einstufen, bietet
App Store Connect – falls verfügbar – die Option, eine **höhere Altersfreigabe**
zu wählen; Pflicht ist das nicht. Die Datenschutzerklärung sagt weiterhin, dass
sich die App an Erwachsene richtet; das widerspricht der Freigabe 4+ nicht.

---

## 10. Verschlüsselung («Verwendet Ihre App Verschlüsselung?»)

Dank `ITSAppUsesNonExemptEncryption = NO` in `App/Info.plist` erscheint die Frage
normalerweise nicht. Falls sie doch kommt (z. B. bei TestFlight von Hand):

* «Welche Art von Verschlüsselungsalgorithmen implementiert deine App?» →
  **«Keiner der oben genannten Algorithmen»** bzw. bei der älteren Form:
  «Verwendet die App Verschlüsselung?» → **Ja** (Apple will «Ja» auch bei reinem
  HTTPS), dann «Fällt die App unter eine Ausnahme?» → **Ja** – die App nutzt
  ausschliesslich die im Betriebssystem eingebaute Verschlüsselung (HTTPS über
  `URLSession`).
* Keine Dokumente, keine CCATS, keine Jahresmeldung nötig.
* Die passwortgeschützte Sicherung (seit 16.2.2) verschlüsselt mit **Apple CryptoKit**
  (AES-256-GCM, `BackupCrypto.swift`) – also mit der im Betriebssystem eingebauten
  Kryptografie. Damit bleibt die Ausnahme bestehen und `NO` ist korrekt; die
  Prüfnotizen erwähnen es. Kommt eine eigene Krypto-Bibliothek hinzu, neu beurteilen.

---

## 11. Risiken bei der App-Prüfung (App Review Guidelines)

### 11.1 Spendenadressen – Richtlinie 3.1.1 (erledigt)

Die USDT-Adressen sind seit 16.2.2 **aus beiden Apps entfernt**. Hinweise zur
freiwilligen Unterstützung stehen nur in `SUPPORT.md` auf GitHub (über den
«Sponsor»-Knopf des Repositorys), nicht in der `README.md`. Weder App noch
Store-Texte verlinken auf `SUPPORT.md`. Die Zeile «Quellcode auf GitHub» öffnet
die Startseite des Repositorys, dort stehen keine Spendenadressen. Damit gibt es keinen alternativen
Zahlungsweg in der App (Richtlinie 3.1.1). Die Prüfnotizen
(`review_information/notes.txt`) erwähnen keine Spenden mehr.

Später möglich: ein «Trinkgeld» als In-App-Kauf (Verbrauchsartikel). Dafür
braucht es das Paid Apps Agreement samt Bank- und Steuerangaben, und Apple
behält 15–30 % ein.

### 11.2 Richtigkeit der Beschreibung – 2.3.1, 2.3.10

* Funktionen in den Texten müssen im eingereichten Build vorhanden sein
  (siehe Punkt 0.2: Ungewöhnliche Aktivität, «Warum bewegt sich das?»).
  Ebenfalls im Build (16.2.2): Crypto Pulse «Was gerade auffällt» mit Leitsatz
  und «Warum?»-Checkliste der Faktoren, «Warum bewegt sich …?» als Checkliste
  mit «Kurz gesagt», Einstellungen in Gruppen (Darstellung, Währung &
  Umrechnung, Alarme & Benachrichtigungen, Daten & Aktualisierung, Portfolio,
  Sicherheit & Backup, Erweitert, Über), Aktionsblatt mit Alarm/Warum?/Favorit
  oben, «Alarm setzen» im Hinweis nach dem Hinzufügen, Willkommen mit drei
  Fragen, Favoriten mit Akzent-Rand (Stern am Logo), Trennlinie im Merklisten-Widget.
* Keine Erwähnung anderer Plattformen in Texten und Screenshots: Die Store-Texte
  nennen «Android» nicht (geprüft). Dass Sicherungen mit der Android-App
  austauschbar sind, steht deshalb **nicht** in der Beschreibung, nur in der
  Datenschutzerklärung (die ist für beide Plattformen und unkritisch).
* In der iOS-App selbst darf «Android» ebenfalls nicht sichtbar sein. Die
  Texte `battery_message` und `settings_battery_hint` erwähnen Android, werden in
  der iOS-App aber nicht verwendet (geprüft).

### 11.3 Vollständigkeit – 2.1

* Markt-Tab und Binance-Sperre in den USA (Punkt 0.5).
* Auch Bybit sperrt US-Adressen. Die Prüfnotizen empfehlen den Prüfern Coinbase,
  Kraken oder Gemini.
* Nur iPhone (4.2): Prüfer testen auf dem iPhone; auf dem iPad läuft die iPhone-Version.

### 11.4 Mindestfunktionalität – 4.2, 4.3

Unkritisch: eigenständige native App mit Merkliste, Alarmen, Analysen, Widgets,
Sprachausgabe. Keine reine Webseiten-Hülle. Kurs-Apps gibt es viele (4.3 «Spam»);
die Funktionsbreite unterscheidet Crypto Checker klar.

### 11.5 Datenschutz – 5.1.1, 5.1.2

Unkritisch, sobald Punkt 0.3 (Manifest) und 0.4 (Link in der App) erledigt sind:
keine Daten, keine Konten (also auch keine Pflicht zur Konto-Löschung), keine
Berechtigungen ausser Mitteilungen.

### 11.6 Finanz-Themen – 3.1.5, 5.1.1(ix)

* 3.1.5 (Kryptowährungen) betrifft Wallets, Mining, Börsen, ICOs – trifft nicht
  zu, die App zeigt nur Kurse.
* 5.1.1(ix) verlangt für «stark regulierte Bereiche» (z. B. Finanzdienstleistungen)
  ein Firmenkonto. Eine reine Kursanzeige ist keine Finanzdienstleistung. Fragt
  Apple nach: genau so antworten (keine Konten, kein Handel, keine Zahlungen, keine
  Anlageberatung) – der Text steht auch am Ende der Beschreibung.

### 11.7 Hintergrund-Modi – 2.5.4

* `UIBackgroundModes` enthält nur **`fetch`** (Background App Refresh). Der Code
  nutzt dazu `BGAppRefreshTask` mit den in `BGTaskSchedulerPermittedIdentifiers`
  eingetragenen Kennungen (`com.cryptochecker.app.refresh`, `…zone`) – stimmig.
* Zweck (steht in den Prüfnotizen): Kurse für vom Nutzer angelegte Alarme und für
  die Widgets aktualisieren. Wann das läuft, entscheidet iOS.
* Kein `audio`-, `location`- oder `processing`-Modus → nichts weiter zu
  begründen. Sprachausgabe läuft nur, solange die App offen ist.

### 11.8 Dringende Mitteilungen (Time Sensitive)

* Entitlement `com.apple.developer.usernotifications.time-sensitive` ist gesetzt;
  die Capability muss bei der App-ID aktiv sein (Abschnitt 2). Eine besondere
  Genehmigung von Apple braucht es dafür nicht.
* Verwendet wird die Stufe nur für **Alarme, die der Nutzer selbst angelegt
  hat** (`Notifier.showAlarm`). Normale Kurs-Mitteilungen sind «passiv». Das
  entspricht Apples Vorgabe, Time Sensitive nur für wirklich dringende,
  erwartete Ereignisse zu nutzen. Mitteilungen nie für Werbung verwenden (4.5.4).

### 11.9 Marken – 5.2

Börsennamen werden nur als Datenquelle genannt, keine Börsen-Logos im Icon, keine
Marken in Keywords. Screenshots dürfen Börsennamen in der Merkliste zeigen.

---

## 12. Zur Prüfung einreichen

1. Version 16.2.2 › **Build** › «+» → den in TestFlight geprüften Build 17 wählen.
2. **App-Prüfungsinformationen**:
   * Anmeldung erforderlich: **Nein** (kein Demo-Konto)
   * Kontakt: Vorname, Nachname, Telefon, `riad.work@outlook.com`
   * Notizen: Inhalt von `fastlane/metadata/review_information/notes.txt`
3. **Versionsfreigabe**: «Diese Version manuell freigeben» empfohlen – so
   bestimmst du nach der Freigabe selbst den Zeitpunkt.
4. **Zur Prüfung hinzufügen** › **An App-Prüfung senden**. Die Prüfung dauert
   meist 1–2 Tage. Bei Rückfragen schreibt Apple ins **Resolution Center**
   (Mitteilung per E-Mail).

---

## 13. Texte prüfen und später anpassen

Die Längen aller Texte wurden geprüft (Name/Untertitel ≤ 30, Keywords ≤ 100 ohne
Leerzeichen nach Kommas und ohne den App-Namen, Werbetext ≤ 170, Beschreibung und
Neuerungen ≤ 4000, keine «Android»-Erwähnung, kein Eszett in de-DE). Nach eigenen
Änderungen erneut prüfen – im Ordner `ios`:

```sh
python3 - <<'EOF'
import pathlib
lim = {"name":30,"subtitle":30,"keywords":100,"promotional_text":170,"description":4000,"release_notes":4000}
ok = True
for d in sorted(p for p in pathlib.Path("fastlane/metadata").iterdir() if p.is_dir() and p.name != "review_information"):
    for k, n in lim.items():
        f = d / f"{k}.txt"
        t = f.read_text(encoding="utf-8").strip() if f.exists() else ""
        if len(t) > n: ok = False; print(f"{d.name}/{k}: {len(t)} > {n}")
        if k == "keywords" and ", " in t: ok = False; print(f"{d.name}/keywords: Leerzeichen nach Komma")
print("alles in Ordnung" if ok else "bitte kürzen")
EOF
```

Für spätere Updates: neue Build-Nummer, `release_notes.txt` je Sprache anpassen.
Den **Werbetext** kannst du jederzeit ohne neue Prüfung ändern.

---

## 14. Checkliste vor «An App-Prüfung senden»

- [ ] Apple Developer Program aktiv, EU-Händlerstatus angegeben
- [ ] Version 16.2.2 / Build 17 bei App **und** Widget-Erweiterung
- [x] Ungewöhnliche Aktivität und «Warum bewegt sich das?» im Build enthalten
- [x] `PrivacyInfo.xcprivacy` in beiden Targets
- [x] Link zur Datenschutzerklärung in der App
- [x] Markt-Tab funktioniert auch ohne `api.binance.com` (US-Sperre)
- [ ] Datenschutz-URL und Support-URL online (GitHub Pages), Platzhalter ersetzt
- [ ] **Vor dem Einreichen ausfüllen:** Prüfungs-Kontakt (Vorname, Nachname, Telefon) statt der
      Platzhalter `VORNAME` / `NACHNAME` / `+41 00 000 00 00` eingetragen
- [ ] App-Datenschutz: «Keine Daten erfasst», veröffentlicht
- [ ] Altersfreigabe ausgefüllt (4+)
- [ ] Kategorien Finanzen / Dienstprogramme, Copyright `2026 r1AD`
- [ ] Screenshots iPhone 6,9" (1320 × 2868) – 5 Szenen mit Beschriftungen aus `captions.json` (keine iPad-Screenshots nötig, nur iPhone)
- [ ] China (Festland) abgewählt
- [x] Keine Spendenadressen in der App (11.1)
- [ ] Build in TestFlight auf echtem iPhone getestet (Widgets, Alarme, Sicherung)

---

## Lizenz

MIT, siehe `LICENSE`. Der gesamte Quellcode (Android und iOS) ist öffentlich auf
GitHub: <https://github.com/r1adBE/cryptoChecker>. App («Über › Quellcode auf
GitHub», Einleitung der Lizenzhinweise) und Store-Texte (ein Satz im Abschnitt
Datenschutz) dürfen das nennen: «Quelloffen (MIT-Lizenz)». Die Herkunft der
Börsen-Anbindung wird nur in den Lizenzhinweisen genannt, nicht in Store-Texten.
