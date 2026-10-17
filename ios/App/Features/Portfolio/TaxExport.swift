import Foundation

// Steuer-Exporte eines Kalenderjahrs — wie `TaxExport.kt` (Android), dort beschrieben. Keine Steuerberatung.

/// Eine Zeile «Realisierte Gewinne»: eine Veräusserung (`PortfolioDisposal`; FIFO je Posten, Ø je Verkauf).
/// Beträge exakt, gerundet wird erst im Text; nil = unbekannt (Feld leer).
struct TaxGainRow: Equatable, Sendable {
    let coin: String
    /// Datum des Zugangs (Ortszeit); Ø: nil.
    let acquiredDate: LocalDay?
    let soldDate: LocalDay
    /// Kalendertage zwischen Kauf- und Verkaufsdatum; Ø: nil.
    let holdingDays: Int?
    /// Länger als ein Jahr gehalten (`TaxExport.isLongTerm`); Ø: nil.
    let longTerm: Bool?
    let amount: Decimal
    let costUsdt: Decimal?
    let proceedsUsdt: Decimal?
    let gainUsdt: Decimal?
    /// Einstand in der Anzeigewährung: Summe der Herkunftsteile (`PortfolioDisposal.costParts`) je zum Kurs ihres Datums —
    /// FIFO: Erwerb und zugeschlagene Umbuchungsgebühren je zu ihrem Datum (5000 USDT 2018 zu 1.00 + 100 USDT 2024 zu 0.85
    /// = 5085 CHF), Ø: laufender Ø; ohne Teile Einstand × Kurs am Kaufdatum. nil, wenn ein nötiger Kurs fehlt.
    let costTarget: Decimal?
    /// Erlös × Devisenkurs am Verkaufsdatum.
    let proceedsTarget: Decimal?
    /// `proceedsTarget` − `costTarget`.
    let gainTarget: Decimal?
    /// Ein benötigter Devisenkurs fehlt.
    let fxMissing: Bool
    let feeLeg: Bool
    let feeValueMissing: Bool
    let method: CostMethod
    /// Mehr verkauft als erfasst: Teil ohne erfassten Zugang (`PortfolioDisposal.uncovered`).
    var uncovered = false
    /// Veräusserung durch einen Tausch (`PortfolioDisposal.swap`).
    var swap = false
    /// `.FIFO_WALLET`: Wallet (`PortfolioDisposal.wallet`); sonst nil.
    var wallet: String? = nil
    // ---- Steuer-Vorlagen (`TaxEngines`); Tatsachen, keine Schlüsse — wie Android ----
    /// UK: Zuordnung (`PortfolioDisposal.match`).
    var match: TaxMatch? = nil
    /// CA: nicht abziehbarer Verlust (USDT, ≥ 0) und × Devisenkurs am Verkaufsdatum.
    var adjustmentUsdt: Decimal? = nil
    var adjustmentTarget: Decimal? = nil
    /// AT: Altbestand (true) / Neubestand (false).
    var oldHolding: Bool? = nil
    /// FR: Erwerbspreis-Summe unvollständig.
    var costIncomplete = false
    /// FR: Gesamtwert des Portfolios vor der Abtretung, Abtretungspreis vor Gebühren (USDT / Anzeigewährung).
    var portfolioValueUsdt: Decimal? = nil
    var portfolioValueTarget: Decimal? = nil
    var grossProceedsUsdt: Decimal? = nil
    var grossProceedsTarget: Decimal? = nil
    /// Einstand aus Herkunftsteilen (Pool/Durchschnitt) — mit `.AVERAGE` zusammen `pooled`.
    var pooledParts = false
    /// Gebühr als Aufwand (`PortfolioDisposal.feeExpense`): Einstand = Aufwand, Erlös 0; sonst nil.
    var feeExpense: PortfolioFeeExpense? = nil
    /// Gebühr als Veräusserung ohne Erlös (`PortfolioLedger.isFeeDisposal`, CSV-Zeile «Fee»): Vermerk «Gebühr».
    var feeRow = false
    /// Schenkung als Veräusserung (`PortfolioDisposal.gift`, UK/CA/AU): Vermerk «Schenkung».
    var gift = false
    /// Zahlung mit Krypto (`PortfolioDisposal.payment`): Vermerk «Zahlung mit Krypto».
    var payment = false
    /// Wert geschätzt (`PortfolioDisposal.valueEstimated`, Tagesschluss): Vermerk, zählt nicht als «unvollständig».
    var valueEstimated = false
    /// UK/CA: Schenkung an Ehe-/Lebenspartner (`PortfolioDisposal.spouseGift`): Erlös = Einstand, Vermerk.
    var spouseGift = false
    /// Auszahlungsgebühr in USDT/Fiat mit `quoteFeeIsInfo` (UK, US, CA, AU, JP): kein Verlust, nur Information — nicht
    /// in den Summen, Vermerk `TaxCsvTexts.withdrawalFeeInfo`. Wie Android.
    var feeInfoOnly = false

    /// Einstand aus einem Durchschnitt (Herkunftsteile je Kaufdatum umgerechnet) — wie `pooled` (Android); ein
    /// Gebühr-Aufwand ist kein Durchschnitt.
    var pooled: Bool { (method == .AVERAGE && feeExpense == nil) || pooledParts }

    var costMissing: Bool { costUsdt == nil }
    var proceedsMissing: Bool { proceedsUsdt == nil }
    /// Einstand, Erlös und Gewinn in USDT bekannt (zählt in der Summe).
    var completeUsdt: Bool { costUsdt != nil && proceedsUsdt != nil && gainUsdt != nil }
    /// Einstand, Erlös und Gewinn in der Anzeigewährung bekannt (zählt in der Summe).
    var completeTarget: Bool { costTarget != nil && proceedsTarget != nil && gainTarget != nil }
}

/// Eine Zeile «Einkünfte»: Belohnung (`.REWARD`) mit Wert beim Erhalt.
struct TaxIncomeRow: Equatable, Sendable {
    let date: LocalDay
    let coin: String
    let amount: Decimal
    /// Menge × Wert je Coin beim Erhalt; nil = Wert fehlt.
    let valueUsdt: Decimal?
    /// `valueUsdt` × Devisenkurs des Tags.
    let valueTarget: Decimal?
    let fxMissing: Bool
    /// Zins (Lending, `TaxEngines.interestReward`) — AT: beim Zufluss Einkunft zum Sondersteuersatz (`TaxLine.AT_INTEREST`).
    var interest = false

    var valueMissing: Bool { valueUsdt == nil }
}

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

    /// Aus den App-Texten; Währungsspalten mit `currency`, Zeitzone der Daten `zone` (Gerät).
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
            // Jahr und Daten in der Zeitzone des Geräts (wie `PortfolioTaxExporter.build`) — in der CSV genannt
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
            transferFeeInfo: L("tax_export_transfer_fee_info")
        )
    }
}

/// Ergebnis einer Freigrenzen-Prüfung — wie `TaxLimitStatus` (Android).
enum TaxLimitStatus: Sendable, Equatable {
    /// Unter der Freigrenze (steuerfrei).
    case below
    /// Erreicht oder überschritten: der GANZE Betrag ist steuerpflichtig (Freigrenze, kein Freibetrag).
    case reached
    /// Nicht prüfbar (EUR-Werte fehlen, Gewinn/Wert unbekannt, Haltedauer fehlt).
    case unknown
    /// DE § 23: netto ein Verlust — kein steuerpflichtiger Gewinn; nur mit Gewinnen aus privaten Veräusserungsgeschäften
    /// verrechenbar (§ 23 Abs. 3 Sätze 7–8 EStG, Rück-/Vortrag nach § 10d). Wie `TaxLimitStatus.LOSS` (Android).
    case loss
}

/// Zusatz-Vermerk eines Postens der Jahresübersicht (`TaxSummaryLine.note`, Text in `TaxSummaryTexts.notes`) — wie
/// `TaxSummaryNote` (Android).
enum TaxSummaryNote: String, Sendable, Equatable, Hashable {
    /// AT Einkünfte: nur Information, nicht addieren — Belohnungen (Staking, Airdrop) haben im Neubestand Anschaffungskosten
    /// 0 und sind erst beim Verkauf steuerpflichtig (dort schon in den Gewinnen; BMF, § 27b Abs. 2 EStG).
    case AT_REWARD_INFO
    /// CH Vermögen: der Wert per 31.12. der Bestand-CSV ist Börsenkurs × USD/CHF, nicht die Steuerwerte der ESTV-Kursliste.
    case CH_EXCHANGE_VALUE
    /// CH Gewinne/Verluste/netto: nur Information — FIFO-Rechnung der App, keine Einordnung (Kapitalgewinne im
    /// Privatvermögen «nicht gerechnet – siehe ESTV»).
    case CH_FIFO_INFO
    /// DE Auszahlungsgebühren (`TaxLine.WITHDRAWAL_FEES`): nicht mit § 23 verrechnet — ggf. Werbungskosten prüfen.
    case DE_WITHDRAWAL_FEE
    /// AT Auszahlungsgebühren: nicht mit dem Neubestand verrechnet (§ 20 Abs. 2 EStG: beim Sondersteuersatz nicht abzugsfähig).
    case AT_WITHDRAWAL_FEE
    /// AT Zinsen (`TaxLine.AT_INTEREST`): beim Zufluss laufende Einkünfte aus Kryptowährungen (Entgelt für die Überlassung,
    /// § 27b Abs. 2 Z 1 EStG) zum Sondersteuersatz 27,5 %; die Coins behalten diesen Wert als Anschaffungskosten (BMF).
    case AT_INTEREST_INCOME
    /// UK, US, CA, AU, JP Auszahlungsgebühren in USDT/Fiat (`TaxTemplate.quoteFeeIsInfo`): kein Verlust, nur Information
    /// (UK: HMRC CRYPTO22150; sonst keine Regel, die sie abziehbar macht).
    case WITHDRAWAL_FEE_INFO
    /// UK, US, CA, AU, JP Umbuchungsgebühren in USDT/Fiat (`TaxLine.TRANSFER_FEES`, Build 57): kein Verlust und nicht im
    /// Einstand, nur Information.
    case TRANSFER_FEE_INFO
    /// AT Neubestand netto (`TaxLine.NEW_NET`): Verkäufe bzw. Tausch von Neuvermögen vom 1.3.2021 (`TaxTemplate.atNewFrom`)
    /// bis 28.2.2022 (vor `TaxTemplate.atSwapNeutralFrom`) sind mit 27,5 % gebucht — rechtlich galt dort noch die alte
    /// Rechtslage (§ 31 EStG a. F.) mit Option auf 27,5 % (§ 124b Z 384 EStG): mit Steuerberatung klären.
    case AT_TRANSITION_2021
}

/// Jahresübersicht Deutschland (`TaxExport.deYearSummary`) — Orientierung, keine Steuerberatung. Wie `TaxYearSummaryDe`
/// (Android): Gewinne > 0, Verluste ≤ 0; EUR-Werte nil ohne EUR-Kurse.
struct TaxYearSummaryDe: Sendable, Equatable {
    let year: Int
    let method: CostMethod
    let shortGainsUsdt: Decimal
    let shortLossesUsdt: Decimal
    let netShortUsdt: Decimal
    let longGainsUsdt: Decimal
    let longLossesUsdt: Decimal
    /// Bekannter Gewinn/Verlust ohne Haltedauer (Ø, mehr verkauft als erfasst).
    let unclassifiedUsdt: Decimal
    let eurAvailable: Bool
    let shortGainsEur: Decimal?
    let shortLossesEur: Decimal?
    let netShortEur: Decimal?
    let longGainsEur: Decimal?
    let longLossesEur: Decimal?
    let unclassifiedEur: Decimal?
    /// Kurzfristig / ohne Haltedauer mit unbekanntem Gewinn.
    let unknownGainCount: Int
    /// Ohne Haltedauer (Ø, ungedeckter Rest).
    let unclassifiedCount: Int
    /// Kurzfristig / ohne Haltedauer mit bekanntem Gewinn, aber ohne EUR-Kurs.
    let eurFxMissingCount: Int
    let privateSalesLimitEur: Decimal
    let privateSalesStatus: TaxLimitStatus
    /// Einkünfte aus Leistungen (§ 22 Nr. 3: Belohnungen) — Summe der bekannten Werte.
    let incomeUsdt: Decimal
    let incomeEur: Decimal?
    let incomeValueMissingCount: Int
    let incomeFxMissingCount: Int
    let incomeLimitEur: Decimal
    let incomeStatus: TaxLimitStatus
    /// Gebühren von Auszahlungen (`.WITHDRAWAL`, negativ oder 0) — eigener Posten: NICHT in den Summen ≤ 1 Jahr und nicht
    /// in der Freigrenze § 23 (keine Veräusserung; ggf. Werbungskosten). Wie Android.
    var withdrawalFeesUsdt: Decimal = 0
    var withdrawalFeesEur: Decimal? = nil
    /// Anzahl solcher Gebühr-Zeilen (0 = kein Posten).
    var withdrawalFeeCount = 0
}

