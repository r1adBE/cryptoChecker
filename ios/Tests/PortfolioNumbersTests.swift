import XCTest
@testable import CryptoChecker

/// Wie `PortfolioStablesTest.kt`, `PricePlausibilityTest.kt` und `PortfolioHistoryFxTest.kt`:
/// eine Stablecoin-Regel, 25-%-Prüfung für Verlauf/Stichtag, Verlauf mit Devisen-Tageskursen.
final class PortfolioNumbersTests: XCTestCase {

    private var nextId: Int64 = 1
    private let d = 1e-9
    private let today = LocalDay(2026, 10, 6).epochDay

    private func buy(_ coin: String, _ amount: Double, _ time: Int64) -> PortfolioTx {
        defer { nextId += 1 }
        return PortfolioTx(id: nextId, coin: coin, type: .BUY, amount: amount, priceUsdt: 1, time: time)
    }

    private func dec(_ text: String) -> Decimal { Decimal(string: text, locale: Locale(identifier: "en_US_POSIX"))! }

    private func noon(_ day: Int) -> Int64 { Int64(day) * 86_400_000 + 12 * 3_600_000 }

    private func build(_ trades: [PortfolioTx], _ closes: [String: [Int: Double]],
                       _ live: [String: Double]) -> PortfolioHistorySeries {
        PortfolioHistory.build(trades: trades, closes: closes, livePrices: live, range: .week,
                               todayEpochDay: today)
    }

    /// «Heute» für «7 T/1 J», Verlauf und Erwerbstage ist der UTC-Tag: 00:30 Zürich-Sommerzeit ist UTC noch der Vortag.
    /// Wie Android `PortfolioHistoryTest.utcTodayIsTheUtcDayNotTheLocalDate`.
    func testUtcTodayIsTheUtcDayNotTheLocalDate() {
        // 2026-07-01 00:30 Zürich (UTC+2) = 2026-06-30 22:30 UTC
        let millis = Int64(LocalDay(2026, 7, 1).epochDay) * 86_400_000 - 90 * 60_000
        XCTAssertEqual(PortfolioHistory.utcToday(now: millis), LocalDay(2026, 6, 30).epochDay)
        XCTAssertEqual(PortfolioHistory.utcToday(now: millis), PortfolioHistory.epochDay(utcMillis: millis))
        // 02:30 Zürich = 00:30 UTC: gleich wie das lokale Datum
        XCTAssertEqual(PortfolioHistory.utcToday(now: millis + 2 * 3_600_000), LocalDay(2026, 7, 1).epochDay)
    }

    // MARK: Stablecoins

    func testOneListEverywhere() {
        XCTAssertEqual(PortfolioStables.coins, CurrencyConversion.usdStables)
        XCTAssertEqual(CutoffExport.stables, CurrencyConversion.usdStables)
        XCTAssertEqual(PortfolioHistory.stables, CurrencyConversion.usdStables)
        XCTAssertTrue(PortfolioHistory.isStable("usde"))
        XCTAssertTrue(CutoffExport.isStable(" PYUSD "))
        XCTAssertFalse(PortfolioStables.isStable("BTC"))
    }

    func testUsdtAlwaysOneOtherStablesMarketElseNoValue() {
        XCTAssertEqual(PortfolioStables.price("USDT", market: 1.0004), 1)
        XCTAssertEqual(PortfolioStables.price("usdt", market: nil), 1)
        XCTAssertEqual(PortfolioStables.price("USDC", market: 0.99), 0.99)
        // Kein stilles 1 mehr: ohne Kurs kein Wert
        XCTAssertNil(PortfolioStables.price("USDC", market: nil))
        XCTAssertNil(PortfolioStables.price("DAI", market: .nan))
        XCTAssertNil(PortfolioStables.price("DAI", market: 0))
        XCTAssertEqual(PortfolioStables.price("BTC", market: 50), 50)
        XCTAssertNil(PortfolioStables.price("BTC", market: nil))
        XCTAssertFalse(PortfolioStables.needsQuote("USDT"))
        XCTAssertTrue(PortfolioStables.needsQuote("USDC"))
    }

    func testPeriodChangeStableWithoutCloseOnlyNearOne() {
        // Wie PortfolioPeriodChangeTest.kt: 100'000 USDC, aktuell 0.999, kein Schluss → «—» statt −100
        let base = today - PortfolioPeriodChange.days
        XCTAssertNil(PortfolioPeriodChange.change(holdings: ["USDC": 100_000], current: ["USDC": 0.999],
                                                  closes: [:], baseDay: base))
        // Abweichung ≤ 0.1 %: weiterhin 1
        let near = PortfolioPeriodChange.change(holdings: ["DAI": 1_000], current: ["DAI": 1.0005],
                                                closes: [:], baseDay: base)
        XCTAssertEqual(near?.amount ?? .nan, 0.5, accuracy: d)
        // Reihe beginnt erst nach dem Stichtag: gleiche Regel
        let late = Dictionary(uniqueKeysWithValues: ((today - 3)...today).map { ($0, 0.99) })
        XCTAssertNil(PortfolioPeriodChange.change(holdings: ["USDC": 10], current: ["USDC": 0.99],
                                                  closes: ["USDC": late], baseDay: base))
        // USDT gilt immer 1 (keine Kerzen nötig)
        let usdt = PortfolioPeriodChange.change(holdings: ["USDT": 10], current: ["USDT": 1],
                                                closes: [:], baseDay: base)
        XCTAssertEqual(usdt?.amount ?? .nan, 0, accuracy: d)
        XCTAssertEqual(PortfolioStables.fallbackWithoutClose("USDT", live: 0.9), 1)
        XCTAssertEqual(PortfolioStables.fallbackWithoutClose("USDC", live: 1.0), 1)
        XCTAssertNil(PortfolioStables.fallbackWithoutClose("USDC", live: 0.998))
        XCTAssertNil(PortfolioStables.fallbackWithoutClose("USDC", live: .nan))
        XCTAssertNil(PortfolioStables.fallbackWithoutClose("BTC", live: 1.0))
    }

    func testTodaySampleAppliesStableRule() {
        // Wie PortfolioStablesTest.kt: 5'000 USDC mit USDCUSDT 1.0 + 1 BTC 5'000 → 5'500
        // ergibt +5 % (USDC zählt in Stand und Vergleich), nicht +10 %
        let trades = [buy("USDC", 5_000, 1), buy("BTC", 1, 1)]
        let t0: Int64 = 1_700_000_000_000
        let raw0: [String: Double] = ["BTC": 5_000, "USDC": 1]
        let before = PortfolioCalculator.summarize(trades, prices: raw0)
        // Reine Rechenschritte liegen in `PortfolioWidgetStore` (nicht im Stand-Typ)
        let history = PortfolioWidgetStore.record(
            [], prices: PortfolioWidgetStore.samplePrices(raw: raw0, open: before.open), at: t0)
        XCTAssertEqual(history.last?.prices["USDC"], 1)
        // Ohne USDCUSDT-Kurs fehlt USDC im Kursstand ganz (kein angenommenes 1)
        let noUsdc = PortfolioCalculator.summarize(trades, prices: ["BTC": 5_000])
        XCTAssertNil(PortfolioWidgetStore.samplePrices(raw: ["BTC": 5_000], open: noUsdc.open)["USDC"])
        let raw1: [String: Double] = ["BTC": 5_500, "USDC": 1]
        let after = PortfolioCalculator.summarize(trades, prices: raw1)
        var holdings: [String: Double] = [:]
        for p in after.open { holdings[p.coin] = p.holdings }
        let sampled = PortfolioWidgetStore.samplePrices(raw: raw1, open: after.open)
        let delta = PortfolioWidgetStore.change(holdings: holdings, prices: sampled,
                                                   reference: history.last?.prices ?? [:])
        XCTAssertEqual(delta?.amount ?? .nan, 500, accuracy: d)
        XCTAssertEqual(delta?.percent ?? .nan, 5, accuracy: d)
    }

    /// Live-Kurs USDT/USD — bestätigter Einbruch zählt, Tagesschlüsse bleiben im Normalbereich. Wie Android.
    func testUsdtUsdLiveKeepsConfirmedBreak() {
        XCTAssertEqual(PortfolioStables.usdtUsdLive(0.3), UsdtUsd(rate: 0.3, approximate: false))
        XCTAssertEqual(PortfolioStables.usdtUsdLive(2.0), UsdtUsd(rate: 2.0, approximate: false))
        XCTAssertEqual(PortfolioStables.usdtUsdLive(0.04), PortfolioStables.usdtUsdFallback)
        XCTAssertEqual(PortfolioStables.usdtUsdLive(3.5), PortfolioStables.usdtUsdFallback)
        XCTAssertEqual(PortfolioStables.usdtUsdLive(nil), PortfolioStables.usdtUsdFallback)
        // Tagesschluss (eine Quelle, kein Abgleich): 0.3 bleibt unplausibel
        XCTAssertEqual(PortfolioStables.usdtUsd(0.3), PortfolioStables.usdtUsdFallback)
        // Heute gilt der Live-Kurs auch im Einbruch, frühere Tage nur ein plausibler Schluss
        let rates = UsdtUsdRates(live: 0.3, daily: [5: 0.3])
        XCTAssertEqual(rates.on(7, todayEpochDay: 7).rate, 0.3)
        XCTAssertTrue(rates.on(5, todayEpochDay: 7).approximate)
        XCTAssertTrue(PortfolioStables.isUsdtBreak(0.3))
        XCTAssertFalse(PortfolioStables.isUsdtBreak(0.99))
    }

    func testConversionUsesPortfolioStableRule() {
        // ≈-Zeile der Merkliste: gleiche Regel wie das Portfolio (wie CurrencyConversionTest.kt)
        XCTAssertEqual(CurrencyConversion.stableToUsd("USDT", marketUsdt: 0.98), 1)
        XCTAssertEqual(CurrencyConversion.stableToUsd("usd", marketUsdt: 0.98), 1)
        XCTAssertEqual(CurrencyConversion.stableToUsd("USDC", marketUsdt: 0.9993), 0.9993)
        // Ohne Kurs KEIN Faktor (wie das Portfolio, kein stilles 1)
        XCTAssertNil(CurrencyConversion.stableToUsd("USDC", marketUsdt: nil))
        XCTAssertEqual(CurrencyConversion.quoteToUsd(.usd, usdToQuote: nil, cryptoUsdt: 0.97, code: "USDE"), 0.97)
        XCTAssertNil(CurrencyConversion.quoteToUsd(.usd, usdToQuote: nil, cryptoUsdt: nil, code: "DAI"))
        XCTAssertNil(CurrencyConversion.quoteToUsd(.usd, usdToQuote: nil, cryptoUsdt: .nan, code: "PYUSD"))
        XCTAssertNil(CurrencyConversion.quoteToUsd(.usd, usdToQuote: nil, cryptoUsdt: 0, code: "FDUSD"))
        XCTAssertEqual(CurrencyConversion.quoteToUsd(.usd, usdToQuote: nil, cryptoUsdt: 0.97, code: "USDT"), 1)
        // BTC/USDC ≈ CHF ohne USDC-Kurs: kein Faktor, also kein ≈-Wert
        XCTAssertNil(CurrencyConversion.rate(quoteUsd: CurrencyConversion.quoteToUsd(.usd, usdToQuote: nil, cryptoUsdt: nil,
                                                                                    code: "USDC"), usdToTarget: 0.8))
        XCTAssertTrue(CurrencyConversion.stableNeedsMarket("usdc"))
        XCTAssertFalse(CurrencyConversion.stableNeedsMarket("USDT"))
        XCTAssertFalse(CurrencyConversion.stableNeedsMarket("USD"))
    }

