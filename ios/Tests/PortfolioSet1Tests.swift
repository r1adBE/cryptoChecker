import XCTest
@testable import CryptoChecker

// Kleine Testklassen dieses Themas in einer Datei (R, Build 147); jede Klasse unverändert.

// ---- aus PortfolioCoinLedgerKeyTests

/// PERF3-R1: Schlüssel der Coin-Ansicht hängt nur an den Kursen des Coins und der Coins, deren Tageskurs
/// seine Zeilen brauchen — wie Android `PortfolioCoinDetailFlowTest`.
final class PortfolioCoinLedgerKeyTests: XCTestCase {
    private var trades: [PortfolioTx] {
        let buy = PortfolioTx(id: 1, coin: "BTC", type: .BUY, amountExact: 1, priceExact: 20_000, time: 86_400_000)
        // Tausch BTC → SOL ohne Wert: braucht den Tageskurs von BTC bzw. SOL
        var swap = PortfolioTx(id: 2, coin: "BTC", type: .SWAP, amountExact: 1, time: 2 * 86_400_000)
        swap.toCoin = "SOL"
        swap.toAmountExact = 10
        let eth = PortfolioTx(id: 3, coin: "ETH", type: .BUY, amountExact: 1, priceExact: 1_500, time: 3 * 86_400_000)
        return [buy, swap, eth]
    }

    func testGebuehrCoinsNurAusEigenenZeilen() {
        XCTAssertEqual(PortfolioCoinLedgerKey.feeCoins(for: "BTC", trades: trades), ["BTC", "SOL"])
        XCTAssertEqual(PortfolioCoinLedgerKey.feeCoins(for: "SOL", trades: trades), ["BTC", "SOL"])
        XCTAssertEqual(PortfolioCoinLedgerKey.feeCoins(for: "ETH", trades: trades), [])
    }

    func testFremderKursAendertDieRechnungNicht() {
        let fee = PortfolioCoinLedgerKey.feeCoins(for: "BTC", trades: trades)
        func input(_ prices: [String: Double]) -> PortfolioCoinLedgerInput {
            let needed = fee.union(["BTC"])
            return PortfolioCoinLedgerInput(key: PortfolioCoinLedgerKey(
                coin: "BTC", trades: trades, prices: prices.filter { needed.contains($0.key) }, method: .FIFO,
                closesStamp: 0, feeCoins: fee), closes: [:])
        }
        let a = input(["BTC": 40_000, "SOL": 100, "ETH": 2_000])
        XCTAssertEqual(a.key, input(["BTC": 40_000, "SOL": 100, "ETH": 2_100]).key)
        XCTAssertNotEqual(a.key, input(["BTC": 40_000, "SOL": 110, "ETH": 2_000]).key)
        // Gleiches Ergebnis wie mit allen Kursen (frühere Rechnung)
        let full = PortfolioCoinLedgerInput(key: PortfolioCoinLedgerKey(
            coin: "BTC", trades: trades, prices: ["BTC": 40_000, "SOL": 100, "ETH": 2_000], method: .FIFO,
            closesStamp: 0, feeCoins: fee), closes: [:])
        XCTAssertEqual(PortfolioCoinLedgerInput.ledger(a), PortfolioCoinLedgerInput.ledger(full))
    }
}

// ---- aus PortfolioCostFxTests

/// Gemeinsame Testfälle `cost_fx.json` (Android: `PortfolioCostFxParityTest.kt`): «Seit Kauf» und Einstand der
/// Gesamtkarte in der Anzeigewährung zum Kurs der Erwerbstage (`PortfolioCostFx`) — FIFO, FIFO je Wallet und Ø, fehlender
/// Devisenkurs, Kurs bis 7 Tage davor, Kauf heute.
final class PortfolioCostFxTests: XCTestCase {

    private typealias S = TestSupport

    private func dec(_ value: Any?) -> Decimal? { PortfolioDecimal.parse(value as? String) }

