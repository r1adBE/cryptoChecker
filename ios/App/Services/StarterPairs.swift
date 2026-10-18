import Foundation

/// Startbörse und Startpaar der leeren Merkliste — wie `StarterPairs.kt`.
///
/// Binance Spot `<COIN>/USDT` («BTCUSDT») in allen Regionen ausser den USA (auch Schweiz und
/// Liechtenstein, Entscheid 15. Oktober 2026); in der Geräte-Region USA Binance.US `<COIN>/USDT`
/// (gleiche Schnittstelle, `api.binance.us`). Schlüssel wie `Market.key` in `MarketsConfig`.
enum StarterPairs {
    static let binanceKey = "Binance"
    static let binanceUsKey = "BinanceUs"

    /// Quote der Startpaare (Binance und Binance.US).
    static let startQuoteValue = "USDT"

    /// Ländercode für die Startpaare: Geräte-Region (z. B. «CH»); sperrt Binance den Standort
    /// (`StarterGeoBlock`), gilt «US» → Binance.US (nur für den Startschritt, nicht für eigene Paare).
    static func region(_ locale: Locale = .current) -> String? {
        effectiveRegion(locale.region?.identifier, binanceBlocked: StarterGeoBlock.blocked)
    }

    // MARK: Standort-Sperre

    /// Kleine Anfrage an denselben Host wie die Binance-Kurse (der Spiegel `data-api.binance.vision` ist nie gesperrt).
    static let binanceProbeURL = "https://api.binance.com/api/v3/ping"

    /// HTTP 451 («restricted location») bzw. 403 (Geo-Sperre) von Binance.
    static func isGeoBlocked(httpCode: Int?) -> Bool {
        httpCode == 451 || httpCode == 403
    }

    /// Region mit Sperre: ausserhalb der USA, aber Binance gesperrt (z. B. Gerät auf Schweiz, Netz in den USA) → «US».
    static func effectiveRegion(_ region: String?, binanceBlocked: Bool) -> String? {
        binanceBlocked && !isUS(region) ? "US" : region
    }

    /// Nur ausserhalb der Geräte-Region USA prüfen (dort gilt ohnehin Binance.US).
    static func shouldProbe(deviceRegion: String?) -> Bool { !isUS(deviceRegion) }

    /// Neuer Stand der Sperre nach der Prüfung: `httpCode` nil = kein Netz/Zeit um (Stand bleibt), 2xx = erreichbar,
    /// 451/403 = gesperrt, anderer Fehler = Stand bleibt. Wie Android.
    static func blockedAfterProbe(previous: Bool, httpCode: Int?) -> Bool {
        guard let httpCode else { return previous }
        if (200..<300).contains(httpCode) { return false }
        return isGeoBlocked(httpCode: httpCode) ? true : previous
    }

    private static func normalized(_ region: String?) -> String? {
        region?.trimmingCharacters(in: .whitespaces).uppercased()
    }

    static func isUS(_ region: String?) -> Bool { normalized(region) == "US" }

    /// Startbörse der Region.
    static func marketKey(_ region: String?) -> String {
        isUS(region) ? binanceUsKey : binanceKey
    }

    /// Anzeigename der Start-Börse der Region («Binance», bei Standort-Sperre bzw. in den USA «Binance.US»): die
    /// Start-Auswahl sagt laut, woher die Kurse kommen («Kurse von Binance in USDT»). Wie Android.
    /// - Parameter name: Anzeigename aus der Registry; nil = Schlüssel.
    static func marketName(_ region: String?, name: (String) -> String? = { MarketsConfig.market($0)?.name }) -> String {
        let key = marketKey(region)
        return name(key) ?? key
    }

    /// Anzeige-Quote für Kurse ohne eigene Quote.
    static func startQuote(_ region: String?) -> String { startQuoteValue }

    /// Startpaar eines Coins für die Region.
    static func pair(for coin: String, region: String?) -> (marketKey: String, pair: MarketPair) {
        let symbol = coin.trimmingCharacters(in: .whitespaces).uppercased()
        return (marketKey(region), MarketPair(symbol, startQuoteValue, symbol + startQuoteValue))
    }
}

/// Sperrt Binance den Standort (HTTP 451/403, z. B. Geräte-Region Schweiz im US-Netz eines Prüfers), zeigte der
/// Startschritt sonst fünf Fehlerzeilen. Geprüft wird beim Öffnen der leeren Merkliste; gesperrt → Startpaare auf
/// Binance.US (`StarterPairs.region`). Gemerkt bis zur nächsten Prüfung. Nur der Startschritt, nicht eigene Paare.
@MainActor
enum StarterGeoBlock {
    // nonisolated: auch aus `blocked` (nicht isoliert) lesbar
    nonisolated private static let key = "starter_binance_geo_blocked"
    /// Zeitgrenze der Prüfung.
    static let probeSeconds: Double = 4

    /// Zuletzt festgestellt (ohne Prüfung: nicht gesperrt).
    nonisolated static var blocked: Bool {
        UserDefaults.standard.bool(forKey: key)
    }

    private static var running: Task<Bool, Never>?

    /// Läuft die Prüfung gerade?
    static var isProbing: Bool { running != nil }

    /// Einmal prüfen (laufende Prüfung wird mitbenutzt). - Returns: true = Ergebnis anders als vorher.
    @discardableResult
    static func probe() async -> Bool {
        if let running { return await running.value }
        guard StarterPairs.shouldProbe(deviceRegion: Locale.current.region?.identifier) else { return false }
        let task = Task { @MainActor () -> Bool in
            let before = blocked
            let code: Int?
            do {
                code = try await AsyncTimeout.run(seconds: probeSeconds) { () -> Int? in
                    do {
                        _ = try await MarketHTTP.call(StarterPairs.binanceProbeURL)
                        return 200
                    } catch let error as HttpMarketError {
                        return error.httpCode
                    } catch {
                        return nil
                    }
                }
            } catch {
                code = nil
            }
            let after = StarterPairs.blockedAfterProbe(previous: before, httpCode: code)
            UserDefaults.standard.set(after, forKey: key)
            return after != before
        }
        running = task
        defer { running = nil }
        return await task.value
    }

    /// Wartet auf eine laufende Prüfung (vor dem Hinzufügen der Startpaare).
    static func waitForProbe() async {
        if let running { _ = await running.value }
    }
}