/// Texte der «Jahresübersicht DE» (übersetzt) — wie `TaxDeTexts` (Android).
struct TaxDeTexts: Sendable {
    /// «Jahresübersicht DE %d» mit dem Jahr eingesetzt.
    var title: String
    /// «Orientierung, keine Steuerberatung …» — steht immer in der Datei.
    var disclaimer: String
    var item: String
    var usdt: String
    var eur: String
    var note: String
    var shortGains: String
    var shortLosses: String
    var netShort: String
    var longGains: String
    var longLosses: String
    var unclassified: String
    var privateSalesLimit: String
    var income: String
    var incomeLimit: String
    var below: String
    var reached: String
    var unknown: String
    var needsEur: String
    var incomplete: String
    var needsHolding: String
    var noFx: String
    var limitScope: String
    /// Kopf des Orientierungs-Blocks «Orientierung – keine Steuerberatung, keine Veranlagung»; leer = `disclaimer`.
    var orientationHeading: String = ""
    /// Posten im Orientierungs-Block «Orientierung: %s»; leer = ohne Vorsatz.
    var orientationItem: String = ""
    /// Status «Verlust» (`TaxLimitStatus.loss`); leer = `below`.
    var loss: String = ""
    /// Posten «Auszahlungsgebühren»; leer = kein Posten.
    var withdrawalFees: String = ""
    /// Vermerk «Auszahlungsgebühr – nicht mit § 23 verrechnet, ggf. Werbungskosten prüfen».
    var withdrawalFeeNote: String = ""
}

/// Posten der Jahresübersicht einer Vorlage (`TaxExport.yearSummary`) — wie `TaxLine` (Android); `orientation` = Schluss
/// nach den Regeln der Vorlage, nur als «Orientierung» mit Hinweis.
enum TaxLine: String, CaseIterable, Sendable {
    case SHORT_GAINS, SHORT_LOSSES, SHORT_NET, LONG_GAINS, LONG_LOSSES, LONG_NET, UNCLASSIFIED
    case GAINS, LOSSES, NET
    case NEW_NET, OLD_SHORT_NET, OLD_LONG_NET
    case DENIED_LOSS
    case PROCEEDS
    case INCOME
    /// AT: Zinsen aus Lending (`TaxIncomeRow.interest`) zum Wert beim Zufluss — zählen (Vermerk `.AT_INTEREST_INCOME`); nicht
    /// in `INCOME` (dort nur Belohnungen mit Anschaffungskosten 0, Information).
    case AT_INTEREST
    /// FR: Abgänge ohne Verkauf im Jahr — Kürzung der Erwerbspreis-Summe (`TaxOutflow`); das Jahr gilt als unvollständig.
    case FR_OUTFLOW
    /// DE, AT: Gebühren von Auszahlungen — eigener Posten, nicht mit den Veräusserungen verrechnet.
    case WITHDRAWAL_FEES
    /// UK, US, CA, AU, JP (Build 57): Gebühren USDT/Fiat von Umbuchungen zwischen eigenen Wallets (`.TRANSFER`) — nur
    /// Information, weder Verlust noch Einstand.
    case TRANSFER_FEES
    case DE_LONG, DE_PRIVATE_LIMIT, DE_INCOME_LIMIT
    case AT_RATE, AT_OLD_LONG
    case CH_PRIVATE, CH_INCOME, CH_WEALTH
    case UK_EXEMPT, UK_AFTER_EXEMPT
    case US_TERMS
    case CA_TAXABLE
    case AU_DISCOUNTED
    case FR_THRESHOLD, FR_RATE
    case JP_MISC

    var orientation: Bool {
        switch self {
        case .SHORT_GAINS, .SHORT_LOSSES, .SHORT_NET, .LONG_GAINS, .LONG_LOSSES, .LONG_NET, .UNCLASSIFIED,
             .GAINS, .LOSSES, .NET, .NEW_NET, .OLD_SHORT_NET, .OLD_LONG_NET, .DENIED_LOSS, .PROCEEDS, .INCOME, .AT_INTEREST, .FR_OUTFLOW,
             .WITHDRAWAL_FEES, .TRANSFER_FEES:
            return false
        case .DE_LONG, .DE_PRIVATE_LIMIT, .DE_INCOME_LIMIT, .AT_RATE, .AT_OLD_LONG, .CH_PRIVATE, .CH_INCOME, .CH_WEALTH,
             .UK_EXEMPT, .UK_AFTER_EXEMPT, .US_TERMS, .CA_TAXABLE, .AU_DISCOUNTED, .FR_THRESHOLD, .FR_RATE, .JP_MISC:
            return true
        }
    }
}

/// Ein Posten — wie `TaxSummaryLine` (Android).
struct TaxSummaryLine: Sendable, Equatable {
    let line: TaxLine
    var usdt: Decimal? = nil
    var target: Decimal? = nil
    var status: TaxLimitStatus? = nil
    /// Ein Gewinn/Wert fehlt — Betrag nur aus dem Bekannten.
    var incomplete = false
    /// Ein Devisenkurs fehlt.
    var noFx = false
    /// Kein Betrag, weil die App ihn nicht rechnet (CH Vermögenssteuer: ESTV-Kursliste) — Vermerk `notCalculated`.
    var notCalculated = false
    /// Zusatz-Vermerk (`TaxSummaryTexts.notes`); nil = keiner.
    var note: TaxSummaryNote? = nil
}

/// Jahresübersicht einer Vorlage — ORIENTIERUNG, keine Steuerberatung. Wie `TaxYearSummary` (Android).
struct TaxYearSummary: Sendable, Equatable {
    let template: TaxTemplate
    let method: TaxMethod
    let period: TaxPeriod
    /// Steuerwährung (`TaxTemplate.currency`, Neutral: Anzeigewährung).
    let currency: String
    /// Devisenkurse geladen (oder USD).
    let fxAvailable: Bool
    let lines: [TaxSummaryLine]
    /// US-Übergang, mit dem gerechnet wurde (`TaxTemplate.walletFrom`); nil = keiner.
    var walletFrom: LocalDay? = nil
}

/// Texte der Jahresübersicht (`TaxExport.summaryCsv`) — wie `TaxSummaryTexts` (Android).
struct TaxSummaryTexts: Sendable {
    var title: String
    var disclaimer: String
    var item: String
    var usdt: String
    var target: String
    var note: String
    var orientation: String
    var labels: [TaxLine: String]
    var below: String
    var reached: String
    var unknown: String
    var incomplete: String
    var noFx: String
    /// Kopf des Orientierungs-Blocks «Orientierung – keine Steuerberatung, keine Veranlagung»; leer = `orientation`.
    var orientationHeading: String = ""
    /// Posten im Orientierungs-Block «Orientierung: %s»; leer = «`orientation`: Posten».
    var orientationItem: String = ""
    /// Vermerk «nicht gerechnet – ESTV-Kursliste <Link>» (`TaxSummaryLine.notCalculated`); leer = keiner.
    var notCalculated: String = ""
    /// Status «Verlust» (`TaxLimitStatus.loss`); leer = `below`.
    var loss: String = ""
    /// Texte der Zusatz-Vermerke (`TaxSummaryLine.note`); fehlt einer = kein Vermerk.
    var notes: [TaxSummaryNote: String] = [:]
}

/// Posten im Orientierungs-Block — wie `taxOrientationLabel` (Android): `item` mit «%s» für `label`; ohne `item`
/// «`fallback`: `label`» (leer = nur `label`). Jeder Schluss einer Vorlage steht so sichtbar als Orientierung da.
func taxOrientationLabel(_ item: String, fallback: String, label: String) -> String {
    if item.contains("%s") { return item.replacingOccurrences(of: "%s", with: label) }
    if !fallback.isEmpty { return "\(fallback): \(label)" }
    return label
}

/// Welche Datei des Steuer-Exports.
enum TaxExportKind: Sendable {
    case gains
    case income
}

/// Reine Rechnung und CSV der Steuer-Exporte — gleich wie `TaxExport.kt`: Gewinne je Veräusserung mit Verkaufsdatum im
/// Jahr (nach der Einstandsmethode; mehr verkauft als erfasst: Rest ohne Kaufpreis), Einkünfte aus Belohnungen,
/// Umrechnung mit dem EZB-Tageskurs des Datums (letzter Tag davor, höchstens `fxMaxGapDays` Tage; Ø: laufender Ø in der
/// Anzeigewährung, jeder Zugang zu seinem Kurs); «> 1 Jahr» = Verkaufsdatum nach Kaufdatum + 1 Kalenderjahr (§ 23 EStG;
/// 29.2. → 28.2.). Daten in der Zeitzone des Geräts (in der CSV genannt). Summe nur über vollständige Zeilen, dazu
/// «Erlös ohne Kaufpreis». CSV wie der Stichtag-Export (BOM, CRLF, `CutoffCsvFormat`).
enum TaxExport {

    /// Devisenkurs höchstens so viele Tage vor dem Datum (Wochenende, Feiertage).
    static let fxMaxGapDays = 7

    private static let bom = "\u{FEFF}"
    private static let eol = "\r\n"

    // Steuerjahr und Daten bewusst in der Zeitzone des Geräts (`timeZone`): massgebend ist das rechtliche (lokale)
    // Datum des Geschäfts — ein Verkauf am 31.12. um 23:30 Zürich gehört ins alte Jahr, auch wenn es in UTC schon der
    // 1.1. ist. Wertverlauf, «Heute/7 T/1 J» und Tageskerzen rechnen dagegen mit UTC-Tagen (`PortfolioHistory.utcDayEnd`)
    // — eine Transaktion um Mitternacht kann im Chart daher auf einen anderen Tag fallen als in der Steuer-CSV. Wie Android.
    static func localDay(_ millis: Int64, timeZone: TimeZone) -> LocalDay {
        LocalDay(date: Date(timeIntervalSince1970: Double(millis) / 1000), timeZone: timeZone)
    }

    /// Eigene Transaktionen (ohne Adress-Einträge).
    private static func real(_ trades: [PortfolioTx]) -> [PortfolioTx] {
        trades.filter { !AddressBalance.isAddressTrade($0.id) && $0.amountExact > 0 }
    }

    /// Jahre mit Transaktionen, neueste zuerst.
    static func years(_ trades: [PortfolioTx], timeZone: TimeZone) -> [Int] {
        years(trades, timeZone: timeZone, template: .NEUTRAL)
    }

    /// Steuerjahre (`TaxTemplate.periodOf`, Beginnjahr) mit Transaktionen, neueste zuerst.
    static func years(_ trades: [PortfolioTx], timeZone: TimeZone, template: TaxTemplate) -> [Int] {
        Array(Set(real(trades).map { template.periodOf(localDay($0.time, timeZone: timeZone)) })).sorted(by: >)
    }

    /// Standard: letztes volles Jahr mit Transaktionen (vor `today`), sonst das neueste; nil ohne Jahre.
    static func defaultYear(_ years: [Int], today: LocalDay) -> Int? {
        defaultYear(years, today: today, template: .NEUTRAL)
    }

    /// Standard: letztes abgeschlossenes Steuerjahr mit Transaktionen, sonst das neueste; nil ohne Jahre.
    static func defaultYear(_ years: [Int], today: LocalDay, template: TaxTemplate) -> Int? {
        let current = template.periodOf(today)
        return years.filter { $0 < current }.max() ?? years.max()
    }

    /// Mehr als ein Jahr gehalten: `sold` nach `acquired` + 1 Kalenderjahr (29.2. → 28.2. wie `plusYears`).
    static func isLongTerm(acquired: LocalDay, sold: LocalDay) -> Bool {
        let year = acquired.year + 1
        let leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        let day = acquired.month == 2 && acquired.day == 29 && !leap ? 28 : acquired.day
        return sold > LocalDay(year, acquired.month, day)
    }

    /// Stichtag des Bestands am Jahresende.
    static func yearEnd(_ year: Int) -> CutoffDay { CutoffDay(year: year, month: 12, day: 31) }

    /// Kalenderjahr als Steuerjahr (Neutral).
    private static func calendar(_ year: Int) -> TaxPeriod { TaxTemplate.NEUTRAL.period(year) }

