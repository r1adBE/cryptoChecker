import Foundation

/// Entscheidet, ob ein Alarm auslöst — wie `AlarmEvaluator.kt`.
enum AlarmEvaluator {

    /// `moveHistory`: Kursverlauf des Paars für MOVE_PERCENT_WINDOW (gleitendes Fenster, `MoveWindow`).
    static func shouldTrigger(alarm: Alarm, price: Double, previousPrice: Double?, now: Int64, cooldownMinutes: Int,
                              moveHistory: [MoveWindow.PricePoint] = []) -> Bool {
        guard alarm.enabled, price > 0 else { return false }

        if alarm.lastTriggeredAt > 0 && cooldownMinutes > 0 {
            let elapsed = now - alarm.lastTriggeredAt
            if elapsed >= 0 && elapsed < SafeMillis.minutes(cooldownMinutes) { return false }
        }

        switch alarm.condition {
        // Kursmarke: nur scharf (referenceAt == 0) — also beim Überschreiten, nicht bei jeder
        // Aktualisierung, solange der Kurs jenseits der Marke bleibt. Siehe `shouldRearmLevel`.
        case .PRICE_ABOVE:
            return isLevelArmed(alarm) && price >= alarm.threshold
        case .PRICE_BELOW:
            return isLevelArmed(alarm) && price <= alarm.threshold
        case .CHANGE_PERCENT_UP:
            guard let change = changePercent(alarm: alarm, price: price, previousPrice: previousPrice) else { return false }
            return change >= alarm.threshold
        case .CHANGE_PERCENT_DOWN:
            guard let change = changePercent(alarm: alarm, price: price, previousPrice: previousPrice) else { return false }
            return change <= -alarm.threshold
        case .MOVE_PERCENT_WINDOW:
            // Ohne gültigen Bezug setzt der Aufrufer erst einen (`needsReference`).
            if needsWindowReset(alarm: alarm, now: now) { return false }
            guard let reference = alarm.referencePrice else { return false }
            // Gleitendes Fenster: grösste Bewegung gegenüber Verlauf und Bezug seit referenceAt
            guard let change = MoveWindow.changePercent(
                history: moveHistory, reference: MoveWindow.PricePoint(price: reference, time: alarm.referenceAt),
                price: price, hours: alarm.windowHours, since: alarm.referenceAt, now: now) else { return false }
            return abs(change) >= alarm.threshold
        case .VOLUME_SPIKE:
            // Braucht Volumendaten, siehe `shouldTriggerVolumeSpike`.
            return false
        case .NEAR_HIGH, .NEAR_LOW:
            // Braucht Hoch/Tief des Zeitraums, siehe `NearExtreme.decide`.
            return false
        case .FUNDING_ABOVE, .FUNDING_BELOW, .OI_UP, .OI_DOWN:
            // Braucht Funding/Open Interest, siehe `DerivativesAlarm.decide`.
            return false
        }
    }

    /// Hysterese der Kursalarme: 0,2 % der Marke (wie `AlarmEvaluator.LEVEL_HYSTERESIS`).
    static let levelHysteresis = 0.002

    /// Kursalarm frühestens so lange nach der Meldung wieder scharf (wie `AlarmEvaluator.MIN_LEVEL_REARM_MILLIS`).
    static let minLevelRearmMillis: Int64 = 60_000

    /// Kursalarm (PRICE_ABOVE/PRICE_BELOW) scharf? Gespeichert in `referenceAt`: 0 = scharf,
    /// > 0 = schon gemeldet (Zeitpunkt), wartet auf die Rückkehr des Kurses auf die andere
    /// Seite. Neu angelegt, bearbeitet, wieder eingeschaltet → 0: liegt der Kurs dann schon
    /// jenseits der Marke, meldet der Alarm genau einmal.
    static func isLevelArmed(_ alarm: Alarm) -> Bool { alarm.referenceAt <= 0 }

    /// Gemeldeten Kursalarm wieder scharf stellen? Erst, wenn der Kurs um die Hysterese
    /// (`levelHysteresis` der Marke) auf die andere Seite zurückgekehrt ist. `price` in der
    /// Währung des Schwellwerts (wie bei `shouldTrigger`). `now`: frühestens `minLevelRearmMillis`
    /// nach der Meldung (`referenceAt`) — sonst meldete ein Kurs, der mit Abklingzeit 0 um mehr als
    /// die Hysterese pendelt, bei fast jeder Prüfung erneut. nil = ohne Zeitprüfung.
    static func shouldRearmLevel(alarm: Alarm, price: Double, now: Int64? = nil) -> Bool {
        guard alarm.condition.isPriceThreshold, !isLevelArmed(alarm) else { return false }
        guard price.isFinite, price > 0, alarm.threshold > 0 else { return false }
        // Meldung in der Zukunft (Uhr zurückgestellt): nicht blockieren
        if let now {
            let elapsed = now - alarm.referenceAt
            if elapsed >= 0 && elapsed < minLevelRearmMillis { return false }
        }
        if alarm.condition == .PRICE_ABOVE {
            return price < alarm.threshold * (1 - levelHysteresis)
        }
        return price > alarm.threshold * (1 + levelHysteresis)
    }

