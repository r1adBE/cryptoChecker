# Changelog

## 16.2.2 – first public release

* Last price straight from the exchange that lists the pair – 56 markets on 45 exchanges, including futures and DEX pools.
* Alerts run locally on the device when the app checks prices (with the app closed, often only after hours) – no account, no server.
* Home-screen widgets for the watchlist, single pairs, the portfolio and the market pulse.
* Watch apps for Wear OS and Apple Watch with a complication: prices of your favourite coins straight from the exchange; on Android an optional price overlay above other apps.
* «What is moving with it?»: a checklist that puts a price move in context (market, Bitcoin and other factors).
* Optional portfolio (off by default): own transactions, CSV import, watched addresses and tax worksheets.
* Watchlist, alerts and portfolio are stored encrypted on the device.
* Backups with optional password (AES-256-GCM), interchangeable between Android and iPhone.
* 75 languages.
* Full source code of both apps under the MIT license.
* No tracking, no ads, no analytics.

Detailed build history below.

## 16.2.2 (build 179)

* Portfolio: if the price request for a coin without any market fails (e.g. offline), the row says «Price unavailable right now» instead of «Price unchecked».

## 16.2.2 (build 178)

* Portfolio import from Kraken: a deposit or withdrawal that the ledger lists first as pending and then as booked now counts once. If the two rows have different amounts, both are imported and the preview names both lines so you can check your holdings.
* Portfolio import (Kraken, Binance): a trade with two fees – one in the fiat currency and one in the coin – no longer creates a separate fee row. The fiat fee is included in the price, the coin fee lowers the amount received (or raises the amount given); the preview says so.
* Android: «online» only counts once the system has confirmed the network, so a hotel or café Wi-Fi before login shows offline instead of failing price updates.
* Watchlist: the mini chart stays in the row; when space gets tight, the bell and other icons move to a line below the exchange instead of shortening it. Works with large text and in right-to-left languages.
* Android: «All alarms» now looks like on iOS – one card per pair with logo, current price and a direction symbol per alarm, plus a «Portfolio» card.
* iOS: the «Last run» pill next to the watchlist status is gone; the check report stays in the ⋯ menu, as on Android.
* Alarm editor: «Last checked» appears only once; on iOS «Save» stays reachable at the bottom above the keyboard.
* About the app, backup and alarm symbols now match on Android and iOS.
* iOS: the portfolio + button now sits above the tab bar instead of behind it.
* Apple Watch: the complication gives up after 20 seconds without an answer and keeps the last price, like on Wear OS.
* Add pair: DEX pools are easier to find – «DexScreener» and the «DEX pools» section of the exchange list name Uniswap, PancakeSwap, Raydium, Aerodrome …, and typing a DEX name in the search («Uniswap», «raydium pepe») offers DexScreener directly, with the token already filled in.
* Portfolio prices: if Binance does not list one of your coins, the other coins still get their Binance price on the first refresh (before, one unknown coin could leave e.g. IOTA without a price).
* Portfolio: coins that are no longer traded anywhere (e.g. ICX, WTC) show «No market» instead of «Price unchecked».
* Hindi now uses the polite form throughout; language fixes in many other languages.
* Google Play listings for Zulu and for Hong Kong in Cantonese.
* Privacy page: what goes into the Android system backup (including the cached coin list), the fingerprint permission on older Android versions and the iOS local network permission for your own address server.
* Developers: the debug build artifact now also contains the watch APK.

## 16.2.2 (build 177)

* Tests only (no change for users): the Android screenshot tour starts every screen from a freshly started app instead of finding its way back with the system back key, keeps the screen on while it runs and stops at the first screen it cannot reach, naming the screen it saw.

## 16.2.2 (build 176)

* 41 more languages, 74 in total: Afrikaans, Amharic, Armenian, Azerbaijani, Basque, Bengali, Bosnian, Bulgarian, Burmese, Cantonese, Catalan, Croatian, Estonian, Filipino, Galician, Georgian, Gujarati, Icelandic, Irish, Kannada, Kazakh, Khmer, Lao, Latvian, Lithuanian, Macedonian, Malayalam, Marathi, Mongolian, Nepali, Punjabi, Serbian (Cyrillic), Sinhala, Slovak, Slovenian, Swahili, Tamil, Telugu, Urdu (right to left), Uzbek and Zulu – in the app, the widgets, the watch apps and the language picker. Hong Kong keeps Traditional Chinese unless Cantonese is chosen.
* Store listings in the new languages where the store offers them (Google Play 36, App Store 13); all store descriptions now say 75 languages.
* Serbian in Latin script («Srpski (latinica)») next to Cyrillic, generated from the Cyrillic texts – devices set to Serbian Latin no longer fall back to English; 75 languages in total. No separate store listing (Google Play has one Serbian listing, kept in Cyrillic; the App Store has no Serbian).
* Watchlist footer below the last row: «6 pairs», or «6 of 42 pairs» when a group, filter or the search hides pairs; near the limit also the free slots or «Watchlist full». In all 75 languages.

## 16.2.2 (build 175)

* Two more languages: Chinese (Traditional, for Taiwan and Hong Kong) and Malay – in the app, the widgets, the watch apps and the store listings, selectable under Settings › Language on Android. 33 languages in total.

## 16.2.2 (build 174)

* About the app: the backup line now says clearly that a backup without a password is readable (all languages).
* Android device tests on main are green again (stored database schemas carried a wrong check value). No change in the app.

## 16.2.2 (build 173)

* Economic calendar: the first run on the new branch no longer stops when the branch does not exist yet.

## 16.2.2 (build 172)

* Economic calendar: the weekly update of US release dates works again. The calendar now lives on its own branch of the repository and is published to the same web address as before; apps keep checking it against the signed data manifest. No change in the apps.

## 16.2.2 (build 171)

* Android: tabs and pages no longer leave a large gap between the status bar and the title; Settings › Alerts has the usual space between its cards, «Pairs» and «Share diagnostics» line up with the other rows, and the last choice of «Notify only on price change» is no longer cut off (where the word is too long, a pencil icon stands in).
* Alert editor: «When BTC goes below» with a normal word space.
* iPhone: the «+» button in Portfolio and in a coin's page sits above the tab bar; in the watchlist «Add pair» is never shortened, «Last run» shrinks to its icon when space is short, and the mini chart gives way before the exchange name is shortened.
* Add pair: when lists cannot load (e.g. offline), the card sits below the coins and names at most 3 exchanges, alphabetically, with «+N more» to show all.
* Notifications: the short explanation before the system question («Allow notifications?») appears at most once when pairs are added and once more at the first alarm, also after back, leaving the screen or closing the app; on Android, Back in «Add pair» no longer brings it up and leaves the page like the arrow.
* Logos: futures with a multiplier show the coin's initials («1000000MOG» → «MOG» instead of «100»), and when a logo image fails to load, the logo of the same coin from the Binance symbol list or the Binance Alpha list is tried.

## 16.2.2 (build 170)

* iPhone and Apple Watch: the compiler now checks the thread safety of all shared state, including every exchange adapter (0 `@unchecked Sendable`); prices, pair lists, alerts and exports behave as before.

## 16.2.2 (build 169)

* iPhone and Apple Watch: shared state of the app, widgets and watch app sits behind locks the compiler checks; no unchecked global state is left (0 `nonisolated(unsafe)`).
* Logos: the in-memory logo cache keeps at most 300 images and drops the least recently used one; iOS no longer empties it under memory pressure.
* Relative times («2 min ago») follow a language change in the app at once.

## 16.2.2 (build 168)

* Watchlist and live prices: an expired dated futures contract is marked «no longer traded» even with only one or two pairs of that exchange; «1Q», «2Q» … follow the date in the contract, so after the quarter roll «2Q» shows «1Q»; «Live» is shown per pair and only while that pair receives data; a live subscription silent for 3 minutes reconnects; after editing a pair its old price is no longer shown.
* Alerts: the depeg alert keeps its threshold and is armed again after off/on; switching a market alert off during a running check stays off; a failed Fear & Greed or indicator check is retried up to 3 times; indicator alerts accept only the values offered in the settings; the alert editor on Android shows «Last checked» as on iPhone; delivery of a notification is confirmed more reliably.
* Market tab: data sources wait 12 or 20 seconds, the coin analysis 20 seconds everywhere; iOS reports a pair without id as an error instead of asking the exchange.
* Portfolio and tax export: day closes are loaded in batches (far fewer requests for long histories); clearing the portfolio while prices load keeps nothing; on iPhone, editing transactions and creating or restoring a backup no longer block the screen; Binance symbols starting with digits (1INCH, 1000SATS) are read correctly; a French withdrawal or gift valued at a day close shows «value estimated»; Coinbase amounts accept only ASCII digits on Android; very small amounts on iPhone are multiplied exactly.
* Security and backups: the Android system backup never saves an empty portfolio and never creates a key; a single unreadable key no longer deletes the stored key; set-aside copies of unreadable storage on iPhone are removed 30 days after the notice was confirmed; backups are read strictly on both platforms; address queries follow redirects only to the same host and never from https to http; TON addresses are hidden in the diagnostic report; the contract address of a DEX pair is copied the same way on both platforms.
* Display: amounts below half a cent have no sign (never «-0.00»), signs stay in front in right-to-left languages, also on the watch apps; widget texts grow with the system text size up to a limit; with the app lock the portfolio alert shows only a neutral sentence; the price overlay stays on screen after a rotation and tries again when it cannot be shown; the Wear OS complication waits at most 20 seconds for a price, then shows the stored one.

## 16.2.2 (build 167)

* Portfolio with watched addresses: with average cost and FIFO the address part is kept apart from your own holdings in 7 d / 1 y, the history and «Today», as on the card; opening lots count from their purchase date at the cut-off date and in the Swiss wealth value too; the list widget no longer shows the address balance as a booking; «Today» since 00:00 starts each coin at the time of its reference price.
* CSV import: Coinbase and Binance trade history read numbers like «1.23457E+11» correctly; amounts from a restored backup are rounded like stored ones; Android and iOS divide and multiply exactly the same way.
* Tax export: a withdrawal beyond the holdings no longer clears the French acquisition total; every row whose cost basis contains a value estimated at a day close is marked «estimated»; the French network fee at a day close is marked too; in the UK a withdrawal between two sales of the same day no longer affects the earlier sale.
* Coin logos: a missing image no longer blocks a whole image server, pull to refresh also loads missing logos, loading resumes by itself after a server pause, and starter logos are found for «1000…» futures symbols.

## 16.2.2 (build 166)

* Portfolio with watched addresses: «Today», 7 d / 1 y and the value history no longer count opening lots or recorded transfers to the address twice; opening lots count everywhere like buys from their date. A trade exactly at midnight UTC counts on the same day in the header and in the history; «Today» no longer counts a recent purchase as gain when the reference price is older than 24 h; the widget history no longer drops when a coin has no current price.
* CSV import: very long import keys are kept (as a checksum), a transfer link that fails no longer loses the deposit, the exchange rate is reloaded after changing the time zone in the preview, staking rows are no longer offered as withdrawals, prices keep their full precision, amounts of 10^30 or more cannot be saved, and numbers like «1.234E-05» are read for every exchange.
* Tax export: a fee paid in a third coin without a recorded price is valued at that day's close and marked «estimated» (all transaction types and templates); France counts it in the acquisition cost and shows a note on rewards; UK splits two sales on the same day evenly; Germany uses the 600 € exemption limit from 2008; the US wallet note describes what the export computes.

## 16.2.2 (build 165)

* Watch apps: a fetch started earlier (app, complication or widget) never overwrites a newer price; on the Apple Watch app and complication share one file for prices. Phone and watch settings are sent in order and with the current state, also right after the watch connects. The Apple Watch complication is described as «Price». The Apple Watch stops reading a price answer after 4 MB, like Wear OS.
* The privacy page names the user agents of the watch apps.

## 16.2.2 (build 164)

* Watch apps (Wear OS and Apple Watch): a slower earlier fetch can no longer overwrite a newer one; if only some coins arrive, the others keep their last price with their own age; the open Wear app follows changes made on the phone; tapping a coin row no longer reloads (only «Refresh» does); phone and watch settings are reconciled by a change number (on a tie the phone wins).

## 16.2.2 (build 163)

* No visible change. Internal clean-up on both platforms: no migration or compatibility code any more; Android databases are only ever opened encrypted (the copy from the Android system backup is encrypted right on restore).
* Backups are checked completely before anything is replaced: a file missing a part, field or entry field that the app writes is rejected as invalid.
* Code comments and developer documentation describe the current state only.

## 16.2.2 (build 162)

* Watch apps load fallback prices coin by coin instead of whole price lists; the watch, its complications and the Android price overlay grey out outdated prices and show how old they are.
* Tests only: more unit tests for the Android data layer.

## 16.2.2 (build 161)

* The old per-pair holdings field is gone (it was taken over into the portfolio once); backups no longer contain it, and the backup description no longer mentions holdings.
* The origin notice for BitcoinChecker is removed from LICENSE, NOTICE and the license pages of both apps; the start-up clean-up of the predecessor's database is gone.
* Tests only: the CI screenshots show real content on the market tab and in the portfolio (canned answers, only in the screenshot tour).

## 16.2.2 (build 160)

* Tests only (no change for users): the screenshot tour visits every screen step by step and reports missing screens at the end; Android test prices come from the fake exchange answers.

## 16.2.2 (build 159)

* No visible change. In the code, the word for a trading venue is now «exchange» everywhere (Android and iOS: classes, files, keys, database tables `exchanges` / `exchange_pairs`, backup and widget keys); «market» stays only where it means the whole market (market tab, market cap, movers).
* Android: the watchlist database starts over at version 1.
* CI: screenshots of the main screens (light and dark) after every change, on the branch `screenshots`.

## 16.2.2 (build 158)

* Tests only (no change for users): 19 new Android unit-test files – network fees, futures funding and open interest with their fallbacks between exchanges, candle sources (error branches), cache codecs (cycle, market extras, portfolio history), backup journal, logo pauses, alarm drafts, portfolio form prefill and mappings, price formatting, widget snapshot codec, explorer state.

## 16.2.2 (build 157)

* Code cleanup (no change for users): the three largest Android screens get their helpers injected instead of 16–20 single dependencies each (watchlist refresh, start choice and edits through factories; «Test alarm»/«Test voice» and «Check on time» in the settings, USDT valuation and portfolio-alarm saving in their own small classes) – no screen needs more than 14 dependencies any more, and CI now stops at 15. On iPhone, eight thread-safe helpers switch to Apple's checked lock type, so the Swift 6 compiler verifies them instead of trusting a marker. Build numbers removed from code comments (history lives in this changelog); CI caps the few that still name early storage formats.

## 16.2.2 (build 156)

* Code cleanup (no change for users): names inherited from the original BitcoinChecker that described nothing are replaced by names that say what the code does – `FormatUtilsBase` → `DisplayFormat`, `TimeUtils` → `EpochTime`, `PostRequestInfo` → `PostRequest`, `TickerImpl` → `MutableTicker`, `ComboBox` → `DropdownField`, `LogBox` → `HttpLogPanel`, `ErrorScreen` → `LoadErrorScreen`, `LoadingScreen` → `SkeletonLoadingScreen`, `Scrollbar.kt` → `ListScrollbar.kt`, `JsonUtils.kt` → `JsonExtensions.kt` (Android and iOS alike). The «Add pair» screen keeps its state in three groups (all-pairs list, search hits, DEX search) instead of 17 loose fields. All Android lint warnings from builds 150–155 fixed except one documented false positive (DEVELOPMENT.md, «Known warnings»): KTX helpers instead of `Uri.parse`/`edit().apply()`, symmetric padding in the portfolio widget, watch app backup rules for Android 12+, watch hint «Coins: max. 8» in 31 languages.

## 16.2.2 (build 155)

* New: Apple Watch app (watchOS 10), same as on Wear OS. It shows price and 24h change of up to 8 coins (against USDT), fetched directly from Binance (Bybit and OKX as fallback) – no account, no server. Which coins: «Like phone» (your favourites ★) or an own list of the common coins – chosen on the watch or on the iPhone under Settings › Widgets › «Watch» (also found by the settings search). Only this choice and the coin symbols are synced, directly between iPhone and watch (WatchConnectivity). Complication for the watch face (circular, rectangular, inline, corner) with the first coin. VoiceOver reads every row. 31 languages; privacy page updated.
* CI: the Swift checks (escape hatches, line limit, privacy manifest scan) and the release app inspection also cover the watch app.

## 16.2.2 (build 154)

* New: Wear OS app. It shows price and 24h change of up to 8 coins (against USDT) and fetches them itself directly from Binance (Bybit and OKX as fallback) – no account, no server. Which coins: «Like phone» (the coins of your watchlist favourites ★) or an own list of the common coins – chosen on the watch or on the phone under Settings › Widgets › «Watch». Only this choice and the coin symbols are synced between phone and watch (Wear data layer of Google Play services; no prices, amounts or portfolio data). A complication shows the first coin on the watch face (short: symbol and change; long: price and change) and opens the app on tap. Alarms keep arriving from the phone as before. 31 languages; privacy page updated. The Apple Watch app follows in build 155.

## 16.2.2 (build 153)

* Android code structure (no change for users): the 20 domain files (exchange and address parsers, alarm log, address and xpub caches, backup entries of watched addresses, coin lists, gas fees …) read and write JSON with the domain's own JSON instead of `org.json`; the storage layer converts at the boundary. `:domain` no longer uses `org.json`.

## 16.2.2 (build 152)

* Android code structure (no change for users): `:domain` gets its own small JSON reader/writer (`util/json`) that behaves like Android's `org.json` – same conversions, same number and character output – checked against `org.json` in a compatibility test. The domain files switch over in the next builds (step towards Kotlin Multiplatform).

## 16.2.2 (build 151)

* Android build (no change for users): one version of Room's annotation-processing library (2.8.5) in every module, `:domain` no longer declares the unused Timber and AndroidX Core libraries; lockfiles and checksums renewed by the «Supply chain update» workflow.

## 16.2.2 (build 150)

* New (Android): price overlay. Settings › Updates › «Price overlay» (off by default) shows the pairs of the permanent price notification – at most 3 – as a small, semi-transparent pill over other apps: symbol, price and change in the price colours. Drag it anywhere, it snaps to the edge; tap opens the app; drag it onto the × at the bottom to turn it off. It updates whenever the app has new prices (live while open, otherwise at the background interval), runs without its own service and needs the Android permission «Display over other apps», which you grant and can revoke yourself. TalkBack reads the prices and offers «Close overlay». Not available on iPhone (iOS does not allow overlays). 31 languages; privacy page updated (permissions table).

## 16.2.2 (build 149)

* New: compare 2 to 4 pairs. In the watchlist, long-press to select pairs and tap «Compare»: one line per pair in % since the start of the period (24h or «Today», 7 days, 30 days, 1 year), below each pair its price, 24h, 7 days, 30 days and the distance to the 30-day high. Every line has its own colour and dash style. Uses the same candles as the pair's chart – no extra server. TalkBack/VoiceOver read the chart and each row. Android and iPhone, 31 languages.
* Tests: the iOS accessibility audit runs in three groups of audit types (shorter runs, fewer timeouts; UI test step limit 45 min; a second full audit right after a timeout crashed the test runner on main 147), and the CI now also reports UI test cases that fail without an error line of their own.

## 16.2.2 (build 148)

* Settings search finds «Recent checks» (both platforms) and «Check on time» (Android) as their own results and jumps straight to them.
* Tests: the iOS accessibility audit only logs contrast findings for text under the translucent navigation bar (after a timed-out audit round the list can be scrolled); main 146 failed on this once.

## 16.2.2 (build 147)

* Tests only (no change in the apps): 132 small test files merged by topic into 30 files (16 Android, 14 iOS) – each test class unchanged, same names and fixtures.

## 16.2.2 (build 146)

* Android code structure (no change for users): `:domain` no longer uses Android or Timber types for its stored state and logging – the alarm snooze, the last USDT/USD reading, the bulk delisted state and the background error counter read and write through a small key-value interface (the app keeps the same SharedPreferences files), and the domain logs through `DomainLog`, which the app forwards to Timber. Step towards Kotlin Multiplatform.

## 16.2.2 (build 145)

* Portfolio widget, view «List»: new option «Bookings under the coins» (Off / Purchases / Sales / Both, default Off). If there is room left below the coins, each coin shows its bookings, indented and ordered by date: quantity (outgoing with «−»), the note shortened to 6 characters and today's value. Deposits and incoming transfers count as purchases, withdrawals as sales, fees paid in the coin are part of their booking – with «Both» the lines add up exactly to the coin's holding. Coins always come first; a coin shows all of its bookings or none. TalkBack/VoiceOver read each booking. Android and iPhone, 31 languages.

## 16.2.2 (build 144)

* Android code structure (no change for users): manual refresh (button and pull), the widget-chart reloading after a refresh, the chart of the action sheet with the shared chart geometry, the chart summary for TalkBack and the bounded memory cache moved to `:domain`; the app hands its widget updater, refresh statistics and network state in through narrow interfaces (`ChartWidgets`, `ManualRefreshStats`, `ManualRefreshNet`).

## 16.2.2 (build 143)

* Android code structure (no change for users): the settings storage (`SettingsRepository` with its DataStore, keys and reset of market alerts), the sparkline/day-reference candles, the live price stream, the market repository, adding several pairs at once and the address refresh moved to `:data` (13 files). The stored state of the market and gas alerts has its own small objects (`ZoneCheckPrefs`, `GasAlertPrefs`), so the settings no longer reach into the background workers.
* Tests: the iOS accessibility audit retries an audit that ran out of time up to twice (slow CI simulators); findings are never retried.

## 16.2.2 (build 142)

* Android code structure (no change for users): new Gradle module `:data` with the storage and network layer – both Room databases with their DAOs and encryption, the watchlist and portfolio repositories, exchange, candle, futures and address data sources, logo store and parts of the backup (159 files, packages unchanged). Room exports its schemas as before to `app/schemas`; the app's migration tests take them from there. The logo downloads get the app's user agent from the app (`AppUserAgent`) instead of reading the version themselves.

## 16.2.2 (build 141)

* Android code structure (no change for users): the price refresh itself (`PriceRefresher` – fetching, backoff, day references, deferred 24-h values, single refresh, side checks afterwards) moved to `:domain`. It reaches the watchlist, refresh statistics, candles, widgets, network state and the side checks through narrow interfaces (`PriceRefresherPorts`); the app builds it in `RefreshModule`. The shared list of blocked sources (`BlockedSources`) has its own file.

## 16.2.2 (build 140)

* Android code structure (no change for users): the evaluation after new prices – alarms, price notifications and announcements (`RefreshEffects`), saving and evaluating fetched prices (`RefreshResultProcessor`) and saving live prices (`LivePriceApplier`) – moved to `:domain`; the app hands its storage, notification and speech classes in through a Hilt module (`RefreshModule`) instead of a second constructor in each class. Moved along: alarm delivery with its notification budget, the app-wide background scope with its error counter, and the «database key unavailable» error.

## 16.2.2 (build 139)

* Android code structure (no change for users): the interfaces through which the price evaluation reaches storage, notifications, speech and data sources (`RefreshPorts`) moved to `:domain`, their adapters to the app classes stay in the app. With them moved the settings model (`AppSettings`, accent colour, price colour scheme, update mode), the watchlist write models (`PriceWrite`, `ErrorWrite` …) and the alarm data types (`VolumeSpike`, `WindowRanges`, `DerivativesValues`). The accent colour's name and logo resources are now looked up in the app.

## 16.2.2 (build 138)

* Android code structure (no change for users): the «Why is it moving?» analysis (activity, BTC correlation, broad market, funding and open-interest trends, reasons and summary), near-extreme and derivatives alarm rules, alarm sentences and templates, crypto pulse and market summary, and the futures source table and parsers moved to `:domain` (21 files). The futures data model `FuturesInfo` has its own file.

## 16.2.2 (build 137)

* Android code structure (no change for users): the portfolio logic (FIFO, average cost, history, tax engines and exports, CSV import, address checks), the remaining alarm rules, currency conversion and coin lists moved to the module `:domain` (116 more files, packages unchanged). App code reads nullable values of these models through local copies, because Kotlin does not smart-cast properties from another module. The CI coverage gate counts the module `:domain` again.

## 16.2.2 (build 136)

* Add pair (both platforms): if an exchange doesn’t answer while its pair list loads (maintenance, timeout, no network), the card names it – «LATOKEN isn’t responding right now – pair list not loaded. Try again later.» – and the button reads «Try again» instead of showing the same card without a word.
* Android code structure (no change for users): new Gradle module `:domain` with the rules, calculations and models that need neither screens nor storage (139 files, packages unchanged). First step of splitting the app into modules; CI lints the new module, the device tests and the supply-chain update know it.
* Build security: Gradle now also checks the PGP signatures of the Android dependencies against a stored keyring (`android/gradle/verification-keyring.keys`), in addition to the checksums; verification metadata and lock files include the new module.

## 16.2.2 (build 135)

* Tests: all Android instrumented UI tests use the v2 Compose test rule (Compose coroutines on the UI thread); the deprecated v1 rule is gone.
* Macro calendar: the self-test also parses the real BLS, FOMC and BEA pages as served on 26 October 2026 (stored in `.github/scripts/testdata/macro`), with the known number of dates per page – a layout change shows up in the test, not only in the live run.
* Build: the Kotlin warnings in the test sources are fixed; a vendor verification file of kotlinx-serialization is no longer packed into the APK/AAB.

## 16.2.2 (build 134)

* Tests: the Android UI flow tests use the current Compose test rule (v2), which runs Compose coroutines on the UI thread – the old rule let them continue on background threads and caused the rare Compose crashes in the CSV import/undo test.
* Tests: the iOS accessibility audit taps the storage notice's close button once more if a slow simulator missed the first tap.

## 16.2.2 (build 133)

