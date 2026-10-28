import XCTest
@testable import CryptoChecker

/// «Alle», «FAV», «Token» und Gruppen — wie `WatchFilterTest.kt`.
final class WatchFilterTests: XCTestCase {
    func testAllFavoritesAndGroups() {
        XCTAssertTrue(WatchFilter.matches(nil, groupName: "Alts", favorite: false))
        XCTAssertTrue(WatchFilter.matches(WatchFilter.favorites, groupName: "Alts", favorite: true))
        XCTAssertTrue(WatchFilter.matches(WatchFilter.favorites, groupName: nil, favorite: true))
        XCTAssertFalse(WatchFilter.matches(WatchFilter.favorites, groupName: "Alts", favorite: false))
        XCTAssertTrue(WatchFilter.matches("Alts", groupName: "Alts", favorite: false))
        XCTAssertFalse(WatchFilter.matches("Alts", groupName: "Majors", favorite: true))
        // Eine Gruppe, die «FAV» heisst, ist nicht der Favoriten-Filter
        XCTAssertFalse(WatchFilter.isFavorites("FAV"))
        XCTAssertTrue(WatchFilter.matches("FAV", groupName: "FAV", favorite: false))
        // Gleicher gespeicherter Wert wie Android (Sicherungen behalten die Auswahl)
        XCTAssertEqual(WatchFilter.favorites, "\u{1}FAV")
    }

    func testTokensAreEverythingButTradFi() {
        XCTAssertEqual(WatchFilter.tokens, "\u{1}TOK")
        XCTAssertTrue(WatchFilter.matches(WatchFilter.tokens, groupName: nil, favorite: false))
        XCTAssertTrue(WatchFilter.matches(WatchFilter.tokens, groupName: AutoGroup.dated, favorite: false))
        XCTAssertTrue(WatchFilter.matches(WatchFilter.tokens, groupName: "Alts", favorite: true))
        XCTAssertFalse(WatchFilter.matches(WatchFilter.tokens, groupName: AutoGroup.tradFi, favorite: false))
        XCTAssertFalse(WatchFilter.matches(WatchFilter.tokens, groupName: AutoGroup.tradFi, favorite: true))
        XCTAssertTrue(WatchFilter.isTokens(WatchFilter.tokens))
        XCTAssertTrue(WatchFilter.isBuiltIn(WatchFilter.tokens))
        XCTAssertTrue(WatchFilter.isBuiltIn(WatchFilter.favorites))
        XCTAssertFalse(WatchFilter.isBuiltIn(nil))
        XCTAssertFalse(WatchFilter.isBuiltIn(AutoGroup.tradFi))
        // Eine Gruppe, die «TOK» oder «Token» heisst, ist nicht der Token-Filter
        XCTAssertFalse(WatchFilter.isTokens("TOK"))
        XCTAssertTrue(WatchFilter.matches("Token", groupName: "Token", favorite: false))
        XCTAssertFalse(WatchFilter.matches("Token", groupName: nil, favorite: false))
    }

    func testTokenChipOnlyWithTradFiAndOtherPairs() {
        XCTAssertTrue(WatchFilter.showsTokens([AutoGroup.tradFi, nil]))
        XCTAssertTrue(WatchFilter.showsTokens([AutoGroup.tradFi, AutoGroup.dated]))
        XCTAssertTrue(WatchFilter.showsTokens(["Alts", AutoGroup.tradFi]))
        // Nur TradFi: gleich leer; kein TradFi: gleich «Alle»
        XCTAssertFalse(WatchFilter.showsTokens([AutoGroup.tradFi, AutoGroup.tradFi]))
        XCTAssertFalse(WatchFilter.showsTokens([nil, "Alts", AutoGroup.dated]))
        XCTAssertFalse(WatchFilter.showsTokens([String?]()))
        // Gross-/Kleinschreibung zählt wie beim Gruppenfilter
        XCTAssertFalse(WatchFilter.showsTokens(["tradfi", nil]))
    }

