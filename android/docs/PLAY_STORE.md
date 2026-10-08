# Crypto Checker im Google Play Store veröffentlichen

Schritt-für-Schritt-Anleitung für Version **16.2.2 (versionCode 17)**,
Paket `com.cryptochecker.app`.

Alles, was du in die Play Console kopieren musst, liegt bereits im Projekt:

| Was | Wo |
|---|---|
| Datenschutzerklärung (Webseite) | `docs/privacy/index.html` |
| Startseite für GitHub Pages | `docs/index.html` |
| App-Icon 512 × 512 | `docs/store/icon_512.png` |
| Feature-Grafik 1024 × 500 | `docs/store/feature_graphic_1024x500_en.png`, `…_de.png` |
| Store-Texte (31 Sprachen) | `fastlane/metadata/android/<sprache>/` |
| Versionshinweise 16.2.2 | `fastlane/metadata/android/<sprache>/changelogs/17.txt` |

> Google ändert Formulare und Regeln regelmässig. Die Angaben hier entsprechen
> dem Stand Oktober 2026 – prüfe im Zweifel die aktuelle Play-Console-Hilfe.

---

## 1. Datenschutzerklärung über GitHub Pages veröffentlichen

Google verlangt für jede App eine öffentlich erreichbare Datenschutz-URL.

Das Repository github.com/r1adBE/cryptoChecker ist öffentlich und enthält den
kompletten Quellcode (MIT). Im Hauptordner in PowerShell:

```
Set-ExecutionPolicy -Scope Process Bypass
.\setup-github-public.ps1 -DryRun   # aufbauen und prüfen, nichts hochladen
.\setup-github-public.ps1           # hochladen, Pages einschalten
```

Das Skript kopiert `docs/index.html`, `docs/privacy/` und `docs/.nojekyll`
nach `public/docs/` (die Apps nach `public/android` und `public/ios`), prüft
alles auf Schlüssel, Passwörter und persönliche Angaben und
schaltet GitHub Pages (Branch `main`, Ordner `/docs`) ein, wenn die GitHub CLI
(`gh`) angemeldet ist. Sonst von Hand: **Settings → Pages → Deploy from a
branch → `main` / `/docs` → Save**.

Nach ein bis zwei Minuten ist die Seite online:

   ```
   https://<github-benutzername>.github.io/<repository-name>/privacy/
   ```

   Für dieses Projekt: Benutzer `r1adBE`, Repository `cryptoChecker` →
   `https://r1adbe.github.io/cryptoChecker/privacy/`
   (der Benutzername erscheint in der Adresse immer in Kleinbuchstaben).

Hinweise:

* GitHub Pages ist für **öffentliche** Repositories kostenlos. Bei einem
  privaten Repository braucht es GitHub Pro – oder du legst ein eigenes,
  öffentliches Repository nur für die Seite an.
* `docs/.nojekyll` sorgt dafür, dass GitHub die Dateien unverändert ausliefert.
* Seite im Browser öffnen und prüfen, bevor du die URL in der Play Console einträgst.
* Dieselbe Adresse ist in der App verlinkt (Android: Einstellungen › Über ›
  «Datenschutzerklärung», `PRIVACY_POLICY_URL`; iOS: `AppLinks.privacyPolicy`).
  Google verlangt den Link im Store-Eintrag **und** in der App.
* Ändert sich etwas an der App (neue Datenquelle, neue Berechtigung), Datum und
  Inhalt in `docs/privacy/index.html` nachführen.

---

## 2. Upload-Schlüssel (Keystore) erstellen

Einmalig. Den Keystore **nie verlieren und nie einchecken** (`*.jks` und
`keystore.properties` stehen bereits in `.gitignore`).

```bash
keytool -genkeypair -v \
  -keystore ~/keys/cryptochecker-release.jks \
  -storetype PKCS12 \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -alias cryptochecker
```

`keytool` liegt im JDK (bei Android Studio z. B. unter
`<Android Studio>/jbr/bin/keytool`). Das Programm fragt nach Passwort und
Namen (Vor-/Nachname, Organisation, Ort, Land `CH`).

Danach im Projektwurzelverzeichnis die Vorlage kopieren:

