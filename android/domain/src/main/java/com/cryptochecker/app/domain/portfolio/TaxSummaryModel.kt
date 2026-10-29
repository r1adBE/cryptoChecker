package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.LocalDate

// Modell der Jahresübersicht je Steuer-Vorlage ([TaxExport.yearSummary] in TaxExportSummary.kt): Status, Vermerke,
// Posten, Ergebnis.

/** Ergebnis einer Freigrenzen-Prüfung ([TaxSummaryLine.status]). */
enum class TaxLimitStatus {
    /** Unter der Freigrenze (steuerfrei). */
    BELOW,
    /** Freigrenze erreicht oder überschritten: der GANZE Betrag ist steuerpflichtig (Freigrenze, kein Freibetrag). */
    REACHED,
    /** Nicht prüfbar: EUR-Werte fehlen, ein Gewinn/Wert ist unbekannt oder die Haltedauer fehlt (Ø). */
    UNKNOWN,
    /**
     * DE § 23: netto ein Verlust — kein steuerpflichtiger Gewinn; der Verlust ist nur mit Gewinnen aus privaten
     * Veräusserungsgeschäften verrechenbar (§ 23 Abs. 3 Sätze 7–8 EStG, Rück-/Vortrag nach § 10d).
     */
    LOSS,
}

