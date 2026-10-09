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
  For a comma list in the URL (Kraken `?pair=`, Bitfinex `?symbols=`) use
  `BulkPairChunks` and override `bulkTickersRequestCount(pairIds)` as well, so
  long lists are split into several requests with URLs under 2000 characters.
  If a filtered request fails (rejected or unreadable), the app loads the
  unfiltered one once instead.

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
* Run the unit tests on both platforms – see [Running the tests](#12-running-the-tests).

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
| Coinbase | `open` from `/stats` (the only request), against `last` of the same response | price 24 h ago |
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

## 12. Running the tests

**Android unit tests** (JVM, no device; also run by the *Android* workflow on every push):

```sh
cd android
./gradlew testDebugUnitTest        # or: ./gradlew test (debug + release, all modules)
```

Reports: `android/app/build/reports/tests/testDebugUnitTest/index.html`.

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

**iOS unit tests** (target `CryptoCheckerTests`, hosted by the app, `@testable import
CryptoChecker`):

```sh
cd ios
python3 tools/gen_xcodeproj.py     # after adding/removing Swift files or changing the JSON cases
xcodebuild test -project CryptoChecker.xcodeproj -scheme CryptoChecker \
  -destination 'platform=iOS Simulator,name=iPhone 16'
```

In Xcode: scheme *CryptoChecker* → Product › Test (⌘U). The *iOS* workflow (automatic on
changes in `ios/` and by hand under Actions) builds the app and runs the same tests in the Simulator.

**Android instrumented test** (`app/src/androidTest`, `CoreFlowTest`): starter selection →
add BTC → price alarm → new price → alarm and notification → swipe to delete → Undo →
portfolio lock shows the locked state. Hilt replaces the network client with fixed prices
(`FakeRemoteDataModule`), so no exchange is called. `DatabaseMigrationTest` checks the
update path of the database: it creates an old database (schema v3 from `app/schemas/…/3.json`,
and v10 via the app's own migrations), fills it, and lets Room open it – Room runs the
migrations and validates the result against the entities. Needs an emulator or a device:

```sh
cd android
./gradlew :app:connectedDebugAndroidTest
```

The portfolio-lock test sets a temporary screen-lock PIN through `locksettings` (removed
afterwards) and is skipped where that is not possible. CI only compiles the instrumented
tests (`assembleDebugAndroidTest`); running them on an emulator in GitHub Actions is
possible but slow and not set up.

**CI gates** (`.github/workflows/android.yml`, on pushes and pull requests touching
`android/`): unit tests, lint (`lintDebug`, `abortOnError`), instrumented tests compiled, debug
build and an unsigned release build with R8 (catches missing keep rules). Pushes to `main`
submit the Gradle dependency graph (Dependabot alerts); pull requests run a dependency review
that fails on new dependencies with known high-severity vulnerabilities.
`.github/dependabot.yml` opens weekly update PRs for GitHub Actions and Gradle. The *iOS*
workflow builds and tests in the Simulator when `ios/` or the shared parity cases change.

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
| Market phase (scores) | 1 h | several sources with fallbacks |
| Altcoin season | 3 h | ~20 histories; moves over days |
| On-chain values (Coin Metrics) | 12 h | daily data |
| Cycle comparison (halving curves) | 12 h | daily candles since 2016 |
| Economic calendar | 24 h | one small JSON file |

**Data mirror** (`DataMirror.kt` / `DataMirror.swift`, parity cases `data_mirror.json`): data that
is the same for every user is fetched once an hour by `.github/workflows/market-data.yml`
(`.github/scripts/mirror_data.py`) and uploaded as assets of the release «data»
(`https://github.com/r1adBE/cryptoChecker/releases/download/data/<file>`, first line
`CCDM1 <fetched ms>`, then the provider's body unchanged; nothing is committed). `callMarket`
(Android) and `MarketHTTP.call` (iOS) ask the mirror first for exactly these GET URLs and the
provider otherwise: CoinGecko `/global` (`global.txt`, max age 2 h), Fear & Greed (`fng.txt`, 4 h),
CoinGecko top 30/40 for the starter coins (`coins30.txt`/`coins40.txt`, 12 h), Coin Metrics
on-chain values (`onchain.txt`, any `start_time`, 36 h) and BTC price since 2016 (`btcprice.txt`,
36 h). `altseason.txt` (6 h) is not a copy but the altcoin season already computed by the script
(same 20 alts and 90-day rule; `{"outperformers":12,"total":20,"provider":"Binance"}`, read with
`DataMirror.parseAltSeason`); without it the app computes it from ~21 candle requests as before. Live data (tickers, Crypto Pulse, gas, per-coin analysis) stays direct. A new mirrored URL
needs the same entry in `mirror_data.py`, both `DataMirror` files and `data_mirror.json`.

**Coin logos** (`CoinLogos.kt` / `CoinLogos.swift`, parity cases `coin_logos.json`): first
source is our own list `https://r1adbe.github.io/cryptoChecker/logos/index.json`, built daily by
`.github/workflows/coin-logos.yml` (`.github/scripts/build_logos.py`, `--selftest`): all CoinGecko
`/coins/markets` pages (optional demo key in the repository secret `COINGECKO_API_KEY`); per
ticker the same order as the apps: CoinGecko top 1000 → Binance symbol list → Binance Alpha token
list → rest of CoinGecko (highest 24 h volume; top 1500 or ≥ 100k USD) → OKX instruments +
`static.okx.com` icons. TradFi keys (`TRADFI:NVDA`) from the Binance list (`pickTradFi` rules) and
Binance stock images (`static/stock`, probed for all Nasdaq stocks); gold (`XAU`, `GOLD`, `XAUT`),
silver (`XAG`, `SILVER`), platinum (`XPT`) and palladium (`XPD`) get our own drawn icons (`metal_icon`, bump `OWN_ICON_VERSION` after changing
the drawing); names from the Nasdaq symbol
directory, all stock names in `docs/logos/stocks.txt`. Optional sources keep their previous entries
when unreachable (Binance and OKX block some regions — check the run log). Images as 128 px WebP
named by content hash in `docs/logos/img/`, all of them also in one file `logos.pack` (release
«logos»; `CCLP1\n` then `path\tlength\n` + bytes per image). The apps read the list with
`parseIndex` (only `img/<16 hex>.webp`), load all images from the pack when 40+ are missing
(`parsePack`), else one by one, take stock names from `stocks.txt`, delete stored images whose
URL changed (`changedKeys`) and never fall back once they use the list (`isFromIndex`). The
index only holds `f` (file) and `n` (name); source URLs and stock misses are in `state.json`,
the last successful run and the reachable sources in `status.json`. The apps send the last
`ETag` as `If-None-Match` (index and `stocks.txt`); «304» means unchanged and nothing is downloaded.
`setup-github-public.ps1` keeps `docs/logos` from GitHub. Without the list (fallback), the
symbol → image map comes from CoinGecko `/coins/markets` (top 1000 first, first occurrence by
market cap wins), gaps filled from the Binance website list `bapi/composite/v1/public/marketing/symbol/list`
(`name` → `logo` on `*.bnbstatic.com`, unofficial; only symbols CoinGecko lacks, e.g. XAU, XAG,
stocks), then the official Binance Alpha token list
`bapi/defi/v1/public/wallet-direct/buw/wallet/cex/alpha/all/token/list` (`symbol` → `iconUrl`,
tradable first, largest `marketCap` first; futures tokens without a CoinGecko market cap such as
AIA, AGT, AIO), then CoinGecko ranks 1001–2500 for the remaining gaps (e.g. AIN, LUNA), and is
kept for **7 days**. Every CoinGecko page after the first waits 6 s (the keyless limit otherwise
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
`*.coingecko.com`; DEX pools (`DexScreener`) always show initials. Android:
`CoinLogoRepository` → `LocalCoinLogoSource` (null when «In the app» is off) →
`CoinBadge` (`portfolio = true` → `LocalPortfolioCoinLogoSource`), sync started by
`CoinLogoSync` when `CoinLogoUse.needed` (app, widgets, or portfolio with the tab on; app
start, switch on, portfolio tab on). Defaults: app off, portfolio on, widgets off; widgets via
`WidgetCoinLogos` (24 dp bitmaps). iOS: `CoinLogoStore` (actor, `syncAll` from
`AppData.startCoinLogoSync`) → `CoinBadge` (`coinLogosEnabled` environment,
`CoinLogoRevision`); widgets via `WidgetLogos` (disk only) and the `widgetLogos` environment.

**Manual reloads.** Pull-to-refresh, «Retry» and the small «Refresh» action under the
altcoin season force a reload, but slow, expensive areas (altcoin season, market phase,
on-chain values, cycle comparison) reload by hand at most every **5 minutes**
(`CycleCachePolicy.manualMinInterval` / `MANUAL_MIN_INTERVAL_MILLIS`); within that window
the stored value stays. The altcoin season details show «As of 14:05» from the stored
timestamp; its «Refresh» stays disabled until 5 minutes have passed.

**Freshness of watchlist prices.** One threshold for the status pill, the faded row, the red
time, the word «outdated» and the screen-reader sentence: `OutdatedRule.afterMillis` (live mode:
2 min, otherwise 3 × the interval, at least 15 min) — Android `WatchlistViewModel.staleAfterMillis`
= `outdatedAfterMillis`, iOS `AppData.staleAfterMillis` = `outdatedAfterMillis`, widgets the same
(`WidgetOutdated`). The Android live service («Update frequently») polls at the chosen interval
with the app visible, at least every 60 s when hidden and at least every 5 min with the screen
off (`LiveInterval`, `SCREEN_OFF_MIN_SECONDS`).

**Source and age.** Every row under «Context» and «Data» (except the calendar-based halving)
ends its secondary line with the provider that actually delivered the value and its age, e.g.
«Greed · alternative.me · today 02:00» or «CoinGecko · 3 min ago» (`DataFreshness` /
`DataStamp`, both platforms). Sources with a fallback chain report the one that answered
(candles: Binance / Binance.US / Coinbase / Bybit; market phase adds «Coin Metrics» when
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
of REST polling. Setting: *Settings › Updates › «Live prices»*, off on a
new install. Background refresh (WorkManager, foreground service, BGTask) and widgets never open a
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
  (`LiveRules.UI_INTERVAL_MILLIS`); the status pill shows a small «LIVE» badge with a pulsing
  dot (static with Reduce Motion), the refresh report lists «Live: Binance, Bybit …».
* Storage, alarms, widgets: pending quotes are written in one batch every 10 s through
  `PriceRefresher.applyLive` (iOS: `AppData.applyLive` → `PriceRefresher.refresh(liveQuotes:)`)
  – the same path as a refresh, so alarm crossings, cooldowns and re-arming are shared and
  nothing is notified twice. No price announcements and no volume alarms in that path (volume
  needs hourly candles; REST checks it). Widgets are redrawn at most once a minute and when the
  watchlist is left. If a refresh is running, the batch waits for the next round.
* 24 h change: the stream's value is used only with the basis «Last 24 h», a matching change
  stamp and an exchange whose stream value is rolling (`LiveRules.chooseChange`); otherwise the
  stored value stays until the next REST refresh.
* REST while live: a pair with a live quote younger than 30 s is skipped by the REST refresh
  (`LiveRules.skipRest`, rolling basis only); if the stream stalls, the pair falls back to REST
  automatically.

Tests: `LiveFeedTest.kt` / `LiveFeedTests.swift` (parser samples per exchange, symbols,
subscribe messages, plan, backoff, rules, buffer). Adding an exchange: add a `LiveExchange`
case on both platforms, sample messages to `live_feed.json`, and a row to the table above.
