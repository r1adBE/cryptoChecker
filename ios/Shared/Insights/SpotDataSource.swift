import Foundation

/// Spot-Kennzahlen (Marktkapitalisierung, Volumen, Umlauf …) für den Spot-Tab der Karte «Marktdaten» —
/// wie `SpotDataSource.kt`. Zuerst aus der CoinGecko-Top-40 (direkt bei CoinGecko, für alle Nutzer
/// gleich, verrät keinen Coin); steht der Coin nicht darin, EINE Anfrage an CoinGecko
/// `/coins/markets?symbols=<kürzel>`. Ergebnisse (auch «nichts gefunden») ~10 min im Speicher.
actor SpotDataSource {
    static let shared = SpotDataSource()

    private struct Cached {
        let at: Int64
        let data: SpotData?
    }

    private var cache: [String: Cached] = [:]
    private var topList: (at: Int64, entries: [SpotData])?
    private var topText: (at: Int64, body: String?)?

    /// Daten zum Kürzel aus `SpotMarket.lookupSymbol`; nil = keine (oder nicht erreichbar).
    func fetch(symbol: String) async -> SpotData? {
        let now = EpochTime.nowMillis
        if let hit = cache[symbol], fresh(hit.at, now) { return hit.data }
        let result: SpotData?
        let top = await topEntries(now)
        if let fromTop = SpotMarket.pick(top, symbol: symbol) {
            result = fromTop
        } else {
            do {
                let text = try await MarketHTTP.call(SpotMarket.url(symbol))
                result = SpotMarket.pick(SpotMarket.parse(text), symbol: symbol)
            } catch {
                // Blatt geschlossen oder nicht erreichbar: nichts merken (nächstes Öffnen versucht es neu)
                return nil
            }
        }
        cache[symbol] = Cached(at: now, data: result)
        return result
    }

    private func fresh(_ at: Int64, _ now: Int64) -> Bool {
        now - at >= 0 && now - at < SpotMarket.cacheTTLMillis
    }

    /// Top 40 (gleiche Abfrage für alle; fehlt sie, gibt es die Einzelabfrage).
    private func topEntries(_ now: Int64) async -> [SpotData] {
        if let topList, fresh(topList.at, now) { return topList.entries }
        let entries = SpotMarket.parse(await topBody(now))
        // Abgebrochen (Blatt zu): nicht als «Liste leer» merken
        if !Task.isCancelled { topList = (now, entries) }
        return entries
    }

    /// Breiter Markt für «Warum?» (`BroadMarket`): Median der 24-h-Veränderung der Top-Coins aus
    /// derselben Top-40 (ohne `excludeBase`); nil, wenn sie fehlt, zu alt ist oder zu wenige
    /// Coins hat. Nichts Coin-Bezogenes wird gesendet (wie Android).
    func broadMarket(excludeBase: String) async -> BroadMarketSnapshot? {
        let now = EpochTime.nowMillis
        return BroadMarket.snapshot(BroadMarket.parse(await topBody(now)), excludeBase: excludeBase, now: now)
    }

    /// Antwort der Top-40 von CoinGecko (`SpotMarket.topURL`); ~10 Min. im Speicher, auch «fehlt»
    /// (so auch Pause nach Fehler/429). nil = keine.
    private func topBody(_ now: Int64) async -> String? {
        if let topText, fresh(topText.at, now) { return topText.body }
        let text = try? await MarketHTTP.call(SpotMarket.topURL)
        let body = text.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        // Abgebrochen (Blatt zu): nicht als «Liste leer» merken
        if !Task.isCancelled { topText = (now, body) }
        return body
    }
}