    static func gainsFileName(_ year: Int) -> String { "cryptochecker-gewinne-\(year).csv" }

    static func incomeFileName(_ year: Int) -> String { "cryptochecker-einkuenfte-\(year).csv" }

    static func deSummaryFileName(_ year: Int) -> String { "cryptochecker-jahresuebersicht-de-\(year).csv" }

    /// Dateiname der Jahresübersicht einer Vorlage, z. B. «cryptochecker-jahresuebersicht-uk-2025.csv».
    static func summaryFileName(_ template: TaxTemplate, year: Int) -> String {
        "cryptochecker-jahresuebersicht-\(template.rawValue.lowercased())-\(year).csv"
    }

    /// Kopfzeilen der Arbeitsunterlage: Bei CH steht «Vermögenssteuer nicht gerechnet – Kursliste ESTV» (`chWealthNote`)
    /// gleich unter dem Titel (Zeile 2) — in jeder CSV der Vorlage, nicht nur als Vermerk weiter unten. Andere Vorlagen
    /// oder leerer Text: unverändert. Wie `TaxExport.withChWealthNote` (Android).
    static func withChWealthNote(_ template: TaxTemplate, header: [String], chWealthNote: String) -> [String] {
        guard template == .CH, !chWealthNote.isEmpty, !header.contains(chWealthNote) else { return header }
        return Array(header.prefix(1)) + [chWealthNote] + Array(header.dropFirst())
    }

    /// Freigrenze § 23 Abs. 3 Satz 5 EStG in EUR (steuerfrei bei WENIGER als der Grenze): 1000 € ab 2024, 600 € 2009–2023,
    /// 512 € bis 2008 — wie `privateSalesLimitEur` (Android).
    static func privateSalesLimitEur(_ year: Int) -> Decimal {
        if year >= 2024 { return 1000 }
        if year >= 2009 { return 600 }
        return 512
    }

    /// Freigrenze § 22 Nr. 3 Satz 2 EStG (Einkünfte aus Leistungen): steuerfrei, wenn weniger als 256 € im Jahr.
    static let incomeLimitEur: Decimal = 256

    /// USD → Zielwährung am `date`: USD = 1; sonst der Kurs des Tags oder des letzten Tags davor (höchstens
    /// `fxMaxGapDays` Tage); `rates` = Tag (epochDay) → Kurs, nil = nicht geladen.
    static func rateOn(_ rates: [Int: Decimal]?, date: LocalDay, currency: String) -> Decimal? {
        if currency.trimmingCharacters(in: .whitespaces).uppercased() == "USD" { return 1 }
        let day = date.epochDay
        let best = (rates ?? [:])
            .filter { $0.key <= day && day - $0.key <= fxMaxGapDays && $0.value > 0 }
            .max { $0.key < $1.key }
        return best?.value
    }

    /// Veräusserungen mit Verkaufsdatum im Jahr `year`, wie `PortfolioCalculator.disposals` sortiert.
    static func disposals(in trades: [PortfolioTx], method: CostMethod, year: Int,
                          timeZone: TimeZone) -> [PortfolioDisposal] {
        disposals(in: trades, method: TaxMethod.of(method), period: calendar(year), timeZone: timeZone)
    }

    /// Veräusserungen nach `method` mit Verkaufsdatum im Steuerjahr `period` (`TaxEngines.disposals`; `fx` = USDT →
    /// Steuerwährung zu einem Zeitpunkt, CA: Superficial Loss in CAD).
    static func disposals(in trades: [PortfolioTx], method: TaxMethod, period: TaxPeriod, timeZone: TimeZone,
                          priceAt: ((String, Int64) -> Decimal?)? = nil,
                          fx: ((Int64) -> Decimal?)? = nil,
                          rules: TaxEventRules? = nil,
                          walletFrom: LocalDay? = nil) -> [PortfolioDisposal] {
        // Adress-Einträge bleiben drin: keine Veräusserungen (die Rechnungen trennen sie ab), FR braucht sie für den
        // Gesamtwert des Portfolios — wie Android
        TaxEngines.disposals(trades.filter { $0.amountExact > 0 }, method: method, timeZone: timeZone, priceAt: priceAt, fx: fx,
                             rules: rules, walletFrom: walletFrom)
            .filter { period.contains(localDay($0.sellTime, timeZone: timeZone)) }
    }

    /// Einstand von Netzwerkgebühren ohne bezahlten Posten mit Datum im Jahr `year`, zeitlich — wie
    /// `lostFeeCostsIn` (Android); für den Vermerk im Gewinne-Export. Kein realisierter Gewinn/Verlust.
    static func lostFeeCosts(in trades: [PortfolioTx], method: CostMethod, year: Int,
                             timeZone: TimeZone) -> [PortfolioLostFeeCost] {
        lostFeeCosts(in: trades, method: TaxMethod.of(method), period: calendar(year), timeZone: timeZone)
    }

    /// Wie oben für ein Steuerjahr und jede Vorlage — wie Android: Einstandsmethoden über `PortfolioCalculator.ledger`,
    /// eigene Rechnungen über `TaxEngines.lostFeeCosts`. Mit `networkFeeIsDisposal` (UK, US, CA, AU, JP) ist die
    /// Netzwerkgebühr eine Veräusserung — kein Einstand bleibt liegen; FR: keiner. `rules` nil = nach der Rechnung.
    static func lostFeeCosts(in trades: [PortfolioTx], method: TaxMethod, period: TaxPeriod,
                             timeZone: TimeZone, rules: TaxEventRules? = nil) -> [PortfolioLostFeeCost] {
        let r = real(trades)
        let all: [PortfolioLostFeeCost]
        if let cm = method.costMethod {
            let applied = TaxEngines.applyRules(r, rules: rules ?? .of(method), priceAt: nil)
            all = PortfolioLedger.coins(applied)
                .flatMap { PortfolioCalculator.ledger($0, trades: applied, currentPrice: nil, method: cm).lostFeeCosts }
        } else {
            all = TaxEngines.lostFeeCosts(r, method: method, timeZone: timeZone, rules: rules)
        }
        return all
            .filter { period.contains(localDay($0.time, timeZone: timeZone)) }
            .sorted { ($0.time, $0.txId, $0.coin) < ($1.time, $1.txId, $1.coin) }
    }

    /// Umbuchungsgebühren auf Bestand ohne Kaufpreis (`PortfolioUnassignedFee`) mit Datum im Jahr `year`, zeitlich — wie
    /// `unassignedFeesIn` (Android); für die Info-Zeile im Gewinne-Export. Kein realisierter Gewinn/Verlust.
    static func unassignedFees(in trades: [PortfolioTx], method: CostMethod, year: Int,
                               timeZone: TimeZone) -> [PortfolioUnassignedFee] {
        unassignedFees(in: trades, method: TaxMethod.of(method), period: calendar(year), timeZone: timeZone)
    }

    /// Wie oben für ein Steuerjahr und jede Vorlage — wie Android: Einstandsmethoden über `PortfolioCalculator.ledger`
    /// (nach `applyRules`: mit `quoteFeeIsInfo` zählt die Gebühr USDT einer Umbuchung ohnehin nicht zum Einstand), eigene
    /// Rechnungen über `TaxEngines.unassignedFees`. `rules` nil = nach der Rechnung.
    static func unassignedFees(in trades: [PortfolioTx], method: TaxMethod, period: TaxPeriod,
                               timeZone: TimeZone, rules: TaxEventRules? = nil) -> [PortfolioUnassignedFee] {
        let r = real(trades)
        let all: [PortfolioUnassignedFee]
        if let cm = method.costMethod {
            let applied = TaxEngines.applyRules(r, rules: rules ?? .of(method), priceAt: nil)
            all = PortfolioLedger.coins(applied)
                .flatMap { PortfolioCalculator.ledger($0, trades: applied, currentPrice: nil, method: cm).unassignedFees }
        } else {
            all = TaxEngines.unassignedFees(r, method: method, timeZone: timeZone, rules: rules)
        }
        return all
            .filter { period.contains(localDay($0.time, timeZone: timeZone)) }
            .sorted { ($0.time, $0.txId, $0.coin) < ($1.time, $1.txId, $1.coin) }
    }

    /// Belohnungen im Steuerjahr `period`, zeitlich (dann Id).
    private static func rewards(in trades: [PortfolioTx], period: TaxPeriod, timeZone: TimeZone) -> [PortfolioTx] {
        real(trades)
            .filter { $0.type == .REWARD && period.contains(localDay($0.time, timeZone: timeZone)) }
            // Reihenfolge wie das Buch (`PortfolioLedger.precedes`)
            .enumerated().sorted { a, b in
                PortfolioLedger.precedes(PortfolioLedgerEvent(trade: a.element, feeLeg: false),
                                         PortfolioLedgerEvent(trade: b.element, feeLeg: false)) ?? (a.offset < b.offset)
            }.map(\.element)
    }

    /// Erster Tag, für den Devisenkurse gebraucht werden (frühestes Kaufdatum bzw. 1.1.), ohne Vorlauf.
    static func fxFrom(_ trades: [PortfolioTx], method: CostMethod, year: Int, timeZone: TimeZone) -> LocalDay {
        fxFrom(trades, method: TaxMethod.of(method), period: calendar(year), timeZone: timeZone)
    }

    /// Wie oben für `period`: frühestes Kauf-/Herkunftsdatum der Veräusserungen bzw. Beginn des Steuerjahrs. CA: auch
    /// aller früheren Veräusserungen (nicht abziehbarer Verlust eines Vorjahrs wird in CAD entschieden) — wie Android.
    static func fxFrom(_ trades: [PortfolioTx], method: TaxMethod, period: TaxPeriod, timeZone: TimeZone,
                       priceAt: ((String, Int64) -> Decimal?)? = nil, rules: TaxEventRules? = nil,
                       walletFrom: LocalDay? = nil) -> LocalDay {
        // FIFO: Kaufdatum der Posten; Durchschnitt/Pool: Datum jedes Herkunftsteils des Einstands
        let upTo = method == .CA_ACB ? TaxPeriod(startYear: period.startYear, start: LocalDay(1, 1, 1), end: period.end) : period
        let first = disposals(in: trades, method: method, period: upTo, timeZone: timeZone, priceAt: priceAt,
                              rules: rules, walletFrom: walletFrom)
            .flatMap { d -> [Int64] in
                var times: [Int64] = []
                if let a = d.acquiredTime { times.append(a) }
                if let m = d.matchedTime { times.append(m) }
                return times + d.costParts.map(\.time)
            }
            .min()
            .map { localDay($0, timeZone: timeZone) }
        if let first, first < period.start { return first }
        return period.start
    }

    static func gainRows(_ trades: [PortfolioTx], method: CostMethod, year: Int, timeZone: TimeZone,
                         currency: String, rates: [Int: Decimal]?) -> [TaxGainRow] {
        gainRows(trades, method: TaxMethod.of(method), period: calendar(year), timeZone: timeZone,
                 currency: currency, rates: rates)
    }