    func testConversionUsdtAtMarketPrice() {
        // USDT gilt seinen Marktkurs; USD bleibt 1 (wie CurrencyConversionTest.kt)
        XCTAssertEqual(CurrencyConversion.stableToUsd("USDT", marketUsdt: nil, usdtUsd: 0.97) ?? .nan, 0.97, accuracy: 1e-12)
        XCTAssertEqual(CurrencyConversion.stableToUsd("USD", marketUsdt: nil, usdtUsd: 0.97), 1)
        // andere Stablecoins: Marktkurs in USDT × USDT/USD
        XCTAssertEqual(CurrencyConversion.stableToUsd("USDC", marketUsdt: 0.9993, usdtUsd: 0.97) ?? .nan, 0.9993 * 0.97,
                       accuracy: 1e-12)
        // BTC/USDT in CHF: USDT/USD 0.97 senkt den Wert um 3 %
        let rate = CurrencyConversion.rate(quoteUsd: CurrencyConversion.quoteToUsd(.usd, usdToQuote: nil, cryptoUsdt: nil,
                                                                                  code: "USDT", usdtUsd: 0.97),
                                           usdToTarget: 0.8)
        XCTAssertEqual(rate ?? .nan, 0.776, accuracy: 1e-12)
        // Krypto-Quote (über USDT bewertet): × USDT/USD
        XCTAssertEqual(CurrencyConversion.quoteToUsd(.crypto, usdToQuote: nil, cryptoUsdt: 3000, code: "ETH", usdtUsd: 0.97) ?? .nan,
                       3000 * 0.97, accuracy: 1e-9)
        // unplausibler Kurs (Börsenfehler) → 1
        XCTAssertEqual(CurrencyConversion.stableToUsd("USDT", marketUsdt: nil, usdtUsd: 7), 1)
    }

    func testStaleFxTimeAfterThreeHours() {
        // Wie CurrencyConversionTest.kt
        let h: Int64 = 60 * 60_000
        let now = 100 * h
        XCTAssertNil(CurrencyConversion.staleFxTime(quoteFxAt: nil, targetFxAt: nil, now: now))
        XCTAssertNil(CurrencyConversion.staleFxTime(quoteFxAt: nil, targetFxAt: now - 3 * h, now: now))
        XCTAssertNil(CurrencyConversion.staleFxTime(quoteFxAt: now - h, targetFxAt: now - 2 * h, now: now))
        XCTAssertEqual(CurrencyConversion.staleFxTime(quoteFxAt: nil, targetFxAt: now - 4 * h, now: now), now - 4 * h)
        XCTAssertEqual(CurrencyConversion.staleFxTime(quoteFxAt: now - 5 * h, targetFxAt: now - h, now: now), now - 5 * h)
        XCTAssertEqual(CurrencyConversion.fxStaleMillis, 3 * h)
        // Zeile: Hinweis nur für die Quote mit altem Kurs
        var watch = Watch(id: 1, exchangeKey: "Kraken", exchangeName: "Kraken", baseAsset: "BTC", quoteAsset: "EUR")
        watch.lastPrice = 60_000
        XCTAssertEqual(WatchlistConversion.fxTime(watch, fxTimes: ["EUR": now - 4 * h], now: now), now - 4 * h)
        XCTAssertNil(WatchlistConversion.fxTime(watch, fxTimes: ["EUR": now - h], now: now))
        XCTAssertNil(WatchlistConversion.fxTime(watch, fxTimes: ["USDT": now - 4 * h], now: now))
    }

    /// Krypto-/Stablecoin-Kurs für die Umrechnung höchstens 15 min alt, sonst kein Faktor (Alarm und «≈»). Wie Android.
    func testQuotePriceForConversionAtMostFifteenMinutesOld() {
        let min: Int64 = 60_000
        let now = 1_000 * min
        XCTAssertEqual(CurrencyConversion.quoteMaxAgeMillis, 15 * min)
        XCTAssertEqual(CurrencyConversion.freshQuotePrice(60_000, time: now - 15 * min, now: now), 60_000)
        XCTAssertEqual(CurrencyConversion.freshQuotePrice(60_000, time: now, now: now), 60_000)
        XCTAssertNil(CurrencyConversion.freshQuotePrice(60_000, time: now - 15 * min - 1, now: now))
        XCTAssertNil(CurrencyConversion.freshQuotePrice(60_000, time: nil, now: now))
        XCTAssertNil(CurrencyConversion.freshQuotePrice(60_000, time: 0, now: now))
        XCTAssertNil(CurrencyConversion.freshQuotePrice(nil, time: now, now: now))
        // Ohne Kurs kein Faktor (bestehender Weg «keine Umrechnung»)
        let old = CurrencyConversion.freshQuotePrice(60_000, time: now - 16 * min, now: now)
        XCTAssertNil(CurrencyConversion.rate(quoteUsd: CurrencyConversion.quoteToUsd(.crypto, usdToQuote: nil, cryptoUsdt: old),
                                             usdToTarget: 0.8))
    }

    /// BGN aus einem alten EUR-Kurs behält dessen Abrufzeit (Hinweis mit Uhrzeit), wird nie «frisch». Wie Android.
    func testBgnFromEurKeepsTheEurFetchTime() {
        let h: Int64 = 60 * 60_000
        let now = 100 * h
        let eur = (rate: 0.86, at: now - 5 * h)
        let derived = FxRateSource.fxWithTime(own: nil, eur: eur, deriveBgn: true)
        XCTAssertEqual(derived?.rate ?? 0, 0.86 * 1.95583, accuracy: 1e-12)
        XCTAssertEqual(derived?.at, now - 5 * h)
        XCTAssertEqual(CurrencyConversion.staleFxTime(quoteFxAt: nil, targetFxAt: derived?.at, now: now), now - 5 * h)
        let own = FxRateSource.fxWithTime(own: (rate: 1.7, at: now), eur: eur, deriveBgn: true)
        XCTAssertEqual(own?.rate, 1.7)
        XCTAssertEqual(own?.at, now)
        XCTAssertNil(FxRateSource.fxWithTime(own: nil, eur: eur, deriveBgn: false))
        XCTAssertNil(FxRateSource.fxWithTime(own: nil, eur: nil, deriveBgn: true))
    }

    func testOpenBookUsesMarketPriceOfStable() {
        let trades = [buy("USDC", 100, 1), buy("DAI", 10, 1), buy("USDT", 5, 1)]
        let s = PortfolioCalculator.summarize(trades, prices: ["USDC": 0.99])
        XCTAssertEqual(s.open.first { $0.coin == "USDC" }?.value ?? .nan, 99, accuracy: d)
        // DAI ohne Kurs: kein Wert («—», «ohne Kurs»), zählt unter «ohne N Coins»
        let dai = s.open.first { $0.coin == "DAI" }
        XCTAssertNotNil(dai)
        XCTAssertNil(dai?.value)
        XCTAssertNil(dai?.currentPrice)
        XCTAssertNil(dai?.unrealized)
        XCTAssertEqual(s.open.first { $0.coin == "USDT" }?.value ?? .nan, 5, accuracy: d)
        XCTAssertEqual(s.totalValue, 104, accuracy: d)
        XCTAssertEqual(s.missingCurrentPrices, ["DAI"])
        // Gewinn seit Kauf nur über Coins mit Kurs: USDC −1, USDT 0; DAI fehlt (kein angenommenes 1)
        XCTAssertEqual(s.unrealized ?? .nan, -1, accuracy: d)
    }

    func testHistoryUsesStableClosesWithoutFallbackOne() {
        // USDC: Schluss je Tag; DAI ganz ohne Kerzen → fehlt (kein stilles 1), steht unter «ohne …»
        let trades = [buy("USDC", 100, noon(today - 3)), buy("DAI", 10, noon(today - 3))]
        let s = build(trades, ["USDC": [today - 3: 0.97, today - 2: 0.98, today - 1: 0.99]], ["USDC": 0.995])
        XCTAssertEqual(s.skipped, ["DAI"])
        let values = s.points.map(\.value)
        XCTAssertEqual(values.count, 4)
        for (got, want) in zip(values, [97, 98, 99, 99.5]) { XCTAssertEqual(got, want, accuracy: 1e-6) }
    }

    func testCutoffRowsUseStableCloseElseNoPrice() {
        let rows = CutoffExport.rows([CutoffHolding(coin: "USDC", amount: 10), CutoffHolding(coin: "DAI", amount: 5),
                                      CutoffHolding(coin: "USDT", amount: 2)],
                                     prices: ["USDC": dec("0.97"), "USDT": dec("1.2")], fxRate: 1)
        XCTAssertEqual(rows[0].priceUsdt, dec("0.97"))
        XCTAssertEqual(rows[0].valueUsdt, dec("9.7"))
        // DAI ohne Tagesschluss: «ohne Kurs» wie jeder Coin
        XCTAssertNil(rows[1].priceUsdt)
        XCTAssertNil(rows[1].valueUsdt)
        XCTAssertTrue(rows[1].noPrice)
        XCTAssertEqual(rows[2].priceUsdt, 1)
    }

    // MARK: Plausibilität

    func testSameThresholdAsDayChange() {
        XCTAssertEqual(PricePlausibility.maxGap, DayChange.maxPriceGap)
    }

    func testMatchesWithinTwentyFivePercent() {
        XCTAssertTrue(PricePlausibility.matches(125, reference: 100))
        XCTAssertTrue(PricePlausibility.matches(75, reference: 100))
        XCTAssertFalse(PricePlausibility.matches(125.1, reference: 100))
        XCTAssertFalse(PricePlausibility.matches(74.9, reference: 100))
        XCTAssertTrue(PricePlausibility.matches(3, reference: nil))
        XCTAssertTrue(PricePlausibility.matches(3, reference: .nan))
        XCTAssertFalse(PricePlausibility.matches(nil, reference: 100))
        XCTAssertFalse(PricePlausibility.matches(-1, reference: 100))
    }

    func testClosesMatchLiveUsesLatestClose() {
        let closes: [(day: Int, close: Double)] = [(day: 1, close: 10), (day: 2, close: 100)]
        XCTAssertTrue(PricePlausibility.closesMatchLive(closes, live: 110))
        XCTAssertFalse(PricePlausibility.closesMatchLive(closes, live: 10))
        // Ohne aktuellen Kurs ist der Token nicht bestätigt: Reihe zählt nicht
        XCTAssertFalse(PricePlausibility.closesMatchLive(closes, live: nil))
        XCTAssertFalse(PricePlausibility.closesMatchLive(closes, live: .nan))
        XCTAssertFalse(PricePlausibility.closesMatchLive(closes, live: 0))
        XCTAssertTrue(PricePlausibility.closesMatchLive([], live: 5))
        XCTAssertTrue(PricePlausibility.closesMatchLive([], live: nil))
    }

