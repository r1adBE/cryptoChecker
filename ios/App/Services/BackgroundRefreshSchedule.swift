import Foundation

// Zeitplan und Zeitbudget der Hintergrund-Aktualisierung (`BackgroundRefresh`): Kurse, Marktphase und Fear & Greed.

/// Wann die Prüfung von Marktphase und Fear & Greed fällig ist (zweimal täglich, wie Android).
enum ZoneSchedule {
    static let intervalMillis: Int64 = 12 * 3_600_000
    /// Frühestens so lange nach jetzt planen (kein Dauerlauf, wenn die Prüfung fehlschlägt).
    static let minDelayMillis: Int64 = 15 * 60_000

    /// Fällig: noch nie gelaufen, vor mindestens 12 h, oder der letzte Lauf liegt in der Zukunft
    /// (Uhr zurückgestellt).
    static func isDue(lastRunMillis: Int64, now: Int64) -> Bool {
        lastRunMillis <= 0 || lastRunMillis > now || now - lastRunMillis >= intervalMillis
    }

    /// Frühester Beginn der nächsten Hintergrund-Prüfung: 12 h nach dem letzten Lauf (noch nie bzw.
    /// Uhr zurückgestellt: 12 h ab jetzt), aber mindestens `minDelayMillis` ab jetzt.
    static func earliestBegin(lastRunMillis: Int64, now: Int64) -> Date {
        let base = (lastRunMillis > 0 && lastRunMillis <= now) ? lastRunMillis : now
        let next = max(base + intervalMillis, now + minDelayMillis)
        return Date(timeIntervalSince1970: TimeInterval(next) / 1000)
    }

    /// Prüfung in diesem Lauf der Hintergrund-Aufgabe? Nur eingeschaltet und fällig.
    static func shouldRunInRefresh(wanted: Bool, lastRunMillis: Int64, now: Int64) -> Bool {
        wanted && isDue(lastRunMillis: lastRunMillis, now: now)
    }
}

/// Zeitbudget der Hintergrund-Aktualisierung (iOS gibt rund 30 s).
struct RefreshBudget {
    /// Kursabfrage höchstens so lange; langsamere Börsen fehlen diesmal.
    static let networkMillis: Int64 = 15_000
    /// Gesamtbudget, deutlich unter den ~30 s von iOS.
    static let totalMillis: Int64 = 25_000
    /// Portfolio-Stand nur mit so viel Restzeit.
    static let portfolioMinMillis: Int64 = 6_000
    /// Aktivität und Gas nur mit so viel Restzeit.
    static let sideCheckMinMillis: Int64 = 5_000
    /// Alarme mit Zusatzdaten nur mit so viel Restzeit (kürzeste Stufe + Lease + Speichern).
    static let extraAlarmsMinMillis: Int64 = 4_000
    /// So viel bleibt immer für Speichern und Abschluss.
    static let reserveMillis: Int64 = RefreshDeadline.saveReserveMillis

    /// Schritte nach dem Speichern der Kurse, in dieser Reihenfolge (wichtigste zuerst). Portfolio vor den
    /// Alarmen mit Zusatzdaten: braucht nur Kurse und Devisen, sonst bliebe ihm nach langsamen Zusatzdaten oft keine Zeit.
    enum Step: CaseIterable {
        case portfolio, extraAlarms, zone, activity, gas

        /// Mindest-Restzeit, damit der Schritt beginnt (Reserve fürs Speichern schon abgezogen).
        var minMillis: Int64 {
            switch self {
            case .extraAlarms: return RefreshBudget.extraAlarmsMinMillis
            case .portfolio: return RefreshBudget.portfolioMinMillis
            case .zone, .activity, .gas: return RefreshBudget.sideCheckMinMillis
            }
        }
    }

    /// Beginnt `step` noch? Nur wenn nach der Reserve mindestens seine Mindestzeit bleibt.
    static func shouldRun(_ step: Step, remaining: Int64) -> Bool {
        remaining - reserveMillis >= step.minMillis
    }

