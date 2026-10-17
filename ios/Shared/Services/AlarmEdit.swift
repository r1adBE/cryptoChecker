import Foundation

/// Regeln beim Speichern eines Alarms aus dem Editor — wie `AlarmEdit.kt`:
/// Löst er sofort aus (`firesNow`)? Wird er neu scharf gestellt (`rearms`) oder behält er
/// seinen Zustand (`merged`)?
enum AlarmEdit {

    /// Kursmarke schon erreicht? «über»: Kurs ≥ Marke, «unter»: Kurs ≤ Marke (wie
    /// `AlarmEvaluator.shouldTrigger`). `price` in der Währung des Schwellwerts (bei eigener
    /// Alarmwährung also schon umgerechnet); ohne gültigen Kurs false. Andere Bedingungen: false.
    static func firesNow(condition: AlarmCondition, threshold: Double?, price: Double?) -> Bool {
        guard let t = threshold, t.isFinite, t > 0, let p = price, p.isFinite, p > 0 else { return false }
        switch condition {
        case .PRICE_ABOVE: return p >= t
        case .PRICE_BELOW: return p <= t
        default: return false
        }
    }

    /// Bedingung geändert? Paar, Bedingung, Schwellwert, Währung — und das Zeitfenster, wo es zur
    /// Bedingung gehört (Bewegung in y Stunden, Hoch/Tief-Zeitraum, Open-Interest-Fenster).
    /// Ton, Vibration, Ansage und «Einmal/Jedes Mal» zählen nicht.
    static func conditionChanged(_ old: Alarm, _ new: Alarm) -> Bool {
        old.watchId != new.watchId
            || old.condition != new.condition
            || !sameThreshold(old.threshold, new.threshold)
            || normalizedCurrency(old.currency) != normalizedCurrency(new.currency)
            || (usesWindow(new.condition) && old.windowHours != new.windowHours)
    }

    /// Wird der Alarm beim Speichern (neu) scharf — neu angelegt, Bedingung geändert oder
    /// wieder eingeschaltet (`reenables`)? Nur dann kann er gleich nach dem Speichern melden.
    static func rearms(existing: Alarm?, edited: Alarm) -> Bool {
        guard let existing else { return true }
        return conditionChanged(existing, edited) || reenables(existing: existing, edited: edited)
    }

    /// Ausgelöster einmaliger Alarm (aus, mit letzter Meldung) wird auf «Jedes Mal» gestellt.
    static func reenables(existing: Alarm, edited: Alarm) -> Bool {
        edited.repeating && !existing.repeating && !existing.enabled && existing.lastTriggeredAt > 0
    }

    /// Gespeicherter Alarm: `edited` = Eingaben mit dem Zustand «neu scharf» (so wie bisher immer
    /// gespeichert). Hat sich die Bedingung nicht geändert, behält er Ein/Aus, letzte Meldung,
    /// Bezugskurs und Scharf/Gemeldet von `existing` — nur Ton, Vibration, Ansage und
    /// Wiederholung ändern sich.
    ///
    /// Ausnahme, bewusst: Ein ausgelöster einmaliger Alarm (aus, mit letzter Meldung) wird mit
    /// «Jedes Mal» wieder eingeschaltet und scharf gestellt (`referenceAt` = 0 bei Kursmarken,
    /// Hoch/Tief, Funding, Open Interest; Bezugskurs bleibt — wie WatchDao.rearmAlarm in Android).
    /// Umgekehrt («Jedes Mal» → «Einmal») bleibt der Zustand.
    static func merged(existing: Alarm?, edited: Alarm) -> Alarm {
        guard let existing, !conditionChanged(existing, edited) else { return edited }
        var kept = edited
        kept.enabled = existing.enabled
        kept.referencePrice = existing.referencePrice
        kept.lastTriggeredAt = existing.lastTriggeredAt
        kept.lastTriggeredPrice = existing.lastTriggeredPrice
        kept.referenceAt = existing.referenceAt
        // Fenster ohne Bedeutung für die Bedingung: gespeicherten Wert behalten
        if !usesWindow(edited.condition) { kept.windowHours = existing.windowHours }
        guard reenables(existing: existing, edited: edited) else { return kept }
        kept.enabled = true
        if rearmsOnEnable(edited.condition) { kept.referenceAt = 0 }
        return kept
    }

    /// Bezugskurs eines neuen Prozentalarms: der letzte Kurs, aber nur, wenn er frisch ist
    /// (`lastUpdate` höchstens `staleAfterMillis` alt, Regel «veraltet» der Merkliste). Sonst nil —
    /// die nächste Abfrage setzt dann den Bezug (`needsReference`), statt ab einem alten Kurs zu
    /// messen und gleich zu melden (Review 13). Wie AlarmEdit.kt.
    static func freshReference(lastPrice: Double?, lastUpdate: Int64, now: Int64, staleAfterMillis: Int64) -> Double? {
        guard let price = lastPrice, price.isFinite, price > 0 else { return nil }
        if lastUpdate <= 0 || now - lastUpdate > staleAfterMillis { return nil }
        return price
    }

    /// Gehört das Zeitfenster zur Bedingung?
    static func usesWindow(_ condition: AlarmCondition) -> Bool {
        condition == .MOVE_PERCENT_WINDOW || condition.isNearExtreme || condition.isOpenInterest
    }

    /// Bedingungen, die beim Einschalten wieder scharf werden (wie WatchDao.rearmAlarm).
    private static func rearmsOnEnable(_ condition: AlarmCondition) -> Bool {
        condition.isPriceThreshold || condition.isNearExtreme || condition.isDerivatives
    }

    private static func normalizedCurrency(_ code: String?) -> String? {
        guard let trimmed = code?.trimmingCharacters(in: .whitespaces).uppercased(), !trimmed.isEmpty else { return nil }
        return trimmed
    }

    /// Gleicher Schwellwert — mit kleiner Toleranz, falls das Eingabefeld ihn minimal anders zurückliest.
    private static func sameThreshold(_ a: Double, _ b: Double) -> Bool {
        a == b || abs(a - b) <= 1e-9 * max(abs(a), abs(b))
    }
}
