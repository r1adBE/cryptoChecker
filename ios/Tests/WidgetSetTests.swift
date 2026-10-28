import XCTest
@testable import CryptoChecker

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus WidgetDrawBudgetTests

/// Endzeit eines Widget-Durchlaufs aus dem Zeichnen (`WidgetCadence.drawRunBudgetMillis`): an die
/// Zeichen-Frist gekoppelt, damit nach der Rückgabe der Timeline keine Arbeit mehr läuft (vorher 25 s).
final class WidgetDrawBudgetTests: XCTestCase {
    func testBudgetEndsBeforeDrawDeadline() {
        // Zeichen-Frist der Widgets: 8 s (`WidgetRefresh.drawDeadlineSeconds`, Widget-Ziel) → 7 s Kurse, 1 s Reserve
        XCTAssertEqual(WidgetCadence.drawRunBudgetMillis(deadlineSeconds: 8), 7_000)
        for seconds in [2.0, 5, 8, 12] {
            let budget = WidgetCadence.drawRunBudgetMillis(deadlineSeconds: seconds)
            XCTAssertLessThanOrEqual(budget + WidgetCadence.drawReserveMillis, Int64(seconds * 1_000), "\(seconds) s")
        }
    }

    func testBudgetHasFloorAndIgnoresInvalidDeadlines() {
        XCTAssertEqual(WidgetCadence.drawRunBudgetMillis(deadlineSeconds: 0.5), WidgetCadence.minDrawRunMillis)
        XCTAssertEqual(WidgetCadence.drawRunBudgetMillis(deadlineSeconds: 0), WidgetCadence.minDrawRunMillis)
        XCTAssertEqual(WidgetCadence.drawRunBudgetMillis(deadlineSeconds: -3), WidgetCadence.minDrawRunMillis)
        XCTAssertEqual(WidgetCadence.drawRunBudgetMillis(deadlineSeconds: .nan), WidgetCadence.minDrawRunMillis)
        XCTAssertEqual(WidgetCadence.drawRunBudgetMillis(deadlineSeconds: .infinity), WidgetCadence.minDrawRunMillis)
        // Weit unter dem Budget des Knopfs (25 s)
        XCTAssertLessThan(WidgetCadence.drawRunBudgetMillis(deadlineSeconds: 8), 25_000)
    }
}

// ---- aus WidgetFreshnessTests

/// Frische in den Widgets (`WidgetFreshness`); Gegenstück `WidgetFreshnessTest`.
final class WidgetFreshnessTests: XCTestCase {
    private let now: Int64 = 1_760_000_000_000
    private let hour: Int64 = 3_600_000
    private var after: Int64 { WidgetFreshness.afterMillis(backgroundIntervalMinutes: 15) } // 45 Min.

    private func watch(lastUpdateAgo: Int64 = 60_000, changedAgo: Int64 = 2 * 3_600_000, exchangeTime: Int64? = nil,
                       disabled: Bool = false, error: String? = nil) -> Watch {
        var w = Watch(id: 1, marketKey: "kraken", marketName: "Kraken", baseAsset: "BTC", quoteAsset: "EUR")
        w.lastPrice = 62_000
        w.lastUpdate = now - lastUpdateAgo
        w.lastError = error
        w.disabled = disabled
        w.priceChangedAt = now - changedAgo
        w.exchangeTime = exchangeTime
        return w
    }

    func testWidgetsUseThePollingLimitNeverTheLiveLimit() {
        XCTAssertEqual(WidgetFreshness.afterMillis(backgroundIntervalMinutes: 15), 45 * 60_000)
        XCTAssertEqual(WidgetFreshness.afterMillis(backgroundIntervalMinutes: 1), 15 * 60_000)
        XCTAssertEqual(WidgetFreshness.afterMillis(backgroundIntervalMinutes: 60), 180 * 60_000)
    }

    func testUnchangedPriceShowsSinceAndItsTime() {
        XCTAssertEqual(WidgetFreshness.unchangedSince(watch(), now: now, afterMillis: after), now - 2 * hour)
        XCTAssertEqual(WidgetFreshness.priceTime(watch(), now: now, afterMillis: after), now - 2 * hour)
    }

    func testRecentChangeIsFreshWithFetchTime() {
        let w = watch(changedAgo: 30 * 60_000)
        XCTAssertNil(WidgetFreshness.unchangedSince(w, now: now, afterMillis: after))
        XCTAssertEqual(WidgetFreshness.priceTime(w, now: now, afterMillis: after), now - 60_000)
    }

    func testExchangeTimeWinsAndNeverUnchanged() {
        let w = watch(exchangeTime: now - 5 * 60_000)
        XCTAssertNil(WidgetFreshness.unchangedSince(w, now: now, afterMillis: after))
        XCTAssertEqual(WidgetFreshness.priceTime(w, now: now, afterMillis: after), now - 5 * 60_000)
    }

