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
* Mini chart (24 h) in every row; the % pill uses the same candles. Pulse line
  at the top («▲ 7 rising · ▼ 3 falling»).
* Tap opens the action sheet at once (no double tap), with a chart: 24 h / 7 d /
  30 d, candles or line, touch to read price and time.
* Swipe left to delete (with Undo), right for favourite; star only on
  favourites; jump button in long lists.
* Groups, a note per coin, second line «≈ value» in one of 31 currencies.
* «Outdated» / «exchange not reachable» per row and in all widgets (after
  2 minutes with «Update frequently»).
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
* Opens instantly from stored data; every row shows source and age, outdated
  values in a warning colour; «Context» and «Data» start collapsed (also on the first visit).
* Short explanations for funding rate, open interest, RSI and Pi cycle.

**Portfolio & privacy**
* Optional portfolio tab: buys and sells, average price, profit/loss, value
  history (7 d / 30 d / 1 y / since first buy), cut-off date export as CSV.
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

**Settings & navigation**
* Four tabs: Watchlist · Market · Portfolio (optional) · Settings. «Add pair»
  opens from «+» in the watchlist; no separate search tab.
* No welcome dialog: a new install goes straight to the starter selection;
  «What the app can do» under Settings › About.
* Settings in groups, with search (ignores accents and case, jumps to the item
  and highlights it); «Market notifications» and «Speech output» as sub-pages.
* Futures on stocks, commodities, FX and pre-IPO companies (TradFi perpetuals) at
  Binance, Bybit, OKX, MEXC and Bitget, behind a switch under Settings › Watchlist
  (together with dated futures). New pairs without a chosen group go to
  «TradFi» or, for dated futures, «QTLY».
* Basis of the % change like Binance: last 24 h, since 00:00 in the device time
  zone (with summer time) or since 00:00 in a fixed zone UTC+14 … UTC-12.
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
  width on tablets and iPad.
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