```bash
cp keystore.properties.example keystore.properties
```

und ausfüllen:

```properties
storeFile=/home/<du>/keys/cryptochecker-release.jks
storePassword=<dein Keystore-Passwort>
keyAlias=cryptochecker
keyPassword=<dein Schlüssel-Passwort>
```

Unter Windows den Pfad mit normalen Schrägstrichen schreiben, z. B.
`storeFile=C:/Users/<du>/keys/cryptochecker-release.jks`.

**Sicherung:** Keystore-Datei und beide Passwörter an einem zweiten,
sicheren Ort ablegen (z. B. Passwortmanager). Mit «Play App Signing»
(Standard bei neuen Apps) verwaltet Google den eigentlichen
App-Signaturschlüssel; dein Schlüssel ist nur der **Upload-Schlüssel**. Geht
er verloren, kann Google ihn zurücksetzen – das dauert aber einige Tage.

---

## 3. Release-Bundle (AAB) bauen

Der Play Store nimmt nur Android App Bundles (`.aab`) an.

**Variante A – Kommandozeile** (mit ausgefüllter `keystore.properties`):

```bash
./gradlew clean bundleRelease
```

Ergebnis: `app/build/outputs/bundle/release/app-release.aab`

**Variante B – Android Studio:**
**Build → Generate Signed App Bundle or APK… → Android App Bundle →**
Keystore wählen, Alias `cryptochecker`, Passwörter eingeben →
Build-Variante `release` → **Create**.

Vor dem Hochladen prüfen:

* `versionCode 17` / `versionName "16.2.2"` in `app/build.gradle` – jeder
  weitere Upload braucht einen **höheren** `versionCode`.
* Release-Build einmal auf einem echten Gerät installieren und kurz testen
  (R8/Minify ist aktiv – Fehler durch entfernte Klassen zeigen sich nur im
  Release-Build).

---

## 4. Play Console: Konto und App anlegen

1. Entwicklerkonto unter <https://play.google.com/console> anlegen
   (einmalige Gebühr, Identitätsprüfung mit Ausweis; bei einem
   **privaten** Konto wird die Adresse nicht öffentlich angezeigt, die
   E-Mail-Adresse aber schon).
2. **App erstellen**:
   * App-Name: `Crypto Checker`
   * Standardsprache: **Englisch (USA) – en-US** (die App ist im Grundzustand
     englisch; Deutsch wird als Übersetzung hinzugefügt)
   * App oder Spiel: **App**
   * Kostenlos oder kostenpflichtig: **Kostenlos** (lässt sich später nicht
     mehr auf kostenpflichtig ändern)
   * Erklärungen bestätigen → **App erstellen**.

---

## 5. Store-Eintrag (Hauptseite im Store)

**Wachstum → Store-Präsenz → Hauptseite im Store** (Main store listing).

Für jede Sprache die Dateien aus `fastlane/metadata/android/<sprache>/`
einfügen:

| Feld in der Play Console | Datei | Grenze |
|---|---|---|
| App-Name | `title.txt` | 30 Zeichen |
| Kurzbeschreibung | `short_description.txt` | 80 Zeichen |
| Vollständige Beschreibung | `full_description.txt` | 4000 Zeichen |

**Leitidee:** «Crypto Checker erklärt dir, was im Markt passiert.» Dieser Satz (bzw. seine Übersetzung)
steht in jeder Sprache am Anfang der Kurzbeschreibung und der vollständigen Beschreibung. Er bleibt
beobachtend: keine Prognosen, keine Anlageberatung.

Weitere Sprachen: **Übersetzungen verwalten → Eigene Übersetzungen
hinzufügen** → Sprachen wählen. Die Ordnernamen entsprechen den Play-Codes
(z. B. `de-DE`, `fr-FR`, `iw-IL` = Hebräisch, `no-NO` = Norwegisch,
`id` = Indonesisch, `pt-PT` = Portugiesisch (Portugal), `pt-BR` =
Portugiesisch (Brasilien)). Play kennt kein `de-CH`; der Text in `de-DE` ist bereits
in Schweizer Schreibweise (ss statt Eszett) und funktioniert für alle
deutschsprachigen Nutzer.

