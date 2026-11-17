import XCTest
@testable import CryptoChecker

/// Hinweis «Coins ohne Logo»: die Liste unterscheidet «noch nicht geladen» (kommt
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

    /// Schalter «Coin-Logos» aus bzw. «Logos werden geladen» sichtbar: keine Zeile.
    func testHiddenWhenSwitchedOffOrWhileLoading() async {
        let lookup = MissingLogosModel.Lookup(firstLoadDone: { true }, hasLogo: { _ in false }, notYetLoaded: { _ in false })
        let entries = MissingLogosModel.entries([watch(1, "ZZQA")])
        for (enabled, loading) in [(false, false), (true, true)] {
            let model = MissingLogosModel()
            await model.refresh(entries, enabled: enabled, loading: loading, lookup: lookup)
            XCTAssertNil(model.missing, "enabled \(enabled), loading \(loading)")
        }
    }

    /// ✕ merkt genau die gezeigten Coins, in der App-Gruppe (nicht in iCloud), und blendet die Zeile aus.
    func testCloseRemembersExactlyTheShownCoins() async {
        let before = MissingLogoDismissals.shared.keys
        defer { MissingLogoDismissals.shared.dismiss(before) }
        let model = MissingLogosModel()
        let lookup = MissingLogosModel.Lookup(firstLoadDone: { true }, hasLogo: { _ in false }, notYetLoaded: { _ in false })
        await model.refresh(MissingLogosModel.entries([watch(1, "ZZQA"), watch(2, "ZZQB")]), enabled: true, loading: false,
                            lookup: lookup)
        let shown = Set(model.missing ?? [])
        XCTAssertEqual(shown.count, 2)
        model.dismiss()
        XCTAssertEqual(MissingLogoDismissals.shared.keys, shown)
        XCTAssertEqual(MissingLogoNotice.decode(SharedStorage.defaults.string(forKey: MissingLogoDismissals.key)), shown)
        let state = MissingLogoNotice.state(missing: Array(shown), dismissed: MissingLogoDismissals.shared.keys, loading: false,
                                            logosEnabled: true)
        XCTAssertFalse(state.visible)
    }
}
