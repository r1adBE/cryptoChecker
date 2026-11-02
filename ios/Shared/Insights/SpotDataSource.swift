import Foundation

/// Spot-Kennzahlen (Marktkapitalisierung, Volumen, Umlauf …) für den Spot-Tab der Karte «Marktdaten» —
/// wie `SpotDataSource.kt`. Zuerst aus der CoinGecko-Top-40 (direkt bei CoinGecko, für alle Nutzer
/// gleich, verrät keinen Coin); steht der Coin nicht darin, EINE Anfrage an CoinGecko
/// `/coins/markets?symbols=<kürzel>`. Antworten (auch «nichts gefunden») ~10 min im Speicher; welcher Treffer
/// zum Paar gehört, entscheidet je Aufruf der Name (`SpotMarket.pick` mit `SpotMarket.Identity`).
actor SpotDataSource {
    static let shared = SpotDataSource()

    private struct Cached {
        let at: Int64
        let entries: [SpotData]
    }

    private var cache: [String: Cached] = [:]
    private var topList: (at: Int64, entries: [SpotData])?
    private var topText: (at: Int64, body: String?)?

    /// Daten zum Kürzel aus `SpotMarket.lookupSymbol` für das Paar mit `identity`; nil = keine passenden (oder nicht
    /// erreichbar). Passt in der Top-40 kein Treffer, fragt es einmal nach dem Kürzel.
    func fetch(symbol: String, identity: SpotMarket.Identity = SpotMarket.Identity()) async -> SpotData? {
        let now = EpochTime.nowMillis
        let top = await topEntries(now)
        if let fromTop = SpotMarket.pick(top, symbol: symbol, identity: identity) { return fromTop }
        if let hit = cache[symbol], fresh(hit.at, now) { return SpotMarket.pick(hit.entries, symbol: symbol, identity: identity) }
        let entries: [SpotData]
        do {
            let text = try await ExchangeHTTP.call(SpotMarket.url(symbol))
            entries = SpotMarket.parse(text)
        } catch {
            // Blatt geschlossen oder nicht erreichbar: nichts merken (nächstes Öffnen versucht es neu)
            return nil
        }
        cache[symbol] = Cached(at: now, entries: entries)
        return SpotMarket.pick(entries, symbol: symbol, identity: identity)
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
        let text = try? await ExchangeHTTP.call(SpotMarket.topURL)
        let body = text.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        // Abgebrochen (Blatt zu): nicht als «Liste leer» merken
        if !Task.isCancelled { topText = (now, body) }
        return body
    }
}
