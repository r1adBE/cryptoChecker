import XCTest
@testable import CryptoChecker

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus PortfolioWalletNamesTests

/// Vorschläge und «Meintest du …?» für Wallet-Namen — wie `PortfolioWalletNamesTest.kt` (Android).
final class PortfolioWalletNamesTests: XCTestCase {
    private let used: [String] = ["Binance", "Ledger Nano", "Kraken"]

    func testFoldIgnoresCaseSpacesAndAccents() {
        XCTAssertEqual(PortfolioWalletNames.fold(" Bin ance "), "binance")
        XCTAssertEqual(PortfolioWalletNames.fold("Lédger"), "ledger")
        XCTAssertEqual(PortfolioWalletNames.fold("KRAKEN"), "kraken")
    }

    func testKnownKeepsFirstSpellingAndDropsEmpty() {
        XCTAssertEqual(PortfolioWalletNames.known([" Binance ", nil, "", "binance", "Kraken", "BIN ANCE"]),
                       ["Binance", "Kraken"])
    }

    func testTypoIsDetected() {
        XCTAssertEqual(PortfolioWalletNames.nearDuplicate("Binanse", known: used), "Binance")
        XCTAssertEqual(PortfolioWalletNames.nearDuplicate("Bianance", known: used), "Binance")
        XCTAssertEqual(PortfolioWalletNames.nearDuplicate("Krakn", known: used), "Kraken")
    }

    func testSpacesAndAccentsCountAsNear() {
        XCTAssertEqual(PortfolioWalletNames.nearDuplicate("Bin ance", known: used), "Binance")
        XCTAssertEqual(PortfolioWalletNames.nearDuplicate("LedgerNano", known: used), "Ledger Nano")
        XCTAssertEqual(PortfolioWalletNames.nearDuplicate("Krakén", known: used), "Kraken")
    }

    func testSameWalletOrUnrelatedNameGivesNoHint() {
        // Nur Gross/klein bzw. Rand-Leerzeichen: dieselbe Wallet (PortfolioWallets.key)
        XCTAssertNil(PortfolioWalletNames.nearDuplicate("binance", known: used))
        XCTAssertNil(PortfolioWalletNames.nearDuplicate(" Binance ", known: used))
        XCTAssertNil(PortfolioWalletNames.nearDuplicate("", known: used))
        XCTAssertNil(PortfolioWalletNames.nearDuplicate("Trezor", known: used))
        // Drei Tippfehler: zu weit weg
        XCTAssertNil(PortfolioWalletNames.nearDuplicate("Bonanxy", known: ["Binance"]))
    }

    func testShortNamesAllowFewOrNoTypos() {
        XCTAssertNil(PortfolioWalletNames.nearDuplicate("OKX", known: ["BTC"]))
        XCTAssertNil(PortfolioWalletNames.nearDuplicate("OKX", known: ["OKEX"]))
        XCTAssertEqual(PortfolioWalletNames.nearDuplicate("Bybitt", known: ["Bybit"]), "Bybit")
        XCTAssertNil(PortfolioWalletNames.nearDuplicate("Bybxtt", known: ["Bybit"]))
        XCTAssertEqual(PortfolioWalletNames.maxDistance(3), 0)
        XCTAssertEqual(PortfolioWalletNames.maxDistance(5), 1)
        XCTAssertEqual(PortfolioWalletNames.maxDistance(6), 2)
    }

    func testTieGoesToFirstKnownName() {
        XCTAssertEqual(PortfolioWalletNames.nearDuplicate("Binancx", known: ["Binance", "Binancy"]), "Binance")
    }

    func testSuggestionsWithoutQueryAreUsedNames() {
        XCTAssertEqual(PortfolioWalletNames.suggestions(used: used, catalog: ["Bitstamp"], query: ""), used)
        XCTAssertEqual(PortfolioWalletNames.suggestions(used: used, catalog: [], query: " ", limit: 1), ["Binance"])
    }

    func testSuggestionsWithQueryPrefixThenContains() {
        let catalog: [String] = ["Bitstamp", "Coinbase", "binance", "Bitget"]
        XCTAssertEqual(PortfolioWalletNames.suggestions(used: used, catalog: catalog, query: "bi"),
                       ["Binance", "Bitstamp", "Bitget"])
        XCTAssertEqual(PortfolioWalletNames.suggestions(used: used, catalog: catalog, query: "base"), ["Coinbase"])
        // Der schon getippte Name selbst nicht
        XCTAssertEqual(PortfolioWalletNames.suggestions(used: used, catalog: catalog, query: "binance"), [])
    }

