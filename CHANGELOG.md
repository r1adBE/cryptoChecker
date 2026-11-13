# Changelog

## 16.2.2 – first public release

* Last price straight from the exchange that lists the pair – 82 markets: 78 on 69 exchanges (spot and futures), DEX pools via DexScreener and the reference rates of three central banks (ECB, National Bank of Ukraine, National Bank of Poland); live fiat pairs of Kraken and Bitstamp under «Forex».
* Alerts run locally on the device when the app checks prices (with the app closed, often only after hours) – no account, no server.
* Home-screen widgets for the watchlist, single pairs, the portfolio and the market pulse; without a connection they keep the last prices and say so.
* Watch apps for Wear OS and Apple Watch with complications (price of the first coin, optionally the portfolio value): prices of your favourite coins from the phone or straight from the exchange; on Android an optional price overlay above other apps.
* «What is moving with it?»: a checklist that puts a price move in context (market, Bitcoin and other factors).
* Optional portfolio (off by default): own transactions, CSV import, watched addresses (counted once: only what they hold beyond your own entries in the same wallet) and tax worksheets.
* Watchlist, alerts and portfolio are stored encrypted on the device.
* Backups with a password (AES-256-GCM; optional without portfolio data, always required once the file contains portfolio data), interchangeable between Android and iPhone.
* 75 languages.
* Full source code of both apps under the MIT license.
* No tracking, no ads, no analytics.

Detailed build history below.

## 16.2.2 (build 232)

Coin logos: a short disturbance while loading (no answer, 408, 5xx) no longer blocks a logo for a day – it is retried in the same run and otherwise on the next sync; Android keeps the logos in app storage instead of the cache the system may clear.

## 16.2.2 (build 231)

iOS UI tests: a Dynamic Type finding counts only when a second pass on the same screen confirms it; on main and release tags a UI test may be retried once, and a retry counts as failed unless the first failure was a Simulator hiccup (launch, terminate, UI query timeout, time allowance).

## 16.2.2 (build 230)

Back up: other apps' overlay windows are hidden while the backup password dialog is open (Android 12+), as for restore.

## 16.2.2 (build 229)