    func testVanishedSelectionFallsBackToAll() {
        let mixed = [watch(1, AutoGroup.tradFi), watch(2, nil), watch(3, "Alts")]
        let onlyCrypto = [watch(2, nil), watch(3, "Alts")]
        XCTAssertTrue(WatchFilter.isAvailable(nil, in: onlyCrypto))
        XCTAssertTrue(WatchFilter.isAvailable(WatchFilter.favorites, in: []))
        XCTAssertTrue(WatchFilter.isAvailable(WatchFilter.tokens, in: mixed))
        XCTAssertFalse(WatchFilter.isAvailable(WatchFilter.tokens, in: onlyCrypto))
        XCTAssertFalse(WatchFilter.isAvailable(WatchFilter.tokens, in: [watch(1, AutoGroup.tradFi)]))
        XCTAssertTrue(WatchFilter.isAvailable("Alts", in: mixed))
        XCTAssertFalse(WatchFilter.isAvailable("Old", in: mixed))
    }

    // MARK: Deaktivierte Paare (wie QuickViewTest.kt)

    private let now: Int64 = 1_000_000_000
    private let staleAfter: Int64 = 600_000

    /// BTC aktuell, ETH veraltet, SOL/XRP deaktiviert (alter bzw. nie ein Kurs), LUNA nicht mehr gehandelt.
    private func disabledSample() -> [Watch] {
        var btc = watch(1, nil); btc.lastUpdate = now - 60_000
        var eth = watch(2, nil); eth.baseAsset = "ETH"; eth.lastUpdate = now - 3_600_000
        var sol = watch(3, nil); sol.baseAsset = "SOL"; sol.lastUpdate = now - 86_400_000; sol.disabled = true
        var xrp = watch(4, nil); xrp.baseAsset = "XRP"; xrp.disabled = true
        var luna = watch(5, nil); luna.baseAsset = "LUNA"; luna.lastUpdate = now - 86_400_000
        luna.lastError = NotTraded.marker
        return [btc, eth, sol, xrp, luna]
    }

    func testDisabledNeverCountsAsStale() {
        let stale = disabledSample().filter { WatchlistTime.isStale($0, now: now, staleAfter: staleAfter) }
        XCTAssertEqual(stale.map(\.baseAsset), ["ETH"])
        XCTAssertFalse(DisabledView.isStale(disabled: true, notTraded: false, lastUpdate: 0, now: now, staleAfter: staleAfter))
        XCTAssertTrue(DisabledView.isStale(disabled: false, notTraded: false, lastUpdate: 0, now: now, staleAfter: staleAfter))
    }

    func testStatusCountsOnlyActivePairs() {
        // Wie die Statuszeile: erst die deaktivierten weg, dann die nicht gehandelten
        let traded = DisabledView.active(disabledSample()) { $0.disabled }.filter { !$0.isNotTraded }
        XCTAssertEqual(traded.map(\.baseAsset), ["BTC", "ETH"])
        XCTAssertEqual(traded.filter { WatchlistTime.isStale($0, now: now, staleAfter: staleAfter) }.count, 1)
        // Ohne das veraltete Paar: «Alle Kurse aktuell», trotz alter deaktivierter Kurse
        let fresh = disabledSample().filter { $0.baseAsset != "ETH" }
        let freshTraded = DisabledView.active(fresh) { $0.disabled }.filter { !$0.isNotTraded }
        XCTAssertEqual(freshTraded.filter { WatchlistTime.isStale($0, now: now, staleAfter: staleAfter) }.count, 0)
    }

    func testDisabledViewShowsOnlyDisabledInListOrder() {
        let sample = disabledSample()
        XCTAssertEqual(DisabledView.count(sample) { $0.disabled }, 2)
        XCTAssertEqual(DisabledView.only(sample) { $0.disabled }.map(\.baseAsset), ["SOL", "XRP"])
        // Keine deaktivierten mehr: kein Hinweis, die Ansicht endet von selbst, Liste unverändert
        let none = sample.map { w -> Watch in var c = w; c.disabled = false; return c }
        XCTAssertEqual(DisabledView.count(none) { $0.disabled }, 0)
        XCTAssertTrue(DisabledView.only(none) { $0.disabled }.isEmpty)
        XCTAssertEqual(DisabledView.active(none) { $0.disabled }.map(\.id), none.map(\.id))
        XCTAssertNotEqual(QuickView.disabled, QuickView.stale)
    }

    func testDisabledPairsAreNeverSubscribedLive() {
        var w = watch(1, nil)
        XCTAssertFalse(w.livePair.notTraded)
        w.disabled = true
        XCTAssertTrue(w.livePair.notTraded)
        // Ein später Live-Takt ändert den gezeigten letzten Kurs nicht
        w.lastPrice = 100
        let quote = LiveQuote(price: 120, change24h: 1, time: 5)
        XCTAssertEqual(w.withLive(quote, rollingBasis: true).lastPrice, 100)
    }