**Tipp – automatisch hochladen statt kopieren:** Die Ordnerstruktur ist das
Format von *fastlane supply*. Mit einem Google-Cloud-Dienstkonto (JSON-Schlüssel,
in der Play Console unter «Nutzer und Berechtigungen» freigeben) lädt

```bash
fastlane supply --package_name com.cryptochecker.app \
  --json_key /pfad/zu/play-key.json \
  --metadata_path fastlane/metadata/android \
  --skip_upload_apk --skip_upload_aab --skip_upload_images --skip_upload_screenshots
```

alle Texte in einem Schritt hoch. Für den ersten Release ist Kopieren von Hand
aber völlig ausreichend.

### Grafiken

| Feld | Vorgabe | Datei |
|---|---|---|
| App-Symbol | PNG, 512 × 512, max. 1 MB | `docs/store/icon_512.png` |
| Feature-Grafik | PNG/JPEG, 1024 × 500, ohne Transparenz | `docs/store/feature_graphic_1024x500_en.png` (en-US, Standard) und `…_de.png` (de-DE) |

Das Symbol stammt aus dem aktuellen App-Icon (Glocke, Orange) – Google rundet
die Ecken selbst ab, deshalb ist die Vorlage randlos quadratisch. Wer andere
Grafiken möchte: Vorlage ist
`cryptoCheckerIOS/App/Resources/Assets.xcassets/AppIcon.appiconset/icon-1024.png`.
Für Sprachen ohne eigene Feature-Grafik zeigt Play die englische an.

**Markenfarbe Orange:** Orange ist die Erkennungsfarbe (Icon, Standard-Akzent bei
neuen Installationen). Alle Marketing-Bilder – Symbol, Feature-Grafik,
Screenshots – zeigen den Akzent Orange und das orange Icon (Kit:
`docs/store/screenshots/README.md`). Blau, Grün, Rot und Marrs Green sind optionale Themes in
der App (Icon-Varianten `LauncherBlue…`, `LauncherGreen…`, `LauncherRed…`, `LauncherMarrsGreen…`); sie
können im Text erwähnt werden, gehören aber nicht in die Store-Grafiken. Die alten
blauen Globus-Grafiken sind entfernt und werden nicht mehr verwendet.

In der Feature-Grafik steht die Zeile «Ohne Konto · Direkt von der Börse» weiss
auf einer dunklen Pille (Kontrast ≈ 10 : 1, WCAG AA).

### Screenshots

* **Telefon: mindestens 2, höchstens 8.** PNG oder JPEG, Seitenverhältnis
  9:16 (Hochformat), kürzeste Seite mind. 320 px, längste max. 3840 px.
  **Empfohlen: 1080 × 1920 px oder höher, mindestens 4 Stück** – erst dann
  kann Google die App in Empfehlungen und Sammlungen zeigen.
* Tablet-Screenshots (7" und 10") sind freiwillig; ohne sie wird die App auf
  Tablets trotzdem angeboten.

So nimmst du sie auf – ausführlich im Screenshot-Kit
[`docs/store/screenshots/README.md`](store/screenshots/README.md) (Demo-Daten,
Beschriftungen in 31 Sprachen, Rahmen-Skript):

1. Release- oder Debug-Build auf einem Telefon (oder Emulator, z. B. Pixel 8)
   installieren und `docs/store/screenshots/demo-backup.json` über
   Einstellungen → Sicherheit & Backup → Sichern & Wiederherstellen
   einspielen (BTC, ETH, SOL, Alarm, Portfolio, CHF).
2. Screenshot auf dem Telefon (Ein/Aus + Leiser) **oder** per USB:
   ```bash
   adb exec-out screencap -p > raw/de-DE/01.png
   ```
   Im Emulator: Kamera-Symbol in der Seitenleiste.