    /// Zeit für den Portfolio-Schritt: alles bis zur Reserve; warten danach Alarme mit Zusatzdaten
    /// (`extraAlarmsWaiting`), bleibt ihnen ihre Mindestzeit — aber nur, solange dem Portfolio selbst
    /// noch seine Mindestzeit bleibt (sonst hat das Portfolio als früherer Schritt Vorrang).
    static func portfolioAllowance(remaining: Int64, extraAlarmsWaiting: Bool) -> Int64 {
        let all = max(0, remaining - reserveMillis)
        guard extraAlarmsWaiting else { return all }
        let shared = all - extraAlarmsMinMillis
        return shared >= portfolioMinMillis ? shared : all
    }

    /// Devisenkurse für Portfolio-Alarme höchstens so lange (sonst sprengen zwei Quellen je Währung das Budget).
    static let portfolioFxMaxMillis: Int64 = 5_000
    /// Darunter lohnt keine Abfrage (Zwischenspeicher reicht).
    static let portfolioFxMinMillis: Int64 = 500

    /// Zeit für die Devisenkurse der Portfolio-Alarme: höchstens `portfolioFxMaxMillis`, nur bis zum Ende des
    /// Portfolio-Schritts (`portfolioEndMillis`) und mit Reserve; 0 = nicht abfragen.
    static func portfolioFxAllowance(portfolioEndMillis: Int64, deadline: RefreshDeadline,
                                     now: Int64 = TimeUtils.nowMillis) -> Int64 {
        let wanted = min(portfolioFxMaxMillis, max(0, portfolioEndMillis - now))
        let millis = deadline.allowance(wanted: wanted, now: now)
        return millis >= portfolioFxMinMillis ? millis : 0
    }

    let start: Int64

    /// Endzeit für alle Schritte (an `PriceRefresher` und die Nebenprüfungen weitergereicht).
    var deadline: RefreshDeadline { RefreshDeadline(endMillis: start + Self.totalMillis) }

    func remaining(now: Int64 = TimeUtils.nowMillis) -> Int64 { deadline.remaining(now: now) }
}

/// Wann die Kurs-Aktualisierung im Hintergrund frühestens beginnen soll.
enum RefreshSchedule {
    /// Frühestens so lange nach jetzt planen.
    static let minDelayMillis: Int64 = 60_000

    /// Intervall nach der letzten Aktualisierung (noch nie bzw. Uhr zurückgestellt: ab jetzt), aber
    /// mindestens `minDelayMillis` ab jetzt — so verschiebt ein Öffnen der App die Prüfung nicht.
    static func earliestBegin(lastRunMillis: Int64, intervalMinutes: Int, now: Int64) -> Date {
        let base = (lastRunMillis > 0 && lastRunMillis <= now) ? lastRunMillis : now
        // Überlaufsicher (Intervall aus alter/fremder Sicherung kann riesig sein)
        let next = max(base + SafeMillis.minutes(intervalMinutes), now + minDelayMillis)
        return Date(timeIntervalSince1970: TimeInterval(next) / 1000)
    }

    /// Beginn der einen Hintergrund-Aufgabe: Kurs-Intervall (`earliestBegin`), oder früher, wenn Fear & Greed
    /// vorher fällig wird (`ZoneSchedule.earliestBegin`). `zoneLastRunMillis` nil = Prüfung ausgeschaltet.
    static func nextBegin(lastRunMillis: Int64, intervalMinutes: Int, zoneLastRunMillis: Int64?, now: Int64) -> Date {
        let refresh = earliestBegin(lastRunMillis: lastRunMillis, intervalMinutes: intervalMinutes, now: now)
        guard let zoneLastRunMillis else { return refresh }
        return min(refresh, ZoneSchedule.earliestBegin(lastRunMillis: zoneLastRunMillis, now: now))
    }
}
