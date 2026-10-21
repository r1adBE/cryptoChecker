import Foundation

// Texte der Steuer-Exporte als CSV (`TaxExport.gainsCsv`/`incomeCsv` in TaxExportCsv.swift).

/// Übersetzte Texte der Steuer-Exporte (Spalten mit Währung schon eingesetzt) — wie `TaxCsvTexts` (Android).
struct TaxCsvTexts: Sendable {
    var coin: String
    var acquired: String
    var sold: String
    var holdingDays: String
    /// «> 1 Jahr».
    var longTerm: String
    var amount: String
    var costUsdt: String
    var proceedsUsdt: String
    var gainUsdt: String
    var costTarget: String
    var proceedsTarget: String
    var gainTarget: String
    var date: String
    var valueUsdt: String
    var valueTarget: String
    var note: String
    var total: String
    var yes: String
    var no: String
    var costMissing: String
    var proceedsMissing: String
    var feeLeg: String
    var feeValueMissing: String
    /// «Einstand zu den Kursen der Kaufdaten (Ø)».
    var avgCostRates: String
    var valueMissing: String
    var noFx: String
    var incomplete: String
    /// «mehr verkauft als erfasst» (Teil ohne erfassten Zugang).
    var oversold: String = ""
    /// Summenzeile «Erlös ohne Kaufpreis» (Zeilen mit unbekanntem Einstand).
    var proceedsWithoutCost: String = ""
    /// «Zeitzone: …» schon mit der Zeitzone; leer = keine Zeile.
    var timeZone: String = ""
    /// «Tausch»; leer = kein Vermerk.
    var swap: String = ""
    /// «Wallet: %s» (`.FIFO_WALLET`) mit «%s» für den Namen; leer = kein Vermerk.
    var wallet: String = ""
    /// «ohne Angabe» (Wallet ohne Börse).
    var walletUnspecified: String = ""
    /// «Netzwerkgebühr-Einstand ohne bezahlten Posten» (`PortfolioLostFeeCost`); leer = keine solchen Zeilen.
    var lostFeeCost: String = ""
    /// «Gebühr nicht zugeordnet – Einstand unbekannt» (`PortfolioUnassignedFee`); leer = keine solchen Zeilen.
    var unassignedFee: String = ""
    /// UK: Vermerke der Zuordnung «gleicher Tag», «30 Tage», «Pool (Section 104)».
    var matchSameDay: String = ""
    var matchThirtyDay: String = ""
    var matchPool: String = ""
    /// CA: Spalten «Nicht abziehbarer Verlust USDT/…» (nur wenn es solche gibt) und Vermerk «Superficial Loss».
    var adjustmentUsdt: String = ""
    var adjustmentTarget: String = ""
    var adjustment: String = ""
    /// AT: Vermerke «Altbestand (erworben vor 1.3.2021)» / «Neubestand».
    var oldHolding: String = ""
    var newHolding: String = ""
    /// FR: Vermerk «Erwerbspreis-Summe unvollständig»; Spalten Gesamtwert Portfolio und Abtretungspreis vor Gebühren.
    var costIncomplete: String = ""
    var portfolioValueUsdt: String = ""
    var portfolioValueTarget: String = ""
    var grossUsdt: String = ""
    var grossTarget: String = ""
    /// Vermerke der Gebühr-Aufwände (`PortfolioFeeExpense`): «Gebühr verbraucht den ganzen Zugang», «Gebühr Auszahlung».
    var feeConsumed: String = ""
    var withdrawalFee: String = ""
    /// Vermerk «Gebühr» einer Veräusserung ohne Erlös (`TaxGainRow.feeRow`); leer = kein Vermerk.
    var feeRow: String = ""
    /// Vermerk «Schenkung» (`TaxGainRow.gift`); leer = kein Vermerk.
    var gift: String = ""
    /// Vermerk «Zahlung mit Krypto» (`TaxGainRow.payment`); leer = kein Vermerk.
    var payment: String = ""
    /// Vermerk «Wert geschätzt (Tagesschluss)» (`TaxGainRow.valueEstimated`); leer = kein Vermerk.
    var valueEstimated: String = ""
    /// Vermerk «an Ehe-/Lebenspartner – kein Gewinn/Verlust» (`TaxGainRow.spouseGift`); leer = kein Vermerk.
    var spouseGift: String = ""
    /// Vermerk «Auszahlungsgebühr USDT/Fiat – kein Verlust, nur Information» (`TaxGainRow.feeInfoOnly`); leer = `withdrawalFee`.
    var withdrawalFeeInfo: String = ""
    /// Vermerk «Umbuchungsgebühr USDT/Fiat – kein Verlust, nicht im Einstand, nur Information» (`.TRANSFER`).
    var transferFeeInfo: String = ""
    /// Vermerk «USDT = 1 USD angenommen» (`TaxGainRow.usdtApproximate`, `TaxIncomeRow.usdtApproximate`); leer = kein Vermerk.
    var usdtAssumed: String = ""
    /// AT (nur diese Vorlage füllt sie, `withTemplateTexts`): Einkünfte getrennt wie in der Jahresübersicht
    /// (`TaxIncomeRow.interest`) — Vermerk je Zeile «Zins – steuerpflichtig beim Zufluss» bzw. «Staking – nur zur
    /// Information (Anschaffungskosten 0, besteuert beim Verkauf)» und statt der einen Summe zwei Summenzeilen «Summe Zins
    /// (steuerpflichtig)» / «Summe Staking (Information)». Leer (alle anderen Vorlagen) = eine Summe wie bisher. Wie Android.
    var incomeInterest: String = ""
    var incomeStaking: String = ""
    var totalInterest: String = ""
    var totalStaking: String = ""
    /// AT: Vermerk für Belohnungen (Zins und Staking) mit Zufluss vor dem 1.3.2022 «vor 1.3.2022 – mit Fachperson prüfen»
    /// statt `incomeInterest`/`incomeStaking` — § 27b EStG gilt erst ab dann. Leer = kein eigener Vermerk. Wie Android.
    var incomeBeforeNewLaw: String = ""
    /// DE/AT (nur diese Vorlagen füllen ihn, `withTemplateTexts`, `TaxTemplate.withdrawalFeeApart`): Summenzeile
    /// «Auszahlungsgebühren» — die Gebühr-Zeilen einer Auszahlung zählen dann NICHT in der Summe der Gewinne, sondern in
    /// dieser eigenen Zeile, wie der eigene Posten der Jahresübersicht. Leer = in der Summe wie bisher. Wie Android.
    var withdrawalFeesTotal: String = ""

