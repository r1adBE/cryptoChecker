import Foundation

// Typen des Wertverlaufs (`PortfolioHistory.build`) — wie `PortfolioHistoryModels.kt` (Android).

/// Zeitraum des Wertverlaufs im Portfolio-Tab — wie `PortfolioHistoryRange` (Android).
/// `days` = so viele Tage vor heute beginnt er höchstens («Seit 1. Kauf»: 0, kein fester Zeitraum).
enum PortfolioHistoryRange: String, CaseIterable, Identifiable, Codable, Sendable {
    case week
    case month
    case year
    /// «Seit 1. Kauf»: ab der ersten Transaktion, ohne feste Zahl Tage — frühestens ab
    /// `PortfolioHistory.dataStartEpochDay`, dem ersten Tag mit Kursdaten (siehe `PortfolioHistory.startDay`).
    case sinceFirst

    var id: String { rawValue }

    var days: Int {
        switch self {
        case .week: return 7
        case .month: return 30
        case .year: return 365
        case .sinceFirst: return 0
        }
    }
}

/// Ein Tag des Verlaufs: Wert am Tagesende (heute: jetzt). `epochDay` = Tage seit 1970-01-01.
/// `open`, `high` und `low` nur, wenn Tageskerzen mit Eröffnung/Hoch/Tief vorlagen (für die
/// Kerzen-Ansicht, siehe `PortfolioHistory.build` und `PortfolioCandles`); sonst nil.
struct PortfolioHistoryPoint: Equatable, Sendable {
    let epochDay: Int
    let value: Double
    var open: Double? = nil
    var high: Double? = nil
    var low: Double? = nil
}

/// Eröffnung, Hoch und Tief einer UTC-Tageskerze in USDT (der Schluss steht bei den Schlusskursen)
/// — wie `PortfolioDayRange` (Android).
struct PortfolioDayRange: Equatable, Sendable, Codable {
    let open: Double
    let high: Double
    let low: Double

    /// Alle drei Werte endlich und grösser 0.
    var valid: Bool {
        open > 0 && high > 0 && low > 0 && open.isFinite && high.isFinite && low.isFinite
    }
}

/// Fertiger Verlauf eines Zeitraums — wie `PortfolioHistorySeries` (Android).
struct PortfolioHistorySeries: Equatable, Sendable {
    /// Aufsteigend nach Tag; leer, wenn kein Coin einen Verlauf hat.
    let points: [PortfolioHistoryPoint]
    /// Coins mit Bestand im Zeitraum, aber ohne Tageskurse (fehlen im Chart, «ohne …»).
    let skipped: [String]
    /// Letzter minus erster Wert; nil mit weniger als zwei Punkten.
    let change: Double?
    /// Änderung in Prozent des ersten Werts; nil ohne Ausgangswert.
    let changePercent: Double?
    /// Im Zeitraum (nach dem ersten Tag) wurde gekauft oder verkauft.
    let tradesInRange: Bool
    /// «Seit 1. Kauf» reicht nicht bis zum ersten Kauf zurück (Kursdaten erst ab `PortfolioHistory.dataStartEpochDay`).
    var capped: Bool = false
    /// Zuflüsse im Zeitraum (nach dem ersten Tag) der gezeichneten Coins, in der Einheit der Punkte:
    /// Kauf + (Menge × Preis + Gebühr USDT), Verkauf − (Erlös − Gebühr USDT), wie `PortfolioPeriodChange.gain`.
    /// nil = unbekannt (Kauf/Verkauf ohne Preis im Zeitraum).
    var cashFlows: [PortfolioCashFlow]? = []
    /// Gewinn/Verlust: Wert der Coins, die dafür zählen (je Punkt, gleiche Tage wie `points`, mit
    /// Eröffnung/Hoch/Tief wie dort) — ohne `pnlSkipped`. Daraus mit `pnlFlows` die Kurve `pnlPoints`.
    var pnlValues: [PortfolioHistoryPoint] = []
    /// Zuflüsse dieser Coins (`PortfolioLedger.flow`) je lokalem Tag: «Seit 1. Kauf» alle (vor dem ersten
    /// Punkt auf dessen Tag gelegt), sonst die nach dem Ende des ersten Tags.
    var pnlFlows: [PortfolioCashFlow] = []
    /// Coins im Wertverlauf, die im Gewinn/Verlust fehlen (Zufluss unbekannt oder Bestand ohne Kurs).
    var pnlSkipped: [String] = []
    /// «Seit 1. Kauf» oder erster Kauf erst im Zeitraum (junges Portfolio): Gewinn/Verlust ab 0 vor dem ersten Kauf
    /// (Wert − investiert), sonst ab dem ersten Tag.
    var pnlSinceFirst: Bool = false
    /// Im Zeitraum der Gewinn/Verlust-Kurve liegen eigene Auszahlungen ihrer Coins: Die Kurve enthält die Wertänderung
    /// der ausgebuchten Coins, solange sie gehalten wurden (Abfluss zum Marktwert) — die Karte («Seit Kauf»,
    /// «realisiert») nicht. Kopf/Hinweis: «inkl. Wertänderung ausgebuchter Coins». Wie Android.
    var withdrawalsInRange: Bool = false

