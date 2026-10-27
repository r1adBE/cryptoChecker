# Crypto Checker

**Crypto Checker: prices straight from your exchange, and a notice when a check finds
your level reached – no account.** It checks when it looks (with the app closed, the phone decides how often), not
at the moment a price is hit. Watchlist, widgets and a market overview; as an extra, “What is moving with it?”
shows what moves at the same time as a single coin – no cause, no forecast, no investment advice.
Android and iPhone · 31 languages · no ads · no tracking · open source (MIT).

> **Deutsch:** Crypto Checker ist ein Krypto-Checker: Kurse direkt von deiner Börse und
> Hinweise, wenn beim Nachsehen eine Marke erreicht ist – ohne Konto. Die App meldet sich,
> wenn sie nachsieht (bei geschlossener App entscheidet das Telefon, wie oft), nicht im
> Moment, in dem ein Kurs erreicht wird. Dazu Merkliste, Widgets und eine Marktübersicht; als
> Zusatz zeigt «Was bewegt sich mit?», was sich gleichzeitig mit einem Coin bewegt – keine
> Ursache, keine Prognose, keine Anlageberatung. Dieses
> Repository enthält den kompletten Quellcode der Android- und der iOS-App,
> die Börsen-Bibliothek und die Datenschutzerklärung. Lizenz: MIT – jeder darf
> den Code verwenden, ändern und weitergeben.

## Support the project

Crypto Checker is free and stays free – no ads, no account, nothing to unlock. If you
would like to support development voluntarily, the USDT addresses are on
**[SUPPORT.md](SUPPORT.md)** (also behind the “Sponsor” button above). The apps
themselves contain no donation feature.

> **Deutsch:** Die App ist und bleibt gratis. Wer die Entwicklung freiwillig
> unterstützen möchte, findet die USDT-Adressen in **[SUPPORT.md](SUPPORT.md)**.
> Eine Spende schaltet nichts frei.

## What is in this repository

| Folder | Content |
|---|---|
| [`android/`](android) | Android app (Kotlin, Jetpack Compose) with the modules [`android/app`](android/app) (screens, notifications, widgets, background work), [`android/domain`](android/domain) (rules, calculations, models and the refresh flow), [`android/data`](android/data) (databases, repositories, data sources) and the exchange library [`android/marketdata`](android/marketdata) |
| [`ios/`](ios) | iOS (iPhone) app with widgets (Swift, SwiftUI), Xcode project `CryptoChecker.xcodeproj` |
| [`docs/`](docs) | Website and privacy policy (GitHub Pages) |
| [`docs/macro/`](docs/macro) | Calendar of major US economic releases (CPI, PPI, jobs report, Fed decisions, PCE) used by the app's economic-data hint |
| [`DEVELOPMENT.md`](DEVELOPMENT.md) | Development guide: exchange adapters (how to add an exchange), tests, CI and app rules |
| [`.github/`](.github) | Issue templates, CI workflows and scripts |

## Build

### Android

1. Install [Android Studio](https://developer.android.com/studio) (current
   version; it includes the JDK 17+ the project needs).
2. *File › Open* → the folder `android/`. Android Studio downloads Gradle and
   the SDK on first sync.
3. Run the `app` configuration on a device or emulator.

Command line (JDK 17 or newer):

```sh
cd android
./gradlew test             # unit tests
./gradlew assembleDebug    # app/build/outputs/apk/debug/
```

Release builds are signed with a key that is not part of this repository:
copy `android/keystore.properties.example` to `keystore.properties` and fill in
your own keystore. Without it the release build stays unsigned.

### iOS

1. Mac with **Xcode 26** (or newer, same as CI).
2. Open `ios/CryptoChecker.xcodeproj`, choose the scheme `CryptoChecker`
   and a simulator, then *Run*. For a real device select your own team under
   *Signing & Capabilities* (app and widget extension).
3. A real device also needs **your own App Group** (`group.com.cryptochecker.app`
   belongs to the original developer account). Register one in your team, then
   replace the identifier in all three places:
   * `ios/App/CryptoChecker.entitlements` and
     `ios/Widgets/CryptoCheckerWidgets.entitlements` (App Groups),
   * `SharedStorage.appGroup` in `ios/Shared/Storage/SharedStorage.swift` –
     the same value is also the keychain access group of the encrypted files
     (`ios/Shared/Storage/ProtectedFile.swift`).

Helper scripts (Python 3, run from `ios/`):

