import XCTest
@testable import CryptoChecker

/// Wie `ChangeBasisTest.kt`, mit den Sommerzeit-Tagen (Zürich, Santiago).
final class ChangeBasisTests: XCTestCase {

    private let hour = ChangeBasisMath.hourMillis
    private let zurich = TimeZone(identifier: "Europe/Zurich")!
    private let utc = TimeZone(identifier: "UTC")!
    private func t(_ iso: String) -> Int64 { TestSupport.t(iso) }

    func testNamesRoundTripAndUnknownFallsBackToRolling() {
        for basis in ChangeBasis.allCases { XCTAssertEqual(ChangeBasis.from(name: basis.rawValue), basis) }
        XCTAssertEqual(ChangeBasis.from(name: nil), .ROLLING_24H)
        XCTAssertEqual(ChangeBasis.from(name: "WEEK"), .ROLLING_24H)
        XCTAssertFalse(ChangeBasis.ROLLING_24H.isDay)
        XCTAssertTrue(ChangeBasis.UTC_DAY.isDay)
        XCTAssertTrue(ChangeBasis.LOCAL_DAY.isDay)
        XCTAssertFalse(ChangeBasis.SINCE_LAST.isDay)
        XCTAssertEqual(ChangeBasis.SINCE_LAST.rawValue, "SINCE_LAST")
        XCTAssertEqual(ChangeBasis.SINCE_LAST.storage, .ROLLING_24H)
        XCTAssertEqual(ChangeBasis.UTC_DAY.storage, .UTC_DAY)
    }

    func testSinceLastUsesLastAndPreviousPriceAndSharesTheRollingStamp() {
        let now = t("2026-10-07T12:00:00Z")
        XCTAssertEqual(ChangeBasisMath.stamp(.SINCE_LAST, now: now, timeZone: utc), ChangeStamp(basis: .ROLLING_24H, dayStart: 0))
        func watch(previous: Double?, error: String? = nil) -> Watch {
            Watch(id: 1, marketKey: "binance", marketName: "Binance", baseAsset: "BTC", quoteAsset: "USDT",
                  lastPrice: 102, previousPrice: previous, lastError: error, change24h: 5)
        }
        let view = ChangeView(basis: .SINCE_LAST)
        XCTAssertEqual(view.shown(watch(previous: 100)) ?? .nan, 2, accuracy: 1e-9)
        XCTAssertNil(view.shown(watch(previous: nil)))
        // nicht gehandelt: «—»
        XCTAssertNil(view.shown(watch(previous: 100, error: NotTraded.marker)))
        // andere Basen: gespeicherter Wert
        XCTAssertEqual(ChangeView(basis: .ROLLING_24H).shown(watch(previous: 100)) ?? .nan, 5, accuracy: 1e-9)
        XCTAssertNil(ChangeView(basis: .UTC_DAY, current: false).shown(watch(previous: 100)))
    }

    func testUtcDayStartsAtMidnightUtcWhateverTheZone() {
        XCTAssertEqual(ChangeBasisMath.dayStart(.UTC_DAY, now: t("2026-10-07T23:59:00Z"), timeZone: zurich), t("2026-10-07T00:00:00Z"))
        XCTAssertEqual(ChangeBasisMath.dayStart(.UTC_DAY, now: t("2026-10-08T00:00:00Z"), timeZone: zurich), t("2026-10-08T00:00:00Z"))
        XCTAssertNil(ChangeBasisMath.dayStart(.ROLLING_24H, now: t("2026-10-07T23:59:00Z"), timeZone: zurich))
    }

    func testLocalDayFollowsTheDeviceZone() {
        let now = t("2026-10-07T23:30:00Z")
        XCTAssertEqual(ChangeBasisMath.dayStart(.LOCAL_DAY, now: now, timeZone: zurich), t("2026-10-07T22:00:00Z"))
        XCTAssertEqual(ChangeBasisMath.dayStart(.LOCAL_DAY, now: now, timeZone: utc), t("2026-10-07T00:00:00Z"))
    }

