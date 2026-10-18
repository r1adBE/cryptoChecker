# Changelog

## 16.2.2 – first public release

* Last price straight from the exchange that lists the pair – 32 exchanges, including futures and DEX pools.
* Price alarms run locally on the device: no account, no server.
* Home-screen widgets for the watchlist, single pairs, the portfolio and the market pulse.
* «What is moving with it?»: a checklist that puts a price move in context (market, Bitcoin and other factors).
* Optional portfolio (off by default): own transactions, CSV import, watched addresses and tax worksheets.
* Watchlist, alarms and portfolio are stored encrypted on the device.
* Backups with optional password (AES-256-GCM), interchangeable between Android and iPhone.
* 31 languages.
* Full source code of both apps under the MIT license.
* No tracking, no ads, no analytics.

Detailed build history below.

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
* Android: splash screen without empty window or colour jump; tab labels on two lines, at least 12 sp, selected tab
  with strong contrast; re-tapping a tab goes back to its start; «Set first alarm» card; the exchange's own error
  text in the refresh report; compact numbers and seconds in the device language; developer log only in debug
  builds; selection lists fit narrow screens and large fonts; no list inside a scrolling page.
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
* Tab labels at least 11 sp, the selected tab in bold (Android and iPhone).
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