    /// Zeilen «Realisierte Veräusserungen» des Steuerjahrs `period` nach `method` — wie `gainRows` (Android): Einstand mit
    /// Herkunftsteilen je zum Kurs ihres Datums, sonst zum Kurs des Kaufdatums; Erlös, nicht abziehbarer Verlust,
    /// Gesamtwert und Abtretungspreis zum Kurs des Verkaufsdatums.
    static func gainRows(_ trades: [PortfolioTx], method: TaxMethod, period: TaxPeriod, timeZone: TimeZone,
                         currency: String, rates: [Int: Decimal]?,
                         priceAt: ((String, Int64) -> Decimal?)? = nil, rules: TaxEventRules? = nil,
                         walletFrom: LocalDay? = nil) -> [TaxGainRow] {
        // Verkäufe, die Gebühren sind (`PortfolioLedger.isFeeDisposal`) — einmal gesammelt
        let feeSales = Set(trades.filter { PortfolioLedger.isFeeDisposal($0) }.map(\.id))
        // Transaktionen ohne Verkauf (Umbuchung, Auszahlung, Tausch): ihre Veräusserungen ohne Tausch-Vermerk sind
        // Gebühren in Krypto (Netzwerkgebühr nach `networkFeeIsDisposal`, FR Gebühr-Abtretung) — Vermerk «Gebühr»
        let nonSales = Set(trades.filter { $0.type == .TRANSFER || $0.type.isWithdrawalLike || $0.type == .SWAP }.map(\.id))
        // CA: Superficial Loss in der Steuerwährung entscheiden (dieselben Kurse wie die Zeilen)
        let fx: (Int64) -> Decimal? = { t in rateOn(rates, date: localDay(t, timeZone: timeZone), currency: currency) }
        // Auszahlungsgebühr USDT/Fiat nur Information (`TaxEventRules.quoteFeeIsInfo`)
        let feeInfo = (rules ?? .of(method)).quoteFeeIsInfo
        return disposals(in: trades, method: method, period: period, timeZone: timeZone, priceAt: priceAt,
                         fx: fx, rules: rules, walletFrom: walletFrom).map { d -> TaxGainRow in
            let sold = localDay(d.sellTime, timeZone: timeZone)
            let acquired = d.acquiredTime.map { localDay($0, timeZone: timeZone) }
            let saleRate = rateOn(rates, date: sold, currency: currency)
            func atSale(_ v: Decimal?) -> Decimal? {
                guard let v, let saleRate else { return nil }
                return PortfolioDecimal.multiply(v, saleRate)
            }
            // Herkunftsteile je zum Kurs ihres Datums (fehlt einer: leer); FIFO-Posten zum Kurs des Kaufdatums
            let costTarget: Decimal? = {
                guard let c = d.costUsdt else { return nil }
                if !d.costParts.isEmpty {
                    var sum: Decimal = 0
                    for part in d.costParts {
                        guard let r = rateOn(rates, date: localDay(part.time, timeZone: timeZone), currency: currency) else {
                            return nil
                        }
                        sum += PortfolioDecimal.multiply(part.usdt, r)
                    }
                    return sum
                }
                if let acquired {
                    return rateOn(rates, date: acquired, currency: currency).map { PortfolioDecimal.multiply(c, $0) }
                }
                // Einstand 0 (z. B. Belohnung zum Wert 0): nichts umzurechnen
                if c == 0 { return 0 }
                return saleRate.map { PortfolioDecimal.multiply(c, $0) }
            }()
            let costFxMissing = d.costUsdt != nil && costTarget == nil
            // Schenkung an Ehe-/Lebenspartner: kein Gewinn/Verlust auch in der Steuerwährung (TCGA 1992 s. 58, ITA 73(1)) —
            // der Erlös gilt als genau der umgerechnete Einstand
            let proceedsTarget: Decimal? = d.spouseGift ? costTarget : atSale(d.proceedsUsdt)
            let gainTarget: Decimal? = {
                guard let c = costTarget, let p = proceedsTarget else { return nil }
                return p - c
            }()
            var row = TaxGainRow(
                coin: d.coin,
                acquiredDate: acquired ?? d.matchedTime.map { localDay($0, timeZone: timeZone) },
                soldDate: sold,
                holdingDays: acquired.map { $0.days(until: sold) },
                longTerm: acquired.map { isLongTerm(acquired: $0, sold: sold) },
                amount: d.amount,
                costUsdt: d.costUsdt,
                proceedsUsdt: d.proceedsUsdt,
                gainUsdt: d.gainUsdt,
                costTarget: costTarget,
                proceedsTarget: proceedsTarget,
                gainTarget: gainTarget,
                fxMissing: costFxMissing || (!d.spouseGift && d.proceedsUsdt != nil && saleRate == nil),
                feeLeg: d.feeLeg,
                feeValueMissing: d.feeValueMissing,
                method: d.method,
                uncovered: d.uncovered,
                swap: d.swap,
                wallet: d.wallet
            )
            row.match = d.match
            row.adjustmentUsdt = d.adjustmentUsdt
            row.adjustmentTarget = atSale(d.adjustmentUsdt)
            row.oldHolding = d.oldHolding
            row.costIncomplete = d.costIncomplete
            row.portfolioValueUsdt = d.portfolioValueUsdt
            row.portfolioValueTarget = atSale(d.portfolioValueUsdt)
            row.grossProceedsUsdt = d.grossProceedsUsdt
            row.grossProceedsTarget = atSale(d.grossProceedsUsdt)
            // Durchschnitt/Pool (Herkunftsteile ohne Posten-Datum); FIFO-Posten tragen ihre Teile ohne diesen Vermerk
            row.pooledParts = !d.costParts.isEmpty && d.acquiredTime == nil
            row.feeExpense = d.feeExpense
            // Schenkung als Veräusserung ist keine Gebühr, auch wenn die Transaktion kein Verkauf ist
            row.feeRow = !d.feeLeg && d.feeExpense == nil && !d.gift && !d.payment
                && (feeSales.contains(d.sellTxId) || (!d.swap && nonSales.contains(d.sellTxId)))
            row.gift = d.gift
            row.payment = d.payment
            row.valueEstimated = d.valueEstimated
            row.spouseGift = d.spouseGift
            // `.TRANSFER`-Zeilen gibt es nur mit dieser Regel (`TaxEngines.disposals`)
            row.feeInfoOnly = d.feeExpense == .TRANSFER || (feeInfo && d.feeExpense == .WITHDRAWAL)
            return row
        }
    }

    static func incomeRows(_ trades: [PortfolioTx], year: Int, timeZone: TimeZone, currency: String,
                           rates: [Int: Decimal]?) -> [TaxIncomeRow] {
        incomeRows(trades, period: calendar(year), timeZone: timeZone, currency: currency, rates: rates)
    }

    /// Belohnungen des Steuerjahrs `period` mit Wert beim Erhalt (Menge × Wert je Coin), zum Kurs des Tags.
    static func incomeRows(_ trades: [PortfolioTx], period: TaxPeriod, timeZone: TimeZone, currency: String,
                           rates: [Int: Decimal]?) -> [TaxIncomeRow] {
        rewards(in: trades, period: period, timeZone: timeZone).map { t -> TaxIncomeRow in
            let date = localDay(t.time, timeZone: timeZone)
            let value = t.validPriceExact.map { t.amountExact * $0 }
            let rate = rateOn(rates, date: date, currency: currency)
            let target: Decimal? = {
                guard let value, let rate else { return nil }
                return PortfolioDecimal.multiply(value, rate)
            }()
            return TaxIncomeRow(date: date, coin: PortfolioCalculator.normalizeCoin(t.coin), amount: t.amountExact,
                                valueUsdt: value, valueTarget: target, fxMissing: value != nil && rate == nil,
                                interest: TaxEngines.interestReward(t.note))
        }
    }

    /// Summe der bekannten Werte; nil, wenn keiner bekannt ist (und es Zeilen gibt).
    private static func sum<T>(_ rows: [T], _ pick: (T) -> Decimal?) -> Decimal? {
        let known = rows.compactMap(pick)
        if known.isEmpty { return rows.isEmpty ? 0 : nil }
        return known.reduce(Decimal(0), +)
    }

    /// CSV «Realisierte Gewinne» mit BOM; `header` = Kopfzeilen (je ein Feld), danach die Zeitzone (`texts.timeZone`).
    /// Summe nur über vollständige Zeilen (Einstand, Erlös und Gewinn bekannt — je für USDT und Anzeigewährung), so
    /// geht Erlös − Einstand = Gewinn auf; Zeilen mit unbekanntem Einstand summiert die Zeile «Erlös ohne Kaufpreis».
    /// Zusatzspalten vor «Hinweis» nur, wenn eine Zeile sie hat (CA nicht abziehbarer Verlust; FR Preis vor Gebühren und
    /// Gesamtwert). Textfelder gegen Formel-Injektion geschützt, Zahlen unverändert — wie Android.
    static func gainsCsv(_ rows: [TaxGainRow], texts: TaxCsvTexts, header: [String],
                         format: CutoffCsvFormat = .semicolonPoint,
                         lostFeeCosts: [PortfolioLostFeeCost] = [], timeZone: TimeZone = TimeZone(identifier: "UTC")!,
                         unassignedFees: [PortfolioUnassignedFee] = []) -> String {
        func n(_ text: String) -> String {
            format.decimal == "," ? text.replacingOccurrences(of: ".", with: ",") : text
        }
        func money(_ v: Decimal?) -> CsvCell { CsvCell(text: n(v.map { CutoffExport.money($0) } ?? ""), numeric: true) }
        func t(_ v: String) -> CsvCell { CsvCell(text: v) }
        let blank = CsvCell(text: "", numeric: true)
        let adj = rows.contains { $0.adjustmentUsdt != nil }
        let fr = rows.contains { $0.portfolioValueUsdt != nil || $0.grossProceedsUsdt != nil }
        /// Zusatzspalten einer Zeile (leer, wenn es sie nicht gibt).
        func extra(_ a: Decimal?, _ aT: Decimal?, _ g: Decimal?, _ gT: Decimal?, _ v: Decimal?, _ vT: Decimal?) -> [CsvCell] {
            var out: [CsvCell] = []
            if adj { out += [money(a), money(aT)] }
            if fr { out += [money(g), money(gT), money(v), money(vT)] }
            return out
        }
        var out = bom
        for h in header { out += line([t(h)], format) }
        if !texts.timeZone.isEmpty { out += line([t(texts.timeZone)], format) }
        var head: [CsvCell] = [texts.coin, texts.acquired, texts.sold, texts.holdingDays, texts.longTerm, texts.amount,
                               texts.costUsdt, texts.proceedsUsdt, texts.gainUsdt,
                               texts.costTarget, texts.proceedsTarget, texts.gainTarget].map { t($0) }
        if adj { head += [t(texts.adjustmentUsdt), t(texts.adjustmentTarget)] }
        if fr { head += [t(texts.grossUsdt), t(texts.grossTarget), t(texts.portfolioValueUsdt), t(texts.portfolioValueTarget)] }
        head.append(t(texts.note))
        out += line(head, format)
        for r in rows {
            var notes: [String] = []
            let expenseText: String?
            switch r.feeExpense {
            case .some(.CONSUMED): expenseText = texts.feeConsumed
            case .some(.WITHDRAWAL):
                expenseText = r.feeInfoOnly && !texts.withdrawalFeeInfo.isEmpty ? texts.withdrawalFeeInfo : texts.withdrawalFee
            case .some(.TRANSFER): expenseText = texts.transferFeeInfo
            case .none: expenseText = nil
            }
            if let expenseText, !expenseText.isEmpty { notes.append(expenseText) }
            if r.feeRow && !texts.feeRow.isEmpty { notes.append(texts.feeRow) }
            if r.gift && !texts.gift.isEmpty { notes.append(texts.gift) }
            if r.spouseGift && !texts.spouseGift.isEmpty { notes.append(texts.spouseGift) }
            if r.payment && !texts.payment.isEmpty { notes.append(texts.payment) }
            if r.valueEstimated && !texts.valueEstimated.isEmpty { notes.append(texts.valueEstimated) }
            let matchText: String?
            switch r.match {
            case .some(.SAME_DAY): matchText = texts.matchSameDay
            case .some(.THIRTY_DAY): matchText = texts.matchThirtyDay
            case .some(.POOL): matchText = texts.matchPool
            case .none: matchText = nil
            }
            if let matchText, !matchText.isEmpty { notes.append(matchText) }
            if let old = r.oldHolding {
                let text = old ? texts.oldHolding : texts.newHolding
                if !text.isEmpty { notes.append(text) }
            }
            if r.swap && !texts.swap.isEmpty { notes.append(texts.swap) }
            if let w = r.wallet, !texts.wallet.isEmpty {
                notes.append(texts.wallet.replacingOccurrences(of: "%s", with: w.isEmpty ? texts.walletUnspecified : w))
            }
            if r.feeLeg { notes.append(texts.feeLeg) }
            if r.costMissing { notes.append(texts.costMissing) }
            if r.uncovered && !texts.oversold.isEmpty { notes.append(texts.oversold) }
            if r.proceedsMissing { notes.append(texts.proceedsMissing) }
            if r.feeValueMissing { notes.append(texts.feeValueMissing) }
            if r.costIncomplete && !texts.costIncomplete.isEmpty { notes.append(texts.costIncomplete) }
            if r.adjustmentUsdt != nil && !texts.adjustment.isEmpty { notes.append(texts.adjustment) }
            if r.pooled && r.costTarget != nil { notes.append(texts.avgCostRates) }
            if r.fxMissing { notes.append(texts.noFx) }
            let longTerm: String
            switch r.longTerm {
            case .some(true): longTerm = texts.yes
            case .some(false): longTerm = texts.no
            case .none: longTerm = ""
            }
            var cells: [CsvCell] = [
                t(r.coin),
                t(r.acquiredDate?.description ?? ""),
                t(r.soldDate.description),
                CsvCell(text: r.holdingDays.map { String($0) } ?? "", numeric: true),
                t(longTerm),
                CsvCell(text: n(CutoffExport.amount(r.amount)), numeric: true),
                money(r.costUsdt), money(r.proceedsUsdt), money(r.gainUsdt),
                money(r.costTarget), money(r.proceedsTarget), money(r.gainTarget),
            ]
            cells += extra(r.adjustmentUsdt, r.adjustmentTarget, r.grossProceedsUsdt, r.grossProceedsTarget,
                           r.portfolioValueUsdt, r.portfolioValueTarget)
            cells.append(t(notes.joined(separator: ", ")))
            out += line(cells, format)
        }
        // Summe nur vollständiger Zeilen; fehlt irgendwo etwas, «unvollständig». Gebühren nur zur Information
        // (`TaxGainRow.feeInfoOnly`) zählen nicht mit
        let counted = rows.filter { !$0.feeInfoOnly }
        let incomplete = counted.contains {
            $0.costMissing || $0.proceedsMissing || $0.feeValueMissing || $0.fxMissing || $0.costIncomplete
        }
        let usdt = counted.filter(\.completeUsdt)
        let target = counted.filter(\.completeTarget)
        func total(_ list: [TaxGainRow], _ pick: (TaxGainRow) -> Decimal?) -> Decimal? {
            list.isEmpty && !counted.isEmpty ? nil : sum(list, pick)
        }
        var totalCells: [CsvCell] = [
            t(texts.total), t(""), t(""), blank, t(""), blank,
            money(total(usdt) { $0.costUsdt }), money(total(usdt) { $0.proceedsUsdt }), money(total(usdt) { $0.gainUsdt }),
            money(total(target) { $0.costTarget }), money(total(target) { $0.proceedsTarget }),
            money(total(target) { $0.gainTarget }),
        ]
        totalCells += extra(sum(counted) { $0.adjustmentUsdt }, sum(counted) { $0.adjustmentTarget },
                            sum(counted) { $0.grossProceedsUsdt }, sum(counted) { $0.grossProceedsTarget }, nil, nil)
        totalCells.append(t(incomplete ? texts.incomplete : ""))
        out += line(totalCells, format)
        // Erlös der Zeilen ohne Kaufpreis (nicht in der Summe oben)
        let noCost = counted.filter { $0.costMissing && $0.proceedsUsdt != nil }
        if !noCost.isEmpty {
            var cells: [CsvCell] = [
                t(texts.proceedsWithoutCost), t(""), t(""), blank, t(""), blank,
                blank, money(sum(noCost) { $0.proceedsUsdt }), blank,
                blank, money(sum(noCost) { $0.proceedsTarget }), blank,
            ]
            cells += extra(nil, nil, nil, nil, nil, nil)
            cells.append(t(texts.incomplete))
            out += line(cells, format)
        }
        // Netzwerkgebühr-Einstand ohne bezahlten Posten: kein Gewinn/Verlust, aber nicht still verloren
        if !texts.lostFeeCost.isEmpty {
            for l in lostFeeCosts {
                var cells: [CsvCell] = [
                    t(l.coin), t(""), t(localDay(l.time, timeZone: timeZone).description), blank, t(""), blank,
                    money(l.costUsdt), blank, blank, blank, blank, blank,
                ]
                cells += extra(nil, nil, nil, nil, nil, nil)
                cells.append(t(texts.lostFeeCost))
                out += line(cells, format)
            }
        }
        // Umbuchungsgebühr auf Bestand ohne Kaufpreis: offener Posten, sichtbar statt still verworfen
        if !texts.unassignedFee.isEmpty {
            for f in unassignedFees {
                var cells: [CsvCell] = [
                    t(f.coin), t(""), t(localDay(f.time, timeZone: timeZone).description), blank, t(""), blank,
                    money(f.usdt), blank, blank, blank, blank, blank,
                ]
                cells += extra(nil, nil, nil, nil, nil, nil)
                cells.append(t(texts.unassignedFee))
                out += line(cells, format)
            }
        }
        return out
    }