    func testSpringForwardDayIs23Hours() throws {
        let start = try XCTUnwrap(ChangeBasisMath.dayStart(.LOCAL_DAY, now: t("2026-03-29T21:00:00Z"), timeZone: zurich))
        XCTAssertEqual(start, t("2026-03-28T23:00:00Z"))
        let next = try XCTUnwrap(ChangeBasisMath.dayStart(.LOCAL_DAY, now: t("2026-03-29T22:30:00Z"), timeZone: zurich))
        XCTAssertEqual(next, t("2026-03-29T22:00:00Z"))
        XCTAssertEqual(next - start, 23 * hour)
    }

    func testFallBackDayIs25HoursAndStillFoundIn26Candles() throws {
        let now = t("2026-10-25T22:30:00Z")
        let start = try XCTUnwrap(ChangeBasisMath.dayStart(.LOCAL_DAY, now: now, timeZone: zurich))
        XCTAssertEqual(start, t("2026-10-24T22:00:00Z"))
        XCTAssertEqual(t("2026-10-25T23:00:00Z") - start, 25 * hour)
        let last = ChangeBasisMath.hourOf(now)
        var opens26: [Int64: Double] = [:]
        var opens24: [Int64: Double] = [:]
        for i in 0..<ChangeBasisMath.candles { opens26[last - Int64(i) * hour] = 100 + Double(i) }
        for i in 0..<ChangeBasisMath.rollingCandles { opens24[last - Int64(i) * hour] = 100 + Double(i) }
        XCTAssertEqual(ChangeBasisMath.openAt(opens26, dayStart: start), 124)
        XCTAssertNil(ChangeBasisMath.openAt(opens24, dayStart: start))
    }

    func testMissingMidnightStartsAtTheFirstValidTime() throws {
        // Chile stellt um 24:00 auf Sommerzeit um: 00:00 fehlt an diesem Tag
        let santiago = try XCTUnwrap(TimeZone(identifier: "America/Santiago"))
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = santiago
        for day in 1...30 {
            let noon = try XCTUnwrap(cal.date(from: DateComponents(year: 2026, month: 9, day: day, hour: 12)))
            let noonMillis = Int64(noon.timeIntervalSince1970 * 1000)
            let start = try XCTUnwrap(ChangeBasisMath.dayStart(.LOCAL_DAY, now: noonMillis, timeZone: santiago))
            let local = cal.dateComponents([.day, .hour, .minute], from: Date(timeIntervalSince1970: Double(start) / 1000))
            XCTAssertEqual(local.day, day)
            XCTAssertLessThanOrEqual((local.hour ?? 99) * 60 + (local.minute ?? 0), 60)
            XCTAssertLessThanOrEqual(start, noonMillis)
        }
    }

    func testHalfHourZonesUseTheCandleThatContainsTheStart() throws {
        let kolkata = try XCTUnwrap(TimeZone(identifier: "Asia/Kolkata"))
        let start = try XCTUnwrap(ChangeBasisMath.dayStart(.LOCAL_DAY, now: t("2026-10-07T10:00:00Z"), timeZone: kolkata))
        XCTAssertEqual(start, t("2026-10-06T18:30:00Z"))
        XCTAssertEqual(ChangeBasisMath.hourOf(start), t("2026-10-06T18:00:00Z"))
        let opens: [Int64: Double] = [t("2026-10-06T18:00:00Z"): 50, t("2026-10-06T19:00:00Z"): 51]
        XCTAssertEqual(ChangeBasisMath.openAt(opens, dayStart: start), 50)
    }

