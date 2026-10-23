import Foundation

/// Erster Schritt jedes Logo-Abgleichs: Binance-Symbolliste und Alpha-Liste **zuerst und
/// gleichzeitig** holen — unabhängig von CoinGecko, den Paarlisten und den Bildern. Namen und
/// Alpha-Kennzeichen stehen so wenige Sekunden nach dem Start bereit; vorher kamen sie erst nach den
/// Paarlisten, der Nasdaq-Liste und allen Seiten der CoinGecko-Rangliste.
///
/// Jede Quelle ist für sich gekapselt: Scheitert eine, bleibt die andere. Fehlt eine fällige Liste
/// direkt, kommt sie aus `coin_meta.json` (`CoinMeta.url`, nur Binance-Daten), falls diese neuer ist
/// als der letzte direkte Stand. Gleich in Android `CoinLists.kt`.
enum CoinLists {

    /// Woher eine Liste in diesem Abgleich kam.
    enum Source: String { case absent = "NONE", direct = "DIRECT", mirror = "MIRROR" }

    /// Was zu holen ist (fällig je Liste, letzter direkter Erfolg in ms, Ersatzdatei erlaubt, gespeicherte
    /// Binance-Liste — eine Ersatzdatei ohne Namen ergänzt sie nur, `CoinMeta.mirrorBinance`).
    struct Request {
        var binanceDue: Bool
        var alphaDue: Bool
        var lastBinanceAt: Int64
        var lastAlphaAt: Int64
        var now: Int64
        var mirrorAllowed = true
        var savedBinance: [CoinLogos.BinanceEntry] = []
    }

    /// Ergebnis: neue Listen (nil = nicht neu) mit Herkunft; `networkError` = eine fällige Quelle
    /// scheiterte am Netz; `mirrorFailed` = Ersatzdatei gefragt, aber unbrauchbar.
    struct Result {
        var binance: [CoinLogos.BinanceEntry]?
        var binanceSource: Source
        var alpha: [CoinLogos.AlphaEntry]?
        var alphaSource: Source
        var networkError: Bool
        var mirrorFailed: Bool

        /// Eine fällige Liste fehlt auch nach der Ersatzdatei.
        func missing(_ request: Request) -> Bool {
            (request.binanceDue && binance == nil) || (request.alphaDue && alpha == nil)
        }
    }

    /// Text einer Antwort; wirft bei Fehlern (Netz, HTTP-Status).
    typealias Fetcher = @Sendable (String) async throws -> String

    /// Ergebnis eines Abrufs: Wert (nil = fehlt) und ob der Fehler am Netz lag.
    private struct Attempt<T> {
        var value: T?
        var networkError: Bool
    }

    private static func binanceAttempt(_ fetch: Fetcher, due: Bool,
                                       isNetworkError: @Sendable (Error) -> Bool) async -> Attempt<[CoinLogos.BinanceEntry]> {
        guard due else { return Attempt(value: nil, networkError: false) }
        do {
            let text = try await fetch(CoinLogos.binanceListURL)
            let entries = CoinMeta.parseBinanceList(text)
            return Attempt(value: (entries?.isEmpty ?? true) ? nil : entries, networkError: false)
        } catch {
            return Attempt(value: nil, networkError: isNetworkError(error))
        }
    }

    private static func alphaAttempt(_ fetch: Fetcher, due: Bool,
                                     isNetworkError: @Sendable (Error) -> Bool) async -> Attempt<[CoinLogos.AlphaEntry]> {
        guard due else { return Attempt(value: nil, networkError: false) }
        do {
            let text = try await fetch(CoinLogos.alphaListURL)
            return Attempt(value: CoinLogos.parseAlpha(text), networkError: false)
        } catch {
            return Attempt(value: nil, networkError: isNetworkError(error))
        }
    }

    /// Beide Listen gleichzeitig holen, dann bei Bedarf die Ersatzdatei — wie Android `fetch`.
    static func fetch(_ fetch: @escaping Fetcher, request: Request,
                      isNetworkError: @escaping @Sendable (Error) -> Bool) async -> Result {
        async let binanceTask = binanceAttempt(fetch, due: request.binanceDue, isNetworkError: isNetworkError)
        async let alphaTask = alphaAttempt(fetch, due: request.alphaDue, isNetworkError: isNetworkError)
        let b = await binanceTask
        let a = await alphaTask
        var result = Result(binance: b.value, binanceSource: b.value == nil ? .absent : .direct,
                            alpha: a.value, alphaSource: a.value == nil ? .absent : .direct,
                            networkError: b.networkError || a.networkError, mirrorFailed: false)
        // Ersatzdatei nur für fällige Listen, die direkt fehlten (ein direkter Erfolg gilt ab jetzt)
        let binanceLast: Int64 = b.value != nil ? request.now : request.lastBinanceAt
        let alphaLast: Int64 = a.value != nil ? request.now : request.lastAlphaAt
        let wantBinance = request.binanceDue
            && CoinMeta.needed(directOk: b.value != nil, lastDirectAt: binanceLast, now: request.now)
        let wantAlpha = request.alphaDue
            && CoinMeta.needed(directOk: a.value != nil, lastDirectAt: alphaLast, now: request.now)
        guard request.mirrorAllowed, wantBinance || wantAlpha else { return result }
        var meta: CoinMeta.Meta?
        do {
            let text = try await fetch(CoinMeta.url)
            meta = CoinMeta.parse(text)
        } catch {
            if isNetworkError(error) { result.networkError = true }
        }
        guard let usable = meta, CoinMeta.isUsable(usable, now: request.now) else {
            result.mirrorFailed = true
            return result
        }
        // Nur, wenn die Ersatzdatei neuer ist als der letzte direkte Stand
        if wantBinance, !usable.binance.isEmpty, usable.generatedAt > request.lastBinanceAt {
            // Ohne Namen nur fehlende Kürzel ergänzen, gespeicherte Namen und Logos bleiben
            result.binance = CoinMeta.mirrorBinance(usable.binance, saved: request.savedBinance)
            result.binanceSource = .mirror
        }
        if wantAlpha, !usable.alpha.isEmpty, usable.generatedAt > request.lastAlphaAt {
            result.alpha = usable.alpha
            result.alphaSource = .mirror
        }
        return result
    }

    /// Namen nach den Binance-Listen: `known` (gespeichert, mit CoinGecko-Namen) behält Vorrang, die
    /// Listen füllen Lücken; stammt `known` nur aus dem mitgelieferten Auszug, haben die frischen
    /// Listen Vorrang — wie Android `publishNames`.
    static func publishNames(known: [String: String], knownIsSnapshot: Bool,
                             listNames: [String: String], listOrder: [String]) -> (names: [String: String], order: [String]) {
        if knownIsSnapshot || known.isEmpty {
            var names = listNames
            var order = listOrder
            CoinLogos.withKnown(&names, order: &order, known: known)
            return (names, order)
        }
        var names = known
        var order = known.keys.sorted()
        CoinLogos.withKnown(&names, order: &order, known: listNames)
        return (names, order)
    }
}