3. Die fünf Szenen des Kits (in dieser Reihenfolge, Titel hier auf Deutsch):
   1. «Den Markt verstehen, ohne Lärm» – Merkliste mit «≈ … CHF» und Mini-Chart
   2. «Warum bewegt sich das?» – das Blatt mit «Kurz gesagt»
   3. «Deine Merkliste, deine Börsen» – Seite «Paar hinzufügen» («+» in der Merkliste) mit der Börsenauswahl
   4. «Alarme, wenn es zählt» – Alarm-Editor mit Satz und «Alarm testen»
   5. «Dein Portfolio – geschützt» – Portfolio-Tab mit eingeschalteter Portfolio-Sperre

   Titel und Untertitel für alle 31 Sprachen: `docs/store/screenshots/captions.json`
   (Eingabe für `tools/frame_screenshots.py`) und als Tabellen im Kit-README unter
   «Beschriftungen je Sprache».
4. Vor dem Aufnehmen: Statusleiste aufräumen (Demo-Modus, Befehle im Kit),
   keine privaten Benachrichtigungen sichtbar. Für jede Sprache die
   Gerätesprache umstellen und die App neu starten. Ohne eigene Screenshots je
   Sprache zeigt Play die Standard-Screenshots.

### Kategorie und Kontakt

**Store-Präsenz → Store-Einstellungen**:

* App-Kategorie: **Finanzen** (Finance)
* Tags: z. B. «Krypto», «Finanznachrichten», «Aktien & Kurse» (Auswahl je nach Angebot)
* E-Mail-Adresse: `riad.work@outlook.com`
* Website (optional): `https://r1adbe.github.io/cryptoChecker/`
* Telefon: optional, leer lassen

---

## 6. App-Inhalte (Richtlinien-Fragebögen)

**Richtlinien und Programme → App-Inhalte** (App content). Alle Punkte müssen
grün sein, bevor ein Release live gehen kann.

### Datenschutzerklärung
URL aus Schritt 1 eintragen: `https://r1adbe.github.io/cryptoChecker/privacy/`

### Werbung
**Nein, meine App enthält keine Werbung.**

### App-Zugriff
**Alle Funktionen sind ohne besondere Zugriffsrechte verfügbar.**
(Kein Login, kein Konto, keine Zugangsdaten nötig.)

### Einstufung des Inhalts (IARC-Fragebogen)
* E-Mail: `riad.work@outlook.com`
* Kategorie: **«Alle anderen App-Typen»** bzw. «Dienstprogramm, Produktivität,
  Kommunikation oder Sonstiges» (keine Spiele-, keine Social-Kategorie).
* Gewalt, Sexualität, Sprache, Drogen: alles **Nein**.
* **Glücksspiel:** **Nein** – keine Echtgeld-Wetten, kein simuliertes
  Glücksspiel. Die App zeigt nur Kurse an; man kann darin nichts kaufen,
  verkaufen oder setzen.
* Interaktion zwischen Nutzern / nutzergenerierte Inhalte: **Nein**.
* Teilt den Standort: **Nein**. Digitale Käufe: **Nein**.
* Erwartetes Ergebnis: niedrigste Altersstufe (z. B. PEGI 3 / USK 0 / «Everyone»).

### Zielgruppe und Inhalte
* Altersgruppe: **nur «18 und älter»** auswählen.
* «Könnte die App Kinder ansprechen?» → **Nein**.

### Datensicherheit (Data safety)
Exakte Antworten:

| Frage | Antwort |
|---|---|
| Erhebt oder teilt deine App erforderliche Nutzerdatentypen? | **Nein** |
| Werden alle von deiner App erhobenen Nutzerdaten bei der Übertragung verschlüsselt? | **Ja** (alle Verbindungen laufen über HTTPS) |
| Bietest du Nutzern eine Möglichkeit, das Löschen ihrer Daten zu beantragen? | Entfällt – es werden keine Daten erhoben. Falls die Frage erscheint: **Nein**, Begründung: keine Datenerhebung; alle Daten liegen nur auf dem Gerät und werden mit der Deinstallation gelöscht. |

