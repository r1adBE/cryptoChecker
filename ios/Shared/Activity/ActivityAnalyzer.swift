import Foundation

/// Reine Auswertung ohne Netz: aus Stundenkerzen, Funding Rate und Open Interest
/// werden Signale («hier passiert gerade etwas») und Gründe («warum bewegt sich
/// das?») — als Daten mit Art und Zahlen. Texte macht die Oberfläche, damit alles
/// übersetzbar und prüfbar bleibt. Wie `ActivityAnalyzer.kt` (Werte 1:1 übernommen).
enum ActivityAnalyzer {

    static let hourMillis: Int64 = 60 * 60_000

    /// Vergleichsstunden für Volatilität und Volumen.
    static let windowHours = 24

    /// Mindestens so viele gültige Stunden-Renditen (genau 1 h Abstand) im 24-h-Fenster, sonst keine
    /// Kennzahlen — bei vielen Lücken (dünn gehandelt, Coinbase) wäre σ nicht belastbar.
    static let minWindowReturns = 20

    // MARK: Schwellen der Einordnung («Warum»-Blatt)
    static let priceZThreshold = 3.0
    /// Mindestbewegung, damit ruhige Coins (Stablecoins) nicht bei Kleinigkeiten melden.
    static let priceMinMovePercent = 1.5
    static let priceStrongZ = 5.0
    static let priceStrongMovePercent = 5.0
    static let volumeSpikeRatio = 3.0
    static let volumeStrongRatio = 6.0
    /// Funding in % je 8 Stunden (stündliches Funding wird umgerechnet: `fundingPer8h`).
    static let fundingExtremePercent = 0.05
    static let fundingStrongPercent = 0.1
    static let oiJumpPercent = 10.0
    static let oiStrongPercent = 20.0

    /// Höchstens so viele der 24 Vergleichsstunden dürfen ganz ohne Handel sein (ein Viertel),
    /// sonst ist das Volumen-Verhältnis unzuverlässig und entfällt.
    static let maxEmptyVolumeHours = windowHours / 4

    /// Untergrenze der Standardabweichung (in %), sonst teilt ein völlig flacher Verlauf durch 0.
    static let minSigmaPercent = 0.01

    static let signalTtlMillis: Int64 = 1 * hourMillis

    // ⚡-Signale: bewusst strenger als die Einordnung im «Warum»-Blatt,
    // damit nur wirklich Auffälliges markiert wird (v16.2.2 nachgeschärft).
    static let signalPriceZ = 3.5
    static let signalPriceMinMovePercent = 2.5
    static let signalPriceStrongZ = 5.0
    static let signalPriceStrongMovePercent = 6.0
    static let signalVolumeRatio = 5.0
    static let signalVolumeStrongRatio = 10.0
    static let signalFundingPercent = 0.1
    static let signalFundingStrongPercent = 0.2
    static let signalOiPercent = 15.0
    static let signalOiStrongPercent = 25.0

    /// Funding-Schwellen gelten pro so vielen Stunden (wie `DerivativesAlarm.fundingBasisHours`).
    static let fundingBasisHours = 8

    /// Abrechnungsintervall in Stunden, wenn es nicht `fundingBasisHours` ist; sonst nil. `reported` =
    /// Intervall des Kontrakts laut Börse, sonst das übliche der Quelle (Hyperliquid: 1) — wie Android.
    static func fundingIntervalHours(_ source: String?, reported: Int? = nil) -> Int? {
        // Unbekanntes Intervall: wie 8 Std. — nur für die Einschätzung im Blatt, nie für Alarme (wie Android)
        let hours = reported.flatMap { (1...24).contains($0) ? $0 : nil } ?? FuturesSources.fundingIntervalHours(source) ?? fundingBasisHours
        return hours == fundingBasisHours ? nil : hours
    }

    /// Funding für den Vergleich mit den Schwellen auf `fundingBasisHours` Stunden umgerechnet, damit
    /// Gleiches mit Gleichem verglichen wird (stündlicher Satz zählt achtfach). `intervalHours` nil = 8.
    static func fundingPer8h(_ percent: Double, intervalHours: Int?) -> Double {
        let hours = intervalHours.flatMap { $0 > 0 ? $0 : nil } ?? fundingBasisHours
        return percent * Double(fundingBasisHours) / Double(hours)
    }
    static let analysisIntervalMillis: Int64 = 10 * 60_000
    static let notifyIntervalMillis: Int64 = 60 * 60_000

