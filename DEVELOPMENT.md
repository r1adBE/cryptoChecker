# Adding an exchange

> **Deutsch:** Anleitung für neue Börsen-Adapter in der Börsen-Bibliothek
> (Android/Kotlin und iOS/Swift): Aufbau, Dateien, Paarliste, Einzel- und
> Massenabfrage, Tests. Fragen und Pull Requests gern auf Deutsch oder Englisch.

<!-- DEVELOPMENT.md at the repository root: paths and links are relative to the root. -->

This guide explains how an exchange adapter of the market-data library works
and how to add a new one. The example throughout is **Bitstamp**, one of the
exchanges the app uses today:

* Kotlin: [`android/marketdata/…/model/market/Bitstamp.kt`](android/marketdata/src/main/java/com/cryptochecker/marketdata/model/market/Bitstamp.kt)
* Swift: [`ios/Shared/Markets/Bitstamp.swift`](ios/Shared/Markets/Bitstamp.swift)

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

## 1. Structure

| Path | Content |
|---|---|
| `android/marketdata/src/main/java/com/cryptochecker/marketdata/model/Market.kt` | Base class: request URLs and parsing hooks |
| `android/marketdata/…/model/market/generic/SimpleMarket.kt` | Base class for the usual case «pair list URL + ticker URL» |
| `android/marketdata/…/model/market/*.kt` | One file per exchange (or per market, e.g. spot and futures) |
| `android/marketdata/…/model/Ticker.kt`, `SimpleTicker.kt` | Price data of one pair |
| `android/marketdata/…/model/MarketPair.kt` | One tradable pair: base, quote, exchange-specific pair id |
| `android/marketdata/…/config/MarketsConfig.kt` | List of registered exchanges |
| `android/marketdata/…/util/JsonUtils.kt` | JSON helpers (`forEachJSONObject`, `optDoubleOrMissing`, …) |
| `ios/Shared/Markets/Market.swift`, `ios/Shared/Markets/MarketsConfig.swift`, `ios/Shared/Markets/JsonUtils.swift` | The same on iOS |

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
| Android | `android/marketdata/…/model/market/Example.kt` | new adapter |
| Android | `android/marketdata/…/config/MarketsConfig.kt` | `Example()` added |
| iOS | `ios/Shared/Markets/Example.swift` | the same adapter in Swift |
| iOS | `ios/Shared/Markets/MarketsConfig.swift` | `Example()` added |
| Android | `android/app/…/data/remote/candles/ExchangeCandleSources*.kt` | optional: own candles (`ExchangeCandleSource` + `ExchangeCandleRegistry`) |
| iOS | `ios/Shared/Services/Candles/ExchangeCandleSources*.swift` | the same candle source in Swift, case in `testdata/parity/own_candles.json` |
| Both | ticker parsing | set `exchangeTime` when the API sends the time of the price (see [section 15](#15-ui-rules-for-numbers), «Exchange time vs fetch time») |

After adding a Swift file, regenerate the Xcode project with
`python3 ios/tools/gen_xcodeproj.py` (it picks up new files by itself).

Both platforms must behave the same: same pair ids, same fields, same
fallbacks. Backups can be moved between Android and iOS, so the **key** of the
exchange must be identical on both sides (see [section 8](#8-registering-the-exchange)).

## 3. The adapter

Every adapter extends `Market` and answers three questions: *which address*
(`tickerAddress`, `pairListAddress`, bulk URLs), *how to read the answer*
(`fillTicker…`, `fillPairList…`, `parseBulkTickers`) and *how to read an error*
(`errorMessage…`). Networking, retries, size limits and storage live outside the
adapter (`MarketRemoteDataSource` on Android, `MarketService` on iOS).

Most exchanges fit `SimpleMarket`: exactly one fixed pair-list address and one
ticker address with the pair id as `%1$s`. Pass its arguments by name.

```kotlin
private const val BITSTAMP_API = "https://www.bitstamp.net/api/v2"

/** Bitstamp (EU regulated, also EUR/GBP pairs), API v2. Pair id = `url_symbol` («btcusd»). */
class Bitstamp : SimpleMarket(
    name = "Bitstamp",                                   // name shown in the app
    pairListUrl = "$BITSTAMP_API/trading-pairs-info/",   // pair list
    tickerUrl = "$BITSTAMP_API/ticker/%1\$s/",           // ticker, %1$s = pair id
    errorField = "reason",                               // error text in error responses
)
```

```swift
private let bitstampAPI = "https://www.bitstamp.net/api/v2"

final class Bitstamp: SimpleMarket {
    init() {
        super.init(
            key: "Bitstamp",
            name: "Bitstamp",
            pairListURL: bitstampAPI + "/trading-pairs-info/",
            tickerURL: bitstampAPI + "/ticker/%1$s/",
            errorField: "reason"
        )
    }
}
```

`SimpleMarket` also takes an optional `ttsName` – the name used for spoken
announcements when the written name reads badly (e.g. «Gate I O» for «Gate.io») –
and `currencyPairs`, a built-in pair table for exchanges without a pair endpoint.
Override `pairId(info)` (Swift: `pairId(_:)`) if the id in the URL differs from the
stored one.

If the exchange needs something `SimpleMarket` does not cover (POST requests,
several requests per price, a ticker address built from base and quote, several
pair-list addresses), extend `Market` directly, override `tickerAddress(call, info)`
(Swift: `tickerAddress(call:info:)`) and say why in the class comment; see
[section 10](#10-advanced-cases). Exchanges with several products share a base
named `<Exchange>Base` (e.g. `BybitBase` for spot and futures).

**File layout (both platforms, same order).** File-private constants
`<EXCHANGE>_<WHAT>` (Swift: `let`), the concrete classes in `MarketsConfig` order,
then a shared base. Inside a class: hooks and state, traits, then the sections
`// ── Einzelkurs ──`, `// ── Sammelabfrage ──`, `// ── Paarliste ──`,
`// ── Namen ──`, `// ── Lesen ──` (Swift: `// MARK:` with the same titles). The
reader shared by single and bulk ticker is always called `copyRow`. A Kotlin file
and its Swift twin should read side by side.

## 4. Pair list

Override `fillPairList(call, body, out)` (whole response as text) or
`fillPairListJson(call, json, out)` (response is a JSON object) and add one
`MarketPair(base, quote, pairId)` per tradable pair. Swift returns the list:
`fillPairList(call:body:)` / `fillPairList(call:json:)`.

Offer only pairs that can be traded normally: Binance `status == "TRADING"`, Kraken AssetPairs
`status == "online"` (since build 69; `cancel_only`, `post_only`, `limit_only`, `reduce_only` are dropped, a missing
`status` in older responses counts as online). A watched pair that drops out is not removed: its row ends as «no
longer traded» through the bulk/single path below (section 6). Kraken reports such pairs with close price 0; the
single ticker treats that as «no price» (not 0), so `NotTraded.confirmedBySingle` marks the pair instead of the
old price staying silently (`KrakenTradableTest` / `KrakenTradableTests` on `recorded/recorded_kraken.json`).

```kotlin
override fun fillPairList(call: Int, body: String, out: MutableList<MarketPair>) {
    JSONArray(body).forEachJSONObject { entry ->
        if (entry.optString("trading") != "Enabled") return@forEachJSONObject   // skip disabled pairs
        val label = entry.getString("name")                                      // e.g. BTC/USD
        val slash = label.indexOf('/')
        if (slash < 0) return@forEachJSONObject
        out += MarketPair(label.substring(0, slash), label.substring(slash + 1), entry.getString("url_symbol"))
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
  Cases live in `testdata/parity/not_traded.json`. A single-ticker parser must
  therefore throw (or return the exchange's error) for an unknown pair, never
  return a price of 0. The own reader text `MarketParseException.NO_DATA` / `JSONError.noData` («No data») and
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
`{"reason": "…"}` – `errorField = "reason"` is enough for `SimpleMarket`.
For other formats override `errorMessageJson(call, json, info)` or
`errorMessage(call, body, info)` (Swift: `errorMessage(call:json:info:)` /
`errorMessage(call:body:info:)`) and return the exchange's message; call
`noErrorText()` when the response carries none. The app shows it to the user.

## 8. Registering the exchange

Every Android adapter declares its storage key explicitly – `override val key = "Example"` (the class name; it is
the database key of every saved pair, so it must never change). `MarketKeyTest` lists all keys and fails for a
missing or changed one. Then add the adapter to both lists:

```kotlin
// android/marketdata/…/config/MarketsConfig.kt
val MARKETS: Map<String, Market> = listOf(
    …
    Bitstamp(),
    …
).associateBy(Market::key)
```

```swift
// ios/Shared/Markets/MarketsConfig.swift
static let all: [Market] = [
    …
    Bitstamp(),
    …
]
```

The **key** identifies the exchange in saved watchlists, alarms and backups:

* Android uses the class name (`Bitstamp`), iOS the `key:` parameter – they must
  be equal.
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

**POST requests.** Return a `PostRequestInfo` from `tickerPostBody`,
`pairListPostBody` or `getBulkTickersPostRequestInfo`.

**Futures.** Use a separate adapter (e.g. `BinanceFutures` next to `Binance`)
and set the `contractType` of `MarketPair`.

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
  (Swift: `enum Change24h` in `ios/Shared/Markets/MarketModels.swift`); they
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
| BingX, BingX Futures | `openPrice` | price 24 h ago; the spot `priceChangePercent` is text with «%» and not used |
| bitbank | `open` | price 24 h ago |
| bitFlyer | – fallback candles | ticker has no 24 h change |
| Bitfinex | `DAILY_CHANGE_RELATIVE` (index 5, bulk 6) | fraction × 100 |
| Bitget, Bitget Futures | `change24h` | fraction × 100; `changeUtc24h` (since 00:00 UTC) not used |
| Bitso | `change_24` | absolute change → open = last − change |
| Bitstamp | `open_24`, else `percent_change_24` | `open` is the start of the day and not used |
| Bitvavo | `open` (`/ticker/24h`) | price 24 h ago |
| BtcTurk | `open`, else `dailyPercent` | price 24 h ago / percent |
| BTC Markets | `price24h`, else `pricePct24h` | absolute change → open = last − change / percent |
| Bybit, Bybit Futures | `price24hPcnt` | fraction × 100 |
| Coinbase | `open` from `/stats` (the only request), against `last` of the same response | price 24 h ago |
| CoinDCX | `change_24_hour` | percent |
| Coincheck | – fallback candles | ticker has no 24 h change |
| Crypto.com | `c` | fraction × 100; `null` without trades |
| Deribit | `stats.price_change` (single), `price_change` (bulk) | percent |
| DexScreener | `priceChange.h24` | percent |
| Gate.io | `change_percentage` | percent; `change_utc0`/`change_utc8` not used |
| Gemini | – fallback candles | `/pricefeed` `percentChange24h`: unit unclear (docs show percent «5.23», real responses look like fractions «0.0146»); `pubticker` has no 24 h change |
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
| Mercado Bitcoin | `open` | price 24 h ago |
| MEXC | `openPrice`, else `priceChangePercent` | price 24 h ago / **fraction** × 100 |
| MEXC Futures | – fallback candles | `riseFallRate` is the change since 00:00 UTC+8, not rolling; the day values in `riseFallRates` are not used either |
| NonKYC | – fallback candles | no documented rolling 24 h field |
| OKX, OKX Futures | `open24h` | price 24 h ago; `sodUtc0`/`sodUtc8` not used |
| One Trading | `price_change_percentage` | percent |
| Paribu | – fallback candles | `percentChange` is percent, but its window is not documented |
| Phemex | `openEp` (scaled 10^8) | price 24 h ago |
| Phemex Futures | `openRp` | price 24 h ago |
| Poloniex | `open` (`ticker24h`), else `dailyChange` | price 24 h ago / fraction × 100 |
| Upbit, Bithumb | – fallback candles | `signed_change_rate` is against the previous day's close (KST) |
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
./gradlew testDebugUnitTest        # or: ./gradlew test (debug + release, all modules)
```

Reports: `android/app/build/reports/tests/testDebugUnitTest/index.html`.

Source guards (`FileLengthTest`, `HapticsUsageTest`, contrast and backup-rule checks …) fail when a file they read is
missing (`SourceGuard`, since build 105 – before, they passed silently). Swift sources count as optional locally: in an
Android-only checkout those tests are reported as skipped; the *Android* workflow sets `REQUIRE_IOS_SOURCES=true`, so
there a missing Swift file fails the run.

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
python3 tools/gen_xcodeproj.py     # after adding/removing Swift files or changing the JSON cases
xcodebuild test -project CryptoChecker.xcodeproj -scheme CryptoChecker \
  -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:CryptoCheckerTests
```

Without `-only-testing` the scheme runs the unit tests and the UI tests (see below).

Tests on the test app's own storage (`WatchlistLockBehaviorTests`, `BackgroundRefreshRunTests`) call
`HostStorageKey.prepareAppStorage()` first: without a keychain entitlement (unsigned Simulator, CI) the process-wide
`StorageKey.shared` gets an in-memory test keychain with a fixed key (`StorageKey.replaceKeychainForTesting`, Debug
build only); with a real keychain nothing is replaced. They therefore run everywhere; a skip of these classes (quiet
hours, not enough time left) or of `SharedStorageTests` fails `ios.yml` on `main` and release tags.

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
(`FakeRemoteDataModule`), so no exchange is called. `DatabaseMigrationTest` checks the
update path of the database: it creates old databases (schemas v1 and v3 from `app/schemas/…`; v10, v12 – the
published 16.2 – and the test builds' v13–v15 built from them with the app's own migrations or by hand), fills
them, and lets Room open them – Room runs the migrations (the whole chain up to the current version, plus the
one-time move of the portfolio into its own database and portfolio database v1 → current) and validates the
result against the entities. `RoomSchemaMigrationTest` (since build 103) checks the same paths against the
exported schemas with `MigrationTestHelper` (AppDatabase v1–v3 and v13–v15, PortfolioDatabase all versions; the
AppDatabase schemas 4–12 were never exported). Needs an emulator or a device:

```sh
cd android
./gradlew :app:connectedDebugAndroidTest
```

The portfolio-lock test sets a temporary screen-lock PIN through `locksettings` (removed
afterwards) and is skipped where that is not possible. The *Android* workflow only compiles the
instrumented tests (`assembleDebugAndroidTest`); running them takes an emulator and is too slow
for every push, so it has its own workflow:

**Android device tests** (`.github/workflows/android-device-tests.yml`): on every push to `main` that touches
`android/` (or the workflow and its smoke script), on every release tag `v*` (since build 104; GitHub ignores the
paths filter for tags) and by hand under *Actions › Android device tests › Run workflow*; a weekly schedule is
prepared but commented out. On pull requests only the job `release-smoke` runs, and only when a file changes that
decides about the R8 release build (keep rules, build script, dependency versions, the smoke test). It uses the same toolchain as `android.yml` (JDK 17, Gradle with
cache), builds `:app:assembleDebug` and `:app:assembleDebugAndroidTest` before the emulator
starts, then runs `:app:connectedDebugAndroidTest` on an x86_64 emulator with KVM
(`reactivecircus/android-emulator-runner`: API 34, `google_apis`, Pixel 6 profile, animations
off) – i.e. everything in `app/src/androidTest`, including `CoreFlowTest` and
`DatabaseMigrationTest`. On failure the test reports are uploaded as the artifact
`device-test-reports` (kept 14 days). The job has a 60-minute timeout; on pull requests a newer push cancels
the older run, on `main` and tags every run is kept so each commit gets a result; its token is read-only
(`permissions: contents: read`). Pushing a new or
changed workflow file needs a token with the `workflow` scope (`gh auth refresh -s workflow`).

**UI tests of the main flows (both platforms).** The same four flows, the same steps, the same
identifiers and the same test file on Android and iOS:

| Flow | What the test does |
|---|---|
| Add a coin | «+» → coin search «btc» → «Load lists» if offered → Bitcoin → suggested pair → back: the pair is in the watchlist |
| Price alarm | pair row → action sheet «Alarm» → editor (opens by itself for a pair without alarm) → 200000 → save → alarm listed; the notification permission is granted (Android: by the test rule; iOS: the system prompt is answered) |
| CSV import | Portfolio (empty) › «Import CSV» → test file → preview → «Import» → BTC and ETH positions visible; ⋯ › «Imports» › «Undo» → gone |
| Backup | pair + CSV import → Settings › Backup › «Export» with password → data cleared → «Import» with a wrong password (rejected, hint under the field, nothing restored) → right password → confirm → watchlist and portfolio back |

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
* **No network.** Android: `FakeRemoteDataModule` (Binance and Coinbase from `FakeMarket`, everything
  else 404). iOS: `UITestMarket` answers every request of every `URLSession` the same way (the protocol is
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
* **iOS seams are inert in production.** All of `UITesting` and `UITestMarket` is inside `#if DEBUG` (a
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
  -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:CryptoCheckerUITests
```

CI: Android in *Android device tests* (on pushes to `main`, release tags and by hand, see above); iOS in the *iOS*
workflow, step «UI tests
(iOS Simulator)» after the unit tests (30-minute limit, one retry per failed test, the `.xcresult` with
screenshots is uploaded on failure).

**CI gates** (`.github/workflows/android.yml`, on pushes to `main` and pull requests touching
`android/` or the Swift sources checked by the source guards, and on every release tag `v*`): unit tests, lint (`lintDebug`, `abortOnError`), instrumented tests compiled, debug
build and an unsigned release build with R8 (catches missing keep rules). Pushes to `main`
submit the Gradle dependency graph (Dependabot alerts) in two jobs: `dependency-graph` (read-only, runs Gradle,
`dependency-graph: generate-and-upload`, the only place with `--dependency-verification lenient`) and
`dependency-submission` (`contents: write`, no checkout and no Gradle, `download-and-submit` of that run's
artifact); pull requests run a dependency review
that fails on new dependencies with known high-severity vulnerabilities.
`.github/dependabot.yml` opens monthly version update PRs for GitHub Actions (at most two open);
Gradle (version catalog `android/gradle/libs.versions.toml`) only gets security updates. The *iOS*
workflow builds and tests in the Simulator (unit tests, then UI tests) when `ios/`, the shared parity
cases or the UI-test files change.

**Exchange API check** (`.github/workflows/exchange-live-check.yml`, weekly on Wednesdays and
by hand): `.github/scripts/exchange_live_check.py` calls the public single-ticker endpoint of
every adapter for one major pair (BTC) and checks that the fields the adapters parse – last
price and, where read, bid/ask, volume or 24 h change – are present and numeric. The endpoint
and field table lives in the script; update it together with an adapter (URLs and field names
as in `android/testdata/exchanges/exchange_*.json`). On a failure the job opens ONE issue
«Exchange API check failed» (or updates the open one) and the run stays green (the issue is the signal); when all pass again it
closes the issue. HTTP 451 (and 403 for exchanges that block US cloud IPs) counts as skipped,
since GitHub's runners are in the US. Since build 104 the scheduled data workflows (`exchange-live-check.yml`,
`macro-calendar.yml`, `market-data.yml`) run in two jobs: the fetch job (`contents: read` only) talks to third-party
servers and uploads its result as an artifact; the publish job (write/issue permissions, no third-party requests)
checks out `github.sha` afresh, downloads the artifact with `gh run download` (`actions: read`;
`actions/download-artifact` is not pinned in the repository) and checks it with
`.github/scripts/artifact_check.py` (expected file names, size, rough JSON schema; `--self-test`) before committing,
uploading or managing the issue. Locally, without
touching GitHub: `python3 .github/scripts/exchange_live_check.py --dry-run` (optionally
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
  CI task set with `--write-verification-metadata sha256 --write-locks` (plus Kover, the baseline-profile module and,
  optionally, one device-test class on an emulator; Windows and macOS add their aapt2 builds for local builds), checks
  the result (`.github/scripts/supply_chain_files.py`) and force-pushes ONLY these files to the branch
  `supply-chain/update`; open a pull request from it and merge once CI is green. Repeat after every change to
  `libs.versions.toml`, a plugin or the Gradle version (including Dependabot security updates – their pull request
  fails verification until the files are regenerated: run the workflow on the Dependabot branch – `supply-chain/update`
  then starts from that commit, so its pull request carries the version change plus the files; merge it instead).
  Sources and javadoc jars (downloaded by Android Studio) are trusted without checksum; the trust rules live in the
  seed in `supply_chain_files.py`. A local build failing with «Dependency verification failed» after a deliberate
  change: regenerate; for a one-off local experiment `--dependency-verification lenient`.
* *Known vulnerabilities*: `.github/workflows/osv.yml` (pushes and pull requests touching `android/`, release tags,
  weekly on Tuesdays) resolves `:app` `releaseRuntimeClasspath` and checks every coordinate against the OSV database
  (`.github/scripts/osv_check.py`, `--self-test`). A vulnerability with a fixed version is an error on `main`, tags
  and the weekly run, a warning on pull requests; without a fix or when OSV is unreachable it is a warning. An
  accepted finding goes into `.github/osv-ignore.txt` (`ID  # reason`) and is then only a notice.

The *Android* workflow also shows, never failing: lint warnings counted by id (top 20, one notice;
`.github/scripts/lint_warnings.py`) and unit-test line coverage of `:app` and `:marketdata` (Kover, one notice;
`.github/scripts/coverage_summary.py`). Kover is applied only with `-Pcoverage=true`, so the normal test run is not
instrumented; locally: `./gradlew -Pcoverage=true :app:koverHtmlReportDebug` (report under
`app/build/reports/kover/`).

## 13. Market tab caching

Every area of the Market tab is stored on the device with a timestamp (Android
`CycleCacheStore`, iOS `CycleCache`), shown immediately on open and reloaded quietly in the
background only when it is older than its TTL. The TTLs live in one place per platform —
`CycleSource` / `CycleCachePolicy` (Android, `domain/market/CycleCache.kt`) and
`CycleCachePolicy` (iOS, `App/Features/Cycle/CycleCache.swift`) — and must stay equal:

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
`CCDM1 <fetched ms>`, then the provider's body unchanged; nothing is committed). `callMarket`
(Android) and `MarketHTTP.call` (iOS) ask the mirror first for exactly these GET URLs and the
provider otherwise: Fear & Greed (`fng.txt`, max age 4 h), Coin Metrics
on-chain values (`onchain.txt`, any `start_time`, 36 h) and BTC price since 2016 (`btcprice.txt`,
36 h). `altseason.txt` (6 h) is not a copy but the altcoin season already computed by the script
(same 20 alts and 90-day rule; `{"outperformers":12,"total":20,"provider":"Binance"}`, read with
`DataMirror.parseAltSeason`); without it the app computes it from ~21 candle requests as before. Live data (tickers, Crypto Pulse, gas, per-coin analysis) stays direct. A new mirrored URL
needs the same entry in `mirror_data.py`, both `DataMirror` files and `data_mirror.json`.

**Signed mirror** (`MirrorManifest.kt` / `MirrorManifest.swift`, cache `MirrorTrust.kt` / `MirrorTrust.swift`,
parity cases `mirror_signature.json`): the publish job of `market-data.yml` writes, after `artifact_check.py`,
`manifest.json` (`{"created":<ms>,"files":{"<file>":"<SHA-256 hex>"…},"v":1}`, compact, sorted keys) and
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
key (`MirrorManifest.PUBLIC_KEY`, ECDSA P-256 because Android minSdk 26 has no Ed25519 in the platform), the manifest
is less than 7 days old and not more than 5 minutes in the future (no replay of old data), and the file's SHA-256 (over
its UTF-8 bytes) matches. Otherwise the file is ignored exactly like an unreachable mirror and the app asks the
provider directly — no message, no crash. Manifest and signature are kept in memory only: 15 minutes when valid,
5 minutes after a failure; a file newer than the remembered manifest reloads it once (at most once a minute).
Without the secret the workflow keeps running, publishes the data and the manifest unsigned (warning annotation; a key
that does not match the public key gives an error annotation) and removes an old signature, so the apps use the
providers directly; the run then ends red (error annotation after the upload) so that a missing signature is noticed. Tests use their own test key pair (`publicKey` in the fixture); the real private key never enters the
repository.

*Changing the key*: create a new pair (`openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out
mirror_key.pem`; public key for the apps: `openssl pkey -in mirror_key.pem -pubout -outform DER | base64 -w0`), put
the public key into `MirrorManifest.kt`, `MirrorManifest.swift` and `productionPublicKey` in
`mirror_signature.json`, and release the apps. Only once the new versions are out, replace the secret
`MIRROR_SIGNING_KEY` with the new private key and, at the same time, `PUBLIC_KEY` in `mirror_data.py` (the workflow
checks its own signature against it and otherwise publishes unsigned); older app versions then ignore the mirror and
use the providers directly. If the private key leaked, replace the secret at once and ship the new public key quickly.
Keep the private key file offline; never commit it. Rotated in build 113 (the previous private key was lost; no app
with the old key was released).

**CoinGecko is never mirrored.** The CoinGecko API terms forbid redistributing and storing their
data, so `global.txt`, `coins30.txt` and `coins40.txt` were removed in Build 42 (the workflow deletes
the old assets) and there is no `COINGECKO_API_KEY` secret any more. The apps ask CoinGecko directly
(keyless): `/global` cached for 30 minutes, the top 30/40 for the starter coins for 24 hours, and the
top 40 for the Spot tab and the «Why?» market breadth as one shared request kept in memory for
10 minutes. Everything that shows CoinGecko data carries the line «Powered by CoinGecko» (see
*UI rules*).

* Never publish third-party API data (CoinGecko or any provider whose terms forbid redistribution)
  in the repository, on GitHub Pages or as release assets. Check a provider's terms before adding
  a file to `mirror_data.py`.

**Spot data in the action sheet** (`SpotMarket.kt` / `SpotMarket.swift`, `SpotDataSource` on both
platforms, parity cases `spot_market.json`): the «Spot» tab of the «Market data» card (shown for
all crypto pairs except deactivated pairs, DEX pools and TradFi spot data) first reads the CoinGecko
top 40 (`SpotMarket.TOP_URL`, fetched directly, the same request for everyone and shared with the
«Why?» market breadth, 10 minutes in memory) and only for a coin not in it asks
CoinGecko `/coins/markets?vs_currency=usd&symbols=<symbol>` (keyless, sends the coin symbol).
Results, including «not found», stay in memory for 10 minutes per symbol; failures are not cached.
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
search never triggers a request.

**Coin-first search (build 56)** (`ExplorerCoinSearch.kt` / `ExplorerCoinSearch.swift`, parity cases
`coin_search.json`): «Add pair» lists coins first. Rank: exact symbol (or gold/silver alias) → exact name →
symbol prefix → name prefix → name contains; ties by number of exchanges, then shorter name. Picking a coin
suggests one pair: region default exchange first (Binance, or Binance.US in the US / when Binance is geo-blocked),
then favourite exchanges; spot before perpetual before dated; quote order display currency → USDT → USD → USDC →
EUR → BTC (owner decision 17 Oct 2026: display currency first). Only pair lists already on the device are
searched; missing lists load only after the user taps «Load lists» (privacy note next to the button). The
step-by-step flow stays as «Advanced / by exchange».

**Coin logos** (`CoinLogos.kt` / `CoinLogos.swift`, parity cases `coin_logos.json`): there is
no own logo list or logo pack any more (Build 42 removed `build_logos.py`, `requirements-logos.txt`,
`coin-logos.yml`, `docs/logos`, `logos.pack` and `stocks.txt`; CoinGecko's API terms forbid
redistributing their data). Every install builds the same list itself: the
symbol → image map comes from CoinGecko `/coins/markets` (pages 1–4 = top 1000 first, first occurrence by
market cap wins), gaps filled from the Binance website list `bapi/composite/v1/public/marketing/symbol/list`
(`name` → `logo` on `*.bnbstatic.com`, unofficial; only symbols CoinGecko lacks, e.g. XAU, XAG,
stocks), then the official Binance Alpha token list
`bapi/defi/v1/public/wallet-direct/buw/wallet/cex/alpha/all/token/list` (`symbol` → `iconUrl`,
tradable first, largest `marketCap` first; futures tokens without a CoinGecko market cap such as
AIA, AGT, AIO), then CoinGecko pages 5–10 (ranks 1001–2500) for the remaining gaps (e.g. AIN, LUNA), and is
kept for **7 days** (weekly refresh). TradFi pairs never take a CoinGecko logo (`pickTradFi`, key
`tradFiKey`). Every CoinGecko page after the first waits 6 s (the keyless limit otherwise
answers 429); a 429 is retried once after `Retry-After` (1–60 s). If any source fails, the known
entries are kept (`withKnown`) and the list expires after **6 h** (`savedAtFor`) instead of a week
(cache v5). Stock perps without a Binance bStock get
their logo from `bin.bnbstatic.com/static/stock/TICKER.png` (all TradFi tickers of the stored pair
lists) and, with «Show names», their name from the official Nasdaq symbol directory
(`nasdaqlisted.txt` + `otherlisted.txt`, weekly, `cleanStockName` strips «Common Stock» etc.) (retry after 1 h on failure). For privacy the
logos of **all** coins in that list are downloaded in one background sync (`startSync` /
`syncAll`, 4 parallel, small variant first) — never one logo on demand, so the image
requests are the same for every install. Images are scaled to 128 px and stored as PNG (Android `cacheDir/coin_logos`, iOS App
Group `coin_logos/`), a failed image is retried after **1 day**; display reads only from memory/disk, and a
`revision` counter (every 25 new logos) lets badges retry. Only HTTPS images from
`*.coingecko.com` and `*.bnbstatic.com` (`isAllowedUrl`; URLs of the former GitHub Pages list are
rejected); DEX pools (`DexScreener`) always show initials. Android:
`CoinLogoRepository` → `LocalCoinLogoSource` (null when «In the app» is off) →
`CoinBadge` (`portfolio = true` → `LocalPortfolioCoinLogoSource`), sync started by
`CoinLogoSync` when `CoinLogoUse.needed` (app, widgets, or portfolio with the tab on; app
start, switch on, portfolio tab on). Logo switch defaults: «In the app» on, «In the portfolio» on (matters
only while the Portfolio tab is on — off for new installs since build 44), «In widgets» off; widgets via
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
faded with 50 % alpha (Build 42). There is no live service any more: the Android foreground service «Always frequent»
(`PriceService`, `LiveInterval`) was removed before 1.0 because Google Play does not allow `dataSync`
for a permanent price ticker; `AppSettings.liveService` is always `false`.

**Source and age.** Every row under «Context» and «Data» (except the calendar-based halving)
ends its secondary line with the provider that actually delivered the value and its age, e.g.
«Greed · alternative.me · today 02:00» or «CoinGecko · 3 min ago» (`DataFreshness` /
`DataStamp`, both platforms). Sources with a fallback chain report the one that answered
(candles: Binance / Binance.US / Coinbase / Bybit; Bitcoin indicators add «Coin Metrics» when
on-chain values are present; gas: the Ethereum RPC host and mempool.space). The provider is
stored next to the timestamp in the cache file (`src` on Android, `provider` on iOS; older
files without it simply show no provider). Age: < 1 min «just now», < 60 min «N min ago»,
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
of REST polling. Setting: *Settings › Advanced › Price updates › «How current?»* — `UpdateMode`
(`SPARSAM` «Economical» = default and recommended for new installs since build 43, `LIVE_OFFEN` «Live while open» = labelled «for pros»; never call Live the recommended choice); `liveWebSocket` is derived from
it. `IMMER_KURZ` («Always frequent», removed before 1.0) is only read from old data and backups and
always becomes `LIVE_OFFEN` (`withoutService`); a stored value is rewritten once by
`UpdateModeMigration` (DataStore), which also migrates the old switches. Backups write `updateMode`
(never `IMMER_KURZ`) and keep writing the old flags for older versions with `liveService = false`
(restore prefers `updateMode`). Tests: `UpdateModeTest`. Background refresh (WorkManager, BGTask) and widgets never open a
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
  Android: `android/app/…/domain/live/LiveFeed.kt` (with a tiny JSON reader, `LiveJson.kt`,
  so the parsers run in plain unit tests); iOS: `ios/Shared/Services/LiveFeed.swift`.
* Connections: Android `data/live/LivePriceStream.kt` (OkHttp WebSocket, protocol pings every
  20 s), iOS `ios/App/Services/LivePriceStream.swift` (actor, `URLSessionWebSocketTask`). Bybit,
  OKX and Kraken additionally get their text ping (`{"op":"ping"}`, `ping`, `{"method":"ping"}`);
  without any message for 60 s those connections are reopened.
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

**Never show «Live» unless every visible pair is live** (build 44, `LiveStatus` —
Android `domain/watch/LiveStatus.kt`, iOS `ios/Shared/Util/LiveStatus.swift`, tests `LiveStatusTest` /
`LiveStatusTests`):

* «Outdated» is decided **per pair**: a pair on a live socket is stale after **2 min** without a new
  price (`LIVE_STALE_MILLIS`); every other pair — also in «Live while open», where exchanges without
  WebSocket are not polled on a timer while the watchlist is open — uses the polling threshold
  **3 × background interval, at least 15 min** (`pollingStaleAfterMillis`). The old «Always frequent»
  service (`liveService`) plays no part in this decision any more; do not bring it back.
* The pill says «Live» only when the view has at least one traded, active pair, **all** of them are
  on a socket and none is stale (`allLive`). Mixed views show the usual sentence («Current ·
  12 min ago»), and the time is the newest **polled** pair (`shownTime`), so live pairs do not make an
  old poll look fresh.
* Widgets are never live: they always use the polling threshold (`WidgetFreshness.afterMillis`).
* Texts must stay honest: «Live while open» is described as «for pros» (battery, foreground only),
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
  alarm texts (`PriceFormat.changePercent`, `PriceFormat.signedPercent`). The funding rate is coloured
  text without an arrow. Counters carry an arrow («↗ 233 rising», «↘ 295 falling»); the average
  «Ø +0.32%» uses the warning text colour.
* **Time window small and grey.** The period after a change («· 24h», «· 7d», «· 1Y», «· 1J») is small
  secondary grey text, never in the price colour (e.g. «↗ +41.71% · 24h · Updated …»,
  «↘ −38,412.95 USDT −27.85% · 1Y»).
* **Buttons on the accent colour** use the theme's normal contrast colour (`AppColors.onAccent` /
  iOS `\.onAccent`); there is no user setting for it any more (the former «Text on pills and
  buttons» / `pillTextColor` is ignored when an old backup is imported and no longer exported).
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
  back to fading the price with alpha to mark age (it falls below 4.5:1). Since build 45 the same holds
  for disabled watchlist rows (texts in `onSurfaceVariant`, «Binance · disabled» says it in words; only
  logo and symbols keep a reduced opacity) and for a stale portfolio total (amount in
  `onSurfaceVariant`, «Prices from … · outdated» in the warning colour). Since build 60 the mini chart of a
  stale row follows suit: full shape in the same `onSurfaceVariant` grey as the stale price, no gradient fill,
  no up/down colour (`Sparkline(stale =)`, iOS `WatchlistSparkline(stale:)`; was 50 % opacity) — old data must
  not look «nice». The screen-reader sentence still describes the chart.
* **Exchange time vs fetch time** (build 44, `PriceFreshness` — Android `domain/watch/PriceFreshness.kt`,
  iOS `ios/Shared/Util/PriceFreshness.swift`; widgets `WidgetFreshness`; parity
  `testdata/parity/price_freshness.json`). Many exchanges (Kraken, Gate.io, Hyperliquid, bitFlyer, the
  Gemini bulk request …) send no time with their ticker, so the fetch stamps «now» and a last price
  that has not moved for hours would look fresh. Therefore every pair stores three times:
  `lastUpdate` = last successful fetch (unchanged meaning), `exchangeTime` = the exchange's own time
  of the price (`Ticker.exchangeTime`, null when the exchange sends none) and `priceChangedAt` = when
  the price last changed (Android Room 14 → 15, `MIGRATION_14_15`; iOS optional fields in
  `watchlist.json`). Age and «outdated» use `freshnessTime` = exchange time, else the fetch time.
  Without exchange time, a price that has not changed for longer than max(1 h, the outdated
  threshold) shows «Price unchanged since HH:MM» instead of a fresh time — in the row and in the
  widgets (not on pairs that are already outdated, deactivated or failing). Never stamp a price
  with the fetch time and present it as the exchange's time; alarms use none of these fields.

## 16. Portfolio and backup rules

Rules that are easy to break by «simplifying» code. Keep Android and iOS identical and extend
the tests named here.

* **Exact ledger, floating-point valuation.** Transaction amounts, buy prices and fees are exact
  decimals: Android `BigDecimal` (`PortfolioDecimal`, `MathContext.DECIMAL128`, at most 18 decimal
  places, dust below 1E-20 counts as 0), iOS `Decimal` (`PortfolioDecimal.swift`; since build 45
  divisions are rounded to **34 significant digits** like `DECIMAL128`, `significantDigits`, so both
  platforms give the same average cost). Holdings, cost basis and realized gain are summed exactly
  (`holdingsExact`, `costBasisExact`, `realizedExact`), so 1 satoshi (0.00000001 BTC) never drifts;
  since build 45 the unrealized gain of a position is computed as an exact decimal too. Only the
  valuation with market prices (Double) is floating point. When only part of a holding has a
  purchase price, the average is labelled as the average of that part («Avg. of X with purchase
  price», `portfolio_avg_partial`), never shown as the average of the whole holding. iOS import
  of backups applies the same number limits to the old numeric fields as to the text fields, like
  Android `PortfolioJson.decimal` (`PortfolioDecimal.withinLimits`: below 10³⁰, at most 40 decimal
  places; absurd values count as invalid entries). Never parse amounts through `Double` (`PriceFormat.parseDecimalAmount`,
  `ThresholdParser.parseDecimal`). Android stores them as TEXT (portfolio DB v2,
  `PortfolioDecimalConverters`, `MIGRATION_1_2`); backup and portfolio files write the old numbers
  plus `amountText` / `priceText` / `feeText`, readers prefer the text.
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
  whole period —». Since build 44 «Today» follows exactly the same skip rule as 7 d: skipped coins are
  named on the card, the widget says «Today without …» (`widget_portfolio_today_without`). The 24 h
  history uses hourly prices for stablecoins too (only USDT is flat at 1, others need their own hourly
  price). Since build 46 the value history has two modes («Value» | «Gain/loss», remembered): the gain
  curve is P&L(d) = value(d) − net invested(d) with the cash flows of `PortfolioLedger.flow`, so buys and
  sells never move it; the header always shows «Value X · Gain Y (Z %)» with Y = P&L over the range and
  Z = Y ÷ (start value + gross inflows in the range; sales not subtracted – the same base as «Today» and the rows), «Since 1st buy» ÷ all money put in; base ≤ 0 → amount only.
  Coins with unpriced trades or missing day prices are left out and named
  (`portfolio_history_pnl_without`). The old «value now vs. range start» figure is never shown as gain.
* **One «Today».** The snapshot computes today's change once: `todayFromCoinChanges` (coins without
  a day change are left out, never filled flat), then `todayAdjusted` removes today's trades
  (Android `PortfolioSnapshotUpdater.withHonestToday`, iOS `PortfolioWidgetStore.compute`). Card,
  widget and portfolio alarm all read `snapshot.changeAmount` / `changePercent`; the view model
  must not adjust again, or the three numbers drift apart.
* **Portfolio alarms in another currency.** A value alarm keeps the currency it was created in.
  When the snapshot is in another currency (conversion currency changed), the value is
  `totalUsdt × usdTo[currency]` (`PortfolioReading.usdTo`, filled from `FxRateSource`; iOS
  prefetches with `prefetchPortfolioAlarmRates`). No rate → no check this time, never a wrong value.
  The repeating «today ±x %» alarm re-arms only after the change is back by
  `changeRearmMargin` (20 % of the threshold, at least 0.5 points); value alarms keep the 0.2 %
  hysteresis.
* **A fired portfolio alarm is stored with compare-and-set** (Android, build 44):
  `PortfolioAlarmDao.markTriggered` updates only if the alarm is still enabled with the same
  reference, kind, threshold, repeat flag and currency as when it was read; 0 rows → nothing is
  notified (the user's edit wins), like `WatchDao.markAlarmTriggered`. With the portfolio lock on,
  the notification has a neutral body without amounts (both platforms).
* **Fees at the edges.** A sell with nothing left changes neither «realized» nor the period gain
  (its USDT fee is ignored on both). `oversold` counts amount + coin fee, and so does the sell
  warning in the transaction sheet («Amount plus fee is more than your holdings»,
  `portfolio_sell_exceeds_fee`, build 45). A USDT fee on a buy
  without a price belongs to the unknown cost of that part.
* **Restore never drops data silently.** Portfolio transactions and alarms that cannot be read are
  counted (`PortfolioJson.parseTxs/parseAlarms`, Swift `BackupManager.invalidPortfolioEntries`) and named
  in the confirmation (`backup_restore_invalid`); `restore(…, acceptInvalid)` stops before replacing
  anything if the file holds more invalid entries than the user confirmed. Every backup read goes
  through `BackupLimits` (16 MB, JSON nesting ≤ 32), also the Android system-backup copy. Portfolio
  notes are at most 120 characters (`NOTE_MAX` / `Watch.noteMax`). Since build 45 the restore from a
  **system / device backup** counts invalid rows too and shows them once when the portfolio is opened
  (`portfolio_system_restore_invalid`; Android `PortfolioRestoreNotice` / `SystemRestoreNoticeDialog`,
  iOS `PortfolioRestoreNotice`, remembered per file so the same file is not reported again).
* **An unencrypted backup cannot weaken protection** (build 45). Importing a backup without a
  password shows a warning before replacing (`backup_restore_unencrypted_warning`: check that it comes
  from you; it names the four protected settings with their exact labels — `BackupUnencryptedWarningTest`).
  Its settings are applied, except four changes that would lower protection (`BackupRestorePolicy`, Kotlin and
  Swift): it never turns the portfolio lock **off** — if the lock is on, it stays on and the result says so
  (`appLock`, `backup_restore_app_lock_kept`, `appLockKept`); it never turns «Hide amounts» **off**
  (`hideAmounts`); it never turns «Portfolio in system backup» **on** (`systemBackup`); and it never sets,
  replaces or removes the own Bitcoin server (`AddressServer.restored`; the result names a kept server,
  `addressServerNote` KEPT → `backup_restore_server_kept`). An encrypted backup restores all four as stored
  (the lock turns on only if the device can authenticate; an invalid server value is ignored).
* **iOS `portfolio.json` is never overwritten blind.** `PortfolioStore.loadChecked` tells a missing
  file (empty portfolio) from an unreadable or partly unreadable one (`PortfolioFile.dropped`).
  `AppData` re-reads before every change (`currentPortfolioFile`) and, if still unreadable, keeps a
  copy (`preserveUnreadable` → `portfolio.unreadable.json`) before writing; if not even the bytes are
  readable (data protection), nothing is written and the holdings import waits. Saves report failure.
* **Alarm save and notify are one step.** Android marks an alarm as triggered and shows the
  notification inside one `withContext(NonCancellable)` (`RefreshEffects.report`, portfolio alarms);
  one-time alarms without a deliverable notification stay armed (`canShowAlarm`,
  `canShowPortfolioAlarm`).
* **Live stream stop.** `stopAll` clears its state before it suspends to save (Android) or checks a
  generation counter after `await flush` (iOS actor), so a newer `reconcile` keeps its session.
* **Fresh share instead of the oldest coin.** The value is recorded (history, alarms) when at least
  **80 %** of it has prices younger than the update limit (`PortfolioPriceFallback.freshShare`,
  `mayRecordShare`, `MIN_FRESH_SHARE = 0.8`), checked per coin from `PortfolioPrices.times`. One
  rarely traded coin must not block the alarm for the whole portfolio.
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
  (`parts(…, skipUnpricedTrades = true)` in `gain()`), never counted with its fee only. Android resolves routes in `PortfolioPriceSource`
  (pair lists from `MarketRepository`, FX from `FxRateSource`); iOS resolves them in the app
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
  not Binance, quote USDT → USD → fiat, then the most recently updated; build 45) – that is the token
  the user chose. Only without such a pair does it get its portfolio price from
  the last hourly candle of the candle chain. That price counts only if it is within **1/3 … 3**
  (`PricePlausibility.acceptCandle` = `sameToken`) of an independent reference
  (`PricePlausibility.candleReference`): the median price of the same coin on the watchlist
  (USD-stable spot pairs, active, price at most 30 days old), otherwise the last good stored price
  (`PortfolioPriceFallback`). Fails → the candle is discarded and the last good price stays, shown
  with its own «as of» time (faded once stale); with no stored price the coin shows «—». Never let a
  failing candle overwrite the stored price. No reference at all → since build 45 the coin has **no
  price** (`PricePlausibility.candleUnchecked`, `PortfolioPrices.unchecked`): the position shows «—»
  with «Price unchecked» (`portfolio_price_unchecked`) and the coin is named in the «without …» list
  – see «Unchecked prices never count as money». The reference is read from local data only – never
  add a request for it. Tests: `PricePlausibilityTest` / `PortfolioWatchPairTest` /
  `PortfolioNumbersTests`.
* **Unchecked prices never count as money** (build 45). A price whose token could not be confirmed
  (a candle price without any reference, a cut-off close without a matching current price) is never
  added to anything that looks like money: not to the total, «Since purchase», «Today», 7 d / 1 y,
  the value history, the widget, portfolio alarms or the cut-off CSV, and it is never kept as a last
  good price or used as a reference (`PortfolioPriceSource` leaves `uncheckedCoins` out). Show «—» and say
  why («Price unchecked» / «unchecked (no matching price)»). Never fall back to «better a price than
  none» – the same ticker on a fallback exchange can be another token.
* **Portfolio history from the route exchange first** (build 45). For a coin with a route
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
* **Fallback chain for historical daily closes** (build 84). `HistoricCloseChain` (pure, both platforms, parity
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
  - Value history (`PortfolioHistorySource`): a coin with **no Binance series** (not listed, or Binance unreachable
    and nothing stored) and **no route** gets Coinbase → Kraken → OKX → Bybit daily candles (`fetchOwn`, USD ÷
    USDT/USD of the day) when the name matches and the latest close is within ±25 % of the current portfolio price
    (`acceptSeries`; reference from the price cache/stored prices only – no extra request, no reference → no new
    series). Cached 12 h as `phistc_<COIN>` (route = the chain pair), a miss is not retried for 1 h (memory only);
    the widget reads the same cache. `closesMatchLive` still checks 1/3 … 3 later. `PortfolioDailyPrices.chainSources`
    → `PortfolioHistoryUi.chainSources` → caption `portfolio_history_chain_sources` («Daily prices: Kraken (PEPE)
    (instead of Binance)», `PortfolioExchangeRoute.sourcesText`).
  - USDT/USD daily closes: Kraken `USDTZUSD` first; if Kraken misses a day of the last 600 (unreachable, gap),
    Coinbase `USDT-USD` fills it (`fillUsdtUsd`: only plausible 0.5 … 1.5 values, Kraken wins; cached
    `phisto_USDTUSDCB`). Only after both, a day falls back to «USDT = 1 assumed».
  - FX is untouched: `usdTo` (Frankfurter answers a weekend/holiday with the last business day) and `usdToSeries`
    (from `LEAD_DAYS` = 7 days earlier, gaps filled with the previous rate by `PortfolioHistoryFx.rateOn`) do not use
    the chain. Stablecoin rules (`PortfolioStables`) are unchanged.
* **Prefill only what is true for the chosen date** (build 45). The add-transaction sheet prefills
  the current price only for a transaction dated **today**. Choosing another date clears a prefilled
  (untouched) price and shows «Enter the price on the purchase day – leave empty if unknown»
  (`portfolio_tx_price_past_hint`; `PortfolioTxForm` on both platforms, tests
  `PortfolioTxFormTest(s)`); a price the user typed stays. Any other prefilled field follows the same
  rule: never prefill a value that is only true for another date.
* **Stablecoins in the watchlist ≈ line.** Only USD and USDT are 1 (`CurrencyConversion.stableToUsd`,
  `stableNeedsMarket`); other stablecoins (USDC, DAI …) use the portfolio's cached market price
  (`PortfolioPriceSource.cached`), and without a valid one the row shows **no** ≈ value (build 45 –
  no silent par any more). The ≈ line never starts a request of its own. FX rates are fetched again
  after **3 h** (`FxRateSource`, TTL = `CurrencyConversion.FX_STALE_MILLIS`, was 6 h); if the rate
  behind a ≈ value is older than 3 h, the line adds its time («· rate 08:15», `converted_fx_stale`,
  `staleFxTime`).
* **Stablecoins without a daily close («7 d»).** Elsewhere a stablecoin without a price has no
  value (see above). For the «7 d» reference only, a missing close counts as 1
  only while the live price is within **0.1 %** of 1 (`PortfolioStables.fallbackWithoutClose`,
  `MAX_FALLBACK_DEVIATION = 0.001`); otherwise the figure is unknown («—»). USDT is always 1.
  Do not replace this with the general par fallback – a depegged coin would produce a made-up
  change. iOS «Today» samples (every 30 minutes) store prices after the same stablecoin rule as
  Android (a stablecoin without a price is left out, never stored as 1); never store raw market
  prices there. Tests: `PortfolioPeriodChangeTest` / `PortfolioNumbersTests`.
* **Portfolio tab: off for new installs, existing installs keep it** (build 44). The default in
  `AppSettings` is `portfolioEnabled = false`. Android: an existing install (any stored key) gets the
  former default «on» written once by `InstallDefaultsMigration` (`LEGACY_PORTFOLIO_ENABLED`), an
  explicit choice stays. iOS: stored settings without the field decode to the former «on»
  (`legacyPortfolioEnabled`); settings from before «portfolio on by default» (older versions wrote
  the old «off» with the blob) are turned on once by the marker `portfolioDefaultV2`, after that
  every choice stays. A backup without the field restores «on». While the tab is off, the
  «Add to portfolio» action of a pair is hidden. Store texts and review notes must not promise a
  visible Portfolio tab on first launch. Tests: `PortfolioDefaultTest` / `SettingsLogicTests`.
* **Android main database is in the system backup.** `AppDatabase` runs
  `PRAGMA secure_delete = ON` on every open (`SecureDeleteCallback` in `di/LocalDataModule.kt`,
  as a query, failure only logged) so deleted or overwritten rows are zeroed instead of staying
  in free pages. After the legacy portfolio tables are dropped (update from DB 12), the main
  file is compacted: WAL checkpoint → `VACUUM` → checkpoint, outside any transaction; only on
  success is `legacy_vacuum_done` written to `portfolio_meta`, so a failed attempt is retried
  on every start (`PortfolioLegacyPlan.vacuumPending`, `PortfolioLegacyPlanTest`;
  `DatabaseMigrationTest` checks the marker). Never set the marker before the VACUUM succeeded.
* **iOS portfolio alarms and the backup switch.** Portfolio alarm thresholds belong in
  `portfolio_alarms.json`. They are removed from `watchlist.json` only after that file was
  written; until then (old install or write error) `watchlist.json` keeps them and is excluded
  from backup while «Portfolio in system backup» is off (`SharedStorage.writeFiles`). The next
  save retries.
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
  **v2** files (PBKDF2-HMAC-SHA256, `iter` 600 000 or 210 000 read from the file, accepted range
  10 000 … 10 000 000, no AAD) stay readable unchanged; only tests still write v2. Shared vectors
  (v3 exact ciphertexts incl. the production parameters, v2, manipulated headers, RFC 9106) live in
  `testdata/parity/backup_crypto.json` – keep both platforms in sync with it. Changing parameters: new
  constant on both platforms plus a vector; never change how existing files are read. Passwords go to
  `BackupCrypto` as `CharArray` (Android) / NFC UTF-8 bytes (iOS) and are wiped after the derivation.
  Argon2id with 64 MiB takes a few hundred ms up to a few seconds – call encryption and decryption
  only off the main thread (Android `Dispatchers.IO`, iOS a detached task; the «Continue» button
  stays disabled while checking). New backup passwords need at least **12** characters (since build 69;
  10 from build 45, 8 before) (`MIN_PASSWORD_LENGTH` / `minPasswordLength`, Unicode characters); `isWeak`
  (shorter than 12, only digits, one repeated character, a common sequence such as «12345678» or «password»,
  a keyboard walk, a decorated common word, a repeated chunk) **blocks** the export (`isPasswordAllowed`,
  `canExport`, `backup_password_too_weak`; the hint appears from the minimum length on). The former rule
  «≤ 2 character classes and shorter than 12» is covered by the minimum length and was removed. Reading is not
  affected: older files decrypt with any password. Cases in `testdata/parity/backup_passwords.json`.
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
  system backup, and delete it with «Clear portfolio» (`clearStored`).
* **Status pill for Market and Portfolio.** Both tabs use the shared `DataStatusPill` (Android
  `ui/components/DataStatusPill.kt`, iOS `App/Components/DataStatusPill.swift`) with the watchlist's tones:
  green up to date, grey offline/loading, red outdated («Prices outdated · as of HH:MM», «Offline · as of
  HH:MM»). Offline the Market tab keeps its last data and starts no reload; expired data reloads when the
  device is back online. Do not build another pill for a new screen.

* **Ledger types (build 46).** All position, period, history and cut-off maths goes through
  `PortfolioLedger` (Kotlin/Swift, fixture `portfolio.json`, `PortfolioTxTypesTest(s)`). BUY/REWARD/DEPOSIT
  add holdings (with a price: cost basis += amount × price + fees; without: holdings without purchase price);
  SELL realizes gain; WITHDRAWAL removes holdings at average cost **without gain**; TRANSFER never realizes
  gain, a network fee in the coin only lowers the amount (its cost stays in the basis), and the destination
  (`transferTo`) decides the pricing exchange from then on (`PortfolioExchangeRoute`). A fee in a third coin
  (`feeCoin`, `feeCoinAmount`, `feeCoinPriceUsdt`) is a disposal of the fee coin at the fee price and adds
  its value to the main transaction's cost (or takes it from the proceeds); without a fee price the result
  is marked incomplete (`feeValueMissing`, `realizedFeeMissing`), never guessed. The third-coin fee has its
  own fields – never reuse `fee`, older builds would read it as USDT. Room portfolio DB v4: `MIGRATION_2_3` added
  the build-46 columns; `MIGRATION_3_4` (build 58) adds `giftToSpouse` (INTEGER NOT NULL DEFAULT 0),
  `linkedImportKey` and `importRestoreState` and rewrites existing rows that still carry the build-56/57 packed forms
  (`LegacyStorageMigration`, same rules on iOS when `portfolio.json` is read and on both platforms when an old backup is
  restored; cases in `legacy_storage_migration.json`). Schema JSON `app/schemas/…PortfolioDatabase/4.json`.
  Backup/system-backup copy: the three fields are additive (`importRestoreState` as a JSON object), backup
  `version` stays 1 – a bump would make older builds refuse the whole file; they ignore the fields and only lose the
  spouse flag, the second import key and the undo state of linked transfers.
* **Cost method FIFO (build 46).** `CostMethod.FIFO` is the default for everyone, average cost («Ø») is a
  setting (`portfolio_cost_method`, part of the app backup; old backups → FIFO). `PortfolioFifo`: one queue per
  coin across all exchanges and wallets, lots ordered by acquisition time (a DEPOSIT may carry the original
  `acquiredTime`), then transaction time, then id. Buys/rewards/deposits create lots (unknown price → lot with
  unknown cost); sells consume oldest lots and produce `PortfolioDisposal` rows (one per lot slice; proceeds
  split by amount, the last slice gets the remainder); a sell beyond the recorded holdings produces an
  `uncovered` disposal with proceeds and no cost (export marked incomplete); withdrawals consume lots without
  gain; a transfer network fee consumes the oldest coins and moves their known cost onto the oldest remaining
  priced lot (total cost basis unchanged – write this down for tax advisors). Ø keeps `costParts` so the tax
  export converts each acquisition at its own date's FX. Trades are grouped per coin once
  (`PortfolioLedger.eventsByCoin`); FIFO keeps a running holdings total.
* **FIFO cost parts (build 58).** FIFO lots carry their cost basis as dated parts like Ø (`PortfolioCostParts`,
  invariant: sum of parts = cost, exact): the acquisition, a transfer fee spread onto the lot at the transfer's time,
  and a carried network-fee cost with its original parts. Consuming a lot takes all parts pro rata
  (`PortfolioDisposal.costParts` is now filled for FIFO and FIFO_WALLET too), so the tax export converts each part at
  its own date: CH, lot 2018 5000 USDT (rate 1.00) + transfer fee 2024 100 USDT (rate 0.85) = 5085 CHF, not 5100
  (`tax_export.json` › `fifoCostParts`, FIFO = FIFO_WALLET = Ø). The «cost at purchase-date rates (Ø)» note stays for
  pooled rows only (parts without a lot date). The card is unchanged: it converts every USDT amount at today's rate
  (`PortfolioHistoryFx.forCard`), hence the sentence «The tax CSV can differ from ‘realized’»
  (`tax_export_realized_differs`, shown in the export dialog below the template notes and on the total card under
  «Realized» as secondary text). US transition seed lots keep only their acquisition date (tax currency USD).
* **Unassigned fee – «open item visible» (build 58, owner decision).** The share of a transfer fee (USDT + value of
  a third-coin fee) that falls on coins WITHOUT a purchase price belongs to their unknown cost basis and cannot be
  added to a known one. It is no longer dropped silently: `PortfolioUnassignedFee` per transfer from
  `PortfolioFifo.spread` (FIFO, FIFO_WALLET), the Ø transfer branch of `PortfolioCalculator` and `TaxPool.addFee`
  (UK/CA pools without template rules); `CoinPosition.unassignedFeeUsdt`, `PortfolioSummary.unassignedFeeUsdt`,
  `CoinLedger.unassignedFees`; tax export info row after the totals (`TaxExport.unassignedFeesIn`, `gainsCsv`
  `unassignedFees`, text `tax_export_unassigned_fee`), never in a total. Card/position text
  `portfolio_unassigned_fee` («Fee not assigned – cost basis unknown»): on the coin's position card below the
  lost-fee-cost hint, and the portfolio sum as one line under the total card's «Hints» – in USDT like the other
  amounts there, hidden as «•••». The fee-coin disposal (`feeLeg`) is unchanged.
  Templates with `quoteFeeIsInfo` drop the USDT transfer fee before the calculation, so nothing is left open there.
  Cases: `portfolio.json` («B58 …»).
* **AT staking note (build 58).** AT keeps acquisition cost 0 for staking with own coins and airdrops (§ 27b Abs. 2
  EStG, BMF «Kryptowährungen»); the export note `tax_note_at_staking` says that the recorded value on receipt does not
  matter. Lending interest stays at the value on receipt (`TaxEngines.interestReward`, since build 55).
* **USDT line on the card (build 58).** `DepegAlarm.cardNote` (both platforms, parity `depeg_alarm.json` › `cardNote`)
  decides the «USDT = 1» line independently of the depeg alarm: fresh reading ≥ threshold → deviation warning (as
  before, always shown); else values hanging on USDT = USD (`PortfolioExchangeRoute.usdtParityShown`) → price-source
  line; else holdings containing USDT → `portfolio_usdt_assumed` («USDT dollar rate missing – counted as 1 US dollar»); else no line.
  With `usdtMarket = true` (USDT at market price, see below): a fresh plausible reading (`DepegAlarm.liveUsdtUsd`) →
  no line; otherwise `USDT_ASSUMED` whenever values depend on USDT – the visible marker of the 1-fallback.
  The total card gets the last reading, the threshold and the screen clock (`depegReading`, `depegThresholdTenths`,
  `now`) and calls `cardNote` itself; the price-source line moves under «Hints» unless it is the card line.
  `VIA_USDC` (`portfolio_usdt_via_usdc`) warns that a USDC depeg falsifies the value (build 85); Aster's quote «U» (United Stables) is deliberately not in `USD_STABLES` – the set also classifies base coins (`PortfolioStables.isStable`), so a coin «U» would be treated as a stablecoin.
* **Exchange connections checked (build 58).** `ExchangeVerification.LAST_VERIFIED` (Swift `lastVerified`) is the UTC
  date of the newest recorded exchange response; `ExchangeVerificationTest(s)` fail when a newer recording is added
  without moving the date. About shows it with `about_exchanges_verified` below the CoinGecko line, the date in the
  device's medium date format (Android `ofLocalizedDate(MEDIUM)` via `LocaleNumbers.dates`, iOS
  `CycleFormat.mediumDate`).
* **Country event rules (build 56).** `TaxTemplate.eventRules` (`TaxEventRules`, both platforms) is applied once
  before every tax calculation: `giftIsDisposal`; `spouseGiftNeutral` (UK TCGA 1992 s. 58, CA ITA 73(1): a GIFT
  marked «to spouse / civil partner» – own field `giftToSpouse` since build 58, effective only on a GIFT
  (`isGiftToSpouse`); builds 56/57 stored the marker `@spouse` in `transferTo` – is proceeds = cost);
  `networkFeeIsDisposal` (UK/US/CA/AU/JP: a network fee in crypto on a transfer or withdrawal becomes its own
  disposal of the fee coins at market value, placed before the transaction; no cost-basis increase, no extra
  loss); `quoteFeeIsInfo` (UK/US/CA/AU/JP: a withdrawal fee in USDT/cash is listed as information only, own
  `WITHDRAWAL_FEES` summary line, not netted – UK HMRC CRYPTO22150; elsewhere no rule allows a deduction).
  DE/AT/CH/FR/Neutral unchanged. Rules table and cases in `tax_templates.json` / `tax_export.json`.
  Build 57: `quoteFeeIsInfo` also covers a USDT/cash fee on a transfer between own wallets – `applyRules` drops it
  from the transfer (no cost-basis increase) and `TaxEngines.disposals` adds an info row
  (`PortfolioFeeExpense.TRANSFER`, own `TRANSFER_FEES` summary line, not in totals); DE (no administrative view on
  own-wallet transfer fees), CH (ESTV does not classify), Neutral and the portfolio view keep the fee in the cost
  basis, AT and FR never counted it (§ 27a Abs. 4 Z 2 EStG; not part of the French acquisition-price total).
* **Tax export (build 46).** `TaxExport` (Kotlin/Swift, fixture `tax_export.json`): realized gains per year
  (FIFO lot slices or Ø sales; acquired/sold date, holding days, «> 1 year» = sold after acquisition date + 1
  calendar year, local dates; cost at acquisition-date FX, proceeds at sale-date FX, daily ECB rates via
  Frankfurter, up to 7 days back), income per year (rewards at value when received), year-end holdings
  (cut-off CSV on 31 Dec). Totals only over rows with cost, proceeds and gain known, plus «proceeds without
  purchase price»; header states method, currency, time zone, «not tax advice». Exact decimals, rounding only
  when printing.
* **Money-path rules from the build 46 review.** A withdrawal without price is valued at that day's market
  price in period/history flows (never an invented loss); a deposit without price is valued at market as money
  put in (cost basis stays unknown); value alarms never fire or re-arm while a coin price is missing, %-change
  alarms not while «Today» skips coins; the card shows «Withdrawn: …» (value change of withdrawn coins until
  withdrawal) and «Rewards +… (not in gain)»; the history says when a range includes withdrawals.
* **Coin metadata and logos (build 46).** Lists first: the Binance symbol list and the Binance Alpha list are
  fetched in parallel, each on its own (`CoinLists`), and names plus α flags are saved and shown at once – before
  pair lists, CoinGecko pages and images. If a direct list fails, the app uses `coin_meta.json` from the GitHub
  release «data» (built by `mirror_data.py` from Binance lists only – never CoinGecko data or images). A snapshot
  (`coin_meta_snapshot.json`) and the CC0 icon pack «Cryptocurrency Icons» (`coin_icons.bin`, 64 px) ship with
  the app, so names and major logos show offline on first start; regenerate both with
  `android/tools/gen_coin_assets.py` (`--meta live` needs network – run it before each release so Alpha is in
  the snapshot). Images: all entries are downloaded (never per watchlist coin), 6 parallel per host, a
  429/403/503 pauses only that host (Retry-After, ≤ 6 h) and is never a per-image failure.
* **Build 47 rules.** Gift vs payment: `PAYMENT` is a disposal at market value in every template, `GIFT` only where
  `TaxTemplate.giftIsDisposal` (UK, CA, AU), elsewhere it leaves like a withdrawal. A fee row is SELL + price 0 + an
  import key; a manual sell at 0 is a real sale. Same-time events: inflows before outflows, then id (ledger comparator,
  reused by the tax engines). Bulk tickers: a row whose price key is present but null/""/0 is an inactive market and
  skipped (`BulkTickerRows.inactivePrice`); a missing key or unreadable value is a failed row (`bulkRow`). Ticker
  `timestamp` is always the fetch time, the exchange's time goes to `exchangeTime`. Price colours: every accent ×
  scheme keeps ΔE2000 ≥ 24 between the accent primary and rising/falling (`ColorDifference`, fixture
  `price_colors.json`). iOS strings: `ios/tools/ios_overrides.json` replaces single Android texts per language in
  the iOS catalog (German «Mitteilung» on iOS, «Benachrichtigung» on Android). Build number +1 with every state.
* **Tax templates (build 46).** `TaxTemplates`/`TaxEngines`: Neutral (default) plus DE, AT, CH, UK, US, CA, AU, FR,
  JP – each with cost method, tax-year boundaries (UK 6 Apr, AU 1 Jul), holding buckets, crypto-to-crypto
  treatment and thresholds, sources and «Stand» in code comments. Engines: UK same-day / 30-day / Section 104
  pool, CA superficial loss, FR prix total d'acquisition, JP total average, AT Alt-/Neubestand. Data rows state
  facts only («held > 1 year»); legal conclusions appear only as labelled orientation lines in the summary.
  CH (build 80, `TaxWealthCh`): «wealth value 31.12. (orientation)» = holdings at the end of 31 December (Zurich)
  × the 31.12. close in CHF, per coin, reusing the year-end holdings export; a missing or unverified close or FX
  rate marks that coin «not calculated» and the total incomplete (never today's price); capital gains and the
  wealth tax amount stay uncalculated; parity fixture `tax_ch_wealth.json`.
  The template is never applied silently from the device region. Every CSV text cell starting with = + - @,
  tab or CR gets an apostrophe (formula injection); numbers are written separately.
* **Binance Alpha (build 46).** With «Incl. Alpha tokens» on, Alpha tokens missing on Binance spot are added as
  Binance pairs `ALPHA_<alphaId>USDT` (α), priced in one bulk request from the Alpha token list and charted from
  the Alpha klines (source «Binance Alpha», no fallback chain). On other exchanges a pair is α when its symbol
  (1000-prefix stripped) is in the Alpha list, Binance does not list it regularly, it is not TradFi and the names
  don't clearly differ (`TokenIdentity`). Saved pairs always stay.
* **Fee-eats-amount rule (build 46).** A buy or swap whose coin fee is ≥ the amount received cannot be saved (CSV
  skips it); old data books the value as a fee expense everywhere. A quote fee on a withdrawal is a realized
  expense on the card, in periods and in the export.
* **Stale and partial values (build 46).** Portfolio alarms run only when every priced holding has a fresh
  price; widget and history may show a value with up to 20 % stale prices but must mark it («partly old
  price»). A partial total or «Since purchase» carries an inline marker at the number. Wrapped/bridged tokens
  keep their own symbol; only without an own pair are they priced via the base coin, labelled.
* **One holdings book (build 46).** `PortfolioHoldingsBook` / `PortfolioCalculator.holdingsAt(trades, time, method)`
  decides holdings for every screen and file: card, periods, history, widget, alarms, cut-off and tax export
  must never compute holdings another way (FIFO_WALLET caps sells/withdrawals per wallet). The realized
  «incomplete» flag comes from the same disposal rows as the tax export. Bulk ticker parsers report
  unreadable rows per pair (error, never «unchanged»); most rows failing = format error.
* **Swap, wallet FIFO, imports (build 46).** SWAP is one transaction (`toCoin`, `toAmount`, `swapValueUsdt`):
  disposal of the given coin at the swap value, new lot of the received coin at that value; without value the
  gain stays incomplete (USDT legs are automatic). `CostMethod.FIFO_WALLET`: queues per exchange/wallet (empty =
  «not specified»), transfers move lots with their purchase date, a shortfall never borrows from another
  wallet (`walletShortfalls`). CSV imports carry `importBatch`; «Undo import» deletes exactly one batch. Open
  lots per coin (`openLots`). DE annual summary (`TaxExport.yearSummary` with the DE template): ≤/> 1 year, § 23 EStG Freigrenze
  (1000 € from 2024, 600 € 2009–2023, 512 € before) and § 22 Nr. 3 (256 €), a Freigrenze (whole amount taxable
  at or above), labelled «orientation, not tax advice». Everything is called a working paper («Arbeitsunterlage»).
* **One day boundary (build 46).** Period windows, start holdings and the history buckets use UTC days like the
  daily candles; labels may show local dates. Unpriced inflows (rewards, deposits, swap-ins) and unpriced
  third-coin fees are valued at that day's market price in period flows; a coin is skipped only without any
  market price, and app card, rows, widget and alarms show the same figure with the same «without …».
* **Exchange time (build 46).** Never stamp server/envelope time as trade time (Binance closeTime, Bybit `time`,
  HTX `ts`, Bitget, Poloniex, MEXC futures, KuCoin, WOO X futures, Indodax): unknown exchange time lets «unchanged
  since» work from `priceChangedAt`. Exception (build 81): WOO X spot is an hourly close, so its exchange time is the
  end of the information in that candle (response time while it runs, start + 1 h once closed) and the 2-hour
  alarm rule and «outdated» apply; since build 91 the second request (last trade) replaces price and time with the
  real trade when it succeeds. A bulk response can declare itself incomplete (`bulkResponseComplete`; One
  Trading keeps only ACTIVE spot rows and is incomplete when it dropped futures or closed books), so a missing
  pair falls back to the single ticker instead of «not traded».
* **Token identity (build 46).** `TokenIdentity`: same contract → same token; known names compared after
  normalising (table for LUNA/LUNC, CAT, NVDA, TON…); clearly different names reject a fallback even within the
  price band; without names the old symbol + band rule applies (weak).
* **Watched addresses (build 46).** Read-only public BTC/ETH addresses (`AddressValidation` refuses seed
  phrases, private keys, xprv; EIP-55 checksum). The summed balance per coin is merged as a synthetic
  position part (`addressHoldingsExact`) – never written to the transaction table or CSV; it counts in total,
  widget and alarms, never in «Since purchase» or the tax export, and never turns purchased lots into «no
  purchase price». Periods and history use the observed balance history (`AddressBalanceHistory`: first
  observation plus the last per UTC day, stored with the address): an address counts only from its first
  observation, later balance changes are inflows/outflows at market, nothing is backdated. Background address
  refresh is capped (8 s total, 5 s per request) and never delays prices or alarms. Requests at most every 10 min per address, none without
  addresses; hosts are listed in the privacy policy. Addresses (not balances) are part of the app backup
  and follow «Portfolio in system backup».
* **Opening lots of watched addresses (build 84, rule build 85).** `AddressCostLots` count like buys in the address
  wallet, capped at the holding before the recorded movements of that wallet (observed balance + recorded outflows −
  recorded inflows, all dates, as FIFO per wallet books them); only the rest stays address part without cost basis.
* **Networks for watched addresses (build 46).** BTC (addresses incl. Taproot `bc1p…`, and xpub/ypub/zpub, derived
  on the device, gap 20 on the first scan; Taproot accounts (build 81) only as a `tr(…)` descriptor – origin,
  `/0/*`, `/1/*` or `/<0;1>/*`, BIP380 checksum checked – stored as `tr(xpub…/<0;1>/*)`, BIP86 key tweak, bech32m;
  a plain xpub keeps the old type detection; vectors in `taproot.json`), Ethereum and the EVM chains BNB Smart Chain, Polygon, Arbitrum, Base, OP Mainnet,
  Avalanche (native coin + curated token lists, one batched request per chain), Solana (SOL + SPL/Token-2022 by
  mint), TRON (TRX + USDT-TRC20), XRP, LTC, TON (+ USDT jetton), ADA (Koios). BEP-2 is rejected (Beacon Chain
  shut down 2024), DOGE/BCH are rejected (no keyless service whose terms allow it). Keyless public nodes only,
  every host listed in the privacy policy (a test checks it). «Show only» addresses never count; an address
  whose coin is also in own transactions asks «Count / Show only».
* **CSV import (build 46).** `csvimport/` parsers detect Relai/Swissquote by header names; times without
  offset are Europe/Zurich; duplicates by `importKey` (stored on the transaction). Rows without price
  import without price and are named in the preview. Conversion to USDT uses the daily FX rate of the last
  business day ≤ date.
* **Exchange exports (build 56).** `CsvImportBinance` (transaction history + both trade-history layouts; fills
  with the same second, pair and side are merged so both exports produce the same key; a buy and a sell of one
  coin in the same second in the transaction history are skipped; a Binance trade within 5 s of an imported one
  that is not an exact match is held back as `POSSIBLE_DUPLICATE`), `CsvImportCoinbase` (transaction report incl.
  preamble, current and 2021 layout), `CsvImportBitpanda` (transaction history with header block). Moves between
  accounts of one exchange are skipped (`INTERNAL_MOVE`). Fixtures: synthetic files in `android/testdata/csv/`
  (copy in `ios/Tests/Csv/`, first line says «synthetic»), cases in `csv_exchanges.json`.
* **Transfers across exchanges (build 56).** `CsvTransferMatch`: same coin, different exchanges, deposit between
  2 h before and 72 h after the withdrawal, deposit ≤ withdrawal, difference ≤ 5 % → one TRANSFER (amount sent,
  fee = difference in the coin). Within one file and against stored *imported* rows (manual rows are never
  changed). Exactly one candidate → preselected; several → shown, off (preview id `linkId` = «withdrawal →
  deposit», never stored). The linked row keeps both import keys in own fields – `importKey` = withdrawal,
  `linkedImportKey` = deposit (build 58) – so re-importing either file finds duplicates (`importKeys()` returns
  both). A stored row that gets linked moves into the new import run and keeps its original state plus the state of
  the absorbed row of the later file in `importRestoreState` (`CsvRestoreState`, versioned JSON `{"v":1,"stored":
  {…},"absorbed":{…}}`, documented in the class); `undoPlan` restores it exactly when the later import is undone, and
  undoing the *earlier* import turns the linked transfer back into that deposit or withdrawal, kept in the later
  import run (undoing the later run afterwards removes it). No length limit for linking any more (builds 56/57
  packed everything into the 200-character import key with `" ⇄ "`/`" ⟲ "` and skipped rows that did not fit;
  those forms are read only by `LegacyStorageMigration`, shortened `~hash` parts of old links still count as
  duplicates via `LegacyStorageMigration.shortKey`). Cases in `csv_transfers.json` (both undo orders and both in
  sequence).
* **Kraken ledger CSV.** `CsvImportKraken` reads the «Ledgers» export (txid, refid, time UTC, type, subtype,
  asset, amount, fee): trade/spend/receive legs paired by refid (money ↔ coin = buy/sell, coin ↔ coin = swap),
  deposit/withdrawal of coins, staking/earn rewards (inflows only); internal moves are skipped. Kraken codes
  are normalised (XXBT → BTC, ZEUR → EUR, DOT.S → DOT). Fixture `kraken_ledger` in `csv_import.json`.
* **Swap fees from both sides.** `CsvImport.swapFee`: one side → that fee; same unit → sum; different units →
  both rows skipped (`TWO_FEES`), never drop a fee silently.
* **Exact cut-off CSV (build 46).** `CutoffExport` works on `BigDecimal`/`Decimal`; Double prices are
  converted through their shortest decimal text (`PortfolioDecimal.of`); rounding only when printing.
* **Per-coin gain (build 46).** App list and widget rows show the position's own gain for the change
  basis («24h +1'250 CHF · +1.5 %», per-coin `todayByCoin`; percent ÷ (start value + inflows)), never the
  token's market change; the rows add up to the total «Today» (parity check `byCoin`). The widget list has a
  pinned «Total» row (share 100 %, total value, total gain) and the «As of» line (with «Today without …»); both always stay visible, coin rows give way first.
  Per-coin and total gain are persisted in the widget snapshot on both platforms.
* **Widget quantity (build 46).** `WidgetQuantityFormat`: at most 2 decimals, truncated (never more than
  held), below 0.01 two significant digits, from the snapshot's exact quantity text.
* **«Since purchase» with unpriced coins (build 46).** When part of a position has no buy price (deposit
  without price, address), «Since purchase» is computed for the priced part only and marked «without x COIN
  lacking a buy price» (`ledger_partial_cost`). Never fold unpriced coins in at cost 0.
* **CSV withdrawals need a choice.** Each imported withdrawal is either a transfer to an own wallet (cost
  moves along) or leaves the portfolio; the preview asks per row and proposes «transfer» only when a counted
  address matches. Never decide silently.
* **Overlap auto-switch.** An address switches to «Show only» automatically only when its amounts match the
  transactions (`overlapV2` / `ovl2` marker); otherwise it counts again once, with Undo.
* **Tax template texts = formulas.** Every sentence in `tax_note_*` / `tax_line_*` must describe what the
  engine does; a change to an engine needs its text in all 31 languages and a worked example in
  `tax_templates.json` (parity tested). CSV cell texts contain no `;` or `,`.
* **Exact checks and run log.** «Check on time» (Android only, off by default) uses `SCHEDULE_EXACT_ALARM`
  and falls back to WorkManager without the permission; every background check (worker, exact, BGTask,
  widget, foreground) writes one line to the run log (20 entries, no backup). A check within 5 min of the last
  one is skipped and logged as such. Since build 55 iOS widget refreshes also check price alarms (logged as
  «Widget · prices and price alerts»), with the same show → verify → mark order as the app (`AlarmDelivery`).
* **Snapshot time is never exchange time (build 46).** Only a real last-trade time may go into
  `ticker.timestamp`. Server/snapshot clocks (Bitso `created_at`, Crypto.com `t`, Bitstamp/Bitvavo/BtcTurk/ZebPay
  `timestamp`, Phemex ns `timestamp`, Gemini `volume.timestamp`, like Binance `closeTime`, Bybit/OKX/HTX `ts`) stay
  out, otherwise «Unchanged since» never shows.
* **TradFi flag.** Every futures parser whose API marks stocks/indices/metals sets `tradFi` (HTX `tradfi_labels`,
  Phemex `perpProductSubType = TradFi`), and the market is listed in `CoinLogos.TRADFI_MARKETS`.
* **Name lock for all sources.** After a name contradiction no source may supply a fallback series.
* **CSV fee rows** are disposals at the daily close (card, curve, coin detail, tax export use the same price);
  no close → unknown, never 0. **Withdrawal fees** count only for the covered share. **Tax engines:** CA one
  rebuy covers one loss; FR uncovered part unknown; UK late deposit joins the pool on its booking day.
* **One number locale.** Display and input use app language + device region (`LocaleNumbers.formatLocale`).
* **USDT depeg alarm.** Pure rule in `DepegAlarm` (parity `depeg_alarm.json`): both sources must deviate,
  fire once per episode, re-arm at half the threshold, quiet hours hold it. Its reading doubles as the live
  USDT/USD price of the valuation (`DepegAlarm.liveUsdtUsd`, ≤ 30 min, 0.5…1.5).
* **USDT at market price (owner decision Oct 2026).** USDT stays the internal unit (1 USDT = 1 USDT, all
  ledgers/history in USDT), but USDT → fiat (USD included; USD itself stays 1) uses the USDT/USD market price like
  USDC/DAI: `PortfolioStables.usdtUsd` (plausible 0.5…1.5, else **1 marked approximate** – never silent, values never
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
  and the own-candle cache (`phisto_USDTZUSD`, 12 h; gaps filled from Coinbase `USDT-USD`, build 84); `PortfolioDailyPrices.usdtUsd` carries them with every history
  load, and USD/fiat candles of own exchanges divide by them (`ownDailyUsdt`). Callers: total card and rows use
  `displayFxRate` / `displayRate(for:)` (FX × live USDT/USD) and `cardNote(usdtMarket = true)` (the deviation line
  and `portfolio_usdt_depeg_warning` are gone); history and «7 d»/«1 y» pass `UsdtUsdRates(live, daily)`
  (`PortfolioHistoryUi.usdtApproximate` → caption `portfolio_usdt_assumed`); widget snapshot and its draw-time rebase
  multiply by `PortfolioWidgetChart.usdtFactor` (not for USD – the widget shows USDT there); «Today» converts back
  with the snapshot's own factor (`fxFactor`); `PortfolioPriceSource` passes the live rate to `toUsdt`;
  `CurrencyConverter` passes it to `quoteToUsd` (watchlist «≈», alarms in another currency; target USDT ÷ USDT/USD);
  tax export passes `usdtUsd` (daily closes since the first needed date) and `TaxCsvTexts.usdtAssumed =
  portfolio_usdt_assumed`; the cut-off export multiplies its rate by USDT/USD of the date (today live) and adds the
  line `CutoffCsvTexts.usdtAssumed` when there is none (`CutoffExport.csv(usdtApproximate)`, column «Rate USDT→…»).
  The USD display currency keeps showing USDT on the card (no conversion); the portfolio-alarm fallback
  `PortfolioAlarmLogic.totalIn` (alarm in a currency other than the snapshot's) and the fiat-entry/CSV-import
  conversions still treat USDT as 1 USD.
* **Change basis must be visible.** A % from a UTC day open (`StarterPrice.sinceUtcDay`, currently unused since starters are Binance) is labelled «since
  00:00 UTC»; Hyperliquid's % (mark vs prevDayPx) is labelled «Mark» (`Market.changeIsMark`). WOO spot needs 24
  contiguous hourly candles.
* **Near high/low alarm** uses fallback series only with the mini-chart identity rule (name + ±25 %,
  `NearExtreme.seriesAllowed`, parity `near_extreme.json`).
* **Gemini**: an ambiguous symbol always goes through `/symbols/details`.
* **Background decisions are pure** (`work/BackgroundWorkPolicy.kt`): exact alarm arm/cancel, fired action,
  rescheduling actions, worker outcome. Receivers and the worker only call it. No `runBlocking` at app start.
* **User-Agent** carries the real app version on both platforms (`HttpUserAgent`). Exception (build 104): every
  host that sees watched addresses (`AddressBalance.PRIVACY_HOSTS`/`privacyHosts`) gets `HttpUserAgent.NEUTRAL`
  (Firefox ESR 153 string, one value for both platforms, `address_privacy.json`) on *every* request, gas fees and
  halving included (Android: `UserAgentInterceptor` host rule; iOS: `MarketHTTP.sessionFor(host:requested:)`). The
  address client has its own connection pool/dispatcher (iOS: own sessions). It hides the app name, not the fact
  that it is an app (TLS/HTTP fingerprint).
* **Live stream message cap (residual risk, build 104).** Android checks the 1 MiB cap only after OkHttp has read
  the whole message (text and, since build 104, binary → UTF-8 like iOS `.data`): OkHttp has no incoming size limit
  and always offers `permessage-deflate`, inflating before the listener (switching it off would need a network
  interceptor stripping `Sec-WebSocket-Extensions`; not done – more traffic, and uncompressed frames are unbounded
  too). A hostile or broken exchange endpoint (TLS-authenticated) could therefore exhaust memory before the cut;
  iOS limits inside `URLSessionWebSocketTask` (`maximumMessageSize`).
* **Stablecoin trades are swaps (CSV).** A buy/sell priced in a stablecoin (`PortfolioStables` set) is imported as
  a SWAP into/out of the stablecoin; only fiat (`CsvImport.FIAT`) makes a BUY/SELL. FR/AT treat crypto→stablecoin
  as neutral; a manual «Sell» always means a sale for fiat. Converted rows keep their old key as accepted duplicate.
* **Tax rules by date.** US lot method is per wallet from tax year 2025; AT swaps before 1.3.2022 are disposals,
  Altvermögen = acquired before 1.3.2021; CA superficial loss and all gain/loss decisions use the tax currency
  when rates exist. Every rule has an `[R10]` fixture in `tax_templates.json`.
* **Background budgets.** Exact checks have a deadline (receiver ~19 s) and at most 8 s per exchange
  (`CheckBudget`); widget receivers stop after 9 s and draw from cache first. A timeout marks only that pair.
* **One path for price notifications.** Every place that turns a price notification on goes through
  `showPriceNow` / `Notifier.showPriceNow` (`PriceNotificationRule`): master switch and «pin» setting apply.
* **When two rows are the same pair (`WatchIdentity`).** Exchange, base, quote and contract — for DexScreener
  additionally the pool (`pairId` = `chainId/pairAddress`): two pools with the same symbol on the same chain are two
  rows. Exchanges keep the key without the id (ids are spelled differently in old entries). Used for adding
  (`WatchDao.findWatchId(…, pool)` / `AppData.insert`), editing (`WatchEdit.Key.pool`), undo (`restore`) and backup
  restore (`WatchlistRestoreCleanup`). Android's unique index includes `pairId` since app DB v16
  (`MIGRATION_15_16`); NULL ids never collide in SQLite, so the app-side check stays the rule for exchanges.
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
  only from the **same futures source** (a sample of another exchange restarts the history; old
  samples without a source match any). The current value is compared with the **median** of the
  samples at least 30 min old; the direction counts as consistent only if the value is above (or
  below) every one of them, otherwise «Open interest mixed». The old single stored sample is migrated
  as a sample without a source. Alarms keep their own 26 h history.
* **Funding trend.** `FundingTrendRules`: the last 4 settlements (`limit=4`) of the coin's USDT
  perpetual from Binance Futures / Bybit / OKX, the pair's own exchange first; fetched only when the
  sheet opens, at most once per pair every 10 minutes. Rates are compared per 8 hours.
* **Stablecoins and volume.** A USD stablecoin against USD or another USD stablecoin (`StablePeg`)
  is described by its deviation from 1:1 (notable from 0.5 %, strong from 2 %); market, volatility
  and position factors are dropped. Volume without quote volume is compared in coin units and marked
  «estimated»; with more than 6 of the 24 hours without trades the volume factor is left out.
* **Futures data per exchange.** `FuturesSources` (`data/remote/FuturesExchanges.kt`, Swift in
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
  symbol, `coinMContractUsd`, build 45; only without it BTC 100 USD, others 10 USD), `fundingRate` history;
  `FuturesParsers.binanceCoinM`, source «Binance COIN-M»). `FuturesSources.own(marketKey, pairId)` /
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
  factors in hundredths, 0 = off) are checked twice a day (`ZoneCheckWorker` / `ZoneCheck`):
  notify once on crossing, re-arm when the value is back (`indicator_crossed_<kind>`).
  Tests: `BitcoinIndicatorModelTest`, `IndicatorAlertRulesTest`, `HalvingMathTest` (Swift in
  `MarketsTests.swift`).
* **Candles come from the pair's own exchange first; fallback only labelled and same-token
  checked** (build 44). Sheet chart, mini chart, single-coin widget, the 24 h fallback reference and
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
  candles, never the perpetual's or the spot's. Since build 45 volume alarms, quick alarms and «Why?»
  load a watchlist pair's hourly candles through the same acceptance as the chart
  (`VolumeDataSource.pairCandles` → `pairCandlesOf`, `OwnCandlePolicy.acceptsFallback`): own exchange
  first, chain only with the same-token check, no chain series without a price, for TradFi, DEX or
  dated futures. A new exchange source: object + registry entry on both
  platforms and a case in `own_candles.json`.
* **Chart FX per candle.** When a fiat pair has no candles of its own, the sheet chart uses the
  coin's USDT candles and converts **each candle with that day's rate** (Frankfurter time series,
  the one the portfolio value history uses; at most once per currency every 6 h). Only if the series
  is missing it uses today's rate and the caption says «(current rate)» instead of «converted at
  daily rates». Never convert a whole history with one rate silently.
* **Chart cache invalidation.** The stored chart (`SheetChart` cache, keyed by base and quote) is
  dropped on a manual refresh, pull-to-refresh, and when the live price has moved more than 3 % since
  it was stored (`SheetChart.PRICE_JUMP`, `priceJumped`). Axis minimum: a span of 1 % (±0.5 %) around
  the middle for stablecoins (`STABLE_MIN_SPAN`), 0.2 % (±0.1 %) for other flat series (`MIN_SPAN`); since
  build 44 the widget charts use the same minimum spans (`WidgetChartGeometry`). Approximated volume bars (no quote volume from the source, e.g. Coinbase: amount × close) are
  drawn at half strength and marked «≈». Geometry is computed off the main thread.
* **AssetKind and Alpha.** `AssetKind.of(marketKey, base, tradFi, alphaSymbols)` (Android
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

* **Shared building blocks (build 56).** One card (`AppCard` / `.appCard(level:)`: top 20, nested 16, always
  `surfaceContainer`, never a border), one primary button (`AppFab` / `PortfolioAddButton`, accent fill, 56),
  one empty state (`EmptyState` / `EmptyStateView`: icon → title → message → example → button → links, scrolls),
  in everyday words (build 58, portfolio: example without a USDT price, button «Add your first purchase», then the
  text buttons «Bought on an exchange? Import its file» (keeps the `portfolio_import_csv` test tag) and «Just watch
  a wallet address»),
  one unavailable row (grey cloud icon, «Market data currently unavailable», Retry – red only for user-input
  errors). Tab bars grow to about 1.3× text size; beyond that iOS shows the Large Content Viewer, Android a
  tooltip. The watchlist base symbol is never truncated. Typography: display 34, headline 22, section title 16
  semibold; `labelSmall` / `.caption2` only for source credits, legal footers and chart labels – capped by
  `TypographyUsageTest`. A new screen uses these blocks instead of its own variants. Build 57: the watchlist
  mini-chart is 48 instead of 56 dp/pt in rows narrower than 360 (`DenseRowText.sparklineWidth`, both platforms),
  and the watchlist exchange/time line and the converted price use body size (14 sp / `.subheadline`); other
  dense-row metadata (coin name, notes, errors, portfolio rows, market row captions) stays at 12 because a fixed
  line count would cut it at the largest font scale.
* **Plain-language market summary (build 56).** `MarketPlainSummary` (pure, both platforms, cases
  `market_plain_summary.json`) turns data the Market tab already has into three sentences (direction, mood,
  what stands out) shown above the cards; no new requests.
* **No change pills – app and widgets.** Every change is coloured text (arrow + signed value in the price colour, U+2212 for minus, grey for 0/unknown/deactivated, «Ø» averages in the warning text colour); the time window is always small secondary grey (rules in section 15). This holds for watchlist rows, the pulse line, the action sheet, «Market data», «Why?», the Market cards (Fear & Greed number in its scale colour, «Crypto market … ↘ −2.77% · 24h», «Unusual today» rows with grey «24h»), the Portfolio (Today · 7 d · Total, the 1-year line under the total, hidden while the history is shorter than one year) and all home-screen widgets. Status indicators (`DataStatusPill`) are not change pills and stay.

Layout decisions that keep the app calm. Same structure on Android and iOS, same file names.

* **Settings structure.** Main page (`SettingsScreen.kt` / `SettingsScreen.swift`): **General**
  (Language – on iOS it opens the system Settings app –, Currency, Watchlist, Portfolio, Alarms, Price updates),
  **Appearance** (Mode, Theme, Price colours, Widgets), **More** (Data / backup, one row **Advanced**, About) and
  «Reset app» alone in its own red card at the very bottom (build 53 «Weg 1», regrouped in build 55). Since build 83
  Portfolio has exactly one place: the row under General opens the Portfolio page (`PortfolioPage` /
  `PortfolioSettingsPage`) with the «Show Portfolio tab» switch on top, then lock, hide amounts, cost method, tax
  template and «Portfolio in system backup» – so «Settings › Portfolio» in hints and the widget is literally true;
  the search lists that page first for «Portfolio».
  «Advanced» (`AdvancedSettingsPage` on both platforms) holds Token & TradFi, Market alerts, Speech
  output, Coin logos and Developer; its sub-pages
  are unchanged and «back» returns to the Advanced page. **Rule: the main page stays short.** A new
  detail or fine-tuning setting goes under «Advanced» (onto an existing sub-page there, or a new
  sub-page reached from the Advanced page) – never as a new row or group on the main page. Only a
  setting most users need goes onto an existing main-page sub-page (e.g. Watchlist, Portfolio). The
  settings search (`SettingsSearch` / `SettingsSearchUi`) must still find **every** item: entries on
  the Advanced page show the breadcrumb «Advanced › …» and open their sub-page with the item
  highlighted. New items need a search entry (and a backup field, unless they are device-only flags).
  Hints that name a path use the full path, e.g. «Settings › Advanced › Price updates › Live while open».
* **Settings icons.** The line icons before the main-page rows are monochrome in the text colour
  (white in dark mode, black in light mode) – Alarms a bell, Market alerts a lightning bolt; only
  «Reset app» keeps the error colour. The accent colour is reserved for the heading markers
  (accent bar), switches and selections (chosen row, check mark) – never for row icons.
* **Action sheet order.** Price header with one line «↗ +41.71% · 24h · Updated HH:MM» directly
  below (change in the price colour, period and time small and grey; no second row of changes), then
  the tiles Alarm · Why? · Favorite, then chart, range, «Market data» and «More». No second 24 h change
  next to the range on 24 h (the line under the price already shows it); on 7 d / 30 d / 1 y the
  change stays as coloured text, because it shows that range's change. Since build 45 the sheet opens
  at **half height** (price, actions, start of the chart) and can be dragged to full height.
* **Portfolio total card is a report** (build 45). The card shows the amounts only, on a calm
  gradient (8 % → 2 % of the accent colour, was 16 % → 4 %); all hints (price sources, average cost,
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
  both platforms no choice suppresses the hint (Android `LIVE_SERVICE_COVERS_CLOSED_APP`, iOS
  `liveServiceCoversClosedApp`, both `false`). Never promise faster checks with the app closed. The «shown» flag (`alarmTimingHintShown`) is device-only and not part of the backup.
* **iOS background time budget.** `RefreshBudget` (`BackgroundRefresh.swift`): network at most 15 s
  (`PriceRefresher(networkBudgetMillis:)` cancels the remaining exchanges), whole task 25 s, portfolio
  only with at least 6 s left, side checks with at least 5 s, 2 s reserve before the expiration; the
  task is cancelled at `remaining − reserve`. `SharedStorage.flushSnapshotWrites()` runs before
  `complete(success: true)` and before portfolio alarm notifications, so a widget never shows a value
  older than the alarm. `BackgroundOffBanner` warns above the alarms when Background App Refresh is
  off/restricted or Low Power Mode is on.
* **Backup alarms are validated.** Pair alarms go through `AlarmJson.parse` (Android) /
  `BackupManager.alarmFromJson` (iOS): unknown condition, non-finite threshold or missing id/watchId →
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
* **Calm defaults for new installs (Build 42).** Non-dismissible («ongoing») price notifications are
  off. New pairs (also the starter watchlist) get the price notification since 16 Oct 2026
  (`WatchRepository.DEFAULT_NOTIFICATION_ENABLED = true`; owner decision: someone installing a price checker
  wants to see the price; before that it was off). Existing installs keep their value through
  a one-time migration (`OngoingNotificationsMigration`, `AppSettings.ongoingForMigration`). Changing a
  default means a migration that keeps the stored behaviour of existing users.
* **Watchlist header.** The add-pair button carries its text («+ Add pair», `AddPairButton`, iOS
  `addPairButton`; only the starter screen uses the round «+» because a text button sits below it).
  The alarm bell is always visible, marked when alarms are active (Android filled, iOS with waves), without
  a count. Since build 60 its target follows `AlarmBellRoute.of(alarmCount, watchCount)` (Android
  `domain/watch/AlarmBellRoute.kt`, iOS `AlarmBellRoute.swift`, same test cases): with at least one alarm
  (everything the overview would list — pair alarms also when switched off, portfolio alarms only with the
  portfolio on; not loaded yet counts as «has alarms») the overview as before; with none it leads straight to
  creating one — one pair: its alarm page (which opens the editor), several: the pair picker of the empty
  overview first (`AlarmPairPicker`, iOS `confirmationDialog`), empty watchlist: add a coin (explorer). The
  starter screen's bell does the same; the menu item «All alarms» always opens the overview.
* **«What is moving with it?» is announced** (build 60). The starter screen (empty watchlist) and the first-run
  gesture hint carry one line «Tap a coin to see what is moving along with it» (`watch_hint_tap_why`; starter
  with the ⚡ symbol, gesture hint as its bold first line). Tapping a row stays the only way in (action sheet).
* **Thin watchlist row** (build 45). A row shows logo, pair, price, change (and mini chart if on) and
  at most **one** bell: the alarm bell with waves, only when the pair has at least one active alarm.
  The price-notification state is no longer drawn in the row (only on its switch in the action sheet),
  and the source note for a foreign change / mini chart moved to the action sheet (see «Row source»).
  Sort arrows in the column header are 11 sp in `onSurfaceVariant` (no transparency) with a touch
  target of at least 48 dp (`WatchlistSortBar`). The alarm empty state (overview and per pair) shows
  an example sentence in the style of the alarm sentences («For example: Tell me when BTC rises above
  90,000.», `alarms_empty_example`).
* **Cut-off export is checked like the history.** `PricePlausibility.acceptSourceClose` is strict for the
  cut-off CSV: every source needs a valid current price; Binance spot must pass `sameToken`, other sources the
  25 % rule. Otherwise the price field stays empty with «unchecked» – never export an unchecked number.
  Format (build 45, `CutoffCsvFormat.forLocale`): UTF-8 with BOM, CRLF; separator and decimal mark follow
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
  (since build 69, like the pulse widget) shows the number large with the level word next to it and one level dot
  (`MarketScaleColors`; since build 80 in the user's price colours via `MarketScalePalette.steps` — fear = down,
  greed = up, incl. colour-blind scheme, high contrast, swap and accent substitutes, so it matches the price arrows;
  the pulse widget derives the same steps from its own up/down colours) on a neutral `outlineVariant` track — no five-colour gradient. Widget text at least 11 sp/pt. Light dividers
  (`outlineVariant`, all light schemes and iOS `ColorTokens.outlineVariant`) are `#ADA9A0` since build 58:
  2.23:1 on `#FBF9F6` (before `#C9C6BF`, 1.62:1), deliberately below 3:1 – the only outline of a control is
  `outline`. Dark mode unchanged (`#474747`).
* **Fixed exchange price colours** (decision 18 October 2026: «colours like every exchange; the accent
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

  «Calm» dark falling (same decision): salmon red `#FFB097` (HSL 14°, C* 35.6, before the pinker `#FFAFA8`),
  still 1.5:1 lighter than rising, also after deuteranopia simulation; beside the orange accent `#FFAFBB`
  (HSL 351°, ΔE 20.3 to `#ED835E`); beside the red accent plum stays (any red ≥ 24 from `#FF7173` is almost white).
* **Alarms wait for a deliverable notification.** One-time and repeating alarms (pair and portfolio, both
  platforms) fire only via `QuietHours.mayFire(holdAlarms, deliverable)`: during quiet hours or without a
  deliverable notification nothing is written and the alarm stays armed. Never mark an alarm as triggered
  before checking (iOS `Notifier.isAuthorized()`, Android `canShowAlarm` / `canShowPortfolioAlarm`).
* **Foreign candle series need the same token.** `SheetChart.accept` (action sheet) and `sparklineFor` (mini
  chart) accept a series from another exchange only if its last close × FX rate is within
  `DayChange.MAX_PRICE_GAP` (25 %) of the pair price; no price or no rate → no chart. Never scale a series
  to the pair price without this check (cases in `testdata/parity/sheet_chart.json`).
  `PricePlausibility.sameToken` returns false without a valid reference.
* **Row source.** When the % change or the mini chart of a watchlist row come from another
  exchange than the pair's, the action sheet says so under the price (`DayChange.rowSource` →
  «Change: …», «Chart: …», «Change & chart: …»); since build 45 this note is no longer in the row
  itself (too dense). Since build 60 the row marks a % change from another exchange's candles with a small
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
  battery dependent), iOS checks when the system allows; there is no «Always frequent» any more.
  Any change to alarm timing or quiet hours needs the store texts in all 31 languages.
* **Widget previews.** The picker previews (PNG `widget_preview_*_img.png` and the Android 12+ preview
  layouts `layout/widget_preview_*.xml`) must show what the live widgets draw. When a
  widget's look changes, redraw its preview in the same change, with the shared sample data (BTC
  83,142.00, ETH 2,505.49, SOL 109.60, XRP 1.394 – Android `values/widget_preview_samples.xml`, iOS
  preview data in `Widgets/WidgetData.swift`). List dividers in the watchlist and portfolio list widgets
  are neutral (text colour at 25 %, 45 % with high contrast, `WidgetContrast.dividerColor` /
  `WidgetStyle.divider`), never the accent colour. Widget backgrounds are neutral too: since build 45
  the iOS widgets are flat like Android (plain `surfaceContainer`, no gradient, no accent glow; same
  logo, title and row sizes; disabled pairs grey instead of 60 % opacity).
* **Logo sync after a network failure.** `CoinLogoRetry` (both platforms): a failed list fetch without
  internet is not recorded as an attempt, and failed images are not blocked for a day when the network
  was the cause; the sync runs again when the connection returns and on the next foreground, with a
  growing pause between attempts (5 s → 15 s → 30 s → 60 s … at most 15 min, `backoffMillis`), and
  never two syncs in parallel. Same requests as a normal sync – no per-coin fetch.
* **File size (build 58).** Screens, view models, widget renderers and the notifier stay below ~450
  lines. A longer file is split by responsibility into files of the same package/folder – pure moves, no
  behaviour change – each with a German header comment (what it holds, why it is separate); a twin on the
  other platform gets the same file name. View models keep their state (flows, jobs, `@Published`) in the
  class; actions move into Kotlin extension functions (members they use become `internal`) or Swift
  `extension` files (`private` → internal, `private(set)` dropped only where an extension writes). Public
  API called from `data/`, `domain/` or `work/` stays a member. File map:
  watchlist VM → controllers that own their state privately (build 77): `WatchlistStarterController`,
  `WatchlistEditController`, `WatchlistRefreshController`, plus the injected `WatchlistSheetData` (iOS: data helpers of
  `WatchlistRow`) and `WatchlistGroupFilter`; the screen collects one `WatchlistUiState` and the live-price map; explorer VM →
  `ExplorerSearchActions`, `ExplorerSelectionActions`, `ExplorerBulkActions`, `ExplorerDexActions`; iOS
  `ExplorerRecentSearches`/`ExplorerQuickFilter` (as Android), `ExplorerPicker`, `ExplorerFeedback`; Android
  `ExplorerPreciseSelection`; market VM (`InfoViewModel` / `CycleViewModel`) → `MarketDataLoaders`,
  `MarketDataSync`, Android `MarketPreviewActions`; `CryptoPulseCard` → `CryptoPulseParts`; `MarketRow` →
  `MarketSectionHeader`, iOS `MarketInfo` and `MarketCoinSheets` (as Android); `WatchWhyFactors` →
  `WatchWhyTexts`, Android `WatchWhyAllValues`, `WatchWhyReasons`; `WatchlistRow` → `WatchlistRowParts`,
  Android `WatchlistConversion` (as iOS), iOS `WatchlistTime` in `WatchlistStatus` (as Android `ago`);
  Android `WatchlistStatusPill`; `WatchActionsSheet` → `WatchActionsHeader`; iOS asset-kind icons →
  `Components/AssetKindIcon.swift` (as Android); portfolio → `PortfolioTotalFigures`, `PortfolioStatusPill`,
  Android `PortfolioTotalHints`, `PortfolioHistoryHeader`, `PortfolioHistoryControls`,
  `PortfolioHistoryCompareSection`, `PortfolioChartRenderer`, `PortfolioCompareChart`,
  `PortfolioPositionCard`, `PortfolioTxRow`, `PortfolioExportTexts`, `PortfolioTaxSummaryCard`, Android
  `PortfolioAddressRow`, `PortfolioTaxPrices`; alarms → Android `AlarmDraft` (as iOS), `AlarmCard`; settings
  VM → `SettingsBackupActions`, `SettingsAppLockActions`, `SettingsAlarmActions`; `AppNavHost` →
  `AppTabBar`; portfolio widget → `PortfolioWidgetOverview`, `PortfolioWidgetList` (both), Android
  `PortfolioWidgetTexts`, `PortfolioWidgetValues`, `PortfolioWidgetChartView`; single widget → Android
  `SingleWidgetCandles`, `SingleWidgetChart`, iOS `SingleWidgetProvider`; `AppNotifier` →
  `AppNotifierPrices`, `AppNotifierDelivery`. Phase 2: portfolio VM (`PortfolioViewModel` / `PortfolioModel`) →
  `PortfolioLoadActions`, Android `PortfolioEditActions`, `PortfolioAddressActions`, `PortfolioTxDraft`,
  `PortfolioHistoryLoad` (both), iOS `PortfolioUi` (as Android); transaction sheet → `PortfolioQuickAdd`,
  `PortfolioTxFields` (iOS also `PortfolioTxLedgerFields`, `PortfolioTxActions`), Android `PortfolioTxInput`;
  CSV import → Android `PortfolioCsvImportViewModel`, `PortfolioCsvImportPreview`, iOS `PortfolioCsvImporter`,
  `PortfolioImports` (both); iOS `PortfolioScreen` → `PortfolioScreenActions`, `PortfolioToolbar`; `AppData.swift`
  → `AppData+Watchlist`, `+Portfolio`, `+Refresh`, `+Live`, `+Settings`, `+CoinLogos` (next to `+Alarms`,
  `+Reading`, `+WatchEdits`, `+WatchedAddresses`); `BackupManager.swift` → `BackupLimits.swift` (as Android),
  `BackupManager+Portfolio`, `+Watchlist`, `+Settings`; `WatchlistScreen.kt` → `WatchlistScreenEffects.kt`.
  Split in build 77: `PortfolioTxSheet.kt` (frame only; `TxFormState` in `PortfolioTxFormState.kt`, sections in
  `PortfolioTxSections.kt`, save checks in `PortfolioTxForm.check`), `TaxEngines` (one file per country engine),
  `TaxExport` (Rows/Summary/Csv; iOS in `App/Features/Portfolio/Tax/`), `PriceRefresher` (`RefreshLock` with a
  `RefreshLockScope` token, `LivePriceApplier`, `ChartRefresher`, `RefreshResultProcessor`, `RefreshSideChecks`),
  `CoinLogoRepository` (facade over `LogoImageStore`, `CoinListSync`, `AlphaTokens`, `CoinNames`).
  Build 81 split every production file over 650 lines by responsibility (largest now ≈ 455 lines), with parallel
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

## 19. Storage encryption

Since build 59 user data is encrypted at rest on both platforms (owner decision 17 Oct 2026). The rule above all
others: **a migration may fail, it may never lose data** – the plaintext file is replaced only by a verified
encrypted copy, through an atomic rename, and every failure leaves the plaintext file as it was and retries on the
next start.

**Android – SQLCipher for both Room databases.**
* Library: `net.zetetic:sqlcipher-android` (`@aar`, no transitive dependencies), version in the version catalog
  `android/gradle/libs.versions.toml` (`sqlcipher`). `androidx.sqlite` (`SupportSQLiteOpenHelper`, `sqlite-framework`) comes from
  `room-runtime` as an api dependency – no own pin. R8 keeps `net.zetetic.**` (JNI). Native library per ABI
  (`libsqlcipher.so`, 16 KB page aligned since 4.6.1).
* `LocalDataModule` gives both builders the same `EncryptingOpenHelperFactory`. It decides on the **first access**
  to a file (Room never touches the database on the main thread, so the one-time migration never runs there):
  encrypted (`SupportOpenHelperFactory` with the raw key), plaintext fallback (framework SQLite as up to build 58) – never for a new portfolio database (build 81): without a key
  `cryptochecker_portfolio.db` is not created; `Decision.Refused` opens an in-memory SQLite instead
  (`DatabaseEncryptionNotice.inMemory`, banner `portfolio_storage_in_memory`; the portfolio callbacks skip the mirror
  takeover and the legacy import, `importHoldings` waits) and the next start tries again; an existing plaintext
  portfolio or watchlist file without a key is only read since build 91 (`OpenPlan.COPY_TO_MEMORY`,
  `Decision.PlaintextKept`: `PlaintextMemoryCopy` attaches the file and copies every table into the in-memory database
  when the Room `identity_hash` matches; the file stays byte-identical, changes are not saved – like iOS
  `ProtectedFile.write(requireSealed:)`; `PlaintextMemoryCopyTest` on the device) and migrates at the next start with a key
  (`DatabaseEncryptionMigration.planOpen`),
  or – while the Keystore temporarily refuses the key of an encrypted file – `DatabaseKeyUnavailableException`
  and nothing changed. Background entry points skip the run and retry later, logged at debug level
  (`Throwable.isDatabaseKeyUnavailable()` checks the cause chain): `PriceUpdateWorker` returns `Result.retry()`,
  `ExactCheckReceiver` re-arms the next exact alarm, widget receivers (`launchAsync`/`ReceiverAsync`) keep the drawn
  state, `PriceWidgetService` keeps its rows (an exception there would kill the process). The UI shows its usual
  error state.
* Keys: `DatabaseKeyStore` – per database 32 random bytes, wrapped with AES-256-GCM by a Keystore key of its own
  per database (`cryptochecker_db_key_v1_app`, `…_portfolio`; `DatabaseKeyStore.aliasFor`), so a permanently broken
  Keystore key only affects that one database. Creating a Keystore key runs under a process-wide lock (two first
  opens at the same time would otherwise overwrite each other's key). No user authentication (WorkManager, widgets
  and alarm receivers must work after the first unlock; no component is `directBootAware`). Stored in `noBackupFilesDir/db_keys/<db>.key`
  (format: version byte, 12-byte IV, ciphertext + tag). SQLCipher gets the key as raw key `x'<64 hex>'`, so no
  PBKDF2 runs per connection. A key file is only (re)created while no encrypted file depends on it; a replaced
  one is kept as `.previous`.
* Orchestration: `DatabaseEncryptionMigration` (pure Kotlin, harness + `DatabaseEncryptionMigrationTest`), file and
  SQL layer `SqlCipherDatabaseFiles`. Whether a file is encrypted is read from its header
  (`SQLite format 3\0` = plaintext) – no preference flag that a restored old backup could contradict. Steps for a
  plaintext file: delete a stale `.encrypting` copy → `wal_checkpoint(TRUNCATE)` → the WAL must be empty (else
  fallback) → fingerprint (user_version, per table row count + order-independent row checksum over all columns,
  incl. `sqlite_sequence`, without `android_metadata`) → `ATTACH … KEY` + `sqlcipher_export` + `user_version` into
  `<db>.encrypting` → fingerprint of the copy with the key must be identical → delete `-wal/-shm/-journal` →
  rename the copy over the database. Room migrations (app DB 1 → 16, portfolio 1 → 4) run afterwards on the
  encrypted file. `MarketDatabase.db` (predecessor project) is still only deleted, never migrated.
* Plaintext fallback is not silent (build 69): `EncryptingOpenHelperFactory` records every decision in
  `DatabaseEncryptionNotice` (own SharedPreferences, never in a backup; rule `EncryptionPending`, pure Kotlin +
  `EncryptionPendingTest`): `Plaintext` and `Refused` add the database, `Encrypted` removes it, `KeyUnavailable` changes
  nothing. While the set is not empty, Settings › Backup shows «Encryption not active yet – the app will try again
  at the next start» (`backup_encryption_pending`); persisted, so it also covers a database not opened in this
  process (portfolio).
* Before Room opens an encrypted file the key is checked (`opens`): Room's `onCorruption` would delete a file that
  SQLite does not recognise. All own connections use an error handler that never deletes.
* Key permanently lost (alias gone, invalidated, wrong tag, or key does not open the file): the file is renamed to
  `<db>.unreadable` (+ `-wal/-shm`), a new key and an empty database are created, `StorageRecoveryNotice` shows
  once «restore a backup file» (`storage_recovery_title/message`). With «Portfolio in system backup» on, the new
  portfolio database imports the local mirror on creation by itself.
* System backup: the encrypted `cryptochecker.db` is useless on another device, so the backup rules carry
  `cryptochecker_system_backup.db` instead – `xml-v28/backup_rules.xml` (Android 9–11) and
  `data_extraction_rules.xml` (12+), **not** `xml/backup_rules.xml` (Android 8/8.1, since build 60): API 26/27
  ignore `requireFlags="clientSideEncryption"` and would upload the plaintext copy without end-to-end encryption.
  There `SystemBackupAgent` does not create the copy at all (`SystemBackupScope.writesDatabaseCopy(sdkInt)`, same
  limit 28 as `PortfolioLockPolicy.SYSTEM_BACKUP_MIN_SDK`) and only runs the rule-based backup (settings,
  favorites, language, migration flag); a restore on such a device brings no watchlist (the redirected file is
  not in the rules and Android drops it). `PortfolioLockPolicyTest` checks the three rule files. `SystemBackupAgent` (`android:backupAgent`,
  `fullBackupOnly`) writes and verifies that plaintext copy only inside `onFullBackup`, runs the normal rule-based
  backup (`super`) and deletes it; if the copy fails, the backup run is aborted so the previous backup survives.
  `onRestoreFinished` turns the restored copy into `cryptochecker.db`, which the next start encrypts like an update.
  A build ≤ 58 backup brings `cryptochecker.db` (+ `-wal`) itself; Android checks restored files against the
  *current* rules and would drop them, so `onRestoreFile` redirects them to the copy's name (the rules also list
  `cryptochecker_system_backup.db-wal`, restore only – it never exists during a backup) and the same path follows. The portfolio database and the key
  files are never in a backup; `portfolio_system_backup.json` stays the opt-in plain JSON mirror.
* Portfolio widget snapshot (build 60): `PortfolioSnapshotStore` keeps the whole snapshot (totals, positions,
  P/L, daily/period changes, the held coins' price samples and hourly prices) as **one** sealed entry
  `sealed_snapshot` in the SharedPreferences `portfolio_widget` – the widget reads synchronously, a small blob
  decrypts fast. Payload: a JSON object whose keys are the former single entries (`total`, `positions_v2`, …
  `coin_pnl_v7`), so the per-version fallbacks stay. Container and rules in `PortfolioSnapshotSeal` (pure Kotlin,
  harness + `PortfolioSnapshotSealTest`): Base64 of `CCWS` + version byte 1 + 12-byte IV + AES-256-GCM ciphertext
  and tag, magic and version as AAD; `planRead` (no key → nothing; sealed → open; only plaintext of build ≤ 59
  → read once, write sealed, remove the plaintext keys) and `planWrite` (no key → write nothing, a sealed entry
  stays, plaintext keys are removed). A broken, foreign or newer entry opens as «no snapshot» and the next save
  replaces it (it is a cache). Key: `PortfolioSnapshotKeyStore`, own Keystore alias `cryptochecker_widget_key_v1`
  (no user authentication, created under a process-wide lock, a permanently broken entry is deleted and
  recreated) – deliberately independent of `DatabaseKeyStore`. Without a key every reader gets `null`, so
  widgets show their empty state (`widget_portfolio_empty`) and the Portfolio tab starts without the stored
  «Today» values until it has computed its own (`showStoredToday`). Still plain in that file: the remembered FX rate (`fx_*`, no holdings). Never in a backup rule (as before).
* Other Android files outside the databases (checked for build 60, no amounts or addresses): settings
  DataStore, `refresh_stats`, `activity`, `move_history`, `widgets` (widget configuration), `recent_searches`,
  `check_runs.txt`, `token_names.json`, coin lists/logos and `portfolio_last_prices.txt` (USDT prices of the held
  coins – reveals which coins, not how many; same as iOS). `portfolio_system_backup.json` is the documented
  opt-in plaintext mirror.
* Tests: `DatabaseEncryptionTest` (androidTest: portfolio v3, app DB v1 and v12 «16.2», failed verification, lost
  key, backup copy round trip, plain SQLite cannot read the result) and `RealDatabaseEncryptionTest` (runs only when
  `app/src/androidTest/assets/realdb/` holds `cryptochecker.db` / `cryptochecker_portfolio.db` with `-wal/-shm`
  from a test device – never commit real holdings).

**iOS – sealed files in the App Group.**
* `Shared/Storage/ProtectedFile.swift`: format `ProtectedContainer` – `CCSE`, version byte 1, 12-byte nonce,
  AES-256-GCM ciphertext, 16-byte tag; magic + version are authenticated. JSON never starts with `CCSE`, so every
  reader still accepts old plaintext files.
* Key: 256 bit in the keychain (`StorageKey`, service `com.cryptochecker.storage-key`), access group = the App Group
  `group.com.cryptochecker.app` (works with the existing `application-groups` entitlement for app and widgets; a
  `keychain-access-groups` entitlement is deliberately not added because it would change the default group of all
  keychain items). `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` since build 69 (owner decision 17 Oct 2026;
  before `kSecAttrAccessibleAfterFirstUnlock`): readable after the first unlock (widgets, background), but never
  part of an iCloud/computer backup and never moved to another iPhone — whoever gets hold of a device backup has
  the sealed files but never their key. Consequence, said up front under Settings › Backup on both platforms
  («Before switching devices, create a backup file…», `backup_device_bound`; Android Keystore keys are
  device-bound too): after restoring a device backup on a **new** iPhone the sealed files cannot be opened;
  `prepareAtLaunch` sets them aside (`.locked`) and `StorageRecoveryNotice` names it once — the way back is the
  backup file. Existing installs: `StorageKey.restrictToThisDevice` (called by `prepareAtLaunch` when the key was
  found) reads the item's `kSecAttrAccessible` and changes only that attribute with `SecItemUpdate` — same key
  bytes, same service/account/access group (widgets keep reading); never delete and recreate (every file would
  become unreadable). If it fails (e.g. before the first unlock) nothing changes and the next start retries.
  Only the app creates the key; widgets only read it.
* Protected files (`ProtectedStorage.files`): watchlist, portfolio alarms, portfolio, watched addresses, portfolio
  widget snapshot, `alarm_log.json` (build 80) and their `.unreadable` copies. The widget snapshot is written only sealed
  (`PortfolioWidgetStore.writeSnapshotFile` checks the key first, since build 60): without a key or when the
  write fails nothing is stored – the former fallback into the App Group defaults (plaintext amounts) is gone, and
  `migrateSnapshotToFile` moves a leftover defaults entry into the sealed file or removes it. Plain on purpose (no amounts, addresses or notes; Android keeps
  them outside the databases too): price caches of the portfolio widget, `portfolio_last_prices.txt`,
  `check_runs.txt`, coin names/logos, `phemex_scales.json`, `alarm.lease`, App Group defaults.
* All reads/writes of these files go through `ProtectedFile`: `read` returns plaintext (old plain file unchanged),
  throws `.locked` without key (callers treat it as «inaccessible»: never overwrite) and `.damaged(raw)` for a
  broken sealed file (treated like corrupt JSON: raw bytes preserved). `write` seals, writes a sibling temp file,
  reads it back, decrypts and compares, then `rename(2)`; without key it writes plaintext as before.
* `ProtectedStorage.prepareAtLaunch` (first thing in `AppData.init`): keychain unreadable → touch nothing; no key
  but sealed files → move them to `<name>.locked` (never delete), then create the key and show
  `StorageRecoveryNotice`; key found → restrict an old keychain item to this device (above); then encrypt every
  plaintext file (coordinated like `SharedStorage`, only replaced if the file is still byte-identical,
  backup-exclusion flag restored). Every file left plaintext (keychain unreadable, no key possible, failed
  encryption) is listed in `LaunchOutcome.keptPlain`; `encryptionPending` → Settings › Backup shows
  `backup_encryption_pending` like Android. Tests: `ProtectedFileTests` (fake keychain, incl. the
  accessibility migration).
* File protection stays the system default (complete until first user authentication).

**Rules for later changes.** Never open an encrypted database with a helper that deletes on corruption; never create
a key while an encrypted file depends on the stored one; new files with user data go into
`ProtectedStorage.files` (iOS) or a Room database / a sealed entry like `PortfolioSnapshotSeal` (Android); a
cache with amounts is rather not written than written in plaintext; a file with the watchlist, holdings, amounts or
addresses never goes into `xml/backup_rules.xml` (Android 8/8.1 back it up without end-to-end encryption); a schema change keeps working because migrations run
after the encryption step.

## Alarm log (build 80)

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

## Market movers (build 81)

* Card «Gainers and losers 24 h» in the Market tab under the Pulse card: top 5 up / top 5 down among the top 100
  by market cap without stablecoins and wrapped/staked coins (same exclusion as the breadth), valid 24 h value,
  no duplicate tickers; exactly 0 % is neither; ties by rank, then ticker; fewer than 10 valid coins → hidden.
  `MarketMovers.kt` / `MarketMovers.swift`, fixture `market_movers.json`. The Pulse CoinGecko request now asks for
  `per_page=100` (one request, same cache); the breadth still uses the first 30 valid coins. Tap behaves like
  «Unusual today».

## Alarm delivery and diagnostics (build 81)

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

## Robustness and parity fixes (build 81)

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

## Build 82

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
* Build 115: iOS warning ceiling 0 (`MAX_COMPILER_WARNINGS`, `warning_details`), `HostStorageKey` / `StorageKey.replaceKeychainForTesting`,
  `UITEST_FAIL_VERIFY`, `tickerFallbackAddress` / `SingleTickerFallback` (One Trading), job names for required checks.
* Build 114: `AddressBalance.discardFetched` (+ `Update.snapshotCountTokens/snapshotNetworks`, replaces `resetDuringFetch`/`snapshotAttempt`),
  `WatchedAddressBalanceWriter`, iOS `presentDelayNanos` / `UITEST_BACKUP`, `BackupSettingsTextsTests`, exchange check `onetradingbulk`.
* Build 113: `AddressBalance.resetDuringFetch` (+ `Update.snapshotAttempt`), `BackupResultPresentation.displayWidth` / font scale /
  `canPresent`, iOS `ToastMessage`, `BackupUnencryptedWarningTest`, mirror signing key rotated (`MirrorManifest`, `mirror_data.py`).
* Build 112: `singleFlightRefresh` / `AddressRefreshGate`, `AddressServerTestRun`, `BackupResultPresentation` (+ `backup_result_dialog.json`),
  `DialogText` + `DialogScrollGuardTest`, `mirrorLoader` / `loadMirrorMeta` tests, environment `mirror-signing`, split dependency graph jobs.
* Build 111: `AddressServerRules` (+ `address_server_ui.json`), `BackupRestorePolicy.addressServerNote`, `SettingsRestoreNotes`,
  `BlockedSources.unblock` / `clearServerPauses`, `MirrorTrust` with injectable key (Android class + `shared`), iOS mirror size
  limits, `supply_chain_files.py` configuration check, market-data red on unsigned manifest, guard rules for detached scopes.
* Build 110: comments without build numbers (F-21; history lives in CHANGELOG and git), `NSLocalNetworkUsageDescription`
  (`ios_local_network_usage` in `ios_extra_strings.json`, `convert_strings.py` `INFO_PLIST_KEYS`), `lint_warnings.py --details`.
* Build 109: signed mirror (`MirrorManifest`, `MirrorTrust`, `mirror_signature.json`, `mirror_data.py --manifest`,
  `artifact_check.py` profile `mirror_manifest`, secret `MIRROR_SIGNING_KEY`); consent per action
  (`WatchlistNotSavedGate` job scope / `TaskLocal` `actionConsent`, `unaskedPassScope`); condition-direction guards;
  `supply-chain-update.yml`, `osv.yml` (`osv_check.py`), Kover (`-Pcoverage=true`, `coverage_summary.py`),
  `lint_warnings.py`, marketdata unit tests.
* Build 108: transfer coverage «amount + network fee» on all methods (`uncoveredShare`, `portfolio.json` B108 cases,
  `tax_templates.json` `poolUncovered`, `TaxPool.addFee` with amount/fee); own address server (`AddressServer`,
  `address_server.json`, `WatchedAddressSourceServer` / `WatchedAddressSource+Server`, Bitcoin Esplora only, backup key
  in `backup_settings.json`); first-xpub consent; `PortfolioWidgetOptions` (`widget_portfolio_options.json`);
  `SettingsUiEvents`; double-tap guards; `launchOrSkip`; `AlarmEditorStateTest`; `WatchlistNotSavedGate.run` +
  `EditorConfirmBehaviorTests`; background error kinds (`background_errors.json`).
* Build 107 (review round 5): `ScreenEvents<T>` + `CollectScreenEvents` (buffered one-shot results, collected by the
  current screen); alert editor draft in `AlarmEditorState` (`SavedStateHandle`), effects in `AlarmEditorEffects`;
  `WatchUiEventsHolder` / `MarketCoinUiEffects`; `launchSystem()` guards an unregistered permission launcher;
  `AlarmEditorRecreateTest`; iOS `ConfirmedFinish` (editor, note and group sheets close after the confirmation,
  `saveFromEditor` / `templateFromEditor`); guards: cancel branch (Android) and own closure parameters (iOS);
  `ios_release_audit.py` skips `#/…/#` regex literals.
* Build 106 (review round 4): `launchWrite(cancelled = …)` with `onDone(Boolean)` for favourite, group, disable and
  notification writes (feedback only after writing); `addPreview` reports cancel/locked; `WatchlistNotSavedGate.confirm`
  waits up to 2 s for a screen (`lateScreen` in `watchlist_not_saved_gate.json`, `mayAsk()`), iOS `confirmClose` keeps
  the alert editor open on cancel; `portfolio_alarm_threshold_invalid_recreate`; iOS `AppData+DeliveryMarkers` prunes
  after the write, `AppData+PairPickerKey`, `SwitchRow(dimsLabel:)`; per-call guards `WatchRepositoryCallGuardTest` /
  `WatchlistConfirmCallGuard(Tests)`; `ios_release_audit.py` string-aware comment stripping; `release_readiness.py`
  head build check for ROADMAP/TESTPLAN.
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
* Build 101: nameless portfolio holdings on an ambiguous ticker — `PortfolioPriceIdentity.binanceCheck(..., otherNames)` returns
  `UNVERIFIED` when Binance's name and the names of other stored pair lists (`TokenIdentityStore.otherNames`) clearly
  conflict (`TokenIdentity.conflicting`); the Binance price is used but listed in `PortfolioPrices.unverified` (row «Kurs
  ungeprüft»; persisted with the price as 4th field `;u` in `portfolio_last_prices.txt`, `StoredPrice.unverified`, never a
  candle reference via `PortfolioPriceFallback.confirmed`; wrapped coins inherit it from their base), stored in the snapshot (`unverifiedCoins`/`unverifiedValueUsdt`) and counted like stale prices by
  `PortfolioAlarmLogic.staleMaterial`; own pause reason `PriceUnverified` when no stale/missing prices block. Exports and the
  value-history chain exclude unverified prices as references (`CutoffExport.referencePrices`/`referenceOf`). Fixtures:
  `portfolio_price_identity.json`, `portfolio_alarm.json`, `cutoff_export.json` (`referenceCases`, `chainReferenceCases`).
* Build 100: haptics only through `ui/components/Haptics.kt` (`HapticEvent`, `Haptics.constantFor(event, sdk)`,
  `rememberHaptics()`) and `ios/App/Components/Haptics.swift` (`Haptics.play`, `.haptic(_:trigger:)`); `HapticsUsageTest`
  forbids direct haptic APIs elsewhere and any haptics in notification/widget/work/tts code and iOS Shared/Widgets.
* Build 99 (second full audit): `WatchlistLimits` (Kotlin/Swift, 2000 pairs, 10 alerts per pair; `BackupRestoreLimits`
  reuses it), `AddWatchOutcome`. iOS `PrivateSessionConfig.make` (no cookies, no URL cache) for every URLSession,
  `StorageKey.renew()` with an App Group generation counter so the widget re-reads the key, `OrderedSerialRunner` for
  background widget snapshots. `TextLimit.clean` (trim, cut by graphemes, trim) for notes, groups and exchange names;
  fixtures `backup_limits.json`, `text_limit.json` («fields»). `BackupRestorePolicy` rules for `systemBackup` and
  `hideAmounts`. CSV `tokenizeOrNull` caps (200 cells, 200 000 rows). `PortfolioTxFirstInvalid`, `DexFailure`,
  `LegacyDatabaseCleanup`, `BoundedCache.MEMORY_CACHE_ENTRIES`. JVM unit tests run with en-US/UTC.
* Build 98 (full audit of build 97): fixtures `exchange_backoff.json` (next/groupPause/adoptedPause/merge with
  implausible pauses) and `text_limit.json` (`TextLimit.takeGraphemes`/`graphemeCount`, java.text.BreakIterator;
  ZWJ/flag/skin-tone cases need JDK 20+ in JVM tests, `TextLimitDeviceTest` covers them on device). `label_value`
  resource for «label: value» (`LabelValueUsageTest` scans Kotlin and Swift sources). `MonthYearFormat` (ICU skeleton
  «MMMyyyy»). iOS `PortfolioSummaryCache` (off-main summarize, newest result wins), `DateFormatterCache`,
  `AlarmCreateGuard` (both platforms). Android `BoundedCache`/`KeyedMutex`, JSON depth guard in `MarketHttp`
  (`HttpBodyLimit.MAX_JSON_DEPTH`). CI: `room_schema_drift.py` compares exported identity hashes with HEAD.
* Build 97 (review 21 October 2026, afternoon): `OpenTargetGuard` (Kotlin/Swift) – per-install secret (not backed up)
  appended to the app's own notification/widget intents and URLs; without it only `add`, `alarms`, `cycle` and
  `watch/<id>` are opened. `StorageRecoveryNotice.SETTINGS_RESET` (Android: set by the DataStore corruption handler;
  iOS: `SharedStorage.settingsCorrupt(hasData:decodes:)`), cleared only on dismiss. `PortfolioSnapshotUpdater.shouldRefresh`
  treats unreadable alarm storage as «alarms may be active». Fixture `gas_fees.json` (feeHistory, gasPrice, mempool,
  transfer cost); hex parsing accepts ASCII hex digits only on both platforms.
* Build 96 (review 21 October 2026, noon): `DepegAlarm.usablePrice(last, exchangeTime, now)` (Kotlin/Swift) drops a
  depeg reading whose exchange time is too old by `AlarmFreshness.exchangeTimeTooOld` (fixture `depeg_alarm.json`
  → `usablePrice`). Visible percentages go through `PercentText` only (new `minDecimals` for trimmed trailing zeros);
  `AlarmSentence`/`AlarmTexts` no longer use `NumberFormat.getPercentInstance` / `NumberFormatter.percent`, so ICU
  differences cannot split Android and iOS. Volume alert editor hint `alarm_volume_zero_hours_hint`; volume fixture
  case «newest candle 20 h old».
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
* Build 90 (third audit of 20 October 2026): `BulkDelisted` state is persisted (`BulkDelistedStore`, SharedPreferences
  `bulk_delisted`; iOS App Group key `bulk_delisted`); the iOS widget never rechecks (`plan(…, lowMemory: true)`).
  `wealthCoinNoteBelowValue` shows the incomplete note under a partial CH value. iOS `PortfolioEdits` only reports
  delete/undo/clear after a successful write. `PortfolioRepository.linkStoredTransfers` writes CSV links in one
  transaction; `portfolio_csv_import_locked`. Indicator `decide` re-stores the limit while beyond; gas reset runs
  under `GasAlertChecker.lock` (iOS: reset generation); `BackgroundCheck` waits for pulse/speech after `runLocked`.
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
* Build 88 (audit of 20 October 2026): gas alerts store the threshold with the armed state and re-arm on change;
  Fear & Greed stores its thresholds with the last value and re-evaluates only the changed bound. iOS
  `TimeUtils.isIntervalDue` treats a last check in the future as due (gas, depeg). Android re-syncs the exact
  alarm on TIME_SET / TIMEZONE_CHANGED (`resyncsExactOn`) and counts the pulse widget in the background demand.
  `AlarmsViewModel.watchMissing` returns from the alerts screen when the pair is gone, saving skips a missing pair,
  and deleting pairs cancels their alert notifications (both platforms). Quick-alert chips show «· from now»
  (`alarm_template_from_now`, they have no window) and the banner uses `alarm_saved_banner`. `PortfolioStorageLock`
  refuses portfolio writes while the stored portfolio is kept (key briefly unavailable). Pair lists are parsed on
  `Dispatchers.Default` in `MarketRemoteDataSource`. Lighter price 0 → «No data»; `BulkTickerRows` needs price > 0.
  `TaxEngines.interestReward` matches whole words. Swiss wealth rounds each part before summing per coin;
  `AddressCostLots.hasCountedLots(addresses, cutoff)` follows the cutoff. iOS release notes wait in
  `ios/fastlane/release_notes_later/` until the first update (APP_STORE.md 7.2).
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
* Watchlist widget refresh lock (build 86): Android draws the countdown (Chronometer) only with the screen on
  (`WidgetRefreshState.countdownMillis`); drawn with the screen off it shows the symbol at once, since a frozen
  process may miss the redraw at the end of the lock. The redraw runs 300 ms after the end (`UNLOCK_SLACK_MILLIS`,
  in-process and as the alarm fallback) and is planned again whenever the previous one has already run. iOS needs
  no change: a timeline entry at the end of the lock brings the symbol back.
* Reference data: `bip32_fuzz.json` (1,000 random xpub/ypub/zpub leaves with all four address types, 74
  point-arithmetic edge cases, 12 invalid keys; generator `android/tools/gen_bip32_vectors.py`, cross-checked with
  OpenSSL via Python `cryptography`) and `argon2_random.json` (20 random cases) on both platforms.