    /// Genug für eine Linie.
    var hasChart: Bool { points.count >= 2 }

    /// Gewinn/Verlust je Tag in der Einheit der Punkte: Wert(d) − netto investiert(d). Netto investiert =
    /// Σ `pnlFlows` bis Tag d, im Zeitraum zusätzlich der Wert am ersten Tag (die Kurve beginnt dort bei 0);
    /// «Seit 1. Kauf» ab 0 vor dem ersten Kauf. Käufe/Verkäufe verschieben die Kurve also nicht, nur
    /// Kursbewegungen der gehaltenen Coins. Eröffnung/Hoch/Tief um denselben Betrag verschoben. Wie Android.
    var pnlPoints: [PortfolioHistoryPoint] {
        guard let first = pnlValues.first else { return [] }
        let flows = pnlFlows.sorted { $0.epochDay < $1.epochDay }
        var next = 0
        var invested = pnlSinceFirst ? 0 : first.value
        return pnlValues.map { p in
            while next < flows.count && flows[next].epochDay <= p.epochDay {
                invested += flows[next].amount
                next += 1
            }
            return PortfolioHistoryPoint(epochDay: p.epochDay, value: p.value - invested,
                                         open: p.open.map { $0 - invested }, high: p.high.map { $0 - invested },
                                         low: p.low.map { $0 - invested })
        }
    }

    /// Genug Gewinn/Verlust-Punkte für eine Linie.
    var hasPnlChart: Bool { pnlValues.count >= 2 }

    /// Gewinn/Verlust über den Zeitraum (letzter Punkt von `pnlPoints`); nil ohne Kurve.
    var pnlChange: Double? { hasPnlChart ? pnlPoints.last?.value : nil }

    /// Basis des Prozents, wie «Heute» und die Zeilen (`PortfolioPeriodChange`): im Zeitraum Wert am ersten Tag +
    /// Zugänge danach (positive Zuflüsse: Käufe, Belohnungen, Einzahlungen samt Gebühren; Verkäufe NICHT
    /// abgezogen), «Seit 1. Kauf» Σ aller Zugänge (alles eingesetzte Geld). nil ohne Kurve. Wie Android.
    var pnlCapital: Double? {
        guard hasPnlChart, let first = pnlValues.first else { return nil }
        return (pnlSinceFirst ? 0 : first.value) + pnlFlows.filter { $0.amount > 0 }.reduce(0) { $0 + $1.amount }
    }

    /// `pnlChange` in Prozent von `pnlCapital`; nil, wenn diese Basis höchstens ~0 ist — dann nur der Betrag.
    var pnlPercent: Double? {
        guard let change = pnlChange, let base = pnlCapital, base > PortfolioCalculator.eps else { return nil }
        return change / base * 100
    }

    /// Änderung ohne Käufe/Verkäufe (wie der Gesamtwert-Kopf): `change` − Σ `cashFlows`; nil ohne
    /// Verlauf oder wenn ein Zufluss unbekannt ist.
    var netChange: Double? {
        guard let change, let cashFlows else { return nil }
        return change - cashFlows.reduce(0) { $0 + $1.amount }
    }

    /// `netChange` in Prozent von Wert am Anfang + Käufe im Zeitraum (wie «7 T»); nil ohne Basis.
    var netChangePercent: Double? {
        guard let net = netChange, let first = points.first?.value else { return nil }
        let base = first + (cashFlows ?? []).filter { $0.amount > 0 }.reduce(0) { $0 + $1.amount }
        return base > PortfolioCalculator.eps ? net / base * 100 : nil
    }

    static let empty = PortfolioHistorySeries(points: [], skipped: [], change: nil, changePercent: nil, tradesInRange: false)
}

/// Zufluss am (UTC-)Tag `epochDay` (Kauf positiv, Verkauf negativ), in der Einheit des Verlaufs —
/// wie `PortfolioCashFlow` (Android).
struct PortfolioCashFlow: Equatable, Sendable {
    let epochDay: Int
    let amount: Double
}