    // MARK: Gründe
    static let marketMovePercent = 1.5
    static let coinMovePercent = 3.0
    static let marketStrongPercent = 3.0
    static let volumeHighRatio = 2.0
    static let volumeLowRatio = 0.6
    static let volatilityHighZ = 2.0
    /// «Schwankung höher als üblich» braucht neben |z| ≥ `volatilityHighZ` auch eine Stunden-
    /// Bewegung von mindestens 0,5 % — sonst meldet ein sehr ruhiger Coin schon Staub.
    static let volatilityMinMovePercent = 0.5
    static let maxReasons = 5

    /// «Nähe zum Tief»: gleiche Tage wie das Hoch.
    static let lowDays = 30

    /// Kerzen für «Warum?»: 72 Stunden Lage + 24-h-Fenster + Vergleichsstunden davor, mit Reserve.
    static let whyHourlyLimit = 100

    // MARK: Lage in den letzten drei Tagen
    /// Fenster in Stunden.
    static let rangeHours = 72
    /// Mindestens so viele Stunden Verlauf, sonst keine Aussage.
    static let rangeMinHours = 48
    /// Bis zu diesem Abstand (in %) «nahe am 3-Tage-Hoch/-Tief».
    static let rangeNearPercent = 1.0
    /// «Nahe am Hoch/Tief» nur, wenn die Spanne der drei Tage mindestens so gross ist (in %).
    static let rangeMinSpanPercent = 3.0
    /// Untergrenze für «−8 % seit dem 3-Tage-Hoch» (in %).
    static let rangeFloorPercent = 5.0
    /// Abstand auffällig ab k · σ₁ₕ · √72 (mindestens `rangeFloorPercent`).
    static let rangeSigmaK = 2.0
    /// Ähnlich genug, dass «nur dieser Coin» nicht stimmt: Eine Referenz bewegt sich in dieselbe
    /// Richtung und mindestens halb so stark wie der Coin.
    static let similarRatio = 0.5

    /// «Nähe zum Hoch»: so viele Tage zurück.
    static let highDays = 30
    static let dayMillis: Int64 = 24 * hourMillis

    /// Stärkstes zuerst, bei gleicher Stärke nach Art — stabil wie `sortedWith` in Kotlin.
    static func sortedSignals(_ signals: [ActivitySignal]) -> [ActivitySignal] {
        signals.enumerated().sorted { a, b in
            let sa = a.element.severity.ordinal, sb = b.element.severity.ordinal
            if sa != sb { return sa > sb }
            let ka = a.element.kind.ordinal, kb = b.element.kind.ordinal
            if ka != kb { return ka < kb }
            return a.offset < b.offset
        }.map(\.element)
    }

    // MARK: Kennzahlen

    /// Letzte abgeschlossene Stunde gegen die 24 Stunden davor. Renditen Schluss zu Schluss in %,
    /// z = (r − Mittel) / Standardabweichung (Stichprobe, n − 1). Nur Renditen über genau eine
    /// Stunde zählen: Coinbase u. a. lassen Stunden ohne Handel weg, sonst gälte eine Bewegung über
    /// mehrere Stunden als Stunden-Bewegung (z zu hoch, falsches ⚡). Die abgeschlossene Stunde ist
    /// die vor der laufenden zu `now` (im Raster der Kerzen; nil = die letzte Kerze läuft) — fehlt
    /// sie oder ihre Vorgängerin: nil. nil auch bei weniger als `minWindowReturns` gültigen
    /// Vergleichsrenditen. Wie Android.
    static func hourStats(_ candles: [MarketCandle], now: Int64? = nil) -> HourStats? {
        guard let newest = candles.last else { return nil }
        var byOpen: [Int64: MarketCandle] = [:]
        for c in candles { byOpen[c.openTime] = c }
        // Wie viele Stunden die neueste Kerze vor der laufenden liegt (Uhr nachgehend: 0)
        let behind: Int64 = now.map { max(0, ($0 - newest.openTime) / hourMillis) } ?? 0
        let moveOpen = newest.openTime + (behind - 1) * hourMillis

        func ret(_ open: Int64) -> Double? {
            guard let prev = byOpen[open - hourMillis]?.close, let cur = byOpen[open]?.close,
                  prev > 0, cur > 0 else { return nil }
            let r = (cur / prev - 1) * 100
            return r.isFinite ? r : nil
        }

        guard let move = ret(moveOpen), let moveCandle = byOpen[moveOpen] else { return nil }
        // Vergleichsstunden aufsteigend (feste Summenreihenfolge)
        let windowOpens = (1...windowHours).reversed().map { moveOpen - Int64($0) * hourMillis }
        let window = windowOpens.compactMap { ret($0) }
        guard window.count >= minWindowReturns else { return nil }
        let mean = window.reduce(0, +) / Double(window.count)
        let variance = window.reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / Double(window.count - 1)
        let sigma = max(variance.squareRoot(), minSigmaPercent)

        // Volumen: Umsatz in der Quote, wenn jede Kerze ihn hat (Binance), sonst die gehandelte
        // Menge in Coins (Coinbase u. a.) — dann nur genähert. Fehlende Stunden zählen als Stunden
        // ohne Handel. Zu viele davon: kein Verhältnis (ein Schnitt über leere Stunden wäre bedeutungslos).
        let windowCandles = windowOpens.compactMap { byOpen[$0] }
        let quote = (windowCandles + [moveCandle]).allSatisfy { c in
            guard let q = c.quoteVolume else { return false }
            return q.isFinite && q >= 0
        }
        func vol(_ c: MarketCandle) -> Double { quote ? (c.quoteVolume ?? 0) : c.volume }
        let emptyHours = windowHours - windowCandles.count + windowCandles.filter { !(vol($0) > 0) }.count
        let avgVolume = windowCandles.reduce(0.0) { $0 + vol($1) } / Double(windowHours)
        var ratio: Double? = avgVolume > 0 && emptyHours <= maxEmptyVolumeHours ? vol(moveCandle) / avgVolume : nil
        if let r = ratio, !r.isFinite { ratio = nil }

        return HourStats(
            movePercent: move,
            zScore: (move - mean) / sigma,
            volumeRatio: ratio,
            candleOpenTime: moveOpen,
            volumeApproximate: !quote
        )
    }

