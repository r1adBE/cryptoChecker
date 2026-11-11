package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

// Jahresübersicht je Steuer-Vorlage ([TaxExport.yearSummary]): Posten, Freigrenzen, Orientierung. Modell in
// TaxSummaryModel.kt, Texte in TaxSummaryTexts.kt, Teile (Summen, FR-Abgänge) in TaxExportSummaryHelpers.kt.


/**
 * Jahresübersicht der Vorlage [template] für das Steuerjahr ab [startYear] — ORIENTIERUNG, KEINE STEUERBERATUNG.
 * Aus den Zeilen ([gainRows] nach [method], in [currency] mit [rates]; null = keine Kurse) und den Einkünften:
 *  - Tatsachen: Gewinne (> 0), Verluste (≤ 0) und netto — mit Haltedauer-Gruppen ≤ 1 / > 1 Jahr bei DE, US, AU und
 *    Neutral (ohne Haltedauer: eigener Posten), AT getrennt nach Neubestand (verkauft ab 1.3.2022), Spekulationsgeschäften
 *    nach alter Rechtslage (Altbestand ≤ 1 Jahr und Neuvermögen verkauft vor dem 1.3.2022) und Altbestand > 1 Jahr; CA
 *    nicht abziehbare Verluste; Erlöse (FR: Abtretungspreise vor Gebühren); Einkünfte (Belohnungen zum Wert beim Erhalt).
 *  - AT Einkünfte: Belohnungen nur Information ([TaxSummaryNote.AT_REWARD_INFO]) — sie stecken mit Anschaffungskosten 0
 *    im Neubestand und zählen erst beim Verkauf (kein Doppel aus Zufluss und Verkaufsgewinn); Zinsen aus Lending
 *    ([TaxLine.AT_INTEREST]) sind dagegen beim Zufluss Einkünfte (§ 27b Abs. 2 Z 1 EStG) und zählen.
 *  - Orientierung ([TaxLine.orientation]): DE Freigrenzen § 23 (netto ≤ 1 Jahr < Grenze; netto ein Verlust: eigener
 *    Status [TaxLimitStatus.LOSS]) und § 22 Nr. 3 (< 256 €),
 *    > 1 Jahr nicht steuerbar; AT 27,5 % auf Neubestand netto + Zinsen (Verlustausgleich innerhalb der Einkünfte zum
 *    Sondersteuersatz, § 27 Abs. 8 EStG), Altbestand > 1 Jahr nicht steuerbar, Freigrenze 440 € für die
 *    Spekulationsgeschäfte (ohne Option auf 27,5 % für 1.1.–28.2.2022); CH ohne Einordnung:
 *    Kapitalgewinne «nicht gerechnet – siehe ESTV» (kein Betrag), Belohnungen aufgeführt (Betrag, Behandlung siehe
 *    ESTV-Arbeitspapier), Vermögenssteuer «nicht gerechnet» (ESTV-Kursliste; Vermerk: Bestand-CSV per 31.12. =
 *    Börsenkurs × USD/CHF, [TaxSummaryNote.CH_EXCHANGE_VALUE]); davor als Posten der Vermögenswert per 31.12. zur
 *    Orientierung ([TaxLine.CH_WEALTH_VALUE], [chWealth], je Coin in [TaxYearSummary.wealth]); UK Annual Exempt Amount und netto darüber;
 *    US kurz-/langfristig; CA steuerbarer Anteil (netto + nicht abziehbar) × 1/2, nur in CAD; AU Verluste zuerst gegen
 *    Gewinne ≤ 12 Monate, dann 50 % Rabatt auf den Rest > 12 Monate; CA/AU netto ein Verlust: steuerbar 0 und
 *    «Netto-Kapitalverlust (Vortrag)»; FR Abtretungen ≤ 305 € befreit (sonst ganz steuerbar); JP sonstige Einkünfte =
 *    netto + Einkünfte. UK/CA: Fenster der 30-Tage-Regel nach dem Steuerjahr noch offen ([today] bis und mit dem
 *    letzten Tag) und eine Veräusserung (CA: mit Verlust) in den letzten 30 Tagen des Steuerjahrs → Vermerk
 *    [TaxSummaryNote.WINDOW_OPEN]; UK Erlöse vor Gebühren über der Meldegrenze → [TaxSummaryNote.UK_HMRC_REPORT];
 *    FR Abtretung mit Guthaben beobachteter Adressen im Gesamtwert → [TaxSummaryNote.FR_ADDRESS_NO_COST] am Posten netto.
 *    AT Zinsen mit Zufluss vor dem 1.3.2022: eigener Posten [TaxLine.AT_INTEREST_OLD] (alte Rechtslage), nicht 27,5 %.
 * Gezählt wird nur Bekanntes; was fehlt, steht als «unvollständig» bzw. «ohne Devisenkurs» am Posten (auch ein Gewinn
 * aus unvollständigem Einstand, FR). Gewinn oder Verlust entscheidet die Steuerwährung (ohne Kurs USDT). CH: Gewinne,
 * Verluste, netto nur als Information ([TaxSummaryNote.CH_FIFO_INFO]).
 */
fun TaxExport.yearSummary(
    trades: List<PortfolioTrade>,
    template: TaxTemplate,
    method: TaxMethod,
    startYear: Int,
    zone: ZoneId,
    currency: String,
    rates: Map<Long, BigDecimal>?,
    priceAt: ((String, Long) -> BigDecimal?)? = null,
    /** US-Übergang ([TaxTemplate.walletFrom]); null = keiner. */
    walletFrom: LocalDate? = null,
    /** USDT/USD-Tagesschluss je Datum ([rateOn]); null = USDT gilt 1 USD. */
    usdtUsd: Map<Long, BigDecimal>? = null,
    /** Heute (UK/CA: ist das 30-Tage-Fenster nach dem Steuerjahr noch offen?); null = heute in [zone]. */
    today: LocalDate? = null,
    /** CH: Tagesschlüsse des 31.12. ([chWealth]); null = nicht geladen → Vermögenswert «nicht gerechnet». */
    yearEndCloses: CutoffCloses? = null,
    /** CH: beobachtete Adressen am 31.12. in der Sicht der Karte ([AddressCutoff.at]); null = keine. */
    yearEndAddresses: AddressCutoffView? = null,
    /** Veräusserungen aller Jahre aus [TaxExport.allDisposals] (für [gainRows]); null = dort rechnen. */
    disposals: List<PortfolioDisposal>? = null,
): TaxYearSummary {
    return TaxYearSummaryBuilder(
        trades, template, method, startYear, zone, currency, rates, priceAt, walletFrom, usdtUsd, today, yearEndCloses,
        yearEndAddresses, disposals,
    ).build()
}