* Exchange picker in sections: Spot · Futures · DEX pools · Forex (reference rate), favorites on top, search across all sections. Within a section the exchanges are sorted by trading volume on the install date (fetched once from CoinGecko's public exchange lists); without it alphabetically as before.
* Coin search: after the start exchange and your favorites, the pairs of the largest exchanges come first (volume instead of alphabet).
* New exchange: Bitunix Futures (perpetuals; Bitunix spot has no 24 h ticker). The API names no contract type, so stock contracts are not marked TradFi.
* DEX pools: GeckoTerminal as fallback when DexScreener answers with a server error or no longer knows a pool; a GeckoTerminal rate limit never pauses DexScreener.
* Forex: daily ECB reference rates (via Frankfurter) as currency pairs, labelled «reference rate, daily», never live and without alerts.
* Privacy page: Bitunix, GeckoTerminal, the one-time exchange lists of CoinGecko and the forex reference rates. Store descriptions: 56 markets on 45 exchanges.
* CI on main: release unit tests run again (AGP 9 had stopped creating `testReleaseUnitTest`).
* Clean-up before the next review: code comments (Android and iOS), both app READMEs, DEVELOPMENT, CONTRIBUTING, SECURITY, the privacy page and the CI comments checked against the app and corrected; translations made consistent (Portuguese, Dutch and Spanish portfolio terms, one name for Live Activities per language, newest texts use each language's word for alerts).

## 16.2.2 (build 132)

* Settings simplified: no «Advanced» page any more – «Token & TradFi» is on «Watchlist», market alerts and spoken output on «Alerts», coin logos under «Appearance», developer options (debug builds only) on «About the app». Search finds everything at its new place. The privacy page names the new places (coin logos, market alerts, Token & TradFi).
* iOS: the «Allow notifications» row no longer cuts its button text; with very large text the button moves below the sentence.
* Macro calendar: US release dates (CPI, PPI, jobs report, PCE) load again – the BLS pages need a contact address in the request, the BEA page splits words with inline markup.
* iOS accessibility audit blocking again.
* Android CSV import: progress is set on the main thread (fixes an occasional crash of the import/undo UI test).

## 16.2.2 (build 131)

* Coin logos: images up to 4 MB are loaded – Binance has uploaded high-resolution logos since 2025 (e.g. SATS, ACE, RATS, MOG showed initials); images that failed under the old limit are tried again at once.
* FIFO: the transfer fee spread over all lots is stored once per transfer and its parts are worked out only when needed – portfolio overview and tax export with many transfers are faster (amounts unchanged up to the last of 34 digits).
* iOS: exchange pauses, change basis, unusual activity and pending portfolio saves no longer redraw every screen; tab page titles show in the large content viewer on long press.
* Accessibility audits: Android also checks double text size, Arabic (right to left) and the alerts overview; iOS reports its findings (blocking again after the settings redesign).

## 16.2.2 (build 130)

* iOS starts faster: the watchlist is read and decrypted in the background, placeholder rows show until it is there (nothing is saved before it is loaded); frequently changing status values no longer redraw every screen.
* iOS FIFO and other portfolio decimals compute faster (digits counted without text conversion).
* Accessibility: alerts overview title and count follow large text (count moves into the list when the bar is too small), empty states scroll instead of clipping, decorative bell hidden from VoiceOver; iOS placeholders in text and search fields as dark as on Android; error colour (light) slightly darker; empty favourite star in a selected row darker.
* Portfolio menu: «Cutoff-date export…» and «Worksheet…» (page title «Worksheet»).
* Macro calendar is accepted only when it is listed in the signed market-data manifest.
* Smaller Android app: exchange classes are no longer exempt from R8 shrinking.
* iOS portfolio start page: the file import and address links are easier to tap (44 pt).
* Android watchlist widget: after the 15-second refresh lock the refresh symbol reliably returns – the countdown no longer runs into negative values («−01:05»).

## 16.2.2 (build 129)

* Portfolio menu: «Cutoff-date export…» and «Tax worksheet…» are separate; the tax worksheet is a page of its own (template selectable there, rules date and warning, the four exports and the year summary; it stays open after an export).
* Exports can be cancelled while running (no half-written file remains); errors name the cause (file location, missing prices, other) and offer «Try again».
* Widgets follow larger text sizes: iOS watchlist, single-pair, portfolio, pulse and lock-screen widgets and the Live Activity grow up to 1.3× (fewer watchlist rows when needed, amounts never shortened); Android keeps the price in the watchlist widget complete and shortens the pair name instead.
* Charts for screen readers: iOS Audio Graph and swipe up/down point by point, Android «Next/Previous point» actions; the portfolio comparison names both lines.
* Android 12+: «Reconfigure widget» opens the widget setup.
* Market-data mirror: the signed manifest can revoke a signing key; keys revoking each other turn the mirror off until an app update.

## 16.2.2 (build 128)

* Portfolio and tax worksheet: the cost origin of the average method and of the UK/CA/AT pools is no longer rewritten on every disposal – «Since purchase» in a display currency other than USDT and the UK export stay fast with many transactions (same numbers).
* CSV import: matching withdrawals with deposits uses a time window per coin instead of comparing every pair.
* Tax worksheet: the dialog shows the rules date; for a tax year after it, a warning says that rates and allowances may have changed.
* Pull to refresh and the widget refresh button are no longer blocked for 15 s by a live price tick; only a full refresh starts the 15 s window.
* Prices are taken only if they are not older than the stored one (a slower single refresh no longer overwrites a newer price); iOS applies refresh results one after another.
* Android: a refresh lock that hit its time limit exactly when it became free is no longer left held.
* Backups: pairs whose base or quote contains characters that do not belong in a symbol are skipped when restoring.
* Android 8/8.1: the cloud backup no longer contains the settings (it cannot be end-to-end encrypted there); app language and favourites still are.
* Fear & Greed notification names the mood index; English export menu matches the other languages; loading placeholders respect «Remove animations»; iOS arrows mirror in right-to-left languages.

## 16.2.2 (build 127)

* iOS: the watchlist no longer redraws every row while a refresh is running.
* Android: deferred price notifications survive an error late in a refresh; iOS: a pending price save is folded into the next full save; open-lots sheet keeps the full list while closing.

## 16.2.2 (build 126)

* Tax worksheet: the realised-gains export and the year summary compute the disposals only once per file (dates for the exchange rates and the rows come from the same run) and the two notes about network-fee cost and unassigned fees from one shared run – same files, less work.
* Price notifications no longer hold up a refresh: widgets, «Last refresh» and the updated time come first, the throttled price notifications follow (same order, at most five per second, reference price only for notifications actually shown); alarm notifications are still posted immediately.
* The list widget reads the watchlist once per update for all its widgets (header, empty note and rows) instead of three times per widget.
* Alarm thresholds reuse their number formatter.
* iOS: the watchlist recomputes less, the portfolio loads after the first frame, live prices are saved less often, «7D»/«1Y» cancel outdated work, and switching the currency no longer shows a brief «rate missing» note.

## 16.2.2 (build 125)

* Portfolio card, value history and «7D»/«1Y» are faster with large portfolios; the average-cost method now takes time in proportion to the number of transactions (the cost history per purchase is only kept where it is needed: tax export and «Since purchase» in another currency).
* RSI: completely flat prices now give 50 (neutral) instead of 100.
* Long lists of open purchase lots show the first 50 with «Show all (N)».
* Fewer watchlist reads per widget update (iOS).
* iOS: «7D»/«1Y» are calculated off the main thread, adding many pairs at once is faster and number formatters are reused.

## 16.2.2 (build 124)

* Android: the portfolio CSV import no longer freezes the app while the rows are checked before saving.
* The import preview already lists rows that cannot be saved (amount too small, swap without a valid coin or amount received) as skipped, so one such row no longer stops the whole import.
* Android: if the app was closed by the system during a CSV import, the import closes cleanly and «Import CSV» works again right away.
* Android: the list widget clears an expired «Retrying at …» time when the app starts.

## 16.2.2 (build 123)

* Android: when the widget's «Refresh» has to wait because the encrypted storage is not unlocked yet, the watchlist status shows «Storage still locked – retrying in 27 s» and counts down (announced once by TalkBack); the list widget shows the fixed retry time «Retrying at 14:32» (12/24 h as set on the device) instead of the time. The retry interval stays 30 s.
* Android: «Last refresh» notes under «Waiting to start» when a run was not the first attempt («Attempt 2 – the encrypted storage wasn't ready on the first one»).
* Android: the portfolio CSV import keeps its result when the screen is rotated while saving; preparing the rows after «Import» runs off the main thread; «Cancel» disappears once all rows are saved (linking transfers can no longer be cancelled); a failed row is reported as an error instead of a count.
* «Remove pairs again?» after adding many pairs checks the undo window again on «Remove»: if it has expired, nothing is deleted and a note says the pairs stay in the watchlist (Android and iOS).

## 16.2.2 (build 122)

Phase C, continued:

* Portfolio CSV import of large files (e.g. 10 000 lines) runs in the background with a progress bar «Line X of Y» and «Cancel»; cancelling leaves the portfolio unchanged. On iPhone the import no longer blocks the app while saving.
* The setting «Show the period next to the change» is gone: the period (24h, today, last update) is now always shown next to the change – in the pulse line, the single widget and the iOS Live Activity too.
* Watchlist: without own groups the group row shows «Your groups will appear here» with «New group» from the first start (it used to stay empty until the first alert).
* iOS: the UK tax pool finds the 30-day matches faster (same result).
* Tests and tooling: line coverage is now a gate (logic at least 58 % on Android, 78 % on iOS, just under today's values); performance tests with 10 000 portfolio transactions (holdings and tax years for NEUTRAL, CH, DE, UK, US); synthetic error responses (broken, empty, HTTP 429, rate-limit texts) for 14 exchanges; shared cases for quiet hours (including daylight-saving changes) and currency conversion; iOS checks currency codes and the rate-limit length exactly like Android.

## 16.2.2 (build 121)

Start of phase C (quality and tests):

* iOS: the app is built in the Swift 6 language mode (strict concurrency checked by the compiler and at run time); system callbacks (notifications, WebSocket ping, Low Power Mode) run on the right thread.
* Watchlist search: the search text of every pair is prepared once per list change, so each keystroke only compares (also with thousands of pairs).
* Indicators: the RSI with an invalid period returns no value on Android, like iOS (it returned «not a number»).
* Tests and tooling: line coverage shown per platform as total and «logic» (Android including the market-data module, iOS new); new shared indicator cases and tests for exchange rates and price formatting; weekly mutation tests for the tax and portfolio logic; the device test classifies the undo of «add many pairs» as a user write (locked watchlist: nothing changes); the store-guide check also covers tag examples and the archive name; the performance tests run in their own CI step (not in parallel with other tests, so the timings no longer vary with the load); one lint warning less (bulk-add banner reads resources via LocalResources).

## 16.2.2 (build 120)

Fixes from the thirteenth review round (on build 119, end of phase B):

* Market headline: when BTC, ETH and SOL move the same way but the whole market moves at least 1 % the other way or most coins do, the headline says «mixed» instead of following the three.
* Adding many pairs: a cancelled or failed run frees the button again (with an error message on failure); the result is reported once (not again on the watchlist); «Undo» is offered only within 60 seconds and removes only new pairs that have no alarm yet; the button text no longer flickers; iOS adds all pairs in one pass (no hang with 2000 pairs) and shows «Undo» as long as Android.
* Watchlist: the converted price may wrap to a second line, so «conversion uncertain» and an old exchange-rate time are never cut off; iOS filter chips no longer jump back to the start at the end of the row; relative times at the start of a line begin with a capital letter.
* Market pulse: chips and info texts say «leverage mood» like the lead line (measured by the funding rate of Bitcoin futures).
* «Alarm saved · notifications off» is shown as information, not as success.
* Android: «not checked» marks of a refresh run are collected and published once (no copy of the whole list per alarm), like iOS.
* SBOM: SHA-256 also for libraries whose variant file has a different name (Compose, DataStore, OkHttp, Room …); 122 of 172 components carry a hash, the rest have no binary of their own.
* Tests and tooling: scaling checks for the paths made linear, a tighter tolerance; the mirror self-test compares the keys with the apps; the reproducible-build check also runs for SBOM changes; the iOS test seam for the save gate exists only in debug builds.

## 16.2.2 (build 119)

* Messages at the bottom match the theme (dark in dark mode instead of a light pill); success shows a green outline with a check mark, errors a red outline with an exclamation mark (orange with the colour-vision palette), information only a quiet outline. Success stays green with swapped price colours; the meaning never depends on colour alone (both platforms).
* Software bill of materials: every Android build writes a CycloneDX SBOM of all shipped libraries with version, package URL and the verified SHA-256; the iOS part states that the app has no third-party packages.
* Reproducible build check: the Android release APK and AAB are built twice on fresh runners without caches and compared byte for byte (main, release tags, weekly).
* Behaviour tests for the central guarantees (no write without consent while the watchlist is not saved, consent only for the action itself, bulk add with undo, the pair limit on undo) instead of source-text checks where they cover the same; performance tests with 2000 pairs (sorting, filters, search, refresh, alarms, pulse, widget plan, backup size) with fixed limits and a scaling check.
* iOS CI: release build, unit tests and UI tests run on three Macs in parallel (about 25 instead of about 65 minutes); the duplicate Release build for the Simulator is gone, the release checks run on the device build.
* iOS performance: alarm changes after a refresh are applied through an index (no longer one linear search per change), and the «not checked» marks change single entries and notify the view once per run.

## 16.2.2 (build 118)

UX review of build 117 (both platforms unless noted):

* Adding many pairs at once (Android): continues when you leave «Add pair», with a progress bar there and above the watchlist and the result on the watchlist; both platforms count new pairs first («312 new, 434 already in the watchlist»), disable the button when all are there, name only the new ones in the confirmation and offer «Undo» for exactly the pairs just added.
* Market tab: the headline follows the whole market and its breadth as well as BTC, ETH and SOL – never «mixed» when all three move the same way – and «slight»/«clear» use one shared scale (1 % / 3 %) everywhere.
* Watchlist: relative times in lower case; the «last check» line only when background checks are late; the selected filter chip shows its name, and a fade at the edge hints that more chips follow.
* Empty portfolio: one paragraph instead of two so the disclaimer fits on the screen; the example amount uses the app's number format.
* Wording: «leverage mood» instead of «funding» in the market lead line; the search hint in «Add pair» is one sentence; the finished step badge no longer looks like a checkbox.
* Exchange API check: One Trading is checked with BTC/USDC (BTC/EUR is closed), Lighter with the BTC market instead of the first row.

## 16.2.2 (build 117)

* Watchlist: right after the start, the «≈ CHF» line with the note on the USDT rate could push pair, name and exchange out of the row. The converted line now takes at most half the row (longer text ends in «…»), the row shows the short note «conversion uncertain» (the full sentence stays for the screen reader), and the conversion is recalculated as soon as the first USDT/USD reading arrives instead of after up to 60 seconds (both platforms).
* Signed data mirror: the apps and the workflow accept a second, reserve public key; its private key is kept offline, so a lost or leaked signing key can be replaced without an app update (F-29).
* CI: Kotlin compiler warnings in the app and library sources fail the Android build (ceiling 0, like lint and the iOS release build); three unnecessary safe calls removed.
* Dependency verification trusts the Gradle sources that Android Studio downloads for the IDE (not part of the build), like the existing sources and javadoc rules.
* iOS tests: the in-memory test keychain is used only when the host verifiably lacks the keychain entitlement (probe entry), never in an unknown state.

## 16.2.2 (build 116)

Fixes from the twelfth review round (on build 115):

* CI: pull requests always run the Android, iOS, Strings and Parity workflows (no paths filter there any more), so these jobs can be required checks without blocking documentation-only pull requests; pushes to main keep the paths filter.
* iOS CI: environment-dependent skips of the background run tests (quiet hours of the test app, «not enough time left» on a slow runner) are reported but no longer fail main; the in-memory test keychain is only used when the test host lacks the keychain entitlement (never when a real keychain is merely locked).
* One Trading: a rate limit (429/418) of the all-tickers fallback is passed on, so the exchange pause applies instead of the app asking again (both platforms).
* Release checklists name all seven tag workflows, including the dependency vulnerability check.

## 16.2.2 (build 115)

Quality towards a review score of 9:

* iOS: all 94 compiler warnings of the Release build fixed (Swift strict concurrency: restated Sendable conformances, main-actor isolation of gestures and view helpers, async-safe locking, Live Activity calls without crossing actors); the warning ceiling drops from 94 to 0, so every new warning fails the build. The CI lists every warning in its annotations.
* iOS: the watchlist lock and background run tests no longer need the real keychain (an in-memory test keychain is used only when the real one is unavailable; the lock test also waits for pending writes of the previous step) and therefore run in every CI build; skipping them on main counts as failure.
* Dutch: the remaining three texts use «Portfolio-vergrendeling».
* iOS: second UI test – the result dialog after creating a backup (forced «could not be verified») appears and closes.
* One Trading: when the single-ticker endpoint answers with a server error (5xx), the price is read from the all-tickers endpoint (both platforms); a pair missing there keeps the original error and is never marked as delisted.
* CI: unique job names (Android build, iOS build, Strings check, Parity check, Release readiness check) so they can be required checks.

## 16.2.2 (build 114)

Fixes from the tenth review round (on build 113):

* Watched addresses: a refresh result is discarded when «count tokens» was turned on or a network was added while it ran (compared with the settings the refresh used, instead of a reset marker); the address is then loaded again. Turning something off keeps the result (it is filtered). iOS refreshes after every network change, like Android.
* iOS: the backup/restore result dialog appears shortly after a confirmation or file picker has finished closing and is checked again before showing; a result that arrives while a dialog is open follows after it. New UI test for the restore result dialog.
* Restore hints name the own server exactly like the setting («Own server for addresses»); Dutch uses «Portfolio-vergrendeling» everywhere. Tests check this in all 31 languages on both platforms.
* CI: the weekly exchange check also tests One Trading's all-tickers endpoint (the single-ticker endpoint answers HTTP 500 since 21 October).
* Android: the address balance write rule is tested against a real Room database.

## 16.2.2 (build 113)

Fixes from the ninth review round (on build 112):

* Watched addresses: turning on «count tokens» or adding a network while a refresh runs is no longer overwritten by that refresh; the stale result is discarded and the address is loaded again with the new setting (both platforms).
* Backup/restore results: the toast-or-dialog limit counts East Asian wide characters double and shrinks with the font size; «backup not verified» and «backup from a newer version» always appear as a dialog. iOS shows the result dialog as soon as no other sheet, picker or question is open (no fixed delay) and still asks the notification question when the page is left. iOS toasts are announced to VoiceOver after the focus change (400 ms) and restart when the same text appears again.
* Own Bitcoin server: leading spaces no longer let an over-long address pass as valid.
* The warning before restoring a backup without password uses the exact setting names of each language; the lock hint after restoring does too (fa, pt, he, th, hu, it).
* Signed data mirror: new signing key (the previous private key was lost; no app with the old key was released). Docs: privacy policy, DEVELOPMENT, iOS README and SECURITY list all four rules for backups without password; SECURITY names the environment «mirror-signing».
* More tests: cancelled and failing refresh rounds free the gate, late test results after a reset, robust waiting in iOS tests.

## 16.2.2 (build 112)

Fixes from the eighth review round (on build 111):

* Watched addresses: only one refresh runs at a time; a second one waits and reads the list afresh, so no address is queried twice (both platforms, also for xpub keys).
* Backup and restore results with a note (own Bitcoin server, portfolio lock kept, entries over the limits) or a long text now appear as a dialog instead of a short toast (Android limits toasts to two lines); the notification question follows after closing it. iOS announces toasts to VoiceOver and keeps them visible according to their length.
* Android: all dialogs with longer texts scroll at large font sizes (shared building block, guarded by a test).
* Own Bitcoin server: pasting more than 200 characters is reported as invalid again (build 111 shortened it to a valid-looking address); iOS also asks before switching to public nodes when the address list cannot be read; no public default hosts in the address code any more; a late result of a cancelled server test can no longer overwrite a newer test.
* The warning before restoring a backup without password names what is not taken over (portfolio lock, own Bitcoin server, hide amounts, system backup), in all 31 languages; SECURITY.md describes the same rule. Arabic dual form for unknown tokens.
* CI: the mirror signing step runs on main only and in the environment «mirror-signing»; the dependency graph is built with read rights only and submitted in a separate job without Gradle. More tests for the signed mirror (cancelled caller, size limits over the real loading path).

## 16.2.2 (build 111)

Fixes from the seventh review round (on build 110):

* Own Bitcoin server: a backup without password never sets, replaces or removes it any more; the restore message says so. A password backup that replaces or removes the server is mentioned in the restore message too.
* Own Bitcoin server: no 6-hour block after HTTP 403/451; saving or a successful test lifts any pause. The test result belongs to the tested address only (discarded when the input changes) and is announced by TalkBack/VoiceOver. Pasting more than 200 characters is shortened the same way on both platforms. Switching back to public nodes while Bitcoin addresses or xpub keys are watched asks first and names the public hosts. The hint now asks for a publicly trusted certificate (e.g. Let's Encrypt).
* iOS: concurrent address refreshes keep their own Bitcoin hosts; manifest and signature of the data mirror are size-limited like on Android (64 KB / 1 KB).
* Android: the privacy dialog before the first xpub scrolls (long texts at large font sizes were cut off).
* «+N unknown tokens» is a real plural in all 31 languages.
* iOS lock screen portfolio widget keeps the chosen range and display (the per-view rule applies to home screen views only).
* Confirmation «watchlist not saved»: iOS confirmation only via the action itself; guards on both platforms also catch writes in another scope or a detached task and two more condition forms; behaviour tests pin the boundary.
* CI: the market data run ends red if the manifest stays unsigned (data is still uploaded); the supply chain check rejects a changed configuration block in the verification metadata. More tests: signed mirror success path with a test key, URL length boundary for bulk requests.
* Docs: README, SECURITY and privacy policy describe the signed mirror and the own Bitcoin server.

## 16.2.2 (build 110)

* Code comments describe the code as it is now: build-number history removed from comments in about 820 files (audit F-21); compatibility notes for older versions kept without numbers.
* iOS: own text for the local network prompt (own address server in the home network), in all 31 languages.
* Portfolio widget: the display hint now correctly says the USDT line is hidden for a USD stablecoin (e.g. USDC), not for USD.
* Supply chain active: Gradle dependency checksums (`gradle/verification-metadata.xml`) and lock files from the «Supply chain update» workflow; every build now verifies each dependency.
* CI: lint warnings additionally listed per finding (grouped by id and file, with line numbers) in the check annotations.
* Lint warnings cleaned up: real findings fixed (KTX extensions, modifier parameter order, empty super call, obsolete resource folder, one unused string in 31 languages, unused Hebrew plural form), deliberate cases suppressed per element with a reason (RemoteViews widget layouts, iOS-only strings, translations flagged as typos, dependency update hints handled by the supply chain workflow).
* Repository: a stray CI output line at the end of `docs/privacy/index.html` (published with build 109, after `</html>`) removed; the page is again identical to `android/docs/privacy/index.html`.

## 16.2.2 (build 109)

* Mirror data is signed: the apps accept files from the GitHub data mirror only with a valid signature (ECDSA P-256 over a manifest with SHA-256 per file, at most 7 days old); otherwise they use the exchanges directly as before.
* Watchlist not saved: a pass without a visible screen now covers only that one action (Android per coroutine job, iOS per task) instead of switching the safeguard off until the next answer.
* Supply chain: Gradle dependency locking and checksum verification prepared (files generated by a dedicated workflow), weekly and per-change vulnerability check against OSV, coverage report (Kover, display only), lint warnings summarised in CI, first unit tests in the marketdata module.
* Tests: the confirmation guards on both platforms also check the direction of the condition (no write in the refusal branch).

## 16.2.2 (build 108)

* Portfolio: a transfer counts as «more than recorded» on every method (FIFO, average, FIFO per wallet) when amount plus network fee exceed the holdings; the uncovered part of the fee is shown as unassigned. UK and Canada pools report an uncovered fee instead of dropping it (the app's own UK/CA templates were not affected).
* Own server for addresses (Settings › Portfolio): Bitcoin balance and xpub queries can go to your own Esplora/mempool-compatible server (https only, test button, no fallback to public nodes); included in backups. A consent dialog now also appears before the first xpub/descriptor and explains what the node operator can see.
* Portfolio widget: «List» offers only «USDT» and «Conversion» (the USDT line has no effect there); «Overview» offers 30 days and 1 year (24 h and 7 days repeated the fixed figures). Saved choices are kept.
* Android: the notification permission after «Change anyway» in Settings › Price notifications appears in the current screen; double-tap protection for note, group and alert editing on both platforms.
* Diagnostics: background errors are also counted per error type (type name only, no texts). Privacy policy and SECURITY.md updated (data without encryption key: readable, changes not kept).

## 16.2.2 (build 107)

* Android: results after the «not saved» question survive rotating or a theme/language change – the alert editor closes after «Change anyway» (no second alert from a second tap), confirmations, undo and the notification permission appear in the current screen; the permission request can no longer crash on a screen that was just rebuilt.
* «Cancel» keeps the note and group dialogs open with their text (both platforms); the Android disable/enable sheet closes only after the change, with haptics like on iPhone.
* iPhone: the alert editor asks only once per save.
* The question text no longer names a reason that may not apply.
* Tests: the confirmation guards also check the cancel branch and closure parameters; the privacy scan skips Swift regex literals.

## 16.2.2 (build 106)

* Watchlist not saved: no feedback before the answer any more – favourite, disable, «add to watchlist», price notifications and group actions show banner, haptics and the notification permission only after the change was written; «Cancel» keeps a multi-selection and an open alert editor with its input; if the screen is briefly rebuilt, the app waits up to 2 s to ask instead of refusing silently. Clearer question text in all languages («Change anyway»).
* Portfolio alerts with an invalid «falls by 100 % or more» threshold say «delete and create again» (there is no editor); iOS no longer dims that hint.
* iOS: swipe-to-delete keeps the row visible until the answer; delivery markers are pruned only after the delete was written; the portfolio cleanup only marks itself done after the alert file was read; the pair picker reuses its key per data state.
* Background error counter also counts failures of the alert cleanups (Android).
* Tests and release: the not-saved guard checks every single write call on both platforms; iOS gate tests have a time limit; all iOS source guards fail when their file is missing; the privacy manifest check understands strings and more file-timestamp functions; release readiness compares the build in the private work lists with the project.

## 16.2.2 (build 105)

* Watchlist not saved (encryption not active yet): the confirmation now comes once per action and before it – «Cancel» changes nothing (no «removed» banner, notifications stay, no «alert limit reached»); «Continue anyway» runs the normal path with banner and undo; actions on many pairs ask once; on iPhone the question also appears above the alert editor and the explorer.
* Alerts: «falls by 100 % or more» can no longer be switched on (pair and portfolio alerts); stored portfolio alerts of that kind are switched off once and marked; an old delivery marker no longer swallows a new real trigger; iOS stops notifying at the end of the background time also in the extra-data path and between alerts; iOS only clears a delivery marker after the alert was really written; Android keeps delivery markers while only an in-memory copy is open.
* iOS: the privacy manifest matches the code again (monotonic clock instead of the system uptime API) – checked in CI.
* Security: DexScreener pool ids with «.» or «..» parts are rejected; coin logo addresses are checked with a real URL parser (no tricks with «#» or «\»).
* Performance: the coin page only recomputes when a price it needs changes (both platforms); the iOS pair picker builds its rows in the background.
* Privacy policy: alert log in diagnostics also lists the condition; background error counter stays on the device (on iPhone also in the device backup).
* Release and tests: source commit must be a full 40-character hash; tag must match version and build; iOS storage tests run in CI without skip; source guard tests fail when their file is missing; UI test requires the first-alert explanation.

## 16.2.2 (build 104)

* Positioning: Crypto Checker checks your exchange's prices and notifies you when a check finds your level reached – store titles «Crypto Checker: Coin Prices» / «Kurse & Börse» instead of «Price Alerts»; descriptions, screenshots captions, README and website say so honestly in all languages.
* Alerts: after a crash an alert only counts as already shown if its notification was posted after the attempt (an older notification of the same alert no longer swallows a new one); delivery markers are cleared when alerts are deleted, a backup is restored or the database is recreated; Android portfolio alerts use the same crash-safe delivery as iOS; stored «falls by 100 %» alerts from build 102 are switched off and marked «threshold invalid – please adjust», backups bring them in switched off.
* Background: no alert is shown after the background time has run out (both platforms); iOS planning ignores outdated demand decisions and re-schedules when needed.
* Portfolio: transfer fees above the recorded holdings show the uncovered part as unassigned instead of loading it onto the recorded coins; «undo import» reports failures; clearing the portfolio also forgets unavailable coins; the coin page skips some recomputation on other coins' price ticks (not yet all of it).
* CSV import: preview rows wrap (amount and price stay readable at large text sizes); choices are disabled while re-reading; the confirm check compares against the shown preview; daylight-saving gaps and overlaps west of UTC are handled like in Europe.
* Privacy and security: every request to an address node uses the neutral browser user agent (also fees and halving) and on Android its own connection pool; privacy policy lists time zone and source commit in diagnostics; DexScreener pool ids are validated; scheduled workflows fetch without write access and publish in a separate checked job.
* Accessibility: no notification permission prompt while the watchlist is locked; VoiceOver focus stays on a refused toggle; pair picker announces «no matches» and marks favourites.
* Release: release builds stop without a source commit; CI runs on version tags; one tester rule (invite 16–20, keep ≥ 12 for 14 days).
* Diagnostics: count of background errors per area (numbers and time of the last one only, no texts). While encryption isn't active yet, the first watchlist change per run asks for confirmation (it would be lost on restart). Device test for two simultaneous encryption conversions.

## 16.2.2 (build 103)

* Watchlist: while the stored watchlist can't be read (key temporarily locked), it is read-only on Android and iOS – changes show a short notice instead of being lost on restart. Background price updates keep running.
* Alerts: a crash between showing an alert and saving it no longer rings a second time; «falls by 100 %» (never reachable) is no longer accepted, also not from backups; undo after deleting respects the 2,000-pair limit and says how many pairs stayed out; the pair picker for new alerts has a search field and lists favourites first.
* Portfolio: deleting, undoing and clearing report failures instead of showing «removed» (own notice when saving fails); undoing the last transaction of a coin keeps the page open; the coin page reuses the overview's data instead of loading everything twice; portfolio maths runs off the main thread on both platforms.
* CSV import: «Import» is disabled while the file is re-read after changing time zone, day/month order or decimal mark (the old reading could be imported); long lists of withdrawals and transfers show the first 10 and «Show all».
* Number input accepts Indian digit grouping (1,00,000) where the region uses it.
* iOS: re-saving after a background alert no longer writes an older state back (doubled alert); background work no longer blocks the main thread on file coordination; live prices are saved with a cheaper sync; on expiry the background task reports completion first; widgets stop working when their timeline is returned; no background task is scheduled when there is nothing to check (like Android).
* Android: background scopes catch unexpected errors instead of ending the app; the encryption conversion is serialized per database for the whole process; the portfolio lock requires strong biometrics from Android 11; the main activity can't be pulled into another app's task.
* Privacy and security: address lookups send the same neutral user agent on both platforms; live messages over 1 MiB are dropped; workflows that fetch third-party pages no longer hold a write token; a weekly keepalive keeps scheduled workflows enabled.
* Accessibility: search fields are named for TalkBack; coin badge initials meet 4.5:1 contrast in light mode.
* Diagnostics show the source commit; store texts mention the background limits in the first paragraph; README wording «shows what moves at the same time – no cause».
* Tests: Room migrations from the real schemas, behaviour tests for the read-only watchlist, background worker and iOS background task, fewer timing-dependent tests; one release-candidate device checklist with a «required before upload» stage.

## 16.2.2 (build 102)

* Backups: restoring refuses before replacing anything when the safety journal of the current data cannot be written (e.g. key temporarily unavailable) and says so; the rollback path is tested on its own. Alerts with a threshold of 0 or less (price level, percent, move, volume spike, open interest) are dropped and counted as invalid. Settings round-trip Android ↔ iOS checked against a shared fixture.
* Android storage: a device key that is briefly unavailable is retried and only treated as lost after a second app start; the portfolio lock is assumed on when the settings file is damaged; the backup password is no longer kept in memory (salted hash) and decrypted backup content expires after 5 minutes; set-aside database copies are deleted after the notice was confirmed and 30 days have passed. iOS: unreadable settings are also stored with the lock on.
* Alerts: iOS limits notifications per refresh like Android (20 plus a summary); percent-down thresholds are range-checked; editing an alert keeps fields saved meanwhile; re-arming checks the currency; the move history is stored per pair.
* Portfolio and tax: French fee path for uncovered rows, Austrian old holdings, rounding to 18 digits (banker's rounding) on both platforms; deleting a coin also removes fee references to it (with a notice); fees of withdrawals without a coin are shown as unassigned; an xpub with very many used addresses falls back to the quick search instead of failing daily and shows a hint.
* iOS widgets: shared row layout, safer logo decoding, per-widget storage, 10 s deadline for the portfolio widget, data written durably; reduce-motion respected.
* Interface: undo combines several removals into one; snackbars instead of toasts; actions have spoken labels; live prices use their own connection pool; the lock prompt opens only once.
* CI and docs: iOS paths trigger the Android workflow where shared fixtures change, marketdata lint; repository setup script also scans the history for secrets.

## 16.2.2 (build 101)

* Portfolio: a holding without a known coin name whose ticker stands for different tokens on other exchanges (e.g. BEAM, NEIRO-like symbols) no longer silently takes the Binance price as confirmed – the value is still shown, but marked «Price unchecked», and portfolio alerts treat it like an outdated price (they pause only if it makes up at least 1 % of the portfolio).
* Alerts overview: own hint «Portfolio alerts paused: price unverified for …» when only such prices block the portfolio alerts.
* Cut-off and tax exports and the portfolio value history no longer use an unverified price to confirm day-close prices of that ticker; affected coins are marked «unverified» as before for unchecked prices.

## 16.2.2 (build 100)

* Haptics unified on Android and iOS: one catalog by event (selection, light tap, long press, success, warning, error, delete) and one wrapper per platform. Android now also confirms saving, backups, undo and copying, warns at limits and validation errors and marks failures and deletions like iOS. No vibration while scrolling, on price ticks, in widgets or in the background; the system haptics setting applies.

## 16.2.2 (build 99)

* Limits: at most 2,000 pairs in the watchlist and 10 alerts per pair – adding more shows a notice (bulk add takes pairs up to the limit and says how many were left out); the same limits apply when restoring a backup.
* Percent input in Arabic/Persian accepts «٫» (2.5 % no longer becomes 25 %); an invalid percent shows an error on both platforms and iOS no longer discards it silently.
* iOS privacy: network requests no longer accept or send cookies (like Android), old cookies and the HTTP cache are cleared; «Reset app» also renews the storage key and clears temporary files.
* iOS: the portfolio widget is computed in the background after each price update; CSV import reads files with a size limit even when the size is unknown.
* Backups: restored pair fields are length-checked, invalid pair ids are dropped; an unencrypted backup can no longer switch on «portfolio in system backup» or switch off «hide amounts». Group, exchange and note names are cut by visible characters on both platforms. CSV import limits cells per row and rows.
* Accessibility: selected chips and bulk-add rows are announced as selected and have 44 pt / 48 dp targets; saving a transaction with errors jumps to the first invalid field and announces the error; fee fields explain that 0 or empty is allowed.
* DEX search tells offline from server errors and offers «Retry»; app shortcuts have long labels; a double tap no longer creates two portfolio alerts; own alert sounds are removed when replaced or on reset (Android).
* Android starts faster (database library loaded on first use, legacy database removed once in the background); in-memory caches are bounded.
* Consistent term for «alert» within each language; notification channel description no longer says «price alert» only; Arabic «open interest» corrected. All languages now address the user informally (French tu, Russian ты, Hindi तुम, Indonesian kamu …; Hebrew gender-neutral), app and store texts.
* Restoring a backup tells before replacing anything how many pairs and alerts exceed the limits and are left out; CSV files that are too large say so instead of «format not supported».
* CI: fixed locale and time zone for unit tests, deterministic timing tests, device tests also on API 26, smoke test forces jobs on API 34, iOS warning ceiling, retried UI tests reported.

## 16.2.2 (build 98)

* iOS like Android: an exchange pause with an implausible end time (clock set back) no longer lifts the pause on the next rate limit (also for the shared Binance pause); re-enabling a «near high/low» alert keeps the reported mark (no second notification for the same high); funding/open-interest alerts without data show «not checked».
* iOS: watched-address answers and coin logos are read with a size limit like Android (2 MB / logo limit). Android: deeply nested exchange answers are rejected before parsing instead of crashing.
* Diagnostics report: the temporary file is deleted after use (Android at the next start or when the preview closes; iOS only written when sharing).
* Notes count visible characters on both platforms (emoji, flags and families no longer count double or get cut in half); month and year follow the language on Android like iOS (e.g. Japanese «2028年10月»).
* «Label: value» texts follow the language's typography (French « : », Chinese/Japanese «：»).
* iOS: portfolio figures are computed in the background once per refresh (no stutter with large portfolios); date formats are reused; the password eye and the favourite star have 44 pt touch targets. Portfolio amounts shrink instead of being cut with «…» (Android and iOS). A double tap on «Save» no longer creates two alerts (Android and iOS). Android caches for hourly candles and sparklines are bounded.
* Privacy policy: corrected sentence on a failed encryption conversion (the old file is only read since build 93).
* CI: the release smoke test reads newer job lists (API 34) and fails on main when the emulator gave no result; a Room schema check fails when an entity changes without a new database version; runs on main are no longer cancelled by newer pushes; runners pinned to Ubuntu 24.04.

## 16.2.2 (build 97)

* Removing a watched address that cannot be saved now shows a message instead of doing nothing.
* Background refresh: if the alert storage cannot be read for a moment, portfolio alerts are still checked instead of being treated as «no alerts» (Android).
* Corrupted settings: after they had to be reset to defaults, the app says so once and asks to check the portfolio lock, alert interval and currency (Android and iOS; iOS now also replaces the unreadable settings file like Android).
* «Reset app» mentions that device backups (Google, iCloud or computer) keep the previous data until the next backup runs.
* Other apps can no longer open the portfolio or other personal screens directly; notifications and widgets of the app carry a per-install token. Copied token addresses are marked sensitive (Android) and expire after 60 seconds, device only (iOS).
* Network fees: shared test cases for both platforms; a single unreadable fee entry is skipped instead of discarding the whole history, and signed hex values are rejected (Android and iOS alike).
* Store guides point to build 97; rollback notes explain that only a new build can replace a release.

## 16.2.2 (build 96)

* USDT depeg check: a price whose exchange time is more than 2 hours old no longer counts as a measurement (same rule as price alerts) – no depeg alert and no USDT rate in the portfolio from a stale last trade; shared test cases on both platforms.
* Percentages everywhere through the same formatter: depeg notification and threshold, indicator alerts and texts, alert sentences (no longer the platform percent format, which differs between Android and iOS), «Why» texts, market phase and cycle chart, portfolio shares and widgets, spot markets. Turkish «%0,5», Arabic/Persian «٪» and the regional spacing now apply there too. Alert percentages keep the thousands separator («1,000%»); iOS VoiceOver reads the cycle chart like TalkBack; iOS portfolio «0.00%» is kept left-to-right in Arabic/Hebrew like Android.
* Volume alerts: the editor explains that hours without trades count as zero volume (thin pairs). New test for a series whose newest candle is 20 hours old.
* Privacy policy: states that an unencrypted system-backup copy left behind when the app is stopped during a backup is deleted at the next start.

## 16.2.2 (build 95)

* iOS volume spikes are computed on the hour grid like Android (missing hours count as empty, a series that stopped gives nothing), shared test cases on both platforms.
* Portfolio alerts: an address that never loaded pauses them only for its first 24 hours on this device (counted again after a restore or «Undo»); afterwards it is treated as failing (named in the alerts overview) and alerts evaluate without it. TON tokens missing from a full jetton page are marked «partly stale» and count like stale prices instead of keeping an old value silently.
* Undo of «Remove address» reports when it cannot restore (address added again, list full, not saved). An unreadable or locked portfolio no longer decides whether the portfolio is switched back on after a settings reset; iOS keeps that decision pending across restarts until the files are readable (like Android).
* Percentages: the space before «%» follows the region too (e.g. es-MX without, en-DE with); all remaining fixed «%» in texts use the same formatter (Arabic/Persian «٪»); tiny changes show «0.000%» without a sign; chart summaries and the portfolio widget use the same format. Android times follow the system 12/24-hour setting.
* Undo after clearing a long watchlist no longer posts a burst of price notifications (like iOS).
* CI: the release smoke test adds starter pairs so background workers run in the R8 build and fails on worker creation errors; permissions are checked against an allow-list; release smoke also runs on pull requests touching release rules; store guides are checked for the current build; size budget warning at 22 MB. Store explanations state that watched addresses are watch-only (no keys, no wallet).

## 16.2.2 (build 94)

* Watched addresses: an xpub checked in the background also looks at the next unused addresses, so change after a spend is no longer missing; an incomplete, lower background result is not stored. Addresses whose balance was never loaded (e.g. after a restore) pause portfolio alerts instead of counting as 0; an EVM network that did not answer stays selected; a full TON jetton page without a known token keeps the last value; background updates take the oldest addresses first; Litecoin uses its own supply limit; iOS keeps reading the address list when one entry is unreadable.
* Portfolio alerts: a stale price below 1 % of the portfolio value no longer pauses them; when they are paused, the alerts overview says why.
* «Notable» activity: hourly statistics only use returns exactly one hour apart and skip pairs with too many missing candles (no false ⚡ for thinly traded pairs).
* Live prices: reconnect delay resets only after a stable minute and is spread ±20 %; Android also closes a live connection when the exchange pauses, like iOS.
* Android: a corrupted settings file no longer hides an existing portfolio (iOS likewise for unreadable settings).
* Undo for «Clear watchlist», «Clear portfolio» and «Remove address» (the dialog names cost lots and history that go with an address; locked storage shows a notice instead of doing nothing).
* Consistent wording for unreadable saved data with one remedy («Restart the app»); English uses «alert» throughout and «Notable»; percentages follow the language (e.g. Turkish «%2,4», Arabic/Persian «٪»); time spans in notifications are localized; «last at» shows a date when it was not today; «Add pair» on the empty alerts overview; iOS explorer rows and fields grow with large text; Hebrew tax note without arrows.
* Release checks in CI: Android R8 release APK and bundle, release lint, merged-manifest policy, size, mapping, baseline profile and 16 KB alignment as annotations, plus a release smoke test on emulators; iOS release settings, a device build and inspection of the built app; store text limits, version parity and Xcode project drift on every pull request. Native debug symbols in the bundle; Swift optimisation set explicitly for Release; staged rollout documented.

## 16.2.2 (build 93)

* UK tax export: rebuys within 30 days after 5 April now get the exchange rate and day close of their own date (rates were only loaded up to the end of the tax year). CSV totals add up to the cent (gain per row = rounded proceeds − rounded cost); DE/AT withdrawal fees have their own total line, as in the summary. The «orientation, not a tax return» note also stands under the totals in the app.
* CSV import: fees in another fiat currency are converted (or flagged as missing rate) instead of being dropped; rewards with a fiat amount get their price from it.
* Exchanges: Bithumb's trade time is read in UTC (it was 9 h in the future), and any exchange time more than 5 minutes ahead is ignored; Lighter markets without trades in 24 h hold price alerts like a stale price; bitFlyer gives no price while trading is halted; LATOKEN no longer treats a snapshot time as trade time; HTX shows no day-start high/low as 24 h values; Independent Reserve volume is the pair's own. Recorded exchange answers are now checked for plausible times and high/low.
* Alert reliability line: keeps warning when only failed background runs remain, offline wake-ups no longer count as a check, and Android shows «alerts paused in the background» when the watchlist is only a copy in memory (the exact wake-up is then stopped).
* Android: when encryption fails although a key exists, the watchlist and portfolio are only read, never written unencrypted. An interrupted restore is retried once on the next start and otherwise given up (no automatic rollback over later changes). Large backups are read once and run out of memory with a message instead of a crash.
* iOS: an unsealed older watchlist is excluded from the device backup until it is sealed; «1 h later» also updates the app's in-memory state.
* No alert currency is offered for fiat quotes without an exchange rate; Android confirms copying the contract address below Android 13 and reads it out for TalkBack; the reliability line wraps at large font sizes; portfolio rows are read as one sentence by screen readers, and the iOS portfolio total no longer shrinks below 70 %; portfolio widget chart uses the same time zone as the single-pair widget; logo requests send the full app version.
* Store guides point to build 93.

## 16.2.2 (build 92)

* Portfolio: with «FIFO per wallet» the cut-off file, the German holdings sheet and the Swiss wealth file no longer keep counting an address opening lot that was sold or transferred away (they now match the card).
* Kraken CSV: a staking transfer is paired with the row that has the same refid first; the one-hour window only applies afterwards, so an unrelated deposit or withdrawal is no longer swallowed.
* iOS alerts: background and widget checks no longer notify for a pair that was edited meanwhile; «1 h later» on a price level works when the widget had triggered it; speech uses the alert currency like the notification.
* «1 h later» on an old notification can no longer switch an alert back on that you switched off meanwhile (switching off, editing or deleting removes its notification).
* Backups: written compactly; up to 64 MB can be read on both platforms, and a backup that would be too large is refused with a clear message instead of an unrestorable file. iOS keeps an interrupted restore for another try when rolling back failed.
* Android without a device key and an old unencrypted watchlist: background checks pause (alerts would otherwise repeat after every restart) and restoring a backup is refused until the data can be saved.
* Transaction sheet keeps the decimal separator it was opened with (Android: across a language change; iOS: while open).
* Quotes in fiat currencies without an exchange rate (e.g. NGN, ARS) no longer trigger lookups as if they were coins; they show no conversion.
* Alert reliability line: failed runs no longer count as «last check»; iOS warns only after 6 hours (background refresh runs less often there); no line limit at large text sizes. iOS confirms copying the contract address, also for VoiceOver.
* Futures: the delivery date moves to the next contract after expiry (search and old Binance ids). WOO X ignores an unusable trade time.
* Plain backup warning names holdings, balance history and cost basis; store texts mention Cardano; the privacy page describes the Android fallback exactly; iOS CI also builds the Release configuration; the repository README links the voluntary support page.

## 16.2.2 (build 91)

* Watchlist: price one step larger, the change basis («24h») always shown, and «+2.4 % · Binance» when the change comes from another exchange's candles; a quiet line shows the last background check and turns amber when alerts are at risk.
* Alerts: «1 h later» also for price levels; the editor shows pair, condition, amount and once/every time, the rest under «More»; volume-spike texts name the candle source; Android keeps «Undo» after «Check».
* Franc/euro alerts skip a check (and stay armed) when the FX rate is older than 36 business hours or when USDT/USD is only known via a USDC that is off by more than 1 %; USDT/USD is measured whenever such an alert or conversion needs it.
* Exchanges: Lighter markets without trades show no «0.00 %»; delisted pairs are confirmed on CoinDCX, Luno, ICRYPEX and HashKey; BingX and bitbank rate limits are recognised; DexScreener pairs show the contract address in the pair sheet; Aster «U» counts as USD. WOO X spot shows the real last trade and its time (no more «hourly price»).
* Portfolio: watched addresses count like the card in the cut-off file and the Swiss wealth file, also without cost lots; the card itself now counts a recorded transfer to an address once (and no longer drops a transfer away from it). Binance prices without a known coin name are only used for unambiguous symbols. DE 256 € line marked when values are estimated.
* Storage: without a device key Android never writes the watchlist or the portfolio unencrypted, like iOS (it stays in memory with a notice; an older unencrypted file is only read and stays untouched); addresses cannot be changed while the stored portfolio is locked; iOS reads the watchlist off the main thread when coming to the foreground.
* Tab labels shrink at large font sizes; widgets follow the app's type scale and use the system font for Arabic and Persian amounts; the portfolio sheet survives rotation; the single-coin widget opens its pair.
* APK keeps only the 31 app languages (checked by a device test); the app's licence file matches LICENSE, the origin note is separate; Play texts say «at most once per 15 minutes».

## 16.2.2 (build 90)

* Exchanges: the «no longer traded» recheck state is stored, so a fresh background process no longer downloads Binance's full list (~2 MB) each time; the iOS widget never starts a recheck.
* Swiss tax card: a partly known wealth value shows the «incomplete» note under the value, as in the CSV.
* iOS: deleting, undoing and clearing the portfolio only report success when the file was written; otherwise the «changes won't be saved» notice appears and cached prices stay.
* Android CSV import links stored transfers in one database transaction and says plainly when the locked portfolio prevents an import.
* Indicator alerts on older installs store their limit, so a later limit change notifies; switching a gas alert off can no longer be undone by a check that is running; background checks no longer hold their lock while waiting for the pulse widget.
* CSV transfer matching: when withdrawal and deposit differ by a network fee in the coin, the transfer now records the amount that arrived (fee on top at the source), so per-wallet FIFO (DE, AT, UK …) and wallet balances are correct. Transfers imported before build 90 keep their old amount.
* Light mode uses the same warm orange-red error colour as dark mode (#B8361A, error surfaces #FFDBD0) instead of Material red and pink, for every accent and in high contrast; iOS delete and reset actions use it instead of the system red.
* Alert editor hint reads «Enter a value»; Czech prompts use one form of address; Arabic xpub hint grammar; Greek and Ukrainian App Store texts shortened below the limit.

## 16.2.2 (build 89)

* Android: all 31 app languages are kept in the Play download (no language splits), so the in-app language choice works for installs from Play.
* iOS: a pending background refresh that lies implausibly far ahead (after a clock or time-zone change) is replaced; the alert readiness page no longer counts a last check in the future as recent (Android too).
* Exchanges: if every watched pair of an exchange is marked «no longer traded», a restart rechecks them once, so relisted markets come back. Lighter and Hyperliquid confirm «no longer traded» also with one or two pairs; a Hyperliquid mid price of 0 falls back to mark; Hyperliquid's 24 h change uses the displayed price.
* DexScreener results show the token's shortened contract address; searching by address lists pools with that token as base first. WOO X spot rows are labelled «hourly price».
* Alerts: the Undo after a first quick alert waits until the first-alert dialog is closed; the amount field is focused and «Enter an amount» explains the disabled button; percentages are formatted like the rest of the app; deleting an alert or all pairs also removes their notifications.
* Fear & Greed, gas and the Bitcoin indicator alerts (Mayer, MVRV, distance to all-time high): switching off resets the remembered state; a changed indicator limit is evaluated afresh.
* Locked watchlist: alert screens say «locked» instead of «not in watchlist» and do not open the editor; iOS keeps the selected group and blocks portfolio alerts with an explanation.
* Portfolio: CSV import on a locked portfolio shows the notice instead of «0 imported» and a real error when saving fails; delete, undo, clear and replace are also refused while locked (Android); iOS no longer reports success when it cannot write an existing portfolio.
* Backups keep the «count anyway» decision for watched addresses; iOS backups now include address cost lots.
* Legacy data keeps the old Austrian interest rule; the Swiss wealth overview counts known parts and marks a coin incomplete like the holdings file; CSV formula protection also after leading spaces; cancelling the import clears a stale preview.
* Pulse widget: background checks wait briefly for its data. Accessibility: no duplicate Explorer label, «Loading…» while «What is moving with it?» loads, ellipsis on long chips, grammatical Russian, Ukrainian and Czech announcements.
* Android: tapping a watchlist column quickly several times no longer skips back to the same order (the next step is computed from the stored sort, not the displayed one).
* xpub scans keep the BIP44 gap of 20 unused addresses; the add-address sheet says so.
* Store and privacy: Finnish, Romanian, Polish and Czech check-frequency sentences corrected; iOS text says amounts leave the device only by export or in the encrypted device backup; privacy page uses the current feature names, mentions the iOS device backup and names more countries.

## 16.2.2 (build 88)

* Gas and Fear & Greed alerts: changing a threshold (or switching the alert off and on) re-arms it, so a value already beyond the new threshold is reported; unchanged thresholds do not repeat.
* iOS: gas and USDT depeg checks no longer stay silent after the device clock was set back (as on Android).
* Android: after a clock or time-zone change the «Check on time» alarm is set again; a Crypto pulse widget keeps background refresh scheduled.
* Old alert notification for a deleted pair: «Adjust» no longer crashes (Android) or creates an orphaned alert (iOS); deleting pairs also removes their alert notifications.
* Quick alerts show their reference («+5 % · from now») and the confirmation names the alert; the empty start no longer shows the watchlist tap hint.
* CSV import: the loading dialog can be cancelled; nothing is written before «Import».
* Android: while the saved portfolio cannot be unlocked, new transactions and imports are refused instead of being lost on restart; the notice stays visible.
* Android: exchange pair lists are parsed off the main thread (no stutter when choosing Aster, BingX or CoinDCX).
* Lighter: a price of 0 counts as «no data», so delisted markets are confirmed instead of showing a permanent error; bulk rows need a price above 0.
* Austrian interest is recognised by whole words only («Verzinsung» no longer counts as interest).
* Swiss wealth uses the same rounded values per line as the holdings file; the «addresses with cost basis» note follows the cutoff date.
* Accessibility: alert preview no longer re-announces each digit, labels wrap at large text, search rows have one focus, every wrong backup password clears the field.
* Store: iOS text no longer suggests a Live Activity makes background checks more reliable; release notes read as a first version. NOTICE lists fonts, libraries, icons and data sources; privacy page names the countries.

## 16.2.2 (build 87)

* Alarm editor: quick alarms moved below the price condition, so the first thing you set is your own price; a chip still creates the alarm at once (with Undo).
* Watchlist hint now says that tapping a coin opens alerts and «What is moving with it?»; the empty start screen shows a chart icon instead of a bell that could not be tapped.
* Watchlist that cannot be unlocked right now (device key briefly unavailable): the notice stays until it is resolved and saving alarms is blocked with an explanation instead of creating alarms that would be lost. iOS now shows the same notice.
* Watched addresses switch to «show only» automatically only when every coin with a balance matches your own transactions (before, one matching coin hid the other tokens).
* Tax workpaper: the holdings file uses the template's cost method stated in its header and carries the «template replaces the method» note.
* Swiss wealth (31 Dec): the total is the sum of the rounded rows in the file.
* Cut-off and Swiss wealth exports add a header note when a counted watched address has cost-basis lots (transfers to or from it may appear twice or be missing there; the portfolio card is correct).
* Play store text (all languages): background checks «at most every 15 minutes, often only after hours», as the app itself says.

## 16.2.2 (build 86)

* Internal clean-up: string files grouped by topic, unused strings removed, translation check enabled in lint, no change in behaviour.
* Android watchlist widget: the refresh countdown no longer stays at 0:00 after unlocking – with the screen off the widget shows the refresh symbol straight away, and the redraw at the end of the 15 s lock is retried reliably.
* Android pair search: results are computed in the background but set on the main thread (fixes an intermittent crash in the device tests).

## 16.2.2 (build 85)

* Twelve more exchanges (now 53 markets on 44 exchanges plus DexScreener): bitbank and Coincheck (Japan), BTC Markets
  (Australia), HashKey Exchange (Hong Kong), Mercado Bitcoin (Brazil, cryptocurrencies only), Luno (Africa, Southeast
  Asia), CoinDCX (India), Paribu and ICRYPEX (Türkiye), BingX (spot and futures) and the perp DEXs Aster and Lighter.
  Each adapter was checked against recorded live responses; Android and iPhone read them identically.
* The daily exchange check covers the new exchanges.
* Android notifications show the bell of the app logo with a sign per alert type: $ for the USDT depeg, a bolt for
  «Notable», a calendar for economic dates, the Ethereum diamond for gas, ₿ for the Bitcoin fee and a gauge for
  Fear & Greed; price alerts keep the logo. iPhone always shows the app icon there.
* Dark mode: errors and error surfaces (notifications-off banner, delete swipe) are orange-red (#FF5833 / #4A1C10)
  instead of Material pink.
* First alarm dialog is titled «Alert saved» instead of «All set»: whether alerts actually arrive is what the
  checklist below it answers.
* Stocks, ETFs and commodities on Aster, BingX Futures and Lighter (incl. Robinhood stock tokens) are marked as TradFi
  with their real ticker (e.g. AAPL, not NCSKAAPL2USD); bitbank's «BCC» is shown as BCH.
* Watchlist sort mode: «Sort by coin» and «Sort by exchange» order the shown pairs once (favorites stay on top) and
  save the result as your own order – same coins from different exchanges end up next to each other; drag as before.
* Plain wording when the USDT dollar rate is missing: «USDT dollar rate missing – counted as 1 US dollar» instead of
  «USDT = 1 assumed» (all 31 languages, also the cost-basis hint).
* When USDT is valued via Binance USDC/USDT (no direct USDT/USD rate), the card line now also warns: «if USDC falls,
  this value is wrong».
* After the first alarm, the main button «Check that alerts arrive» opens the «Make sure alerts arrive» checklist
  right away, so you see whether alerts will actually reach you.
* Watchlist: tapping the small «*» next to a change explains it – «Calculated from Binance candles – the exchange
  itself doesn’t provide this value»; screen readers get it as an action of the row.
* Portfolio: swiping a coin (or «Delete» for screen readers) no longer removes all its transactions at once – a dialog
  first asks «Delete all transactions of BTC?» with their number; «Cancel» keeps the row. Afterwards the banner says
  «BTC removed from portfolio (N transactions)» with «Undo» instead of the watchlist text.
* Portfolio total card: the weekly change is labelled «7 days» instead of the abbreviation «7 d» (all 31 languages;
  the history range chips and widgets keep the short form).
* «1 h later» is offered only for percent and movement alerts, where it works; price-level alerts (also funding, open
  interest and near high/low) re-arm when the value returns, and volume spikes report at most once per hourly candle.
* Tax export: opening lots of watched addresses now also count for the fee notes and the year list, as they already did
  for disposals – a network fee on a lotted address wallet no longer shows as «fee not assigned – cost basis unknown»,
  and a year with only opening lots appears in the year selection.
* Opening lots of watched addresses are now capped at the address holding before the transfers you recorded for that
  wallet (balance + recorded outflows − recorded inflows): a recorded transfer Ledger → Binance no longer cuts the lot
  a second time (missing holding, cost basis too low), and a recorded transfer to the address is no longer counted
  again as address balance without cost basis.
* Portfolio lists end with a short line instead of the long data note: «Working aid, not tax or investment advice.
  Privacy: Settings › About the app › Privacy policy.» (all 31 languages); what data goes where is in the privacy policy.
* Watchlist pulse: the average change «avg. +1.80%» is shown in the normal text colour instead of amber – amber now
  only means «attention» (Android and iPhone).
* The free space below portfolio and alert lists for the floating «+» button is a named spacing value (3 × 32) on
  both platforms instead of a bare 96.
* Arabic and Persian: amounts use the system font instead of Rubik (which has no Arabic-Indic digits), so a price no
  longer mixes two typefaces (Android; iPhone already uses the system font).
* iPhone: the watchlist (pairs with notes and amounts, alerts) is now only ever saved encrypted, like the portfolio –
  if the keychain is briefly unavailable, changes wait in memory and are saved on the next possible write instead of
  in plain text; an unencrypted file from older versions stays readable and is encrypted with the next save.
* iPhone: saving the watchlist no longer blocks the screen (e.g. while pulling to refresh) when the widget is reading
  the file at the same time; alert delivery and going to the background wait for the write without freezing the app.
* iPhone: alerts reported by the widget extension play the chosen alert tone reliably – the app copies its tones at
  launch to the shared Library/Sounds folder, where iOS looks for them for the app and its widgets.
* Delisted pairs on exchanges with a complete price list (e.g. Paribu, BingX, Bybit, Hyperliquid) are now shown as
  «no longer traded» once that list and the single request both lack them («No data», «Unknown coin»), instead of a
  permanent error that was re-requested on every refresh; Lighter pairs without a stored id look up the right symbol.
* BingX: identical requests are recognised despite the timestamp in the address – no second identical request after a
  failed price list, and concurrent identical requests share one answer.
* A «too many requests» pause now also covers the other half on the same host (BingX, Bybit, OKX, Phemex, Bitget, WOO
  spot and futures) instead of only the market that got the 429.
* Android alert timing hint is honest about idle devices: without «Check on time», Android checks irregularly while
  the device rests – sometimes only after hours; with it, about every 15 minutes (all 31 languages, also the
  «permission missing» line).
* Android «Make sure alerts arrive»: on Xiaomi, Samsung, Huawei/Honor, OnePlus, Oppo/Realme and Vivo an extra row
  explains the manufacturer's battery saver (Autostart, deep sleep, app launch) – after it stops the app, nothing
  arrives – with a button to the manufacturer's page (falls back to App info; no new permission).
* Android «Check on time»: light Doze now counts as idle too, so the check uses the short network budget (about 9 s)
  instead of running past the idle network window.
* Android economic-dates morning notice: after a time zone or clock change it is re-planned for 08:00 local time (or
  shown right away between 08:00 and 12:00 if not yet shown today) instead of skipping a day.
* Android: password-protected backups can be restored on devices with 2 GB RAM – the app requests the large heap for the
  64 MiB key derivation (parameters unchanged, older backups stay compatible); if memory still runs out, it says «not
  enough memory» instead of crashing.
* Tax export: one daily-close rule for estimated market values – fee rows, gifts/payments, swaps and rewards without a
  value, network fees as disposals and the French portfolio value all use the close of the transaction's UTC day
  (network fees and the French portfolio value used the previous day before). A Kraken fee and a network fee in the
  same hour now have the same value; the rule is stated in the header of every tax CSV.
* CSV exports (holdings, realized gains, income): the total row adds up the rounded row values, so summing the column
  gives the same total (0.34 + 0.34 + 0.34 = 1.02, no longer 1.01).
* Tax years older than the USDT/USD history (about two years) are no longer silently counted with USDT = 1: besides the
  note on each affected row, the annual summary now also says «USDT dollar rate missing – counted as 1 US dollar».
* Clearer names: the Crypto Pulse card and widget («What stands out») are now «Crypto pulse»; «Unusual
  today» and «Unusual activity» (⚡ filter, settings, market notification) are now called «Notable» (German
  «Auffällig», a short equivalent in all 31 languages); the notification reads «Notable: SOL/USDT · Binance». Per coin
  it stays «What is moving with it?». Wording only, no change in behaviour.
* Android alert editor: entries survive rotating the device, and «Create»/«Save» stay pinned at the bottom of the sheet
  above the keyboard while the content scrolls – reachable even at 200 % font size. iPhone already kept both (state
  survives rotation, «Save» in the navigation bar).

## 16.2.2 (build 84)

* Portfolio: entries without cost basis can get one – a card «Entries without cost basis» lists them with a suggestion
  (the day close of the entry date) to apply, edit or leave unknown; rewards and airdrops keep their value at receipt.
* Watched addresses: opening lots (date, amount, cost) per coin; they count like purchases in FIFO, average and the tax
  export, capped at the observed balance. The French note about addresses without cost disappears once they are lotted.
* More sources for past daily closes: when Binance lacks a coin or day, Coinbase, Kraken, OKX and Bybit are asked in turn,
  with the same token check; a second USDT/USD source makes «USDT = 1 assumed» rarer. The value history names the source.

## 16.2.2 (build 83)

* Market tab «Now» has one main card: Crypto Pulse with «In short» inside it and a larger headline. Below it, quieter:
  Fear & Greed (compact) and gainers/losers 24 h (3 + 3, «Show all» for 5 + 5). «Unusual today» and the macro note
  are under «More market data» (a macro notification opens it).
* Portfolio in one place: «Settings › Portfolio» on the first settings page (group «General»); the duplicate entries
  under «More» and «Advanced» are gone.
* Cycle chart: older cycles clearly visible (at least 3:1 contrast).
* High contrast also raises the accent in widgets.
* iPhone: the hint for «Portfolio in system backup» says it only restores on the same iPhone; to move to a new phone,
  use the app's password backup.

## 16.2.2 (build 82)

Two more reviews before the store.

* «Outdated» in the row now follows the same rule as alarms: the last successful fetch is too old, or the exchange's
  last trade is older than 2 hours («Binance · last trade 3 h ago»). Quiet pairs no longer look fresh just because
  the last fetch worked.
* A price of 0 from an exchange counts as «no price» (e.g. a halted Binance pair ends as «no longer traded» instead
  of being asked again every run); an internal error without text never marks a pair as no longer traded.
* Coinbase: offline and trading-disabled products are left out of the pair list.
* Network: at most 32 requests at once (5 per exchange), timeouts count from the real start of a request, so a
  long watchlist no longer produces false timeouts and exchange pauses; DexScreener pools on the same chain are
  fetched together; identical Hyperliquid requests are shared.
* Transaction sheet: when the USDT/USD rate is missing it says «USDT = 1 assumed», like the totals. The portfolio
  chart says «As of … – exchange not reachable» when it shows an older stored exchange history.
* Alarm notifications: «1 h later» (repeating alarms; the alarm stays armed) and «Adjust» (opens the editor), coin logo
  from the device; the alarm log shows who checked («via widget», «background»).
* Spoken announcements follow the notification volume and stay silent on a muted phone; «Test voice/alarm» is always
  audible.
* Pull to refresh within 15 seconds is accepted and says «Just updated · N s ago» instead of «Again in N s».
* «Make sure alerts arrive» also counts unusual-activity alerts (and market notices on Android).
* Group chips grow with large text; Android German says «Benachrichtigungen» throughout (iPhone «Mitteilungen»).
* Widgets: spacing on the 4/8/12/16/24/32 grid on both platforms (rounded down so nothing wraps).
* Code: every production file is at most 400 lines (one documented exception, the settings model); a test keeps it
  that way. Independent reference data for BIP32/secp256k1 (1,000 random keys and 74 edge cases, checked against
  OpenSSL) and 20 random Argon2id cases on both platforms.
* LICENSE is plain MIT (GitHub shows «MIT»); origin, trademarks, market data and alarm sounds are in NOTICE.

## 16.2.2 (build 81)

Second review before the store.

* WOO X spot: the price is the hourly close and now carries the time of that candle – after 2 hours without a new
  candle the row says «outdated» and alarms wait.
* One Trading: the bulk request keeps only active spot pairs; a pair missing from it is asked for singly instead of
  being reported as «not traded».
* France: when watched addresses (without acquisition cost) are part of the total value, the summary says the gain
  is therefore likely too high and suggests booking a purchase.
* Android: the portfolio database is never created unencrypted. Without a key the portfolio stays in memory for
  that run, with a notice, like on iPhone. If the key of an existing database is briefly unavailable, portfolio or
  watchlist open empty for that run with a notice; the saved file is never touched, and backup is refused until a
  restart.
* New page «Make sure alerts arrive»: the real status of notifications, on-time checks, battery saving, background
  refresh and quiet hours, with buttons to the right system settings.
* «Share diagnostics» (Settings › About): a text file to send to support yourself – no portfolio data, amounts,
  addresses or notes, preview first, nothing sent automatically.
* Chart in a fiat currency says when the USDT history was converted with today's rate.
* Watched addresses: Taproot accounts via `tr(…)` descriptor (BIP86), single `bc1p…` addresses as before.
* Market tab: «Gainers and losers 24 h» under the market pulse (top 100 without stablecoins, CoinGecko).
* Stablecoins and wrapped coins are excluded the same way on Android and iPhone.
* Settings search finds «Make sure alerts arrive», «Share diagnostics» and «Alarm log».
* Store texts: «no own price» instead of «no live price» for Bitpanda and Swissquote.
* Code: every file over 650 lines split by responsibility on both platforms (behaviour unchanged).

## 16.2.2 (build 80)

Review fixes before the store.

* Alarm log: each price alarm shows «Last checked» (time, exchange, price seen, threshold, result); a new page
  «Alarm log» lists fired alarms and alarms that would have fired but were held back (outdated price, mark price,
  liquidity below 10,000 $, quiet hours, notifications off, no price). Stored encrypted on the device only, not in
  backups, at most 200 entries and 14 days. Price-level and percent alarms; the alarm decision itself is unchanged.
* Watchlist row: when an exchange pauses requests, the row says «Binance · paused until 19:45» instead of only the
  refresh sheet.
* «Set your first alert» no longer promises «as soon as»: it says that the system checks irregularly while the app
  is closed.
* First start names the source: «Prices from Binance in USDT» (Binance.US in the US).
* Switzerland: the tax worksheet shows the wealth value at 31 December (holdings × closing price in CHF) as
  orientation, per coin; missing closes are marked «not calculated». The ESTV price list is authoritative; capital
  gains and the wealth tax itself are still not calculated.
* iPhone Live Activity uses the widget design: same surface colour, text colours, price colours, rounded amounts and
  spacing; light or dark like the system.
* Design: tax-template selection uses the theme accent (iPhone), warning icon in the readable amber, note icon in full
  accent, sort arrows 12 pt, error screen uses the standard empty state, tab bar 8 pt padding and identical on both
  platforms; German «Funding ausgeglichen», «Tief», «Hoch» instead of English terms.
* Market pulse widget: when «Top 30» moves to the second line it gets its own dot (colour of the majority: rising,
  falling, or grey when even), so both lines look alike.
* Fear & Greed colours follow your price colours (card dot and pulse widget dot): fear in the falling colour, greed in
  the rising colour, so the colour-blind scheme, high contrast and swapped colours apply; the fixed scale is gone.
* Year-end holdings export: if watched addresses cannot be read, the file says so and the total is marked
  incomplete instead of silently leaving those balances out (same for the Swiss wealth value).
* Alarm log: live prices write the encrypted file at most once a minute unless an alarm fires or a new outcome
  starts; nothing is written unencrypted on iPhone either.
* Spacing on the 4/8/12/16/24/32 grid on both platforms (about 90 values, shrinking so nothing wraps; touch areas rounded up); a guard test keeps new off-grid spacing out.

## 16.2.2 (build 79)

Review fixes before the store.

* iPhone: a backup that contains watched addresses or an xpub (even without transactions) now requires unlocking,
  preselects a password and names the addresses in the warning; watched addresses are never written unencrypted
  (like the portfolio); new portfolio-alarm thresholds no longer end up unencrypted in the watchlist file.
* Android: a system-backup copy that could not be imported is retried once and then always deleted.
* Privacy page: precise list of what can stay unencrypted when encryption cannot be set up.
* Calculations no longer crash on unexpected missing values – the row is marked unpriced instead.
* Hyperliquid: a mark price used because the order book is empty is labelled «Mark» and does not fire alarms; WOO X
  spot is labelled as hourly price.
* «Set your first alert» names the pair it opens; «outdated» uses the same amber colour everywhere; the live chip no
  longer reaches into the neighbouring row.
* Store release notes: «current prices» instead of «live».
* CI: a parity check fails if the shared test data of Android and iPhone ever differ.
* First start shows one task: prices and «Set your first alert»; the groups hint, the market pulse row and the gesture
  hint appear after the first alert or after three days of use.
* Refresh: after a refresh the watchlist menu counts down «Refresh (15)» … «(1)» and becomes active again by itself;
  pulling during the lock says «Again in N s». The watchlist widget gets the same 15-second lock with a countdown
  in place of its refresh button (system timer, no extra battery).
* Market pulse widget: when Fear & Greed and «Top 30» don't fit on one line, the Top 30 moves to a second line
  instead of disappearing.
* Market pulse widget: «In short» sentences (market direction, Fear & Greed) – the first one replaces the lead on
  the medium widget, the large widget shows all that fit with sources and «no forecast, no investment advice»;
  stale sentences disappear by themselves. New large size on iPhone.
* Security audit follow-ups: the iPhone developer HTTP log records nothing in release builds and shortens addresses
  in debug builds; Android device tests (UI, database, migration) now run on every push that touches the app.

## 16.2.2 (build 78)

Independent check of every calculation (five auditors, official sources) and the fixes it found.

* Portfolio card: «Since purchase» and «Invested» in your currency use the exchange rate of each purchase day (same
  rule as the tax export) instead of today's rate; missing rates are marked «incomplete».
* Portfolio curve and «7 D / 1 Y» agree with the card: the first day of a young portfolio is included, a swap
  without a value is priced once at the day close, a transfer fee from an empty wallet no longer counts, and the
  window follows the UTC day.
* Germany: FIFO per wallet is required (BMF letter of 6 March 2025).
* Austria: sales of new holdings from 1.3.2021 to 31.12.2021 count as speculation under the old law (440 € limit,
  purchase and sale fees deductible); the 27.5 % option only for 1.1.–28.2.2022; income file separates lending
  interest (taxable) from staking (information only); note on staking received before 1.3.2022.
* Canada and Australia: a net loss shows 0 taxable gain and its own carry-forward line.
* UK: notes when the 30-day rule can still change the figures and when proceeds above £50,000 must be reported;
  Canada: same note for the superficial-loss window.
* France: a purchase whose fee consumes everything is no longer deducted twice; withdrawal fees are information
  only; the outflow line uses purchase-day rates; coins bought in the same millisecond get a price.
* Exchange-rate text says what the code does (rate of the day, otherwise up to 7 days before); long rate histories
  are loaded year by year so they are never thinned out.
* Alarms: percent alarms fire exactly at the threshold; spoken prices round the same on both systems; currency
  conversion refuses crypto quote prices older than 15 minutes; Bulgarian lev derived the same way on both systems;
  portfolio alarms in USDC/DAI use the USDT market price.
* Alarm cards show whether an alarm is really watched («Notifications off», «Low liquidity», «Waiting for a current
  price»); Settings › About explains in four lines what stays on the device.
* Transaction form: the note sits directly below the price for every type.
* Second audit round: the portfolio widget shows «Since purchase» and «7 D» in your currency like the app (never USDT
  × today's rate); France: withdrawal fees also left out of the gains CSV total; Austria: lending interest before
  1.3.2022 under the old law; UK/CA 30-day note until the last day of the window and only when relevant; UK £50,000
  test on proceeds before fees (£49,200 for 2020/21–2022/23); exchange-rate history split into equal yearly parts;
  rates reload after midnight; «Today %» portfolio alarm rounded like the others.
* Kotlin 2.4 compiler warnings cleared.

## 16.2.2 (build 77)

Structural overhaul of the code base (no new features), plus tests for the wiring.

* Backups with a password use format v3: key from Argon2id (64 MiB, 3 passes), the file header is authenticated
  together with the content; v2 files (PBKDF2) can still be opened. Cross-platform test vectors on both systems.
* Android architecture: the watchlist view model composes three controllers that own their state; the screen collects
  one UI state; type-safe navigation routes; market jumps and explorer sections survive process death; settings in
  focused slices.
* Refresh: the refresh lock hands out a token, so a re-entrant call can no longer be written by accident; live
  prices, chart reloads, result processing and side checks are separate classes; errors are typed (`MarketFailure`)
  on both systems instead of matched by text; every exchange declares its storage key explicitly.
* Logos and coin lists: split into image store, list sync, Binance Alpha and names; downloads stop when cancelled; a
  symbols-only mirror never replaces a list with names.
* Portfolio: the transaction sheet is split into form state and sections with tested save checks; tax engines one
  file per country, tax export split into rows, summary and CSV (iPhone: moved into its own folder).
* Cancellation is no longer swallowed in background code (119 places), guarded by a test.
* iPhone: strict concurrency checking enabled (warnings), unlocked shared state now behind locks; exchange pauses
  expire like on Android when the clock jumps.
* Tests: alarms, result processing and live prices against fakes of the real classes; HTTP layer with a mock server
  (429/Retry-After, size limits, hanging responses, partial requests); restore and watchlist view model as device
  tests; iPhone wiring and HTTP stub tests.
* Build: Gradle version catalog and Kotlin build scripts; CI shows compile errors and failed tests directly.
* Docs: «Add an exchange» for contributors, English API documentation of the exchange layer.

## 16.2.2 (build 76)

Two external reviews worked through (all «must» and «can» items), before the device test.

* Prices: a request that hits its time limit ends after 20 s instead of hanging 5 s longer, and partial requests
  (Binance Alpha, COIN-M list, Kraken fallback) may fail again without losing the rest; a failed COIN-M request
  keeps the previous pair list; a maintenance answer no longer marks a pair as «not traded» for 6 h; the rate-limit
  check only reads error fields; pairs with an alarm are fetched first in the short Doze window; an alarm does not
  fire on a price whose exchange time is more than 2 h old; the pair sheet warns about a wide bid/ask spread.
* USDT depeg: Kraken, Coinbase (new) and Binance USDC/USDT; a crash below 0.50 counts when two sources agree (2 of
  3); Binance alone never alarms; the portfolio shows «USDT crash» at the market price instead of «assumed 1».
* DexScreener: pools under $10,000 liquidity are marked and cannot alarm.
* Portfolio: the Binance price is only used when the coin is really the same token; Kraken CSV shows repeated txids
  with a different amount and staking entries without a counter-entry; bridged stablecoins are marked «≈»; xpub
  finds new addresses up to the full gap of 20 right away; fee rows and lending interest are stored as their own
  fields; the tax year follows the template's time zone; Austria: 440 € limit for old holdings; totals are summed
  exactly.
* Safety of your data: old database keys are never overwritten and are tried again automatically; the recovery
  notice stays until confirmed and offers «Restore backup»; the Android system-backup copy of the portfolio only
  exists while the backup runs; iPhone never writes the portfolio unencrypted; an interrupted restore is rolled back
  on the next start; written backups are read back and checked; the portfolio lock no longer opens silently without
  a screen lock and is immune to clock changes.
* Notifications: deleting pairs while price notifications are posted no longer freezes the app; a notification is
  withdrawn when its alarm was changed in the meantime; «Check on time» re-arms after any error; spoken prices use
  the decimal separator of the speech language.
* iPhone: background work sets its expiration handler first; widgets wait at most 8 s for the network and reload
  only what changed (daily limit respected); alarm sounds also play when a widget fires the alarm; re-tapping a tab
  goes back to its start; faster reading of large answers; activity logging without personal data.
* Licence: the original BitcoinChecker notices (mobnetic, Aneonex Software) are back in LICENSE and on the licence
  pages; alarm sounds are self-made (script in the repo, CC0).
* Privacy page: controller named with full name, legal bases and third-country transfers added, GitHub connection
  data described correctly, Coinbase as USDT source; age 18+ in both stores.
* Repository: data mirror uses only documented Binance endpoints; weekly exchange check and macro calendar report
  problems as issues instead of red runs; Gradle security updates via the dependency graph; Compose BOM 2026.09.00,
  SQLCipher 4.19.1.

## 16.2.2 (build 75)

In-depth check of all areas before the device test.

* Android: widget charts are loaded outside the refresh lock and the background time budget (no aborted runs in
  Doze); price notification updates are paced so Android doesn't drop them, and an alarm only counts as shown when
  the new notification is really there; the last live prices are saved when the watchlist closes during a refresh;
  clock jumps no longer silence gas, depeg and activity alerts; a restore clears notifications of old pairs and
  alarms; release builds keep readable error names; an old database key file is removed once the new key works.
* iPhone: portfolio and watched addresses are re-read after the first unlock; CSV preview parses off the main
  thread; morning notifications can no longer be scheduled after being switched off; widgets never refresh twice at
  once; refresh time only counts when something was saved; reset can't bring back portfolio alarms; duplicate ids
  in an old watchlist file no longer crash; badge and check log are written safely between app and widget.
* Money: CSV import and the entry form convert USD and fiat with the day's USDT/USD close (same as the export, no
  phantom gains); Binance «ETH 2.0 Staking Rewards» are rewards; Kraken staking assets (DOT28.S, ETH2.S …) and
  EUR.HOLD/USD.HOLD are recognised; derived prices keep 34 significant digits (tiny-price coins); portfolio alarms in
  another currency use the USDT/USD price.
* First run: the notification question comes after the «Set alert» hint; the starter shows an offline state with
  «Try again»; the bell is hidden on an empty watchlist; the header bell says what it does.
* Alarm editor: one direction control, a pair header with the current price on Android, «Notify» label for once /
  every time; «rises by 5 %» says from which price.
* Pair sheet: «Try again» for every error; screen readers announce the price notification only when it's off.
* Texts: clearer notification wording, English «alert» everywhere, correct quotation marks in 15 languages.
* Widgets: the refresh duration stays visible – the title gets smaller first.
* Build tooling: Kotlin 2.4.21, Android Gradle Plugin 9.4.1, Navigation 2.10.2; Dependabot only for security updates.
* Privacy page names every file you pick yourself (backup, CSV import, alarm sound).

## 16.2.2 (build 74)

Fixes and polish.

* Phemex Spot: a symbol without a price scale in the product list is an error, never a guessed 10^8.
* Live prices: a stream tick is no longer stored as the exchange's trade time – «unchanged since» works again and a
  quiet book no longer looks freshly traded.
* % change from another exchange's candles only when the coin name matches (otherwise «—»); a foreign source is no
  longer shown as the own exchange after a live update.
* Rewards without a price (e.g. Kraken staking) get the day's close in the tax export (marked as estimated).
* CSV: trades with two fees in different assets are kept (second fee as its own row); a Binance buy and sell of the
  same coin in one second becomes two trades; truly ambiguous groups are listed as skipped with a clear reason.
* Watched addresses: a chain balance within ±5 % of the booked balance is only displayed (no double count).
* Tax lines no longer read like a ruling («usually not taxable – check», UK allowance applies to all disposals,
  US wallet allocation is an app rule, AT transition period refers to the note).
* Alarm hint on both systems: often later than 15 minutes, on iPhone sometimes only at the next opening.
* Watchlist jump button has a 48 dp touch target.
* Room schema 16 committed.
* USDT is valued at its USDT/USD market price (Kraken) instead of a fixed 1 – portfolio card, history, widget,
  portfolio alarms, «≈» conversion and tax export; past days use the USDT/USD daily close; without a price it falls
  back to 1 and says «USDT = 1 assumed». USD as display currency converts too.
* Alarm editor: price above/below and rises/falls by % up front, the other eight conditions behind «More conditions».
* Settings start page: own «Portfolio» switch in «More» (the page under Advanced stays).
* Price colours follow the exchanges: «Fresh» = Binance (#0ECB81 / #F6465D), «Traditional» = TradingView
  (#089981 / #F23645), «Colour vision» = blue/orange – each as close to the original as text contrast (AA, high
  contrast 7:1) allows, independent of the theme colour; «Calm» dark falling is now salmon red instead of pink.
  Checked by android/tools/check_price_colors.py.
* Build tooling: GitHub actions setup-gradle / dependency-submission 6.4, setup-java 6.1; KSP 2.3.12, uiautomator 2.4.

## 16.2.2 (build 73)

Fixes and polish.

* Android: after «Edit pair» or a restore, a price fetched or streamed for the previous pair can no longer be written
  into the row (the write is bound to the pair; the live buffer forgets edited rows; edit and restore run under the
  refresh lock) – no false alarm from a stale reference. iOS drops such results the same way.
* iPhone: settings and the app lock are only trusted once protected data is readable – the lock counts as on and
  nothing is saved before the first unlock after a reboot; reloaded when the device is unlocked.
* iPhone: refresh, alarm delivery, backup export/restore and CSV loading finish as background tasks when the app is
  swiped away; an alarm being delivered is marked first (no repeat after a crash); the alarm lease can no longer be
  taken over twice; the backoff table is merged between app and widget; large downloads are streamed with a limit.
* Alarm notifications with a threshold in another currency show «now» in that currency too.
* Funding/open-interest alarms without data count as «not checked» instead of «checked».
* Android: demand tracking restarts after a read error; a backward clock jump no longer extends exchange pauses;
  portfolio alarms re-arm no sooner than 60 s; volume spikes use a clock-hour grid; one failing alarm no longer
  skips the others of the pair.
* Android: a leftover plaintext system-backup copy (backup killed midway) is deleted at the next start.
* Android 8–12: with the app lock on, the whole window is protected from screenshots and the recents preview.
* Weak backup passwords such as «aaaaaaaaaaa1» are refused (same rules on both platforms).
* Bitfinex: OPX is shown and found as OP (Optimism).
* Licenses: SQLCipher (BSD notice), OpenSSL, SQLite and the missing Apache libraries; «Rates By Exchange Rate API».
* Privacy page: postal address of the controller (c/o); DEX token search terms go to DexScreener; SECURITY.md: watched addresses go to public nodes.
* CoinGecko: the extra rank pages are only fetched on unmetered networks.
* Add pair: no «no pair for this coin» while pair lists are still loading or missing; one sentence explains «pair».
* Stale / unreachable rows in amber with a warning icon (no longer like a falling price); row icons scale with text.
* Widgets: text at least 12 sp/pt, background opacity at least 30 %.
* Selected tab easier to see; Retry button grows with its text; «+» shows its label while the list is short.
* %-basis moved to Settings › Watchlist; one name for unusual activity; bid/ask and gas units explained; English
  «Update frequency», «Market alerts»; empty groups offer «Edit group».
* iPhone: forward chevrons mirror in right-to-left languages; price notifications don't pop up as banners while the
  app is open; widget may send time-sensitive alarms.
* Upload script stops when the private review data is missing, scans the local history before a first push and
  explains the order with the settings script.

## 16.2.2 (build 72)

Fixes and polish.

* MEXC Futures: the 24 h change is computed from hourly candles; the exchange's `riseFallRate` only counts since
  00:00 UTC+8 (it showed +0.35 % while every other exchange showed −0.72 %). %-alarms were not affected.
* «What is moving with it?»: the market and Bitcoin comparison only runs for pairs quoted in USD or a USD stablecoin;
  ETH/BTC or SOL/EUR no longer get «strikingly independent» from a comparison with BTC/USDT.
* Android: a background run in which every exchange failed no longer asks WorkManager for a retry – the exponential
  backoff could delay the next check and alarms by up to 5 hours after an outage.
* iOS: an unreadable watched-addresses file no longer blocks every backup restore (it is set aside, the undo puts it
  back unchanged).
* iOS: before each alarm notification the alarm is re-read from disk and the delivery lease refreshed – no second
  notification when the app is suspended mid-delivery and the background task takes over.
* Spoken prices below 1 keep 4 significant digits (PEPE was spoken as «0.000007» or «0»).
* Alarm thresholds are shown exactly as stored (1.0005 instead of «1», 70,000 instead of 70,000.00).
* UK: deposits from own wallets with an earlier purchase date take part in the same-day and 30-day rules on their
  purchase date (HMRC); the rest enters the pool on the booking day.
* Austria: sales or swaps of new holdings between 1.3.2021 and 28.2.2022 get a note – old rules with option for
  27.5 percent, to be clarified with a tax adviser.
* First pairs or first alarm: a short explanation before the system asks for notification permission («Continue» /
  «Later»; «Later» asks once more at the first alarm).
* Watchlist header: the alarm button always shows the bell with waves (the plain bell in a row means price
  notification on).
* Feature graphic redrawn: BTC/USDT priced in USDT, change as calm-green text, «No account · Price from the exchange».

## 16.2.2 (build 71)

Fixes and polish.

* «N disabled» pill above the watchlist: tap area 48 dp / 44 pt high; the status line does not grow.
* Pair editor: the alarm warning icon is amber (warning colour) on both platforms instead of olive / text colour.
* iOS: futures pairs show «/USDT PERP» small and grey after the base like Android, no accent capsule.
* Docs: Portfolio switch path, Android README version line and market count corrected.

## 16.2.2 (build 70)

Fixes and polish; the last failing iOS UI test passes.

* Right-to-left languages: the drop-down arrows in the alarm editor (above/below) and the group picker of «Add pair»
  point down instead of up (own arrow that is not mirrored).
* «What is moving with it?» chip: tap area also 48 dp / 44 pt wide, so the bolt-only chip is no longer missed.
* Search field and group-name field: real control border (`outline`, at least 3:1), checked in the contrast test.
* iOS start page: «Search coin» has a real outline like Android instead of a faint accent line.
* Group selector in «Add pair»: tap area at least 48 dp / 44 pt high.
* iOS tab bar: the selected tab has the same 64 × 32 pill as Android.
* iOS: the widget's daily price file is also in the backup-exclusion repair at start.
* Docs: backup passwords need at least 12 characters everywhere (SECURITY, README, test plan).
* iOS UI test: the Backup row is scrolled clear of the tab bar before it is tapped – the CI screen recording showed
  the tap landing on the Portfolio tab behind it.

## 16.2.2 (build 69)

Fixes and polish; first green CI runs.

* Kraken: pairs that are not "online" (e.g. cancel_only) are no longer offered; watched ones show «no longer traded»
  instead of a frozen price.
* DexScreener: two pools with the same symbol on one chain can both be watched (main database v16: the unique index
  includes the pool id).
* Settings › Backup says when encryption is not active yet, and that a backup file is needed before switching
  devices.
* iOS: the storage key is device-only (`…ThisDeviceOnly`) and no longer part of device backups; existing installs
  are switched without changing the key.
* Screen readers announce whether a pair's price notification is on.
* Fear & Greed card shows number, word and one dot instead of a colour gradient.
* New backup passwords need at least 12 characters.
* "LIVE" badge and the «What is moving with it?» chip translated naturally.
* Toasts never block taps (found by the iOS UI tests).
* CI: Android unit tests, lint and builds green; iOS build and unit tests green; Android device tests (32, incl. the
  new UI flows and the encryption migration) green.

## 16.2.2 (build 68)

* iOS UI test for backup: reports what is on screen when the backup page does not open (diagnosis for CI).

## 16.2.2 (build 67)

* GitHub: Android (unit tests, lint, debug and release build) and iOS (build and unit tests) are green. Three of the
  four iOS UI tests passed on the first run; the backup test now scrolls the settings row into view before tapping.

## 16.2.2 (build 66)

* iOS builds on GitHub for the first time since the rewrite. One unit test compared watched-address networks in the
  fixture's free order with the saved (sorted) order; it now compares the sorted form, like the app stores it.

## 16.2.2 (build 65)

* iOS: the alarm pair picker (bell without alarms) used an interpolated button title that the compiler could not
  type-check in time; it now builds the title as a plain string first.
* GitHub: «Sponsor» button links to SUPPORT.md (the apps still contain no donation feature).

## 16.2.2 (build 64)

* iOS: first GitHub build found a compile error in the Kraken CSV reader – a local constant was named `internal`, a
  reserved word in Swift. Renamed (`stakingIndices`); no behaviour change.

## 16.2.2 (build 63)

* UI tests: the fixed test passphrase and the backup field tags are named so the upload script's secret scan no
  longer mistakes them for real passwords (no behaviour change).
* Device check done: encryption of existing databases verified on Android and iPhone with real data (update from
  build 58, restart, widgets, background alarm).

## 16.2.2 (build 62)

* Android: the one-time encryption of existing databases failed on the device with SQLITE_CANTOPEN – SQLCipher's
  ATTACH inherits the main connection's open flags, so the new encrypted copy could not be created. The export now
  opens with "create if necessary" (same fix for the system-backup copy). Thanks to the safety net nothing was lost:
  the app kept working with the old file and retries on the next start.

## 16.2.2 (build 61)

* Android: all four tabs are visible again – the long-press tooltip (build 56) had swallowed the equal-width rule, so
  only the first tab was shown (found on a device).
* Database schema files carry the identity hashes Room generated on a real build (build 58 device database); columns
  and indices checked against that database.
* Upload script and .gitignore never publish database files or the real-device test databases (`realdb/`).
* Three compiler warnings fixed (unneeded `!!` and `?.`; the deprecated tooltip position call is kept on purpose).

## 16.2.2 (build 60)

Fixes and polish.

* Arabic, Hebrew and Persian: pair, name and the activity chip in watchlist rows now sit on the correct side.
* Android: the portfolio widget's values are stored encrypted (AES-256-GCM, own Keystore key); older plaintext
  snapshots are converted once. Without the key the widget shows no values instead of storing them unencrypted.
* iOS: portfolio widget values are never stored unencrypted in the shared app settings any more.
* Android 8/8.1: the watchlist and alarms are no longer part of the system backup, because these versions cannot
  require end-to-end encryption.
* Privacy policy, SECURITY.md and store documents state exactly what is encrypted and what is not.
* Stale watchlist mini-charts are drawn in neutral grey without a fill instead of faded price colours.
* A small «*» marks a 24 h change taken from another exchange's candles; screen readers say where it comes from.
* The watchlist bell leads straight to creating an alarm when there are none yet.
* The empty watchlist and the first-run hint say that tapping a coin shows what is moving along with it.

## 16.2.2 (build 59)

* Watchlist, alarms, portfolio and watched addresses are now encrypted on the device. Android: both databases with
  SQLCipher (AES-256), the key wrapped by the Android Keystore; iOS: the user-data files with AES-256-GCM, the key
  in the keychain shared with the widgets. Existing data is converted once on the first start: the encrypted copy
  is verified table by table (Android) or by decoding it again (iOS) before the plaintext file is replaced; on any
  failure the app keeps the old file and tries again on the next start.
* If the key is ever lost (e.g. a restore onto another device without it), nothing is deleted: the unreadable files
  are set aside and the app explains how to restore a backup file.
* Android system backup keeps working: for the duration of a backup a verified plain copy of the main database is
  handed to the system and deleted afterwards; after a restore it is encrypted again on the next start.

## 16.2.2 (build 58)

Code quality, fixes and polish (Android and iOS alike).

**Portfolio and tax**
* Tax export: the FIFO cost basis is kept in dated parts and each part is converted at the exchange rate of its own
  date – a transfer fee counts at the transfer date's rate, not the purchase date's (e.g. 5,000 USDT at USDCHF 1.00
  plus a 100 USDT fee at 0.85 = 5,085 CHF).
* A transfer fee share on coins without a purchase price is shown as «Fee not assigned – cost basis unknown» on the
  coin card, in the hints and as an info row in the tax CSV instead of being dropped.
* The «USDT = 1 assumed» note appears whenever you hold USDT, independent of the depeg alarm; a fresh deviation is
  shown as before.
* Tax export notes: Austria – staking and airdrops have acquisition cost 0 (§ 27b Abs. 2 EStG, BMF); all
  countries – the tax CSV can differ from «realized» (country tax rules).
* Empty portfolio in plain words: «Add your first purchase», with file import and wallet address as other options.
* The spouse/civil-partner gift flag and cross-exchange transfer links (both import keys and the undo state) are
  stored in their own fields (portfolio database v4); existing data and old backups are converted automatically,
  and long import keys no longer prevent linking.

**Texts and design**
* «Why?» is now «What is moving with it?» (short label on chips and notification buttons), also in the welcome
  screen – the sheet never claimed a cause.
* About shows when the exchange connections were last checked against real responses.
* iOS: the first-alert hint starts with the platform limit.
* Light mode: dividers are easier to see (2.2:1 instead of 1.6:1).

**Code and tests**
* Long screens, view models, widget renderers, AppData and backup split into focused files (rule: about 450 lines,
  same file names on Android and iOS); no behaviour change.
* Automated UI tests of the main flows on Android and iOS – add a coin from the search, price alert, CSV import with
  undo, backup with password – with shared test identifiers and a shared fixture, without network; iOS runs them in
  CI, Android in the «Android device tests» workflow.

## 16.2.2 (build 57)

Follow-ups to the open points of build 56.

* Tax (UK, US, CA, AU, JP): a fee in USDT or cash on a transfer between your own wallets is no longer added to the
  cost basis; like the withdrawal fee it is listed as information only (own summary line «Transfer fees (own
  wallets)», UK: HMRC CRYPTO22150). DE, CH and Neutral keep it in the cost basis (no administrative guidance),
  AT and FR never counted it.
* CSV import: undoing the earlier of two linked imports keeps the deposit or withdrawal from the later file as its
  own row in that later import; undoing the later import afterwards removes it. Links made with build 56 keep the
  old behaviour.
* Watchlist: the mini-chart is narrower (48 instead of 56) on narrow screens, leaving more room for the pair; the
  exchange/time line and the converted price are easier to read (14 instead of 12). Lines with a fixed number of
  rows stay at 12 so nothing is cut off at the largest text size.

## 16.2.2 (build 56)

Work on the remaining weak areas (Android and iOS alike unless noted).

**Add a coin, watchlist and market tab**
* Add pair: the search finds coins by name or symbol («Bitcoin», «btc») and suggests the best pair – your
  region's exchange, your display currency, otherwise USDT/USD – added with one tap. The step-by-step selection is
  now «Advanced / by exchange».
* The search uses only data already on the device; missing exchange pair lists are loaded only when you tap
  «Load lists» (the full public lists, the same for everyone).
* Empty watchlist: no portfolio prompt any more; «Search for another coin» instead.
* Market tab: three short sentences in everyday language at the top (direction of the market today, mood, what
  stands out), each with an icon; the detailed cards follow unchanged.

**CSV import**
* Recognises the exports of Binance (transaction and trade history), Coinbase (transaction report) and Bitpanda
  (transaction history).
* A withdrawal and the matching deposit on another exchange – in the same file or an earlier import – are
  proposed as one transfer; purchase price and holding period carry over, the difference is booked as network fee.
  Unambiguous pairs are preselected, ambiguous ones only shown.
* Undoing an import also reverses the transfers it linked to earlier imports.
* Importing both Binance exports never counts a trade twice: partial fills are merged, near-identical trades are
  held for review.

**Tax**
* UK, CA: gifts to a spouse or civil partner can be marked and count as no gain/no loss at your cost basis
  (TCGA 1992 s. 58, ITA 73(1)).
* UK, US, CA, AU, JP: network fees paid in crypto on transfers and withdrawals are a disposal of the fee coins at
  market value instead of a full loss or a cost-basis increase; withdrawal fees in USDT or cash are no longer a
  realised loss and are listed as information only (UK: HMRC CRYPTO22150). The export notes cite the sources.
  DE, AT, CH and FR unchanged.

**Design and accessibility**
* One card style across Market, Portfolio, Settings and Alerts (20/16 radius, no borders); the portfolio total card
  is highlighted only by its gradient.
* The «+» button looks the same on both platforms and in light and dark mode.
* Empty states for alerts and portfolio share one layout (icon, title, example, actions) and scroll at large text
  sizes.
* Data-source outages show a calm grey «Market data currently unavailable» row with Retry instead of a red error.
* Tab bars stop growing past about 1.3× text size; a long press shows the tab name (iOS Large Content Viewer,
  Android tooltip). Icon-only buttons on iOS also show the Large Content Viewer.
* Watchlist rows never truncate the coin symbol; the quote is dropped when space is tight.
* Clearer typography: larger section titles, aligned display and headline sizes on both platforms, no 11-pt
  metadata on the main screens (guarded by a test).
* iOS group chips use the same rounded shape as Android; Android screen titles use one weight.

**Privacy**
* App Store privacy details now declare «Other financial info – not linked to you – app functionality» for watched
  addresses, like the Play Store data safety section; no tracking.

**Tests and tools**
* Real exchange responses recorded on 16/17 October 2026 for all 41 exchanges (trimmed, 107 cases) are checked on
  both platforms.
* Fixture recorder: same User-Agent as the Android app, `--user-agent`, error hints (server, start of the
  response), certificate store from certifi when available.

## 16.2.2 (build 55)

Fixes and polish (Android and iOS alike unless noted).

**Alerts and widgets**
* Notifications and alerts show the app logo as status icon (no rising-chart symbol).
* iOS: widgets also check price alerts with the prices they just loaded (no double alert with the app), refresh
  about every 30 minutes with at most one quick retry; an alert (also a portfolio alert) is only marked as
  triggered once its notification has really arrived – the same show → verify → mark order as Android.
* One short sentence per platform says when alerts are checked (under every alert, in the first-alert dialog,
  once after saving); four older versions removed.
* Android: notifications (price notifications, alerts, market notes) show the app logo in the status bar instead
  of a rising price line.

**Tax (Austria)**
* Fees are no longer deducted for holdings acquired from 1 Mar 2021 (§ 27a(4) no. 2, § 20(2) EStG; BMF): cost
  without incidental costs, proceeds before fees; older holdings unchanged.
* Lending interest is income on receipt (27.5 %, § 27b(2) no. 1 EStG) as its own line; the special-rate line
  totals net gains plus interest.

**Exchanges**
* Kraken reads currency codes from its own pair names (`wsname`); the old asset table is gone (only XBT→BTC and
  XDG→DOGE remain).
* Gemini: pair-list paging is kept per sync (no mix-up when two syncs run at once). HTX Futures: quote volume
  only from `trade_turnover`, never from the contract count. Binance: smaller pair list (tradable pairs only).
* iOS: an unreadable Bybit pair-list page fails the sync instead of silently dropping pairs (like Android).
* Own types and names throughout: `PairQuery`, `MarketPair` (`base`, `quote`, `pairId` on both platforms),
  `Ticker.MISSING`; unused subunit code (mBTC, satoshi) and the placeholder pair of the unknown exchange removed;
  CHF shown as «CHF» instead of «₣», RUR removed.

**Market tab, texts, settings**
* Every market indicator has a plain line «high means …, low means …»; info sheets explain leverage, funding and
  the altcoin share.
* English texts read more naturally («avg.» instead of «Ø», «Export holdings on a date», «Tax worksheet» …).
* Settings: «Price updates» back under «General», «Widgets» under «Appearance»; search, privacy policy and review
  notes follow.
* Store short texts and subtitles name Bitcoin, crypto and price alerts.

**Code base and tools**
* Remains of the former live service removed (backups stay compatible with older versions both ways).
* Release builds really strip Timber debug logs.
* String resources grouped by area.
* Opt-in recording of real exchange responses (`android/tools/record_exchange_fixtures.py`, manual GitHub
  workflow) and a contract test that runs them when present.
* Privacy policy: watched addresses are also refreshed in the background (for portfolio alerts and the widget).

## 16.2.2 (build 54)

**Own code base (Android and iOS alike)**
* The whole exchange layer is refactored and extended from BitcoinChecker: base class `Market` with clear sections (single ticker, bulk, pair list,
  names) and new method names (`tickerAddress`, `fillTickerJson`, `readTicker`, `pairListAddress`, `fillPairList`,
  `readPairList`, `errorMessage` …); one shared `SimpleMarket` for «pair list URL + ticker URL» exchanges, shared
  bases per exchange family (`BinanceBase`, `BitgetBase`, `BybitBase`, `HtxBase`, `PhemexBase`, `UpbitStyleMarket`,
  `WooBase`), one helper per pattern (`copyRow`, `bulkRowIfActive`, `noErrorText`), the same file layout on both
  platforms.
* Pair explorer, pair-list loading, local pair storage, error types, HTTP log, combo box, log box, error and loading
  screens refactored and extended from BitcoinChecker. The old currency tables are replaced by a small `CurrencyText`.
* Behaviour unchanged: all unit, contract and parity tests pass; a differential run over every fixture and about
  28,000 field variations gave identical results. New tests for the shared building blocks.
* LICENSE: MIT, one copyright line (r1adBE). The licences page in the app shows the app's own licence.
* Docs: «Adding an exchange» (DEVELOPMENT.md) follows the new API.

## 16.2.2 (build 53)

**Settings (Android and iOS alike)**
* Shorter main page: General (Language, Currency, Watchlist, Alerts) · Appearance (Light/Dark, Accent colour,
  Price colours) · More (Backup, Advanced, About the app); «Reset app» as its own red card at the bottom.
* New page «Advanced»: Token & TradFi (now its own page, previously on «Watchlist»), Portfolio, Market alerts,
  Spoken output, Price updates, % change, Coin logos, Widgets and – once unlocked – Developer.
* Privacy policy, Request an exchange, Source code and Licences moved to «About the app». Search shows the new
  paths; hints that name a path («Settings › Advanced › Portfolio») and the privacy policy follow.

## 16.2.2 (build 52)

Fixes and polish (Android and iOS alike unless noted).

**Alerts**
* Android 15+: spoken alerts also speak without audio focus (alarm stream, no ducking); silent during calls, Do Not
  Disturb or silent mode; stale announcements (> 60 s) are dropped.
* Exact checks are re-armed after «Force stop»; portfolio and depeg alerts are saved only after the notification is
  shown; extra data (rates, high/low, volume, funding) is fetched together with prices (Doze).
* A level alert re-arms at the earliest after one minute (oscillating prices); a new percent alert never uses a stale
  price as reference.
* New alert: a pair without alerts opens the editor directly, the field starts empty with the price as placeholder,
  «Alert active · Test» after saving. Android tab bar shows the selected tab.
* iOS: the next background check is planned from the last alert check; «Last checked …» is shown; the widget no
  longer loads huge ticker lists; if Binance is geo-blocked the starter uses Binance.US (Android too); 44 pt star.

**Prices**
* Gemini maps responses by symbol; inactive markets skipped on KuCoin, Gate.io, Bybit, OKX, Kraken and Phemex; Phemex
  reloads price scales; long Upbit lists are split; «≈ today» where local midnight is not on a full hour; nanosecond
  timestamps; iOS bulk tickers time out after 20 s.

**Portfolio and tax**
* US 2024 → 2025: remaining lots of universal FIFO go to the wallets (Rev. Proc. 2024-28); warning when changing the
  method; rules date in country exports and a one-time beta confirmation.
* Kraken: duplicate txids dropped, spot↔staking moves not counted twice; AT: lending interest at market value;
  generic CSV: fiat rows skipped, «Date(UTC)» columns (UTC), same FX day as the export.

**Look, texts, store**
* Falling colour for Orange + «Calm»: clear dark red / light rosé-red; base «Calm» falling a bit stronger in dark mode.
* Five price colour styles: «Calm», «Fresh», «Traditional», «Colour vision» (now a stronger blue/orange) and the new
  «Calm · Colour vision» (the previous, softer blue/orange). «Fresh», «Traditional» and «Colour vision» keep their
  standard exchange colours with every accent colour; only the two «Calm» styles switch to a replacement tone when
  one is too close to the accent.
* Sheet title «What is moving with it?»; English says «alert»; store name «Crypto Checker: Price Alerts», subtitle
  «Price alerts & widgets», new first sentence; honest iOS background text; plain-language sentence before Funding,
  Open Interest and leverage; Android German «Kursbenachrichtigung».

**Robustness and tests**
* Chain test price → alert → notification on both platforms; weekly live check of all exchanges on GitHub (opens an
  issue on failure); response size limits; cancelled requests no longer swallowed; stored errors as short codes.
* Publish script blocks only `android/app/release`; market-data workflow writes only in its job; privacy reasoning
  for Play and App Store aligned.

## 16.2.2 (build 51)

* Gradle wrapper 9.8.1 with checksum.

## 16.2.2 (build 50)

* iOS test build fix (explicit `self` in closures).

## 16.2.2 (build 49)

* Android build fix: the pair-alarm notification now returns whether it was shown (build 48 did not compile).

## 16.2.2 (build 48)

Fixes and polish (Android and iOS alike unless noted).

**Alarms**
* Android: at most 20 price notifications are shown on their own, the rest go into one summary («+N more prices»);
  10 places always stay free for alarms (Android shows about 50 notifications per app and dropped the rest silently).
  An alarm is saved as triggered only after Android really shows it – otherwise it stays armed and retries.
* The starter selection asks for notification permission (Android and iOS).
* Spoken alarms stay silent during calls, with Do Not Disturb (Android) or without audio focus.
* First alarm: one dialog «All set» with «Check on time» and, if needed, the battery hint instead of up to four.
* Live ticks of single pairs no longer count as a full check (no skipped scheduled run); exact checks in Doze give the
  portfolio snapshot its own time limit; alarm and market notifications are private on the lock screen with app lock.

**Robustness**
* iOS: the background exchange-rate fetch for portfolio alarms has a time limit (Android too).
* A tampered backup with huge values can no longer crash the app (values clamped on both platforms, overflow-safe
  time maths on iOS; Android no longer wraps huge numbers silently). Retry-After is capped at one day.

**Prices**
* «24h» from hourly candles uses a real 24-hour window (quiet pairs on Upbit and others showed several days).
* Rate limits signalled only in the response text (Kraken, Bybit, OKX) are recognised; no extra full request.
* Kraken: delisted pairs are recognised as «no longer traded».

**Portfolio and tax**
* CSV: a fee in the received coin (e.g. USDT) reduces the received amount (Kraken balance now matches);
  «ETH/BTC» rows become swaps.
* CA: a deposit of own coins no longer counts as a repurchase for the superficial-loss rule.

**Texts and store**
* Starter title «Start with these coins», correct plural after adding, honest alarm-timing and release-note texts,
  consistent terms, less jargon (German), Pulse card button «Details», widget percentages 12 sp.
* Store descriptions start «Bitcoin & co. at a glance …», data-flow paragraph moved, keyword «ticker»; screenshot
  kit with five scenes (incl. widget) in the factory colours «Calm».
* Privacy policy lists «Check on time» (SCHEDULE_EXACT_ALARM) and the GitHub download hosts; the publish script also
  removes the `data` release tag when replacing the history.

## 16.2.2 (build 47)

Fixes and polish (Android and iOS alike unless noted). From now on the build number goes up by one
with every delivered state.

**Alarms and background**
* iOS: one background refresh task instead of two (iOS allows only one pending refresh request – the second one
  could silently block price alarms while a Fear & Greed or indicator alarm was on); the zone check runs inside it,
  scheduling errors appear in «Recent checks». A widget refresh no longer counts as an alarm check.
* Android: spoken alarms use the alarm audio stream and duck other audio; the last announcement is spoken in full;
  the speech engine is released after a minute idle. iOS releases the audio session after speaking.
* Android: the periodic background job only runs when something needs it (alarms, price notifications,
  widgets …); exact checks end all network calls within 9 s in Doze; the portfolio backup copy only runs when on.
* Tapping a price notification or a widget row opens that pair (Android now too, without rebuilding the screen).
* Android: price notifications and alarms are grouped; price notifications have their own icon and stay private
  on the lock screen with the app lock on.
* Android: the launcher icon only changes with the accent colour, no longer with light/dark mode (some launchers
  removed the home-screen shortcut).
* iOS: the Live Activity hint says that iOS ends it after 8 hours; the tab bar grows with large text.

**Prices and exchanges**
* After the first «too many requests» answer the remaining single requests to that exchange are not sent.
* A failing Binance Alpha request no longer switches off delisting detection for Binance spot pairs.
* Inactive markets without a price are skipped, a renamed or unreadable price field counts as a format error –
  now on all exchanges with bulk requests.
* Crypto.com quote volume only for USD/USDT/USDC pairs; Woo 24 h values cover a real 24 h window; inverse
  perpetuals (Binance COIN-M, OKX, Deribit) are marked as such; Bithumb learns delisted pairs.
* «Outdated» is measured from the fetch time, the exchange's own time is only shown.

**Portfolio and tax**
* DE: withdrawal fees no longer reduce the § 23 sum for the exemption limit (own line); AT the same for new holdings.
* New transaction types «Gift» and «Paid with it»: a payment is a disposal at market value everywhere, a gift
  only in UK, CA and AU (elsewhere it leaves like a withdrawal).
* Swaps without a recorded value are valued at the day's close and marked «value estimated».
* UK: the coin network fee of a withdrawal is its own disposal; FR: the total portfolio value includes watched
  addresses; selling at price 0 by hand is possible.
* Country tax templates are marked **Beta**.
* CSV import: «0.125» counts as a decimal hint, ambiguous numbers are asked in the preview; at the same time
  inflows come before outflows; Windows-1252 files are read; the preview lists the recognized columns.
* xpub: when no legacy address has history, SegWit (P2SH and native) addresses are searched too.

**Look and texts**
* Price colours keep a clear distance (ΔE2000 ≥ 24) to every accent colour; «Calm» separates rising and falling
  by lightness too (colour vision deficiency).
* Market tab: each tab reveals its cards on its own.
* «Why is this moving?» is now «What is moving with it?» (the sheet never names a cause); store texts open with
  the benefit.
* Android German: «Benachrichtigung» instead of «Mitteilung» (iOS keeps «Mitteilung»).

**Repository**
* Publishing script stops on a failed commit, creates a public repository only on a definite «not found»,
  checks and shortens the commit message, keeps the internal store guides local; foreign `.gitignore` replaced;
  workflows check out without persisted credentials; Gradle wrapper checksum.

## 16.2.2 (build 46)

### App

**Portfolio as a real ledger (build 46)**
* New transaction types: Transfer (between exchanges or wallets – no gain, the cost basis moves with the coins,
  optional network fee in the coin), Reward (staking/airdrop – cost basis = market value when received, the
  checked daily close is prefilled for past dates), Deposit and Withdrawal (holdings without a trade; a
  withdrawal removes holdings at average cost without gain).
* Fee in another coin (e.g. BNB): the fee coin's holdings go down and the fee value is added to the cost basis
  (or taken from the proceeds); without a fee price the figure is marked incomplete.
* After a transfer the coin is valued at the destination exchange.
* Watched addresses (read-only, no seed): public Bitcoin and Ethereum addresses count as holdings without
  purchase price; balances from mempool.space/blockstream.info and publicnode.com/llamarpc.com, at most every
  10 minutes. Seed phrases and private keys are refused and never stored.
* CSV import of Relai and Swissquote exports with preview, duplicate detection and daily FX conversion.
* Cut-off CSV with exact amounts and values (rounding only in the output).
* Portfolio widgets show the held quantity in front of the symbol («1.3 BTC»).
* Value history with a «Value | Gain/loss» switch: the gain curve is value minus net money put in, so buys
  and sells never look like gains; the header shows value and gain for the range.
* Coin rows in the app and the widgets show your own gain/loss on the position («24h +1,250 CHF · +1.5 %»)
  instead of the token's market change; the portfolio list widget has a fixed «Total» row at the bottom.
* Backups carry the new types, addresses and import keys; older backups still restore.
* FIFO is now the default cost method (average cost selectable in the portfolio settings); realized gains,
  cost basis and «Since purchase» follow the chosen method.
* Tax export per year: realized gains per lot with holding period and «> 1 year», income from rewards, and
  holdings on 31 December – exact, converted with the daily rate of each date. Not tax advice.
* Deposits can carry the original purchase date (holding period). Withdrawals and deposits without price no
  longer invent gains or losses; watched addresses count from their first observed balance and no longer
  void the purchased coins' gain.
* Value alarms never fire on a missing coin price.
* Swap (coin for coin) as one transaction, also from CSV; open purchase lots per coin; undo a whole CSV import;
  optional FIFO per wallet/exchange with a hint when a wallet lacks coins; prices can be entered in CHF/EUR.
* Working paper for taxes with an annual summary for Germany (orientation only) and a link to the official
  ESTV price list in CHF year-end exports.
* Watched addresses: xpub/ypub/zpub, Ethereum tokens and the EVM chains BNB Smart Chain, Polygon, Arbitrum,
  Base, OP Mainnet and Avalanche, plus Solana, TRON, XRP, Litecoin, TON and Cardano; «Show only» and a warning
  when the same coins are also entered as transactions.
* One day boundary for «7 D» and the history; one booking without price no longer drops the whole coin; app
  and widget show the same figure.
* Fresher-looking stale prices fixed for Bybit, HTX and others; clearer «price unavailable right now».
* Warmer light mode, shorter tab names, settings with «Advanced», no blocking at app start.
* One holdings figure per cost method everywhere (card, periods, history, widget, exports).
* Names and Binance Alpha flags appear right away (lists first, a bundled starter list, a mirror of the
  Binance lists as fallback); logos load in parallel, with CC0 icons for major coins built in.
* Working paper for taxes for any country: neutral facts plus templates for Germany, Austria, Switzerland,
  the UK (pooling, 30-day rule), USA, Canada (superficial loss), Australia, France and Japan – orientation only.
* Calmer portfolio card («Details», «Notes»), exchange shown in coin rows, «Did you mean …?» for wallet names.
* Working paper: France formula handles withdrawals, Austria per wallet, orientation lines kept apart.
* CSV import: «Unstaking» is a transfer, not a reward; time zone from the file or chosen in the preview.
* Partial totals are marked at the number; portfolio alarms only with fresh prices; wrapped tokens kept apart.
* Clearer terms («price notification»), larger chart switches, backup without password asks first.
* Binance Alpha tokens: with «Incl. Alpha tokens» on they appear as α pairs under Binance (price, chart, alarms)
  and are marked α on other exchanges.
* Fees that eat the whole amount can no longer create phantom gains; withdrawal fees count the same everywhere.
* Backups from a newer app version are refused instead of half imported; weak passwords blocked more reliably.
* Large text wraps instead of cutting rows; «Updates» back under «General».
* CSV import reads Kraken ledger exports; a swap with fees on both sides keeps both (same unit) or is skipped
  with a reason, never silently dropping one.
* Swiss year-end line says plainly that wealth tax is not calculated and the CSV uses exchange price × USD/CHF.
* Cost method and tax template appear only once the portfolio is on; Android widgets use the app's number font.
* Portfolio card warns when USDT is off its peg («USDT = 1 assumed – market at …»).
* iOS sends its real app version as User-Agent; Hyperliquid % is labelled «Mark»;
  high/low alarms and WOO spot no longer use incomplete or foreign data; Gemini
  resolves ambiguous symbols via its details API.
* The iOS alarm hint says that widgets only load prices; the Swiss export names «wealth tax not calculated»
  at the top; the privacy policy states that the database has no extra encryption.
* Faster, non-blocking start and widget list on Android.
* Portfolio widgets follow the currency setting even without a fresh exchange rate; the list widget hides the unused period choice.
  The exchange rate is now remembered as soon as the app or the background refresh fetches it (not only with a new
  snapshot), and the widget fetches it itself when none is remembered – «Conversion» no longer stays on USDT.
* Tax templates fixed: France/Austria crypto→stablecoin neutral (CSV books stablecoin trades as swaps), US per-wallet
  FIFO from 2025, Canada superficial loss in CAD, UK deposits without date, Austria swaps before March 2022.
* Exact checks no longer fail when one exchange is slow; iOS restore rolls back on error; iOS editor says speech
  works only with the app open; CSV import handles ambiguous dates, time zones and Kraken USDT.
* Store texts corrected in all languages; «Updates» is now «Price updates»; licence copyright r1adBE.
* Price notifications are on again for new pairs; Settings show «Pairs – x of y» with a list, «All on» and «All off»;
  every watchlist row shows a bell (on) or a crossed-out bell (off).
* USDT depeg alarm (off by default): notifies once when USDT leaves 1 USD by 0.5 % (adjustable), with a button
  to the exchange's USDC/USDT page; values keep using USDT = 1, now also shown on the portfolio card.
* Honest freshness: exchanges that only send a snapshot time (Bitstamp, Bitvavo, Crypto.com, Bitso, BtcTurk,
  ZebPay, Phemex, Gemini single ticker) no longer look «just traded»; HTX and Phemex futures mark stocks,
  indices and metals as TradFi; Bitfinex UDC is USDC; Alpha tokens sharing a symbol stay listed.
* Tax templates: CSV fee rows at the daily close, Canada one loss per rebuy, France unknown part above
  holdings, UK late deposits from their booking day, Germany net loss status, Austria reward line for
  information, Switzerland year-end value explained.
* Starter pairs: Binance …/USDT everywhere (including Switzerland and Liechtenstein), Binance.US …/USDT in the US;
  numbers follow app language plus device region (90'000.00);
  calmer default price colours; first screen says what the app does.
* «Check on time» (Android, off by default): checks prices and alarms about every 15 minutes even in Doze, with
  the system's «Alarms & reminders» permission; a battery hint when Android restricts the app; «Recent checks»
  shows when checks really ran (Android and iOS).
* Settings without «Advanced»: General, Appearance, Alarms & notifications, Data & backup, About.
* Buy sheet with four fields (the rest behind «Fee, exchange, note»); after the first transaction the app asks
  before turning on the Portfolio tab.
* «Since purchase» for the priced part when some coins lack a buy price; CSV withdrawals are chosen per row
  (own wallet or leaves the portfolio); address overlap switches to «Show only» only when amounts match.
* Tax templates: texts match the formulas (Austria rewards at cost 0 per wallet, France crypto fees, UK network
  fee in the coin, Switzerland lines, USDT note) with worked examples.
* Live clock moves only on a price change; Android sends its own User-Agent.
* Market tab: Fear & Greed on the first screen, the rest behind «More market data».
* One gain figure everywhere (fees on partial sales, curve follows the cost method); save errors shown.
* Coinbase keeps prices at zero volume; no fiat 24 h change without an exchange rate; foreign candles never on a name clash.
* Status line names the exchange prices; «Why?» is a labelled chip; alarm field explains errors; one orange, one radius set.
* Portfolio card and coin rows in your display currency (USDT below); cost method as a list; «+» to add a pair;
  better contrast in light mode, larger info buttons.
* Transaction sheet: date before price, «Discard changes?» guard, clearer terms (cost basis, amount,
  open/realized gain/loss), CSV import from the empty portfolio.
* Price core: dated Binance futures use their own contract symbol; Binance/MEXC freshness no longer taken from
  the 24 h window end; HTTP 418 on tickers waits like on candles.

### Store

* Store texts sharpened in all languages: your exchange's price and what moves at the same time first; the
  portfolio is an optional feature.

## 16.2.2 (build 45)

### App

**Money paths (build 45)**
* A transaction dated in the past no longer keeps today's price: the prefilled price is cleared for other
  dates, with the hint «Enter the price on the purchase day».
* Unchecked candle prices never count as money: such a coin shows «Price unchecked» and is left out of total,
  «Since purchase», «Today» and portfolio alarms; a watchlist pair on another exchange is used first.
* Portfolio history (7 D, 1 Y, value history, widget) comes from the daily candles of the coin's own
  exchange; scaled Binance only as a labelled fallback with a current reference, otherwise the coin is named
  as missing.
* Exact unrealized gain, 34-digit division on both platforms, «Avg. of … with purchase price», sell warning
  includes the coin fee, iOS import checks number limits.
* Volume alarms and «Why?» use the chart's candle rules; dated futures never borrow the perpetual's candles.
* «≈» shows the exchange-rate time when older than 3 hours; only USD/USDT count as 1; COIN-M contract size
  from Binance.
* Cut-off CSV uses the separator and decimal mark that Excel expects for the device region.

**Calmer screens (build 45)**
* Disabled rows and an outdated total use colour instead of transparency; rows show only the alarm bell and
  move the source note to the pair sheet; the total card keeps notes below it; the pair sheet opens at half
  height; larger sort arrows; empty alarm list with an example; iPhone widgets flat like Android.

**Backups (build 45)**
* Importing an unencrypted backup warns and can no longer switch the portfolio lock off; new backup
  passwords need 10 characters and simple ones are refused; invalid rows from a system backup are reported
  once.

## 16.2.2 (build 44)

### App

**Charts from the pair's own exchange (build 44)**
* Action-sheet chart, mini chart, single-coin widget, 24h-change fallback and «Why?» use candles from the
  pair's own exchange (Binance, Binance.US, Binance Futures, Kraken, Bitstamp, Bitvavo, Bybit, OKX, Coinbase,
  Bitfinex, Bitget, Gate.io, KuCoin, MEXC, WOO X, Poloniex, Deribit, One Trading, HTX, Gemini, Crypto.com,
  Upbit, Hyperliquid). Others fall back to Binance with the existing note and the same-token check; the next
  source is tried when one does not match; perpetuals prefer futures candles; TradFi never uses crypto spot
  candles; DexScreener stays on the chosen pool.

**Honest freshness (build 44)**
* «Live» only when every visible pair is on a live connection; live pairs are outdated after 2 minutes
  without a price, polled pairs after 3 × the background interval.
* Exchanges without a trade time no longer look fresh: «Price unchanged since HH:MM» in rows and widgets
  (Android database 14 → 15).
* New installs start with «Economical» (recommended); «Live while open» is «for pros».

**Portfolio and alarms (build 44)**
* «Today» uses the same rule as «7 D» (coins with an unpriced trade are left out and named, also in the
  widget and portfolio alarm); the 24 h history uses hourly prices for stablecoins other than USDT; the
  collapsed history shows the change without buys and sells.
* Gas and activity notices are only used up when they can be delivered; Android portfolio alarms can no
  longer be re-enabled by a trigger that races with switching them off; iPhone background runs check the
  portfolio right after prices and always plan the next run; with the portfolio lock the lock-screen text
  is neutral.
* Widget charts keep a minimum span (calm pairs don't look like a crash); large prices in the action sheet
  use the amount font.
* New installs: Portfolio tab off (Settings › General › Portfolio). «Add to portfolio» stays in the pair sheet;
  saving the first transaction turns the portfolio on. A placed portfolio widget says «Portfolio is off» and
  where to turn it on; a tap opens that setting.

**Texts**
* No internal words like «action sheet» for users; French consistently «vous»; Fear & Greed notices say
  «by this index»; update-mode hints say «not more often than every 15 min, often later».

## 16.2.2 (build 43)

### App

**Numbers you can hand in (build 43)**
* Cut-off CSV uses the same token check as the value history: no matching current price → empty price
  and «unchecked», total «incomplete»; no silent offline fallback to unchecked sources.
* Backup import rejects absurd numbers (more than 30 digits before or 40 after the point) instead of
  crashing (Android).
* Single-coin widget shows a chart only if the candles belong to the pair (same check as the action
  sheet).
* CoinGecko ranking and names are refreshed at least every 24 hours (API terms).

**Alarms on iPhone (build 43)**
* Background check with one deadline for every step: prices and price alarms are saved and reported
  first, extra data only while time remains; the task always completes.
* Foreground alarms are saved before the notification is posted (no double one-time alarm).
* Notifications count as deliverable only if banners, lock screen or notification centre are on.

**Robustness (build 43)**
* Alpha tokens load again quickly: the Binance Alpha list is fetched first (before the slow CoinGecko pages)
  with a longer timeout, and also on its own when Alpha is on but logos and names are off (build 42
  regression).
* Android widget receivers catch errors and always finish; the background job no longer trusts a stale
  offline flag; cancellation is no longer reported as retry; the widget button keeps a running refresh.
* Gemini pairs are split correctly (e.g. MOG/USD, BTC/GUSD).
* Cloud backup only with end-to-end encryption.

**Readable everywhere (build 43)**
* New installs start with «Economical» (background check about every 15 min, recommended); «Live while open»
  is marked «for pros». Existing installs keep their choice.
* Light accent colours reach 4.5:1 on all card surfaces; Fear & Greed number in text colour, zone colours
  only on the scale and following the colour-blind scheme; dark red slightly lighter; widget texts at
  least 11 sp/pt, iOS widgets with Dynamic Type.
* New installs show coin names and the converted value, Alpha tokens off.
* German and English use one word list (alarm, price notification, market notice, notification);
  Greek and Hebrew distinguish alarm and price notification; French «n'est plus négocié» fixed.

**Project**
* Licence and privacy policy name Riad Work; privacy policy adds the right to complain (FDPIC/EU) and a
  note on emails and GitHub issues. Data release notes name sources and licences. Upload script ignores
  case for excluded folders and catches more key files. Play changelogs as `default.txt`.

## 16.2.2 (build 42)

### App

**Third-party data done right (build 42)**
* CoinGecko data is no longer republished on GitHub (API terms): the «data» release keeps only Fear &
  Greed, Coin Metrics and the computed altcoin count; the apps ask CoinGecko directly (global 30 min, top
  30/40 24 h, top 40 for Spot/«Why?» 10 min). «Powered by CoinGecko» is shown under CoinGecko-based data.
* No own logo pack anymore: every install loads the same CoinGecko ranking, Binance lists and images
  directly (weekly), US stock names from Nasdaq. The licence note excludes third-party names, logos,
  trademarks and market data from MIT.

**Alarms that do not surprise (build 42)**
* A new alarm suggests 5 % above or below the price (following the direction); saving a threshold that is
  already met asks first («Alarm would trigger now»).
* Editing only sound, vibration or note keeps the alarm's state; only condition, threshold, currency, pair
  or window re-arm it.
* An alarm that could not be checked (no exchange rate, no candle data) says so on its row.
* iPhone: alarm hints and the store text say that iOS stops background checks after the app is swiped
  away until it is opened again.

**Calmer first start (build 42)**
* New installs: no non-dismissible notification, new pairs without price notification (existing settings
  stay).
* Labelled «Add pair» button, alarm bell always visible, outdated prices readable (colour instead of
  50 % transparency).

## 16.2.2 (build 41)

### App

**Alarms that wait for a deliverable notification (build 41)**
* iPhone: a one-time portfolio alarm stays armed while notifications cannot be delivered (as on
  Android); nothing is marked as triggered.
* Repeating price and portfolio alarms no longer use up a crossing when no notification can be shown;
  once notifications are allowed, they report on the next check (shared rule `QuietHours.mayFire`).
* «Speak price» on a pair turns spoken output on when it was off or alarms-only, and says where to
  change it.
* Settings › Alarms starts with «How current?», which opens the update settings.

**Charts only from the same token (build 41)**
* The action-sheet chart and the mini chart accept a fallback series from another exchange only when its
  last price (with the exchange rate) is within 25 % of the pair price; otherwise no chart instead of
  another token's curve. Shared fixture `sheet_chart.json`.
* `PricePlausibility.sameToken` needs a reference: without a current price a coin still held is left out
  of the value history and named («no matching daily prices»).

**Calmer screens (build 41)**
* Market tab: Pulse and «Unusual today» on top, then two rows «Context» and «Data» that open the
  existing cards on their own page.
* Back from «Add pair» opened via «Unusual today» returns to the market tab.
* The empty group hint in the watchlist appears only from 5 pairs on.
* Portfolio: «Since purchase» says at the number that it uses average cost incl. fees, not FIFO.
* German texts: clearer pulse headlines and «Why?» summaries.

**Store**
* Store texts in all languages claim only what holds: last price from the exchange that lists the pair,
  an alarm that notifies once, «Why is this moving?» without forecasts, no account, amounts stay on the
  device (only pair tickers are sent). Screenshot kit reduced to two scenes plus an optional one.

## 16.2.2 (build 40)

### App

**Portfolio figures that hold for every coin (build 40)**
* «7 D», «1 Y» and the value history scale Binance daily candles to the own exchange price only when
  both are the same token (same plausibility check as the price itself). A coin whose ticker means a
  different project on Binance is left out and named in the «without …» note instead of being
  multiplied into a wrong figure.
* The value chart scales open, high and low with the same factor as the close, so a candle can no
  longer close outside its own range.
* A failed exchange-rate fetch no longer keeps the previous rate on screen; a stored rate older than
  24 hours is not used either (USD or «no price» instead).
* A buy without a price inside the «7 D» window is left out and named, instead of counting its USDT
  fee as a loss.
* Shared portfolio fixture (`testdata/parity/portfolio.json`) checks positions, period gain and the
  scaling on Android and iOS.

**Honest rows and private alarms (build 40)**
* Android: with the app lock on, portfolio alarms on the lock screen show no amounts (as with «Hide
  amounts»).
* Watchlist rows say when the % change or the mini chart come from another exchange («Change: …»,
  «Chart: …», «Change & chart: …»), also for TalkBack/VoiceOver.
* The «Current» status pill is neutral instead of using the rising colour.
* Settings: price colours, % change, coin logos and widgets moved from «Advanced» to «Appearance»;
  «Advanced» keeps updates and spoken output. Search shows the new paths.
* German texts without «Ihre», «Volume (in coins / in quote currency)», friendlier error prefix.

**Alarms you can rely on (build 40)**
* «Always frequent» removed before 1.0: Android no longer declares a foreground service; old values and
  backups become «Live while open». Alarm hints say plainly that iOS and Android decide when a closed
  app checks.
* Backup import validates pair alarms like portfolio entries: an unknown condition, a non-finite
  threshold or a missing id is counted in the confirmation and not imported (shared fixture
  `backup_alarms.json`).
* iPhone: warning above the alarms when Background App Refresh is off (with a button to Settings) or
  Low Power Mode is on; own iOS texts in «How current?».
* iPhone background check with a time budget (network 15 s, total 25 s, reserve 2 s); widget and
  snapshot writes are flushed before the task ends and before portfolio alarm notifications.
* Android: the worker waits for the gas and activity checks (30 s limit); zone check retries up to 3
  times; notification IDs in one place; cancelled requests close their response; changing the
  currency re-checks the alarm.
* Binance spot, futures and COIN-M pause together after HTTP 418/429 (Android and iPhone).
* iPhone: widget refresh guard after a failure (10 min), reload only after a real save; Gregorian
  calendar for portfolio dates and CSV; restoring a backup cleans up leftovers of removed pairs.
* Privacy manifest declares the file-timestamp API (C617.1).

### CI and project
* Workflows pinned to commit SHAs, logo script dependencies pinned, iOS CI on Xcode 26; stricter
  secret scan before upload.

## 16.2.2 (build 39)

### App

**Data safety (build 39)**
* Backup import no longer drops invalid portfolio transactions or portfolio alarms silently: the
  confirmation names how many entries are invalid and will not be imported; cancelling leaves
  everything as it was. The import itself stops if the file has more invalid entries than shown.
* Backup files are read with a limit (16 MB, nesting at most 32 levels) on both platforms, also the
  Android system-backup copy of the portfolio; too large or malformed files get their own message.
* Portfolio notes are limited to 120 characters like watchlist notes (input and import; an older,
  longer note is shortened when it is edited).
* iPhone: a `portfolio.json` that cannot be read (e.g. before the first unlock after a restart) or
  has unreadable entries is no longer replaced by an empty portfolio on the next save. The app reads
  it again first; if it is still unreadable, a copy (`portfolio.unreadable.json`) is kept before
  writing, and if not even that is possible nothing is written.
* Live prices: stopping the stream clears its state before saving the last prices, so a quick tab
  switch can no longer wipe a session that has already restarted (iOS: generation check after the
  save).
* Alarms (Android): saving «triggered» and showing the notification happen in one step that cannot
  be cancelled in between, so a service time-out no longer disarms an alarm without a
  notification. One-time portfolio alarms stay armed while notifications cannot be delivered.
* Android portfolio database: `secure_delete` on, and one VACUUM to remove earlier deleted rows
  from free pages.
* Gas alarm: a failed fetch allows a new attempt after 2 minutes instead of 10; iOS Binance
  interval cache with a lock.

## 16.2.2 (build 38)

### App

**Alarms and futures: what the code really does (build 38)**
* iPhone: the background price check is no longer cancelled and re-planned on every launch and
  every switch to the background. A pending check stays; the next one counts from the last
  refresh. Opening the app often no longer pushes the closed-app alarm further out.
* Binance COIN-M perpetuals (e.g. BTCUSD_PERP) get their own funding rate, open interest and
  funding history from `dapi.binance.com` (open interest = contracts × contract value). USDⓈ-M
  figures (BTCUSDT) on a COIN-M pair are now marked as a reference instead of own data.
* Funding interval unknown (e.g. the Binance interval list could not be loaded and no list from
  the last 7 days is known) is no longer treated as 8 h: the card says «interval unknown» and
  funding alarms skip the check instead of comparing a wrongly scaled rate. For contracts that
  do not settle every 8 h the card also shows the 8-h value the alarm uses; the alarm hint
  explains the conversion for all exchanges.
* Portfolio alarms keep working after a change of the conversion currency (e.g. a CHF alarm
  with the portfolio in EUR): the value is converted with the exchange rate.
* Repeating «today ±x %» portfolio alarm re-arms only after a real return (20 % of the
  threshold, at least 0.5 points), not after a 0.01-point wobble.
* A sell with nothing left no longer subtracts its USDT fee in «7 D» / «1 Y» (as in
  «realized»); a sell whose coin fee exceeds the holdings is marked as too large.
* Binance perpetual with spot candles (or a spot pair with futures candles) names the source
  («change from Binance Spot candles»); Binance futures candles are «Binance Futures».
* Thresholds like «60.000»: the current price decides only if one reading is within a factor
  of 10 of it; otherwise the region's decimal separator does.
* Portfolio note names the exchange you entered as a price source; halving info names
  mempool.space.

### Store
* Play and App Store texts (31 languages): quiet hours hold alarms until they end instead of
  «silent alarms»; «Always frequent» with its 6-hour daily limit (Android 15+); iOS checks in
  the background when the system allows it.

## 16.2.2 (build 37)

### App

**Honest numbers (build 37)** – everything below is arithmetic on public data, not a forecast.
* Portfolio Today / 7 D / 1 Y: a coin without a usable start price (no daily candles, or the
  last close more than 3× away from the live price) is left out and named in the hint
  «Today / 7 D / 1 Y without …» – the other coins still count, instead of the whole period
  turning into «—». Holdings in this calculation are summed exactly (`BigDecimal` / `Decimal`).
  The portfolio widget shows 7 D / 1 Y only when no coin had to be left out.
* «Today» is one number everywhere: the card, the portfolio widget and the portfolio alarm use
  the same value; coins without a day change are left out instead of counting as flat.
* Portfolio alarm: one coin with an old price no longer blocks it – the value is recorded and
  checked as long as at least 80 % of it has fresh prices.
* Funding: the real settlement interval per contract (Binance `fundingInfo`, Bybit, OKX,
  Bitget, MEXC). Funding alarms convert to 8 h with it; the display says «per 4 h» etc. when the
  interval is not 8 h.
* Exchange per transaction is now a choice of the app's spot exchanges. A coin whose
  transactions name one exchange is valued at that exchange's spot price (USDT, USD or fiat
  converted); mixed exchanges or an unreachable exchange fall back to Binance with a hint.
  The value history scales Binance daily closes to that price. A short line under the total
  says where prices come from: «Prices: Binance · USDT = 1».
* Gain (i) shortened to two sentences.

**Market tab: context instead of verdicts (build 37)**
* Bitcoin key figures: Mayer multiple, distance to the high, 30-day change and the new
  **Pi Cycle distance** (111-day average vs. 2 × 350-day average, in %) show the share of days
  since 2016 with a lower value (BTC daily closes from Coin Metrics via the data mirror) and,
  for Mayer and Pi Cycle, the value in 30 days at an unchanged price. No zones, no top/bottom.
* Halving: the month is computed from the current block height and the average block time
  (mempool.space), with the remaining blocks; offline the calendar estimate stays.
* Own thresholds for Bitcoin key figures (Settings › Market alerts): Mayer above/below, MVRV
  above/below, close to the high – checked twice a day on the device, one notification when the
  threshold is crossed, re-armed when the value is back. Included in backups.

## 16.2.2 (build 36)

### App

**Fixes (build 36)**
* After importing a backup, the app asks for notification permission (Android 13+ / iOS) if it
  has not been granted yet – after «Reset app» Android had dropped it, so alarms and price
  notifications of the imported pairs never arrived. The import also fetches prices right away
  instead of waiting for the next background refresh (widgets, notifications, alarms).
* Watchlist widget (Android): the hint «No pairs in the watchlist yet» is shown or hidden by the
  app itself instead of the launcher's empty view, which some launchers kept showing after pairs
  had been added.
* Token symbol: an outlined diamond instead of the binary digits (group chips, row, settings).

**Portfolio ledger & neutral market tab (build 35)**
* Exact portfolio amounts: quantities, buy prices and fees are stored and summed as exact
  decimals (Android `BigDecimal`, iOS `Decimal`, up to 18 decimal places) – 1 satoshi
  (0.00000001 BTC) stays exactly 1 satoshi, no rounding drift over many trades. Valuation
  with market prices stays floating point. Android portfolio database version 2 (columns
  as text, migration 1→2 rebuilds the table); backup and portfolio files add the text
  fields `amountText` / `priceText` / `feeText`, the number fields stay for older versions.
* Fee and exchange per transaction (under «Fee and exchange» in the transaction sheet):
  a fee in USDT raises the cost basis (on a sell it lowers the proceeds), a fee in the
  coin reduces the amount; the exchange is for your overview only. Average cost, not FIFO.
* Honest gain: Today / 7 D / 1 Y take buys and sells within the period out (value at the
  end − value at the start − net inflows), so a purchase no longer counts as gain.
  «Since purchase [USDT]» only covers coins with a buy price and a current price; the others
  are named in a hint. An (i) next to «Gain/loss» explains the calculation.
* Market tab without judgements: the card «Bitcoin indicators» shows measured values only
  (Mayer multiple, 200-week average, distance to the all-time high, 30 days, SMA 50/200,
  weekly and daily RSI, MVRV, Puell, hash rate 30/60 days), each with one sentence of
  explanation. Market phase, zones, top/bottom hints, Pi cycle and «altcoin season» are
  gone; «Altcoins vs. Bitcoin» shows a plain count; the halving shows only the expected
  month («~Apr 2028»). Zone notifications removed (the old backup key is ignored).
* «How current?» replaces the two switches «Update frequently» and «Live prices»:
  Economical · Live while open (recommended, default) · Always frequent (Android only).
  Old settings and backups are migrated; the watchlist shows «Live» when live and fresh.
* Smaller fixes: «More» in the action sheet opens per coin, accent buttons in Settings use
  the accent colour, watchlist widget preview without a cut-off row, 96 dp/pt bottom space
  in the portfolio, clearer status texts («Current · …», «Outdated · as of …»), «Accent
  colour», «Light / Dark», revised portfolio disclaimer.

**Watchlist & live prices**
* Live prices over WebSocket while the watchlist is open (Binance, Bybit, OKX,
  Coinbase, Kraken); REST stays the fallback. «Live» in the status line, mode
  under Settings › Advanced › Updates › How current?.
* Mini chart (24 h) per row, switched on under Settings › Watchlist (off on a new
  install); the % change uses the same candles. Pulse line at the top as coloured
  text, no pills: «↗ 233 rising» green, «↘ 295 falling» red and the average «Ø +0.32%»
  in the yellow warning text colour.
* Tap opens the action sheet at once (no double tap). Order from the top: price with
  one line «↗ +41.71% · 24h · Updated HH:MM» below it (change as coloured text, period
  and time small and grey; the duplicate row of changes is gone), then the three tiles Alarm · Why? · Favorite, then the chart (24 h / 7 d / 30 d, candles or line, touch to read price and time;
  candles also show open, high, low and close), the range, «Market data» and «More».
  The extra 24 h change next to the range is gone (the line under the price already
  shows it); on 7 d / 30 d / 1 y the change stays as coloured text, because it shows
  that range's change.
* Chart caption: a line under the chart names the exchange and the data time
  («Binance · as of 14:00»). Fiat pairs without their own candles use the USDT
  candles converted with each candle's historical daily rate («converted at daily
  rates»; «(current rate)» when the daily rates could not be loaded) – the same
  Frankfurter time series the portfolio uses, at most once per currency every 6 h.
  The stored chart is dropped after a manual refresh, pull-to-refresh or a price
  move of more than 3 %. Stablecoins keep an axis of at least ±0.5 % (other flat
  series ±0.1 %), so tiny wiggles no longer look like crashes; approximated volume
  bars are drawn at half strength and marked «≈»; the chart geometry is computed
  off the main thread.
* Swipe left to delete (with Undo), right for favorite; no star button any more:
  with coin logos on, favorites get a small star on the logo (no accent border any
  more; selected rows show a tint); jump button in long lists.
* Groups, a note per coin, second line «≈ value» in one of 31 currencies.
* Header with more room for groups: «All» as soon as there is one pair, no
  logo; ⋯ menu on the right (logo and app name → About, Refresh, All alarms,
  Sort, report). The bell next to it appears only while alarms are active and
  shows no number (a number read like «fired that often»); it sits close to ⋯ so
  the last group chip is no longer cut off. «+» (add pair) next to the search icon.
* The «+» (add pair) on the starter screen is the same filled accent circle as next to the
  search icon.
* First start: the starter selection waits briefly (max. 4 s) for the logos of the five
  suggested coins and shows a quiet placeholder meanwhile – no jump from initials to logos.
* Buttons that only fold something open («Why?», «Show details», «Show indicators»,
  «Show list») have a down/up chevron like «Context»/«Data» instead of «→». One
  shared chevron in the accent colour everywhere something folds open (e.g. «Value
  history», advanced alarm options, Add pair), turning when it opens.
* Links look the same everywhere: links to websites are accent-coloured with ↗
  (settings rows, «Request exchange» on Add pair), links inside running text are
  accent-coloured and underlined on both platforms. Rows on the settings
  sub-pages have no leading icons.
* Sort the watchlist like an exchange: «Name ⇅ · Price ⇅ · 24h ⇅» above the
  pairs – first tap A–Z / highest first, second tap reverse, third tap back to
  your own order (which stays saved). Favorites stay on top, sorted among
  themselves; prices are compared in CHF in the background (also without
  «≈ conversion»). The choice is remembered.
* All coin lists look the same (Watchlist, starter selection, Market › Unusual
  today, Portfolio): logo 36, same text sizes, and the list is one unit – no gaps
  between rows, full-width dividers, only the first and last row rounded, one thin
  outline around the whole list.
* Market: the «Now» title is gone; instead a status pill like on the watchlist
  («All current · 2 min ago», «Updating…» while loading).
* Portfolio numbers made consistent and honest: one stablecoin rule everywhere
  (header, value history, cut-off CSV, widget and portfolio alarms). Only USDT, the
  valuation basis, is always 1; USDC, DAI & co. use their market price, and without
  one they have no value – the position shows «—» and «no price», it is counted in
  «without N coins» and the total profit is unknown, instead of a silent 1 that would
  hide a depeg. In the value history such a stablecoin without daily candles goes under
  «without …» as well. The watchlist «≈» line keeps 1 as a pure display fallback. One
  exception for the «7 d» figure: a stablecoin without a daily close a week ago counts
  as 1 only while its live price is within 0.1 % of 1; otherwise «7 d» shows «—»
  instead of a number built on a price nobody saw.
* Portfolio prices for coins not on Binance spot (last hourly candle from another
  source) must pass an identity check: within 1/3…3 of the median watchlist price of
  the same coin (USD-stable spot pairs, active, at most 30 days old) or of the last good
  stored price. A candle price outside that range (most likely another token with the
  same ticker) is discarded: the last good price stays with its «as of» time (no stored
  price → «—») instead of a confident wrong total; without any
  reference the candle price is accepted as before (internally marked unchecked). The
  check reads the watchlist on the device – no extra request.
* Value history in CHF/EUR/… converts every day with that day's ECB rate, so
  franc moves show up; if the rates can't be loaded, «Converted at today's rate»
  appears under the chart.
* Implausible historic prices are no longer used: the cut-off export skips fallback
  closes more than 25 % away from the current price; the value history and the «7 d»
  figure drop a coin only when its latest daily close is outside 1/3…3 of the live
  price (most likely another token with the same ticker), not after a normal day
  with a 30 % move. The 25 % rule of the % change is unchanged.
* When a pair's % change comes from another exchange's candles (e.g. Kraken →
  Binance), the action sheet and «Why?» say so («Change from Binance candles»).
* Android: the first-alarm message says honestly when prices are checked with the
  app closed (background interval – not more often than every 15 minutes, often later) and points to
  «Update frequently».
* Portfolio no longer in the Android system backup / iOS device backup by default;
  new switch Settings › Portfolio › «Portfolio in system backup». On Android the
  portfolio lives in its own database file. Use the password-protected backup to
  move it to another device.
* Market › Unusual today: tapping a coin opens the same action sheet as in the
  watchlist; coins not on the watchlist open it as a preview (COIN/USDT on
  Binance) with «Add to watchlist».
* Watchlist: long-press a row to start selecting (that row already ticked).
* Group chips: all neutral; the selected one gets an accent outline and accent
  text/icon (★ and ⚡ white until selected). In the rows the ⚡ and the favorite
  star are white and turn accent-coloured only while the matching chip (⚡ / ★) is
  selected; the ⚡ in settings hints is a white symbol instead of a yellow emoji.
* Chip row: All (shapes) · ★ · Token (diamond outline) · TradFi (bank) · α · ⚡ · other groups ·
  «+». «Token» shows everything except the TradFi group; Token and TradFi appear
  only together, when there are TradFi and other pairs. «α» shows Alpha tokens
  (coins from the Binance Alpha list that Binance spot does not list regularly; never
  Binance spot pairs or DEX pools), only when there are Alpha and other pairs; an
  Alpha token also counts as a token. Widgets
  can use «Token» and «α» too.
* Settings › «Token & TradFi» (was «Dated futures»): choose «Tokens only» (default),
  «TradFi only» or «Tokens and TradFi» – with the same icons as the chips; the
  QTLY switch (quarterly and dated futures) and the new switch «Incl. Alpha tokens
  (α)» (on by default, with a short explanation) sit below as part of Tokens and are
  locked under «TradFi only». Both are in the backup and the settings search.
  Replaces the TradFi switch (on → both, off → tokens only). Saved pairs always stay.
* Watchlist rows show a small kind icon (Token / TradFi / α) before the bell – grey,
  in the accent colour while its chip is selected; screen readers say «TradFi» or
  «Alpha token».
* Chips: tapping an icon chip briefly shows its name below it; without any chips
  a grey hint «Your groups will appear here».
* Pair sheet: the alarm bell is outlined without alarms and filled with alarms;
  «Why?» shows a filled light bulb while something unusual is happening (⚡).
* Portfolio value history like the pair chart: candles | line, grid with value
  labels, dashed line and label at today's value, «High» marker; candles are daily
  (weekly/monthly for long ranges); compare mode with % grid and 0 % line.
* Market «Context» and «Data» and the whole Settings area use the same cards as
  «Now» (title with accent bar inside the card); expandable market cards have a
  chevron on the right.
* Deactivate a pair (action sheet › More, or multi-select): no requests, no
  notifications, no alarms – it stays greyed out with its last price; a pill
  «N deactivated» next to the status shows only those. Re-activating refreshes it.
* Alarms during quiet hours wait until quiet hours end and then fire with sound
  (instead of arriving silently and a one-time alarm being used up).
* Alarm editor says under the sentence when alarms are checked with the app
  closed («on the next check – often only after 15 min or later»); an (i) next to that
  line (also in the portfolio alarm sheet) explains it in full.
* Honest alarm timing: «When alarms are checked» appears once, after the first alarm is
  saved (folded into the first-alarm message where there is one, never two dialogs in a
  row), and always behind the (i) in the alarm editor – with the app closed the system
  checks about every 15 minutes, short spikes in between can be missed. iOS text made
  truthful: background checks are irregular, often less often than every 15 minutes, and
  «Update frequently» only checks more often while the app is open. Path shown: Settings ›
  Advanced › Updates › Update frequently. The «shown» flag stays on the device and is not
  part of the backup.
* Gesture hint mentions «Tap: Alarm and Why?» and long-press to select; iOS close
  button 44 pt; alarm cards read sound/speech/repeat to screen readers, iOS card
  and switch tap separately.
* Alarm bell in watchlist rows in text colour (like the header), not the accent.
* Settings main page: a monochrome line icon in the text colour (white in dark mode,
  black in light mode) before every row (tab icons reused for Watchlist, Market and
  Portfolio; a bell for Alarms, a lightning bolt for Market alerts); only «Reset app»
  keeps the red error colour. The accent colour stays for heading markers, switches and
  selections; sub-pages unchanged.
  On all settings rows the current value or summary sits under the title, smaller and
  grey (no longer on the right).
* Privacy policy / SECURITY.md: exchanges see the requested pairs; a backup
  without password is plain JSON; iOS portfolio alarms follow the backup switch.
* Price colours as three styles with a candle preview: «Fresh» (green/red),
  «Traditional» (olive/magenta, new) and «Color vision deficiency» (blue/orange);
  switches for swapping rise/fall and high contrast below the cards.
* Portfolio value history: «CHF | USDT | Compare» – compare shows both curves in
  % from the start of the range and the currency effect («Currency effect:
  −5.10%»), only when a non-USD currency is chosen and the daily rates loaded.
* Market tab: registers «Now | Context | Data» at the top instead of fold-out
  sections (swipe to switch); a macro event within ±2 h appears at the top of
  «Now»; the macro notification opens the Market tab at the economic data.
* Alarms: «x % in y hours» now uses a sliding window and also fires with rare
  background runs; no more duplicate «new 30-day high» on the same day; a one-time
  alarm is not used up while notifications are blocked; edits made during a
  refresh are no longer overwritten; percent alarms created before the first price
  get a proper reference. Android: alarm saved before it is shown (no duplicates),
  one failing pair no longer stops the others. iOS: market phase / Fear & Greed
  checks run again (no reschedule on every app switch, catch-up on opening).
* Number input: portfolio amounts and prices read like alarm thresholds
  («60.000» = 60000 in German), same decimal separator rules on both platforms,
  price 0 kept when editing (iOS), tiny values no longer cut to 0 (cut-off CSV,
  DEX, alarm editor), «5%» accepted in settings, NaN/∞ prices rejected.
* Pairs no longer traded: a pair missing from an exchange's complete price list is
  first checked once with a single request; it is marked «no longer traded» only if
  the exchange answers without a price. Network errors and rate limits stay ordinary
  errors (the last price stays); marked pairs are not asked again and come back as
  soon as the price list contains them again.
* OKX Futures loads its pair list from the instrument list, so stocks and
  commodities (NVDA, AAPL, XAU …) count as TradFi instead of crypto tokens; only
  live contracts. Bybit pair lists load all pages.
* Exchanges without a trade time show the time of the fetch; «outdated» counts from
  the last successful fetch. Bybit live: an update without a new price no longer
  makes the pair look current.
* Exchanges: OKX/Gate no longer fail a whole batch when one pair has no bid/ask;
  Kraken high/low/volume over the rolling 24 h; Upbit/Bithumb day-only high/low
  hidden; correct volume units for Binance COIN-M and OKX swaps; iOS live price of
  exactly 1 no longer dropped.
* Robustness: damaged settings file no longer crashes Android at start; failed
  background tasks are logged instead of ending the app; restore validates first
  and rolls back on failure; iOS never overwrites an unreadable watchlist (copy kept
  as watchlist.unreadable.json), reads unknown entries leniently, coordinates
  widget/app writes, reports expired background tasks in time; iOS portfolio
  alarms and portfolio widget data follow the system-backup switch too.
* Add pair › Select precisely: «Choose pair» and «Several pairs at once» as two
  tabs of equal height instead of a step card plus a fold-out card.
* Group row: favorites as a ★ chip, and a ⚡ chip (no number) that shows only
  the pairs where something is happening right now – replaces the card
  «Something is happening right now» above the list.
* Tap «10 of 30 outdated» to see only the outdated pairs; tap again (✕) for all.
  Ends by itself once everything is current.
* Multi-select (⋯ › Select): tick pairs, then Favorite · Group · Delete for all
  at once (Delete with Undo).
* Rows show the time only when a pair is not current («Binance · 13 min ago»);
  otherwise just the exchange – the status line already says «All current · …».
* «Refresh» in the menu is greyed out while a refresh runs or right after one,
  instead of the message «Just refreshed».
* Favorite star without coin logos in text colour instead of the accent colour.
* Calmer look: section titles in text colour with a short accent bar in front
  instead of accent-coloured text; in dark mode no more brownish fills –
  selected chips, segments and the jump button are neutral grey with
  accent-coloured text, light highlights (portfolio total card, initials,
  drag handle, selected rows) are grey, and the allocation bar blends toward
  white. Light mode unchanged.
* Two different bells: a plain bell = price notification for this pair (the
  same bell next to its switch in the action sheet; crossed out when off, in the row too), a ringing bell (with waves)
  = alarms everywhere – row, action sheet, header, menus, alarm pages, app
  shortcut (no more alarm clock). Starter pairs get the price notification like
  every other pair; permission is asked only when you add a pair or an alarm.
* «Something is happening right now» card can be hidden under Settings ›
  Watchlist; market alerts stay as they are.
* «Outdated» / «exchange not reachable» per row and in all widgets (after
  2 minutes with «Update frequently»). One freshness threshold for everything:
  status pill, faded row, red time, the word «outdated» and the screen reader
  now switch together (before, the pill counted from 5 minutes on its own rule).
* Watchlist widget: rows arrive together with the header (Android 12+), no
  more rows stuck on «Loading…»; updates with every refresh in the app.
* Start with one tap: the five largest coins with live prices, all preselected.
* BTC pairs show «1 CHF = n sats».
* One look for numbers everywhere, no change pills anywhere in the app: every change is
  coloured text – arrow plus signed value in the price colour («↗ +0.21%», «↘ −2.77%»,
  «↗ +12.40 USDT»; a real minus sign), grey for 0, unknown or deactivated, averages
  («Ø +0.32%») in the yellow warning text colour, the period («24h», «7d», «1Y») small and
  grey. Watchlist rows, pulse line, action sheet, «Market data», «Why?», Market cards and
  Portfolio all follow this rule. Up/down symbols are diagonal arrows ↗ ↘ (no more ▲/▼),
  also in the price-colour preview, the «Why?» factors, notifications, derivative alarm
  texts and cycle signals. The funding rate in the action sheet's «Futures» tab is
  coloured text without an arrow.
* The setting «Text on pills and buttons» is gone (Android and iOS): buttons with the
  theme colour as background use the theme's normal contrast colour again. Older backups
  that contain the field still import (it is ignored); new backups no longer write it.
* The quote currency after a price («USDT») is small and grey like the exchange
  name – everywhere, including the high/low/bid/ask/volume block on Add pair.
* Add pair: recent searches under the empty search field (stored only on the
  device, «Clear» removes them) and quick filters (gainers, losers, volume) on
  «All …-pairs», filled by one bulk request to the exchange at most once a minute.
* Search by coin name: «Search pair, name or exchange» on Add pair, in the watchlist
  search and when editing a group. «bitcoin» finds BTC, «ether» ETH, accents don't
  matter, name plus quote works («ethereum eur»); order: exact symbol, symbol
  prefix, name prefix, name contains, exchange. Add pair also searches by exchange
  («kraken», «btc kraken», exchange word of 3+ letters). Names come only from data
  already on the device (logo/name list, stock names) – no new request.
* Action sheet: the card «Market data» with the tabs «Spot | Futures» (was «Futures
  data», only for futures pairs) now appears for every crypto pair (not for
  deactivated pairs, DEX pools or TradFi spot data). Spot (CoinGecko, USD, compact):
  market cap with its 24 h change as coloured text, 24 h volume, vol/mkt cap, FDV, circulating/total/max
  supply with a progress bar, «Source: CoinGecko · as of …», or «No spot data for
  this coin». Futures: funding rate as coloured text with countdown, open interest, source; for
  spot pairs the coin's USDT perpetual (Binance → Bybit → OKX) is loaded only when
  the tab is opened and labelled «Reference from Binance Futures (the coin's USDT
  perpetual)», otherwise «No futures data for this coin». Opens on Futures for
  futures pairs and on Spot for spot pairs, remembered for the session; (i) explains
  the open tab.
* Futures data per exchange: funding rate and open interest now come from the
  exchange you watch for WOO X, Bitget, MEXC, HTX, Hyperliquid and Deribit (as before
  for Binance, Bybit and OKX). Other futures exchanges (e.g. Phemex) – or an exchange
  whose own source fails – show reference data from Binance → Bybit → OKX with the
  hint «Reference from Binance Futures – funding and open interest differ per
  exchange; on WOO X Futures they may differ.». Hyperliquid's hourly funding is shown
  «per hour».

**Alarms**
* Alarm as a sentence: «When BTC goes above …» with a price suggestion,
  «Once / Every time», other conditions under «Advanced».
* One-tap templates: ±1 %, ±5 %, new 30-day high/low, volume ×3 (with Undo).
* Repeating price alarms fire only when the level is crossed and re-arm after a
  0.2 % move back.
* New conditions: move x % within y hours, volume spike, near the high/low of
  30 days / 90 days / 1 year (optionally new highs/lows only), gas price.
* Funding rate above/below x % and open interest change in 1/4/24 h for
  perpetuals on exchanges with their own futures data (Binance, Bybit, OKX, WOO X,
  Bitget, MEXC, HTX, Hyperliquid, Deribit). Thresholds are per 8 hours; Hyperliquid's
  hourly funding is converted (× 8). If only reference data from another exchange is
  available, funding alarms still fire and the notification adds « · Reference:
  Binance Futures»; open-interest alarms pause (open interest of different exchanges
  is never mixed in one history).
* Alarms in your currency (e.g. 60,000 EUR on a USDT pair).
* Portfolio alarms: total value above/below an amount, today's change up/down x %.
* After saving an alarm a banner confirms it; when notifications are off it says
  so honestly (Alarms and portfolio alarms).
* Market notices (Fear & Greed, gas, unusual activity) respect quiet hours and the
  notification permission; a notice that could not be shown is not marked as sent.
* Quiet hours, «Test alarm», choice of alarm sound; speech output queues
  several alarms and respects silent mode.

**«Why?» & market**
* «Why?» page, short first: a top card «In short · As of HH:MM» with one sentence, up
  to three notable factors as one grey text line and «Confidence»; everything else sits in the
  collapsed card «All values and sources» – «Observation» (measured values), «Possible
  context» (hints, BTC line), the sources line (only the sources actually used) and the
  long disclaimer. No causal wording («passt zeitlich zu», «möglicherweise» instead of
  «weil» / «treibt»), title «Hints» instead of «Likely reasons». Short footer
  «Simultaneous does not mean cause · No investment advice». All 31 languages.
* «Why is this moving?» as a factor list (volume, market, volatility, open
  interest, distance to the 30-day high and low, position within 3 days, funding,
  sentiment) with how closely the coin follows Bitcoin.
* «Why?» measures against the coin's own volatility: a 24 h move counts as clear
  only from k × its usual swing (σ of the hourly returns over up to 72 h before the
  24 h window; k = 2 for the coin with a 2 % floor, 1.5 for the market with a 1 %
  floor; fixed thresholds as before when there is too little history). Besides BTC
  and ETH the broad market is a reference: the median 24 h change of the mirrored
  top 40 without stablecoins, BTC and ETH (at least 10 coins, data at most 3 h old);
  «market-wide» vs. «only this coin» considers all three. Each factor names its
  source and age («Candles: Binance · 14:00», «Market breadth: CoinGecko · 13:05»,
  «Futures: Bybit · open interest compared with 2 hours ago», «Fear & Greed: today»);
  fallback sources and reference data are marked. Open interest is compared with the
  median of up to 5 stored samples (at least 30 min apart, at most 12 h old, same
  source only; mixed directions → «Open interest mixed»; the old single sample is
  taken over). New: near the 30-day low, position within 3 days, funding trend
  («rising for 24 hours», from the last funding settlements). Stablecoins are
  described by their deviation from the 1:1 peg, without market and volatility
  factors. Volume is marked «estimated» when the source has no quote volume and is
  left out when more than 6 of the 24 hours had no trades; the 1 h volatility factor
  needs a move of at least 0.5 %.
* Unusual activity detection with adjustable sensitivity, optional notifications.
* Market tab in three sections: Crypto Pulse «What stands out», «Unusual
  today», Fear & Greed; Bitcoin indicators, dominance, altcoins vs. Bitcoin, halving; total
  market cap and volume, network fees (Ethereum, Base, Arbitrum, Polygon, BNB
  Chain, Bitcoin), economic-calendar hint.
* «What stands out» now also shows market breadth («Top 30 ↗ 22 · ↘ 8» as coloured text: how many
  of the 30 largest coins, without stablecoins, are up over 24 h) and the total
  crypto market cap with its 24 h change; a sentence only in clear cases (almost
  all rising/falling, or Bitcoin moving while most large coins don't follow). The
  widget shows the breadth next to Fear & Greed when there is room.
* Periods named where they apply: «What stands out» shows «24h» above the coins,
  «Top 30 · 24h» for the breadth and the market cap as «Crypto market 2.82 tn USD
  ↘ −2.77% · 24h»; «Unusual today» rows show a small grey «24h»; the Fear & Greed card
  shows its number as coloured text on the app's five-step scale. No change pills on
  the Market cards.
* Market tab status pill knows «Offline · as of HH:MM»: the last data stays on screen,
  nothing is reloaded while offline, and expired data reloads once the device is back
  online.
* Opens instantly from stored data; every row shows source and age, outdated
  values in a warning colour; «Context» and «Data» start collapsed (also on the first visit).
* Short explanations for funding rate, open interest, RSI; now every
  row of the market cards has an (i) button with a short explanation (37 texts),
  so the cards themselves show less text.

**Portfolio & privacy**
* Portfolio tab (on by default, can be switched off under Settings › Portfolio; Android
  stores only an explicit choice, iOS turns it on once for older settings
  (`portfolioDefaultV2`), a backup without the field restores it as on): buys and sells, average price, profit/loss, value
  history (7 d / 30 d / 1 y / ∞ since first buy, no 5-year limit; range and candles/line below the chart), cut-off date export as CSV.
* Total card: «Today · 7 d · Total [USDT]» as coloured text (the total names its unit;
  the portfolio widget shows «Total [CHF]» etc.); a new line under the total shows the
  1-year change («↘ −38,412.95 USDT −27.85% · 1Y»), hidden while the history is shorter
  than one year; amounts carry arrow and sign («↗ +12.40 USDT»); the chart in a coin's sheet shows volume bars
  (quote volume of the candles; approximated bars at half strength with «≈»).
* iOS «Today» on the total card uses the same prices as Android: its 30-minute
  samples now apply the stablecoin rule too (example: 5,000 USDC at 1.00 plus 1 BTC
  going from 5,000 to 5,500 shows +5 % on both platforms; iOS showed +10 % before;
  a stablecoin without a price is left out of the sample, never stored as 1). Older
  samples age out within 48 hours.
* Portfolio status pill like the watchlist: up to date / «Prices outdated · as of HH:MM» /
  «Offline · as of HH:MM». The last fetched price of each coin is kept on the device with
  its time, so offline or right after a start the portfolio shows the last value instead
  of 0.00; outdated values are faded. Prices count as outdated after 3 × the background
  interval (at least 15 min), and outdated prices are never written into the widget, the
  value history or the portfolio alarm checks. The stored prices are never part of a
  backup and are deleted with «Clear portfolio» (`PortfolioPriceFallback`, both platforms).
* 24 h change: the source is named «Binance USDT»; for non-USDT quotes the
  USD → quote rate is checked as well.
* Number on the app icon (Settings › Alarms, on by default): on iOS every new
  alarm or market alert counts +1 and opening the app resets it to 0, like
  messengers do; on Android each alarm counts 1 and the home screen decides
  between number and dot (e.g. Samsung «Show with number»).
* Section headings look the same everywhere (settings, portfolio, market,
  refresh report, licences): small, bold, in the text colour with a short accent bar.
* Same ⋯ menu start in Watchlist, Market and Portfolio: app logo and name
  (About), then Refresh, then the tab's own items; the Market tab gets a ⋯ menu.
* «Reset app» (own red card at the bottom of the settings main page): deletes
  everything, like a fresh install (with confirmation).
* «Why?» opens as a page with a back arrow, like «Alarms». Back from either returns
  to the pair's action sheet; back again shows the watchlist.
* Watchlist with names: pair/price, name/% change and exchange/≈ value line up
  row by row; a missing name shows «–»; names equal to the ticker are shown
  («BNB»), so 1000BONK now shows «Bonk».
* All logos and names in one place: a GitHub Action builds our own list once a
  day from CoinGecko (all coins; a ticker shared by several coins goes to the
  top-1000 coin, otherwise to the most traded one), the Binance symbol and Alpha
  lists, OKX, Binance's stock images and the Nasdaq stock names, with every logo
  as a small WebP on GitHub Pages. The app loads this one list, the first time all
  logos in one pack (one download), later only the few new ones — instead of
  hundreds of requests to CoinGecko, Binance and Nasdaq. Small futures tokens like
  AIA, AGT, AIO, ALPINE or ANIME now get their logo. Changed logos are reloaded.
  Until the list exists the app uses the old sources (now with the Binance Alpha
  token list, pauses between the CoinGecko pages and a retry after 6 hours if a
  source was missing). The weekly check asks GitHub «anything new?» and downloads
  nothing when the list (or the stock-name list) is unchanged.
* Watchlist, calmer and easier to hit: the gesture hint names the directions («swipe left to
  delete · swipe right for favorite»); a long press no longer starts sort mode by accident
  (sort via ⋯ › Sort, then drag); search, «+», close search and group chips have 48 dp / 44 pt
  touch areas (the symbols stay small), the ⚡ in a row too; without coin logos a favorite shows
  a small star and the screen reader says «Favorite».
* Empty «All alarms» page has a «New alarm» button (pick a pair → its alarms) instead of a dead end.
* Add pair: with no search results, «Choose exchange and pair yourself» stays right there.
* Settings › Advanced › Updates: each mode says when it works – «While the watchlist is open» (live
  prices) and «Even when the app is closed» (background, update frequently).
* Android «Update frequently»: with the screen off at most every 5 minutes instead of every
  minute (the regular background update is unchanged); iOS saves the watchlist off the main
  thread.
* Realized profit/loss with buys entered without a price: the priced part is
  realized at the average price of the priced buys, the rest (and sells without a
  price) shows as unknown («+12.50 USDT + —» or «—»); fully sold coins always appear
  under «Closed», also at break-even or with an unknown result.
* Watchlist «≈» line: stablecoins other than USD/USDT use the portfolio's stored
  market price (otherwise 1 – only as a display fallback here; the portfolio itself
  never assumes 1), without extra requests. Pairs with a «1000» prefix
  stay 1000 units on purpose (1000SATS = 1000 × SATS, 1000CAT = 1000 × Simon's Cat).
* Portfolio: the example in the empty portfolio uses USDT like the price field; the coin page
  explains the average buy price (weighted average, sells at the average, not FIFO). The old
  tip «tap ☆» now says «swipe right».
* Alarms: when notifications for the app are turned off, the alarm list and the
  alarm overview show a red notice «Notifications are off – alarms can’t reach you»
  with «Turn on» (opens the app's notification settings); checked again on return.
* «Why?»: «BTC is pulling the market along» now reads «Market is moving with BTC» –
  a measured co-movement, not a claimed cause.
* Gold, silver, platinum and palladium contracts (XAU, XAG, XPT, XPD …) get their own
  drawn icon — a bar on a coin in the metal's colour — and their names.
* Market data the same for everyone comes from one place too: an hourly GitHub
  Action mirrors CoinGecko's market cap, dominance and top coins, the Fear & Greed
  index and Coin Metrics' on-chain values and BTC price history, and computes the
  altcoin season once (instead of about 21 price requests per phone every 3 hours).
  The app reads the mirror and asks the providers itself only when it is missing or
  too old. Live
  prices, the Crypto Pulse and everything per coin still come straight from the
  exchanges (except the Spot tab of «Market data», which asks CoinGecko for coins
  outside the mirrored top 40).
* LUNA2 (Terra 2.0 futures) shows the LUNA logo and name.
* Stock perpetuals without a tokenised bStock (e.g. CAT, BYD) get their logo
  from Binance's stock images; loaded for all stock tickers of the stored pair
  lists, never just the watched ones.
* Quieter first day: on a new install Fear & Greed, unusual activity and the
  economic-calendar morning notice are off until switched on (⚡ in the watchlist and the activity card stay).
* Stock names with «Show names»: CAT shows «Caterpillar, Inc.», BYD «Boyd
  Gaming Corporation» (official Nasdaq symbol list, loaded at most weekly).
* Portfolio works like the watchlist: swipe a coin left to delete all its
  transactions (with Undo), swipe a transaction left to delete it (with Undo,
  no long press); ⋯ menu with «Refresh» on top and «Clear portfolio» at the
  bottom (red, with confirmation); pull down to refresh, no refresh button in
  the top bar. The «Biggest movers» card is gone: each coin row shows its
  24 h change instead, and the number of transactions next to the amount.
* Portfolio lock (biometrics or device passcode) for the portfolio only. When the
  session locks again (after more than 60 s in the background), open backup
  dialogs close like the add-transaction sheets (Android: export dialog and restore
  step; iOS: export options, restore password and «Replace?» confirmation). An
  export already confirmed or a restore already writing still finishes; a file
  picked or a password check finishing after the lock engaged is discarded – tap
  again and unlock.
* «Hide amounts»: values shown as ••• in the portfolio, its widget and
  portfolio alarms; percentages stay visible.
* Portfolio widget: hourly prices for coins that are only in the portfolio
  (not on the watchlist) are loaded once an hour, so the value history no
  longer stays at «History coming soon»; with «today since 00:00» the chart
  starts at midnight and fills up over the day. iOS keeps these hourly prices, the
  history and the candle attempts in files that are always excluded from device and
  iCloud backups (moved once from the App Group settings).
* Backups can be protected with a password (AES-256-GCM, PBKDF2-HMAC-SHA256 with
  600,000 iterations for new backups; older files with 210,000 still open) and are
  interchangeable between Android and iOS. A weak password (shorter than 12
  characters, only digits, one repeated character or a common sequence) gets a
  warning; the minimum stays 8 characters. iOS encrypts and decrypts off the main
  thread, «Continue» stays disabled while checking. Android system backup now includes
  watchlist, alarms and settings. The backup file also keeps the watchlist column
  sort.
* Android: the main database (watchlist, alarms) overwrites deleted or replaced
  content with zeros (`secure_delete`), so removed entries do not linger in the
  file or its system backup. After an update from 16.2 (database 12) the space of
  the old portfolio tables is compacted (VACUUM); if that fails once, it is retried
  on every start until it succeeds.
* iOS: if the portfolio alarm file cannot be written, the thresholds stay in the
  watchlist file and are moved on the next save; while they are there and
  «Portfolio in system backup» is off, the watchlist file is excluded from the
  device backup too.

**Widgets**
* No pills in widgets any more: every change is coloured text (arrow + signed value, green/red,
  grey for 0 or unknown) and the time window («1Y», «24h», «7d») is always grey. Portfolio
  widget: the header pill is gone, the percent sits on the change line («↘ −31,997.28 CHF
  −27.85% · 1Y»; on narrow widgets the time window, then the amount drop first); Today /
  7 d / Total as right-aligned coloured text; Pulse coin chips as plain text (three coins
  from 238 dp); single widget «24h» grey on Android too. Preview images updated.
* New: portfolio widget (adapts to its size) and «What stands out» (Crypto
  Pulse); single-coin widget with candles or line and a 24 h / 7 d / 30 d range.
* Rounded corners, previews in the widget picker, compact rows with ↗/↘, a
  group per widget.
* Portfolio widget options per widget (Android: configure screen, can be changed
  later; iOS: widget settings): «USDT only / Conversion only / Conversion + USDT», range
  24 h / 7 d / 30 d / 1 y, candles or line; the chart looks like the app charts
  (guide lines, «High» marker, price colours). 7 d / 30 d / 1 y use the daily
  history the Portfolio tab already stored, otherwise the widget falls back to 24 h
  and says so. Existing widgets keep their look. New option «View»: «Overview» (like
  the total card: value, change, Today · 7 d · Total), «Chart» (as before, default) or
  «List» (holdings with logo, share and value, as many as fit, «+ n more»).
* All widgets show the quote currency («USDT») small and grey after prices, like the
  watchlist; also in the Portfolio tab (total, rows, allocation, coin detail).
* Portfolio widget: coins without a price are counted («without 2 coins»)
  instead of silently showing a total that is too low.
* Watchlist widget: a changed group selection shows up immediately after the widget
  setup (Android draws one after another, iOS redraws at once from the stored
  prices); the setup offers «α» (Alpha tokens) as well. Both platforms pick the
  group with the same rules (`WidgetGroupSelection.pick` / `options`).
* Watchlist widget no longer shows a new time above old rows: Android rows carry a redraw
  version (`ListWidgetDataVersion`, the row service reloads when it is behind); iOS waits
  up to 3 s for the prices file to be at least as new as the refresh time, otherwise it
  redraws again in 60 s (`WidgetDataFreshness`).
* Portfolio widget list rows name the period of their change («24h ↘ −0.25 %», «today»
  with the since-midnight basis), matching the % basis actually used.
* «What stands out» widget: Fear & Greed number in the app's five-step colours, breadth
  «↗ 21» green / «↘ 9» red, «Top 30 · 24h» and «24h» before the coins.
* Widget previews in the picker redrawn with current sample data (BTC 83,142.00, ETH
  2,505.49, SOL 109.60, XRP 1.394): preview images, the Android 12+ preview layouts and
  their sample values, and the iOS preview data – they show exactly what the live widgets
  draw today.
* Watchlist and portfolio list widget: the divider between rows is a neutral grey (text
  colour at 25 %, 45 % with high contrast) instead of the accent colour.
* iOS widgets: the accent glow in the background is gone – a neutral gradient, like
  Android.
* Add a widget from inside the app (Android: pin, iOS: short guide).
* iOS: Live Activity for one pair on the Lock Screen and in the Dynamic Island.
* Coin logos in the watchlist and single-coin widgets (own switch, see below).

**Coin logos**
* Real coin logos instead of letters: watchlist rows, action sheet header,
  portfolio, market cards, add-pair lists (iOS) and the watchlist and single-coin
  widgets. Round, fixed size, never at the expense of price or number columns; a
  light backing in dark mode keeps dark logos visible.
* Nothing bundled, nothing fetched one by one: logos come from CoinGecko
  (CoinMarketCap would need an API key). The app downloads the logos of all of the
  roughly 2,500 largest coins at once, then only new ones (symbol list at most
  weekly), and shows them only from the device (iOS: App Group, so widgets use them
  too). Gaps such as gold, silver and stocks are filled from the Binance website's
  public symbol list (unofficial; if it disappears, those keep their initials).
  Every install makes the same requests, so neither provider can tell which coins
  you follow.
* Without a logo (unknown coin, offline, error) the circle with the coin's initials
  appears — never a broken image. DEX pools always show initials, so a token that
  calls itself «BTC» never gets the Bitcoin logo.
* TradFi pairs (stocks, gold, FX) never take a CoinGecko logo, where a crypto token
  often uses the same ticker (CAT, NVDA): their logo comes only from the Binance
  list (tokenised stock such as NVDAB, futures-only entries), otherwise initials.
  Which pairs are TradFi comes from the exchange's pair list (loaded once if missing).
* With a switch off, that area shows neither logos nor initials (no empty
  placeholder).
* Logos after an offline first start: a sync that failed without internet no longer
  blocks further attempts. It runs again when the connection returns, with a growing
  pause (5 s → 15 s → 30 s → 60 s … at most 15 min), and on return to the app; never two
  syncs at once (`CoinLogoRetry`, both platforms) – same requests as before.
* Three switches under Settings › Advanced › Coin logos: «In the app», «In the
  portfolio» and «In widgets». A new install has «In the app» and «In the portfolio»
  on (the watchlist shows logos right away), «In widgets» off; all off means nothing is
  loaded. Included in backups (Android ↔ iOS).

**Settings & navigation**
* Four tabs: Watchlist · Market · Portfolio (on by default, can be switched off) · Settings. «Add pair»
  opens from «+» in the watchlist; no separate search tab.
* Tab bar: labels in the normal text colour (white in dark mode), only the chosen tab's icon
  in the theme colour (no coloured pill behind it), the active icon 10 % larger. Market, Portfolio and Settings show their tab icon in
  front of the title (in the title colour); the watchlist stays without a title. New line
  icons in the style of «Market»: list with a small price line for Watchlist,
  coin stack for Portfolio – also everywhere else the portfolio is meant («Add to portfolio»,
  empty portfolio, portfolio alarms, widget help); Market and Settings unchanged.
* No welcome dialog: a new install goes straight to the starter selection;
  «What the app can do» under Settings › About.
* Settings, calmer main page: General (Language on Android, Currency, Watchlist,
  Portfolio), Appearance (Mode light/dark, Theme accent colour), Alarms & notifications
  (Alarms, Market alerts), Data (Backup & restore), one row «Advanced» (Updates, Price
  colours, % change, Coin logos, Widgets, Speech output), About, and
  «Reset app» in its own card at the very bottom. The search (ignores accents and case,
  jumps to the item and highlights it) still finds every item; those on the «Advanced»
  page show the breadcrumb «Advanced › …».
* The period next to the % change («24h», «today», «last update») is hidden by default;
  Settings › Advanced › % change › «Show period next to the change» brings it back (watchlist row,
  pulse line, single-coin widget, iOS Live Activity). Screen readers always say it.
* Settings › Advanced › «Widgets» and Settings › About › «Licences» open as normal pages like every other row (no sheet
  any more); links to a website (privacy policy, request an exchange, source code) show an
  «open externally» arrow instead of «›», and screen readers add «opens in the browser».
* Settings: every single-choice page looks the same – a list where the chosen row is softly
  tinted in the accent colour with a check mark (mode, theme, price colours, % change basis,
  language, currency). Theme as a list with colour dots; currency as a list with search and
  the currency name in the app language (CHF Swiss franc); Android: language on its own page
  with search (iOS keeps the language in the iOS Settings app, as Apple intends).
* «Favorites» chip next to «All»: shows the favorites; a pair stays in its own group and
  appears there as soon as it is a favorite. Also selectable for the watchlist widget. Shown
  only once there is a favorite, the «+» for groups only once there is a group (the first one
  is created from a pair's action sheet); with neither, the chip row stays empty. Translated
  in every language (was a fixed «FAV»).
* Settings › Watchlist › «Show names» (off by default): name under the pair, e.g. Bitcoin or
  NVIDIA, from the same lists as the logos (no extra request); TradFi pairs never get the name
  of a crypto token with the same ticker.
* Pair search understands exchange symbols: «BTCUSDT», «BTCUSDT Qtly 1225»,
  «BTCUSDT_261225», «BTCUSD_PERP», words like «Quartal»/«Perp»; order spot, perpetual,
  then quarterly contracts (nearest first). Coin names and exchanges work too (see
  «Search by coin name» above).
* Futures on stocks, commodities, FX and pre-IPO companies (TradFi perpetuals) at
  Binance, Bybit, OKX, MEXC and Bitget, behind a switch under Settings › Watchlist
  (together with dated futures). New pairs without a chosen group go to
  «TradFi» or, for dated futures, «QTLY».
* Basis of the % change like Binance: last 24 h, since the last update, since 00:00 in
  the device time zone (with summer time) or since 00:00 in a fixed zone UTC+14 … UTC-12.
  Like Binance’s «Change(%) & Chart Timezone», the chosen zone also sets the times in the
  charts. «Since last update» compares the latest price with the previous one (watchlist,
  action sheet, widgets; close to 0 % with live prices); portfolio stays on 24 h.
* Settings › Advanced › Updates (Android): battery usage moved to the bottom of the page.
* Android app shortcuts: Add pair, All alarms, Market.
* Questions, wishes and exchange requests via GitHub issues (link under About).
* Developer options (7 taps on the version) now show a countdown «N more taps»
  for the last 3 taps on Android and iOS.

**Design & accessibility**
* Screen reader (TalkBack/VoiceOver): watchlist rows say the freshness in words («Price
  outdated, last updated 45 minutes ago», «exchange unreachable», «no longer traded»),
  fiat currencies as words, change with period; «Alarm triggered» for 15 minutes after an
  alarm on the pair fired; the status pill announces only changes between up to date /
  outdated / offline; action sheet price and widgets include the stale state (A11ySpeech).
* Prices from 10 upwards always show two decimals, also large ones («83,142.00»
  instead of «83,142») — watchlist, sheets, widgets, alarms; below 10 up to four
  significant digits as before.
* Watchlist: the price no longer jumps below the «USDT» after an update (rolling
  digits keep the baseline of the whole number).
* New app icon (bell with a rising line) and five themes: Orange, Red, Blue,
  Green and Marrs Green, each with a matching icon. iOS: light, dark and tinted
  icons from the asset catalog.
* Price colours green/red, olive/magenta or blue/orange, «Swap colours» (red = rising), high
  contrast mode; amounts in Rubik with equal-width digits.
* TalkBack and VoiceOver read every row, chart and widget as one sentence;
  Dynamic Type on iOS; Reduce Motion and right-to-left languages respected.
* 31 languages (Brazilian Portuguese added) with correct plural forms; readable
  width on Android tablets.
* Developer shown as r1AD.

**Reliability & performance**
* Android: the live service starts only from the visible app (not at boot),
  stops cleanly at the Android 15 time limit, and the background job pauses
  while it runs. Widgets are not redrawn per live tick while the screen is off.
* Fixed: Kraken symbols lost a leading X/Z (XTZ shown as «TZ»).
* iOS: alarms are notified only after saving, «60,000» is read correctly, the
  widget no longer checks alarms.
* Fewer requests: bulk tickers for the watched pairs only, Coinbase one request
  per pair; targeted widget redraws, chart image cache.
* Battery-optimisation hint only after the first alarm; delete everywhere with Undo.
* Android: if the «Update frequently» service was killed, the regular background
  job restarts it.
* Android: the boot receiver listens only to the protected system broadcasts
  «boot completed» and «app updated» (no more `QUICKBOOT_POWERON`, an unprotected
  manufacturer action any app could send) and only reschedules the background update.
* iOS futures data: an empty Bybit answer (no funding rate and no open interest)
  moves on to the next source like on Android, so funding and open-interest alarms
  no longer stay silent.

**Tests & CI**
* Shared test cases for Android and iOS (`android/testdata/parity`): alarms,
  funding/open-interest alarms, threshold input, % change basis (incl. daylight
  saving), «no longer traded» (incl. the single-request check), 24 h change (incl. USD → quote rate), outdated,
  live feed, market notices, spot market data (`spot_market.json`), the «Why?»
  analysis (`why_analysis.json`: thresholds, broad market, open-interest samples,
  funding trend, stablecoin peg, sources); pair and
  watchlist search by name (`PairSearchTest`, `WatchSearchTest`,
  `ExplorerPairSearchTests`).
* Tests for this round's rules on both platforms: stablecoins without a price
  (`PortfolioStablesTest` / `PortfolioNumbersTests`), the candle identity check
  (`PricePlausibilityTest` / `PortfolioNumbersTests`), the alarm timing hint
  (`AlarmTimingHintTest` / `AlarmLogicTests`), the portfolio default
  (`PortfolioDefaultTest` / `SettingsLogicTests`), logo retries (`CoinLogoRetryTest` /
  `CoinLogosTests`) and the widget divider (`WidgetContrastTest`).
* iOS test target `CryptoCheckerTests`; Android instrumented core-flow test
  (`CoreFlowTest`, compiled in CI, run on an emulator by «Android device tests»).
* Workflows: Android unit tests and build, translation check, iOS build and
  tests (manual), Android device tests on an emulator (manual), weekly
  economic-calendar update.

### Repository
* Complete source code of the Android and iOS apps published under the MIT
  License (`android/`, `ios/`); exchange guide moved to `DEVELOPMENT.md`.
* GitHub Actions: Android unit tests and debug build, translation check,
  iOS simulator build (manual), Android instrumented tests on an emulator
  (`android-device-tests.yml`, manual).
* One shared change display (coloured text), switch row, loading placeholder and colour tokens on
  Android and iOS; iOS no longer redraws every tab when live prices are saved.
  All changes follow one rule (`ChangePill` / `TonePill` / `PillTone`; the names
  stayed, they draw coloured text only), prices with
  their quote currency use `PriceWithQuote`.
* Exchange contract tests also check pair lists (case type «pairs»: OKX swap
  instruments, Bybit pages).
* `ios/project.yml` carries the same build number as the generated project;
  `gen_xcodeproj.py` fails if the two differ.
* CI: lint and unsigned release build as gates, dependency review on pull
  requests, Dependabot; iOS build and tests run automatically on iOS changes.
* Store texts, screenshot captions and app texts in all 31 languages
  reviewed for natural wording and consistent terms.
* Code split by responsibility, same file names on Android and iOS, no
  behaviour change: watchlist, action sheet and chart, add pair, market cards,
  settings, alarms, portfolio, widgets, price refresh (`PriceFetcher`,
  `DayReferences`, `RefreshEffects`), iOS `AppData` extensions. ViewModels stay
  one class per screen.

### Exchange library
* 15 new exchanges: WOO X (spot, futures), Deribit (futures), Phemex (spot,
  futures), Poloniex, One Trading, Upbit, Bithumb, bitFlyer, BtcTurk, Bitso,
  Indodax, ZebPay, Independent Reserve, LATOKEN, NonKYC.
* Now 40 markets on 32 exchanges plus DexScreener.
* Combined pair lists (LATOKEN, Independent Reserve) parsed in one request.
* Swift versions of all adapters for the iOS app.

### Privacy policy
* Build 35: summary states that portfolio amounts never leave the device (only coin
  symbols go to Binance / CoinGecko); fee and exchange listed among the stored portfolio
  data; «How current?» and the WebSocket / foreground-service wording; «indicators»
  instead of «cycle»; dated 13 October 2026.
* Lists all exchanges and data providers, exchange-rate sources (Frankfurter,
  ExchangeRate-API), network-fee providers, portfolio lock and portfolio widget.
* Also covers notes, portfolio transactions and cached market data stored on
  the device, and questions via GitHub issues.
* The apps contain no payment or donation feature.
* Coin logos: all logos of the largest coins downloaded at once from CoinGecko’s
  image servers, gaps (gold, silver, stocks) from Binance’s public symbol list
  (never one by one), shown from the device; one switch each for
  app, portfolio and widgets (all off = no requests).
* Recent searches on Add pair are stored only on the device (not in the app's
  backup file or the Android system backup); the quick filters on «All …-pairs»
  make one bulk request to the chosen exchange, at most once a minute, with no
  personal data.
* «Market data» card: the Spot tab reads the hourly top-40 mirror (`coins40.txt`,
  release «data»); only for a coin not in it (or with the mirror unreachable or
  older than 12 h) one request to CoinGecko `/coins/markets` with the coin's symbol
  (CoinGecko can see which coin is viewed; no key, no watchlist, at most once per
  symbol per 10 minutes, kept in memory only). The Futures tab of a spot pair uses
  the existing Binance Futures / Bybit / OKX requests, only when opened.
* Backup encryption: 600,000 PBKDF2 iterations for new backups, older files still
  readable, warning for weak passwords; iOS portfolio widget caches (hourly prices,
  history, candle attempts) are files always excluded from device/iCloud backups.
* OKX pair list from `okx.com` public instruments (same host as the prices); a
  missing pair is checked once with a single price request to the same exchange.
* System backup made precise: Android's main database zeroes deleted content and,
  after an update from database 12, keeps retrying to compact away the old
  portfolio tables; on iOS the watchlist file is excluded from the device backup
  while it still holds portfolio alarm thresholds and «Portfolio in system backup»
  is off.
* «Why?» and futures requests listed: funding history (last 4 settlements of the
  coin's USDT perpetual from Binance Futures, Bybit or OKX, the pair's own exchange
  first; at most once per pair every 10 minutes, only when the sheet opens), ETHUSDT
  hourly candles as a second market reference (the same for every coin), 100 instead
  of 30 hourly candles for the coin, and `coins40.txt` from the GitHub data mirror
  for the broad market (nothing coin-specific). Futures data per exchange goes only
  to the exchange whose pair you watch (WOO X, Bitget, MEXC, HTX, Hyperliquid,
  Deribit endpoints listed). The chart's fiat fallback uses the Frankfurter time
  series already used by the portfolio.
* Portfolio: the last fetched price per coin is stored on the device with its time, so
  the last state shows offline; never in a backup (Android no-backup folder, iOS excluded
  from device/iCloud backup), deleted with «Clear portfolio».
* Coin logos: a sync that failed without internet is repeated when the connection returns
  (with a growing pause, at most every 15 minutes) or on return to the app – the same
  requests; settings paths updated to the new «Advanced»
  page (coin logos, live prices). The portfolio's candle price check compares with the
  watchlist on the device only and adds no request.
