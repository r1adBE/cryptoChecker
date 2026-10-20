import Foundation

// Übersetzte Texte der Steuer-Exporte aus den App-Texten (`L`).

extension TaxCsvTexts {
    /// Zusätze der Steuer-Vorlagen (Zuordnung UK, nicht abziehbarer Verlust CA, Alt-/Neubestand AT, Gesamtwert FR) —
    /// wie `taxCsvTexts` (Android). `template` AT: Einkünfte getrennt nach Zins und Staking (Vermerke, zwei Summen).
    func withTemplateTexts(currency: String, template: TaxTemplate = .NEUTRAL) -> TaxCsvTexts {
        var t = self
        t.matchSameDay = L("tax_export_match_same_day")
        t.matchThirtyDay = L("tax_export_match_thirty_day")
        t.matchPool = L("tax_export_match_pool")
        t.adjustmentUsdt = L("tax_export_col_adjustment", "USDT")
        t.adjustmentTarget = L("tax_export_col_adjustment", currency)
        t.adjustment = L("tax_export_adjustment")
        t.oldHolding = L("tax_export_old_holding")
        t.newHolding = L("tax_export_new_holding")
        t.costIncomplete = L("tax_export_cost_incomplete")
        t.portfolioValueUsdt = L("tax_export_col_portfolio_value", "USDT")
        t.portfolioValueTarget = L("tax_export_col_portfolio_value", currency)
        t.grossUsdt = L("tax_export_col_gross", "USDT")
        t.grossTarget = L("tax_export_col_gross", currency)
        // AT: Zinsen beim Zufluss steuerpflichtig, Staking (Neubestand) nur Information — getrennt wie die Jahresübersicht
        if template == .AT {
            t.incomeInterest = L("tax_export_at_income_interest")
            t.incomeStaking = L("tax_export_at_income_staking")
            t.totalInterest = L("tax_export_at_total_interest")
            t.totalStaking = L("tax_export_at_total_staking")
            // Zufluss vor dem 1.3.2022 (vor § 27b EStG) — eigener Vermerk statt Zins/Staking
            t.incomeBeforeNewLaw = L("tax_export_at_income_before_new_law")
        }
        return t
    }
}

extension TaxLine {
    /// Text-Schlüssel des Postens — wie `taxLineLabel` (Android).
    var labelKey: String {
        switch self {
        case .SHORT_GAINS: return "tax_de_short_gains"
        case .SHORT_LOSSES: return "tax_de_short_losses"
        case .SHORT_NET: return "tax_line_short_net"
        case .LONG_GAINS: return "tax_de_long_gains"
        case .LONG_LOSSES: return "tax_de_long_losses"
        case .LONG_NET: return "tax_line_long_net"
        case .UNCLASSIFIED: return "tax_de_unclassified"
        case .GAINS: return "tax_line_gains"
        case .LOSSES: return "tax_line_losses"
        case .NET: return "tax_line_net"
        case .NEW_NET: return "tax_line_new_net"
        case .OLD_SHORT_NET: return "tax_line_old_short_net"
        case .OLD_LONG_NET: return "tax_line_old_long_net"
        case .DENIED_LOSS: return "tax_line_denied_loss"
        case .PROCEEDS: return "tax_line_proceeds"
        case .INCOME: return "tax_line_income"
        case .AT_INTEREST: return "tax_line_at_interest"
        case .AT_INTEREST_OLD: return "tax_line_at_interest_old"
        case .FR_OUTFLOW: return "tax_line_fr_outflow"
        case .WITHDRAWAL_FEES: return "tax_line_withdrawal_fees"
        case .TRANSFER_FEES: return "tax_line_transfer_fees"
        case .CH_WEALTH_VALUE: return "tax_line_ch_wealth_value"
        case .DE_LONG: return "tax_line_de_long"
        case .DE_PRIVATE_LIMIT: return "tax_de_private_limit"
        case .DE_INCOME_LIMIT: return "tax_de_income_limit"
        case .AT_RATE: return "tax_line_at_rate"
        case .AT_OLD_LONG: return "tax_line_at_old_long"
        case .AT_OLD_SPECULATION_LIMIT: return "tax_line_at_old_speculation_limit"
        case .CH_PRIVATE: return "tax_line_ch_private"
        case .CH_INCOME: return "tax_line_ch_income"
        case .CH_WEALTH: return "tax_line_ch_wealth"
        case .UK_EXEMPT: return "tax_line_uk_exempt"
        case .UK_AFTER_EXEMPT: return "tax_line_uk_after_exempt"
        case .US_TERMS: return "tax_line_us_terms"
        case .CA_TAXABLE: return "tax_line_ca_taxable"
        case .AU_DISCOUNTED: return "tax_line_au_discounted"
        case .NET_CAPITAL_LOSS: return "tax_line_net_capital_loss"
        case .FR_THRESHOLD: return "tax_line_fr_threshold"
        case .FR_RATE: return "tax_line_fr_rate"
        case .JP_MISC: return "tax_line_jp_misc"
        }
    }
}