    func testDayJustStartedUsesTheRunningCandleOpen() throws {
        let start = t("2026-10-07T00:00:00Z")
        let opens: [Int64: Double] = [start - hour: 99, start: 100]
        let ref = try XCTUnwrap(ChangeBasisMath.reference(opens, lastClose: 101, dayStart: start))
        XCTAssertEqual(ref.open, 100)
        XCTAssertEqual(try XCTUnwrap(DayChange.fromPrice(102, ref)), 2, accuracy: 1e-9)
        XCTAssertNil(ChangeBasisMath.reference([start - hour: 99], lastClose: 101, dayStart: start))
        XCTAssertNil(ChangeBasisMath.reference([start: .nan], lastClose: 101, dayStart: start))
        XCTAssertNil(ChangeBasisMath.reference(opens, lastClose: nil, dayStart: start))
    }

    func testDayBasesNeverUseTheRollingTickerValue() {
        XCTAssertEqual(ChangeBasisMath.choose(.ROLLING_24H, tickerChange: 5) { 1 }, 5)
        XCTAssertEqual(ChangeBasisMath.choose(.ROLLING_24H, tickerChange: nil) { 1 }, 1)
        XCTAssertEqual(ChangeBasisMath.choose(.UTC_DAY, tickerChange: 5) { 1 }, 1)
        XCTAssertNil(ChangeBasisMath.choose(.LOCAL_DAY, tickerChange: 5) { nil })
        XCTAssertNil(ChangeBasisMath.choose(.LOCAL_DAY, tickerChange: 5) { .nan })
        XCTAssertTrue(ChangeBasisMath.needsCandles(.UTC_DAY, tickerChange: 5))
        XCTAssertFalse(ChangeBasisMath.needsCandles(.ROLLING_24H, tickerChange: 5))
        XCTAssertTrue(ChangeBasisMath.needsCandles(.ROLLING_24H, tickerChange: nil))
    }

    func testStampDecidesWhetherStoredValuesStillApply() {
        let now = t("2026-10-07T12:00:00Z")
        XCTAssertTrue(ChangeBasisMath.isCurrent(stamp: nil, basis: .ROLLING_24H, now: now, timeZone: utc))
        XCTAssertFalse(ChangeBasisMath.isCurrent(stamp: nil, basis: .UTC_DAY, now: now, timeZone: utc))
        let today = ChangeBasisMath.stamp(.UTC_DAY, now: now, timeZone: utc)
        XCTAssertEqual(today.dayStart, t("2026-10-07T00:00:00Z"))
        XCTAssertTrue(ChangeBasisMath.isCurrent(stamp: today, basis: .UTC_DAY, now: now, timeZone: utc))
        XCTAssertFalse(ChangeBasisMath.isCurrent(stamp: today, basis: .UTC_DAY, now: t("2026-10-08T00:00:01Z"), timeZone: utc))
        XCTAssertFalse(ChangeBasisMath.isCurrent(stamp: today, basis: .LOCAL_DAY, now: now, timeZone: zurich))
        XCTAssertFalse(ChangeBasisMath.isCurrent(stamp: today, basis: .ROLLING_24H, now: now, timeZone: utc))
        XCTAssertNil(ChangeView.of(stamp: today, basis: .UTC_DAY, now: t("2026-10-08T01:00:00Z"), timeZone: utc).shown(2))
        XCTAssertEqual(ChangeView.of(stamp: today, basis: .UTC_DAY, now: now, timeZone: utc).shown(2), 2)
    }

    func testStampTextRoundTrip() {
        let stamp = ChangeStamp(basis: .LOCAL_DAY, dayStart: 1_760_000_000_000)
        XCTAssertEqual(ChangeStamp.decode(stamp.encoded), stamp)
        XCTAssertNil(ChangeStamp.decode(nil))
        XCTAssertNil(ChangeStamp.decode("LOCAL_DAY"))
        XCTAssertNil(ChangeStamp.decode("WEEK@1"))
        XCTAssertNil(ChangeStamp.decode("UTC_DAY@x"))
    }

