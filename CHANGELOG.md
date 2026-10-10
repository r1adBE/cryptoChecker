# Changelog

## 16.2.2 (build 17)

### App

**Watchlist & live prices**
* Live prices over WebSocket while the watchlist is open (Binance, Bybit, OKX,
  Coinbase, Kraken); REST stays the fallback. «LIVE» in the status pill, switch
  under Settings › Updates.
* Clear names for the two update modes: «Update frequently» (short intervals,
  also with the app closed, shows a notification) and «Live prices» (instant,
  only while the watchlist is open). No more «WebSocket» in the settings.
* Mini chart (24 h) per row, switched on under Settings › Watchlist (off on a new
  install); the % pill uses the same candles. Pulse line at the top
  («▲ 7 rising · ▼ 3 falling»).
* Tap opens the action sheet at once (no double tap), with a chart: 24 h / 7 d /
  30 d, candles or line, touch to read price and time.
* Swipe left to delete (with Undo), right for favorite; no star button any more:
  favorites keep the accent border and, with coin logos on, get a small star on
  the logo; jump button in long lists.
* Groups, a note per coin, second line «≈ value» in one of 31 currencies.
* Header with more room for groups: «All» as soon as there is one pair, no
  logo; ⋯ menu on the right (logo and app name → About, Refresh, All alarms,
  Sort, report). The bell next to it appears only while alarms are active and
  shows no number (a number read like «fired that often»). «+» (add pair) next
  to the search icon.
* The «+» (add pair) on the starter screen is the same filled accent circle as next to the
  search icon.
* First start: the starter selection waits briefly (max. 4 s) for the logos of the five
  suggested coins and shows a quiet placeholder meanwhile – no jump from initials to logos.
* Buttons that only fold something open («Why?», «Show details», «Show indicators»,
  «Show list») have a down/up chevron like «Context»/«Data» instead of «→».
* Links look the same everywhere: links to websites are accent-coloured with ↗
  (settings rows, «Request exchange» on Add pair), links inside running text are
  accent-coloured and underlined on both platforms. Settings rows have no
  leading icons.
* Sort the watchlist like an exchange: «Name ⇅ · Price ⇅ · 24h ⇅» above the
  pairs – first tap A–Z / highest first, second tap reverse, third tap back to
  your own order (which stays saved). Favorites stay on top, sorted among
  themselves; prices are compared in CHF in the background (also without
  «≈ conversion»). The choice is remembered.
* All coin lists look the same (Watchlist, starter selection, Market › Unusual
  today, Portfolio): logo 36, same text sizes, each row its own card with only a
  thin gap – strongly rounded at the top and bottom of the list, slightly rounded
  in between, so the list reads as one unit.
* Market: the «Now» title is gone; instead a status pill like on the watchlist
  («All current · 2 min ago», «Updating…» while loading).
* Portfolio numbers made consistent: one stablecoin rule everywhere (USDT is
  always 1; USDC, DAI & co. use their market price, 1 only when none is known) in
  header, value history, cut-off CSV, widget and portfolio alarms.
* Value history in CHF/EUR/… converts every day with that day's ECB rate, so
  franc moves show up; if the rates can't be loaded, «Converted at today's rate»
  appears under the chart.
* Implausible historic prices (more than 25 % away from the current price) are no
  longer used in the value history and the cut-off export.
* When a pair's % change comes from another exchange's candles (e.g. Kraken →
  Binance), the action sheet and «Why?» say so («Change from Binance candles»).
* Android: the first-alarm message says honestly when prices are checked with the
  app closed (background interval, at most every 15 minutes) and points to
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
  text/icon (★ and ⚡ white until selected). The ⚡ in rows is white.
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

**Alarms**
* Alarm as a sentence: «When BTC goes above …» with a price suggestion,
  «Once / Every time», other conditions under «Advanced».
* One-tap templates: ±1 %, ±5 %, new 30-day high/low, volume ×3 (with Undo).
* Repeating price alarms fire only when the level is crossed and re-arm after a
  0.2 % move back.
* New conditions: move x % within y hours, volume spike, near the high/low of
  30 days / 90 days / 1 year (optionally new highs/lows only), gas price.
* Funding rate above/below x % and open interest change in 1/4/24 h for
  perpetuals on Binance, Bybit and OKX.
* Alarms in your currency (e.g. 60,000 EUR on a USDT pair).
* Portfolio alarms: total value above/below an amount, today's change up/down x %.
* Quiet hours, «Test alarm», choice of alarm sound; speech output queues
  several alarms and respects silent mode.