    func testEditDistance() {
        XCTAssertEqual(PortfolioWalletNames.distance("abc", "abc"), 0)
        XCTAssertEqual(PortfolioWalletNames.distance("binance", "binanse"), 1)
        XCTAssertEqual(PortfolioWalletNames.distance("kraken", "krkn"), 2)
        XCTAssertEqual(PortfolioWalletNames.distance("", "abc"), 3)
    }
}

// ---- aus PortfolioWidgetBackgroundTests

/// Portfolio-Widget-Stand ausserhalb des Hauptthreads (`OrderedSerialRunner`,
/// `PortfolioWidgetStore.updateInBackground`): die Rechnung läuft nie auf dem Hauptthread, Aufnahmen laufen einzeln
/// nacheinander, eine ältere überschreibt nie eine neuere, und das Ergebnis ist dasselbe wie synchron gerechnet.
final class PortfolioWidgetBackgroundTests: XCTestCase {
    @MainActor
    func testWorkRunsOffMainThread() async {
        XCTAssertTrue(Thread.isMainThread)
        let runner = OrderedSerialRunner()
        let onMain = await runner.runDetached { Thread.isMainThread }
        XCTAssertEqual(onMain, false)
    }

    func testOlderTicketIsSkippedAfterNewerRan() {
        let runner = OrderedSerialRunner()
        let older = runner.ticket()
        let newer = runner.ticket()
        XCTAssertEqual(runner.run(newer) { "neu" }, "neu")
        // Kommt erst danach an die Reihe: Eingaben älter → entfällt
        XCTAssertNil(runner.run(older) { "alt" })
        XCTAssertEqual(runner.run(runner.ticket()) { "neuer" }, "neuer")
    }

    func testWorkNeverRunsConcurrently() async {
        let runner = OrderedSerialRunner()
        let state = LockedBox((active: 0, maxActive: 0))
        await withTaskGroup(of: Void.self) { group in
            for _ in 0..<8 {
                group.addTask {
                    _ = await runner.runDetached {
                        state.write { $0.active += 1; $0.maxActive = max($0.maxActive, $0.active) }
                        Thread.sleep(forTimeInterval: 0.01)
                        state.write { $0.active -= 1 }
                    }
                }
            }
        }
        XCTAssertEqual(state.current.maxActive, 1)
    }

    /// Gleiche Eingaben → gleicher Stand, ob auf dem Hauptthread oder im Hintergrund gerechnet. Ohne Kursstand
    /// (`updatedAt` 0) schreibt `compute` nichts — der Test verändert die App Group nicht.
    @MainActor
    func testBackgroundResultEqualsSynchronousResult() async {
        let txs = [PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 0.5, priceUsdt: 30_000, time: 1_700_000_000_000),
                   PortfolioTx(id: 2, coin: "ETH", type: .BUY, amount: 2, priceUsdt: 2_000, time: 1_700_000_100_000)]
        let prices = PortfolioPrices(prices: ["BTC": 60_000, "ETH": 3_000], updatedAt: 0)
        let now: Int64 = 1_760_000_000_000
        @Sendable func run() -> PortfolioWidgetSnapshot {
            PortfolioWidgetStore.compute(transactions: txs, prices: prices, currency: "CHF", rate: 0.8, now: now,
                                         dailyPrices: [:], routed: [])
        }
        let sync = run()
        let background = await OrderedSerialRunner().runDetached { run() }
        XCTAssertEqual(background, sync)
        XCTAssertEqual(sync.totalUsdt ?? 0, 36_000, accuracy: 1e-6)
    }
}

// ---- aus PortfolioWidgetOptionsTests

/// Gemeinsame Testfälle `widget_portfolio_options.json` (Android: `WidgetPortfolioOptionsParityTest`).
final class PortfolioWidgetOptionsTests: XCTestCase {

    private typealias S = TestSupport

    private func view(_ raw: Any?) throws -> PortfolioWidgetMode {
        try XCTUnwrap(PortfolioWidgetMode(rawValue: raw as? String ?? ""), "Ansicht \(raw ?? "nil")")
    }

