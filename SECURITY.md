# Security

Please report security issues **privately** by e-mail to
riad.work@outlook.com – not as a public issue.

Helpful information:

* affected part (Android app, iOS app, exchange adapter, privacy page) and app
  version
* steps to reproduce
* possible impact

You will get an answer within a few days. Please allow reasonable time for a fix
before publishing details.

Crypto Checker reads only public market data, has no accounts and stores no
user data on servers. The complete source code is public; signing keys, API
keys or other secrets never belong in this repository.

## What the app does to protect your data

* **Stays on your device.** Watchlist, alarms, portfolio and settings are stored
  only locally (Android: Room/DataStore in the app's private storage; iOS: the
  app's container, shared only with its own widgets). Storage is protected by the
  operating system's encryption; the app adds no own database encryption.
* **Encrypted connections only.** All market data is fetched over HTTPS, live
  prices in the open watchlist over secure WebSockets (`wss://`) (Android:
  cleartext traffic disabled; iOS: App Transport Security without exceptions). Standard system certificate checks, no custom trust rules.
* **Portfolio lock.** Optional biometric / device-credential lock for the
  portfolio. While it is on, the portfolio is hidden in the app switcher
  (Android: FLAG_SECURE and no recents preview; iOS: privacy cover), portfolio
  widgets show no values, and portfolio alarms show no amounts on the lock
  screen. «Hide amounts» also applies to widgets and notifications.
* **Backups.** Exported via the system file picker only (no temporary copies),
  optionally encrypted with a password (AES-256-GCM, PBKDF2-HMAC-SHA256). The
  password is never stored. Android's system backup covers only the app's own
  data files listed in its backup rules – these include the database (watchlist,
  alarms, portfolio). The portfolio lock protects what the app shows on the
  device; it does not protect Android system backups or iOS device backups. A
  switch to keep the portfolio out of the system backup is planned (16.3).
* **No sensitive logs, no tracking.** Release builds write no prices, amounts,
  holdings or alarm thresholds to the system log. No analytics, no ads, no
  WebView, no clipboard use for your data.
* **Links and shortcuts** (`cryptochecker://…`, app shortcuts, notification and
  widget taps) can only open fixed screens; they never change data.

### Kurz auf Deutsch

Alle Daten (Merkliste, Alarme, Portfolio) bleiben auf dem Gerät und sind durch
die Verschlüsselung des Betriebssystems geschützt. Verbindungen nur über HTTPS
bzw. verschlüsselte WebSockets (`wss://`) für Live-Kurse.
Die Portfolio-Sperre verbirgt Werte im App-Umschalter, in Widgets und auf dem
Sperrbildschirm; «Beträge verbergen» gilt auch für Widgets und Mitteilungen.
Sicherungen gehen nur über die Dateiauswahl des Systems und lassen sich mit
einem Passwort verschlüsseln (AES-256-GCM), das nirgends gespeichert wird.
Keine sensiblen Daten im Protokoll, kein Tracking, keine Werbung. Links und
Verknüpfungen öffnen nur feste Bereiche und ändern keine Daten.