Begründung für «Nein» bei der Erhebung: Google zählt Daten, die nur auf dem
Gerät verarbeitet werden, nicht als «erhoben». Die Kursabfragen gehen direkt
vom Gerät an die öffentlichen Börsen-APIs; der Entwickler erhält nichts. Die
Sicherungsdatei legt der Nutzer selbst ab. Die Android-Systemsicherung zählt
ebenfalls nicht als Erhebung durch die App.
Dasselbe gilt für den Stichtag-Export (CSV, Ablageort wählt der Nutzer) und
die Umrechnung: Abgefragt werden nur öffentliche Devisen- und Tageskurse
(Frankfurter, open.er-api.com, Binance, Coinbase), nie Bestände oder Werte.

Wird später eine Bibliothek mit Analyse, Absturzberichten oder Werbung
eingebaut, muss dieser Fragebogen **vor** dem Release angepasst werden.

### Finanzfunktionen (Financial features)
Seit dem Portfolio-Tab (Käufe/Verkäufe erfassen, Gewinn/Verlust, Wert) ist
«Meine App bietet keine Finanzfunktionen» zu knapp. Vorsichtiger und belastbar:

* **Andere / Other** ankreuzen, sonst nichts. Bewusst **nicht**:
  «Cryptocurrency wallet», «Cryptocurrency exchange», «Stock trading and
  portfolio management» (die App verwaltet keine Vermögenswerte und handelt
  nicht), «Financial advice».
* Beschreibung (Englisch):

  > Crypto Checker displays public cryptocurrency market data and provides
  > local portfolio tracking and calculations entered by the user. It does not
  > execute trades, hold or transfer assets, connect to brokerage or exchange
  > accounts, process payments, or provide personalized investment advice.

* Sprache in App und Store bleibt beobachtend: «Kurs beobachten, Alarm setzen,
  Portfolio lokal verfolgen» – nie «Kaufsignal», «Trading-Chance», «Rendite».