    func testSameTokenWithinFactorThree() {
        XCTAssertTrue(PricePlausibility.sameToken(300, reference: 100))
        XCTAssertTrue(PricePlausibility.sameToken(34, reference: 100))
        XCTAssertTrue(PricePlausibility.sameToken(140, reference: 100))
        XCTAssertFalse(PricePlausibility.sameToken(300.1, reference: 100))
        XCTAssertFalse(PricePlausibility.sameToken(33.3, reference: 100))
        // Streng: ohne gültigen Bezug nicht bestätigt
        XCTAssertFalse(PricePlausibility.sameToken(3, reference: nil))
        XCTAssertFalse(PricePlausibility.sameToken(3, reference: 0))
        XCTAssertFalse(PricePlausibility.sameToken(3, reference: .nan))
        XCTAssertFalse(PricePlausibility.sameToken(3, reference: .infinity))
        XCTAssertFalse(PricePlausibility.sameToken(nil, reference: 100))
        XCTAssertFalse(PricePlausibility.sameToken(nil, reference: nil))
    }

    func testClosesMatchLiveToleratesBigRealMoves() {
        // Echter Tag mit +40 % / −30 %: Reihe bleibt gültig (keine 25-%-Regel mehr)
        let closes: [(day: Int, close: Double)] = [(day: 1, close: 90), (day: 2, close: 100)]
        XCTAssertTrue(PricePlausibility.closesMatchLive(closes, live: 140))
        XCTAssertTrue(PricePlausibility.closesMatchLive(closes, live: 70))
        XCTAssertFalse(PricePlausibility.closesMatchLive(closes, live: 301))
    }

    func testHistoryKeepsCoinAfterThirtyPercentDay() {
        // Zwischengespeicherte Kerzen enden gestern bei 100, jetzt +30 %
        var btc: [Int: Double] = [:]
        for i in 1...5 { btc[today - i] = 100 }
        let s = build([buy("BTC", 1, noon(today - 2))], ["BTC": btc], ["BTC": 130])
        XCTAssertTrue(s.skipped.isEmpty)
        XCTAssertEqual(s.points.first?.value ?? .nan, 100, accuracy: d)
        // heutiger Punkt mit dem aktuellen Kurs
        XCTAssertEqual(s.points.last?.value ?? .nan, 130, accuracy: d)
    }

    func testCutoffSourceCheck() {
        // Binance-Spot: Identitätsprüfung 1/3…3 gegen den aktuellen Kurs (wie Wertverlauf)
        XCTAssertTrue(PricePlausibility.acceptSourceClose(5, primary: true, sourceLatest: 2.5, current: 1))
        XCTAssertFalse(PricePlausibility.acceptSourceClose(5, primary: true, sourceLatest: 3.5, current: 1))
        // Binance-Spot ohne heutigen Kurs der Quelle oder ohne aktuellen Kurs: nicht bestätigt
        XCTAssertFalse(PricePlausibility.acceptSourceClose(5, primary: true, sourceLatest: nil, current: 900))
        XCTAssertFalse(PricePlausibility.acceptSourceClose(5, primary: true, sourceLatest: 1, current: nil))
        // Ausweich-Quelle führt heute denselben Coin (±25 %) … oder nicht
        XCTAssertTrue(PricePlausibility.acceptSourceClose(5, primary: false, sourceLatest: 1.1, current: 1))
        XCTAssertFalse(PricePlausibility.acceptSourceClose(5, primary: false, sourceLatest: 1.5, current: 1))
        XCTAssertFalse(PricePlausibility.acceptSourceClose(5, primary: false, sourceLatest: 40, current: 1))
        XCTAssertFalse(PricePlausibility.acceptSourceClose(5, primary: false, sourceLatest: nil, current: 1))
        // Kein aktueller Kurs bekannt: streng → nicht übernommen (nicht «gilt ungeprüft»)
        XCTAssertFalse(PricePlausibility.acceptSourceClose(5, primary: false, sourceLatest: 1, current: nil))
        XCTAssertFalse(PricePlausibility.acceptSourceClose(5, primary: false, sourceLatest: nil, current: nil))
        XCTAssertFalse(PricePlausibility.acceptSourceClose(5, primary: true, sourceLatest: 1, current: .nan))
        XCTAssertFalse(PricePlausibility.acceptSourceClose(nil, primary: true, sourceLatest: 1, current: 1))
    }

    func testCutoffUncheckedCoinsGetEmptyPriceAndNote() {
        // ETH ungeprüft — auch ein vorhandener Kurs wird nicht verwendet; USDT bleibt 1
        let rows = CutoffExport.rows([CutoffHolding(coin: "BTC", amount: 0.5), CutoffHolding(coin: "ETH", amount: 2),
                                      CutoffHolding(coin: "USDT", amount: 10)],
                                     prices: ["BTC": 100, "ETH": 3000], fxRate: 1, unchecked: ["ETH", "USDT"])
        XCTAssertTrue(rows[1].unchecked)
        XCTAssertFalse(rows[1].noPrice)
        XCTAssertNil(rows[1].priceUsdt)
        XCTAssertFalse(rows[2].unchecked)
        XCTAssertEqual(rows[2].priceUsdt, 1)
        let texts = CutoffCsvTexts(
            coin: "Coin", amount: "Menge", priceUsdt: "Kurs USDT", valueUsdt: "Wert USDT", rate: "Kurs USD→CHF",
            valueTarget: "Wert CHF", note: "Hinweis", total: "Total", noPrice: "kein Kurs", noFx: "kein Devisenkurs",
            unchecked: "ungeprüft", incomplete: "unvollständig", commentFormat: "# %@ %@ %@"
        )
        let csv = CutoffExport.csv(day: CutoffDay(year: 2025, month: 12, day: 31), currency: "CHF",
                                   fxDate: "2025-12-31", rows: rows, texts: texts)
        let lines = csv.replacingOccurrences(of: "\u{FEFF}", with: "").components(separatedBy: "\r\n")
        XCTAssertEqual(lines[3], "ETH;2;;;1;;ungeprüft")
        XCTAssertEqual(lines[5], "Total;;;60.00;1;60.00;unvollständig")
    }

    func testCutoffCsvFormatPerLocale() {
        // de-CH: Excel trennt mit «;», Dezimalpunkt; fr-CH mit Komma
        XCTAssertEqual(CutoffCsvFormat.forLocale(region: "CH", decimalSeparator: "."), CutoffCsvFormat(separator: ";", decimal: "."))
        XCTAssertEqual(CutoffCsvFormat.forLocale(region: "li", decimalSeparator: "."), CutoffCsvFormat(separator: ";", decimal: "."))
        XCTAssertEqual(CutoffCsvFormat.forLocale(region: "CH", decimalSeparator: ","), CutoffCsvFormat(separator: ";", decimal: ","))
        // de-DE: Dezimalkomma → «;» + «,»; en-US u. a.: «,» + «.»
        XCTAssertEqual(CutoffCsvFormat.forLocale(region: "DE", decimalSeparator: ","), CutoffCsvFormat(separator: ";", decimal: ","))
        XCTAssertEqual(CutoffCsvFormat.forLocale(region: "US", decimalSeparator: "."), CutoffCsvFormat(separator: ",", decimal: "."))
        XCTAssertEqual(CutoffCsvFormat.forLocale(region: "", decimalSeparator: "٫"), CutoffCsvFormat(separator: ",", decimal: "."))
    }

    func testCutoffCsvDecimalCommaAndCommaSeparator() {
        let texts = CutoffCsvTexts(
            coin: "Coin", amount: "Menge", priceUsdt: "Kurs USDT", valueUsdt: "Wert USDT", rate: "Kurs USD→CHF",
            valueTarget: "Wert CHF", note: "Hinweis", total: "Total", noPrice: "kein Kurs", noFx: "kein Devisenkurs",
            unchecked: "ungeprüft", incomplete: "unvollständig", commentFormat: "# %@, %@, %@"
        )
        let day = CutoffDay(year: 2025, month: 12, day: 31)
        let holdings = [CutoffHolding(coin: "BTC", amount: 0.5), CutoffHolding(coin: "FOO", amount: 3)]
        let rows = CutoffExport.rows(holdings, prices: ["BTC": dec("87654.321")], fxRate: dec("0.79123"))
        let comma = CutoffExport.csv(day: day, currency: "EUR", fxDate: "2025-12-31", rows: rows, texts: texts,
                                     format: CutoffCsvFormat(separator: ";", decimal: ","))
            .replacingOccurrences(of: "\u{FEFF}", with: "").components(separatedBy: "\r\n")
        XCTAssertEqual(comma[2], "BTC;0,5;87654,321;43827,16;0,79123;34677,36;")
        XCTAssertEqual(comma[3], "FOO;3;;;0,79123;;kein Kurs")
        XCTAssertEqual(comma[4], "Total;;;43827,16;0,79123;34677,36;unvollständig")

        let rowsNoFx = CutoffExport.rows(holdings, prices: ["BTC": dec("87654.321")], fxRate: nil)
        let point = CutoffExport.csv(day: day, currency: "USD", fxDate: nil, rows: rowsNoFx, texts: texts,
                                     format: CutoffCsvFormat(separator: ",", decimal: "."))
            .replacingOccurrences(of: "\u{FEFF}", with: "").components(separatedBy: "\r\n")
        XCTAssertEqual(point[0], "\"# 2025-12-31, USD, —\"")
        XCTAssertEqual(point[1], "Coin,Menge,Kurs USDT,Wert USDT,Kurs USD→CHF,Wert CHF,Hinweis")
        XCTAssertEqual(point[2], "BTC,0.5,87654.321,43827.16,,,kein Devisenkurs")
        XCTAssertEqual(point[3], "FOO,3,,,,,\"kein Kurs, kein Devisenkurs\"")
        XCTAssertEqual(CutoffExport.escape("a,b", separator: ","), "\"a,b\"")
        XCTAssertEqual(CutoffExport.escape("a;b", separator: ","), "a;b")
    }

    func testCutoffExactWhereDoubleWouldBeWrong() {
        // 0.1 + 0.2 aus der Buchhaltung: genau 0.3 (Double: 0.30000000000000004)
        let trades = [
            PortfolioTx(id: 1, coin: "ETH", type: .BUY, amountExact: dec("0.1"), priceExact: 1, time: 1),
            PortfolioTx(id: 2, coin: "ETH", type: .BUY, amountExact: dec("0.2"), priceExact: 1, time: 2),
            PortfolioTx(id: 3, coin: "SOL", type: .BUY, amountExact: dec("0.000000000000000001"), priceExact: 1, time: 3),
        ]
        let h = CutoffExport.holdingsAt(trades, cutoffMillis: 10)
        XCTAssertEqual(h.map(\.coin), ["ETH", "SOL"])
        XCTAssertEqual(CutoffExport.amount(h[0].amount), "0.3")
        XCTAssertEqual(CutoffExport.amount(h[1].amount), "0.000000000000000001")
        // 0.075 × 3 = 0.225 → «0.23» (Double: 0.22499999999999998 → «0.22»)
        let rows = CutoffExport.rows([CutoffHolding(coin: "ETH", amount: dec("0.075")),
                                      CutoffHolding(coin: "BTC", amount: dec("123456789.123456789"))],
                                     prices: ["ETH": 3, "BTC": dec("12345.6789")], fxRate: dec("0.79123"))
        XCTAssertEqual(rows[0].valueUsdt, dec("0.225"))
        XCTAssertEqual(CutoffExport.money(rows[0].valueUsdt ?? 0), "0.23")
        XCTAssertEqual(rows[1].valueUsdt, dec("1524157876543.2099750190521"))
        XCTAssertEqual(rows[1].valueTarget, dec("1205959436657.284028534324593083"))
        // Kurs aus einem Double: über die kürzeste Darstellung, nicht über den Binärwert
        XCTAssertEqual(PortfolioDecimal.of(0.1), dec("0.1"))
        XCTAssertEqual(CutoffExport.amount(dec("1000000000.000000001")), "1000000000.000000001")
        XCTAssertEqual(CutoffExport.price(dec("3E-11")), "0.00000000003")
        XCTAssertEqual(CutoffExport.money(dec("-0.001")), "0.00")
        XCTAssertEqual(CutoffExport.money(dec("45000")), "45000.00")
    }