    /// CSV «Einkünfte» mit BOM; `header` = Kopfzeilen (je ein Feld).
    static func incomeCsv(_ rows: [TaxIncomeRow], texts: TaxCsvTexts, header: [String],
                          format: CutoffCsvFormat = .semicolonPoint) -> String {
        func n(_ text: String) -> String {
            format.decimal == "," ? text.replacingOccurrences(of: ".", with: ",") : text
        }
        func money(_ v: Decimal?) -> CsvCell { CsvCell(text: n(v.map { CutoffExport.money($0) } ?? ""), numeric: true) }
        func t(_ v: String) -> CsvCell { CsvCell(text: v) }
        var out = bom
        for h in header { out += line([t(h)], format) }
        if !texts.timeZone.isEmpty { out += line([t(texts.timeZone)], format) }
        out += line([texts.date, texts.coin, texts.amount, texts.valueUsdt, texts.valueTarget, texts.note].map { t($0) }, format)
        for r in rows {
            var notes: [String] = []
            if r.valueMissing { notes.append(texts.valueMissing) }
            if r.fxMissing { notes.append(texts.noFx) }
            out += line([t(r.date.description), t(r.coin), CsvCell(text: n(CutoffExport.amount(r.amount)), numeric: true),
                         money(r.valueUsdt), money(r.valueTarget), t(notes.joined(separator: ", "))], format)
        }
        let incomplete = rows.contains { $0.valueMissing || $0.fxMissing }
        out += line([t(texts.total), t(""), CsvCell(text: "", numeric: true), money(sum(rows) { $0.valueUsdt }),
                     money(sum(rows) { $0.valueTarget }), t(incomplete ? texts.incomplete : "")], format)
        return out
    }

    /// Jahresübersicht Deutschland — ORIENTIERUNG, KEINE STEUERBERATUNG; Regeln wie `deYearSummary` (Android): kurzfristig
    /// (≤ 1 Jahr) Gewinne/Verluste und netto, langfristig (> 1 Jahr, in DE steuerfrei), ohne Haltedauer (Ø, ungedeckt);
    /// Freigrenzen § 23 (netto kurzfristig in EUR < Grenze) und § 22 Nr. 3 (Belohnungen < 256 €). `eurRates` = USD → EUR
    /// je Tag; nil = keine EUR-Werte (Prüfung offen).
    static func deYearSummary(_ trades: [PortfolioTx], method: CostMethod, year: Int, timeZone: TimeZone,
                              eurRates: [Int: Decimal]?) -> TaxYearSummaryDe {
        let eur = eurRates != nil
        let all = gainRows(trades, method: method, year: year, timeZone: timeZone, currency: "EUR", rates: eurRates ?? [:])
        // Auszahlungsgebühren: keine Veräusserung — eigener Posten, nicht in § 23 (Summe ≤ 1 Jahr, Freigrenze)
        let feeRows = all.filter { $0.feeExpense == .WITHDRAWAL }
        let rows = all.filter { $0.feeExpense != .WITHDRAWAL }
        let feeUsdt: Decimal = feeRows.reduce(Decimal(0)) { $0 + ($1.gainUsdt ?? 0) }
        let feeEur: Decimal = feeRows.reduce(Decimal(0)) { $0 + ($1.gainTarget ?? 0) }
        var sg: Decimal = 0, sl: Decimal = 0, lg: Decimal = 0, ll: Decimal = 0, un: Decimal = 0
        var sgE: Decimal = 0, slE: Decimal = 0, lgE: Decimal = 0, llE: Decimal = 0, unE: Decimal = 0
        var unknownGain = 0
        var unclassified = 0
        var fxMissing = 0
        for r in rows {
            let g = r.gainUsdt
            let gE = r.gainTarget
            switch r.longTerm {
            case .some(true):
                if let g { if g > 0 { lg += g } else { ll += g } }
                if let gE { if gE > 0 { lgE += gE } else { llE += gE } }
            case .some(false):
                if let g { if g > 0 { sg += g } else { sl += g } } else { unknownGain += 1 }
                if let gE {
                    if gE > 0 { sgE += gE } else { slE += gE }
                } else if g != nil && eur {
                    fxMissing += 1
                }
            case .none:
                unclassified += 1
                if let g { un += g } else { unknownGain += 1 }
                if let gE { unE += gE } else if g != nil && eur { fxMissing += 1 }
            }
        }
        let netE = sgE + slE
        let limit = privateSalesLimitEur(year)
        let salesStatus: TaxLimitStatus
        if !eur || unknownGain > 0 || unclassified > 0 || fxMissing > 0 {
            salesStatus = .unknown
        } else if netE < 0 {
            // Netto-Verlust: nicht «unter der Freigrenze», sondern eigener Status (nur mit § 23-Gewinnen verrechenbar)
            salesStatus = .loss
        } else {
            salesStatus = netE < limit ? .below : .reached
        }
        let income = incomeRows(trades, year: year, timeZone: timeZone, currency: "EUR", rates: eurRates ?? [:])
        let incomeUsdt = income.reduce(Decimal(0)) { $0 + ($1.valueUsdt ?? 0) }
        let incomeEur = income.reduce(Decimal(0)) { $0 + ($1.valueTarget ?? 0) }
        let valueMissing = income.filter(\.valueMissing).count
        let incomeFx = eur ? income.filter(\.fxMissing).count : 0
        let incomeStatus: TaxLimitStatus
        if !eur || valueMissing > 0 || incomeFx > 0 {
            incomeStatus = .unknown
        } else {
            incomeStatus = incomeEur < incomeLimitEur ? .below : .reached
        }
        return TaxYearSummaryDe(
            year: year, method: method,
            shortGainsUsdt: sg, shortLossesUsdt: sl, netShortUsdt: sg + sl,
            longGainsUsdt: lg, longLossesUsdt: ll, unclassifiedUsdt: un,
            eurAvailable: eur,
            shortGainsEur: eur ? sgE : nil, shortLossesEur: eur ? slE : nil, netShortEur: eur ? netE : nil,
            longGainsEur: eur ? lgE : nil, longLossesEur: eur ? llE : nil, unclassifiedEur: eur ? unE : nil,
            unknownGainCount: unknownGain, unclassifiedCount: unclassified, eurFxMissingCount: fxMissing,
            privateSalesLimitEur: limit, privateSalesStatus: salesStatus,
            incomeUsdt: incomeUsdt, incomeEur: eur ? incomeEur : nil,
            incomeValueMissingCount: valueMissing, incomeFxMissingCount: incomeFx,
            incomeLimitEur: incomeLimitEur, incomeStatus: incomeStatus,
            withdrawalFeesUsdt: feeUsdt, withdrawalFeesEur: eur ? feeEur : nil, withdrawalFeeCount: feeRows.count)
    }