extension TaxMethod {
    /// Name der Methode — wie `taxMethodName` (Android).
    var label: String {
        switch self {
        case .FIFO: return "FIFO"
        case .FIFO_WALLET: return L("settings_cost_method_fifo_wallet")
        case .AVERAGE: return L("settings_cost_method_average")
        case .UK_POOL: return L("tax_method_uk_pool")
        case .CA_ACB: return L("tax_method_ca_acb")
        case .FR_PTA: return L("tax_method_fr_pta")
        case .JP_TOTAL_AVERAGE: return L("tax_method_jp_total")
        case .AT_MOVING_AVERAGE: return L("tax_method_at")
        }
    }
}

extension TaxSummaryTexts {
    /// Aus den App-Texten für Vorlage, Steuerjahr und Währung — wie `taxSummaryTexts` (Android).
    /// `year` = Steuerjahr (Link der ESTV-Kursliste beim Vermerk «nicht gerechnet»; nil = ohne Vermerk).
    static func localized(template: TaxTemplate, yearLabel: String, currency: String,
                          year: Int? = nil) -> TaxSummaryTexts {
        var labels: [TaxLine: String] = [:]
        // CH Vermögenswert: «Vermögenswert 31.12.<Jahr> (Orientierung)» mit dem Steuerjahr
        for l in TaxLine.allCases { labels[l] = l == .CH_WEALTH_VALUE ? L(l.labelKey, yearLabel) : L(l.labelKey) }
        return TaxSummaryTexts(
            title: L("tax_summary_title", L(template.labelKey), yearLabel),
            disclaimer: L("tax_de_disclaimer"),
            item: L("tax_de_col_item"),
            usdt: "USDT",
            target: currency,
            note: L("portfolio_export_col_note"),
            orientation: L("tax_summary_orientation"),
            labels: labels,
            below: L("tax_de_below"),
            reached: L("tax_de_reached"),
            unknown: L("tax_de_unknown"),
            incomplete: L("portfolio_export_incomplete"),
            noFx: L("portfolio_export_no_fx"),
            orientationHeading: L("tax_summary_orientation_block"),
            orientationItem: L("tax_summary_orientation_item", "%s"),
            // CH Vermögenssteuer: kein Betrag, sondern Link auf die ESTV-Kursliste des Steuerjahrs
            notCalculated: year.map { y -> String in
                L("tax_summary_not_calculated_estv",
                  EstvKursliste.listUrl(year: y, language: Bundle.main.preferredLocalizations.first ?? "en"))
            } ?? "",
            loss: L("tax_de_loss"),
            notes: [
                .AT_REWARD_INFO: L("tax_summary_at_reward_info"),
                .CH_EXCHANGE_VALUE: L("tax_summary_ch_year_end_value"),
                .CH_FIFO_INFO: L("tax_summary_ch_fifo_info"),
                .CH_WEALTH_ORIENTATION: L("tax_summary_ch_wealth_orientation"),
                .DE_WITHDRAWAL_FEE: L("tax_summary_de_withdrawal_fee"),
                .AT_WITHDRAWAL_FEE: L("tax_summary_at_withdrawal_fee"),
                .WITHDRAWAL_FEE_INFO: L("tax_summary_withdrawal_fee_info"),
                .TRANSFER_FEE_INFO: L("tax_summary_transfer_fee_info"),
                .AT_INTEREST_INCOME: L("tax_summary_at_interest_income"),
                .AT_INTEREST_OLD_LAW: L("tax_summary_at_interest_old_law"),
                .AT_TRANSITION_2021: L("tax_summary_at_transition_2021"),
                .AT_WITHOUT_OPTION: L("tax_summary_at_without_option"),
                .AT_STAKING_ZERO_COST: L("tax_summary_at_staking_zero_cost"),
                // Datum setzt die Übersicht ein (`TaxSummaryLine.noteText`): hier nur der Platzhalter
                .WINDOW_OPEN: L("tax_summary_window_open", TaxExport.noteDateToken),
                .UK_HMRC_REPORT: L("tax_summary_uk_hmrc_report"),
                .FR_ADDRESS_NO_COST: L("tax_summary_fr_address_no_cost"),
            ],
            // Coin und Menge setzt die Übersicht ein (`wealthCoinLabel`): hier nur die Platzhalter
            wealthCoin: L("tax_ch_wealth_coin", TaxExport.wealthCoinToken, TaxExport.wealthAmountToken),
            wealthMissing: L("tax_ch_wealth_not_calculated"),
            wealthNoPrice: L("portfolio_export_no_price"),
            wealthUnchecked: L("portfolio_export_unchecked"),
            wealthAddressUnobserved: L("portfolio_export_address_unobserved"),
            // USDT = 1 an einem Tag ohne USDT/USD-Schluss (z. B. vor dem Fenster der Quellen): eigene Zeile, nie still
            usdtAssumed: L("portfolio_usdt_assumed"),
            addressLots: L("portfolio_export_address_lots_note"),
            estimated: L("tax_export_value_estimated")
        )
    }
}
