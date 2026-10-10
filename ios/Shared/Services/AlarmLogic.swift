import Foundation

/// Entscheidet, ob ein Alarm auslöst — wie `AlarmEvaluator.kt`.
enum AlarmEvaluator {

    /// `moveHistory`: Kursverlauf des Paars für MOVE_PERCENT_WINDOW (gleitendes Fenster, `MoveWindow`).
    static func shouldTrigger(alarm: Alarm, price: Double, previousPrice: Double?, now: Int64, cooldownMinutes: Int,
                              moveHistory: [MoveWindow.PricePoint] = []) -> Bool {
        guard alarm.enabled, price > 0 else { return false }

        if alarm.lastTriggeredAt > 0 && cooldownMinutes > 0 {
            let elapsed = now - alarm.lastTriggeredAt
            if elapsed >= 0 && elapsed < Int64(cooldownMinutes) * 60_000 { return false }
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

    /// Kursalarm (PRICE_ABOVE/PRICE_BELOW) scharf? Gespeichert in `referenceAt`: 0 = scharf,
    /// > 0 = schon gemeldet (Zeitpunkt), wartet auf die Rückkehr des Kurses auf die andere
    /// Seite. Neu angelegt, bearbeitet, wieder eingeschaltet → 0: liegt der Kurs dann schon
    /// jenseits der Marke, meldet der Alarm genau einmal.
    static func isLevelArmed(_ alarm: Alarm) -> Bool { alarm.referenceAt <= 0 }

    /// Gemeldeten Kursalarm wieder scharf stellen? Erst, wenn der Kurs um die Hysterese
    /// (`levelHysteresis` der Marke) auf die andere Seite zurückgekehrt ist. `price` in der
    /// Währung des Schwellwerts (wie bei `shouldTrigger`).
    static func shouldRearmLevel(alarm: Alarm, price: Double) -> Bool {
        guard alarm.condition.isPriceThreshold, !isLevelArmed(alarm) else { return false }
        guard price.isFinite, price > 0, alarm.threshold > 0 else { return false }
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
            if elapsed >= 0 && elapsed < Int64(cooldownMinutes) * 60_000 { return false }
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

    static func changePercent(alarm: Alarm, price: Double, previousPrice: Double?) -> Double? {
        guard let reference = alarm.referencePrice ?? previousPrice, reference > 0 else { return nil }
        return (price - reference) / reference * 100
    }
}

/// Bewegungs-Alarm «x % in y Stunden» mit gleitendem Fenster — wie `MoveWindow.kt` (gemeinsame
/// Fälle in Tests/Parity/alarms.json).
///
/// Je Paar ein kleiner Kursverlauf (die letzten `denseMillis` dicht, ältere Punkte ausgedünnt,
/// `retentionMillis` lang). Verglichen wird der aktuelle Kurs mit jedem Punkt der letzten y Stunden
/// und dazu mit dem jüngsten Punkt knapp davor (höchstens `maxAgeMillis` alt) — so deckt auch eine
/// Hintergrund-Aktualisierung im Stundentakt ein 1-Stunden-Fenster ab. Es zählt die grösste
/// Bewegung. Punkte vor `since` (`referenceAt`: Beginn des Alarms bzw. letzte Meldung) zählen nicht,
/// damit dieselbe Bewegung nicht zweimal meldet. Der gespeicherte Bezug zählt wie ein Punkt.
enum MoveWindow {

    /// Ein Kurs zum Zeitpunkt `time` (Epoch-ms).
    struct PricePoint: Equatable, Sendable {
        var price: Double
        var time: Int64
    }

    private static let hourMillis: Int64 = 3_600_000
    /// Verlauf so lange aufbewahren: längstes Fenster (24 h) plus Spielraum plus Reserve.
    static let retentionMillis: Int64 = 31 * 3_600_000
    /// Jüngere Punkte dicht (`denseSpacingMillis`), ältere ausgedünnt (`sparseSpacingMillis`).
    static let denseMillis: Int64 = 2 * 3_600_000
    static let denseSpacingMillis: Int64 = 2 * 60_000
    static let sparseSpacingMillis: Int64 = 15 * 60_000

    /// Vergleichspunkt vor dem Fenster darf höchstens so alt sein: y Stunden + max(30 Min., y/4).
    static func maxAgeMillis(_ hours: Int) -> Int64 {
        let window = Int64(max(hours, 1)) * hourMillis
        return window + max(30 * 60_000, window / 4)
    }

    /// Grösste Veränderung in % von `price` gegenüber den Punkten aus `history` und `reference`
    /// innerhalb von `hours` Stunden (plus dem jüngsten Punkt knapp davor); nur Punkte ab `since`.
    /// nil ohne gültigen Kurs oder ohne Vergleichspunkt.
    static func changePercent(history: [PricePoint], reference: PricePoint?, price: Double, hours: Int,
                              since: Int64, now: Int64) -> Double? {
        guard price.isFinite, price > 0 else { return nil }
        let window = Int64(max(hours, 1)) * hourMillis
        let maxAge = maxAgeMillis(hours)
        let points = (reference.map { history + [$0] } ?? history)
            .filter { $0.price.isFinite && $0.price > 0 && $0.time >= since && $0.time <= now }
        var candidates = points.filter { now - $0.time <= window }
        if let bridge = points.filter({ now - $0.time > window && now - $0.time <= maxAge }).max(by: { $0.time < $1.time }) {
            candidates.append(bridge)
        }
        var best: Double?
        for p in candidates {
            let change = (price - p.price) / p.price * 100
            if best == nil || abs(change) > abs(best ?? 0) { best = change }
        }
        return best
    }

    /// Neuen Punkt anhängen (nur wenn der letzte mindestens `denseSpacingMillis` älter ist) und aufräumen.
    static func append(_ history: [PricePoint], _ point: PricePoint, now: Int64) -> [PricePoint] {
        let valid = point.price.isFinite && point.price > 0
        let last = history.max { $0.time < $1.time }
        let add = valid && (last == nil || point.time - (last?.time ?? 0) >= denseSpacingMillis)
        return prune(add ? history + [point] : history, now: now)
    }

    /// Verlauf aufräumen: nach Zeit sortiert, ungültige, zu alte (> `retentionMillis`) und künftige
    /// (> 1 Min.) Punkte weg; dann ausdünnen wie `DerivativesAlarm.pruneOi`.
    static func prune(_ history: [PricePoint], now: Int64) -> [PricePoint] {
        let sorted = history
            .filter { $0.price.isFinite && $0.price > 0 && now - $0.time <= retentionMillis && $0.time - now <= 60_000 }
            .sorted { $0.time < $1.time }
        var kept: [PricePoint] = []
        for p in sorted {
            guard let previous = kept.last else {
                kept.append(p)
                continue
            }
            let spacing = now - p.time <= denseMillis ? denseSpacingMillis : sparseSpacingMillis
            if p.time - previous.time >= spacing { kept.append(p) }
        }
        return kept
    }
}

/// Kursverlauf je Paar für den Bewegungs-Alarm — wie `MoveHistoryStore.kt`. Nur für Paare mit so
/// einem Alarm, im gemeinsamen Speicher (App Group, auch die Hintergrund-Aktualisierung prüft
/// Alarme). Format wie in Android: `{"<watchId>": [[zeit, kurs], …]}`. Fehlt der Verlauf, prüft der
/// Alarm gegen seinen gespeicherten Bezug.
enum MoveHistoryStore {
    private static let key = "move_history"
    private static let lock = NSLock()

    /// Gespeicherter Verlauf des Paars, älteste zuerst.
    static func history(_ watchId: Int64) -> [MoveWindow.PricePoint] {
        lock.lock(); defer { lock.unlock() }
        return decode(readMap()[String(watchId)])
    }

    /// Punkt anhängen und aufräumen (`MoveWindow.append`); schreibt nur, wenn sich etwas ändert.
    /// Verläufe anderer Paare, deren letzter Punkt älter als die Aufbewahrung ist, fallen weg.
    static func append(_ watchId: Int64, _ point: MoveWindow.PricePoint, now: Int64) {
        lock.lock(); defer { lock.unlock() }
        var map = readMap()
        let id = String(watchId)
        let before = decode(map[id])
        let updated = MoveWindow.append(before, point, now: now)
        // Live-Kurse kommen sekündlich: meist kein neuer Punkt — dann nichts schreiben
        guard updated != before else { return }
        map[id] = updated.map { [NSNumber(value: $0.time), NSNumber(value: $0.price)] }
        for other in Array(map.keys) where other != id {
            let last = decode(map[other]).map(\.time).max()
            if last == nil || now - (last ?? 0) > MoveWindow.retentionMillis { map.removeValue(forKey: other) }
        }
        guard let data = try? JSONSerialization.data(withJSONObject: map),
              let text = String(data: data, encoding: .utf8) else { return }
        SharedStorage.defaults.set(text, forKey: key)
    }

    private static func readMap() -> [String: Any] {
        guard let text = SharedStorage.defaults.string(forKey: key), let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return object
    }

    private static func decode(_ value: Any?) -> [MoveWindow.PricePoint] {
        guard let array = value as? [Any] else { return [] }
        return array.compactMap { item in
            guard let pair = item as? [Any], pair.count >= 2,
                  let time = (pair[0] as? NSNumber)?.int64Value,
                  let price = (pair[1] as? NSNumber)?.doubleValue else { return nil }
            return MoveWindow.PricePoint(price: price, time: time)
        }
    }
}

/// Alarmbedingung als Text — wie `AlarmTexts.kt`.
enum AlarmTexts {
    static func conditionName(_ c: AlarmCondition) -> String {
        switch c {
        case .PRICE_ABOVE: L("alarm_condition_above")
        case .PRICE_BELOW: L("alarm_condition_below")
        case .CHANGE_PERCENT_UP: L("alarm_condition_up_percent")
        case .CHANGE_PERCENT_DOWN: L("alarm_condition_down_percent")
        case .MOVE_PERCENT_WINDOW: L("alarm_condition_move_percent")
        case .VOLUME_SPIKE: L("alarm_condition_volume_spike")
        case .NEAR_HIGH: L("alarm_condition_near_high")
        case .NEAR_LOW: L("alarm_condition_near_low")
        case .FUNDING_ABOVE: L("alarm_condition_funding_above")
        case .FUNDING_BELOW: L("alarm_condition_funding_below")
        case .OI_UP: L("alarm_condition_oi_up")
        case .OI_DOWN: L("alarm_condition_oi_down")
        }
    }

    static func describe(_ alarm: Alarm) -> String {
        let name = conditionName(alarm.condition)
        // «Volumen-Spike ×3»
        if alarm.condition == .VOLUME_SPIKE {
            return "\(name) ×\(factor(alarm.threshold))"
        }
        // «Funding über 0,05 %»
        if alarm.condition.isFunding {
            return "\(name) " + fundingPercent(alarm.threshold)
        }
        // «Open Interest steigt 10 % in 4 Std.»
        if alarm.condition.isOpenInterest {
            return "\(name) \(percent(alarm.threshold)) "
                + L("alarm_window_hours", count: DerivativesAlarm.oiWindowHours(alarm.windowHours))
        }
        // Nur neue Hochs/Tiefs (Abstand 0): «Neues 30-Tage-Hoch»
        if alarm.condition.isNearExtreme && NearExtreme.isNewOnly(alarm.threshold) {
            return newExtremeLabel(high: alarm.condition == .NEAR_HIGH, days: alarm.windowHours)
        }
        // «Nahe am Hoch 2.00% · 30 Tage»
        if alarm.condition.isNearExtreme {
            return "\(name) " + String(format: "%.2f%%", locale: Locale.current, alarm.threshold) + " · "
                + windowLabel(NearExtreme.windowDays(alarm.windowHours))
        }
        let value: String
        if alarm.condition.isPercent {
            value = String(format: "%.2f%%", locale: Locale.current, alarm.threshold)
        } else if let currency = alarm.convertCurrency {
            // Schwellwert in eigener Währung: «Über 60’000 CHF»
            value = PriceFormat.priceWithCurrency(alarm.threshold, currency)
        } else {
            value = PriceFormat.price(alarm.threshold)
        }
        if alarm.condition == .MOVE_PERCENT_WINDOW {
            return "\(name) \(value) " + L("alarm_window_hours", count: alarm.windowHours)
        }
        return "\(name) \(value)"
    }

    /// Alarm als Satz, z. B. «Sag mir Bescheid, wenn BTC über 70’000 USDT steigt.» —
    /// `base` = Basis-Symbol, `currency` = Währung des Schwellwerts (Kursalarme),
    /// `windowHours` nur für «bewegt sich um x % in y Stunden». Ohne gültigen Wert
    /// der Hinweis `alarm_sentence_incomplete`.
    static func sentence(condition: AlarmCondition, base: String, threshold: Double?,
                         currency: String, windowHours: Int) -> String {
        // Nur neue Hochs/Tiefs: «Sag mir Bescheid, wenn BTC ein neues 30-Tage-Hoch erreicht.»
        if condition.isNearExtreme, let threshold, NearExtreme.isNewOnly(threshold) {
            guard !base.trimmingCharacters(in: .whitespaces).isEmpty else { return L("alarm_sentence_incomplete") }
            return L("alarm_sentence_new_extreme", base, extremeLabel(high: condition == .NEAR_HIGH, days: windowHours))
        }
        // Funding: mit Vorzeichen, auch 0 («unter 0 %» = Funding wird negativ)
        if condition.isFunding {
            guard let threshold, DerivativesAlarm.isValidThreshold(condition, threshold),
                  !base.trimmingCharacters(in: .whitespaces).isEmpty else { return L("alarm_sentence_incomplete") }
            return L(condition == .FUNDING_ABOVE ? "alarm_sentence_funding_above" : "alarm_sentence_funding_below",
                     base, fundingPercent(threshold))
        }
        guard let threshold, threshold > 0, threshold.isFinite else { return L("alarm_sentence_incomplete") }
        switch condition {
        case .PRICE_ABOVE:
            return L("alarm_sentence_above", base, PriceFormat.priceWithCurrency(threshold, currency))
        case .PRICE_BELOW:
            return L("alarm_sentence_below", base, PriceFormat.priceWithCurrency(threshold, currency))
        case .CHANGE_PERCENT_UP:
            return L("alarm_sentence_up_percent", base, percent(threshold))
        case .CHANGE_PERCENT_DOWN:
            return L("alarm_sentence_down_percent", base, percent(threshold))
        case .MOVE_PERCENT_WINDOW:
            return L("alarm_sentence_move_percent", count: max(windowHours, 1), base, percent(threshold), max(windowHours, 1))
        case .VOLUME_SPIKE:
            return L("alarm_sentence_volume", base, factor(threshold))
        case .NEAR_HIGH:
            // «Sag mir Bescheid, wenn BTC höchstens 2 % unter dem 30-Tage-Hoch liegt.» (windowHours = Tage)
            return L("alarm_sentence_near_high", base, percent(threshold), extremeLabel(high: true, days: windowHours))
        case .NEAR_LOW:
            return L("alarm_sentence_near_low", base, percent(threshold), extremeLabel(high: false, days: windowHours))
        case .FUNDING_ABOVE, .FUNDING_BELOW:
            // Oben behandelt (Vorzeichen erlaubt)
            return L("alarm_sentence_incomplete")
        case .OI_UP, .OI_DOWN:
            // «Sag mir Bescheid, wenn das Open Interest von BTC innerhalb von 4 Stunden um 10 % steigt.»
            let hours = DerivativesAlarm.oiWindowHours(windowHours)
            return L(condition == .OI_UP ? "alarm_sentence_oi_up" : "alarm_sentence_oi_down",
                     count: hours, base, percent(threshold), hours)
        }
    }

    /// «30 Tage», «90 Tage», «1 Jahr» — Zeitraum des Alarms «Nahe am Hoch/Tief».
    static func windowLabel(_ days: Int) -> String {
        days >= 365 ? L("alarm_near_window_year") : L("alarm_near_window_days", count: days)
    }

    /// «Neues 30-Tage-Hoch», «Neues Jahrestief» — Titel und Schnell-Alarm.
    static func newExtremeLabel(high: Bool, days: Int) -> String {
        L("alarm_new_extreme", extremeLabel(high: high, days: days))
    }

    /// «30-Tage-Hoch», «Jahrestief» … für Satz und Benachrichtigung.
    static func extremeLabel(high: Bool, days: Int) -> String {
        switch NearExtreme.windowDays(days) {
        case 90: return L(high ? "alarm_near_extreme_high_90" : "alarm_near_extreme_low_90")
        case 365: return L(high ? "alarm_near_extreme_high_365" : "alarm_near_extreme_low_365")
        default: return L(high ? "alarm_near_extreme_high_30" : "alarm_near_extreme_low_30")
        }
    }

    /// Benachrichtigung «BTC ist 1,6 % unter dem 30-Tage-Hoch (98’450 / 100’050)» bzw.
    /// «Neues 30-Tage-Hoch für BTC: 101’200 USDT (bisher 100’050)». Kurse in `currency`.
    static func nearExtremeText(_ alarm: Alarm, symbol: String, currency: String, price: Double,
                                newExtreme: Bool, distancePercent: Double, extreme: Double) -> String {
        let high = alarm.condition == .NEAR_HIGH
        let label = extremeLabel(high: high, days: alarm.windowHours)
        if newExtreme {
            return L("alarm_near_notification_new", label, symbol,
                     PriceFormat.priceWithCurrency(price, currency), PriceFormat.price(extreme))
        }
        // Eine Nachkommastelle (1.63 → 1.6), damit die Meldung ruhig bleibt
        let distance = percent((distancePercent * 10).rounded() / 10)
        return L(high ? "alarm_near_notification_high" : "alarm_near_notification_low",
                 symbol, distance, label, PriceFormat.price(price), PriceFormat.price(extreme))
    }

    /// Satz zu einem gespeicherten Alarm; Kursalarme in seiner Währung, sonst in der Quote.
    static func sentence(_ alarm: Alarm, base: String, quote: String) -> String {
        sentence(condition: alarm.condition, base: base, threshold: alarm.threshold,
                 currency: alarm.convertCurrency ?? quote, windowHours: alarm.windowHours)
    }

    /// Prozent nach Region: «5%» (en), «5 %» (de).
    static func percent(_ value: Double) -> String {
        let f = NumberFormatter()
        f.numberStyle = .percent
        f.locale = Locale.current
        f.minimumFractionDigits = 0
        f.maximumFractionDigits = 2
        return f.string(from: NSNumber(value: value / 100)) ?? String(format: "%.2f%%", value)
    }

    /// Funding Rate: «0,05 %», «−0,0125 %» — bis vier Nachkommastellen (wie `AlarmSentence.fundingPercent`).
    static func fundingPercent(_ value: Double) -> String {
        let f = NumberFormatter()
        f.numberStyle = .percent
        f.locale = Locale.current
        f.minimumFractionDigits = 0
        f.maximumFractionDigits = 4
        // −0 nicht als «-0 %» zeigen
        let shown = value == 0 ? 0 : value / 100
        return f.string(from: NSNumber(value: shown)) ?? String(format: "%.4f%%", value)
    }

    /// Veränderung mit Vorzeichen und einer Nachkommastelle: «+12,3 %», «-4 %».
    static func signedPercent(_ value: Double) -> String {
        let f = NumberFormatter()
        f.numberStyle = .percent
        f.locale = Locale.current
        f.minimumFractionDigits = 0
        f.maximumFractionDigits = 1
        let text = f.string(from: NSNumber(value: value / 100)) ?? String(format: "%.1f%%", value)
        return value > 0 && !text.hasPrefix("+") ? "+" + text : text
    }

    /// Gemessener Wert in der Mitteilung eines Funding- bzw. Open-Interest-Alarms:
    /// Funding «0,061 %», Open-Interest-Veränderung «+12,3 %» — wie `AlarmTexts.derivativesValue`.
    static func derivativesValue(_ condition: AlarmCondition, _ value: Double) -> String {
        condition.isFunding ? fundingPercent(value) : signedPercent(value)
    }

    /// Faktor ohne überflüssige Nachkommastellen: 3 → "3", 4.25 → "4.3" — in den Ziffern der
    /// App-Sprache (wie Android `AlarmTexts.factor`).
    static func factor(_ value: Double) -> String {
        guard value.isFinite else { return LocaleNumbers.integer(0) }
        return LocaleNumbers.decimal(value, maxDecimals: 1, minDecimals: 0)
    }
}

/// Sätze für die Sprachausgabe — wie `SpokenText.kt`.
enum SpokenText {
    static func price(_ watch: Watch, _ price: Double) -> String {
        L("tts_price", marketName(watch), watch.baseAsset, watch.quoteAsset, PriceFormat.spokenPrice(price))
    }

    static func alarm(_ watch: Watch, _ condition: AlarmCondition, _ price: Double) -> String {
        let direction: String
        switch condition {
        case .PRICE_ABOVE, .CHANGE_PERCENT_UP: direction = L("tts_direction_up")
        case .PRICE_BELOW, .CHANGE_PERCENT_DOWN: direction = L("tts_direction_down")
        case .MOVE_PERCENT_WINDOW: direction = L("tts_direction_move")
        case .VOLUME_SPIKE: direction = L("tts_direction_volume_spike")
        case .NEAR_HIGH: direction = L("tts_direction_near_high")
        case .NEAR_LOW: direction = L("tts_direction_near_low")
        case .FUNDING_ABOVE: direction = L("tts_direction_funding_above")
        case .FUNDING_BELOW: direction = L("tts_direction_funding_below")
        case .OI_UP: direction = L("tts_direction_oi_up")
        case .OI_DOWN: direction = L("tts_direction_oi_down")
        }
        return L("tts_alarm", marketName(watch), watch.baseAsset, watch.quoteAsset, direction, PriceFormat.spokenPrice(price))
    }

    private static func marketName(_ watch: Watch) -> String {
        MarketsConfig.market(watch.marketKey)?.ttsName ?? watch.marketName
    }
}