    private func decMap(_ value: Any?) -> [Int: Decimal] {
        var out: [Int: Decimal] = [:]
        for (key, v) in value as? [String: Any] ?? [:] {
            if let k = Int(key), let r = dec(v) { out[k] = r }
        }
        return out
    }

    private func trades(_ value: Any?) -> [PortfolioTx] {
        S.list(value).map { t in
            PortfolioTx(id: (t["id"] as? NSNumber)?.int64Value ?? 0, coin: t["coin"] as? String ?? "",
                        type: PortfolioTxType(rawValue: t["type"] as? String ?? "") ?? .BUY,
                        amountExact: dec(t["amount"]) ?? 0, priceExact: dec(t["price"]),
                        time: (t["time"] as? NSNumber)?.int64Value ?? 0)
        }
    }

    private func prices(_ value: Any?) -> [String: Double] {
        var out: [String: Double] = [:]
        for (coin, v) in value as? [String: Any] ?? [:] { out[coin] = S.number(v) }
        return out
    }

    private func summary(_ c: [String: Any]) -> PortfolioSummary {
        PortfolioCalculator.summarize(trades(c["trades"]), prices: prices(c["prices"]),
                                      method: CostMethod.fromName(c["method"] as? String))
    }

    func testCases() throws {
        let d = try S.fixture("cost_fx")
        let currency = d["currency"] as? String ?? ""
        let today = (d["todayEpochDay"] as? NSNumber)?.intValue ?? 0
        let currentRate = try XCTUnwrap(dec(d["currentRate"]))
        let cases = S.list(d["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 3)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let s = summary(c)
            let e = c["expected"] as? [String: Any] ?? [:]
            XCTAssertEqual(PortfolioCostFx.firstDay(s), (e["firstDay"] as? NSNumber)?.intValue, "\(name) firstDay")
            let f = PortfolioCostFx.figures(s, currency: currency, currentRate: currentRate, todayEpochDay: today,
                                            rates: decMap(c["rates"]), usdtUsd: decMap(c["usdtUsd"]))
            XCTAssertEqual(f.unrealized, dec(e["unrealized"]), "\(name) unrealized")
            XCTAssertEqual(f.invested, dec(e["invested"]), "\(name) invested")
            if let percent = S.number(e["percent"]) {
                XCTAssertEqual(try XCTUnwrap(f.unrealizedPercent, "\(name) percent"), percent, accuracy: 1e-9, "\(name) percent")
            } else {
                XCTAssertNil(f.unrealizedPercent, "\(name) percent")
            }
            XCTAssertEqual(f.unrealizedFxMissing, e["unrealizedFxMissing"] as? Bool, "\(name) unrealizedFxMissing")
            XCTAssertEqual(f.investedFxMissing, e["investedFxMissing"] as? Bool, "\(name) investedFxMissing")
            XCTAssertEqual(f.fxMissingCoins, e["fxMissingCoins"] as? [String], "\(name) fxMissingCoins")
            XCTAssertEqual(f.usdtApproximate, e["usdtApproximate"] as? Bool, "\(name) usdtApproximate")
        }
    }

    /// Teile des Einstands gehen exakt auf den Einstand des Teils mit Kaufpreis auf (alle Methoden).
    func testPartsSumToPricedCost() throws {
        let d = try S.fixture("cost_fx")
        for c in S.list(d["cases"]) {
            for p in summary(c).open {
                guard let cost = p.pricedCostExact else { continue }
                let sum = p.pricedCostParts.reduce(Decimal(0)) { $0 + $1.usdt }
                XCTAssertEqual(sum, cost, "\(c["name"] ?? "") \(p.coin) parts")
            }
        }
    }
}

// ---- aus PortfolioCsvReadFileTests

/// CSV-Datei höchstens bis zur Grenze lesen (`PortfolioCsvImporter.readFile`): auch wenn die Grösse
/// vorab unbekannt ist, liest der Import nie mehr als Grenze + 1 Byte — wie `BackupManager.readFile`.
final class PortfolioCsvReadFileTests: XCTestCase {
    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("csv-read-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func file(_ bytes: Int) throws -> URL {
        let url = dir.appendingPathComponent("\(bytes).csv")
        try Data(repeating: UInt8(ascii: "a"), count: bytes).write(to: url)
        return url
    }

