import Foundation

/// Rohdaten für die Bitcoin-Kennzahlen. Fehlende Werte (nil) ergeben einfach eine
/// Kennzahl ohne Wert («zu wenig Daten»).
struct CycleInputs: Equatable, Sendable {
    /// Aktueller BTC-Kurs in USD.
    var price: Double
    /// Durchschnitt der letzten 200 Tagesschlusskurse.
    var sma200d: Double?
    /// Durchschnitt der letzten 111 bzw. 350 Tagesschlusskurse (nur noch im Zwischenspeicher, nicht angezeigt).
    var sma111d: Double?
    var sma350d: Double?
    /// Schlusskurs vor 30 Tagen.
    var price30dAgo: Double?
    /// Durchschnitt der letzten 200 Wochenschlusskurse.
    var sma200w: Double?
    /// Allzeithoch und wann es war.
    var ath: Double?
    var athDate: LocalDay?
    /// On-Chain (Coin Metrics): Marktwert / realisierter Wert.
    var mvrv: Double?
    /// Mining-Einnahmen heute geteilt durch ihren 365-Tage-Schnitt.
    var puell: Double?
    /// Hashrate: 30- und 60-Tage-Schnitt.
    var hash30d: Double?
    var hash60d: Double?
    var today: LocalDay = .today()
    /// Einordnung in den eigenen Verlauf seit 2016 (`IndicatorHistoryMath`); nil = Verlauf nicht geladen.
    var history: IndicatorHistory? = nil
}

/// Neutrale Kennzahl: nur der Messwert, ohne Wertung, Punkte oder Schwellen — wie `IndicatorId` (Android).
enum IndicatorId: String, CaseIterable, Sendable, Codable {
    /// Kurs geteilt durch den 200-Tage-Schnitt (Faktor).
    case MAYER
    /// 200-Wochen-Schnitt (in der Kurswährung).
    case MA200W
    /// Abstand zum bisherigen Allzeithoch in Prozent (≥ 0 = so weit darunter).
    case ATH_DISTANCE
    /// Kursveränderung in 30 Tagen in Prozent.
    case CHANGE_30D
    /// 50-Tage-Schnitt gegenüber dem 200-Tage-Schnitt in Prozent.
    case SMA50_VS_200
    /// RSI der Wochen- bzw. Tageskerzen (0–100).
    case RSI_WEEKLY, RSI_DAILY
    /// Kursveränderung gegenüber Bitcoin in 90 Tagen in Prozent.
    case VS_BTC
    /// On-Chain: Marktwert geteilt durch den realisierten Wert (Faktor).
    case MVRV
    /// On-Chain: Miner-Einnahmen geteilt durch ihren 365-Tage-Schnitt (Faktor).
    case PUELL
    /// On-Chain: 30-Tage-Schnitt der Hashrate gegenüber dem 60-Tage-Schnitt in Prozent.
    case HASHRATE_30_60
    /// 111-Tage-Schnitt gegenüber dem Doppelten des 350-Tage-Schnitts in Prozent (Abstand der Linien des
    /// «Pi Cycle»; gemessen, ohne Signal).
    case PI_CYCLE_GAP
}

/// Einordnung einer Kennzahl in ihren eigenen Verlauf — reine Rechnung, keine Prognose — wie Android:
/// `percentileBelow` = an so viel Prozent der Tage seit `IndicatorHistory.since` lag der Wert tiefer;
/// `flat30` = Wert in 30 Tagen, falls der Kurs bis dahin genau gleich bleibt.
struct IndicatorStat: Equatable, Sendable, Codable {
    let percentileBelow: Double?
    let flat30: Double?
}

/// Einordnungen je Kennzahl; `since` = erster Tag mit Wert im Verlauf.
struct IndicatorHistory: Equatable, Sendable {
    let since: LocalDay
    let stats: [IndicatorId: IndicatorStat]
}

/// Eine Kennzahl; `value` nil heisst: zu wenig Daten bzw. nicht verfügbar. `stat` = Einordnung in den
/// Verlauf; nil = ohne Verlauf (auch in älteren gespeicherten Berichten).
struct Indicator: Equatable, Sendable, Codable {
    let id: IndicatorId
    let value: Double?
    var stat: IndicatorStat? = nil
}

/// Bitcoin-Kennzahlen in fester Reihenfolge, dazu ob On-Chain-Daten (Coin Metrics) vorlagen.
struct BitcoinReport: Equatable, Sendable, Codable {
    let price: Double
    let indicators: [Indicator]
    let onChainAvailable: Bool
    /// Erster Tag des Verlaufs der Einordnung; nil = ohne Verlauf.
    var historySince: LocalDay? = nil
}

/// Bitcoin-Kennzahlen als reine Messwerte — bewusst ohne Zonen, Scores, Phasen oder
/// Deutung («überhitzt», «günstig» usw.). Jede Zahl wird mit einem Satz erklärt, was sie
/// misst; die App zieht daraus keinen Schluss. Wie `BitcoinIndicatorModel.kt`.
enum BitcoinIndicatorModel {

    static func evaluate(_ input: CycleInputs) -> BitcoinReport {
        let price = input.price
        let mvrv = input.mvrv.flatMap { $0.isFinite ? $0 : nil }
        let puell = input.puell.flatMap { $0.isFinite ? $0 : nil }
        let stats = input.history?.stats ?? [:]
        func ind(_ id: IndicatorId, _ value: Double?) -> Indicator {
            Indicator(id: id, value: value, stat: value == nil ? nil : stats[id])
        }
        let indicators = [
            ind(.MAYER, IndicatorMath.ratio(price, input.sma200d)),
            ind(.MA200W, input.sma200w.flatMap { $0 > 0 ? $0 : nil }),
            ind(.ATH_DISTANCE, IndicatorMath.athDistance(price, input.ath)),
            ind(.CHANGE_30D, IndicatorMath.changePercent(price, input.price30dAgo)),
            ind(.PI_CYCLE_GAP, IndicatorMath.piGap(input.sma111d, input.sma350d)),
            ind(.MVRV, mvrv),
            ind(.PUELL, puell),
            ind(.HASHRATE_30_60, IndicatorMath.changePercent(input.hash30d, input.hash60d)),
        ]
        return BitcoinReport(price: price, indicators: indicators, onChainAvailable: mvrv != nil || puell != nil,
                             historySince: input.history?.since)
    }
}

/// Gemeinsame Rechnungen von Bitcoin- und Coin-Kennzahlen.
enum IndicatorMath {
    /// `value` / `base`; nil ohne gültige Basis.
    static func ratio(_ value: Double?, _ base: Double?) -> Double? {
        guard let value, let base, base > 0, value.isFinite else { return nil }
        return value / base
    }

    /// Veränderung von `base` zu `value` in Prozent; nil ohne gültige Werte.
    static func changePercent(_ value: Double?, _ base: Double?) -> Double? {
        ratio(value, base).map { ($0 - 1.0) * 100.0 }
    }

    /// Abstand unter dem Allzeithoch in Prozent (0, wenn der Kurs darüber liegt).
    static func athDistance(_ price: Double, _ ath: Double?) -> Double? {
        ratio(price, ath).map { max(0.0, (1.0 - $0) * 100.0) }
    }

    /// 111-Tage-Schnitt gegenüber dem Doppelten des 350-Tage-Schnitts in Prozent.
    static func piGap(_ sma111: Double?, _ sma350: Double?) -> Double? {
        changePercent(sma111, sma350.map { $0 * 2 })
    }
}