    /// Kurs zu einem Zeitpunkt, innerhalb einer abgeschlossenen Kerze linear
    /// zwischen Eröffnung und Schluss geschätzt. Ab der laufenden Kerze: letzter Kurs.
    static func priceAt(_ candles: [MarketCandle], _ time: Int64) -> Double? {
        guard let firstCandle = candles.first, let lastCandle = candles.last, time >= firstCandle.openTime else { return nil }
        if time >= lastCandle.openTime { return lastCandle.close }
        guard let candle = candles.last(where: { $0.openTime <= time }) else { return nil }
        let fraction = min(max(Double(time - candle.openTime) / Double(hourMillis), 0), 1)
        return candle.open + (candle.close - candle.open) * fraction
    }

    /// Veränderung des letzten Kurses gegenüber vor `hours` Stunden, in %.
    static func changeOver(_ candles: [MarketCandle]?, hours: Int, now: Int64) -> Double? {
        guard let candles, let current = candles.last?.close else { return nil }
        guard let past = priceAt(candles, now - Int64(hours) * hourMillis) else { return nil }
        return past > 0 && current > 0 ? (current / past - 1) * 100 : nil
    }

    // MARK: Signale

    /// Alle Signale eines Paars, stärkstes zuerst. Die Schwellen kommen aus der
    /// `sensitivity` (Standard = `.NORMAL`) — für Karte und Meldungen gleich.
    /// `fundingSource` = Quelle der Funding Rate (stündliches Funding zählt achtfach, `fundingPer8h`).
    static func signals(stats: HourStats?, fundingPercent: Double?, oiChangePercent: Double?,
                        oiMinutes: Int?, now: Int64,
                        sensitivity: ActivitySensitivity = .NORMAL,
                        fundingSource: String? = nil,
                        fundingIntervalHours reportedInterval: Int? = nil) -> [ActivitySignal] {
        // Kandidaten mit allen Werten; ob und wie stark sie zählen, entscheiden die Schwellen
        var candidates: [ActivitySignal] = []
        if let stats {
            candidates.append(ActivitySignal(
                kind: .PRICE_MOVE,
                severity: .NOTABLE,
                value: stats.movePercent,
                factor: abs(stats.zScore),
                seenAt: now
            ))
            if let ratio = stats.volumeRatio {
                candidates.append(ActivitySignal(kind: .VOLUME_SPIKE, severity: .NOTABLE, value: ratio, seenAt: now))
            }
        }
        if let oi = oiChangePercent {
            candidates.append(ActivitySignal(
                kind: .OPEN_INTEREST_JUMP,
                severity: .NOTABLE,
                value: oi,
                factor: oiMinutes.map { Double($0) },
                seenAt: now
            ))
        }
        if let funding = fundingPercent {
            // Wert der Börse (Anzeige), Intervall für den Vergleich auf 8 Stunden
            let interval = fundingIntervalHours(fundingSource, reported: reportedInterval).map { Double($0) }
            candidates.append(ActivitySignal(kind: .FUNDING_EXTREME, severity: .NOTABLE, value: funding,
                                             factor: interval, seenAt: now))
        }
        return applySensitivity(candidates, sensitivity)
    }