Kategorien laut [Play-Console-Hilfe](https://support.google.com/googleplay/android-developer/answer/13849271)
(Stand Oktober 2026). Vor dem Absenden in der Console nochmals vergleichen.

### Berechtigungen für Vordergrunddienste
Da die App `FOREGROUND_SERVICE_DATA_SYNC` verwendet und auf Android 14+
zielt, fragt die Play Console nach einer Erklärung:

* Typ: **Datensynchronisierung (dataSync)**
* Beschreibung, z. B.: «Häufig aktualisieren: Auf ausdrücklichen Wunsch des Nutzers
  werden die Kurse der Merkliste in kurzen Intervallen (ab 15 Sekunden) von
  den Börsen abgerufen und in einer laufenden Benachrichtigung angezeigt. Der
  Dienst lässt sich jederzeit in den Einstellungen (Erweitert) oder über die
  Benachrichtigung beenden.»
* Ab Android 15 läuft ein dataSync-Dienst im Hintergrund höchstens 6 Stunden
  in 24 Stunden; die App beendet ihn dann sauber (`PriceService.onTimeout`) und
  startet ihn erst beim nächsten Öffnen wieder. Nach einem Neustart startet ihn
  die App ab Android 15 nicht aus `BOOT_COMPLETED`, sondern beim Öffnen.
* Video-Link: kurzes Bildschirmvideo (z. B. als «nicht gelistet» auf YouTube),
  das das Einschalten von «Häufig aktualisieren» und die Benachrichtigung zeigt.
  Wurde Erklärung oder Video noch mit dem alten Namen «Live-Modus» eingereicht:
  beim nächsten Update Text und Video an den neuen Namen anpassen.

### Weitere Fragen
* Nachrichten-App: **Nein** · Gesundheits-App: **Nein** ·
  Regierungs-App: **Nein** · COVID-19: **Nein**.

### Spenden
Die App enthält **keine** Spenden-, Zahlungs- oder Kauffunktion (seit 16.2.2
entfernt). Hinweise zur freiwilligen Unterstützung stehen nur in `SUPPORT.md` auf
GitHub (erreichbar über den «Sponsor»-Knopf des Repositorys), nicht in der
`README.md`. So fällt die App nicht unter die Zahlungsrichtlinie von Google
Play (Play Billing). Im Store-Eintrag und in der App nie auf `SUPPORT.md`
verlinken. Die Zeile «Quellcode auf GitHub» öffnet die Startseite des
Repositorys; dort stehen keine Spendenadressen.

---

## 7. Testen und Veröffentlichen

### Interner Test (sofort, bis 100 Tester)
**Testen und veröffentlichen → Testen → Interner Test → Neuen Release
erstellen** → `app-release.aab` hochladen → Versionshinweise einfügen → Release
prüfen → **Einführung starten**. Tester per E-Mail-Liste hinzufügen und den
Einladungslink teilen. Gut, um Play App Signing und den Download einmal
durchzuspielen.

### Geschlossener Test – Pflicht für neue private Konten
Für **private Entwicklerkonten, die nach dem 13. November 2023 erstellt
wurden**, gilt (Stand der Google-Richtlinie, bitte vor dem Start aktuell
prüfen):

* Ein **geschlossener Test** mit mindestens **12 Testern**, die
  **14 Tage ununterbrochen** daran teilnehmen (opt-in), ist Voraussetzung.
* Erst danach lässt sich unter **Dashboard → Zugriff auf Produktion
  beantragen** die Freigabe für die Produktion beantragen (einige
  Fragen zum Test beantworten).

Vorgehen: **Geschlossener Test → Track erstellen** → Tester als
Google-Gruppe oder E-Mail-Liste anlegen → gleiches AAB hochladen → Einladungslink
an Freunde/Familie senden. Die Tester müssen den Link annehmen und die App über
Play installieren; sie sollten die App in den 14 Tagen auch tatsächlich
benutzen und nicht aus dem Test austreten.

Organisationskonten (Firma mit D-U-N-S-Nummer) sind davon ausgenommen.

### Produktion
**Produktion → Neuen Release erstellen** → das bereits getestete AAB aus der
Bibliothek übernehmen (oder neu hochladen) → Versionshinweise → Länder
auswählen (z. B. alle) → **Zur Überprüfung senden**. Die erste Prüfung dauert
oft einige Tage.

### Versionshinweise eintragen
In der Release-Maske pro Sprache, z. B.:

```
<de-DE>
…Inhalt von fastlane/metadata/android/de-DE/changelogs/17.txt…
</de-DE>
<en-US>
…Inhalt von fastlane/metadata/android/en-US/changelogs/17.txt…
</en-US>
```

Für spätere Versionen eine neue Datei `changelogs/<versionCode>.txt` je
Sprache anlegen (max. 500 Zeichen).

---

## 8. Checkliste vor «Zur Überprüfung senden»

- [ ] Datenschutz-URL öffnet sich im Browser (Deutsch und Englisch)
- [ ] `keystore.properties` und `.jks` gesichert, **nicht** im Repository
- [ ] AAB mit `versionCode 17` gebaut und auf einem Gerät getestet
- [ ] Store-Texte en-US (Standard) + weitere Sprachen eingefügt
- [ ] Icon 512 × 512, Feature-Grafik 1024 × 500, mind. 2 (besser 4+) Screenshots
- [ ] Kategorie «Finanzen», Kontakt-E-Mail eingetragen
- [ ] App-Inhalte vollständig: Datenschutz, Werbung (Nein), App-Zugriff,
      Einstufung, Zielgruppe 18+, Datensicherheit, Finanzfunktionen,
      Vordergrunddienst-Erklärung
- [x] Keine Spendenadressen in der App (nur auf GitHub)
- [ ] Geschlossener Test: 12 Tester × 14 Tage abgeschlossen (neue private Konten)

---

## Lizenz

MIT, siehe `LICENSE`. Der gesamte Quellcode (Android und iOS) ist öffentlich auf
GitHub: <https://github.com/r1adBE/cryptoChecker>. App («Über › Quellcode auf
GitHub», Einleitung der Lizenzhinweise) und Store-Texte (ein Satz im Abschnitt
Datenschutz) dürfen das nennen: «Quelloffen (MIT-Lizenz)». Die Herkunft der
Börsen-Anbindung wird nur in den Lizenzhinweisen genannt, nicht in Store-Texten.

Die App enthält die Schrift **Rubik** (SIL Open Font License 1.1, Copyright 2015 The
Rubik Project Authors) für Beträge; der Lizenztext liegt in der App unter
`assets/licenses/Rubik-OFL.txt`.