/** Zusatz-Vermerk eines Postens der Jahresübersicht ([TaxSummaryLine.note], Text in [TaxSummaryTexts.notes]). */
enum class TaxSummaryNote {
    /**
     * AT Einkünfte: nur Information, nicht addieren — Belohnungen (Staking, Airdrop) haben im Neubestand Anschaffungskosten 0
     * und sind erst beim Verkauf steuerpflichtig (dort schon in den Gewinnen enthalten; BMF, § 27b Abs. 2 EStG).
     */
    AT_REWARD_INFO,
    /** CH Vermögen: der Wert per 31.12. der Bestand-CSV ist Börsenkurs × USD/CHF, nicht die Steuerwerte der ESTV-Kursliste. */
    CH_EXCHANGE_VALUE,
    /**
     * CH Gewinne/Verluste/netto: nur Information — FIFO-Rechnung der App, keine Einordnung (Kapitalgewinne im
     * Privatvermögen «nicht gerechnet – siehe ESTV»).
     */
    CH_FIFO_INFO,
    /**
     * CH Vermögenswert per 31.12. ([TaxLine.CH_WEALTH_VALUE]): nur Orientierung — massgebend ist die Kursliste der ESTV
     * (ictax.admin.ch), für Coins ohne Eintrag in der Regel der Schlusskurs der Börse; Vermögenssteuer nicht gerechnet.
     */
    CH_WEALTH_ORIENTATION,
    /**
     * DE Auszahlungsgebühren ([TaxLine.WITHDRAWAL_FEES]): nicht mit § 23 verrechnet (keine Veräusserung, mindern weder
     * die Summe ≤ 1 Jahr noch die Freigrenze) — ggf. als Werbungskosten prüfen.
     */
    DE_WITHDRAWAL_FEE,
    /**
     * AT Auszahlungsgebühren: nicht mit dem Neubestand verrechnet — beim Sondersteuersatz 27,5 % sind Aufwendungen nicht
     * abzugsfähig (§ 20 Abs. 2 EStG).
     */
    AT_WITHDRAWAL_FEE,
    /**
     * AT Zinsen ([TaxLine.AT_INTEREST]): beim Zufluss laufende Einkünfte aus Kryptowährungen (Entgelt für die Überlassung,
     * § 27b Abs. 2 Z 1 EStG) zum Sondersteuersatz 27,5 %; die Coins behalten diesen Wert als Anschaffungskosten (BMF).
     */
    AT_INTEREST_INCOME,
    /**
     * AT Zinsen vor dem 1.3.2022 ([TaxLine.AT_INTEREST_OLD]): § 27b EStG gilt erst für Zuflüsse ab dem 1.3.2022 (§ 124b
     * Z 384 EStG) — davor alte Rechtslage (Tarif), nicht im Sondersteuersatz 27,5 %.
     */
    AT_INTEREST_OLD_LAW,
    /**
     * UK, US, CA, AU, JP Auszahlungsgebühren in USDT/Fiat ([TaxTemplate.quoteFeeIsInfo]): kein Verlust, nur Information
     * (UK: HMRC CRYPTO22150, keine «allowable cost»; sonst keine Regel, die sie abziehbar macht).
     */
    WITHDRAWAL_FEE_INFO,
    /**
     * UK, US, CA, AU, JP Umbuchungsgebühren in USDT/Fiat ([TaxLine.TRANSFER_FEES]): kein Verlust und nicht im
     * Einstand, nur Information.
     */
    TRANSFER_FEE_INFO,
    /**
     * AT Spekulationsgeschäfte netto ([TaxLine.OLD_SHORT_NET]): darin Verkäufe bzw. Tausch von Neuvermögen vom 1.1. bis
     * 28.2.2022 ([TaxTemplate.AT_OPTION_FROM] bis vor [TaxTemplate.AT_SWAP_NEUTRAL_FROM]) — für diese Realisationen ist
     * auf Antrag schon der Sondersteuersatz 27,5 % möglich (§ 124b Z 384 lit. c EStG); gerechnet ist ohne Option.
     * Verkäufe vom 1.3. bis 31.12.2021 haben diese Option nicht (nur alte Rechtslage). Alter Name beibehalten.
     */
    AT_TRANSITION_2021,
    /**
     * AT Freigrenze Spekulationsgeschäfte ([TaxLine.AT_OLD_SPECULATION_LIMIT]): geprüft ohne Option — die Verkäufe von
     * Neuvermögen vom 1.1. bis 28.2.2022 zählen mit (ohne Antrag gilt die alte Rechtslage).
     */
    AT_WITHOUT_OPTION,
    /**
     * AT Sondersteuersatz ([TaxLine.AT_RATE]): Belohnungen (Staking, Airdrop) erhalten vom 1.3.2021 bis 28.2.2022 sind wie
     * nach neuer Rechtslage mit Anschaffungskosten 0 angesetzt. Ob vor dem Inkrafttreten (1.3.2022) der Wert beim Zufluss
     * gilt, sagt keine Quelle der Verwaltung ausdrücklich (BMF «Steuerliche Behandlung von Kryptowährungen», Stand
     * 01.01.2026: Anschaffungskosten 0 nur für die Neuregelung) — mit Fachperson prüfen.
     */
    AT_STAKING_ZERO_COST,
    /**
     * UK/CA netto ([TaxLine.NET]): das Fenster der 30-Tage-Regel nach dem Ende des Steuerjahrs ist noch offen (UK
     * Bed-and-Breakfast, CA Superficial Loss) — Käufe bis [TaxSummaryLine.noteDate] können die Zahlen noch ändern.
     * Text mit Platzhalter [TAX_NOTE_DATE] ([noteText]).
     */
    WINDOW_OPEN,
    /**
     * UK Erlöse ([TaxLine.PROCEEDS]): Veräusserungserlöse vor Gebühren über [TaxTemplate.ukReportProceedsGbp] (£50,000 ab
     * 2023/24, 2020/21–2022/23 £49,200) — Meldung an HMRC, falls für Self Assessment registriert, auch ohne steuerbaren
     * Gewinn (gov.uk «Report and pay Capital Gains Tax»).
     */
    UK_HMRC_REPORT,
    /**
     * FR netto ([TaxLine.NET]): mindestens eine Abtretung im Jahr ist mit einem Gesamtwert samt Guthaben beobachteter
     * Adressen gerechnet ([TaxGainRow.addressValueIncluded]). Sie zählen zu Recht zum Gesamtwert (art. 150 VH bis), haben
     * aber keinen Einstand in der Erwerbspreis-Summe — der Gewinn ist dadurch eher zu hoch; Einstand als Kauf erfassen.
     */
    FR_ADDRESS_NO_COST,
    /**
     * FR Gewinne ([TaxLine.GAINS]), wenn bis zum Ende des Jahres Belohnungen erfasst sind ([TaxExport.frRewards]): die
     * Erwerbspreis-Summe enthält sie zum Wert beim Erhalt — das setzt voraus, dass sie beim Erhalt als Einkommen versteuert
     * wurden; sonst wäre Erwerbspreis 0 die vorsichtige Lesart (Entscheid des Eigentümers: Regel bleibt, nur vermerkt).
     */
    FR_REWARDS_INCOME,
}

