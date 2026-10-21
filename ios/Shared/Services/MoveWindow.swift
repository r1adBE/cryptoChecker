import Foundation

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
        let window = SafeMillis.hours(max(hours, 1))
        return window + max(30 * 60_000, window / 4)
    }

    /// Grösste Veränderung in % von `price` gegenüber den Punkten aus `history` und `reference`
    /// innerhalb von `hours` Stunden (plus dem jüngsten Punkt knapp davor); nur Punkte ab `since`.
    /// nil ohne gültigen Kurs oder ohne Vergleichspunkt.
    static func changePercent(history: [PricePoint], reference: PricePoint?, price: Double, hours: Int,
                              since: Int64, now: Int64) -> Double? {
        guard price.isFinite, price > 0 else { return nil }
        let window = SafeMillis.hours(max(hours, 1))
        let maxAge = maxAgeMillis(hours)
        let points = (reference.map { history + [$0] } ?? history)
            .filter { $0.price.isFinite && $0.price > 0 && $0.time >= since && $0.time <= now }
        var candidates = points.filter { now - $0.time <= window }
        if let bridge = points.filter({ now - $0.time > window && now - $0.time <= maxAge }).max(by: { $0.time < $1.time }) {
            candidates.append(bridge)
        }
        var best: Double?
        for p in candidates {
            // Gerundet wie alle Prozent-Alarme (`AlarmPercent`): genau die Marke zählt
            let change = AlarmPercent.rounded((price - p.price) / p.price * 100)
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

/// Prozent-Werte der Alarme vor dem Vergleich mit der Schwelle — wie `AlarmPercent` (MoveWindow.kt): auf 1e-6
/// Prozentpunkte gerundet (nächster Wert, genau die Mitte vom Nullpunkt weg). Sonst verfehlt ein Alarm genau seine Marke
/// um einen Gleitkomma-Rest — 3.00 → 3.09 ergibt 2.9999999999999956 % und meldete bei «+3 %» nicht. Gleiche Regel für
/// Veränderung (`AlarmEvaluator.changePercent`), Bewegungs-Fenster (`MoveWindow.changePercent`) und Abstand zum Hoch/Tief
/// (`NearExtreme.decide`).
enum AlarmPercent {
    /// Kehrwert der Auflösung: 1e-6 Prozentpunkte.
    static let scale = 1e6

    /// `percent` auf 1e-6 gerundet; nicht endliche Werte unverändert.
    static func rounded(_ percent: Double) -> Double {
        guard percent.isFinite else { return percent }
        let scaled = (abs(percent) * scale + 0.5).rounded(.down)
        return Double(signOf: percent, magnitudeOf: scaled / scale)
    }
}