    func testFileAtLimitIsReadCompletely() throws {
        XCTAssertEqual(PortfolioCsvImporter.readFile(try file(64), maxBytes: 64)?.count, 64)
    }

    func testFileOverLimitIsRejected() throws {
        XCTAssertNil(PortfolioCsvImporter.readFile(try file(65), maxBytes: 64))
    }

    func testMissingFileIsNil() {
        XCTAssertNil(PortfolioCsvImporter.readFile(dir.appendingPathComponent("fehlt.csv"), maxBytes: 64))
    }

    /// Mit Grund: über der Grenze «zu gross» (eigene Meldung), fehlende Datei «nicht lesbar».
    func testReadCheckedNamesTheReason() throws {
        XCTAssertEqual(try PortfolioCsvImporter.readChecked(try file(64), maxBytes: 64).get().count, 64)
        XCTAssertEqual(PortfolioCsvImporter.readChecked(try file(65), maxBytes: 64), .failure(.tooLarge))
        XCTAssertEqual(PortfolioCsvImporter.readChecked(dir.appendingPathComponent("fehlt.csv"), maxBytes: 64),
                       .failure(.unreadable))
    }

    func testDefaultLimitIsSixteenMegabytes() throws {
        XCTAssertEqual(PortfolioCsvImporter.maxBytes, 16 * 1024 * 1024)
        XCTAssertEqual(PortfolioCsvImporter.readFile(try file(10))?.count, 10)
    }
}

// ---- aus PortfolioDecimalDigitsTests

/// `PortfolioDecimal.mantissaDigits` zählt die Ziffern der Mantisse ohne Text — gleich wie die Länge von
/// `NSDecimalString` der Mantisse; `roundSignificant` rundet damit genau wie über den Text.
final class PortfolioDecimalDigitsTests: XCTestCase {
    private static let posix = Locale(identifier: "en_US_POSIX")

    /// Ziffern der Mantisse über den Text (Mantisse mit Exponent 0, ohne Nullen am Ende).
    private static func textDigits(_ value: Decimal) -> Int {
        var compact = value
        NSDecimalCompact(&compact)
        var mantissa = compact.significand
        return NSDecimalString(&mantissa, posix).filter(\.isNumber).count
    }

    /// `roundSignificant` mit der Zählung über den Text (Referenz).
    private static func roundByText(_ value: Decimal, digits: Int = PortfolioDecimal.significantDigits) -> Decimal {
        guard value != 0, !value.isNaN, value.isFinite else { return value }
        var compact = value
        NSDecimalCompact(&compact)
        let excess = textDigits(compact) - digits
        guard excess > 0 else { return compact }
        var rounded = Decimal()
        NSDecimalRound(&rounded, &compact, -(Int(compact.exponent) + excess), .bankers)
        return rounded
    }

    private static func dec(_ text: String) -> Decimal {
        Decimal(string: text, locale: posix) ?? .nan
    }

