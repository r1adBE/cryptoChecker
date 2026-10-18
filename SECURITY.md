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
* **Encrypted at rest (since build 59).** On top of the operating system's
  storage encryption:
  * Android: both Room databases (`cryptochecker.db` – watchlist, alarms, notes;
    `cryptochecker_portfolio.db` – transactions, portfolio alarms, watched
    addresses and xpubs) are SQLCipher databases (AES-256). Each database has its
    own random 256-bit key, stored wrapped by an AES-256-GCM key in the Android
    Keystore (no user authentication, so background checks and widgets work
    after the first unlock) in `noBackupFilesDir`, never in a backup.
  * Android portfolio widget (since build 60): the snapshot the widget reads
    synchronously (totals, positions, gains/losses, the held coins' price
    samples) is one AES-256-GCM sealed entry in the SharedPreferences
    `portfolio_widget` (versioned header `CCWS`, authenticated). Its key is a
    separate Android Keystore key (`cryptochecker_widget_key_v1`, no user
    authentication), not tied to the database keys. Plaintext entries of older
    versions are read once and rewritten sealed. Without the key the widget shows
    its empty state; the app never writes the snapshot in plaintext. Only the
    remembered exchange rate (currency and USD rate, no holdings) stays readable.
  * iOS: the files with user data in the App Group (`watchlist.json`,
    `portfolio_alarms.json`, `portfolio.json`, `watched_addresses.json`, the
    portfolio widget snapshot and the copies of unreadable files) are sealed with
    AES-256-GCM (CryptoKit, versioned header). The 256-bit key lives in the
    keychain, shared with the widgets through the App Group
    (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` since build 69, not synced
    to iCloud Keychain and never part of an iCloud/computer backup or a device
    transfer – a device backup holds the sealed files but never their key;
    existing items are switched with `SecItemUpdate`, same key bytes). After a
    restore on a new iPhone the files are set aside and the app asks for a backup
    file; Settings › Backup says so up front on both platforms (Android Keystore
    keys are device-bound too). File protection stays the system default.
  * If encryption cannot be set up (no key, failed migration), the data stays
    readable in plaintext as before and Settings › Backup says «Encryption not
    active yet» until a later start succeeds – except the portfolio: iOS keeps
    changes in memory, Android never creates the portfolio database unencrypted
    (kept in memory only, retried at the next start; an existing plaintext one
    still opens and migrates).
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
  * Migration from older versions runs once per file, never loses data: the
    plaintext file is replaced only after the encrypted copy has been verified
    (Android: same `user_version`, row count and row checksum per table; iOS:
    decrypted bytes identical) by an atomic rename; any failure keeps the
    plaintext file and retries on the next start. If the key of an encrypted file
    is permanently lost (e.g. Keystore reset, or an unencrypted iOS computer
    backup restored on another iPhone), the file is set aside unchanged
    (`*.unreadable` / `*.locked`), the app starts empty and tells the user to
    restore a backup file. Details: DEVELOPMENT.md, «Storage encryption».
* **Encrypted connections only.** All market data is fetched over HTTPS, live
  prices in the open watchlist over secure WebSockets (`wss://`) (Android:
  cleartext traffic disabled; iOS: App Transport Security without exceptions). Standard system certificate checks, no custom trust rules.
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
  fails like a wrong password). Files from earlier versions (v2: PBKDF2-HMAC-SHA256
  with 600 000 or 210 000 iterations) can still be opened. Parameters read from a
  file are capped (at most 256 MiB, 10 passes, 4 lanes) so a crafted file cannot
  stall the device. A new backup password needs at least 12
  characters (since build 69; 10 before); a password that is only digits, one repeated character (also with digits or
  symbols only at the start or end, e.g. «aaaaaaaaaaa1»), mostly one character
  (75 % or more), at most 3 different characters or contains
  a common sequence (e.g. «12345678», «qwertz», «password») is blocked for new
  backups – the protection of an exported file depends on the password. Older
  files (minimum 8 characters back then) still decrypt with their password. The
  password is never stored. Importing a backup without a password shows a
  warning (check that it comes from you) and can never turn the portfolio lock
  off. When the portfolio comes back from a system or device backup, invalid
  rows are counted and named once in a notice instead of being dropped silently. Android's system backup covers only the app's own
  data files listed in its backup rules – the main database (watchlist, alarms)
  and settings, each only in an end-to-end encrypted cloud backup or a
  device-to-device transfer. Because the main database is encrypted with a key that never
  leaves the device, the backup agent writes a verified plaintext copy of it
  only for the duration of a backup and deletes it afterwards (if the backup is
  killed midway, the next app start deletes the leftover copy); after a restore
  the copy becomes the database again and is encrypted on the next start.
  Android 8/8.1 ignore `requireFlags="clientSideEncryption"`, so there the
  copy is not in the backup rules and the agent does not create it (since
  build 60): the watchlist and alarms are neither backed up nor restored on
  those versions, only settings and favorites. The portfolio (transactions and portfolio alarms) lives in its own
  database file that is never in the backup rules; only with the switch
  «Portfolio in system backup» (default off) does the app keep a plain JSON copy
  in a backed-up file. The main database runs with `secure_delete` (deleted content
  is overwritten with zeros); after an update from database 12, which still held
  the portfolio, the dropped portfolio tables are compacted away with VACUUM, retried
  on every start until it succeeds. On iOS, portfolio.json is excluded from device backups by
  default (isExcludedFromBackup), and so are portfolio_alarms.json (same switch; while
  portfolio alarm thresholds are still in watchlist.json – old install or a failed
  write – watchlist.json is excluded as well)
  and the portfolio widget file (always excluded and always sealed; since build 60
  a snapshot that cannot be written sealed is not stored at all – no fallback to
  the App Group defaults any more – and a plaintext copy left there by an older
  version is moved into the sealed file or removed on the next start).
  The portfolio widget's caches (hourly prices, value history, candle attempts)
  are files in the App Group that are always excluded from device and iCloud
  backups (moved there once from the App Group defaults).
  A backup exported without a password is plain JSON. The portfolio lock protects
  what the app shows on the device, not system or device backups.