    private func display(_ raw: Any?) throws -> PortfolioWidgetDisplay {
        try XCTUnwrap(PortfolioWidgetDisplay(rawValue: raw as? String ?? ""), "Anzeige \(raw ?? "nil")")
    }

    private func range(_ raw: Any?) throws -> PortfolioWidgetRange {
        try XCTUnwrap(PortfolioWidgetRange(rawValue: raw as? String ?? ""), "Zeitraum \(raw ?? "nil")")
    }

    func testOffered() throws {
        let offered = try XCTUnwrap(try S.fixture("widget_portfolio_options")["offered"] as? [String: Any])
        XCTAssertEqual(offered.count, PortfolioWidgetMode.allCases.count)
        for (name, raw) in offered {
            let v = try view(name)
            let o = try XCTUnwrap(raw as? [String: Any])
            let displays = try (o["displays"] as? [Any] ?? []).map { try display($0) }
            let ranges = try (o["ranges"] as? [Any] ?? []).map { try range($0) }
            XCTAssertEqual(PortfolioWidgetOptions.displays(v), displays, "displays \(name)")
            XCTAssertEqual(PortfolioWidgetOptions.ranges(v), ranges, "ranges \(name)")
        }
    }

    func testCases() throws {
        let cases = S.list(try S.fixture("widget_portfolio_options")["cases"])
        // Alle Kombinationen Ansicht × Anzeige × Zeitraum
        XCTAssertEqual(cases.count, PortfolioWidgetMode.allCases.count * PortfolioWidgetDisplay.allCases.count
                       * PortfolioWidgetRange.allCases.count)
        for c in cases {
            let v = try view(c["view"])
            let label = "\(c["view"] ?? "") \(c["display"] ?? "") \(c["range"] ?? "")"
            XCTAssertEqual(PortfolioWidgetOptions.effectiveDisplay(v, try display(c["display"])),
                           try display(c["effectiveDisplay"]), "display \(label)")
            XCTAssertEqual(PortfolioWidgetOptions.effectiveRange(v, try range(c["range"])),
                           try range(c["effectiveRange"]), "range \(label)")
        }
    }

    /// Sperrbildschirm ohne Regel der Ansicht — Zeitraum und Anzeige wirken wie gewählt, auch wenn das
    /// Widget auf «Übersicht» oder «Liste» steht; das Sperrbildschirm-Widget zeichnet mit dieser Regel.
    func testLockScreenKeepsChoice() throws {
        let lock = PortfolioWidgetOptions.lockScreenView
        for r in PortfolioWidgetRange.allCases {
            XCTAssertEqual(PortfolioWidgetOptions.effectiveRange(lock, r), r, "range \(r)")
        }
        for d in PortfolioWidgetDisplay.allCases {
            XCTAssertEqual(PortfolioWidgetOptions.effectiveDisplay(lock, d), d, "display \(d)")
        }
        let widget = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Widgets/PortfolioWidget.swift")
        let text = try String(contentsOf: widget, encoding: .utf8)
        XCTAssertTrue(text.contains("PortfolioRectangularView(entry: entry.lockScreen)"), "Sperrbildschirm mit Ansicht-Regel")
        XCTAssertTrue(text.contains("copy.view = PortfolioWidgetOptions.lockScreenView"))
    }

    /// Was wirkt, ist auch angeboten (Zeitraum nur, wo die Ansicht ihn nutzt).
    func testEffectiveIsAlwaysOffered() {
        for v in PortfolioWidgetMode.allCases {
            for d in PortfolioWidgetDisplay.allCases {
                XCTAssertTrue(PortfolioWidgetOptions.displays(v).contains(PortfolioWidgetOptions.effectiveDisplay(v, d)))
            }
            guard v.usesRange else { continue }
            for r in PortfolioWidgetRange.allCases {
                XCTAssertTrue(PortfolioWidgetOptions.ranges(v).contains(PortfolioWidgetOptions.effectiveRange(v, r)))
            }
        }
    }
}

// ---- aus PortfolioWriteFailureTests

/// Löschen, «Rückgängig» und «Portfolio leeren» melden nur Geschriebenes als erledigt (`PortfolioEdits`): scheitert das
/// Schreiben (gesperrte Datei, ohne Schlüssel), nichts gelöscht, kein Banner «entfernt», gemerkte Kurse bleiben.
/// Dazu der Hinweis «unvollständig» unter einem Teilwert im CH-Vermögenswert der Steuerkarte.
final class PortfolioWriteFailureTests: XCTestCase {

