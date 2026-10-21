import Foundation

/// Quellen, die mit 451/403 geantwortet haben (Geo-Sperre, z. B. Binance in den USA).
/// Sie werden 6 h übersprungen. Geteilt von Kerzen und Futures-Kennzahlen;
/// Schlüssel ist der Hostname.
///
/// Dazu die gemeinsame Pause nach 429/418 («zu viele Anfragen», «IP gesperrt»): Antwortet ein
/// Binance-Host so, pausieren ALLE Binance-Hosts (`ExchangeBackoff.binanceHosts`) — weitere
/// Anfragen an irgendeinen davon verlängerten die Sperre. Dauer siehe `ExchangeBackoff.groupPause`
/// («Retry-After» gilt, sonst gestaffelt ab 30 s). Die Kursabfrage gleicht sich in beide
/// Richtungen ab (`pauseGroup`, `groupPausedUntil`) — wie `BlockedSources` in `CandleDataSource.kt`.
enum BlockedSources {
    private static let blockMillis: Int64 = 6 * 60 * 60_000
    private static let lock = NSLock()
    // nonisolated(unsafe): jeder Zugriff nur unter `lock` (NSLock)
    nonisolated(unsafe) private static var blockedUntil: [String: Int64] = [:]
    /// Gemeinsame Pausen je Gruppe (`ExchangeBackoff.sharedGroup`); nur unter `lock`.
    nonisolated(unsafe) private static var groupPauses: [String: ExchangeBackoff.State] = [:]

    static func isBlocked(_ key: String) -> Bool {
        if groupPausedUntil(key) != nil { return true }
        lock.lock(); defer { lock.unlock() }
        guard let until = blockedUntil[key] else { return false }
        if TimeUtils.nowMillis < until { return true }
        blockedUntil[key] = nil
        return false
    }

    /// Wertet einen Fehler aus; true = Sperr- bzw. Pausen-Antwort, Quelle ist jetzt vermerkt.
    /// 451/403: diese Quelle 6 h. 429/418 eines Hosts mit gemeinsamer Pause: die ganze Gruppe.
    @discardableResult
    static func noteFailure(_ key: String, _ error: Error) -> Bool {
        guard let http = error as? HttpMarketError else { return false }
        if http.httpCode == 451 || http.httpCode == 403 {
            lock.lock(); defer { lock.unlock() }
            blockedUntil[key] = TimeUtils.nowMillis + blockMillis
            return true
        }
        if ExchangeBackoff.isRateLimitCode(http.httpCode) {
            guard let group = ExchangeBackoff.sharedGroup(key) else { return false }
            let now = TimeUtils.nowMillis
            lock.lock(); defer { lock.unlock() }
            groupPauses[group] = ExchangeBackoff.groupPause(groupPauses[group], now: now, httpCode: http.httpCode,
                                                           retryAfterSeconds: http.retryAfterSeconds)
            return true
        }
        return false
    }

    /// Ende der gemeinsamen Pause, die `hostOrMarketKey` betrifft (Hostname oder Börsen-Schlüssel
    /// der Kursabfrage); nil = keine Pause.
    static func groupPausedUntil(_ hostOrMarketKey: String) -> Int64? {
        guard let group = ExchangeBackoff.sharedGroup(hostOrMarketKey) else { return nil }
        lock.lock(); defer { lock.unlock() }
        guard let state = groupPauses[group], ExchangeBackoff.isPaused(state, now: TimeUtils.nowMillis) else { return nil }
        return state.pausedUntil
    }

    /// Pause aus der Kursabfrage übernehmen (429/418 beim Ticker); verkürzt nie eine laufende.
    static func pauseGroup(_ hostOrMarketKey: String, until: Int64) {
        guard let group = ExchangeBackoff.sharedGroup(hostOrMarketKey) else { return }
        lock.lock(); defer { lock.unlock() }
        // Eine unmögliche alte Pause (`ExchangeBackoff.isPaused`: zu weit in der Zukunft) blockiert die neue nicht
        groupPauses[group] = ExchangeBackoff.adoptedPause(groupPauses[group], until: until, now: TimeUtils.nowMillis)
    }
}