    /// CSV «Jahresübersicht DE» mit BOM — wie `deSummaryCsv` (Android): Kopfzeilen, Titel, Hinweis «Orientierung, keine
    /// Steuerberatung», Zeitzone (`zoneLine`, leer = keine), dann Posten | USDT | EUR | Hinweis. Zuerst Tatsachen (Summen,
    /// Einkünfte), dann der Orientierungs-Block (Kopf `orientationHeading`, Freigrenzen mit Vorsatz).
    static func deSummaryCsv(_ s: TaxYearSummaryDe, texts: TaxDeTexts, header: [String], zoneLine: String = "",
                             format: CutoffCsvFormat = .semicolonPoint) -> String {
        func n(_ text: String) -> String {
            format.decimal == "," ? text.replacingOccurrences(of: ".", with: ",") : text
        }
        func money(_ v: Decimal?) -> String { n(v.map { CutoffExport.money($0) } ?? "") }
        func status(_ st: TaxLimitStatus) -> String {
            switch st {
            case .below: return texts.below
            case .reached: return texts.reached
            case .unknown: return texts.unknown
            case .loss: return texts.loss.isEmpty ? texts.below : texts.loss
            }
        }
        var out = bom
        for h in header { out += textLine([h], format) }
        out += textLine([texts.title], format)
        out += textLine([texts.disclaimer], format)
        if !zoneLine.isEmpty { out += textLine([zoneLine], format) }
        out += textLine([texts.item, texts.usdt, texts.eur, texts.note], format)
        let shortIncomplete = s.unknownGainCount > 0 || s.eurFxMissingCount > 0
        out += textLine([texts.shortGains, money(s.shortGainsUsdt), money(s.shortGainsEur), ""], format, numeric: [1, 2])
        out += textLine([texts.shortLosses, money(s.shortLossesUsdt), money(s.shortLossesEur), ""], format, numeric: [1, 2])
        var netNotes: [String] = []
        if s.unknownGainCount > 0 { netNotes.append(texts.incomplete) }
        if s.eurFxMissingCount > 0 { netNotes.append(texts.noFx) }
        out += textLine([texts.netShort, money(s.netShortUsdt), money(s.netShortEur), netNotes.joined(separator: ", ")], format, numeric: [1, 2])
        out += textLine([texts.longGains, money(s.longGainsUsdt), money(s.longGainsEur), ""], format, numeric: [1, 2])
        out += textLine([texts.longLosses, money(s.longLossesUsdt), money(s.longLossesEur), ""], format, numeric: [1, 2])
        if s.unclassifiedCount > 0 {
            out += textLine([texts.unclassified, money(s.unclassifiedUsdt), money(s.unclassifiedEur), texts.needsHolding], format, numeric: [1, 2])
        }
        // Auszahlungsgebühren: eigener Posten, nicht mit § 23 verrechnet
        if s.withdrawalFeeCount > 0 && !texts.withdrawalFees.isEmpty {
            out += textLine([texts.withdrawalFees, money(s.withdrawalFeesUsdt), money(s.withdrawalFeesEur), texts.withdrawalFeeNote],
                            format, numeric: [1, 2])
        }
        var incomeNotes: [String] = []
        if s.incomeValueMissingCount > 0 { incomeNotes.append(texts.incomplete) }
        if s.incomeFxMissingCount > 0 { incomeNotes.append(texts.noFx) }
        out += textLine([texts.income, money(s.incomeUsdt), money(s.incomeEur), incomeNotes.joined(separator: ", ")], format, numeric: [1, 2])
        // Freigrenzen nur im Orientierungs-Block (Kopf, jeder Posten mit Vorsatz «Orientierung:»), nie bei den Tatsachen
        func oriented(_ label: String) -> String { taxOrientationLabel(texts.orientationItem, fallback: "", label: label) }
        out += textLine([texts.orientationHeading.isEmpty ? texts.disclaimer : texts.orientationHeading], format)
        var salesNotes = [status(s.privateSalesStatus)]
        if !s.eurAvailable { salesNotes.append(texts.needsEur) }
        if s.unclassifiedCount > 0 && s.eurAvailable { salesNotes.append(texts.needsHolding) }
        if shortIncomplete && s.eurAvailable && s.unclassifiedCount == 0 { salesNotes.append(texts.incomplete) }
        out += textLine([oriented(texts.privateSalesLimit), "", money(s.privateSalesLimitEur), salesNotes.joined(separator: ", ")],
                        format, numeric: [1, 2])
        var limitNotes = [status(s.incomeStatus)]
        if !s.eurAvailable { limitNotes.append(texts.needsEur) }
        out += textLine([oriented(texts.incomeLimit), "", money(s.incomeLimitEur), limitNotes.joined(separator: ", ")],
                        format, numeric: [1, 2])
        if !texts.limitScope.isEmpty { out += textLine([oriented(texts.limitScope)], format) }
        return out
    }

    /// Summe eines Teils der Zeilen: USDT, Steuerwährung, Anzahl ohne Gewinn, Anzahl mit Gewinn aus unvollständigem
    /// Einstand (FR: Erwerbspreis-Summe nicht gesichert, `TaxGainRow.costIncomplete`), Anzahl ohne Devisenkurs.
    private struct Acc {
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

    /// Jahresübersicht der Vorlage `template` für das Steuerjahr ab `startYear` — ORIENTIERUNG, KEINE STEUERBERATUNG;
    /// Posten und Regeln wie `yearSummary` (Android).
    static func yearSummary(_ trades: [PortfolioTx], template: TaxTemplate, method: TaxMethod, startYear: Int,
                            timeZone: TimeZone, currency: String, rates: [Int: Decimal]?,
                            priceAt: ((String, Int64) -> Decimal?)? = nil,
                            walletFrom: LocalDay? = nil) -> TaxYearSummary {
        let period = template.period(startYear)
        let fx = rates != nil || currency.trimmingCharacters(in: .whitespaces).uppercased() == "USD"
        let allRows = gainRows(trades, method: method, period: period, timeZone: timeZone, currency: currency,
                               rates: rates ?? [:], priceAt: priceAt, rules: template.eventRules,
                               walletFrom: walletFrom)
        // DE/AT: Auszahlungsgebühren sind keine Veräusserung — eigener Posten, nicht in den Summen (DE: § 23 und Freigrenze;
        // AT: Aufwendungen beim Sondersteuersatz nicht abzugsfähig, § 20 Abs. 2 EStG) — wie Android
        // UK, US, CA, AU, JP: kein Verlust, nur Information (`TaxTemplate.quoteFeeIsInfo`) — wie Android
        let feeApart = template == .DE || template == .AT || template.quoteFeeIsInfo
        let withdrawalFees = feeApart ? allRows.filter { $0.feeExpense == .WITHDRAWAL } : []
        let sales = feeApart ? allRows.filter { $0.feeExpense != .WITHDRAWAL } : allRows
        // Umbuchungsgebühren nur zur Information (`.TRANSFER`, Build 57): eigener Posten — wie Android
        let transferFees = sales.filter { $0.feeExpense == .TRANSFER }
        let rows = sales.filter { $0.feeExpense != .TRANSFER }
        // AT: Zinsen eigener Posten (beim Zufluss Einkunft), übrige Belohnungen bleiben «Einkünfte»
        let allIncome = incomeRows(trades, period: period, timeZone: timeZone, currency: currency, rates: rates ?? [:])
        let interest = template == .AT ? allIncome.filter(\.interest) : []
        let income = template == .AT ? allIncome.filter { !$0.interest } : allIncome
        let pos: (Decimal) -> Bool = { $0 > 0 }
        let neg: (Decimal) -> Bool = { $0 <= 0 }
        let all: (Decimal) -> Bool = { _ in true }
        var lines: [TaxSummaryLine] = []
        let net: Acc
        let short = rows.filter { $0.longTerm == false }
        let long = rows.filter { $0.longTerm == true }
        let unclassifiedRows = rows.filter { $0.longTerm == nil }
        if template.holdingBuckets {
            lines.append(Acc(short, pos, fx: fx).line(.SHORT_GAINS, fx: fx, countUnknown: false))
            lines.append(Acc(short, neg, fx: fx).line(.SHORT_LOSSES, fx: fx, countUnknown: false))
            lines.append(Acc(short, all, fx: fx).line(.SHORT_NET, fx: fx))
            lines.append(Acc(long, pos, fx: fx).line(.LONG_GAINS, fx: fx, countUnknown: false))
            lines.append(Acc(long, neg, fx: fx).line(.LONG_LOSSES, fx: fx, countUnknown: false))
            lines.append(Acc(long, all, fx: fx).line(.LONG_NET, fx: fx))
            if !unclassifiedRows.isEmpty { lines.append(Acc(unclassifiedRows, all, fx: fx).line(.UNCLASSIFIED, fx: fx)) }
            net = Acc(rows, all, fx: fx)
        } else if template == .AT {
            let newRows = rows.filter { $0.oldHolding != true }
            let old = rows.filter { $0.oldHolding == true }
            net = Acc(newRows, all, fx: fx)
            lines.append(Acc(newRows, pos, fx: fx).line(.GAINS, fx: fx, countUnknown: false))
            lines.append(Acc(newRows, neg, fx: fx).line(.LOSSES, fx: fx, countUnknown: false))
            // Verkäufe/Tausch von Neuvermögen 1.3.2021–28.2.2022: alte Rechtslage mit Option auf 27,5 % — Vermerk am Posten
            let transition = newRows.contains {
                $0.soldDate >= TaxTemplate.atNewFrom && $0.soldDate < TaxTemplate.atSwapNeutralFrom
            }
            lines.append(net.line(.NEW_NET, fx: fx, note: transition ? .AT_TRANSITION_2021 : nil))
            if !old.isEmpty {
                lines.append(Acc(old.filter { $0.longTerm != true }, all, fx: fx).line(.OLD_SHORT_NET, fx: fx))
                lines.append(Acc(old.filter { $0.longTerm == true }, all, fx: fx).line(.OLD_LONG_NET, fx: fx))
            }
        } else {
            net = Acc(rows, all, fx: fx)
            // CH: die App ordnet Kapitalgewinne nicht ein — die FIFO-Summen sind nur Information, keine Tatsache der
            // Veranlagung (Vermerk wie «nicht gerechnet – siehe ESTV»)
            let info: TaxSummaryNote? = template == .CH ? .CH_FIFO_INFO : nil
            lines.append(Acc(rows, pos, fx: fx).line(.GAINS, fx: fx, countUnknown: false, note: info))
            lines.append(Acc(rows, neg, fx: fx).line(.LOSSES, fx: fx, countUnknown: false, note: info))
            lines.append(net.line(.NET, fx: fx, note: info))
        }
        if !withdrawalFees.isEmpty {
            let note: TaxSummaryNote
            switch template {
            case .AT: note = .AT_WITHDRAWAL_FEE
            case .DE: note = .DE_WITHDRAWAL_FEE
            case .NEUTRAL, .CH, .UK, .US, .CA, .AU, .FR, .JP: note = .WITHDRAWAL_FEE_INFO
            }
            lines.append(Acc(withdrawalFees, all, fx: fx).line(.WITHDRAWAL_FEES, fx: fx, note: note))
        }
        if !transferFees.isEmpty {
            lines.append(Acc(transferFees, all, fx: fx).line(.TRANSFER_FEES, fx: fx, note: .TRANSFER_FEE_INFO))
        }
        // CA: nicht abziehbare Verluste
        let adjRows = rows.filter { $0.adjustmentUsdt != nil }
        let deniedUsdt: Decimal = adjRows.reduce(Decimal(0)) { $0 + ($1.adjustmentUsdt ?? 0) }
        let deniedTarget: Decimal = adjRows.reduce(Decimal(0)) { $0 + ($1.adjustmentTarget ?? 0) }
        let deniedFx = fx && adjRows.contains { $0.adjustmentTarget == nil }
        if !adjRows.isEmpty {
            lines.append(TaxSummaryLine(line: .DENIED_LOSS, usdt: deniedUsdt, target: fx ? deniedTarget : nil, noFx: deniedFx))
        }
        // Erlöse (FR: vor Gebühren); ohne Schenkungen an Ehe-/Lebenspartner — ihr «Erlös» ist nur der übergehende Einstand,
        // kein Verkaufserlös (UK s. 58, CA 73(1))
        let grossFr = template == .FR
        let sold = rows.filter { !$0.spouseGift }
        func procU(_ r: TaxGainRow) -> Decimal? { grossFr ? r.grossProceedsUsdt : r.proceedsUsdt }
        func procT(_ r: TaxGainRow) -> Decimal? { grossFr ? r.grossProceedsTarget : r.proceedsTarget }
        let procUsdt: Decimal = sold.reduce(Decimal(0)) { $0 + (procU($1) ?? 0) }
        let procTarget: Decimal = sold.reduce(Decimal(0)) { $0 + (procT($1) ?? 0) }
        let procMissing = sold.contains { procU($0) == nil }
        let procFx = fx && sold.contains { procU($0) != nil && procT($0) == nil }
        lines.append(TaxSummaryLine(line: .PROCEEDS, usdt: procUsdt, target: fx ? procTarget : nil,
                                    incomplete: procMissing, noFx: procFx))
        // Einkünfte
        let incUsdt: Decimal = income.reduce(Decimal(0)) { $0 + ($1.valueUsdt ?? 0) }
        let incTarget: Decimal = income.reduce(Decimal(0)) { $0 + ($1.valueTarget ?? 0) }
        let incMissing = income.contains { $0.valueMissing }
        let incFx = fx && income.contains { $0.fxMissing }
        // AT: Belohnungen im Neubestand mit Anschaffungskosten 0 (kein Zufluss besteuert, erst der Verkauf) — der Posten ist
        // nur Information und zählt in keine Summe (sonst doppelt: Zufluss + Verkaufsgewinn mit AK 0)
        lines.append(TaxSummaryLine(line: .INCOME, usdt: incUsdt, target: fx ? incTarget : nil,
                                    incomplete: incMissing, noFx: incFx,
                                    note: template == .AT ? TaxSummaryNote.AT_REWARD_INFO : nil))
        // AT: Zinsen aus Lending beim Zufluss (§ 27b Abs. 2 Z 1 EStG, 27,5 %) — zählen, Anschaffungskosten = dieser Wert
        let intUsdt: Decimal = interest.reduce(Decimal(0)) { $0 + ($1.valueUsdt ?? 0) }
        let intTarget: Decimal = interest.reduce(Decimal(0)) { $0 + ($1.valueTarget ?? 0) }
        let intMissing = interest.contains { $0.valueMissing }
        let intFx = fx && interest.contains { $0.fxMissing }
        if !interest.isEmpty {
            lines.append(TaxSummaryLine(line: .AT_INTEREST, usdt: intUsdt, target: fx ? intTarget : nil,
                                        incomplete: intMissing, noFx: intFx, note: .AT_INTEREST_INCOME))
        }
        // FR: Abgänge ohne Verkauf im Jahr — Erwerbspreis-Summe gekürzt (BOFiP § 100); das Jahr ist «unvollständig»
        if template == .FR {
            // Mit Adress-Einträgen: der Gesamtwert vor dem Abgang zählt beobachtete Adressen mit
            let outs = TaxEngines.outflows(trades.filter { $0.amountExact > 0 }, method: method, priceAt: priceAt)
                .filter { period.contains(localDay($0.time, timeZone: timeZone)) }
            if !outs.isEmpty {
                var outUsdt: Decimal = 0
                var outTarget: Decimal = 0
                var outFx = false
                for o in outs {
                    guard let r = o.ptaReductionUsdt else { continue }
                    outUsdt += r
                    if let rate = rateOn(rates, date: localDay(o.time, timeZone: timeZone), currency: currency) {
                        outTarget += PortfolioDecimal.multiply(r, rate)
                    } else if fx {
                        outFx = true
                    }
                }
                lines.append(TaxSummaryLine(line: .FR_OUTFLOW, usdt: outUsdt, target: fx ? outTarget : nil,
                                            incomplete: true, noFx: outFx))
            }
        }
        let netIncomplete = net.incomplete
        let netFx = net.fxMissing > 0
        // Orientierung je Vorlage
        switch template {
        case .DE:
            lines.append(Acc(long, all, fx: fx).line(.DE_LONG, fx: fx))
            let sh = Acc(short, all, fx: fx)
            let limit = privateSalesLimitEur(startYear)
            let salesStatus: TaxLimitStatus
            if !fx || sh.unknown > 0 || !unclassifiedRows.isEmpty || sh.fxMissing > 0 {
                salesStatus = .unknown
            } else if sh.target < 0 {
                // Netto-Verlust: eigener Status (nur mit Gewinnen aus privaten Veräusserungsgeschäften verrechenbar)
                salesStatus = .loss
            } else {
                salesStatus = sh.target < limit ? .below : .reached
            }
            lines.append(TaxSummaryLine(line: .DE_PRIVATE_LIMIT, usdt: nil, target: limit, status: salesStatus))
            let incomeStatus: TaxLimitStatus
            if !fx || incMissing || incFx {
                incomeStatus = .unknown
            } else {
                incomeStatus = incTarget < incomeLimitEur ? .below : .reached
            }
            lines.append(TaxSummaryLine(line: .DE_INCOME_LIMIT, usdt: nil, target: incomeLimitEur, status: incomeStatus))
        case .AT:
            // Sondersteuersatz 27,5 % auf Neubestand netto + Zinsen (beide Einkünfte aus Kryptowährungen, § 27b EStG;
            // Gebühren darin nicht abgezogen, § 20 Abs. 2 EStG)
            lines.append(TaxSummaryLine(line: .AT_RATE, usdt: net.usdt + intUsdt, target: fx ? net.target + intTarget : nil,
                                        incomplete: netIncomplete || intMissing, noFx: netFx || intFx))
            if rows.contains(where: { $0.oldHolding == true }) {
                let oldLong = rows.filter { $0.oldHolding == true && $0.longTerm == true }
                lines.append(Acc(oldLong, all, fx: fx).line(.AT_OLD_LONG, fx: fx))
            }
        case .CH:
            // Kapitalgewinne im Privatvermögen: kein Betrag, keine Einordnung — «nicht gerechnet, siehe ESTV»
            lines.append(TaxSummaryLine(line: .CH_PRIVATE))
            lines.append(TaxSummaryLine(line: .CH_INCOME, usdt: incUsdt, target: fx ? incTarget : nil,
                                        incomplete: incMissing, noFx: incFx))
            // Vermögenssteuer: kein Betrag (Steuerwerte der ESTV-Kursliste) — ausdrücklich «nicht gerechnet»
            lines.append(TaxSummaryLine(line: .CH_WEALTH, notCalculated: true, note: .CH_EXCHANGE_VALUE))
        case .UK:
            let aea = TaxTemplate.ukAnnualExemptGbp(startYear)
            let aeaStatus: TaxLimitStatus? = aea == nil ? TaxLimitStatus.unknown : nil
            lines.append(TaxSummaryLine(line: .UK_EXEMPT, usdt: nil, target: aea, status: aeaStatus))
            var after: Decimal? = nil
            if let aea, fx { after = max(0, net.target - aea) }
            lines.append(TaxSummaryLine(line: .UK_AFTER_EXEMPT, usdt: nil, target: after, incomplete: netIncomplete,
                                        noFx: netFx))
        case .US:
            lines.append(TaxSummaryLine(line: .US_TERMS))
        case .CA:
            let f = TaxTemplate.caInclusion
            let u = PortfolioDecimal.multiply(net.usdt + deniedUsdt, f)
            let tg: Decimal? = fx ? PortfolioDecimal.multiply(net.target + deniedTarget, f) : nil
            lines.append(TaxSummaryLine(line: .CA_TAXABLE, usdt: u, target: tg, incomplete: netIncomplete,
                                        noFx: netFx || deniedFx))
        case .AU:
            // Verluste zuerst gegen Gewinne ohne Rabatt (≤ 12 Monate, ohne Haltedauer), dann gegen die übrigen; 50 % Rabatt
            func discounted(_ pick: (TaxGainRow) -> Decimal?) -> Decimal {
                var nonDisc: Decimal = 0
                var disc: Decimal = 0
                var losses: Decimal = 0
                for r in rows {
                    guard let g = pick(r) else { continue }
                    if g <= 0 {
                        losses -= g
                    } else if r.longTerm == true {
                        disc += g
                    } else {
                        nonDisc += g
                    }
                }
                var rest = nonDisc - losses
                if rest < 0 {
                    disc += rest
                    rest = 0
                }
                if disc < 0 { return disc }
                let factor: Decimal = 1 - TaxTemplate.auDiscount
                return rest + PortfolioDecimal.multiply(disc, factor)
            }
            let u = discounted { $0.gainUsdt }
            let tg: Decimal? = fx ? discounted { $0.gainTarget } : nil
            lines.append(TaxSummaryLine(line: .AU_DISCOUNTED, usdt: u, target: tg, incomplete: netIncomplete, noFx: netFx))
        case .FR:
            let status: TaxLimitStatus
            if !fx || procMissing || procFx {
                status = .unknown
            } else {
                status = procTarget <= TaxTemplate.frExemptEur ? .below : .reached
            }
            lines.append(TaxSummaryLine(line: .FR_THRESHOLD, usdt: nil, target: TaxTemplate.frExemptEur, status: status))
            lines.append(TaxSummaryLine(line: .FR_RATE))
        case .JP:
            lines.append(TaxSummaryLine(line: .JP_MISC, usdt: net.usdt + incUsdt, target: fx ? net.target + incTarget : nil,
                                        incomplete: netIncomplete || incMissing, noFx: netFx || incFx))
        case .NEUTRAL:
            break
        }
        return TaxYearSummary(template: template, method: method, period: period, currency: currency, fxAvailable: fx,
                              lines: lines, walletFrom: walletFrom)
    }

