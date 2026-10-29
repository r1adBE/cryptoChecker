# Security

Please report security issues **privately** by e-mail to
riad.work@outlook.com – not as a public issue. Alternatively use GitHub's
private vulnerability reporting: *Security* tab of this repository › *Report a
vulnerability*. Both channels reach the maintainer only.

Helpful information:

* affected part (Android app, iOS app, exchange adapter, privacy page) and app
  version
* steps to reproduce
* possible impact

You will get an answer within a few days. Please allow reasonable time for a fix
before publishing details.

## Supported versions

Security fixes go into the **latest release** in Google Play and the App Store
(and the matching state of this repository). Older versions get no separate
fixes – please update before reporting, if you can.

Crypto Checker reads only public market data, has no accounts and stores no
user data on servers. The complete source code is public; signing keys, API
keys or other secrets never belong in this repository.

## What the app does to protect your data

* **Stays on your device.** Watchlist, alarms, portfolio and settings are stored
  only locally (Android: Room/DataStore in the app's private storage; iOS: the
  app's container, shared only with its own widgets).
* **Encrypted at rest.** On top of the operating system's
  storage encryption:
  * Android: both Room databases (`cryptochecker.db` – watchlist, alarms, notes;
    `cryptochecker_portfolio.db` – transactions, portfolio alarms, watched
    addresses and xpubs) are SQLCipher databases (AES-256). Each database has its
    own random 256-bit key, stored wrapped by an AES-256-GCM key in the Android
    Keystore (no user authentication, so background checks and widgets work
    after the first unlock) in `noBackupFilesDir`, never in a backup.
  * Android portfolio widget: the snapshot the widget reads
    synchronously (totals, positions, gains/losses, the held coins' price
    samples) is one AES-256-GCM sealed entry in the SharedPreferences
    `portfolio_widget` (versioned header `CCWS`, authenticated). Its key is a
    separate Android Keystore key (`cryptochecker_widget_key`, no user
    authentication), not tied to the database keys. Without the key the widget shows
    its empty state; the app never writes the snapshot in plaintext. Only the
    remembered exchange rate (currency and USD rate, no holdings) stays readable.
  * Android alarm log (`alarm_log.sealed`, pairs and thresholds) and the restore
    journal (`restore_journal.sealed`, the state before a backup file is restored,
    deleted when the restore is done): AES-256-GCM in `noBackupFilesDir`, each with
    its own Android Keystore key (`cryptochecker_alarm_log`,
    `cryptochecker_restore_journal`). Unlike the widget key, these keys count as
    lost only when the Keystore error repeats on a later start (same rule as the
    database keys), so a transient error never makes the journal unreadable.
  * iOS: the files with user data in the App Group (`watchlist.json`,
    `portfolio_alarms.json`, `portfolio.json`, `watched_addresses.json`,
    `alarm_log.json`, the portfolio widget snapshot and the copies of unreadable
    files) and the restore journal in the app's container
    (`restore_journal.sealed`, never in a device backup) are sealed with
    AES-256-GCM (CryptoKit, versioned header). The 256-bit key lives in the
    keychain, shared with the widgets through the App Group
    (created with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, not synced
    to iCloud Keychain and never part of an iCloud/computer backup or a device
    transfer – a device backup holds the sealed files but never their key). After a
    restore on a new iPhone the files are set aside and the app asks for a backup
    file; Settings › Backup says so up front on both platforms (Android Keystore
    keys are device-bound too). File protection stays the system default. The
    keychain item (`com.cryptochecker.storage-key`) can survive deleting the app;
    no data it could open remains, and a later installation uses it for new files.
  * If no key can be created, the app never writes the watchlist or the
    portfolio in plaintext (the same on Android and iOS): nothing is created on
    disk, the app works from memory and every start tries again. Changes are kept
    in memory for this session only and are lost when the app closes. The
    watchlist asks once before the first change («not being saved» – change
    anyway?), and the watchlist and the portfolio show a notice while this lasts.
    Settings › Backup says «Encryption not active yet» while data cannot be stored
    encrypted (Android: a database kept in memory; iOS: a copy of an unreadable
    file still waiting to be sealed).
  * If the key of an existing encrypted file is only temporarily unavailable, the
    file stays exactly as it was (nothing is written into it, deleted or set
    aside); the app shows the saved state again after a restart. Watchlist alerts
    pause in the background during this time.
  * Copying the files off the device does not reveal their content without the
    device's key. On an unlocked phone the app itself still reads and shows
    everything – the portfolio lock only hides the display.
  * Not encrypted by the app: settings (DataStore / App Group defaults), price
    caches and coin lists (including the last remembered prices of the held
    coins, `portfolio_last_prices.txt`, and on iOS the widget's hourly prices and
    price samples – coin symbols and prices, no quantities), the refresh and
    check statistics, backup files exported without a password, and on Android
    the system-backup copies: the watchlist database copy (only while a backup
    runs, Android 9+) and the opt-in `portfolio_system_backup.json` («Portfolio
    in system backup», plain JSON by design so it can be restored on a new
    device).
  * Encryption never loses data: a plaintext copy (Android: the restored
    system-backup copy of the watchlist; iOS: a copy of an unreadable file written
    without a key) is replaced only after the encrypted file has been verified
    (Android: same `user_version`, row count and row checksum per table; iOS:
    decrypted bytes identical) by an atomic rename; any failure keeps the copy and
    retries on the next start. If the key of an encrypted file
    is permanently lost (e.g. Keystore reset, or an unencrypted iOS computer
    backup restored on another iPhone), the file is set aside unchanged
    (`*.unreadable` / `*.locked`), the app starts empty and tells the user to
    restore a backup file. Files set aside, and files replaced when a set-aside
    file comes back (`*.replaced`), are never in a backup and are deleted no
    earlier than 30 days after the app first saw them and only once the notice is
    confirmed (both platforms). During a system backup, Android never sets the
    portfolio database aside or creates a key: without its matching key the backup
    is aborted (the previous one stays) instead of backing up an empty portfolio. Details: DEVELOPMENT.md, «Storage encryption».
* **Encrypted connections only.** All market data is fetched over HTTPS, live
  prices in the open watchlist over secure WebSockets (`wss://`) (Android:
  cleartext traffic disabled; iOS: App Transport Security without exceptions). Standard system certificate checks, no custom trust rules.
  An own Bitcoin server with a certificate from a private CA therefore works on
  iOS only if that CA is installed as a profile and fully trusted (Settings ›
  General › About › Certificate Trust Settings); on Android it does not work,
  because the app trusts only the system CAs, not CAs added by the user (no
  `network_security_config`). Use a certificate from a public CA there.
* **Portfolio lock.** Optional biometric / device-credential lock for the
  portfolio. While it is on, the portfolio is hidden in the app switcher
  (Android: FLAG_SECURE and no recents preview; iOS: privacy cover), portfolio
  widgets show no values, and portfolio alarms show no amounts on the lock
  screen. «Hide amounts» also applies to widgets and notifications. When the
  session locks again (more than 60 s in the background), open portfolio sheets and
  backup dialogs close; a file picked or a password check finishing after that is
  discarded until the user unlocks again.
* **Backups.** Exported via the system file picker only (no temporary copies),
  optionally encrypted with a password (format v3: AES-256-GCM, key from Argon2id
  per RFC 9106 with 64 MiB memory, 3 passes, 1 lane and a random 16-byte salt; the
  file header with all parameters is authenticated as AAD, so a modified header
  fails like a wrong password). Parameters read from a
  file are capped (at most 256 MiB, 10 passes, 4 lanes) so a crafted file cannot
  stall the device. A new backup password needs at least 12
  characters; a password that is only digits, one repeated character (also with digits or
  symbols only at the start or end, e.g. «aaaaaaaaaaa1»), mostly one character
  (75 % or more), at most 3 different characters or contains
  a common sequence (e.g. «12345678», «qwertz», «password») is blocked for new
  backups – the protection of an exported file depends on the password. The
  password is never stored. Importing a backup without a password shows a
  warning (check that it comes from you) and never lowers protection: it never
  turns the portfolio lock or «Hide amounts» off, never turns «Portfolio in
  system backup» on, and never sets, replaces or removes the user's own Bitcoin
  server (after the import, a note names a portfolio lock or server that was
  kept). When the portfolio comes back from a system or device backup, invalid
  rows are counted and named once in a notice instead of being dropped silently. Android's system backup covers only the app's own
  data files listed in its backup rules – the main database (watchlist, alarms)
  and settings, each only in an end-to-end encrypted cloud backup or a
  device-to-device transfer. Because the main database is encrypted with a key that never
  leaves the device, the backup agent writes a verified plaintext copy of it
  only for the duration of a backup and deletes it afterwards (if the backup is
  killed midway, the next app start deletes the leftover copy); after a restore
  the copy is encrypted into the database right away, or at the next start if
  no key is available at that moment.
  Android 8/8.1 ignore `requireFlags="clientSideEncryption"`, so there the
  copy is not in the backup rules and the agent does not create it: the watchlist and alarms are neither backed up nor restored on
  those versions, and neither are the settings (they hold the address of an own
  Bitcoin server), only the app language and favorites. The portfolio (transactions, portfolio alarms, watched
  addresses and xpubs) lives in its own
  database file that is never in the backup rules; only with the switch
  «Portfolio in system backup» (default off) does the app keep a plain JSON copy
  in a backed-up file. The main database runs with `secure_delete`: deleted content
  is overwritten with zeros, so deleted rows do not linger in the database file on
  the device (the backup copy is made logically, row by row, and never contains
  free pages). On iOS, portfolio.json is excluded from device backups by
  default (isExcludedFromBackup), and so are portfolio_alarms.json and
  watched_addresses.json (same switch; portfolio alarm thresholds are never stored
  in watchlist.json)
  and the portfolio widget file (always excluded and always sealed; a snapshot
  that cannot be written sealed is not stored at all – never in the App Group
  defaults).
  The portfolio widget's caches (hourly prices, value history, candle attempts)
  are files in the App Group that are always excluded from device and iCloud
  backups.
  A backup exported without a password is plain JSON. The portfolio lock protects
  what the app shows on the device, not system or device backups.
* **What exchanges see.** Prices are fetched directly from public exchange APIs, so
  each exchange sees which pairs are requested (and your IP address) – the
  watchlist is stored only on the device, but the requests themselves are not
  anonymous. Futures figures go to the exchange of the watched pair; only reference
  data and the funding history of the «Why?» sheet ask Binance Futures, Bybit or
  OKX with the coin's contract symbol. Market breadth comes from CoinGecko's list of the 40 largest coins – the same
  request for everyone. Watched addresses (and, for an xpub, the addresses derived
  from it on the device – the xpub itself never leaves the device) go to the
  public blockchain nodes named in the privacy policy, together with your IP
  address. Optionally, Bitcoin addresses and xpubs can go to your own
  Esplora/mempool-compatible server instead (Settings › Portfolio; https only, no user
  name or password in the address): then only
  that server is asked, with no fallback to the public nodes, so they never see these
  addresses. Other networks stay with the public nodes. Address queries follow a
  redirect only to the same host (never from https to http); a redirect to another
  host is treated as an error, so the address never reaches a third party that way.
* **Integrity of the data mirror.** Market data that is the same for everyone comes
  from the release «data» on GitHub (README, «Market data mirror»). The apps use a
  mirror file only if it matches `manifest.json` (SHA-256 per file), whose ECDSA P-256
  signature matches one of the two public keys built into the apps (active and
  reserve key), and if that manifest is at
  most 7 days old; manifest and signature are read with small size limits (64 KB /
  1 KB). Otherwise the file is ignored and the app asks the provider directly. The
  private key exists only as a GitHub Actions secret in the environment `mirror-signing`, usable
  from `main` only; a run that cannot sign ends red. The reserve private key is kept
  offline by the maintainer, so a lost or leaked key can be replaced without an app update.
  A leaked key can also be revoked: the manifest has a revocation list that counts only
  when a fresh manifest is signed with the other key (a key cannot revoke or restore
  itself); the apps then remember the revoked key permanently and no longer accept
  anything signed only with it. If the two keys revoke each other (in either order),
  both stay revoked and the apps stop trusting the mirror until an app update brings
  new keys — they then ask the providers directly.
* **Integrity of the economic calendar.** The calendar of US economic releases
  (`macro/events.json` on the project's GitHub Pages site) is covered by the same
  signed manifest (entry `macro_events.json`). The apps use a downloaded calendar only
  if its SHA-256 is listed in a valid manifest; a changed or unlisted file is treated
  like a download error and the app keeps its cached calendar or the one built into
  the app.
* **No sensitive logs, no tracking.** Release builds write no prices, amounts,
  holdings or alarm thresholds to the system log. No analytics, no ads, no
  WebView, no clipboard use for your data.
* **Links and shortcuts** (`cryptochecker://…`, app shortcuts, notification and
  widget taps) can only open fixed screens; they never change data. The Android
  boot receiver accepts only protected system broadcasts, which no other app can
  send: `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` and
  `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` reschedule the background update
  and the exact-check alarm; `TIMEZONE_CHANGED` and `TIME_SET` re-plan the
  economic-calendar notification and re-check the exact alarm. No
  `QUICKBOOT_POWERON`, which any app could send. The widget setup screens are exported (launchers
  open them, also for «reconfigure» on Android 12+), but each works only with the id of
  one of the app's own widgets of its kind and closes otherwise.

### Kurz auf Deutsch

Alle Daten (Merkliste, Alarme, Portfolio) gehen nie an den Entwickler. Unter Android nimmt die
Systemsicherung (Google-Konto, Geräteübertragung) die Merkliste samt Menge und Notiz mit, sobald sie
eingeschaltet ist, das Portfolio nur mit dem Schalter «Portfolio in Systemsicherung». Zusätzlich zur
Verschlüsselung des Betriebssystems sind sie verschlüsselt
gespeichert (Android: SQLCipher, Schlüssel im Android Keystore; iOS: AES-256-GCM,
Schlüssel im Schlüsselbund, nur auf diesem Gerät und in keinem
Geräte-Backup) – auf einem entsperrten Telefon zeigt die App sie trotzdem an. Vor
einem Gerätewechsel braucht es darum eine Sicherungsdatei. Bleibt die
Verschlüsselung aus (kein Schlüssel möglich), speichert die App nichts im Klartext: Änderungen bleiben nur bis zum
Schliessen im Arbeitsspeicher, und Einstellungen › Sicherung nennt das. Auch die Momentaufnahme des Android-Portfolio-Widgets ist
versiegelt (AES-256-GCM, eigener Keystore-Schlüssel); ohne Schlüssel zeigt das
Widget keine Werte statt Klartext. Unverschlüsselt bleiben Einstellungen,
Kurs-Zwischenspeicher und Coin-Listen, Sicherungen ohne Passwort sowie die
Android-Kopien für die Systemsicherung (Merkliste nur während des Sicherens – eine bei
abgebrochener Sicherung liegengebliebene Kopie löscht der nächste App-Start; nach dem
Wiederherstellen wird sie gleich verschlüsselt, ohne Schlüssel beim nächsten Start –
und erst ab Android 9, Portfolio nur mit Schalter). Verbindungen nur über HTTPS
bzw. verschlüsselte WebSockets (`wss://`) für Live-Kurse.
Bitcoin-Adressen und xpub können statt an öffentliche Knoten an einen eigenen
Esplora-/mempool-Server gehen (nur https, ohne Rückfall auf öffentliche Knoten).
Dateien des Daten-Spiegels nutzt die App nur mit gültiger Signatur (ECDSA P-256,
Manifest höchstens 7 Tage alt, SHA-256 je Datei), sonst fragt sie den Anbieter direkt.
Die Portfolio-Sperre verbirgt Werte im App-Umschalter, in Widgets und auf dem
Sperrbildschirm; «Beträge verbergen» gilt auch für Widgets und Mitteilungen.
Sperrt sie sich wieder, schliessen sich offene Portfolio- und Sicherungs-Dialoge.
Sicherungen gehen nur über die Dateiauswahl des Systems und lassen sich mit
einem Passwort verschlüsseln (AES-256-GCM, Schlüssel per Argon2id mit 64 MiB, der
Kopf der Datei ist mitgeschützt), das
nirgends gespeichert wird.
Neue Sicherungs-Passwörter brauchen mindestens 12 Zeichen; leicht zu erratende
(nur Ziffern, ein wiederholtes Zeichen – auch mit Ziffern oder Zeichen nur am
Anfang oder Ende –, zu 75 % dasselbe Zeichen, höchstens 3 verschiedene Zeichen,
häufige Folgen) sind gesperrt. Eine unverschlüsselte Sicherung warnt beim
Einspielen und senkt nie den Schutz: Sie schaltet die Portfolio-Sperre und
«Beträge verbergen» nie aus, «Portfolio in Systemsicherung» nie ein und setzt,
ersetzt oder löscht den eigenen Bitcoin-Server nie.
Kurse kommen direkt von den Börsen: Sie sehen die abgefragten Paare und die
IP-Adresse. Beobachtete Adressen (beim xpub die auf dem Gerät abgeleiteten
Adressen – der xpub selbst bleibt auf dem Gerät) gehen zusammen mit der
IP-Adresse an die in der Datenschutzerklärung genannten öffentlichen
Blockchain-Knoten.
Keine sensiblen Daten im Protokoll, kein Tracking, keine Werbung. Links und
Verknüpfungen öffnen nur feste Bereiche und ändern keine Daten.