**«Why?» & market**
* «Why is this moving?» as a factor list (volume, market, volatility, open
  interest, distance to the 30-day high, funding, sentiment) with a short
  summary and how closely the coin follows Bitcoin.
* Unusual activity detection with adjustable sensitivity, optional notifications.
* Market tab in three sections: Crypto Pulse «What stands out», «Unusual
  today», Fear & Greed; market phase, dominance, altcoin season, halving; total
  market cap and volume, network fees (Ethereum, Base, Arbitrum, Polygon, BNB
  Chain, Bitcoin), cycle comparison, economic-calendar hint.
* «What stands out» now also shows market breadth («Top 30 ▲ 22 · ▼ 8»: how many
  of the 30 largest coins, without stablecoins, are up over 24 h) and the total
  crypto market cap with its 24 h change; a sentence only in clear cases (almost
  all rising/falling, or Bitcoin moving while most large coins don't follow). The
  widget shows the breadth next to Fear & Greed when there is room.
* Opens instantly from stored data; every row shows source and age, outdated
  values in a warning colour; «Context» and «Data» start collapsed (also on the first visit).
* Short explanations for funding rate, open interest, RSI and Pi cycle.

**Portfolio & privacy**
* Optional portfolio tab: buys and sells, average price, profit/loss, value
  history (7 d / 30 d / 1 y / since first buy), cut-off date export as CSV.
* Number on the app icon (Settings › Alarms, on by default): on iOS every new
  alarm or market alert counts +1 and opening the app resets it to 0, like
  messengers do; on Android each alarm counts 1 and the home screen decides
  between number and dot (e.g. Samsung «Show with number»).
* Section headings look the same everywhere (settings, portfolio, market,
  refresh report, licences): small, bold, in the theme colour.
* Same ⋯ menu start in Watchlist, Market and Portfolio: app logo and name
  (About), then Refresh, then the tab's own items; the Market tab gets a ⋯ menu.
* Settings › Data › «Reset app»: deletes everything, like a fresh install
  (with confirmation).
* «Why?» opens as a page with a back arrow, like «Alarms». Back from either returns
  to the pair's action sheet; back again shows the watchlist.
* Watchlist with names: pair/price, name/% pill and exchange/≈ value line up
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
* Settings › Updates: each mode says when it works – «While the watchlist is open» (live
  prices) and «Even when the app is closed» (background, update frequently).
* Android «Update frequently»: with the screen off at most every 5 minutes instead of every
  minute (the regular background update is unchanged); iOS saves the watchlist off the main
  thread.
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
  exchanges.
* LUNA2 (Terra 2.0 futures) shows the LUNA logo and name.
* Stock perpetuals without a tokenised bStock (e.g. CAT, BYD) get their logo
  from Binance's stock images; loaded for all stock tickers of the stored pair
  lists, never just the watched ones.
* Quieter first day: on a new install only the market phase change notifies;
  Fear & Greed, unusual activity and the economic-calendar morning notice are
  off until switched on (⚡ in the watchlist and the activity card stay).
* Stock names with «Show names»: CAT shows «Caterpillar, Inc.», BYD «Boyd
  Gaming Corporation» (official Nasdaq symbol list, loaded at most weekly).
* Portfolio works like the watchlist: swipe a coin left to delete all its
  transactions (with Undo), swipe a transaction left to delete it (with Undo,
  no long press); ⋯ menu with «Refresh» on top and «Clear portfolio» at the
  bottom (red, with confirmation); pull down to refresh, no refresh button in
  the top bar. The «Biggest movers» card is gone: each coin row shows its
  24 h change instead, and the number of transactions next to the amount.
* Portfolio lock (biometrics or device passcode) for the portfolio only.
* «Hide amounts»: values shown as ••• in the portfolio, its widget and
  portfolio alarms; percentages stay visible.
* Portfolio widget: hourly prices for coins that are only in the portfolio
  (not on the watchlist) are loaded once an hour, so the value history no
  longer stays at «History coming soon»; with «today since 00:00» the chart
  starts at midnight and fills up over the day.
* Backups can be protected with a password (AES-256-GCM, PBKDF2) and are
  interchangeable between Android and iOS. Android system backup now includes
  watchlist, alarms and settings.

**Widgets**
* New: portfolio widget (adapts to its size) and «What stands out» (Crypto
  Pulse); single-coin widget with candles or line and a 24 h / 7 d / 30 d range.
* Rounded corners, previews in the widget picker, compact rows with ▲/▼, a
  group per widget.
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
* Three switches under Settings › Appearance › Coin logos: «In the app», «In the
  portfolio» and «In widgets». A new install has «In the app» and «In the portfolio»
  on (the watchlist shows logos right away), «In widgets» off; all off means nothing is
  loaded. Included in backups (Android ↔ iOS).

**Settings & navigation**
* Four tabs: Watchlist · Market · Portfolio (optional) · Settings. «Add pair»
  opens from «+» in the watchlist; no separate search tab.
* Tab bar: the chosen tab shows icon and name in the theme colour (no coloured pill behind
  it). New line icons in the style of «Market»: list with a small price line for Watchlist,
  coin stack for Portfolio – also everywhere else the portfolio is meant («Add to portfolio»,
  empty portfolio, portfolio alarms, widget help); Market and Settings unchanged.
* No welcome dialog: a new install goes straight to the starter selection;
  «What the app can do» under Settings › About.
* Settings in groups, with search (ignores accents and case, jumps to the item
  and highlights it); «Market notifications» and «Speech output» as sub-pages.
* The period next to the % change («24h», «today», «last update») is hidden by default;
  Settings › % change › «Show period next to the change» brings it back (watchlist pill,
  pulse line, single-coin widget, iOS Live Activity). Screen readers always say it.
* Settings › «Widgets» and «Licences» open as normal pages like every other row (no sheet
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
  then quarterly contracts (nearest first).
* Futures on stocks, commodities, FX and pre-IPO companies (TradFi perpetuals) at
  Binance, Bybit, OKX, MEXC and Bitget, behind a switch under Settings › Watchlist
  (together with dated futures). New pairs without a chosen group go to
  «TradFi» or, for dated futures, «QTLY».
* Basis of the % change like Binance: last 24 h, since the last update, since 00:00 in
  the device time zone (with summer time) or since 00:00 in a fixed zone UTC+14 … UTC-12.
  Like Binance’s «Change(%) & Chart Timezone», the chosen zone also sets the times in the
  charts. «Since last update» compares the latest price with the previous one (watchlist,
  action sheet, widgets; close to 0 % with live prices); portfolio stays on 24 h.
* Settings › Updates (Android): battery usage moved to the bottom of the page.
* Android app shortcuts: Add pair, All alarms, Market.
* Questions, wishes and exchange requests via GitHub issues (link under About).

**Design & accessibility**
* New app icon (bell with a rising line) and five themes: Orange, Red, Blue,
  Green and Marrs Green, each with a matching icon. iOS: light, dark and tinted
  icons from the asset catalog.
* Price colours green/red or blue/orange, «Swap colours» (red = rising), high
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

**Tests & CI**
* Shared test cases for Android and iOS (`android/testdata/parity`): alarms,
  funding/open-interest alarms, threshold input, % change basis (incl. daylight
  saving), «no longer traded», 24 h change, outdated, live feed.
* iOS test target `CryptoCheckerTests`; Android instrumented core-flow test
  (`CoreFlowTest`, compiled in CI).
* Workflows: Android unit tests and build, translation check, iOS build and
  tests (manual), weekly economic-calendar update.

### Repository
* Complete source code of the Android and iOS apps published under the MIT
  License (`android/`, `ios/`); exchange guide moved to `DEVELOPMENT.md`.
* GitHub Actions: Android unit tests and debug build, translation check,
  iOS simulator build (manual).
* One shared change pill, switch row, loading placeholder and colour tokens on
  Android and iOS; iOS no longer redraws every tab when live prices are saved.
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
* Now 41 markets on 32 exchanges plus DexScreener.
* Combined pair lists (LATOKEN, Independent Reserve) parsed in one request.
* Swift versions of all adapters for the iOS app.

### Privacy policy
* Lists all exchanges and data providers, exchange-rate sources (Frankfurter,
  ExchangeRate-API), network-fee providers, portfolio lock and portfolio widget.
* Also covers notes, portfolio transactions and cached market data stored on
  the device, and questions via GitHub issues.
* The apps contain no payment or donation feature.
* Coin logos: all logos of the largest coins downloaded at once from CoinGecko’s
  image servers, gaps (gold, silver, stocks) from Binance’s public symbol list
  (never one by one), shown from the device; one switch each for
  app, portfolio and widgets (all off = no requests).
