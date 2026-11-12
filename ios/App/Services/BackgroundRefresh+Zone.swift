import Foundation

// Fear & Greed und Kennzahlen (Teil von `BackgroundRefresh`): Plan, Nachholen beim Öffnen und der Schritt in der
// Hintergrund-Aufgabe (`BackgroundRefresh.handleRefresh`).

extension BackgroundRefresh {
    // MARK: Fear & Greed (wie `ZoneCheckWorker`, zweimal täglich)

    private static let lastZoneCheckKey = "zone_check_last_run"

    /// Zuletzt erfolgreich geprüft (ms); 0 = noch nie. Für den Plan und das Nachholen beim Öffnen.
    static var lastZoneCheckAt: Int64 {
        get { Int64(SharedStorage.defaults.double(forKey: lastZoneCheckKey)) }
        set { SharedStorage.defaults.set(Double(newValue), forKey: lastZoneCheckKey) }
    }

    static func zoneWanted(_ settings: AppSettings) -> Bool {
        settings.fearGreedBelow > 0 || settings.fearGreedAbove > 0 || !IndicatorAlertRules.active(settings.indicatorAlerts).isEmpty
    }

    /// Grenzen von Fear & Greed bzw. Kennzahlen geändert: die eine Hintergrund-Aufgabe neu planen, damit ihr
    /// Beginn die Prüfung berücksichtigt (oder nicht mehr).
    static func scheduleZone(settings: AppSettings) {
        scheduleRefresh(settings: settings, replace: true)
    }

    /// Beim Öffnen der App: Prüfung nachholen, wenn die letzte 12 h oder länger her ist (oder noch nie
    /// lief) — iOS startet die Hintergrund-Aufgabe oft spät oder gar nicht. Abruf gescheitert: erneut mit wachsender
    /// Pause (`MarketNoticeGate.retryDelayMillis`, wie der Backoff von `ZoneCheckWorker`), danach beim nächsten Öffnen.
    @MainActor
    static func runZoneCheckIfDue(settings: AppSettings) {
        guard zoneWanted(settings),
              ZoneSchedule.isDue(lastRunMillis: lastZoneCheckAt, now: EpochTime.nowMillis),
              !zoneRunning else { return }
        Task { @MainActor in
            var current = settings
            var attempt = 0
            // nil = es lief schon eine (zählt als erledigt)
            while await runZoneCheck(settings: current) == false, attempt < MarketNoticeGate.maxRetries {
                attempt += 1
                let delay = UInt64(MarketNoticeGate.retryDelayMillis(attempt: attempt))
                try? await Task.sleep(nanoseconds: delay * 1_000_000)
                current = SharedStorage.loadSettings()
                guard zoneWanted(current) else { return }
            }
        }
    }

    /// Läuft gerade eine Prüfung (Hintergrund-Aufgabe oder Nachholen)? Nur eine zur Zeit, sonst
    /// könnte ein Wechsel doppelt gemeldet werden.
    @MainActor private static var zoneRunning = false

    /// - Returns: nil = es lief schon eine; sonst das Ergebnis von `ZoneCheck.run`.
    @MainActor
    private static func runZoneCheck(settings: AppSettings) async -> Bool? {
        guard !zoneRunning else { return nil }
        zoneRunning = true
        defer { zoneRunning = false }
        let ok = await ZoneCheck.run(settings: settings)
        // Nur ein gelungener Lauf zählt (wie `Result.retry()`): sonst holt das nächste Öffnen ihn nach
        if ok { lastZoneCheckAt = EpochTime.nowMillis }
        return ok
    }

    /// Fear & Greed und Kennzahlen als Schritt der Hintergrund-Aufgabe (nur aufrufen, wenn fällig) — höchstens
    /// bis zur Endzeit. Nicht geschafft (keine Zeit, abgebrochen, fehlgeschlagen): die nächste Runde neu planen,
    /// dann richtet sie sich nach dem alten `lastZoneCheckAt` (frühestens `ZoneSchedule.minDelayMillis`).
    @MainActor
    static func runZoneStep(settings: AppSettings, deadline: RefreshDeadline) async {
        var done = false
        if !Task.isCancelled && RefreshBudget.shouldRun(.zone, remaining: deadline.remaining()) {
            let result = await RefreshDeadline.run(within: deadline.allowance(wanted: Int64.max)) { @MainActor in
                await runZoneCheck(settings: settings)
            }
            // nil = Zeit um; innen nil = es lief schon eine (zählt als erledigt)
            done = result.map { $0 ?? true } ?? false
        }
        if !done { scheduleRefresh(settings: settings, replace: true, fromNow: true) }
    }
}