| Script | Purpose |
|---|---|
| `python3 tools/gen_xcodeproj.py` | Regenerates `CryptoChecker.xcodeproj` (picks up new `.swift` files) |
| `python3 tools/convert_strings.py` | Builds the string catalogs (`Shared/Resources/*.xcstrings`) from the Android strings in `android/app/src/main/res` plus `tools/ios_extra_strings.json` (iOS-only keys) and `tools/ios_overrides.json` (iOS wording of single Android texts per language, e.g. German «Mitteilung» on iOS vs «Benachrichtigung» on Android) |
| `python3 tools/gen_assets.py` | Generates app icons and logos from the Android vector drawables (needs Pillow and libcairo) |

## Exchange library

Both apps read **public** price data directly from the exchanges – no API
keys, no accounts. 56 markets: 54 on 45 exchanges, plus DexScreener (with GeckoTerminal as fallback) and the
daily ECB reference rates (via Frankfurter, labelled, no alerts). The exchange picker groups them into Spot ·
Futures · DEX pools · Forex, each sorted by trading volume on the install date:

* Android: [`android/marketdata`](android/marketdata) (Gradle library module,
  registered in `config/MarketsConfig.kt`)
* iOS: [`ios/Shared/Markets`](ios/Shared/Markets) (same adapters in Swift,
  registered in `MarketsConfig.swift`)
* Charts and candles come from the pair's own exchange first (Android
  `android/app/…/data/remote/candles`, iOS `ios/Shared/Services/Candles`); a fallback source
  (Binance and others) is used only after a same-token check and is always labelled
* Guide for new exchanges and pairs: [`DEVELOPMENT.md`](DEVELOPMENT.md)

## Translations

All texts live in the Android string resources
(`android/app/src/main/res/values*/strings*.xml`, 31 languages); the iOS string
catalog is generated from them. The workflow **Strings** checks placeholders,
plural forms and missing translations on every change
([`.github/scripts/check_strings.py`](.github/scripts/check_strings.py)).
The Android build keeps only these 31 languages (`androidResources.localeFilters`, including the `iw`/`he` and
`in`/`id` spellings); an instrumented test (`LocaleFiltersTest`) checks that none of them falls back to English.

## Economic calendar

[`docs/macro/events.json`](docs/macro/events.json) lists upcoming release times
(UTC) of major US economic data and is served at
<https://r1adbe.github.io/cryptoChecker/macro/events.json>. The workflow
[`.github/workflows/macro-calendar.yml`](.github/workflows/macro-calendar.yml)
updates it every Monday (and on demand) with the dependency-free script
[`.github/scripts/macro_calendar.py`](.github/scripts/macro_calendar.py), which
reads only the official public schedules of the BLS, the Federal Reserve and
the BEA. If a source is unreachable, the previous dates are kept. Commits are
made with the GitHub noreply address only. The app reads this file at most
once a day and sends no personal data; it also ships a built-in copy.
GitHub disables scheduled workflows after 60 days without repository activity, so
[`.github/workflows/keepalive.yml`](.github/workflows/keepalive.yml) re-enables the scheduled
workflows (all scheduled ones: market data, economic calendar, exchange check and the other weekly checks) once a
week through the API – without commits.

> **Deutsch:** Termine wichtiger US-Wirtschaftsdaten für den Hinweis
> «Wirtschaftsdaten» in der App, wöchentlich automatisch aus den offiziellen
> Kalendern von BLS, Fed und BEA aktualisiert.

## Coin logos

