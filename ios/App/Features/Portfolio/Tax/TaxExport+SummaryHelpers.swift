import Foundation

// Hilfen der Jahresübersicht (`TaxExport.yearSummary` in TaxExportSummary.swift): Summen je Posten und der
// AT-Vermerk zu Belohnungen. Eigene Datei, damit die Jahresübersicht selbst unter der Längengrenze bleibt.

extension TaxExport {
    /// Summe eines Teils der Zeilen: USDT, Steuerwährung, Anzahl ohne Gewinn, Anzahl mit Gewinn aus unvollständigem
    /// Einstand (FR: Erwerbspreis-Summe nicht gesichert, `TaxGainRow.costIncomplete`), Anzahl ohne Devisenkurs.
    struct Acc {
        var usdt: Decimal = 0
        var target: Decimal = 0
        var unknown = 0
        var partial = 0
        var fxMissing = 0

        init(_ rows: [TaxGainRow], _ pick: (Decimal) -> Bool, fx: Bool) {
            for r in rows {
                guard let g = r.gainUsdt else { unknown += 1; continue }
                // Gewinn oder Verlust entscheidet die Steuerwährung (ein USDT-Verlust kann in CAD/EUR ein Gewinn sein);
                // ohne Kurs USDT
                let gT = r.gainTarget
                let key: Decimal = fx ? (gT ?? g) : g
                guard pick(key) else { continue }
                usdt += g
                // Gewinn aus unvollständigem Einstand: gezählt, aber der Posten ist «unvollständig»
                if r.costIncomplete { partial += 1 }
                if let gT { target += gT } else if fx { fxMissing += 1 }
            }
        }

        /// Ein Gewinn fehlt oder beruht auf unvollständigem Einstand.
        var incomplete: Bool { unknown > 0 || partial > 0 }

        func line(_ l: TaxLine, fx: Bool, countUnknown: Bool = true, note: TaxSummaryNote? = nil) -> TaxSummaryLine {
            TaxSummaryLine(line: l, usdt: usdt, target: fx ? target : nil,
                           incomplete: (countUnknown && unknown > 0) || partial > 0, noFx: fxMissing > 0, note: note)
        }
    }

    /// AT: Belohnungen (ohne Zinsen) mit Erwerb vom 1.3.2021 bis 28.2.2022 bis zum Ende von `period` — mit
    /// Anschaffungskosten 0 im Neubestand (`.AT_STAKING_ZERO_COST`). Wie Android `atEarlyRewards`.
    static func atEarlyRewards(_ trades: [PortfolioTx], timeZone: TimeZone, period: TaxPeriod) -> Bool {
        trades.contains { t in
            guard t.type == .REWARD, !t.interest, t.amountExact > 0 else { return false }
            let d = localDay(t.acquisitionTime, timeZone: timeZone)
            return !(d < TaxTemplate.atNewFrom) && d < TaxTemplate.atSwapNeutralFrom && !(period.end < d)
        }
    }

    /// FR: Belohnungen mit Erwerb bis zum Ende von `period` — sie stecken zum Wert beim Erhalt in der Erwerbspreis-Summe
    /// (`.FR_REWARDS_INCOME`). Wie Android `frRewards`.
    static func frRewards(_ trades: [PortfolioTx], timeZone: TimeZone, period: TaxPeriod) -> Bool {
        trades.contains { t in
            t.type == .REWARD && t.amountExact > 0 && !(period.end < localDay(t.acquisitionTime, timeZone: timeZone))
        }
    }

    /// FR: Kaufdaten der Herkunft (`TaxOutflow.ptaReductionParts`) der Abgänge ohne Verkauf im Jahr `period` — die Kürzung
    /// wird zu ihren Kursen umgerechnet, also braucht die Devisenspanne (`fxSpan`) sie auch in einem Jahr ohne Abtretung.
    /// Andere Methoden: leer. Wie Android.
    static func frOutflowTimes(_ trades: [PortfolioTx], method: TaxMethod, period: TaxPeriod, timeZone: TimeZone,
                               priceAt: ((String, Int64) -> Decimal?)?) -> [Int64] {
        guard method == .FR_PTA else { return [] }
        return TaxEngines.outflows(trades.filter { $0.amountExact > 0 }, method: method, priceAt: priceAt)
            .filter { period.contains(localDay($0.time, timeZone: timeZone)) }
            .flatMap { $0.ptaReductionParts.map(\.time) }
    }
}
