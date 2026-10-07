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

## 1. Structure

| Path | Content |
|---|---|
| `android/marketdata/src/main/java/com/cryptochecker/marketdata/model/Market.kt` | Base class: request URLs and parsing hooks |
| `android/marketdata/…/model/market/generic/SimpleMarket.kt` | Base class for the usual case «pair list URL + ticker URL» |
| `android/marketdata/…/model/market/*.kt` | One file per exchange (or per market, e.g. spot and futures) |
| `android/marketdata/…/model/Ticker.kt`, `SimpleTicker.kt` | Price data of one pair |
| `android/marketdata/…/model/CurrencyPairInfo.kt` | One tradable pair: base, quote, exchange-specific pair id |
| `android/marketdata/…/config/MarketsConfig.kt` | List of registered exchanges |
| `android/marketdata/…/util/JsonUtils.kt` | JSON helpers (`forEachJSONObject`, `optDoubleNoData`, …) |
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

After adding a Swift file, regenerate the Xcode project with
`python3 ios/tools/gen_xcodeproj.py` (it picks up new files by itself).

Both platforms must behave the same: same pair ids, same fields, same
fallbacks. Backups can be moved between Android and iOS, so the **key** of the
exchange must be identical on both sides (see [section 8](#8-registering-the-exchange)).

## 3. The adapter

Most exchanges fit `SimpleMarket`: one URL for the pair list and one ticker URL
with the pair id as `%1$s`.

```kotlin
/** Bitstamp (EU regulated, also EUR/GBP pairs). API v2. */
class Bitstamp : SimpleMarket(
    "Bitstamp",                                              // name shown in the app
    "https://www.bitstamp.net/api/v2/trading-pairs-info/",   // pair list
    "https://www.bitstamp.net/api/v2/ticker/%1\$s/",          // ticker, %1$s = pair id
    errorPropertyName = "reason"                             // error text in error responses
)
```

```swift
final class Bitstamp: SimpleMarket {
    init() {
        super.init(
            key: "Bitstamp",
            name: "Bitstamp",
            pairsURL: "https://www.bitstamp.net/api/v2/trading-pairs-info/",
            tickerURL: "https://www.bitstamp.net/api/v2/ticker/%1$s/",
            errorPropertyName: "reason"
        )
    }
}
```

`SimpleMarket` also takes an optional `ttsName` – the name used for spoken
announcements when the written name reads badly (e.g. «Gate I O» for «Gate.io»).

If the exchange needs something `SimpleMarket` does not cover (POST requests,
several requests per price, a ticker URL built from base and quote), extend
`Market` directly and override `getUrl` / `url(requestId:info:)`; see
[section 10](#10-advanced-cases).

## 4. Pair list

Override `parseCurrencyPairs` (whole response as text) or
`parseCurrencyPairsFromJsonObject` (response is a JSON object) and add one
`CurrencyPairInfo(base, quote, pairId)` per tradable pair.

```kotlin
override fun parseCurrencyPairs(requestId: Int, responseString: String, pairs: MutableList<CurrencyPairInfo>) {
    JSONArray(responseString).forEachJSONObject { item ->
        if (item.optString("trading") != "Enabled") return@forEachJSONObject   // skip disabled pairs
        val name = item.getString("name")                                       // e.g. BTC/USD
        if ('/' !in name) return@forEachJSONObject
        pairs.add(CurrencyPairInfo(name.substringBefore('/'), name.substringAfter('/'), item.getString("url_symbol")))
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
* Skip pairs that are disabled, delisted or in maintenance if the API says so.
* Pairs are loaded when the user syncs the list of an exchange; a static list in
  the code is only a fallback for exchanges without a pair endpoint
  (`CurrencyPairsMap`, see `model/market/example/MarketExample.kt`).

## 5. Single ticker

Override `parseTickerFromJsonObject` (Swift: `parseTicker(requestId:json:ticker:info:)`)
and fill the `Ticker`:

```kotlin
override fun parseTickerFromJsonObject(requestId: Int, jsonObject: JSONObject, ticker: Ticker, checkerInfo: CheckerInfo) =
    read(jsonObject, ticker)

private fun read(json: JSONObject, ticker: Ticker) {
    ticker.last = json.getDouble("last")           // required
    ticker.bid = json.optDoubleNoData("bid")       // optional fields: NO_DATA if missing
    ticker.ask = json.optDoubleNoData("ask")
    ticker.high = json.optDoubleNoData("high")
    ticker.low = json.optDoubleNoData("low")
    ticker.vol = json.optDoubleNoData("volume")    // 24 h volume in the base currency
    ticker.timestamp = json.optLong("timestamp")
    ticker.change24hPercent = Change24h.fromOpen(ticker.last, json.optDouble("open_24"))  // see section 11
}
```

* `last` is required; a response without it must throw so the app shows an error.
* All other fields are optional – use `optDoubleNoData` (Swift: `optDoubleNoData`)
  so a missing value is shown as «no data» and not as 0.
* `vol` is the base-currency volume; set `volQuote` if the exchange reports the
  quote volume as well.
* `timestamp` may be seconds, milliseconds or nanoseconds – the library converts
  it. Leave it 0 if the exchange sends none; the time of the request is used.
* Set `change24hPercent` only if the response carries a **rolling** 24 h change
  or the price 24 hours ago – see [section 11](#11-24-h-change).
* Keep the parsing in one `read` function so single and bulk ticker share it.

If the response is not a JSON object (e.g. a JSON array), override
`parseTicker(requestId, responseString, ticker, checkerInfo)` instead.

## 6. Bulk ticker

If the exchange returns all prices in one request, implement the bulk ticker.
It saves many requests when the watchlist contains several pairs of the exchange.

```kotlin
override val bulkTickersNumOfRequests: Int get() = 1
override fun getBulkTickersUrl(requestId: Int): String = "https://www.bitstamp.net/api/v2/ticker/"

override fun parseBulkTickers(requestId: Int, responseString: String, tickers: MutableMap<String, Ticker>) {
    JSONArray(responseString).forEachJSONObject { item ->
        val pair = item.optString("pair").ifEmpty { return@forEachJSONObject }   // "BTC/USD"
        val ticker = SimpleTicker()
        runCatching { read(item, ticker) }.onFailure { return@forEachJSONObject } // skip broken entries
        tickers[pair.replace("/", "").lowercase(Locale.ROOT)] = ticker           // key = pair id "btcusd"
    }
}
```

* The **map key must be the same pair id** as in the pair list – otherwise the
  price cannot be matched to the watched pair.
* One broken entry must not fail the whole response: skip it.
* `bulkTickersComplete = true` only if the bulk response always contains every
  tradable pair (then a missing pair is treated as no longer traded).
* If the exchange can filter the bulk request by pair ids, override
  `getBulkTickersUrl(requestId, pairIds)` to download only what is needed.

## 7. Errors

Many exchanges answer with an error object (HTTP 200 or 4xx). Bitstamp sends
`{"reason": "…"}` – `errorPropertyName = "reason"` is enough for `SimpleMarket`.
For other formats override `parseErrorFromJsonObject` or `parseError` and return
the exchange's message (or `null` if there is none). The app shows it to the user.

## 8. Registering the exchange

Add the adapter to both lists:

```kotlin
// android/marketdata/…/config/MarketsConfig.kt
private val registeredMarkets = arrayOf(
    …
    Bitstamp(),
    …
)
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
* Run the unit tests: `cd android && ./gradlew test` (Windows: `gradlew.bat test`).

## 10. Advanced cases

**Several requests per price.** If one request does not deliver everything,
override `getNumOfRequests` (Swift: `numOfRequests`) and use `requestId`
(`0 … n-1`) in `getUrl` and the parse methods to tell the requests apart.

**Several requests for the pair list.** Override `currencyPairsNumOfRequests`
and branch on `requestId` in `getCurrencyPairsUrl` / `parseCurrencyPairs`. If the
pair list can only be built from all responses together (e.g. instruments plus
symbol details), set `currencyPairsCombined = true` and implement
`parseCurrencyPairsCombined`.

**POST requests.** Return a `PostRequestInfo` from `getPostRequestInfo`,
`getCurrencyPairsPostRequestInfo` or `getBulkTickersPostRequestInfo`.

**Futures.** Use a separate adapter (e.g. `BinanceFutures` next to `Binance`)
and set the `contractType` of `CurrencyPairInfo`.

## 11. 24 h change

`Ticker.change24hPercent` (Swift: `change24hPercent`) is the **rolling** 24 h
change in **percent** (`1.5` = +1.5 %) as the exchange reports it. The watchlist
pill, the pulse line and the widgets use it first. Only if it is `null`/`nil`
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

| Exchange / market | Field used | Unit / note |
|---|---|---|
| Binance, Binance.US | `priceChangePercent` (`/ticker/24hr`) | percent |
| Binance Futures (USD-M, COIN-M) | `priceChangePercent` (`/ticker/24hr`) | percent |
| bitFlyer | – fallback candles | ticker has no 24 h change |
| Bitfinex | `DAILY_CHANGE_RELATIVE` (index 5, bulk 6) | fraction × 100 |
| Bitget, Bitget Futures | `change24h` | fraction × 100; `changeUtc24h` (since 00:00 UTC) not used |
| Bitso | `change_24` | absolute change → open = last − change |
| Bitstamp | `open_24`, else `percent_change_24` | `open` is the start of the day and not used |
| Bitvavo | `open` (`/ticker/24h`) | price 24 h ago |
| BtcTurk | `open`, else `dailyPercent` | price 24 h ago / percent |
| Bybit, Bybit Futures | `price24hPcnt` | fraction × 100 |
| Coinbase | `open` from `/stats` (request 2), against `last` of `/ticker` | price 24 h ago |
| Crypto.com | `c` | fraction × 100; `null` without trades |
| Deribit | `stats.price_change` (single), `price_change` (bulk) | percent |
| DexScreener | `priceChange.h24` | percent |
| Gate.io | `change_percentage` | percent; `change_utc0`/`change_utc8` not used |
| Gemini | – fallback candles | `/pricefeed` `percentChange24h`: unit unclear (docs show percent «5.23», real responses look like fractions «0.0146»); `pubticker` has no 24 h change |
| HTX, HTX Futures | – fallback candles | `open` of the bulk ticker is documented as the open of the calendar day (Singapore time) |
| Hyperliquid | `prevDayPx` against the shown price (`midPx`, else `markPx`) | mark price 24 h ago |
| Independent Reserve | – fallback candles | no change/open in `GetMarketSummary` |
| Indodax | – fallback candles | no change/open in `ticker` / `ticker_all` |
| Kraken | – fallback candles | `o` is today's open (UTC day), not rolling |
| KuCoin | `changeRate` | fraction × 100 |
| LATOKEN | `change24h` | percent |
| MEXC | `openPrice`, else `priceChangePercent` | price 24 h ago / **fraction** × 100 |
| MEXC Futures | `riseFallRate` | fraction × 100; the day values in `riseFallRates` are not used |
| NonKYC | – fallback candles | no documented rolling 24 h field |
| OKX, OKX Futures | `open24h` | price 24 h ago; `sodUtc0`/`sodUtc8` not used |
| One Trading | `price_change_percentage` | percent |
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