    func testCutoffReferencePricesSkipUncheckedAndInvalid() {
        let ref = CutoffExport.referencePrices(["BTC": 100, "FOO": 2, "BAR": 0, "BAZ": .nan], uncheckedCurrent: ["FOO"])
        XCTAssertEqual(ref, ["BTC": 100])
        // Laden gescheitert → leer → alle ungeprüft
        XCTAssertTrue(CutoffExport.referencePrices([:], uncheckedCurrent: []).isEmpty)
    }

    func testHistoryDropsImplausibleSeries() {
        let trades = [buy("BTC", 1, noon(today - 2)), buy("FOO", 10, noon(today - 2))]
        var btc: [Int: Double] = [:]
        var foo: [Int: Double] = [:]
        for i in 0...5 {
            btc[today - i] = 100
            foo[today - i] = 50
        }
        let s = build(trades, ["BTC": btc, "FOO": foo], ["BTC": 101, "FOO": 1])
        XCTAssertEqual(s.skipped, ["FOO"])
        XCTAssertEqual(s.points.first?.value ?? .nan, 100, accuracy: d)
        XCTAssertEqual(s.points.last?.value ?? .nan, 101, accuracy: d)
    }

    func testHistoryStableWithImplausibleClosesIsSkipped() {
        // Kein stilles 1 mehr: passt die Reihe nicht, fehlt auch ein Stablecoin (ausser USDT)
        let s = build([buy("USDC", 10, noon(today - 1))], ["USDC": [today - 1: 7, today: 7]], ["USDC": 1])
        XCTAssertEqual(s.skipped, ["USDC"])
        XCTAssertTrue(s.points.isEmpty)
    }

    func testHistoryHeldCoinWithoutLivePriceIsSkipped() {
        // Wie PortfolioHistoryTest.kt: strenge Identitätsprüfung — ohne aktuellen Kurs «ohne ETH»
        var linear: [Int: Double] = [:]
        for i in 0..<10 { linear[today - i] = 100 - Double(i) }
        let s = build([buy("ETH", 1, noon(today - 500)), buy("BTC", 1, noon(today - 500))],
                      ["ETH": linear, "BTC": linear], ["BTC": 100])
        XCTAssertEqual(s.skipped, ["ETH"])
        XCTAssertEqual(s.points.first?.value ?? .nan, 93, accuracy: d)
        XCTAssertEqual(s.points.last?.value ?? .nan, 100, accuracy: d)
        let alone = build([buy("ETH", 1, noon(today - 500))], ["ETH": linear], [:])
        XCTAssertEqual(alone.skipped, ["ETH"])
        XCTAssertTrue(alone.points.isEmpty)
    }

    func testHistorySoldCoinWithoutLivePriceStillCounts() {
        // Heute kein Bestand: kein aktueller Kurs zum Vergleichen, die Reihe bleibt (heute 0)
        var linear: [Int: Double] = [:]
        for i in 0..<10 { linear[today - i] = 100 - Double(i) }
        let sell = PortfolioTx(id: 999, coin: "XYZ", type: .SELL, amount: 1, priceUsdt: 1, time: noon(today - 2))
        let s = build([buy("XYZ", 1, noon(today - 100)), sell, buy("BTC", 1, noon(today - 100))],
                      ["XYZ": linear, "BTC": linear], ["BTC": 100])
        XCTAssertTrue(s.skipped.isEmpty)
        XCTAssertEqual(s.points.first?.value ?? .nan, 186, accuracy: d)
        XCTAssertEqual(s.points.last?.value ?? .nan, 100, accuracy: d)
    }

    func testCandleReferencePrefersWatchlistMedianElseStored() {
        // Wie PricePlausibilityTest.kt
        XCTAssertEqual(PricePlausibility.candleReference(watchPrices: [3, 1, 2, .nan, -1], stored: 50), 2)
        XCTAssertEqual(PricePlausibility.candleReference(watchPrices: [1, 2], stored: nil), 1.5)
        XCTAssertEqual(PricePlausibility.candleReference(watchPrices: [], stored: 50), 50)
        XCTAssertNil(PricePlausibility.candleReference(watchPrices: [], stored: nil))
        XCTAssertNil(PricePlausibility.candleReference(watchPrices: [0], stored: .nan))
    }

    func testCandlePriceNeedsSameTokenAsReference() {
        XCTAssertFalse(PricePlausibility.acceptCandle(0.002, reference: 1))
        XCTAssertFalse(PricePlausibility.acceptCandle(3.01, reference: 1))
        XCTAssertTrue(PricePlausibility.acceptCandle(2.9, reference: 1))
        XCTAssertTrue(PricePlausibility.acceptCandle(0.34, reference: 1))
        // Ohne Bezug: NICHT übernommen — ungeprüft zählt nicht als Geld (wie der Stichtag-Export)
        XCTAssertFalse(PricePlausibility.acceptCandle(0.002, reference: nil))
        XCTAssertFalse(PricePlausibility.acceptCandle(50, reference: .nan))
        XCTAssertFalse(PricePlausibility.acceptCandle(nil, reference: 1))
        XCTAssertFalse(PricePlausibility.acceptCandle(.nan, reference: nil))
    }

    func testCandleWithoutReferenceIsUnchecked() {
        // Wie PricePlausibilityTest.kt: gültige Kerze ohne Bezug → «ungeprüft» (Coin ohne Kurs)
        XCTAssertTrue(PricePlausibility.candleUnchecked(0.002, reference: nil))
        XCTAssertTrue(PricePlausibility.candleUnchecked(0.002, reference: 0))
        XCTAssertFalse(PricePlausibility.candleUnchecked(0.002, reference: 1))
        XCTAssertFalse(PricePlausibility.candleUnchecked(2, reference: 1))
        XCTAssertFalse(PricePlausibility.candleUnchecked(nil, reference: nil))
        XCTAssertFalse(PricePlausibility.candleUnchecked(-1, reference: nil))
    }

    // MARK: Devisen-Tageskurse

    private func day(_ iso: String) -> Int { LocalDay(iso: iso)!.epochDay }

    private var flatUsd: PortfolioHistorySeries {
        PortfolioHistorySeries(
            points: ["2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05", "2026-10-06"]
                .map { PortfolioHistoryPoint(epochDay: day($0), value: 1000) },
            skipped: [], change: 0, changePercent: 0, tradesInRange: false)
    }

    private var rates: [Int: Double] {
        [day("2026-10-01"): 0.79, day("2026-10-02"): 0.80, day("2026-10-05"): 0.81]
    }

    func testWeekendsCarryPreviousBusinessDay() {
        let sorted = rates.sorted { $0.key < $1.key }.map { (day: $0.key, rate: $0.value) }
        XCTAssertEqual(PortfolioHistoryFx.rateOn(sorted, day: day("2026-10-03")), 0.80)
        XCTAssertEqual(PortfolioHistoryFx.rateOn(sorted, day: day("2026-10-04")), 0.80)
        XCTAssertEqual(PortfolioHistoryFx.rateOn(sorted, day: day("2026-10-05")), 0.81)
        XCTAssertNil(PortfolioHistoryFx.rateOn(sorted, day: day("2026-09-30")))
    }

    func testFlatUsdShowsCurrencyMove() {
        let r = PortfolioHistoryFx.convert(flatUsd, dailyRates: rates, currentRate: 0.82, todayEpochDay: today)
        XCTAssertFalse(r.approximate)
        for (got, want) in zip(r.series.points.map(\.value), [800.0, 800, 800, 810, 820]) {
            XCTAssertEqual(got, want, accuracy: 1e-6)
        }
        XCTAssertEqual(r.series.change ?? .nan, 20, accuracy: 1e-6)
        XCTAssertEqual(r.series.changePercent ?? .nan, 2.5, accuracy: 1e-6)
    }

    func testMissingSeriesFallsBackToTodayAndFlags() {
        for missing in [nil, [:]] as [[Int: Double]?] {
            let r = PortfolioHistoryFx.convert(flatUsd, dailyRates: missing, currentRate: 0.82, todayEpochDay: today)
            XCTAssertTrue(r.approximate)
            XCTAssertTrue(r.series.points.allSatisfy { abs($0.value - 820) < 1e-6 })
            XCTAssertEqual(r.series.change ?? .nan, 0, accuracy: 1e-6)
        }
    }

    func testDayBeforeFirstRateUsesFirst() {
        let r = PortfolioHistoryFx.convert(flatUsd, dailyRates: [day("2026-10-05"): 0.81], currentRate: 0.82,
                                           todayEpochDay: today)
        XCTAssertEqual(r.series.points.first?.value ?? .nan, 810, accuracy: 1e-6)
    }

    func testRatesByDayParsesAndDerivesBgn() {
        let byDate: [String: [String: Double]] = [
            "2025-12-31": ["BGN": 1.70, "EUR": 0.86],
            "2026-01-02": ["EUR": 0.85],
            "kaputt": ["BGN": 1.0],
            "2026-01-05": ["BGN": -1.0],
        ]
        let bgn = PortfolioHistoryFx.ratesByDay("bgn", byDate: byDate)
        XCTAssertEqual(bgn[day("2025-12-31")] ?? .nan, 1.70, accuracy: d)
        XCTAssertEqual(bgn[day("2026-01-02")] ?? .nan, 0.85 * 1.95583, accuracy: d)
        XCTAssertNil(bgn[day("2026-01-05")])
        XCTAssertEqual(PortfolioHistoryFx.ratesByDay("EUR", byDate: byDate).count, 2)
        XCTAssertEqual(PortfolioHistoryFx.requestCurrencies("BGN"), ["BGN", "EUR"])
        XCTAssertEqual(PortfolioHistoryFx.requestCurrencies(" chf "), ["CHF"])
    }

    func testRequestStartsLeadDaysEarlier() {
        let range = PortfolioHistoryFx.requestRange(days: 30, today: LocalDay(2026, 10, 6))
        XCTAssertEqual(range.from, LocalDay(2026, 8, 30))
        XCTAssertEqual(range.to, LocalDay(2026, 10, 6))
    }