    /// CSV «Jahresübersicht» mit BOM — wie `summaryCsv` (Android): Kopfzeilen, Titel, Hinweis, Zeitzone, dann Posten |
    /// USDT | Währung | Hinweis — zuerst nur Tatsachen, Schlüsse der Vorlage (Sätze, Grenzen, Freibeträge) nur im Block
    /// `texts.orientationHeading`, jeder Posten mit Vorsatz «Orientierung:» (`taxOrientationLabel`).
    static func summaryCsv(_ s: TaxYearSummary, texts: TaxSummaryTexts, header: [String], zoneLine: String = "",
                           format: CutoffCsvFormat = .semicolonPoint) -> String {
        func n(_ text: String) -> String {
            format.decimal == "," ? text.replacingOccurrences(of: ".", with: ",") : text
        }
        func money(_ v: Decimal?) -> String { n(v.map { CutoffExport.money($0) } ?? "") }
        var out = bom
        for h in header { out += textLine([h], format) }
        out += textLine([texts.title], format)
        out += textLine([texts.disclaimer], format)
        if !zoneLine.isEmpty { out += textLine([zoneLine], format) }
        out += textLine([texts.item, texts.usdt, texts.target, texts.note], format)
        func row(_ l: TaxSummaryLine, _ label: String) -> String {
            var notes: [String] = []
            switch l.status {
            case .some(.below): notes.append(texts.below)
            case .some(.reached): notes.append(texts.reached)
            case .some(.unknown): notes.append(texts.unknown)
            case .some(.loss): notes.append(texts.loss.isEmpty ? texts.below : texts.loss)
            case .none: break
            }
            if l.incomplete { notes.append(texts.incomplete) }
            if l.noFx { notes.append(texts.noFx) }
            if l.notCalculated && !texts.notCalculated.isEmpty { notes.append(texts.notCalculated) }
            if let note = l.note, let text = texts.notes[note], !text.isEmpty { notes.append(text) }
            return textLine([label, money(l.usdt), money(l.target), notes.joined(separator: ", ")], format, numeric: [1, 2])
        }
        // Zuerst nur Tatsachen; Schlüsse der Vorlage nur im eigenen Block, jeder Posten mit Vorsatz «Orientierung:»
        let facts = s.lines.filter { !$0.line.orientation }
        let oriented = s.lines.filter { $0.line.orientation }
        for l in facts { out += row(l, texts.labels[l.line] ?? "") }
        if !oriented.isEmpty {
            out += textLine([texts.orientationHeading.isEmpty ? texts.orientation : texts.orientationHeading], format)
            for l in oriented {
                out += row(l, taxOrientationLabel(texts.orientationItem, fallback: texts.orientation, label: texts.labels[l.line] ?? ""))
            }
        }
        return out
    }

    /// Zeile aus typisierten Zellen (Text gegen Formel-Injektion geschützt, Zahlen unverändert).
    private static func line(_ cells: [CsvCell], _ format: CutoffCsvFormat) -> String {
        CutoffExport.joinCells(cells, separator: format.separator) + eol
    }

    /// Zeile aus Texten; Spalten in `numeric` sind Zahlen (z. B. Beträge der Übersicht).
    private static func textLine(_ fields: [String], _ format: CutoffCsvFormat, numeric: Set<Int> = []) -> String {
        line(fields.enumerated().map { CsvCell(text: $0.element, numeric: numeric.contains($0.offset)) }, format)
    }
}

extension TaxCsvTexts {
    /// Zusätze der Steuer-Vorlagen (Zuordnung UK, nicht abziehbarer Verlust CA, Alt-/Neubestand AT, Gesamtwert FR) —
    /// wie `taxCsvTexts` (Android).
    func withTemplateTexts(currency: String) -> TaxCsvTexts {
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
        case .FR_OUTFLOW: return "tax_line_fr_outflow"
        case .WITHDRAWAL_FEES: return "tax_line_withdrawal_fees"
        case .TRANSFER_FEES: return "tax_line_transfer_fees"
        case .DE_LONG: return "tax_line_de_long"
        case .DE_PRIVATE_LIMIT: return "tax_de_private_limit"
        case .DE_INCOME_LIMIT: return "tax_de_income_limit"
        case .AT_RATE: return "tax_line_at_rate"
        case .AT_OLD_LONG: return "tax_line_at_old_long"
        case .CH_PRIVATE: return "tax_line_ch_private"
        case .CH_INCOME: return "tax_line_ch_income"
        case .CH_WEALTH: return "tax_line_ch_wealth"
        case .UK_EXEMPT: return "tax_line_uk_exempt"
        case .UK_AFTER_EXEMPT: return "tax_line_uk_after_exempt"
        case .US_TERMS: return "tax_line_us_terms"
        case .CA_TAXABLE: return "tax_line_ca_taxable"
        case .AU_DISCOUNTED: return "tax_line_au_discounted"
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
        for l in TaxLine.allCases { labels[l] = L(l.labelKey) }
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
                .DE_WITHDRAWAL_FEE: L("tax_summary_de_withdrawal_fee"),
                .AT_WITHDRAWAL_FEE: L("tax_summary_at_withdrawal_fee"),
                .WITHDRAWAL_FEE_INFO: L("tax_summary_withdrawal_fee_info"),
                .TRANSFER_FEE_INFO: L("tax_summary_transfer_fee_info"),
                .AT_INTEREST_INCOME: L("tax_summary_at_interest_income"),
                .AT_TRANSITION_2021: L("tax_summary_at_transition_2021"),
            ]
        )
    }
}