    func testOutdatedDisabledOrFailedPairsShowNoUnchangedHint() {
        XCTAssertNil(WidgetFreshness.unchangedSince(watch(lastUpdateAgo: hour), now: now, afterMillis: after))
        XCTAssertNil(WidgetFreshness.unchangedSince(watch(disabled: true), now: now, afterMillis: after))
        XCTAssertNil(WidgetFreshness.unchangedSince(watch(error: "timeout"), now: now, afterMillis: after))
    }

    func testOutdatedLikeTheRowAndTheAlarmBlock() {
        // Abruf zu alt: Zeit des Abrufs
        XCTAssertEqual(WidgetFreshness.outdatedTime(watch(lastUpdateAgo: hour), now: now, afterMillis: after), now - hour)
        // Abruf frisch, letzter Handel 3 h her: Zeit des Handels; ruhiges Paar (1 h) nicht
        XCTAssertEqual(WidgetFreshness.outdatedTime(watch(exchangeTime: now - 3 * hour), now: now, afterMillis: after),
                       now - 3 * hour)
        XCTAssertNil(WidgetFreshness.outdatedTime(watch(exchangeTime: now - hour), now: now, afterMillis: after))
        XCTAssertNil(WidgetFreshness.outdatedTime(watch(), now: now, afterMillis: after))
        XCTAssertNil(WidgetFreshness.outdatedTime(watch(lastUpdateAgo: hour, disabled: true), now: now, afterMillis: after))
        // Wecker: Handel so verschoben, dass er mit derselben Grenze genau nach 2 h wechselt
        let w = watch(exchangeTime: now - hour)
        XCTAssertEqual(WidgetFreshness.outdatedTimes(w, afterMillis: after), [w.lastUpdate, now - hour + 2 * hour - after])
        XCTAssertEqual(OutdatedRule.nextChangeAt([now - hour + 2 * hour - after], now: now, afterMillis: after),
                       now + hour + 1)
    }

    /// Einzel-Widget: Paar vor dem Zeichnen neu lesen nach eigener Aktualisierung oder wenn die Merkliste seit dem
    /// ersten Lesen geschrieben wurde; sonst nicht (kein zweites Lesen der ganzen Merkliste).
    func testSingleWidgetRereadsWatchOnlyWhenWritten() {
        XCTAssertTrue(WidgetFreshness.rereadWatch(refreshed: true, writtenBefore: 1_000, writtenNow: 1_000))
        XCTAssertTrue(WidgetFreshness.rereadWatch(refreshed: false, writtenBefore: 1_000, writtenNow: 2_000))
        // Datei erst nach dem ersten Lesen angelegt (vorher 0 = keine Datei)
        XCTAssertTrue(WidgetFreshness.rereadWatch(refreshed: false, writtenBefore: 0, writtenNow: 2_000))
        XCTAssertFalse(WidgetFreshness.rereadWatch(refreshed: false, writtenBefore: 1_000, writtenNow: 1_000))
        XCTAssertFalse(WidgetFreshness.rereadWatch(refreshed: false, writtenBefore: 0, writtenNow: 0))
    }
}

// ---- aus WidgetListRowsParityTests

/// Zeilen des Merklisten-Widgets bei grosser Schrift (`widget_list_rows.json`, Quelle `android/testdata/parity`) —
/// wie `WidgetListRowsParityTest`.
final class WidgetListRowsParityTests: XCTestCase {
    private typealias S = TestSupport

    func testRowsFor() throws {
        let cases = S.list(try S.fixture("widget_list_rows")["rowsFor"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let height = S.number(c["height"]) ?? -1
            let scale = S.number(c["scale"]) ?? -1
            let baseRows = Int(S.number(c["baseRows"]) ?? -1)
            let want = Int(S.number(c["rows"]) ?? -1)
            XCTAssertEqual(WidgetRows.rowsFor(height: height, scale: scale, baseRows: baseRows), want,
                           c["name"] as? String ?? "")
        }
    }

    func testFewerRowsOnlyWithLargerText() {
        XCTAssertEqual(WidgetRows.rowsFor(height: 112, scale: 1, baseRows: WidgetRows.mediumListRows), 4)
        XCTAssertEqual(WidgetRows.rowsFor(height: 308, scale: 0.8, baseRows: WidgetRows.maxListRows), 10)
        XCTAssertLessThan(WidgetRows.rowsFor(height: 308, scale: 1.3, baseRows: WidgetRows.maxListRows), 10)
        XCTAssertEqual(WidgetRows.rowsFor(height: 308, scale: 1.3, baseRows: 10),
                       WidgetRows.rowsFor(height: 308, scale: 3, baseRows: 10))
    }
}

// ---- aus WidgetQuantityFormatTests

/// Gemeinsame Testfälle `widget_quantity.json` (Android: `WidgetQuantityParityTest`).
final class WidgetQuantityFormatTests: XCTestCase {

