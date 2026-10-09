# Crypto Checker

**Crypto Checker explains what is happening in the market.** Prices and alarms
straight from the exchanges, a market overview and “Why is this moving?” for
single coins – no account, no forecasts, no investment advice.
Android and iPhone · 31 languages · no ads · no tracking · open source (MIT).

> **Deutsch:** Crypto Checker erklärt dir, was im Markt passiert – Kurse und
> Alarme direkt von der Börse, ohne Konto, ohne Prognosen. Dieses
> Repository enthält den kompletten Quellcode der Android- und der iOS-App,
> die Börsen-Bibliothek und die Datenschutzerklärung. Lizenz: MIT – jeder darf
> den Code verwenden, ändern und weitergeben.

## What is in this repository

| Folder | Content |
|---|---|
| [`android/`](android) | Android app (Kotlin, Jetpack Compose) and the exchange library module [`android/marketdata`](android/marketdata) |
| [`ios/`](ios) | iOS (iPhone) app with widgets (Swift, SwiftUI), Xcode project `CryptoChecker.xcodeproj` |
| [`docs/`](docs) | Website and privacy policy (GitHub Pages) |
| [`docs/macro/`](docs/macro) | Calendar of major US economic releases (CPI, PPI, jobs report, Fed decisions, PCE) used by the app's economic-data hint |
| [`DEVELOPMENT.md`](DEVELOPMENT.md) | How exchange adapters work and how to add an exchange |
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

1. Mac with **Xcode 16** (or newer).
2. Open `ios/CryptoChecker.xcodeproj`, choose the scheme `CryptoChecker`
   and a simulator, then *Run*. For a real device select your own team under
   *Signing & Capabilities* (app and widget extension).

Helper scripts (Python 3, run from `ios/`):

| Script | Purpose |
|---|---|
| `python3 tools/gen_xcodeproj.py` | Regenerates `CryptoChecker.xcodeproj` (picks up new `.swift` files) |
| `python3 tools/convert_strings.py` | Builds the string catalogs (`Shared/Resources/*.xcstrings`) from the Android strings in `android/app/src/main/res` plus `tools/ios_extra_strings.json` |
| `python3 tools/gen_assets.py` | Generates app icons and logos from the Android vector drawables (needs Pillow and libcairo) |

## Exchange library

Both apps read **public** price data directly from the exchanges – no API
keys, no accounts. 41 markets on 32 exchanges plus DexScreener:

* Android: [`android/marketdata`](android/marketdata) (Gradle library module,
  registered in `config/MarketsConfig.kt`)
* iOS: [`ios/Shared/Markets`](ios/Shared/Markets) (same adapters in Swift,
  registered in `MarketsConfig.swift`)
* Guide for new exchanges and pairs: [`DEVELOPMENT.md`](DEVELOPMENT.md)

## Translations

All texts live in the Android string resources
(`android/app/src/main/res/values*/strings*.xml`, 31 languages); the iOS string
catalog is generated from them. The workflow **Strings** checks placeholders,
plural forms and missing translations on every change
([`.github/scripts/check_strings.py`](.github/scripts/check_strings.py)).

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

> **Deutsch:** Termine wichtiger US-Wirtschaftsdaten für den Hinweis
> «Wirtschaftsdaten» in der App, wöchentlich automatisch aus den offiziellen
> Kalendern von BLS, Fed und BEA aktualisiert.

## Coin logos

`docs/logos/index.json` (ticker → logo and name, crypto and stocks), the logos
as small WebP files in `docs/logos/img/` and all US stock names in
`docs/logos/stocks.txt` are served at <https://r1adbe.github.io/cryptoChecker/logos/>;
all logos in one file are attached to the release
[`logos`](https://github.com/r1adBE/cryptoChecker/releases/tag/logos) as `logos.pack`.
The workflow [`.github/workflows/coin-logos.yml`](.github/workflows/coin-logos.yml)
rebuilds them once a day (and on demand) with
[`.github/scripts/build_logos.py`](.github/scripts/build_logos.py) from public data of
CoinGecko (optional free demo key in the repository secret `COINGECKO_API_KEY`),
Binance, OKX and Nasdaq. If CoinGecko does not deliver every page, nothing is
published and the apps keep the last list. The apps load this one list and all
logos in it (the first time as one pack) — never a single coin — so nobody
learns what is on a watchlist. Logos are trademarks of their projects.

> **Deutsch:** Alle Coin- und Aktien-Logos und Namen für die App an einem Ort,
> täglich automatisch aus öffentlichen Daten gebaut; die App lädt nur diese eine
> Liste und beim ersten Mal alle Logos in einem Paket.

## Market data mirror

Values that are the same for every user — CoinGecko's market cap, dominance and top
coins, the Fear & Greed index (alternative.me), Coin Metrics' on-chain values and
BTC price history, and the altcoin season computed from Binance daily candles — are
fetched once an hour by
[`.github/workflows/market-data.yml`](.github/workflows/market-data.yml)
([`.github/scripts/mirror_data.py`](.github/scripts/mirror_data.py)) and attached to the
release [`data`](https://github.com/r1adBE/cryptoChecker/releases/tag/data). The apps read
these files first and ask the providers themselves only when a file is missing or too
old. Nothing is committed; live prices still come straight from the exchanges.

> **Deutsch:** Marktdaten, die für alle gleich sind, holt eine GitHub Action stündlich an
> einen Ort; die App fragt die Anbieter nur noch, wenn der Spiegel fehlt oder zu alt ist.

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
Originally based on Bitcoin Checker by mobnetic, further developed by Aneonex
Software. Third-party licenses used by the apps (e.g. the Rubik font, SIL Open
Font License 1.1) are listed in the apps under *About › Licenses*.

## Contact

r1AD · riad.work@outlook.com · security issues: see [`SECURITY.md`](SECURITY.md)