    func testChartTodayStartsAtTheDayStartCandle() {
        let start = t("2026-10-07T00:00:00Z")
        let times = (0..<24).map { start - 10 * hour + Int64($0) * hour }
        let since = ChangeBasisMath.sinceDayStart(times, dayStart: start) { $0 }
        XCTAssertEqual(since.first, start)
        XCTAssertEqual(since.count, 14)
        let early = (0..<24).map { start - 23 * hour + Int64($0) * hour }
        XCTAssertEqual(ChangeBasisMath.sinceDayStart(early, dayStart: start) { $0 }, [start - hour, start])
    }

    func testFixedZonesLikeBinanceRollingAndDeviceFirst() throws {
        let all = ChangeBasis.allCases
        XCTAssertEqual(all.count, 3 + 27)
        XCTAssertEqual(all.first, .ROLLING_24H)
        XCTAssertEqual(all[1], .SINCE_LAST)
        XCTAssertEqual(all[2], .LOCAL_DAY)
        XCTAssertEqual(all[3], ChangeBasis.utc(14))
        XCTAssertEqual(all.last, ChangeBasis.utc(-12))
        XCTAssertEqual(ChangeBasis.utc(0), .UTC_DAY)
        XCTAssertNil(ChangeBasis.utc(15))
        XCTAssertNil(ChangeBasis.utc(-13))
        XCTAssertEqual(Set(all).count, all.count)
    }

    func testFixedZoneNamesRoundTrip() throws {
        XCTAssertEqual(ChangeBasis.UTC_DAY.rawValue, "UTC_DAY")
        XCTAssertEqual(try XCTUnwrap(ChangeBasis.utc(8)).rawValue, "UTC_DAY+8")
        XCTAssertEqual(try XCTUnwrap(ChangeBasis.utc(-5)).rawValue, "UTC_DAY-5")
        XCTAssertEqual(ChangeBasis.from(name: "UTC_DAY-5"), ChangeBasis.utc(-5))
        XCTAssertEqual(ChangeBasis.from(name: "UTC_DAY+0"), .ROLLING_24H)
        XCTAssertEqual(ChangeBasis.from(name: "UTC_DAY+99"), .ROLLING_24H)
        let stamp = ChangeStamp(basis: try XCTUnwrap(ChangeBasis.utc(-5)), dayStart: 1_760_000_000_000)
        XCTAssertEqual(ChangeStamp.decode(stamp.encoded), stamp)
        // Gespeichert wie bisher als einfacher Text (Einstellungen, Sicherung, Live-Aktivität)
        let json = try JSONEncoder().encode([try XCTUnwrap(ChangeBasis.utc(8))])
        XCTAssertEqual(String(data: json, encoding: .utf8), "[\"UTC_DAY+8\"]")
        XCTAssertEqual(try JSONDecoder().decode([ChangeBasis].self, from: Data("[\"UTC_DAY-3\",\"WEEK\"]".utf8)),
                       [try XCTUnwrap(ChangeBasis.utc(-3)), .ROLLING_24H])
    }

    func testFixedZoneDayStartIgnoresDeviceZoneAndSummerTime() throws {
        let now = t("2026-10-07T23:59:00Z")
        let plus8 = try XCTUnwrap(ChangeBasis.utc(8))
        XCTAssertEqual(ChangeBasisMath.dayStart(plus8, now: now, timeZone: zurich), t("2026-10-07T16:00:00Z"))
        XCTAssertEqual(ChangeBasisMath.dayStart(plus8, now: now, timeZone: utc), t("2026-10-07T16:00:00Z"))
        for basis in ChangeBasis.allCases where basis.kind == .utcDay {
            let start = try XCTUnwrap(ChangeBasisMath.dayStart(basis, now: now, timeZone: zurich))
            XCTAssertTrue(start <= now && now - start < ChangeBasisMath.dayMillis, basis.rawValue)
            XCTAssertTrue(now - ChangeBasisMath.hourOf(start) < Int64(ChangeBasisMath.candles) * hour, basis.rawValue)
        }
    }