    private typealias S = TestSupport

    func testCases() throws {
        let cases = S.list(try S.fixture("widget_quantity")["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 30)
        for c in cases {
            let text = c["text"] as? String
            let decimal = c["decimal"] as? String ?? "."
            let grouping = c["grouping"] as? String ?? ","
            XCTAssertEqual(WidgetQuantityFormat.format(text, decimal: decimal, grouping: grouping),
                           c["expected"] as? String, "format '\(text ?? "nil")' (\(decimal) \(grouping))")
        }
    }

    func testFromDouble() throws {
        for c in S.list(try S.fixture("widget_quantity")["fromDouble"]) {
            let value = try XCTUnwrap(Double(c["value"] as? String ?? ""))
            XCTAssertEqual(WidgetQuantityFormat.format(DecimalText.plain(value), decimal: ".", grouping: ","),
                           c["expected"] as? String, "fromDouble \(c["value"] ?? "")")
        }
    }

    func testHiddenAndSpoken() {
        XCTAssertNil(WidgetQuantityFormat.visible("1.3", hidden: true))
        XCTAssertNil(WidgetQuantityFormat.visible(nil, hidden: false))
        XCTAssertEqual(WidgetQuantityFormat.spokenName("BTC", quantity: "1.3"), "1.3 BTC")
        XCTAssertEqual(WidgetQuantityFormat.spokenName("BTC", quantity: nil), "BTC")
    }

    /// Menge je Position aus dem Bestand (inkl. beobachteter Adressen), als Dezimaltext.
    func testTopPositionsCarryQuantity() {
        let values: [String: Double?] = ["BTC": 60_000, "ETH": 3_000, "XRP": nil]
        let top = PortfolioWidgetStore.topPositions(valuesUsd: values, totalUsd: 63_000, factor: 1, changes: [:],
                                                    amounts: ["BTC": 1.13, "ETH": 1.2e-5])
        XCTAssertEqual(top.positions.map(\.quantity), ["1.13", "0.000012"])
        let old = PortfolioWidgetStore.topPositions(valuesUsd: ["BTC": 1], totalUsd: 1, factor: 1, changes: [:])
        XCTAssertNil(old.positions.first?.quantity)
    }

    /// Ältere gespeicherte Positionen ohne `quantity` laden weiter; neue behalten die Menge.
    func testPositionDecodesWithoutQuantity() throws {
        let old = Data(#"{"symbol":"BTC","value":100,"sharePercent":50}"#.utf8)
        let decoded = try JSONDecoder().decode(PortfolioWidgetPosition.self, from: old)
        XCTAssertEqual(decoded.symbol, "BTC")
        XCTAssertNil(decoded.quantity)
        let fresh = PortfolioWidgetPosition(symbol: "ETH", value: 1, sharePercent: 1, change24hPercent: nil,
                                            quantity: "12.4567")
        let round = try JSONDecoder().decode(PortfolioWidgetPosition.self, from: JSONEncoder().encode(fresh))
        XCTAssertEqual(round.quantity, "12.4567")
    }
}

// ---- aus WidgetRowsTests

/// Merkliste-Widget: Logos und Zeitpunkte nur für die Zeilen, die es zeigen kann (`WidgetRows`).
final class WidgetRowsTests: XCTestCase {

    private func watches(_ count: Int, marketKey: String = "kraken") -> [Watch] {
        (0..<count).map { index in
            Watch(id: Int64(index + 1), marketKey: marketKey, marketName: "Kraken", baseAsset: "C\(index)", quoteAsset: "EUR")
        }
    }

    func testZweitausendPaareHoechstensZehnLogoSchluessel() {
        let keys = WidgetRows.logoKeys(for: watches(2000))
        XCTAssertLessThanOrEqual(keys.count, WidgetRows.maxListRows)
        XCTAssertEqual(keys.count, 10)
    }

    func testGezeigtSindDieErstenPaareInGleicherReihenfolge() {
        let list = watches(25)
        XCTAssertEqual(WidgetRows.shown(list).map(\.id), Array(list.prefix(10)).map(\.id))
        XCTAssertEqual(WidgetRows.shown(list, rows: WidgetRows.mediumListRows).count, 4)
        XCTAssertEqual(WidgetRows.shown(watches(3)).count, 3)
    }

    func testDexPoolsOhneLogo() {
        XCTAssertTrue(WidgetRows.logoKeys(for: watches(5, marketKey: CoinLogos.dexMarketKey)).isEmpty)
    }
}
