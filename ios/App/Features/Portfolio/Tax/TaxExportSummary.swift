import Foundation

// Jahresübersicht je Steuer-Vorlage (`TaxExport.yearSummary`) — wie `TaxExportSummary.kt` (Android). Modell in
// TaxSummaryModel.swift, Texte in TaxSummaryTexts.swift, Hilfen (`Acc`, `atEarlyRewards`) in
// TaxExport+SummaryHelpers.swift.

extension TaxExport {
    /// Jahresübersicht der Vorlage `template` für das Steuerjahr ab `startYear` — ORIENTIERUNG, KEINE STEUERBERATUNG;
    /// Posten und Regeln wie `yearSummary` (Android). `run`: dieselben Veräusserungen schon gerechnet — nur mit genau
    /// diesen Eingaben (`trades`, `method`, Steuerjahr, `timeZone`, `priceAt`, `template.eventRules`, `walletFrom`); Debug
    /// prüft das per `assert` (`GainsRun.matches`), `trades` und `priceAt` nicht (Closure): dafür bürgt der Aufrufer.
    static func yearSummary(_ trades: [PortfolioTx], template: TaxTemplate, method: TaxMethod, startYear: Int,
                            timeZone: TimeZone, currency: String, rates: [Int: Decimal]?,
                            priceAt: ((String, Int64) -> Decimal?)? = nil,
                            walletFrom: LocalDay? = nil, usdtUsd: [Int: Decimal]? = nil,
                            today: LocalDay? = nil, yearEndCloses: CutoffCloses? = nil,
                            yearEndAddresses: AddressCutoffView? = nil, run: GainsRun? = nil) -> TaxYearSummary {
        assert(run?.matches(method: method, period: template.period(startYear), timeZone: timeZone,
                            rules: template.eventRules, walletFrom: walletFrom) ?? true,
               "yearSummary: GainsRun aus anderen Eingaben")
        var builder = TaxYearSummaryBuilder(
            trades, template: template, method: method, startYear: startYear, timeZone: timeZone, currency: currency,
            rates: rates, priceAt: priceAt, walletFrom: walletFrom, usdtUsd: usdtUsd, today: today,
            yearEndCloses: yearEndCloses, yearEndAddresses: yearEndAddresses, run: run)
        return builder.build()
    }
}