    func testZoneLabels() throws {
        XCTAssertEqual(ChangeBasisMath.zoneLabel(offsetSeconds: 0), "UTC")
        XCTAssertEqual(ChangeBasisMath.zoneLabel(try XCTUnwrap(ChangeBasis.utc(-12))), "UTC-12")
        XCTAssertNil(ChangeBasisMath.zoneLabel(.LOCAL_DAY))
        XCTAssertEqual(ChangeBasisMath.deviceZoneLabel(now: t("2026-07-01T12:00:00Z"), timeZone: zurich), "UTC+2")
        XCTAssertEqual(ChangeBasisMath.deviceZoneLabel(now: t("2026-12-01T12:00:00Z"), timeZone: zurich), "UTC+1")
        XCTAssertEqual(ChangeBasisMath.deviceZoneLabel(now: t("2026-12-01T12:00:00Z"),
                                                       timeZone: try XCTUnwrap(TimeZone(identifier: "Asia/Kolkata"))), "UTC+5:30")
    }

    func testCacheKeepsUtcLocalAndChosenZone() throws {
        let now = t("2026-10-07T12:00:00Z")
        XCTAssertEqual(ChangeBasisMath.keptDayStarts(try XCTUnwrap(ChangeBasis.utc(8)), now: now, timeZone: zurich),
                       [t("2026-10-07T00:00:00Z"), t("2026-10-06T22:00:00Z"), t("2026-10-06T16:00:00Z")])
        XCTAssertEqual(ChangeBasisMath.keptDayStarts(.ROLLING_24H, now: now, timeZone: zurich).count, 2)
    }

    func testDayEndIsNextDayStart() throws {
        let utcStart = t("2026-10-08T00:00:00Z")
        XCTAssertEqual(ChangeBasisMath.dayEnd(.UTC_DAY, dayStart: utcStart, timeZone: zurich), t("2026-10-09T00:00:00Z"))
        let plus8 = try XCTUnwrap(ChangeBasis.utc(8))
        let start8 = try XCTUnwrap(ChangeBasisMath.dayStart(plus8, now: utcStart, timeZone: zurich))
        XCTAssertEqual(ChangeBasisMath.dayEnd(plus8, dayStart: start8, timeZone: zurich), start8 + ChangeBasisMath.dayMillis)
        // Sommerzeit endet (25. Oktober 2026): der Tag hat 25 Stunden
        let local = try XCTUnwrap(ChangeBasisMath.dayStart(.LOCAL_DAY, now: t("2026-10-25T10:00:00Z"), timeZone: zurich))
        XCTAssertEqual(ChangeBasisMath.dayEnd(.LOCAL_DAY, dayStart: local, timeZone: zurich), local + 25 * ChangeBasisMath.hourMillis)
        XCTAssertNil(ChangeBasisMath.dayEnd(.ROLLING_24H, dayStart: utcStart, timeZone: zurich))
    }

    func testChartTimeZoneFollowsBasis() throws {
        XCTAssertEqual(ChangeBasisMath.chartTimeZone(try XCTUnwrap(ChangeBasis.utc(8)), device: zurich).secondsFromGMT(), 8 * 3600)
        XCTAssertEqual(ChangeBasisMath.chartTimeZone(.UTC_DAY, device: zurich).secondsFromGMT(), 0)
        XCTAssertEqual(ChangeBasisMath.chartTimeZone(try XCTUnwrap(ChangeBasis.utc(-5)), device: zurich).secondsFromGMT(), -5 * 3600)
        XCTAssertEqual(ChangeBasisMath.chartTimeZone(.LOCAL_DAY, device: zurich), zurich)
        XCTAssertEqual(ChangeBasisMath.chartTimeZone(.ROLLING_24H, device: zurich), zurich)
    }
}