    /// Devisen-Zeitreihe in Stücken von höchstens 365 Tagen (Frankfurter liefert längere Zeiträume nur wöchentlich).
    /// Wie Android `fxRequestInChunksOfAtMostOneYear`.
    func testFxRequestInChunksOfAtMostOneYear() {
        let from = LocalDay(2023, 1, 1)
        let to = LocalDay(2026, 10, 6)
        let chunks = PortfolioHistoryFx.fxChunks(from: from, to: to)
        XCTAssertEqual(PortfolioHistoryFx.fxChunkDays, 365)
        XCTAssertEqual(chunks.first?.from, from)
        XCTAssertEqual(chunks.last?.to, to)
        for c in chunks { XCTAssertLessThanOrEqual(c.to.epochDay - c.from.epochDay + 1, 365) }
        for i in 1..<chunks.count { XCTAssertEqual(chunks[i - 1].to.plusDays(1), chunks[i].from) }
        XCTAssertEqual(chunks.count, 4)
        let year = PortfolioHistoryFx.fxChunks(from: from, to: from.plusDays(364))
        XCTAssertEqual(year.count, 1)
        XCTAssertEqual(year.first?.to, from.plusDays(364))
        XCTAssertEqual(PortfolioHistoryFx.fxChunks(from: from, to: from.plusDays(365)).count, 2)
        XCTAssertEqual(PortfolioHistoryFx.fxChunks(from: to, to: to).count, 1)
        XCTAssertTrue(PortfolioHistoryFx.fxChunks(from: to, to: from).isEmpty)
    }

    /// Gleich lange Stücke: kein Rest von ein, zwei Tagen (Wochenende/heute ohne EZB-Kurs liesse die ganze Reihe
    /// scheitern). Wie Android `fxChunksAreEqualParts`.
    func testFxChunksAreEqualParts() {
        func lengths(_ from: LocalDay, _ to: LocalDay) -> [Int] {
            PortfolioHistoryFx.fxChunks(from: from, to: to).map { $0.to.epochDay - $0.from.epochDay + 1 }
        }
        let from = LocalDay(2023, 1, 1)
        // 1375 Tage → 4 Stücke 344/344/344/343 (vorher 365/365/365/280)
        XCTAssertEqual(lengths(from, LocalDay(2026, 10, 6)), [344, 344, 344, 343])
        // 367 Tage → 184/183 statt 365 + 2
        XCTAssertEqual(lengths(LocalDay(2025, 10, 5), LocalDay(2026, 10, 6)), [184, 183])
        XCTAssertEqual(lengths(from, from.plusDays(365)), [183, 183])
        XCTAssertEqual(lengths(from, from.plusDays(729)), [365, 365])
        XCTAssertEqual(lengths(from, from.plusDays(730)), [244, 244, 243])
    }

    /// Geladene Devisenkurse gelten bis zum nächsten UTC-Tag; die Abfrage endet am späteren von Geräte- und UTC-Tag. Wie
    /// Android `fxLoadOutdatedOnNewUtcDay`.
    func testFxLoadOutdatedOnNewUtcDay() {
        let day = LocalDay(2026, 10, 18)
        XCTAssertFalse(PortfolioHistoryFx.fxLoadOutdated(loadedTo: day, utcToday: day))
        XCTAssertTrue(PortfolioHistoryFx.fxLoadOutdated(loadedTo: day, utcToday: day.plusDays(1)))
        XCTAssertFalse(PortfolioHistoryFx.fxLoadOutdated(loadedTo: day.plusDays(1), utcToday: day))
        // Westlich von UTC am Abend: Gerätetag noch gestern, UTC schon heute → bis heute abfragen
        XCTAssertEqual(PortfolioHistoryFx.fxRequestEnd(localToday: day.plusDays(-1), utcToday: day), day)
        XCTAssertEqual(PortfolioHistoryFx.fxRequestEnd(localToday: day.plusDays(1), utcToday: day), day.plusDays(1))
    }

    /// Stück ohne Werktag (nur Sa/So bzw. nur heute): Fehlantwort zählt als leer. Wie Android `chunkWithoutBusinessDayMayFail`.
    func testChunkWithoutBusinessDayMayFail() {
        let fri = LocalDay(2026, 10, 16)
        let sat = fri.plusDays(1)
        let sun = fri.plusDays(2)
        let mon = fri.plusDays(3)
        XCTAssertFalse(PortfolioHistoryFx.chunkMayHaveRates(from: sat, to: sun, today: sun))
        XCTAssertFalse(PortfolioHistoryFx.chunkMayHaveRates(from: sat, to: mon, today: mon))
        XCTAssertFalse(PortfolioHistoryFx.chunkMayHaveRates(from: fri, to: fri, today: fri))
        XCTAssertTrue(PortfolioHistoryFx.chunkMayHaveRates(from: mon, to: mon, today: sun))
        XCTAssertTrue(PortfolioHistoryFx.chunkMayHaveRates(from: fri, to: sat, today: sat))
        XCTAssertFalse(PortfolioHistoryFx.chunkMayHaveRates(from: sun, to: sat, today: sun))
    }

    /// USDT zum Marktkurs: jeder Tag × USDT/USD-Schluss DES Tags, heute × Live-Kurs; ein Tag
    /// ohne Schluss rechnet mit 1 und markiert die Näherung (`usdtApproximate`). Wie Android.
    func testUsdtAtMarketScalesEachDayAndFlagsMissingClose() {
        let usdt = UsdtUsdRates(live: 0.98, daily: [day("2026-10-02"): 0.97, day("2026-10-03"): 0.97, day("2026-10-04"): 0.97])
        let r = PortfolioHistoryFx.convert(flatUsd, dailyRates: rates, currentRate: 0.82, todayEpochDay: today, usdt: usdt)
        XCTAssertFalse(r.approximate)
        XCTAssertTrue(r.usdtApproximate)
        // 800 × 0.97 (Fr–So), 810 × 1 (Mo ohne Schluss: Näherung), heute 820 × 0.98
        for (got, want) in zip(r.series.points.map(\.value), [776.0, 776, 776, 810, 803.6]) {
            XCTAssertEqual(got, want, accuracy: 1e-6)
        }
    }

    func testUsdtAt097LowersUsdValueByThreePercentWithAllCloses() {
        var daily: [Int: Double] = [:]
        for d in day("2026-10-02")...day("2026-10-05") { daily[d] = 0.97 }
        let usdt = UsdtUsdRates(live: 0.97, daily: daily)
        let usd = PortfolioHistoryFx.usdParity(fromEpochDay: day("2026-10-02"), toEpochDay: today)
        let r = PortfolioHistoryFx.convert(flatUsd, dailyRates: usd, currentRate: 1, todayEpochDay: today, usdt: usdt)
        XCTAssertFalse(r.approximate)
        XCTAssertFalse(r.usdtApproximate)
        XCTAssertTrue(r.series.points.allSatisfy { abs($0.value - 970) < 1e-6 })
        // In USD wird mit USDT zum Marktkurs umgerechnet, in USDT nie
        XCTAssertTrue(PortfolioHistoryFx.convertsUsdt("USD", usdtMarket: true))
        XCTAssertFalse(PortfolioHistoryFx.convertsUsdt("USD", usdtMarket: false))
        XCTAssertFalse(PortfolioHistoryFx.convertsUsdt(" usdt ", usdtMarket: true))
        XCTAssertTrue(PortfolioHistoryFx.convertsUsdt("CHF", usdtMarket: false))
    }

    func testOfflineUsdtFallsBackToOneAndFlags() {
        let r = PortfolioHistoryFx.convert(flatUsd, dailyRates: rates, currentRate: 0.82, todayEpochDay: today,
                                           usdt: UsdtUsdRates(live: nil))
        XCTAssertTrue(r.usdtApproximate)
        // Werte bleiben (nie leer): wie mit USDT = 1
        for (got, want) in zip(r.series.points.map(\.value), [800.0, 800, 800, 810, 820]) {
            XCTAssertEqual(got, want, accuracy: 1e-6)
        }
        // Ohne USDT/USD-Kurse (USDT = 1 USD) keine Markierung
        XCTAssertFalse(PortfolioHistoryFx.convert(flatUsd, dailyRates: rates, currentRate: 0.82,
                                                  todayEpochDay: today).usdtApproximate)
    }

    /// «7 T» im Kopf rechnet mit denselben USDT/USD-Tageskursen wie die Kurve (`rateFunction` `usdt`).
    func testRateFunctionMultipliesUsdtUsdOfTheDay() {
        let usdt = UsdtUsdRates(live: 0.98, daily: [day("2026-10-02"): 0.97])
        let f = PortfolioHistoryFx.rateFunction(dailyRates: rates, currentRate: 0.82, todayEpochDay: today, usdt: usdt)
        XCTAssertEqual(f(day("2026-10-02")), 0.80 * 0.97, accuracy: d)
        XCTAssertEqual(f(day("2026-10-03")), 0.80, accuracy: d)
        XCTAssertEqual(f(today), 0.82 * 0.98, accuracy: d)
    }

    // MARK: Wertverlauf ohne Käufe/Verkäufe (zugeklappte Zahl) — wie PortfolioHistoryTest.kt

    private func linearCloses(_ days: Int) -> [Int: Double] {
        var out: [Int: Double] = [:]
        for i in 0..<days { out[today - i] = 100 - Double(i) }
        return out
    }

    func testNetChangeTakesOutBuysInRange() throws {
        let trades = [
            PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 50, time: noon(today - 500)),
            PortfolioTx(id: 2, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 97, time: noon(today - 3),
                        fee: 1, feeUnit: .QUOTE),
        ]
        let s = build(trades, ["BTC": linearCloses(400)], ["BTC": 110])
        XCTAssertTrue(s.tradesInRange)
        // Brutto: 93 → 220 (samt Kauf); netto: Kauf zu 97 + 1 USDT Gebühr herausgerechnet
        XCTAssertEqual(try XCTUnwrap(s.change), 127, accuracy: d)
        XCTAssertEqual(s.cashFlows, [PortfolioCashFlow(epochDay: today - 3, amount: 98)])
        XCTAssertEqual(try XCTUnwrap(s.netChange), 29, accuracy: d)
        XCTAssertEqual(try XCTUnwrap(s.netChangePercent), 29.0 / (93.0 + 98.0) * 100.0, accuracy: d)

