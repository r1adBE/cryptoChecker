import Foundation

/// Alarme für Perpetual-Futures: «Funding über/unter x %» (FUNDING_ABOVE/FUNDING_BELOW) und
/// «Open Interest steigt/fällt um x % in N Stunden» (OI_UP/OI_DOWN) — wie `DerivativesAlarm.kt`
/// (gemeinsame Fälle in testdata/parity/alarms_derivatives.json).
///
/// Wie Kursmarken: meldet beim Überschreiten der Schwelle (scharf = `referenceAt` 0, gemeldet =
/// Zeitpunkt > 0) und wird erst wieder scharf, wenn der Wert um die Hysterese zurückgekehrt ist
/// (`fundingHysteresis` bzw. `oiHysteresis` Prozentpunkte). Abklingzeit und «einmalig» wie sonst.
///
/// Open Interest: Verlauf in Coins je Paar, `oiRetentionMillis` lang; verglichen wird mit der
/// jüngsten Messung, die mindestens N Stunden alt ist (höchstens `oiMaxAgeMillis`). Fehlt sie,
/// meldet der Alarm nicht.
enum DerivativesAlarm {

    /// Funding: Wiederscharfstellung 0,005 Prozentpunkte jenseits der Schwelle.
    static let fundingHysteresis = 0.005
    /// Open Interest: Wiederscharfstellung 1 Prozentpunkt jenseits der Schwelle.
    static let oiHysteresis = 1.0

    /// Wählbare Zeitfenster für Open Interest in Stunden.
    static let oiWindows = [1, 4, 24]
    static let defaultOiWindowHours = 4

    /// Vorschläge beim Wechsel auf die Bedingung.
    static let defaultFundingPercent = 0.05
    static let defaultOiPercent = 10.0

    /// Grösste sinnvolle Funding-Schwelle (Betrag, in %).
    static let maxFundingPercent = 10.0
    /// Grösste Open-Interest-Schwelle in %.
    static let maxOiPercent = 1000.0

    /// Funding/Open Interest je Paar höchstens so oft abfragen.
    static let fetchIntervalMillis: Int64 = 5 * 60_000

    private static let hourMillis: Int64 = 3_600_000

    /// Open-Interest-Verlauf: so lange aufbewahren (deckt 24 Stunden samt Spielraum).
    static let oiRetentionMillis: Int64 = 26 * hourMillis
    /// Jüngere Messungen dicht, ältere ausgedünnt.
    static let oiDenseMillis: Int64 = 2 * hourMillis
    static let oiDenseSpacingMillis: Int64 = 4 * 60_000
    static let oiSparseSpacingMillis: Int64 = 25 * 60_000

    /// Börsen, deren Perpetuals eigene Funding-/Open-Interest-Daten liefern (`FuturesDataSource`).
    static let markets: Set<String> = ["BinanceFutures", "BybitFutures", "OkexFutures"]

    /// Paar kann diese Alarme haben: Perpetual an einer Börse mit eigenen Daten.
    static func supports(marketKey: String, perpetual: Bool) -> Bool {
        perpetual && markets.contains(marketKey)
    }

    static func supports(_ watch: Watch) -> Bool {
        supports(marketKey: watch.marketKey, perpetual: watch.contractType == .perpetual)
    }

    /// Gespeichertes Fenster auf 1, 4 oder 24 Stunden abbilden.
    static func oiWindowHours(_ hours: Int) -> Int {
        if hours <= 1 { return 1 }
        if hours <= 4 { return 4 }
        return 24
    }

    /// Gültige Schwelle? Funding mit Vorzeichen (auch 0), Open Interest > 0.
    static func isValidThreshold(_ condition: AlarmCondition, _ value: Double?) -> Bool {
        guard let value, value.isFinite else { return false }
        if condition.isFunding { return abs(value) <= maxFundingPercent }
        if condition.isOpenInterest { return value > 0 && value <= maxOiPercent }
        return false
    }

    /// Funding-Schwelle lesen: wie `ThresholdParser`, dazu ein Vorzeichen («-», «−», «+») und die
    /// Null («0», «0,00»). Ungültig oder über `maxFundingPercent` → nil.
    static func parseFunding(_ text: String, decimalSeparator: Character) -> Double? {
        var rest = ThresholdParser.latinDigits(text).trimmingCharacters(in: .whitespaces)
        var negative = false
        if rest.hasPrefix("-") || rest.hasPrefix("\u{2212}") {
            negative = true
            rest = String(rest.dropFirst()).trimmingCharacters(in: .whitespaces)
        } else if rest.hasPrefix("+") {
            rest = String(rest.dropFirst()).trimmingCharacters(in: .whitespaces)
        }
        let value: Double
        if let parsed = ThresholdParser.parse(rest, decimalSeparator: decimalSeparator) {
            value = parsed
        } else if isZero(rest) {
            value = 0
        } else {
            return nil
        }
        let signed = negative && value != 0 ? -value : value
        return abs(signed) <= maxFundingPercent ? signed : nil
    }

