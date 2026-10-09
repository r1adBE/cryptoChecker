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

iOS: open `ios/CryptoChecker.xcodeproj` in Xcode 16 and build the scheme
`CryptoChecker` for a simulator. After adding or removing Swift files run
`python3 ios/tools/gen_xcodeproj.py`.

Translations:

```sh
python3 .github/scripts/check_strings.py
```

GitHub runs the same checks for every pull request (workflows **Android** and
**Strings**; the iOS build can be started by hand under *Actions › iOS*).

## Add an exchange

Open an issue with the template **«Exchange request»** first, or send a pull
request directly. Only public endpoints without API keys can be used. The
guide [`DEVELOPMENT.md`](DEVELOPMENT.md) explains the adapters step by step.

* Keep Android (`android/marketdata`) and iOS (`ios/Shared/Markets`) in sync:
  an adapter added in Kotlin should get its Swift counterpart (or say so in
  the PR so it can be ported).
* One exchange per pull request, with a short note on the API endpoints used.

## Translations

All texts live in the Android string resources:
`android/app/src/main/res/values/strings*.xml` (English) and
`values-xx/strings*.xml` (31 languages). To fix or add a translation:

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

By contributing you agree that your contribution is licensed under the MIT
License of this repository.
