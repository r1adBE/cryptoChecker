# Development guide

Sections 1–11 explain the exchange adapters (adding an exchange); the later sections cover tests, CI, the app's
UI, portfolio and storage rules. The build history is in [`CHANGELOG.md`](CHANGELOG.md).

> **Deutsch:** Entwicklerhandbuch. Abschnitte 1–11: neue Börsen-Adapter in der Börsen-Bibliothek
> (Android/Kotlin und iOS/Swift) – Aufbau, Dateien, Paarliste, Einzel- und Massenabfrage, Tests; danach Tests,
> CI und Regeln der App; die Build-Geschichte steht in `CHANGELOG.md`. Fragen und Pull Requests gern auf Deutsch oder Englisch.

<!-- DEVELOPMENT.md at the repository root: paths and links are relative to the root. -->

This guide explains how an exchange adapter of the market-data library works
and how to add a new one. The example throughout is **Bitstamp**, one of the
exchanges the app uses today:

* Kotlin: [`android/marketdata/…/model/exchange/Bitstamp.kt`](android/marketdata/src/main/java/com/cryptochecker/marketdata/model/exchange/Bitstamp.kt)
* Swift: [`ios/Shared/Exchanges/Bitstamp.swift`](ios/Shared/Exchanges/Bitstamp.swift)

Only **public** endpoints can be used – no API keys, no accounts, no login.

## Contents