        // Devisen-Tageskurse: Zufluss mit dem Kurs seines Tags
        var dayRates: [Int: Double] = [:]
        for day in (today - 10)..<today { dayRates[day] = day == today - 3 ? 3 : 2 }
        let fx = PortfolioHistoryFx.convert(s, dailyRates: dayRates, currentRate: 2, todayEpochDay: today).series
        XCTAssertEqual(fx.cashFlows, [PortfolioCashFlow(epochDay: today - 3, amount: 294)])
        XCTAssertEqual(try XCTUnwrap(fx.netChange), 440 - 186 - 294, accuracy: d)
    }

    func testNetChangeUnknownWithUnpricedTradeAndEqualWithoutTrades() throws {
        let unpriced = [
            buy("BTC", 1, noon(today - 500)),
            PortfolioTx(id: 99, coin: "BTC", type: .BUY, amount: 1, priceUsdt: nil, time: noon(today - 3)),
        ]
        let s = build(unpriced, ["BTC": linearCloses(400)], ["BTC": 110])
        XCTAssertTrue(s.tradesInRange)
        XCTAssertNil(s.cashFlows)
        XCTAssertNil(s.netChange)
        // Ohne Transaktion im Zeitraum: netto = brutto
        let plain = build([buy("BTC", 1, noon(today - 500))], ["BTC": linearCloses(400)], ["BTC": 110])
        XCTAssertEqual(try XCTUnwrap(plain.netChange), try XCTUnwrap(plain.change), accuracy: d)
        XCTAssertEqual(try XCTUnwrap(plain.netChangePercent), try XCTUnwrap(plain.changePercent), accuracy: d)
    }

    // MARK: Gewinn/Verlust-Kurve — wie PortfolioHistoryTest.kt

    private func build(_ trades: [PortfolioTx], range: PortfolioHistoryRange, _ closes: [String: [Int: Double]],
                       _ live: [String: Double], addresses: [AddressBalanceHistory] = []) -> PortfolioHistorySeries {
        PortfolioHistory.build(trades: trades, closes: closes, livePrices: live, range: range,
                               todayEpochDay: today,
                               addresses: addresses)
    }

    private func pnlByDay(_ s: PortfolioHistorySeries) -> [Int: Double] {
        var out: [Int: Double] = [:]
        for p in s.pnlPoints { out[p.epochDay] = p.value }
        return out
    }

    func testPnlCurveIgnoresBuysAndStartsAtZeroInRange() throws {
        let trades = [
            PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 50, time: noon(today - 500)),
            PortfolioTx(id: 2, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 97, time: noon(today - 3),
                        fee: 1, feeUnit: .QUOTE),
        ]
        let s = build(trades, ["BTC": linearCloses(400)], ["BTC": 110])
        XCTAssertTrue(s.hasPnlChart)
        XCTAssertTrue(s.pnlSkipped.isEmpty)
        let pnl = pnlByDay(s)
        // Ab 0 am ersten Tag (93); der Kauf (97 + 1 Gebühr) verschiebt die Kurve nicht, nur der Kurs
        XCTAssertEqual(try XCTUnwrap(pnl[today - 7]), 0, accuracy: d)
        XCTAssertEqual(try XCTUnwrap(pnl[today - 4]), 3, accuracy: d)
        XCTAssertEqual(try XCTUnwrap(pnl[today - 3]), 194 - 93 - 98, accuracy: d)
        XCTAssertEqual(try XCTUnwrap(pnl[today]), 220 - 93 - 98, accuracy: d)
        XCTAssertEqual(try XCTUnwrap(s.pnlChange), 29, accuracy: d)
        XCTAssertEqual(try XCTUnwrap(s.pnlCapital), 191, accuracy: d)
        XCTAssertEqual(try XCTUnwrap(s.pnlPercent), 29.0 / 191.0 * 100.0, accuracy: d)

        // Devisen-Tageskurse: Werte und Zuflüsse je mit dem Kurs ihres Tags
        var dayRates: [Int: Double] = [:]
        for day in (today - 10)..<today { dayRates[day] = day == today - 3 ? 3 : 2 }
        let fx = PortfolioHistoryFx.convert(s, dailyRates: dayRates, currentRate: 2, todayEpochDay: today).series
        XCTAssertEqual(try XCTUnwrap(fx.pnlChange), 440 - 186 - 294, accuracy: d)
    }

    func testPnlSinceFirstIsValueMinusNetInvested() throws {
        let trades = [
            PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 95, time: noon(today - 5),
                        fee: 1, feeUnit: .QUOTE),
            PortfolioTx(id: 2, coin: "BTC", type: .SELL, amount: 0.5, priceUsdt: 98, time: noon(today - 2)),
        ]
        let s = build(trades, range: .sinceFirst, ["BTC": linearCloses(400)], ["BTC": 110])
        XCTAssertTrue(s.pnlSinceFirst)
        let pnl = pnlByDay(s)
        // Netto investiert: 96 (Kauf samt Gebühr), nach dem Verkauf 96 − 49 = 47
        XCTAssertEqual(try XCTUnwrap(pnl[today - 5]), 95 - 96, accuracy: d)
        XCTAssertEqual(try XCTUnwrap(pnl[today - 2]), 49 - 47, accuracy: d)
        XCTAssertEqual(try XCTUnwrap(s.pnlChange), 55 - 47, accuracy: d)
        // Basis: alles eingesetzte Geld (96), der Verkauf wird nicht abgezogen
        XCTAssertEqual(try XCTUnwrap(s.pnlCapital), 96, accuracy: d)
        XCTAssertEqual(try XCTUnwrap(s.pnlPercent), 8.0 / 96.0 * 100.0, accuracy: d)
    }

    func testPnlPercentBaseIgnoresSales() throws {
        // Mehr verkauft als investiert: die Basis bleibt das eingesetzte Geld (10), nie ~0
        let trades = [
            PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 10, time: noon(today - 5)),
            PortfolioTx(id: 2, coin: "BTC", type: .SELL, amount: 0.5, priceUsdt: 98, time: noon(today - 2)),
        ]
        let s = build(trades, range: .sinceFirst, ["BTC": linearCloses(400)], ["BTC": 110])
        XCTAssertEqual(try XCTUnwrap(s.pnlCapital), 10, accuracy: d)
        XCTAssertEqual(try XCTUnwrap(s.pnlChange), 55 + 39, accuracy: d)
        XCTAssertEqual(try XCTUnwrap(s.pnlPercent), 94.0 / 10.0 * 100.0, accuracy: d)
        // Adress-Guthaben vor dem Zeitraum beobachtet, ohne Zugang: Basis = Wert am ersten Tag
        let held = build([], range: .week, ["BTC": linearCloses(400)], ["BTC": 110],
                         addresses: [observed((noon(today - 20), "1"))])
        XCTAssertEqual(try XCTUnwrap(held.pnlCapital), 93, accuracy: d)
    }

    private func observed(_ points: (Int64, String)...) -> AddressBalanceHistory {
        AddressBalanceHistory(chain: .BTC, address: "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
                              observations: points.map { AddressObservation(time: $0.0, balance: Decimal(string: $0.1) ?? 0) })
    }

    func testPnlAddressBalanceCountsOnlyPriceMoves() throws {
        // Beobachtetes Guthaben: erste Beobachtung = Zufluss zum Marktkurs (2 × 95), Zuwachs ebenso (1 × 98)
        let closes = ["BTC": linearCloses(400)]
        let s = build([], range: .sinceFirst, closes, ["BTC": 110],
                      addresses: [observed((noon(today - 5), "2"), (noon(today - 2), "3"))])
        XCTAssertTrue(s.pnlSkipped.isEmpty)
        XCTAssertEqual(s.points.first?.epochDay, today - 5)
        XCTAssertEqual(try XCTUnwrap(s.points.last?.value), 330, accuracy: d)
        let firstPnl: Double = 2 * (110 - 95)
        let secondPnl: Double = 1 * (110 - 98)
        let expectedPnl: Double = firstPnl + secondPnl
        XCTAssertEqual(try XCTUnwrap(s.pnlChange), expectedPnl, accuracy: d)
        // Im Zeitraum (vor dem Zeitraum beobachtet): Guthaben × Kursänderung
        let week = build([], range: .week, closes, ["BTC": 110], addresses: [observed((noon(today - 20), "2"))])
        XCTAssertEqual(try XCTUnwrap(week.pnlChange), 2 * (110 - 93), accuracy: d)
        // Zusammengeführte Adress-Einzahlung (`AddressBalance.withAddresses`, negative Id) ohne beobachtete Reihe:
        // nie rückwirkend — zählt gar nicht
        let merged = build([buy("BTC", 1, noon(today - 5)),
                            PortfolioTx(id: -1, coin: "BTC", type: .DEPOSIT, amount: 2, priceUsdt: nil, time: noon(today - 20))],
                           closes, ["BTC": 110])
        XCTAssertEqual(try XCTUnwrap(merged.points.last?.value), 110, accuracy: d)
    }

    func testWithdrawalWithoutPriceIsValuedAtTheMarket() throws {
        // Flacher Kurs: eine Auszahlung ohne Preis darf keinen Gewinn/Verlust erzeugen
        var flat: [Int: Double] = [:]
        for i in 0..<40 { flat[today - i] = 20 }
        let trades = [
            PortfolioTx(id: 1, coin: "SOL", type: .BUY, amount: 2, priceUsdt: 10, time: noon(today - 20)),
            PortfolioTx(id: 2, coin: "SOL", type: .WITHDRAWAL, amount: 1, priceUsdt: nil, time: noon(today - 3)),
        ]
        let week = build(trades, ["SOL": flat], ["SOL": 20])
        XCTAssertTrue(week.pnlSkipped.isEmpty)
        XCTAssertEqual(try XCTUnwrap(week.pnlChange), 0, accuracy: d)
        XCTAssertEqual(try XCTUnwrap(week.netChange), 0, accuracy: d)
    }

    func testPnlLeavesOutCoinWithUnpricedTradeAndNamesIt() throws {
        let trades = [
            buy("BTC", 1, noon(today - 500)),
            buy("ETH", 1, noon(today - 500)),
            PortfolioTx(id: 99, coin: "ETH", type: .BUY, amount: 1, priceUsdt: nil, time: noon(today - 3)),
        ]
        let s = build(trades, ["BTC": linearCloses(400), "ETH": linearCloses(400)], ["BTC": 110, "ETH": 110])
        XCTAssertEqual(s.pnlSkipped, ["ETH"])
        // Wertkurve mit beiden Coins, Gewinn/Verlust nur BTC
        XCTAssertEqual(try XCTUnwrap(s.points.last?.value), 330, accuracy: d)
        XCTAssertEqual(try XCTUnwrap(s.pnlChange), 110 - 93, accuracy: d)
        // Bereich des Charts schliesst 0 ein
        XCTAssertEqual(PortfolioChartAxis.pnlBounds(low: 3, high: 29).low, 0)
        XCTAssertEqual(PortfolioChartAxis.pnlBounds(low: -5, high: -1).high, 0)
    }

    func testWithdrawalInRangeIsFlaggedAndDepositWithoutPriceCountsAtMarket() {
        let closes = ["SOL": Dictionary(uniqueKeysWithValues: (0...40).map { (i: Int) -> (Int, Double) in (today - i, 20.0 + Double(i % 3)) })]
        let withdrawal = PortfolioTx(id: 900, coin: "SOL", type: .WITHDRAWAL, amount: 1, time: noon(today - 3))
        let deposit = PortfolioTx(id: 901, coin: "SOL", type: .DEPOSIT, amount: 1, time: noon(today - 2))
        let first = buy("SOL", 2, noon(today - 20))
        let week = build([first, withdrawal, deposit], range: .week, closes, ["SOL": 20])
        // Einzahlung ohne Preis zum Tagesschluss: SOL bleibt in der Gewinn/Verlust-Kurve
        XCTAssertEqual(week.pnlSkipped, [])
        XCTAssertTrue(week.withdrawalsInRange)
        // Auszahlung vor dem Zeitraum: kein Hinweis, «Seit 1. Kauf» schon
        var older = withdrawal
        older.time = noon(today - 15)
        XCTAssertFalse(build([first, older], range: .week, closes, ["SOL": 20]).withdrawalsInRange)
        XCTAssertTrue(build([first, older], range: .sinceFirst, closes, ["SOL": 20]).withdrawalsInRange)
    }

    func testMarketPriceAtUsesTheCloseOfItsUtcDayThenTheLivePrice() {
        let today = 20_000
        let day: Int64 = 86_400_000
        let btc: [String: [Int: Double]] = ["BTC": [today - 2: 100, today - 1: 110]]
        // Mitten im Tag: Schluss dieses Tags; Kleinschreibung wie eingegeben
        let midDay: Int64 = Int64(today - 2) * day + day / 2
        XCTAssertEqual(PortfolioPeriodChange.marketPriceAt("btc", live: 112, closes: btc, time: midDay) ?? 0, 100, accuracy: 1e-9)
        // Heute (nach dem letzten Schluss): aktueller Kurs
        XCTAssertEqual(PortfolioPeriodChange.marketPriceAt("BTC", live: 112, closes: btc,
                                                           time: Int64(today) * day + 1) ?? 0, 112, accuracy: 1e-9)
        // Vor dem ersten Schluss bzw. ohne Schlusskurse: unbekannt
        XCTAssertNil(PortfolioPeriodChange.marketPriceAt("BTC", live: 112, closes: btc, time: Int64(today - 5) * day))
        XCTAssertNil(PortfolioPeriodChange.marketPriceAt("ETH", live: 3, closes: btc, time: Int64(today) * day))
        // USDT zählt 1
        XCTAssertEqual(PortfolioPeriodChange.marketPriceAt("USDT", live: nil, closes: [:], time: Int64(today) * day), 1)
    }

    /// USDT zum USD-Kurs, Verlauf in der Anzeigewährung samt Devisenbewegung — wie Android `fxLabelFlags`.
    func testFxLabelFlags() {
        XCTAssertTrue(PortfolioHistoryFx.convertsUsdtAsUsd("chf"))
        XCTAssertFalse(PortfolioHistoryFx.convertsUsdtAsUsd("USD"))
        XCTAssertFalse(PortfolioHistoryFx.convertsUsdtAsUsd("USDT"))
        XCTAssertTrue(PortfolioHistoryFx.includesFxMovement("CHF", approximate: false))
        XCTAssertFalse(PortfolioHistoryFx.includesFxMovement("CHF", approximate: true))
        XCTAssertFalse(PortfolioHistoryFx.includesFxMovement("USD", approximate: false))
        let series = PortfolioHistorySeries(points: [PortfolioHistoryPoint(epochDay: 1, value: 100),
                                                     PortfolioHistoryPoint(epochDay: 2, value: 100)],
                                            skipped: [], change: 0, changePercent: 0, tradesInRange: false)
        XCTAssertTrue(PortfolioHistoryFx.convert(series, dailyRates: [1: 0.9], currentRate: 0.8, todayEpochDay: 2).includesFx)
        XCTAssertFalse(PortfolioHistoryFx.convert(series, dailyRates: nil, currentRate: 0.8, todayEpochDay: 2).includesFx)
    }

    /// Fehler (Netz, Sperre) ist nicht «kein Kurs» — wie Android.
    func testPriceIssueSeparatesErrorsFromNoPrice() {
        XCTAssertEqual(PortfolioPriceIssue.of(failed: false, allSourcesBlocked: false, networkFailed: false), .noPrice)
        XCTAssertEqual(PortfolioPriceIssue.of(failed: true, allSourcesBlocked: false, networkFailed: false), .unavailable)
        XCTAssertEqual(PortfolioPriceIssue.of(failed: false, allSourcesBlocked: true, networkFailed: false), .unavailable)
        XCTAssertEqual(PortfolioPriceIssue.of(failed: false, allSourcesBlocked: false, networkFailed: true), .unavailable)
    }
}