    /// Volumen-Spike: `ratio` = Volumen der letzten abgeschlossenen Stunde geteilt
    /// durch den Schnitt der 24 Stunden davor, `candleOpenTime` = Startzeit dieser
    /// Stundenkerze. Löst je Kerze höchstens einmal aus (gemerkt in `referenceAt`).
    static func shouldTriggerVolumeSpike(alarm: Alarm, ratio: Double?, candleOpenTime: Int64,
                                         now: Int64, cooldownMinutes: Int) -> Bool {
        guard alarm.condition == .VOLUME_SPIKE, alarm.enabled else { return false }
        guard let ratio, ratio.isFinite else { return false }
        guard candleOpenTime > 0, alarm.threshold > 0 else { return false }
        // Dieselbe Kerze wurde schon gemeldet
        if alarm.referenceAt == candleOpenTime { return false }

        if alarm.lastTriggeredAt > 0 && cooldownMinutes > 0 {
            let elapsed = now - alarm.lastTriggeredAt
            if elapsed >= 0 && elapsed < SafeMillis.minutes(cooldownMinutes) { return false }
        }
        return ratio >= alarm.threshold
    }

    /// Bewegungs-Alarm ohne gültigen Bezug (noch keiner, oder er liegt in der Zukunft — z. B. nach
    /// einer Zeitumstellung): Aufrufer setzt den aktuellen Kurs als Bezug. Ein ALTER Bezug braucht
    /// keinen Neubeginn mehr — das Fenster gleitet (`MoveWindow`); vorher setzte der Ablauf des
    /// Fensters den Bezug zurück, ohne die Bewegung zu prüfen (meldete im Hintergrund-Takt nie).
    static func needsWindowReset(alarm: Alarm, now: Int64) -> Bool {
        guard alarm.condition == .MOVE_PERCENT_WINDOW else { return false }
        guard let reference = alarm.referencePrice, reference.isFinite, reference > 0, alarm.referenceAt > 0 else { return true }
        return alarm.referenceAt - now > 60_000
    }

    /// Braucht der Alarm zuerst einen Bezugskurs? Bewegungs-Alarm siehe `needsWindowReset`;
    /// Prozentalarm (CHANGE_PERCENT_*) ohne Bezug — etwa angelegt, bevor das Paar einen Kurs hatte:
    /// Aufrufer setzt den aktuellen Kurs als Bezug (und meldet diesmal nicht), sonst verglich der
    /// Alarm nur aufeinanderfolgende Kurse — wie `AlarmEvaluator.needsReference` (Android).
    static func needsReference(alarm: Alarm, now: Int64) -> Bool {
        switch alarm.condition {
        case .MOVE_PERCENT_WINDOW:
            return needsWindowReset(alarm: alarm, now: now)
        case .CHANGE_PERCENT_UP, .CHANGE_PERCENT_DOWN:
            guard let reference = alarm.referencePrice, reference.isFinite, reference > 0 else { return true }
            return false
        default:
            return false
        }
    }

    /// Zustand nach dem Auslösen — wie `AlarmEvaluator.triggered` (Android): letzte Meldung merken;
    /// Prozentalarme messen ab `price` weiter; «Nahe am Hoch/Tief» merkt die gemeldete Marke
    /// (`nearLevel`); Bewegungs-Alarm: neues Fenster ab jetzt; Volumen-Spike: die gemeldete Kerze
    /// (`candleOpenTime`); Kursmarken, «Nahe am Hoch/Tief», Funding und Open Interest: gemeldet
    /// (`referenceAt` > 0) bis zur Wiederscharfstellung; einmalige Alarme aus.
    static func triggered(_ alarm: Alarm, price: Double, time: Int64, candleOpenTime: Int64? = nil,
                          nearLevel: Double? = nil) -> Alarm {
        var a = alarm
        a.lastTriggeredAt = time
        a.lastTriggeredPrice = price
        if alarm.condition.isNearExtreme {
            a.referencePrice = nearLevel ?? price
        } else if alarm.condition.isPercent {
            a.referencePrice = price
        }
        switch alarm.condition {
        case .VOLUME_SPIKE: a.referenceAt = candleOpenTime ?? alarm.referenceAt
        case .CHANGE_PERCENT_UP, .CHANGE_PERCENT_DOWN: break
        default: a.referenceAt = time
        }
        a.enabled = alarm.repeating
        return a
    }

    /// Auf 1e-6 gerundet (`AlarmPercent`), damit genau die Marke auslöst (3.00 → 3.09 = +3 %). Wie Android.
    static func changePercent(alarm: Alarm, price: Double, previousPrice: Double?) -> Double? {
        guard let reference = alarm.referencePrice ?? previousPrice, reference > 0 else { return nil }
        return AlarmPercent.rounded((price - reference) / reference * 100)
    }
}