1. [Structure](#1-structure)
2. [Files to touch](#2-files-to-touch)
3. [The adapter](#3-the-adapter)
4. [Pair list](#4-pair-list)
5. [Single ticker](#5-single-ticker)
6. [Bulk ticker](#6-bulk-ticker)
7. [Errors](#7-errors)
8. [Registering the exchange](#8-registering-the-exchange)
9. [Tests and checks](#9-tests-and-checks)
10. [Advanced cases](#10-advanced-cases)
11. [24 h change](#11-24-h-change)
12. [Running the tests](#12-running-the-tests)
13. [Market tab caching](#13-market-tab-caching)
14. [Live prices (WebSocket)](#14-live-prices-websocket)
15. [UI rules for numbers](#15-ui-rules-for-numbers)
16. [Portfolio and backup rules](#16-portfolio-and-backup-rules)
17. [«Why?», futures data, chart and asset kinds](#17-why-futures-data-chart-and-asset-kinds)
18. [Screen layout rules](#18-screen-layout-rules)
19. [Storage encryption](#19-storage-encryption)
20. [Modules, layers and cross-cutting rules](#20-modules-layers-and-cross-cutting-rules)

Then: [Build notes](#build-notes) · [Known warnings](#known-warnings)

## 1. Structure

| Path | Content |
|---|---|
| `android/marketdata/src/main/java/com/cryptochecker/marketdata/model/Exchange.kt` | Base class: request URLs and parsing hooks |
| `android/marketdata/…/model/exchange/generic/SimpleExchange.kt` | Base class for the usual case «pair list URL + ticker URL» |
| `android/marketdata/…/model/exchange/*.kt` | One file per exchange (or per market, e.g. spot and futures) |
| `android/marketdata/…/model/Ticker.kt`, `SimpleTicker.kt` | Price data of one pair |
| `android/marketdata/…/model/ExchangePair.kt` | One tradable pair: base, quote, exchange-specific pair id |
| `android/marketdata/…/config/ExchangesConfig.kt` | List of registered exchanges |
| `android/marketdata/…/util/JsonExtensions.kt` | JSON helpers (`forEachJSONObject`, `optDoubleOrMissing`, …) |
| `ios/Shared/Exchanges/Exchange.swift`, `ios/Shared/Exchanges/ExchangesConfig.swift`, `ios/Shared/Exchanges/JsonUtils.swift` | The same on iOS |

The app asks an adapter for two things:

* the **pair list** – which pairs exist and under which id the exchange knows them;
* **prices** – either one pair per request (single ticker) or all pairs in one
  request (bulk ticker). If an adapter offers a bulk ticker, the app uses it to
  refresh the watched pairs of that exchange at once and falls back to single
  requests for pairs missing in the bulk response.

## 2. Files to touch

For a new exchange «Example»:

| Platform | File | What |
|---|---|---|
| Android | `android/marketdata/…/model/exchange/Example.kt` | new adapter |
| Android | `android/marketdata/…/config/ExchangesConfig.kt` | `Example()` added |
| iOS | `ios/Shared/Exchanges/Example.swift` | the same adapter in Swift |
| iOS | `ios/Shared/Exchanges/ExchangesConfig.swift` | `Example()` added |
| Android | `android/data/…/data/remote/candles/ExchangeCandleSources*.kt` | optional: own candles (`ExchangeCandleSource` + `ExchangeCandleRegistry`) |
| iOS | `ios/Shared/Services/Candles/ExchangeCandleSources*.swift` | the same candle source in Swift, case in `testdata/parity/own_candles.json` |
| Both | ticker parsing | set `exchangeTime` when the API sends the time of the price (see [section 15](#15-ui-rules-for-numbers), «Exchange time vs fetch time») |
| Both | the adapter | `category` when it is not Spot (FUTURES, DEX, FOREX: section of the exchange picker) |
| Both | `ExchangeOrder` (Android `domain/…/domain/market`, iOS `Shared/Core`) | CoinGecko id in `SPOT_IDS`/`spotIds` or `DERIVATIVES_IDS`/`derivativesIds`, so the volume order of the install date includes it |

After adding a Swift file, regenerate the Xcode project with
`python3 -I ios/tools/gen_xcodeproj.py` (it picks up new files by itself).

Both platforms must behave the same: same pair ids, same fields, same
fallbacks. Backups can be moved between Android and iOS, so the **key** of the
exchange must be identical on both sides (see [section 8](#8-registering-the-exchange)).

## 3. The adapter

Every adapter extends `Exchange` and answers three questions: *which address*
(`tickerAddress`, `pairListAddress`, bulk URLs), *how to read the answer*
(`fillTicker…`, `fillPairList…`, `parseBulkTickers`) and *how to read an error*
(`errorMessage…`). Networking, retries, size limits and storage live outside the
adapter (`ExchangeRemoteDataSource` on Android, `ExchangeService` on iOS).

Most exchanges fit `SimpleExchange`: exactly one fixed pair-list address and one
ticker address with the pair id as `%1$s`. Pass its arguments by name.

```kotlin
private const val BITSTAMP_API = "https://www.bitstamp.net/api/v2"

/** Bitstamp (EU regulated, also EUR/GBP pairs), API v2. Pair id = `url_symbol` («btcusd»). */
class Bitstamp : SimpleExchange(
    name = "Bitstamp",                                   // name shown in the app
    pairListUrl = "$BITSTAMP_API/trading-pairs-info/",   // pair list
    tickerUrl = "$BITSTAMP_API/ticker/%1\$s/",           // ticker, %1$s = pair id
    errorField = "reason",                               // error text in error responses
)
```

```swift
private let bitstampAPI = "https://www.bitstamp.net/api/v2"

struct Bitstamp: SimpleExchange {
    let key: String = "Bitstamp"
    let name: String = "Bitstamp"
    let pairListURL: String = bitstampAPI + "/trading-pairs-info/"
    let tickerURL: String = bitstampAPI + "/ticker/%1$s/"
    let errorField: String? = "reason"
}
```

On iOS `Exchange` and `SimpleExchange` are protocols (checked `Sendable`): an adapter is a `struct` with constants,
or a `final class` whose changing state sits in an `OSAllocatedUnfairLock` (`Gemini`). Give every constant its type
(`let errorField: String? = …`, `let currencyPairs: CurrencyPairsMap? = …`): without it the constant does not match the
requirement and the default (nil) would apply silently. Hooks are implemented without `override`; a hook that needs
the shared behaviour calls the named helper (`simpleTickerAddress`, `simpleErrorMessage`, `phemexFillTicker` …)
instead of `super`.

`SimpleExchange` also takes an optional `ttsName` – the name used for spoken
announcements when the written name reads badly (e.g. «Gate I O» for «Gate.io») –
and `currencyPairs`, a built-in pair table for exchanges without a pair endpoint.
Override `pairId(info)` (Swift: `pairId(_:)`) if the id in the URL differs from the
stored one.

If the exchange needs something `SimpleExchange` does not cover (POST requests,
several requests per price, a ticker address built from base and quote, several
pair-list addresses), extend `Exchange` directly, override `tickerAddress(call, info)`
(Swift: implement `tickerAddress(call:info:)`) and say why in the class comment; see
[section 10](#10-advanced-cases). Exchanges with several products share a base
named `<Exchange>Base` (e.g. `BybitBase` for spot and futures; Swift: a protocol with
an extension, its parameters as requirements).

**File layout (both platforms, same order).** File-private constants
`<EXCHANGE>_<WHAT>` (Swift: `let`), the concrete classes in `ExchangesConfig` order,
then a shared base. Inside a class: hooks and state, traits, then the sections
`// ── Einzelkurs ──`, `// ── Sammelabfrage ──`, `// ── Paarliste ──`,
`// ── Namen ──`, `// ── Lesen ──` (Swift: `// MARK:` with the same titles). The
reader shared by single and bulk ticker is always called `copyRow`. A Kotlin file
and its Swift twin should read side by side.

## 4. Pair list

Override `fillPairList(call, body, out)` (whole response as text) or
`fillPairListJson(call, json, out)` (response is a JSON object) and add one
`ExchangePair(base, quote, pairId)` per tradable pair. Swift returns the list:
`fillPairList(call:body:)` / `fillPairList(call:json:)`.

Offer only pairs that can be traded normally: Binance `status == "TRADING"`, Kraken AssetPairs
`status == "online"` (`cancel_only`, `post_only`, `limit_only`, `reduce_only` are dropped, a missing
`status` in older responses counts as online). A watched pair that drops out is not removed: its row ends as «no
longer traded» through the bulk/single path below (section 6). Kraken reports such pairs with close price 0; the
single ticker treats that as «no price» (not 0), so `NotTraded.confirmedBySingle` marks the pair instead of the
old price staying silently (`KrakenTradableTest` / `KrakenTradableTests` on `recorded/recorded_kraken.json`).

```kotlin
override fun fillPairList(call: Int, body: String, out: MutableList<ExchangePair>) {
    JSONArray(body).forEachJSONObject { entry ->
        if (entry.optString("trading") != "Enabled") return@forEachJSONObject   // skip disabled pairs
        val label = entry.getString("name")                                      // e.g. BTC/USD
        val slash = label.indexOf('/')
        if (slash < 0) return@forEachJSONObject
        out += ExchangePair(label.substring(0, slash), label.substring(slash + 1), entry.getString("url_symbol"))
    }
}
```

Rules:

* **Base and quote** in upper case, with the common ticker symbol (`BTC`, not an
  exchange-internal alias). If the exchange uses its own codes, normalise them
  with an explicit table and unit tests – never with a blanket rule such as
  «strip the first letter», which breaks other symbols.
* **Pair id** exactly as the exchange expects it in the ticker URL and as it
  appears in the bulk response (here `btcusd`). The app stores it with every
  watched pair, so it must stay stable.
* Skip pairs that are disabled, delisted or in maintenance if the API says so
  (e.g. OKX `state` other than `live`: `preopen` / `suspend` have no price).
* Take the pair list from the endpoint that describes the instruments, not from a
  ticker list, when only the former tells crypto from TradFi. OKX Futures reads
  `/api/v5/public/instruments?instType=SWAP`: `instCategory` 3 (stocks) and 4
  (commodities) make NVDA, AAPL or XAU TradFi instead of a token with the same
  ticker; bulk prices still come from `market/tickers`.
* If the pair list is paginated, follow the cursor with `pairListNextPage` and the
  hard limit `MAX_PAIR_LIST_PAGES` (Bybit `instruments-info`: `nextPageCursor`) –
  never stop silently after the first page.
* A «1000» prefix (`1000SATS`, `1000CAT`) means the price is for 1000 units of the
  coin (1000SATS = 1000 × SATS, 1000CAT = 1000 × Simon's Cat). Keep the pair
  symbol and price as the exchange lists them – never rescale; only logo and name
  lookup strip the prefix. This is intentional and pinned down by the parity cases
  in `testdata/parity/coin_logos.json`.
* Pairs are loaded when the user syncs the list of an exchange; a built-in table
  (`currencyPairs`) is only a fallback for exchanges without a pair endpoint
  (Coinbase).

## 5. Single ticker

Override `fillTickerJson(call, json, ticker, info)` (Swift:
`fillTicker(call:json:ticker:info:)`) and fill the `Ticker` in `copyRow`:

```kotlin
override fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) {
    copyRow(json, ticker)
}

private fun copyRow(row: JSONObject, ticker: Ticker) {
    val last = row.getDouble("last")            // required
    ticker.last = last
    ticker.bid = row.optDoubleOrMissing("bid")     // optional fields: MISSING if absent
    ticker.ask = row.optDoubleOrMissing("ask")
    ticker.high = row.optDoubleOrMissing("high")
    ticker.low = row.optDoubleOrMissing("low")
    ticker.vol = row.optDoubleOrMissing("volume")  // 24 h volume in the base currency
    ticker.change24hPercent = Change24h.fromOpen(last, row.optDouble("open_24"))  // see section 11
}
```

* `last` is required; a response without it must throw so the app shows an error.
* All other fields are optional – use `optDoubleOrMissing` (Swift: `optDoubleOrMissing`)
  so a missing value is shown as «no data» and not as 0.
* `vol` is the base-currency volume; set `volQuote` if the exchange reports the
  quote volume as well.
* `timestamp` may be seconds, milliseconds, microseconds or nanoseconds –
  `readTicker` converts it. Leave it 0 if the exchange sends none; the time of the
  request is used. So for exchanges without a trade or ticker time the time shown
  in the row is the **fetch time**, not the time of the last trade. «Outdated»
  always counts from the last *successful* fetch, never from the exchange's
  timestamp. Do not invent a timestamp from other fields.
* Set `change24hPercent` only if the response carries a **rolling** 24 h change
  or the price 24 hours ago – see [section 11](#11-24-h-change).
* Keep the parsing in `copyRow` so single and bulk ticker share it.

If the response is not a JSON object (e.g. a JSON array), override
`fillTicker(call, body, ticker, info)` (Swift: `fillTicker(call:body:ticker:info:)`)
instead.

## 6. Bulk ticker

If the exchange returns all prices in one request, implement the bulk ticker.
It saves many requests when the watchlist contains several pairs of the exchange.

```kotlin
override val bulkTickersNumOfRequests: Int
    get() = 1

override fun getBulkTickersUrl(call: Int): String = "$BITSTAMP_API/ticker/"

override fun parseBulkTickers(call: Int, body: String, tickers: MutableMap<String, Ticker>, failed: MutableSet<String>) {
    JSONArray(body).forEachJSONObject { row ->
        val pairId = row.optString("pair").replace("/", "").lowercase()   // "BTC/USD" → "btcusd"
        bulkRowIfActive(pairId, row, "last", tickers, failed) { copyRow(row, it) }
    }
}
```

* The **map key must be the same pair id** as in the pair list – otherwise the
  price cannot be matched to the watched pair.
* `bulkRowIfActive` is the one shared rule for rows: no id or a blank/empty price
  → skipped (inactive market); a row that cannot be read → listed in `failed`, the
  others still count. Use `bulkRow` when there is no single price field to check.
* `bulkTickersComplete = true` only if the bulk response always contains every
  tradable pair. A pair missing from such a list is **not** marked «no longer
  traded» right away: the app asks it **once** with a single ticker request to the
  same exchange (`NotTraded.askSingleWhenMissing`). Only if the exchange answers
  without a price – its error text, a response without data, or HTTP 4xx except
  401/403/407/408/418/429/451 – is the pair marked (`NotTraded.confirmedBySingle`).
  Network errors, timeouts, rate limits, 5xx and refused access stay ordinary
  errors and the pair keeps its last price. A pair already marked is not asked
  again on every run; it recovers as soon as the bulk list contains it again.
  Outside the bulk path (fewer than 3 pairs of the exchange, or the bulk query not usable) a single answer
  confirms only with a second statement (`NotTraded.singleConfirms`): the single request reads the full list
  itself (Lighter, Hyperliquid), or the dated contract has expired — the date in the pair id
  (`BTCUSDT_250926`, `FuturesContractType.deliveryCodeOf`) is past its settlement at 08:00 UTC
  (`NotTraded.contractExpired`). An expired contract already marked is not asked again. The short form shown
  for a dated contract («1Q», «2Q», «1M» …) comes from the date in its id (`FuturesContractType.shownContract`,
  iOS `shown(stored:pairId:now:)`), not from the type stored when it was added: after the quarter roll a «2Q»
  contract shows «1Q». Cases live in `testdata/parity/not_traded.json`. A single-ticker parser must
  therefore throw (or return the exchange's error) for an unknown pair, never
  return a price of 0. The own reader text `ExchangeParseException.NO_DATA` / `JSONError.noData` («No data») and
  Hyperliquid «Unknown coin: …» count as «unknown pair» (`NotTraded.isMissingEntry`, failure `UnknownInstrument`);
  `errorMessage` must call `noErrorText()` for a valid answer so the reader error is kept.
* If the exchange can filter the bulk request by pair ids, override
  `getBulkTickersUrl(call, pairIds)` to download only what is needed.
  For a comma list in the URL (Kraken `?pair=`, Bitfinex `?symbols=`) use
  `BulkPairChunks` and override `bulkTickersRequestCount(pairIds)` as well, so
  long lists are split into several requests with URLs under 2000 characters.
  If a filtered request fails (rejected or unreadable), the app loads the
  unfiltered one once instead. URLs are compared without a `timestamp=<digits>` parameter (`RequestKey`, BingX
  stamps every URL), also for sharing identical concurrent requests.
* Pause after 429/418 (`ExchangeBackoff`) is per market key; spot and futures keys on the same host (BingX, Bybit,
  OKX, Phemex, Bitget, WOO: `HOST_GROUPS` / `hostGroups`) share it (`withHostGroupPauses`).

## 7. Errors

Many exchanges answer with an error object (HTTP 200 or 4xx). Bitstamp sends
`{"reason": "…"}` – `errorField = "reason"` is enough for `SimpleExchange`.
For other formats override `errorMessageJson(call, json, info)` or
`errorMessage(call, body, info)` (Swift: `errorMessage(call:json:info:)` /
`errorMessage(call:body:info:)`) and return the exchange's message; call
`noErrorText()` when the response carries none. The app shows it to the user.

Numbers from a server are never trusted to fit: seconds → ms only through `EpochTime.secondsToMillis` (1 … < 10¹³ s,
else no time / the candle is dropped), `EpochTime.parseTimeToMillis` returns 0 instead of overflowing, block heights
from 10 million on are implausible (`HalvingMath.estimate`). Swift traps on `*` overflow – use these helpers,
`multipliedReportingOverflow` or bounds first. Cases: `testdata/parity/server_values.json`.

## 8. Registering the exchange

Every Android adapter declares its storage key explicitly – `override val key = "Example"` (the class name; it is
the database key of every saved pair, so it must never change). `ExchangeKeyTest` lists all keys and fails for a
missing or changed one. Then add the adapter to both lists:

```kotlin
// android/marketdata/…/config/ExchangesConfig.kt
val EXCHANGES: Map<String, Market> = listOf(
    …
    Bitstamp(),
    …
).associateBy(Market::key)
```

```swift
// ios/Shared/Exchanges/ExchangesConfig.swift
static let all: [Exchange] = [
    …
    Bitstamp(),
    …
]
```

The **key** identifies the exchange in saved watchlists, alarms and backups:

* Android declares `key` explicitly (equal to the former class name, `Bitstamp`), iOS passes the `key:`
  parameter – they must be equal.
* Never rename an adapter class or key once it is released; saved pairs would
  lose their exchange.

## 9. Tests and checks

* Put pure logic (symbol normalisation, pair-id building, number parsing) in a
  small function or object without network or Android dependencies and cover it
  with JUnit tests, including edge cases (unusual symbols, empty fields, numbers
  as strings).
* Mirror the same logic in Swift with the same cases.
* Check against the live API before opening a pull request:
  * the pair list contains the expected pairs with correct base/quote;
  * single ticker and bulk ticker return the same price for a few pairs;
  * a pair that does not exist produces a readable error, not a crash.
* No API keys, tokens or personal data in code, tests or sample responses.
* Run the unit tests on both platforms – see [Running the tests](#12-running-the-tests).

## 10. Advanced cases

**Several requests per price.** If one request does not deliver everything,
override `tickerCallCount(info)` (Swift: `tickerCallCount(_:)`) and use `call`
(`0 … n-1`) in `tickerAddress` and the fill methods to tell the requests apart.

**Several requests for the pair list.** Override `pairListCallCount` and branch
on `call` in `pairListAddress` / `fillPairList`. If the pair list can only be
built from all responses together (e.g. instruments plus symbol details), set
`pairListCombined = true` and implement `fillPairListCombined(bodies, out)`
(Swift: `fillPairListCombined(bodies:)`).

**POST requests.** Return a `PostRequest` from `tickerPostBody`,
`pairListPostBody` or `getBulkTickersPostRequest`.

**Futures.** Use a separate adapter (e.g. `BinanceFutures` next to `Binance`)
and set the `contractType` of `ExchangePair`.

## 11. 24 h change

`Ticker.change24hPercent` (Swift: `change24hPercent`) is the **rolling** 24 h
change in **percent** (`1.5` = +1.5 %) as the exchange reports it. The watchlist
% change, the pulse line and the widgets use it first. Only if it is `null`/`nil`
does the app load hourly candles for the pair and compute the change itself –
one extra request per coin, which gets slow with hundreds of pairs.

Rules:

* Use only what the ticker requests the adapter already makes (single **and**
  bulk ticker). Never add a request just for the 24 h value.
* Use only **rolling** 24 h values. A change since midnight (UTC, KST, Singapore
  time …) is a different number and must stay `null` – the candle fallback is
  better than a wrong value.
* Convert with the helpers in `android/marketdata/…/util/Change24h.kt`
  (Swift: `enum Change24h` in `ios/Shared/Exchanges/ExchangeModels.swift`); they
  return `null`/`nil` for missing, non-finite or non-positive input:
  * `Change24h.percent(x)` – value already in percent;
  * `Change24h.fraction(x)` – value is a fraction (`0.015` = +1.5 %), multiplied by 100;
  * `Change24h.fromOpen(last, open)` – from the price 24 hours ago: (last − open) / open × 100;
  * `Change24h.fromAbsolute(last, change)` – from the absolute 24 h change (open = last − change).
* Pass the raw field with `optDouble(name)` – a missing field gives NaN and thus `null`.
* If the response has both a price 24 hours ago and a percentage, prefer the
  price (more digits); use the percentage only as a fallback.
* Keep Android and iOS identical and update the table below.
* «Fallback candles» means the pair's **own exchange** candles first (`ExchangeCandleRegistry`,
  see «Candles come from the pair's own exchange first» in section 17); only an exchange without own
  candles falls back to the labelled, same-token-checked Binance chain.

| Exchange / market | Field used | Unit / note |
|---|---|---|
| Aster | `priceChangePercent` (`/fapi/v1/ticker/24hr`) | percent (like Binance) |
| Binance, Binance.US | `priceChangePercent` (`/ticker/24hr`) | percent |
| Binance Futures (USD-M, COIN-M) | `priceChangePercent` (`/ticker/24hr`) | percent |
| Binance TH | `priceChangePercent` (`/api/v1/ticker/24hr`) | percent (like Binance) |
| Binance TR | own markets: – fallback candles (last trade only); type 1: `priceChangePercent` | percent (like Binance) |
| BingX, BingX Futures | `openPrice` | price 24 h ago; the spot `priceChangePercent` is text with «%» and not used |
| Bit2Me | `open` (`/v2/trading/tickers`) | price 24 h ago (rolling: it moves within the hour; `percentage` matches it) |
| bitbank | `open` | price 24 h ago |
| bitFlyer | – fallback candles | ticker has no 24 h change |
| Bitfinex | `DAILY_CHANGE_RELATIVE` (index 5, bulk 6) | fraction × 100 |
| Bitget, Bitget Futures | `change24h` | fraction × 100; `changeUtc24h` (since 00:00 UTC) not used |
| Bitkub | – fallback candles | `percent_change` is percent, but its window is not documented |
| BitOasis | `open_price` | price 24 h ago (rolling, moves hourly; checked against Binance BTC/USDT) |
| BitoPro | `priceChange24hr` (`/v3/tickers`) | percent (checked against MAX BTC/TWD at the same time, 3 Nov 2026) |
| Bitpanda | – fallback candles | price snapshot only: no 24 h change, volume, bid/ask or exchange time in `/v1/ticker` |
| Bitso | `change_24` | absolute change → open = last − change |
| Bitstamp | `open_24`, else `percent_change_24` | `open` is the start of the day and not used |
| Bitvavo | `open` (`/ticker/24h`) | price 24 h ago |
| BtcTurk | `open`, else `dailyPercent` | price 24 h ago / percent |
| BTC Markets | `price24h`, else `pricePct24h` | absolute change → open = last − change / percent |
| Buda | `price_variation_24h` | fraction × 100 (three decimals: 0.1 % steps) |
| Bybit, Bybit Futures | `price24hPcnt` | fraction × 100 |
| Coinbase | `open` from `/stats` (the only request), against `last` of the same response | price 24 h ago |
| CoinDCX | `change_24_hour` | percent |
| Coincheck | – fallback candles | ticker has no 24 h change |
| CoinJar | `change_24h` | fraction × 100 (rolling: it moves within the hour); `prev_close` is a session close and not used |
| Coinone | `first` (`/public/v2/ticker_new`) | price 24 h ago (documented as the open of the 24 h window) |
| Coins.ph | `openPrice` (`/quote/v1/ticker/24hr`) | price 24 h ago; `priceChangePercent` is a fraction here (not percent as at Binance) and not used |
| CoinSpot | – fallback candles | `/pubapi/v2/latest` has bid/ask/last only (no 24 h change, no volume) |
| Crypto.com | `c` | fraction × 100; `null` without trades |
| Deribit | `stats.price_change` (single), `price_change` (bulk) | percent |
| DexScreener | `priceChange.h24` | percent |
| Foxbit | `rolling_24h.open` | price 24 h ago |
| Gate.io | `change_percentage` | percent; `change_utc0`/`change_utc8` not used |
| Gemini | – fallback candles | `/pricefeed` `percentChange24h`: unit unclear (docs show percent «5.23», real responses look like fractions «0.0146»); `pubticker` has no 24 h change |
| GMO Coin | – fallback candles | ticker has no 24 h change |
| HashKey | `o` (`/quote/v1/ticker/24hr`) | price 24 h ago |
| HTX, HTX Futures | – fallback candles | `open` of the bulk ticker is documented as the open of the calendar day (Singapore time) |
| Hyperliquid | `prevDayPx` against the shown price (`midPx`, else `markPx`) | mark price 24 h ago |
| Icrypex | – fallback candles | `change` is percent (`qty` = absolute change), but its window is not documented; rows without an open report `change` 0 with `qty` = `last` |
| Independent Reserve | – fallback candles | no change/open in `GetMarketSummary` |
| Indodax | – fallback candles | no change/open in `ticker` / `ticker_all` |
| Kraken | – fallback candles | `o` is today's open (UTC day), not rolling |
| KuCoin | `changeRate` | fraction × 100 |
| LATOKEN | `change24h` | percent |
| Lighter | `daily_price_change` (`/exchangeStats`) | percent (rolling 24 h; checked against BTC high/low/last) |
| Luno | – fallback candles | ticker has no 24 h change |
| MAX (MaiCoin) | `open` (`/api/v3/ticker`) | price 24 h ago |
| Mercado Bitcoin | `open` | price 24 h ago |
| MEXC | `openPrice`, else `priceChangePercent` | price 24 h ago / **fraction** × 100 |
| MEXC Futures | – fallback candles | `riseFallRate` is the change since 00:00 UTC+8, not rolling; the day values in `riseFallRates` are not used either |
| NDAX | – fallback candles | `percentChange` without documented window (like Paribu) |
| NonKYC | – fallback candles | no documented rolling 24 h field |
| OKX, OKX Futures | `open24h` | price 24 h ago; `sodUtc0`/`sodUtc8` not used |
| One Trading | `price_change_percentage` | percent |
| Paribu | – fallback candles | `percentChange` is percent, but its window is not documented |
| Phemex | `openEp` (scaled 10^8) | price 24 h ago |
| Phemex Futures | `openRp` | price 24 h ago |
| Poloniex | `open` (`ticker24h`), else `dailyChange` | price 24 h ago / fraction × 100 |
| Quidax | `open` (`/markets/tickers`) | price 24 h ago |
| Swyftx | – fallback candles | buy/sell prices only (price = mid); `volume24H` is the global volume, not used |
| Tokocrypto | own markets: – fallback candles (last trade only); type 1: `priceChangePercent` | percent (like Binance) |
| Upbit (also TH, ID), Bithumb | – fallback candles | `signed_change_rate` is against the previous day's close (KST) |
| VALR | `previousClosePrice` (`marketsummary`) | price 24 h ago (rolling: it moves within the hour; `changeFromPrevious` matches it) |
| WazirX | `openPrice` (`/sapi/v1/ticker/24hr`) | price 24 h ago |
| WhiteBIT | `open` (v1 single), `change` (v4 bulk) | price 24 h ago / percent |
| WOO X | `open` of the oldest of the 24 hourly candles the adapter loads anyway | only with all 24 candles |
| WOO X Futures | `24hOpen` | price 24 h ago |
| ZebPay | – fallback candles | 24 h window of `priceChangePercent` not documented |

Unit-check a new field with a sample response from the documentation: a value
of `0.0123` can be 1.23 % (fraction) or 0.0123 % (percent) – compare it with
`last` and the 24 h open or high/low to be sure.

## 12. Running the tests

**Android unit tests** (JVM, no device; also run by the *Android* workflow on every push):

```sh
cd android
./gradlew testDebugUnitTest        # as in CI; ./gradlew test also runs the release variants (twice the time)
./gradlew :app:testDebugUnitTest -PperformanceTests=only   # performance tests alone (timings)
```

The performance tests (`app/src/test/.../performance`; watchlist, backup and refresh with 2000 pairs, portfolio and
tax with 10 000 transactions; most also compare against a quarter of the size to check the scaling) are excluded from
the normal run: their timings vary when lint or other tests run in parallel. The *Android* workflow runs them in their
own step («Performance tests») with nothing else running; `-PperformanceTests=true` includes them in a normal run.

Where the tests live (audit C-03): a logic test sits in the module it tests, in the package of its subject –
`android/domain/src/test` (needs only `:domain`/`:marketdata`), `android/data/src/test` (needs only `:data` and
`:domain`), `android/marketdata/src/test`, `android/wear/src/test`. `android/app/src/test` keeps the tests of the app
itself and everything that needs an `:app` test helper: source guards (`SourceGuard`, tests that read sources of
several modules), tests with the refresh fakes or string resources, the exchange contract tests (real `org.json`
over `:marketdata`) and the performance tests. The one shared helper is `ParityJson` in `android/test-shared/java`,
a test source of `:app`, `:domain` and `:data` (build 223, no new dependency), so parity tests live in their module. `:domain` and `:data` run their tests in the same environment as
`:app` (en-US/UTC, real `org.json`, `kotlinx-coroutines-test`; `:data` also MockWebServer). `LayerBoundaryTest`
checks that a test under `domain/src/test` or `data/src/test` keeps to its module (package = module and folder,
from the app only `domain` – for `:data` also `data` – imports, no Room/OkHttp import in `:domain`) and that every
module keeps at least its count of `@Test` methods (`TEST_FLOOR` = today's counts of all five modules; raise it in
the commit that adds tests). `./gradlew testDebugUnitTest` (as in the *Android* workflow) runs
`:app:testDebugUnitTest`, `:domain:testDebugUnitTest`, `:data:testDebugUnitTest`, `:marketdata:testDebugUnitTest`
and `:wear:testDebugUnitTest`; one class: `./gradlew :domain:testDebugUnitTest --tests '*.PortfolioFifoTest'` (a
`--tests` filter belongs to the task of the module that holds the class).

Reports: `android/<module>/build/reports/tests/testDebugUnitTest/index.html` (`app`, `domain`, `data`, `marketdata`,
`wear`).

Source guards (`FileLengthTest`, `HapticsUsageTest`, contrast and backup-rule checks …) fail when a file they read is
missing (`SourceGuard`). Swift sources count as optional locally: in an
Android-only checkout those tests are reported as skipped; the *Android* workflow sets `REQUIRE_IOS_SOURCES=true`, so
there a missing Swift file fails the run.

**What the tests read is a task input.** Parity tests and source guards read files outside their module's classpath
(from `user.dir` upwards): `android/testdata`, the Swift sources under `ios/`, sources and tests of the sibling
modules, `app/schemas`, the Play texts, the privacy page, `LICENSE`. `android/gradle/test-inputs.gradle.kts` (applied
to every module by the root build script) declares these as inputs of every `Test` task – as file trees with
relative path sensitivity, so a folder missing from the checkout (no `ios/`) is simply empty, and both layouts work
(`public/.github` in the private tree, `.github` next to `android/` in the published repository) – plus
`REQUIRE_IOS_SOURCES` as an input property. A change to a fixture, a Swift file or a sibling module therefore re-runs
the tests and changes the build-cache key; `--rerun-tasks` is not needed. `TestInputsGuardTest` (`:app`) reads every
string literal of the unit tests of all modules: one that names an existing place in the tree must lie under a
declared root, so a new read location fails that test until it is added to `testInputRoots`.

**Shared test cases (parity).** `android/testdata/parity/*.json` hold input → expected
result for alarms (crossing, re-arm, cooldown, volume spike), threshold parsing, the basis
of the % change (including daylight-saving days), «no longer traded» and the 24 h change
selection. Both platforms read the same files:

* Android: `ParityFixturesTest` (part of `testDebugUnitTest`; finds the folder from the
  module or the project directory).
* iOS: `ParityFixtureTests`; `ios/tools/gen_xcodeproj.py` copies the files to
  `ios/Tests/Parity/` and bundles them with the test target.

Change a rule → change the JSON once, then run both test suites. Numbers JSON cannot hold
are written as `"NaN"`, `"Infinity"`, `"-Infinity"`; `null` means «no value».

**Exchange contract tests.** `android/testdata/exchanges/exchange_*.json` hold raw response
bodies per exchange (`kind`: `single`, `bulk`, `live` message, `pairs` = pair-list response →
expected pairs, TradFi flag and contract type) with the expected parsed values;
fields that must not be used as a rolling 24 h value are expected as `null`, cases with a known
parser issue carry `knownIssue`. Android: `ExchangeContractTest` (needs the real `org.json` from
`testImplementation 'com.vaadin.external.google:android-json'`); iOS: `ExchangeContractTests`,
files copied to `ios/Tests/Exchanges/` by `gen_xcodeproj.py`. When an exchange changes its API,
update the body and expected values, then run both suites. Adding an exchange → add a fixture
file, otherwise «every registered market has a fixture» fails.

**iOS unit tests** (target `CryptoCheckerTests`, hosted by the app, `@testable import
CryptoChecker`):

```sh
cd ios
python3 -I tools/gen_xcodeproj.py  # after adding/removing Swift files or changing the JSON cases
xcodebuild test -project CryptoChecker.xcodeproj -scheme CryptoChecker \
  -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:CryptoCheckerTests
```

Without `-only-testing` the scheme runs the unit tests and the UI tests (see below).

Tests on the test app's own storage (`WatchlistLockBehaviorTests`, `BackgroundRefreshRunTests`,
`PortfolioBackgroundWriteTests`, `AddWatchesBehaviorTests`) call
`HostStorageKey.prepareAppStorage()` first: without a keychain entitlement (unsigned Simulator, CI) the process-wide
`StorageKey.shared` gets an in-memory test keychain with a fixed key (`StorageKey.replaceKeychainForTesting`, Debug
build only); with a real keychain nothing is replaced. They therefore run everywhere; a skip of these classes or of
`SharedStorageTests` fails `ios.yml` on `main` and release tags. Only «not enough time left» (`Restzeit`, extra-data
alarms of `BackgroundRefreshRunTests` on a slow runner) is allowed: reported as a notice, the run stays green. The
Simulator in the examples is the one CI picks («iPhone 17», otherwise the first available iPhone).
`ios.yml` runs three Macs in parallel – «iOS release build» (unsigned Release build for devices, release settings
audit, built-app inspection with the compiler-warning ceiling 0), «iOS unit tests» (Debug build and unit tests) and
«iOS UI tests» – and the job «iOS build» (the required check) is green only if all three are (or all three were
skipped because a pull request changes only Markdown, job «changes»); logs on failure as
artifacts `xcode-logs-release`, `-unit`, `-ui`. About 25 minutes instead of about 65 one after another.

**iOS performance tests in Release.** The iOS performance tests (`ios/Tests/*PerformanceTests.swift`, timings via
`PerformanceSupport.swift`) run in the unit-test job of `ios.yml` as a Debug build (`-Onone`) with coverage on a shared
Mac; there FIFO and tax export grow ×6–7 with 4× the data (Android ×2–3), so on iOS those tests only check fixed
limits. `.github/workflows/ios-performance.yml` (Actions › *iOS performance* › Run workflow; also weekly on Thursdays;
never a required check) measures the same classes in a Release build: `-configuration Release` (`-O`, whole module, no
`DEBUG` condition), `ENABLE_TESTABILITY=YES` for `@testable import`, no coverage, unsigned, Simulator «iPhone 17» (or
the first iPhone), no parallel test clones, `-only-testing:CryptoCheckerTests/<class>` for every `…PerformanceTests`
class found in `ios/Tests`. Every other file of `ios/Tests` is left out of that build (`EXCLUDED_SOURCE_FILE_NAMES`):
some use test seams that exist only in Debug builds (`WatchlistNotSavedGate.appDataOverride`,
`AppData.pairPickerKeyComputations`, `AlarmPairPicker.makeRowsCalls`, `StorageKey.replaceKeychainForTesting`), so the
full test target does not compile in Release; the performance files need nothing but the app. With the input
`debug_comparison` (default on, always on the weekly run) the same classes then run again on the same Mac in Debug with
coverage, as in `ios.yml`. Result: the `[Performance]` lines (quarter and full size in ms, ratio ×N; linear = ×4) as
one notice per build on the run page, a table «Release vs Debug» of the ratios in the job summary, logs as artifact
`xcode-logs-performance`. Locally the same Release run:

```sh
cd ios
xcodebuild test -project CryptoChecker.xcodeproj -scheme CryptoChecker -configuration Release \
  -destination 'platform=iOS Simulator,name=iPhone 17' -enableCodeCoverage NO -parallel-testing-enabled NO \
  -only-testing:CryptoCheckerTests/TaxPerformanceTests -only-testing:CryptoCheckerTests/PortfolioPerformanceTests \
  ENABLE_TESTABILITY=YES EXCLUDED_SOURCE_FILE_NAMES="$(find Tests -name '*.swift' ! -name '*Performance*.swift' \
  -exec basename {} \; | tr '\n' ' ')" CODE_SIGNING_ALLOWED=NO 2>&1 | grep '\[Performance\]'
```

`gen_xcodeproj.py` (also with `--check`) fails when `ios/project.yml` (the XcodeGen fallback) carries a
`MARKETING_VERSION` or `CURRENT_PROJECT_VERSION` different from the constants at the top of the script —
change the version in both places. The build number (`versionCode` in `android/app/build.gradle.kts`,
`CURRENT_PROJECT_VERSION` here) goes up by one with every delivered state, on both platforms together.

In Xcode: scheme *CryptoChecker* → Product › Test (⌘U) runs both test targets; a single one from the
Test navigator. The *iOS* workflow (automatic on changes in `ios/`, on every release tag `v*` and by hand under
Actions) builds the
app and runs the unit tests and then the UI tests in the Simulator (two steps).

**Android instrumented test** (`app/src/androidTest`, `CoreFlowTest`): starter selection →
add BTC → price alarm → new price → alarm and notification → swipe to delete → Undo →
portfolio lock shows the locked state. Hilt replaces the network client with fixed prices
(`FakeRemoteDataModule`), so no exchange is called. `DatabaseFreshInstallTest` opens the main and the portfolio
database with Room as on a fresh install (Room validates the schema against the entities); it also covers taking
over the system-backup copy when the portfolio database is created. `DatabaseFreshInstallBackupTest` runs the system
backup round trip (`PortfolioBackupMirror.writeForBackup` → `importInto`) with every `portfolio_tx` column set in at
least one row (a column the test data does not cover fails it). `RoomSchemaMigrationTest` creates each database
exactly from its exported schema with `MigrationTestHelper` and lets Room open it (identity hash against the
entities). Needs an emulator or a device.

*Golden schemas*: both Room databases are at version 1 with no migration –
`app/schemas/com.cryptochecker.app.data.local.AppDatabase/1.json` and `…PortfolioDatabase/1.json` are their golden
schemas. Room writes them while building; they are committed and never edited by hand – `room_schema_drift.py` (CI)
fails when an entity changes without a new version. The unit test `RoomSchemaRulesTest` (no device needed) requires
for the current version N of each database: `N.json` exists and names its version; if N > 1 also `N-1.json`,
N-1 → N in `ALL_MIGRATIONS` and a migration test **with data** for N-1 → N that is listed in the test
(`MAIN_DATA_TESTS` / `PORTFOLIO_DATA_TESTS`, both empty today) and declared in `RoomSchemaMigrationTest`. A future
schema change therefore needs a version bump plus a migration: bump `VERSION`, add the migration (create
`ALL_MIGRATIONS` and pass it to the builder in `LocalDataModule`), commit the exported schema, write the data test
and add it to the list.

Running the instrumented tests:

```sh
cd android
./gradlew :app:connectedDebugAndroidTest
```

The portfolio-lock test sets a temporary screen-lock PIN through `locksettings` (removed
afterwards) and is skipped where that is not possible. The *Android* workflow only compiles the
instrumented tests (`assembleDebugAndroidTest`); running them takes an emulator and is too slow
for every push, so it has its own workflow:

**Android device tests** (`.github/workflows/android-device-tests.yml`): on every push to `main` that touches
`android/` (or the workflow and its smoke script), on every release tag `v*` (GitHub ignores the
paths filter for tags) and by hand under *Actions › Android device tests › Run workflow*; a weekly schedule is
prepared but commented out. On pull requests the job `changes` decides from the changed files: `connected-tests`
(every class, API 34) when sources of a phone module (`app`, `domain`, `data`, `marketdata`; not their unit tests),
the device tests or `android/testdata/uitest` change, `migration-tests` when only the Room schemas change, and
`release-smoke` when a file changes that decides about the R8 release build (keep rules, build script, dependency
versions, the smoke test); every pull request starts the workflow. The job «Android device tests» collects the
results as a required check (`collect_jobs.py`): a job «changes» did not pick counts as green, a failed one, one
stopped by its time limit or one that never started as red. It uses the same toolchain as `android.yml` (JDK 17, Gradle with
cache), builds `:app:assembleDebug` and `:app:assembleDebugAndroidTest` before the emulator
starts, then runs `:app:connectedDebugAndroidTest` on an x86_64 emulator with KVM
(`reactivecircus/android-emulator-runner`: API 34, `google_apis`, Pixel 6 profile, animations
off) – i.e. everything in `app/src/androidTest`, including `CoreFlowTest` and
`DatabaseFreshInstallTest`. On failure the test reports are uploaded as the artifact
`device-test-reports` (kept 14 days). Besides the 5-minute limit per test method (`timeout_msec`, which does not
cover `@Before`, rules or a hung emulator), `timeout` stops the whole Gradle call after 40 minutes with an error
annotation (API 26: 15, migration tests: 25); the job limit is 75 minutes. On pull requests a newer push cancels
the older run, on `main` and tags every run is kept so each commit gets a result; its token is read-only
(`permissions: contents: read`). Pushing a new or
changed workflow file needs a token with the `workflow` scope (`gh auth refresh -s workflow`).

**UI tests of the main flows (both platforms).** The same four flows, the same steps, the same
identifiers and the same test file on Android and iOS:

| Flow | What the test does |
|---|---|
| Add a coin | «+» → coin search «btc» → «Load lists» if offered → Bitcoin → suggested pair → back: the pair is in the watchlist |
| Price alarm | pair row → pair page «Set alarm» → editor → 200000 → save → alarm card on the pair page; the notification permission is granted (Android: by the test rule; iOS: the system prompt is answered) |
| CSV import | Portfolio (empty) › «Import CSV» → test file → preview → «Import» → BTC and ETH positions visible; ⋯ › «Imports» › «Undo» → gone |
| Backup | pair + CSV import → Settings › Backup › «Back up» with password → data cleared → «Restore» with a wrong password (rejected, hint under the field, nothing restored) → right password → confirm → watchlist and portfolio back |

* **Identifiers.** `UiTestTags` – `android/app/src/main/java/com/cryptochecker/app/ui/UiTestTags.kt`
  (`Modifier.testTag`) and `ios/App/Components/UiTestTags.swift` (`accessibilityIdentifier`), same string
  values (`watch_row`, `explorer_coin_BTC`, `backup_restore_password_hint` …). Only where text would be
  fragile (language, the same label twice, fields without text); everything else is found by resource
  text (Android) or not needed. Identifiers are semantics only – no behaviour, not read by TalkBack or
  VoiceOver. Add a tag on both platforms under the same name.
* **Test file.** `android/testdata/uitest/uitest_portfolio.csv` (synthetic: two USD buys in the generic
  CSV format). Android packs the folder as assets of the test APK only (`sourceSets.androidTest.assets`
  in `app/build.gradle.kts`); `ios/tools/gen_xcodeproj.py` copies it to `ios/UITests/Fixtures/` (`--check`
  fails when the copy differs); the iOS test reads it next to its source (`#filePath`).
* **No network.** Android: `FakeRemoteDataModule` (Binance and Coinbase from `FakeExchange`, everything
  else 404). iOS: `UITestExchange` answers every request of every `URLSession` the same way (the protocol is
  added to the `default`/`ephemeral` configurations and registered for `URLSession.shared`).
* **File pickers.** Android: `DocumentPickerStub` (androidTest) – a blocking `Instrumentation.ActivityMonitor`
  answers «open document»/«create document» with a `file://` URI in the app cache; the app receives it
  through its normal `rememberLauncherForActivityResult`. No production code involved. iOS:
  `testableFileImporter` (in place of `.fileImporter` for the CSV import and the backup restore) and the
  export branch in `BackupSettingsPage.presentExport` use `UITesting.importURL`/`exportURL`: the CSV text
  comes from the launch environment `UITEST_CSV`, the backup goes to `tmp/uitest_backup.json`; `UITEST_BACKUP` puts a given backup text into that file for the restore test.
  The export branch reads the file back like the real path (`UITesting.writeExport` →
  `BackupManager.verifyWritten`); `UITEST_FAIL_VERIFY=1` truncates it after writing, so the check fails and
  «backup not verified» comes as a result dialog (`testExportResultDialogAfterSheet`).
* **iOS seams are inert in production.** All of `UITesting` and `UITestExchange` is inside `#if DEBUG` (a
  Release/App Store build does not contain it; `testableFileImporter` compiles to a plain `.fileImporter`
  there). In a Debug build they act only when the app is launched with `-uiTesting`: then every launch
  starts from empty storage (`AppData.clearStoredFiles`, the same code as «Reset app» – do not run the UI
  tests on a Simulator whose data you want to keep), with «welcome» and the gesture hint seen and the
  portfolio on. `UITEST_KEEP_BACKUP=1` keeps the backup file across a relaunch (the backup test exports,
  relaunches = data cleared, then restores).
* **Waits, not sleeps.** Every step waits for its element (15 s; 60 s while all pair lists load), Android
  with `waitUntil` on the semantics tree, iOS with `waitForExistence`/predicates. iOS runs in English
  (`-AppleLanguages (en)`); a few menu and dialog buttons fall back to their English label where an
  identifier does not reach the system menu.

Run:

```sh
# Android (emulator or device; all instrumented tests or only the UI flows)
cd android
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cryptochecker.app.UiFlowsTest

# iOS (Simulator)
cd ios
xcodebuild test -project CryptoChecker.xcodeproj -scheme CryptoChecker \
  -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:CryptoCheckerUITests
```

CI: Android in *Android device tests* (on pushes to `main`, release tags and by hand, see above); iOS in the *iOS*
workflow, step «UI tests
(iOS Simulator)» after the unit tests (30-minute limit, one retry per failed test, the `.xcresult` with
screenshots is uploaded on failure).

**Screenshots branch** (`.github/workflows/screenshots.yml`, by hand and on release tags `v*` – not on pushes to
`main`; a manual run publishes only from `main`): screenshots of every main screen of both apps, light and dark, readable with plain git
(no artifact download needed). Two jobs in parallel – `ScreenshotTourTest` (androidTest, API 34 emulator, images
written by `screencap` to `/data/local/tmp/cc-screenshots` and pulled with `adb`) and `ScreenshotTourTests`
(XCUITest, attachments `NN_name_light|dark` exported with `xcrun xcresulttool export attachments`) – then the job
`publish` (the only one with `contents: write`) checks the PNGs and force-pushes ONE orphan commit to the branch
`screenshots` (never `main`; each run replaces the previous set, older runs are cancelled). Layout:
`android/<light|dark>/NN_name.png`, `ios/<light|dark>/NN_name.png` and `README.md` with the source commit, date
and run. Only a complete set is published (job green, same names in light and dark, no `NN_name_failed.png`): a
platform whose job failed keeps its images from the previous set (marked «kept» in the README); its partial images
stay run artifacts. Data:
the store demo backup (`android/docs/store/screenshots/demo-backup.json`), restored on both platforms with the same
overrides (Android through `BackupManager.restore` in the test, the file as an androidTest asset; iOS through
«Restore» in the UI), prices from the fake exchanges (Android: the tour fails if they do not load), canned market
data (Android `FakeExchange.marketData`, iOS `UITEST_MARKET_DATA`), light/dark through the app's own setting. Both tests skip themselves in the normal runs: Android needs the runner argument
`screenshotTour=true`, iOS the environment `TEST_RUNNER_SCREENSHOT_TOUR=1`. Screen list: `01_watchlist`,
`02_pair_actions`, `03_pair_alarms`, `04_alarm_editor`, `05_alarms_overview`, `06_refresh_report`,
`07_explorer`, `08_coin_search`, `09_market` (waits for the tag `market_loaded`: Crypto Pulse loaded and every
visible card on «Now» shown; no fixed wait),
`10_portfolio`, `11_settings`, `12_settings_display`, `13_settings_alarms`, `14_settings_backup`, `15_about`.

```sh
git fetch origin screenshots
git show origin/screenshots:README.md                              # source commit of the images
git worktree add ../cc-screenshots origin/screenshots              # or: git archive origin/screenshots | tar -x -C DIR
git show origin/screenshots:android/dark/01_watchlist.png > /tmp/01_watchlist.png
```

**Baseline profile** (`.github/workflows/baseline-profile.yml`, by hand only: *Actions › Baseline profile › Run
workflow*): generates the measured baseline profile of the Android app. The build never generates it itself
(`baselineProfile { automaticGenerationDuringBuild = false; saveInSrc = true }` in `android/app/build.gradle.kts`):
the profile is a committed source file, so the release build stays reproducible and needs no device. The workflow
builds `:app` as `nonMinifiedRelease` plus the generator APK, boots the API 34 `google_apis` x86_64 emulator of the
device tests (userdebug image with root, which the collection needs – not `google_play`) and runs
`./gradlew :app:generateBaselineProfile`; `BaselineProfileGenerator` (module `:baselineprofile`) has two journeys:
`startup` starts the app cold until the watchlist shows (rows, or the starter choice on a fresh install) and alone
forms the startup profile (`includeInStartupProfile = true`); `generate` takes over the starter coins on the fresh
install, scrolls the watchlist, opens a pair page and the tabs Market, Portfolio (switched on once under Settings ›
Portfolio) and Settings – it goes into the baseline profile only. Both run several times. It finds the UI through the test
tags of `UiTestTags` (`testTagsAsResourceId` on the Scaffold of `AppNavHost`); rename a tag on both sides. The
release build has no demo mode, so the starter coins and the Market tab need the network: without it the run goes
on, and the step «Check profile» warns about every screen without a rule (it fails without a profile or with fewer
than 100 rules for the app, or when the startup profile equals the baseline profile). To commit the result:

1. Run the workflow on the commit to measure (normally `main`) and download the artifact `baseline-profile`
   (kept 14 days; on failure `baseline-profile-logs` holds the Gradle log, the logcat and the test results).
2. Put its files into `android/app/src/release/generated/baselineProfiles/` (`baseline-prof.txt`, and
   `startup-prof.txt`, which R8 uses to lay out the DEX files for the start: only the classes of the start up to
   the watchlist, so they share the first DEX file) and commit them unchanged.
3. The next build packages the profile; there is no hand-written profile beside it. `apk_contents.py` warns when the
   release APK or AAB carries no profile.

Repeat after larger changes to the start, the watchlist, the pair page or the tabs. Dependency verification applies
to this run as to every build; its artifacts are written by *Supply chain update* (steps «baseline profile module»
and «device tests») – after a dependency change run that workflow first.

**CI gates** (`.github/workflows/android.yml`, on every pull request – one that changes only Markdown skips the
three heavy jobs –, on pushes to `main` touching `android/` or the Swift sources checked by the source guards, and
on every release tag `v*`): unit tests, lint (`lintDebug`, `abortOnError`), instrumented tests compiled, debug
build and an unsigned release build with R8 (catches missing keep rules). Pushes to `main`
submit the Gradle dependency graph (Dependabot alerts) in two jobs: `dependency-graph` (read-only, runs Gradle,
`dependency-graph: generate-and-upload`, the only place with `--dependency-verification lenient`) and
`dependency-submission` (`contents: write`, no checkout and no Gradle, `download-and-submit` of that run's
artifact); pull requests run a dependency review
that fails on new dependencies with known high-severity vulnerabilities.
`.github/dependabot.yml` opens monthly version update PRs for GitHub Actions (at most two open);
Gradle (version catalog `android/gradle/libs.versions.toml`) only gets security updates. The *iOS*
workflow builds and tests in the Simulator (unit tests, then UI tests) on every pull request that changes more
than Markdown; on pushes to
`main` only when `ios/`, the shared parity cases or the UI-test files change.

**What to do when CI fails without a code reason.**
* *Device test stopped after 40 minutes or a job cancelled at its limit* (emulator hung): «Re-run failed jobs» once;
  if it hangs again on the same class, the report artifact names the last test – look there, not at the emulator.
* *«Dependency verification failed»* (annotation «Dependency verification» from `gradle_errors.sh`, naming the
  artifact and repository): other bytes arrived than `verification-metadata.xml` records (cut-off download, or the
  same coordinate from another repository). Note the artifact and the received checksum before re-running; if it
  comes back, do not keep re-running – compare with *Supply chain update* («Supply chain (Android)» below).
* *A collector («Android build», «Android device tests», «iOS build») red with «never started»*, or a required check
  that stays «Expected» (GitHub outage: a job or the collector itself never got a runner): «Re-run all jobs» of that
  run; nothing in the workflow can catch a collector that never starts.

**Exchange API check** (`.github/workflows/exchange-live-check.yml`, weekly on Wednesdays and
by hand): `.github/scripts/exchange_live_check.py` calls the public single-ticker endpoint of
every adapter for one major pair (BTC) and checks that the fields the adapters parse – last
price and, where read, bid/ask, volume or 24 h change – are present and numeric. The endpoint
and field table lives in the script; update it together with an adapter (URLs and field names
as in `android/testdata/exchanges/exchange_*.json`). On a failure the job opens ONE issue
«Exchange API check failed» (or updates the open one) and the run stays green (the issue is the signal); when all pass again it
closes the issue. HTTP 451 (and 403 for exchanges that block US cloud IPs, e.g. LATOKEN, or an exchange's own block
answer listed under `blocked`, e.g. Mercado Bitcoin's 302 without Location) counts as skipped,
since GitHub's runners are in the US. The scheduled data workflows (`exchange-live-check.yml`,
`macro-calendar.yml`, `market-data.yml`) run in two jobs: the fetch job (`contents: read` only) talks to third-party
servers and uploads its result as an artifact; the publish job (write/issue permissions, no third-party requests)
checks out `github.sha` afresh, downloads the artifact with `gh run download` (`actions: read`) and checks it with
`.github/scripts/artifact_check.py` (expected file names, size, rough JSON schema; `--self-test`) before pushing,
uploading or managing the issue (`macro-calendar.yml` then calls `pages.yml` and starts «Market data» from a job of its
own with `actions: write`; see *Macro calendar* under «Data mirror»). Locally, without
touching GitHub: `python3 -I .github/scripts/exchange_live_check.py --dry-run` (optionally
`--only kraken,okx`).

**Supply chain (Android).** Three layers, none needs a secret:

* *Gradle itself*: `android/gradle/wrapper/gradle-wrapper.properties` pins the distribution with
  `distributionSha256Sum`; `setup-gradle` validates the wrapper jar in CI.
* *Checksums and locks*: `android/gradle/verification-metadata.xml` (sha256 of every dependency, plugin and tool
  artifact) and `android/<module>/gradle.lockfile` (resolved versions per module). Gradle verifies the checksums on
  every build as soon as the file exists; `android/build.gradle.kts` locks all configurations – LENIENT while a module
  has no lock file, DEFAULT once it has one (a different version fails the build; configurations without an entry,
  e.g. ones resolved only on a device, stay allowed). Both files are **generated, never edited by hand**: run
  *Actions › Supply chain update › Run workflow* (`.github/workflows/supply-chain-update.yml`) on `main`. It runs the
  CI task set with `--write-verification-metadata sha256 --write-locks` (plus Kover, the baseline-profile module with the app's `nonMinifiedRelease` build and,
  optionally, one device-test class on an emulator; Windows and macOS add their aapt2 builds for local builds), checks
  the result (`.github/scripts/supply_chain_files.py`) and force-pushes ONLY these files to the branch
  `supply-chain/update`; open a pull request from it and merge once CI is green. Repeat after every change to
  `libs.versions.toml`, a plugin or the Gradle version (including Dependabot security updates – their pull request
  fails verification until the files are regenerated: run the workflow on the Dependabot branch – `supply-chain/update`
  then starts from that commit, so its pull request carries the version change plus the files; merge it instead).
  Sources and javadoc jars and the Gradle sources zip (downloaded by Android Studio) are trusted without checksum; the trust rules live in the
  seed in `supply_chain_files.py`. A local build failing with «Dependency verification failed» after a deliberate
  change: regenerate; for a one-off local experiment `--dependency-verification lenient`.
* *Routine: once a month and before every release.* Gradle version pull requests are switched off
  (`.github/dependabot.yml`), so nothing reminds of the state of the dependencies – this routine does. It is started
  by hand on purpose: the workflow pushes a branch, and no schedule should write to the repository unattended.
  1. Look at *Security › Dependabot* and the last weekly run of *Dependency vulnerabilities* (`osv.yml`); compare
     the versions in `android/gradle/libs.versions.toml` (Android Gradle Plugin, Kotlin, Compose BOM, Room, OkHttp)
     with their current releases and raise only what is needed (security fix, required by a store, a bug that
     affects the app).
  2. Run *Actions › Supply chain update › Run workflow* on `main`, «signatures» checked – also when no version
     changed (new signing keys, artifacts of the platform tools).
  3. Compare the branch `supply-chain/update` with `main`. No difference: done. Otherwise open the pull request,
     read the summary of «publish» (trusted keys, every ignored key with its reason, sha256-only artifacts – an
     ignored key that is new needs a reason you accept) and merge once CI is green.
  4. Before a release: do this before the last build number is set, so the tag carries the current files; the
     tag then runs *Reproducible build* and *Dependency vulnerabilities* for exactly that state.
* *Signatures (PGP)*: the workflow input **signatures** (checked by default) runs Gradle with
  `--write-verification-metadata sha256,pgp --export-keys` from a seed with `verify-signatures=true` and
  `keyring-format=armored`, and the branch then also carries `android/gradle/verification-keyring.keys` (public keys
  only; `supply_chain_files.py --check` rejects anything else, also a key trusted without a group or for a pattern
  that matches every group). Signed artifacts are covered by `<trusted-keys>` (each key limited to its groups).
  Exceptions are recorded, not hidden: an artifact without signature keeps its sha256 entry (origin «Generated by
  Gradle because artifact wasn't signed»), a key that no key server has goes to `<ignored-keys>` with its reason and
  its artifacts keep their sha256 entry; no extra `<trusted-artifacts>` rule is added. Gradle runs several times and
  may ignore a key in one run that another run trusted and exported: `supply_chain_files.py --clean-ignored` (linux
  job and «publish», before `--check`) removes every ignored key that is in the keyring (key IDs computed from the key
  packets) so its signatures are checked, and drops the trust of an ignored key that is trusted but not in the
  keyring (its artifacts stay on sha256, with a warning); `--check` rejects a key that is still both. The job summary of «publish»
  lists the number of trusted keys, every ignored key and the number of sha256-only artifacts – review them in the
  pull request. `supply_chain_files.py --fill-checksums` also adds the sha256 of every other artifact of the fresh
  Gradle cache, because the SBOM needs the SHA-256 of each shipped binary (publish runs `sbom.py` as a check). **Until
  this has run once, the committed file stays at `verify-signatures=false`** (a keyring cannot be created offline;
  switching the flag by hand breaks every build). The owner starts it: *Actions › Supply chain update › Run
  workflow* on `main`, «signatures» checked (Windows/macOS as needed), then open the pull request from
  `supply-chain/update`, check the summary and merge once CI is green. From then on every run keeps signatures on;
  unchecking «signatures» writes checksums only again and removes the keyring. Builds read the keyring locally and
  need no key server.
* *Known vulnerabilities*: `.github/workflows/osv.yml` (pushes and pull requests touching `android/`, release tags,
  weekly on Tuesdays) resolves `:app` `releaseRuntimeClasspath` and checks every coordinate against the OSV database
  (`.github/scripts/osv_check.py`, `--self-test`). A vulnerability with a fixed version is an error on `main`, tags
  and the weekly run, a warning on pull requests; without a fix or when OSV is unreachable it is a warning. An
  accepted finding goes into `.github/osv-ignore.txt` (`ID  # reason`; optional, created when first needed) and is
  then only a notice.
* *SBOM*: the *Android* workflow writes a CycloneDX 1.5 software bill of materials (`.github/scripts/sbom.py`,
  `--self-test`) after the release build and keeps it as artifact `sbom` (90 days): `sbom-android.cdx.json` lists every
  `releaseRuntimeClasspath` coordinate of the committed lock files (`:app`, `:marketdata`; not the baseline-profile
  generator) with package URL and the SHA-256 of the shipped `.aar`/`.jar` from `verification-metadata.xml`
  (also when the variant file is named differently, e.g. Compose `ui.aar`; Kotlin-multiplatform umbrella modules and
  BOMs have no binary, hence no hash); a shipped coordinate missing there
  fails the step. `sbom-ios.cdx.json` states that the iOS app has no third-party packages and fails as soon as a
  package manifest (`Package.swift`, `Podfile`, `Cartfile` …) appears. No timestamp or serial number: same sources,
  same bytes.
* *Reproducible build*: `.github/workflows/reproducible.yml` (release tags, weekly on Sundays, by hand and on pull
  requests that change the check itself – not on pushes to `main`) builds the unsigned release APK and AAB and the unsigned Wear OS release APK
  (`:wear:assembleRelease`) twice on two fresh runners without any cache and
  compares them and the SBOM byte for byte (`.github/scripts/repro_compare.py`, `--self-test`; a difference names the
  archive entries). Not a required check. It proves that two independent builds on the same runner image agree; a
  different OS, JDK or build path is not covered (anyone rebuilding should use the same JDK 17 and Gradle wrapper).
  iOS is not covered (Xcode output is not byte-reproducible).
* *Build provenance (release tags `v*`)*: once both builds agree, the job «provenance» of `reproducible.yml` attests
  the unsigned APKs (app, Wear OS) and AAB of run a with `actions/attest-build-provenance` (Sigstore, stored as an attestation of the
  repository; only this job has `id-token: write` and `attestations: write`) and lists their SHA-256 in the job
  summary (copy them into the release notes if wanted). Check a CI artifact: `gh attestation verify
  app-release-unsigned.apk --repo r1adBE/cryptoChecker`. Check a signed store APK against it – signing only adds the
  signature block, so after removing it the APK must equal the CI artifact byte for byte:

  ```sh
  pip install apksigcopier                      # or the Debian/Ubuntu package apksigcopier
  apksigcopier extract signed.apk sig/          # signature block of the signed APK
  apksigcopier patch sig/ app-release-unsigned.apk resigned.apk
  cmp resigned.apk signed.apk && echo "signed APK = CI build + signature"
  ```

  (`apksigcopier compare --unsigned signed.apk app-release-unsigned.apk` does the same in one step.) Without
  apksigcopier: `zipalign -c -P 16 -v 4` both files and compare the entry lists and CRCs (`unzip -lv`) ignoring
  `META-INF/*.SF|*.RSA|*.EC|MANIFEST.MF` – weaker, because v2/v3 signatures live outside the ZIP entries. The AAB
  from Play cannot be compared this way (Google re-signs the generated APKs); compare the AAB you uploaded.

Kotlin compiler warnings are errors in the app and library sources (`allWarningsAsErrors` in
`android/build.gradle.kts` for `:app`, `:domain`, `:marketdata` and `:wear`, not for test sources; it holds for
modules from the Gradle build cache too). `:data` is left out because the same task compiles Room's generated
Kotlin; the step «Kotlin warnings» in `android.yml` counts the `src/main` warnings of every module compiled in the
run (ceiling 0) and covers it.

The *Android* workflow also shows, never failing: lint warnings counted by id (top 20, one notice;
`.github/scripts/lint_warnings.py`). Unit-test line coverage is shown and gated (see below).

**Line limit and history in comments.** `.github/scripts/line_limit.py` (`android.yml`, step «Line limit»,
`--self-test`): at most 400 lines per production file (Kotlin in `android/*/src/main`, Swift in `ios/App`,
`ios/Shared`, `ios/Widgets` and the watch folders). Build numbers («Build NNN») and review tags («(Review D13)»,
«(D1)») are red in those files and in the build and CI files (`.github`, `ios/project.yml`, `ios/tools`,
`android/tools`, Gradle scripts, version catalog, `gradle.properties`, ProGuard rules); version lines and
`release_readiness.py` are skipped. Comments describe the current state; history goes to CHANGELOG.

**Size ceilings beside the 400-line limit.** Both scripts have `--self-test`, ignore comments and string literals,
and keep their ceilings in the script (today's maximum: nothing may grow past it; lower a ceiling when the code
shrinks, raise it only deliberately with the reason in the same pull request).
* *Code metrics* (`android.yml`, step after «Line limit», and `ios.yml`, step after «Swift escape hatches»;
  `.github/scripts/code_metrics.py`): constructor
  dependencies of every `@HiltViewModel` / `@Inject constructor` class in `android/*/src/main`, of every class a
  `@Provides` function returns and of the `@Provides` function itself; a parameter whose type is a pure bundle (only
  `val` constructor parameters, no function of its own, e.g. `RefresherState`) counts with the bundle's parameters,
  so wrapping collaborators lowers nothing – red above `DEPS_CEILING` (the value is in the script) – and `private`/`internal` properties declared in a ViewModel body (not constructor parameters,
  not nested classes or functions) – red above `PROPS_CEILING` 26 (`ExplorerViewModel`). A class at the limit is
  split (use case, helper, state holder) instead of growing. Function length (lines from the keyword to the end of
  the body): Kotlin `fun` (block or expression body) in `android/*/src/main`, Swift `func`, `init`, `deinit` and the
  SwiftUI `var body` in the same folders as the escape hatches; the 30 longest per platform are listed, red above
  `FUNCTION_CEILINGS` (the values are in the script); logic functions (not user interface) have their own lower
  `LOGIC_CEILINGS`, and Swift types are measured across all their extension files (`SWIFT_TYPE_CEILINGS`). New functions stay under
  `FUNCTION_TARGET` 80 lines; the ceilings only keep the remaining long ones from growing and go down with every
  split (long calculations become a class with one method per step, e.g. `TaxYearSummaryBuilder`,
  `PortfolioFifoBook` on both platforms).
* *Swift escape hatches* (`ios.yml`, job «iOS release build», first step, `.github/scripts/swift_escape_hatches.py`):
  `@unchecked Sendable`, `nonisolated(unsafe)`, `DispatchSemaphore` and `uncheckedState:` in `ios/App`, `ios/Shared`, `ios/Widgets`,
  `ios/Watch`, `ios/WatchCore` and `ios/WatchWidgets` (not `ios/Tests`) against `CEILINGS` (`@unchecked Sendable` 0,
  `nonisolated(unsafe)` 0, `DispatchSemaphore` 0, `uncheckedState` 7); above is red, below is a notice to lower it. Shared mutable state
  goes into `OSAllocatedUnfairLock<State>` (module `os`, `withLock { state in … }`, the state type `Sendable`) or an
  actor instead of `NSLock` + `nonisolated(unsafe)` / `@unchecked Sendable`; never `withLockUnchecked`. A
  non-`Sendable` object that must be shared (cached formatters, `UserDefaults`, `BGTask`, an SDK completion handler)
  lives only inside such a lock (`init(uncheckedState:)`) and is touched only in `withLock`, which takes `@Sendable`
  closures and returns only `Sendable` values – it never leaves the lock. Its own thread safety is still trusted, so
  every `uncheckedState:` counts as an escape hatch (ceiling 7, the places in «Known warnings»). A caller's non-`Sendable` closure that must
  run in order (write bodies, actions) runs under an `NSLock`, with the counter beside it in an
  `OSAllocatedUnfairLock` read only under that `NSLock`.

**Coverage (logic gate).** Both platforms report line coverage of the unit tests as ONE notice on the run page, each
with two numbers: *total* (every measured source file) and *logic* – the share that tests are expected to cover. The
*logic* number is a gate: below `MIN_LOGIC` (workflow env, set just under the measured value – Android 65 %, measured
68.1 %; iOS 70 %, measured 71.9 %) or without a report the job is red. It stops regressions without forcing new work;
raise `MIN_LOGIC` when coverage grows. (`--min-logic P` in both scripts; without it they only display.)

* *Android* (`android.yml`, step «Coverage (logic gate)»): Kover is applied only with
  `-Pcoverage=true` (no new plugin or dependency; lock files and `verification-metadata.xml` already cover it). The
  step «Unit tests» runs `testDebugUnitTest` once with `-Pcoverage=true` and writes `:app:koverXmlReportDebug` and
  `:marketdata:koverXmlReportDebug`; the gate step only reads these reports (no tests run again). `:app` merges
  `:marketdata`, `:domain` and `:data` into its report (`kover(project(…))` in `app/build.gradle.kts`): the report
  counts the unit tests of all four modules together – the logic tests in `:domain` and `:data` and the tests in
  `:app` that cover the other modules (e.g. the exchange parsers in `ExchangeContractTest` with
  `testdata/exchanges/exchange_*.json` and `recorded/*.json`, the parity fixtures), which a module's own report
  would miss. `.github/scripts/coverage_summary.py --merged app
  --min-logic 65 --min-module data=57 app=app domain=domain data=data marketdata=marketdata` attributes every file to
  the module whose `src/main` contains it and prints per module and in total; every module must be listed, or its
  files count as generated and drop out of *logic*. `:marketdata`'s own report is listed as «own tests only». Second
  gate `MIN_DATA` 57: the *logic* coverage of `:data` (storage, network) on its own (measured 59.5 %). *Logic*
  leaves out UI – source files containing `@Composable` (screens, components and their Compose-generated classes),
  `RemoteViews` or `androidx.glance` (widget rendering) and `…Activity.kt` – and generated code (anything not in
  `src/main`: Hilt, Room `_Impl`, `BuildConfig`, `R`). ViewModels and other classes in `ui/` packages without
  `@Composable` count as logic. `--self-test` checks the classification and the merge. Locally:
  `./gradlew -Pcoverage=true :app:koverHtmlReportDebug` (report under `app/build/reports/kover/`; the summary needs
  the XML: `testDebugUnitTest :app:koverXmlReportDebug :marketdata:koverXmlReportDebug`), then in `android/`:
  `python3 -I ../.github/scripts/coverage_summary.py --merged app --min-logic 65 --min-module data=57 app=app
  domain=domain data=data marketdata=marketdata`.
* *iOS* (`ios.yml`, job «iOS unit tests»): the unit-test run itself records coverage (`-enableCodeCoverage YES`,
  result bundle `UnitTests.xcresult`; same run, only the flag). The step «Coverage (logic gate)»
  (only after green unit tests) reads `xcrun xccov view --report --json` with
  `.github/scripts/xccov_summary.py` (`--self-test`). Test bundles are left out; a file compiled into app and widget
  extension counts once (best entry). The notice shows *logic* = files under `ios/Shared/` and `ios/App/` that
  define no SwiftUI view (no `some View`) and *shared logic* = the same rule for `ios/Shared/` only (measured 80.8 %).
  `GATE_SCOPE: logic` gates *logic*; change it only together with a `MIN_LOGIC` measured for the other scope. Locally:
  run the tests in Xcode with coverage on (scheme › Test › Options › Code Coverage), then
  `xcrun xccov view --report --json <path to .xcresult> > /tmp/cov.json` and
  `python3 -I .github/scripts/xccov_summary.py --min-logic 70 --gate-scope logic /tmp/cov.json`. A manual run with
  Thread Sanitizer (input `thread_sanitizer`) skips the performance tests (`TEST_RUNNER_CC_SKIP_PERFORMANCE=1`, seen by
  the tests as `CC_SKIP_PERFORMANCE=1`) and therefore only shows the coverage, without the gate.
* Where logic is still uncovered, prefer a parity fixture (`testdata/parity/`, same cases on both platforms) over a
  single-platform test; network sources take an OkHttp `Interceptor` as fake provider (e.g. `FxRateSourceTest`).

**Mutation tests (portfolio and tax logic).** `.github/workflows/mutation.yml` (Saturdays and by hand, never a required
check) first runs `./gradlew :app:testDebugUnitTest :domain:testDebugUnitTest :data:testDebugUnitTest` (must be
green), then `.github/scripts/mutation_test.py` (standard
library only, `--self-test` without Gradle). For each target file – `PortfolioFifo`, `PortfolioFifoBook`, `PortfolioFifoLots`,
`PortfolioCalculatorAverage`, `PortfolioFeeExpenses`, `PortfolioCostFx`, `TaxEngineCore/Uk/Ca/Fr/At/Jp` and
`TaxExportRows` under `domain/portfolio` – it creates mutants by fixed rules (`<`↔`<=`, `>`↔`>=`, `==`↔`!=`, `&&`↔`||`,
`+`↔`-`, `*`↔`/`, `+=`↔`-=`, `*=`↔`/=`, `true`↔`false`, `.subtract(`→`.add(`, `.multiply(`↔`.divide(`, `.min(`↔`.max(`
and `return` of a constant of the declared type; never in strings, comments or generics), at most 4 per file and 50 in
total, chosen deterministically (hash of file, line, column and rule), no new mutant after 55 minutes. Each mutant is
written into the file, the test classes listed for that file in `TARGETS` run with
`./gradlew :<module>:testDebugUnitTest --tests '<pattern>' … -q --offline` (every pattern with the test task of the
module that holds the class – `app`, `domain` or `data`, read from `src/test`; the self-test fails for a pattern
without a class), and the file is restored in a `finally` (also
when the run is cancelled; written
atomically via a temporary file and `os.replace`). A Gradle run that hits the mutant timeout counts as killed; the
script then stops the Gradle daemons (`gradlew --stop`), so a hung test run does not block the next mutant. The
setup-gradle cache is read only in this workflow. Result: «killed» (tests red), «survived» (tests green: a warning with
file:line and the change) or «not compilable» (not counted); a notice per file with the share killed and one with the
total, via `annotate.py`. The run is red only when the baseline or the script fails (missing target, a pattern that
matches no test, a file that could not be restored). A surviving mutant means a missing test – or an equivalent change;
add a case (preferably a parity fixture) rather than a filter. When a target file is renamed or its tests move, update
`TARGETS` (the self-test checks that every target exists). Locally (repository root, after a green test run):
`python3 -I .github/scripts/mutation_test.py --list` shows the chosen mutants,
`python3 -I .github/scripts/mutation_test.py --findings /tmp/m.txt --only TaxEngineUk --online` runs them.

## 13. Market tab caching

Every area of the Market tab is stored on the device with a timestamp (Android
`CycleCacheStore`, iOS `CycleCache`), shown immediately on open and reloaded quietly in the
background only when it is older than its TTL. The TTLs live in one place per platform —
`CycleSource` / `CycleCachePolicy` (Android, `domain/market/CycleCache.kt`) and
`CycleCachePolicy` (iOS, `App/Features/Cycle/CycleCache.swift`) — and must stay equal. The same goes for the hard
time limit per area (`CycleSource.timeoutMillis` / `CycleCachePolicy.timeoutMillis`, parity
`market_notice.json`): 12 s for Crypto Pulse, Fear & Greed, `/global` and gas, 20 s for the multi-request areas
(Unusual today, Bitcoin indicators, on-chain values, altcoin count, cycle comparison, coin analysis).

| Area | TTL | Why |
|------|-----|-----|
| Crypto Pulse | 5 min | 24 h changes, BTC volume, funding, breadth of the top 30 (total market cap: 30 min in memory) |
| Unusual today | 10 min | 24 h tickers and funding of all perpetuals |
| Gas | 1 min | fees change by the block |
| Coin analysis | 15 min | per coin |
| Fear & Greed | 1 h | the index changes once a day |
| Market cap / volume / dominance (CoinGecko `/global`) | 30 min | slow-moving |
| Bitcoin indicators (`MARKET`) | 1 h | several sources with fallbacks |
| Altcoins vs. Bitcoin count (`ALT_SEASON`) | 3 h | ~20 histories; moves over days |
| On-chain values (Coin Metrics) | 12 h | daily data |
| Cycle comparison (halving curves) | 12 h | daily candles since 2016 |
| Economic calendar | 24 h | one small JSON file |

**Data mirror** (`DataMirror.kt` / `DataMirror.swift`, parity cases `data_mirror.json`): data that
is the same for every user is fetched once an hour by `.github/workflows/market-data.yml`
(`.github/scripts/mirror_data.py`) and uploaded as assets of the release «data»
(`https://github.com/r1adBE/cryptoChecker/releases/download/data/<file>`, first line
`CCDM1 <fetched ms>`, then the provider's body unchanged; nothing is committed). `callExchange`
(Android) and `ExchangeHTTP.call` (iOS) ask the mirror first for exactly these GET URLs and the
provider otherwise: Fear & Greed (`fng.txt`, max age 4 h), Coin Metrics
on-chain values (`onchain.txt`, any `start_time`, 36 h) and BTC price since 2016 (`btcprice.txt`,
36 h). `altseason.txt` (6 h) is not a copy but the altcoin season already computed by the script
(same 20 alts and 90-day rule; `{"outperformers":12,"total":20,"provider":"Binance"}`, read with
`DataMirror.parseAltSeason`); without it the app computes it from ~21 candle requests as before. Live data (tickers, Crypto Pulse, gas, per-coin analysis) stays direct. A new mirrored URL
needs the same entry in `mirror_data.py`, both `DataMirror` files and `data_mirror.json`.

**Signed mirror** (`MirrorManifest.kt` / `MirrorManifest.swift`, cache `MirrorTrust.kt` / `MirrorTrust.swift`,
parity cases `mirror_signature.json`): the publish job of `market-data.yml` writes, after `artifact_check.py`,
`manifest.json` (`{"created":<ms>,"files":{"<file>":"<SHA-256 hex>"…},"revoked":[…],"v":1}`, compact, sorted keys) and
`manifest.json.sig` (Base64 of the DER ECDSA P-256 / SHA-256 signature over the exact manifest bytes) with
`mirror_data.py --manifest`, signed with the secret `MIRROR_SIGNING_KEY` (PEM, PKCS#8) through the
preinstalled `openssl` command line tool — no package is installed in the job that holds the write token. Both are
uploaded after the data files. The publish job runs only on `main` (`if: github.ref == 'refs/heads/main'`; a manual
run on another branch fetches and checks but publishes nothing) and in the environment `mirror-signing`. The secret
belongs there as an environment secret, with *Deployment branches* limited to `main` (Settings › Environments ›
mirror-signing); a repository secret of the same name also works (an environment secret takes precedence), so moving
it is: create the environment secret with the same content first, then delete the repository secret. Until the
environment exists GitHub creates it unprotected on the first run, which changes nothing. Files that were not renewed in a run keep their hash only from the previous manifest,
and only if that one is validly signed (the old asset stays on the release). The apps read every file under
`releases/download/data/` (also `altseason.txt` and `coin_meta.json`) only if the signature matches the built-in public
keys (`MirrorManifest.PUBLIC_KEYS`: the active `PUBLIC_KEY` and the reserve `RESERVE_PUBLIC_KEY`, ECDSA P-256 because Android minSdk 26 has no Ed25519 in the platform), the manifest
is less than 7 days old and not more than 5 minutes in the future (no replay of old data), and the file's SHA-256 (over
its UTF-8 bytes) matches. Otherwise the file is ignored exactly like an unreachable mirror and the app asks the
provider directly — no message, no crash. Manifest and signature are kept in memory only: 15 minutes when valid,
5 minutes after a failure; a file newer than the remembered manifest reloads it once (at most once a minute).
Without the secret the workflow keeps running, publishes the data and the manifest unsigned (warning annotation; a key
that does not match the public key gives an error annotation) and removes an old signature, so the apps use the
providers directly; the run then ends red (error annotation after the upload) so that a missing signature is noticed. Tests use their own test key pair (`publicKey` in the fixture); the real private key never enters the
repository.

*Macro calendar* (`MacroCalendar.kt` / `MacroCalendar.swift`, parity cases `macro_calendar_signature.json`): the
economic calendar is not a release asset but `macro/events.json` on GitHub Pages
(<https://r1adbe.github.io/cryptoChecker/macro/events.json>, unchanged URL). Its data lives on the branch **`macro`**
(`events.json` and `status.json` at the root, nothing else), not on `main`: `main` has required status checks, so the
weekly push with `GITHUB_TOKEN` would be refused there (no bypass by decision of the owner).

* `macro-calendar.yml` (Mondays, by hand): *fetch* starts from the tip of `macro` (public, no token), builds the new
  calendar and hands it over with that tip as `base`; *publish* (main only) checks the artifact, runs the health check
  and pushes one commit on top of `base` to `refs/heads/macro` – never to `main`, never with `--force`, so a branch
  that moved meanwhile fails the run instead of being overwritten. First run: the branch is missing (`git ls-remote`
  exit 2), so it is created as an orphan whose first commit is `docs/macro` of `main` (seed), the new calendar
  follows; any other error reading the branch fails the run. Then *pages* calls `pages.yml` (reusable workflow:
  runs inside the same run, the next job waits for the deployment, no extra token permission), every week, also
  without changes; *manifest* starts «Market data» with `gh workflow run` (`actions: write`; pushes and runs with
  `GITHUB_TOKEN` start no other workflow, a dispatch does) only when the calendar changed and was deployed.
* `pages.yml` (push to `main` touching `docs/**`, by hand, called by `macro-calendar.yml`) deploys with
  `actions/deploy-pages` (`pages: write`, `id-token: write`, environment `github-pages`): `docs/` of the current
  `main` without `docs/macro`, plus `events.json` of the current tip of `macro`. Branch or file missing → `docs/macro/events.json`
  of `main` with a warning; neither → the run fails and the site stays. The calendar is checked by
  `artifact_check.py --profile pages_macro` (same schema checks as the calendar artifact; empty or invalid is never
  deployed) and kept as the artifact `pages-macro` (`events.json` plus `source.txt` = `macro <commit>` or
  `main <commit>`, 90 days). One job with the job-level concurrency group `pages`: builds and deployments never
  overlap or overtake each other; it reads the tips, not `github.sha`, so a re-run of an old run brings back nothing old.
  Needs *Settings › Pages › Source: GitHub Actions* (owner, once; ROADMAP).
* `market-data.yml` signs exactly what Pages serves: the newest `github-pages` deployment whose latest status is
  `success`, its run (from the status log URL), and from that run the artifact `pages-macro`; for the old branch
  build (`pages-build-deployment`, before the switch) `docs/macro/events.json` of the deployed commit
  (`deployments: read`, GitHub API only). Checked again with the profile `pages_macro`, then
  `mirror_data.py --signed-extra macro_events.json=<file>` (same manifest and keys, no second secret). Lookup failed:
  the hash of the previous signed manifest stays (warning).
* `main` keeps `docs/macro/events.json` as the bootstrap seed and fallback (`setup-github-public.ps1` copies it from
  `android/docs/macro`, and keeps the GitHub copy once it exists); it is no longer updated and Pages serves it only
  while the branch `macro` is missing.

Remaining window: after a deployment the manifest follows within minutes (macro-calendar's *manifest* job, else the
next hourly run), and the Pages CDN may serve the previous file for up to about 10 minutes; in both cases hash and file
differ briefly and the apps keep their cache. A deployment never signs bytes it did not deploy.
The apps accept a downloaded calendar only if that hash is in a valid manifest
(`MacroCalendar.parseVerified` with `MirrorTrust`); a changed, unlisted or unverifiable file counts as a download
error: the cache (also expired) or else the built-in `macro_events.json` is used, as before.
When a date in the note is the last known one of its series (no later date of that type in the file, e.g. BEA
publishes PCE only until December), `MacroHint.seriesEnds` adds one line «Further dates will follow once the source
publishes them.» (`macro_more_dates_later`, `MacroTexts.rowText` / `MacroCalendar.rowText`; not in the morning
notification).

*Reserve key*: the apps and `mirror_data.py` (`PUBLIC_KEYS`) accept a second public key,
`RESERVE_PUBLIC_KEY` (`productionReservePublicKey` in `mirror_signature.json`). Its private key is not a secret on
GitHub; the owner keeps it offline in two separate places (password manager plus a second offline copy). If
`MIRROR_SIGNING_KEY` is lost or leaks, set the environment secret to the reserve private key — the released apps keep
trusting the mirror without an update. Then create a new reserve pair and ship it as the next `RESERVE_PUBLIC_KEY`
(the used one becomes `PUBLIC_KEY`; drop the leaked key from the apps in the same release).
Backup rule: every private signing key exists in two offline places before its public key ships; never in the
repository, a chat, a ticket or a CI log.

*Revocation list*: every manifest carries `"revoked"` — key ids (`keyId` / `key_id`: SHA-256 hex of the public
key DER bytes) of revoked signing keys; `mirror_data.py` writes `REVOKED_KEY_IDS`, normally an empty list (a missing
field counts as empty, so older manifests stay valid; `artifact_check.py` accepts both). The apps honour an id only in
a fresh, validly signed manifest of the OTHER built-in key; their own id (self-revocation) and unknown ids are
ignored. A revoked key id is stored for good (`MirrorRevocations`: Android `noBackupFilesDir/
mirror_revoked.txt`, iOS `mirror_revoked.txt` in the App Group folder) and from then on a manifest signed only with
that key is rejected. After a leak of `MIRROR_SIGNING_KEY`: set the secret to the reserve private key, add the id of
the leaked key to `REVOKED_KEY_IDS` (`python3 -c "import base64,hashlib;print(hashlib.sha256(base64.b64decode('<public
key>')).hexdigest())"`) and keep it there until no supported app version has the leaked key any more (apps that were
offline, reinstalled or restored learn the revocation only from a current manifest). Mutual revocation: a fresh,
validly signed manifest of an already revoked key is rejected, but if it revokes the other key, that one is revoked
too — A revokes B and B revokes A (in either order) leaves both revoked; the apps then ignore the mirror for good
(market data from the providers / local fallbacks, as with an invalid manifest) until an app update ships new keys.
So whoever holds a leaked key can at worst switch the mirror off, never keep it trusted alone. Revocations only come
from manifests younger than 7 days. Parity cases: `revocation` and `keyIds` in `mirror_signature.json` (own test key
pairs).

*Changing the key*: create a new pair (`openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out
mirror_key.pem`; public key for the apps: `openssl pkey -in mirror_key.pem -pubout -outform DER | base64 -w0`), put
the public key into `MirrorManifest.kt`, `MirrorManifest.swift` and `productionPublicKey` in
`mirror_signature.json`, and release the apps. Only once the new versions are out, replace the secret
`MIRROR_SIGNING_KEY` with the new private key and, at the same time, `PUBLIC_KEY` in `mirror_data.py` (the workflow
checks its own signature against `PUBLIC_KEYS` and otherwise publishes unsigned); older app versions then ignore the mirror and
use the providers directly. If the private key leaked, replace the secret at once and ship the new public key quickly.
Keep the private key file offline; never commit it.

**CoinGecko is never mirrored.** The CoinGecko API terms forbid redistributing and storing their
data, so the mirror carries no CoinGecko file (no `global.txt`, `coins30.txt` or `coins40.txt`) and there is no
`COINGECKO_API_KEY` secret. The apps ask CoinGecko directly
(keyless): `/global` cached for 30 minutes, the top 30/40 for the starter coins for 24 hours, and the
top 40 for the Spot tab and the «Why?» market breadth as one shared request kept in memory for
10 minutes. Everything that shows CoinGecko data carries the line «Powered by CoinGecko» (see
*UI rules*).

* Never publish third-party API data (CoinGecko or any provider whose terms forbid redistribution)
  in the repository, on GitHub Pages or as release assets. Check a provider's terms before adding
  a file to `mirror_data.py`.

**Spot data in the pair page** (`SpotMarket.kt` / `SpotMarket.swift`, `SpotDataSource` on both
platforms, parity cases `spot_market.json`): the «Spot» tab of the «Market data» card (shown for
all crypto pairs except deactivated pairs, DEX pools and TradFi spot data) first reads the CoinGecko
top 40 (`SpotMarket.TOP_URL`, fetched directly, the same request for everyone and shared with the
«Why?» market breadth, 10 minutes in memory) and only for a coin with no matching hit in it asks
CoinGecko `/coins/markets?vs_currency=usd&symbols=<symbol>` (keyless, sends the coin symbol).
Which hit belongs to the pair is decided by name, not by market cap (`SpotMarket.pick` with
`SpotMarket.Identity`, the portfolio price rule): the pair's own name from its exchange list, else
Binance's name (not for a known double symbol or conflicting pair lists), compared with
`TokenIdentity.matchNames`; the same name wins (several: the largest market cap among them), a merely
similar name only as the sole hit, a different name never; without a usable name only a sole hit. No
match shows no spot data rather than another token's.
Answers, including «not found», stay in memory for 10 minutes per symbol; failures are not cached.
Nothing is requested until the tab is opened. The «Futures» tab of a spot pair loads the coin's USDT
perpetual with the existing `fetchForBase` (Binance Futures `premiumIndex` / `openInterest` →
Bybit → OKX), also only on opening, and labels it as reference data (see [section 17](#17-why-futures-data-chart-and-asset-kinds)).
A new provider here needs an entry in the privacy policy.

**Pair search by name** (`ExplorerPairSearch` / `WatchlistSearch`, same file names on both
platforms; tests `PairSearchTest`, `WatchSearchTest`, `ExplorerPairSearchTests`): queries match
symbols, coin names and (Add pair only) exchanges, accent- and case-insensitive. Ranking: exact
symbol → symbol prefix → name prefix → name contains → exchange; an exchange word needs ≥ 3
characters («btc kraken»). Names come only from data already on the device (the saved logo/name
list and stock names; Android `CoinLogoRepository.loadSavedNames` reads the saved file offline) —
search never triggers a request. In the watchlist the folded search fields of each pair are cached
while the search is open (`WatchSearchIndex` / `WatchlistSearchIndex`, keyed by pair id with the searched
fields and coin name as fingerprint); a keystroke folds only the query (tests `WatchSearchIndexTest`,
`WatchlistSearchIndexTests`).

**Coin-first search** (`ExplorerCoinSearch.kt` / `ExplorerCoinSearch.swift`, parity cases
`coin_search.json`): «Add pair» lists coins first. Rank: exact symbol (or gold/silver alias) → exact name →
symbol prefix → name prefix → name contains; ties by number of exchanges, then shorter name. Picking a coin
suggests one pair: region default exchange first (Binance, or Binance.US in the US / when Binance is geo-blocked),
then favourite exchanges; spot before perpetual before dated; quote order display currency → USDT → USD → USDC →
EUR → BTC (display currency first). Only pair lists already on the device are
searched; missing lists load only after the user taps «Load lists» (privacy note next to the button). The
step-by-step flow stays as «Advanced / by exchange».

**Coin logos** (`CoinLogos.kt` / `CoinLogos.swift`, parity cases `coin_logos.json`): there is
no own logo list or logo pack (CoinGecko's API terms forbid redistributing their data). Every install builds the same list itself: the
symbol → image map starts with the crypto symbols listed on Binance (`CoinMeta.binanceFirst`: spot entries of the
Binance website list `bapi/composite/v1/public/marketing/symbol/list`, `name` → `logo` on `*.bnbstatic.com`,
unofficial; one request, already fetched for names), then CoinGecko `/coins/markets` (pages 1–4 = top 1000, first
occurrence by market cap wins) only for the gaps, then the remaining Binance entries (e.g. XAU, XAG, stocks), then
the official Binance Alpha token list
`bapi/defi/v1/public/wallet-direct/buw/wallet/cex/alpha/all/token/list` (`symbol` → `iconUrl`,
tradable first, largest `marketCap` first; futures tokens without a CoinGecko market cap such as
AIA, AGT, AIO), then CoinGecko pages 5–10 (ranks 1001–2500) for the remaining gaps (e.g. AIN, LUNA), and is
kept for **24 h** (CoinGecko terms; `MAP_TTL_MILLIS`). TradFi pairs never take a CoinGecko logo (`pickTradFi`, key
`tradFiKey`). Every CoinGecko page after the first waits 6 s (the keyless limit otherwise
answers 429); a 429 is retried once after `Retry-After` (1–60 s). If any source fails, the known
entries are kept (`withKnown`) and the list expires after **6 h** (`savedAtFor`) instead of 24 h
(cache v5). Stock perps without a Binance bStock get
their logo from `bin.bnbstatic.com/static/stock/TICKER.png` (all TradFi tickers of the stored pair
lists) and, with «Show names», their name from the official Nasdaq symbol directory
(`nasdaqlisted.txt` + `otherlisted.txt`, weekly, `cleanStockName` strips «Common Stock» etc.) (retry after 1 h on failure). For privacy the
logos of **all** coins in that list are downloaded in one background sync (`startSync` /
`syncAll`, small variant first) — never one logo on demand, so the set of image requests is the same for every
install. The download runs in stages: Binance-listed symbols start before the first CoinGecko page, then the top
1000, then the rest; within a stage the order is the list order, the same for every install (Binance list, then rank;
never by watchlist or portfolio – iOS keeps it via `Lists.order` / the saved map file, `CoinLogoQueue.ordered`),
at most 6 at once and 4 per host (`CoinLogoQueue`, parity `coin_logos.json` › `queue`). Without a logo (yet) the
badge shows a coloured circle with the initials (`CoinLogoPlaceholder`: colour from FNV-1a of the symbol, 8 colours
per theme, text ≥ 4.5:1; parity `coin_placeholder.json`); a logo that arrives later fades in (220 ms, none with
reduced motion / animator scale 0). Images are scaled to 128 px and stored as PNG (Android `cacheDir/coin_logos`, iOS App
Group `coin_logos/`), a failed image is retried after **1 day**; display reads only from memory/disk, and a
`revision` counter (at most once per second while loading) lets badges retry. Every lookup in the map uses the
merge key (`CoinLogos.urlFor`: `normalize`, TradFi via `tradFiKey`), also for the starter logos («1000CAT» → CAT). Only HTTPS images from
`*.coingecko.com` and `*.bnbstatic.com` (`isAllowedUrl`; URLs of the former GitHub Pages list are
rejected); DEX pools (`DexScreener`) always show the initials circle. Android:
`CoinLogoRepository` → `LocalCoinLogoSource` (null when «In the app» is off) →
`CoinBadge` (`portfolio = true` → `LocalPortfolioCoinLogoSource`), sync started by
`CoinLogoSync` when `CoinLogoUse.needed` (app, widgets, or portfolio with the tab on; app
start/foreground, switch on, portfolio tab on, pull-to-refresh or «Refresh» in the watchlist). Logo switch defaults: «In the app» on, «In the portfolio» on (matters
only while the Portfolio tab is on — off by default), «In widgets» off; widgets via
`WidgetCoinLogos` (24 dp bitmaps). iOS: `CoinLogoStore` (actor, `syncAll` from
`AppData.startCoinLogoSync`) → `CoinBadge` (`coinLogosEnabled` environment,
`CoinLogoRevision`); widgets via `WidgetLogos` (disk only) and the `widgetLogos` environment.
The «Coin logos» settings page shows «Powered by CoinGecko».

* Never add an own logo list, pack or mirror of third-party images or names; load them on the
  device from the original image servers, and only as the full list (never one coin on demand).

**Manual reloads.** Pull-to-refresh, «Retry» and the small «Refresh» action under the
altcoin season force a reload, but slow, expensive areas (altcoin count, Bitcoin indicators,
on-chain values, cycle comparison) reload by hand at most every **5 minutes**
(`CycleCachePolicy.manualMinInterval` / `MANUAL_MIN_INTERVAL_MILLIS`); within that window
the stored value stays. The altcoin count details show «As of 14:05» from the stored
timestamp; its «Refresh» stays disabled until 5 minutes have passed.

**Freshness of watchlist prices.** One threshold for the status pill, the stale price colour, the red
time, the word «outdated» and the screen-reader sentence: `OutdatedRule.afterMillis` (live mode:
2 min, otherwise 3 × the interval, at least 15 min) — Android `WatchlistViewModel.staleAfterMillis`
= `outdatedAfterMillis`, iOS `AppData.staleAfterMillis` = `outdatedAfterMillis`, widgets the same
(`WidgetOutdated`). A stale price is drawn in `onSurfaceVariant` (Android
`colorScheme.onSurfaceVariant`, iOS `AppColors.onSurfaceVariant`, ≥ 4.5:1 in light and dark), not
faded with 50 % alpha. There is no live service: Google Play does not allow a `dataSync` foreground service for a
permanent price ticker, so prices are live only while the watchlist is open ([section 14](#14-live-prices-websocket)).

**Source and age.** Every row under «Context» and «Data» (except the calendar-based halving)
ends its secondary line with the provider that actually delivered the value and its age, e.g.
«Greed · alternative.me · today 02:00» or «CoinGecko · 3 min ago» (`DataFreshness` /
`DataStamp`, both platforms). Sources with a fallback chain report the one that answered
(candles: Binance / Binance.US / Coinbase / Bybit; Bitcoin indicators add «Coin Metrics» when
on-chain values are present; gas: the Ethereum RPC host and mempool.space). The provider is
stored next to the timestamp in the cache file (`src` on Android, `provider` on iOS; a
file without it simply shows no provider). Age: < 1 min «just now», < 60 min «N min ago»,
today «today HH:MM», otherwise the date. Older than **3 × TTL** → the age is shown in the
warning colour and screen readers add «outdated».

**Widget «What stands out».** The Fear & Greed line in the widget never fetches: Android
takes the newer of the Market tab entry and the Pulse input, iOS the value stored in the
App Group by every successful Fear & Greed fetch (`FearGreedShared`). Older than 24 h → no
line.

Changing a TTL: update both platforms, `CycleCachePolicyTest` (Android),
`MarketTabCacheTests` (iOS) and this table.

## 14. Live prices (WebSocket)

While the **watchlist is on screen and the app is in the foreground**, prices of the
visible pairs (the selected group) come from the exchanges' public WebSocket tickers instead
of REST polling. Setting: *Settings › Price updates › «Update frequency»* — `UpdateMode`
(`SPARSAM` «Economical» = default and recommended, `LIVE_OFFEN` «Live while open» = labelled «needs more data and battery»; never call Live the recommended choice); `liveWebSocket` is derived from
it. These two are the only values, stored and backed up under the enum name (`update_mode` / `updateMode`): an
unknown or missing stored value reads as the default; a backup with an unknown value leaves the setting unchanged. Tests: `UpdateModeTest`. Background refresh (WorkManager, BGTask) and widgets never open a
WebSocket — they keep using REST.

**Supported exchanges** (only documented public endpoints, no keys):

| Exchange (adapter key) | Endpoint | Subscription | Fields used | 24 h change |
|---|---|---|---|---|
| Binance (`Binance`) | `wss://stream.binance.com:9443/ws` | `SUBSCRIBE` `<symbol>@ticker` | `s`, `c`, `P` (%), `E` | yes (rolling) |
| Binance USDⓈ-M (`BinanceFutures`) | `wss://fstream.binance.com/ws` | same; perpetuals only, no COIN-M (`2:` prefix) | same | yes |
| Bybit spot / linear (`Bybit`, `BybitFutures`) | `wss://stream.bybit.com/v5/public/spot` · `/linear` | `tickers.<symbol>`, ≤ 10 args per message | `lastPrice`, `price24hPcnt` (fraction), `ts`; linear sends deltas | yes |
| OKX spot / swap (`Okex`, `OkexFutures`) | `wss://ws.okx.com:8443/ws/v5/public` | channel `tickers`, `instId` | `last`, `open24h`, `ts` | yes (from `open24h`) |
| Coinbase Exchange (`Coinbase`) | `wss://ws-feed.exchange.coinbase.com` | channel `ticker` | `price`, `open_24h` | yes (from `open_24h`) |
| Kraken (`Kraken`) | `wss://ws.kraken.com/v2` | channel `ticker`, symbols `BTC/USD` | `last` | no – as with REST, candles |

All other exchanges keep REST polling at the configured interval.

**Architecture** (same on both platforms; pure logic shared via `testdata/parity/live_feed.json`):

* `LiveFeed` – pure: `LiveExchange` (endpoint, stream symbol per pair, subscribe messages,
  ping), `LiveParser` (message → ticks; acks, pongs and heartbeats are ignored), `LivePlanner`
  (connections: ≤ 200 symbols each, at most 3 per exchange; paused exchanges and pairs no
  longer traded are skipped), `LiveBackoff` (reconnect after 1, 2, 4 … 60 s; HTTP 429/418 → 5 min),
  `LiveRules`, `LiveBuffer`.
  Android: `android/domain/…/domain/live/LiveFeed.kt` (with a tiny JSON reader, `LiveJson.kt`,
  so the parsers run in plain unit tests); iOS: `ios/Shared/Services/LiveFeed.swift`.
* Connections: Android `data/live/LivePriceStream.kt` (OkHttp WebSocket, protocol pings every
  20 s), iOS `ios/App/Services/LivePriceStream.swift` (actor, `URLSessionWebSocketTask`). Bybit,
  OKX and Kraken additionally get their text ping (`{"op":"ping"}`, `ping`, `{"method":"ping"}`);
  without any message for 60 s those connections are reopened. Every connection without a single price
  **3 min** after subscribing (rejected subscription; Binance and Coinbase stay open on protocol pongs) is
  reopened as well, with the growing pause (`LiveRules.silentSubscription`).
* Lifecycle: the watchlist reports its visible pairs and whether it is shown (Android
  `LifecycleStartEffect`, iOS `onAppear`/`onDisappear`); app foreground (Android
  `AppVisibility`, iOS `scenePhase`), the setting and `NetworkStatus`/`ConnectivityMonitor`
  gate the stream. Leaving the watchlist closes the sockets and saves what is pending.
* Display: live quotes are laid over the stored pairs at most twice per second
  (`LiveRules.UI_INTERVAL_MILLIS`); the status pill shows a small «Live» badge with a pulsing
  dot (static with Reduce Motion) only under the rule below, the refresh report lists «Live: Binance, Bybit …».
* Storage, alarms, widgets: pending quotes are written in one batch every 10 s through
  `PriceRefresher.applyLive` (iOS: `AppData.applyLive` → `PriceRefresher.refresh(liveQuotes:)`)
  – the same path as a refresh, so alarm crossings, cooldowns and re-arming are shared and
  nothing is notified twice. No price announcements and no volume alarms in that path (volume
  needs hourly candles; REST checks it). Widgets are redrawn at most once a minute and when the
  watchlist is left. If a refresh is running, the batch waits for the next round.
* Time of a live quote: only a message that carries a new price refreshes the pair's time. A
  Bybit linear delta without `lastPrice` (e.g. only funding or volume) updates nothing, so a
  quiet pair can still turn «outdated».
* 24 h change: the stream's value is used only with the basis «Last 24 h», a matching change
  stamp and an exchange whose stream value is rolling (`LiveRules.chooseChange`); otherwise the
  stored value stays until the next REST refresh.
* REST while live: a pair with a live quote younger than 30 s is skipped by the REST refresh
  (`LiveRules.skipRest`, rolling basis only); if the stream stalls, the pair falls back to REST
  automatically.
* «On a socket» is decided **per pair** from its own last live price (`LiveRules.liveIds`, ≤ 30 s), not per
  exchange: with several connections of one exchange a disconnected one takes only its own pairs out.
* «Edit pair»: every buffered quote carries the pair it was subscribed for and is saved only while that is still
  the entry's pair (`LiveRules.samePair`, Android `LivePriceApplier.applyLive`, iOS `AppData.applyLive`) —
  never by comparing times. The stream drops buffer and last-live time of a changed id and trims both to the
  subscribed ids on every sync (Android `LiveCoverage`, iOS `tickAt`).

**Never show «Live» unless every visible pair is live** (`LiveStatus` —
Android `domain/watch/LiveStatus.kt`, iOS `ios/Shared/Util/LiveStatus.swift`, tests `LiveStatusTest` /
`LiveStatusTests`):

* «Outdated» is decided **per pair**: a pair on a live socket is measured at its **last tick**, not at
  its last price change (`LiveStatus.freshnessTime`: at most `LiveRules.FRESH_MILLIS` old, applied to
  row, pair page, pill and the «only outdated» view by Android `LiveOverlay.atHeartbeat` / iOS
  `Watch.atHeartbeat`; the stored `lastUpdate` is unchanged), so a quiet pair on a healthy stream is
  never stale; the threshold on the socket stays **2 min** (`LIVE_STALE_MILLIS`). Without ticks for
  30 s the pair leaves the socket set and, like every other pair — also in «Live while open», where exchanges without
  WebSocket are not polled on a timer while the watchlist is open —, uses the polling threshold
  **3 × background interval, at least 15 min** (`pollingStaleAfterMillis`). There is no permanent live
  service in this decision; do not add one.
* The pill says «Live» only when the view has at least one traded, active pair, **all** of them are
  on a socket and none is stale (`allLive`). Mixed views show the usual sentence («Current ·
  12 min ago»), and the time is the newest **polled** pair (`shownTime`), so live pairs do not make an
  old poll look fresh.
* Widgets are never live: they always use the polling threshold (`WidgetFreshness.afterMillis`).
* Texts must stay honest: «Live while open» is described as «needs more data and battery» (foreground only),
  «Economical» is the default and the recommended choice.

Tests: `LiveFeedTest.kt` / `LiveFeedTests.swift` (parser samples per exchange, symbols,
subscribe messages, plan, backoff, rules, buffer). Adding an exchange: add a `LiveExchange`
case on both platforms, sample messages to `live_feed.json`, and a row to the table above.

## 15. UI rules for numbers

Prices, changes and their symbols look the same on every screen and on both platforms. Use the
shared building blocks instead of styling numbers by hand:

* **Coloured text for every signed value – no pills.** There are no pills for changes anywhere,
  neither in the app nor in the widgets. Every % change and every signed amount (profit/loss,
  funding rate, currency effect, counters such as «↗ 233 rising») is coloured text directly on the
  surface (card, row, sheet), without a filled background. The colour comes only from `PillTone`
  (Android `ui/components/ChangePill.kt` with `pillContent(tone)`, iOS `App/Components/ChangePill.swift`
  with `PillTone.content`): `UP` / `DOWN` in the price colour of the chosen scheme (with swap and high
  contrast, ≥ 4.5:1 or 7:1 on all surfaces), `NEUTRAL` grey for 0, unknown or deactivated, `AVERAGE`
  in the yellow warning text colour (`AppColors.warningText`, ≥ 4.5:1) for neutral figures such as the
  average «Ø +0.32%». `ChangePill` (% change) and `TonePill` (any other text) keep their names but
  render text only; pick the tone with `PillTone.forPercent` / `PillTone.forValue`, never a colour by
  hand. Size: `SMALL` in list rows, `MEDIUM` in cards, headers and sheets. Do not add a background,
  capsule or border to a change.
* **Arrow plus signed value.** A change is the arrow followed by the signed value in the same price
  colour: «↗ +0.21%», «↘ −2.77%», «↗ +12.40 USDT»; 0 and unknown show no arrow and stay grey. The
  minus is always the real minus sign U+2212, never a hyphen – also in notifications, widgets and
  alarm texts (`PriceFormat.changePercent`, `PriceFormat.signedPercent`). Below half a cent an amount has no sign
  (never «-0.00»); sign and number are one LTR isolate (`BidiText.ltr`) so RTL languages keep the sign in front – also
  value-history labels and axis, tax sums (`PortfolioFormat.signedMoney`: sign only for a loss, cases
  `signed_amount.json`) and the watch apps (Android `WatchFormat`, iOS `WatchPercent`: same table as `PercentText`,
  cases `percent_text.json` incl. `watch_change`). The funding rate is coloured
  text without an arrow. Counters carry an arrow («↗ 233 rising», «↘ 295 falling»); the average
  «Ø +0.32%» uses the warning text colour.
* **Time window small and grey.** The period after a change («· 24h», «· 7d», «· 1Y», «· 1J») is small
  secondary grey text, never in the price colour (e.g. «↗ +41.71% · 24h · Updated …»,
  «↘ −38,412.95 USDT −27.85% · 1Y»).
* **Buttons on the accent colour** use the theme's normal contrast colour (`AppColors.onAccent` /
  iOS `\.onAccent`); there is no user setting for it.
* **Diagonal arrows.** Up/down is always ↗ (U+2197) / ↘ (U+2198) (`PriceFormat.changeArrow`,
  iOS `arrow.up.right` / `arrow.down.right`); no ▲/▼ for price moves. The arrow follows the
  sign, never the colour swap. ▲/▼ remain only for markers that are not price moves (e.g. sort direction).
* **Price digits.** `PriceFormat.price` / `formatDouble`: below 10 up to four significant digits,
  from 10 upwards always two decimals («83,142.00»), on both platforms.
* **Quote currency.** A price followed by its quote currency uses `PriceWithQuote` (one text:
  `withSmallQuote()` / `PriceWithQuote.text()`; widgets `quoteText()` / `Text.quoted`): the number in
  the normal style, «USDT» small and grey like the exchange name.
* **Fold-out chevron.** Anything that expands uses the one accent-coloured chevron that turns
  when opened (Android `ExpandChevronIcon` in `ui/components/ExpandToggle.kt`; iOS `chevron.down`
  in the accent colour, rotated 180° when open); buttons with a text label use
  `ExpandToggleButton` / `ExpandToggleLabel`.
* **Screen readers** get words, not symbols: pass a `contentDescription` / accessibility label
  such as «up 2.35%» when the change text is only symbols and digits. The watchlist row sentence says every
  icon of the row, including the price-notification bell («Price notification on/off»,
  `A11ySpeech.priceNotificationOn` = pair switch AND master switch, the same rule as the plain/struck-through
  bell), before the alarms.
* **«Powered by CoinGecko».** Everything that shows CoinGecko data carries the line
  `DataFreshness.POWERED_BY_COINGECKO` (iOS `poweredByCoinGecko`; brand name, never translated) at
  ≥ 10 sp/pt, readable: under the CoinGecko-based Market cards (`MarketCardParts`), on the «Coin logos»
  settings page, in About and in the licences. A new screen with CoinGecko data needs the line too
  (CoinGecko API terms § 4.2.3).
* **Stale is a colour, not transparency.** Stale watchlist prices use `onSurfaceVariant`; do not go
  back to fading the price with alpha to mark age (it falls below 4.5:1). The same holds
  for disabled watchlist rows (texts in `onSurfaceVariant`, «Binance · disabled» says it in words; only
  logo and symbols keep a reduced opacity) and for a stale portfolio total (amount in
  `onSurfaceVariant`, «Prices from … · outdated» in the warning colour). The mini chart of a
  stale row follows suit: full shape in the same `onSurfaceVariant` grey as the stale price, no gradient fill,
  no up/down colour (`Sparkline(stale =)`, iOS `WatchlistSparkline(stale:)`; no reduced opacity) — old data must
  not look «nice». The screen-reader sentence still describes the chart.
* **Exchange time vs fetch time** (`PriceFreshness` — Android `domain/watch/PriceFreshness.kt`,
  iOS `ios/Shared/Util/PriceFreshness.swift`; widgets `WidgetFreshness`; parity
  `testdata/parity/price_freshness.json`). Many exchanges (Kraken, Gate.io, Hyperliquid, bitFlyer, the
  Gemini bulk request …) send no time with their ticker, so the fetch stamps «now» and a last price
  that has not moved for hours would look fresh. Therefore every pair stores three times:
  `lastUpdate` = last successful fetch (unchanged meaning), `exchangeTime` = the exchange's own time
  of the price (`Ticker.exchangeTime`, null when the exchange sends none) and `priceChangedAt` = when
  the price last changed (Android columns of `WatchEntity`; iOS optional fields in
  `watchlist.json`). Age and «outdated» use `freshnessTime` = exchange time, else the fetch time.
  Without exchange time, a price that has not changed for longer than max(1 h, the outdated
  threshold) shows «Price unchanged since HH:MM» instead of a fresh time — in the row and in the
  widgets (not on pairs that are already outdated, deactivated or failing). Never stamp a price
  with the fetch time and present it as the exchange's time; alarms use none of these fields.
  The healthy watchlist row shows only the exchange (the header names the age); «last trade … ago» follows only
  when the exchange's trade time is more than max(refresh interval, 15 min) before the fetch
  (`rowTradeAgeTime`, `tradeAgeShownAfterMillis`). The screen-reader sentence keeps the age on every row.

## 16. Portfolio and backup rules

Rules that are easy to break by «simplifying» code. Keep Android and iOS identical and extend
the tests named here.

* **Exact ledger, floating-point valuation.** Transaction amounts, buy prices and fees are exact
  decimals: Android `BigDecimal` (`PortfolioDecimal`, `MathContext.DECIMAL128`, at most 18 decimal
  places, dust below 1E-20 counts as 0), iOS `Decimal` (`PortfolioDecimal.swift`;
  divisions and pro-rata products are rounded **once** to **34 significant digits**, half even, like `DECIMAL128`,
  `significantDigits`, so both platforms give the same average cost). `Decimal` itself computes with 38 digits, and
  rounding that result again to 34 differs in the last digit now and then (376761.99 / 41.47463084), so iOS
  `divide`, `multiply` and `divide(a, b, by: c)` (= Android `a.multiply(b).divide(c, MC)`, product exact) compute
  exactly from the integer mantissas (`PortfolioDecimal+Exact.swift`); never write `divide(a * b, c)` on iOS — `a * b`
  is rounded to 38 digits once it is longer. Every product in the money paths (amount × price, fee × price, holdings ×
  price, value × FX rate, tolerances) is rounded once to 34 digits on both platforms: Android `a.multiply(b,
  PortfolioDecimal.MC)`, iOS `PortfolioDecimal.multiply(a, b)` — never a plain `*` (Kotlin) or `*` on `Decimal` (Swift),
  since Android would keep all digits and iOS only 38. Plain `+` and `−` on `Decimal` stay exact only within 38 digits
  (Android: unlimited); a sum with more significant digits can differ from Android in the 36th–38th digit – far below
  any shown amount (owner decision: documented, not rebuilt). Fixture `portfolio_decimal.json`. Holdings, cost basis and realized gain are summed exactly
  (`holdingsExact`, `costBasisExact`, `realizedExact`), so 1 satoshi (0.00000001 BTC) never drifts;
  the unrealized gain of a position is computed as an exact decimal too. Only the
  valuation with market prices (Double) is floating point. When only part of a holding has a
  purchase price, the average is labelled as the average of that part («Avg. of X with purchase
  price», `portfolio_avg_partial`), never shown as the average of the whole holding. Backup import
  applies the same number limits on both platforms (`PortfolioDecimal.parse`, iOS `PortfolioDecimal.withinLimits`:
  below 10³⁰, at most 40 decimal places; absurd values count as invalid entries); amounts, fees and values are read
  rounded to 18 decimals like saving (Android `PortfolioJson.amountOf`, iOS `BackupManager.storedAmount`): an amount or
  received amount that becomes 0 counts as an invalid entry, a fee that becomes 0 is dropped (fixture
  `portfolio_stored_fields.json` › `backupAmounts`). Saving refuses a transaction whose
  numbers would not read back (Android `storable` after `cleanForSave`, iOS `AppData.cleanedPortfolioTx`, same rule as
  the CSV preview `unsavableReason` → `BAD_NUMBER`): amounts, fees and values ≥ 10³⁰, or a price > 0 that would store as
  0. Never parse amounts through `Double` (`PriceFormat.parseDecimalAmount`,
  `ThresholdParser.parseDecimal`). Android stores them as TEXT
  (`PortfolioDecimalConverters` with the amount rule; the price columns `priceUsdt` and `feeCoinPriceUsdt` carry the
  field converter `PortfolioPriceConverters` with the price rule — never one rule for every column); backup and portfolio files carry only the exact text fields `amountText` /
  `priceText` / `feeText`. Amounts, fees and swap values are stored with 18 decimals (`PortfolioDecimal.text`); prices
  (`priceText`, `feeCoinPriceText`, also in `CsvRestoreState`) keep **34 significant digits, at least 18 and at most 40
  decimals** (`PortfolioDecimal.priceText`), so a BABYDOGE price of 1.3717333…E-9 or 4E-19 survives saving instead of
  shrinking or turning into a free buy (fixture `csv_import.json` › `priceText`).
* **Average cost with fees.** No FIFO. A fee in USDT (`feeUnit` quote) raises the cost of a buy and
  lowers the proceeds of a sell; a fee in the coin reduces the amount received on a buy and adds to
  the amount removed on a sell.
* **Gain only where it is known.** «Since purchase» sums only coins with both a cost basis and a
  current price; the rest are listed (`missingCostCoins`) and named in the hint. Period gain (today,
  7 d, 1 y) = value at the end − value at the start − net inflows in the period
  (`PortfolioPeriodChange.gain`, `todayAdjusted`), so a purchase is not a gain. Holdings in this
  step are summed exactly (`BigDecimal` / `Decimal`), like the position. Tests:
  `PortfolioLedgerTest` / `PortfolioLedgerTests`.
* **One coin never erases a period.** «Today / 7 d / 1 y» are non-strict: a held coin without a
  usable start close (no daily candle, or the identity check below fails) is skipped and listed in
  `TodayChange.skipped` / `PortfolioChange.skipped`; the card names it (`ledger_period_without`),
  the other coins still count. Same rule as «Since purchase». The widget shows 7 d / 1 y only when
  nothing was skipped (a smaller widget has no room for the hint). Never go back to «one missing →
  whole period —». «Today» follows exactly the same skip rule as 7 d: skipped coins are
  named on the card, the widget says «Today without …» (`widget_portfolio_today_without`). The 24 h
  history uses hourly prices for stablecoins too (only USDT is flat at 1, others need their own hourly
  price). The value history has two modes («Value» | «Gain/loss», remembered): the gain
  curve is P&L(d) = value(d) − net invested(d) with the cash flows of `PortfolioLedger.flow`, so buys and
  sells never move it; the header always shows «Value X · Gain Y (Z %)» with Y = P&L over the range and
  Z = Y ÷ (start value + gross inflows in the range; sales not subtracted – the same base as «Today» and the rows), «Since 1st buy» ÷ all money put in; base ≤ 0 → amount only.
  Coins with unpriced trades or missing day prices are left out and named
  (`portfolio_history_pnl_without`). The old «value now vs. range start» figure is never shown as gain.
* **One «Today».** The snapshot computes today's change once: `todayFromCoinChanges` (coins without
  a day change are left out, never filled flat), then `todayAdjusted` removes today's trades
  (Android `PortfolioSnapshotUpdater.withHonestToday`, iOS `PortfolioWidgetStore.compute`). Card,
  widget and portfolio alarm all read `snapshot.changeAmount` / `changePercent`; the view model
  must not adjust again, or the three numbers drift apart. Rolling, each coin's window starts at the time of
  its comparison price (hourly price 20–25 h ago, else the stored sample 20–48 h ago;
  `PortfolioWidgetSeries.changeSince` → `todayAdjusted(coinStart)`), so a buy after that price is a flow, never a
  gain. «Since 00:00» the same with the day-start price, which may be up to 90 min before 00:00
  (`changeSinceDay`: hourly price, else the stored day-start sample). The widget's fallback line (`valueHistory`) leaves coins without a valid current price out of every point,
  like the hourly line.
* **Portfolio alarms in another currency.** A value alarm keeps the currency it was created in.
  When the snapshot is in another currency (conversion currency changed), the value is
  `totalUsdt × usdTo[currency]` (`PortfolioReading.usdTo`, filled from `FxRateSource`; iOS
  prefetches with `prefetchPortfolioAlarmRates`). No rate → no check this time, never a wrong value.
  The repeating «today ±x %» alarm re-arms only after the change is back by
  `changeRearmMargin` (20 % of the threshold, at least 0.5 points); value alarms keep the 0.2 %
  hysteresis.
* **A fired portfolio alarm is stored with compare-and-set** (Android):
  `PortfolioAlarmDao.markTriggered` updates only if the alarm is still enabled with the same
  reference, kind, threshold, repeat flag and currency as when it was read; 0 rows → nothing is
  notified (the user's edit wins), like `WatchDao.markAlarmTriggered`. With the portfolio lock on,
  the notification has a neutral body without amounts (both platforms).
* **Fees at the edges.** A sell with nothing left changes neither «realized» nor the period gain
  (its USDT fee is ignored on both). `oversold` counts amount + coin fee, and so does the sell
  warning in the transaction sheet («Amount plus fee is more than your holdings»,
  `portfolio_sell_exceeds_fee`). A USDT fee on a buy
  without a price belongs to the unknown cost of that part.
* **Restore never drops data silently.** Portfolio transactions and alarms that cannot be read are
  counted (`PortfolioJson.parseTxs/parseAlarms`, Swift `BackupManager.invalidEntries`) and named
  in the confirmation (`backup_restore_invalid`); `restore(…, acceptInvalid)` stops before replacing
  anything if the file holds more invalid entries than the user confirmed. Every backup read goes
  through `BackupLimits` (16 MB, JSON nesting ≤ 32), also the Android system-backup copy. Portfolio
  notes are at most 120 characters (`NOTE_MAX` / `Watch.noteMax`). The restore from a
  **system / device backup** counts invalid rows too and shows them once when the portfolio is opened
  (`portfolio_system_restore_invalid`; Android `PortfolioRestoreNotice` / `SystemRestoreNoticeDialog`,
  iOS `PortfolioRestoreNotice`, remembered per file so the same file is not reported again).
* **An unencrypted backup cannot weaken protection.** Importing a backup without a
  password shows a warning before replacing (`backup_restore_unencrypted_warning`: check that it comes
  from you; it names the four protected settings with their exact labels — `BackupUnencryptedWarningTest`).
  Its settings are applied, except four changes that would lower protection (`BackupRestorePolicy`, Kotlin and
  Swift): it never turns the portfolio lock **off** — if the lock is on, it stays on and the result says so
  (`appLock`, `backup_restore_app_lock_kept`, `appLockKept`); it never turns «Hide amounts» **off**
  (`hideAmounts`); it never turns «Portfolio in system backup» **on** (`systemBackup`); and it never sets,
  replaces or removes the own Bitcoin server (`AddressServer.restored`; the result names a kept server,
  `addressServerNote` KEPT → `backup_restore_server_kept`). An encrypted backup restores all four as stored
  (the lock turns on only if the device can authenticate; an invalid server value is ignored) — but never
  silently, because a password proves no origin: the result names an own Bitcoin server that was newly set
  where none was or replaced an existing one, with its host name (`addressServerNote` SET/CHANGED,
  `addressServerHost` → `backup_restore_server_set`, `backup_restore_server_changed`), and «Portfolio in system backup» switched on, «Hide amounts» or the portfolio
  lock switched off by the backup (`loweredProtection` → `backup_restore_system_backup_on`,
  `backup_restore_amounts_shown`, `backup_restore_app_lock_off`);
  each of them shows the result as a dialog (`BackupResultPresentation.asDialog`).
* **iOS `portfolio.json` is never overwritten blind.** `PortfolioStore.loadChecked` tells a missing
  file (empty portfolio) from an unreadable or partly unreadable one (`PortfolioFile.dropped`).
  `AppData` re-reads before every change (`currentPortfolioFile`) and, if still unreadable, keeps a
  copy (`preserveUnreadable` → `portfolio.unreadable.json`) before writing; if not even the bytes are
  readable (data protection), nothing is written. Saves report failure. User edits (save, delete, undo, clear,
  undo import) go through `AppData.editPortfolio`: value copy on the MainActor, encode/seal/write/sort and the widget
  off the main thread (`setPortfolioFileInBackground`), one after another (`portfolioEditGate`, the import's write
  takes it too), the list changes only after the write (`present`) and the caller gets the result (`async`).
  The synchronous `setPortfolioFile` is left for reset and backup restore/rollback.
* **Alarm save and notify are one step.** Android marks an alarm as triggered and shows the
  notification inside one `withContext(NonCancellable)` (`RefreshEffects.report`, portfolio alarms);
  one-time alarms without a deliverable notification stay armed (`canShowAlarm`,
  `canShowPortfolioAlarm`).
* **Live stream stop.** `stopAll` clears its state before it suspends to save (Android) or checks a
  generation counter after `await flush` (iOS actor), so a newer `reconcile` keeps its session.
* **Fresh share instead of the oldest coin.** A snapshot (widget, «today», value history) is recorded when at least
  **80 %** of the value has prices younger than the update limit (`PortfolioPriceFallback.freshShare`,
  `mayRecordShare`, `MIN_FRESH_SHARE = 0.8`), checked per coin from `PortfolioPrices.times`. Portfolio alarms use their
  own, stricter rule: they check only while the value with old prices (plus unconfirmed token balances and unverified
  prices) stays below **1 %** of the total (`PortfolioAlarmLogic.staleMaterial`, `IMMATERIAL_SHARE = 0.01`). One
  rarely traded coin must not block the alarms for the whole portfolio.
* **Price from the entered exchange.** `PortfolioExchangeRoute` (both platforms, tests
  `PortfolioExchangeRouteTest(s)`): a coin whose transactions with an exchange all name the same
  spot exchange is valued at that exchange's spot price – pair order USDT, USD (÷ USDT/USD market price,
  `toUsdt` `usdtUsd`; default 1), then fiat via the FX rate and the same USDT/USD price (`QUOTES`); other
  stablecoins are never assumed at 1. Mixed exchanges on one coin
  → Binance and `mixedExchange`; exchange unreachable / no pair / no FX → Binance and
  `exchangeFailed`; both are named on the card. Value history, 7 d, 1 y and the widget chart use the
  **route exchange's own daily candles** first (`PortfolioHistorySource`, see «Portfolio history from
  the route exchange first» below); Binance daily closes scaled by own price ÷ Binance price
  (`scaleCloses`, `PortfolioPrices.binance`) are only the fallback – never mix two exchanges' levels in
  one series. **Scaling needs the same token:** `scale` drops the coin
  when `PricePlausibility.sameToken(own, binance)` fails (a ticker that is another project on
  Binance), and the chart's open/high/low go through `scaleRanges` with the same factor as the close.
  Never hand the chart scaled closes with unscaled ranges. Shared cases: `testdata/parity/portfolio.json`
  (`PortfolioParityTest(s)`, also positions and period gain). An FX failure sets the rate to none – never
  keep the previous value (`?: _fxRate.value`); `FxRateSource.cached()` returns nothing older than 24 h.
  A coin with a trade without price inside the 7 d window is skipped and named
  (`PortfolioPeriodChange.parts` in `gain()`), never counted with its fee only. Android resolves routes in `PortfolioPriceSource`
  (pair lists from `ExchangeRepository`, FX from `FxRateSource`); iOS resolves them in the app
  (`PortfolioRouteResolver`, `PairCache`) into `PortfolioExchangeRouteStore` (App Group), so the
  widget and background use the same prices. The card shows the sources in one line
  («Prices: Binance · USDT at market price» / `portfolio_price_source_routes`). Only the trading pair is sent
  to the exchange; a new route needs a privacy-policy check.

* **Two different plausibility checks.** `DayChange` (the % change) rejects a 24 h reference price
  more than **25 %** (`DayChange.MAX_PRICE_GAP`) away from the current price – the change then
  shows nothing rather than a wrong value. The cut-off export uses the same 25 % for fallback
  sources (`PricePlausibility.acceptSourceClose`). The portfolio value history and the «7 d»
  figure use only an **identity check**: a coin is dropped only if its latest daily close is
  outside **1/3 … 3** of the live price (`PricePlausibility.sameToken` / `closesMatchLive`,
  `TOKEN_FACTOR = 3`), i.e. most likely another token with the same ticker. A real day with
  30 % movement must stay in the history. Do not merge the two thresholds.
* **Realized profit/loss with buys without a price.** The share of a sale covered by priced
  buys is realized at the average cost of the priced buys; the unpriced share, and sells
  without a price, are counted as unknown (`realizedUnknownAmount`, `realizedIncomplete`).
  Show «+X USDT + —» (known part plus unknown) or «—» (nothing known), never a number that
  silently treats a missing price as 0. A fully sold coin always appears under «Closed», also at
  break-even or with an unknown result.
* **Only USDT is at par.** In the portfolio (header, positions, value history, cut-off export,
  widget, portfolio alarms) USDT is the valuation basis and always 1; every other stablecoin
  (USDC, DAI …) needs its market price like any coin (`PortfolioStables.price` returns `null`
  without one). No price → no value: the position shows «—» / «no price», it counts in «without
  N coins» and the total profit is unknown. In the value history and the cut-off export the same
  applies per day (no daily close → «without …»). Never reintroduce a silent 1 – a depegged coin
  would land in value and profit unnoticed. Tests: `PortfolioStablesTest` / `PortfolioNumbersTests`.
* **Identity check for candle prices.** A coin not on Binance spot first takes the price of a
  watchlist pair of the same coin on another spot exchange (`PortfolioWatchPair.pick`: active, spot,
  not Binance, quote USDT → USD → fiat, then the most recently updated) – that is the token
  the user chose. Only without such a pair does it get its portfolio price from
  the last hourly candle of the candle chain. That price counts only if it is within **1/3 … 3**
  (`PricePlausibility.acceptCandle` = `sameToken`) of an independent reference
  (`PricePlausibility.candleReference`): the median price of the same coin on the watchlist
  (USD-stable spot pairs, active, price at most 30 days old), otherwise the last good stored price
  (`PortfolioPriceFallback`). Fails → the candle is discarded and the last good price stays, shown
  with its own «as of» time (faded once stale); with no stored price the coin shows «—». Never let a
  failing candle overwrite the stored price. No reference at all → the coin has **no
  price** (`PricePlausibility.candleUnchecked`, `PortfolioPrices.unchecked`): the position shows «—»
  with «Price unchecked» (`portfolio_price_unchecked`) and the coin is named in the «without …» list
  – see «Unchecked prices never count as money». The reference is read from local data only – never
  add a request for it. Tests: `PricePlausibilityTest` / `PortfolioWatchPairTest` /
  `PortfolioNumbersTests`.
* **Unchecked prices never count as money.** A price whose token could not be confirmed
  (a candle price without any reference, a cut-off close without a matching current price) is never
  added to anything that looks like money: not to the total, «Since purchase», «Today», 7 d / 1 y,
  the value history, the widget, portfolio alarms or the cut-off CSV, and it is never kept as a last
  good price or used as a reference (`PortfolioPriceSource` leaves `uncheckedCoins` out). Show «—» and say
  why («Price unchecked» / «unchecked (no matching price)»). Never fall back to «better a price than
  none» – the same ticker on a fallback exchange can be another token.
* **«No market» is not «unchecked».** A coin Binance was skipped for (no checkable name,
  `PortfolioPriceIdentity` UNCHECKED) for which no source returned any price and nothing failed
  (`PortfolioPriceIssue.NO_PRICE`) is `PortfolioPrices.noMarket`: «—» with «No market»
  (`portfolio_price_no_market`), never money either. A candle price without reference and a Binance price
  with an unconfirmed token stay «Price unchecked»; a failed request shows «Price unavailable right now» instead (neither label).
  `PortfolioPriceIssue.nameUnchecked` splits the two. Tests: `BinanceBatchSplitTest` / `BinanceBatchSplitTests`.
* **Binance batch survives unknown symbols.** Binance answers `ticker/price?symbols=[…]` with HTTP 400 when
  one symbol is unknown. `BinanceBatchSplit` then halves the chunk down to single symbols (at most
  `EXTRA_REQUESTS` extra requests); a single symbol with 400 is «not listed» (not a failure, skipped for 6 h
  in memory), only other errors mark the batch failed. The batch is filtered by the coin list only when that
  list came from Binance (exchangeInfo or the Binance pair cache, stored source `coin_list_source`) and is at
  most a day old – read from memory or prefs, never fetched first. So the first refresh (fresh install, add
  sheet never opened, widget) prices every Binance coin. A 400 never blocks the host (`BlockedSources`).
  Fixture `binance_batch_split.json`.
* **Portfolio history from the route exchange first.** For a coin with a route
  (`PortfolioExchangeRoute`), `PortfolioHistorySource` loads that exchange's own daily candles
  (`OwnCandleDataSource`, at most 1000 per request, converted to USDT – fiat with the daily FX series,
  cached 12 h as `phisto_<COIN>`, iOS `App/Features/Portfolio/PortfolioHistorySource.swift`). If the
  own series does not reach back far enough, older days are Binance stitched to it
  (`PortfolioExchangeRoute.stitch`, `binanceBefore`). Without an own series the Binance series is used
  **only** scaled with a current reference (own price and Binance price now, `historyCloses`); is one
  missing (e.g. after a restart), the coin is left out and named («without …»), never the unscaled
  Binance series. A scaled coin is named under the chart and on the card
  (`portfolio_history_scaled`, `portfolio_period_scaled`). Same series for value history, 7 d, 1 y
  and the portfolio widget.
* **Fallback chain for historical daily closes.** `HistoricCloseChain` (pure, both platforms, parity
  `testdata/parity/historic_close_chain.json`, tests `HistoricCloseChainParityTest(s)`) fixes the order and the
  identity rules; only exchanges the app already talks to, **no new hosts** (all candles go through
  `OwnCandleDataSource`: its time limit, `BlockedSources` 451/403 block, 429/418 pause via `CandleHostPauses` /
  Binance group, 60 s cache – a constant 1000-candle request so all days of one pair share one fetch).
  - Single day (`HistoricPriceSource.dailyCloseUsdt` / `dailyClosesUsdt`: cut-off export / CH wealth 31.12., tax
    rows, «checked daily close» prefill): own route exchange (`resolveHistoryRoutes` on Android,
    `PortfolioExchangeRouteStore` on iOS; fiat quote ÷ FX of the day; identity 1/3 … 3 like Binance spot, since
    current portfolio prices come from it) → Binance group unchanged (spot, futures, Binance.US) → Coinbase unchanged
    (day window, any age) → Kraken (`XBTUSD`, `…USDT`) → OKX (`-USDT`, `-USDC`) → Bybit (`…USDT`, `…USDC`). Chain
    sources need the same name (`nameAllowed` = `TokenIdentity.fallbackSeriesAllowed`; a clash between the watchlist
    name and Binance's name disables the whole chain, as for charts) **and** their latest close within ±25 % of the
    current price (`decide` → `PricePlausibility.acceptSourceClose`). USD/USDC count as USDT here, as Binance.US and
    Coinbase always did. Only rejected closes → «unchecked»; no close anywhere → «no price». The result carries its
    source (`CloseResult.source`, e.g. «Kraken», «Binance.US»). Kraken serves 720 daily candles, OKX/Coinbase about
    600 via the candle sources, Bybit 1000 – older days stay without a close there.
  - Many days (tax export: fee rows, rewards without value, network fees, FR total value): `dayClosesUsdt` runs the
    **same chain per (coin, UTC day)** but fetches Binance/Coinbase candles per coin and source in spans
    (`SpanFetch` / `HistoricSpanFetch`: Binance ≤ 1000 days with `startTime`/`endTime`, Coinbase ≤ 300 days, only
    where days are wanted; the latest close once per series), reads routes and watchlist names once, at most 4 coins
    in parallel. Result per day must equal `dailyCloseUsdt` (same candle that starts that day, same fallback order) –
    pinned by `HistoricDayClosesBatchTest(s)` on a fake market. Never call `dailyCloseUsdt` in a loop over days.
  - Value history (`PortfolioHistorySource`): a coin with **no Binance series** (not listed, or Binance unreachable
    and nothing stored) and **no route** gets Coinbase → Kraken → OKX → Bybit daily candles (`fetchOwn`, USD ÷
    USDT/USD of the day) when the name matches and the latest close is within ±25 % of the current portfolio price
    (`acceptSeries`; reference from the price cache/stored prices only – no extra request, no reference → no new
    series). Cached 12 h as `phistc_<COIN>` (route = the chain pair), a miss is not retried for 1 h (memory only);
    the widget reads the same cache. `closesMatchLive` still checks 1/3 … 3 later. `PortfolioDailyPrices.chainSources`
    → `PortfolioHistoryUi.chainSources` → caption `portfolio_history_chain_sources` («Daily prices: Kraken (PEPE)
    (instead of Binance)», `PortfolioExchangeRoute.sourcesText`).
  - USDT/USD daily closes: Kraken `USDTZUSD` first; if Kraken misses a day of the last 600 (unreachable, gap),
    Coinbase `USDT-USD` fills it (`fillUsdtUsd`: Kraken wins unless it is outside the normal 0.5 … 1.5 and Coinbase
    is inside – `PortfolioStables.isUsdtNormal` only picks the source; a crash only one source has stays; cached
    `phisto_USDTUSDCB`). Only after both, a day falls back to «USDT = 1 assumed».
  - FX is untouched: `usdTo` (a weekend/holiday takes the last ECB business day ≤ the date) and `usdToSeries`
    (from `LEAD_DAYS` = 7 days earlier, gaps filled with the previous rate by `PortfolioHistoryFx.rateOn`) do not use
    the chain. Stablecoin rules (`PortfolioStables`) are unchanged.
* **Prefill only what is true for the chosen date.** The add-transaction sheet prefills
  the current price only for a transaction dated **today**. Choosing another date clears a prefilled
  (untouched) price and shows «Enter the price on the purchase day – leave empty if unknown»
  (`portfolio_tx_price_past_hint`; `PortfolioTxForm` on both platforms, tests
  `PortfolioTxFormTest(s)`); a price the user typed stays. Any other prefilled field follows the same
  rule: never prefill a value that is only true for another date.
* **Stablecoins in the watchlist ≈ line.** Only USD and USDT are 1 (`CurrencyConversion.stableToUsd`,
  `stableNeedsMarket`); other stablecoins (USDC, DAI …) use the portfolio's cached market price
  (`PortfolioPriceSource.cached`), and without a valid one the row shows **no** ≈ value (no
  silent par). The ≈ line never starts a request of its own. FX rates are fetched again
  after **3 h** (`FxRateSource`, TTL = `CurrencyConversion.FX_STALE_MILLIS`); if the rate
  behind a ≈ value is older than 3 h, the line adds its time («· rate 08:15», `converted_fx_stale`,
  `staleFxTime`).
* **FX rates straight from the ECB (build 200).** No third-party mirror; `EcbRates` (Kotlin in `:marketdata`,
  Swift in `Shared/Exchanges`, parity fixture `ecb_rates.json`) holds addresses, parsers and the cross rate.
  - Current rate (`FxRateSource`): `eurofxref-daily.xml`, one request for **all** currencies (all go into the cache
    with the ECB date as `rateTime`); a currency the ECB lacks (BGN since 2026) or a failed request → open.er-api.com.
  - Forex pairs (`EcbReference`, name «ECB»): one request `eurofxref-hist-90d.xml` for every pair (bulk = all ordered
    pairs of the codes), newest value within 10 days before the newest ECB day, change against the previous ECB day;
    pair list from `eurofxref-daily.xml` (its codes plus EUR). XML only via SAX (`javax.xml.parsers`) / Foundation
    `XMLParser`; a body with DOCTYPE/ENTITY is rejected unread.
  - Historic rates (`HistoricPriceSource.usdTo` / `usdToSeries`): ECB Data Portal
    `data-api.ecb.europa.eu/service/data/EXR/D.<CUR>+USD.EUR.SP00.A?startPeriod&endPeriod&format=jsondata&detail=dataonly`
    – one request for target and USD over the whole range (no more yearly chunks; the Data Portal has no weekly
    thinning). The Data Portal often answers a first-time query only after 30 s with HTTP 504 (measured 5 Nov 2026:
    1 to 5 of 10 cold queries), so on failure: `eurofxref-hist-90d.xml` if the range starts at most 80 days back,
    otherwise / then `eurofxref-hist.xml` (all days since 1999, ~1 MB gzip, ~8 MB XML, read with only the needed codes,
    16 MB body limit, kept 6 h in memory per code set). `usdTo` takes the newest ECB day ≤ date with a USD rate within
    10 days back; BGN from 2026 = EUR × 1.95583 from the same response.
  - **Cross rates (same precision as up to build 199):** all ECB values are units per 1 EUR. Base EUR = the ECB value
    unchanged; any other base (USD → CHF, USD/JPY pair …) = quote / base rounded to **5 significant digits**
    (`EcbRates.round5`, like the former mirror's «%.5g»: floor(v × 10^k + 0.5) / 10^k with k = 4 − ⌊log10 v⌋;
    exact .5 ties may differ from «%.5g» in the last digit). USD → EUR = round5(1 / USD).
* **Forex: live fiat pairs and reference rates of three central banks (build 201).** The «Forex» chip of «Add pair»
  has two groups. «Live (exchange)» = Kraken and Bitstamp (`ForexLive` / `ExplorerForexLive`); opened from there the
  pair list is filtered to fiat–fiat pairs (both sides in the ECB currency list), otherwise they are ordinary pairs
  with alerts and the live stream (no adapter filters fiat pairs). «Reference rate» = the `FOREX` sources:
  - **Source per currency** (`ReferenceRates.assign`, parity fixture `reference_rates.json`): ECB (incl. EUR) >
    National Bank of Ukraine (incl. UAH) > National Bank of Poland (tables B and A, incl. PLN). Metals/XDR (XAU, XAG,
    XPT, XPD, XDR) and withdrawn ISO codes (`ReferenceRates.EXCLUDED`: VEF, ZWL, HRK, SLL, ANG, BGN …) never appear.
    The ECB keeps all ordered pairs (build 200); `NbuReference` / `NbpReference` (`BankReference`) list each of their
    currencies against USD and EUR only. Their pair lists are `pairListCombined` (ECB daily file, NBU list, NBP tables)
    so a currency never comes from two sources.
  - **Rates:** NBU `bank.gov.ua/NBU_Exchange/exchange_site?start=<Kyiv today − 7>&end=<Kyiv today>&json` (one
    request, all currencies, `rate_per_unit`); a publication is one `calcdate` (weekend days repeat Friday's), change
    against the previous publication. NBP `tables/A/last/15` + `tables/B/last/2`: X/PLN from table B with USD and EUR
    from table A of the **same `effectiveDate`** (never across days; missing table A → no rate), change against the
    previous week. Cross rates r(X) / r(quote) rounded with `EcbRates.round5`. Labels «Referenzkurs, täglich» (NBU) /
    «wöchentlich» (NBP, no «24h» on the pair page), no alerts.
  - **One request per source and refresh:** `MultiResponseSource` – the data source (`MultiResponses`, Kotlin
    `:data` / Swift `Shared/Core`) fetches all addresses together, joins them with U+001E and hands them to the
    adapter as response 0 of the single or bulk query; kept in memory until `validUntil` (NBU: midnight in Kyiv;
    NBP: Wednesday 12:15 Warsaw after the expected table, `nbpExpectedWednesday`; missing publication → retry after
    1 h).
  - **Source line:** under the price in «Add pair» and on the pair page «Quelle: EZB / Nationalbank der Ukraine /
    Polnische Nationalbank», plus «· umgerechnet» when the value is a cross rate or a computed change
    (`ReferenceSource.converted`; the ECB requires a clear notice for modified data). NOTICE and the licence screens
    name the three banks.
* **Stablecoins without a daily close («7 d»).** Elsewhere a stablecoin without a price has no
  value (see above). For the «7 d» reference only, a missing close counts as 1
  only while the live price is within **0.1 %** of 1 (`PortfolioStables.fallbackWithoutClose`,
  `MAX_FALLBACK_DEVIATION = 0.001`); otherwise the figure is unknown («—»). USDT is always 1.
  Do not replace this with the general par fallback – a depegged coin would produce a made-up
  change. iOS «Today» samples (every 30 minutes) store prices after the same stablecoin rule as
  Android (a stablecoin without a price is left out, never stored as 1); never store raw market
  prices there. Tests: `PortfolioPeriodChangeTest` / `PortfolioNumbersTests`.
* **Portfolio tab: off by default.** The default in `AppSettings` is `portfolioEnabled = false`; stored
  settings without the field read as that default. A backup carries the switch (`portfolioEnabled`). While the tab is off, the
  «Add to portfolio» action of a pair is hidden. Store texts and review notes must not promise a
  visible Portfolio tab on first launch. Tests: `PortfolioDefaultTest` / `SettingsLogicTests`.
* **Android main database is in the system backup.** `AppDatabase` runs
  `PRAGMA secure_delete = ON` on every open (`SecureDeleteCallback` in `di/LocalDataModule.kt`,
  as a query, failure only logged) so deleted or overwritten rows are zeroed instead of staying
  in free pages.
* **iOS portfolio alarms and the backup switch.** Portfolio alarm thresholds live only in
  `portfolio_alarms.json`, never in `watchlist.json`; that file (and its `.unreadable` copy) is excluded
  from backup while «Portfolio in system backup» is off. If it cannot be written (no key, write error), the
  alarms stay in memory (`SharedStorage.portfolioAlarmsPending`) and the next save retries
  (`SharedStorage.writeFiles`).
* **Re-lock closes backup dialogs.** When the portfolio lock engages again (session timeout,
  60 s in background), every portfolio-related sheet closes: the add-transaction sheets and the
  backup dialogs (Android export dialog and restore step; iOS export options, restore password
  sheet, «Replace?» confirmation). Work already confirmed (an export written, a restore
  writing) finishes; results that arrive after the lock engaged (a picked file, a finished
  password check) are discarded, never applied – the user taps again and unlocks. A new
  portfolio-related dialog must follow the same rule.
* **Backup key derivation.** New encrypted backups are format **v3**: Argon2id (RFC 9106,
  version 0x13) with **m = 65 536 KiB (64 MiB), t = 3, p = 1**, 16-byte salt, 32-byte key
  (`BackupCrypto.DEFAULT_KDF` / `defaultKdf`), implemented without a third-party library in
  `Argon2id.kt` / `Argon2id.swift` (line-by-line mirrors, verified against the RFC 9106 §5.3 vector).
  AES-256-GCM binds the canonical header as AAD: UTF-8 of the compact JSON
  `{"format":"cryptochecker-backup-enc","v":3,"enc":"AES-256-GCM","kdf":"Argon2id","m":…,"t":…,"p":…,"salt":"…","iv":"…"}`
  (fixed field order, no spaces, decimal numbers, salt/iv re-encoded as padded Base64 from the decoded
  bytes – `BackupCrypto.header`). A changed header fails the GCM tag and is reported like a wrong
  password. Reading caps `m ≤ 262 144` (256 MiB), `t ≤ 10`, `p ≤ 4` (minimum m = 8·p) so a crafted
  file cannot stall the device; out of memory is a clear error (`NotEnoughMemoryException` /
  `Failure.notEnoughMemory`, message `backup_not_enough_memory`), never a fallback to weaker parameters.
  v3 is the only encrypted format; a file with another `v` is not readable. The envelope is read as strictly as
  iOS `JSONSerialization` on both platforms (`BackupFormat.parse` / `BackupCrypto.envelope(from:)`): nothing but
  whitespace after the root object, `v`/`m`/`t`/`p` only as integral JSON numbers (`65536.0` counts, `"65536"`,
  `65536.9` and `true` do not – `BackupFormat.integral` / `BackupCrypto.integral`), `enc`/`kdf`/`salt`/`iv`/`data`
  only as JSON strings. Shared vectors
  (v3 exact ciphertexts incl. the production parameters, manipulated headers, envelope texts, RFC 9106) live in
  `testdata/parity/backup_crypto.json` – keep both platforms in sync with it. Changing parameters: new
  constant on both platforms plus a vector; never change how existing files are read. Passwords go to
  `BackupCrypto` as `CharArray` (Android) / NFC UTF-8 bytes (iOS) and are wiped after the derivation.
  Argon2id with 64 MiB takes a few hundred ms up to a few seconds – call encryption and decryption
  only off the main thread (Android the injected IO dispatcher, iOS a detached task; the «Continue» button
  stays disabled while checking). New backup passwords need at least **12** characters (`MIN_PASSWORD_LENGTH` / `minPasswordLength`, Unicode characters); `isWeak`
  (shorter than 12, only digits, one repeated character, a common sequence such as «12345678» or «password»,
  a keyboard walk, a decorated common word, a repeated chunk) **blocks** the export (`isPasswordAllowed`,
  `canExport`, `backup_password_too_weak`; the hint appears from the minimum length on). Reading is not
  affected: a file decrypts with its password, whatever its length. Cases in `testdata/parity/backup_passwords.json`.
* **iOS widget caches.** Portfolio widget data that is not needed for a restore (hourly prices,
  value history, candle attempts) is stored as files in the App Group and always excluded from
  backup (`BackupExclusion`), not in the App Group `UserDefaults`, which cannot be excluded.
* **Last known portfolio prices.** `PortfolioPriceFallback` (both platforms, `PortfolioPriceFallbackTest(s)`)
  keeps the last fetched USDT price per coin with its time in `portfolio_last_prices.txt` (Android
  `noBackupFilesDir`, iOS App Group excluded with `BackupExclusion`; at most 30 days old and 300 coins), so
  the tab shows the last value offline instead of 0.00. Prices are stale after 3 × the background interval
  (at least 15 min, `staleAfterMillis`; live mode does not count). **Stale prices are shown only** (faded,
  with the status pill) – never write them into the widget snapshot, the value history or the portfolio
  alarm checks (`mayRecord`). The file names the held coins: never put it into the app backup file or a
  system backup, and delete it with «Clear portfolio» (`clearStored`). A price fetch that started before the clear
  must not bring them back: `clearGeneration` is bumped under the store lock, a fetch keeps its prices and writes the
  file only if the generation is unchanged (iOS: `clearGeneration` is part of `CacheState`, the file is written
  inside `state.withLock`).
* **Status pill for Market and Portfolio.** Both tabs use the shared `DataStatusPill` (Android
  `ui/components/DataStatusPill.kt`, iOS `App/Components/DataStatusPill.swift`) with the watchlist's tones:
  green up to date, grey offline/loading, red outdated («Prices outdated · as of HH:MM», «Offline · as of
  HH:MM»). Offline the Market tab keeps its last data and starts no reload; expired data reloads when the
  device is back online. Do not build another pill for a new screen.
* **«Online» means a validated network.** Android `ConnectivityMonitor`: the default network needs `INTERNET` and
  `VALIDATED`, so a captive-portal Wi-Fi before login counts as offline (no refresh run, no error states). iOS
  `NetworkStatus` has no equivalent (`NWPath` reports only a route, no validation; finding a portal would need an own
  probe request) – there a run behind a portal starts and ends «not reachable». Known platform gap.

* **Ledger types.** All position, period, history and cut-off maths goes through
  `PortfolioLedger` (Kotlin/Swift, fixture `portfolio.json`, `PortfolioTxTypesTest(s)`). BUY/REWARD/DEPOSIT
  add holdings (with a price: cost basis += amount × price + fees; without: holdings without purchase price);
  SELL realizes gain; WITHDRAWAL removes holdings at average cost **without gain**; TRANSFER never realizes
  gain, a network fee in the coin only lowers the amount (its cost stays in the basis), and the destination
  (`transferTo`) decides the pricing exchange from then on (`PortfolioExchangeRoute`). A fee in a third coin
  (`feeCoin`, `feeCoinAmount`, `feeCoinPriceUsdt`) is a disposal of the fee coin at the fee price and adds
  its value to the main transaction's cost (or takes it from the proceeds); on the card a missing fee price
  marks the result incomplete (`feeValueMissing`, `realizedFeeMissing`), never guessed (no estimated prices, like
  rewards without value). The tax export has one rule for every third-coin fee without a price, whatever the
  type (buy, reward, deposit, sell, payment, swap, transfer, withdrawal, gift): the fee is valued at the fee coin's
  UTC day close (`PortfolioLedger.dayCloseNeeds`, `valueThirdFeesAtDayClose`, `feePriceEstimated`) – in the cost
  of a buy or transfer, off the proceeds of a sale or swap, in the expense of a withdrawal. The fee coin's row and
  every row that contains the fee value carry «value estimated» instead of «without fee value». Without a close the
  row keeps «without fee value». In general every value the tax export takes from a day close carries «value
  estimated» (`valueEstimated`), also an FR payment without market value and the network fee sold at the close where
  it is a disposal (UK, US, CA, AU, JP, coin fee and third-coin fee); a recorded price or a stablecoin carries none.
  An estimated value inside a cost basis is carried through the lots (`costEstimated`, derived during the run, never
  stored): a reward or swap valued at the close, a third-coin fee at the close in the cost of a buy or transfer, and
  what they pass on (network-fee cost, moved lots, CA superficial-loss adjustment from such a row). FIFO, FIFO per
  wallet, Ø, UK pool, CA, JP, AT and the FR total keep the flag until that cost has fully left the lot or pool; every
  disposal row whose cost contains such a part carries «value estimated». The FR summary line of outflows without sale
  (`FR_OUTFLOW`, withdrawal or gift) carries it too when a reduction of the total rests on such a part or on the
  outflow's value at the day close (`TaxOutflow.estimated`).
  The info lines «fee not assigned» and lost fee cost (`TaxExport.feesIn`) are computed without day closes, so an
  estimated fee share on holdings without purchase price is not listed there. Fixtures: `tax_templates.json`,
  `tax_day_close.json`. The
  third-coin fee has its own fields – never reuse `fee` (it is always the quote/USDT fee). The Room portfolio database also has
  `giftToSpouse`, `feeRow`, `interest`, `stakingMove` (INTEGER NOT NULL DEFAULT 0), `linkedImportKey` and
  `importRestoreState` (schema JSON `app/schemas/…PortfolioDatabase/1.json`). Backup/system-backup copy: these fields
  are additive (`importRestoreState` as a JSON object) and backup `version` stays 1 – readers refuse a newer `version` as a whole
  (`BackupVersion.check`) but ignore unknown fields. A backup without `version` (a number), without `createdAt`,
  without one of the parts the export writes (`watches`, `alarms`, `favorites`, `settings`, `portfolio`,
  `portfolioAlarms`, `watchedAddresses`, each with its type), without a favorites kind (list of texts), without a
  settings field or with an entry that lacks a field the writer always writes (or has it with another type) is
  invalid and nothing is replaced (`BackupFields.checkParts`, both platforms; settings fields in
  `BackupSettingsCodec.FIELDS` / `BackupManager.settingsFields`, entry fields in `BackupFields`; cases in
  `testdata/parity/backup_settings.json` and `backup_entries.json`). Null only where the writer writes null; fields the
  writer omits when empty (watched addresses: `label`, `observations` …) are optional; `indicatorAlerts` lists only
  enabled kinds. Entries with invalid values (unknown kind, amount 0 …) are still counted and named before the restore.
  The restore journal (Android `BackupJournalCodec`, iOS via `restore`) and the system-backup copy
  (`PortfolioBackupMirror`: `version` and its three lists with their entries) follow the same rule; a journal
  without `createdAt` has an unknown age and is given up.
* **Cost method FIFO.** `CostMethod.FIFO` is the default for everyone, average cost («Ø») is a
  setting (`portfolio_cost_method`, part of the app backup; an unknown value → FIFO). `PortfolioFifo`: one queue per
  coin across all exchanges and wallets, lots ordered by acquisition time (a DEPOSIT may carry the original
  `acquiredTime`), then transaction time, then id. Buys/rewards/deposits create lots (unknown price → lot with
  unknown cost); sells consume oldest lots and produce `PortfolioDisposal` rows (one per lot slice; proceeds
  split by amount, the last slice gets the remainder); a sell beyond the recorded holdings produces an
  `uncovered` disposal with proceeds and no cost (export marked incomplete); withdrawals consume lots without
  gain; a transfer network fee consumes the oldest coins and moves their known cost onto the oldest remaining
  priced lot (total cost basis unchanged – write this down for tax advisors). Ø keeps `costParts` so the tax
  export converts each acquisition at its own date's FX. Trades are grouped per coin once
  (`PortfolioLedger.eventsByCoin`); FIFO keeps a running holdings total.
* **FIFO cost parts.** FIFO lots carry their cost basis as dated parts like Ø (`PortfolioCostParts`,
  invariant: sum of parts = cost, exact): the acquisition, a transfer fee spread onto the lot at the transfer's time,
  and a carried network-fee cost with its original parts. Consuming a lot takes all parts pro rata
  (`PortfolioDisposal.costParts` is filled for FIFO and FIFO_WALLET too), so the tax export converts each part at
  its own date: CH, lot 2018 5000 USDT (rate 1.00) + transfer fee 2024 100 USDT (rate 0.85) = 5085 CHF, not 5100
  (`tax_export.json` › `fifoCostParts`, FIFO = FIFO_WALLET = Ø). The «cost at purchase-date rates (Ø)» note stays for
  pooled rows only (parts without a lot date). The card is unchanged: it converts every USDT amount at today's rate
  (`PortfolioHistoryFx.forCard`), hence the sentence «The tax CSV can differ from ‘realized’»
  (`tax_export_realized_differs`, shown in the export dialog below the template notes and on the total card under
  «Realized» as secondary text). US transition seed lots keep only their acquisition date (tax currency USD).
* **Unassigned fee – «open item visible».** The share of a transfer fee (USDT + value of
  a third-coin fee) that falls on coins WITHOUT a purchase price belongs to their unknown cost basis and cannot be
  added to a known one. It is never dropped silently: `PortfolioUnassignedFee` per transfer from
  `PortfolioFifo.spread` (FIFO, FIFO_WALLET), the Ø transfer branch of `PortfolioCalculator` and `TaxPool.addFee`
  (UK/CA pools without template rules); `CoinPosition.unassignedFeeUsdt`, `PortfolioSummary.unassignedFeeUsdt`,
  `CoinLedger.unassignedFees`; tax export info row after the totals (`TaxExport.unassignedFeesIn`, `gainsCsv`
  `unassignedFees`, text `tax_export_unassigned_fee`), never in a total. Card/position text
  `portfolio_unassigned_fee` («Fee not assigned – cost basis unknown»): on the coin's position card below the
  lost-fee-cost hint, and the portfolio sum as one line under the total card's «Hints» – in USDT like the other
  amounts there, hidden as «•••». The fee-coin disposal (`feeLeg`) is unchanged.
  Templates with `quoteFeeIsInfo` drop the USDT transfer fee before the calculation, so nothing is left open there.
  Cases: `portfolio.json` («unassigned fee …»).
* **AT staking note.** AT keeps acquisition cost 0 for staking with own coins and airdrops (§ 27b Abs. 2
  EStG, BMF «Kryptowährungen»); the export note `tax_note_at_staking` says that the recorded value on receipt does not
  matter. Lending interest stays at the value on receipt (`TaxEngines.interestReward`).
* **USDT line on the card.** `DepegAlarm.cardNote` (both platforms, parity `depeg_alarm.json` › `cardNote`)
  decides the «USDT = 1» line independently of the depeg alarm: fresh reading ≥ threshold → deviation warning (always
  shown); else values hanging on USDT = USD (`PortfolioExchangeRoute.usdtParityShown`) → price-source
  line; else holdings containing USDT → `portfolio_usdt_assumed` («USDT dollar rate missing – counted as 1 US dollar»); else no line.
  With `usdtMarket = true` (USDT at market price, see below): a fresh plausible reading (`DepegAlarm.liveUsdtUsd`) →
  no line; otherwise `USDT_ASSUMED` whenever values depend on USDT – the visible marker of the 1-fallback.
  The total card gets the last reading, the threshold and the screen clock (`depegReading`, `depegThresholdTenths`,
  `now`) and calls `cardNote` itself; the price-source line moves under «Hints» unless it is the card line.
  `VIA_USDC` (`portfolio_usdt_via_usdc`) warns that a USDC depeg falsifies the value; Aster's quote «U» (United Stables) is deliberately not in `USD_STABLES` – the set also classifies base coins (`PortfolioStables.isStable`), so a coin «U» would be treated as a stablecoin.
* **Exchange connections checked.** `ExchangeVerification.LAST_VERIFIED` (Swift `lastVerified`) is the UTC
  date of the newest recorded exchange response; `ExchangeVerificationTest(s)` fail when a newer recording is added
  without moving the date. About shows it with `about_exchanges_verified` below the CoinGecko line, the date in the
  device's medium date format (Android `ofLocalizedDate(MEDIUM)` via `LocaleNumbers.dates`, iOS
  `CycleFormat.mediumDate`).
* **Country event rules.** `TaxTemplate.eventRules` (`TaxEventRules`, both platforms) is applied once
  before every tax calculation: `giftIsDisposal`; `spouseGiftNeutral` (UK TCGA 1992 s. 58, CA ITA 73(1): a GIFT
  marked «to spouse / civil partner» – own field `giftToSpouse`, effective only on a GIFT
  (`isGiftToSpouse`) – is proceeds = cost);
  `networkFeeIsDisposal` (UK/US/CA/AU/JP: a network fee in crypto on a transfer or withdrawal becomes its own
  disposal of the fee coins at market value, placed before the transaction; no cost-basis increase, no extra
  loss); `quoteFeeIsInfo` (UK/US/CA/AU/JP: a withdrawal fee in USDT/cash is listed as information only, own
  `WITHDRAWAL_FEES` summary line, not netted – UK HMRC CRYPTO22150; elsewhere no rule allows a deduction).
  DE/AT/CH/FR/Neutral unchanged. Rules table and cases in `tax_templates.json` / `tax_export.json`.
  `quoteFeeIsInfo` also covers a USDT/cash fee on a transfer between own wallets – `applyRules` drops it
  from the transfer (no cost-basis increase) and `TaxEngines.disposals` adds an info row
  (`PortfolioFeeExpense.TRANSFER`, own `TRANSFER_FEES` summary line, not in totals); DE (no administrative view on
  own-wallet transfer fees), CH (ESTV does not classify), Neutral and the portfolio view keep the fee in the cost
  basis, AT and FR never counted it (§ 27a Abs. 4 Z 2 EStG; not part of the French acquisition-price total).
* **Tax export.** `TaxExport` (Kotlin/Swift, fixture `tax_export.json`): realized gains per year
  (FIFO lot slices or Ø sales; acquired/sold date, holding days, «> 1 year» = sold after acquisition date + 1
  calendar year, local dates; cost at acquisition-date FX, proceeds at sale-date FX, daily ECB rates
  straight from the ECB, up to 7 days back), income per year (rewards at value when received), year-end holdings
  (cut-off CSV on 31 Dec). Totals only over rows with cost, proceeds and gain known, plus «proceeds without
  purchase price»; header states method, currency, time zone, «not tax advice». Exact decimals, rounding only
  when printing.
* **Money-path rules.** A withdrawal without price is valued at that day's market
  price in period/history flows (never an invented loss); a deposit without price is valued at market as money
  put in (cost basis stays unknown); value alarms never fire or re-arm while a coin price is missing, %-change
  alarms not while «Today» skips coins; the card shows «Withdrawn: …» (value change of withdrawn coins until
  withdrawal) and «Rewards +… (not in gain)»; the history says when a range includes withdrawals.
* **Coin metadata and logos.** Lists first: the Binance symbol list and the Binance Alpha list are
  fetched in parallel, each on its own (`CoinLists`), and names plus α flags are saved and shown at once – before
  pair lists, CoinGecko pages and images. If a direct list fails, the app uses `coin_meta.json` from the GitHub
  release «data» (built by `mirror_data.py` from Binance lists only – never CoinGecko data or images). A snapshot
  (`coin_meta_snapshot.json`) ships with the app, so names show offline on first start; regenerate it with
  `android/tools/gen_coin_assets.py` (`--meta live` needs network – run it before each release so Alpha is in
  the snapshot). Bundled logos (no download for them): `coin_logos.bin` (the 100 largest coins by market cap,
  shown until a coin's downloaded logo is there; Android assets, iOS `Shared/Resources`) and `exchange_logos.bin`
  (every exchange in «Add pair», futures share the parent's logo, DexScreener/ECB a fixed symbol; Android assets,
  iOS `App/Resources`), 80 px near-lossless PNGs in the `CCIP1` pack format, built by
  `android/tools/gen_logo_packs.py` (standard library only; sources and sha256 in
  `android/tools/logo_packs_manifest.json`; run it before a release). Images: all entries are downloaded (never per watchlist coin, in a fixed order for everyone), 6 at once, 4 per host, a
  429/503, or a 403 with Retry-After, pauses only that host (Retry-After, ≤ 6 h) and is never a per-image failure. A
  403 without Retry-After (CloudFront/S3 also answers a missing file that way) fails only that image (1-day rule; the
  small variant still falls through to the large one); only the third image of a host in a row ending in 403 within
  one run pauses the host (`CoinLogoRetry.pausesImages` / `nextForbiddenStreak` / `forbiddenStreakPauses`, fixture
  `logo_fallback.json`). An expired pause counts as none (`shortestPauseMillis` → null/nil on both platforms); paused
  images left over with an expired pause go into the next round at once. When a pause outlasts
  the short in-run wait (2 min), the sync restarts by itself at the earliest pause end + 1 s
  (`CoinLogoRetry.pauseRetryDelayMillis`, `imagesPausedUntil`) while the app process lives – a coroutine in
  `CoinLogoSync` (Android) / a `Task` in `AppData+CoinLogos` (iOS), no WorkManager or background task; any sync that
  starts earlier cancels it and schedules anew. Pull-to-refresh / «Refresh» in the watchlist also starts the
  sync (same bulk sync, privacy-neutral; switches, list freshness and host pauses apply). One run fetches each
  file name once (`CoinLogos.downloadJobs`: «FLOKI» and «1000FLOKI» both write `FLOKI.png`). Files are written to
  a uniquely named temp file and renamed in one step (Android `LogoFiles.write`, iOS `Data.write(.atomic)`), so a
  concurrent starter-logo and bulk download never leaves half an image; a write that fails counts as a failed
  image (1-day rule, retried) and never as «new» without a file.
* **Gift, fee rows, same-time events, bulk rows, colours.** Gift vs payment: `PAYMENT` is a disposal at market value in every template, `GIFT` only where
  `TaxTemplate.giftIsDisposal` (UK, CA, AU), elsewhere it leaves like a withdrawal. A fee row is SELL + price 0 + an
  import key; a manual sell at 0 is a real sale. Same-time events: inflows before outflows, then id (ledger comparator,
  reused by the tax engines). Bulk tickers: a row whose price key is present but null/""/0 is an inactive market and
  skipped (`BulkTickerRows.inactivePrice`); a missing key or unreadable value is a failed row (`bulkRow`). Ticker
  `timestamp` is always the fetch time, the exchange's time goes to `exchangeTime`. Price colours: every accent ×
  scheme keeps ΔE2000 ≥ 24 between the accent primary and rising/falling (`ColorDifference`, fixture
  `price_colors.json`). iOS strings: `ios/tools/ios_overrides.json` replaces single Android texts per language in
  the iOS catalog (German «Mitteilung» on iOS, «Benachrichtigung» on Android). Build number +1 with every state.
* **Tax templates.** `TaxTemplates`/`TaxEngines`: Neutral (default) plus DE, AT, CH, UK, US, CA, AU, FR,
  JP – each with cost method, tax-year boundaries (UK 6 Apr, AU 1 Jul), holding buckets, crypto-to-crypto
  treatment and thresholds, sources and «Stand» in code comments. Engines: UK same-day / 30-day / Section 104
  pool, CA superficial loss, FR prix total d'acquisition, JP total average, AT Alt-/Neubestand. UK: all disposals of
  a day are one disposal – same-day, 30-day and pool matches go to the day as a whole and are split pro rata by
  quantity (pool part at the day's last disposal); a withdrawal between two disposals of the same day is booked after
  that pool part, so the day's disposals are matched first. FR: a third-coin fee uses one price (recorded, else day close,
  marked «value estimated» like every third-coin fee) for the acquisition total, the price before fees and its own
  cession – without one the total is incomplete; a network fee in the coin valued at the day close is marked «value
  estimated» too; a withdrawal beyond the recorded holdings reduces the total only for the covered quantity (value =
  covered quantity × price) and marks the total incomplete from then on; a
  withdrawal fee row is only the USDT fee at the covered share; the FX span includes the purchase dates behind a
  reduction of the total by a withdrawal/gift; with rewards up to the year end the gains line notes that the value
  on receipt assumes income taxation on receipt (otherwise cost 0 would be the cautious reading). Data rows state
  facts only («held > 1 year»); legal conclusions appear only as labelled orientation lines in the summary.
  CH (`TaxWealthCh`): «wealth value 31.12. (orientation)» = holdings at the end of 31 December (Zurich)
  × the 31.12. close in CHF, per coin, reusing the year-end holdings export; a missing or unverified close or FX
  rate marks that coin «not calculated» and the total incomplete (never today's price); capital gains and the
  wealth tax amount stay uncalculated; parity fixture `tax_ch_wealth.json`.
  The template is never applied silently from the device region. Every CSV text cell starting with = + - @,
  tab or CR gets an apostrophe (formula injection); numbers are written separately.
* **Binance Alpha.** With «Incl. Alpha tokens» on, Alpha tokens missing on Binance spot are added as
  Binance pairs `ALPHA_<alphaId>USDT` (α), priced in one bulk request from the Alpha token list and charted from
  the Alpha klines (source «Binance Alpha», no fallback chain). On other exchanges a pair is α when its symbol
  (1000-prefix stripped) is in the Alpha list, Binance does not list it regularly, it is not TradFi and the names
  don't clearly differ (`TokenIdentity`). Saved pairs always stay.
* **Fee-eats-amount rule.** A buy or swap whose coin fee is ≥ the amount received cannot be saved (CSV
  skips it); a stored one books the value as a fee expense everywhere. A quote fee on a withdrawal is a realized
  expense on the card, in periods and in the export.
* **Stale and partial values.** Portfolio alarms run only while old prices are immaterial (below 1 % of the value,
  `PortfolioAlarmLogic.staleMaterial`; coins without any price block value alarms); widget and history may show a value
  with up to 20 % stale prices but must mark it («partly old price»). A partial total or «Since purchase» carries an inline marker at the number. Wrapped/bridged tokens
  keep their own symbol; only without an own pair are they priced via the base coin, labelled.
* **One holdings book.** `PortfolioHoldingsBook` / `PortfolioCalculator.holdingsAt(trades, time, method)`
  decides holdings for every screen and file: card, periods, history, widget, alarms, cut-off and tax export
  must never compute holdings another way (FIFO_WALLET caps sells/withdrawals per wallet). For every method the
  address part without cost basis (`AddressBalance.isAddressPart`) is its own pot, as the card splits it off
  (`AddressBalance.split`): an own outflow beyond own holdings stays uncovered and never eats the address part, an
  address outflow comes only from the address part, and `capFor` returns the pot of the step. The list widget
  (`PortfolioWidgetEntries.of`) shows no row for the address part; opening lots are rows like buys. The realized
  «incomplete» flag comes from the same disposal rows as the tax export. Bulk ticker parsers report
  unreadable rows per pair (error, never «unchanged»); most rows failing = format error.
* **Swap, wallet FIFO, imports.** SWAP is one transaction (`toCoin`, `toAmount`, `swapValueUsdt`):
  disposal of the given coin at the swap value, new lot of the received coin at that value; without value the
  gain stays incomplete (USDT legs are automatic). `CostMethod.FIFO_WALLET`: queues per exchange/wallet (empty =
  «not specified»), transfers move lots with their purchase date, a shortfall never borrows from another
  wallet (`walletShortfalls`). CSV imports carry `importBatch`; «Undo import» deletes exactly one batch. Open
  lots per coin (`openLots`). DE annual summary (`TaxExport.yearSummary` with the DE template): ≤/> 1 year, § 23 EStG Freigrenze
  (1000 € from 2024, 600 € 2008–2023, 512 € before) and § 22 Nr. 3 (256 €), a Freigrenze (whole amount taxable
  at or above), labelled «orientation, not tax advice». Everything is called a working paper («Arbeitsunterlage»).
* **One day boundary.** Period windows, start holdings and the history buckets use UTC days like the
  daily candles; labels may show local dates. The 7 d / 1 y window starts after the last millisecond of the base
  day (`PortfolioPeriodChange.windowStart` = `PortfolioHistory.utcDayEnd`): a trade at exactly 00:00:00.000 UTC
  is in the window, as in the history. Unpriced inflows (rewards, deposits, swap-ins) and unpriced
  third-coin fees are valued at that day's market price in period flows; a coin is skipped only without any
  market price, and app card, rows, widget and alarms show the same figure with the same «without …».
* **Exchange time.** Never stamp server/envelope time as trade time (Binance closeTime, Bybit `time`,
  HTX `ts`, Bitget, Poloniex, MEXC futures, KuCoin, WOO X futures, Indodax): unknown exchange time lets «unchanged
  since» work from `priceChangedAt`. Exception: WOO X spot is an hourly close, so its exchange time is the
  end of the information in that candle (response time while it runs, start + 1 h once closed) and the 2-hour
  alarm rule and «outdated» apply; the second request (last trade) replaces price and time with the
  real trade when it succeeds. A bulk response can declare itself incomplete (`bulkResponseComplete`; One
  Trading keeps only ACTIVE spot rows and is incomplete when it dropped futures or closed books), so a missing
  pair falls back to the single ticker instead of «not traded».
* **Token identity.** `TokenIdentity`: same contract → same token; known names compared after
  normalising (table for LUNA/LUNC, CAT, NVDA, TON…); clearly different names reject a fallback even within the
  price band; without names the old symbol + band rule applies (weak).
* **Watched addresses.** Read-only public BTC/ETH addresses (`AddressValidation` refuses seed
  phrases, private keys, xprv; EIP-55 checksum). The summed balance per coin is merged as a synthetic
  position part (`addressHoldingsExact`) – never written to the transaction table or CSV; it counts in total,
  widget and alarms, never in «Since purchase» or the tax export, and never turns purchased lots into «no
  purchase price». Periods and history use the observed balance history (`AddressBalanceHistory`: first
  observation plus the last per UTC day, stored with the address): an address counts only from its first
  observation, later balance changes are inflows/outflows at market, nothing is backdated. One holdings rule
  (`AddressBooked`) for card, «Today», 7 d / 1 y, history, widget and cut-off/CH wealth: own bookings + opening
  lots (buys from their date) + address part = observed balance minus what lots and recorded movements of the
  address wallet already book. Periods and history apply it at every observation (max(balance(t) − booked moves up
  to t, 0); moves clamped to the last observation, recorded ones only up to the balance fetch), so the last step is
  the card's address part and «Today» uses `ledgerHoldingsExact` + that step. Cut-off CSV and CH wealth
  (`AddressCutoff.at`) take the same values on their date: the card's effective lots (coverage against today's
  balance) from their purchase date, also before the first observation, plus the step at the last observation up to
  the cut-off (none before the first observation: the coin is «not observed»). A lot dated after that observation
  (up to the cut-off) adds the note `portfolio_export_address_lots_note` (`AddressCutoff.lateLots`): lots count from
  their purchase date and are reduced when the address's balance today is lower — only there the observed step does
  not make up for such a cut. The FR total value adds the same
  series (`AddressCostLots.exportTrades` → `AddressBooked.histories`). Background address
  refresh is capped (8 s total, 5 s per request) and never delays prices or alarms. Requests at most every 10 min per address, none without
  addresses; hosts are listed in the privacy policy. Addresses (not balances) are part of the app backup
  and follow «Portfolio in system backup».
* **Opening lots of watched addresses.** `AddressCostLots` count like buys in the address
  wallet, capped at the holding before the recorded movements of that wallet (observed balance + recorded outflows −
  recorded inflows, all dates, as FIFO per wallet books them); only the rest stays address part without cost basis.
  Periods and history count them like buys from their date, as the card and the cut-off do.
* **Only the surplus over the same wallet counts.** A counting address adds per coin only what it holds beyond the own
  holdings recorded in ITS wallet (`AddressCostLots.walletName`, compared via `PortfolioWallets.key`); holdings in other
  wallets (exchange, other address) never reduce it. `AddressSurplus.counted` = max(0, address part − own holdings of the
  wallet). Address part = summed balance of the counting addresses of that wallet minus what lots and recorded movements
  of the wallet already book (`AddressCostLots.accountedByWallet`); own holdings of the wallet = its recorded movements
  (`AddressCostLots.recordedMove`) minus those already in the balance (up to the balance check of the settling address),
  never below 0, plus recorded inflows beyond the settling address's balance (history: negative steps of
  `AddressBooked.held(floor = false)`). Several counting addresses of one wallet are summed first, the wallet's own holdings
  are subtracted once; wallets are independent. Without lots a wallet thus shows max(own entries of the wallet, balance).
  Card, rows, detail, alarms, widgets and watch (`AddressSurplus.parts` via `AddressBalance.trades`) and periods, history,
  cut-off and CH wealth (`AddressBalanceHistory.trades` with the ledger steps per coin and wallet from
  `AddressBooked.histories`, at every observation) use this one rule. Examples (`address_surplus.json`): exchange 1 BTC +
  cold-wallet address 1 → 2; own 1.00 in the address wallet + address 1.06 → 1.06; recorded transfer exchange → address
  wallet 1 + address 1 → 1; coin only on the address → in full. The automatic «Show only» switch below is unchanged.
  Hint only (`AddressWithdrawalMatch`, `address_withdrawal_match.json`): an own transfer from another wallet into
  another wallet than the address's whose amount matches the balance or an observed inflow (± 1 %, as
  `transferMatches`) and that is not also recorded into the address wallet shows `portfolio_address_withdrawal_match`
  with the target wallet of the first match (`AddressWithdrawalMatch.targetWallet`) on the address row and the coin card — no change of totals (a recorded withdrawal leaves the portfolio,
  no hint). Its one action «Change target» (`portfolio_address_retarget`, `RetargetHint` / `PortfolioRetargetHint.swift`)
  asks first (`portfolio_address_retarget_ask`: amount, old target → address wallet) and then sets `transferTo` of the
  named transfer (`AddressWithdrawalMatch.retarget` / `retargetFor`: first match with a target name; on the coin card the
  wallet of the first address it matches) to `AddressCostLots.walletName` through the normal save path
  (`retargetTransfer` → `PortfolioRepository.save`, iOS `AppData.savePortfolioTx`; not written → banner); afterwards
  nothing matches, the hint is gone (fixture field `retarget`).
* **Networks for watched addresses.** BTC (addresses incl. Taproot `bc1p…`, and xpub/ypub/zpub, derived
  on the device, gap 20 on the first scan; Taproot accounts only as a `tr(…)` descriptor – origin,
  `/0/*`, `/1/*` or `/<0;1>/*`, BIP380 checksum checked – stored as `tr(xpub…/<0;1>/*)`, BIP86 key tweak, bech32m;
  a plain xpub keeps the old type detection; vectors in `taproot.json`), Ethereum and the EVM chains BNB Smart Chain, Polygon, Arbitrum, Base, OP Mainnet,
  Avalanche (native coin + curated token lists, one batched request per chain), Solana (SOL + SPL/Token-2022 by
  mint), TRON (TRX + USDT-TRC20), XRP, LTC, TON (+ USDT jetton), ADA (Koios). BEP-2 is rejected (Beacon Chain
  shut down 2024), DOGE/BCH are rejected (no keyless service whose terms allow it). Keyless public nodes only,
  every host listed in the privacy policy (a test checks it). «Show only» addresses never count; an address
  whose coin is also in own transactions asks «Count / Show only».
* **CSV import.** `csvimport/` parsers detect Relai/Swissquote by header names; times without
  offset are Europe/Zurich; duplicates by `importKey` (stored on the transaction). A key longer than **200 UTF-8 bytes**
  (e.g. an ID cell over 190 characters) is replaced by `«format»|h:«SHA-256 hex»` of the whole key including `#n`
  (`CsvImport.stableKey`, applied in `withOccurrence`); saving and backups accept at most 200 UTF-8 bytes on both
  platforms (`cleanImportKey`), so a key is never dropped silently (fixture `importKeys`, `cleanImportKeys`). Numbers in
  scientific notation as Excel writes them («1.234E-05», «1,234E-05») are read by every format
  (`CsvImportParser.parseNumber`; value below 10³⁰; decimals are checked when saving, after rounding, like plain
  numbers, so «1.234E-38» as a price is kept; at most 128 decimals, the range of iOS `Decimal`). Format helpers that
  clean a cell first must keep the exponent: Coinbase keeps an E between digits when stripping currency signs
  («1.23457E+11»; «EUR 3.00» stays a code) and keeps only the digits 0–9 (other scripts are dropped like currency signs),
  the Binance trade history counts an exponent to the number before the unit («1E-8BNB» = 0.00000001 BNB). The Binance
  unit is first matched at the end of the field against the pair's base and quote (fee: also BNB), longest first, so
  leading digits stay with the symbol («10.51INCH» = 10.5 1INCH, «350000.001000SATS» = 350000 1000SATS); only without a
  match does the unit start at the first letter.
  Derived prices and conversions (fiat amount ÷ amount, ÷ FX rate) are exact and rounded
  once to 34 significant digits on both platforms (Android `CsvImport.derive`, iOS `PortfolioDecimal.divide` from the
  integer mantissas — `Decimal` itself divides to 38 digits, and rounding that again would differ in the last digit and
  change the import key; fixture `divide`). Rows the save would refuse are skipped in the
  preview (`unsavableReason`: `MISSING_FIELD`, `AMOUNT_TOO_SMALL`, `BAD_NUMBER`, `INVALID_SWAP`). Rows without price
  import without price and are named in the preview. Conversion to USDT uses the daily FX rate of the last
  business day ≤ date. Choosing another time zone re-reads the text and reloads the rates when a day now falls outside
  the loaded range (`CsvImportRateDays.of`/`cover`, fixture `rateDays`), instead of silently taking the previous day's rate.
* **Exchange exports.** `CsvImportBinance` (transaction history + both trade-history layouts; fills
  with the same second, pair and side are merged so both exports produce the same key; a buy and a sell of one
  coin in the same second in the transaction history are skipped; a Binance trade within 5 s of an imported one
  that is not an exact match is held back as `POSSIBLE_DUPLICATE`), `CsvImportCoinbase` (transaction report incl.
  preamble, current and 2021 layout), `CsvImportBitpanda` (transaction history with header block). Moves between
  accounts of one exchange are skipped (`INTERNAL_MOVE`). Fixtures: synthetic files in `android/testdata/csv/`
  (copy in `ios/Tests/Csv/`, first line says «synthetic»), cases in `csv_exchanges.json`.
* **Transfers across exchanges.** `CsvTransferMatch`: same coin, different exchanges, deposit between
  2 h before and 72 h after the withdrawal, deposit ≤ withdrawal, difference ≤ 5 % → one TRANSFER (amount sent,
  fee = difference in the coin). Within one file and against stored *imported* rows (manual rows are never
  changed). Exactly one candidate → preselected; several → shown, off (preview id `linkId` = «withdrawal →
  deposit», never stored). The linked row keeps both import keys in own fields – `importKey` = withdrawal,
  `linkedImportKey` = deposit – so re-importing either file finds duplicates (`importKeys()` returns
  both). A stored row that gets linked moves into the new import run and keeps its original state plus the state of
  the absorbed row of the later file in `importRestoreState` (`CsvRestoreState`, versioned JSON `{"v":1,"stored":
  {…},"absorbed":{…}}`, documented in the class); `undoPlan` restores it exactly when the later import is undone, and
  undoing the *earlier* import turns the linked transfer back into that deposit or withdrawal, kept in the later
  import run (undoing the later run afterwards removes it). Both keys live in their own fields (each within the
  200-byte key limit, long ones hashed by `CsvImport.stableKey`), never packed into one. Duplicates are recognised only by
  the exact stored keys (`importKey`, `linkedImportKey`). A stored side is a withdrawal candidate when it is a withdrawal
  or a transfer without destination that is not a staking move (choice «to own wallet»,
  `CsvTransferMatch.storedWithdrawal`). Staking transfers of a file («Unstaking», «Redeem», `CsvImportKind.TRANSFER`)
  carry the stored flag `stakingMove` (only on TRANSFER; set by `CsvImportMapping.toTx`, kept by the editor while the
  kind stays TRANSFER, never set for a manual entry) and are never candidates – with or without note; the note plays
  no part. Saving writes the new rows and the stored
  sides in **one** write (Android `insertImportedLinked` in one Room transaction, iOS `AppData.withImported` in one file
  write); a stored side deleted or linked meanwhile is left out and its absorbed new row is saved as a normal row instead
  (`CsvTransferMatch.withFallbacks`/`fallbacks`; a withdrawal without choice stays a withdrawal). Cases in
  `csv_transfers.json` (both undo orders and both in sequence, `fallbacks`).
* **Kraken ledger CSV.** `CsvImportKraken` reads the «Ledgers» export (txid, refid, time UTC, type, subtype,
  asset, amount, fee): trade/spend/receive legs paired by refid (money ↔ coin = buy/sell, coin ↔ coin = swap),
  deposit/withdrawal of coins, staking/earn rewards (inflows only); internal moves are skipped. Kraken codes
  are normalised (XXBT → BTC, ZEUR → EUR, DOT.S → DOT). Fixture `kraken_ledger` in `csv_import.json`. A pending
  deposit/withdrawal row (empty txid) with a booked twin (same refid, type, asset, amount) is dropped; with a different
  amount both rows stay, flagged `pendingDiffers`, and the preview names both lines (`kraken_pending_differs`).
* **Fiat fee plus coin fee on one trade (Kraken, Binance).** A buy/sell against fiat with a fee in the fiat currency
  AND one in the traded coin: the fiat fee goes into the price (buy (amount + fee) / quantity, sell (amount − fee) /
  quantity), the coin fee stays on the trade (less received / more given) – never a separate fee row, which would
  dispose of the oldest lot (`CsvImport.fiatFeeInPrice`, row flag `feeInPrice`, preview hint; fixtures
  `kraken_two_fees`, `binance_ledger_two_fees`, `binance_trades_two_fees`). Other unit pairs: one fee on the trade,
  the rest fee rows (`CsvImport.splitFees`).
* **Swap fees from both sides.** `CsvImport.swapFee`: one side → that fee; same unit → sum; different units →
  both rows skipped (`TWO_FEES`), never drop a fee silently.
* **Exact cut-off CSV.** `CutoffExport` works on `BigDecimal`/`Decimal`; Double prices are
  converted through their shortest decimal text (`PortfolioDecimal.of`); rounding only when printing.
* **Per-coin gain.** App list and widget rows show the position's own gain for the change
  basis («24h +1'250 CHF · +1.5 %», per-coin `todayByCoin`; percent ÷ (start value + inflows)), never the
  token's market change; the rows add up to the total «Today» (parity check `byCoin`). The widget list has a
  pinned «Total» row (share 100 %, total value, total gain) and the «As of» line (with «Today without …»); both always stay visible, coin rows give way first.
  Per-coin and total gain are persisted in the widget snapshot on both platforms.
* **Widget quantity.** `WidgetQuantityFormat`: at most 2 decimals, truncated (never more than
  held), below 0.01 two significant digits, from the snapshot's exact quantity text.
* **«Since purchase» with unpriced coins.** When part of a position has no buy price (deposit
  without price, address), «Since purchase» is computed for the priced part only and marked «without x COIN
  lacking a buy price» (`ledger_partial_cost`). Never fold unpriced coins in at cost 0.
* **CSV withdrawals need a choice.** Each imported withdrawal is either a transfer to an own wallet (cost
  moves along) or leaves the portfolio; the preview asks per row and proposes «transfer» only when a counted
  address matches. Never decide silently.
* **Overlap auto-switch.** A counting address whose overlap was never decided (`overlapChecked`) switches to
  «Show only» automatically once, with a hint and Undo, only when the amounts of all its coins match the
  transactions (`WatchedAddressRules.overlapAutoShowOnly`); otherwise it keeps counting (only the surplus over own
  holdings, see above) and the «Also in own transactions» hint stays. Any choice by the user sets `overlapChecked`.
* **Tax template texts = formulas.** Every sentence in `tax_note_*` / `tax_line_*` must describe what the
  engine does; a change to an engine needs its text in all 31 languages and a worked example in
  `tax_templates.json` (parity tested). CSV cell texts contain no `;` or `,`.
* **Exact checks and run log.** «Check on time» (Android only, off by default) uses `SCHEDULE_EXACT_ALARM`
  and falls back to WorkManager without the permission; every background check (worker, exact, BGTask,
  widget, foreground) writes one line to the run log (20 entries, no backup). A check within 5 min of the last
  one is skipped and logged as such; the exact alarm follows the chosen background interval (section 20). iOS widget refreshes also check price alarms (logged as
  «Widget · prices and price alerts»), with the same show → verify → mark order as the app (`AlarmDelivery`).
* **Snapshot time is never exchange time.** Only a real last-trade time may go into
  `ticker.timestamp`. Server/snapshot clocks (Bitso `created_at`, Crypto.com `t`, Bitstamp/Bitvavo/BtcTurk/ZebPay
  `timestamp`, Phemex ns `timestamp`, Gemini `volume.timestamp`, like Binance `closeTime`, Bybit/OKX/HTX `ts`) stay
  out, otherwise «Unchanged since» never shows.
* **TradFi flag.** Every futures parser whose API marks stocks/indices/metals sets `tradFi` (HTX `tradfi_labels`,
  Phemex `perpProductSubType = TradFi`), and the market is listed in `CoinLogos.TRADFI_EXCHANGES`.
* **Name lock for all sources.** After a name contradiction no source may supply a fallback series.
* **CSV fee rows** are disposals at the daily close (card, curve, coin detail, tax export use the same price);
  no close → unknown, never 0. **Withdrawal fees** count only for the covered share. **Tax engines:** CA one
  rebuy covers one loss; FR uncovered part unknown; UK late deposit joins the pool on its booking day.
* **One number locale.** Display and input use app language + device region (`LocaleNumbers.formatLocale`).
* **USDT depeg alarm.** Pure rule in `DepegAlarm` (parity `depeg_alarm.json`): both sources must deviate,
  fire once per episode, re-arm at half the threshold, quiet hours hold it. Its reading doubles as the live
  USDT/USD price of the valuation (`DepegAlarm.liveUsdtUsd`, ≤ 30 min, 0.5…1.5).
* **Further stablecoins in the depeg alarm.** Pure rule in `StableDepeg` (parity `stable_depeg.json`), run by
  `StableDepegChecker` / `StableDepegCheck` right after the USDT measurement, only with the alarm on, same switch,
  threshold and hysteresis, own state per coin (`DepegAlarmPrefs.STABLE_ARMED_*` / `depeg_stable_armed_*`, reset by
  `MarketAlertReset.depegOff`). USDC always: Kraken `USDCUSD` + Binance USDC/USDT × direct USDT/USD (no extra request;
  Coinbase has no `USDC-USD` product). Others only when in watchlist or portfolio, one spot pair per exchange from the
  stored pair lists of Kraken/Coinbase/Binance (never loaded here; iOS `StableDepegPlanner`); they need two accepted
  sources. Readings are display-only (settings line «Monitored»); valuation still uses USDT alone.
* **USDT at market price.** USDT stays the internal unit (1 USDT = 1 USDT, all
  ledgers/history in USDT), but USDT → fiat (USD included; USD itself stays 1) uses the USDT/USD market price like
  USDC/DAI: `PortfolioStables.usdtUsd` (break band 0.05…3 for the live rate and daily closes alike, so a real crash
  such as 0.40 is kept and a history across it has no invented step; else **1 marked approximate** – never silent, values never
  go blank), `usdtTo` (USD→target × USDT/USD), `UsdtUsdRates` (live from today, daily close before; a day without
  close = 1 approximate). Wired through `PortfolioHistoryFx.convert/rateFunction` (`usdt`, flag `usdtApproximate`,
  `convertsUsdt`, `usdParity` for USD), `PortfolioExchangeRoute.toUsdt/ownDailyUsdt` (USD/fiat quotes ÷ USDT/USD),
  `CurrencyConversion.stableToUsd/quoteToUsd` (`usdtUsd`) and `TaxExport.rateOn/gainRows/incomeRows/yearSummary`
  (`usdtUsd` = daily closes per date; rows without close get `usdtApproximate` and the note
  `TaxCsvTexts.usdtAssumed`). Every new parameter defaults to null/1 = the old USDT = 1 maths; the callers pass them
  explicitly. Parity: `portfolio.json` › `usdtMarket` and `ownDailyUsdt`, `depeg_alarm.json` › `cardNote`
  (`usdtMarket`) and `liveUsdtUsd`, `tax_export.json` › `usdtMarket`.
* **USDT at market price – wiring (live).** Measurement: `DepegAlarmChecker` / `DepegAlarmCheck` measure whenever
  `DepegAlarm.shouldMeasure` (depeg alarm on, or portfolio on with transactions or watched addresses) – same Kraken
  `USDTZUSD` / Binance `USDCUSDT` tickers, at most every 5 min; with the alarm off they only store the reading
  (`DepegReadingStore` / `DepegAlarmCheck.lastReading`), never notify. Also started from the portfolio refresh.
  Daily closes: `PortfolioHistorySource.usdtUsdDaily` = Kraken daily candles `USDTZUSD` through `OwnCandleDataSource`
  and the own-candle cache (`phisto_USDTZUSD`, 12 h; gaps filled from Coinbase `USDT-USD`); `PortfolioDailyPrices.usdtUsd` carries them with every history
  load, and USD/fiat candles of own exchanges divide by them (`ownDailyUsdt`). Callers: total card and rows use
  `displayFxRate` / `displayRate(for:)` (FX × live USDT/USD) and `cardNote(usdtMarket = true)` (no deviation line); history and «7 d»/«1 y» pass `UsdtUsdRates(live, daily)`
  (`PortfolioHistoryUi.usdtApproximate` → caption `portfolio_usdt_assumed`); widget snapshot and its draw-time rebase
  multiply by `PortfolioWidgetChart.usdtFactor` (not for USD – the widget shows USDT there); «Today» converts back
  with the snapshot's own factor (`fxFactor`); `PortfolioPriceSource` passes the live rate to `toUsdt`;
  `CurrencyConverter` passes it to `quoteToUsd` (watchlist «≈», alarms in another currency; target USDT ÷ USDT/USD);
  tax export passes `usdtUsd` (daily closes since the first needed date) and `TaxCsvTexts.usdtAssumed =
  portfolio_usdt_assumed`; the cut-off export multiplies its rate by USDT/USD of the date (today live) and adds the
  line `CutoffCsvTexts.usdtAssumed` when there is none (`CutoffExport.csv(usdtApproximate)`, column «Rate USDT→…»).
  The USD display currency keeps showing USDT on the card (no conversion). The portfolio-alarm fallback
  `PortfolioAlarmLogic.totalIn` (alarm in a currency other than the snapshot's) multiplies by the live rate
  (`usdtUsdLive`; without it × 1 and the alarm text says «USDT = 1 assumed», `usdtAssumed`); fiat entry in the
  transaction sheet (`PortfolioTxForm.entryRate`) and the CSV import (`CsvImport.priceInUsdt`) use the USDT/USD close
  of the transaction's day (today the live rate; missing → × 1, marked as approximation).
* **Change basis must be visible.** A % from a UTC day open (`StarterPrice.sinceUtcDay`, currently unused since starters are Binance) is labelled «since
  00:00 UTC»; Hyperliquid's % compares the shown price (`midPx`, else `markPx`) with `prevDayPx`, one basis for price
  and % (`Hyperliquid.shownChange`), so it needs no label; `Exchange.changeIsMark` (adds «Mark») is intentionally set
  by no adapter. WOO spot needs 24 contiguous hourly candles; a closed candle whose last-trade request failed is
  labelled «hourly price» (`Ticker.hourlyClose`, `HourlyCloseStore`), the running candle stays unlabelled.
* **Near high/low alarm** uses fallback series only with the mini-chart identity rule (name + ±25 %,
  `NearExtreme.seriesAllowed`, parity `near_extreme.json`).
* **Gemini**: an ambiguous symbol always goes through `/symbols/details`.
* **Background decisions are pure** (`work/BackgroundWorkPolicy.kt`): exact alarm arm/cancel, fired action,
  rescheduling actions, worker outcome. Receivers and the worker only call it. No `runBlocking` at app start.
* **User-Agent** carries the real app version on both platforms (`HttpUserAgent`). Exception: every
  host that sees watched addresses (`AddressBalance.PRIVACY_HOSTS`/`privacyHosts`) gets `HttpUserAgent.NEUTRAL`
  (Firefox 159 string, one value for both platforms, `address_privacy.json`) on *every* request, gas fees and
  halving included (Android: `UserAgentInterceptor` host rule; iOS: `ExchangeHTTP.sessionFor(host:requested:)`). The
  address client has its own connection pool/dispatcher (iOS: own sessions). It hides the app name, not the fact
  that it is an app (TLS/HTTP fingerprint). A browser value only blends in while it is current: *Release
  readiness* runs `.github/scripts/user_agent_check.py` (`--self-test`): error if Android, iOS and the fixture differ
  or the value names the app or its version, and for a Firefox version whose estimated release is still in the
  future; warning once the Firefox version is estimated older than 6 months (assumption: a new major version every
  4 weeks up to Firefox 154 on 18 August 2026, every 2 weeks from then on – `REFERENCE_DATE` in the script). Then
  update `HttpUserAgent.NEUTRAL`, `HttpUserAgent.neutral` and `address_privacy.json` together.
  Redirects of address queries are followed only to the same host and never from https to http
  (`AddressRedirect.allowed`; Android `SameHostRedirectInterceptor` with `followRedirects(false)`, at most
  `MAX_HOPS` = 5, 307/308 keep method and body; iOS `SameHostRedirectDelegate` on `WatchedAddressSource.session`);
  any other redirect is the response (HTTP error, next host). Cases: `address_privacy.json` «redirectCases».
* **Live stream message cap (residual risk).** Android checks the 1 MiB cap only after OkHttp has read
  the whole message (text and binary → UTF-8 like iOS `.data`): OkHttp has no incoming size limit
  and always offers `permessage-deflate`, inflating before the listener (switching it off would need a network
  interceptor stripping `Sec-WebSocket-Extensions`; not done – more traffic, and uncompressed frames are unbounded
  too). A hostile or broken exchange endpoint (TLS-authenticated) could therefore exhaust memory before the cut;
  iOS limits inside `URLSessionWebSocketTask` (`maximumMessageSize`).
* **Stablecoin trades are swaps (CSV).** A buy/sell priced in a stablecoin (`PortfolioStables` set) is imported as
  a SWAP into/out of the stablecoin; only fiat (`CsvImport.FIAT`) makes a BUY/SELL. FR/AT treat crypto→stablecoin
  as neutral; a manual «Sell» always means a sale for fiat.
* **Tax rules by date.** US lot method is per wallet from tax year 2025; AT swaps before 1.3.2022 are disposals,
  Altvermögen = acquired before 1.3.2021; CA superficial loss and all gain/loss decisions use the tax currency
  when rates exist. Every rule has a fixture in `tax_templates.json`.
* **Background budgets.** Exact checks have a deadline (receiver ~19 s) and at most 8 s per exchange
  (`CheckBudget`); widget receivers stop after 9 s and draw from cache first. A timeout marks only that pair.
* **One path for price notifications.** Every place that turns a price notification on goes through
  `showPriceNow` / `Notifier.showPriceNow` (`PriceNotificationRule`): master switch and «pin» setting apply.
* **When two rows are the same pair (`WatchIdentity`).** Exchange, base, quote and contract — for DexScreener
  additionally the pool (`pairId` = `chainId/pairAddress`): two pools with the same symbol on the same chain are two
  rows. Exchanges keep the key without the id (ids are spelled differently in old entries). Used for adding
  (`WatchDao.findWatchId(…, pool)` / `AppData.insert`), editing (`WatchEdit.Key.pool`), undo (`restore`) and backup
  restore (`WatchlistRestoreCleanup`). Android's unique index includes `pairId`; NULL ids never collide in SQLite,
  so the app-side check stays the rule for exchanges.
  Cases in `testdata/parity/watch_identity.json`.

## 17. «Why?», futures data, chart and asset kinds

Rules behind the «Why?» sheet, the futures figures, the sheet chart and the Token / TradFi / Alpha
model. All pure logic exists on both platforms with the same names; the shared cases are in
`testdata/parity/why_analysis.json` (Android `WhyAnalysisParityTest`, iOS `WhyAnalysisParityTests`).
Change a rule → change the JSON first, then both implementations.

* **Thresholds relative to the coin's own volatility.** A 24 h move is «clear» only from
  `max(floor, k · σ₁ₕ · √24)` (`RelativeMove`, `WhyContext.kt` / `WhyContext.swift`). σ₁ₕ is the
  sample standard deviation of the hourly returns **before** the 24 h window (at most 72, at least
  24 returns, gaps skipped) – never include the window itself, or the move inflates its own
  threshold. Coin: k = 2, floor 2 %; market references: k = 1.5, floor 1 %; «strong» from 2 × the
  threshold. Without enough history the old fixed thresholds apply (3 % coin, 1.5 % market). The
  1 h volatility factor additionally needs an absolute move of at least 0.5 %
  (`VOLATILITY_MIN_MOVE_PERCENT`), so a tiny move on a very calm coin is never «unusual».
* **Three market references.** BTC, ETH (hourly candles through the existing candle chain, the same
  request for every coin) and the broad market (`BroadMarket`): the median 24 h change of the
  CoinGecko top 40 (`SpotMarket.TOP_URL`, fetched directly, the same request as the Spot tab, held
  10 minutes) without stablecoins, BTC, ETH, their wrapped/staked copies, gold
  tokens and the coin itself; at least 10 coins, data at most 3 h old, otherwise no breadth. «Market-
  wide» vs. «only this coin» looks at all available references. Never add a request that reveals the
  viewed coin for this – the top-40 request is the same for everyone.
* **Name every source and its age.** Each factor's data carries its provider (`WhySources`,
  `WhyFreshness.sources`): candles, comparison candles (only if another provider), futures, breadth
  (only if it was used), Fear & Greed (only with a value). Fallback sources are marked «(fallback)»,
  data of another exchange «(reference)»; the age lines read «Candles: Binance · 14:00», «Market
  breadth: CoinGecko · 13:05», «Futures: Bybit · open interest compared with 2 hours ago», «Fear &
  Greed: today» (UTC days, like alternative.me). A new factor must report its source.
* **Open interest history.** `OiSeries`: up to 5 samples, at least 30 min apart, at most 12 h old,
  only from the **same futures source** (a sample of another exchange restarts the history). The current value is compared with the **median** of the
  samples at least 30 min old; the direction counts as consistent only if the value is above (or
  below) every one of them, otherwise «Open interest mixed». Alarms keep their own 26 h history.
* **Funding trend.** `FundingTrendRules`: the last 4 settlements (`limit=4`) of the coin's USDT
  perpetual from Binance Futures / Bybit / OKX, the pair's own exchange first; fetched only when the
  sheet opens, at most once per pair every 10 minutes. Rates are compared per 8 hours.
* **Stablecoins and volume.** A USD stablecoin against USD or another USD stablecoin (`StablePeg`)
  is described by its deviation from 1:1 (notable from 0.5 %, strong from 2 %); market, volatility
  and position factors are dropped. Volume without quote volume is compared in coin units and marked
  «estimated»; with more than 6 of the 24 hours without trades the volume factor is left out.
* **Futures data per exchange.** `FuturesSources` (`domain/remote/FuturesExchanges.kt`, Swift in
  `FuturesExchanges.swift`): Binance, Bybit, OKX, WOO X, Bitget, MEXC, HTX, Hyperliquid and Deribit
  perpetuals use their **own** funding rate and open interest – requests go only to the exchange of
  the watched pair. Every other futures exchange (e.g. Phemex), a spot pair or a failing own source
  gets **reference** data from Binance → Bybit → OKX, and the UI must say so
  (`futures_reference_hint` / `futures_reference_spot`; `FuturesSources.note` tells own data,
  `FALLBACK` and `REFERENCE` apart). Funding and open interest differ per exchange: never show
  another exchange's figures without that label. Alarms (`DerivativesAlarmData`): funding alarms on
  reference data still fire and name the source (« · Reference: Binance Futures»); open-interest
  alarms pause, because mixing two exchanges' open interest in one history produces fake jumps.
  The settlement interval is read per contract (`FuturesInfo.fundingIntervalHours`): Binance
  `/fapi/v1/fundingInfo` (cached 6 h; a symbol not listed there settles every 8 h), Bybit
  `fundingIntervalHour`, OKX `nextFundingTime − fundingTime`, Bitget `ratePeriod`, MEXC
  `collectCycle`. Only sources with one fixed interval have a default (`FuturesSources.fundingIntervalHours`:
  Hyperliquid 1, Deribit / WOO X / HTX / Binance COIN-M 8); for the per-contract sources a missing
  interval is **unknown** (null) — never silently 8. A failed Binance list reload keeps the last list
  for up to 7 days. `intervalHours` is the effective value: the card says «per N h» (or «interval
  unknown») and, when it is not 8, the 8-h value the alarm uses (`futures_funding_per8h_equiv`);
  alarms convert to 8 h (`DerivativesAlarm.fundingPer8h(…, intervalHours)`) and skip the check when
  the interval is unknown. The «Why?» assessment treats unknown as 8 h (wording only, never alarms).
  The trend uses the reported current interval only, never one guessed from settlement spacing.
* **Binance COIN-M.** Pairs «2:BTCUSD_PERP» use `dapi.binance.com` (`premiumIndex` list,
  `openInterest` in contracts × contract value from `GET /dapi/v1/exchangeInfo` («contractSize» per
  symbol, `coinMContractUsd`; only without it BTC 100 USD, others 10 USD), `fundingRate` history;
  `FuturesParsers.binanceCoinM`, source «Binance COIN-M»). `FuturesSources.own(exchangeKey, pairId)` /
  `note(…, pairId)` make USDⓈ-M data on a COIN-M pair a reference. Always pass the pair id.
  Fixtures: `futures_derivatives.json` («intervals»), `alarms_derivatives.json`. A new futures source
  needs a parser test (`FuturesParsersTest` / `FuturesParsersTests`) and an entry in the privacy
  policy.
* **Bitcoin key figures: context, not verdicts.** `BitcoinIndicatorModel` shows measured values
  only. `IndicatorHistoryMath` (both platforms) adds for price-based figures (Mayer, distance to the
  high, 30 d change, Pi Cycle distance = SMA111 ÷ (2 × SMA350) − 1 in %) the share of days since
  June 2016 with a lower value (BTC daily closes from Coin Metrics via the data mirror; ATH before
  2016 = 1151; at least 365 values) and, for Mayer and Pi Cycle, the value in 30 days at an unchanged
  price. On-chain figures get no percentile (only about 14 months of history in the app). Never add
  zones, scores or «top/bottom» wording; the card always shows `ind_context_note`. The next halving
  is computed from the block height and the average block time of the current difficulty period
  (mempool.space, `HalvingMath.estimate`, UTC day, at most hourly), the calendar estimate stays as
  fallback. User thresholds (`IndicatorAlertKind` / `IndicatorAlertRules`, stored as integers,
  factors in hundredths, 0 = off; only values of `choices` count, `IndicatorAlertRules.active`) are checked twice a
  day (`ZoneCheckWorker` / `ZoneCheck`): notify once on crossing, re-arm when the value is back
  (`indicator_crossed_<kind>`). A failed fetch is retried up to 3 times after 30 s, 1 min, 2 min
  (`MarketNoticeGate.retryDelayMillis`; Android WorkManager backoff, iOS while the app is open).
  Market notices (Fear & Greed, indicator bounds, gas, USDT depeg) store their state together with the bound it
  belongs to; a missing or different bound counts as changed, so the notice is armed again
  (`MarketNoticeGate.boundChanged`, `IndicatorAlertRules.wasCrossed`, `GasAlertLogic.armedFor`, `DepegAlarm.armedFor`;
  parity `market_notice.json`). Switching a bound off resets it (`MarketAlertReset`) under the check's lock
  (Android `ZoneCheckPrefs.lock`, `GasAlertPrefs.lock`, `DepegAlarmPrefs.lock`; iOS a reset counter per check), so a
  check running at that moment cannot write the old state back.
  Tests: `BitcoinIndicatorModelTest`, `IndicatorAlertRulesTest`, `HalvingMathTest` (Swift in
  `ExchangesTests.swift`).
* **Candles come from the pair's own exchange first; fallback only labelled and same-token
  checked**. Sheet chart, mini chart, single-coin widget, the 24 h fallback reference and
  «Why?» ask the pair's own exchange first: `ExchangeCandleRegistry` with one `ExchangeCandleSource`
  per exchange (Android `data/remote/candles/ExchangeCandleSourcesMajor.kt` / `ExchangeCandleSourcesGlobal.kt`,
  `OwnCandleDataSource`, `OwnHourlyCandles`; iOS `ios/Shared/Services/Candles/`; parity
  `testdata/parity/own_candles.json`). Covered today: Binance, Binance.US, Binance USDⓈ-M, Coinbase,
  Kraken, Bitstamp, Bitvavo, Bybit (spot, linear), OKX (spot, swap), Bitfinex, Bitget (spot, USDT
  futures), Gate.io, KuCoin, MEXC, MEXC Futures, HTX, Gemini, Crypto.com, Upbit, WOO X (spot, perp),
  Poloniex, Deribit, Hyperliquid, One Trading. Own candles need no source label (provider = the
  pair's exchange). Only without own candles does the Binance … Coinbase chain (`CandleDataSource`)
  step in, and then: the series must match the pair's price (same token, `SheetChart.accept` /
  `PricePlausibility.sameToken`), a mismatch tries the **next** source instead of giving up or
  showing a foreign curve, futures pairs prefer USDⓈ-M (`preferFutures`), and the UI names the
  fallback source. TradFi pairs (stocks, metals, FX) never use the chain, and DexScreener pairs are
  bound to the remembered pool (`chain/pairAddress`) — no candles of another pool or exchange
  (`OwnCandlePolicy.allowsFallback`). Dated futures (week … quarter) use only their own contract's
  candles, never the perpetual's or the spot's. Volume alarms, quick alarms and «Why?»
  load a watchlist pair's hourly candles through the same acceptance as the chart
  (`VolumeDataSource.pairCandles` → `pairCandlesOf`, `OwnCandlePolicy.acceptsFallback`): own exchange
  first, chain only with the same-token check, no chain series without a price, for TradFi, DEX or
  dated futures. A new exchange source: object + registry entry on both
  platforms and a case in `own_candles.json`.
* **Chart FX per candle.** When a fiat pair has no candles of its own, the sheet chart uses the
  coin's USDT candles and converts **each candle with that day's rate** (ECB time series `usdToSeries`,
  the one the portfolio value history uses; at most once per currency every 6 h). Only if the series
  is missing it uses today's rate and the caption says «(current rate)» instead of «converted at
  daily rates». Never convert a whole history with one rate silently. USDT → USD likewise per candle, like the
  portfolio value history (`UsdtUsdRates.on`): today the live rate, earlier days the USDT/USD daily close, loaded
  through the portfolio's own source (`PortfolioHistorySource.usdtUsdDaily`: Kraken, gaps Coinbase, same cache – also
  for watchlist-only users). Per candle only when EVERY candle has its rate (`SheetChart.usdtSeries`); otherwise one
  rate for the whole series – the live rate with the note «USDT/USD history not available»
  (`usdtHistoryNote`, `sheet_chart_usdt_history_missing`), without a live rate 1 with «USDT = 1 assumed»
  (`usdtParityNote`) – never 1 in the past and live today (an invented step at the UTC day boundary). The 24 h
  fallback `DayChange.fromUsdtSeries` takes USDT/USD at both ends too (`usdtUsd`, `usdtUsdAtOpen`), by the same rule
  (`DayChange.usdtEnds`: both measured, else one rate for both ends – live, else 1; flags `usdtSeriesHistoryMissing` /
  `usdtSeriesApproximate`; parity
  `day_change.json`, `sheet_chart.json`); the mini chart's same-token check divides by the live USDT/USD.
* **Chart cache invalidation.** The stored chart (`SheetChart` cache, keyed by base and quote) is
  dropped on a manual refresh, pull-to-refresh, and when the live price has moved more than 3 % since
  it was stored (`SheetChart.PRICE_JUMP`, `priceJumped`). Axis minimum: a span of 1 % (±0.5 %) around
  the middle for stablecoins (`STABLE_MIN_SPAN`), 0.2 % (±0.1 %) for other flat series (`MIN_SPAN`); the
  widget charts use the same minimum spans (`WidgetChartGeometry`). Approximated volume bars (no quote volume from the source, e.g. Coinbase: amount × close) are
  drawn at half strength and marked «≈». Geometry is computed off the main thread.
* **AssetKind and Alpha.** `AssetKind.of(exchangeKey, base, tradFi, alphaSymbols)` (Android
  `domain/watch/AssetKind.kt`, iOS in `WatchFilter.swift`): TradFi from the pair-list flag first;
  Alpha only for symbols of the Binance Alpha list that Binance spot does not list regularly (from
  the logo index); never for Binance spot pairs or DEX pools (a foreign token may share the ticker);
  leverage prefixes count like the symbol («1000AIA» → «AIA»); an empty Alpha list means nothing is
  Alpha. Alpha **is also a token**: it appears under «Token» and under its own «α» chip, which is
  shown only when there are Alpha and other pairs. The setting «Incl. Alpha tokens» (default on) only
  filters the pair selection (`AssetScope.allows` with `hiddenAlpha`); saved pairs always stay. It is
  locked under «TradFi only» like QTLY and must stay in the backup and the settings search on both
  platforms. The row's kind icon (Token / TradFi / α) is grey and takes the accent colour only while
  its chip is selected; screen readers say «TradFi» / «Alpha token».
* **Widget group selection.** List widgets pick their group with `WidgetGroupSelection.pick` /
  `options` (both platforms, `WidgetGroupSelectionTest(s)`), including Token and Alpha. After the
  setup the widget is redrawn at once (Android serialises the draws, iOS redraws from the stored
  prices without a request).

## 18. Screen layout rules

* **Shared building blocks.** One card (`AppCard` / `.appCard(level:)`: top 20, nested 16, always
  `surfaceContainer`, never a border), one primary button (`AppFab` / `PortfolioAddButton`, accent fill, 56),
  one empty state (`EmptyState` / `EmptyStateView`: icon → title → message → example → button → links, scrolls),
  in everyday words (portfolio: example without a USDT price, button «Add your first purchase», then the
  text buttons «Bought on an exchange? Import its file» (keeps the `portfolio_import_csv` test tag) and «Just watch
  a wallet address»),
  one unavailable row (grey cloud icon, «Market data currently unavailable», Retry – red only for user-input
  errors). Tab bars grow to about 1.3× text size; beyond that iOS shows the Large Content Viewer, Android a
  tooltip. The watchlist base symbol is never truncated. Typography: display 34, headline 22, section title 16
  semibold; `labelSmall` / `.caption2` only for source credits, legal footers and chart labels – capped by
  `TypographyUsageTest`. A new screen uses these blocks instead of its own variants. The watchlist
  mini-chart is 48 instead of 56 dp/pt in rows narrower than 360 (`DenseRowText.sparklineWidth`, both platforms),
  and the watchlist exchange/time line and the converted price use body size (14 sp / `.subheadline`); other
  dense-row metadata (coin name, notes, errors, portfolio rows, market row captions) stays at 12 because a fixed
  line count would cut it at the largest font scale.
* **Plain-language market summary.** `MarketPlainSummary` (pure, both platforms, cases
  `market_plain_summary.json`) turns data the Market tab already has into three sentences (direction, mood,
  what stands out) shown above the cards; no new requests.
* **No change pills – app and widgets.** Every change is coloured text (arrow + signed value in the price colour, U+2212 for minus, grey for 0/unknown/deactivated, «Ø» averages in the warning text colour); the time window is always small secondary grey (rules in section 15). This holds for watchlist rows, the pulse line, the action sheet, «Market data», «Why?», the Market cards (Fear & Greed number in its scale colour, «Crypto market … ↘ −2.77% · 24h», «Unusual today» rows with grey «24h»), the Portfolio (Today · 7 d · Total, the 1-year line under the total, hidden while the history is shorter than one year) and all home-screen widgets. Status indicators (`DataStatusPill`) are not change pills and stay.

Layout decisions that keep the app calm. Same structure on Android and iOS, same file names.

* **Settings structure.** Main page (`SettingsScreen.kt` / `SettingsScreen.swift`): **General**
  (Language – on iOS it opens the system Settings app –, Currency, Watchlist, Portfolio, Alarms, Price updates),
  **Appearance** (Mode, Theme, Price colours, Coin logos, Widgets), **More** (Data / backup, About) and
  «Reset app» alone in its own red card at the very bottom. Portfolio has exactly one place: the row under General opens the Portfolio page (`PortfolioPage` /
  `PortfolioSettingsPage`) with the «Show Portfolio tab» switch on top, then lock, hide amounts, cost method, tax
  template and «Portfolio in system backup» – so «Settings › Portfolio» in hints and the widget is literally true;
  the search lists that page first for «Portfolio».
  There is no «Advanced» page: every setting sits where it belongs – Token & TradFi on the
  Watchlist page, Market alerts and Speech output on the Alerts page, Coin logos under Appearance, Developer on
  the About page (debug builds only). **Rule: the main page stays short.** A new detail or fine-tuning setting
  goes onto the existing sub-page it belongs to (watchlist → Watchlist, looks → Appearance, alerts → Alerts) or a
  new sub-page reached from there – never as a new row or group on the main page. The settings search
  (`SettingsSearch` / `SettingsSearchUi`) must still find **every** item: entries show the breadcrumb of the
  page that holds them and open it with the item highlighted. New items need a search entry (and a backup
  field, unless they are device-only flags). Hints that name a path use the full path, e.g. «Settings › Price
  updates › Live while open».
* **Settings icons.** The line icons before the main-page rows are monochrome in the text colour
  (white in dark mode, black in light mode) – Alarms a bell, Market alerts a lightning bolt; only
  «Reset app» keeps the error colour. The accent colour is reserved for the heading markers
  (accent bar), switches and selections (chosen row, check mark) – never for row icons.
* **Action sheet order.** Price header with one line «↗ +41.71% · 24h · Updated HH:MM» directly
  below (change in the price colour, period and time small and grey; no second row of changes), then
  the tiles Alarm · Why? · Favorite, then chart, range, «Market data» and «More». No second 24 h change
  next to the range on 24 h (the line under the price already shows it); on 7 d / 30 d / 1 y the
  change stays as coloured text, because it shows that range's change. The sheet opens
  at **half height** (price, actions, start of the chart) and can be dragged to full height.
* **Portfolio total card is a report.** The card shows the amounts only, on a calm
  gradient (8 % → 2 % of the accent colour); all hints (price sources, average cost,
  missing coins, scaled history, unchecked prices) go into a small «Notes» block **below** the card
  (`portfolio_card_hints`). A new hint goes there, never into the card.
* **«Why?» layout.** Fixed order, top to bottom: (1) the card «In short · As of HH:MM»
  (`why_brief_head`) with exactly one sentence, at most **3** notable factors as one grey text line
  and «Confidence»; (2) the card «All values and sources» (`why_all_values`), **collapsed by default**
  on every opening, holding «Observation», «Possible context», the sources line and the full
  disclaimer; (3) the short footer «Simultaneous does not mean cause · No investment advice»
  (`why_footer_short`). Rules: a new factor or value goes into «All values and sources», never into the
  top card; the top card gets no second sentence and no fourth factor; do not colour the factors
  (they are no price moves); do not move measured values back above the fold or open the card by
  default.
* **Alarm timing hint.** `AlarmTimingHint` (Android `domain/alarm/AlarmTimingHint.kt`, iOS in
  `AlarmLogic.swift`): shown once, after the first alarm is saved – folded into the first-alarm message
  (never two dialogs in a row) – and always available behind the (i) next to the «With the app closed …»
  line (alarm editor and portfolio alarm sheet). Text per platform: Android `alarm_timing_text_android`
  / `alarm_first_text_android`; iOS `alarm_timing_text_ios_open` – the system checks in the background
  about every 15 minutes or less often and «Live while open» only helps while the app is open, so on
  both platforms no choice suppresses the hint. Never promise faster checks with the app closed. The «shown» flag (`alarmTimingHintShown`) is device-only and not part of the backup.
* **iOS background time budget.** `RefreshBudget` (`BackgroundRefresh.swift`): network at most 15 s
  (`PriceRefresher(networkBudgetMillis:)` cancels the remaining exchanges), whole task 25 s, portfolio
  only with at least 6 s left, side checks with at least 5 s, 2 s reserve before the expiration; the
  task is cancelled at `remaining − reserve`. `SharedStorage.flushSnapshotWrites()` runs before
  `complete(success: true)` and before portfolio alarm notifications, so a widget never shows a value
  older than the alarm. `BackgroundOffBanner` warns above the alarms when Background App Refresh is
  off/restricted or Low Power Mode is on.
* **Backup alarms are validated.** Pair alarms go through `AlarmJson.parse` (Android) /
  `BackupManager.alarmFromJson` (iOS): unknown condition or a threshold ≤ 0 where it must be positive →
  invalid, counted with the portfolio entries in `invalidEntries` and named in the confirmation
  (`backup_restore_invalid`). Shared cases: `testdata/parity/backup_alarms.json`. A new alarm kind
  must be added to both parsers and the fixture.
* **Alarm suggestions never start already met.** The amount field of a new price alarm suggests the
  price + 5 % for «above» and − 5 % for «below» (`AlarmTemplates.suggestedThresholdText(price,
  condition)`, `SUGGESTED_OFFSET`, 3 significant digits). If the threshold is already reached when
  saving (`AlarmEdit.firesNow`, price in the threshold's currency), ask first («Alarm would trigger
  now» – «Save anyway» / «Change»); never save an already-met alarm silently.
* **Editing keeps the alarm state.** Saving an existing alarm re-arms it only when the condition
  changes – pair, condition, threshold, currency, or the window where it belongs to the condition
  (`AlarmEdit.conditionChanged` / `rearms` / `merged`). Sound, vibration, speech and «Once / Every
  time» keep on/off, last trigger and reference price; the one exception is a triggered one-time alarm
  switched to «Every time» (`reenables`). Cases in `AlarmEditTest` / `AlarmEditTests`.
* **Skipped checks are visible.** When a check must skip an alarm (no exchange rate for the alarm
  currency, no candles for «near high/low» or the volume spike), `AlarmCheckStatus` records «not checked
  since …» per alarm id and the alarm row shows «Could not be checked (no exchange rate / no candle
  data)». Memory only (this device, not in the backup, no database column); cleared by the next
  successful check or an app restart. Never treat a skipped check as «not met».
* **Calm defaults.** Non-dismissible («ongoing») price notifications are off
  (`AppSettings.ongoingNotifications = false`). New pairs (also the starter watchlist) get the price notification
  (`WatchRepository.DEFAULT_NOTIFICATION_ENABLED = true`: someone installing a price checker wants to see the
  price).
* **Watchlist header.** The add-pair button carries its text («+ Add pair», `AddPairButton`, iOS
  `addPairButton`; only the starter screen uses the round «+» because a text button sits below it).
  The alarm bell is always visible, marked when alarms are active (Android filled, iOS with waves), without
  a count. Its target follows `AlarmBellRoute.of(alarmCount, watchCount)` (Android
  `domain/watch/AlarmBellRoute.kt`, iOS `AlarmBellRoute.swift`, same test cases): with at least one alarm
  (everything the overview would list — pair alarms also when switched off, portfolio alarms only with the
  portfolio on; not loaded yet counts as «has alarms») the overview as before; with none it leads straight to
  creating one — one pair: its alarm page (which opens the editor), several: the pair picker of the empty
  overview first (`AlarmPairPicker`, iOS `confirmationDialog`), empty watchlist: add a coin (explorer). The
  starter screen's bell does the same; the menu item «All alarms» always opens the overview.
* **«What is moving with it?» is announced.** The starter screen (empty watchlist) and the first-run
  gesture hint carry one line «Tap a coin to see what is moving along with it» (`watch_hint_tap_why`; starter
  with the ⚡ symbol, gesture hint as its bold first line). Tapping a row stays the only way in (pair page).
* **Pair page instead of a half sheet.** Tapping a row (also a search result, a coin from «Unusual today» /
  «Gainers and losers», a notification or a widget row) opens a full page (Android: navigation destination
  `ScreenRoute.PairPage`, `PairScreen`; iOS: NavigationStack push, `PairScreen`), same order on both platforms:
  header (pair, exchange, large price that shrinks and wraps instead of truncating, change, price age as in the
  row; one sentence for screen readers, a heading), chart over the full width (260 dp/pt, same ranges as before),
  one primary button «Set alarm» (alarm editor), small buttons (favourite, «What is moving with it?», change
  exchange = `WatchEditSheet`, price notification), the pair's alarms with «Last checked» (`PairAlarmsSection`,
  editor flow shared with the alarm page via `AlarmEditorHost`), market data, note. Rare actions (group, note,
  portfolio, refresh, widget, read aloud, lock screen on iOS, move on iOS, disable, delete) sit in the ⋮ menu.
  A coin not in the watchlist opens a preview page («Add to watchlist»; afterwards the page of the new entry
  replaces it). Delete goes back and shows «Undo» where the page was opened from.
* **Thin watchlist row.** A row shows logo, pair, price, change (and mini chart if on) and
  at most **one** bell: the alarm bell with waves, only when the pair has at least one active alarm.
  The price-notification state is not drawn in the row (only on its switch in the pair page),
  and the source note for a foreign change / mini chart is in the pair page (see «Row source»).
  Sort arrows in the column header are 11 sp in `onSurfaceVariant` (no transparency) with a touch
  target of at least 48 dp (`WatchlistSortBar`). The alarm empty state (overview and per pair) shows
  an example sentence in the style of the alarm sentences («For example: Tell me when BTC rises above
  90,000.», `alarms_empty_example`).
* **Cut-off export is checked like the history.** `PricePlausibility.acceptSourceClose` is strict for the
  cut-off CSV: every source needs a valid current price; Binance spot must pass `sameToken`, other sources the
  25 % rule. Otherwise the price field stays empty with «unchecked» – never export an unchecked number.
  Format (`CutoffCsvFormat.forLocale`): UTF-8 with BOM, CRLF; separator and decimal mark follow
  the device region so Excel splits the file and reads numbers: Switzerland/Liechtenstein «;» with the
  region's decimal mark (de-CH «;» + «.»), other regions with decimal comma «;» + «,», all others
  «,» + «.».
* **iOS background deadline.** `RefreshDeadline`: one end time for every step; save prices and price alarms
  first, extra-data stages only while time remains (in parallel, cut off at the deadline); the task always
  completes exactly once. Deliverable = authorized/provisional/ephemeral and banner, lock screen or
  notification centre on.
* **Contrast is computed, not assumed.** Accent primaries (light) reach 4.5:1 on every surface up to
  `#E2E2E2`, price colours on every surface up to `#353535`/`#E2E2E2` (`AccentContrastTest`, price colour
  tests). Zone colours are only for non-text marks (≥ 3:1); numbers stay in text colour. The Fear & Greed card
  (like the pulse widget) shows the number large with the level word next to it and one level dot
  (`MarketScaleColors`; in the user's price colours via `MarketScalePalette.steps` — fear = down,
  greed = up, incl. colour-blind scheme, high contrast, swap and accent substitutes, so it matches the price arrows;
  the pulse widget derives the same steps from its own up/down colours) on a neutral `outlineVariant` track — no five-colour gradient. Widget text at least 11 sp/pt. Light dividers
  (`outlineVariant`, all light schemes and iOS `ColorTokens.outlineVariant`) are `#ADA9A0`:
  2.23:1 on `#FBF9F6`, deliberately below 3:1 – the only outline of a control is
  `outline`. Dark mode: `#474747`.
* **Disabled controls stay readable (≥ 3:1).** Material's 0.38 (light 2.3:1) is replaced: disabled text, icons,
  switch tracks/borders and segment borders use `onSurface` · 0.55 (Android `DisabledColors.content`, iOS
  `AppColors.disabledContent`), disabled button fills `onSurface` · 0.12, dimmed rows 0.7 (secondary text ≥ 3:1).
  Every Material element with `enabled = …` passes `DisabledColors.*()`; iOS buttons without their own style use
  `.appDisabled(_:)`, `AccentButtonStyle`/`TonalButtonStyle` grey out themselves. System menus, pickers and the
  system switch on iOS keep Apple's disabled look. Checked by `check_theme_contrast.py`, `ThemeContrastTest`,
  `ThemeContrastTests`.
* **Fixed exchange price colours** (decision: «colours like every exchange; the accent
  colour must not confuse»). «Fresh», «Traditional» and «Colour vision» keep one tone per direction for every
  accent; only the two «Calm» schemes dodge a similar accent. Each colour is the nearest (CIEDE2000) to the
  exchange's own tone, preferring the same HSL hue with only the lightness adjusted: «Fresh» = Binance
  `#0ECB81` / `#F6465D`, «Traditional» = TradingView `#089981` / `#F23645`, «Colour vision» = blue/orange
  (light `#1565C0` / `#E65100`, dark `#3D9BFF` / `#F7931A`). Hard rule: text contrast ≥ 4.5:1 (high contrast
  ≥ 7:1) on every light, warm and dark surface. «Fresh» and «Traditional» differ by ≥ 8 per colour
  («Traditional» is moved, e.g. dark falling `#FE7062`, ≈ 6°). Only «Colour vision»: rising vs falling
  ≥ 35 after protan, deutan and tritan simulation (Machado 2009, severity 1.0) and luminance ratio ≥ 1.4.
  No accent distance, no rule which direction is lighter. Checked by `android/tools/check_price_colors.py`
  (pure Python, prints target and distance, exit code 1 on a violation) and `PriceColorSchemeTest` /
  `SettingsLogicTests`.

  | Scheme | Light ↑ / ↓ | Dark ↑ / ↓ | High contrast light ↑ / ↓ | High contrast dark ↑ / ↓ |
  |---|---|---|---|---|
  | Fresh | `#127248` / `#C3213A` | `#0ECB81` / `#FC6F7D` | `#025430` / `#911127` | `#30DE92` / `#FFAEB7` |
  | Traditional | `#0A7262` / `#CC0416` | `#3EAE97` / `#FE7062` | `#005246` / `#960208` | `#5AD8BE` / `#FEB2A4` |
  | Colour vision | `#064E93` / `#B73900` | `#4F9FFD` / `#FFA73E` | `#00335F` / `#842A00` | `#CFEAFF` / `#FFB46A` |

  «Calm» dark falling (same decision): salmon red `#FFB097` (HSL 14°, C* 35.6),
  still 1.5:1 lighter than rising, also after deuteranopia simulation; beside the orange accent `#FFAFBB`
  (HSL 351°, ΔE 20.3 to `#ED835E`); beside the red accent plum stays (any red ≥ 24 from `#FF7173` is almost white).
* **Alarms wait for a deliverable notification.** One-time and repeating alarms (pair and portfolio, both
  platforms) fire only via `QuietHours.mayFire(holdAlarms, deliverable)`: during quiet hours or without a
  deliverable notification nothing is written and the alarm stays armed. Never mark an alarm as triggered
  before checking (iOS `Notifier.isAuthorized()`, Android `canShowAlarm` / `canShowPortfolioAlarm`).
* **Foreign candle series need the same token.** `SheetChart.accept` (pair page) and `sparklineFor` (mini
  chart) accept a series from another exchange only if its last close × FX rate is within
  `DayChange.MAX_PRICE_GAP` (25 %) of the pair price; no price or no rate → no chart. Never scale a series
  to the pair price without this check (cases in `testdata/parity/sheet_chart.json`).
  `PricePlausibility.sameToken` returns false without a valid reference.
* **Row source.** When the % change or the mini chart of a watchlist row come from another
  exchange than the pair's, the pair page says so under the price (`DayChange.rowSource` →
  «Change: …», «Chart: …», «Change & chart: …»); this note is not in the row
  itself (too dense). The row marks a % change from another exchange's candles with a small
  «*» in `onSurfaceVariant` after the value and says «Change from Binance candles» (`change_candle_source`) in
  the row's screen-reader sentence; no extra tap target, the sheet explains it. Rule
  `DayChange.rowChangeMarker(foreignSource, shownChange, sinceLast)`: only for a shown, finite value and not
  with the basis «since last update» (cases `rowChangeMarker` in `testdata/parity/day_change.json`). The
  provider comes from the same in-memory source as the sheet (`DayReferences.pillCandleSource` /
  `PriceRefresher.candlePills`), so after an app restart the marker appears with the next refresh. Never show
  foreign data without the name.
* **iOS background requests stay pending.** `BackgroundRefresh.scheduleRefresh` keeps an already
  pending `BGAppRefreshTaskRequest` (like Android `ExistingPeriodicWorkPolicy.UPDATE/KEEP`); it replaces
  it only when the interval changes or from inside the running task (`replace: true`). The earliest
  begin counts from the last refresh (`RefreshSchedule.earliestBegin`, at least 1 min from now). Never
  cancel and re-submit on every launch or background switch — frequent opening would push the
  closed-app alarm out indefinitely. The zone/indicator task follows the same rule (`scheduleZone`).
* **Store texts follow the code.** Quiet hours hold alarms (they ring afterwards if the condition
  still holds), with the app closed Android checks about every 15 minutes or less often (device and
  battery dependent), iOS checks when the system allows; there is no «Always frequent» mode.
  Any change to alarm timing or quiet hours needs the store texts in all 31 languages.
* **Widget text size.** Widget text follows the system text size with a cap: Android in sp (font scale, chart
  labels capped at 1.3×), iOS through `WidgetTextSize` – main texts and the lines in the same layout grow up to
  1.3× (`WidgetPrimaryFit`, falls back to base size when the grown layout does not fit), hints without such a layout
  (empty, locked, «choose a coin») up to 1.25× (`widgetSecondaryFont`). Fixed: symbols, labels inside badges and
  charts, header titles next to the logo, the compact Dynamic Island regions.
* **Widget previews.** The picker previews (PNG `widget_preview_*_img.png` and the Android 12+ preview
  layouts `layout/widget_preview_*.xml`) must show what the live widgets draw. When a
  widget's look changes, redraw its preview in the same change, with the shared sample data (BTC
  83,142.00, ETH 2,505.49, SOL 109.60, XRP 1.394 – Android `values/widget_preview_samples.xml`, iOS
  preview data in `Widgets/WidgetData.swift`). List dividers in the watchlist and portfolio list widgets
  are neutral (text colour at 25 %, 45 % with high contrast, `WidgetContrast.dividerColor` /
  `WidgetStyle.divider`), never the accent colour. Widget backgrounds are neutral too:
  the iOS widgets are flat like Android (plain `surfaceContainer`, no gradient, no accent glow; same
  logo, title and row sizes; disabled pairs grey instead of 60 % opacity).
* **Logo sync after a network failure.** `CoinLogoRetry` (both platforms): a failed list fetch without
  internet is not recorded as an attempt, and failed images are not blocked for a day when the network
  was the cause; the sync runs again when the connection returns and on the next foreground, with a
  growing pause between attempts (5 s → 15 s → 30 s → 60 s … at most 15 min, `backoffMillis`), and
  never two syncs in parallel. Same requests as a normal sync – no per-coin fetch.
* **File size.** Screens, view models, widget renderers and the notifier stay below ~450
  lines. A longer file is split by responsibility into files of the same package/folder – pure moves, no
  behaviour change – each with a German header comment (what it holds, why it is separate); a twin on the
  other platform gets the same file name. View models keep their state (flows, jobs, `@Published`) in the
  class; actions move into Kotlin extension functions (members they use become `internal`) or Swift
  `extension` files (`private` → internal, `private(set)` dropped only where an extension writes). Public
  API called from `data/`, `domain/` or `work/` stays a member. File map:
  watchlist VM → controllers that own their state privately: `WatchlistStarterController`,
  `WatchlistEditController`, `WatchlistRefreshController`, plus the injected `WatchlistSheetData` (iOS: data helpers of
  `WatchlistRow`) and `WatchlistGroupFilter`; the screen collects one `WatchlistUiState` and the live-price map; explorer VM →
  `ExplorerSearchActions`, `ExplorerSelectionActions`, `ExplorerBulkActions`, `ExplorerDexActions`,
  `ExplorerBrowseActions` (market chips, open/close an exchange; pure logic in `ExplorerBrowse`, views in
  `ExplorerBrowseUi`, both platforms); iOS
  `ExplorerRecentSearches`/`ExplorerQuickFilter` (as Android), `ExplorerPicker`, `ExplorerFeedback`; Android
  `ExplorerExchangePairs` (pairs of an opened exchange); market VM (`InfoViewModel` / `CycleViewModel`) → `MarketDataLoaders`,
  `MarketDataSync`, Android `MarketPreviewActions`; `CryptoPulseCard` → `CryptoPulseParts`; `MarketRow` →
  `MarketSectionHeader`, iOS `ExchangeInfo` and `MarketCoinSheets` (as Android); `WatchWhyFactors` →
  `WatchWhyTexts`, Android `WatchWhyAllValues`, `WatchWhyReasons`; `WatchlistRow` → `WatchlistRowParts`,
  Android `WatchlistConversion` (as iOS), iOS `WatchlistTime` in `WatchlistStatus` (as Android `ago`);
  Android `WatchlistStatusPill`; `PairScreen` → `PairHeader`; iOS asset-kind icons →
  `Components/AssetKindIcon.swift` (as Android); portfolio → `PortfolioTotalFigures`, `PortfolioStatusPill`,
  Android `PortfolioTotalHints`, `PortfolioHistoryHeader`, `PortfolioHistoryControls`,
  `PortfolioHistoryCompareSection`, `PortfolioChartRenderer`, `PortfolioCompareChart`,
  `PortfolioPositionCard`, `PortfolioTxRow`, `PortfolioExportTexts`, `PortfolioTaxSummaryCard`, Android
  `PortfolioAddressRow`, `PortfolioTaxPrices`; alarms → Android `AlarmDraft` (as iOS), `AlarmCard`; settings
  VM → `SettingsBackupActions`, `SettingsAppLockActions`, `SettingsAlarmActions`; `AppNavHost` →
  `AppTabBar`; portfolio widget → `PortfolioWidgetOverview`, `PortfolioWidgetList` (both), Android
  `PortfolioWidgetTexts`, `PortfolioWidgetValues`, `PortfolioWidgetChartView`; single widget → Android
  `SingleWidgetCandles`, `SingleWidgetChart`, iOS `SingleWidgetProvider`; `AppNotifier` →
  `AppNotifierPrices`, `AppNotifierDelivery`; portfolio VM (`PortfolioViewModel` / `PortfolioModel`) →
  `PortfolioLoadActions`, Android `PortfolioEditActions`, `PortfolioAddressActions`, `PortfolioTxDraft`,
  `PortfolioHistoryLoad` (both), iOS `PortfolioUi` (as Android); transaction sheet → `PortfolioQuickAdd`,
  `PortfolioTxFields` (iOS also `PortfolioTxLedgerFields`, `PortfolioTxActions`), Android `PortfolioTxInput`;
  CSV import → Android `PortfolioCsvImportViewModel`, `PortfolioCsvImportPreview`, iOS `PortfolioCsvImporter`,
  `PortfolioImports` (both); iOS `PortfolioScreen` → `PortfolioScreenActions`, `PortfolioToolbar`; `AppData.swift`
  → `AppData+Watchlist`, `+Portfolio`, `+Refresh`, `+Live`, `+Settings`, `+CoinLogos` (next to `+Alarms`,
  `+Reading`, `+WatchEdits`, `+WatchedAddresses`); `BackupManager.swift` → `BackupLimits.swift` (as Android),
  `BackupManager+Portfolio`, `+Watchlist`, `+Settings`; `WatchlistScreen.kt` → `WatchlistScreenEffects.kt`.
  Further splits: `PortfolioTxSheet.kt` (frame only; `TxFormState` in `PortfolioTxFormState.kt`, sections in
  `PortfolioTxSections.kt`, save checks in `PortfolioTxForm.check`), `TaxEngines` (one file per country engine),
  `TaxExport` (Rows/Summary/Csv; iOS in `App/Features/Portfolio/Tax/`), `PriceRefresher` (`RefreshLock` with a
  `RefreshLockScope` token, `LivePriceApplier`, `ChartRefresher`, `RefreshResultProcessor`, `RefreshSideChecks`),
  `CoinLogoRepository` (facade over `LogoImageStore`, `CoinListSync`, `AlphaTokens`, `CoinNames`).
  Production files over 650 lines are split by responsibility, with parallel
  names on both platforms: `BackupManager` → `BackupFormat`, `BackupWatchlistCodec`, `BackupSettingsCodec`,
  `BackupJournalCodec`; `AppNotifier` → `AppNotifierAlarms`, `AppNotifierMarket` (posting functions are extensions,
  call sites import them); `TaxExportSummary` → `TaxSummaryModel`, `TaxSummaryTexts` (both platforms);
  `WatchlistScreen` → `WatchlistScreenBody`; `PortfolioPriceSource` → `PortfolioPrices`, `…Store`, `…Exchanges`,
  `…Coins`; `PortfolioHistory` → `…Models`, `…Days`; `ActivityAnalyzer` → `ActivityWhy`; `CoinLogos` → `…Alpha`,
  `…StockNames`, `…IconPack`; `SettingsRepository` → `SettingsDataStore`, `SettingsKeys`; iOS `CoinLogoStore+Display/
  +Download/+Names`, `PortfolioWidgetPlan` → `PortfolioWidgetChartRules`, `…ChartGeometry`, `PortfolioWidgetViews`,
  `WidgetQuoteText`; `SharedStorage+Snapshot/+Files`, `WidgetGroupSelection`; `PortfolioWidgetStore(+Compute)`;
  `CutoffExport`, `HistoricPriceSource`; `WatchedAddressRules`, `WatchedAddressStore`; `FuturesExchanges(+FundingHistory)`;
  `Components` → `TickerView`, `CoinBadge`, `EmptyStateView`, `FactorRow`, `TabTitle`; `PriceRefresher+Outcome`;
  `PortfolioTx+Derived`, `PortfolioStore`; `PriceColorScheme`. Files between 450 and 650 lines remain (e.g.
  `PortfolioViewModel.kt`, `PortfolioScreen.kt`, `Bip32`, `PulseWidget`).
* **Provider names in texts.** A provider's name (exchange, data source) belongs in source lines («Source:
  CoinGecko», «Prices: Binance · …» – partly a licence duty) and wherever the name explains an outage or a data limit
  to the user: `why_no_data` («No data from Binance for this pair»), `coin_no_data`, `ledger_exchange_failed` /
  `ledger_exchange_mixed`, `alarm_volume_hint`. Notifications stay neutral (no provider name in title or body), and so
  do the privacy notices about address providers: the text says «the provider» / «public nodes», the host actually
  contacted is inserted at run time (`portfolio_address_privacy`). New texts follow this rule on both platforms and
  in all languages.
* **Backup wording.** The backup file is «Back up» / «Restore» (German «Sichern» / «Wiederherstellen») everywhere –
  buttons, titles, messages, errors, announcements. «Export» / «Import» are reserved for CSV (portfolio import,
  cut-off and tax export); every language uses its own natural backup/restore pair.

## 19. Storage encryption

User data is encrypted at rest on both platforms. The rule above all others: **never lose data** – a file that
cannot be opened is set aside, never deleted on the spot (only after the confirmed notice and the 30-day period,
`AsideRetention`); a plaintext copy is replaced only by a verified encrypted file, through
an atomic rename; without a key nothing with user data is written in plaintext.

**Android – SQLCipher for both Room databases.**
* Library: `net.zetetic:sqlcipher-android` (`@aar`, no transitive dependencies), version in the version catalog
  `android/gradle/libs.versions.toml` (`sqlcipher`). `androidx.sqlite` (`SupportSQLiteOpenHelper`, `sqlite-framework`) comes from
  `room-runtime` as an api dependency – no own pin. R8 keeps `net.zetetic.**` (JNI). Native library per ABI
  (`libsqlcipher.so`, 16 KB page aligned since 4.6.1).
* `LocalDataModule` gives both builders the same `EncryptingOpenHelperFactory`. It decides on the **first access**
  to a file (Room never touches the database on the main thread, so encrypting a restored system-backup copy never
  runs there) through `DatabaseOpenPlanner.prepare` (pure Kotlin, fakes in `DatabaseOpenPlannerTest`; file and SQL
  layer `SqlCipherDatabaseFiles`, keys `DatabaseKeyStore`). A database file is either absent or encrypted
  (`FileKind.ABSENT` / `ENCRYPTED`); every file is created and opened only encrypted. The decisions:
  - `Decision.Encrypted` – open (or let Room create) with `SupportOpenHelperFactory` and the raw key.
  - `Decision.Refused` – no file and no key could be created: no file is created; the database lives in an
    in-memory SQLite for this run (`DatabaseEncryptionNotice.markInMemory`/`inMemory`, banner
    `portfolio_storage_in_memory`; the portfolio callbacks skip the mirror takeover) and the next start tries again.
  - `Decision.KeyUnavailable` – the Keystore temporarily refuses the key of an encrypted file: the file stays
    untouched. `DatabaseOpenPlanner.planOpen` picks `KEEP_FILE_IN_MEMORY` (portfolio and watchlist, app in the
    foreground: empty in-memory database for this run, `DatabaseEncryptionNotice.storedKept`, the stored state comes
    back after a restart) or `WAIT` (`DatabaseKeyUnavailableException`, nothing changed). Background entry points
    skip the run and retry later, logged at debug level (`Throwable.isDatabaseKeyUnavailable()` checks the cause
    chain): `PriceUpdateWorker` returns `Result.retry()`, `ExactCheckReceiver` re-arms the next exact alarm, widget
    receivers (`launchAsync`/`ReceiverAsync`) keep the drawn state, `PriceWidgetService` keeps its rows (an
    exception there would kill the process). The UI shows its usual error state.
  Preparation runs under a process-wide lock per database (`EncryptingOpenHelperFactory.prepareLock`): the system
  backup builds its own factory.
* Keys: `DatabaseKeyStore` – per database 32 random bytes, wrapped with AES-256-GCM by a Keystore key of its own
  per database (`cryptochecker_db_key_app`, `…_portfolio`; `DatabaseKeyStore.aliasFor`), so a permanently broken
  Keystore key only affects that one database. Creating a Keystore key runs under a process-wide lock (two first
  opens at the same time would otherwise overwrite each other's key). No user authentication (WorkManager, widgets
  and alarm receivers must work after the first unlock; no component is `directBootAware`). Stored in `noBackupFilesDir/db_keys/<db>.key`
  (format: version byte, 12-byte IV, ciphertext + tag). SQLCipher gets the key as raw key `x'<64 hex>'`, so no
  PBKDF2 runs per connection. A key is only created while no encrypted file depends on it; a replaced one is kept,
  numbered, as `.previous`, `.previous.1` … (`Keys.previous`). Reading retries temporary Keystore errors
  (`DatabaseKeyRetry`, 3 × 500 ms); a suspicious failure (`UnrecoverableKeyException`, no key returned) counts as lost
  only when it happens again in a later run (`SuspectMarker`, `<db>.key-suspect`).
* Before Room opens an encrypted file the key is checked (`opens`): Room's `onCorruption` would delete a file that
  SQLite does not recognise. All own connections use an error handler that never deletes.
* Key permanently lost (invalidated, wrong tag, or no stored or previous key opens the file): first a previous key
  is tried (`previousOpening`, it then becomes the stored one again); otherwise the file is renamed to
  `<db>.unreadable` (+ `-wal/-shm`), a new key and an empty database are created and `StorageRecoveryNotice` shows
  «restore a backup file» (`storage_recovery_title/message`) until the user confirms it or restores a backup. If a
  previous key opens a set-aside file at a later start, it comes back (`restoreFromAside`, the meanwhile new file
  stays as `<db>.replaced`, notice «unlocked again»). Set-aside copies are deleted only after the notice was
  confirmed and at least 30 days after the app first saw them (`AsideRetention`, `<db>.aside-seen`); previous keys
  go once no set-aside file is left. Replaced files follow the same rule (`pruneReplaced`, `<db>.replaced-seen`),
  their «replaced» keys (`<db>.key.replaced…`, `Keys.discardReplaced`) go once no replaced file is left. Shared
  cases: `aside_retention.json`. With «Portfolio in system backup» on, a new portfolio database imports the local
  mirror on creation by itself.
* «Encryption not active yet»: `EncryptingOpenHelperFactory` records every decision in `DatabaseEncryptionNotice`
  (own SharedPreferences, never in a backup; rule `EncryptionPending`, pure Kotlin + `EncryptionPendingTest`):
  `Refused` adds the database (no key, kept in memory), `Encrypted` removes it, `KeyUnavailable` changes nothing.
  While the set is not empty, Settings › Backup shows «Encryption not active yet – the app will try again at the
  next start» (`backup_encryption_pending`); persisted, so it also covers a database not opened in this process
  (portfolio).
* System backup: the encrypted `cryptochecker.db` is useless on another device, so the backup rules carry
  `cryptochecker_system_backup.db` instead – `xml-v28/backup_rules.xml` (Android 9–11) and
  `data_extraction_rules.xml` (12+), **not** `xml/backup_rules.xml` (Android 8/8.1): API 26/27
  ignore `requireFlags="clientSideEncryption"` and would upload the plaintext copy without end-to-end encryption.
  There `SystemBackupAgent` does not create the copy at all (`SystemBackupScope.writesDatabaseCopy(sdkInt)`, same
  limit 28 as `PortfolioLockPolicy.SYSTEM_BACKUP_MIN_SDK`) and only runs the rule-based backup (favorites,
  language – not the settings DataStore, which holds the own Esplora server address); a restore on such a device
  brings no watchlist. `PortfolioLockPolicyTest` checks the three rule files, `BackupRulesAndroid8Test` the Android
  8/8.1 allow-list. `SystemBackupAgent` (`android:backupAgent`, `fullBackupOnly`) writes and verifies that plaintext
  copy only inside `onFullBackup` (`DatabaseSystemBackup.write`), runs the normal rule-based backup (`super`) and
  deletes it; if the copy fails, the backup run is aborted so the previous backup survives. After a restore,
  `onRestoreFinished` → `DatabaseSystemBackup.adoptRestored` encrypts the copy directly into `cryptochecker.db`
  (stored or new key, `SqlCipherDatabaseFiles.encryptCopy`) and deletes it. If no key is available in the backup
  process, the copy stays and the next start encrypts it before Room opens the database
  (`DatabaseOpenPlanner` → `Files.encryptRestoredCopy`; no key at that start either → `Refused`, the copy stays and no
  empty file displaces it). `encryptCopy`: `ATTACH … KEY` + `sqlcipher_export` + `user_version` into `<db>.encrypting`
  → fingerprint of source and copy must be identical (`DatabaseFingerprint`: user_version, per table row count +
  order-independent row checksum over all columns, incl. `sqlite_sequence`, without `android_metadata`) → delete
  `-wal/-shm/-journal` → rename the copy over the database → delete the plaintext copy. At start,
  `DatabaseSystemBackup.cleanUpLeftover` deletes a copy next to an existing database (an interrupted backup). The
  portfolio database and the key files are never in a backup; `portfolio_system_backup.json` stays the opt-in plain
  JSON mirror, present only during a backup or until the new portfolio database has imported it. The backup agent
  opens the portfolio database with `EncryptingOpenHelperFactory(readOnly = true)` (`DatabaseOpenPlanner.readOnly`):
  no set-aside, restore, pruning or new key; without a matching key it is `KeyUnavailable`, the mirror throws and
  the backup is aborted (the previous one survives) – never an empty portfolio from a set-aside database.
* Watchlist widget snapshot: `ListWidgetSnapshot` keeps the pairs of the last list-widget draw (fields the rows and
  header use, no note; `ListWidgetSnapshotCodec`) as one sealed entry `sealed` in the SharedPreferences
  `list_widget_snapshot` (container `PortfolioSnapshotSeal`, own alias `cryptochecker_list_widget_key`, no
  `SuspectMarker` – a cache). `WidgetUpdater.update` first calls `ListWidgetRenderer.drawStoredFirst` (only the first
  draw of a process, e.g. after a reboot: the stored rows at once instead of the blank initial layout), then draws
  from the database; every database draw saves the snapshot (not while the watchlist is memory-only).
* Portfolio widget snapshot: `PortfolioSnapshotStore` keeps the whole snapshot (totals, positions,
  P/L, daily/period changes, the held coins' price samples and hourly prices) as **one** sealed entry
  `sealed_snapshot` in the SharedPreferences `portfolio_widget` – the widget reads synchronously, a small blob
  decrypts fast. Payload: a JSON object with one key per part (`total`, `positions`, … `coin_pnl`); a missing
  or unreadable part loads as absent. Container and rules in `PortfolioSnapshotSeal` (pure Kotlin,
  harness + `PortfolioSnapshotSealTest`): Base64 of `CCWS` + version byte 1 + 12-byte IV + AES-256-GCM ciphertext
  and tag, magic and version as AAD; `planRead` (no key or no entry → nothing; otherwise open) and `planWrite` (no key
  → write nothing, a sealed entry stays). A broken, foreign or newer entry opens as «no snapshot» and the next save
  replaces it (it is a cache). Key: `PortfolioSnapshotKeyStore`, own Keystore alias `cryptochecker_widget_key`
  (no user authentication, created under a process-wide lock, a permanently broken entry is deleted and
  recreated) – deliberately independent of `DatabaseKeyStore`. The same class seals the restore journal
  (`cryptochecker_restore_journal`) and `alarm_log.sealed` (`cryptochecker_alarm_log`), but with a
  `SuspectMarker` (`DatabaseKeyStore.suspectMarker`): only an invalidated key is deleted at once, an
  `UnrecoverableKeyException` only when it repeats in a later run, and an entry the Keystore holds but does not
  hand out is never overwritten. Without a key every reader gets `null`, so
  widgets show their empty state (`widget_portfolio_empty`) and the Portfolio tab starts without the stored
  «Today» values until it has computed its own (`showStoredToday`). Still plain in that file: the remembered FX rate
  (`fx_*`, no holdings). Never in a backup rule.
* Other Android files outside the databases (no amounts or addresses): settings
  DataStore, `refresh_stats`, `activity`, `move_history`, `widgets` (widget configuration), `recent_searches`,
  `check_runs.txt`, `token_names.json`, coin lists/logos and `portfolio_last_prices.txt` (USDT prices of the held
  coins – reveals which coins, not how many; same as iOS). `portfolio_system_backup.json` is the documented
  opt-in plaintext mirror.
* Tests: `DatabaseOpenPlannerTest`, `DatabaseFingerprintTest`, `DatabaseKeyRetryTest`, `DatabaseKeyRecoveryTest`,
  `AsideRetentionTest`, `AsideRetentionParityTest`, `EncryptionPendingTest` (unit) and `DatabaseEncryptionTest` / `DatabaseEncryptionParallelTest`
  (androidTest, real SQLCipher and Keystore: unreadable without the key, lost key sets aside and a previous key brings
  it back, a temporarily unavailable key leaves the file untouched, system-backup copy round trip – encrypted on
  restore or, without a key, at the next start).

**iOS – sealed files in the App Group.**
* `Shared/Storage/ProtectedFile.swift`: format `ProtectedContainer` – `CCSE`, version byte 1, 12-byte nonce,
  AES-256-GCM ciphertext, 16-byte tag; magic + version are authenticated. JSON never starts with `CCSE`, so a
  plaintext file is recognised at once.
* Key: 256 bit in the keychain (`StorageKey`, service `com.cryptochecker.storage-key`), access group = the App Group
  `group.com.cryptochecker.app` (works with the existing `application-groups` entitlement for app and widgets; a
  `keychain-access-groups` entitlement is deliberately not added because it would change the default group of all
  keychain items). The item is created with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`: readable after the
  first unlock (widgets, background), but never part of an iCloud/computer backup and never moved to another
  iPhone — whoever gets hold of a device backup has the sealed files but never their key. Consequence, said up front
  under Settings › Backup on both platforms («Before switching devices, create a backup file…», `backup_device_bound`;
  Android Keystore keys are device-bound too): after restoring a device backup on a **new** iPhone the sealed files
  cannot be opened; `prepareAtLaunch` sets them aside (`.locked`) and `StorageRecoveryNotice` names it once — the
  way back is the backup file. Only the app creates the key; widgets only read it. «Reset app» deletes it and creates
  a new one (`StorageKey.renew`, generation marker so widgets re-read).
* Protected files (`ProtectedStorage.files`): `watchlist.json`, `portfolio_alarms.json`, `portfolio.json`,
  `watched_addresses.json`, the portfolio widget snapshot `portfolio_widget.json` and `alarm_log.json` are only ever
  written sealed (`sealedOnly`, `ProtectedFile.write(requireSealed:)`): without a key the write throws `.locked`, the
  caller keeps the state in memory and writes at the next save (e.g. `SharedStorage.watchlistPending` /
  `portfolioAlarmsPending`); the widget snapshot is then simply not stored (a cache) – never in the App Group
  defaults. Only the `.unreadable` copies of watchlist, portfolio alarms and portfolio (`ProtectedFile.writeCopy`) may
  be written plain without a key; the next start seals them. Plain on purpose (no amounts, addresses or notes;
  Android keeps them outside the databases too): price caches of the portfolio widget, `portfolio_last_prices.txt`,
  `check_runs.txt`, coin names/logos, `phemex_scales.json`, `alarm.lease`, App Group defaults.
* All reads/writes of these files go through `ProtectedFile`: `read` opens a sealed file, throws `.locked` without
  key or for a newer container version (callers treat it as «inaccessible»: never overwrite) and `.damaged(raw)` for
  a broken sealed file **or a plaintext file** (treated like corrupt JSON: raw bytes preserved). `write` seals,
  writes a sibling temp file, reads it back, decrypts and compares, then `rename(2)`.
* `ProtectedStorage.prepareAtLaunch` (first thing in `AppData.init`): keychain unreadable → touch nothing; no key
  but sealed files → move them to `<name>.locked` (never delete), then create the key and show
  `StorageRecoveryNotice`; then seal every plaintext `.unreadable` copy (coordinated like `SharedStorage`, only
  replaced if the file is still byte-identical, backup-exclusion flag restored) and bring back a `.locked` file the
  key opens (the meanwhile new file stays as `.replaced`). `.locked` and `.replaced` copies are excluded from the
  device backup (a restored `.locked` file takes over the flag of the file it replaces) and, only when the key was
  already stored at this start, deleted after the notice was confirmed and at least 30 days after the app first saw
  them (`AsideRetention.swift`, defaults key `protected_storage_aside_seen`, same cases as Android
  `aside_retention.json`). A plaintext `sealedOnly` file is left alone and reads as
  damaged. A copy left plaintext (keychain unreadable, no key possible, failed sealing) is listed in
  `LaunchOutcome.keptPlain` and kept out of the device backup while plain (`PlainBackupExclusion`);
  `encryptionPending` → Settings › Backup shows `backup_encryption_pending` like Android. Tests: `ProtectedFileTests`
  (fake keychain), `WatchlistSealedTests`, `SharedStorageTests`, `AsideRetentionTests`.
* File protection stays the system default (complete until first user authentication).

**Rules for later changes.** Never open an encrypted database with a helper that deletes on corruption; never create
a key while an encrypted file depends on the stored one; never create a database file or a user-data file without
encryption; new files with user data go into `ProtectedStorage.files` as `sealedOnly` (iOS) or a Room database / a
sealed entry like `PortfolioSnapshotSeal` (Android); a cache with amounts is rather not written than written in
plaintext; a file with the watchlist, holdings, amounts or addresses never goes into `xml/backup_rules.xml`
(Android 8/8.1 back it up without end-to-end encryption); a schema change needs a version bump plus a migration
(section 12), which Room runs on the encrypted file.

## 20. Modules, layers and cross-cutting rules

**Android modules** (`android/settings.gradle.kts`; a module's package is its name):

| Module | Package | Content |
|---|---|---|
| `:marketdata` | `com.cryptochecker.marketdata` | Exchange adapters, pair and ticker models, parsers – no Android UI, no storage |
| `:domain` | `com.cryptochecker.app.domain.…` | Rules, calculations and models (alarms, portfolio, tax, refresh decisions) – no Room, no OkHttp |
| `:data` | `com.cryptochecker.app.data.…` | Room databases with their rows and DAOs, encryption, settings, repositories, network sources |
| `:app` | `com.cryptochecker.app.…` | Screens (Compose), widgets, notifications, workers, backup, Hilt modules |
| `:wear` | `com.cryptochecker.wear` | Wear OS app and its complications (own APK, same applicationId) |
| `:baselineprofile` | – | Generator of the measured baseline profile (section 12), never shipped |

iOS has no modules: `ios/Shared` (logic for app and widgets), `ios/App`, `ios/Widgets`, `ios/Watch`, `ios/WatchWidgets`
and `ios/WatchCore` (code the iPhone app and the watch share) are folders of one Xcode project with four shipped targets.

* **Layer boundaries are tested.** `LayerBoundaryTest` (`:app` unit tests) reads the sources: every file of `:domain`
  and `:data` sits in the package and folder of its module; `:domain` imports neither `androidx.room` nor `okhttp3`
  nor another app package; `:data` imports from the app only `domain` and `data`; `:app` has neither a `…app.domain`
  nor a `…app.data` package (its code that needs app classes sits in packages of its own such as `…app.backup`,
  `…app.logos`, `…app.chart` and `…app.alarm`), and no package occurs in `src/main` of two modules. The models `WatchEntity`/`AlarmEntity` are plain classes in `:domain`, the tables `WatchRow`/`AlarmRow`
  live in `:data` (`toDomain()`/`toRow()`). Classes named in the manifest or stored by class name (workers,
  receivers, activities, widget providers, `SystemBackupAgent`, `AppDatabase`, `PortfolioDatabase`) keep their names.
* **Dispatchers are injected.** A class built by Hilt takes `@IoDispatcher` / `@DefaultDispatcher`
  (`DispatcherQualifiers` in `:domain`, provided by `DispatcherModule` in `:app`) in its constructor instead of naming
  `Dispatchers.IO` / `Dispatchers.Default`; tests pass their own. `DispatcherInjectionGuardTest` reads every Kotlin
  main file of `:app`, `:data` and `:domain` (not only the classes built by Hilt; `DispatcherModule` itself is the
  one exception) and counts the remaining hard-wired places per file in its allowlist `REMAINING`. The current
  number is the sum of that list in the test – it is not repeated here, so it cannot go stale; the numbers may only
  go down. `:wear` is outside the guard.
* **Permissions.** The merged release manifest holds exactly 13 permissions, each with its reason in
  `manifest_policy.py` (`EXPECTED`): `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`,
  `RECEIVE_BOOT_COMPLETED`, `VIBRATE`, `WAKE_LOCK`, `SCHEDULE_EXACT_ALARM`, `SYSTEM_ALERT_WINDOW`,
  `HIDE_OVERLAY_WINDOWS`, `USE_BIOMETRIC`, `USE_FINGERPRINT` and AndroidX core's
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Any other permission fails the *Android* workflow – a new one is a
  decision (manifest comment, `EXPECTED`, privacy page, Play «Data safety»).
* **Encrypted connections only.** `network_security_config.xml` (app and Wear OS app) forbids cleartext for every
  host; no pinning (many exchanges, changing certificates) and no own trust anchors.

**Workflows at a glance** (`.github/workflows/`; details in section 12 and «Data mirror»). A release tag `v*` runs
the nine marked ⬤ for the tagged commit, whatever the path filters say. «by hand» = *Actions › … › Run workflow*.
The required checks of `main` are the six jobs «Android build», «Android device tests», «iOS build», «Strings check»,
«Parity check» and «Release readiness check»; their workflows run on every pull request without a path filter. A
pull request that changes only Markdown (or issue templates) skips the heavy jobs of `android.yml` and `ios.yml`
(job «changes», like `codeql.yml`); their collectors then report green.

| Workflow | Runs on | Purpose |
|---|---|---|
| ⬤ `android.yml` *Android* | every pull request; `main` (`android/`, the checked Swift sources, its scripts); by hand | three parallel jobs – unit tests with source checks, Kotlin warnings, Room schema drift and coverage gate · lint with the instrumented tests compiled · debug and R8 release build with manifest and APK audit and SBOM – collected by the job «Android build»; dependency graph (`main`) and review (pull requests) |
| ⬤ `android-device-tests.yml` | every pull request; `main` (`android/`, its scripts); by hand | instrumented tests on an emulator (API 34, on `main` also the ICU tests on API 26), R8 smoke test; on pull requests the job «changes» picks: all device tests for source changes, the migration tests for schema-only changes, the smoke test for release-build files – collected by the job «Android device tests» (jobs not picked count as green) |
| ⬤ `ios.yml` *iOS* | every pull request; `main` (`ios/`, shared fixtures, its scripts); by hand | release build with audit (settings, bundle contents, size ceilings), unit tests with coverage gate, UI tests – collected by the job «iOS build» |
| ⬤ `strings.yml` *Strings* | every pull request; `main` (string resources, Kotlin and Swift sources, `ios/tools/`, catalogues); by hand | placeholders, plurals, missing translations (`check_strings.py`), Serbian Latin, string catalogues up to date including the small watch catalogue and its key list |
| ⬤ `parity.yml` *Parity fixtures* | every pull request; `main` (fixtures, `gen_xcodeproj.py`); by hand | `android/testdata/parity` = `ios/Tests/Parity`, byte for byte |
| ⬤ `release-readiness.yml` | every pull request; `main`; by hand | store text limits, version parity, Xcode project drift, User-Agent, iOS privacy manifest |
| ⬤ `osv.yml` *Dependency vulnerabilities* | pull requests and `main` (`android/`, the check itself); weekly (Tuesday); by hand | OSV check of the release classpath |
| ⬤ `reproducible.yml` | weekly (Sunday); by hand; pull requests that change the check itself – not on pushes to `main` | two release builds compared byte for byte; provenance attestation on tags |
| ⬤ `screenshots.yml` | by hand – not on pushes to `main` | screenshot tour of both apps; published to the branch `screenshots` from a release tag or a manual run on `main` |
| `codeql.yml` *CodeQL* | `main` (every push); weekly (Wednesday); by hand; every pull request, there only the languages whose files changed (job «changes»; none for documents, translations or test data) | code scanning for Kotlin/Java (debug compile by hand, dependency verification on), Swift, Python and the workflows, with the `security-extended` query suite; results under Security › Code scanning |
| `ios-performance.yml` | weekly (Thursday); by hand; pull requests that change the performance tests or the workflow | iOS performance tests in a Release build |
| `mutation.yml` | weekly (Saturday); by hand | mutation tests of the portfolio and tax logic |
| `baseline-profile.yml` | by hand | measured baseline profile (artifact) |
| `supply-chain-update.yml` | by hand (monthly and before a release, see «Supply chain») | regenerates verification metadata, keyring and lock files (branch `supply-chain/update`) |
| `record-exchange-fixtures.yml` | by hand | records real exchange responses for the contract tests (artifact, nothing committed) |
| `exchange-live-check.yml` | weekly (Wednesday); by hand | calls every adapter's public endpoint, one issue on failure |
| `market-data.yml` | hourly; by hand | signed data mirror (release `data`) |
| `macro-calendar.yml` | weekly (Monday); by hand | economic calendar (branch `macro`), then Pages |
| `pages.yml` | `main` (`docs/`); by hand; called by the calendar | GitHub Pages: start page, privacy policy, calendar |
| `keepalive.yml` | weekly (Sunday); by hand | re-enables the scheduled workflows through the API (GitHub disables them after 60 days without activity) |

Artifacts are kept 14 days (test reports, lint reports, logs, baseline profile), the SBOM and the Pages archive 90 days, the
release evidence and recorded exchange responses 30 days; hand-over artifacts between jobs 1–7 days.

**Rules added with the widgets, the watch and the audit fixes** (same on both platforms unless noted):

* **Widget status line.** `WidgetStatusRule` (parity `widget_status.json`): offline › outdated › time – without
  a connection «No connection · as of 14:05» (time of the last successful refresh), the rows keep their last prices.
  Android schedules a one-time `WidgetReconnectWorker` with a network constraint, iOS asks WidgetKit for an early
  reload. Android widgets start from an initial layout in the system's light or dark colours
  (`colors_widget_initial`) with grey placeholder rows and one loader (`widget_skeleton_loading`) until the app has
  drawn them – never a white area after a reboot.
* **Logo hints in the watchlist.** `LogoLoadingHint` shows «Loading logos · 40 %» only during the first load (at most
  99 % while running, throttled); afterwards `MissingLogoNotice` shows «2 coins without a logo» in the same place
  until it is closed (parity `coin_logos.json`, `missing_logos.json`). DEX pools and symbols that always show
  initials never count.
* **Watch complications show the price.** Small slots show the coin and its price in at most about seven characters
  (`WatchFormat.compactPrice` / `WatchPriceFormat`, parity `watch_price.json`), the long slot pair, price and 24 h
  change; the change is always in the spoken text. The portfolio complication says «turn on on the phone» while the
  phone switch is off instead of staying empty.
* **«Check on time» follows the interval** (Android): the exact alarm fires at the chosen background interval, never
  below 15 minutes (`BackgroundSchedule.exactIntervalMillis`, parity `exact_cadence.json`). While it is armed, a
  WorkManager run is skipped unless the interval minus a tolerance of 2 minutes has passed since the last full check
  (`dedupeWindowMillis`; the full check is stamped at the end of a run, the next WorkManager run starts one interval
  after the start of the previous one – without the tolerance every second run was skipped when the alarm stayed
  away) – WorkManager stays scheduled as the net below the alarm. The alarm is armed whenever there is something to
  check on time: active pairs, an armed portfolio alarm or a gas/depeg alarm (`ExactCheckDemand`,
  `BackgroundWorkPolicy.exactNeeded` – the same source for the receiver, the worker, the switch and the text
  «nothing to check»). iOS widgets reload at the same interval.
* **Portfolio alarms are private on the lock screen** – always, also without the portfolio lock: on both platforms
  `PortfolioAlarmNotice` puts only the title and a neutral sentence into the notification, no threshold, amount or
  percentage in any field (Android `showPortfolioAlarm(alarmId)` receives nothing else). `VISIBILITY_PRIVATE` alone
  would not do: Android shows the full text of a private notification unless the user hides sensitive content on the
  lock screen. Android still sets it, with the same neutral public version. The details are in the app (alarm list
  and log). Price alarms keep following the lock setting.
* **Sensitive screens hide foreign overlays** (Android 12+): `HideOverlaysEffect` (`ui/lock/HideOverlays.kt`,
  counted per window by `OverlayShield`) is active on the portfolio, the portfolio settings and the restore and
  «Reset app» confirmations; the app's own price overlay disappears with them.
* **A backup with portfolio data needs a password.** `PortfolioLockPolicy.backupExportRequiresPassword`: with at
  least one transaction, watched address or portfolio alarm (`backupHasPortfolioData`, parity
  `backup_portfolio_data.json` – an alarm threshold such as «above 250,000 CHF» reveals the size of the holdings) the
  password switch is on and locked and there is no «export without a password», and exporting while locked asks to
  unlock first; a file without portfolio data keeps the choice. Old unencrypted files can still be restored.
* **Destructive or far-reaching taps ask first.** Swiping a transaction away asks like the editor (no Undo banner
  afterwards); changing the cost method and switching «Portfolio in system backup» on ask too (parity
  `settings_confirm.json`).

## Build notes

One short technical note per build (classes, fixtures, checks), newest last. Only the builds since the last tidy-up
are listed here: the notes of all earlier builds are in [`CHANGELOG.md`](CHANGELOG.md) under «Technical notes» of
their build entry. At a release tag, or when this list has more than ten entries, move the entries there the same way.

<!-- Bookkeeping: add the line «* Build NNN: …» at the end of this list, directly after the previous build. -->

* Build 222 (audit of 220: S-02, S-04, S-06, F-04 to F-08, F-10, F-11, U-02 to U-11; F-09 was wrong): `ExactCheckDemand`, `RestoreSteps.execute`, new fixtures `price_format.json`, `full_check.json`, `restore_steps.json`; text rule for provider names in section 18.
* Build 223 (audit of 220: C-02, C-03, C-04 partly, C-05, C-06, C-08, C-10, P-01, P-03, P-05 to P-08, P-10, D-01 to D-05): `test-shared/java` (`ParityJson`), packages `app.backup`/`logos`/`chart`/`alarm`, `compose-stability.conf`, `WatchTaskAwait`, `PriceRefresher+Steps.swift`, watch string catalogue `ios/Watch/Device/Localizable.xcstrings` + `watch_string_keys.txt`.
* Build 224: baseline/startup profile re-measured (workflow «Baseline profile» on build 223, 12'657 app rules); `ios_release_audit.py` size ceilings 140/40/4.5 MB (measured 125.6/36.3/3.8 MB).
* Build 225: `ExchangeRetirement` (Kotlin + Swift, instant 2027-02-01T00:00Z, reuses `NotTraded` with a stored reason; fixture `exchange_retirement.json`), `backup_restore_storage_unavailable`, proofreading of 15 languages.
* Build 226: audit of 225 (F2-01…F2-08, U2-01…U2-12, S2-01, S2-02, S2-05, C2-05, P2-04, D2-03, D2-05): one rule `retiredNow` (marker or instant), advance notice from 2027-01-01, debug switch «Stichtag vorziehen», retired-notice store in `:data`, `bump.py` bookkeeping no longer touches running text.
* Build 227: audit of 225 (C2-01…C2-04, P2-01…P2-07, S2-03, S2-04, S2-06, D2-02, D2-04): sixth required check «Android device tests», startup-profile journey, `supply_chain_files.py --check` rejects ignored+trusted keys, watch key literal check.
* Build 228: Baseline and startup profiles re-measured on main after build 227 (own startup journey).
* Build 229: mutation_test.py self-test: file mode 0o640 -> 0o600 (CodeQL «Overly permissive file permissions»); ios.yml UI tests: Simulator booted first (simctl bootstatus), per-test timeout 600 s, step limit 50 min.
* Build 230: SettingsDataPages: HideOverlaysEffect(exportDialog != null) for the backup password dialog (external review of build 229).

## Known warnings

Warnings that are not fixed, each with the reason. Everything else is fixed; CI keeps compiler warnings at 0.

* **Swift `@unchecked Sendable`: none left (ceiling 0, like `nonisolated(unsafe)` and `DispatchSemaphore`).** The
  exchange adapters are protocols with `struct`s (Gemini: `final class` with its cache in `OSAllocatedUnfairLock`);
  every other lock box is checked `Sendable` as well. Seven places hand a non-`Sendable` object to
  `OSAllocatedUnfairLock(uncheckedState:)` and touch it only in `withLock` (`NumberFormatterCache`, `IsoDates`,
  `DateFormatterCache`, `AlarmDelivery` with its `UserDefaults`, `MoveHistoryCore` with its storage,
  `BackgroundTaskCompletion` with the `BGTask`, `NotificationCompletion` with the SDK completion handler): the object
  never leaves the lock, but its own thread safety is still trusted, not proven. `swift_escape_hatches.py` counts
  them as `uncheckedState` (ceiling 7).