/**
 * Posten der Jahresübersicht einer Vorlage ([TaxExport.yearSummary]); die App nennt sie übersetzt. Tatsachen-Posten
 * (Summen) zuerst; [orientation] = Schluss nach den Regeln der Vorlage, nur als «Orientierung» mit Hinweis.
 */
enum class TaxLine(val orientation: Boolean = false) {
    SHORT_GAINS, SHORT_LOSSES, SHORT_NET, LONG_GAINS, LONG_LOSSES, LONG_NET, UNCLASSIFIED,
    GAINS, LOSSES, NET,
    /** AT: Neubestand netto, Altbestand ≤ 1 Jahr / > 1 Jahr netto. */
    NEW_NET, OLD_SHORT_NET, OLD_LONG_NET,
    /** CA: nicht abziehbare Verluste (Superficial Loss). */
    DENIED_LOSS,
    /** Erlöse (FR: Abtretungspreise vor Gebühren). */
    PROCEEDS,
    INCOME,
    /**
     * AT: Zinsen aus Lending ([TaxIncomeRow.interest]) zum Wert beim Zufluss — zählen (Vermerk [TaxSummaryNote.AT_INTEREST_INCOME]);
     * nicht in [INCOME] (dort nur Belohnungen mit Anschaffungskosten 0, Information).
     */
    AT_INTEREST,
    /**
     * AT: Zinsen aus Lending mit Zufluss vor dem 1.3.2022 — alte Rechtslage (Tarif), nicht im Sondersteuersatz
     * ([AT_RATE]); Vermerk [TaxSummaryNote.AT_INTEREST_OLD_LAW].
     */
    AT_INTEREST_OLD,
    /** FR: Abgänge ohne Verkauf im Jahr — Kürzung der Erwerbspreis-Summe ([TaxOutflow]); das Jahr gilt als unvollständig. */
    FR_OUTFLOW,
    /**
     * DE, AT, UK, US, CA, AU, JP: Gebühren von Auszahlungen ([PortfolioFeeExpense.WITHDRAWAL]) — eigener Posten, nicht mit
     * den Veräusserungen verrechnet (Vermerk [TaxSummaryNote.DE_WITHDRAWAL_FEE], [TaxSummaryNote.AT_WITHDRAWAL_FEE] bzw.
     * [TaxSummaryNote.WITHDRAWAL_FEE_INFO]).
     */
    WITHDRAWAL_FEES,
    /**
     * UK, US, CA, AU, JP: Gebühren USDT/Fiat von Umbuchungen zwischen eigenen Wallets ([PortfolioFeeExpense.TRANSFER])
     * — nur Information (Vermerk [TaxSummaryNote.TRANSFER_FEE_INFO]), weder Verlust noch Einstand.
     */
    TRANSFER_FEES,
    /**
     * CH: Vermögenswert per 31.12. ([TaxExport.chWealth], Bestand × Börsen-Tagesschluss × Devisenkurs) — der Text des
     * Postens nennt selbst «(Orientierung)», darum ohne Vorsatz im Orientierungs-Block; Vermerk
     * [TaxSummaryNote.CH_WEALTH_ORIENTATION], Werte je Coin in [TaxYearSummary.wealth].
     */
    CH_WEALTH_VALUE,
    DE_LONG(true), DE_PRIVATE_LIMIT(true), DE_INCOME_LIMIT(true),
    AT_RATE(true), AT_OLD_LONG(true),
    /**
     * AT: Altbestand ≤ 1 Jahr gehalten = Spekulationsgeschäft nach alter Rechtslage (§ 30 EStG a. F.) — Freigrenze 440 €
     * im Kalenderjahr (§ 31 Abs. 3 EStG a. F.: steuerfrei, wenn höchstens 440 €), Status wie [DE_PRIVATE_LIMIT].
     */
    AT_OLD_SPECULATION_LIMIT(true),
    CH_PRIVATE(true), CH_INCOME(true), CH_WEALTH(true),
    UK_EXEMPT(true), UK_AFTER_EXEMPT(true),
    US_TERMS(true),
    CA_TAXABLE(true),
    AU_DISCOUNTED(true),
    /**
     * CA, AU: netto ein Kapitalverlust — der steuerbare Posten bleibt 0, der Verlust ist vorzutragen (CA: abziehbarer Teil
     * = Verlust × Einschlussrate 1/2, «net capital loss»; AU: ganzer Verlust, «net capital loss» ohne Rabatt). Betrag positiv.
     */
    NET_CAPITAL_LOSS(true),
    FR_THRESHOLD(true), FR_RATE(true),
    JP_MISC(true),
}