/// Lädt Devisen-Tageskurse (und für FR Tagesschlusskurse) und baut die Dateien der Arbeitsunterlage — wie
/// `PortfolioExportViewModel` (Android).
enum PortfolioTaxExporter {
    /// Devisen-Tageskurse USD → `currency` von `from` bis `to` (höchstens heute); USD = leer; Fehler = nil.
    static func rates(_ currency: String, from: LocalDay, to: LocalDay) async -> [Int: Decimal]? {
        if currency == "USD" { return [:] }
        let today = LocalDay.today()
        let end = to > today ? today : to
        guard let series = await HistoricPriceSource.usdToSeries(currency, from: from.plusDays(-PortfolioHistoryFx.leadDays),
                                                                  to: end) else { return nil }
        var exact: [Int: Decimal] = [:]
        for (day, rate) in series { if let r = PortfolioDecimal.of(rate) { exact[day] = r } }
        return exact
    }

    /// FR: Gesamtwert des Portfolios samt beobachteter Adressen («im Portfolio zählen») — ihre Guthaben-Änderungen als
    /// Adress-Einträge (`AddressBalanceHistory.trades`, negative Id: keine Veräusserungen). Andere Vorlagen: nur die eigenen
    /// Transaktionen. Wie `withAddressTrades` (Android).
    static func withAddressTrades(_ transactions: [PortfolioTx], method: TaxMethod) -> [PortfolioTx] {
        guard method == .FR_PTA else { return transactions }
        return transactions + AddressBalanceHistory.trades(AddressBalanceHistory.of(WatchedAddressStore.load().addresses))
    }

    /// Tageskurse der Vorlage: FR `frPrices`, Netzwerkgebühren als Veräusserung (`TaxTemplate.networkFeeIsDisposal`: UK,
    /// US, CA, AU, JP) `networkFeePrices`; dazu für alle Vorlagen die Gebühr-Zeilen, Verschenkt/bezahlt ohne Marktwert und
    /// Tausch ohne Wert zum Tageskurs (`feeDisposalPrices`) — wie `taxPrices` (Android).
    static func taxPrices(_ template: TaxTemplate, _ method: TaxMethod, _ transactions: [PortfolioTx],
                          period: TaxPeriod) async -> ((String, Int64) -> Decimal?)? {
        let base: ((String, Int64) -> Decimal?)?
        if method == .FR_PTA {
            base = await frPrices(transactions, period: period)
        } else if template.networkFeeIsDisposal {
            base = await networkFeePrices(transactions, period: period)
        } else {
            base = nil
        }
        guard let fees = await feeDisposalPrices(transactions, period: period) else { return base }
        return { coin, time in fees(coin, time) ?? base?(coin, time) }
    }

    /// Gebühr-Zeilen (`PortfolioLedger.isFeeDisposal`, CSV «Fee»), Verschenkt/bezahlt ohne Marktwert und Tausch ohne Wert
    /// (`PortfolioLedger.dayCloseNeeds`) bis Ende `period`: zum Tageskurs — der Coin zum Tagesschluss SEINES UTC-Tags (gleicher
    /// Kurs wie Karte und Wertverlauf); nur für genau diese (Coin, Zeit), sonst nil. Ohne solche nil. Wie Android.
    static func feeDisposalPrices(_ transactions: [PortfolioTx],
                                  period: TaxPeriod) async -> ((String, Int64) -> Decimal?)? {
        let end = CutoffDay(year: period.end.year, month: period.end.month, day: period.end.day)
            .endOfDayMillis(in: TimeZone.current)
        var keys = Set<String>()
        var wanted: [String: Set<Int>] = [:]
        let own = transactions.filter { $0.time <= end && !AddressBalance.isAddressTrade($0.id) }
        for need in PortfolioLedger.dayCloseNeeds(own) {
            keys.insert("\(need.coin)|\(need.time)")
            if PortfolioStables.needsQuote(need.coin) {
                wanted[need.coin, default: []].insert(LocalDay(epochMillisUTC: need.time).epochDay)
            }
        }
        if keys.isEmpty { return nil }
        let closes = await dailyCloses(wanted, dayOffset: 0)
        let found = keys
        return { coin, time in
            guard found.contains("\(coin)|\(time)") else { return nil }
            if !PortfolioStables.needsQuote(coin) { return 1 }
            return closes(coin, time)
        }
    }

    /// Marktwert der Netzwerkgebühren in Krypto (eigene Veräusserung, `TaxEngines.applyRules`) bis Ende `period`: Gebühr
    /// im Coin bzw. im dritten Coin ohne erfassten Gebühr-Preis einer Umbuchung, Auszahlung oder Schenkung — der Coin zum
    /// Tagesschluss des Vortags (UTC). Wie `networkFeePrices` (Android).
    static func networkFeePrices(_ transactions: [PortfolioTx], period: TaxPeriod) async -> (String, Int64) -> Decimal? {
        let end = CutoffDay(year: period.end.year, month: period.end.month, day: period.end.day)
            .endOfDayMillis(in: TimeZone.current)
        var wanted: [String: Set<Int>] = [:]
        for t in transactions where (t.type == .TRANSFER || t.type.isWithdrawalLike) && t.time <= end
            && !AddressBalance.isAddressTrade(t.id) {
            let day = LocalDay(epochMillisUTC: t.time).plusDays(-1).epochDay
            let coin = PortfolioCalculator.normalizeCoin(t.coin)
            if t.coinFeeExact > 0 && t.validPriceExact == nil && PortfolioStables.needsQuote(coin) {
                wanted[coin, default: []].insert(day)
            }
            if let third = t.thirdFeeCoin, t.thirdFeePrice == nil, PortfolioStables.needsQuote(third) {
                wanted[third, default: []].insert(day)
            }
        }
        return await dailyCloses(wanted)
    }

    /// FR: Kurse für den Gesamtwert des Portfolios vor jeder Abtretung bis Ende `period` — je Verkauf, Auszahlung und
    /// Gebühr in Krypto die gehaltenen Coins (ohne den verkauften, ohne Stablecoins) zum Tagesschluss des Vortags (UTC).
    /// Wie `frPrices` (Android).
    static func frPrices(_ transactions: [PortfolioTx], period: TaxPeriod) async -> (String, Int64) -> Decimal? {
        let zone = TimeZone.current
        let end = CutoffDay(year: period.end.year, month: period.end.month, day: period.end.day).endOfDayMillis(in: zone)
        var wanted: [String: Set<Int>] = [:]
        let own = transactions.filter { !AddressBalance.isAddressTrade($0.id) }
        // Beobachtete Adressen zählen im Gesamtwert mit (`TaxEngines` FR): ihre Coins brauchen ebenfalls Kurse
        let addressTrades = transactions.filter { AddressBalance.isAddressTrade($0.id) }
        // Verkäufe, Verschenkt/bezahlt, Auszahlungen (Abgang ohne Verkauf kürzt die PTA anteilig, `TaxEngines.outflows`)
        // und Gebühren in Krypto
        for t in own where t.time <= end {
            let feeInCrypto: Bool = t.thirdFeeCoin != nil || ((t.type == .TRANSFER || t.type == .SWAP) && t.coinFeeExact > 0)
            guard t.type.isSaleLike || t.type.isWithdrawalLike || feeInCrypto else { continue }
            let dayBefore = LocalDay(epochMillisUTC: t.time).plusDays(-1).epochDay
            // Verkauf: Coin zum Verkaufspreis; sonst ohne erfassten Wert: auch der Coin selbst braucht einen Kurs
            let sold: String? = (t.type.isSaleLike || t.validPriceExact != nil) ? PortfolioCalculator.normalizeCoin(t.coin) : nil
            var held = Set(PortfolioCalculator.holdingsAt(own, time: t.time - 1, method: .FIFO).map(\.coin))
            for a in addressTrades where a.time <= t.time { held.insert(PortfolioCalculator.normalizeCoin(a.coin)) }
            for coin in held where coin != sold && PortfolioStables.needsQuote(coin) {
                wanted[coin, default: []].insert(dayBefore)
            }
        }
        return await dailyCloses(wanted)
    }

    /// Tagesschlusskurse (USDT) je Coin und Tag (epochDay); Abfrage über (Coin, Zeit) → Schluss des Tags + `dayOffset`
    /// (Standard −1: Vortag); fehlt = nil.
    private static func dailyCloses(_ wanted: [String: Set<Int>], dayOffset: Int = -1) async -> (String, Int64) -> Decimal? {
        if wanted.isEmpty { return { _, _ in nil } }
        let coins = wanted.keys.sorted()
        let live = await PortfolioPriceSource.prices(coins)
        let current = CutoffExport.referencePrices(live.prices, uncheckedCurrent: live.unchecked)
        var closes: [String: Decimal] = [:]
        for coin in coins {
            for epochDay in (wanted[coin] ?? []).sorted() {
                let d = LocalDay(epochDay: epochDay)
                let r = await HistoricPriceSource.dailyCloseUsdt(coin, day: CutoffDay(year: d.year, month: d.month, day: d.day),
                                                                 current: current[coin])
                if let p = PortfolioDecimal.of(r.price) { closes["\(coin)|\(epochDay)"] = p }
            }
        }
        let found = closes
        return { coin, time in found["\(coin)|\(LocalDay(epochMillisUTC: time).plusDays(dayOffset).epochDay)"] }
    }

    /// Jahresübersicht der Vorlage für das Steuerjahr ab `year` (ohne Devisenkurse: Werte in der Währung leer).
    static func summary(transactions: [PortfolioTx], template: TaxTemplate, method: TaxMethod, year: Int,
                        currency: String, walletFrom: LocalDay? = nil) async -> TaxYearSummary {
        let zone = TimeZone.current
        let period = template.period(year)
        let transactions = withAddressTrades(transactions, method: method)
        let priceAt: ((String, Int64) -> Decimal?)? = await taxPrices(template, method, transactions, period: period)
        let from = TaxExport.fxFrom(transactions, method: method, period: period, timeZone: zone, priceAt: priceAt,
                                    rules: template.eventRules, walletFrom: walletFrom)
        let fx = await rates(currency, from: from, to: period.end)
        return TaxExport.yearSummary(transactions, template: template, method: method, startYear: year, timeZone: zone,
                                     currency: currency, rates: fx, priceAt: priceAt, walletFrom: walletFrom)
    }

    /// CSV «Jahresübersicht» (Orientierung, keine Steuerberatung).
    static func summaryCsv(_ summary: TaxYearSummary, texts: TaxSummaryTexts, header: [String]) -> String {
        TaxExport.summaryCsv(summary, texts: texts, header: header,
                             zoneLine: L("tax_export_year_local_zone", TimeZone.current.identifier), format: CutoffCsvFormat.current)
    }

    static func build(kind: TaxExportKind, transactions: [PortfolioTx], template: TaxTemplate, method: TaxMethod, year: Int,
                      currency: String, texts: TaxCsvTexts, header: [String], walletFrom: LocalDay? = nil) async -> String {
        let zone = TimeZone.current
        let period = template.period(year)
        // Trenn-/Dezimalzeichen wie der Stichtag-Export (Excel der Region)
        let format = CutoffCsvFormat.current
        switch kind {
        case .gains:
            let transactions = withAddressTrades(transactions, method: method)
            let priceAt: ((String, Int64) -> Decimal?)? = await taxPrices(template, method, transactions, period: period)
            // Devisen-Tageskurse ab dem frühesten Kauf-/Herkunftsdatum (Einstand zum Kurs seines Datums)
            let from = TaxExport.fxFrom(transactions, method: method, period: period, timeZone: zone, priceAt: priceAt,
                                    rules: template.eventRules, walletFrom: walletFrom)
            let fx = await rates(currency, from: from, to: period.end)
            let rows = TaxExport.gainRows(transactions, method: method, period: period, timeZone: zone,
                                          currency: currency, rates: fx, priceAt: priceAt,
                                          rules: template.eventRules, walletFrom: walletFrom)
            return TaxExport.gainsCsv(rows, texts: texts, header: header, format: format,
                                      lostFeeCosts: TaxExport.lostFeeCosts(in: transactions, method: method, period: period,
                                                                           timeZone: zone, rules: template.eventRules),
                                      timeZone: zone,
                                      unassignedFees: TaxExport.unassignedFees(in: transactions, method: method, period: period,
                                                                               timeZone: zone, rules: template.eventRules))
        case .income:
            let fx = await rates(currency, from: period.start, to: period.end)
            let rows = TaxExport.incomeRows(transactions, period: period, timeZone: zone, currency: currency, rates: fx)
            return TaxExport.incomeCsv(rows, texts: texts, header: header, format: format)
        }
    }
}