No logo files are mirrored on GitHub. Each app loads, the same way on every install, the
CoinGecko ranking (`/coins/markets`, pages 1–10), the public Binance symbol list and the
Binance Alpha token list (lists refreshed daily), and downloads the logos of **all** coins in
them directly from the CoinGecko and Binance image servers — on the first sync, afterwards only
new or changed logos — never a single coin, so nobody learns what is on a watchlist. As an
offline fallback the apps ship the free icon set
[«Cryptocurrency Icons»](https://github.com/spothq/cryptocurrency-icons) 0.18.1 (CC0 1.0) as
`coin_icons.bin`. US stock names come directly from the Nasdaq symbol directory. Logos are
trademarks of their projects. The settings page «Coin logos» shows «Powered by CoinGecko».

> **Deutsch:** Keine Logo-Dateien auf GitHub: Die App lädt die Rangliste von CoinGecko und die
> Listen von Binance (täglich) selbst und alle Logos direkt von deren Bildservern — für alle
> gleich, danach nur neue oder geänderte. Als Ersatz ohne Netz liegt das freie Icon-Paket
> «Cryptocurrency Icons» (CC0) bei.

## Portfolio (optional, off by default)

An optional portfolio on the device: transactions you enter yourself (buy, sell, swap,
transfer, reward, deposit, withdrawal, gift/payment), CSV import (Relai, Swissquote, Kraken,
Bitpanda, Binance, Coinbase),
read-only watched addresses (public BTC/ETH/… addresses, xpub and Taproot `tr(…)` descriptors — never a seed), value history,
portfolio alarms and widgets, and CSV exports: cut-off holdings and realized gains. Country tax
templates (DE, AT, CH, UK, US, CA, AU, FR, JP) are marked **Beta**: simplified rules for
orientation, not tax advice — check them before filing. Everything stays on the device unless you
export it yourself.

A watched address counts once: a booked transfer to or from its wallet (wallet = address label) is already in its
on-chain balance, so the portfolio card, the cut-off CSV and the CH wealth value add only the part not covered by
bookings or cost-basis lots (cut-off: balance observed by that day, bookings up to that balance check). Only lots
dated after the last balance check before the cut-off can be missing — the file and the tax card then say so; in
the DE summary the €256 limit for other income is marked «estimated» when a reward was valued at the daily close.

> **Deutsch:** Freiwilliges Portfolio auf dem Gerät (aus ab Werk): selbst erfasste Transaktionen,
> CSV-Import, beobachtete Adressen (nur lesen, nie ein Seed), Verlauf, Alarme, Widgets und
> CSV-Exporte. Die Länder-Steuervorlagen sind **Beta** – Orientierung, keine Steuerberatung.

## Market data mirror

Values that are the same for every user — the Fear & Greed index (alternative.me), Coin
Metrics' on-chain values and BTC price history, and the altcoin season computed from Binance
daily candles — are fetched once an hour by
[`.github/workflows/market-data.yml`](.github/workflows/market-data.yml)
([`.github/scripts/mirror_data.py`](.github/scripts/mirror_data.py)) and attached to the
release [`data`](https://github.com/r1adBE/cryptoChecker/releases/tag/data) (`fng.txt`,
`onchain.txt`, `btcprice.txt`, `altseason.txt`, plus `coin_meta.json` — only the symbols
traded on Binance Spot, from the public endpoint `/api/v3/exchangeInfo`, used as a fallback).
Every run also publishes `manifest.json` (SHA-256 of each file and the creation time) and
`manifest.json.sig` (ECDSA P-256 signature with a key held only as a repository secret). The
apps use a mirror file only if the signature matches the public key built into the apps, the
manifest is at most 7 days old and the file's hash matches; otherwise they ask the providers
themselves, exactly as when a file is missing or too old. A run that cannot sign the
manifest still uploads the data but ends red. Nothing is committed; live prices still come
straight from the exchanges.

CoinGecko data is **not** mirrored: the CoinGecko API terms forbid redistributing it. The
apps ask CoinGecko directly (market cap and dominance cached 30 minutes, top 30/40 for the
starter coins 24 hours, top 40 for the Spot tab and «What is moving with it?» 10 minutes), no API key is used, and every screen with CoinGecko data
says «Powered by CoinGecko».

> **Deutsch:** Marktdaten, die für alle gleich sind, holt eine GitHub Action stündlich an
> einen Ort; die App fragt die Anbieter nur noch, wenn der Spiegel fehlt, zu alt oder nicht
> gültig signiert ist (signiertes Verzeichnis `manifest.json`, ECDSA P-256, höchstens 7 Tage alt).
> CoinGecko-Daten werden nicht gespiegelt — die App fragt CoinGecko direkt.

## Privacy policy

<https://r1adbe.github.io/cryptoChecker/privacy/> (German and English)

## Questions, wishes, missing exchange?

Open an [issue](https://github.com/r1adBE/cryptoChecker/issues/new/choose) –
German or English welcome. Templates: **Question**, **Wish / idea**,
**Exchange request**, **Bug report**. For an exchange, please include the
exchange's public API documentation (no API key needed – the app only reads
public prices). Pull requests are welcome, see
[`CONTRIBUTING.md`](CONTRIBUTING.md).

## License

MIT, see [`LICENSE`](LICENSE). Anyone may use, copy, modify and distribute the
code, provided the copyright notice and the license text are kept.
Portions derived from BitcoinChecker by mobnetic and Aneonex Software (MIT); their
original copyright notices are kept in [`LICENSE`](LICENSE).
The license file shown in the apps is byte-identical with `LICENSE`; the origin and third-party notes follow
from a separate `NOTICE-origin.txt`.
Third-party licenses used by the apps (e.g. the Rubik font, SIL Open
Font License 1.1) are listed in the apps under *About › Licenses*.
Names, logos and trademarks of coins, tokens, stocks, exchanges and other third
parties, and the market data the apps fetch from third-party APIs (e.g.
CoinGecko, Binance, alternative.me, Coin Metrics), are not part of the code and
are not covered by the MIT License.

## Contact

r1AD · riad.work@outlook.com · security issues: see [`SECURITY.md`](SECURITY.md) ·
controller under data protection law (name and postal address): see the
[privacy policy](https://r1adbe.github.io/cryptoChecker/privacy/)