    /// «0», «0,00», «.0»: nur Nullen und höchstens ein Trenner.
    private static func isZero(_ text: String) -> Bool {
        !text.isEmpty && text.contains("0") && text.allSatisfy { $0 == "0" || $0 == "." || $0 == "," }
            && text.filter { $0 == "." || $0 == "," }.count <= 1
    }

    /// Ergebnis einer Prüfung.
    enum Decision: Equatable {
        /// Nichts zu tun.
        case idle
        /// Gemeldeter Alarm: wieder scharf stellen (`referenceAt` = 0).
        case rearm
        /// Melden; Wert = Funding in % bzw. Open-Interest-Veränderung in %.
        case fire(Double)
    }

    /// - Parameter value: Funding in % (FUNDING_*) bzw. Open-Interest-Veränderung in % über das
    ///   Fenster (OI_*); nil = keine Daten (nichts tun)
    /// - Parameter armed: `referenceAt` <= 0
    static func decide(condition: AlarmCondition, threshold: Double, value: Double?, armed: Bool, enabled: Bool,
                       lastTriggeredAt: Int64, now: Int64, cooldownMinutes: Int) -> Decision {
        guard enabled, condition.isDerivatives, threshold.isFinite else { return .idle }
        guard let v = value, v.isFinite else { return .idle }
        if !armed { return rearms(condition, threshold: threshold, value: v) ? .rearm : .idle }
        guard crossed(condition, threshold: threshold, value: v) else { return .idle }
        if lastTriggeredAt > 0 && cooldownMinutes > 0 {
            let elapsed = now - lastTriggeredAt
            if elapsed >= 0 && elapsed < Int64(cooldownMinutes) * 60_000 { return .idle }
        }
        return .fire(v)
    }

    /// Schwelle erreicht? Open Interest «fällt um x %»: Veränderung <= −x.
    static func crossed(_ condition: AlarmCondition, threshold: Double, value: Double) -> Bool {
        switch condition {
        case .FUNDING_ABOVE, .OI_UP: return value >= threshold
        case .FUNDING_BELOW: return value <= threshold
        case .OI_DOWN: return value <= -threshold
        default: return false
        }
    }

    /// Um die Hysterese auf die andere Seite zurück?
    static func rearms(_ condition: AlarmCondition, threshold: Double, value: Double) -> Bool {
        switch condition {
        case .FUNDING_ABOVE: return value < threshold - fundingHysteresis
        case .FUNDING_BELOW: return value > threshold + fundingHysteresis
        case .OI_UP: return value < threshold - oiHysteresis
        case .OI_DOWN: return value > -threshold + oiHysteresis
        default: return false
        }
    }

    // MARK: Open-Interest-Verlauf

    /// Eine Open-Interest-Messung in Coins.
    struct OiPoint: Equatable, Sendable {
        var units: Double
        var time: Int64
    }

    /// Vergleichsmessung darf höchstens so alt sein: N Stunden + max(30 Min., N/4).
    static func oiMaxAgeMillis(_ hours: Int) -> Int64 {
        let window = Int64(max(hours, 1)) * hourMillis
        return window + max(30 * 60_000, window / 4)
    }

    /// Veränderung des Open Interest in % gegenüber der jüngsten Messung, die mindestens `hours`
    /// Stunden alt ist (und höchstens `oiMaxAgeMillis`); nil ohne solche Messung oder ohne
    /// gültigen aktuellen Wert.
    static func oiChangePercent(history: [OiPoint], currentUnits: Double?, hours: Int, now: Int64) -> Double? {
        guard let current = currentUnits, current.isFinite, current > 0 else { return nil }
        let window = Int64(max(hours, 1)) * hourMillis
        let maxAge = oiMaxAgeMillis(hours)
        let past = history
            .filter { $0.units.isFinite && $0.units > 0 && now - $0.time >= window && now - $0.time <= maxAge }
            .max { $0.time < $1.time }
        guard let past else { return nil }
        return (current / past.units - 1) * 100
    }