    func testDigitsAtPowersOfTen() {
        for k in 1...38 {
            let nines = PortfolioDecimalDigitsTests.dec(String(repeating: "9", count: k))
            let power = PortfolioDecimalDigitsTests.dec("1" + String(repeating: "0", count: k))
            var compactPower = power
            NSDecimalCompact(&compactPower)
            XCTAssertEqual(PortfolioDecimal.mantissaDigits(nines), k, "10^\(k) − 1")
            XCTAssertEqual(PortfolioDecimal.mantissaDigits(nines), PortfolioDecimalDigitsTests.textDigits(nines))
            XCTAssertEqual(PortfolioDecimal.mantissaDigits(compactPower), 1, "10^\(k) kompakt")
        }
        let largest = PortfolioDecimalDigitsTests.dec("340282366920938463463374607431768211455")
        XCTAssertEqual(PortfolioDecimal.mantissaDigits(largest), PortfolioDecimalDigitsTests.textDigits(largest))
        XCTAssertEqual(PortfolioDecimal.mantissaDigits(0), 0)
        XCTAssertEqual(PortfolioDecimal.mantissaDigits(1), 1)
        XCTAssertEqual(PortfolioDecimal.mantissaDigits(-12_345), 5)
    }

    func testDivisionsRoundLikeTextCount() {
        var state: UInt64 = 41
        func next(_ bound: Int) -> Int {
            state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return Int((state >> 33) % UInt64(bound))
        }
        for _ in 0..<2_000 {
            let a = Decimal(sign: .plus, exponent: -next(19), significand: Decimal(next(1_000_000_000) + 1))
            let b = Decimal(sign: .plus, exponent: -next(9), significand: Decimal(next(99_999) + 1))
            let product = a * b
            let quotient = a / b
            XCTAssertEqual(PortfolioDecimal.mantissaDigits(quotient), PortfolioDecimalDigitsTests.textDigits(quotient))
            let expected = PortfolioDecimalDigitsTests.roundByText(quotient)
            XCTAssertEqual(PortfolioDecimal.roundSignificant(quotient), expected)
            XCTAssertEqual(PortfolioDecimal.roundSignificant(product), PortfolioDecimalDigitsTests.roundByText(product))
            let short = PortfolioDecimal.roundSignificant(quotient, digits: 8)
            XCTAssertEqual(short, PortfolioDecimalDigitsTests.roundByText(quotient, digits: 8))
            let negative = -quotient
            let expectedNegative = PortfolioDecimalDigitsTests.roundByText(negative)
            XCTAssertEqual(PortfolioDecimal.roundSignificant(negative), expectedNegative)
        }
    }
}

// ---- aus PortfolioDecimalRoundingTests

/// Eine Rundungsregel (halbe auf gerade) für Rechnen (`PortfolioDecimal.amount`) und Speichern
/// (`PortfolioDecimal.text`); eine Menge unter 10⁻¹⁸ ist nach dem Runden 0 und damit ungültig. Wie
/// `PortfolioTxCleanupTest` (Android).
final class PortfolioDecimalRoundingTests: XCTestCase {

    private func dec(_ s: String) -> Decimal { Decimal(string: s, locale: Locale(identifier: "en_US_POSIX")) ?? .nan }

    func testEineRundungsregelFuerRechnenUndSpeichern() {
        for v in ["0.0000000000000000015", "0.0000000000000000025", "2.0000000000000000035", "0.0000000000000000005"] {
            XCTAssertEqual(PortfolioDecimal.amount(dec(v)), dec(PortfolioDecimal.text(dec(v))), v)
        }
        XCTAssertEqual(PortfolioDecimal.text(dec("0.0000000000000000015")), "0.000000000000000002")
        XCTAssertEqual(PortfolioDecimal.text(dec("0.0000000000000000025")), "0.000000000000000002")
        XCTAssertEqual(PortfolioDecimal.text(dec("0.0000000000000000005")), "0")
    }

    func testMengeUnterEinemWeiIstNachDemRundenNull() {
        // Das Erfassen-Blatt prüft die gerundete Menge (> 0): unter 1 Wei ist ungültig statt als 0 gespeichert
        XCTAssertEqual(PortfolioDecimal.amount(dec("0.0000000000000000004")), 0)
        XCTAssertEqual(PortfolioDecimal.amount(dec("1.0000000000000000005")), 1)
    }
}

// ---- aus PortfolioDeleteCoinFeeTests

/// «Coin löschen» entfernt auch die Gebühr in diesem Coin bei Transaktionen anderer Coins (sonst erschiene
/// der Coin als Position «mehr abgegeben als erfasst»); «Rückgängig» legt die Originalfassung wieder an. Wie
/// `PortfolioCoinDeletionTest` (Android).
final class PortfolioDeleteCoinFeeTests: XCTestCase {