* **What exchanges see.** Prices are fetched directly from public exchange APIs, so
  each exchange sees which pairs are requested (and your IP address) – the
  watchlist is stored only on the device, but the requests themselves are not
  anonymous. Futures figures go to the exchange of the watched pair; only reference
  data and the funding history of the «Why?» sheet ask Binance Futures, Bybit or
  OKX with the coin's contract symbol. Market breadth comes from the same mirror
  file for everyone. Watched addresses (and, for an xpub, the addresses derived
  from it on the device – the xpub itself never leaves the device) go to the
  public blockchain nodes named in the privacy policy, together with your IP
  address.
* **No sensitive logs, no tracking.** Release builds write no prices, amounts,
  holdings or alarm thresholds to the system log. No analytics, no ads, no
  WebView, no clipboard use for your data.
* **Links and shortcuts** (`cryptochecker://…`, app shortcuts, notification and
  widget taps) can only open fixed screens; they never change data. The Android
  boot receiver accepts only the protected system broadcasts `BOOT_COMPLETED` and
  `MY_PACKAGE_REPLACED` (no `QUICKBOOT_POWERON`, which any app could send) and only
  reschedules the background update.

### Kurz auf Deutsch

Alle Daten (Merkliste, Alarme, Portfolio) bleiben auf dem Gerät. Zusätzlich zur
Verschlüsselung des Betriebssystems sind sie seit Build 59 verschlüsselt
gespeichert (Android: SQLCipher, Schlüssel im Android Keystore; iOS: AES-256-GCM,
Schlüssel im Schlüsselbund, seit Build 69 nur auf diesem Gerät und in keinem
Geräte-Backup) – auf einem entsperrten Telefon zeigt die App sie trotzdem an. Vor
einem Gerätewechsel braucht es darum eine Sicherungsdatei. Bleibt die
Verschlüsselung aus (kein Schlüssel möglich), nennt das Einstellungen › Sicherung. Seit Build 60 ist auch die Momentaufnahme des Android-Portfolio-Widgets
versiegelt (AES-256-GCM, eigener Keystore-Schlüssel); ohne Schlüssel zeigt das
Widget keine Werte statt Klartext. Unverschlüsselt bleiben Einstellungen,
Kurs-Zwischenspeicher und Coin-Listen, Sicherungen ohne Passwort sowie die
Android-Kopien für die Systemsicherung (Merkliste nur während des Sicherens – eine bei
abgebrochener Sicherung liegengebliebene Kopie löscht der nächste App-Start – und
erst ab Android 9, Portfolio nur mit Schalter). Verbindungen nur über HTTPS
bzw. verschlüsselte WebSockets (`wss://`) für Live-Kurse.
Die Portfolio-Sperre verbirgt Werte im App-Umschalter, in Widgets und auf dem
Sperrbildschirm; «Beträge verbergen» gilt auch für Widgets und Mitteilungen.
Sperrt sie sich wieder, schliessen sich offene Portfolio- und Sicherungs-Dialoge.
Sicherungen gehen nur über die Dateiauswahl des Systems und lassen sich mit
einem Passwort verschlüsseln (AES-256-GCM, Schlüssel per Argon2id mit 64 MiB, der
Kopf der Datei ist mitgeschützt; ältere Dateien mit PBKDF2 bleiben lesbar), das
nirgends gespeichert wird.
Neue Sicherungs-Passwörter brauchen mindestens 12 Zeichen (seit Build 69); leicht zu erratende
(nur Ziffern, ein wiederholtes Zeichen – auch mit Ziffern oder Zeichen nur am
Anfang oder Ende –, zu 75 % dasselbe Zeichen, höchstens 3 verschiedene Zeichen,
häufige Folgen) sind gesperrt, ältere
Dateien lassen sich weiter öffnen. Eine unverschlüsselte Sicherung warnt beim
Einspielen und kann die Portfolio-Sperre nicht ausschalten.
Kurse kommen direkt von den Börsen: Sie sehen die abgefragten Paare und die
IP-Adresse. Beobachtete Adressen (beim xpub die auf dem Gerät abgeleiteten
Adressen – der xpub selbst bleibt auf dem Gerät) gehen zusammen mit der
IP-Adresse an die in der Datenschutzerklärung genannten öffentlichen
Blockchain-Knoten.
Keine sensiblen Daten im Protokoll, kein Tracking, keine Werbung. Links und
Verknüpfungen öffnen nur feste Bereiche und ändern keine Daten.
