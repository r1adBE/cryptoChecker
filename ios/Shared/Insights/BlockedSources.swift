import Foundation
import os

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
    /// Sperren je Quelle und gemeinsame Pausen je Gruppe (`ExchangeBackoff.sharedGroup`), zusammen hinter einer Sperre.
    private struct Entries: Sendable {
        var blockedUntil: [String: Int64] = [:]
        var groupPauses: [String: ExchangeBackoff.State] = [:]
    }

    private static let entries = OSAllocatedUnfairLock(initialState: Entries())

    static func isBlocked(_ key: String) -> Bool {
        if groupPausedUntil(key) != nil { return true }
        let now = TimeUtils.nowMillis
        return entries.withLock { e in
            guard let until = e.blockedUntil[key] else { return false }
            if now < until { return true }
            e.blockedUntil[key] = nil
            return false
        }
    }

    /// Sperre (451/403) dieser Quelle aufheben, z. B. eigener Bitcoin-Server nach Speichern oder erfolgreichem Test.
    static func unblock(_ key: String) {
        entries.withLock { $0.blockedUntil[key] = nil }
    }

    /// Wertet einen Fehler aus; true = Sperr- bzw. Pausen-Antwort, Quelle ist jetzt vermerkt.
    /// 451/403: diese Quelle 6 h. 429/418 eines Hosts mit gemeinsamer Pause: die ganze Gruppe.
    @discardableResult
    static func noteFailure(_ key: String, _ error: Error) -> Bool {
        guard let http = error as? HttpMarketError else { return false }
        if http.httpCode == 451 || http.httpCode == 403 {
            let until = TimeUtils.nowMillis + blockMillis
            entries.withLock { $0.blockedUntil[key] = until }
            return true
        }
        if ExchangeBackoff.isRateLimitCode(http.httpCode) {
            guard let group = ExchangeBackoff.sharedGroup(key) else { return false }
            let now = TimeUtils.nowMillis
            let code = http.httpCode
            let retryAfter = http.retryAfterSeconds
            entries.withLock {
                $0.groupPauses[group] = ExchangeBackoff.groupPause($0.groupPauses[group], now: now, httpCode: code,
                                                                   retryAfterSeconds: retryAfter)
            }
            return true
        }
        return false
    }

    /// Ende der gemeinsamen Pause, die `hostOrMarketKey` betrifft (Hostname oder Börsen-Schlüssel
    /// der Kursabfrage); nil = keine Pause.
    static func groupPausedUntil(_ hostOrMarketKey: String) -> Int64? {
        guard let group = ExchangeBackoff.sharedGroup(hostOrMarketKey) else { return nil }
        let now = TimeUtils.nowMillis
        guard let state = entries.withLock({ $0.groupPauses[group] }), ExchangeBackoff.isPaused(state, now: now) else {
            return nil
        }
        return state.pausedUntil
    }

    /// Pause aus der Kursabfrage übernehmen (429/418 beim Ticker); verkürzt nie eine laufende.
    static func pauseGroup(_ hostOrMarketKey: String, until: Int64) {
        guard let group = ExchangeBackoff.sharedGroup(hostOrMarketKey) else { return }
        let now = TimeUtils.nowMillis
        // Eine unmögliche alte Pause (`ExchangeBackoff.isPaused`: zu weit in der Zukunft) blockiert die neue nicht
        entries.withLock {
            $0.groupPauses[group] = ExchangeBackoff.adoptedPause($0.groupPauses[group], until: until, now: now)
        }
    }
}
