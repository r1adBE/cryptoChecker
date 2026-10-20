import Foundation

// Ansichten «Übersicht» und «Liste» des Portfolio-Widgets — Spiegel von `PortfolioWidgetViews.kt` (Android).

/// Kennzahl der Übersicht — wie die drei Zahlen der Gesamtkarte im Portfolio-Tab.
enum PortfolioFigureKind: Sendable, CaseIterable {
    case today, week, total
}

/// Eine Kennzahl: Betrag in der Anzeige-Einheit und Prozent; nil = unbekannt («—»). `unit` = abweichende Einheit des
/// Betrags («USDT» für «7 T» ohne Tageskurse der Anzeigewährung); nil = die des Widgets. `incomplete` = ein Coin fehlt
/// (Wert mit «*», VoiceOver «unvollständig»). Wie Android.
struct PortfolioFigure: Equatable, Sendable {
    let kind: PortfolioFigureKind
    let amount: Double?
    let percent: Double?
    var unit: String? = nil
    var incomplete = false
}

/// Reine Regeln der Ansichten «Übersicht» und «Liste» — wie `PortfolioWidgetViews` (Android, dort
/// getestet in PortfolioWidgetViewsTest; hier in PortfolioWidgetChartTests).
enum PortfolioWidgetViews {
    /// Höchstens so viele Zeilen ohne bekannte Höhe.
    static let unknownRows = 3

    /// Heute · 7 T · Gesamt aus dem gezeigten Stand (`PortfolioWidgetChart.displayed`): «Heute» ist die
    /// Veränderung des Stands (schon in der Anzeige-Einheit). In USDT («Nur USDT», ohne Devisenkurs) «7 T» und «Gesamt»
    /// (± seit Kauf) aus den USDT-Werten. In einer Anzeigewährung wie die Gesamtkarte, nie USDT × heutiger Kurs (der
    /// Einstand gilt zum Kurs der Erwerbstage): «Gesamt» = `unrealizedDisplay`, ohne ihn «—»; «7 T» = `weekDisplay`,
    /// ohne ihn die USDT-Zahl mit Einheit «USDT». Wie Android.
    static func figures(_ s: PortfolioWidgetSnapshot) -> [PortfolioFigure] {
        func valid(_ value: Double?) -> Double? { value.flatMap { $0.isFinite ? $0 : nil } }
        let today = PortfolioFigure(kind: .today, amount: valid(s.changeAmount),
                                    percent: s.changeAmount == nil ? nil : valid(s.changePercent))
        let weekUsdt = s.periodChanges?[String(PortfolioWidgetRange.week.days)]
        if s.currency == "USDT" {
            let total = s.unrealized
            return [
                today,
                PortfolioFigure(kind: .week, amount: valid(weekUsdt?.amount),
                                percent: weekUsdt == nil ? nil : valid(weekUsdt?.percent)),
                PortfolioFigure(kind: .total, amount: valid(total?.amount),
                                percent: total == nil ? nil : valid(total?.percent)),
            ]
        }
        let week: PortfolioFigure
        if let shown = s.weekDisplay {
            week = PortfolioFigure(kind: .week, amount: valid(shown.amount), percent: valid(shown.percent))
        } else {
            week = PortfolioFigure(kind: .week, amount: valid(weekUsdt?.amount),
                                   percent: weekUsdt == nil ? nil : valid(weekUsdt?.percent),
                                   unit: weekUsdt == nil ? nil : "USDT")
        }
        let total = s.unrealizedDisplay
        return [
            today,
            week,
            PortfolioFigure(kind: .total, amount: valid(total?.amount),
                            percent: total == nil ? nil : valid(total?.percent),
                            incomplete: !(total?.skipped ?? []).isEmpty),
        ]
    }