    /// Neue Messung anhängen (nur wenn die letzte mindestens `oiDenseSpacingMillis` älter ist)
    /// und den Verlauf aufräumen (`pruneOi`).
    static func appendOi(_ history: [OiPoint], _ point: OiPoint, now: Int64) -> [OiPoint] {
        let valid = point.units.isFinite && point.units > 0
        let last = history.max { $0.time < $1.time }
        let add = valid && (last == nil || point.time - (last?.time ?? 0) >= oiDenseSpacingMillis)
        return pruneOi(add ? history + [point] : history, now: now)
    }

    /// Verlauf aufräumen: nach Zeit sortiert, ungültige, zu alte (> `oiRetentionMillis`) und künftige
    /// (> 1 Min.) Messungen weg; dann ausdünnen — von der ältesten an bleibt eine Messung, wenn sie
    /// mindestens `oiDenseSpacingMillis` (jünger als `oiDenseMillis`) bzw. `oiSparseSpacingMillis`
    /// (älter) nach der zuletzt behaltenen liegt.
    static func pruneOi(_ history: [OiPoint], now: Int64) -> [OiPoint] {
        let sorted = history
            .filter { $0.units.isFinite && $0.units > 0 && now - $0.time <= oiRetentionMillis && $0.time - now <= 60_000 }
            .sorted { $0.time < $1.time }
        var kept: [OiPoint] = []
        for p in sorted {
            guard let previous = kept.last else {
                kept.append(p)
                continue
            }
            let spacing = now - p.time <= oiDenseMillis ? oiDenseSpacingMillis : oiSparseSpacingMillis
            if p.time - previous.time >= spacing { kept.append(p) }
        }
        return kept
    }
}

/// Funding und Open Interest für die Alarme FUNDING_* / OI_* — je Paar höchstens alle
/// `DerivativesAlarm.fetchIntervalMillis` abgefragt (auch Fehlschläge zählen). Jede neue
/// Open-Interest-Messung kommt in den Verlauf (`ActivityRepository.appendOiHistory`) — wie
/// `DerivativesAlarmData.kt`.
enum DerivativesAlarmData {

    /// Werte eines Paars; nil-Felder = diesmal keine Daten.
    struct Values: Sendable {
        var fundingPercent: Double?
        var oiUnits: Double?
        var fetchedAt: Int64
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var cache: [String: Values] = [:]

    /// Schlüssel mit Börse und Paar: Wird ein Paar bearbeitet (gleiche Id), gilt der alte Wert nicht.
    private static func key(_ watch: Watch) -> String {
        "\(watch.id)|\(watch.marketKey)|\(watch.baseAsset)|\(watch.quoteAsset)|\(watch.pairId ?? "")"
    }

    private static func cached(_ key: String) -> Values? {
        lock.lock(); defer { lock.unlock() }
        return cache[key]
    }

    private static func store(_ key: String, _ values: Values) {
        lock.lock(); defer { lock.unlock() }
        cache[key] = values
    }

    /// Aktuelle Werte (zwischengespeichert); `price` = letzter Kurs, um den USD-Wert des Open
    /// Interest in Coins umzurechnen. nil für Paare ohne solche Daten.
    static func values(_ watch: Watch, price: Double, now: Int64) async -> Values? {
        guard DerivativesAlarm.supports(watch) else { return nil }
        let k = key(watch)
        if let hit = cached(k), now - hit.fetchedAt >= 0, now - hit.fetchedAt < DerivativesAlarm.fetchIntervalMillis {
            return hit
        }
        let info = try? await FuturesDataSource.fetch(watch: watch)
        var units: Double? = nil
        if let usd = info?.openInterestUsd, usd.isFinite, usd > 0, price > 0 { units = usd / price }
        let values = Values(fundingPercent: info?.fundingRatePercent.flatMap { $0.isFinite ? $0 : nil },
                            oiUnits: units, fetchedAt: now)
        store(k, values)
        if let units {
            ActivityRepository.appendOiHistory(watch.id, DerivativesAlarm.OiPoint(units: units, time: now), now: now)
        }
        return values
    }

    /// Gemessener Wert für einen Alarm: Funding in % bzw. Open-Interest-Veränderung in % über das
    /// Fenster des Alarms (nil ohne alte genug Messung).
    static func value(for alarm: Alarm, watchId: Int64, values: Values, now: Int64) -> Double? {
        if alarm.condition.isFunding { return values.fundingPercent }
        return DerivativesAlarm.oiChangePercent(history: ActivityRepository.oiHistory(watchId),
                                                currentUnits: values.oiUnits,
                                                hours: DerivativesAlarm.oiWindowHours(alarm.windowHours), now: now)
    }
}