    private func watch(_ id: Int64, _ group: String?) -> Watch {
        var w = Watch(id: id, exchangeKey: "Binance", exchangeName: "Binance", baseAsset: "BTC", quoteAsset: "USDT")
        w.groupName = group
        return w
    }
}

/// «Alpha» und die Art eines Paars — wie `WatchFilterTest.kt` (alpha…) und `AssetKindTest.kt`.
final class AssetKindTests: XCTestCase {
    private let alpha: Set<String> = ["AIA", "AGT"]

    func testTradFiWinsOverEverything() {
        XCTAssertEqual(AssetKind.of(exchangeKey: "BinanceFutures", base: "NVDA", tradFi: true, alphaSymbols: alpha), .TRADFI)
        // Auch ein Kürzel der Alpha-Liste ist als TradFi-Kontrakt TradFi
        XCTAssertEqual(AssetKind.of(exchangeKey: "BinanceFutures", base: "AIA", tradFi: true, alphaSymbols: alpha), .TRADFI)
    }

    func testAlphaOnlyForListedSymbols() {
        XCTAssertEqual(AssetKind.of(exchangeKey: "BinanceFutures", base: "AIA", tradFi: false, alphaSymbols: alpha), .ALPHA)
        XCTAssertEqual(AssetKind.of(exchangeKey: "Bybit", base: "agt", tradFi: false, alphaSymbols: alpha), .ALPHA)
        // Hebel-Kürzel wie das Kürzel
        XCTAssertEqual(AssetKind.of(exchangeKey: "BinanceFutures", base: "1000AIA", tradFi: false, alphaSymbols: alpha), .ALPHA)
        XCTAssertEqual(AssetKind.of(exchangeKey: "BinanceFutures", base: "BTC", tradFi: false, alphaSymbols: alpha), .TOKEN)
    }

    func testBinanceSpotAndDexNeverAlpha() {
        // An Binance Spot gehandelt = regulär gelistet
        XCTAssertEqual(AssetKind.of(exchangeKey: "Binance", base: "AIA", tradFi: false, alphaSymbols: alpha), .TOKEN)
        // DEX: fremder Token darf gleich heissen
        XCTAssertEqual(AssetKind.of(exchangeKey: "DexScreener", base: "AIA", tradFi: false, alphaSymbols: alpha), .TOKEN)
    }

    func testNoAlphaListMeansToken() {
        XCTAssertEqual(AssetKind.of(exchangeKey: "BinanceFutures", base: "AIA", tradFi: false, alphaSymbols: []), .TOKEN)
    }

    func testAlphaIsBuiltInFilterOnAlphaPairs() {
        // Gleicher gespeicherter Wert wie Android
        XCTAssertEqual(WatchFilter.alpha, "\u{1}ALP")
        XCTAssertTrue(WatchFilter.isAlpha(WatchFilter.alpha))
        XCTAssertTrue(WatchFilter.isBuiltIn(WatchFilter.alpha))
        XCTAssertTrue(WatchFilter.matches(WatchFilter.alpha, groupName: nil, favorite: false, alpha: true))
        XCTAssertTrue(WatchFilter.matches(WatchFilter.alpha, groupName: "Alts", favorite: true, alpha: true))
        XCTAssertFalse(WatchFilter.matches(WatchFilter.alpha, groupName: nil, favorite: false, alpha: false))
        // «Token» zeigt Alpha-Tokens weiterhin (ein Alpha-Token ist auch ein Token)
        XCTAssertTrue(WatchFilter.matches(WatchFilter.tokens, groupName: nil, favorite: false, alpha: true))
        // Eine Gruppe «ALP» ist kein Filter
        XCTAssertFalse(WatchFilter.isAlpha("ALP"))
    }

    func testAlphaChipOnlyWithAlphaAndOtherPairs() {
        XCTAssertTrue(WatchFilter.showsAlpha([true, false]))
        XCTAssertFalse(WatchFilter.showsAlpha([true, true]))
        XCTAssertFalse(WatchFilter.showsAlpha([false]))
        XCTAssertFalse(WatchFilter.showsAlpha([Bool]()))
    }
}

