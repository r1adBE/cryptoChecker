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
    /// messen und gleich zu melden. Wie AlarmEdit.kt.
    static func freshReference(lastPrice: Double?, lastUpdate: Int64, now: Int64, staleAfterMillis: Int64) -> Double? {
        guard let price = lastPrice, price.isFinite, price > 0 else { return nil }
        if lastUpdate <= 0 || now - lastUpdate > staleAfterMillis { return nil }
        return price
    }

    /// Gehört das Zeitfenster zur Bedingung?
    static func usesWindow(_ condition: AlarmCondition) -> Bool {
        condition == .MOVE_PERCENT_WINDOW || condition.isNearExtreme || condition.isOpenInterest
    }

    /// Alarm ein- oder ausgeschaltet (Schalter in der Liste, «1 h später»): Eingeschaltet melden «Nahe am Hoch/Tief»,
    /// Kursmarken, Funding und Open Interest wieder (`referenceAt` = 0). `referencePrice` bleibt: Bei «Nahe am
    /// Hoch/Tief» ist das die gemeldete Marke (`NearExtreme.reportedMark`) — ohne sie meldete ein Aus/Ein dasselbe
    /// «neue Hoch» am selben Tag nochmals; die übrigen haben ohnehin keinen Bezugskurs. Wie WatchDao.rearmAlarm.
    static func settingEnabled(_ alarm: Alarm, _ enabled: Bool) -> Alarm {
        var result = alarm
        result.enabled = enabled
        if enabled && rearmsOnEnable(alarm.condition) { result.referenceAt = 0 }
        return result
    }

    /// Bedingungen, die beim Einschalten wieder scharf werden (wie WatchDao.rearmAlarm).
    private static func rearmsOnEnable(_ condition: AlarmCondition) -> Bool {
        condition.isPriceThreshold || condition.isNearExtreme || condition.isDerivatives
    }

    private static func normalizedCurrency(_ code: String?) -> String? {
        guard let trimmed = code?.trimmingCharacters(in: .whitespaces).uppercased(), !trimmed.isEmpty else { return nil }
        return trimmed
    }

    /// Eigene Alarmwährung im Editor anbieten? Nur bei Kursmarken (`priceThreshold`), wenn die Quote `quote` nicht schon
    /// die Währung `other` ist — und nur, wenn sich die Quote überhaupt umrechnen lässt: Eine Fiat-Quote ohne Devisenkurs
    /// (NGN, ARS: `.fiatUnsupported`) oder ein ungültiges Kürzel nie, sonst wartete der Alarm für immer auf einen Kurs.
    /// `fiat` = Währungen mit Devisenkurs. Wie Android.
    static func offersCurrency(priceThreshold: Bool, quote: String, other: String,
                               fiat: [String] = FxRateSource.currencies) -> Bool {
        let blank = quote.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        guard priceThreshold, !blank, !CurrencyConversion.sameCurrency(quote, other) else { return false }
        switch CurrencyConversion.classify(quote, fiat: fiat) {
        case .fiatUnsupported, .invalid: return false
        case .usd, .fiat, .crypto: return true
        }
    }

    /// Gleicher Schwellwert — mit kleiner Toleranz, falls das Eingabefeld ihn minimal anders zurückliest.
    private static func sameThreshold(_ a: Double, _ b: Double) -> Bool {
        a == b || abs(a - b) <= 1e-9 * max(abs(a), abs(b))
    }
}
