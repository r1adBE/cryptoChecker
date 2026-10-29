# Contributing

Thank you for helping! This repository contains the complete source code of
Crypto Checker: the Android app (`android/`), the iOS app (`ios/`), the
exchange library and the privacy page. Questions and pull requests are welcome
in German or English.

## Build and test

Android (Android Studio or JDK 17+):

```sh
cd android
./gradlew test            # unit tests (Windows: gradlew.bat test)
./gradlew assembleDebug   # debug APK
```

iOS: open `ios/CryptoChecker.xcodeproj` in Xcode 26 (or newer; App Store uploads
need the iOS 26 SDK) and build the scheme `CryptoChecker` for a simulator. After adding or removing Swift files run
`python3 ios/tools/gen_xcodeproj.py`.

Translations:

```sh
python3 .github/scripts/check_strings.py
```

GitHub runs the same checks on every pull request: **Android**, **iOS** (build, unit and UI tests in the
Simulator), **Strings**, **Parity** and **Release readiness**. The Android
device tests (emulator) run on every push to `main` that changes `android/**`, on
every release tag and on pull requests that touch the databases (migration tests
only); they can also be started by hand under *Actions › Android device tests*.

## Add an exchange

Open an issue with the template **«Exchange request»** first, or send a pull
request directly. Only public endpoints without API keys can be used. The
guide [`DEVELOPMENT.md`](DEVELOPMENT.md) explains the adapters step by step.

* Keep Android (`android/marketdata`) and iOS (`ios/Shared/Exchanges`) in sync:
  an adapter added in Kotlin should get its Swift counterpart (or say so in
  the PR so it can be ported).
* One exchange per pull request, with a short note on the API endpoints used.
* Register the adapter on both platforms as described in
  [`DEVELOPMENT.md` § 8](DEVELOPMENT.md#8-registering-the-exchange): give it a
  fixed `key` (stored in every saved pair, alarm and backup – never change it
  later) and add the key to `ExchangeKeyTest`
  (`android/app/src/test/java/com/cryptochecker/app/contract/ExchangeKeyTest.kt`),
  which fails for a missing or changed key.
* Add the adapter's ticker endpoint and parsed fields to the table in
  `.github/scripts/exchange_live_check.py`. The weekly **Exchange API check**
  workflow calls every adapter's public endpoint and opens (or updates) one
  issue «Exchange API check failed» when an exchange changes its API; the issue
  closes itself once all checks pass again. Try it locally with
  `python3 .github/scripts/exchange_live_check.py --dry-run --only kraken`
  (the script's lowercase id of the exchange).

## Translations

All texts live in the Android string resources:
`android/app/src/main/res/values/strings*.xml` (English) and
`values-xx/strings*.xml` (75 languages). Every folder has the same area files,
one per part of the app (`strings_common.xml`, `strings_watchlist.xml`,
`strings_alarms.xml`, `strings_portfolio.xml`, `strings_tax.xml`,
`strings_settings.xml`, `strings_widgets.xml` …); a key lives in the same file in
every language, grouped by its prefix. New keys go into the area file of their
prefix, in all languages. To fix or add a translation:

1. Edit the matching `values-xx/strings*.xml` (keep placeholders such as
   `%1$s` and `%d` unchanged; escape apostrophes as `\'`; plural forms in
   `<plurals>` need every quantity of the language).
2. Rebuild the iOS string catalog:
   `python3 ios/tools/convert_strings.py --android-res android/app/src/main/res`
   (iOS-only texts are in `ios/tools/ios_extra_strings.json`).
3. Run `python3 .github/scripts/check_strings.py`.

## Pull requests

* Small, focused changes with a short description of what and why.
* Add or update unit tests for logic changes (`android/app/src/test`).
* Never commit API keys, tokens, keystores, `keystore.properties`,
  `local.properties`, certificates or provisioning profiles.
* Never add third-party API data (e.g. CoinGecko market data), logos or name
  lists to the repository, GitHub Pages or release assets — the apps fetch
  them at runtime from the original source (see «Data mirror» and «Coin logos»
  in [`DEVELOPMENT.md`](DEVELOPMENT.md)).

By contributing you agree that your contribution is licensed under the MIT
License of this repository. Third-party names, logos, trademarks and market
data are not covered by it (see [`LICENSE`](LICENSE)).