    private func file() -> PortfolioFile {
        var transfer = PortfolioTx(id: 2, coin: "ETH", type: .TRANSFER, amount: 1, time: 2)
        transfer.feeCoin = "bnb"
        transfer.feeCoinAmountExact = Decimal(string: "0.01")
        transfer.feeCoinPriceExact = 600
        return PortfolioFile(transactions: [
            PortfolioTx(id: 1, coin: "BNB", type: .BUY, amount: 1, priceUsdt: 500, time: 1),
            transfer,
            PortfolioTx(id: 3, coin: "ETH", type: .BUY, amount: 2, priceUsdt: 10, time: 3),
        ], nextId: 4)
    }

    func testGebuehrInGeloeschtemCoinWirdMitEntfernt() {
        var written: PortfolioFile?
        let removed = PortfolioEdits.deleteCoin("BNB", in: file(), write: { written = $0; return true })
        // Gelöscht: der BNB-Kauf; geändert (Originalfassung für «Rückgängig»): die ETH-Umbuchung
        XCTAssertEqual(removed.map(\.id), [1, 2])
        XCTAssertEqual(removed.last?.feeCoin, "bnb")
        XCTAssertEqual(written?.transactions.map(\.id), [2, 3])
        let kept = written?.transactions.first { $0.id == 2 }
        XCTAssertNil(kept?.feeCoin)
        XCTAssertNil(kept?.feeCoinAmountExact)
        XCTAssertNil(kept?.feeCoinPriceExact)
        // «Rückgängig» ersetzt je id: alles wie vorher
        var restored: PortfolioFile?
        XCTAssertTrue(PortfolioEdits.restore(removed, in: written!, write: { restored = $0; return true }))
        XCTAssertEqual(restored?.transactions.first { $0.id == 2 }?.feeCoin, "bnb")
        XCTAssertEqual(restored?.transactions.count, 3)
    }

    func testNurGebuehrBezugWirdTrotzdemBereinigt() {
        // Coin ohne eigene Transaktion, nur als Gebühr erfasst (Phantom-Position): Löschen entfernt die Gebühr
        var f = file()
        f.transactions.removeAll { $0.id == 1 }
        var written: PortfolioFile?
        let removed = PortfolioEdits.deleteCoin("bnb", in: f, write: { written = $0; return true })
        XCTAssertEqual(removed.map(\.id), [2])
        XCTAssertNil(written?.transactions.first { $0.id == 2 }?.feeCoin)
        XCTAssertFalse(PortfolioEdits.paysFee(f.transactions[0], in: "ETH"))
        XCTAssertTrue(PortfolioEdits.paysFee(f.transactions[0], in: "BNB"))
    }
}

// ---- aus PortfolioEmptyStateTests

/// Leeres Portfolio: die Menge im Beispiel steht als Platzhalter und kommt aus dem Zahlenformat der App
/// (wie die Bestände). Wie `PortfolioEmptyStateTest`.
final class PortfolioEmptyStateTests: XCTestCase {
    func testMengeWieBestaende() {
        XCTAssertEqual(PortfolioEmptyExample.amount, 0.01)
        XCTAssertEqual(PortfolioEmptyExample.amountText(), PriceFormat.amount(0.01))
    }

    func testBeispielEnthaeltFormatierteMenge() {
        let amount = PortfolioEmptyExample.amountText()
        let text = L("portfolio_empty_example_plain", amount)
        XCTAssertTrue(text.contains("\(amount) BTC"), text)
        XCTAssertFalse(text.contains("%"), text)
    }
}