    private func file() -> PortfolioFile {
        PortfolioFile(transactions: [
            PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 100, time: 1),
            PortfolioTx(id: 2, coin: "ETH", type: .BUY, amount: 2, priceUsdt: 10, time: 2),
            PortfolioTx(id: 3, coin: "BTC", type: .SELL, amount: 0.5, priceUsdt: 120, time: 3),
        ], nextId: 4)
    }

    func testDeleteCoinReportsNothingWhenNotWritten() {
        var written: PortfolioFile?
        XCTAssertEqual(PortfolioEdits.deleteCoin("btc", in: file(), write: { _ in false }).count, 0)
        let removed = PortfolioEdits.deleteCoin("btc", in: file(), write: { written = $0; return true })
        XCTAssertEqual(removed.map(\.id), [1, 3])
        XCTAssertEqual(written?.transactions.map(\.id), [2])
        // Nichts betroffen: gar nicht schreiben
        XCTAssertEqual(PortfolioEdits.deleteCoin("SOL", in: file(), write: { _ in XCTFail("geschrieben"); return true }).count, 0)
    }

    func testDeleteTxAndRestoreReturnWriteResult() {
        XCTAssertFalse(PortfolioEdits.deleteTx(1, in: file(), write: { _ in false }))
        var written: PortfolioFile?
        XCTAssertTrue(PortfolioEdits.deleteTx(1, in: file(), write: { written = $0; return true }))
        XCTAssertEqual(written?.transactions.map(\.id), [2, 3])
        let tx = PortfolioTx(id: 1, coin: "BTC", type: .BUY, amount: 1, priceUsdt: 100, time: 1)
        XCTAssertFalse(PortfolioEdits.restore([tx], in: written!, write: { _ in false }))
        XCTAssertTrue(PortfolioEdits.restore([tx], in: written!, write: { written = $0; return true }))
        XCTAssertEqual(Set(written?.transactions.map(\.id) ?? []), [1, 2, 3])
        XCTAssertTrue(PortfolioEdits.restore([], in: file(), write: { _ in XCTFail("geschrieben"); return false }))
    }

    /// Gemerkte Kurse nur nach geschriebenem Leeren löschen.
    func testClearKeepsPriceCacheWhenNotWritten() {
        var cacheCleared = false
        XCTAssertFalse(PortfolioEdits.clear(in: file(), write: { _ in false }, afterSave: { cacheCleared = true }))
        XCTAssertFalse(cacheCleared)
        var written: PortfolioFile?
        XCTAssertTrue(PortfolioEdits.clear(in: file(), write: { written = $0; return true }, afterSave: { cacheCleared = true }))
        XCTAssertTrue(cacheCleared)
        XCTAssertEqual(written?.transactions.count, 0)
        XCTAssertEqual(written?.nextId, 4)
    }

    /// Teilwert: Wert und darunter «unvollständig – Grund»; ohne Wert steht der Hinweis an Stelle des Werts (wie Android).
    func testWealthNoteBelowPartialValue() {
        var labels: [TaxLine: String] = [:]
        for l in TaxLine.allCases { labels[l] = l.rawValue }
        let texts = TaxSummaryTexts(title: "T", disclaimer: "D", item: "Item", usdt: "USDT", target: "CHF", note: "Note",
                                    orientation: "O", labels: labels, below: "below", reached: "reached", unknown: "unknown",
                                    incomplete: "incomplete", noFx: "nofx", wealthMissing: "nc",
                                    wealthAddressUnobserved: "unobserved")
        let partial = TaxWealthRow(coin: "BTC", amount: Decimal(string: "0.1")!, priceUsdt: 60000, valueUsdt: 6000,
                                   valueTarget: 4800, addressUnobserved: true, incomplete: true)
        XCTAssertEqual(texts.wealthCoinNoteBelowValue(partial, hasValue: true), "incomplete – unobserved")
        XCTAssertNil(texts.wealthCoinNoteBelowValue(partial, hasValue: false))
        let full = TaxWealthRow(coin: "BTC", amount: Decimal(string: "0.1")!, priceUsdt: 60000, valueUsdt: 6000,
                                valueTarget: 4800)
        XCTAssertNil(texts.wealthCoinNoteBelowValue(full, hasValue: true))
    }
}