/** Ein Posten: Beträge (USDT / Steuerwährung; null = keiner bzw. nicht bekannt), Grenze, Vermerke. */
data class TaxSummaryLine(
    val line: TaxLine,
    val usdt: BigDecimal? = null,
    val target: BigDecimal? = null,
    val status: TaxLimitStatus? = null,
    /** Ein Gewinn/Wert fehlt (Kaufpreis, Erlös, Haltedauer) — Betrag nur aus dem Bekannten. */
    val incomplete: Boolean = false,
    /** Ein Devisenkurs fehlt — Betrag in der Steuerwährung nur aus dem Bekannten. */
    val noFx: Boolean = false,
    /** Kein Betrag, weil die App ihn nicht rechnet (CH Vermögenssteuer: ESTV-Kursliste) — Vermerk [TaxSummaryTexts.notCalculated]. */
    val notCalculated: Boolean = false,
    /** Zusatz-Vermerk ([TaxSummaryTexts.notes]); null = keiner. */
    val note: TaxSummaryNote? = null,
    /** Datum im Vermerk ([TaxSummaryNote.WINDOW_OPEN]: Käufe bis …); null = keins. */
    val noteDate: LocalDate? = null,
    /** An einem Tag ohne USDT/USD-Schluss mit USDT = 1 umgerechnet ([TaxExport.usdtApproximateOn]); zählt in [TaxYearSummary.usdtApproximate]. */
    val usdtApproximate: Boolean = false,
    /**
     * Ein Wert darin ist geschätzt — Vermerk [TaxSummaryTexts.estimated]: DE § 22 Nr. 3 an der Grenzzeile (Einkünfte mit
     * [TaxIncomeRow.estimated]), damit «unter der Grenze» nicht wie ein belegter Betrag aussieht; FR an [TaxLine.FR_OUTFLOW]
     * (eine Kürzung mit [TaxOutflow.estimated]).
     */
    val estimated: Boolean = false,
)

/** Jahresübersicht einer Vorlage — ORIENTIERUNG, keine Steuerberatung. */
data class TaxYearSummary(
    val template: TaxTemplate,
    val method: TaxMethod,
    val period: TaxPeriod,
    /** Steuerwährung ([TaxTemplate.currency], Neutral: Anzeigewährung). */
    val currency: String,
    /** Devisenkurse geladen (oder USD). */
    val fxAvailable: Boolean,
    val lines: List<TaxSummaryLine>,
    /** US-Übergang, mit dem gerechnet wurde ([TaxTemplate.walletFrom]); null = keiner. */
    val walletFrom: LocalDate? = null,
    /** CH: Vermögenswert per 31.12. je Coin ([TaxExport.chWealth]); null = nicht gerechnet bzw. andere Vorlage. */
    val wealth: TaxWealth? = null,
    /**
     * Irgendein Betrag der Übersicht an einem Tag ohne USDT/USD-Schluss mit USDT = 1 umgerechnet (Zeilen der Gewinne und
     * Einkünfte, FR-Abgänge, CH-Vermögenswert) — die Datei sagt es in einer eigenen Zeile ([TaxSummaryTexts.usdtAssumed]),
     * weil die Übersicht keine Zeilen hat, an denen der Vermerk stehen könnte.
     */
    val usdtApproximate: Boolean = false,
)