/// Kerzen und Achsen des Wertverlaufs (`PortfolioCandles`, `PortfolioChartAxis`) samt Eröffnung/Hoch/Tief
/// aus `PortfolioHistory.build` — wie `PortfolioChartTest.kt`.
final class PortfolioChartTests: XCTestCase {

    private var nextId: Int64 = 1
    private let d = 1e-9
    private let today = LocalDay(2026, 10, 6).epochDay
    /// Fünf Jahre (5 × 365 + 1 Schalttag) — Beispiel für einen langen Verlauf.
    private let fiveYearsDays = 1826

    private func buy(_ coin: String, _ amount: Double, _ time: Int64) -> PortfolioTx {
        defer { nextId += 1 }
        return PortfolioTx(id: nextId, coin: coin, type: .BUY, amount: amount, priceUsdt: 1, time: time)
    }

    private func noon(_ day: Int) -> Int64 { Int64(day) * 86_400_000 + 12 * 3_600_000 }

    private func day(_ y: Int, _ m: Int, _ dd: Int) -> Int { LocalDay(y, m, dd).epochDay }

    private func points(_ values: [Double], first: Int? = nil) -> [PortfolioHistoryPoint] {
        let start = first ?? today - values.count + 1
        return values.enumerated().map { PortfolioHistoryPoint(epochDay: start + $0.offset, value: $0.element) }
    }

    // MARK: Tageskerzen

    func testDailyOpenIsPreviousCloseWithoutWicks() {
        let c = PortfolioCandles.daily(points([100, 110, 105]))
        XCTAssertEqual(c.count, 3)
        XCTAssertEqual(c[0].open, 100, accuracy: d)
        XCTAssertEqual(c[1].open, 100, accuracy: d)
        XCTAssertEqual(c[1].high, 110, accuracy: d)
        XCTAssertEqual(c[1].low, 100, accuracy: d)
        XCTAssertTrue(c[1].up)
        XCTAssertEqual(c[2].open, 110, accuracy: d)
        XCTAssertFalse(c[2].up)
        XCTAssertEqual(c[2].low, 105, accuracy: d)
    }

    func testDailyUsesOwnOpenOnFirstDayAndClampsBounds() {
        let p = [
            PortfolioHistoryPoint(epochDay: today - 1, value: 100, open: 90, high: 120, low: 80),
            PortfolioHistoryPoint(epochDay: today, value: 130, open: 999, high: 125, low: 101),
        ]
        let c = PortfolioCandles.daily(p)
        XCTAssertEqual(c[0].open, 90, accuracy: d)
        XCTAssertEqual(c[0].high, 120, accuracy: d)
        XCTAssertEqual(c[0].low, 80, accuracy: d)
        XCTAssertEqual(c[1].open, 100, accuracy: d)
        XCTAssertEqual(c[1].high, 130, accuracy: d)
        XCTAssertEqual(c[1].low, 100, accuracy: d)
    }

    // MARK: Zusammenfassen

    func testBucketThresholds() {
        XCTAssertEqual(PortfolioCandles.bucket(dayCount: 31), .day)
        XCTAssertEqual(PortfolioCandles.bucket(dayCount: PortfolioCandles.maxDailyDays), .day)
        XCTAssertEqual(PortfolioCandles.bucket(dayCount: PortfolioCandles.maxDailyDays + 1), .week)
        XCTAssertEqual(PortfolioCandles.bucket(dayCount: 366), .week)
        XCTAssertEqual(PortfolioCandles.bucket(dayCount: PortfolioCandles.maxWeeklyDays + 1), .month)
        XCTAssertEqual(PortfolioCandles.bucket(dayCount: fiveYearsDays), .month)
        XCTAssertEqual(PortfolioCandles.bucket(dayCount: 3400), .month)
    }

    func testBucketStartIsMondayOrFirstOfMonth() {
        XCTAssertEqual(PortfolioCandles.bucketStart(day(2026, 10, 6), bucket: .week), day(2026, 10, 5))
        XCTAssertEqual(PortfolioCandles.bucketStart(day(2026, 10, 11), bucket: .week), day(2026, 10, 5))
        XCTAssertEqual(PortfolioCandles.bucketStart(0, bucket: .week), day(1969, 12, 29))
        XCTAssertEqual(PortfolioCandles.bucketStart(day(2026, 10, 31), bucket: .month), day(2026, 10, 1))
        XCTAssertEqual(PortfolioCandles.bucketStart(42, bucket: .day), 42)
    }

    func testAggregateWeeks() {
        let daily = PortfolioCandles.daily(points([10, 12, 8, 9, 11, 15, 14], first: day(2026, 9, 30)))
        let weeks = PortfolioCandles.aggregate(daily, bucket: .week)
        XCTAssertEqual(weeks.count, 2)
        XCTAssertEqual(weeks[0].lastDay, day(2026, 10, 4))
        XCTAssertEqual(weeks[0].open, 10, accuracy: d)
        XCTAssertEqual(weeks[0].close, 11, accuracy: d)
        XCTAssertEqual(weeks[0].high, 12, accuracy: d)
        XCTAssertEqual(weeks[0].low, 8, accuracy: d)
        XCTAssertEqual(weeks[1].firstDay, day(2026, 10, 5))
        XCTAssertEqual(weeks[1].open, 11, accuracy: d)
        XCTAssertEqual(weeks[1].close, 14, accuracy: d)
        XCTAssertEqual(weeks[1].high, 15, accuracy: d)
    }

    func testBuildCapsCandleCount() {
        let weekly = PortfolioCandles.build(points((0..<366).map { (i: Int) -> Double in 100 + Double(i) }))
        XCTAssertTrue((52...54).contains(weekly.count))
        XCTAssertEqual(weekly.last?.lastDay, today)
        let monthly = PortfolioCandles.build(points((0..<fiveYearsDays).map { (i: Int) -> Double in 50 + Double(i % 17) }))
        XCTAssertTrue((60...62).contains(monthly.count))
        for i in 1..<monthly.count { XCTAssertEqual(monthly[i - 1].lastDay + 1, monthly[i].firstDay) }
        XCTAssertEqual(PortfolioCandles.build(points((0..<31).map { (i: Int) -> Double in Double(i) + 1 })).count, 31)
        XCTAssertTrue(PortfolioCandles.build([]).isEmpty)
    }

    // MARK: Eröffnung/Hoch/Tief aus den Coin-Kerzen

    private var closes: [String: [Int: Double]] { ["BTC": [today - 2: 100, today - 1: 110, today: 120]] }
    private var ranges: [String: [Int: PortfolioDayRange]] {
        ["BTC": [
            today - 2: PortfolioDayRange(open: 95, high: 105, low: 90),
            today - 1: PortfolioDayRange(open: 100, high: 118, low: 99),
            today: PortfolioDayRange(open: 110, high: 125, low: 108),
        ]]
    }

    private func build(ranges: [String: [Int: PortfolioDayRange]] = [:]) -> PortfolioHistorySeries {
        nextId = 1
        let trades = [buy("BTC", 2, noon(today - 2)), buy("USDT", 50, noon(today - 2))]
        return PortfolioHistory.build(trades: trades, closes: closes, livePrices: ["BTC": 121], range: .month,
                                      todayEpochDay: today,
                                      ranges: ranges)
    }

    func testBuildSumsHoldingsTimesCoinOhlc() {
        let p = build(ranges: ranges).points
        XCTAssertEqual(p.count, 3)
        XCTAssertEqual(p[0].value, 250, accuracy: d)
        XCTAssertEqual(p[0].open ?? 0, 240, accuracy: d)
        XCTAssertEqual(p[0].high ?? 0, 260, accuracy: d)
        XCTAssertEqual(p[0].low ?? 0, 230, accuracy: d)
        XCTAssertEqual(p[1].high ?? 0, 286, accuracy: d)
        XCTAssertEqual(p[1].low ?? 0, 248, accuracy: d)
        XCTAssertEqual(p[2].value, 292, accuracy: d)
        XCTAssertEqual(p[2].open ?? 0, 270, accuracy: d)
        XCTAssertEqual(p[2].high ?? 0, 300, accuracy: d)
        XCTAssertEqual(p[2].low ?? 0, 266, accuracy: d)
        let c = PortfolioCandles.daily(p)
        XCTAssertEqual(c[0].open, 240, accuracy: d)
        XCTAssertEqual(c[1].open, 250, accuracy: d)
        XCTAssertEqual(c[2].close, 292, accuracy: d)
    }

