import XCTest
@testable import CryptoChecker

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
