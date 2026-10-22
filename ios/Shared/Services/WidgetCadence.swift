import Foundation

/// Takt der Widgets (nur iOS; Android-Widgets holen nichts selbst, sie zeigen den Stand der
/// Hintergrund-Prüfung): neu zeichnen und Kurse holen etwa alle 45 Minuten. Nach einem misslungenen
/// Versuch oder noch nicht geschriebenen Kursen höchstens EIN früher Neuversuch je Takt — vorher konnte
/// sich das Merkliste-Widget jede Minute neu laden und das Neuladen-Kontingent von WidgetKit aufbrauchen.
/// Das Kontingent (rund 40–70 je Tag und Widget) teilen sich alle Widgets einer Art; darum zusätzlich ein
/// Tageszähler (`dailyReloadSoftCap`): ist er erreicht, gibt es keine frühen Neuversuche mehr, nur den Takt.
enum WidgetCadence {
    /// Normaler Abstand zwischen zwei Zeichnungen (45 statt 30 Minuten: lässt dem Kontingent Luft für
    /// Neuladen aus der App und nach dem Aktualisieren-Knopf).
    static let reloadMinutes = 45
    /// Ab diesem Alter holt das Zeichnen zuerst neue Kurse: unter dem Takt, damit das geplante
    /// Neuladen sicher holt, ein vorgezogenes (App, anderes Widget) aber nicht gleich noch einmal.
    static let staleAfterMillis: Int64 = 25 * 60_000
    /// Nach einem misslungenen Versuch (offline, kein einziger Kurs, Datei nicht schreibbar) so lange
    /// keinen neuen aus dem Zeichnen heraus — gleiche Grenze wie `staleAfterMillis`.
    static let retryAfterMillis: Int64 = staleAfterMillis
    /// Abstand des frühen Neuversuchs.
    static let quickRetrySeconds: Double = 60

    /// Nächstes Neuladen: der frühe Neuversuch nur, wenn er gewünscht ist und der letzte mindestens einen
    /// Takt zurückliegt (Uhr zurückgestellt: erlaubt); sonst der normale Takt.
    /// - Returns: Zeitpunkt und ob es der frühe Neuversuch ist (dann `lastQuickRetryAt` merken).
    /// - Parameter reloadsToday: bisher gezählte Neuladen heute (`DailyReloadCounter`); ab `dailyReloadSoftCap`
    ///   kein früher Neuversuch mehr.
    static func nextReload(now: Date, wantsQuickRetry: Bool, lastQuickRetryAt: Date?,
                           reloadsToday: Int = 0) -> (date: Date, quickRetry: Bool) {
        let period = Double(reloadMinutes) * 60
        var allowed = wantsQuickRetry && reloadsToday < dailyReloadSoftCap
        if allowed, let last = lastQuickRetryAt {
            let age = now.timeIntervalSince(last)
            allowed = age < 0 || age >= period
        }
        return (now.addingTimeInterval(allowed ? quickRetrySeconds : period), allowed)
    }

    /// Weiche Tagesgrenze für Neuladen aus der Erweiterung: darüber nur noch der normale Takt (kein früher
    /// Neuversuch), damit das Kontingent von WidgetKit nicht vor dem Abend aufgebraucht ist.
    static let dailyReloadSoftCap = 40
    /// Reserve nach dem Kursholen im Zeichenpfad (Speichern, Melden) — seit Build 103.
    static let drawReserveMillis: Int64 = 1_000
    /// Kürzeste Endzeit eines Durchlaufs aus dem Zeichnen.
    static let minDrawRunMillis: Int64 = 1_000

    /// Endzeit eines Durchlaufs aus dem Zeichnen (`WidgetRefresh.refreshIfStale`), seit Build 103 an die Zeichen-Frist
    /// gekoppelt (8 s → 7 s): Kurse, Lease und Kursalarme enden so vor der Rückgabe der Timeline, Speichern und Melden
    /// haben die Reserve. Vorher galten auch hier 25 s, und der Lauf arbeitete nach der Rückgabe unbeaufsichtigt weiter
    /// (WidgetKit kann die Erweiterung dann jederzeit anhalten). Der Knopf (App-Intent) behält seine 25 s.
    static func drawRunBudgetMillis(deadlineSeconds: Double) -> Int64 {
        guard deadlineSeconds.isFinite, deadlineSeconds > 0 else { return minDrawRunMillis }
        let millis = Int64(min(deadlineSeconds, 3_600) * 1_000)
        return max(minDrawRunMillis, millis - drawReserveMillis)
    }
}

/// Zählt Neuladen der Widgets je Kalendertag (Gerätezeit) in den App-Group-Defaults — für alle Widget-Arten
/// zusammen, da auch `reloadTimelines(ofKind:)` aus der Erweiterung zählt. Ein neuer Tag beginnt bei 0.
struct DailyReloadCounter {
    static let dayKey = "widget_reload_day"
    static let countKey = "widget_reload_count"

    let defaults: UserDefaults

    /// Neuladen heute (anderer gespeicherter Tag = 0).
    func count(now: Date = Date(), timeZone: TimeZone = .current) -> Int {
        guard defaults.string(forKey: Self.dayKey) == Self.day(now, timeZone) else { return 0 }
        return defaults.integer(forKey: Self.countKey)
    }

    /// Ein Neuladen mehr; liefert den neuen Stand.
    @discardableResult
    func increment(by n: Int = 1, now: Date = Date(), timeZone: TimeZone = .current) -> Int {
        let today = Self.day(now, timeZone)
        let base = defaults.string(forKey: Self.dayKey) == today ? defaults.integer(forKey: Self.countKey) : 0
        let next = base + max(0, n)
        defaults.set(today, forKey: Self.dayKey)
        defaults.set(next, forKey: Self.countKey)
        return next
    }

    private static func day(_ date: Date, _ timeZone: TimeZone) -> String {
        LocalDay(date: date, timeZone: timeZone).description
    }
}