    /// Beurteilt Signale neu nach der `sensitivity`: was unter den Schwellen liegt, fällt
    /// weg, die Stärke wird neu bestimmt. So wirkt eine geänderte Einstellung sofort auch
    /// auf gespeicherte Signale (Karte, ⚡ an den Zeilen); stärkstes zuerst.
    static func applySensitivity(_ signals: [ActivitySignal], _ sensitivity: ActivitySensitivity) -> [ActivitySignal] {
        let thresholds = SignalThresholds.of(sensitivity)
        return sortedSignals(signals.compactMap { signal in
            thresholds.severity(of: signal).map { severity in
                ActivitySignal(kind: signal.kind, severity: severity, value: signal.value,
                               factor: signal.factor, seenAt: signal.seenAt)
            }
        })
    }

    /// Neuer Bericht plus die Arten, die vorher nicht aktiv waren (für die Meldung).
    struct Merge: Sendable {
        let report: ActivityReport
        let newKinds: Set<ActivitySignalKind>
    }

    /// Frische Signale ersetzen gleichartige alte; alte, die nicht mehr erkannt
    /// werden, bleiben bis 1 Std. nach dem letzten Erkennen stehen.
    static func merge(previous: ActivityReport?, fresh: [ActivitySignal], now: Int64,
                      sensitivity: ActivitySensitivity = .NORMAL) -> Merge {
        // Alte Signale nach der aktuellen Empfindlichkeit: nach «Weniger» zählt nur noch, was dort auffällt
        let before = applySensitivity(previous?.active(now: now) ?? [], sensitivity)
        let freshKinds = Set(fresh.map(\.kind))
        let kept = before.filter { !freshKinds.contains($0.kind) }
        let renewed = fresh.map { s -> ActivitySignal in
            var copy = s
            copy.seenAt = now
            return copy
        }
        return Merge(
            report: ActivityReport(signals: sortedSignals(renewed + kept), computedAt: now),
            newKinds: freshKinds.subtracting(before.map(\.kind))
        )
    }

    /// Melden, wenn etwas Neues dazukam und die letzte Meldung über 60 Min. her ist.
    static func shouldNotify(enabled: Bool, newKinds: Set<ActivitySignalKind>, lastNotifiedAt: Int64, now: Int64) -> Bool {
        enabled && !newKinds.isEmpty && now - lastNotifiedAt >= notifyIntervalMillis
    }

    /// Arten für die Meldung: neue plus die zurückgehaltenen `pending` (Mitteilung kam zuletzt nicht an),
    /// soweit sie im Bericht noch aktiv sind — wie Android.
    static func noticeKinds(newKinds: Set<ActivitySignalKind>, pending: Set<ActivitySignalKind>,
                            report: ActivityReport, now: Int64) -> Set<ActivitySignalKind> {
        guard !pending.isEmpty else { return newKinds }
        let active = Set(report.active(now: now).map(\.kind))
        return newKinds.union(pending.intersection(active))
    }

    /// Zurückzuhaltende Arten nach der Meldung — gleiche Regel wie bei den Kursalarmen
    /// (`QuietHours.mayFire`): Fällige Meldung (`notify`) nicht zustellbar bzw. nicht angenommen
    /// (`posted` = false) → `kinds` bleiben offen und nichts gilt als gemeldet; sonst nichts offen.
    static func pendingAfter(kinds: Set<ActivitySignalKind>, notify: Bool, posted: Bool) -> Set<ActivitySignalKind> {
        (notify && !posted) ? kinds : []
    }

    /// Ist die letzte Prüfung älter als 10 Min. (oder gibt es keine)?
    static func isDue(_ report: ActivityReport?, now: Int64) -> Bool {
        guard let report else { return true }
        let age = now - report.computedAt
        return !(age >= 0 && age < analysisIntervalMillis)
    }

    // MARK: Gründe

    static func fearGreedLevel(_ value: Int) -> FearGreedLevel {
        if value < 25 { return .extremeFear }
        if value < 45 { return .fear }
        if value <= 55 { return .neutral }
        if value <= 75 { return .greed }
        return .extremeGreed
    }
}
