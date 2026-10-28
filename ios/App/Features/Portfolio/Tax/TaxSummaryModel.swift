import Foundation

// Modell der Jahresübersicht je Steuer-Vorlage (`TaxExport.yearSummary` in TaxExportSummary.swift) — wie
// `TaxSummaryModel.kt` (Android).

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
    /// CH Vermögenswert per 31.12. (`TaxLine.CH_WEALTH_VALUE`): nur Orientierung — massgebend ist die Kursliste der ESTV
    /// (ictax.admin.ch), für Coins ohne Eintrag in der Regel der Schlusskurs der Börse; Vermögenssteuer nicht gerechnet.
    case CH_WEALTH_ORIENTATION
    /// DE Auszahlungsgebühren (`TaxLine.WITHDRAWAL_FEES`): nicht mit § 23 verrechnet — ggf. Werbungskosten prüfen.
    case DE_WITHDRAWAL_FEE
    /// AT Auszahlungsgebühren: nicht mit dem Neubestand verrechnet (§ 20 Abs. 2 EStG: beim Sondersteuersatz nicht abzugsfähig).
    case AT_WITHDRAWAL_FEE
    /// AT Zinsen (`TaxLine.AT_INTEREST`): beim Zufluss laufende Einkünfte aus Kryptowährungen (Entgelt für die Überlassung,
    /// § 27b Abs. 2 Z 1 EStG) zum Sondersteuersatz 27,5 %; die Coins behalten diesen Wert als Anschaffungskosten (BMF).
    case AT_INTEREST_INCOME
    /// AT Zinsen vor dem 1.3.2022 (`TaxLine.AT_INTEREST_OLD`): § 27b EStG gilt erst für Zuflüsse ab dem 1.3.2022 — davor
    /// alte Rechtslage (Tarif), nicht im Sondersteuersatz 27,5 %. Wie Android.
    case AT_INTEREST_OLD_LAW
    /// UK, US, CA, AU, JP Auszahlungsgebühren in USDT/Fiat (`TaxTemplate.quoteFeeIsInfo`): kein Verlust, nur Information
    /// (UK: HMRC CRYPTO22150; sonst keine Regel, die sie abziehbar macht).
    case WITHDRAWAL_FEE_INFO
    /// UK, US, CA, AU, JP Umbuchungsgebühren in USDT/Fiat (`TaxLine.TRANSFER_FEES`): kein Verlust und nicht im
    /// Einstand, nur Information.
    case TRANSFER_FEE_INFO
    /// AT Spekulationsgeschäfte netto (`TaxLine.OLD_SHORT_NET`): darin Verkäufe bzw. Tausch von Neuvermögen vom 1.1. bis
    /// 28.2.2022 (`TaxTemplate.atOptionFrom` bis vor `TaxTemplate.atSwapNeutralFrom`) — auf Antrag schon 27,5 % möglich
    /// (§ 124b Z 384 lit. c EStG); gerechnet ohne Option. Früherer Name beibehalten. Wie Android.
    case AT_TRANSITION_2021
    /// AT Freigrenze Spekulationsgeschäfte (`TaxLine.AT_OLD_SPECULATION_LIMIT`): geprüft ohne Option — die Verkäufe von
    /// Neuvermögen vom 1.1. bis 28.2.2022 zählen mit. Wie Android.
    case AT_WITHOUT_OPTION
    /// AT Sondersteuersatz (`TaxLine.AT_RATE`): Belohnungen erhalten vom 1.3.2021 bis 28.2.2022 mit Anschaffungskosten 0 —
    /// Rechtslage vor dem 1.3.2022 nicht ausdrücklich geklärt, mit Fachperson prüfen. Wie Android.
    case AT_STAKING_ZERO_COST
    /// UK/CA netto (`TaxLine.NET`): 30-Tage-Fenster nach dem Steuerjahr noch offen — Käufe bis `TaxSummaryLine.noteDate`
    /// können die Zahlen noch ändern; Text mit Platzhalter `TaxExport.noteDateToken`. Wie Android.
    case WINDOW_OPEN
    /// UK Erlöse (`TaxLine.PROCEEDS`): Erlöse vor Gebühren über `TaxTemplate.ukReportProceedsGbp` — Meldung an HMRC, falls
    /// für Self Assessment registriert, auch ohne steuerbaren Gewinn. Wie Android.
    case UK_HMRC_REPORT
    /// FR netto (`TaxLine.NET`): mindestens eine Abtretung im Jahr ist mit einem Gesamtwert samt Guthaben beobachteter
    /// Adressen gerechnet (`TaxGainRow.addressValueIncluded`). Sie zählen zu Recht zum Gesamtwert (art. 150 VH bis), haben
    /// aber keinen Einstand in der Erwerbspreis-Summe — der Gewinn ist dadurch eher zu hoch; Einstand als Kauf erfassen.
    /// Wie Android.
    case FR_ADDRESS_NO_COST
    /// FR Gewinne (`TaxLine.GAINS`), wenn bis zum Ende des Jahres Belohnungen erfasst sind (`TaxExport.frRewards`): die
    /// Erwerbspreis-Summe enthält sie zum Wert beim Erhalt — das setzt voraus, dass sie beim Erhalt als Einkommen versteuert
    /// wurden; sonst wäre Erwerbspreis 0 die vorsichtige Lesart. Wie Android.
    case FR_REWARDS_INCOME
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
    /// AT: Zinsen aus Lending mit Zufluss vor dem 1.3.2022 — alte Rechtslage (Tarif), nicht im Sondersteuersatz (`AT_RATE`);
    /// Vermerk `.AT_INTEREST_OLD_LAW`. Wie Android.
    case AT_INTEREST_OLD
    /// FR: Abgänge ohne Verkauf im Jahr — Kürzung der Erwerbspreis-Summe (`TaxOutflow`); das Jahr gilt als unvollständig.
    case FR_OUTFLOW
    /// DE, AT: Gebühren von Auszahlungen — eigener Posten, nicht mit den Veräusserungen verrechnet.
    case WITHDRAWAL_FEES
    /// UK, US, CA, AU, JP: Gebühren USDT/Fiat von Umbuchungen zwischen eigenen Wallets (`.TRANSFER`) — nur
    /// Information, weder Verlust noch Einstand.
    case TRANSFER_FEES
    /// CH: Vermögenswert per 31.12. (`TaxExport.chWealth`) — der Text nennt selbst «(Orientierung)», darum ohne Vorsatz im
    /// Orientierungs-Block; Vermerk `.CH_WEALTH_ORIENTATION`, Werte je Coin in `TaxYearSummary.wealth`. Wie Android.
    case CH_WEALTH_VALUE
    case DE_LONG, DE_PRIVATE_LIMIT, DE_INCOME_LIMIT
    case AT_RATE, AT_OLD_LONG
    /// AT: Altbestand ≤ 1 Jahr = Spekulationsgeschäft nach alter Rechtslage (§ 30 EStG a. F.) — Freigrenze 440 € im
    /// Kalenderjahr (§ 31 Abs. 3 EStG a. F.), Status wie `DE_PRIVATE_LIMIT`. Wie Android.
    case AT_OLD_SPECULATION_LIMIT
    case CH_PRIVATE, CH_INCOME, CH_WEALTH
    case UK_EXEMPT, UK_AFTER_EXEMPT
    case US_TERMS
    case CA_TAXABLE
    case AU_DISCOUNTED
    /// CA, AU: netto ein Kapitalverlust — steuerbar 0, Verlust vorzutragen (CA × 1/2, AU ganz); Betrag positiv. Wie Android.
    case NET_CAPITAL_LOSS
    case FR_THRESHOLD, FR_RATE
    case JP_MISC

    var orientation: Bool {
        switch self {
        case .SHORT_GAINS, .SHORT_LOSSES, .SHORT_NET, .LONG_GAINS, .LONG_LOSSES, .LONG_NET, .UNCLASSIFIED,
             .GAINS, .LOSSES, .NET, .NEW_NET, .OLD_SHORT_NET, .OLD_LONG_NET, .DENIED_LOSS, .PROCEEDS, .INCOME, .AT_INTEREST, .AT_INTEREST_OLD, .FR_OUTFLOW,
             .WITHDRAWAL_FEES, .TRANSFER_FEES, .CH_WEALTH_VALUE:
            return false
        case .DE_LONG, .DE_PRIVATE_LIMIT, .DE_INCOME_LIMIT, .AT_RATE, .AT_OLD_LONG, .AT_OLD_SPECULATION_LIMIT, .CH_PRIVATE, .CH_INCOME, .CH_WEALTH,
             .UK_EXEMPT, .UK_AFTER_EXEMPT, .US_TERMS, .CA_TAXABLE, .AU_DISCOUNTED, .NET_CAPITAL_LOSS, .FR_THRESHOLD, .FR_RATE,
             .JP_MISC:
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
    /// Datum im Vermerk (`.WINDOW_OPEN`: Käufe bis …); nil = keins.
    var noteDate: LocalDay? = nil
    /// An einem Tag ohne USDT/USD-Schluss mit USDT = 1 umgerechnet; zählt in `TaxYearSummary.usdtApproximate`.
    var usdtApproximate = false
    /// Ein Wert darin ist zum Tagesschluss geschätzt (DE § 22 Nr. 3) — Vermerk `TaxSummaryTexts.estimated` an der
    /// Grenzzeile. Wie Android.
    var estimated = false

    /// Text des Vermerks aus `texts` samt Datum (ISO) — wie `noteText` (Android); nil = keiner.
    func noteText(_ texts: TaxSummaryTexts) -> String? {
        guard let note, let text = texts.notes[note], !text.isEmpty else { return nil }
        guard let noteDate else { return text }
        return text.replacingOccurrences(of: TaxExport.noteDateToken, with: noteDate.description)
    }
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
    /// CH: Vermögenswert per 31.12. je Coin (`TaxExport.chWealth`); nil = nicht gerechnet bzw. andere Vorlage.
    var wealth: TaxWealth? = nil
    /// Irgendein Betrag der Übersicht an einem Tag ohne USDT/USD-Schluss mit USDT = 1 umgerechnet — eigene Zeile
    /// `TaxSummaryTexts.usdtAssumed` in der Datei. Wie Android.
    var usdtApproximate = false
}