    func testBuildWithoutRangesKeepsPlainPoints() {
        let plain = build()
        XCTAssertTrue(plain.points.allSatisfy { $0.open == nil && $0.high == nil && $0.low == nil })
        var gap = ranges
        gap["BTC"]?[today - 1] = nil
        let s = build(ranges: gap)
        XCTAssertNil(s.points[1].high)
        XCTAssertEqual(s.points.map(\.value), plain.points.map(\.value))
    }

    func testFxConvertsOhlcWithSameDailyRate() {
        let s = build(ranges: ranges)
        let r = PortfolioHistoryFx.convert(s, dailyRates: [today - 2: 0.9, today - 1: 0.8], currentRate: 0.5,
                                           todayEpochDay: today)
        let p = r.series.points
        XCTAssertEqual(p[0].value, 250 * 0.9, accuracy: d)
        XCTAssertEqual(p[0].open ?? 0, 240 * 0.9, accuracy: d)
        XCTAssertEqual(p[0].low ?? 0, 230 * 0.9, accuracy: d)
        XCTAssertEqual(p[1].high ?? 0, 286 * 0.8, accuracy: d)
        XCTAssertEqual(p[2].high ?? 0, 300 * 0.5, accuracy: d)
    }

    // MARK: Achsen

    func testGridUnitsByLength() {
        XCTAssertEqual(PortfolioChartAxis.gridUnit(dayCount: 8), .day)
        XCTAssertEqual(PortfolioChartAxis.gridUnit(dayCount: 31), .week)
        XCTAssertEqual(PortfolioChartAxis.gridUnit(dayCount: 366), .month)
        XCTAssertEqual(PortfolioChartAxis.gridUnit(dayCount: 800), .quarter)
        XCTAssertEqual(PortfolioChartAxis.gridUnit(dayCount: 1826), .year)
    }

    func testGridDaysOnCalendarBoundaries() {
        XCTAssertEqual(PortfolioChartAxis.gridDays(firstDay: today - 7, lastDay: today), Array((today - 6)...today))
        XCTAssertEqual(PortfolioChartAxis.gridDays(firstDay: day(2026, 9, 6), lastDay: today),
                       [day(2026, 9, 7), day(2026, 9, 14), day(2026, 9, 21), day(2026, 9, 28), day(2026, 10, 5)])
        let year = PortfolioChartAxis.gridDays(firstDay: today - 365, lastDay: today)
        XCTAssertEqual(year.count, 12)
        XCTAssertTrue(year.allSatisfy { LocalDay(epochDay: $0).day == 1 })
        let quarters = PortfolioChartAxis.gridDays(firstDay: day(2024, 5, 10), lastDay: today)
        XCTAssertEqual(quarters.first, day(2024, 7, 1))
        XCTAssertTrue(quarters.allSatisfy { [1, 4, 7, 10].contains(LocalDay(epochDay: $0).month) })
        XCTAssertEqual(PortfolioChartAxis.gridDays(firstDay: day(2021, 10, 7), lastDay: today),
                       [day(2022, 1, 1), day(2023, 1, 1), day(2024, 1, 1), day(2025, 1, 1), day(2026, 1, 1)])
        XCTAssertTrue(PortfolioChartAxis.gridDays(firstDay: today, lastDay: today).isEmpty)
    }

    func testSlotPositionWithinSpans() {
        let spans = [10...16, 17...23, 24...24]
        XCTAssertEqual(PortfolioChartAxis.slotPosition(spans, day: 10) ?? -1, 0, accuracy: d)
        XCTAssertEqual(PortfolioChartAxis.slotPosition(spans, day: 13) ?? -1, 3.0 / 7.0, accuracy: d)
        XCTAssertEqual(PortfolioChartAxis.slotPosition(spans, day: 17) ?? -1, 1, accuracy: d)
        XCTAssertEqual(PortfolioChartAxis.slotPosition(spans, day: 24) ?? -1, 2, accuracy: d)
        XCTAssertNil(PortfolioChartAxis.slotPosition(spans, day: 9))
        XCTAssertNil(PortfolioChartAxis.slotPosition(spans, day: 25))
        XCTAssertEqual(PortfolioChartAxis.slotOfDay(spans, day: 20), 1)
    }

    func testMaxIndexAndLevels() {
        XCTAssertEqual(PortfolioChartAxis.maxIndex([1, 5, 5, 2]), 1)
        XCTAssertEqual(PortfolioChartAxis.maxIndex([.nan, 1, 3]), 2)
        XCTAssertNil(PortfolioChartAxis.maxIndex([]))
        let lv = PortfolioChartAxis.levels(low: 10, high: 20)
        XCTAssertEqual(lv.mid, 15, accuracy: d)
        let bounds = PortfolioChartAxis.candleBounds(PortfolioCandles.daily(points([100, 120, 90])))
        XCTAssertEqual(bounds?.low ?? 0, 90, accuracy: d)
        XCTAssertEqual(bounds?.high ?? 0, 120, accuracy: d)
        XCTAssertNil(PortfolioChartAxis.candleBounds([]))
    }
}

/// Zeitraum «Seit 1. Kauf» (`PortfolioHistory.startDay`, `isCapped`, `candleDays`) — wie
/// `PortfolioHistoryTest.kt`: nicht auf 5 Jahre begrenzt, nur frühestens ab den ersten Kursdaten.
final class PortfolioHistoryRangeTests: XCTestCase {

    private var nextId: Int64 = 1
    private let zone = TimeZone(identifier: "Europe/Zurich")!
    private let today = LocalDay(2026, 10, 6).epochDay

    private func dayEnd(_ day: Int) -> Int64 { PortfolioHistory.dayEndMillis(day, timeZone: zone) }

    /// Mittag (lokal) des Tags `day`.
    private func noon(_ day: Int) -> Int64 { dayEnd(day - 1) + 1 + 12 * 3_600_000 }

    private func buy(_ coin: String, _ amount: Double, _ time: Int64) -> PortfolioTx {
        defer { nextId += 1 }
        return PortfolioTx(id: nextId, coin: coin, type: .BUY, amount: amount, priceUsdt: 1, time: time)
    }

    /// Kerzen mit gleichem Schluss 50 über `days` Tage bis heute.
    private func flatCloses(_ days: Int) -> [Int: Double] {
        Dictionary(uniqueKeysWithValues: (0..<days).map { (i: Int) -> (Int, Double) in (today - i, 50.0) })
    }

    private func start(_ range: PortfolioHistoryRange, _ first: Int64) -> Int {
        PortfolioHistory.startDay(range: range, firstTradeMillis: first, todayEpochDay: today)
    }

    private func capped(_ range: PortfolioHistoryRange, _ first: Int64) -> Bool {
        PortfolioHistory.isCapped(range: range, firstTradeMillis: first, todayEpochDay: today)
    }

    private func candleDays(_ range: PortfolioHistoryRange, _ first: Int64?) -> Int {
        PortfolioHistory.candleDays(range: range, firstTradeMillis: first, todayEpochDay: today)
    }

    func testSinceFirstStartsAtTheFirstTransaction() {
        let first = noon(today - 800)
        XCTAssertEqual(start(.sinceFirst, first), today - 800)
        XCTAssertFalse(capped(.sinceFirst, first))
        let s = PortfolioHistory.build(trades: [buy("BTC", 1, first), buy("BTC", 1, noon(today - 10))],
                                       closes: ["BTC": flatCloses(900)], livePrices: ["BTC": 50], range: .sinceFirst,
                                       todayEpochDay: today)
        XCTAssertEqual(s.points.count, 801)
        XCTAssertEqual(s.points.first?.epochDay, today - 800)
        XCTAssertFalse(s.capped)
    }

    func testSinceFirstIsNotLimitedToFiveYears() {
        // Erster Kauf vor gut 7 Jahren: ganz gezeigt, nicht auf 5 Jahre gekappt
        let first = noon(today - 2600)
        XCTAssertEqual(start(.sinceFirst, first), today - 2600)
        XCTAssertFalse(capped(.sinceFirst, first))
        let s = PortfolioHistory.build(trades: [buy("BTC", 1, first)], closes: ["BTC": flatCloses(2700)],
                                       livePrices: ["BTC": 50], range: .sinceFirst, todayEpochDay: today)
        XCTAssertEqual(s.points.count, 2601)
        XCTAssertFalse(s.capped)
        XCTAssertEqual(candleDays(.sinceFirst, first), 2602)
    }

    func testSinceFirstStartsNoEarlierThanTheFirstPriceData() {
        let dataStart = PortfolioHistory.dataStartEpochDay
        XCTAssertEqual(dataStart, LocalDay(2017, 8, 17).epochDay)
        let first = noon(today - 4000)
        XCTAssertEqual(start(.sinceFirst, first), dataStart)
        XCTAssertTrue(capped(.sinceFirst, first))
        let s = PortfolioHistory.build(trades: [buy("BTC", 1, first)], closes: ["BTC": flatCloses(4100)],
                                       livePrices: ["BTC": 50], range: .sinceFirst, todayEpochDay: today)
        XCTAssertEqual(s.points.count, today - dataStart + 1)
        XCTAssertTrue(s.capped)
        // Erster Kauf genau am ersten Tag mit Kursdaten: nicht begrenzt
        XCTAssertFalse(capped(.sinceFirst, noon(dataStart)))
        XCTAssertEqual(start(.sinceFirst, noon(dataStart)), dataStart)
        // Andere Zeiträume sind nie «begrenzt»
        XCTAssertFalse(capped(.year, first))
    }

    func testStartDayMatchesTheFixedRanges() {
        let old = noon(today - 5000)
        XCTAssertEqual(start(.week, old), today - 7)
        XCTAssertEqual(start(.month, old), today - 30)
        XCTAssertEqual(start(.year, old), today - 365)
        XCTAssertEqual(start(.month, noon(today - 3)), today - 3)
        XCTAssertEqual(start(.sinceFirst, noon(today)), today)
        // EINE Tagesgrenze (UTC): kurz nach Mitternacht UTC zählt schon der neue Tag …
        XCTAssertEqual(start(.sinceFirst, PortfolioHistory.utcDayEnd(today - 2) + 1), today - 1)
        // … kurz nach Mitternacht Zürich-Sommerzeit (22:00 UTC) noch der UTC-Vortag, wie bei den Tageskerzen
        XCTAssertEqual(start(.sinceFirst, dayEnd(today - 2) + 1), today - 2)
    }

    func testCandleDaysOnlyGrowForSinceFirst() {
        let old = noon(today - 1500)
        XCTAssertEqual(candleDays(.week, old), 366)
        XCTAssertEqual(candleDays(.year, old), 366)
        XCTAssertEqual(candleDays(.sinceFirst, old), 1502)
        XCTAssertEqual(candleDays(.sinceFirst, noon(today - 20)), 366)
        XCTAssertEqual(candleDays(.sinceFirst, nil), 366)
        // Nur durch den ersten Tag mit Kursdaten begrenzt: ab da samt heute und einem Tag davor
        let sinceDataStart = today - PortfolioHistory.dataStartEpochDay + 2
        XCTAssertEqual(candleDays(.sinceFirst, noon(today - 9000)), sinceDataStart)
        // … in höchstens vier Abfragen zu 1000 Kerzen (Stand 2026)
        XCTAssertEqual(PortfolioHistory.candleChunks(days: sinceDataStart, todayUtcDay: today).count, 4)
    }
}