    /// Coins, die in der sichtbaren «Heute»-Zahl fehlen (kein Vergleichskurs, Kauf ohne Preis —
    /// `PortfolioWidgetSnapshot.todaySkipped`), alphabetisch — für den Hinweis «Heute ohne …» wie die
    /// Gesamtkarte. Leer, wenn das Widget keine «Heute»-Zahl zeigt: Übersicht (`overview`) mit Betrag,
    /// sonst nur beim Zeitraum 24 h (auch als Ersatz für 7 T ohne Tageskurse). Wie Android.
    static func todaySkipped(_ s: PortfolioWidgetSnapshot, plan: PortfolioChartPlan, overview: Bool) -> [String] {
        guard s.changeAmount != nil else { return [] }
        guard overview || (plan.range == .day && plan.changeAmount != nil) else { return [] }
        return Array(Set((s.todaySkipped ?? []).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty })).sorted()
    }

    /// Zeitraum der sichtbaren Tages-Zahl («7 T», «30 T», «1 J») und die Coins, die darin fehlen — für «7 T ohne …» wie
    /// «Gewinn 7 T» in der App. Übersicht: die «7 T»-Zahl; sonst die des gezeigten Tages-Zeitraums. nil = keine solche
    /// Zahl oder kein Coin fehlt. Wie Android `periodSkipped`.
    static func periodSkipped(_ s: PortfolioWidgetSnapshot, plan: PortfolioChartPlan,
                              overview: Bool) -> (range: PortfolioWidgetRange, coins: [String])? {
        let range: PortfolioWidgetRange
        if overview {
            range = .week
        } else if plan.range.daily && plan.changeAmount != nil {
            range = plan.range
        } else {
            return nil
        }
        let coins = Array(Set((s.periodChanges?[String(range.days)]?.skipped ?? [])
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty })).sorted()
        return coins.isEmpty ? nil : (range: range, coins: coins)
    }

    /// Wie die Positionen im Portfolio-Tab (Standard): absteigend nach Wert, gleicher Wert nach Kürzel.
    static func sortedByValue(_ positions: [PortfolioWidgetPosition]) -> [PortfolioWidgetPosition] {
        positions.filter { $0.value.isFinite }
            .sorted { $0.value != $1.value ? $0.value > $1.value : $0.symbol < $1.symbol }
    }

    /// Höhe einer Listenzeile (pt): einzeilig (breit) Logo 20 bzw. 12 pt; zweizeilig Logo 24 bzw.
    /// 12 + 12 pt (`WidgetFont.label`); je 8 pt Abstand — wie Android (`rowHeightDp`, Schriftgrösse 1).
    static func rowHeight(wide: Bool, fontScale: Double = 1) -> Double {
        // In Schritten mit festen Typen (Literale, `max` und `?:` in einem Ausdruck sind für den Type-Checker teuer)
        if wide {
            let text: Double = 12 * 1.3 * fontScale
            let content: Double = Swift.max(20, text)
            return content + 8
        }
        let lines: Double = 12 + 12
        let text: Double = lines * 1.2 * fontScale + 2
        let content: Double = Swift.max(24, text)
        return content + 8
    }

    /// Zeilen und Fusszeile in `available` (pt für Zeilen und Fusszeile; nil = unbekannt): so viele der
    /// `count` Positionen wie ganz passen; die Fusszeile nur, wenn sie keine Zeile kostet, die sonst Platz
    /// hätte. Unbekannt: höchstens `unknownRows` Zeilen mit Fusszeile. Wie Android (`listRows`).
    static func listRows(available: Double?, row: Double, footer: Double, count: Int) -> (rows: Int, footer: Bool) {
        let wanted = Swift.max(0, count)
        guard let available else { return (Swift.min(wanted, unknownRows), true) }
        guard row > 0 else { return (0, true) }
        let without = Swift.min(wanted, Swift.max(0, Int((available / row).rounded(.down))))
        let with = Swift.min(wanted, Swift.max(0, Int(((available - footer) / row).rounded(.down))))
        return with >= without && available >= footer ? (with, true) : (without, false)
    }

    /// Anteil der Total-Zeile: das ganze Portfolio — wie Android `LIST_TOTAL_SHARE`.
    static let listTotalShare: Double = 100

    /// Total-Zeile der Liste, gestaltet wie die Listenzeilen ohne Logo (Build 46: breit einzeilig 12 pt, sonst
    /// zweizeilig 12 + 12 pt; je 8 pt Innenrand) und die Trennlinie darüber (1 pt) — wie Android `listTotalHeightDp`.
    static func listTotalHeight(fontScale: Double = 1, wide: Bool = false) -> Double {
        if wide {
            let line: Double = 12 * 1.3 * fontScale
            return line + 8 + 1
        }
        let lines: Double = 12 + 12
        let text: Double = lines * 1.2 * fontScale
        return text + 2 + 8 + 1
    }

    /// Zeilen der Liste in `height` (pt, ganze Fläche ohne Rand) unter Kopfzeile (`header`) und Abstand (`gap`):
    /// Total-Zeile und «Stand …» (`footer`, samt «Heute ohne …») stehen IMMER da und gehen den Zeilen vor —
    /// nie fällt «Stand …» weg, um eine Zeile mehr zu zeigen (nie negativ) — wie Android `listLayout`.
    static func listRowsBelowTotal(height: Double, header: Double, gap: Double, row: Double, footer: Double,
                                   count: Int, fontScale: Double = 1, wide: Bool = false) -> (rows: Int, footer: Bool) {
        guard row > 0 else { return (0, true) }
        let total: Double = listTotalHeight(fontScale: fontScale, wide: wide)
        let available = Swift.max(0, height - header - gap - total - footer)
        return (Swift.min(Swift.max(0, count), Int((available / row).rounded(.down))), true)
    }

    /// Gewinn/Verlust «Heute» bzw. 24 h des Gesamtwerts (`changeAmount`, schon um Käufe/Verkäufe berichtigt
    /// und nach der %-Basis geprüft) — die Summe der Zeilen (`rowPnl`); Richtung nach dem Betrag. nil ohne
    /// Betrag — dann bleibt die zweite Zeile rechts leer. Wie Android `listTotalChange`.
    static func listTotalChange(_ s: PortfolioWidgetSnapshot) -> PortfolioListTotalChange? {
        guard let amount = s.changeAmount, amount.isFinite else { return nil }
        return PortfolioListTotalChange(amount: amount, percent: s.changePercent.flatMap { $0.isFinite ? $0 : nil },
                                        direction: PortfolioWidgetSeries.direction(amount))
    }

    /// Gewinn/Verlust einer Position (`pnlAmount`/`pnlPercent`); nil = unbekannt (Kurs am Anfang oder Preis
    /// einer Transaktion fehlt, älterer Stand) → «—». Wie Android `rowPnl`.
    static func rowPnl(_ p: PortfolioWidgetPosition) -> PortfolioListTotalChange? {
        guard let amount = p.pnlAmount, amount.isFinite else { return nil }
        return PortfolioListTotalChange(amount: amount, percent: p.pnlPercent.flatMap { $0.isFinite ? $0 : nil },
                                        direction: PortfolioWidgetSeries.direction(amount))
    }

    /// Texte für Gewinn/Verlust, lang → kurz (bei knapper Breite geht der Betrag vor): «+1’250.00 CHF · +1.50%»,
    /// «+1’250.00 CHF». «Beträge verbergen»: Betrag «•••», das Prozent bleibt. Ohne Prozent nur der Betrag.
    /// Wie Android `pnlTexts`.
    static func pnlTexts(_ c: PortfolioListTotalChange, currency: String, hidden: Bool) -> [String] {
        let sign = abs(c.amount) < 0.005 ? "" : (c.amount > 0 ? "+" : "−")
        let amount = PortfolioInsights.mask(BidiText.ltr(sign + PriceFormat.valueWithCurrency(abs(c.amount), currency)),
                                            hidden: hidden)
        guard let percent = c.percent else { return [amount] }
        let p = PriceFormat.changePercent(percent) ?? PriceFormat.zeroPercent()
        return [amount + " · " + p, amount]
    }

    /// Wert einer Kennzahl als Text (keine Pille): Prozent mit Vorzeichen; der Pfeil davor folgt der Richtung
    /// (`ChangeArrowIcon`, bei 0 keiner) — wie Android `PortfolioWidgetViews.figureText`.
    static func figureDirection(_ f: PortfolioFigure) -> Double? {
        guard let percent = f.percent else { return nil }
        return Double(PortfolioWidgetSeries.direction(f.amount ?? percent))
    }

    /// Schriftgrössen der Betragszeile (pt): normal, dann kleiner, bevor ein Teil wegfällt
    /// (wie Android `WidgetTextFit.CHANGE_LINE_SIZES_SP`).
    /// J-18: nicht unter 11 pt — lieber fällt der Zeitraum bzw. Betrag weg.
    static let changeLineSizes: [CGFloat] = [12, 11]

    /// Betragszeile als Text (keine Pille): «−31’997.28 CHF  −27.85% · 1J» (Pfeil davor als Symbol) —
    /// Kandidaten vom längsten zum kürzesten: zuerst fällt der Zeitraum weg, dann der Betrag (das Prozent
    /// bleibt), zuletzt der Zeitraum hinter dem Prozent; ohne Prozent Betrag mit und ohne Zeitraum.
    /// Wie Android `WidgetTextFit.changeLineCandidates`.
    static func changeLineCandidates(amount: String, percent: String?, period: String?) -> [PortfolioChangeLine] {
        let period = (period?.isEmpty ?? true) ? nil : period
        guard let percent else {
            return [PortfolioChangeLine(amount: amount, percent: nil, period: period),
                    PortfolioChangeLine(amount: amount, percent: nil, period: nil)].uniqued()
        }
        return [PortfolioChangeLine(amount: amount, percent: percent, period: period),
                PortfolioChangeLine(amount: amount, percent: percent, period: nil),
                PortfolioChangeLine(amount: nil, percent: percent, period: period),
                PortfolioChangeLine(amount: nil, percent: percent, period: nil)].uniqued()
    }
}

/// MEIN Gewinn/Verlust einer Zeile bzw. des Totals über 24 h / seit Tagesbeginn: Betrag in der Anzeige-Einheit,
/// Prozent (nil = Basis ~0) und Richtung nach dem Betrag (+1 / −1 / 0) — wie Android.
struct PortfolioListTotalChange: Equatable, Sendable {
    let amount: Double
    let percent: Double?
    let direction: Int
}

/// Ein Kandidat der Betragszeile: Betrag und Prozent in der Kursfarbe, Zeitraum grau (nie rot/grün).
struct PortfolioChangeLine: Hashable, Sendable {
    let amount: String?
    let percent: String?
    let period: String?
}

private extension Array where Element == PortfolioChangeLine {
    /// Doppelte Kandidaten (etwa ohne Zeitraum) nur einmal, Reihenfolge bleibt.
    func uniqued() -> [PortfolioChangeLine] {
        var seen = Set<PortfolioChangeLine>()
        return filter { seen.insert($0).inserted }
    }
}
