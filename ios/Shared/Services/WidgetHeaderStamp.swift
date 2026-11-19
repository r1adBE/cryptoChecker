import Foundation
import os

/// Kopfzeile des Puls- und des Portfolio-Widgets wie im Merkliste-Widget: «Uhrzeit · Dauer» der letzten Aktualisierung
/// aus dem Widget und die 15-s-Sperre des Knopfs (`WidgetRefreshState`). Speicher in der App Group, je Widget-Art.
/// Wie Android `WidgetHeaderStamp` / `WidgetPrefs.headerStamp`.
enum WidgetHeaderStamp {
    enum Kind: String {
        case pulse, portfolio
    }

    /// Zeit der Daten (`dataAt`), Dauer und Ende (Wanduhr, ms; für die Sperre).
    struct Stamp: Equatable {
        var dataAt: Int64 = 0
        var durationMillis: Int64 = 0
        var finishedAt: Int64 = 0

        /// Dauer nur für genau diesen Stand (Daten aus der App haben keine); 0 = ohne.
        func duration(for time: Int64) -> Int64 { time > 0 && time == dataAt ? durationMillis : 0 }

        /// Aktualisierung fertig: Stand `dataAt`, Dauer seit `startedAt` (nie negativ), Ende `now`.
        static func finished(dataAt: Int64, startedAt: Int64, now: Int64) -> Stamp {
            Stamp(dataAt: dataAt, durationMillis: max(0, now - startedAt), finishedAt: now)
        }
    }

    static func load(_ kind: Kind) -> Stamp {
        let d = SharedStorage.defaults
        return Stamp(dataAt: Int64(d.double(forKey: key(kind, "data_at"))),
                     durationMillis: Int64(d.double(forKey: key(kind, "duration"))),
                     finishedAt: Int64(d.double(forKey: key(kind, "finished_at"))))
    }

    /// Aktualisierung aus dem Widget fertig: Stand `dataAt` mit der Dauer seit `startedAt` merken.
    static func finished(_ kind: Kind, dataAt: Int64, startedAt: Int64, now: Int64 = EpochTime.nowMillis) {
        let stamp = Stamp.finished(dataAt: dataAt, startedAt: startedAt, now: now)
        let d = SharedStorage.defaults
        d.set(Double(stamp.dataAt), forKey: key(kind, "data_at"))
        d.set(Double(stamp.durationMillis), forKey: key(kind, "duration"))
        d.set(Double(stamp.finishedAt), forKey: key(kind, "finished_at"))
    }

    /// Knopf gesperrt bis (Wanduhr, ms; 0 = nie) — nach einem Tipp in der Sperre, wie `WidgetRefresh.blockedUntil`.
    static func blockedUntil(_ kind: Kind) -> Int64 {
        Int64(SharedStorage.defaults.double(forKey: key(kind, "blocked_until")))
    }

    static func setBlockedUntil(_ kind: Kind, _ until: Int64) {
        SharedStorage.defaults.set(Double(until), forKey: key(kind, "blocked_until"))
    }

    /// Ende der Sperre, das ein Eintrag zum Zeitpunkt `now` zeigt; 0 = Knopf.
    static func lockUntil(_ kind: Kind, now: Int64) -> Int64 {
        WidgetRefreshState.blockedUntil(stored: blockedUntil(kind), lastFinishedAt: load(kind).finishedAt,
                                         runningSince: 0, now: now)
    }

    /// Letzter Kursabruf der App fürs Portfolio (Ende, Dauer; Wanduhr, ms). Eine Aufnahme aus der App kurz danach zeigt
    /// diese Dauer im Widget («Uhrzeit · Dauer»), wie das Merkliste-Widget die Dauer jeder Aktualisierung. Wie Android
    /// `PortfolioSnapshotUpdater.lastPriceFetch`.
    private static let lastPriceFetch = OSAllocatedUnfairLock<(at: Int64, duration: Int64)?>(initialState: nil)

    /// Eine Aufnahme gilt als Folge eines Kursabrufs, wenn er höchstens so lange (ms) zurückliegt.
    static let appFetchRecentMillis: Int64 = 60_000

    /// Kursabruf der App fürs Portfolio fertig (`durationMillis` seit seinem Start).
    static func notePriceFetch(durationMillis: Int64, now: Int64 = EpochTime.nowMillis) {
        lastPriceFetch.withLock { $0 = (now, max(0, durationMillis)) }
    }

    /// Aufnahme des Portfolio-Stands aus der App (`dataAt`): mit der Dauer des Kursabrufs kurz davor merken; ohne
    /// solchen Abruf nichts (dann steht nur die Uhrzeit).
    static func recordedFromApp(dataAt: Int64) {
        guard let fetch = lastPriceFetch.withLock({ $0 }), (0...appFetchRecentMillis).contains(dataAt - fetch.at) else {
            return
        }
        finished(.portfolio, dataAt: dataAt, startedAt: dataAt - fetch.duration, now: dataAt)
    }

    private static func key(_ kind: Kind, _ field: String) -> String { "widget_header_\(kind.rawValue)_\(field)" }
}
