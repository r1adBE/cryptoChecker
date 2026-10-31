# Development guide

Sections 1–11 explain the exchange adapters (adding an exchange); the later sections cover tests, CI, the app's
UI, portfolio and storage rules and the build history.

> **Deutsch:** Entwicklerhandbuch. Abschnitte 1–11: neue Börsen-Adapter in der Börsen-Bibliothek
> (Android/Kotlin und iOS/Swift) – Aufbau, Dateien, Paarliste, Einzel- und Massenabfrage, Tests; danach Tests,
> CI, Regeln der App und die Build-Geschichte. Fragen und Pull Requests gern auf Deutsch oder Englisch.

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

## 1. Structure

| Path | Content |
|---|---|
| `android/marketdata/src/main/java/com/cryptochecker/marketdata/model/Exchange.kt` | Base class: request URLs and parsing hooks |
| `android/marketdata/…/model/exchange/generic/SimpleExchange.kt` | Base class for the usual case «pair list URL + ticker URL» |
| `android/marketdata/…/model/exchange/*.kt` | One file per exchange (or per market, e.g. spot and futures) |
| `android/marketdata/…/model/Ticker.kt`, `SimpleTicker.kt` | Price data of one pair |
| `android/marketdata/…/model/ExchangePair.kt` | One tradable pair: base, quote, exchange-specific pair id |
| `android/marketdata/…/config/ExchangesConfig.kt` | List of registered exchanges |
| `android/marketdata/…/util/JsonUtils.kt` | JSON helpers (`forEachJSONObject`, `optDoubleOrMissing`, …) |
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
| Android | `android/app/…/data/remote/candles/ExchangeCandleSources*.kt` | optional: own candles (`ExchangeCandleSource` + `ExchangeCandleRegistry`) |
| iOS | `ios/Shared/Services/Candles/ExchangeCandleSources*.swift` | the same candle source in Swift, case in `testdata/parity/own_candles.json` |
| Both | ticker parsing | set `exchangeTime` when the API sends the time of the price (see [section 15](#15-ui-rules-for-numbers), «Exchange time vs fetch time») |
| Both | the adapter | `category` when it is not Spot (FUTURES, DEX, FOREX: section of the exchange picker) |
| Both | `ExchangeOrder` (Android `app/…/domain/market`, iOS `Shared/Core`) | CoinGecko id in `SPOT_IDS`/`spotIds` or `DERIVATIVES_IDS`/`derivativesIds`, so the volume order of the install date includes it |

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
./gradlew :app:testDebugUnitTest -PperformanceTests=only   # performance tests alone (timings)
```

The performance tests (`app/src/test/.../performance`; watchlist, backup and refresh with 2000 pairs, portfolio and
tax with 10 000 transactions; most also compare against a quarter of the size to check the scaling) are excluded from
the normal run: their timings vary when lint or other tests run in parallel. The *Android* workflow runs them in their
own step («Performance tests») with nothing else running; `-PperformanceTests=true` includes them in a normal run.

Reports: `android/app/build/reports/tests/testDebugUnitTest/index.html`.

Source guards (`FileLengthTest`, `HapticsUsageTest`, contrast and backup-rule checks …) fail when a file they read is
missing (`SourceGuard`). Swift sources count as optional locally: in an
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
«iOS UI tests» – and the job «iOS build» (the required check) is green only if all three are; logs on failure as
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
prepared but commented out. On pull requests only two jobs can run: `migration-tests` when a database file
changes (entities, DAOs, schemas) and `release-smoke` when a file changes that decides about the R8 release build
(keep rules, build script, dependency versions, the smoke test). It uses the same toolchain as `android.yml` (JDK 17, Gradle with
cache), builds `:app:assembleDebug` and `:app:assembleDebugAndroidTest` before the emulator
starts, then runs `:app:connectedDebugAndroidTest` on an x86_64 emulator with KVM
(`reactivecircus/android-emulator-runner`: API 34, `google_apis`, Pixel 6 profile, animations
off) – i.e. everything in `app/src/androidTest`, including `CoreFlowTest` and
`DatabaseFreshInstallTest`. On failure the test reports are uploaded as the artifact
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

**Screenshots branch** (`.github/workflows/screenshots.yml`, on pushes to `main` that touch `android/`, `ios/` or
the workflow, and by hand): screenshots of every main screen of both apps, light and dark, readable with plain git
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
workflow builds and tests in the Simulator (unit tests, then UI tests) on every pull request; on pushes to
`main` only when `ios/`, the shared parity cases or the UI-test files change.

**Exchange API check** (`.github/workflows/exchange-live-check.yml`, weekly on Wednesdays and
by hand): `.github/scripts/exchange_live_check.py` calls the public single-ticker endpoint of
every adapter for one major pair (BTC) and checks that the fields the adapters parse – last
price and, where read, bid/ask, volume or 24 h change – are present and numeric. The endpoint
and field table lives in the script; update it together with an adapter (URLs and field names
as in `android/testdata/exchanges/exchange_*.json`). On a failure the job opens ONE issue
«Exchange API check failed» (or updates the open one) and the run stays green (the issue is the signal); when all pass again it
closes the issue. HTTP 451 (and 403 for exchanges that block US cloud IPs) counts as skipped,
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
  CI task set with `--write-verification-metadata sha256 --write-locks` (plus Kover, the baseline-profile module and,
  optionally, one device-test class on an emulator; Windows and macOS add their aapt2 builds for local builds), checks
  the result (`.github/scripts/supply_chain_files.py`) and force-pushes ONLY these files to the branch
  `supply-chain/update`; open a pull request from it and merge once CI is green. Repeat after every change to
  `libs.versions.toml`, a plugin or the Gradle version (including Dependabot security updates – their pull request
  fails verification until the files are regenerated: run the workflow on the Dependabot branch – `supply-chain/update`
  then starts from that commit, so its pull request carries the version change plus the files; merge it instead).
  Sources and javadoc jars and the Gradle sources zip (downloaded by Android Studio) are trusted without checksum; the trust rules live in the
  seed in `supply_chain_files.py`. A local build failing with «Dependency verification failed» after a deliberate
  change: regenerate; for a one-off local experiment `--dependency-verification lenient`.
* *Signatures (PGP)*: the workflow input **signatures** (checked by default) runs Gradle with
  `--write-verification-metadata sha256,pgp --export-keys` from a seed with `verify-signatures=true` and
  `keyring-format=armored`, and the branch then also carries `android/gradle/verification-keyring.keys` (public keys
  only; `supply_chain_files.py --check` rejects anything else, also a key trusted without a group or for a pattern
  that matches every group). Signed artifacts are covered by `<trusted-keys>` (each key limited to its groups).
  Exceptions are recorded, not hidden: an artifact without signature keeps its sha256 entry (origin «Generated by
  Gradle because artifact wasn't signed»), a key that no key server has goes to `<ignored-keys>` with its reason and
  its artifacts keep their sha256 entry; no extra `<trusted-artifacts>` rule is added. The job summary of «publish»
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
* *Reproducible build*: `.github/workflows/reproducible.yml` (pushes to `main` touching `android/`, release tags,
  weekly on Sundays, by hand) builds the unsigned release APK and AAB and the unsigned Wear OS release APK
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
* *Code metrics* (`android.yml`, step after «Line limit», `.github/scripts/code_metrics.py`): constructor
  dependencies of every `@HiltViewModel` / `@Inject constructor` class in `android/*/src/main` – red above
  `DEPS_CEILING` 14 – and `private`/`internal` properties declared in a ViewModel body (not constructor parameters,
  not nested classes or functions) – red above `PROPS_CEILING` 35 (`ExplorerViewModel`). A class at the limit is
  split (use case, helper, state holder) instead of growing.
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
  `:marketdata`, `:domain` and `:data` into its report (`kover(project(…))` in `app/build.gradle.kts`): most of their
  tests run in `:app` (e.g. the exchange parsers in `ExchangeContractTest` with `testdata/exchanges/exchange_*.json`
  and `recorded/*.json`), which their own reports would miss. `.github/scripts/coverage_summary.py --merged app
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
check) first runs `./gradlew :app:testDebugUnitTest` (must be green), then `.github/scripts/mutation_test.py` (standard
library only, `--self-test` without Gradle). For each target file – `PortfolioFifo`, `PortfolioFifoLots`,
`PortfolioCalculatorAverage`, `PortfolioFeeExpenses`, `PortfolioCostFx`, `TaxEngineCore/Uk/Ca/Fr/At/Jp` and
`TaxExportRows` under `domain/portfolio` – it creates mutants by fixed rules (`<`↔`<=`, `>`↔`>=`, `==`↔`!=`, `&&`↔`||`,
`+`↔`-`, `*`↔`/`, `+=`↔`-=`, `*=`↔`/=`, `true`↔`false`, `.subtract(`→`.add(`, `.multiply(`↔`.divide(`, `.min(`↔`.max(`
and `return` of a constant of the declared type; never in strings, comments or generics), at most 4 per file and 50 in
total, chosen deterministically (hash of file, line, column and rule), no new mutant after 55 minutes. Each mutant is
written into the file, the test classes listed for that file in `TARGETS` run with
`./gradlew :app:testDebugUnitTest --tests '<pattern>' -q --offline`, and the file is restored in a `finally` (also
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
symbol → image map comes from CoinGecko `/coins/markets` (pages 1–4 = top 1000 first, first occurrence by
market cap wins), gaps filled from the Binance website list `bapi/composite/v1/public/marketing/symbol/list`
(`name` → `logo` on `*.bnbstatic.com`, unofficial; only symbols CoinGecko lacks, e.g. XAU, XAG,
stocks), then the official Binance Alpha token list
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
`syncAll`, 6 parallel per host, small variant first) — never one logo on demand, so the image
requests are the same for every install. Images are scaled to 128 px and stored as PNG (Android `cacheDir/coin_logos`, iOS App
Group `coin_logos/`), a failed image is retried after **1 day**; display reads only from memory/disk, and a
`revision` counter (at most once per second while loading) lets badges retry. Every lookup in the map uses the
merge key (`CoinLogos.urlFor`: `normalize`, TradFi via `tradFiKey`), also for the starter logos («1000CAT» → CAT). Only HTTPS images from
`*.coingecko.com` and `*.bnbstatic.com` (`isAllowedUrl`; URLs of the former GitHub Pages list are
rejected); DEX pools (`DexScreener`) always show initials. Android:
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
  Android: `android/app/…/domain/live/LiveFeed.kt` (with a tiny JSON reader, `LiveJson.kt`,
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

* «Outdated» is decided **per pair**: a pair on a live socket is stale after **2 min** without a new
  price (`LIVE_STALE_MILLIS`); every other pair — also in «Live while open», where exchanges without
  WebSocket are not polled on a timer while the watchlist is open — uses the polling threshold
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
  counted (`PortfolioJson.parseTxs/parseAlarms`, Swift `BackupManager.invalidPortfolioEntries`) and named
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
  (the lock turns on only if the device can authenticate; an invalid server value is ignored).
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
  (`parts(…, skipUnpricedTrades = true)` in `gain()`), never counted with its fee only. Android resolves routes in `PortfolioPriceSource`
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
    Coinbase `USDT-USD` fills it (`fillUsdtUsd`: only plausible 0.5 … 1.5 values, Kraken wins; cached
    `phisto_USDTUSDCB`). Only after both, a day falls back to «USDT = 1 assumed».
  - FX is untouched: `usdTo` (Frankfurter answers a weekend/holiday with the last business day) and `usdToSeries`
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
  only off the main thread (Android `Dispatchers.IO`, iOS a detached task; the «Continue» button
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
  calendar year, local dates; cost at acquisition-date FX, proceeds at sale-date FX, daily ECB rates via
  Frankfurter, up to 7 days back), income per year (rewards at value when received), year-end holdings
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
  (`coin_meta_snapshot.json`) and the CC0 icon pack «Cryptocurrency Icons» (`coin_icons.bin`, 64 px) ship with
  the app, so names and major logos show offline on first start; regenerate both with
  `android/tools/gen_coin_assets.py` (`--meta live` needs network – run it before each release so Alpha is in
  the snapshot). Images: all entries are downloaded (never per watchlist coin), 6 parallel per host, a
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
  transactions (`WatchedAddressRules.overlapAutoShowOnly`); otherwise it keeps counting and the «Also in own
  transactions» hint stays. Any choice by the user sets `overlapChecked`.
* **Tax template texts = formulas.** Every sentence in `tax_note_*` / `tax_line_*` must describe what the
  engine does; a change to an engine needs its text in all 31 languages and a worked example in
  `tax_templates.json` (parity tested). CSV cell texts contain no `;` or `,`.
* **Exact checks and run log.** «Check on time» (Android only, off by default) uses `SCHEDULE_EXACT_ALARM`
  and falls back to WorkManager without the permission; every background check (worker, exact, BGTask,
  widget, foreground) writes one line to the run log (20 entries, no backup). A check within 5 min of the last
  one is skipped and logged as such. iOS widget refreshes also check price alarms (logged as
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
* **USDT at market price.** USDT stays the internal unit (1 USDT = 1 USDT, all
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
  00:00 UTC»; Hyperliquid's % (mark vs prevDayPx) is labelled «Mark» (`Exchange.changeIsMark`). WOO spot needs 24
  contiguous hourly candles.
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
  coin's USDT candles and converts **each candle with that day's rate** (Frankfurter time series,
  the one the portfolio value history uses; at most once per currency every 6 h). Only if the series
  is missing it uses today's rate and the caption says «(current rate)» instead of «converted at
  daily rates». Never convert a whole history with one rate silently.
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
  with the ⚡ symbol, gesture hint as its bold first line). Tapping a row stays the only way in (action sheet).
* **Thin watchlist row.** A row shows logo, pair, price, change (and mini chart if on) and
  at most **one** bell: the alarm bell with waves, only when the pair has at least one active alarm.
  The price-notification state is not drawn in the row (only on its switch in the action sheet),
  and the source note for a foreign change / mini chart is in the action sheet (see «Row source»).
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
* **Foreign candle series need the same token.** `SheetChart.accept` (action sheet) and `sparklineFor` (mini
  chart) accept a series from another exchange only if its last close × FX rate is within
  `DayChange.MAX_PRICE_GAP` (25 %) of the pair price; no price or no rate → no chart. Never scale a series
  to the pair price without this check (cases in `testdata/parity/sheet_chart.json`).
  `PricePlausibility.sameToken` returns false without a valid reference.
* **Row source.** When the % change or the mini chart of a watchlist row come from another
  exchange than the pair's, the action sheet says so under the price (`DayChange.rowSource` →
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
  `ExplorerSearchActions`, `ExplorerSelectionActions`, `ExplorerBulkActions`, `ExplorerDexActions`; iOS
  `ExplorerRecentSearches`/`ExplorerQuickFilter` (as Android), `ExplorerPicker`, `ExplorerFeedback`; Android
  `ExplorerPreciseSelection`; market VM (`InfoViewModel` / `CycleViewModel`) → `MarketDataLoaders`,
  `MarketDataSync`, Android `MarketPreviewActions`; `CryptoPulseCard` → `CryptoPulseParts`; `MarketRow` →
  `MarketSectionHeader`, iOS `ExchangeInfo` and `MarketCoinSheets` (as Android); `WatchWhyFactors` →
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
* Build 134: `UiFlowsTest` uses `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule`. The v1 rule ran
  Compose-scoped coroutines on an unconfined test dispatcher, so collectors resumed on the thread that woke them
  (`flowOn(Dispatchers.Default)` flows, DataStore) and wrote snapshot state there – the rare «Reentrant composition» /
  `SlotTable` crashes of `importCsvAndUndo`. New instrumented UI tests use the v2 rule. iOS `AccessibilityAuditTests`
  taps the storage notice's close button a second time when the first tap was missed.
* Build 135 (audit 134, F-10/F-19/F-23): every instrumented Compose test uses the v2 rule
  (`androidx.compose.ui.test.junit4.v2.createAndroidComposeRule`). `macro_calendar.py --self-test` also runs
  `recorded_pages_test` against the gzip-compressed real pages in `.github/scripts/testdata/macro` (BLS schedule and
  release pages, FOMC, BEA; expected counts and first/last dates; refresh them with the parser). Test-source Kotlin
  warnings fixed (redundant `!!`/conversions, unchecked casts suppressed locally, nullable `parentFile`).
  `packaging.resources.excludes` drops `META-INF/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/
  verification.properties`. The remaining Gradle deprecation (`Configuration.setVisible`, removed in Gradle 11)
  comes from an applied plugin, not from the build scripts.
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
* Build 160: screenshot tour reworked – each screen is its own step that starts from the watchlist or the settings,
  a failing step is recorded and the tour goes on; the test fails at the end with the list of missing screens
  (`ScreenshotTourSteps.kt` / `ScreenshotTourSteps.swift`). Android: settings rows are found by their content
  description (`SettingsNavRow` clears its semantics), the tour runs one full price refresh after seeding, and
  `FakeExchange.binanceMirror` (only in the tour) answers `data-api.binance.vision` for the portfolio prices. iOS: no
  deep link any more (it restarted the app with `-uiTesting`, which clears the storage); «All alerts» via the bell,
  «Last refresh» via the watchlist menu.
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
* Build 173: `macro-calendar.yml` (fetch) and `pages.yml` read the exit code of `git ls-remote --exit-code` as
  `rc=0; … || rc=$?`: under `bash -e` the bare call ended the step with exit 2 when `macro` did not exist yet, so the
  seeding path was never reached (first run 29.10.). App code unchanged.
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
* Build 179: `PortfolioPriceIssue.nameUnchecked` takes the failed coins: a coin Binance was skipped for whose request failed is
  neither «unchecked» nor «no market» and shows «Price unavailable right now»; a stored «unchecked» is cleared then.
* Build 180: review fixes after an external review. `LiveStatus` uses the oldest visible update time; `movers_price_note`
  bridges CoinGecko percentages and exchange prices; `tax_template_beta_note` says not checked by a tax professional;
  iOS alarm placeholder uses `draft.priceHint` (display currency); text fixes de/en/it.
* Build 181: audit follow-up. `TaxCountryFigures` hides country rows until a template is listed as checked (empty today);
  `WatchlistPairName` keeps PERP and drops only the quote; healthy rows show the price age; per-pair alarm list shows
  «last checked»; `movers_price_note` names the tap target; text fixes de/fr/it.
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
* Build 153 (KMP prep, part 3): all `:domain` files on `util/json` (imports renamed, `JSONObject.NULL` → `JsonNull`);
  `MarketError.toMarketFailure` maps `JsonException` and (by class name) `org.json.JSONException` from `:data` to
  `Parse`. Boundary helpers `asDomainJson()` / `asOrgJson()` in `:data` (`DomainJsonBridge.kt`, via the text) for the
  backup codecs (watched addresses, CSV restore state). Remaining Android/JVM bits in `:domain`: Room annotations on
  `WatchEntity`/`AlarmEntity`, OkHttp's URL parser in `LogoUrlPolicy`, `java.*` (time, math, concurrency).
* Build 152 (KMP prep, part 2): `util/json` in `:domain` – `JsonObject`, `JsonArray`, `JsonNull`, `JsonException`,
  `JsonTokener` with AOSP `org.json` semantics (lenient reader: comments, `=`/`=>`, `;`, empty array slots, hex/octal,
  unquoted words; `get*` coercions; `optString` of null = "null"; `put(name, null)` removes; whole doubles written
  without ".0", "-0", `\/` and `\uXXXX` escapes). `JsonCompatibilityTest` compares it with AOSP `org.json` (unit-test
  dependency `android-json`). Next: switch the 20 domain files over (string boundaries towards `:data`).
* Build 151: `app/build.gradle.kts` forces `androidx.room:room-compiler-processing` to `libs.versions.room` in every
  configuration (Hilt's compiler pulled 2.8.4); `:domain` drops `timber` and `androidx.core.ktx` (unused since 146);
  lockfiles/verification metadata from «Supply chain update».
* Build 150 (Android): price overlay – `overlay/PriceOverlay` (singleton started in `CryptoCheckerApp.onCreate`,
  combines `settings` and `observeWatches()`, one `TYPE_APPLICATION_OVERLAY` window with plain views, no service; the
  overlay window keeps the process perceptible), `overlay/PriceOverlayRules` (pairs like the permanent price
  notification via `PriceNotificationRule.showNow`, max 3; snap/clamp/close zone; `PriceOverlayRulesTest`). Setting
  `priceOverlay` (DataStore key `price_overlay`, device-local, not in backups), row `PriceOverlayRow` under the
  permanent notification (anchor `updates.overlay`, search entry), position in SharedPreferences `price_overlay`.
  Permission `SYSTEM_ALERT_WINDOW` moved from FORBIDDEN to EXPECTED in `manifest_policy.py`; privacy page and
  PLAY_STORE.md list it.
* Build 149: compare view – `CompareMath` (Android `:domain` `domain/watch`, iOS `Shared/Insights`; parity
  `compare.json`): `normalized` (% from first valid close), `figures` (7/30 days vs. the last daily close starting
  ≤ now − 7/30 d, distance to max(30-day high, price)), `bounds`. UI `CompareScreen` (Android full-screen dialog like
  «Why?», iOS `navigationDestination`) from the selection bar («Compare», 2–4 pairs, otherwise a hint banner); lines
  from `loadSheetChart`/`SheetChartStore` per range (day basis: since 00:00 like the sheet), figures from the 1-year
  daily candles. Line colours from the theme plus dash patterns.
  CI: `repro_compare.py` names the first differing line of differing text entries in the archives (R8
  `proguard.map` differed twice on main, APK identical) or «same lines in a different order».
* Build 148: settings search entries `updates.check_runs` (both, anchor of the check-runs card) and `updates.exact`
  (Android, anchor of the exact-checks row); no new strings.
* Build 147 (R, test files): small test files (≤ 80 lines, one top-level class) merged per directory and name
  prefix into `<Prefix>{ParitySet|UiSet|RuleSet|DataSet|Set}[n]Test.kt` / `<Prefix>Set[n]Tests.swift` (≤ 380 lines,
  classes unchanged, imports merged); references to the old file names now name the class. New small tests go into
  the matching set file.
* Build 146 (KMP prep, part 1): `util/KeyValueStore` + `KeyValueStores` in `:domain`, SharedPreferences adapter
  `data/local/SharedPreferencesStores` (same file names), provided in `LocalDataModule`; `AlarmSnoozeStore`,
  `DepegReadingStore`, `BulkDelistedStore` and `BackgroundErrorCounter.install` use it. `util/DomainLog` replaces
  Timber in `:domain` (sink set in `CryptoCheckerApp`). `rethrowIfCritical` recognises `DeadSystemException` by class
  name. Still Android/JVM-bound in `:domain`: Room annotations on `WatchEntity`/`AlarmEntity`, `org.json`, OkHttp's URL
  parser in `LogoUrlPolicy`; the unused `timber`/`core-ktx` dependencies go at the next lockfile update.
* Build 145: portfolio widget list with bookings per coin – `PortfolioWidgetEntries` (Android `:domain`, iOS
  `Shared/Portfolio`): one entry per ledger step with a holdings change (`PortfolioLedger.events` +
  `PortfolioHoldingsBook`, so «both» sums to the holding), note via `shortNote` (6 graphemes + «…»), today's value,
  at most 40 per coin (more → none); stored in the snapshot (`entries` per position, Android JSON key `"e"`), scaled
  with the positions in «USDT only»/rebasing. `assign` places them only in the height left below the coins (all or
  none per coin). Setting: Android `WidgetPrefs.portfolio_entries_<id>` (configure screen, list view only), iOS
  `PortfolioWidgetIntent.entries`. Parity: `widget_portfolio_entries.json`.
* Build 144 (R, step 9): `ManualRefresh` and `ChartRefresher` in `:domain` behind `ManualRefreshStats`,
  `ManualRefreshNet` and `ChartWidgets` (adapters in `PriceRefresherAdapters.kt`, providers in `RefreshModule`);
  `domain/watch/SheetChart`, `widget/WidgetChartGeometry`, `util/ChartSummary` and `data/BoundedCache` (from `:data`,
  same package) in `:domain`. Left in `app/…/domain`: `ActivityMonitor`, `DepegAlarmChecker`, `GasAlertChecker`,
  `DayReferences`, `RefreshSideChecks` (data sources, notifications), `DiagnosticReport` (string resources).
* Build 143 (R, step 8): 13 more files to `:data` – `settings/SettingsRepository`, `SettingsDataStore`, `SettingsKeys`,
  `SettingsReading`, `SettingsLockFallback`, `MarketAlertReset` (new `settings/MarketAlertPrefs.kt`: `ZoneCheckPrefs`
  and `GasAlertPrefs` with the prefs names, keys and the gas lock; `ZoneCheckWorker` and `GasAlertChecker` keep their
  constants as aliases, `GasAlertChecker.lock` returns the shared mutex), `data/SparklineRepository`,
  `data/live/LivePriceStream`, `data/MarketRepositoryImpl`, `data/AddMoments`, `data/BulkAddRunner`,
  `data/portfolio/WatchedAddressRefresher`. Still in `:app/data`: `BackupManager*`/`RestoreRun` (app lock),
  `CoinLogoSync` (widgets), `SheetChartRepository` (widget chart geometry), `PortfolioAlarms` (notifications).
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
* Build 141 (R, step 6): `PriceRefresher` (+ `PriceRefresherAfter`, `…Deferred`, `…One`) and `WidgetRefreshState` in
  `:domain`. New ports in `domain/refresh/PriceRefresherPorts.kt`: `RefresherStore` (watch list, alarms,
  `clearChanges`, `fillChange`), `RefresherStats`, `RefresherSparklines`, `RefresherOnline`, `RefresherWatchWidgets`,
  `RefresherDayReferences`, `RefresherCharts`, `RefresherSideChecks` and `SideCheckExtra` (formerly the nested
  `RefreshSideChecks.Extra`); settings and widget drawing reuse `RefreshSettings`/`RefreshWidgets`. Adapters in
  `app/…/domain/refresh/PriceRefresherAdapters.kt`, provider in `RefreshModule`. `BlockedSources` split out of
  `CandleDataSource.kt` (same package) and moved. Still in `:app`: `ManualRefresh`, `ChartRefresher`, `DayReferences`,
  `RefreshSideChecks` (with `ActivityMonitor`, depeg and gas checkers) – they use Android, DAOs or notifications.
* Build 140 (R, step 5): `RefreshEffects` (+ `RefreshEffectsAlarmKinds`), `RefreshResultProcessor` and
  `LivePriceApplier` in `:domain`. Their second `@Inject` constructor (app classes → `as…()` adapters) is gone; Hilt
  builds them in `app/…/di/RefreshModule.kt` (`@Provides @Singleton`), tests keep using the primary constructor with
  fakes. Moved along so they compile: `domain/alarm/AlarmDelivery` (`AlarmDeliveryMarkerStore`),
  `notification/NotificationBudget`, `util/AppScope` (`appScope`, `appExceptionHandler`), `util/BackgroundErrorCounter`
  and `data/local/DatabaseKeyUnavailable.kt` (`DatabaseKeyUnavailableException`, `isDatabaseKeyUnavailable`, split out
  of `EncryptingOpenHelperFactory.kt`, same package). For Kotlin Multiplatform later: `appScope` and the error counter
  still use Timber and SharedPreferences.
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
* Build 138 (R, step 3): 21 more files to `:domain` – `domain/activity` without `ActivityMonitor` (analyzer,
  models, sensitivity, why/factors/summary, broad market, BTC correlation, candle/OI series, funding trend),
  `domain/alarm` `NearExtreme`, `DerivativesAlarm`, `AlarmSentence`, `AlarmTemplates`, `domain/market`
  `CryptoPulse`, `MarketMove`, `MarketPlainSummary`, and `data/remote/FuturesExchanges` (`FuturesSources`,
  `FuturesParsers`) with `FuturesInfo`, split out of `FuturesDataSource.kt` (same package). The rest of `app/domain`
  (refresh flow, `ActivityMonitor`, depeg/gas checkers, `DerivativesAlarmData`) uses repositories, DAOs or
  notifications directly; moving it needs interfaces (ports) in `:domain` implemented in `:app`/`:data`.
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
* Build 131: FIFO origin parts read on demand – `PortfolioFifoParts` (Kotlin/Swift): per queue a `FeeStream` (time,
  factor = fee ÷ holdings, prefix sums); a lot carries own entries (points, ranges) plus amount × Σ factors since it
  joined; disposals keep entries and expand them on first read (`PortfolioCostPartList(fifo:)` on iOS, lazy list on
  Android); open parts aggregate the stream once per queue. Logo image limit 4 MB (`MAX_IMAGE_BYTES` /
  `maxImageBytes`), failure memory key v2. iOS `AppStatus` also holds exchangePauses, changeStamp, activityReports,
  portfolioSavePending. Android audit: `TestDisplayOverride` (Compose locals only: font scale, locale, layout
  direction) set by `DeviceOverridesRule`; runs in standard size, font ×2 and Arabic plus the alerts overview (bell).
  iOS audit still report-only (findings in settings come with the settings redesign); nav-bar dynamic type and element-less text detections logged only; tab tap and audit
  timeout retried once.
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
* Build 124: CSV import – `PortfolioRepository.insertImported` cleans and checks the rows (`cleanForSave` + `require`)
  on `Dispatchers.Default`; `CsvImportMapping.unsavableReason` puts rows that saving would reject into the preview's
  skipped list (`MISSING_FIELD`, new `AMOUNT_TOO_SMALL`, `INVALID_SWAP`; parity `csv_import.json` › «unsavable», also
  checked against `cleanForSave`); `runCsvImportSave` (confirm flow as a pure function: prepare, cancel, save, enable)
  and `canCancelSave`; `csvImportOrphaned` closes the import flow after process death (picked, no pending file picker
  result, view model `Idle`). Widget: `ListWidgetRetry.clearIfExpired` at app start (`RetryWait.widgetRetryExpired`).
  `release_readiness.py`: «Build N:» history only for N < versionCode, «früher» / «previously» only at the line start
  or in parentheses.
* Build 123: `RetryWait` (retry wait of the widget refresh: WorkInfo ENQUEUED + `runAttemptCount` > 0 →
  `nextScheduleTimeMillis`, linear backoff, countdown seconds), `PriceUpdateScheduler.observeManualRetryAt` →
  `WatchlistLive.widgetRetryAt` → status pill countdown (`WatchlistRetryCountdown`), `ListWidgetRetry` (partial widget
  update with the fixed retry time `widget_retry_at` via `RetryWait.widgetRetryTime`, `WidgetPrefs.refreshRetryAt`),
  `RefreshReport.attempt` (`RefreshReportCodec`, extracted from `RefreshStats`), `BackgroundWorkPolicy.RETRY_BACKOFF_SECONDS`.
  Android only: iOS runs the widget refresh directly in the intent without a retry. CSV import: `CsvImportState.Done` /
  `Cancelled` (result as state, survives rotation), `prepareCsvImport` / `CsvImportSavePlan` (on `Dispatchers.Default`),
  `insertImported` inserts directly via `portfolioDao` (no nested transaction). `BulkUndoConfirm.finishedAt` (saved;
  «Remove» re-checks `BulkAddRunner.undoOffered`, `bulk_undo_expired`). Device test `PortfolioImportInsertTest`.
* Build 122: `CsvImportProgress` (throttled progress, cancellable insert; iOS `CsvImportBackground`, `CsvImportRun`,
  `AppData+PortfolioImport`), `BulkAddProgress(text:)`, coverage gate `--min-logic` (`MIN_LOGIC` 58 / 78),
  `PortfolioPerformanceTest` / `TaxPerformanceTest` (+ iOS), `PerformanceTiming.Sizes`, `exchange_errors_synthetic.json`
  + `ExchangeErrorContractTest`, parity `quiet_hours.json` / `currency_conversion.json`, groups hint without
  onboarding/minimum (`showsGroupsHint`), iOS `TaxEngineUk` binary search, `RateLimitSignal` UTF-16 length.
* Build 121: Swift 6 language mode (`SWIFT_VERSION 6.0`, `ios/README.md` «Swift 6 Migration»), `WatchSearchIndex` /
  `rememberWatchSearchResult` (iOS `WatchlistSearchIndex`), `coverage_summary.py` «logic» + Kover merge of :marketdata,
  `xccov_summary.py`, `mutation_test.py` + `mutation.yml`, `indicators.json` parity, `rsi` period ≤ 0 → null (Android),
  `release_readiness` guide patterns for tag examples / archive name, `-PperformanceTests` (only/true; performance
  tests in their own CI step), `LocalResources` in `WatchlistBulkAdd`.
* Build 120: `AlarmCheckStatus.batch` (Android), `BulkAddRunner` end states (cancelled/failed, finish time, undo window
  60 s, `deleteNewWatchesForUndo` without alarms), `BulkAddDisplay`, iOS `WatchInsertion`, `DenseRowText.convertedMaxLines`
  / `convertedLineLimit`, `lineStart` (relative time at line start), `MarketMoveScale` opposing market/breadth → mixed.
* Build 119: `AppSnackbarHost` / `showAppSnackbar` / `SnackbarKind` / `SnackbarPalette` (+ `snackbar_colors.json`), iOS
  `snackbarSurface`, `sbom.py`, `reproducible.yml` + `repro_compare.py`, behaviour tests (`FakeWatchStore`,
  `WatchlistWriteConsentTest`, `BulkAddRunnerTest`, iOS `WatchlistConsentBehaviorTests`, `AddWatchesBehaviorTests`),
  performance tests (`performance/*PerformanceTest`, iOS `*PerformanceTests`, 2000 pairs), iOS `AlarmUpdate.Index`,
  `ios.yml` split into three parallel jobs (no separate Release Simulator build any more).
* Build 118: `BulkAddRunner` (app-wide bulk add, progress, `undo`), `bulkNewCount` / iOS `ExplorerBulkCount`,
  `BulkAddProgress` / `WatchlistBulkAdd`, `MarketMoveScale` (+ `market_move_scale.json`), `alarmReliabilityLineShown`,
  `PortfolioEmptyExample`, exchange check row selector (`{"symbol": "BTC"}`).
* Build 117: reserve mirror key (`RESERVE_PUBLIC_KEY` / `PUBLIC_KEYS`, `productionReservePublicKey`), CI step «Kotlin
  warnings» (ceiling 0, main sources, from the unit test log), `DenseRowText.convertedMaxWidth` (+ iOS `CappedWidthLayout`),
  `CurrencyConverter.usdtMeasured` / iOS `WatchlistConversion.waitForNextRound`, `WatchlistConversion.accessibilityText`,
  `HostStorageKey.probeAddStatus`, trusted Gradle sources (`gradle-*-src.zip`) in the verification seed.
* Build 116: no `paths` filter for `pull_request` (required checks), allowed environment skips in ios.yml,
  `HostStorageKey.shouldUseTestKeychain`, rate limit passthrough in `SingleTickerFallback`.
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
