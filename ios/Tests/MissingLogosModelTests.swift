import XCTest
@testable import CryptoChecker

/// Hinweis «Coins ohne Logo» (Audit A-01, früher Quelltext-Wächter): die Liste unterscheidet «noch nicht geladen» (kommt
/// mit dem nächsten Abgleich) von «kein Logo verfügbar» — Vermerk je Coin und Erklärung. Wie Android
/// `MissingLogosHintTest.kt` (Gerät).
@MainActor
final class MissingLogosModelTests: XCTestCase {
    private func watch(_ id: Int64, _ base: String) -> Watch {
        Watch(id: id, exchangeKey: "Binance", exchangeName: "Binance", baseAsset: base, quoteAsset: "USDT")
    }

    private func rows(notYet: Set<String>) async -> [MissingLogosSheet.Row] {
        let model = MissingLogosModel()
        let lookup = MissingLogosModel.Lookup(firstLoadDone: { true }, hasLogo: { _ in false },
                                              notYetLoaded: { notYet.contains($0) })
        let entries = MissingLogosModel.entries([watch(1, "ZZQA"), watch(2, "ZZQB")])
        await model.refresh(entries, enabled: true, loading: false, lookup: lookup)
        model.openList()
        return model.rows
    }

    func testNotYetLoadedIsNamedAsSuch() async {
        let keyA = MissingLogoNotice.key(exchangeKey: "Binance", base: "ZZQA", tradFi: false) ?? "ZZQA"
        let list = await rows(notYet: [keyA])
        XCTAssertEqual(list.map(\.symbol), ["ZZQA", "ZZQB"])
        XCTAssertEqual(list.map(\.notYet), [true, false])
        XCTAssertEqual(MissingLogosSheet.explanation(list), L("watchlist_missing_logos_explain_pending"))
    }

    func testNoLogoAvailableWithoutPendingCoins() async {
        let list = await rows(notYet: [])
        XCTAssertEqual(list.map(\.notYet), [false, false])
        XCTAssertEqual(MissingLogosSheet.explanation(list), L("watchlist_missing_logos_explain"))
    }

    func testHiddenUntilTheFirstLoadIsDone() async {
        let model = MissingLogosModel()
        let lookup = MissingLogosModel.Lookup(firstLoadDone: { false }, hasLogo: { _ in false }, notYetLoaded: { _ in true })
        await model.refresh(MissingLogosModel.entries([watch(1, "ZZQA")]), enabled: true, loading: false, lookup: lookup)
        XCTAssertNil(model.missing)
    }
}