* CI mutation self-test uses owner-only file mode (CodeQL alert #4).
* iOS UI tests: the Simulator is booted before the run, and a single test is stopped after 10 min (step limit 50 min), so a hung app launch can no longer use up the whole step (build 228).

## 16.2.2 (build 228)

Start profile re-measured with the dedicated startup journey.

## 16.2.2 (build 227)

Last fixes from the audit of build 225, no visible change:

* Supply chain: keys that were both ignored and trusted are cleaned up, so their signatures are checked again; the check rejects such entries.
* CI: device tests are a required check through a collector (skipped = green, failed, hung or never started = red), a hard time limit around the Gradle call, documentation-only pull requests skip the heavy jobs, dependency verification errors are annotated.
* Guards: watch string keys are checked against the full catalogue, test counts per module, honest dependency count for the price refresher.
* The startup profile gets its own short journey.

## 16.2.2 (build 226)

Fixes from the audit of build 225:

* A retired exchange (Bitpanda from 1 February 2027) is shown the same everywhere: no price, "—" with the reason, in the app, widgets and on the watch, also offline. From 1 January 2027 a notice announces the date.
* Portfolio coins booked on a retired exchange are valued with Binance and say so.
* Retired pairs no longer count as failed checks; the portfolio fallback skips them; the pair page offers "Change exchange".
* Restore: the result names a turned-off portfolio lock and a replaced server with its host name; known exchange names come from the catalogue.
* CSV import blocks only rows that really lose a price or fee and says why next to the button.
* Android: backup and restore show progress and lock the buttons. A portfolio alert opens the alert list with the alert highlighted.
* Watch complications: a pair without a price shows "—" with the reason on Wear OS; the Apple Watch empty state matches Wear OS.

## 16.2.2 (build 225)

* Bitpanda ends its old public price service on 31 January 2027. From 1 February 2027 the app no longer asks Bitpanda for prices: Bitpanda pairs stay in the watchlist greyed out with the reason, their alerts rest, Bitpanda is no longer offered when adding a pair, and a notice (with ✕) suggests another exchange. Widgets and watch show the same reason. CSV files from Bitpanda can still be imported.
* Restore: when the app storage cannot be written, a specific message says so and that your data are unchanged.
* Translations proofread in 15 languages.

## 16.2.2 (build 224)

* Android: the baseline and startup profile are measured again on the current code (classes moved in builds 216 and 223).
* iOS: size ceilings in the release audit from the first measurement (app 140 MB, widgets 40 MB, watch 4.5 MB).

## 16.2.2 (build 223)

Last fixes from the audit of build 220, no visible change:

* Apple Watch app and complications carry only their own texts instead of the whole catalogue (smaller watch app).
* Alarm check and price refresh split into named steps on both platforms; backup, logo, chart and alarm classes in their own packages.
* Guards: hard-wired dispatchers in every module, class size across Swift extensions, logic function length.
* CI: Android checks in three parallel jobs, device tests already on pull requests, screenshots by hand and on tags.

## 16.2.2 (build 222)

More fixes from the audit of build 220:

* Backup: the two actions are called "Back up" and "Restore" everywhere; export and import are the CSV words. The buttons are stacked at full width.
* Restore: a backup that sets an own address server, turns on the system backup of the portfolio or shows amounts again says so; an interrupted restore always ends in the old state on both platforms.
* Watch apps: a pair without a price shows the reason, the list mode is a switch, Wear OS shows the scroll position; the Apple Watch portfolio complication is marked privacy sensitive.
* Android: "Check on time" also runs for portfolio, gas and depeg alerts without active pairs.
* Deleting a cost-basis lot of an address asks first; the coin page's transaction sheet and the lot editor survive rotation.
* iOS widgets handle the tinted home screen; "Reduce motion" is respected in "Add pair".
* Code scanning runs the extended security queries.

## 16.2.2 (build 221)

Fixes from the audit of build 220:

* Android: a portfolio alert never shows an amount or threshold in the notification, whatever the lock-screen setting (as on iOS).
* iOS: alerts restored from a backup over existing data are armed again.
* CSV import: the day of the daily rate follows the time zone of the selected tax template; rows without a daily rate block the import until you retry or confirm, and get a permanent note.
* A backup that contains portfolio alerts always needs a password.
* Store description: only Swissquote is "CSV only". Privacy page: the watchlist is backed up with its groups and notes.
* Tests declare the files they read, so they run again when fixtures or Swift sources change.

**Technical notes**

* Build 221 (audit of 220: S-01, S-03, S-05, F-01, F-02, F-03, U-01, C-01): `PortfolioAlarmNotice.kt`, `SharedStorage.replaceFiles`, `CsvImportParser.fxZoneFor`, rate gate in the CSV preview, `gradle/test-inputs.gradle.kts` + `TestInputsGuardTest`, `PROPERTY_RE` without backtracking.

## 16.2.2 (build 220)

* Code scanning (CodeQL) runs as its own workflow: Kotlin/Java compiled once with dependency verification on; Swift, Python and the workflows as before.

**Technical notes**

* Build 220: `codeql.yml` (advanced setup) replaces GitHub's default CodeQL setup, whose autobuild of all variants ran out of memory.

## 16.2.2 (build 219)

* Documents up to date: README, SECURITY, CONTRIBUTING, DEVELOPMENT, notices and templates match the app.
* Store descriptions: 82 markets on 69 exchanges and at central banks; widgets line names the pulse widget and the watch.
* Privacy page: a backup that contains portfolio data always needs a password.
* Android licences sheet lists Google Play services Wearable.

**Technical notes**

* Build 219: documents only (section 20 added), store descriptions, privacy page sentence on the backup password, licences sheet.

## 16.2.2 (build 218)

* No visible change: the tax year summary, FIFO, average cost and backup restore calculations are split into named steps on Android and iOS; the largest view model and the classes with the most dependencies are smaller; function length is now measured with a ceiling.

**Technical notes**

* Build 218 (audit C-02): `code_metrics.py` measures function length (ceilings Kotlin 326, Swift 356); `TaxYearSummaryBuilder`, `PortfolioFifoBook`, `ExplorerSearchData`; constructor ceiling 13, ViewModel properties 26; dispatcher allowlist 5 files.

## 16.2.2 (build 217)

* Android, no visible change: 208 test files moved into the modules they test (domain and data layer); the number of tests is unchanged and a guard keeps it from dropping.

**Technical notes**

* Build 217 (audit C-03): unit tests of `:domain` and `:data` live in those modules; `LayerBoundaryTest` checks package, imports and minimum counts; `mutation_test.py` routes each test to its module.

## 16.2.2 (build 216)

* Android, no visible change: clean layer boundaries. Every file now lives in the package of its module, the database rows for watchlist entries and alarms moved into the data layer, and the domain layer no longer depends on Room or OkHttp. A test keeps it that way.

**Technical notes**

* Build 216 (audit C-01, layers; no behaviour change): package = module on Android. Every file of `:domain` is in
  `com.cryptochecker.app.domain.…` (39 files moved: `util` → `domain/util`, `settings` → `domain/settings`, `widget` →
  `domain/widget`, `notification` → `domain/notification`, `data/…` → `domain/…` with the same tail, the models
  `WatchEntity`/`AlarmEntity` → `domain/model`); every file of `:data` is in `com.cryptochecker.app.data.…` (16 moved:
  `settings` → `data/settings`, `widget` → `data/widget`, `util` → `data/util`, `notification` → `data/notification`,
  `domain/refresh/AlarmLogStore` → `data/refresh`, `domain/alarm/DerivativesAlarmData` → `data/alarm`); the 9 flows of
  `:app` that sat in `com.cryptochecker.app.domain` are in `app/…/activity`, `alarm`, `diagnostics`, `gas`, `refresh`.
  Not moved: everything named in the manifest or persisted by class name (workers, receivers, services, activities,
  widget providers, `SystemBackupAgent`) and `AppDatabase`/`PortfolioDatabase` (schema directory = class name) – none
  of them was in a wrong package. `:domain` has no Room and no OkHttp: `WatchEntity`/`AlarmEntity` are plain data
  classes, the tables are `WatchRow`/`AlarmRow` in `:data` (`data/local/model`, same columns, order, defaults, index
  and foreign key – schema 1 and its identity hash unchanged) with `toDomain()`/`toRow()`; `WatchDao` keeps its
  functions for the models (default functions that map) next to the Room functions named `…Row(s)`.
  `LogoUrlPolicy` decides without a parser library (plus OkHttp's limits: label ≤ 63, host ≤ 253 characters);
  `LogoUrlPolicyTest` compares it with OkHttp. `domain/gradle.lockfile` lost Room, SQLite, OkHttp, Okio and
  `startup-runtime`; the other locked versions stay until the next «Supply chain update». `LayerBoundaryTest` keeps
  the rule: package and folder of every `:domain`/`:data` file, no import of `androidx.room`, `okhttp3` or another
  app package in `:domain`, only `domain`/`data` imports from the app in `:data`, no `…app.domain` package in `:app`.
  The committed baseline and startup profiles carry the new class names (renamed in place, not measured again).

## 16.2.2 (build 215)

* Android: measured baseline and start-up profile (generated on an API 34 emulator, 12,601 rules for the app) packaged with the app – faster first start and smoother first scrolling after installation.

**Technical notes**

* Build 215: generated `baseline-prof.txt` and `startup-prof.txt` committed under `app/src/release/generated/baselineProfiles/`.

## 16.2.2 (build 214)

* Android, internal: 40 classes get their coroutine dispatcher through Hilt (`@IoDispatcher`, `@DefaultDispatcher`) instead of a hard-wired one; a guard test keeps the remaining count from growing. No behaviour change.

**Technical notes**

* Build 214: `DispatcherQualifiers` (:domain) + `DispatcherModule` (:app); `DispatcherInjectionGuardTest` with the
  list of remaining files.

## 16.2.2 (build 213)

* New workflow «Baseline profile»: generates a measured start-up profile on an emulator; the result is committed and packaged with the app (the hand-written profile stays as fallback).

**Technical notes**

* Build 213: `baseline-profile.yml` + generator journey (start, watchlist, pair page, tabs); `baselineProfile { saveInSrc }`.

## 16.2.2 (build 212)

* Dependency update: Android Gradle Plugin 9.5.1, Gradle 9.9.1, KSP 2.3.13, lifecycle 2.11.0, datastore 1.3.0, OkHttp 5.5.1, SQLCipher 4.20.0, kotlinx-serialization 1.12.0, wear-compose 1.7.1, play-services-wearable 20.0.1; checksums, signatures and lock files regenerated.

**Technical notes**

* Build 212: dependency update through «Supply chain update» (verification metadata, keyring, lock files).

## 16.2.2 (build 211)

* A backup that contains portfolio data always needs a password.
* Android hides windows of other apps over the portfolio, restore and reset dialogs (Android 12+, new permission HIDE_OVERLAY_WINDOWS).
* Watchlist: «Symbols explained» in the menu; notes stay on one line. Alert editor: one sentence and «More» instead of a text block. The «alerts may be late» warning appears only after hours of failed background runs. The portfolio «+» button hides while scrolling down.
* Store screenshots: the tour fails when a system dialog covers the app; demo values no longer depend on the time of day.
* Privacy page with a strict content security policy; SECURITY.md lists known limits; index on the import batch (reinstall needed for test builds).

**Technical notes**

* Build 211: audit fixes: forced backup password with portfolio data, `HideOverlaysEffect`, `network_security_config`,
  watchlist legend, alert editor hint, late-check rule by duration; screenshots guarded by
  `android_emulator_idle.sh` and a focus check in the tour; deterministic demo candles.

## 16.2.2 (build 210)

* iOS: portfolio alerts always show only the neutral sentence – no amount on the lock screen, even with previews set to «Always». The details are in the app.

**Technical notes**

* Build 210: iOS `PortfolioAlarmNotice.content()` always neutral (no amount in any notification field).

## 16.2.2 (build 209)

* Deleting a transaction by swiping asks first, like the editor.
* Portfolio alerts never show amounts on the lock screen.
* «Check on time» and the iOS widgets follow the chosen update interval; on Android no double background run while the exact check is set (iOS widgets still fetch on their own, as widgets cannot share the app's runs).
* Confirmations before changing the cost method and before turning on «Portfolio in system backup»; the tax worksheet says what the file contains before sharing.
* CSV import preview lists every skipped row group with «+ N more»; the lock setting shows its real state when the portfolio tab is hidden; Apple Watch small complications show the age of old values; long amounts shrink on Android instead of being cut; larger iOS widget buttons.

**Technical notes**

* Build 209: audit fixes (exact cadence = interval with WorkManager dedupe, private portfolio notifications, swipe
  confirmation, settings confirmations; parity `csv_line_list`, `portfolio_lock_row`, `exact_cadence`, `settings_confirm`).

## 16.2.2 (build 208)

* Watch complications (Wear OS and Apple Watch): the small slot shows the price (e.g. 84'450), the long one «BTC • 84'450.00 USDT +1.20%»; the 24 h change is still spoken.
* Portfolio complication: while the phone switch is off it says «Portfolio · turn on on the phone» instead of staying empty.

**Technical notes**

* Build 208: `WatchFormat.compactPrice` (≤ 7 characters, parity `watch_price.json`); portfolio complication hint
  instead of NoData.

## 16.2.2 (build 207)

* Watchlist: after the first logo load, «2 coins without a logo» with a close button; a tap lists them. Closing hides it until another coin without a logo appears; it disappears by itself when the logos arrive.

**Technical notes**

* Build 207: `MissingLogoNotice` (badge rule, dismissed set, hidden while loading or with logos off; parity
  `missing_logos.json`).

## 16.2.2 (build 206)

* The hint «Loading logos» during the first logo load shows the progress in percent (at most 99 % while running).

**Technical notes**

* Build 206: `LogoLoadingHint` percent (done / to load in the first-load pass, floor, ≤ 99 %, throttled; parity
  `coin_logos.json › loadingPercent`); loading itself unchanged.

## 16.2.2 (build 205)

* Widgets (Android and iOS) show «No connection · as of 14:05» when the device is offline; the last prices stay, and the widget updates by itself when the connection is back.
* Android list widget: after a restart only the loader at the bottom is shown, the refresh icon appears with the prices.

**Technical notes**

* Build 205: `WidgetStatusRule` (offline > outdated > time, parity `widget_status.json`), `WidgetOffline`, one-time
  `WidgetReconnectWorker` with a network constraint (Android); iOS widget asks for an early reload while offline.

## 16.2.2 (build 204)

* Android widgets: below the grey placeholder rows a small spinner with «Loading prices…» until the app has drawn them.

**Technical notes**

* Build 204: `widget_skeleton_loading` (system ProgressBar + `starter_loading_prices`) inside the skeleton group.

## 16.2.2 (build 203)

* Android widgets: after a phone restart, until the app has drawn them, they show the system light/dark background with grey placeholder rows instead of a white area.

**Technical notes**

* Build 203: widget initial layouts with day/night `colors_widget_initial` and a static skeleton; `WidgetToolkit.background`
  hides it on every draw (WidgetInitialLayoutTest).

## 16.2.2 (build 202)

* Watched addresses: the hint about a transfer into another wallet now has a «Change target» button; after a short confirmation the transfer points to the address's wallet and nothing counts twice.

**Technical notes**

* Build 202: `AddressWithdrawalMatch.retarget` changes the named transfer's target to the address wallet (confirmation, normal
  save path); hint button on the address row and the coin card.

## 16.2.2 (build 201)

* Forex in two groups: «Live (exchange)» with real fiat pairs of Kraken and Bitstamp (alerts, live stream) and reference rates.
* Reference rates for practically every currency: ECB first, then the National Bank of Ukraine (daily) and the National Bank of Poland (weekly), each against USD and EUR. Every reference rate names its source, «converted» when the value is a cross rate or a computed change.
* GitHub Actions: gradle/actions 6.4.1, artifacts kept 14 days.

**Technical notes**

* Build 201: `ReferenceRates` picks one source per currency (ECB > NBU > NBP table B), cross rates from the same publication;
  `NbuReference`/`NbpReference` cached until the next publication; forex chip with live fiat pairs (Kraken, Bitstamp).

## 16.2.2 (build 200)

* Forex reference rates and fiat conversion come straight from the European Central Bank (daily file, 90-day file, ECB Data Portal for older dates) – the third-party mirror is gone. Rates and pairs stay the same.

**Technical notes**

* Build 200: Frankfurter replaced by the ECB: `eurofxref-daily.xml` (conversion), `eurofxref-hist-90d.xml` (forex pairs), ECB
  Data Portal for history with the 90-day and full-history files as fallback; cross rates as before (5 significant digits).

## 16.2.2 (build 199)

* Watched addresses subtract only what you recorded in the address's own wallet – an exchange balance plus a separate cold wallet now add up (1 + 1 = 2).
* Hint when a recorded transfer went to another wallet than the watched address: set the transfer's target to this address, otherwise it counts twice.
* Disabled buttons and texts are grey with at least 3:1 contrast in light and dark mode.
* Macro calendar: below the last known date of a series, «More dates follow once the source publishes them».

**Technical notes**

* Build 199: `AddressSurplus` per wallet (address subtracts only own holdings of its wallet); `AddressWithdrawalMatch` (±1 %)
  names the transfer target wallet; disabled colours ≥ 3:1 (contrast script covers them); macro calendar «more dates follow».

## 16.2.2 (build 198)

* Watched addresses that count in the portfolio add only what they hold beyond your own transactions, per coin (e.g. staking rewards or an airdrop) – nothing counts twice in the total, alerts, widgets, the watch and the Swiss wealth-tax cutoff.
* The warning «probably counts twice» for addresses is gone, it no longer applies.
* Public changelog header: 80 markets on 69 exchanges.

**Technical notes**

* Build 198: `AddressSurplus` (Android/iOS): counting addresses add max(0, address part − own holdings) per coin, own holdings
  without recorded inflows to the address wallets; overlap warning `portfolio_address_overlap_tx` removed.

## 16.2.2 (build 197)

* Fiat charts load the USDT/USD daily closes themselves (Kraken, gaps from Coinbase); if the history is missing they use one rate for the whole chart and say so, instead of drawing a step that was never measured.
* A real USDT crash below 0.50 is kept in the daily closes (band 0.05–3.0, like the live rate).
* Watch: «only from the phone» when the asked exchanges do not list the pair, «exchanges not reachable right now» when they did not answer.

**Technical notes**

* Build 197: chart loads `usdtUsdDaily`, never mixes measured and assumed USDT/USD (one rate + note); daily close band 0.05–3.0;
  24 h value same rule; watch remembers the fetch outcome per pair (not listed vs. not reachable; HTTP 400 = not listed).

## 16.2.2 (build 196)

* Depeg alarm: besides USDT it now watches USDC (Kraken USDC/USD and the Binance cross rate), and further stablecoins you hold or watch (e.g. DAI, FDUSD, PYUSD) when two exchanges quote them. The settings show which stablecoins are watched; the notification names the coin.

**Technical notes**

* Build 196: `StableDepeg` checks USDC (Kraken + cross rate) and held/watched stables (stored pair lists, two sources required);
  per-stable state, one switch; iOS `StableDepegPlanner` at the three call sites.

## 16.2.2 (build 195)

* Charts in a fiat currency convert each day with that day's USDT/USD close, so a USDT depeg shows in the history; «USDT = 1 assumed» appears whenever a day had to be approximated.
* Watch: for pairs it cannot fetch itself (e.g. Kraken spot) the screen reader says the watch gets them only from the phone.

**Technical notes**

* Build 195: SheetChart/WatchSheetChart and sparkline use USDT/USD per day (cached portfolio closes, live today); note per approximated day;
  `DayChange.fromUsdtSeries` with USDT/USD at both ends; watch reason «only from the phone» for venues it cannot query.

## 16.2.2 (build 194)

* Watch: normal spot pairs (e.g. Binance BTC/USDT) get their price again – from the phone or, without the phone, from the exchange; only DexScreener pools are pools, and two pools with the same pair stay apart.
* Watch: perps of exchanges the watch cannot ask itself show only the phone's price; the complication names the actual source.
* iOS: starring a coin in the market no longer removes stars set in the pair search.
* Charts in a fiat currency use the measured USDT/USD rate; «USDT = 1» appears only when it is an assumption.
* Wear OS: changing the price source reloads at once. Watch hint text updated.

**Technical notes**

* Build 194: `WatchPair.contractOf` → Pool only for DexScreener (pool id in the key); spot = contract NONE; iOS coin favourites
  through AppData; watch self-fetch only for venues it can query; SheetChart uses USDT/USD; Wear reload on source change.

## 16.2.2 (build 193)

* Watch: choosing «Like phone» on the phone now sticks on the watch, whichever side changed last.
* Watch: favourites show the phone's exact pair and exchange, including futures (e.g. Binance Futures · BANK Perp); when the watch fetches itself it asks that exchange first.

**Technical notes**

* Build 193: watch choice revision = max(local, other side) + 1 before a change; phone sends each favourite as pair (exchange,
  contract) with price (`watch_pairs.json`); watch self-fetch from the same exchange/contract first (Binance USDⓈ-M, Bybit linear, OKX swap).

## 16.2.2 (build 192)

* Watchlist: right after installing, a calm line «Loading logos …» explains the letter circles; it disappears once the first logo load is done.

**Technical notes**

* Build 192: `LogoLoadingHint` flag (pending images in the first logo run; `.first_load_done` in the logo folder).

## 16.2.2 (build 191)

* Watch (Wear OS and Apple Watch): with the phone nearby the watch shows the phone's prices and follows its update interval (15–60 min); without the phone it fetches prices itself as before. Choose «prices from the phone, otherwise itself» or «always fetch itself» in the settings.
* Watch: optional portfolio total and today's change, in the watch app and as a complication (off by default; hidden amounts show «•••»).

**Technical notes**

* Build 191: watch prices from the phone (Data Layer / WatchConnectivity, at most once a minute), `WatchInterval` clamp 15–60 min,
  source setting, opt-in portfolio total + complication (hidden amounts and portfolio lock → «•••»).

## 16.2.2 (build 190)

* Live prices: a quiet pair on a running live stream no longer turns «outdated» after two minutes; freshness follows the last live tick.
* Wear OS: the short complication shows the age of an old price; the watch price fetch has a total time limit.
* Macro calendar: PCE dates for the whole year.

**Technical notes**

* Build 190: `LiveStatus.freshnessTime` (last tick of a healthy stream); Wear SHORT_TEXT age, `WatchPriceSource` 20 s deadline and
  cancellation; BEA full-year PCE schedule; Mercado Bitcoin/LATOKEN counted as blocked in the live check; APK size gate 32 MB (language splits).

## 16.2.2 (build 189)

* Exchange logos and current logos of the 100 largest coins are built into the app: visible at once, no download. The old icon pack is gone, so the app is smaller (Android about 0.5 MB, iOS about 1.3 MB).
* Licences: names and logos are trademarks of their owners.

**Technical notes**

* Build 189: `exchange_logos.bin` and `coin_logos.bin` (top 100) generated by `android/tools/gen_logo_packs.py` with manifest;
  CC0 pack removed; runtime exchange-logo download of 188 removed; exchange logos independent of the logo switch.

## 16.2.2 (build 188)

* Watchlist: a healthy row shows just the exchange; the age appears only when the last trade is clearly older than the update (the header already shows the time).
* Add pair: real exchange logos (futures show their parent exchange), no more «Popular» section.
* OKX TR removed – it only repeated the TRY pairs that OKX already lists (now 80 markets on 69 exchanges).

**Technical notes**

* Build 188: row age only for an old last trade (`PriceFreshness`, parity `rowTradeAge`); exchange logos from the
  CoinGecko exchange lists (further pages only while gaps remain, 1-day retry), HashKey id unified; «Beliebt» removed; OKX TR removed.

## 16.2.2 (build 187)

* 24 new exchanges: Bitpanda, Bitkub, Binance TH, Binance TR, Coins.ph, Coinone, WhiteBIT, CoinSpot, MAX, BitoPro, Foxbit, Bit2Me, VALR, WazirX, OKX TR, Quidax, Buda, Swyftx, Tokocrypto, Upbit Thailand, Upbit Indonesia, NDAX, CoinJar, GMO Coin, BitOasis – now 81 markets on 70 exchanges.
* An exchange that blocks your region shows «blocked in this region» instead of «not reachable».

**Technical notes**

* Build 187: 24 exchanges added (adapters, fixtures, recordings, privacy hosts); HTTP 451 shown as region-blocked;
  Binance TR and Tokocrypto built from docs/ccxt, not reachable from the build environment (owner tests via VPN).

## 16.2.2 (build 186)

* Add pair, redesigned: search on top, chips All · Spot · Futures · DEX · Forex below. Without search text you see popular pairs, recently used exchanges and all exchanges of the chosen kind; a coin shows the exchanges that list it. The «Advanced / by exchange» section is gone.

**Technical notes**

* Build 186: add-pair overview with market chips (All/Spot/Futures/DEX/Forex), popular pairs, recent and all exchanges;
  accordion and step UI removed; no extra price requests (privacy); shared overview cases for both platforms.

## 16.2.2 (build 185)

* Coin logos appear much faster after installing: Binance logos come first, several images load at once, in the same order for everyone. Until a logo is there, a coloured circle with the initials stands in; the logo fades in (no fade with Reduce Motion). Also in the widgets.
* Market data: the spot card picks the CoinGecko entry by name; with an ambiguous ticker and no matching name it shows nothing instead of another token.
* Alarm sound row without the lone note icon; Android shows the pulse line before «Crypto pulse» like iOS.
* Languages: Telugu and Tamil buttons in the polite form; Hebrew and Danish use one quotation style; Georgian, Armenian and Latvian terms translated; Norwegian store text fixed.

**Technical notes**

* Build 185: logos – Binance symbol list first, `CoinLogoQueue` (6 parallel, 4 per host, fixed order for everyone),
  `CoinLogoPlaceholder` (colour from symbol, AA contrast, fade-in); spot card by name identity; iOS `BlockedSources.clearForTest`;
  te/ta polite form, he/da quotes, ka/hy/lv/nb texts.

## 16.2.2 (build 184)

* Tax templates: the country figures are shown again in the card and in every CSV. The beta note invites you to report errors on GitHub – the app is free, so the community helps keep the templates right.

**Technical notes**

* Build 184: owner decision – `TaxCountryFigures` gating from 181/183 removed, country figures shown again; beta note links
  to GitHub issues (shared link helper with About).

## 16.2.2 (build 183)

* New pair page: a tap on a pair (watchlist, search, market, notification, widget) opens a full page with price and age, a larger chart, «Set alarm», favourite, «What moves along?», change exchange, price notification, the pair's alarms with «last checked» and market data. Rarely used actions are in the ⋮ menu. The half sheet is gone.
* Watchlist: the row age is the time of the last trade where the exchange reports it; connecting the live stream no longer makes an old price look fresh; delisted pairs show no age.
* Android: «PERP» stays visible with large text.
* Tax templates: Swiss wealth value and ESTV lines are hidden as well until checked, in the card and in every CSV.
* Android widget: after a restart it shows the last rows immediately instead of a blank card.

**Technical notes**

* Build 183: pair page replaces the watch actions sheet on both platforms (live overlay on Android too); `PairRestFit` FULL only
  when the whole rest fits; row age from `PriceFreshness.freshnessTime`; first live tick without trade keeps the shown time;
  `TaxCountryFigures` also gates CH wealth/ESTV lines; widget shows the last drawn rows (encrypted, not backed up) after boot.

## 16.2.2 (build 182)

* Android: after the first alarm you choose once between «Economical» and «About every 15 minutes»; the second turns on punctual checks (with the system permission).
* iOS: the pulsing status dot in the watchlist header stays still when Reduce Motion is on.
* WOO: if the hourly candle is closed and the second request is lost, the price is marked as hourly close.
* Developers: the Wear OS debug build uses the «.debug» package like the phone, so debug phone and debug watch find each other; a timing-sensitive iOS test no longer depends on runner speed.

**Technical notes**

* Build 182: first-alarm check-mode choice (Android, two options); wear debug `applicationIdSuffix ".debug"`;
  iOS Reduce Motion guard on the status dot; `HourlyCloseStore` marks WOO closed-candle fallback as hourly close;
  `ExchangeHTTPSharedPostTests` gated stub instead of timer; Hyperliquid doc corrected.

## 16.2.2 (build 181)

* Watchlist: healthy rows show the age of the price next to the exchange; on futures pairs «PERP» always stays visible.
* Gainers and losers: the note says what a tap opens – your saved pair or Binance COIN/USDT.
* Tax templates: country-specific figures are hidden until a tax professional has checked them; the neutral totals stay.
* Alarm list of a pair: shows when the alarms were last checked.
* Text fixes in German, French and Italian (average cost, depeg threshold, backup messages, screen reader) and in the Italian store texts.

**Technical notes**

* Build 181: audit follow-up. `TaxCountryFigures` hides country rows until a template is listed as checked (empty today);
  `WatchlistPairName` keeps PERP and drops only the quote; healthy rows show the price age; per-pair alarm list shows
  «last checked»; `movers_price_note` names the tap target; text fixes de/fr/it.

## 16.2.2 (build 180)

* Watchlist: the header names the time of the oldest visible price instead of the newest.
* Gainers and losers: a note explains that the percentages come from CoinGecko (average across exchanges) and the price from the chosen exchange, so the numbers can differ.
* Tax templates: the beta note says the figures are not yet checked by a tax professional.
* iOS: the alarm editor placeholder shows the price in the chosen display currency, as on Android.
* Text fixes in German, English and Italian.

**Technical notes**

* Build 180: review fixes after an external review. `LiveStatus` uses the oldest visible update time; `movers_price_note`
  bridges CoinGecko percentages and exchange prices; `tax_template_beta_note` says not checked by a tax professional;
  iOS alarm placeholder uses `draft.priceHint` (display currency); text fixes de/en/it.

## 16.2.2 (build 179)

* Portfolio: if the price request for a coin without any market fails (e.g. offline), the row says «Price unavailable right now» instead of «Price unchecked».

**Technical notes**

* Build 179: `PortfolioPriceIssue.nameUnchecked` takes the failed coins: a coin Binance was skipped for whose request failed is
  neither «unchecked» nor «no market» and shows «Price unavailable right now»; a stored «unchecked» is cleared then.

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

**Technical notes**

* Build 178: review of build 177. Import: Kraken `pendingTwins` drops a pending deposit/withdrawal (empty txid) with
  a booked twin (same refid, type, asset, amount); a different amount keeps both, flagged `pendingDiffers` with a
  preview hint (`portfolio_csv_import_pending_differs`). Fiat fee plus coin fee on one trade (Kraken, Binance ledger
  and trades): `CsvImport.fiatFeeInPrice`, row flag `feeInPrice`, hint `portfolio_csv_import_fee_in_price`; other
  pairs `CsvImport.splitFees`. Network: Android `ConnectivityMonitor` needs `INTERNET` and `VALIDATED`
  (`ConnectivityMonitorTest`); iOS `NetworkStatus` documents the gap. Watchlist row: the mini chart keeps its fixed
  size; `RowMetaLine` (Android) / `WatchlistMetaLine` (iOS) wrap the icons under the exchange
  (`DenseRowText.metaWraps`, parity `row_meta_line.json`, `DenseRowTextTest`/`DenseRowTextTests`) instead of
  shortening it or hiding the chart. Android «All alarms» in `AlarmsOverviewCards.kt` (pair and portfolio cards
  like iOS `groupCard`/`portfolioCard`), direction symbols `AlarmSymbols.kt` (`ic_alarm_*` drawables as iOS
  `AlarmStyle.symbol`); «Last checked» once per editor page (iOS `showsLastChecked`); iOS alarm editor with one «Save»
  at the bottom. iOS: «Last run» pill removed from `WatchlistStatus` (report in the ⋯ menu; key `watchlist_last_run`
  removed in all languages); portfolio + in `PortfolioAddSlot`/`PortfolioAddAccessory` above the tab bar (`RootView`,
  `safeAreaInset`); «Reset app» waits with `snapshotWritesDrained` without blocking the main thread; complication
  fetch within `WatchQuoteFetch.complicationTimeoutSeconds` (20 s, as Android `FETCH_TIMEOUT_MS`). Android: one
  `SQL_ID_CHUNK` (500) for every `IN (:ids)` query of the watchlist (`SqlIdChunkTest`); R8 keeps the names of own
  `Throwable` classes. Coverage gates raised (owner decision): Android `MIN_LOGIC` 58 → 65 %, `MIN_DATA` (`:data`)
  50 → 57 %. `mutation_test.py` resolves moved targets against `--repo` (self-test with a temporary tree).
  Screenshots publish only a complete set (job green, same names light/dark, no `NN_name_failed.png`).
  `DatabaseFreshInstallBackupTest`: system-backup round trip with every `portfolio_tx` column set. Wear OS in OSV
  (`:wear` release runtime classpath), in the release audit (APK missing/debuggable = error, 16 KB = warning) and in
  `reproducible.yml` (`:wear:assembleRelease`, attested); the debug artifact contains the watch APK. Hindi in the
  polite form, Play listings `zu` and `zh-HK` (Cantonese); `frame_screenshots.py` maps `zh-HK` to the `yue` captions.
  DEX discoverability: `DexNames.match` (Android `:domain`, iOS `Shared/Core`, parity `dex_names.json`) turns a DEX name
  in the coin search into a DexScreener row (`dex_search_hit`, token prefilled via `openDexSearch`); picker subtitles
  (`DropdownField`/`ExplorerPickerSheet` `subtitles`, `dex_picker_subtitle`) on DexScreener and the DEX section.

## 16.2.2 (build 177)

* Tests only (no change for users): the Android screenshot tour starts every screen from a freshly started app instead of finding its way back with the system back key, keeps the screen on while it runs and stops at the first screen it cannot reach, naming the screen it saw.

**Technical notes**

* Build 177: Android screenshot tour deterministic. Cause of the failures at builds 170 and 176 («Merkliste nicht
  erreicht; sichtbar: alarm_card» from `04_alarm_editor` on, light and dark): the way back used `input keyevent
  KEYCODE_BACK` and `input tap`, which the system delivers only while the screen is on and the app has input focus;
  on the pair's alarm page (no tab bar, so no tab to tap and no relaunch) back was the only way out, and 10 backs per
  step never left the page. Nothing in the app consumes back there. Now `TourNavigator.toWatchlist`/`toSettings` start
  `MainActivity` fresh for every step (explicit intent, `NEW_TASK | CLEAR_TASK`, not `MAIN`/`LAUNCHER` so the rule's
  `ActivityScenario` does not adopt it; older instances finished and awaited via `ActivityLifecycleMonitorRegistry`);
  a new activity always opens the watchlist without sheet or subpage. No system back, swipe or tap loop is left; all
  input goes through Compose semantics. The tour keeps the screen on (`svc power stayon`, restored afterwards),
  checks before every `screencap` that the app is in front with the screen on and unlocked, and stops at the first
  failing step with the screen it saw, the visible tags, activity stage, screen/keyguard state and `mCurrentFocus`.
  Strings come from the current activity (the rule's activity is closed after the first restart). Images unchanged.
  iOS (green) unchanged: it returns through its own tab bar (`RootView.reselect`) and XCUITest taps, no system keys.

## 16.2.2 (build 176)

* 41 more languages, 74 in total: Afrikaans, Amharic, Armenian, Azerbaijani, Basque, Bengali, Bosnian, Bulgarian, Burmese, Cantonese, Catalan, Croatian, Estonian, Filipino, Galician, Georgian, Gujarati, Icelandic, Irish, Kannada, Kazakh, Khmer, Lao, Latvian, Lithuanian, Macedonian, Malayalam, Marathi, Mongolian, Nepali, Punjabi, Serbian (Cyrillic), Sinhala, Slovak, Slovenian, Swahili, Tamil, Telugu, Urdu (right to left), Uzbek and Zulu – in the app, the widgets, the watch apps and the language picker. Hong Kong keeps Traditional Chinese unless Cantonese is chosen.
* Store listings in the new languages where the store offers them (Google Play 36, App Store 13); all store descriptions now say 75 languages.
* Serbian in Latin script («Srpski (latinica)») next to Cyrillic, generated from the Cyrillic texts – devices set to Serbian Latin no longer fall back to English; 75 languages in total. No separate store listing (Google Play has one Serbian listing, kept in Cyrillic; the App Store has no Serbian).
* Watchlist footer below the last row: «6 pairs», or «6 of 42 pairs» when a group, filter or the search hides pairs; near the limit also the free slots or «Watchlist full». In all 75 languages.

**Technical notes**

* Build 176: 41 more languages, 74 in total (owner decision). Folders `values-<code>` in app and wear; Cantonese
  in `values-b+yue` (script Hant from likely subtags: matches yue, yue-HK and yue-Hant, never zh-HK, which stays on
  `b+zh+Hant`); Filipino in `values-fil` only – since API 24 the resource matcher treats `tl` and `fil` as the same
  language, minSdk is 26, so no `values-tl` copy; `localeFilters` has `tl` for the Filipino texts of the libraries.
  Serbian is Cyrillic (`values-sr`; Latin script in `values-b+sr+Latn`, see below),
  Bosnian, Uzbek and Azerbaijani Latin, Kazakh and Mongolian Cyrillic, Punjabi Gurmukhi, Urdu right to left
  (already in `BidiText`/`WatchFormat`). Plural categories per CLDR in `check_strings.py` and `convert_strings.py`
  (ca/lt/sk with `many`, hr/bs/sr one/few/other, sl one/two/few/other, lv zero/one/other, ga one/two/few/many/other,
  km/lo/my/yue only `other`). iOS: `b+yue` → `yue-Hant`, all 74 codes in the catalogs, `CFBundleLocalizations` of
  the four targets and `knownRegions`. Language picker (`AppLanguages`, `locales_config.xml`, iOS names) keeps the
  regional order (Catalan, Galician, Basque after Spanish; South Slavic after Romanian; Indic after Hindi; Cantonese
  after Traditional Chinese); `normalize` maps `tl` to `fil`. Store: Play only for the codes of its list
  (36 new folders, none for bs, ga, uz, yue, zu), App Store only for its 13 new codes (`bn-BD ca gu-IN hr kn-IN
  ml-IN mr-IN pa-IN sk sl-SI ta-IN te-IN ur-PK`). Store descriptions say 75 languages. Lithuanian uses «komisinis
  mokestis» for every fee, «mokestis» only for tax. Texts shortened where tests limit them (shortcut long labels
  ≤ 25, alarm timing hint ≤ 160, `ind_read_*` ≤ 160) or require the setting names verbatim (restore warnings).
  `frame_screenshots.py`: Noto fonts per script (Ethiopic, Khmer, Lao, Myanmar, Sinhala, Indic, Armenian, Georgian,
  Urdu Nastaliq), Urdu right to left, Cantonese with the TC fonts and CJK line breaks. Tests that count languages
  expect 75; `StringResources` also reads three-letter codes (`fil`) and `b+yue`.
* Build 176, Serbian Latin (75 languages): `values-b+sr+Latn` (app and wear) and iOS `sr-Latn` are generated from
  `values-sr` / `sr` by `android/tools/sr_latn.py` (letter-by-letter transliteration, Љ/Њ/Џ as LJ/NJ/DŽ in capitals and
  Lj/Nj/Dž otherwise; placeholders, markup, entities, URLs and Latin tokens unchanged; `--self-test`), and
  `check_strings.py` runs it with `--check`, so the two scripts cannot drift; never edit the Latin files by hand.
  Registered in `localeFilters`, `locales_config.xml`, `AppLanguages` («Srpski (latinica)» after «Српски»; `normalize`
  keeps script Latn as `sr-Latn`), `convert_strings.py` (`b+sr+Latn` → `sr-Latn`), `CFBundleLocalizations` and the iOS
  language names; no store listing (Play has only `sr`, the App Store no Serbian).
* Build 176, watchlist footer: the four keys `watchlist_footer_count`, `watchlist_footer_of`, `watchlist_footer_remaining`
  (plurals) and `watchlist_footer_full` in `strings_watchlist.xml` now also in the 41 new languages (end of the file as
  in `values-de`; complex inflection as «Pairs: N» like the other texts), iOS via `convert_strings.py`.

## 16.2.2 (build 175)

* Two more languages: Chinese (Traditional, for Taiwan and Hong Kong) and Malay – in the app, the widgets, the watch apps and the store listings, selectable under Settings › Language on Android. 33 languages in total.

**Technical notes**

* Build 175: two more languages, 33 in total. Traditional Chinese in `values-b+zh+Hant` (app and wear) next to the
  unchanged `values-zh` (Simplified): Android computes the script of a qualifier without one from likely subtags, so
  `values-zh` counts as Hans and only matches Hans devices, while `b+zh+Hant` matches zh-Hant, zh-TW, zh-HK and zh-MO –
  no `values-zh-rTW`/`-rHK` copies needed. Malay in `values-ms`; both languages have only the plural `other`
  (`check_strings.py`, `convert_strings.py`). iOS: `b+zh+Hant` → `zh-Hant`, `ms`, 33 languages in the catalogs,
  `CFBundleLocalizations` of app, widgets, watch and watch widgets, `ios_extra_strings.json`. Language picker:
  `AppLanguages` (`ms`, `zh-Hant`; `normalize` keeps Hant/TW/HK/MO as `zh-Hant`), `locales_config.xml`,
  `localeFilters` (`ms`, `b+zh+Hant`, plus `zh-rTW`/`zh-rHK` for library texts), iOS language names. Store: Play `ms`,
  `zh-TW` and `zh-HK` (copy of zh-TW), App Store `ms` and `zh-Hant`, screenshot captions (TC fonts in `frame_screenshots.py`). Tests that count
  languages expect 33; `StringResources` also reads `values-b+…` folders.

## 16.2.2 (build 174)

* About the app: the backup line now says clearly that a backup without a password is readable (all languages).
* Android device tests on main are green again (stored database schemas carried a wrong check value). No change in the app.

**Technical notes**

* Build 174: `about_data_backup` names the password explicitly («…; without one it is readable», 31 languages, iOS
  via `convert_strings.py`). Screenshot tours: Android restores `demo-backup.json` (androidTest asset from
  `docs/store/screenshots`) through `BackupManager.restore` with the same overrides as iOS (`demoBackup` on both:
  dark mode, USD without conversion, alarm currency null, one alarm per pair) instead of building rows through the
  repositories; the BTC note in the demo backup is «Long-term hold».
  Device tests: `identityHash` in both `app/schemas/…/1.json` set to the hash Room compiles from the entities
  (`9b1d29d4…`, `94e00c83…`; red on main since build 163, Room keeps a schema file that differs only in the hash, so
  «Room schema drift» passed); «Failed tests» skips assumption skips (ScreenshotTourTest). `room_schema_drift.py`
  also compares that hash with the generated `*_Impl` (`data/build/generated/ksp`, `--self-test`); migration-tests
  fails unless the result XML shows every listed class and each `@Test` of RoomSchemaMigrationTest passed.

## 16.2.2 (build 173)

* Economic calendar: the first run on the new branch no longer stops when the branch does not exist yet.

**Technical notes**

* Build 173: `macro-calendar.yml` (fetch) and `pages.yml` read the exit code of `git ls-remote --exit-code` as
  `rc=0; … || rc=$?`: under `bash -e` the bare call ended the step with exit 2 when `macro` did not exist yet, so the
  seeding path was never reached (first run 29.10.). App code unchanged.

## 16.2.2 (build 172)

* Economic calendar: the weekly update of US release dates works again. The calendar now lives on its own branch of the repository and is published to the same web address as before; apps keep checking it against the signed data manifest. No change in the apps.

**Technical notes**

* Build 172: macro calendar on its own branch. Since 24.10. `main` has required status checks, so the push of
  `macro-calendar.yml` with `GITHUB_TOKEN` failed («Commit if changed», run of 26.10.); no bypass (owner). The data
  moves to the orphan branch `macro` (`events.json`, `status.json`; created on the first run from `docs/macro` of
  `main`, pushed without `--force` on top of the tip the fetch job read). New `pages.yml` (`actions/upload-pages-artifact`
  v5.0.0, `actions/deploy-pages` v5.0.1, pinned): `docs/` of `main` plus `events.json` of `macro`, fallback to the seed
  on `main`, checked with the new `artifact_check.py` profile `pages_macro`, kept as artifact `pages-macro`;
  called by `macro-calendar.yml` (reusable workflow), then «Market data» by dispatch. `market-data.yml` signs the
  calendar of the live `github-pages` deployment (`deployments: read`), no longer `docs/macro` of its checkout;
  `mirror_data.py` keeps the previous signed hash of an extra file it cannot read. Pages source must be switched to
  «GitHub Actions» once (ROADMAP). App code unchanged.

## 16.2.2 (build 171)

* Android: tabs and pages no longer leave a large gap between the status bar and the title; Settings › Alerts has the usual space between its cards, «Pairs» and «Share diagnostics» line up with the other rows, and the last choice of «Notify only on price change» is no longer cut off (where the word is too long, a pencil icon stands in).
* Alert editor: «When BTC goes below» with a normal word space.
* iPhone: the «+» button in Portfolio and in a coin's page sits above the tab bar; in the watchlist «Add pair» is never shortened, «Last run» shrinks to its icon when space is short, and the mini chart gives way before the exchange name is shortened.
* Add pair: when lists cannot load (e.g. offline), the card sits below the coins and names at most 3 exchanges, alphabetically, with «+N more» to show all.
* Notifications: the short explanation before the system question («Allow notifications?») appears at most once when pairs are added and once more at the first alarm, also after back, leaving the screen or closing the app; on Android, Back in «Add pair» no longer brings it up and leaves the page like the arrow.
* Logos: futures with a multiplier show the coin's initials («1000000MOG» → «MOG» instead of «100»), and when a logo image fails to load, the logo of the same coin from the Binance symbol list or the Binance Alpha list is tried.

**Technical notes**

* Build 171: layout fixes from the screenshot review (CI shots of build 161). Android: `AppNavHost` consumes the outer
  Scaffold's insets (`consumeWindowInsets(innerPadding)` after the IME padding), so pages with their own
  Scaffold/TopAppBar no longer add the status bar a second time; `SettingsNavRow(horizontalPadding = 0.dp)` inside a
  `GroupCard` («Pairs», «Share diagnostics»), spacer between the cards of Settings › Alerts; the «Custom» segment of
  `PercentChoiceRow` uses `FittingSegmentLabel` (custom `Layout`, not `BoxWithConstraints` – the segment row asks for
  intrinsics): label shrinks to 80 % (`TextAutoSize`), else `ic_edit` with the word as description. Alert sentence:
  «When BTC» and the direction in one text (both platforms). iOS: `PortfolioAddButton` via
  `safeAreaInset(edge: .bottom, alignment: .trailing)` instead of an overlay under the custom tab bar (list end spacer
  and `Spacing.fabClearance` on iOS removed; `SpacingUsageTest` adapted); watchlist header: add button `fixedSize`
  + `layoutPriority(2)`, status `layoutPriority(1)`, «Last run» pill `ViewThatFits` (icon only);
  `WatchlistSparklineSlot` shows the chart only if the info column keeps its ideal width (error and note lines count
  0). Add pair: `ListsOffer`/`ExplorerListsOffer` (new file) below the coin results, failed exchanges sorted
  case-insensitively, collapsed to 3 names + `explorer_lists_failed_more` (new plural, 31 languages), tap expands.
  Notification pre-prompt: one app-wide instance (`PrePromptSlot`; Android `NotificationPrePromptHost` in `MainActivity`
  instead of a `rememberSaveable` dialog per screen, which the watchlist saved as open when its starter banner was cut by
  «+» and reopened in every predictive-back preview, swallowing Back); stage written when shown; the starter ask is
  skipped when the banner ends off screen (iOS: explorer open); `NotificationPrePromptTest`/`NotificationPrePromptTests`.
  Logos: `CoinLogos.stripMultiplier` (shared by `normalize` and `initials`, TradFi unchanged; parity `coin_logos.json` › `multiplier`); `CoinMeta.alternates` (Binance list without futures-only/bStocks, then Alpha) is tried by the bulk download when the mapped image fails (parity `coin_meta.json` › `alternates`).

## 16.2.2 (build 170)

* iPhone and Apple Watch: the compiler now checks the thread safety of all shared state, including every exchange adapter (0 `@unchecked Sendable`); prices, pair lists, alerts and exports behave as before.

**Technical notes**

* Build 170: `@unchecked Sendable` 76 → 0 (`CEILINGS` 0/0/0, new kind `uncheckedState` 7). `Exchange` and `SimpleExchange` are protocols
  (`Sendable`); the template methods (`readTicker`, `parseBulkTickersDetailed`, `readPairList` …) and all defaults in
  `Exchange+Defaults.swift`, constants in `ExchangeConstants`. The 57 adapters are `struct`s, the shared bases
  (`BinanceBase`, `BybitBase`, `HtxBase`, `WooBase`, `BingXBase`, `BitgetBase`, `PhemexBase`, `UpbitStyleExchange`)
  protocols with extensions; former `super` calls go through named helpers (`simpleTickerAddress`,
  `simpleErrorMessage`, `phemexFillTicker`, `phemexParseBulkTickers`). `Gemini` is a `final class` with its detail cache
  in `OSAllocatedUnfairLock`. `LockedBox<Value: Sendable>` with `@Sendable` closures and `modify`;
  `PortfolioWriteOrder`, `BackgroundDemandGate`, `OrderedSerialRunner`: `NSLock` for the ordered work plus the counter in
  `OSAllocatedUnfairLock`; `PortfolioPartStore`: all state in one lock, weights/tests of the caller called outside it
  (identified by `PortfolioPartIds`); formatter caches, `AlarmDelivery`, `MoveHistoryCore`, `BackgroundTaskCompletion`,
  `NotificationCompletion`: the non-`Sendable` object only inside `OSAllocatedUnfairLock(uncheckedState:)`.

## 16.2.2 (build 169)

* iPhone and Apple Watch: shared state of the app, widgets and watch app sits behind locks the compiler checks; no unchecked global state is left (0 `nonisolated(unsafe)`).
* Logos: the in-memory logo cache keeps at most 300 images and drops the least recently used one; iOS no longer empties it under memory pressure.
* Relative times («2 min ago») follow a language change in the app at once.

**Technical notes**

* Build 169: iOS lock boxes moved to `final class …: Sendable` with `OSAllocatedUnfairLock<State>` (module `os`):
  `PortfolioPriceSource.CacheState` (cache, hints, stored prices and the clear counter `clearGeneration` behind one
  `state` lock; the price file is written inside it), `LiveClock` (last message, subscription time, first price),
  `NetworkStatus` (`@Sendable` observers), `StorageKey`, `TokenIdentityStore`, `SheetChartStore`, `AlarmLease`,
  `AlarmCheckStatus`, `PriceRunGate`, `FirstResult<T: Sendable>`, `BulkDelistedMemory`, `MirrorRevocations`, logo boxes
  and others. `nonisolated(unsafe)` 13 → 0, `@unchecked Sendable` 96 → 76 (`CEILINGS` 76/0/0). Logo memory cache is a
  `BoundedCache` of 300 images instead of `NSCache` (not emptied under memory pressure); `RelativeDateTimeFormatter`
  created per call, so relative times follow a language change at once.
  iOS `PortfolioDecimal` exact ops no longer fall back to Decimal `*` below 1e-128 (8-bit exponent overflow in
  Foundation), with a fast path (`plainProduct`).

## 16.2.2 (build 168)

* Watchlist and live prices: an expired dated futures contract is marked «no longer traded» even with only one or two pairs of that exchange; «1Q», «2Q» … follow the date in the contract, so after the quarter roll «2Q» shows «1Q»; «Live» is shown per pair and only while that pair receives data; a live subscription silent for 3 minutes reconnects; after editing a pair its old price is no longer shown.
* Alerts: the depeg alert keeps its threshold and is armed again after off/on; switching a market alert off during a running check stays off; a failed Fear & Greed or indicator check is retried up to 3 times; indicator alerts accept only the values offered in the settings; the alert editor on Android shows «Last checked» as on iPhone; delivery of a notification is confirmed more reliably.
* Market tab: data sources wait 12 or 20 seconds, the coin analysis 20 seconds everywhere; iOS reports a pair without id as an error instead of asking the exchange.
* Portfolio and tax export: day closes are loaded in batches (far fewer requests for long histories); clearing the portfolio while prices load keeps nothing; on iPhone, editing transactions and creating or restoring a backup no longer block the screen; Binance symbols starting with digits (1INCH, 1000SATS) are read correctly; a French withdrawal or gift valued at a day close shows «value estimated»; Coinbase amounts accept only ASCII digits on Android; very small amounts on iPhone are multiplied exactly.
* Security and backups: the Android system backup never saves an empty portfolio and never creates a key; a single unreadable key no longer deletes the stored key; set-aside copies of unreadable storage on iPhone are removed 30 days after the notice was confirmed; backups are read strictly on both platforms; address queries follow redirects only to the same host and never from https to http; TON addresses are hidden in the diagnostic report; the contract address of a DEX pair is copied the same way on both platforms.
* Display: amounts below half a cent have no sign (never «-0.00»), signs stay in front in right-to-left languages, also on the watch apps; widget texts grow with the system text size up to a limit; with the app lock the portfolio alert shows only a neutral sentence; the price overlay stays on screen after a rotation and tries again when it cannot be shown; the Wear OS complication waits at most 20 seconds for a price, then shows the stored one.

**Technical notes**

* Build 168: full audit of build 167. Market/alarms: outside the bulk path an expired dated contract confirms «not
  traded» (`NotTraded.singleConfirms`, `contractExpired`, settlement 08:00 UTC; not asked again), short form from the
  contract date (`FuturesContractType.shownContract`); depeg threshold stored (`DepegAlarmPrefs.KEY_ARMED_TENTHS`,
  `armedFor`) and re-armed after off/on; a running zone check no longer overwrites an «off» (Android shared lock, iOS
  `keyResetGeneration`); zone check retried 3× (`MAX_RETRIES`, 30 s doubling); indicator bounds only from
  `IndicatorAlertKind.choices`; «Live» per pair (`LiveFeed.liveIds`), reconnect after 180 s without data
  (`SILENT_SUBSCRIPTION_MILLIS`), no old price after editing a pair (`samePair`); Android `AlarmLastCheckedLine`
  (`RefreshStats.lastFullCheck`); delivery check 10 × 100 ms (`VERIFY_ATTEMPTS`, `confirmedPost`); market-tab timeouts
  12/20 s, coin analysis 20 s on both platforms; iOS «Missing pair id» without request. Fixtures `live_feed.json`,
  `market_notice.json`, `not_traded.json`, `notification_budget.json`. Robustness: tax-export day closes in spans
  (`SpanFetch`, Binance 1000 / Coinbase 300 days, `HistoricDayClosesBatchTest` equal to per-day closes); iOS portfolio
  edits and backup file work off the main thread (`BackupFileWork`, `PortfolioImportSave`); server numbers bounded
  (`EpochTime.secondsToMillis`, `MAX_PLAUSIBLE_HEIGHT`, fixture `server_values.json`); price overlay clamped/snapped
  after rotation (`place`) with retry (`RETRY_MS`); clearing the portfolio during a price fetch keeps nothing
  (`persist(generation)`); settings write failures logged; reduce motion observed once (`rememberReduceMotion`); Wear
  complication rethrows cancellation, 20 s timeout; `MacroNotifyWorker` reschedules after an error. Security: read-only
  open (system backup) never sets aside or creates a key, a single `UnrecoverableKeyException` no longer deletes the
  keystore key of the restore journal and alarm log (`PortfolioSnapshotKeyStore` with `DatabaseKeyRetry.SuspectMarker`:
  only when it recurs in a later run); replaced copies pruned after the same retention (`pruneReplaced`); iOS
  `*.locked`/`*.replaced` kept 30 days after the acknowledged notice (`AsideRetention`, fixture `aside_retention.json`); backup envelope read strictly (`integral`, fixture
  `backup_crypto.json`); address queries follow redirects only to the same host, never https → http
  (`AddressRedirect`, `SameHostRedirectInterceptor`/`SameHostRedirectDelegate`, at most 5 hops); TON addresses redacted
  in the diagnostic report (`diagnostic_redact.json`); `:wear` release signing like `:app`; DEX contract address copied
  alike; SECURITY.md and privacy page updated. Hygiene/CI: Kotlin warnings as errors (not `:data`), CI path filters,
  required-checks script, coverage docs, dead code removed (10 pulse strings in 31 languages), review tags and build
  numbers removed from comments, `line_limit.py` also scans CI/build files, flaky tests fixed, screenshots workflow only
  from `main`, `user_agent_check.py` models Firefox's 2-week cadence since Firefox 154 (neutral UA stays Firefox 159).
  Money: Binance symbols with leading digits (`1INCH`, `1000SATS`), FR outflow/gift reduction marked «value
  estimated» (`TaxOutflow.estimated`), Coinbase `money()` only ASCII digits on Android. Display: signed amounts
  without «-0.00» in an LTR isolate (`PortfolioFormat.signedMoney`, `WatchFormat`/`WatchPercent`, fixtures
  `signed_amount.json`, `percent_text.json`), widget text size capped (`WidgetTextSize`), portfolio alarm with app lock
  only `notification_portfolio_alarm_locked`; `LoadErrorScreen` removed (the exchange list is fixed and cannot fail).

## 16.2.2 (build 167)

* Portfolio with watched addresses: with average cost and FIFO the address part is kept apart from your own holdings in 7 d / 1 y, the history and «Today», as on the card; opening lots count from their purchase date at the cut-off date and in the Swiss wealth value too; the list widget no longer shows the address balance as a booking; «Today» since 00:00 starts each coin at the time of its reference price.
* CSV import: Coinbase and Binance trade history read numbers like «1.23457E+11» correctly; amounts from a restored backup are rounded like stored ones; Android and iOS divide and multiply exactly the same way.
* Tax export: a withdrawal beyond the holdings no longer clears the French acquisition total; every row whose cost basis contains a value estimated at a day close is marked «estimated»; the French network fee at a day close is marked too; in the UK a withdrawal between two sales of the same day no longer affects the earlier sale.
* Coin logos: a missing image no longer blocks a whole image server, pull to refresh also loads missing logos, loading resumes by itself after a server pause, and starter logos are found for «1000…» futures symbols.

**Technical notes**

* Build 167: second money-path review of build 166. Holdings book keeps the address pot apart for every cost method
  (own outflows capped at own holdings, address outflows at the address part); list widget skips address-part
  entries; cut-off and CH wealth follow `AddressBooked` (lots from their purchase date); FR total value via
  `AddressBooked.histories`; «Today» in day mode with `changeSinceDay`. CSV: Coinbase `money()` and Binance `withUnit`
  keep the exponent, E notation checked only against < 1e30, Room converters per column (amount rule, price rule
  for `priceUsdt`/`feeCoinPriceUsdt`), `jsonToTx` rounds amounts like stored ones (rounded to 0 = invalid entry).
  Decimal parity: Android products with `PortfolioDecimal.MC`, iOS `PortfolioDecimal.multiply`/`divide`/`divide(a, b,
  by:)` exact with one rounding to 34 digits (`PortfolioDecimal+Exact.swift`, fixture `portfolio_decimal.json`); plain
  sums beyond 38 digits on iOS documented (owner decision). Tax: FR withdrawal uses the covered quantity, FR network fee
  at day close estimated, `costEstimated` lot flag carries «value estimated» to every disposal whose cost contains an
  estimated part (all templates), UK same-day withdrawals booked after the day's pool share; TAX_REFERENCE references
  fixed. Logos: `LogoFiles` writes atomically with unique temp names and reports failures, `downloadJobs` one fetch per
  file name, starter logos via `CoinLogos.urlFor` (normalised), pull to refresh starts the logo sync, a pause schedules a
  restart at its end, 403 without Retry-After fails only that image (server pause on 429/503, Retry-After or 3
  consecutive 403). Hint `portfolio_export_address_lots_note` reworded (31 languages).

## 16.2.2 (build 166)

* Portfolio with watched addresses: «Today», 7 d / 1 y and the value history no longer count opening lots or recorded transfers to the address twice; opening lots count everywhere like buys from their date. A trade exactly at midnight UTC counts on the same day in the header and in the history; «Today» no longer counts a recent purchase as gain when the reference price is older than 24 h; the widget history no longer drops when a coin has no current price.
* CSV import: very long import keys are kept (as a checksum), a transfer link that fails no longer loses the deposit, the exchange rate is reloaded after changing the time zone in the preview, staking rows are no longer offered as withdrawals, prices keep their full precision, amounts of 10^30 or more cannot be saved, and numbers like «1.234E-05» are read for every exchange.
* Tax export: a fee paid in a third coin without a recorded price is valued at that day's close and marked «estimated» (all transaction types and templates); France counts it in the acquisition cost and shows a note on rewards; UK splits two sales on the same day evenly; Germany uses the 600 € exemption limit from 2008; the US wallet note describes what the export computes.

**Technical notes**

* Build 166: money-path review (independent recalculation in Python Decimal, fixtures verified). Watched addresses:
  `AddressBooked` subtracts what the app already books (effective opening lots, recorded moves of the address wallet)
  from the observed series, so card, «Today», periods, history, widget and cut-off share one holdings book; opening lots
  count as buys from their date everywhere. Period window starts at `utcDayEnd(baseDay)`; «Today» starts per coin at
  the time of its reference price (`coinStart`); widget fallback history only over coins with a valid current price.
  CSV: keys over 200 UTF-8 bytes become `<format>|h:<sha256>` (`CsvImport.stableKey`), insert and link in one
  transaction (Android Room, iOS one file write) with fallback rows (`CsvTransferMatch.withFallbacks`), FX range
  reload after time-zone changes (`CsvImportRateDays`), `stakingMove` marker on CSV staking transfers (Room column,
  iOS field, backups, `BackupFields`) instead of the note rule, `unsavableReason` checks limits (≥ 1e30, price that
  would store as 0), prices stored with 34 significant digits (`PortfolioDecimal.priceText`), exponent notation in the
  common number parser. Tax: every third-coin fee without price valued at the UTC day close with «value estimated»
  (`thirdFeeNeedsDayClose`, `valueThirdFeesAtDayClose`; card keeps no estimates), every day-close value carries the
  note (FR crypto payment, network fee in the coin), FR acquisition total with that fee value and FX span over the
  reduction origin (`frOutflowTimes`), FR withdrawal fee share, FR rewards note (`tax_summary_fr_rewards_income`), UK
  same-day sales split pro rata for all three rules, DE exemption 600 € from 2008, `tax_note_us_wallet` reworded.
  Rule questions decided: CA superficial loss, FR rewards at value on receipt (with note), DE transfer fee in cost –
  unchanged.

## 16.2.2 (build 165)

* Watch apps: a fetch started earlier (app, complication or widget) never overwrites a newer price; on the Apple Watch app and complication share one file for prices. Phone and watch settings are sent in order and with the current state, also right after the watch connects. The Apple Watch complication is described as «Price». The Apple Watch stops reading a price answer after 4 MB, like Wear OS.
* The privacy page names the user agents of the watch apps.

**Technical notes**

* Build 165: reviews of build 164. Watch cache: every fetch (app, Wear complication, watchOS widget) records
  `startedAt`; `WatchCache.merge` takes a delivered coin only if the stored entry is not newer than `startedAt` (equal:
  delivered wins) and stores `startedAt` as its time («as of the fetch» = its start). watchOS: prices in an App Group
  file (`WatchQuoteFile`, read-merge-write under `NSFileCoordinator`, excluded from backup) shared by app and widget
  extension; `WatchBodyLimit` streams the answer and stops after 4 MB like Wear. Settings sync: `SerialPublisher`
  (Wear and phone) sends one after another and reads the current state at send time; the phone loads its stored
  settings before any merge (`ensureLoaded`); watchOS merges the phone's context on the MainActor
  (`WatchModel.takeFromPhone`) and republishes its state after activation. watchOS complication description
  `watch_complication_label`. `gen_xcodeproj.py --check` compares `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION` of
  every build configuration. Privacy page names `CryptoChecker-Wear`/`CryptoChecker-Watch`. Tests: fixture
  `watch_quotes.json` (stale fetch, equal start), `WatchFetcherTest`, `SerialPublisherTest` (wear and app),
  `WatchQuoteFileTests`, `WatchCacheTests`.

## 16.2.2 (build 164)

* Watch apps (Wear OS and Apple Watch): a slower earlier fetch can no longer overwrite a newer one; if only some coins arrive, the others keep their last price with their own age; the open Wear app follows changes made on the phone; tapping a coin row no longer reloads (only «Refresh» does); phone and watch settings are reconciled by a change number (on a tie the phone wins).

**Technical notes**

* Build 164: watch apps (external review of build 162). Fetch gate: `WatchFetchGate` (Wear `WatchCache.kt`, iOS
  `WatchCore/WatchCache.swift`) – a new fetch cancels the running one (Wear `WatchFetcher`, iOS `Task`), a result is
  taken only for the newest run while the shown list is still the one it asked for. Per-coin cache: `WatchCached`
  (price + own fetch time), `WatchCache.merge` replaces only delivered coins, others keep their last price and time;
  outdated per coin «as of the fetch» (not the last trade), status line shows the oldest shown coin
  (`WatchCache.listTime`); complications keep the last price of the first coin. Wear: one in-process state
  (`WatchStore` StateFlow) for activity, `WatchDataListener` and complication; listeners read the data events at
  once and work in a process scope (no `runBlocking`); coin rows have no tap action. iOS: one ephemeral `URLSession`
  per fetch run, quotes and times stored as one JSON value (`watch_quotes`). `WatchSettings`: `revision` (JSON `r`)
  instead of wall-clock time, own changes `nextRevision()`, higher revision wins, on a tie the phone's settings.
  Fixtures `watch_quotes.json` (`cache`, `gate`), `watch_settings.json` (`nextRevision`, tie cases); tests
  `WatchFetcherTest`, iOS `WatchCacheTests`.

## 16.2.2 (build 163)

* No visible change. Internal clean-up on both platforms: no migration or compatibility code any more; Android databases are only ever opened encrypted (the copy from the Android system backup is encrypted right on restore).
* Backups are checked completely before anything is replaced: a file missing a part, field or entry field that the app writes is rejected as invalid.
* Code comments and developer documentation describe the current state only.

**Technical notes**

* Build 163: compatibility code for earlier builds removed on both platforms (owner decision: no migrations, no
  fallbacks, nothing from earlier builds stays readable). Settings: no DataStore migrations, legacy keys or downgrade
  write-back; `UpdateMode` only `SPARSAM`/`LIVE_OFFEN`; `liveIntervalSeconds` and the old backup flags gone; iOS
  legacy zone background task and its `BGTaskSchedulerPermittedIdentifiers` entry removed. Widgets: no legacy
  prefs, no plaintext snapshot migration (`PortfolioSnapshotSeal.planRead(sealed, keyReady)`), no App Group defaults
  leftovers. Portfolio database restarted at version 1 (schema `1.json`, same identity hash), migrations and
  `LegacyStorageMigration` gone, CSV import keys only in the current form (`legacyKeys`/`shortKey` gone),
  `overlapLegacy` and its one-time revert and hint gone (string `portfolio_address_overlap_restored` removed).
  Backups: only v3 (Argon2id); backups and `portfolio.json` carry only `amountText`/`priceText`/`feeText`.
  Android encryption: no plaintext conversion – `DatabaseEncryptionMigration` → `DatabaseOpenPlanner`,
  `DatabaseFingerprintDiff` → `DatabaseFingerprint`, `PlaintextMemoryCopy` and `RealDatabaseEncryptionTest` removed;
  the system-backup copy is encrypted on restore (`DatabaseSystemBackup.adoptRestored`) or, without a key, at the next
  start before Room opens; string `watchlist_storage_copy_in_memory` (and `strings_restore.xml`) removed. iOS: user
  files only sealed (`ProtectedStorage` `sealedOnly`, a plain file there counts as damaged), only the `.unreadable`
  copies may be written plain without a key; portfolio alarms never in `watchlist.json`; keychain items created
  device-only without a later switch. Start-up clean-ups removed (old cache and report keys, jsDelivr logo folder,
  `map4`/`names4`, legacy OI key, move-history split, `FallAlarmCleanup`, inverse-perpetual normalisation, VACUUM after
  `secure_delete`); unused `ActivityAnalyzer.oiChange`/`OiUpdate` removed. Comments describe the current state only
  (no review IDs, rounds or build history); `line_limit.py` build-number ceiling 0. Docs (this guide's reference
  part, READMEs, SECURITY, privacy page) describe the current behaviour. Screenshot tour: taps above the action sheet
  when back does not close it. `demo-backup.json` in the current format.
  Final pass after an independent review: backups, the restore journal and the portfolio system-backup copy are read
  strictly (`BackupFields` on both platforms, fixture `backup_entries.json`; `version` a number, every part, settings
  field and entry field the writer always writes is required, otherwise the whole backup is invalid); indicator and gas
  alarms treat a missing stored bound as changed; Android storage keys and cache files without version suffixes
  (notification channels `alarms_vN` keep theirs); string resources moved from `strings_r*`/`dimens_r10_widget` into
  the area files (`dimens_widgets.xml`); tests renamed by content (no build numbers), `DatabaseMigrationTest` →
  `DatabaseFreshInstallTest`; `FuturesContractType.normalized`, `smallIdOf` and unused parameters (`dayEndMillis`,
  `cleanName` symbol, `startQuote` region) removed; parity fixtures without review IDs or history; the «Verlauf»
  sections of both READMEs removed (history lives here and in the CHANGELOG). Second sweep: iOS persisted models (watchlist snapshot,
  Watch, Alarm, portfolio and address files, portfolio alarms, xpub cache, cost lots) require every field the writer
  always writes – a missing field takes the existing damaged-file path; Binance quarterly ids without expiry get no
  calendar fallback (prices and candles); a watched address without `addedAt` is dropped; xpub cache without a stored
  address type is not guessed as P2PKH.

## 16.2.2 (build 162)

* Watch apps load fallback prices coin by coin instead of whole price lists; the watch, its complications and the Android price overlay grey out outdated prices and show how old they are.
* Tests only: more unit tests for the Android data layer.

**Technical notes**

* Build 162: watch apps and price overlay (review of build 161). Data volume: the Bybit and OKX fallbacks of the watch
  request each missing coin on its own (`v5/market/tickers?category=spot&symbol=…`, `v5/market/ticker?instId=…`, in
  parallel) instead of the full spot lists (OKX ~380 KB); Binance keeps one request with `symbols=[…]`; at most 8
  coins. iOS: parser and flow in WatchCore (`WatchQuoteParser.swift`), `Watch/Device/WatchPriceSource.swift` HTTP only.
  Freshness: `WatchFreshness` (marketdata/watch, WatchCore) – outdated after 45 min without a successful fetch
  (`OutdatedRule.afterMillis(15)`); watch list dimmed with relative age, Wear complication with the age in the long
  text and the content description, watchOS complication dimmed with an extra timeline entry, Android price overlay
  with the watchlist row's rule (`PriceOverlayRules.stale`), redrawn every minute. New wear strings
  `a11y_watch_outdated`, `a11y_watch_updated_minutes/hours/days` (31 languages). User agents: wear
  `CryptoChecker-Wear/<versionName>` from `app/build.gradle.kts`, watchOS `CryptoChecker-Watch/<version>`; neutral
  Firefox user agent 153 → 159 (also `address_privacy.json` and the privacy page). `:wear` lint: `tools:targetApi`
  for `dataExtractionRules`, `android_wear_capabilities` kept via `res/raw/keep.xml`. Tests: shared fixture
  `watch_quotes.json` (`WatchQuotesParityTest`, `WatchFreshnessParityTest`, `PriceOverlayRulesTest`, iOS
  `WatchQuotesParityTests`). Unit tests for `:data` (in app/src/test, measured through the `:app` Kover report):
  candle fallback chain of `CandleDataSource`, `InsightsDataSource`, `CycleDataSource`, `SpotDataSource`,
  `NearExtremeDataSource`, `TokenIdentityStore`, `CycleCacheStore`, `ActivityRepository`, `StarterCoinsRepository`,
  `PortfolioPriceSource`, `HistoricPriceSource`; helpers `RouteHttp`/`Klines`, `FolderContext` (stub `ContextWrapper`
  with test folders and in-memory SharedPreferences), proxy fakes for `WatchDao`, `PortfolioDao`,
  `ExchangeRepository`. Still uncovered: `PulseDataSource`, `ExchangeRepositoryImpl`, `SparklineRepository`,
  `PortfolioHistorySource` (Android-bound constructor dependencies).
  Measured in CI: `:data` 57.9 % (from 43.1 %); new gate `coverage_summary.py --min-module data=50` (`MIN_DATA` in
  android.yml) next to the overall logic gate of 58 %.

## 16.2.2 (build 161)

* The old per-pair holdings field is gone (it was taken over into the portfolio once); backups no longer contain it, and the backup description no longer mentions holdings.
* The origin notice for BitcoinChecker is removed from LICENSE, NOTICE and the license pages of both apps; the start-up clean-up of the predecessor's database is gone.
* Tests only: the CI screenshots show real content on the market tab and in the portfolio (canned answers, only in the screenshot tour).

**Technical notes**

* Build 161: the legacy per-pair holdings are gone on both platforms. Android: column `watches.holdings`,
  `WatchEntity.holdingsValue`, `WatchDao.clearAllHoldings`, `PortfolioRepository.migrateHoldingsOnce` /
  `importHoldings` (prefs `holdings_migrated`, marker `holdings_import_done`) and `markOnce` / `portfolio_meta`
  removed; `PortfolioRepository` no longer takes `Context` or `WatchDao`; the restore has no holdings step any more.
  `AppDatabase` stays at version 1 (golden schema `AppDatabase/1.json` and `DatabaseEncryptionTest.MAIN_SCHEMA`
  without `holdings`). iOS: `Watch.holdings` / `holdingsValue` / `validHoldings`, `PortfolioFile.holdingsImportDone`,
  `PortfolioStore.holdingsMigrated` / `importHoldings` and `AppData.migrateHoldingsOnce` removed. Backups neither
  write nor read «holdings» for pairs; `backup_plain_contents_base` without «holdings» (31 languages).
  Screenshot tour: market tab and portfolio answered offline with canned data generated at the current time
  (CoinGecko, Fear & Greed, Coin Metrics, funding, fees, candles, Binance name list, USDT/USD) – Android
  `FakeExchange.marketData` (`FakeMarketData.kt`, `FakeMarketSeries.kt`), iOS `UITestExchange.marketData` via
  `UITEST_MARKET_DATA=1` (`UITestExchange+Market.swift`, `UITestExchange+Series.swift`, DEBUG only); off for all other
  tests.
  Origin notice removed (owner decision; origin check: no code taken over): `LICENSE` (and the app copies
  `MIT-cryptochecker.txt`) only with r1adBE, `NOTICE` without «Origin», `NOTICE-origin.txt` → `NOTICE-thirdparty.txt`
  (third-party names/logos/data, alarm sounds) on both platforms, `LicenseFilesTest`/`LicenseFilesTests` adapted.
  `LegacyDatabaseCleanup` (deleted the predecessor's `MarketDatabase.db` once) and `AppDatabase.LEGACY_DB_NAME` removed.

## 16.2.2 (build 160)

* Tests only (no change for users): the screenshot tour visits every screen step by step and reports missing screens at the end; Android test prices come from the fake exchange answers.

**Technical notes**

* Build 160: screenshot tour reworked – each screen is its own step that starts from the watchlist or the settings,
  a failing step is recorded and the tour goes on; the test fails at the end with the list of missing screens
  (`ScreenshotTourSteps.kt` / `ScreenshotTourSteps.swift`). Android: settings rows are found by their content
  description (`SettingsNavRow` clears its semantics), the tour runs one full price refresh after seeding, and
  `FakeExchange.binanceMirror` (only in the tour) answers `data-api.binance.vision` for the portfolio prices. iOS: no
  deep link any more (it restarted the app with `-uiTesting`, which clears the storage); «All alerts» via the bell,
  «Last refresh» via the watchlist menu.

## 16.2.2 (build 159)

* No visible change. In the code, the word for a trading venue is now «exchange» everywhere (Android and iOS: classes, files, keys, database tables `exchanges` / `exchange_pairs`, backup and widget keys); «market» stays only where it means the whole market (market tab, market cap, movers).
* Android: the watchlist database starts over at version 1.
* CI: screenshots of the main screens (light and dark) after every change, on the branch `screenshots`.

**Technical notes**

* Build 159: the word for a trading venue is «exchange» in the whole code base (Android and iOS, same names on both
  platforms): `Market` → `Exchange`, `MarketsConfig` → `ExchangesConfig`, `MarketPair` → `ExchangePair`,
  `MarketInfo` (domain) → `ExchangeInfo`, `MarketRepository` → `ExchangeRepository`, `MarketHttp`/`MarketHTTP` →
  `ExchangeHttp`/`ExchangeHTTP`, `MarketService` → `ExchangeService`, `MarketFailure` → `ExchangeFailure` (case
  «message of the exchange» is now `Message` / `.message`), `marketKey`/`marketName` → `exchangeKey`/`exchangeName`
  (also in backups, widget and watch data, parity fixtures), `FavoriteKind.MARKET` → `EXCHANGE`, package
  `marketdata.model.market` → `marketdata.model.exchange`, `ios/Shared/Markets` → `ios/Shared/Exchanges`; string
  resources of the exchange sense (`exchange_unavailable_error`, `error_exchange_unreachable`, …). «Market» stays
  where it means the whole market (market tab, `MarketMove`, `MarketMovers`, market cap, `domain.market`, iOS
  `MarketCandle`) and in API field names of the exchanges (Upbit/Luno «market», BTC Markets «marketId»). The module
  `:marketdata` keeps its name (market data).
  Android `AppDatabase` starts over at version 1 (tables `exchanges`, `exchange_pairs`, column `exchangeKey`): no
  migrations, no destructive fallback, golden schema
  `AppDatabase/1.json` only. `PortfolioLegacyImport` / `PortfolioLegacyPlan` removed,
  `PortfolioDatabase.callback(context, mirrorFile)` keeps the system-backup takeover; `portfolio_meta` is created
  by `markOnce`. `RoomSchemaRulesTest`: `N.json` with its version; for N > 1 also `N-1.json`, migration N-1 → N and
  a data test (`MAIN_DATA_TESTS` empty). Device tests for old AppDatabase versions and the legacy import removed;
  PortfolioDatabase migration tests (1 → 6) unchanged. The old German error text «Börse nicht verfügbar» is gone
  from the error checks.
  CI: «Compile instrumented tests» (android.yml) and «Build debug and test APKs» (android-device-tests.yml) turn
  Kotlin errors into annotations via `gradle_errors.sh`, like the unit-test step.
  New workflow «Screenshots» (`screenshots.yml`): after every push to `main` touching `android/` or `ios/` (and
  by hand) the instrumented test `ScreenshotTourTest` (API 34 emulator) and the XCUITest `ScreenshotTourTests`
  photograph the main screens in light and dark mode with the store demo data; a publish job replaces the orphan
  branch `screenshots` (`git fetch origin screenshots`). Both tests skip themselves in the regular runs. No new
  dependencies or actions.

## 16.2.2 (build 158)

* Tests only (no change for users): 19 new Android unit-test files – network fees, futures funding and open interest with their fallbacks between exchanges, candle sources (error branches), cache codecs (cycle, market extras, portfolio history), backup journal, logo pauses, alarm drafts, portfolio form prefill and mappings, price formatting, widget snapshot codec, explorer state.

**Technical notes**

* Build 158: new unit tests (app/src/test) for `:data` – `GasDataSource`, `FuturesDataSource`,
  `FuturesDataSourceFundingHistory`, `CandleTime`/`OkxCandles`, error branches of the exchange candle sources,
  `CycleCacheCodecs`, `MarketExtraCodecs`, `PortfolioHistoryCodecs`, `BackupJournalCodec`, `LogoImagePauses`,
  `SharedPreferencesStore` (in-memory fake) – and for `:app` logic – `AlarmDraft` condition switches,
  `PortfolioSnapshotCodec` readers, transaction-form prefill/derived values and entity mappings, history load helpers,
  explorer state holders, watchlist group filter, `PriceFormat` basics. Remaining gap of the app logic: ViewModels and
  controllers depend on final Android-bound classes; without Robolectric/MockK or interfaces (owner's decision, not
  wanted) they stay covered by the device tests only.
  `code_metrics.py`: `DEPS_CEILING` 15 → 14 (today's maximum), no warning tier by default (it sat above the
  ceiling).

## 16.2.2 (build 157)

* Code cleanup (no change for users): the three largest Android screens get their helpers injected instead of 16–20 single dependencies each (watchlist refresh, start choice and edits through factories; «Test alarm»/«Test voice» and «Check on time» in the settings, USDT valuation and portfolio-alarm saving in their own small classes) – no screen needs more than 14 dependencies any more, and CI now stops at 15. On iPhone, eight thread-safe helpers switch to Apple's checked lock type, so the Swift 6 compiler verifies them instead of trusting a marker. Build numbers removed from code comments (history lives in this changelog); CI caps the few that still name early storage formats.

**Technical notes**

* Build 157: `WatchlistRefreshController`, `WatchlistStarterController`, `WatchlistEditController` are created
  through Dagger assisted factories (`@AssistedInject`, nested `Factory`; the ViewModel passes `viewModelScope` and its
  callbacks) – `WatchlistViewModel` 20 → 14 dependencies (`@Inject internal constructor`, the factories are internal).
  `SettingsTestActions` (test alarm, test voice) and `ExactCheckControl` (exact alarm, last checks) take five
  dependencies out of `SettingsViewModel` (17 → 14); `UsdtPeg` (depeg reading store + checker) and
  `PortfolioAlarmSaving` (alarm repository + notifier) out of `PortfolioViewModel` (16 → 14). `code_metrics.py`
  `DEPS_CEILING` 20 → 15. Constants of the edit controller in `WatchlistEditLimits.kt` (400-line limit). iOS:
  `OwnPairRegistry`, `CandleNeeds`, `CandlePills`, `ReferenceProviders`, `ResumeOnce` (DayReferences) and
  `TaskCancelBox`, `CheckResultBox`, `OnceGate` (BackgroundTaskBoxes) use `OSAllocatedUnfairLock` and are checked
  `Sendable` (`@unchecked Sendable` 104 → 96, ceiling lowered). Build numbers removed from comments (151 places);
  `line_limit.py` caps them at 6 (`BUILD_REFS_CEILING`; the rest name storage formats of early test versions).

## 16.2.2 (build 156)

* Code cleanup (no change for users): names inherited from the original BitcoinChecker that described nothing are replaced by names that say what the code does – `FormatUtilsBase` → `DisplayFormat`, `TimeUtils` → `EpochTime`, `PostRequestInfo` → `PostRequest`, `TickerImpl` → `MutableTicker`, `ComboBox` → `DropdownField`, `LogBox` → `HttpLogPanel`, `ErrorScreen` → `LoadErrorScreen`, `LoadingScreen` → `SkeletonLoadingScreen`, `Scrollbar.kt` → `ListScrollbar.kt`, `JsonUtils.kt` → `JsonExtensions.kt` (Android and iOS alike). The «Add pair» screen keeps its state in three groups (all-pairs list, search hits, DEX search) instead of 17 loose fields. All Android lint warnings from builds 150–155 fixed except one documented false positive (DEVELOPMENT.md, «Known warnings»): KTX helpers instead of `Uri.parse`/`edit().apply()`, symmetric padding in the portfolio widget, watch app backup rules for Android 12+, watch hint «Coins: max. 8» in 31 languages.

**Technical notes**

* Build 156: cleanup without behaviour change. Renamed (Kotlin and Swift, files and identifiers): `FormatUtilsBase` →
  `DisplayFormat`, `TimeUtils` → `EpochTime` (epoch milliseconds and exchange timestamps), `PostRequestInfo` →
  `PostRequest` (`getBulkTickersPostRequest`, iOS `bulkTickersPostRequest`), `TickerImpl` → `MutableTicker`,
  `ComboBox` → `DropdownField`, `LogBox`/`ExplorerLogBox` → `HttpLogPanel`/`ExplorerHttpLogPanel`, `ErrorScreen` →
  `LoadErrorScreen`, `LoadingScreen` → `SkeletonLoadingScreen`, `simpleVerticalScrollbar` → `verticalScrollbar`
  (`ListScrollbar.kt`), `JsonUtils.kt` → `JsonExtensions.kt`; iOS test helper `AlarmLogRecorder`. The vocabulary
  «Market» for an exchange (≈ 12 000 places, also the backup key `marketKey`) stays on purpose. `ExplorerViewModel`
  groups its state in `ExplorerBulkState`, `ExplorerSearchHits` and `ExplorerDexState` (`ExplorerState.kt`); 35
  instead of 49 private/internal properties, `PROPS_CEILING` lowered to 35. Lint: `toUri()`/`edit { }` (core-ktx
  now declared in `:wear`), `paddingEnd` in `widget_portfolio_list_entry.xml`, `wear/res/xml/data_extraction_rules.xml`
  (nothing backed up or transferred), `watch_choose_hint` without a plural noun.

## 16.2.2 (build 155)

* New: Apple Watch app (watchOS 10), same as on Wear OS. It shows price and 24h change of up to 8 coins (against USDT), fetched directly from Binance (Bybit and OKX as fallback) – no account, no server. Which coins: «Like phone» (your favourites ★) or an own list of the common coins – chosen on the watch or on the iPhone under Settings › Widgets › «Watch» (also found by the settings search). Only this choice and the coin symbols are synced, directly between iPhone and watch (WatchConnectivity). Complication for the watch face (circular, rectangular, inline, corner) with the first coin. VoiceOver reads every row. 31 languages; privacy page updated.
* CI: the Swift checks (escape hatches, line limit, privacy manifest scan) and the release app inspection also cover the watch app.

**Technical notes**

* Build 155: Apple Watch app. New folders `ios/WatchCore` (`WatchCoins`, `WatchSettings` – same JSON and rules as
  `:marketdata/watch`, compiled into the app for `WatchSettingsParityTests` and the settings card), `ios/Watch` (target
  `CryptoCheckerWatch`, watchOS 10, `com.cryptochecker.app.watchkitapp`, embedded under `Watch/` of the app: SwiftUI
  list, `WatchModel` (@Observable), `WatchLink` (WCSession, application context `["json": …]`); `Watch/Device` =
  `WatchQuoteParser`/`WatchPriceSource` (Binance, Bybit, OKX via URLSession), `WatchStore` (App Group defaults),
  `WatchText`, also compiled into the complication) and `ios/WatchWidgets` (target `CryptoCheckerWatchWidgets`,
  WidgetKit accessory families, timeline every 15 min, reload if quotes are older than 10 min). iPhone:
  `PhoneWatchLink` (@MainActor, WCSession; favourites from every `AppData.snapshot` adoption, sends only when paired
  and the watch app is installed), `WatchSettingsSection` on the Widgets page (anchor `widgets.watch`, scrolls there
  from the search), iOS-only hint `ios_settings_watch_hint`. Generator and `project.yml` know both watch targets
  (`Localizable.xcstrings` and `PrivacyInfo.xcprivacy` in each bundle). CI: `swift_escape_hatches.py`,
  `line_limit.py` and the privacy scan cover the watch folders (+1 `@unchecked Sendable`: `WatchLink`);
  `ios_release_audit.py` checks the embedded watch app and its extension (privacy manifest, version).

## 16.2.2 (build 154)

* New: Wear OS app. It shows price and 24h change of up to 8 coins (against USDT) and fetches them itself directly from Binance (Bybit and OKX as fallback) – no account, no server. Which coins: «Like phone» (the coins of your watchlist favourites ★) or an own list of the common coins – chosen on the watch or on the phone under Settings › Widgets › «Watch». Only this choice and the coin symbols are synced between phone and watch (Wear data layer of Google Play services; no prices, amounts or portfolio data). A complication shows the first coin on the watch face (short: symbol and change; long: price and change) and opens the app on tap. Alarms keep arriving from the phone as before. 31 languages; privacy page updated. The Apple Watch app follows in build 155.

**Technical notes**

* Build 154: new module `:wear` (Wear OS 3+, `minSdk 30`, same `applicationId`, `versionCode` = 90 000 + phone build
  read from `app/build.gradle.kts`, standalone). `WatchQuotes` (Binance `ticker/24hr?symbols=…`, Bybit and OKX spot
  tickers as fallback; `WatchQuotesTest`), `WatchPriceSource` (HttpURLConnection, 10 s, 4 MB cap, own user agent),
  `WatchStore` (`WatchSettings` + last quotes in SharedPreferences `watch`, no backup),
  Compose for Wear UI (`WatchApp`), `PriceComplicationService` (SHORT_TEXT/LONG_TEXT, 15 min, reloads quotes older
  than 10 min). CI: `:wear:lintRelease`, `:wear:assembleRelease`; supply chain writes `wear/gradle.lockfile`.
  List choice: `WatchSettings` and `WatchCoins` (top 30, max 8, `fromFavorites`) live in `:marketdata/watch` for phone
  and watch; mode PHONE (base coins of the favourites, in sort order, max 8) or OWN, `time` = last change, `merge`:
  mode/own list from the newer side, phone list always from the phone (parity fixture `watch_settings.json`,
  `WatchSettingsParityTest`). Sync over the Wear data layer (`play-services-wearable`): one data item per node at
  `/cc/watch` with the JSON; watch `WatchDataLayer` + `WatchDataListener` (capability `cryptochecker_watch` in
  `wear/res/values/wear.xml`), phone `PhoneWatchSync` (prefs `watch_sync`, publishes only when a node has the
  capability, asks Play services only on a change or while «Widgets» is open) + `PhoneWatchListener` (exported for
  Play services; allowed in `manifest_policy.py`). UI: `WatchSettingsCard` on the Widgets page (anchor `widgets.watch`).
  `composeCompiler.includeComposeMappingFile = false` in `:wear` as in `:app`.

## 16.2.2 (build 153)

* Android code structure (no change for users): the 20 domain files (exchange and address parsers, alarm log, address and xpub caches, backup entries of watched addresses, coin lists, gas fees …) read and write JSON with the domain's own JSON instead of `org.json`; the storage layer converts at the boundary. `:domain` no longer uses `org.json`.

**Technical notes**

* Build 153 (KMP prep, part 3): all `:domain` files on `util/json` (imports renamed, `JSONObject.NULL` → `JsonNull`);
  `MarketError.toMarketFailure` maps `JsonException` and (by class name) `org.json.JSONException` from `:data` to
  `Parse`. Boundary helpers `asDomainJson()` / `asOrgJson()` in `:data` (`DomainJsonBridge.kt`, via the text) for the
  backup codecs (watched addresses, CSV restore state). Remaining Android/JVM bits in `:domain`: Room annotations on
  `WatchEntity`/`AlarmEntity`, OkHttp's URL parser in `LogoUrlPolicy`, `java.*` (time, math, concurrency).

## 16.2.2 (build 152)

* Android code structure (no change for users): `:domain` gets its own small JSON reader/writer (`util/json`) that behaves like Android's `org.json` – same conversions, same number and character output – checked against `org.json` in a compatibility test. The domain files switch over in the next builds (step towards Kotlin Multiplatform).

**Technical notes**

* Build 152 (KMP prep, part 2): `util/json` in `:domain` – `JsonObject`, `JsonArray`, `JsonNull`, `JsonException`,
  `JsonTokener` with AOSP `org.json` semantics (lenient reader: comments, `=`/`=>`, `;`, empty array slots, hex/octal,
  unquoted words; `get*` coercions; `optString` of null = "null"; `put(name, null)` removes; whole doubles written
  without ".0", "-0", `\/` and `\uXXXX` escapes). `JsonCompatibilityTest` compares it with AOSP `org.json` (unit-test
  dependency `android-json`). Next: switch the 20 domain files over (string boundaries towards `:data`).

## 16.2.2 (build 151)

* Android build (no change for users): one version of Room's annotation-processing library (2.8.5) in every module, `:domain` no longer declares the unused Timber and AndroidX Core libraries; lockfiles and checksums renewed by the «Supply chain update» workflow.

**Technical notes**

* Build 151: `app/build.gradle.kts` forces `androidx.room:room-compiler-processing` to `libs.versions.room` in every
  configuration (Hilt's compiler pulled 2.8.4); `:domain` drops `timber` and `androidx.core.ktx` (unused since 146);
  lockfiles/verification metadata from «Supply chain update».

## 16.2.2 (build 150)

* New (Android): price overlay. Settings › Updates › «Price overlay» (off by default) shows the pairs of the permanent price notification – at most 3 – as a small, semi-transparent pill over other apps: symbol, price and change in the price colours. Drag it anywhere, it snaps to the edge; tap opens the app; drag it onto the × at the bottom to turn it off. It updates whenever the app has new prices (live while open, otherwise at the background interval), runs without its own service and needs the Android permission «Display over other apps», which you grant and can revoke yourself. TalkBack reads the prices and offers «Close overlay». Not available on iPhone (iOS does not allow overlays). 31 languages; privacy page updated (permissions table).

**Technical notes**

* Build 150 (Android): price overlay – `overlay/PriceOverlay` (singleton started in `CryptoCheckerApp.onCreate`,
  combines `settings` and `observeWatches()`, one `TYPE_APPLICATION_OVERLAY` window with plain views, no service; the
  overlay window keeps the process perceptible), `overlay/PriceOverlayRules` (pairs like the permanent price
  notification via `PriceNotificationRule.showNow`, max 3; snap/clamp/close zone; `PriceOverlayRulesTest`). Setting
  `priceOverlay` (DataStore key `price_overlay`, device-local, not in backups), row `PriceOverlayRow` under the
  permanent notification (anchor `updates.overlay`, search entry), position in SharedPreferences `price_overlay`.
  Permission `SYSTEM_ALERT_WINDOW` moved from FORBIDDEN to EXPECTED in `manifest_policy.py`; privacy page and
  PLAY_STORE.md list it.

## 16.2.2 (build 149)

* New: compare 2 to 4 pairs. In the watchlist, long-press to select pairs and tap «Compare»: one line per pair in % since the start of the period (24h or «Today», 7 days, 30 days, 1 year), below each pair its price, 24h, 7 days, 30 days and the distance to the 30-day high. Every line has its own colour and dash style. Uses the same candles as the pair's chart – no extra server. TalkBack/VoiceOver read the chart and each row. Android and iPhone, 31 languages.
* Tests: the iOS accessibility audit runs in three groups of audit types (shorter runs, fewer timeouts; UI test step limit 45 min; a second full audit right after a timeout crashed the test runner on main 147), and the CI now also reports UI test cases that fail without an error line of their own.

**Technical notes**

* Build 149: compare view – `CompareMath` (Android `:domain` `domain/watch`, iOS `Shared/Insights`; parity
  `compare.json`): `normalized` (% from first valid close), `figures` (7/30 days vs. the last daily close starting
  ≤ now − 7/30 d, distance to max(30-day high, price)), `bounds`. UI `CompareScreen` (Android full-screen dialog like
  «Why?», iOS `navigationDestination`) from the selection bar («Compare», 2–4 pairs, otherwise a hint banner); lines
  from `loadSheetChart`/`SheetChartStore` per range (day basis: since 00:00 like the sheet), figures from the 1-year
  daily candles. Line colours from the theme plus dash patterns.
  CI: `repro_compare.py` names the first differing line of differing text entries in the archives (R8
  `proguard.map` differed twice on main, APK identical) or «same lines in a different order».

## 16.2.2 (build 148)

* Settings search finds «Recent checks» (both platforms) and «Check on time» (Android) as their own results and jumps straight to them.
* Tests: the iOS accessibility audit only logs contrast findings for text under the translucent navigation bar (after a timed-out audit round the list can be scrolled); main 146 failed on this once.

**Technical notes**

* Build 148: settings search entries `updates.check_runs` (both, anchor of the check-runs card) and `updates.exact`
  (Android, anchor of the exact-checks row); no new strings.

## 16.2.2 (build 147)

* Tests only (no change in the apps): 132 small test files merged by topic into 30 files (16 Android, 14 iOS) – each test class unchanged, same names and fixtures.

**Technical notes**

* Build 147 (R, test files): small test files (≤ 80 lines, one top-level class) merged per directory and name
  prefix into `<Prefix>{ParitySet|UiSet|RuleSet|DataSet|Set}[n]Test.kt` / `<Prefix>Set[n]Tests.swift` (≤ 380 lines,
  classes unchanged, imports merged); references to the old file names now name the class. New small tests go into
  the matching set file.

## 16.2.2 (build 146)

* Android code structure (no change for users): `:domain` no longer uses Android or Timber types for its stored state and logging – the alarm snooze, the last USDT/USD reading, the bulk delisted state and the background error counter read and write through a small key-value interface (the app keeps the same SharedPreferences files), and the domain logs through `DomainLog`, which the app forwards to Timber. Step towards Kotlin Multiplatform.

**Technical notes**

* Build 146 (KMP prep, part 1): `util/KeyValueStore` + `KeyValueStores` in `:domain`, SharedPreferences adapter
  `data/local/SharedPreferencesStores` (same file names), provided in `LocalDataModule`; `AlarmSnoozeStore`,
  `DepegReadingStore`, `BulkDelistedStore` and `BackgroundErrorCounter.install` use it. `util/DomainLog` replaces
  Timber in `:domain` (sink set in `CryptoCheckerApp`). `rethrowIfCritical` recognises `DeadSystemException` by class
  name. Still Android/JVM-bound in `:domain`: Room annotations on `WatchEntity`/`AlarmEntity`, `org.json`, OkHttp's URL
  parser in `LogoUrlPolicy`; the unused `timber`/`core-ktx` dependencies go at the next lockfile update.

## 16.2.2 (build 145)

* Portfolio widget, view «List»: new option «Bookings under the coins» (Off / Purchases / Sales / Both, default Off). If there is room left below the coins, each coin shows its bookings, indented and ordered by date: quantity (outgoing with «−»), the note shortened to 6 characters and today's value. Deposits and incoming transfers count as purchases, withdrawals as sales, fees paid in the coin are part of their booking – with «Both» the lines add up exactly to the coin's holding. Coins always come first; a coin shows all of its bookings or none. TalkBack/VoiceOver read each booking. Android and iPhone, 31 languages.

**Technical notes**

* Build 145: portfolio widget list with bookings per coin – `PortfolioWidgetEntries` (Android `:domain`, iOS
  `Shared/Portfolio`): one entry per ledger step with a holdings change (`PortfolioLedger.events` +
  `PortfolioHoldingsBook`, so «both» sums to the holding), note via `shortNote` (6 graphemes + «…»), today's value,
  at most 40 per coin (more → none); stored in the snapshot (`entries` per position, Android JSON key `"e"`), scaled
  with the positions in «USDT only»/rebasing. `assign` places them only in the height left below the coins (all or
  none per coin). Setting: Android `WidgetPrefs.portfolio_entries_<id>` (configure screen, list view only), iOS
  `PortfolioWidgetIntent.entries`. Parity: `widget_portfolio_entries.json`.

## 16.2.2 (build 144)

* Android code structure (no change for users): manual refresh (button and pull), the widget-chart reloading after a refresh, the chart of the action sheet with the shared chart geometry, the chart summary for TalkBack and the bounded memory cache moved to `:domain`; the app hands its widget updater, refresh statistics and network state in through narrow interfaces (`ChartWidgets`, `ManualRefreshStats`, `ManualRefreshNet`).

**Technical notes**

* Build 144 (R, step 9): `ManualRefresh` and `ChartRefresher` in `:domain` behind `ManualRefreshStats`,
  `ManualRefreshNet` and `ChartWidgets` (adapters in `PriceRefresherAdapters.kt`, providers in `RefreshModule`);
  `domain/watch/SheetChart`, `widget/WidgetChartGeometry`, `util/ChartSummary` and `data/BoundedCache` (from `:data`,
  same package) in `:domain`. Left in `app/…/domain`: `ActivityMonitor`, `DepegAlarmChecker`, `GasAlertChecker`,
  `DayReferences`, `RefreshSideChecks` (data sources, notifications), `DiagnosticReport` (string resources).

## 16.2.2 (build 143)

* Android code structure (no change for users): the settings storage (`SettingsRepository` with its DataStore, keys and reset of market alerts), the sparkline/day-reference candles, the live price stream, the market repository, adding several pairs at once and the address refresh moved to `:data` (13 files). The stored state of the market and gas alerts has its own small objects (`ZoneCheckPrefs`, `GasAlertPrefs`), so the settings no longer reach into the background workers.
* Tests: the iOS accessibility audit retries an audit that ran out of time up to twice (slow CI simulators); findings are never retried.

**Technical notes**

* Build 143 (R, step 8): 13 more files to `:data` – `settings/SettingsRepository`, `SettingsDataStore`, `SettingsKeys`,
  `SettingsReading`, `SettingsLockFallback`, `MarketAlertReset` (new `settings/MarketAlertPrefs.kt`: `ZoneCheckPrefs`
  and `GasAlertPrefs` with the prefs names, keys and the gas lock; `ZoneCheckWorker` and `GasAlertChecker` keep their
  constants as aliases, `GasAlertChecker.lock` returns the shared mutex), `data/SparklineRepository`,
  `data/live/LivePriceStream`, `data/MarketRepositoryImpl`, `data/AddMoments`, `data/BulkAddRunner`,
  `data/portfolio/WatchedAddressRefresher`. Still in `:app/data`: `BackupManager*`/`RestoreRun` (app lock),
  `CoinLogoSync` (widgets), `SheetChartRepository` (widget chart geometry), `PortfolioAlarms` (notifications).

## 16.2.2 (build 142)

* Android code structure (no change for users): new Gradle module `:data` with the storage and network layer – both Room databases with their DAOs and encryption, the watchlist and portfolio repositories, exchange, candle, futures and address data sources, logo store and parts of the backup (159 files, packages unchanged). Room exports its schemas as before to `app/schemas`; the app's migration tests take them from there. The logo downloads get the app's user agent from the app (`AppUserAgent`) instead of reading the version themselves.

**Technical notes**

* Build 142 (R, step 7): Gradle module `android/data` (Android library, namespace `com.cryptochecker.data`, KSP for
  Hilt and Room, Room plugin) with 159 files: most of `app/…/data` (both databases, DAOs, entities of markets and
  portfolio, `WatchRepository*`, `PortfolioRepository*`, data sources, `LogoImageStore`, `CoinLogoRepository`, backup
  codecs and crypto), plus `settings/AddressServerStore`, `SettingsBackupTarget`, `SettingsSlices`,
  `util/ConnectivityMonitor`, `notification/AlarmDeliveryMarkers`, `widget/PortfolioSnapshotSeal`/`KeyStore`,
  `domain/refresh/AlarmLogStore`, `domain/alarm/DerivativesAlarmData`; `util/AppVisibility` went to `:domain`. Room:
  `room { schemaDirectory("$rootDir/app/schemas") }` in `:data`, `:app` drops the Room plugin and compiler and adds
  `app/schemas` to its androidTest assets itself (what the plugin did). `LogoImageStore` takes the user agent as
  `@AppUserAgent String` (provided by `di/UserAgentModule`, separate so that UI tests replacing `RemoteDataModule` still get it) instead of `BuildConfig`. Still in `:app` because of
  settings, notifications or widgets: `SettingsRepository` (with `MarketAlertReset` → zone/gas workers),
  `BackupManager*`, `SparklineRepository`, `LivePriceStream`, `MarketRepositoryImpl`, `CoinLogoSync`,
  `SheetChartRepository`, `PortfolioAlarms`, `WatchedAddressRefresher`, `DayReferences`, `ActivityMonitor`. Hilt modules
  stay in `:app`. `data/src/main/AndroidManifest.xml` declares INTERNET and ACCESS_NETWORK_STATE (lint `MissingPermission`
  in the library; the merged manifest is unchanged). CI: `:data:lintRelease`, coverage `data=data`, device-test paths,
  `data/gradle.lockfile`, `user_agent_check.py` reads `android/data/…/HttpUserAgent.kt`; source-guard tests look in
  `android/data` too.

## 16.2.2 (build 141)

* Android code structure (no change for users): the price refresh itself (`PriceRefresher` – fetching, backoff, day references, deferred 24-h values, single refresh, side checks afterwards) moved to `:domain`. It reaches the watchlist, refresh statistics, candles, widgets, network state and the side checks through narrow interfaces (`PriceRefresherPorts`); the app builds it in `RefreshModule`. The shared list of blocked sources (`BlockedSources`) has its own file.

**Technical notes**

* Build 141 (R, step 6): `PriceRefresher` (+ `PriceRefresherAfter`, `…Deferred`, `…One`) and `WidgetRefreshState` in
  `:domain`. New ports in `domain/refresh/PriceRefresherPorts.kt`: `RefresherStore` (watch list, alarms,
  `clearChanges`, `fillChange`), `RefresherStats`, `RefresherSparklines`, `RefresherOnline`, `RefresherWatchWidgets`,
  `RefresherDayReferences`, `RefresherCharts`, `RefresherSideChecks` and `SideCheckExtra` (formerly the nested
  `RefreshSideChecks.Extra`); settings and widget drawing reuse `RefreshSettings`/`RefreshWidgets`. Adapters in
  `app/…/domain/refresh/PriceRefresherAdapters.kt`, provider in `RefreshModule`. `BlockedSources` split out of
  `CandleDataSource.kt` (same package) and moved. Still in `:app`: `ManualRefresh`, `ChartRefresher`, `DayReferences`,
  `RefreshSideChecks` (with `ActivityMonitor`, depeg and gas checkers) – they use Android, DAOs or notifications.

## 16.2.2 (build 140)

* Android code structure (no change for users): the evaluation after new prices – alarms, price notifications and announcements (`RefreshEffects`), saving and evaluating fetched prices (`RefreshResultProcessor`) and saving live prices (`LivePriceApplier`) – moved to `:domain`; the app hands its storage, notification and speech classes in through a Hilt module (`RefreshModule`) instead of a second constructor in each class. Moved along: alarm delivery with its notification budget, the app-wide background scope with its error counter, and the «database key unavailable» error.

**Technical notes**

* Build 140 (R, step 5): `RefreshEffects` (+ `RefreshEffectsAlarmKinds`), `RefreshResultProcessor` and
  `LivePriceApplier` in `:domain`. Their second `@Inject` constructor (app classes → `as…()` adapters) is gone; Hilt
  builds them in `app/…/di/RefreshModule.kt` (`@Provides @Singleton`), tests keep using the primary constructor with
  fakes. Moved along so they compile: `domain/alarm/AlarmDelivery` (`AlarmDeliveryMarkerStore`),
  `notification/NotificationBudget`, `util/AppScope` (`appScope`, `appExceptionHandler`), `util/BackgroundErrorCounter`
  and `data/local/DatabaseKeyUnavailable.kt` (`DatabaseKeyUnavailableException`, `isDatabaseKeyUnavailable`, split out
  of `EncryptingOpenHelperFactory.kt`, same package). For Kotlin Multiplatform later: `appScope` and the error counter
  still use Timber and SharedPreferences.

## 16.2.2 (build 139)

* Android code structure (no change for users): the interfaces through which the price evaluation reaches storage, notifications, speech and data sources (`RefreshPorts`) moved to `:domain`, their adapters to the app classes stay in the app. With them moved the settings model (`AppSettings`, accent colour, price colour scheme, update mode), the watchlist write models (`PriceWrite`, `ErrorWrite` …) and the alarm data types (`VolumeSpike`, `WindowRanges`, `DerivativesValues`). The accent colour's name and logo resources are now looked up in the app.

**Technical notes**

* Build 139 (R, step 4): `RefreshPorts.kt` split – the interfaces (`RefreshStore`, `RefreshNotifier`,
  `RefreshSpeaker`, `RefreshSpokenText`, `Alarm*Source`, `RefreshSettings` …, now public) are in `:domain`, the
  adapters (`asRefreshStore()` …, private wrapper classes) in `app/…/domain/refresh/RefreshPortAdapters.kt`. Types the
  interfaces need moved too: `data/WatchRepositoryModels` (KDoc no longer links `WatchDao`), `settings/AppSettings`,
  `AppSettingsDefaults`, `AccentColor`, `PriceColorScheme`, `ColorDifference`, `UpdateMode`, `VolumeSpike` and
  `WindowRanges` (split out of their data sources, same package) and `DerivativesValues` (formerly the nested
  `DerivativesAlarmData.Values`). `AccentColor` has no resource ids any more: `labelRes` and `logoRes(dark)` are
  extensions in `app/…/settings/AccentColorResources.kt` (callers import them). The fiat list moved to
  `domain/convert/FxCurrencies.ALL` (`FxRateSource.CURRENCIES` points there; `defaultCurrency` needs it). Next: `RefreshEffects`,
  `RefreshResultProcessor` and `LivePriceApplier` need ports for the remaining app types they touch.

## 16.2.2 (build 138)

* Android code structure (no change for users): the «Why is it moving?» analysis (activity, BTC correlation, broad market, funding and open-interest trends, reasons and summary), near-extreme and derivatives alarm rules, alarm sentences and templates, crypto pulse and market summary, and the futures source table and parsers moved to `:domain` (21 files). The futures data model `FuturesInfo` has its own file.

**Technical notes**

* Build 138 (R, step 3): 21 more files to `:domain` – `domain/activity` without `ActivityMonitor` (analyzer,
  models, sensitivity, why/factors/summary, broad market, BTC correlation, candle/OI series, funding trend),
  `domain/alarm` `NearExtreme`, `DerivativesAlarm`, `AlarmSentence`, `AlarmTemplates`, `domain/market`
  `CryptoPulse`, `MarketMove`, `MarketPlainSummary`, and `data/remote/FuturesExchanges` (`FuturesSources`,
  `FuturesParsers`) with `FuturesInfo`, split out of `FuturesDataSource.kt` (same package). The rest of `app/domain`
  (refresh flow, `ActivityMonitor`, depeg/gas checkers, `DerivativesAlarmData`) uses repositories, DAOs or
  notifications directly; moving it needs interfaces (ports) in `:domain` implemented in `:app`/`:data`.

## 16.2.2 (build 137)

* Android code structure (no change for users): the portfolio logic (FIFO, average cost, history, tax engines and exports, CSV import, address checks), the remaining alarm rules, currency conversion and coin lists moved to the module `:domain` (116 more files, packages unchanged). App code reads nullable values of these models through local copies, because Kotlin does not smart-cast properties from another module. The CI coverage gate counts the module `:domain` again.

**Technical notes**

* Build 137 (R, step 2): 116 more files to `:domain` – the rest of `domain/portfolio` (incl. `csvimport/`), the
  remaining `domain/alarm` rules (`AlarmEdit`, `AlarmLog`, `DepegAlarm`, snooze and depeg stores …), `domain/convert`,
  `domain/logos` (`CoinLists`, `CoinMeta`), `domain/mirror`, `AlarmLogCollector`, `CheckOrigin`, `PriceWriteOrder`
  and `util/TextLimit`. Selection by the dependency graph of both modules (a file moves only if everything it uses
  in `:app` moves too); still in `:app` because of a dependency: `AlarmDelivery` (`NotificationBudget`),
  `AlarmLogStore` (`PortfolioSnapshotSeal`), `SheetChart` (`WidgetChartGeometry`). `internal` became public in 43
  files. Kotlin does not smart-cast a public `val` declared in another module: app code takes a local copy
  (`PortfolioAddressRow`, `AlarmLogTexts`, `SettingsViewModel`, …) or uses `?.`/`orEmpty()`. Coverage gate:
  `coverage_summary.py` lists `domain=domain` – without it the files of `:domain` in the merged `:app` report had no
  sources, counted as generated and dropped out of «logic» (with the portfolio logic moved: 43 % instead of ~60 %).

## 16.2.2 (build 136)

* Add pair (both platforms): if an exchange doesn’t answer while its pair list loads (maintenance, timeout, no network), the card names it – «LATOKEN isn’t responding right now – pair list not loaded. Try again later.» – and the button reads «Try again» instead of showing the same card without a word.
* Android code structure (no change for users): new Gradle module `:domain` with the rules, calculations and models that need neither screens nor storage (139 files, packages unchanged). First step of splitting the app into modules; CI lints the new module, the device tests and the supply-chain update know it.
* Build security: Gradle now also checks the PGP signatures of the Android dependencies against a stored keyring (`android/gradle/verification-keyring.keys`), in addition to the checksums; verification metadata and lock files include the new module.

**Technical notes**

* Build 136 (R, step 1): Gradle module `android/domain` (Android library, namespace `com.cryptochecker.domain`,
  Hilt/KSP for its `@Inject` classes) with the 139 files of `app` that depend on no screen, storage or network flow:
  most of `domain/` plus `data/local/model/WatchEntity` and `AlarmEntity` (Room annotations only; DAOs and the
  database stay in `:app`), `MarketRepository`, `BackupRestoreLimits`, `AssetScope` and four `util` helpers. Packages
  are unchanged (`com.cryptochecker.app.*`), so no import changed; `internal` in moved files became public (module
  boundary). `:app` depends on `:domain`, which exposes `:marketdata`, coroutines and Room runtime as `api`.
  Selection rule for later moves: a file may move only if everything it uses has moved (no `R`, `BuildConfig`, UI,
  DAO/database or Hilt entry points). Next steps: interfaces for repositories so the refresh/alarm flows can move,
  then drop the Android dependencies of `:domain` (Kotlin Multiplatform), then `:data`. CI: `:domain:lintRelease`,
  device-test paths, `domain/gradle.lockfile` in the supply-chain update, mutation targets resolved in either module,
  `FileLengthTest` covers the module.
  Add pair: `SearchLists.failed` / `ExplorerSearchLists.failed` hold the names of exchanges whose pair list did not
  load in the last «Load lists» run (sync error or empty list) and are still missing; the card shows
  `explorer_lists_failed_one` / `_many` (two strings, not a plural: Xcode requires the count in plural
  variants; names joined with «, ») before the remaining `explorer_lists_missing` and
  the button becomes `action_retry`.
  Supply chain (SEC-2 done): the run of «Supply chain update» on this build wrote `verify-signatures=true` with
  `android/gradle/verification-keyring.keys`; checksums remain for unsigned artifacts. Lock files gained the
  release unit-test configurations (no version changed).

## 16.2.2 (build 135)

* Tests: all Android instrumented UI tests use the v2 Compose test rule (Compose coroutines on the UI thread); the deprecated v1 rule is gone.
* Macro calendar: the self-test also parses the real BLS, FOMC and BEA pages as served on 26 October 2026 (stored in `.github/scripts/testdata/macro`), with the known number of dates per page – a layout change shows up in the test, not only in the live run.
* Build: the Kotlin warnings in the test sources are fixed; a vendor verification file of kotlinx-serialization is no longer packed into the APK/AAB.

**Technical notes**

* Build 135 (audit 134, F-10/F-19/F-23): every instrumented Compose test uses the v2 rule
  (`androidx.compose.ui.test.junit4.v2.createAndroidComposeRule`). `macro_calendar.py --self-test` also runs
  `recorded_pages_test` against the gzip-compressed real pages in `.github/scripts/testdata/macro` (BLS schedule and
  release pages, FOMC, BEA; expected counts and first/last dates; refresh them with the parser). Test-source Kotlin
  warnings fixed (redundant `!!`/conversions, unchecked casts suppressed locally, nullable `parentFile`).
  `packaging.resources.excludes` drops `META-INF/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/
  verification.properties`. The remaining Gradle deprecation (`Configuration.setVisible`, removed in Gradle 11)
  comes from an applied plugin, not from the build scripts.

## 16.2.2 (build 134)

* Tests: the Android UI flow tests use the current Compose test rule (v2), which runs Compose coroutines on the UI thread – the old rule let them continue on background threads and caused the rare Compose crashes in the CSV import/undo test.
* Tests: the iOS accessibility audit taps the storage notice's close button once more if a slow simulator missed the first tap.

**Technical notes**

* Build 134: `UiFlowsTest` uses `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule`. The v1 rule ran
  Compose-scoped coroutines on an unconfined test dispatcher, so collectors resumed on the thread that woke them
  (`flowOn(Dispatchers.Default)` flows, DataStore) and wrote snapshot state there – the rare «Reentrant composition» /
  `SlotTable` crashes of `importCsvAndUndo`. New instrumented UI tests use the v2 rule. iOS `AccessibilityAuditTests`
  taps the storage notice's close button a second time when the first tap was missed.

## 16.2.2 (build 133)

* Exchange picker in sections: Spot · Futures · DEX pools · Forex (reference rate), favorites on top, search across all sections. Within a section the exchanges are sorted by trading volume on the install date (fetched once from CoinGecko's public exchange lists); without it alphabetically as before.
* Coin search: after the start exchange and your favorites, the pairs of the largest exchanges come first (volume instead of alphabet).
* New exchange: Bitunix Futures (perpetuals; Bitunix spot has no 24 h ticker). The API names no contract type, so stock contracts are not marked TradFi.
* DEX pools: GeckoTerminal as fallback when DexScreener answers with a server error or no longer knows a pool; a GeckoTerminal rate limit never pauses DexScreener.
* Forex: daily ECB reference rates (via Frankfurter) as currency pairs, labelled «reference rate, daily», never live and without alerts.
* Privacy page: Bitunix, GeckoTerminal, the one-time exchange lists of CoinGecko and the forex reference rates. Store descriptions: 56 markets on 45 exchanges.
* CI on main: release unit tests run again (AGP 9 had stopped creating `testReleaseUnitTest`).
* Clean-up before the next review: code comments (Android and iOS), both app READMEs, DEVELOPMENT, CONTRIBUTING, SECURITY, the privacy page and the CI comments checked against the app and corrected; translations made consistent (Portuguese, Dutch and Spanish portfolio terms, one name for Live Activities per language, newest texts use each language's word for alerts).

**Technical notes**

* Build 133: exchange list. `MarketCategory` (SPOT, FUTURES, DEX, FOREX) and `Market.category` on both platforms
  (futures classes, Hyperliquid, Aster, Lighter, Deribit = FUTURES; DexScreener = DEX; EcbReference = FOREX; Bybit's
  constructor field is now `apiCategory`). `ExchangeOrder` (app domain / iOS Shared/Core): sections in category
  order, within by the install-date volume, without it the input order (the explorer passes the alphabetical list);
  `parseVolumes` reads CoinGecko `/exchanges` (spot ids) and `/derivatives/exchanges` (futures ids), `ranks` feeds
  `CoinPairPreference.exchangeRank` (coin search: exchange tier → contract group → quote → volume rank → name).
  `ExchangeRankingStore` (SharedPreferences `exchange_ranking` / UserDefaults `exchangeRanking.*`, not backed up)
  fetches once per session until one fetch succeeds, then never again. `ComboBox(sections =)` / iOS picker draw
  section headers (heading semantics); favourites stay on top without a header. New adapters `BitunixFutures`
  (base keeps multipliers like «1000PEPE», no TradFi flag) and `EcbReference` (Frankfurter time series of 10 days,
  change against the previous ECB day, bulk per base currency, `allowsAlarms` false, `priceIsDailyReference` →
  `PriceKindLabel.DAILY_REFERENCE`, row label `watch_price_daily`). Alerts screen: no add/edit for
  `!allowsAlarms`, `alarms_reference_rate_hint`; `RefreshResultProcessor` / `RefreshEffects` never evaluate them.
  DexScreener → GeckoTerminal: `tickerFallbackAddress` (`GeckoTerminalNetworks` maps chain ids) and the new hook
  `Market.tickerFallbackOnParseError` («Pool not found») → `SingleTickerFallback.loadAfterMissing`; any fallback
  failure keeps the original error. In the 5xx path a rate limit of the fallback only passes when it is the same host
  (`sameHost`), so GeckoTerminal never pauses DexScreener. Fixtures: `exchange_bitunixfutures.json`,
  `exchange_ecbreference.json`, GeckoTerminal `singleFallback` cases in `exchange_dexscreener.json`, parity
  `picker_order.json` and `gecko_terminal_network.json`. Escape-hatch ceiling 103 (two new Swift adapters).

## 16.2.2 (build 132)

* Settings simplified: no «Advanced» page any more – «Token & TradFi» is on «Watchlist», market alerts and spoken output on «Alerts», coin logos under «Appearance», developer options (debug builds only) on «About the app». Search finds everything at its new place. The privacy page names the new places (coin logos, market alerts, Token & TradFi).
* iOS: the «Allow notifications» row no longer cuts its button text; with very large text the button moves below the sentence.
* Macro calendar: US release dates (CPI, PPI, jobs report, PCE) load again – the BLS pages need a contact address in the request, the BEA page splits words with inline markup.
* iOS accessibility audit blocking again.
* Android CSV import: progress is set on the main thread (fixes an occasional crash of the import/undo UI test).

**Technical notes**

* Build 132: settings without «Advanced» (`SettingsPage.ADVANCED`/`SettingsHome.ADVANCED` and iOS `.advanced`
  removed; old route name falls back): MARKETS row on WatchlistPage, market alerts and speech rows on AlarmsPage,
  COIN_LOGOS under Appearance, DEVELOPER row on AboutPage; search paths follow (`page.markets` → Watchlist,
  `page.market_alerts`/`page.speech` → Alerts, `page.developer` → About). String `settings_row_advanced` and
  `ic_settings_advanced` removed; `watchlist_tts_speech_on` points to Settings › Alerts › Spoken output in all
  languages. `macro_calendar.py`: User-Agent with the public contact e-mail (BLS answers 403 otherwise), inline tags
  removed without a space (BEA «<span>N</span><span>ews</span>»), `--self-test` with stored markup, run in the
  workflow. `room_schema_drift.py` skips outside a git work tree. iOS audit blocking again (settings section titles
  and the portfolio note excepted as dynamic-type false positives, to be checked on a device).
  `PortfolioCsvImportViewModel`: progress from the matching (Dispatchers.Default) and the save (database thread) is
  posted to the main thread with a run counter (`postProgress`/`clearProgress`); setting the StateFlow from the worker
  let the Compose test clock apply changes on that thread (CalledFromWrongThread, corrupt slot table, crash on
  activity destroy in UiFlowsTest.importCsvAndUndo).

## 16.2.2 (build 131)

* Coin logos: images up to 4 MB are loaded – Binance has uploaded high-resolution logos since 2025 (e.g. SATS, ACE, RATS, MOG showed initials); images that failed under the old limit are tried again at once.
* FIFO: the transfer fee spread over all lots is stored once per transfer and its parts are worked out only when needed – portfolio overview and tax export with many transfers are faster (amounts unchanged up to the last of 34 digits).
* iOS: exchange pauses, change basis, unusual activity and pending portfolio saves no longer redraw every screen; tab page titles show in the large content viewer on long press.
* Accessibility audits: Android also checks double text size, Arabic (right to left) and the alerts overview; iOS reports its findings (blocking again after the settings redesign).

**Technical notes**

* Build 131: FIFO origin parts read on demand – `PortfolioFifoParts` (Kotlin/Swift): per queue a `FeeStream` (time,
  factor = fee ÷ holdings, prefix sums); a lot carries own entries (points, ranges) plus amount × Σ factors since it
  joined; disposals keep entries and expand them on first read (`PortfolioCostPartList(fifo:)` on iOS, lazy list on
  Android); open parts aggregate the stream once per queue. Logo image limit 4 MB (`MAX_IMAGE_BYTES` /
  `maxImageBytes`), failure memory key v2. iOS `AppStatus` also holds exchangePauses, changeStamp, activityReports,
  portfolioSavePending. Android audit: `TestDisplayOverride` (Compose locals only: font scale, locale, layout
  direction) set by `DeviceOverridesRule`; runs in standard size, font ×2 and Arabic plus the alerts overview (bell).
  iOS audit still report-only (findings in settings come with the settings redesign); nav-bar dynamic type and element-less text detections logged only; tab tap and audit
  timeout retried once.

## 16.2.2 (build 130)

* iOS starts faster: the watchlist is read and decrypted in the background, placeholder rows show until it is there (nothing is saved before it is loaded); frequently changing status values no longer redraw every screen.
* iOS FIFO and other portfolio decimals compute faster (digits counted without text conversion).
* Accessibility: alerts overview title and count follow large text (count moves into the list when the bar is too small), empty states scroll instead of clipping, decorative bell hidden from VoiceOver; iOS placeholders in text and search fields as dark as on Android; error colour (light) slightly darker; empty favourite star in a selected row darker.
* Portfolio menu: «Cutoff-date export…» and «Worksheet…» (page title «Worksheet»).
* Macro calendar is accepted only when it is listed in the signed market-data manifest.
* Smaller Android app: exchange classes are no longer exempt from R8 shrinking.
* iOS portfolio start page: the file import and address links are easier to tap (44 pt).
* Android watchlist widget: after the 15-second refresh lock the refresh symbol reliably returns – the countdown no longer runs into negative values («−01:05»).

**Technical notes**

* Build 130: iOS `AppData+SnapshotLoad` (launch read off main, `whenSnapshotLoaded`/`waitForSnapshot`, guarded
  accessors, `WatchlistLoadingRows`), `AppStatus` (@Observable: online, appStartMillis, watchlistSavePending,
  watchlistLockedHints). `PortfolioDecimal.mantissaDigits`. Accessibility audits: iOS still report-only in build 130
  (findings as warnings; blocking again in 131, tab bar exception by position); Android in standard size.
  Android widget lock end: `WidgetRefreshUnlock.hold` sends a broadcast to `PriceWidgetProvider` that waits with
  goAsync in steps of at most 6 s (`holdStep`) and then redraws – a running receiver keeps the process from being
  frozen, so the chronometer never counts below zero; the alarm (now waking, allow-while-idle) stays as fallback.
  iOS audit waits 1.5 s before each audit and first closes the watchlist storage notice (always shown in the unsigned
  simulator; the audit reported «Contrast failed» for it and the starter title beneath, although the text has ~13:1).
  Contrast findings for text reaching under the translucent tab bar or out of the window (largest text, Arabic) are
  only logged. Portfolio empty state: «Import its file» and the address link get a 44 pt tap height (same look).
  `check_theme_contrast.py` (CI, reads the colour sources). CI: `swift_escape_hatches.py`, `code_metrics.py`,
  `user_agent_check.py`, `RoomSchemaRulesTest`, signed macro calendar (`macro_events.json` in the manifest),
  provenance attestation on tags, PGP keyring via supply-chain-update, fastlane `store_build`. iOS coverage gate on
  «logic» (Shared/ and App/), 70 %.

## 16.2.2 (build 129)

* Portfolio menu: «Cutoff-date export…» and «Tax worksheet…» are separate; the tax worksheet is a page of its own (template selectable there, rules date and warning, the four exports and the year summary; it stays open after an export).
* Exports can be cancelled while running (no half-written file remains); errors name the cause (file location, missing prices, other) and offer «Try again».
* Widgets follow larger text sizes: iOS watchlist, single-pair, portfolio, pulse and lock-screen widgets and the Live Activity grow up to 1.3× (fewer watchlist rows when needed, amounts never shortened); Android keeps the price in the watchlist widget complete and shortens the pair name instead.
* Charts for screen readers: iOS Audio Graph and swipe up/down point by point, Android «Next/Previous point» actions; the portfolio comparison names both lines.
* Android 12+: «Reconfigure widget» opens the widget setup.
* Market-data mirror: the signed manifest can revoke a signing key; keys revoking each other turn the mirror off until an app update.

**Technical notes**

* Build 129: tax worksheet page (Android `ScreenRoute.PortfolioTaxWorksheet`, `TaxWorksheetScreen`; iOS
  `TaxWorksheetPage`), cutoff dialog/sheet only for the cutoff export. `ExportRunner` / `ExportRun`: cancellable runs,
  Android deletes a partially written target after the write finishes; error kinds file / prices / other
  (`export_errors.json`). Widgets: `WidgetPrimaryFit` (iOS, `@ScaledMetric`, cap 1.3×, `ViewThatFits` fallback),
  `WidgetRows.rowsFor` / `WidgetListRows` (`widget_list_rows.json`), Android `WidgetTextFit.listPriceSp`. Charts:
  `ChartAudioGraph`, `ChartPointAdjustable` (iOS), `ChartPointSemantics` (Android). `AppNotifierDeliveryPlan` (pure
  decisions of the Android notifier). Widget configure activities exported with `WidgetConfigureGuard`. Mirror
  manifest `revoked` (key id = SHA-256 of the DER public key), `MirrorRevocations` stored permanently; mutual
  revocation disables the mirror. Accessibility audits: `AccessibilityAuditTest` (Android, `ComposeA11yAudit`),
  `AccessibilityAuditTests` (iOS `performAccessibilityAudit`), each also with large text and Arabic.

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

**Technical notes**

* Build 128: `PortfolioCostPartPool` (Android/iOS) keeps cost-origin parts lazily: a disposal stores the remaining
  share instead of rewriting every part, parts are materialised on read; large lists (> 64 parts) give export cost,
  «USDT ≈ 1» and date span from running sums. Used by the average method, `TaxPool` (UK/CA/AT) and the CA loss check;
  equivalence tests against the previous code (`PortfolioAverageReference`, `TaxPoolReference`), performance tests use
  `assertScales`. `CsvTransferWindow`: deposits per coin sorted by time, window by binary search (same pairs as the
  nested loop). `PriceWriteOrder`: a price is written only if not older than the stored one (stored time more than 1 h
  in the future counts as a reset clock); Android in the `WatchDao` update, iOS in `Outcome.apply`. iOS
  `PriceWriteGate` (FIFO, continuations) serialises applying results of `refreshAll`, `refreshOne`, live and the
  background task; `applyLive` only tries. `lastFullRefreshAt` (iOS `SharedStorage`, Android `RefreshStats`) drives
  the 15 s debounce of pull to refresh and the widget button; live ticks set only `lastRefreshAt`. `RefreshLock`
  `lockWithin` never leaves an acquired mutex behind after a timeout. `BackupRestoreLimits.validAsset` (letters and
  digits of any script and `._-`; DexScreener pairs length only). `TaxRulesDate.rulesStale`. Fixtures
  `price_write_order.json`, `refresh_debounce.json`, `tax_rules_stale.json`, `backup_limits.json`.

## 16.2.2 (build 127)

* iOS: the watchlist no longer redraws every row while a refresh is running.
* Android: deferred price notifications survive an error late in a refresh; iOS: a pending price save is folded into the next full save; open-lots sheet keeps the full list while closing.

**Technical notes**

* Build 127: RefreshState (iOS, `AppData.refreshState`, an `ObservableObject` of its own): `refreshing`,
  `refreshingWatchIds`, `lastRefreshMillis`, `lastRefreshReport`, `liveExchanges` and `liveWatchIds` publish only
  there, not via `AppData.objectWillChange`, so the watchlist body is not recomputed for them. Views that show them
  observe it: status row with circle and run time (`RefreshStateReader`), ⋯ menu (`WatchlistMenuHead`), report sheet,
  action sheet, developer settings; countdown, clock and «only stale» via `WatchlistRefreshEvents`. Rows read
  per-pair `@Observable` boxes (`rowLoading`, `rowOnLive`, like `LiveQuoteBox`): only the row whose «refreshing» or
  live state changes redraws; a full run touches only rows without a price. `RefreshStateTests`.
  Review fixes: `RefreshLock.stageTail` stages the tail of a `withLockThen` block early; a staged tail still runs
  (lock handed over) when the block later fails or is cancelled, then the error propagates. `PriceRefresher` and
  `LivePriceApplier` stage the price notifications right after `process` (report count via reference);
  `applyLiveFinal` does not wait for the tail (it keeps the lock; waiting would only lengthen stopping the stream).
  iOS: a full `mutate` folds a pending price save in (`foldPendingPriceSave`: `take`, widget wishes merged,
  interval from now); `yearSummary(run:)` asserts `GainsRun.matches` in debug; the open-lots sheet always lists
  `PortfolioOpenLotItem.of(lots)`; `TaxFees` sits below `object TaxExport`.

## 16.2.2 (build 126)

* Tax worksheet: the realised-gains export and the year summary compute the disposals only once per file (dates for the exchange rates and the rows come from the same run) and the two notes about network-fee cost and unassigned fees from one shared run – same files, less work.
* Price notifications no longer hold up a refresh: widgets, «Last refresh» and the updated time come first, the throttled price notifications follow (same order, at most five per second, reference price only for notifications actually shown); alarm notifications are still posted immediately.
* The list widget reads the watchlist once per update for all its widgets (header, empty note and rows) instead of three times per widget.
* Alarm thresholds reuse their number formatter.
* iOS: the watchlist recomputes less, the portfolio loads after the first frame, live prices are saved less often, «7D»/«1Y» cancel outdated work, and switching the currency no longer shows a brief «rate missing» note.

**Technical notes**

* Build 126: Deeper performance points, results unchanged (each with an equality test against the previous way).
  Tax export: `TaxExport.allDisposals` (all years, without fx) is computed once per gains export / year summary and
  passed to `fxSpan(…, disposals)`, `gainRows(…, disposals)` and `yearSummary(…, disposals)`; CA (`CA_ACB`) rows keep
  their own run with fx (superficial loss decided in CAD, the rates depend on the span). `TaxExport.feesIn` returns
  `lostFeeCostsIn` + `unassignedFeesIn` from one run (one `ledgers`/engine run; same input as before: no day closes,
  `applyRules(…, null)`) – deliberately not from the disposal run, whose day-close valuation can change the holdings
  with cost. Cost parts stay over all years. `TaxExportSingleRunTest`: identical span and CSV texts (summary, gains,
  income) for all `tax_templates.json` cases/summaries, `tax_export.json` and the performance data (all templates,
  several years, FIFO/Ø). Price notifications: `RefreshResultProcessor.process(…, deferPrices = true)` returns
  `pendingPrices`; full refresh and live prices post them with `postPrices` (showPrices + `setNotifiedPrices` only for
  shown) after widgets and report as a tail of `RefreshLock.withLockThen` / `tryWithLockThen` / `withLockWithinThen`:
  the caller finishes, the lock is handed to the tail in the app scope and released only after it (no other writer in
  between, so the next run never decides with an old notified price). WorkManager/exact runs wait for the tail; side
  checks (their own notifications) start after it; the report gets the shown count afterwards (`noteShownPrices`).
  Single-pair refresh unchanged (one post, never throttled). Overflow lines are built once (only pairs cancelled
  during the pause drop out), `PriceFormat.threshold` keeps one formatter per locale. List widget: `WidgetOnceRead`
  reads the pairs once per draw (empty note, title, rows of all widgets), `ListWidgetRenderer.update(ids, select)`
  picks the lists for `updateForWatch` from the same read (`WidgetRefreshScope.listsShowing`), and the outdated alarm
  reuses it. Tests: `PortfolioEventsByCoinTest` (`eventsByCoin` = `events` per coin; `PortfolioPeriodParts` all coins =
  sum per coin; `cashFlows`/`pnlFlows` all coins = per coin).

## 16.2.2 (build 125)

* Portfolio card, value history and «7D»/«1Y» are faster with large portfolios; the average-cost method now takes time in proportion to the number of transactions (the cost history per purchase is only kept where it is needed: tax export and «Since purchase» in another currency).
* RSI: completely flat prices now give 50 (neutral) instead of 100.
* Long lists of open purchase lots show the first 50 with «Show all (N)».
* Fewer watchlist reads per widget update (iOS).
* iOS: «7D»/«1Y» are calculated off the main thread, adding many pairs at once is faster and number formatters are reused.

**Technical notes**

* Build 125: Performance without changed results. Ø (`PortfolioCalculatorAverage`) keeps the cost parts per acquisition
  (`takeParts`, acquisitions × disposals) only with `withParts` (default true: tax export, CA engine, coin detail);
  `summarize(…, withParts = false)` / `ledgers(…, withParts = false)` are linear with identical numbers (empty
  `costParts`/`pricedCostParts`). The portfolio card keeps parts only outside USDT (`PortfolioCostFx`); widget
  snapshot, freshness check, hourly candles and alarms never. `PortfolioLedger.eventsByCoin` once instead of
  `events` per coin: `PortfolioCalculator.ledgers` (tax notes `lostFeeCostsIn`/`unassignedFeesIn`), value history
  (`holdingsTimelineOf`, `cashFlows`, `pnlFlows`), `PortfolioPeriodParts` (all coins). `PortfolioSummaryCache`
  (last summary by trades, prices, method, parts) shared by the app's freshness check, the background refresh and
  `PortfolioSnapshotUpdater.record` – one summary per update. Open lots: 50 rows, then «Show all (N)» in a lazy sheet,
  formatters once per list. RSI: average gain and loss both 0 → 50 (parity `indicators.json` › «flat closes»).

## 16.2.2 (build 124)

* Android: the portfolio CSV import no longer freezes the app while the rows are checked before saving.
* The import preview already lists rows that cannot be saved (amount too small, swap without a valid coin or amount received) as skipped, so one such row no longer stops the whole import.
* Android: if the app was closed by the system during a CSV import, the import closes cleanly and «Import CSV» works again right away.
* Android: the list widget clears an expired «Retrying at …» time when the app starts.

**Technical notes**

* Build 124: CSV import – `PortfolioRepository.insertImported` cleans and checks the rows (`cleanForSave` + `require`)
  on `Dispatchers.Default`; `CsvImportMapping.unsavableReason` puts rows that saving would reject into the preview's
  skipped list (`MISSING_FIELD`, new `AMOUNT_TOO_SMALL`, `INVALID_SWAP`; parity `csv_import.json` › «unsavable», also
  checked against `cleanForSave`); `runCsvImportSave` (confirm flow as a pure function: prepare, cancel, save, enable)
  and `canCancelSave`; `csvImportOrphaned` closes the import flow after process death (picked, no pending file picker
  result, view model `Idle`). Widget: `ListWidgetRetry.clearIfExpired` at app start (`RetryWait.widgetRetryExpired`).
  `release_readiness.py`: «Build N:» history only for N < versionCode, «früher» / «previously» only at the line start
  or in parentheses.

## 16.2.2 (build 123)

* Android: when the widget's «Refresh» has to wait because the encrypted storage is not unlocked yet, the watchlist status shows «Storage still locked – retrying in 27 s» and counts down (announced once by TalkBack); the list widget shows the fixed retry time «Retrying at 14:32» (12/24 h as set on the device) instead of the time. The retry interval stays 30 s.
* Android: «Last refresh» notes under «Waiting to start» when a run was not the first attempt («Attempt 2 – the encrypted storage wasn't ready on the first one»).
* Android: the portfolio CSV import keeps its result when the screen is rotated while saving; preparing the rows after «Import» runs off the main thread; «Cancel» disappears once all rows are saved (linking transfers can no longer be cancelled); a failed row is reported as an error instead of a count.
* «Remove pairs again?» after adding many pairs checks the undo window again on «Remove»: if it has expired, nothing is deleted and a note says the pairs stay in the watchlist (Android and iOS).

**Technical notes**

* Build 123: `RetryWait` (retry wait of the widget refresh: WorkInfo ENQUEUED + `runAttemptCount` > 0 →
  `nextScheduleTimeMillis`, linear backoff, countdown seconds), `PriceUpdateScheduler.observeManualRetryAt` →
  `WatchlistLive.widgetRetryAt` → status pill countdown (`WatchlistRetryCountdown`), `ListWidgetRetry` (partial widget
  update with the fixed retry time `widget_retry_at` via `RetryWait.widgetRetryTime`, `WidgetPrefs.refreshRetryAt`),
  `RefreshReport.attempt` (`RefreshReportCodec`, extracted from `RefreshStats`), `BackgroundWorkPolicy.RETRY_BACKOFF_SECONDS`.
  Android only: iOS runs the widget refresh directly in the intent without a retry. CSV import: `CsvImportState.Done` /
  `Cancelled` (result as state, survives rotation), `prepareCsvImport` / `CsvImportSavePlan` (on `Dispatchers.Default`),
  `insertImported` inserts directly via `portfolioDao` (no nested transaction). `BulkUndoConfirm.finishedAt` (saved;
  «Remove» re-checks `BulkAddRunner.undoOffered`, `bulk_undo_expired`). Device test `PortfolioImportInsertTest`.

## 16.2.2 (build 122)

Phase C, continued:

* Portfolio CSV import of large files (e.g. 10 000 lines) runs in the background with a progress bar «Line X of Y» and «Cancel»; cancelling leaves the portfolio unchanged. On iPhone the import no longer blocks the app while saving.
* The setting «Show the period next to the change» is gone: the period (24h, today, last update) is now always shown next to the change – in the pulse line, the single widget and the iOS Live Activity too.
* Watchlist: without own groups the group row shows «Your groups will appear here» with «New group» from the first start (it used to stay empty until the first alert).
* iOS: the UK tax pool finds the 30-day matches faster (same result).
* Tests and tooling: line coverage is now a gate (logic at least 58 % on Android, 78 % on iOS, just under today's values); performance tests with 10 000 portfolio transactions (holdings and tax years for NEUTRAL, CH, DE, UK, US); synthetic error responses (broken, empty, HTTP 429, rate-limit texts) for 14 exchanges; shared cases for quiet hours (including daylight-saving changes) and currency conversion; iOS checks currency codes and the rate-limit length exactly like Android.

**Technical notes**

* Build 122: `CsvImportProgress` (throttled progress, cancellable insert; iOS `CsvImportBackground`, `CsvImportRun`,
  `AppData+PortfolioImport`), `BulkAddProgress(text:)`, coverage gate `--min-logic` (`MIN_LOGIC` 58 / 78),
  `PortfolioPerformanceTest` / `TaxPerformanceTest` (+ iOS), `PerformanceTiming.Sizes`, `exchange_errors_synthetic.json`
  + `ExchangeErrorContractTest`, parity `quiet_hours.json` / `currency_conversion.json`, groups hint without
  onboarding/minimum (`showsGroupsHint`), iOS `TaxEngineUk` binary search, `RateLimitSignal` UTF-16 length.

## 16.2.2 (build 121)

Start of phase C (quality and tests):

* iOS: the app is built in the Swift 6 language mode (strict concurrency checked by the compiler and at run time); system callbacks (notifications, WebSocket ping, Low Power Mode) run on the right thread.
* Watchlist search: the search text of every pair is prepared once per list change, so each keystroke only compares (also with thousands of pairs).
* Indicators: the RSI with an invalid period returns no value on Android, like iOS (it returned «not a number»).
* Tests and tooling: line coverage shown per platform as total and «logic» (Android including the market-data module, iOS new); new shared indicator cases and tests for exchange rates and price formatting; weekly mutation tests for the tax and portfolio logic; the device test classifies the undo of «add many pairs» as a user write (locked watchlist: nothing changes); the store-guide check also covers tag examples and the archive name; the performance tests run in their own CI step (not in parallel with other tests, so the timings no longer vary with the load); one lint warning less (bulk-add banner reads resources via LocalResources).

**Technical notes**

* Build 121: Swift 6 language mode (`SWIFT_VERSION 6.0`, `ios/README.md` «Swift 6 Migration»), `WatchSearchIndex` /
  `rememberWatchSearchResult` (iOS `WatchlistSearchIndex`), `coverage_summary.py` «logic» + Kover merge of :marketdata,
  `xccov_summary.py`, `mutation_test.py` + `mutation.yml`, `indicators.json` parity, `rsi` period ≤ 0 → null (Android),
  `release_readiness` guide patterns for tag examples / archive name, `-PperformanceTests` (only/true; performance
  tests in their own CI step), `LocalResources` in `WatchlistBulkAdd`.

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

**Technical notes**

* Build 120: `AlarmCheckStatus.batch` (Android), `BulkAddRunner` end states (cancelled/failed, finish time, undo window
  60 s, `deleteNewWatchesForUndo` without alarms), `BulkAddDisplay`, iOS `WatchInsertion`, `DenseRowText.convertedMaxLines`
  / `convertedLineLimit`, `lineStart` (relative time at line start), `MarketMoveScale` opposing market/breadth → mixed.

## 16.2.2 (build 119)

* Messages at the bottom match the theme (dark in dark mode instead of a light pill); success shows a green outline with a check mark, errors a red outline with an exclamation mark (orange with the colour-vision palette), information only a quiet outline. Success stays green with swapped price colours; the meaning never depends on colour alone (both platforms).
* Software bill of materials: every Android build writes a CycloneDX SBOM of all shipped libraries with version, package URL and the verified SHA-256; the iOS part states that the app has no third-party packages.
* Reproducible build check: the Android release APK and AAB are built twice on fresh runners without caches and compared byte for byte (main, release tags, weekly).
* Behaviour tests for the central guarantees (no write without consent while the watchlist is not saved, consent only for the action itself, bulk add with undo, the pair limit on undo) instead of source-text checks where they cover the same; performance tests with 2000 pairs (sorting, filters, search, refresh, alarms, pulse, widget plan, backup size) with fixed limits and a scaling check.
* iOS CI: release build, unit tests and UI tests run on three Macs in parallel (about 25 instead of about 65 minutes); the duplicate Release build for the Simulator is gone, the release checks run on the device build.
* iOS performance: alarm changes after a refresh are applied through an index (no longer one linear search per change), and the «not checked» marks change single entries and notify the view once per run.

**Technical notes**

* Build 119: `AppSnackbarHost` / `showAppSnackbar` / `SnackbarKind` / `SnackbarPalette` (+ `snackbar_colors.json`), iOS
  `snackbarSurface`, `sbom.py`, `reproducible.yml` + `repro_compare.py`, behaviour tests (`FakeWatchStore`,
  `WatchlistWriteConsentTest`, `BulkAddRunnerTest`, iOS `WatchlistConsentBehaviorTests`, `AddWatchesBehaviorTests`),
  performance tests (`performance/*PerformanceTest`, iOS `*PerformanceTests`, 2000 pairs), iOS `AlarmUpdate.Index`,
  `ios.yml` split into three parallel jobs (no separate Release Simulator build any more).

## 16.2.2 (build 118)

UX review of build 117 (both platforms unless noted):

* Adding many pairs at once (Android): continues when you leave «Add pair», with a progress bar there and above the watchlist and the result on the watchlist; both platforms count new pairs first («312 new, 434 already in the watchlist»), disable the button when all are there, name only the new ones in the confirmation and offer «Undo» for exactly the pairs just added.
* Market tab: the headline follows the whole market and its breadth as well as BTC, ETH and SOL – never «mixed» when all three move the same way – and «slight»/«clear» use one shared scale (1 % / 3 %) everywhere.
* Watchlist: relative times in lower case; the «last check» line only when background checks are late; the selected filter chip shows its name, and a fade at the edge hints that more chips follow.
* Empty portfolio: one paragraph instead of two so the disclaimer fits on the screen; the example amount uses the app's number format.
* Wording: «leverage mood» instead of «funding» in the market lead line; the search hint in «Add pair» is one sentence; the finished step badge no longer looks like a checkbox.
* Exchange API check: One Trading is checked with BTC/USDC (BTC/EUR is closed), Lighter with the BTC market instead of the first row.

**Technical notes**

* Build 118: `BulkAddRunner` (app-wide bulk add, progress, `undo`), `bulkNewCount` / iOS `ExplorerBulkCount`,
  `BulkAddProgress` / `WatchlistBulkAdd`, `MarketMoveScale` (+ `market_move_scale.json`), `alarmReliabilityLineShown`,
  `PortfolioEmptyExample`, exchange check row selector (`{"symbol": "BTC"}`).

## 16.2.2 (build 117)

* Watchlist: right after the start, the «≈ CHF» line with the note on the USDT rate could push pair, name and exchange out of the row. The converted line now takes at most half the row (longer text ends in «…»), the row shows the short note «conversion uncertain» (the full sentence stays for the screen reader), and the conversion is recalculated as soon as the first USDT/USD reading arrives instead of after up to 60 seconds (both platforms).
* Signed data mirror: the apps and the workflow accept a second, reserve public key; its private key is kept offline, so a lost or leaked signing key can be replaced without an app update (F-29).
* CI: Kotlin compiler warnings in the app and library sources fail the Android build (ceiling 0, like lint and the iOS release build); three unnecessary safe calls removed.
* Dependency verification trusts the Gradle sources that Android Studio downloads for the IDE (not part of the build), like the existing sources and javadoc rules.
* iOS tests: the in-memory test keychain is used only when the host verifiably lacks the keychain entitlement (probe entry), never in an unknown state.

**Technical notes**

* Build 117: reserve mirror key (`RESERVE_PUBLIC_KEY` / `PUBLIC_KEYS`, `productionReservePublicKey`), CI step «Kotlin
  warnings» (ceiling 0, main sources, from the unit test log), `DenseRowText.convertedMaxWidth` (+ iOS `CappedWidthLayout`),
  `CurrencyConverter.usdtMeasured` / iOS `WatchlistConversion.waitForNextRound`, `WatchlistConversion.accessibilityText`,
  `HostStorageKey.probeAddStatus`, trusted Gradle sources (`gradle-*-src.zip`) in the verification seed.

## 16.2.2 (build 116)

Fixes from the twelfth review round (on build 115):

* CI: pull requests always run the Android, iOS, Strings and Parity workflows (no paths filter there any more), so these jobs can be required checks without blocking documentation-only pull requests; pushes to main keep the paths filter.
* iOS CI: environment-dependent skips of the background run tests (quiet hours of the test app, «not enough time left» on a slow runner) are reported but no longer fail main; the in-memory test keychain is only used when the test host lacks the keychain entitlement (never when a real keychain is merely locked).
* One Trading: a rate limit (429/418) of the all-tickers fallback is passed on, so the exchange pause applies instead of the app asking again (both platforms).
* Release checklists name all seven tag workflows, including the dependency vulnerability check.

**Technical notes**

* Build 116: no `paths` filter for `pull_request` (required checks), allowed environment skips in ios.yml,
  `HostStorageKey.shouldUseTestKeychain`, rate limit passthrough in `SingleTickerFallback`.

## 16.2.2 (build 115)

Quality towards a review score of 9:

* iOS: all 94 compiler warnings of the Release build fixed (Swift strict concurrency: restated Sendable conformances, main-actor isolation of gestures and view helpers, async-safe locking, Live Activity calls without crossing actors); the warning ceiling drops from 94 to 0, so every new warning fails the build. The CI lists every warning in its annotations.
* iOS: the watchlist lock and background run tests no longer need the real keychain (an in-memory test keychain is used only when the real one is unavailable; the lock test also waits for pending writes of the previous step) and therefore run in every CI build; skipping them on main counts as failure.
* Dutch: the remaining three texts use «Portfolio-vergrendeling».
* iOS: second UI test – the result dialog after creating a backup (forced «could not be verified») appears and closes.
* One Trading: when the single-ticker endpoint answers with a server error (5xx), the price is read from the all-tickers endpoint (both platforms); a pair missing there keeps the original error and is never marked as delisted.
* CI: unique job names (Android build, iOS build, Strings check, Parity check, Release readiness check) so they can be required checks.

**Technical notes**

* Build 115: iOS warning ceiling 0 (`MAX_COMPILER_WARNINGS`, `warning_details`), `HostStorageKey` / `StorageKey.replaceKeychainForTesting`,
  `UITEST_FAIL_VERIFY`, `tickerFallbackAddress` / `SingleTickerFallback` (One Trading), job names for required checks.

## 16.2.2 (build 114)

Fixes from the tenth review round (on build 113):

* Watched addresses: a refresh result is discarded when «count tokens» was turned on or a network was added while it ran (compared with the settings the refresh used, instead of a reset marker); the address is then loaded again. Turning something off keeps the result (it is filtered). iOS refreshes after every network change, like Android.
* iOS: the backup/restore result dialog appears shortly after a confirmation or file picker has finished closing and is checked again before showing; a result that arrives while a dialog is open follows after it. New UI test for the restore result dialog.
* Restore hints name the own server exactly like the setting («Own server for addresses»); Dutch uses «Portfolio-vergrendeling» everywhere. Tests check this in all 31 languages on both platforms.
* CI: the weekly exchange check also tests One Trading's all-tickers endpoint (the single-ticker endpoint answers HTTP 500 since 21 October).
* Android: the address balance write rule is tested against a real Room database.

**Technical notes**

* Build 114: `AddressBalance.discardFetched` (+ `Update.snapshotCountTokens/snapshotNetworks`, replaces `resetDuringFetch`/`snapshotAttempt`),
  `WatchedAddressBalanceWriter`, iOS `presentDelayNanos` / `UITEST_BACKUP`, `BackupSettingsTextsTests`, exchange check `onetradingbulk`.

## 16.2.2 (build 113)

Fixes from the ninth review round (on build 112):

* Watched addresses: turning on «count tokens» or adding a network while a refresh runs is no longer overwritten by that refresh; the stale result is discarded and the address is loaded again with the new setting (both platforms).
* Backup/restore results: the toast-or-dialog limit counts East Asian wide characters double and shrinks with the font size; «backup not verified» and «backup from a newer version» always appear as a dialog. iOS shows the result dialog as soon as no other sheet, picker or question is open (no fixed delay) and still asks the notification question when the page is left. iOS toasts are announced to VoiceOver after the focus change (400 ms) and restart when the same text appears again.
* Own Bitcoin server: leading spaces no longer let an over-long address pass as valid.
* The warning before restoring a backup without password uses the exact setting names of each language; the lock hint after restoring does too (fa, pt, he, th, hu, it).
* Signed data mirror: new signing key (the previous private key was lost; no app with the old key was released). Docs: privacy policy, DEVELOPMENT, iOS README and SECURITY list all four rules for backups without password; SECURITY names the environment «mirror-signing».
* More tests: cancelled and failing refresh rounds free the gate, late test results after a reset, robust waiting in iOS tests.

**Technical notes**

* Build 113: `AddressBalance.resetDuringFetch` (+ `Update.snapshotAttempt`), `BackupResultPresentation.displayWidth` / font scale /
  `canPresent`, iOS `ToastMessage`, `BackupUnencryptedWarningTest`, mirror signing key rotated (`MirrorManifest`, `mirror_data.py`).

## 16.2.2 (build 112)

Fixes from the eighth review round (on build 111):

* Watched addresses: only one refresh runs at a time; a second one waits and reads the list afresh, so no address is queried twice (both platforms, also for xpub keys).
* Backup and restore results with a note (own Bitcoin server, portfolio lock kept, entries over the limits) or a long text now appear as a dialog instead of a short toast (Android limits toasts to two lines); the notification question follows after closing it. iOS announces toasts to VoiceOver and keeps them visible according to their length.
* Android: all dialogs with longer texts scroll at large font sizes (shared building block, guarded by a test).
* Own Bitcoin server: pasting more than 200 characters is reported as invalid again (build 111 shortened it to a valid-looking address); iOS also asks before switching to public nodes when the address list cannot be read; no public default hosts in the address code any more; a late result of a cancelled server test can no longer overwrite a newer test.
* The warning before restoring a backup without password names what is not taken over (portfolio lock, own Bitcoin server, hide amounts, system backup), in all 31 languages; SECURITY.md describes the same rule. Arabic dual form for unknown tokens.
* CI: the mirror signing step runs on main only and in the environment «mirror-signing»; the dependency graph is built with read rights only and submitted in a separate job without Gradle. More tests for the signed mirror (cancelled caller, size limits over the real loading path).

**Technical notes**

* Build 112: `singleFlightRefresh` / `AddressRefreshGate`, `AddressServerTestRun`, `BackupResultPresentation` (+ `backup_result_dialog.json`),
  `DialogText` + `DialogScrollGuardTest`, `mirrorLoader` / `loadMirrorMeta` tests, environment `mirror-signing`, split dependency graph jobs.

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

**Technical notes**

* Build 111: `AddressServerRules` (+ `address_server_ui.json`), `BackupRestorePolicy.addressServerNote`, `SettingsRestoreNotes`,
  `BlockedSources.unblock` / `clearServerPauses`, `MirrorTrust` with injectable key (Android class + `shared`), iOS mirror size
  limits, `supply_chain_files.py` configuration check, market-data red on unsigned manifest, guard rules for detached scopes.

## 16.2.2 (build 110)

* Code comments describe the code as it is now: build-number history removed from comments in about 820 files (audit F-21); compatibility notes for older versions kept without numbers.
* iOS: own text for the local network prompt (own address server in the home network), in all 31 languages.
* Portfolio widget: the display hint now correctly says the USDT line is hidden for a USD stablecoin (e.g. USDC), not for USD.
* Supply chain active: Gradle dependency checksums (`gradle/verification-metadata.xml`) and lock files from the «Supply chain update» workflow; every build now verifies each dependency.
* CI: lint warnings additionally listed per finding (grouped by id and file, with line numbers) in the check annotations.
* Lint warnings cleaned up: real findings fixed (KTX extensions, modifier parameter order, empty super call, obsolete resource folder, one unused string in 31 languages, unused Hebrew plural form), deliberate cases suppressed per element with a reason (RemoteViews widget layouts, iOS-only strings, translations flagged as typos, dependency update hints handled by the supply chain workflow).
* Repository: a stray CI output line at the end of `docs/privacy/index.html` (published with build 109, after `</html>`) removed; the page is again identical to `android/docs/privacy/index.html`.

**Technical notes**

* Build 110: comments without build numbers (F-21; history lives in CHANGELOG and git), `NSLocalNetworkUsageDescription`
  (`ios_local_network_usage` in `ios_extra_strings.json`, `convert_strings.py` `INFO_PLIST_KEYS`), `lint_warnings.py --details`.

## 16.2.2 (build 109)

* Mirror data is signed: the apps accept files from the GitHub data mirror only with a valid signature (ECDSA P-256 over a manifest with SHA-256 per file, at most 7 days old); otherwise they use the exchanges directly as before.
* Watchlist not saved: a pass without a visible screen now covers only that one action (Android per coroutine job, iOS per task) instead of switching the safeguard off until the next answer.
* Supply chain: Gradle dependency locking and checksum verification prepared (files generated by a dedicated workflow), weekly and per-change vulnerability check against OSV, coverage report (Kover, display only), lint warnings summarised in CI, first unit tests in the marketdata module.
* Tests: the confirmation guards on both platforms also check the direction of the condition (no write in the refusal branch).

**Technical notes**

* Build 109: signed mirror (`MirrorManifest`, `MirrorTrust`, `mirror_signature.json`, `mirror_data.py --manifest`,
  `artifact_check.py` profile `mirror_manifest`, secret `MIRROR_SIGNING_KEY`); consent per action
  (`WatchlistNotSavedGate` job scope / `TaskLocal` `actionConsent`, `unaskedPassScope`); condition-direction guards;
  `supply-chain-update.yml`, `osv.yml` (`osv_check.py`), Kover (`-Pcoverage=true`, `coverage_summary.py`),
  `lint_warnings.py`, marketdata unit tests.

## 16.2.2 (build 108)

* Portfolio: a transfer counts as «more than recorded» on every method (FIFO, average, FIFO per wallet) when amount plus network fee exceed the holdings; the uncovered part of the fee is shown as unassigned. UK and Canada pools report an uncovered fee instead of dropping it (the app's own UK/CA templates were not affected).
* Own server for addresses (Settings › Portfolio): Bitcoin balance and xpub queries can go to your own Esplora/mempool-compatible server (https only, test button, no fallback to public nodes); included in backups. A consent dialog now also appears before the first xpub/descriptor and explains what the node operator can see.
* Portfolio widget: «List» offers only «USDT» and «Conversion» (the USDT line has no effect there); «Overview» offers 30 days and 1 year (24 h and 7 days repeated the fixed figures). Saved choices are kept.
* Android: the notification permission after «Change anyway» in Settings › Price notifications appears in the current screen; double-tap protection for note, group and alert editing on both platforms.
* Diagnostics: background errors are also counted per error type (type name only, no texts). Privacy policy and SECURITY.md updated (data without encryption key: readable, changes not kept).

**Technical notes**

* Build 108: transfer coverage «amount + network fee» on all methods (`uncoveredShare`, `portfolio.json` B108 cases,
  `tax_templates.json` `poolUncovered`, `TaxPool.addFee` with amount/fee); own address server (`AddressServer`,
  `address_server.json`, `WatchedAddressSourceServer` / `WatchedAddressSource+Server`, Bitcoin Esplora only, backup key
  in `backup_settings.json`); first-xpub consent; `PortfolioWidgetOptions` (`widget_portfolio_options.json`);
  `SettingsUiEvents`; double-tap guards; `launchOrSkip`; `AlarmEditorStateTest`; `WatchlistNotSavedGate.run` +
  `EditorConfirmBehaviorTests`; background error kinds (`background_errors.json`).

## 16.2.2 (build 107)

* Android: results after the «not saved» question survive rotating or a theme/language change – the alert editor closes after «Change anyway» (no second alert from a second tap), confirmations, undo and the notification permission appear in the current screen; the permission request can no longer crash on a screen that was just rebuilt.
* «Cancel» keeps the note and group dialogs open with their text (both platforms); the Android disable/enable sheet closes only after the change, with haptics like on iPhone.
* iPhone: the alert editor asks only once per save.
* The question text no longer names a reason that may not apply.
* Tests: the confirmation guards also check the cancel branch and closure parameters; the privacy scan skips Swift regex literals.

**Technical notes**

* Build 107 (review round 5): `ScreenEvents<T>` + `CollectScreenEvents` (buffered one-shot results, collected by the
  current screen); alert editor draft in `AlarmEditorState` (`SavedStateHandle`), effects in `AlarmEditorEffects`;
  `WatchUiEventsHolder` / `MarketCoinUiEffects`; `launchSystem()` guards an unregistered permission launcher;
  `AlarmEditorRecreateTest`; iOS `ConfirmedFinish` (editor, note and group sheets close after the confirmation,
  `saveFromEditor` / `templateFromEditor`); guards: cancel branch (Android) and own closure parameters (iOS);
  `ios_release_audit.py` skips `#/…/#` regex literals.

## 16.2.2 (build 106)

* Watchlist not saved: no feedback before the answer any more – favourite, disable, «add to watchlist», price notifications and group actions show banner, haptics and the notification permission only after the change was written; «Cancel» keeps a multi-selection and an open alert editor with its input; if the screen is briefly rebuilt, the app waits up to 2 s to ask instead of refusing silently. Clearer question text in all languages («Change anyway»).
* Portfolio alerts with an invalid «falls by 100 % or more» threshold say «delete and create again» (there is no editor); iOS no longer dims that hint.
* iOS: swipe-to-delete keeps the row visible until the answer; delivery markers are pruned only after the delete was written; the portfolio cleanup only marks itself done after the alert file was read; the pair picker reuses its key per data state.
* Background error counter also counts failures of the alert cleanups (Android).
* Tests and release: the not-saved guard checks every single write call on both platforms; iOS gate tests have a time limit; all iOS source guards fail when their file is missing; the privacy manifest check understands strings and more file-timestamp functions; release readiness compares the build in the private work lists with the project.

**Technical notes**

* Build 106 (review round 4): `launchWrite(cancelled = …)` with `onDone(Boolean)` for favourite, group, disable and
  notification writes (feedback only after writing); `addPreview` reports cancel/locked; `WatchlistNotSavedGate.confirm`
  waits up to 2 s for a screen (`lateScreen` in `watchlist_not_saved_gate.json`, `mayAsk()`), iOS `confirmClose` keeps
  the alert editor open on cancel; `portfolio_alarm_threshold_invalid_recreate`; iOS `AppData+DeliveryMarkers` prunes
  after the write, `AppData+PairPickerKey`, `SwitchRow(dimsLabel:)`; per-call guards `WatchRepositoryCallGuardTest` /
  `WatchlistConfirmCallGuard(Tests)`; `ios_release_audit.py` string-aware comment stripping; `release_readiness.py`
  head build check for ROADMAP/TESTPLAN.

## 16.2.2 (build 105)

* Watchlist not saved (encryption not active yet): the confirmation now comes once per action and before it – «Cancel» changes nothing (no «removed» banner, notifications stay, no «alert limit reached»); «Continue anyway» runs the normal path with banner and undo; actions on many pairs ask once; on iPhone the question also appears above the alert editor and the explorer.
* Alerts: «falls by 100 % or more» can no longer be switched on (pair and portfolio alerts); stored portfolio alerts of that kind are switched off once and marked; an old delivery marker no longer swallows a new real trigger; iOS stops notifying at the end of the background time also in the extra-data path and between alerts; iOS only clears a delivery marker after the alert was really written; Android keeps delivery markers while only an in-memory copy is open.
* iOS: the privacy manifest matches the code again (monotonic clock instead of the system uptime API) – checked in CI.
* Security: DexScreener pool ids with «.» or «..» parts are rejected; coin logo addresses are checked with a real URL parser (no tricks with «#» or «\»).
* Performance: the coin page only recomputes when a price it needs changes (both platforms); the iOS pair picker builds its rows in the background.
* Privacy policy: alert log in diagnostics also lists the condition; background error counter stays on the device (on iPhone also in the device backup).
* Release and tests: source commit must be a full 40-character hash; tag must match version and build; iOS storage tests run in CI without skip; source guard tests fail when their file is missing; UI test requires the first-alert explanation.

**Technical notes**

* Build 105 (review round 3): `WatchlistNotSavedGate` asked once per user action before side effects – Android
  `confirmUserWrite()` before `withRefreshLock`/`undoMutex`, repository write paths return `Boolean` / save outcome
  (`WatchRepositoryAlarmSave`), cancelled waiter clears `pending`; iOS `withWatchlistConfirm` (async), `retry` removed,
  alert in its own window (`WatchlistNotSavedGatePresenter`, 2 s safety net); fixtures `watchlist_not_saved_gate.json`
  (`actions`, `cancelledWaiter`), `alarm_toggle.json` (`toggleAllowed`). `AlarmDelivery` `savedSinceMarker`
  (`alarm_delivery_marker.json` `savedSinceCases`, `deliverCases`, `resetCases` / `shouldClear`), iOS `persisted` step,
  `deliverAlarms(isCancelled:)`, `submitIfCurrent`; portfolio `CHANGE_DOWN` < 100 (`portfolio_alarm.json` `threshold`,
  `PortfolioFallAlarmCleanup`); `LogoUrlPolicy` (`logo_url_allow.json`); DexScreener dot segments
  (`dex_pair_id.json`); `ios_release_audit.py privacy` (required-reason APIs vs. `PrivacyInfo.xcprivacy`);
  `release_readiness.py` tag check; commit `^[0-9a-f]{40}$`; `SourceGuard` / `REQUIRE_IOS_SOURCES`.

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

**Technical notes**

* Build 104 (review round 2): `AlarmDelivery` `isShown(markerAt)` with post-time tolerance (`alarm_delivery_marker.json`
  `postedAt`), `mark()==false` → `retract()`, marker cleanup (delete, restore, DB onCreate); Android
  `PortfolioAlarmDeliveryMarkers` for portfolio alerts; `FallAlarmCleanup` / iOS `disableInvalidFallAlarmsOnce`
  (`alarm_threshold_invalid_fix`, `backup_alarms.json` `expectedEnabled`); `PortfolioFeeExpenses.transferUncovered`;
  `CsvImportState.Preview.choice`, `CsvImportTime` later offset at overlap (`csv_import.json` dates `zone`);
  `BackgroundRunSeams`, cancelled run after saving does not notify (Android `ensureActive` in `RefreshEffects.report`);
  `BackgroundDemandGate` (generation); `HttpUserAgent` privacy hosts always neutral (Firefox ESR UA,
  `address_privacy.json` host cases), own pool/dispatcher for address lookups; `DexScreener.isSafePairId`
  (`dex_pair_id.json`); workflows macro/market-data/exchange-live-check as fetch job + publish job
  (`artifact_check.py`); `line_limit.py --self-test`; `REQUIRE_GIT_COMMIT` / Gradle stop without `gitCommit` for
  signed release builds; CI workflows also on tags `v*`; iOS skipped-test check in `ios.yml`; `AlarmPickerRows`;
  `BackgroundErrors` counter per area in diagnostics (`background_errors.json`); `WatchlistNotSavedGate`
  (`watchlist_not_saved_gate.json`); `DatabaseEncryptionParallelTest`.

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

**Technical notes**

* Build 103 (two review rounds): watchlist read-only while `storedKept` (Android `WatchRepository.userWriteRefused()`,
  `AddWatchOutcome.Locked`/`PairEditResult.LOCKED`/`BulkAddResult.locked`, `WatchlistWriteRefusal` + `WatchlistLockedHint`;
  iOS `AppData.watchlistWriteRefused()` for `watchlistLocked`); `EncryptingOpenHelperFactory.prepareLock(name)`;
  `util/AppScope.appScope()` (SupervisorJob + handler) for all process-wide scopes; `PortfolioWriteResult`
  (OK/LOCKED/FAILED, `portfolio_write_failed`), restore `NonCancellable`, coin page shares the overview's
  `PortfolioViewModel`, `PortfolioCoinDetailFlow` / iOS `PortfolioCoinLedger` off the main thread; `CsvReadChoice`
  (confirm gate, `csv_import.json` › `confirmGate`); `AlarmDeliveryMarkers` (`alarm_delivery_marker.json`);
  `AlarmPairPicker` (`alarm_pair_picker.json`); falling percent < 100; undo respects `WatchlistLimits`;
  `ThresholdParser` Indian grouping; iOS `PortfolioSaveRetry` (merge path), `ProtectedFile+Durability` (`.full`/
  `.barrier`), `BackgroundExpiry`, `BackgroundWorkDemand` (`periodic_needed.json`), widget draw budget 7 s,
  `BackgroundRefreshTask` protocol; `HttpUserAgent.NEUTRAL` for address lookups (`address_privacy.json`); live
  message cap 1 MiB; `AppLock.authenticatorsFor(sdk)`; `taskAffinity=""`; diagnostics `Source:` (BuildConfig
  `GIT_COMMIT` / Info.plist `CCGitCommit`); workflows `keepalive.yml`, `line_limit.py`, token split in
  `macro-calendar.yml` and `exchange-live-check.yml`, Binance via data-api.binance.vision, `ios.yml` input
  `thread_sanitizer`; tests `RoomSchemaMigrationTest` (room-testing), `WatchlistLockDeviceTest`,
  `PriceUpdateWorkerTest`, `WatchlistLockBehaviorTests`, `BackgroundRefreshRunTests`.

## 16.2.2 (build 102)

* Backups: restoring refuses before replacing anything when the safety journal of the current data cannot be written (e.g. key temporarily unavailable) and says so; the rollback path is tested on its own. Alerts with a threshold of 0 or less (price level, percent, move, volume spike, open interest) are dropped and counted as invalid. Settings round-trip Android ↔ iOS checked against a shared fixture.
* Android storage: a device key that is briefly unavailable is retried and only treated as lost after a second app start; the portfolio lock is assumed on when the settings file is damaged; the backup password is no longer kept in memory (salted hash) and decrypted backup content expires after 5 minutes; set-aside database copies are deleted after the notice was confirmed and 30 days have passed. iOS: unreadable settings are also stored with the lock on.
* Alerts: iOS limits notifications per refresh like Android (20 plus a summary); percent-down thresholds are range-checked; editing an alert keeps fields saved meanwhile; re-arming checks the currency; the move history is stored per pair.
* Portfolio and tax: French fee path for uncovered rows, Austrian old holdings, rounding to 18 digits (banker's rounding) on both platforms; deleting a coin also removes fee references to it (with a notice); fees of withdrawals without a coin are shown as unassigned; an xpub with very many used addresses falls back to the quick search instead of failing daily and shows a hint.
* iOS widgets: shared row layout, safer logo decoding, per-widget storage, 10 s deadline for the portfolio widget, data written durably; reduce-motion respected.
* Interface: undo combines several removals into one; snackbars instead of toasts; actions have spoken labels; live prices use their own connection pool; the lock prompt opens only once.
* CI and docs: iOS paths trigger the Android workflow where shared fixtures change, marketdata lint; repository setup script also scans the history for secrets.

**Technical notes**

* Build 102: backup restore goes through `RestoreRun` (journal first — `BackupJournalUnavailableException` / iOS
  `Failure.journalUnavailable` before any write; `RestoreRollback` separate, `RestoreRunTest`); `DatabaseKeyRetry`
  (3 × 500 ms, key «lost» only on the next run via `<db>.key-suspect`); `SettingsLockFallback` / iOS
  `SharedStorage.settingsAfterRead` (lock assumed on when settings are corrupt); `BackupReadCache` salted SHA-256, 5 min
  plaintext TTL; `AsideRetention` (30 days after first seen, notice confirmed, current file opens); `LiveJson` depth 128;
  `HttpBodyLimit` start buffer ≤ 256 KiB; `AlarmJson.needsPositiveThreshold` incl. volume spike and open interest
  (`backup_alarms.json`); `SettingsBackupTarget` + `backup_settings.json`. Alerts: iOS `NotificationBudget` (20 +
  overflow, `notification_budget.json`), `ActivityDueOrder`, `AlarmSaveBuilder` + `WatchRepository.saveAlarmMerged`,
  per-key `MoveHistoryCore`/`MoveHistoryStore`, `AlarmLog.shouldPersist`. Portfolio: FR fee path, AT old holdings,
  18-digit banker's rounding, `PortfolioCoinDeletion` (fee refs), `PortfolioUnassignedFee`, xpub QUICK fallback when a
  FULL scan would exceed `XpubScan.MAX_REQUESTS` with a cache (hint `portfolio_address_xpub_limited`; a FULL that still
  fails stamps `fullScan`, so it is retried after a day),
  `OwnCandleDataSource` bounded. iOS widgets: `WidgetRows`, `CoinLogoDecode`, `PerKeyDefaults`, async
  `reloadFromDisk`. UI: `NavigateOnce`, `RemovedBatch` + `UndoSlot.merge`, `LiveDispatcher` (own pool),
  `UserAgentInterceptor`, `LiveRules.tickTime`. Fixtures changed: `live_feed.json`, `exchange_okx.json`,
  `tax_templates.json`, `portfolio.json`, `address_balance.json`.

## 16.2.2 (build 101)

* Portfolio: a holding without a known coin name whose ticker stands for different tokens on other exchanges (e.g. BEAM, NEIRO-like symbols) no longer silently takes the Binance price as confirmed – the value is still shown, but marked «Price unchecked», and portfolio alerts treat it like an outdated price (they pause only if it makes up at least 1 % of the portfolio).
* Alerts overview: own hint «Portfolio alerts paused: price unverified for …» when only such prices block the portfolio alerts.
* Cut-off and tax exports and the portfolio value history no longer use an unverified price to confirm day-close prices of that ticker; affected coins are marked «unverified» as before for unchecked prices.

**Technical notes**

* Build 101: nameless portfolio holdings on an ambiguous ticker — `PortfolioPriceIdentity.binanceCheck(..., otherNames)` returns
  `UNVERIFIED` when Binance's name and the names of other stored pair lists (`TokenIdentityStore.otherNames`) clearly
  conflict (`TokenIdentity.conflicting`); the Binance price is used but listed in `PortfolioPrices.unverified` (row «Kurs
  ungeprüft»; persisted with the price as 4th field `;u` in `portfolio_last_prices.txt`, `StoredPrice.unverified`, never a
  candle reference via `PortfolioPriceFallback.confirmed`; wrapped coins inherit it from their base), stored in the snapshot (`unverifiedCoins`/`unverifiedValueUsdt`) and counted like stale prices by
  `PortfolioAlarmLogic.staleMaterial`; own pause reason `PriceUnverified` when no stale/missing prices block. Exports and the
  value-history chain exclude unverified prices as references (`CutoffExport.referencePrices`/`referenceOf`). Fixtures:
  `portfolio_price_identity.json`, `portfolio_alarm.json`, `cutoff_export.json` (`referenceCases`, `chainReferenceCases`).

## 16.2.2 (build 100)

* Haptics unified on Android and iOS: one catalog by event (selection, light tap, long press, success, warning, error, delete) and one wrapper per platform. Android now also confirms saving, backups, undo and copying, warns at limits and validation errors and marks failures and deletions like iOS. No vibration while scrolling, on price ticks, in widgets or in the background; the system haptics setting applies.

**Technical notes**

* Build 100: haptics only through `ui/components/Haptics.kt` (`HapticEvent`, `Haptics.constantFor(event, sdk)`,
  `rememberHaptics()`) and `ios/App/Components/Haptics.swift` (`Haptics.play`, `.haptic(_:trigger:)`); `HapticsUsageTest`
  forbids direct haptic APIs elsewhere and any haptics in notification/widget/work/tts code and iOS Shared/Widgets.

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

**Technical notes**

* Build 99 (second full audit): `WatchlistLimits` (Kotlin/Swift, 2000 pairs, 10 alerts per pair; `BackupRestoreLimits`
  reuses it), `AddWatchOutcome`. iOS `PrivateSessionConfig.make` (no cookies, no URL cache) for every URLSession,
  `StorageKey.renew()` with an App Group generation counter so the widget re-reads the key, `OrderedSerialRunner` for
  background widget snapshots. `TextLimit.clean` (trim, cut by graphemes, trim) for notes, groups and exchange names;
  fixtures `backup_limits.json`, `text_limit.json` («fields»). `BackupRestorePolicy` rules for `systemBackup` and
  `hideAmounts`. CSV `tokenizeOrNull` caps (200 cells, 200 000 rows). `PortfolioTxFirstInvalid`, `DexFailure`,
  `LegacyDatabaseCleanup`, `BoundedCache.MEMORY_CACHE_ENTRIES`. JVM unit tests run with en-US/UTC.

## 16.2.2 (build 98)

* iOS like Android: an exchange pause with an implausible end time (clock set back) no longer lifts the pause on the next rate limit (also for the shared Binance pause); re-enabling a «near high/low» alert keeps the reported mark (no second notification for the same high); funding/open-interest alerts without data show «not checked».
* iOS: watched-address answers and coin logos are read with a size limit like Android (2 MB / logo limit). Android: deeply nested exchange answers are rejected before parsing instead of crashing.
* Diagnostics report: the temporary file is deleted after use (Android at the next start or when the preview closes; iOS only written when sharing).
* Notes count visible characters on both platforms (emoji, flags and families no longer count double or get cut in half); month and year follow the language on Android like iOS (e.g. Japanese «2028年10月»).
* «Label: value» texts follow the language's typography (French « : », Chinese/Japanese «：»).
* iOS: portfolio figures are computed in the background once per refresh (no stutter with large portfolios); date formats are reused; the password eye and the favourite star have 44 pt touch targets. Portfolio amounts shrink instead of being cut with «…» (Android and iOS). A double tap on «Save» no longer creates two alerts (Android and iOS). Android caches for hourly candles and sparklines are bounded.
* Privacy policy: corrected sentence on a failed encryption conversion (the old file is only read since build 93).
* CI: the release smoke test reads newer job lists (API 34) and fails on main when the emulator gave no result; a Room schema check fails when an entity changes without a new database version; runs on main are no longer cancelled by newer pushes; runners pinned to Ubuntu 24.04.

**Technical notes**

* Build 98 (full audit of build 97): fixtures `exchange_backoff.json` (next/groupPause/adoptedPause/merge with
  implausible pauses) and `text_limit.json` (`TextLimit.takeGraphemes`/`graphemeCount`, java.text.BreakIterator;
  ZWJ/flag/skin-tone cases need JDK 20+ in JVM tests, `TextLimitDeviceTest` covers them on device). `label_value`
  resource for «label: value» (`LabelValueUsageTest` scans Kotlin and Swift sources). `MonthYearFormat` (ICU skeleton
  «MMMyyyy»). iOS `PortfolioSummaryCache` (off-main summarize, newest result wins), `DateFormatterCache`,
  `AlarmCreateGuard` (both platforms). Android `BoundedCache`/`KeyedMutex`, JSON depth guard in `MarketHttp`
  (`HttpBodyLimit.MAX_JSON_DEPTH`). CI: `room_schema_drift.py` compares exported identity hashes with HEAD.

## 16.2.2 (build 97)

* Removing a watched address that cannot be saved now shows a message instead of doing nothing.
* Background refresh: if the alert storage cannot be read for a moment, portfolio alerts are still checked instead of being treated as «no alerts» (Android).
* Corrupted settings: after they had to be reset to defaults, the app says so once and asks to check the portfolio lock, alert interval and currency (Android and iOS; iOS now also replaces the unreadable settings file like Android).
* «Reset app» mentions that device backups (Google, iCloud or computer) keep the previous data until the next backup runs.
* Other apps can no longer open the portfolio or other personal screens directly; notifications and widgets of the app carry a per-install token. Copied token addresses are marked sensitive (Android) and expire after 60 seconds, device only (iOS).
* Network fees: shared test cases for both platforms; a single unreadable fee entry is skipped instead of discarding the whole history, and signed hex values are rejected (Android and iOS alike).
* Store guides point to build 97; rollback notes explain that only a new build can replace a release.

**Technical notes**

* Build 97 (review 21 October 2026, afternoon): `OpenTargetGuard` (Kotlin/Swift) – per-install secret (not backed up)
  appended to the app's own notification/widget intents and URLs; without it only `add`, `alarms`, `cycle` and
  `watch/<id>` are opened. `StorageRecoveryNotice.SETTINGS_RESET` (Android: set by the DataStore corruption handler;
  iOS: `SharedStorage.settingsCorrupt(hasData:decodes:)`), cleared only on dismiss. `PortfolioSnapshotUpdater.shouldRefresh`
  treats unreadable alarm storage as «alarms may be active». Fixture `gas_fees.json` (feeHistory, gasPrice, mempool,
  transfer cost); hex parsing accepts ASCII hex digits only on both platforms.

## 16.2.2 (build 96)

* USDT depeg check: a price whose exchange time is more than 2 hours old no longer counts as a measurement (same rule as price alerts) – no depeg alert and no USDT rate in the portfolio from a stale last trade; shared test cases on both platforms.
* Percentages everywhere through the same formatter: depeg notification and threshold, indicator alerts and texts, alert sentences (no longer the platform percent format, which differs between Android and iOS), «Why» texts, market phase and cycle chart, portfolio shares and widgets, spot markets. Turkish «%0,5», Arabic/Persian «٪» and the regional spacing now apply there too. Alert percentages keep the thousands separator («1,000%»); iOS VoiceOver reads the cycle chart like TalkBack; iOS portfolio «0.00%» is kept left-to-right in Arabic/Hebrew like Android.
* Volume alerts: the editor explains that hours without trades count as zero volume (thin pairs). New test for a series whose newest candle is 20 hours old.
* Privacy policy: states that an unencrypted system-backup copy left behind when the app is stopped during a backup is deleted at the next start.

**Technical notes**

* Build 96 (review 21 October 2026, noon): `DepegAlarm.usablePrice(last, exchangeTime, now)` (Kotlin/Swift) drops a
  depeg reading whose exchange time is too old by `AlarmFreshness.exchangeTimeTooOld` (fixture `depeg_alarm.json`
  → `usablePrice`). Visible percentages go through `PercentText` only (new `minDecimals` for trimmed trailing zeros);
  `AlarmSentence`/`AlarmTexts` no longer use `NumberFormat.getPercentInstance` / `NumberFormatter.percent`, so ICU
  differences cannot split Android and iOS. Volume alert editor hint `alarm_volume_zero_hours_hint`; volume fixture
  case «newest candle 20 h old».

## 16.2.2 (build 95)

* iOS volume spikes are computed on the hour grid like Android (missing hours count as empty, a series that stopped gives nothing), shared test cases on both platforms.
* Portfolio alerts: an address that never loaded pauses them only for its first 24 hours on this device (counted again after a restore or «Undo»); afterwards it is treated as failing (named in the alerts overview) and alerts evaluate without it. TON tokens missing from a full jetton page are marked «partly stale» and count like stale prices instead of keeping an old value silently.
* Undo of «Remove address» reports when it cannot restore (address added again, list full, not saved). An unreadable or locked portfolio no longer decides whether the portfolio is switched back on after a settings reset; iOS keeps that decision pending across restarts until the files are readable (like Android).
* Percentages: the space before «%» follows the region too (e.g. es-MX without, en-DE with); all remaining fixed «%» in texts use the same formatter (Arabic/Persian «٪»); tiny changes show «0.000%» without a sign; chart summaries and the portfolio widget use the same format. Android times follow the system 12/24-hour setting.
* Undo after clearing a long watchlist no longer posts a burst of price notifications (like iOS).
* CI: the release smoke test adds starter pairs so background workers run in the R8 build and fails on worker creation errors; permissions are checked against an allow-list; release smoke also runs on pull requests touching release rules; store guides are checked for the current build; size budget warning at 22 MB. Store explanations state that watched addresses are watch-only (no keys, no wallet).

**Technical notes**

* Build 95 (final audit round, 21 October 2026): iOS `VolumeDataSource.spikeOf(_:now:)` mirrors Android (hour grid,
  `isLive`, missing hours = 0, 24 comparison hours covered; fixture `volume_spike.json`). `AddressAlarmState`: an
  address without loaded balance pauses portfolio alerts only while it has no error or was added < 24 h ago; failing
  addresses are named (`AddressesFailing`). `ChainBalance.parseTonJettonPage` → `WatchedAddress.staleTokens` (Android
  extra column key `stk`) counted like stale prices against the 1 % rule (`TokensStale`). `WatchedAddressUndo.failure`
  banners. `PortfolioDataCheck.keepMarker` while the portfolio is in memory; iOS `portfolioRecoveryDeferred`.
  `PercentText.isSpaced(language, region)` (es-MX no space; en-AT/DE/DK/FI/SE space); fixed «%» strings take `%1$s`
  from `PercentText`; `changePercentDetailed` via `PercentText.signed`; `ClockText` (Android 12/24 h from
  `is24HourFormat`). Undo restores price notifications only for ≤ 3 pairs. CI: smoke test seeds starter pairs
  (`starter_add`), reinstalls, forces jobs and fails on worker creation errors; `manifest_policy.py` allow-list of 11
  permissions; release smoke on PRs touching release rules; `release_readiness.py` checks the store guides' build;
  APK budget warning 22 MB.

## 16.2.2 (build 94)

* Watched addresses: an xpub checked in the background also looks at the next unused addresses, so change after a spend is no longer missing; an incomplete, lower background result is not stored. Addresses whose balance was never loaded (e.g. after a restore) pause portfolio alerts instead of counting as 0; an EVM network that did not answer stays selected; a full TON jetton page without a known token keeps the last value; background updates take the oldest addresses first; Litecoin uses its own supply limit; iOS keeps reading the address list when one entry is unreadable.
* Portfolio alerts: a stale price below 1 % of the portfolio value no longer pauses them; when they are paused, the alerts overview says why.
* «Notable» activity: hourly statistics only use returns exactly one hour apart and skip pairs with too many missing candles (no false ⚡ for thinly traded pairs).
* Live prices: reconnect delay resets only after a stable minute and is spread ±20 %; Android also closes a live connection when the exchange pauses, like iOS.
* Android: a corrupted settings file no longer hides an existing portfolio (iOS likewise for unreadable settings).
* Undo for «Clear watchlist», «Clear portfolio» and «Remove address» (the dialog names cost lots and history that go with an address; locked storage shows a notice instead of doing nothing).
* Consistent wording for unreadable saved data with one remedy («Restart the app»); English uses «alert» throughout and «Notable»; percentages follow the language (e.g. Turkish «%2,4», Arabic/Persian «٪»); time spans in notifications are localized; «last at» shows a date when it was not today; «Add pair» on the empty alerts overview; iOS explorer rows and fields grow with large text; Hebrew tax note without arrows.
* Release checks in CI: Android R8 release APK and bundle, release lint, merged-manifest policy, size, mapping, baseline profile and 16 KB alignment as annotations, plus a release smoke test on emulators; iOS release settings, a device build and inspection of the built app; store text limits, version parity and Xcode project drift on every pull request. Native debug symbols in the bundle; Swift optimisation set explicitly for Release; staged rollout documented.

**Technical notes**

* Build 94 (third audit of 21 October 2026): CI tests production readiness – `android.yml` builds R8 release APK +
  AAB + `lintRelease` and runs `.github/scripts/android_release_audit.sh` (`manifest_policy.py`, `apk_contents.py`,
  annotations via `annotate.py`); `android-device-tests.yml` job `release-smoke` (API 26/34, throwaway-signed R8 APK,
  start, monkey, WorkManager, crash/ANR); `ios.yml` release settings audit, unsigned device build, built-app inspection
  (`ios_release_audit.py`); `release-readiness.yml` (store limits, URLs, screenshots, version parity, Xcode project
  drift). Release `ndk.debugSymbolLevel = SYMBOL_TABLE`, `-PrequireReleaseSigning`, iOS Release `-O`. Addresses:
  `XpubScan` background lookahead 2 + `keepBackground`; `AddressBalance.pendingCount` → `addressesPending` pauses
  portfolio alerts; `staleMaterial` (stale < 1 % of value does not pause); `PortfolioAlarmLogic.pause` +
  `PortfolioPauseText`; `discoveredNetworks` keeps silent networks; TON full page → unknown, not 0; `dueOrder` oldest
  first in background; per-chain sat caps (LTC 8.4e15); iOS address file via `lenientArray`. `ActivityAnalyzer.hourStats`
  only 1-hour returns aligned to the last full hour, ≥ 20 returns. `LiveBackoff.nextAttempt`/`reconnectDelayMillis`
  (reset after 60 s stable, ±20 % jitter). `PortfolioDataCheck` / `enablePortfolioForData` after a settings reset.
  Undo for clear watchlist / clear portfolio / remove address (`WatchedAddressUndo`). `PercentText` (CLDR table),
  `PriceFormat.ageParts` (MeasureFormat / DateComponentsFormatter), `MomentText` (today time, else date + time).

## 16.2.2 (build 93)

* UK tax export: rebuys within 30 days after 5 April now get the exchange rate and day close of their own date (rates were only loaded up to the end of the tax year). CSV totals add up to the cent (gain per row = rounded proceeds − rounded cost); DE/AT withdrawal fees have their own total line, as in the summary. The «orientation, not a tax return» note also stands under the totals in the app.
* CSV import: fees in another fiat currency are converted (or flagged as missing rate) instead of being dropped; rewards with a fiat amount get their price from it.
* Exchanges: Bithumb's trade time is read in UTC (it was 9 h in the future), and any exchange time more than 5 minutes ahead is ignored; Lighter markets without trades in 24 h hold price alerts like a stale price; bitFlyer gives no price while trading is halted; LATOKEN no longer treats a snapshot time as trade time; HTX shows no day-start high/low as 24 h values; Independent Reserve volume is the pair's own. Recorded exchange answers are now checked for plausible times and high/low.
* Alert reliability line: keeps warning when only failed background runs remain, offline wake-ups no longer count as a check, and Android shows «alerts paused in the background» when the watchlist is only a copy in memory (the exact wake-up is then stopped).
* Android: when encryption fails although a key exists, the watchlist and portfolio are only read, never written unencrypted. An interrupted restore is retried once on the next start and otherwise given up (no automatic rollback over later changes). Large backups are read once and run out of memory with a message instead of a crash.
* iOS: an unsealed older watchlist is excluded from the device backup until it is sealed; «1 h later» also updates the app's in-memory state.
* No alert currency is offered for fiat quotes without an exchange rate; Android confirms copying the contract address below Android 13 and reads it out for TalkBack; the reliability line wraps at large font sizes; portfolio rows are read as one sentence by screen readers, and the iOS portfolio total no longer shrinks below 70 %; portfolio widget chart uses the same time zone as the single-pair widget; logo requests send the full app version.
* Store guides point to build 93.

**Technical notes**

* Build 93 (second audit of 21 October 2026): `TaxExport.priceUntil`/`fxSpan` load FX and day closes up to the end
  of the tax year + 30 days for UK/CA (capped at today); CSV gain per row = rounded proceeds − rounded cost;
  `TaxTemplate.withdrawalFeeApart` (DE/AT) gives withdrawal fees their own total line. CSV import converts any fiat fee
  unit (`needsFx` without rate) and prices rewards from the fiat amount (old import key kept as previous key). Bithumb
  trade time from `trade_date` + `trade_time` (UTC, `UpbitStyleMarket.utcTradeMillis`); `Market.markFetched` drops an
  exchange time more than 5 min in the future. Lighter with `daily_trades_count == 0`: exchange time = fetch − 24 h, so
  the 2-hour alarm rule holds alerts. bitFlyer `state` ≠ RUNNING → no price; LATOKEN `updateTimestamp` is not a trade
  time; HTX high/low only from spot `detail/merged`; Independent Reserve vol = `DayVolumeXbtInSecondaryCurrrency`.
  Contract tests check recorded answers (exchange time within recordedAt − 30 d … + 5 min, low ≤ last ≤ high).
  `CheckRunLog.lastFailedBackgroundCheck` keeps the reliability line STALE when only failed runs remain; offline exact
  wake-ups are not logged; `AlarmReliability` status STORAGE_NO_KEY (Android volatile copy, `hasWork()` false).
  Android `DatabaseEncryptionMigration.migrate` returns `PlaintextKept` for encryption-required databases when the
  export fails with a key (banner `watchlist_storage_copy_in_memory`). Restore recovery (`RestoreRecoveryPolicy`): one
  retry on the next start, journal older than 24 h or second attempt → give up. Android `BackupReadCache` (one read and
  one Argon2id per flow), OOM → `NotEnoughMemory`. iOS `PlainBackupExclusion` keeps an unsealed protected file out of the
  device backup; snooze rearm also applied in memory. `AlarmEdit.offersCurrency` hides alert currencies for
  FIAT_UNSUPPORTED quotes. Accessibility: portfolio rows one spoken sentence (`coinRowSpoken`), iOS total min scale 0.7,
  Android DEX copy toast below API 33. Portfolio widget bars and grid in `chartZone(basis)`. Logo user agent from
  `HttpUserAgent.android(VERSION_NAME)`.

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

**Technical notes**

* Build 92 (audit of 21 October 2026): `PortfolioHoldingsBook.walletKey` parks only `AddressBalance.isAddressPart` in the
  address pot; address opening lots (`AddressCostLots.isLotTrade`) live in their wallet like in `PortfolioFifo`, so a
  later sale or transfer from that wallet consumes them (cut-off, DE sheet, CH wealth with FIFO per wallet;
  `address_cost_lots.json` cases «lot moved away and sold elsewhere» / «sold directly»). Kraken staking pairing: same
  refid first for all staking legs, then the 1-hour window for the rest (`csv_import.json` `kraken_staking_refid_first`).
  iOS: `Outcome.keepingCurrentPairs` before every `deliverAlarms` (app, BG task, widget) and `AlarmUpdate.pair` makes
  `saveAlarmOnDisk` refuse a changed pair; «1 h later» rearms via `SharedStorage.updateSnapshot` + `AlarmSnooze.rearm`
  under `BackgroundTaskGuard`, then `reloadFromDisk`; switching off / editing / deleting an alert removes its delivered
  notification on both platforms (Android `AlarmUserChange`). Speech of converted alerts uses the shown price and
  currency (`SpokenText.spokenAlarmPrice`). Backups: compact JSON (`BackupFormat.encode`, iOS `BackupManager.encode`,
  sorted keys), `BackupLimits` 64 MiB read and write (`checkWritable`, message `backup_export_too_large`); CSV import stays
  16 MiB (`CSV_MAX_BYTES`). iOS `finishRecovery` keeps the restore journal when the rollback failed. Android
  `DatabaseEncryptionNotice.isVolatileCopy` (PlaintextKept): `BackgroundCheck` opens the database first and skips the run,
  alarm log is not pruned, restore refused (`BackupRestorePolicy.storageRefusal`, `backup_restore_storage_in_memory`).
  `PortfolioTxFormState` saver keeps the decimal separator (iOS: fixed when the sheet opens). `QuoteKind.FIAT_UNSUPPORTED`
  (`KNOWN_FIAT`, ISO 4217 without codes that are also coins) never triggers crypto lookups. `CheckRunLog.lastBackgroundCheck`
  ignores failed runs; `AlarmReliability.staleMillis(platform)` 2 h Android / 6 h iOS (`alarm_reliability.json`
  `staleMillisIos`). `FuturesContractType.getDeliveryDate(now)` / iOS `delivery(asOf:)` roll to the next contract after
  expiry 08:00 UTC. WOO trade time must be finite and < 2^63 ms. iOS copy confirmation for the DEX address
  (`ios_dex_token_address_copied`). iOS CI builds the Release configuration too.

## 16.2.2 (build 91)

* Watchlist: price one step larger, the change basis («24h») always shown, and «+2.4 % · Binance» when the change comes from another exchange's candles; a quiet line shows the last background check and turns amber when alerts are at risk.
* Alerts: «1 h later» also for price levels; the editor shows pair, condition, amount and once/every time, the rest under «More»; volume-spike texts name the candle source; Android keeps «Undo» after «Check».
* Franc/euro alerts skip a check (and stay armed) when the FX rate is older than 36 business hours or when USDT/USD is only known via a USDC that is off by more than 1 %; USDT/USD is measured whenever such an alert or conversion needs it.
* Exchanges: Lighter markets without trades show no «0.00 %»; delisted pairs are confirmed on CoinDCX, Luno, ICRYPEX and HashKey; BingX and bitbank rate limits are recognised; DexScreener pairs show the contract address in the pair sheet; Aster «U» counts as USD. WOO X spot shows the real last trade and its time (no more «hourly price»).
* Portfolio: watched addresses count like the card in the cut-off file and the Swiss wealth file, also without cost lots; the card itself now counts a recorded transfer to an address once (and no longer drops a transfer away from it). Binance prices without a known coin name are only used for unambiguous symbols. DE 256 € line marked when values are estimated.
* Storage: without a device key Android never writes the watchlist or the portfolio unencrypted, like iOS (it stays in memory with a notice; an older unencrypted file is only read and stays untouched); addresses cannot be changed while the stored portfolio is locked; iOS reads the watchlist off the main thread when coming to the foreground.
* Tab labels shrink at large font sizes; widgets follow the app's type scale and use the system font for Arabic and Persian amounts; the portfolio sheet survives rotation; the single-coin widget opens its pair.
* APK keeps only the 31 app languages (checked by a device test); the app's licence file matches LICENSE, the origin note is separate; Play texts say «at most once per 15 minutes».

**Technical notes**

* Build 91 (post-launch list done early, 21 October 2026): foreign-candle 24 h change shows the short exchange name
  at the number; volume-spike texts name the candle source. Fiat alerts skip a cycle when the FX rate is older than
  36 business hours (`fx_stale`) or USDT/USD is only known via USDC with USDC > 1 % off (`fx_unsure`, row «Conversion
  uncertain»); USDT/USD is measured whenever a fiat alert or ≈ conversion depends on it. Aster quote «U» = USD 1:1
  (`CurrencyConversion.conversionQuote`). Lighter rows with 0 trades leave the change empty; CoinDCX, Luno, ICRYPEX,
  HashKey bulk lists complete (`RecordedBulkCompletenessTest`); BingX 100410 and bitbank 10009 are rate limits; DEX
  token address kept per pool (`dex_token_address`); Binance price without known name only for non-colliding
  symbols (`PortfolioPriceIdentity.binanceCheck`). «1 h later» for price levels; `AlarmReliability` line above the
  watchlist; alert editor with «More»; Android undo survives «Check» (`AlarmUndoOffer`). Row price one step larger
  (`DenseRowText.largePrice`), change basis always shown; `TabLabelFit`; widget type scale (`WidgetType`) and
  ar/fa amount font; portfolio sheet/confirmations saveable; single widget opens the pair. Android watchlist never
  created unencrypted (in memory + notice without key); an existing unencrypted watchlist or portfolio without a key is
  only read (`PlaintextMemoryCopy`, decision 21 October: Android like iOS); WOO X spot makes a second request
  (`/v1/public/market_trades`, live response 20 October) for the real last trade and its time, keeps the candle opening
  for the change, and no longer shows «hourly price»; address writes refused while the portfolio is locked;
  iOS foreground reload off the main thread (`AppData+DiskReload`); lease in the future = orphaned. Stichtag and
  Swiss wealth count addresses like the card (`AddressCutoff`), including addresses without lots; the card counts a
  transfer to the address once (1.4, not 1.7). DE 256 € line marked estimated. `localeFilters` with
  `LocaleFiltersTest`; app licence file = LICENSE plus `NOTICE-origin.txt`.

## 16.2.2 (build 90)

* Exchanges: the «no longer traded» recheck state is stored, so a fresh background process no longer downloads Binance's full list (~2 MB) each time; the iOS widget never starts a recheck.
* Swiss tax card: a partly known wealth value shows the «incomplete» note under the value, as in the CSV.
* iOS: deleting, undoing and clearing the portfolio only report success when the file was written; otherwise the «changes won't be saved» notice appears and cached prices stay.
* Android CSV import links stored transfers in one database transaction and says plainly when the locked portfolio prevents an import.
* Indicator alerts on older installs store their limit, so a later limit change notifies; switching a gas alert off can no longer be undone by a check that is running; background checks no longer hold their lock while waiting for the pulse widget.
* CSV transfer matching: when withdrawal and deposit differ by a network fee in the coin, the transfer now records the amount that arrived (fee on top at the source), so per-wallet FIFO (DE, AT, UK …) and wallet balances are correct. Transfers imported before build 90 keep their old amount.
* Light mode uses the same warm orange-red error colour as dark mode (#B8361A, error surfaces #FFDBD0) instead of Material red and pink, for every accent and in high contrast; iOS delete and reset actions use it instead of the system red.
* Alert editor hint reads «Enter a value»; Czech prompts use one form of address; Arabic xpub hint grammar; Greek and Ukrainian App Store texts shortened below the limit.

**Technical notes**

* Build 90 (third audit of 20 October 2026): `BulkDelisted` state is persisted (`BulkDelistedStore`, SharedPreferences
  `bulk_delisted`; iOS App Group key `bulk_delisted`); the iOS widget never rechecks (`plan(…, lowMemory: true)`).
  `wealthCoinNoteBelowValue` shows the incomplete note under a partial CH value. iOS `PortfolioEdits` only reports
  delete/undo/clear after a successful write. `PortfolioRepository.linkStoredTransfers` writes CSV links in one
  transaction; `portfolio_csv_import_locked`. Indicator `decide` re-stores the limit while beyond; gas reset runs
  under `GasAlertChecker.lock` (iOS: reset generation); `BackgroundCheck` waits for pulse/speech after `runLocked`.

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

**Technical notes**

* Build 89 (second audit of 20 October 2026): `bundle { language { enableSplit = false } }` keeps all locales in the
  AAB for the in-app language picker. iOS `RefreshSchedule.isImplausiblyFar` replaces a far-off BG request (launch,
  foreground, clock/time-zone change); readiness treats a last check > 5 min in the future as old
  (`FUTURE_TOLERANCE_MILLIS`). `BulkDelisted.plan` rechecks all pairs once when every pair is marked and no state
  exists; `NotTraded.confirmWithoutBulk` for markets whose single ticker reads the full list (Lighter,
  Hyperliquid). Hyperliquid change = shown price vs `prevDayPx`. `DexPool` keeps `baseToken.address`. WOO spot rows
  showed `watch_price_hourly` (build 91: real last trade instead). `MarketAlertReset` clears remembered alert state when a market alert is switched off;
  indicator zones store their limit with the beyond-flag. Quick-alert undo waits for the first-alert dialog
  (`AlarmsScreenSnackbars`). Portfolio writes (incl. delete/undo/clear/replace) are refused while locked;
  `PortfolioStore.keepsUnsealed` on iOS. Backups carry `overlapChecked`/`overlapLegacy` (and costLots on iOS).
  `LegacyStorageMigration.interestRewardLegacy` freezes the old interest rule for legacy data. `TaxWealthRow.incomplete`.
  CSV `escape` skips leading spaces/NBSP before the formula check. `PulseBackgroundFetch` lets background checks
  await the pulse fetch (≤ 20 s, within deadlines). xpub gap stays 20 (`portfolio_address_xpub_gap`).

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

**Technical notes**

* Build 88 (audit of 20 October 2026): gas alerts store the threshold with the armed state and re-arm on change;
  Fear & Greed stores its thresholds with the last value and re-evaluates only the changed bound. iOS
  `EpochTime.isIntervalDue` treats a last check in the future as due (gas, depeg). Android re-syncs the exact
  alarm on TIME_SET / TIMEZONE_CHANGED (`resyncsExactOn`) and counts the pulse widget in the background demand.
  `AlarmsViewModel.watchMissing` returns from the alerts screen when the pair is gone, saving skips a missing pair,
  and deleting pairs cancels their alert notifications (both platforms). Quick-alert chips show «· from now»
  (`alarm_template_from_now`, they have no window) and the banner uses `alarm_saved_banner`. `PortfolioStorageLock`
  refuses portfolio writes while the stored portfolio is kept (key briefly unavailable). Pair lists are parsed on
  `Dispatchers.Default` in `MarketRemoteDataSource`. Lighter price 0 → «No data»; `BulkTickerRows` needs price > 0.
  `TaxEngines.interestReward` matches whole words. Swiss wealth rounds each part before summing per coin;
  `AddressCostLots.hasCountedLots(addresses, cutoff)` follows the cutoff. iOS release notes wait in
  `ios/fastlane/release_notes_later/` until the first update (APP_STORE.md 7.2).

## 16.2.2 (build 87)

* Alarm editor: quick alarms moved below the price condition, so the first thing you set is your own price; a chip still creates the alarm at once (with Undo).
* Watchlist hint now says that tapping a coin opens alerts and «What is moving with it?»; the empty start screen shows a chart icon instead of a bell that could not be tapped.
* Watchlist that cannot be unlocked right now (device key briefly unavailable): the notice stays until it is resolved and saving alarms is blocked with an explanation instead of creating alarms that would be lost. iOS now shows the same notice.
* Watched addresses switch to «show only» automatically only when every coin with a balance matches your own transactions (before, one matching coin hid the other tokens).
* Tax workpaper: the holdings file uses the template's cost method stated in its header and carries the «template replaces the method» note.
* Swiss wealth (31 Dec): the total is the sum of the rounded rows in the file.
* Cut-off and Swiss wealth exports add a header note when a counted watched address has cost-basis lots (transfers to or from it may appear twice or be missing there; the portfolio card is correct).
* Play store text (all languages): background checks «at most every 15 minutes, often only after hours», as the app itself says.

**Technical notes**

* Build 87 (review of 20 October 2026): quick-alarm chips come after the condition and the price sentence in the
  alarm editor (tap still creates the alarm with a 6 s undo). While the saved watchlist cannot be unlocked
  (`DatabaseEncryptionNotice.isStoredKept` on Android, `SnapshotRead.inaccessible` / `WatchlistStorageLock` on iOS)
  the watchlist keeps a persistent notice that returns after other banners, and saving alarms or quick alarms is
  blocked (`alarm_save_storage_locked`). Watched addresses switch to «show only» automatically only when every coin
  with a balance matches (`overlapAllMatched`). The tax workpaper's holdings file uses the template's cost method
  (`TaxMethod.holdingsCostMethod`) and states the method note; the general cut-off export keeps the app's method.
  The Swiss wealth total is the sum of the rounded rows. Cut-off and Swiss wealth exports add
  `portfolio_export_address_lots_note` when a counted address has cost-basis lots (`AddressCostLots.hasCountedLots`);
  the double count of recorded transfers to such an address in those exports is a known limitation (roadmap).

## 16.2.2 (build 86)

* Internal clean-up: string files grouped by topic, unused strings removed, translation check enabled in lint, no change in behaviour.
* Android watchlist widget: the refresh countdown no longer stays at 0:00 after unlocking – with the screen off the widget shows the refresh symbol straight away, and the redraw at the end of the 15 s lock is retried reliably.
* Android pair search: results are computed in the background but set on the main thread (fixes an intermittent crash in the device tests).

**Technical notes**

* Watchlist widget refresh lock (build 86): Android draws the countdown (Chronometer) only with the screen on
  (`WidgetRefreshState.countdownMillis`); drawn with the screen off it shows the symbol at once, since a frozen
  process may miss the redraw at the end of the lock. The redraw runs 300 ms after the end (`UNLOCK_SLACK_MILLIS`,
  in-process and as the alarm fallback) and is planned again whenever the previous one has already run. iOS needs
  no change: a timeline entry at the end of the lock brings the symbol back.

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

**Technical notes**

* File length: every production file ≤ 400 lines (SwiftLint's default warning, applied to Kotlin too);
  `FileLengthTest` scans Android and iOS sources; the only allowlisted file is `settings/AppSettings.kt` (one
  data-class constructor with ~95 documented settings), ceiling only goes down.
* Staleness: `PriceFreshness.staleReason` – FETCH when the last successful fetch is older than the user's limit,
  TRADE when the exchange reports a last trade older than the alarm limit (2 h, `AlarmFreshness`); row, quick
  filter, sheet header and widgets use it, so row and alarm block on the same condition. «x ago» stays fetch time.
* Single ticker: a missing, 0, negative or non-finite price is «Parsed ticker has no data» (parse failure) on both
  platforms; `NotTraded` never confirms on `MarketFailure.Other`, blank text only without any failure type.
* Network: OkHttp dispatcher 32 / 5 per host, `CallStartInterceptor` + `CallGuard` start the guard timeout when the
  call actually runs; iOS `RequestGate` (32 / 5 per host before a task is created, 6 connections per host).
  Identical concurrent POSTs share one request (Hyperliquid). DexScreener: pools per chain in one
  `/latest/dex/pairs/{chain}/{a,b,…}` call (≤ 30), never «complete», liquidity rule per pool.
* Notifications: «1 h later» (repeating percent/movement alarms and, since build 91, price-level alarms – `AlarmSnooze.rearmsOnSnooze`) stores `snoozeUntil` per alarm outside the database (Android
  prefs `alarm_snooze`, iOS App Group `alarm_snooze_until`), honoured at delivery like quiet hours (outcome
  SNOOZED); «Adjust» opens `alarm/<watchId>/<alarmId>`; coin logo only from the local cache (300 ms limit).
  Automatic announcements use the notification stream (Android USAGE_NOTIFICATION, iOS `.ambient`) and stay silent
  on a muted phone, in a call and in Do not disturb – like the alarm sound; user-started tests (voice/alarm) always
  play (Android media stream, iOS `.playback`). Alarm log entries carry the check origin (app, background/exact,
  widget).
* Pull to refresh inside the 15 s window: accepted, 400 ms spinner, «Just updated · N s ago»; no network run.
* Reference data: `bip32_fuzz.json` (1,000 random xpub/ypub/zpub leaves with all four address types, 74
  point-arithmetic edge cases, 12 invalid keys; generator `android/tools/gen_bip32_vectors.py`, cross-checked with
  OpenSSL via Python `cryptography`) and `argon2_random.json` (20 random cases) on both platforms.

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

**Technical notes**

*Market movers*

* Card «Gainers and losers 24 h» in the Market tab under the Pulse card: top 5 up / top 5 down among the top 100
  by market cap without stablecoins and wrapped/staked coins (same exclusion as the breadth), valid 24 h value,
  no duplicate tickers; exactly 0 % is neither; ties by rank, then ticker; fewer than 10 valid coins → hidden.
  `MarketMovers.kt` / `MarketMovers.swift`, fixture `market_movers.json`. The Pulse CoinGecko request now asks for
  `per_page=100` (one request, same cache); the breadth still uses the first 30 valid coins. Tap behaves like
  «Unusual today».

*Alarm delivery and diagnostics*

* «Make sure alerts arrive» (`AlarmReadiness.kt` / `.swift`, fixture `alarm_delivery.json`): pure rows from the real
  state – Android: notification permission, alarm channel importance (read without creating it), «Check on time»
  (exact alarms), battery optimisation (informational, opens the app's battery settings; the app never requests the
  exemption, a test checks it), background restriction, Do not disturb, app quiet hours, last background check
  (warning after 6 h when alarms are on); iOS: notification authorisation, alert/sound, time-sensitive, Background App
  Refresh, Low Power Mode, quiet hours, last background check, swipe-away note. Reachable from «All alarms», the alarm
  editor's background hint and Settings › Alerts. Read-only: the alarm core is untouched.
* Fiat chart caption: when the sheet chart converts the USDT path with today's rate (`SheetChartFx.CURRENT`) it says so
  under the chart; daily-rate conversion keeps its own caption.
* «Share diagnostics» (Settings › About, `DiagnosticReport.kt` / `.swift`, fixture `diagnostic_redact.json`): plain
  text with English labels and codes – version/build, OS, device, locale, alarm-related settings, readiness rows, last
  20 check runs, alarm-log events (time, pair, exchange, condition, outcome – no prices or thresholds), exchange
  pauses. Input types carry no portfolio, amount, address or note fields; anything address-like is replaced by
  `[redacted]`. Preview first, then the system share sheet (Android FileProvider limited to `cache/diagnostics/`);
  nothing is sent automatically.

*Robustness and parity fixes*

* Keystore temporarily refusing the key of an EXISTING encrypted portfolio or main (watchlist/alarms) database
  (Android): `DatabaseEncryptionMigration.planOpen` → `KEEP_FILE_IN_MEMORY` only while the app is in the foreground
  (otherwise `WAIT`, as before): an empty in-memory database for this run, banner (`portfolio_storage_kept_in_memory`,
  `watchlist_storage_kept_in_memory`), the file is never overwritten, deleted or set aside; background checks,
  widget snapshot/history, system backup and the holdings migration wait for the next start. iOS has its own
  handling for unreadable protected files.
* Stablecoin / wrapped / staked exclusion: one rule per platform (`CoinExclusion.kt` / `CoinExclusion.swift`), the
  union of both former lists, case-insensitive name and symbol rules, fixture `stable_exclusion.json` – used by the
  «Top 30» breadth, market movers, start coins and «Unusual today».
* Settings search: `alarms.delivery`, `about.diagnostics`, `updates.alarm_log`; `SettingsSearchAnchorsTest` checks
  that every search target has an anchor on both platforms.

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

**Technical notes**

*Alarm log*

* `AlarmLog` (Kotlin `domain/alarm/AlarmLog.kt`, Swift `Shared/Services/AlarmLog.swift`): outcome enum (fired,
  not reached, outdated, mark price, low liquidity, quiet hours, notifications off, cooldown, already triggered,
  not shown, no rate, no price), last check per alarm (overwritten) and an event ring buffer (200 entries,
  14 days; equal consecutive outcomes merge into one «from–to» entry, fired entries never merge). Parity fixture
  `alarm_log.json`.
* It only observes: the refresh path records via wrapped `canShow`/`show` and a second call of the same pure
  rule; no firing decision changes, recording errors are swallowed. Covered: price-level and percent alarms.
  Not covered: volume, high/low, funding, open interest, depeg and portfolio alarms (own paths; portfolio
  amounts would bypass the portfolio lock).
* Storage: Android sealed file `alarm_log.sealed` in `noBackupFilesDir` (own Keystore alias, never plaintext;
  without a key it stays in memory), iOS `alarm_log.json` in the App Group via `ProtectedFile`, excluded from
  backups; both platforms write only sealed (without a key entries wait in memory; Android replays changes made
  while locked after loading). Live ticks write at most once a minute; only a fired alarm or a new outcome writes
  at once, merged «from–to» updates wait for the minute.
* UI: «Last checked» line in the alarm editor and alarm list; page «Alarm log» from «All alarms» and next to
  «Recent checks» in the update settings; «Clear log» with confirmation.

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