    /// Aus den App-Texten; Währungsspalten mit `currency`, Zeitzone der Daten `zone` (die der Vorlage, Neutral: Gerät).
    static func localized(currency: String, zone: TimeZone = .current) -> TaxCsvTexts {
        TaxCsvTexts(
            coin: L("portfolio_export_col_coin"),
            acquired: L("tax_export_col_acquired"),
            sold: L("tax_export_col_sold"),
            holdingDays: L("tax_export_col_days"),
            longTerm: L("tax_export_col_long_term"),
            amount: L("portfolio_export_col_amount"),
            costUsdt: L("tax_export_col_cost", "USDT"),
            proceedsUsdt: L("tax_export_col_proceeds", "USDT"),
            gainUsdt: L("tax_export_col_gain", "USDT"),
            costTarget: L("tax_export_col_cost", currency),
            proceedsTarget: L("tax_export_col_proceeds", currency),
            gainTarget: L("tax_export_col_gain", currency),
            date: L("tax_export_col_date"),
            valueUsdt: L("portfolio_export_col_value"),
            valueTarget: L("portfolio_export_col_value_cur", currency),
            note: L("portfolio_export_col_note"),
            total: L("portfolio_export_total"),
            yes: L("tax_export_yes"),
            no: L("tax_export_no"),
            costMissing: L("tax_export_cost_missing"),
            proceedsMissing: L("tax_export_proceeds_missing"),
            feeLeg: L("tax_export_fee_leg"),
            feeValueMissing: L("tax_export_fee_value_missing"),
            avgCostRates: L("tax_export_avg_cost_rates"),
            valueMissing: L("tax_export_value_missing"),
            noFx: L("portfolio_export_no_fx"),
            incomplete: L("portfolio_export_incomplete"),
            oversold: L("tax_export_oversold"),
            proceedsWithoutCost: L("tax_export_proceeds_without_cost"),
            // Jahr und Daten in der Zeitzone der Vorlage (wie `PortfolioTaxExporter.build`) — in der CSV genannt
            // («Daten in Ortszeit (Zone)»; Verlauf/Heute rechnen in UTC)
            timeZone: L("tax_export_year_local_zone", zone.identifier),
            // Tausch und FIFO je Wallet (Platzhalter «%s» setzt der Export mit dem Namen der Wallet)
            swap: L("tax_export_swap"),
            wallet: L("tax_export_wallet", "%s"),
            walletUnspecified: L("tax_export_wallet_unspecified"),
            lostFeeCost: L("tax_export_lost_fee_cost"),
            unassignedFee: L("tax_export_unassigned_fee"),
            // Gebühr als Aufwand (`PortfolioFeeExpenses`)
            feeConsumed: L("tax_export_fee_consumed"),
            withdrawalFee: L("tax_export_withdrawal_fee"),
            // CSV-Zeile «Fee»: Veräusserung ohne Erlös
            feeRow: L("tax_export_fee_row"),
            // Verschenkt/bezahlt und geschätzter Wert (Build 47)
            gift: L("tax_export_gift"),
            payment: L("tax_export_payment"),
            valueEstimated: L("tax_export_value_estimated"),
            // Build 56: Schenkung an Ehe-/Lebenspartner (UK s. 58, CA 73(1))
            spouseGift: L("tax_export_spouse_gift"),
            // Auszahlungsgebühr USDT/Fiat nur Information (UK, US, CA, AU, JP: `TaxTemplate.quoteFeeIsInfo`)
            withdrawalFeeInfo: L("tax_export_withdrawal_fee_info"),
            // Build 57: Umbuchungsgebühr USDT/Fiat nur Information (nicht im Einstand)
            transferFeeInfo: L("tax_export_transfer_fee_info"),
            // USDT zum Marktkurs: Tag ohne USDT/USD-Tagesschluss rechnet mit 1 — je Zeile vermerkt
            usdtAssumed: L("portfolio_usdt_assumed")
        )
    }
}
