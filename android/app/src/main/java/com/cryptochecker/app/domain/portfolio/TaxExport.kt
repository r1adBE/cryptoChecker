package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Eine Zeile «Realisierte Gewinne»: eine Veräusserung ([PortfolioDisposal]; FIFO je Posten, Ø je Verkauf).
 * Beträge exakt, gerundet wird erst im Text. null = unbekannt (Feld leer).
 */
data class TaxGainRow(
    val coin: String,
    /** Datum des Zugangs (Ortszeit); Ø: null. */
    val acquiredDate: LocalDate?,
    val soldDate: LocalDate,
    /** Kalendertage zwischen [acquiredDate] und [soldDate]; Ø: null. */
    val holdingDays: Long?,
    /** Länger als ein Jahr gehalten ([TaxExport.isLongTerm]); Ø: null. */
    val longTerm: Boolean?,
    val amount: BigDecimal,
    val costUsdt: BigDecimal?,
    val proceedsUsdt: BigDecimal?,
    val gainUsdt: BigDecimal?,
    /**
     * Einstand in der Anzeigewährung: Summe der Herkunftsteile ([PortfolioDisposal.costParts]) je zum Kurs ihres Datums
     * — FIFO: Erwerb und zugeschlagene Umbuchungsgebühren je zu ihrem Datum, Ø: laufender Ø in der Anzeigewährung; ohne
     * Teile Einstand × Kurs am Kaufdatum. null, wenn ein nötiger Kurs fehlt.
     */
    val costTarget: BigDecimal?,
    /** Erlös × Devisenkurs am Verkaufsdatum. */
    val proceedsTarget: BigDecimal?,
    /** [proceedsTarget] − [costTarget]. */
    val gainTarget: BigDecimal?,
    /** Ein benötigter Devisenkurs fehlt. */
    val fxMissing: Boolean,
    val feeLeg: Boolean,
    val feeValueMissing: Boolean,
    val method: CostMethod,
    /** Mehr verkauft als erfasst: Teil ohne erfassten Zugang ([PortfolioDisposal.uncovered]). */
    val uncovered: Boolean = false,
    /** Veräusserung durch einen Tausch ([PortfolioDisposal.swap]). */
    val swap: Boolean = false,
    /** [CostMethod.FIFO_WALLET]: Wallet ([PortfolioDisposal.wallet]); sonst null. */
    val wallet: String? = null,
    // ---- Steuer-Vorlagen ([TaxEngines]); Tatsachen, keine Schlüsse ----
    /** UK: Zuordnung ([PortfolioDisposal.match]). */
    val match: TaxMatch? = null,
    /** CA: nicht abziehbarer Verlust (USDT, ≥ 0). */
    val adjustmentUsdt: BigDecimal? = null,
    /** [adjustmentUsdt] × Devisenkurs am Verkaufsdatum. */
    val adjustmentTarget: BigDecimal? = null,
    /** AT: Altbestand (true) / Neubestand (false). */
    val oldHolding: Boolean? = null,
    /** FR: Erwerbspreis-Summe unvollständig. */
    val costIncomplete: Boolean = false,
    /** FR: Gesamtwert des Portfolios vor der Abtretung (USDT / Anzeigewährung am Verkaufsdatum). */
    val portfolioValueUsdt: BigDecimal? = null,
    val portfolioValueTarget: BigDecimal? = null,
    /** FR: Abtretungspreis vor Gebühren (USDT / Anzeigewährung). */
    val grossProceedsUsdt: BigDecimal? = null,
    val grossProceedsTarget: BigDecimal? = null,
    /** Einstand aus einem Durchschnitt (Herkunftsteile je Kaufdatum umgerechnet). */
    val pooled: Boolean = method == CostMethod.AVERAGE,
    /** Gebühr als Aufwand ([PortfolioDisposal.feeExpense]): Einstand = Aufwand, Erlös 0; sonst null. */
    val feeExpense: PortfolioFeeExpense? = null,
    /** Gebühr als Veräusserung ohne Erlös ([PortfolioLedger.isFeeDisposal], CSV-Zeile «Fee»): Vermerk «Gebühr». */
    val feeRow: Boolean = false,
    /** Schenkung als Veräusserung ([PortfolioDisposal.gift], UK/CA/AU): Vermerk «Schenkung». */
    val gift: Boolean = false,
    /** Zahlung mit Krypto ([PortfolioDisposal.payment]): Vermerk «Zahlung mit Krypto». */
    val payment: Boolean = false,
    /** Wert geschätzt ([PortfolioDisposal.valueEstimated], Tagesschluss): Vermerk, zählt nicht als «unvollständig». */
    val valueEstimated: Boolean = false,
    /** UK/CA: Schenkung an Ehe-/Lebenspartner ([PortfolioDisposal.spouseGift]): Erlös = Einstand, Vermerk. */
    val spouseGift: Boolean = false,
    /**
     * Gebühr in USDT/Fiat einer Auszahlung bzw. Umbuchung mit [TaxEventRules.quoteFeeIsInfo] (UK, US, CA, AU, JP): kein
     * Verlust, nur Information — nicht in den Summen, Vermerk [TaxCsvTexts.withdrawalFeeInfo] bzw. [TaxCsvTexts.transferFeeInfo].
     */
    val feeInfoOnly: Boolean = false,
) {
    val costMissing: Boolean get() = costUsdt == null
    val proceedsMissing: Boolean get() = proceedsUsdt == null

    /** Einstand, Erlös und Gewinn in USDT bekannt (zählt in der Summe). */
    val completeUsdt: Boolean get() = costUsdt != null && proceedsUsdt != null && gainUsdt != null

    /** Einstand, Erlös und Gewinn in der Anzeigewährung bekannt (zählt in der Summe). */
    val completeTarget: Boolean get() = costTarget != null && proceedsTarget != null && gainTarget != null
}

/** Eine Zeile «Einkünfte»: Belohnung ([PortfolioTxType.REWARD]) mit Wert beim Erhalt. */
data class TaxIncomeRow(
    val date: LocalDate,
    val coin: String,
    val amount: BigDecimal,
    /** Menge × Wert je Coin beim Erhalt; null = Wert fehlt. */
    val valueUsdt: BigDecimal?,
    /** [valueUsdt] × Devisenkurs des Tags. */
    val valueTarget: BigDecimal?,
    val fxMissing: Boolean,
    /** Zins (Lending, [PortfolioTrade.interestIncome]) — AT: beim Zufluss Einkunft zum Sondersteuersatz ([TaxLine.AT_INTEREST]). */
    val interest: Boolean = false,
) {
    val valueMissing: Boolean get() = valueUsdt == null
}

/** Übersetzte Texte der Steuer-Exporte (Spalten mit Währung schon eingesetzt). */
data class TaxCsvTexts(
    val coin: String,
    val acquired: String,
    val sold: String,
    val holdingDays: String,
    /** «> 1 Jahr». */
    val longTerm: String,
    val amount: String,
    val costUsdt: String,
    val proceedsUsdt: String,
    val gainUsdt: String,
    val costTarget: String,
    val proceedsTarget: String,
    val gainTarget: String,
    val date: String,
    val valueUsdt: String,
    val valueTarget: String,
    val note: String,
    val total: String,
    val yes: String,
    val no: String,
    /** «Kaufpreis fehlt». */
    val costMissing: String,
    /** «Verkaufspreis fehlt». */
    val proceedsMissing: String,
    /** «Gebühr in anderem Coin». */
    val feeLeg: String,
    /** «ohne Wert der Gebühr im dritten Coin». */
    val feeValueMissing: String,
    /** «Einstand zu den Kursen der Kaufdaten (Ø)». */
    val avgCostRates: String,
    /** «Wert beim Erhalt fehlt». */
    val valueMissing: String,
    val noFx: String,
    val incomplete: String,
    /** «mehr verkauft als erfasst» (Teil ohne erfassten Zugang). */
    val oversold: String = "",
    /** Summenzeile «Erlös ohne Kaufpreis» (Zeilen mit unbekanntem Einstand). */
    val proceedsWithoutCost: String = "",
    /** «Zeitzone: %s» schon mit der Zeitzone (z. B. «Zeitzone: Europe/Zurich»); leer = keine Zeile. */
    val timeZone: String = "",
    /** «Tausch» (Veräusserung durch einen Tausch Coin gegen Coin); leer = kein Vermerk. */
    val swap: String = "",
    /** «Wallet: %s» ([CostMethod.FIFO_WALLET]); leer = kein Vermerk. Mit «%s» für den Namen. */
    val wallet: String = "",
    /** «ohne Angabe» (Wallet ohne Börse); nur mit [wallet]. */
    val walletUnspecified: String = "",
    /** «Netzwerkgebühr-Einstand ohne bezahlten Posten» ([PortfolioLostFeeCost]); leer = keine solchen Zeilen. */
    val lostFeeCost: String = "",
    /** «Gebühr nicht zugeordnet – Einstand unbekannt» ([PortfolioUnassignedFee]); leer = keine solchen Zeilen. */
    val unassignedFee: String = "",
    /** UK: Vermerke der Zuordnung «gleicher Tag», «30 Tage», «Pool (Section 104)». */
    val matchSameDay: String = "",
    val matchThirtyDay: String = "",
    val matchPool: String = "",
    /** CA: Spalten «Nicht abziehbarer Verlust USDT/…» (nur wenn es solche gibt) und Vermerk «Superficial Loss». */
    val adjustmentUsdt: String = "",
    val adjustmentTarget: String = "",
    val adjustment: String = "",
    /** AT: Vermerke «Altbestand (erworben vor 1.3.2021)» / «Neubestand». */
    val oldHolding: String = "",
    val newHolding: String = "",
    /** FR: Vermerk «Erwerbspreis-Summe unvollständig»; Spalten Gesamtwert Portfolio und Abtretungspreis vor Gebühren. */
    val costIncomplete: String = "",
    val portfolioValueUsdt: String = "",
    val portfolioValueTarget: String = "",
    val grossUsdt: String = "",
    val grossTarget: String = "",
    /** Vermerke der Gebühr-Aufwände ([PortfolioFeeExpense]): «Gebühr verbraucht den ganzen Zugang», «Gebühr Auszahlung». */
    val feeConsumed: String = "",
    val withdrawalFee: String = "",
    /** Vermerk «Gebühr» einer Veräusserung ohne Erlös ([TaxGainRow.feeRow]); leer = kein Vermerk. */
    val feeRow: String = "",
    /** Vermerk «Schenkung» ([TaxGainRow.gift]); leer = kein Vermerk. */
    val gift: String = "",
    /** Vermerk «Zahlung mit Krypto» ([TaxGainRow.payment]); leer = kein Vermerk. */
    val payment: String = "",
    /** Vermerk «Wert geschätzt (Tagesschluss)» ([TaxGainRow.valueEstimated]); leer = kein Vermerk. */
    val valueEstimated: String = "",
    /** Vermerk «an Ehe-/Lebenspartner – kein Gewinn/Verlust» ([TaxGainRow.spouseGift]); leer = kein Vermerk. */
    val spouseGift: String = "",
    /** Vermerk «Auszahlungsgebühr USDT/Fiat – kein Verlust, nur Information» ([TaxGainRow.feeInfoOnly]); leer = [withdrawalFee]. */
    val withdrawalFeeInfo: String = "",
    /** Vermerk «Umbuchungsgebühr USDT/Fiat – kein Verlust, nicht im Einstand, nur Information» ([PortfolioFeeExpense.TRANSFER]). */
    val transferFeeInfo: String = "",
)

/** Ergebnis einer Freigrenzen-Prüfung ([TaxYearSummaryDe]). */
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
     * UK, US, CA, AU, JP Auszahlungsgebühren in USDT/Fiat ([TaxTemplate.quoteFeeIsInfo]): kein Verlust, nur Information
     * (UK: HMRC CRYPTO22150, keine «allowable cost»; sonst keine Regel, die sie abziehbar macht).
     */
    WITHDRAWAL_FEE_INFO,
    /**
     * UK, US, CA, AU, JP Umbuchungsgebühren in USDT/Fiat ([TaxLine.TRANSFER_FEES], Build 57): kein Verlust und nicht im
     * Einstand, nur Information.
     */
    TRANSFER_FEE_INFO,
}

/**
 * Jahresübersicht Deutschland ([TaxExport.deYearSummary]) — Orientierung, keine Steuerberatung. Beträge exakt; EUR-Werte
 * null, wenn keine EUR-Kurse übergeben wurden ([eurAvailable]). Gewinne > 0, Verluste ≤ 0 (negativ summiert).
 */
data class TaxYearSummaryDe(
    val year: Int,
    val method: CostMethod,
    /** Gewinne bis 1 Jahr gehalten (§ 23 EStG, privates Veräusserungsgeschäft). */
    val shortGainsUsdt: BigDecimal,
    /** Verluste bis 1 Jahr (negativ oder 0). */
    val shortLossesUsdt: BigDecimal,
    /** [shortGainsUsdt] + [shortLossesUsdt]. */
    val netShortUsdt: BigDecimal,
    /** Gewinne nach mehr als 1 Jahr (in DE steuerfrei). */
    val longGainsUsdt: BigDecimal,
    val longLossesUsdt: BigDecimal,
    /** Bekannter Gewinn/Verlust ohne Haltedauer (Ø, mehr verkauft als erfasst) — weder kurz- noch langfristig. */
    val unclassifiedUsdt: BigDecimal,
    val eurAvailable: Boolean,
    val shortGainsEur: BigDecimal?,
    val shortLossesEur: BigDecimal?,
    val netShortEur: BigDecimal?,
    val longGainsEur: BigDecimal?,
    val longLossesEur: BigDecimal?,
    val unclassifiedEur: BigDecimal?,
    /** Veräusserungen bis 1 Jahr oder ohne Haltedauer mit unbekanntem Gewinn (Kaufpreis/Erlös fehlt). */
    val unknownGainCount: Int,
    /** Veräusserungen ohne Haltedauer (Ø-Methode, ungedeckter Rest). */
    val unclassifiedCount: Int,
    /** Veräusserungen bis 1 Jahr / ohne Haltedauer mit bekanntem Gewinn, aber ohne EUR-Kurs. */
    val eurFxMissingCount: Int,
    /**
     * Gebühren von Auszahlungen ([PortfolioFeeExpense.WITHDRAWAL], negativ oder 0) — eigener Posten: NICHT in den Summen
     * ≤ 1 Jahr und nicht in der Freigrenze § 23 (keine Veräusserung; ggf. Werbungskosten).
     */
    val withdrawalFeesUsdt: BigDecimal = BigDecimal.ZERO,
    val withdrawalFeesEur: BigDecimal? = null,
    /** Anzahl solcher Gebühr-Zeilen (0 = kein Posten). */
    val withdrawalFeeCount: Int = 0,
    /** Freigrenze § 23 Abs. 3 Satz 5 EStG in EUR für [year] ([TaxExport.privateSalesLimitEur]). */
    val privateSalesLimitEur: BigDecimal,
    val privateSalesStatus: TaxLimitStatus,
    /** Einkünfte aus Leistungen (§ 22 Nr. 3 EStG: Belohnungen/Staking) — Summe der bekannten Werte. */
    val incomeUsdt: BigDecimal,
    val incomeEur: BigDecimal?,
    /** Belohnungen ohne Wert beim Erhalt. */
    val incomeValueMissingCount: Int,
    /** Belohnungen mit Wert, aber ohne EUR-Kurs. */
    val incomeFxMissingCount: Int,
    /** Freigrenze § 22 Nr. 3 Satz 2 EStG ([TaxExport.INCOME_LIMIT_EUR]). */
    val incomeLimitEur: BigDecimal,
    val incomeStatus: TaxLimitStatus,
)

/** Texte der «Jahresübersicht DE» (übersetzt). */
data class TaxDeTexts(
    /** «Jahresübersicht DE %d» mit dem Jahr eingesetzt. */
    val title: String,
    /** «Orientierung, keine Steuerberatung …» — steht immer in der Datei. */
    val disclaimer: String,
    val item: String,
    /** Spalte «USDT». */
    val usdt: String,
    /** Spalte «EUR». */
    val eur: String,
    val note: String,
    val shortGains: String,
    val shortLosses: String,
    val netShort: String,
    val longGains: String,
    val longLosses: String,
    val unclassified: String,
    val privateSalesLimit: String,
    val income: String,
    val incomeLimit: String,
    val below: String,
    val reached: String,
    val unknown: String,
    /** «Prüfung braucht EUR-Werte (Devisenkurse fehlen)». */
    val needsEur: String,
    val incomplete: String,
    /** «Haltedauer fehlt (Ø-Methode) – für die Prüfung FIFO wählen». */
    val needsHolding: String,
    val noFx: String,
    /** «Freigrenze gilt für alle privaten Veräusserungsgeschäfte bzw. Leistungen zusammen». */
    val limitScope: String,
    /** Kopf des Orientierungs-Blocks «Orientierung – keine Steuerberatung, keine Veranlagung»; leer = [disclaimer]. */
    val orientationHeading: String = "",
    /** Posten im Orientierungs-Block «Orientierung: %s»; leer = ohne Vorsatz. */
    val orientationItem: String = "",
    /** Status «Verlust» ([TaxLimitStatus.LOSS]); leer = [below]. */
    val loss: String = "",
    /** Posten «Auszahlungsgebühren»; leer = kein Posten. */
    val withdrawalFees: String = "",
    /** Vermerk «Auszahlungsgebühr – nicht mit § 23 verrechnet, ggf. Werbungskosten prüfen». */
    val withdrawalFeeNote: String = "",
)

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
    /** FR: Abgänge ohne Verkauf im Jahr — Kürzung der Erwerbspreis-Summe ([TaxOutflow]); das Jahr gilt als unvollständig. */
    FR_OUTFLOW,
    /**
     * DE, AT, UK, US, CA, AU, JP: Gebühren von Auszahlungen ([PortfolioFeeExpense.WITHDRAWAL]) — eigener Posten, nicht mit
     * den Veräusserungen verrechnet (Vermerk [TaxSummaryNote.DE_WITHDRAWAL_FEE], [TaxSummaryNote.AT_WITHDRAWAL_FEE] bzw.
     * [TaxSummaryNote.WITHDRAWAL_FEE_INFO]).
     */
    WITHDRAWAL_FEES,
    /**
     * UK, US, CA, AU, JP (Build 57): Gebühren USDT/Fiat von Umbuchungen zwischen eigenen Wallets ([PortfolioFeeExpense.TRANSFER])
     * — nur Information (Vermerk [TaxSummaryNote.TRANSFER_FEE_INFO]), weder Verlust noch Einstand.
     */
    TRANSFER_FEES,
    DE_LONG(true), DE_PRIVATE_LIMIT(true), DE_INCOME_LIMIT(true),
    AT_RATE(true), AT_OLD_LONG(true),
    CH_PRIVATE(true), CH_INCOME(true), CH_WEALTH(true),
    UK_EXEMPT(true), UK_AFTER_EXEMPT(true),
    US_TERMS(true),
    CA_TAXABLE(true),
    AU_DISCOUNTED(true),
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
)

/** Texte der Jahresübersicht ([TaxExport.summaryCsv]); [labels] je Posten. */
data class TaxSummaryTexts(
    val title: String,
    val disclaimer: String,
    val item: String,
    val usdt: String,
    /** Spalte der Steuerwährung, z. B. «GBP». */
    val target: String,
    val note: String,
    /** «Orientierung». */
    val orientation: String,
    val labels: Map<TaxLine, String>,
    val below: String,
    val reached: String,
    val unknown: String,
    val incomplete: String,
    val noFx: String,
    /** Kopf des Orientierungs-Blocks «Orientierung – keine Steuerberatung, keine Veranlagung»; leer = [orientation]. */
    val orientationHeading: String = "",
    /** Posten im Orientierungs-Block «Orientierung: %s»; leer = «[orientation]: Posten». */
    val orientationItem: String = "",
    /** Vermerk «nicht gerechnet – ESTV-Kursliste <Link>» ([TaxSummaryLine.notCalculated]); leer = keiner. */
    val notCalculated: String = "",
    /** Status «Verlust» ([TaxLimitStatus.LOSS]); leer = [below]. */
    val loss: String = "",
    /** Texte der Zusatz-Vermerke ([TaxSummaryLine.note]); fehlt einer = kein Vermerk. */
    val notes: Map<TaxSummaryNote, String> = emptyMap(),
)

/**
 * Posten im Orientierungs-Block: [item] mit «%s» für [label]; ohne [item] «[fallback]: [label]» (leer = nur [label]).
 * Jeder Schluss nach den Regeln einer Vorlage steht so sichtbar als Orientierung da (CSV und Karte).
 */
fun taxOrientationLabel(item: String, fallback: String, label: String): String = when {
    item.contains("%s") -> item.replace("%s", label)
    fallback.isNotEmpty() -> "$fallback: $label"
    else -> label
}

/**
 * Steuer-Exporte eines Kalenderjahrs (reines Kotlin, Spiegel: `TaxExport.swift`) — keine Steuerberatung:
 *  - Realisierte Gewinne: je Veräusserung mit Verkaufsdatum im Jahr ([PortfolioCalculator.disposals] nach der
 *    Einstandsmethode; FIFO je Posten, Ø je Verkauf) Coin, Kauf-/Verkaufsdatum, Haltedauer, «> 1 Jahr», Menge,
 *    Einstand, Erlös, Gewinn in USDT und in der Anzeigewährung. Umrechnung mit dem EZB-Tageskurs des jeweiligen
 *    Datums (letzter Geschäftstag davor, höchstens [FX_MAX_GAP_DAYS] Tage): Einstand je Herkunftsteil
 *    ([PortfolioDisposal.costParts]) zum Kurs SEINES Datums — FIFO: Kauf zum Kurs des Kaufdatums, eine später
 *    zugeschlagene Umbuchungsgebühr zum Kurs der Umbuchung (5000 USDT 2018 zu 1.00 + 100 USDT 2024 zu 0.85 = 5085 CHF);
 *    Ø: laufender Ø in der Anzeigewährung, ein Abgang nimmt alle Teile anteilig mit; fehlt ein Kurs, bleibt der
 *    Einstand leer —, Erlös zum Kurs des Verkaufsdatums, Gewinn = Differenz. Mehr verkauft als erfasst: der Rest als
 *    Zeile ohne Kaufpreis. Die Karte rechnet dagegen alle Beträge mit dem heutigen Kurs um (darum Hinweis «Die
 *    Steuer-CSV kann von ‹realisiert› abweichen»).
 *  - Info-Zeilen nach der Summe (kein Gewinn/Verlust): Netzwerkgebühr-Einstand ohne bezahlten Posten ([lostFeeCostsIn])
 *    und Umbuchungsgebühren auf Bestand ohne Kaufpreis ([unassignedFeesIn], «Gebühr nicht zugeordnet – Einstand
 *    unbekannt»).
 *  - Summe: Einstand/Erlös/Gewinn nur über Zeilen, bei denen alle drei bekannt sind (die Summe geht auf);
 *    dazu eine Zeile «Erlös ohne Kaufpreis» für Zeilen mit unbekanntem Einstand; fehlt etwas: «unvollständig».
 *  - «> 1 Jahr» ([isLongTerm]): Verkaufsdatum NACH Kaufdatum + 1 Kalenderjahr (mehr als ein Jahr wie
 *    § 23 EStG; Kauf 29.2. → Grenze 28.2.). Daten und Jahr in der Zeitzone des Geräts; sie steht in der CSV
 *    ([TaxCsvTexts.timeZone]).
 *  - Einkünfte: Belohnungen im Jahr mit Wert beim Erhalt (Menge × erfasster Wert je Coin), umgerechnet zum
 *    Kurs des Tags.
 *  - Bestand am Jahresende: der Stichtag-Export per 31.12. ([CutoffExport]).
 *  - Jahresübersicht DE ([deYearSummary], [deSummaryCsv]): kurz-/langfristig, Freigrenzen § 23 und § 22 Nr. 3 EStG.
 *  - Tausch: Veräusserung wie ein Verkauf (Vermerk [TaxCsvTexts.swap]); FIFO je Wallet: Vermerk [TaxCsvTexts.wallet].
 * Exakt mit [BigDecimal]; gerundet wird nur im Text (wie [CutoffExport]). CSV wie der Stichtag-Export: UTF-8 mit
 * BOM, CRLF, Trenn-/Dezimalzeichen je Region ([CutoffCsvFormat]); Kopfzeilen (Methode, Währung, keine
 * Steuerberatung, App-Version) gibt der Aufrufer übersetzt mit.
 */
object TaxExport {

    /** Devisenkurs höchstens so viele Tage vor dem Datum (Wochenende, Feiertage). */
    const val FX_MAX_GAP_DAYS = 7L

    private const val BOM = "\uFEFF"
    private const val EOL = "\r\n"

    /*
     * Steuerjahr und Daten bewusst in der Zeitzone des Geräts ([zone]): massgebend ist das rechtliche (lokale) Datum
     * des Geschäfts — ein Verkauf am 31.12. um 23:30 Zürich gehört ins alte Jahr, auch wenn es in UTC schon der 1.1.
     * ist. Wertverlauf, «Heute/7 T/1 J» und Tageskerzen rechnen dagegen mit UTC-Tagen ([PortfolioHistory.utcDayEnd]) —
     * eine Transaktion um Mitternacht kann im Chart daher auf einen anderen Tag fallen als in der Steuer-CSV.
     */
    fun localDate(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** Eigene Transaktionen (ohne Adress-Einträge). */
    private fun real(trades: List<PortfolioTrade>): List<PortfolioTrade> =
        trades.filter { !AddressBalance.isAddressTrade(it.id) && it.amount.signum() > 0 }

    /** Jahre mit Transaktionen, neueste zuerst. */
    fun years(trades: List<PortfolioTrade>, zone: ZoneId): List<Int> = years(trades, zone, TaxTemplate.NEUTRAL)

    /** Steuerjahre ([TaxTemplate.periodOf], Beginnjahr) mit Transaktionen, neueste zuerst. */
    fun years(trades: List<PortfolioTrade>, zone: ZoneId, template: TaxTemplate): List<Int> =
        real(trades).map { template.periodOf(localDate(it.time, zone)) }.distinct().sortedDescending()

    /** Standard: letztes volles Jahr mit Transaktionen (vor [today]), sonst das neueste; null ohne Jahre. */
    fun defaultYear(years: List<Int>, today: LocalDate): Int? = defaultYear(years, today, TaxTemplate.NEUTRAL)

    /** Standard: letztes abgeschlossenes Steuerjahr mit Transaktionen, sonst das neueste; null ohne Jahre. */
    fun defaultYear(years: List<Int>, today: LocalDate, template: TaxTemplate): Int? {
        val current = template.periodOf(today)
        return years.filter { it < current }.maxOrNull() ?: years.maxOrNull()
    }

    /** Mehr als ein Jahr gehalten: [sold] nach [acquired] + 1 Kalenderjahr. */
    fun isLongTerm(acquired: LocalDate, sold: LocalDate): Boolean = sold.isAfter(acquired.plusYears(1))

    /** Stichtag des Bestands am Jahresende. */
    fun yearEnd(year: Int): LocalDate = LocalDate.of(year, 12, 31)

    /** Kalenderjahr als Steuerjahr (Neutral). */
    private fun calendar(year: Int): TaxPeriod = TaxTemplate.NEUTRAL.period(year)

    fun gainsFileName(year: Int): String = "cryptochecker-gewinne-$year.csv"

    fun incomeFileName(year: Int): String = "cryptochecker-einkuenfte-$year.csv"

    fun deSummaryFileName(year: Int): String = "cryptochecker-jahresuebersicht-de-$year.csv"

    /** Dateiname der Jahresübersicht einer Vorlage, z. B. «cryptochecker-jahresuebersicht-uk-2025.csv». */
    fun summaryFileName(template: TaxTemplate, year: Int): String =
        "cryptochecker-jahresuebersicht-${template.name.lowercase()}-$year.csv"

    /**
     * Kopfzeilen der Arbeitsunterlage: Bei CH steht «Vermögenssteuer nicht gerechnet – Kursliste ESTV» ([chWealthNote])
     * gleich unter dem Titel (Zeile 2) — in jeder CSV der Vorlage, nicht nur als Vermerk weiter unten. Andere Vorlagen
     * oder leerer Text: unverändert. Wie `TaxExport.withChWealthNote` (iOS).
     */
    fun withChWealthNote(template: TaxTemplate, header: List<String>, chWealthNote: String): List<String> =
        if (template != TaxTemplate.CH || chWealthNote.isEmpty() || chWealthNote in header) header
        else header.take(1) + chWealthNote + header.drop(1)

    /**
     * Freigrenze für private Veräusserungsgeschäfte (§ 23 Abs. 3 Satz 5 EStG) in EUR: steuerfrei, wenn der Gesamtgewinn
     * des Jahres WENIGER als die Grenze beträgt (Freigrenze, kein Freibetrag): 1000 € ab 2024 (Wachstumschancengesetz),
     * 600 € 2009–2023, 512 € bis 2008.
     */
    fun privateSalesLimitEur(year: Int): BigDecimal = when {
        year >= 2024 -> BigDecimal(1000)
        year >= 2009 -> BigDecimal(600)
        else -> BigDecimal(512)
    }

    /** Freigrenze für Einkünfte aus Leistungen (§ 22 Nr. 3 Satz 2 EStG): steuerfrei, wenn weniger als 256 € im Jahr. */
    val INCOME_LIMIT_EUR: BigDecimal = BigDecimal(256)

    /**
     * USD → Zielwährung am [date]: USD = 1; sonst der Kurs des Tags oder des letzten Tags davor (höchstens
     * [FX_MAX_GAP_DAYS] Tage); [rates] = Tag (Tage seit 1970) → Kurs, null = nicht geladen.
     */
    fun rateOn(rates: Map<Long, BigDecimal>?, date: LocalDate, currency: String): BigDecimal? {
        if (currency.trim().uppercase() == "USD") return BigDecimal.ONE
        val day = date.toEpochDay()
        val best = rates.orEmpty().entries
            .filter { it.key <= day && day - it.key <= FX_MAX_GAP_DAYS && it.value.signum() > 0 }
            .maxByOrNull { it.key }
        return best?.value
    }

    /** Veräusserungen mit Verkaufsdatum im Jahr [year], wie [PortfolioCalculator.disposals] sortiert. */
    fun disposalsIn(trades: List<PortfolioTrade>, method: CostMethod, year: Int, zone: ZoneId): List<PortfolioDisposal> =
        disposalsIn(trades, TaxMethod.of(method), calendar(year), zone)

    /**
     * Veräusserungen nach [method] mit Verkaufsdatum im Steuerjahr [period] ([TaxEngines.disposals]; [fx] = USDT →
     * Steuerwährung zu einem Zeitpunkt, CA: Superficial Loss in CAD).
     */
    fun disposalsIn(
        trades: List<PortfolioTrade>,
        method: TaxMethod,
        period: TaxPeriod,
        zone: ZoneId,
        priceAt: ((String, Long) -> BigDecimal?)? = null,
        fx: ((Long) -> BigDecimal?)? = null,
        /** Schenkung und Netzwerkgebühr nach der Vorlage ([TaxTemplate.eventRules]); Standard nach der Rechnung. */
        rules: TaxEventRules = TaxEventRules.of(method),
        /** US-Übergang ([TaxTemplate.walletFrom]); null = keiner. */
        walletFrom: LocalDate? = null,
    ): List<PortfolioDisposal> =
        // Adress-Einträge bleiben drin: sie sind keine Veräusserungen (die Rechnungen trennen sie ab), FR braucht sie für
        // den Gesamtwert des Portfolios
        TaxEngines.disposals(trades.filter { it.amount.signum() > 0 }, method, zone, priceAt, fx, rules, walletFrom)
            .filter { localDate(it.sellTime, zone) in period }

    /**
     * Einstand von Netzwerkgebühren ohne bezahlten Posten ([PortfolioLostFeeCost]) mit Datum im Jahr [year], zeitlich —
     * für den Vermerk im Gewinne-Export ([gainsCsv] `lostFeeCosts`). Kein realisierter Gewinn/Verlust.
     */
    fun lostFeeCostsIn(trades: List<PortfolioTrade>, method: CostMethod, year: Int, zone: ZoneId): List<PortfolioLostFeeCost> =
        lostFeeCostsIn(trades, TaxMethod.of(method), calendar(year), zone)

    /**
     * Wie oben für ein Steuerjahr und jede Vorlage: Einstandsmethoden des Portfolios über [PortfolioCalculator.ledger],
     * eigene Rechnungen über [TaxEngines.lostFeeCosts]. Mit [TaxEventRules.networkFeeIsDisposal] (UK, US, CA, AU, JP) ist
     * die Netzwerkgebühr eine Veräusserung — kein Einstand bleibt liegen (leer); FR kennt keinen Gebühr-Einstand je Coin.
     */
    fun lostFeeCostsIn(
        trades: List<PortfolioTrade>,
        method: TaxMethod,
        period: TaxPeriod,
        zone: ZoneId,
        rules: TaxEventRules = TaxEventRules.of(method),
    ): List<PortfolioLostFeeCost> {
        val r = real(trades)
        val cm = method.costMethod
        val all = if (cm != null) {
            val applied = TaxEngines.applyRules(r, rules, null)
            PortfolioLedger.coins(applied).flatMap { PortfolioCalculator.ledger(it, applied, null, cm).lostFeeCosts }
        } else TaxEngines.lostFeeCosts(r, method, zone, rules)
        return all
            .filter { localDate(it.time, zone) in period }
            .sortedWith(compareBy<PortfolioLostFeeCost> { it.time }.thenBy { it.txId }.thenBy { it.coin })
    }

    /**
     * Umbuchungsgebühren auf Bestand ohne Kaufpreis ([PortfolioUnassignedFee]) mit Datum im Jahr [year], zeitlich — für
     * die Info-Zeile im Gewinne-Export ([gainsCsv] `unassignedFees`). Kein realisierter Gewinn/Verlust.
     */
    fun unassignedFeesIn(trades: List<PortfolioTrade>, method: CostMethod, year: Int, zone: ZoneId): List<PortfolioUnassignedFee> =
        unassignedFeesIn(trades, TaxMethod.of(method), calendar(year), zone)

    /**
     * Wie oben für ein Steuerjahr und jede Vorlage: Einstandsmethoden des Portfolios über [PortfolioCalculator.ledger]
     * (nach [TaxEngines.applyRules]: mit [TaxEventRules.quoteFeeIsInfo] zählt die Gebühr USDT einer Umbuchung ohnehin
     * nicht zum Einstand), eigene Rechnungen über [TaxEngines.unassignedFees].
     */
    fun unassignedFeesIn(
        trades: List<PortfolioTrade>,
        method: TaxMethod,
        period: TaxPeriod,
        zone: ZoneId,
        rules: TaxEventRules = TaxEventRules.of(method),
    ): List<PortfolioUnassignedFee> {
        val r = real(trades)
        val cm = method.costMethod
        val all = if (cm != null) {
            val applied = TaxEngines.applyRules(r, rules, null)
            PortfolioLedger.coins(applied).flatMap { PortfolioCalculator.ledger(it, applied, null, cm).unassignedFees }
        } else TaxEngines.unassignedFees(r, method, zone, rules)
        return all
            .filter { localDate(it.time, zone) in period }
            .sortedWith(compareBy<PortfolioUnassignedFee> { it.time }.thenBy { it.txId }.thenBy { it.coin })
    }

    /** Belohnungen im Steuerjahr [period], zeitlich (dann Id). */
    private fun rewardsIn(trades: List<PortfolioTrade>, period: TaxPeriod, zone: ZoneId): List<PortfolioTrade> =
        real(trades).filter { it.type == PortfolioTxType.REWARD && localDate(it.time, zone) in period }
            // Reihenfolge wie das Buch ([PortfolioLedger.ORDER])
            .sortedWith(compareBy(PortfolioLedger.ORDER) { PortfolioLedgerEvent(it, false) })

    /** Erster Tag, für den Devisenkurse gebraucht werden (frühestes Kaufdatum bzw. 1.1.), ohne Vorlauf. */
    fun fxFrom(trades: List<PortfolioTrade>, method: CostMethod, year: Int, zone: ZoneId): LocalDate =
        fxFrom(trades, TaxMethod.of(method), calendar(year), zone)

    /**
     * Wie oben für [period]: frühestes Kauf-/Herkunftsdatum der Veräusserungen bzw. Beginn des Steuerjahrs. CA: auch
     * aller früheren Veräusserungen — ein nicht abziehbarer Verlust eines Vorjahrs (im ACB dieses Jahrs) wird in CAD
     * entschieden und braucht die Kurse seiner Herkunft.
     */
    fun fxFrom(
        trades: List<PortfolioTrade>,
        method: TaxMethod,
        period: TaxPeriod,
        zone: ZoneId,
        priceAt: ((String, Long) -> BigDecimal?)? = null,
        rules: TaxEventRules = TaxEventRules.of(method),
        walletFrom: LocalDate? = null,
    ): LocalDate {
        // FIFO: Kaufdatum der Posten; Durchschnitt/Pool: Datum jedes Herkunftsteils des Einstands
        val upTo = if (method == TaxMethod.CA_ACB) TaxPeriod(period.startYear, LocalDate.MIN, period.end) else period
        val first = disposalsIn(trades, method, upTo, zone, priceAt, rules = rules, walletFrom = walletFrom)
            .flatMap { d -> listOfNotNull(d.acquiredTime, d.matchedTime) + d.costParts.map { it.time } }
            .minOrNull()?.let { localDate(it, zone) }
        return if (first != null && first.isBefore(period.start)) first else period.start
    }

    fun gainRows(
        trades: List<PortfolioTrade>,
        method: CostMethod,
        year: Int,
        zone: ZoneId,
        currency: String,
        rates: Map<Long, BigDecimal>?,
    ): List<TaxGainRow> = gainRows(trades, TaxMethod.of(method), calendar(year), zone, currency, rates)

    /**
     * Zeilen «Realisierte Veräusserungen» des Steuerjahrs [period] nach [method]. Einstand in der Anzeigewährung: mit
     * Herkunftsteilen ([PortfolioDisposal.costParts]) jeder Teil zum Kurs seines Datums, sonst zum Kurs des Kaufdatums;
     * Erlös, nicht abziehbarer Verlust, Gesamtwert und Abtretungspreis zum Kurs des Verkaufsdatums.
     */
    fun gainRows(
        trades: List<PortfolioTrade>,
        method: TaxMethod,
        period: TaxPeriod,
        zone: ZoneId,
        currency: String,
        rates: Map<Long, BigDecimal>?,
        priceAt: ((String, Long) -> BigDecimal?)? = null,
        /** Verkäufe, die Gebühren sind ([PortfolioLedger.isFeeDisposal]) — einmal gesammelt. */
        feeSales: Set<Long> = trades.filter(PortfolioLedger::isFeeDisposal).mapTo(HashSet()) { it.id },
        /**
         * Transaktionen ohne Verkauf (Umbuchung, Auszahlung, Tausch): ihre Veräusserungen ohne Tausch-Vermerk sind
         * Gebühren in Krypto (Netzwerkgebühr nach [TaxEventRules.networkFeeIsDisposal], FR Gebühr-Abtretung) — Vermerk «Gebühr».
         */
        nonSales: Set<Long> = trades.filter {
            it.type == PortfolioTxType.TRANSFER || it.type.isWithdrawalLike || it.type == PortfolioTxType.SWAP
        }.mapTo(HashSet()) { it.id },
        /** Schenkung und Netzwerkgebühr nach der Vorlage ([TaxTemplate.eventRules]); Standard nach der Rechnung. */
        rules: TaxEventRules = TaxEventRules.of(method),
        /** US-Übergang ([TaxTemplate.walletFrom]); null = keiner. */
        walletFrom: LocalDate? = null,
    ): List<TaxGainRow> = disposalsIn(
        trades, method, period, zone, priceAt,
        // CA: Superficial Loss in der Steuerwährung entscheiden (dieselben Kurse wie die Zeilen)
        fx = { t -> rateOn(rates, localDate(t, zone), currency) },
        rules = rules,
        walletFrom = walletFrom,
    ).map { d ->
        val sold = localDate(d.sellTime, zone)
        val acquired = d.acquiredTime?.let { localDate(it, zone) }
        val saleRate = rateOn(rates, sold, currency)
        fun atSale(v: BigDecimal?): BigDecimal? = v?.let { x -> saleRate?.let { x.multiply(it, PortfolioDecimal.MC) } }
        // Herkunftsteile je zum Kurs ihres Datums (fehlt einer: leer); FIFO-Posten zum Kurs des Kaufdatums
        var costFxMissing = false
        val costTarget: BigDecimal? = d.costUsdt?.let { c ->
            when {
                d.costParts.isNotEmpty() -> {
                    var sum: BigDecimal? = BigDecimal.ZERO
                    for (part in d.costParts) {
                        val r = rateOn(rates, localDate(part.time, zone), currency)
                        sum = if (r == null || sum == null) null else sum + part.usdt.multiply(r, PortfolioDecimal.MC)
                    }
                    sum
                }
                acquired != null -> rateOn(rates, acquired, currency)?.let { c.multiply(it, PortfolioDecimal.MC) }
                // Einstand 0 (z. B. Belohnung zum Wert 0): nichts umzurechnen
                c.signum() == 0 -> BigDecimal.ZERO
                else -> saleRate?.let { c.multiply(it, PortfolioDecimal.MC) }
            }.also { if (it == null) costFxMissing = true }
        }
        // Schenkung an Ehe-/Lebenspartner: kein Gewinn/Verlust auch in der Steuerwährung (TCGA 1992 s. 58, ITA 73(1)) —
        // der Erlös gilt als genau der umgerechnete Einstand, nicht als Einstand in USDT zum Kurs des Schenkungstags
        val proceedsTarget = if (d.spouseGift) costTarget else atSale(d.proceedsUsdt)
        TaxGainRow(
            coin = d.coin,
            acquiredDate = acquired ?: d.matchedTime?.let { localDate(it, zone) },
            soldDate = sold,
            holdingDays = acquired?.let { ChronoUnit.DAYS.between(it, sold) },
            longTerm = acquired?.let { isLongTerm(it, sold) },
            amount = d.amount,
            costUsdt = d.costUsdt,
            proceedsUsdt = d.proceedsUsdt,
            gainUsdt = d.gainUsdt,
            costTarget = costTarget,
            proceedsTarget = proceedsTarget,
            gainTarget = if (costTarget != null && proceedsTarget != null) proceedsTarget - costTarget else null,
            fxMissing = costFxMissing || (!d.spouseGift && d.proceedsUsdt != null && saleRate == null),
            feeLeg = d.feeLeg,
            feeValueMissing = d.feeValueMissing,
            method = d.method,
            uncovered = d.uncovered,
            swap = d.swap,
            wallet = d.wallet,
            match = d.match,
            adjustmentUsdt = d.adjustmentUsdt,
            adjustmentTarget = atSale(d.adjustmentUsdt),
            oldHolding = d.oldHolding,
            costIncomplete = d.costIncomplete,
            portfolioValueUsdt = d.portfolioValueUsdt,
            portfolioValueTarget = atSale(d.portfolioValueUsdt),
            grossProceedsUsdt = d.grossProceedsUsdt,
            grossProceedsTarget = atSale(d.grossProceedsUsdt),
            // Durchschnitt/Pool (Herkunftsteile ohne Posten-Datum); FIFO-Posten tragen ihre Teile ohne diesen Vermerk
            pooled = (d.method == CostMethod.AVERAGE && d.feeExpense == null) || (d.costParts.isNotEmpty() && d.acquiredTime == null),
            feeExpense = d.feeExpense,
            // Schenkung als Veräusserung ist keine Gebühr, auch wenn die Transaktion kein Verkauf ist
            feeRow = !d.feeLeg && d.feeExpense == null && !d.gift && !d.payment &&
                (d.sellTxId in feeSales || (!d.swap && d.sellTxId in nonSales)),
            gift = d.gift,
            payment = d.payment,
            valueEstimated = d.valueEstimated,
            spouseGift = d.spouseGift,
            // TRANSFER-Zeilen gibt es nur mit dieser Regel ([TaxEngines.disposals])
            feeInfoOnly = d.feeExpense == PortfolioFeeExpense.TRANSFER ||
                (rules.quoteFeeIsInfo && d.feeExpense == PortfolioFeeExpense.WITHDRAWAL),
        )
    }

    fun incomeRows(
        trades: List<PortfolioTrade>,
        year: Int,
        zone: ZoneId,
        currency: String,
        rates: Map<Long, BigDecimal>?,
    ): List<TaxIncomeRow> = incomeRows(trades, calendar(year), zone, currency, rates)

    /** Belohnungen des Steuerjahrs [period] mit Wert beim Erhalt (Menge × Wert je Coin), zum Kurs des Tags. */
    fun incomeRows(
        trades: List<PortfolioTrade>,
        period: TaxPeriod,
        zone: ZoneId,
        currency: String,
        rates: Map<Long, BigDecimal>?,
    ): List<TaxIncomeRow> = rewardsIn(trades, period, zone).map { t ->
        val date = localDate(t.time, zone)
        val value = t.validPrice?.let { t.amount * it }
        val rate = rateOn(rates, date, currency)
        TaxIncomeRow(
            date = date,
            coin = PortfolioCalculator.normalizeCoin(t.coin),
            amount = t.amount,
            valueUsdt = value,
            valueTarget = value?.let { v -> rate?.let { v.multiply(it, PortfolioDecimal.MC) } },
            fxMissing = value != null && rate == null,
            interest = t.interestIncome,
        )
    }

    /** Summe der bekannten Werte; null, wenn keiner bekannt ist (und es Zeilen gibt). */
    private fun <T> sum(rows: List<T>, pick: (T) -> BigDecimal?): BigDecimal? {
        val known = rows.mapNotNull(pick)
        if (known.isEmpty()) return if (rows.isEmpty()) BigDecimal.ZERO else null
        return known.fold(BigDecimal.ZERO) { a, b -> a + b }
    }

    /**
     * CSV «Realisierte Gewinne» mit BOM; [header] = Kopfzeilen (je ein Feld), danach die Zeitzone
     * ([TaxCsvTexts.timeZone]). Summe nur über vollständige Zeilen (Einstand, Erlös und Gewinn bekannt — je für
     * USDT und Anzeigewährung), so geht Erlös − Einstand = Gewinn auf; Zeilen mit unbekanntem Einstand summiert
     * die Zeile «Erlös ohne Kaufpreis». Zusatzspalten vor «Hinweis» nur, wenn eine Zeile sie hat: nicht abziehbarer
     * Verlust (CA), Abtretungspreis vor Gebühren und Gesamtwert des Portfolios (FR). Textfelder sind gegen
     * Formel-Injektion geschützt ([CutoffExport.escape]), Zahlen kommen unverändert vom Zahlenformat.
     */
    fun gainsCsv(
        rows: List<TaxGainRow>,
        texts: TaxCsvTexts,
        header: List<String>,
        format: CutoffCsvFormat = CutoffCsvFormat.SEMICOLON_POINT,
        /** [lostFeeCostsIn]: je eine Zeile nach der Summe (Datum, Einstand USDT, Vermerk [TaxCsvTexts.lostFeeCost]). */
        lostFeeCosts: List<PortfolioLostFeeCost> = emptyList(),
        zone: ZoneId = ZoneId.of("UTC"),
        /** [unassignedFeesIn]: je eine Zeile nach der Summe (Datum, Betrag USDT, Vermerk [TaxCsvTexts.unassignedFee]). */
        unassignedFees: List<PortfolioUnassignedFee> = emptyList(),
    ): String {
        fun n(text: String): String = if (format.decimal == ',') text.replace('.', ',') else text
        fun money(v: BigDecimal?): CsvCell = CsvCell(n(v?.let { CutoffExport.money(it) }.orEmpty()), numeric = true)
        fun t(v: String): CsvCell = CsvCell(v)
        val adj = rows.any { it.adjustmentUsdt != null }
        val fr = rows.any { it.portfolioValueUsdt != null || it.grossProceedsUsdt != null }
        /** Zusatzspalten einer Zeile (leer, wenn es sie nicht gibt). */
        fun extra(a: BigDecimal?, aT: BigDecimal?, g: BigDecimal?, gT: BigDecimal?, v: BigDecimal?, vT: BigDecimal?): List<CsvCell> =
            (if (adj) listOf(money(a), money(aT)) else emptyList()) +
                (if (fr) listOf(money(g), money(gT), money(v), money(vT)) else emptyList())
        val blank = CsvCell("", numeric = true)
        val sb = StringBuilder(BOM)
        header.forEach { sb.append(line(listOf(t(it)), format)) }
        if (texts.timeZone.isNotEmpty()) sb.append(line(listOf(t(texts.timeZone)), format))
        sb.append(
            line(
                listOf(
                    texts.coin, texts.acquired, texts.sold, texts.holdingDays, texts.longTerm, texts.amount,
                    texts.costUsdt, texts.proceedsUsdt, texts.gainUsdt,
                    texts.costTarget, texts.proceedsTarget, texts.gainTarget,
                ).map { t(it) } +
                    (if (adj) listOf(t(texts.adjustmentUsdt), t(texts.adjustmentTarget)) else emptyList()) +
                    (if (fr) listOf(t(texts.grossUsdt), t(texts.grossTarget), t(texts.portfolioValueUsdt), t(texts.portfolioValueTarget)) else emptyList()) +
                    t(texts.note),
                format,
            )
        )
        for (r in rows) {
            val notes = listOfNotNull(
                when (r.feeExpense) {
                    PortfolioFeeExpense.CONSUMED -> texts.feeConsumed
                    PortfolioFeeExpense.WITHDRAWAL -> texts.withdrawalFeeInfo.takeIf { r.feeInfoOnly && it.isNotEmpty() } ?: texts.withdrawalFee
                    PortfolioFeeExpense.TRANSFER -> texts.transferFeeInfo
                    null -> null
                }?.takeIf { it.isNotEmpty() },
                texts.feeRow.takeIf { r.feeRow && it.isNotEmpty() },
                texts.gift.takeIf { r.gift && it.isNotEmpty() },
                texts.spouseGift.takeIf { r.spouseGift && it.isNotEmpty() },
                texts.payment.takeIf { r.payment && it.isNotEmpty() },
                texts.valueEstimated.takeIf { r.valueEstimated && it.isNotEmpty() },
                when (r.match) {
                    TaxMatch.SAME_DAY -> texts.matchSameDay
                    TaxMatch.THIRTY_DAY -> texts.matchThirtyDay
                    TaxMatch.POOL -> texts.matchPool
                    null -> null
                }?.takeIf { it.isNotEmpty() },
                when (r.oldHolding) {
                    true -> texts.oldHolding
                    false -> texts.newHolding
                    null -> null
                }?.takeIf { it.isNotEmpty() },
                texts.swap.takeIf { r.swap && it.isNotEmpty() },
                r.wallet?.takeIf { texts.wallet.isNotEmpty() }?.let { w ->
                    texts.wallet.replace("%s", w.ifEmpty { texts.walletUnspecified })
                },
                texts.feeLeg.takeIf { r.feeLeg },
                texts.costMissing.takeIf { r.costMissing },
                texts.oversold.takeIf { r.uncovered && it.isNotEmpty() },
                texts.proceedsMissing.takeIf { r.proceedsMissing },
                texts.feeValueMissing.takeIf { r.feeValueMissing },
                texts.costIncomplete.takeIf { r.costIncomplete && it.isNotEmpty() },
                texts.adjustment.takeIf { r.adjustmentUsdt != null && it.isNotEmpty() },
                texts.avgCostRates.takeIf { r.pooled && r.costTarget != null },
                texts.noFx.takeIf { r.fxMissing },
            ).joinToString(", ")
            sb.append(
                line(
                    listOf(
                        t(r.coin),
                        t(r.acquiredDate?.toString().orEmpty()),
                        t(r.soldDate.toString()),
                        CsvCell(r.holdingDays?.toString().orEmpty(), numeric = true),
                        t(when (r.longTerm) { true -> texts.yes; false -> texts.no; null -> "" }),
                        CsvCell(n(CutoffExport.amount(r.amount)), numeric = true),
                        money(r.costUsdt), money(r.proceedsUsdt), money(r.gainUsdt),
                        money(r.costTarget), money(r.proceedsTarget), money(r.gainTarget),
                    ) + extra(r.adjustmentUsdt, r.adjustmentTarget, r.grossProceedsUsdt, r.grossProceedsTarget, r.portfolioValueUsdt, r.portfolioValueTarget) +
                        t(notes),
                    format,
                )
            )
        }
        // Summe nur vollständiger Zeilen; fehlt irgendwo etwas, «unvollständig». Gebühren nur zur Information
        // ([TaxGainRow.feeInfoOnly]) zählen nicht mit
        val counted = rows.filter { !it.feeInfoOnly }
        val incomplete = counted.any { it.costMissing || it.proceedsMissing || it.feeValueMissing || it.fxMissing || it.costIncomplete }
        val usdt = counted.filter { it.completeUsdt }
        val target = counted.filter { it.completeTarget }
        fun total(list: List<TaxGainRow>, pick: (TaxGainRow) -> BigDecimal?): BigDecimal? =
            if (list.isEmpty() && counted.isNotEmpty()) null else sum(list, pick)
        sb.append(
            line(
                listOf(
                    t(texts.total), t(""), t(""), blank, t(""), blank,
                    money(total(usdt) { it.costUsdt }), money(total(usdt) { it.proceedsUsdt }), money(total(usdt) { it.gainUsdt }),
                    money(total(target) { it.costTarget }), money(total(target) { it.proceedsTarget }), money(total(target) { it.gainTarget }),
                ) + extra(
                    sum(counted) { it.adjustmentUsdt }, sum(counted) { it.adjustmentTarget },
                    sum(counted) { it.grossProceedsUsdt }, sum(counted) { it.grossProceedsTarget }, null, null,
                ) + t(if (incomplete) texts.incomplete else ""),
                format,
            )
        )
        // Erlös der Zeilen ohne Kaufpreis (nicht in der Summe oben)
        val noCost = counted.filter { it.costMissing && it.proceedsUsdt != null }
        if (noCost.isNotEmpty()) {
            sb.append(
                line(
                    listOf(
                        t(texts.proceedsWithoutCost), t(""), t(""), blank, t(""), blank,
                        blank, money(sum(noCost) { it.proceedsUsdt }), blank,
                        blank, money(sum(noCost) { it.proceedsTarget }), blank,
                    ) + extra(null, null, null, null, null, null) + t(texts.incomplete),
                    format,
                )
            )
        }
        // Netzwerkgebühr-Einstand ohne bezahlten Posten: kein Gewinn/Verlust, aber nicht still verloren
        if (texts.lostFeeCost.isNotEmpty()) {
            for (l in lostFeeCosts) {
                sb.append(
                    line(
                        listOf(
                            t(l.coin), t(""), t(localDate(l.time, zone).toString()), blank, t(""), blank,
                            money(l.costUsdt), blank, blank, blank, blank, blank,
                        ) + extra(null, null, null, null, null, null) + t(texts.lostFeeCost),
                        format,
                    )
                )
            }
        }
        // Umbuchungsgebühr auf Bestand ohne Kaufpreis: offener Posten, sichtbar statt still verworfen
        if (texts.unassignedFee.isNotEmpty()) {
            for (f in unassignedFees) {
                sb.append(
                    line(
                        listOf(
                            t(f.coin), t(""), t(localDate(f.time, zone).toString()), blank, t(""), blank,
                            money(f.usdt), blank, blank, blank, blank, blank,
                        ) + extra(null, null, null, null, null, null) + t(texts.unassignedFee),
                        format,
                    )
                )
            }
        }
        return sb.toString()
    }

    /** CSV «Einkünfte» mit BOM; [header] = Kopfzeilen (je ein Feld). */
    fun incomeCsv(
        rows: List<TaxIncomeRow>,
        texts: TaxCsvTexts,
        header: List<String>,
        format: CutoffCsvFormat = CutoffCsvFormat.SEMICOLON_POINT,
    ): String {
        fun n(text: String): String = if (format.decimal == ',') text.replace('.', ',') else text
        fun money(v: BigDecimal?): CsvCell = CsvCell(n(v?.let { CutoffExport.money(it) }.orEmpty()), numeric = true)
        fun t(v: String): CsvCell = CsvCell(v)
        val sb = StringBuilder(BOM)
        header.forEach { sb.append(line(listOf(t(it)), format)) }
        if (texts.timeZone.isNotEmpty()) sb.append(line(listOf(t(texts.timeZone)), format))
        sb.append(line(listOf(texts.date, texts.coin, texts.amount, texts.valueUsdt, texts.valueTarget, texts.note).map { t(it) }, format))
        for (r in rows) {
            val notes = listOfNotNull(
                texts.valueMissing.takeIf { r.valueMissing },
                texts.noFx.takeIf { r.fxMissing },
            ).joinToString(", ")
            sb.append(
                line(
                    listOf(
                        t(r.date.toString()), t(r.coin), CsvCell(n(CutoffExport.amount(r.amount)), numeric = true),
                        money(r.valueUsdt), money(r.valueTarget), t(notes),
                    ),
                    format,
                )
            )
        }
        val incomplete = rows.any { it.valueMissing || it.fxMissing }
        sb.append(
            line(
                listOf(
                    t(texts.total), t(""), CsvCell("", numeric = true), money(sum(rows) { it.valueUsdt }), money(sum(rows) { it.valueTarget }),
                    t(if (incomplete) texts.incomplete else ""),
                ),
                format,
            )
        )
        return sb.toString()
    }

    /**
     * Jahresübersicht Deutschland für [year] — ORIENTIERUNG, KEINE STEUERBERATUNG. Aus den Veräusserungen nach [method]
     * ([gainRows], in EUR mit [eurRates] = USD → EUR je Tag; null = keine EUR-Kurse):
     *  - kurzfristig (≤ 1 Jahr, [isLongTerm] false): Gewinne und Verluste getrennt, netto = Summe (§ 23 EStG);
     *  - langfristig (> 1 Jahr): in DE steuerfrei, nur zur Orientierung;
     *  - ohne Haltedauer (Ø-Methode, mehr verkauft als erfasst): eigener Posten, die Prüfung bleibt offen.
     * Freigrenze § 23: netto kurzfristig in EUR < 0 → [TaxLimitStatus.LOSS] (nur mit § 23-Gewinnen verrechenbar), sonst
     * < [privateSalesLimitEur] → [TaxLimitStatus.BELOW], sonst REACHED (der ganze Betrag ist steuerpflichtig); UNKNOWN ohne EUR-Kurse oder wenn ein kurzfristiger/unklassierter Gewinn
     * unbekannt ist, die Haltedauer fehlt oder ein EUR-Kurs fehlt. Die Grenze gilt für alle privaten
     * Veräusserungsgeschäfte des Jahres zusammen (auch ausserhalb der App).
     * Einkünfte aus Leistungen (§ 22 Nr. 3): Belohnungen ([incomeRows]) — < 256 € → BELOW; gilt für alle Leistungen
     * zusammen. Gezählt wird nur Bekanntes; was fehlt, steht in den Zählern.
     */
    fun deYearSummary(
        trades: List<PortfolioTrade>,
        method: CostMethod,
        year: Int,
        zone: ZoneId,
        eurRates: Map<Long, BigDecimal>?,
    ): TaxYearSummaryDe {
        val zero = BigDecimal.ZERO
        val eur = eurRates != null
        val all = gainRows(trades, method, year, zone, "EUR", eurRates ?: emptyMap())
        // Auszahlungsgebühren: keine Veräusserung — eigener Posten, nicht in § 23 (Summe ≤ 1 Jahr, Freigrenze)
        val (feeRows, rows) = all.partition { it.feeExpense == PortfolioFeeExpense.WITHDRAWAL }
        val feeUsdt = feeRows.fold(zero) { a, r -> a + (r.gainUsdt ?: zero) }
        val feeEur = feeRows.fold(zero) { a, r -> a + (r.gainTarget ?: zero) }
        var sg = zero; var sl = zero; var lg = zero; var ll = zero; var un = zero
        var sgE = zero; var slE = zero; var lgE = zero; var llE = zero; var unE = zero
        var unknownGain = 0
        var unclassified = 0
        var fxMissing = 0
        for (r in rows) {
            val g = r.gainUsdt
            val gE = r.gainTarget
            when (r.longTerm) {
                true -> {
                    if (g != null) { if (g.signum() > 0) lg += g else ll += g }
                    if (gE != null) { if (gE.signum() > 0) lgE += gE else llE += gE }
                }
                false -> {
                    if (g == null) unknownGain++
                    else { if (g.signum() > 0) sg += g else sl += g }
                    if (gE != null) { if (gE.signum() > 0) sgE += gE else slE += gE }
                    else if (g != null && eur) fxMissing++
                }
                null -> {
                    unclassified++
                    if (g == null) unknownGain++ else un += g
                    if (gE != null) unE += gE else if (g != null && eur) fxMissing++
                }
            }
        }
        val netE = sgE + slE
        val limit = privateSalesLimitEur(year)
        val salesStatus = when {
            !eur || unknownGain > 0 || unclassified > 0 || fxMissing > 0 -> TaxLimitStatus.UNKNOWN
            // Netto-Verlust: nicht «unter der Freigrenze», sondern eigener Status (nur mit § 23-Gewinnen verrechenbar)
            netE.signum() < 0 -> TaxLimitStatus.LOSS
            netE < limit -> TaxLimitStatus.BELOW
            else -> TaxLimitStatus.REACHED
        }
        val income = incomeRows(trades, year, zone, "EUR", eurRates ?: emptyMap())
        val incomeUsdt = income.fold(zero) { a, r -> a + (r.valueUsdt ?: zero) }
        val incomeEur = income.fold(zero) { a, r -> a + (r.valueTarget ?: zero) }
        val valueMissing = income.count { it.valueMissing }
        val incomeFx = if (eur) income.count { it.fxMissing } else 0
        val incomeStatus = when {
            !eur || valueMissing > 0 || incomeFx > 0 -> TaxLimitStatus.UNKNOWN
            incomeEur < INCOME_LIMIT_EUR -> TaxLimitStatus.BELOW
            else -> TaxLimitStatus.REACHED
        }
        return TaxYearSummaryDe(
            year = year, method = method,
            shortGainsUsdt = sg, shortLossesUsdt = sl, netShortUsdt = sg + sl,
            longGainsUsdt = lg, longLossesUsdt = ll, unclassifiedUsdt = un,
            eurAvailable = eur,
            shortGainsEur = sgE.takeIf { eur }, shortLossesEur = slE.takeIf { eur }, netShortEur = netE.takeIf { eur },
            longGainsEur = lgE.takeIf { eur }, longLossesEur = llE.takeIf { eur }, unclassifiedEur = unE.takeIf { eur },
            unknownGainCount = unknownGain, unclassifiedCount = unclassified, eurFxMissingCount = fxMissing,
            withdrawalFeesUsdt = feeUsdt, withdrawalFeesEur = feeEur.takeIf { eur }, withdrawalFeeCount = feeRows.size,
            privateSalesLimitEur = limit, privateSalesStatus = salesStatus,
            incomeUsdt = incomeUsdt, incomeEur = incomeEur.takeIf { eur },
            incomeValueMissingCount = valueMissing, incomeFxMissingCount = incomeFx,
            incomeLimitEur = INCOME_LIMIT_EUR, incomeStatus = incomeStatus,
        )
    }

    /**
     * CSV «Jahresübersicht DE» mit BOM: [header] (je ein Feld), Titel, Hinweis «Orientierung, keine Steuerberatung»,
     * Zeitzone ([zoneLine], leer = keine), dann Posten | USDT | EUR | Hinweis. EUR leer ohne EUR-Kurse. Zuerst Tatsachen
     * (Summen, Einkünfte), dann der Orientierungs-Block (Kopf [TaxDeTexts.orientationHeading], Freigrenzen mit Vorsatz).
     */
    fun deSummaryCsv(
        s: TaxYearSummaryDe,
        texts: TaxDeTexts,
        header: List<String>,
        zoneLine: String = "",
        format: CutoffCsvFormat = CutoffCsvFormat.SEMICOLON_POINT,
    ): String {
        fun n(text: String): String = if (format.decimal == ',') text.replace('.', ',') else text
        fun money(v: BigDecimal?): String = n(v?.let { CutoffExport.money(it) }.orEmpty())
        fun status(st: TaxLimitStatus): String = when (st) {
            TaxLimitStatus.BELOW -> texts.below
            TaxLimitStatus.REACHED -> texts.reached
            TaxLimitStatus.UNKNOWN -> texts.unknown
            TaxLimitStatus.LOSS -> texts.loss.ifEmpty { texts.below }
        }
        val sb = StringBuilder(BOM)
        header.forEach { sb.append(textLine(listOf(it), format)) }
        sb.append(textLine(listOf(texts.title), format))
        sb.append(textLine(listOf(texts.disclaimer), format))
        if (zoneLine.isNotEmpty()) sb.append(textLine(listOf(zoneLine), format))
        sb.append(textLine(listOf(texts.item, texts.usdt, texts.eur, texts.note), format))
        val shortIncomplete = s.unknownGainCount > 0 || s.eurFxMissingCount > 0
        sb.append(textLine(listOf(texts.shortGains, money(s.shortGainsUsdt), money(s.shortGainsEur), ""), format, setOf(1, 2)))
        sb.append(textLine(listOf(texts.shortLosses, money(s.shortLossesUsdt), money(s.shortLossesEur), ""), format, setOf(1, 2)))
        sb.append(
            textLine(
                listOf(
                    texts.netShort, money(s.netShortUsdt), money(s.netShortEur),
                    listOfNotNull(
                        texts.incomplete.takeIf { s.unknownGainCount > 0 },
                        texts.noFx.takeIf { s.eurFxMissingCount > 0 },
                    ).joinToString(", "),
                ),
                format,
                setOf(1, 2),
            )
        )
        sb.append(textLine(listOf(texts.longGains, money(s.longGainsUsdt), money(s.longGainsEur), ""), format, setOf(1, 2)))
        sb.append(textLine(listOf(texts.longLosses, money(s.longLossesUsdt), money(s.longLossesEur), ""), format, setOf(1, 2)))
        if (s.unclassifiedCount > 0) {
            sb.append(textLine(listOf(texts.unclassified, money(s.unclassifiedUsdt), money(s.unclassifiedEur), texts.needsHolding), format, setOf(1, 2)))
        }
        // Auszahlungsgebühren: eigener Posten, nicht mit § 23 verrechnet
        if (s.withdrawalFeeCount > 0 && texts.withdrawalFees.isNotEmpty()) {
            sb.append(
                textLine(listOf(texts.withdrawalFees, money(s.withdrawalFeesUsdt), money(s.withdrawalFeesEur), texts.withdrawalFeeNote), format, setOf(1, 2))
            )
        }
        sb.append(
            textLine(
                listOf(
                    texts.income, money(s.incomeUsdt), money(s.incomeEur),
                    listOfNotNull(
                        texts.incomplete.takeIf { s.incomeValueMissingCount > 0 },
                        texts.noFx.takeIf { s.incomeFxMissingCount > 0 },
                    ).joinToString(", "),
                ),
                format,
                setOf(1, 2),
            )
        )
        // Freigrenzen nur im Orientierungs-Block (Kopf, jeder Posten mit Vorsatz «Orientierung:»), nie bei den Tatsachen
        fun oriented(label: String): String = taxOrientationLabel(texts.orientationItem, "", label)
        sb.append(textLine(listOf(texts.orientationHeading.ifEmpty { texts.disclaimer }), format))
        val salesNote = listOfNotNull(
            status(s.privateSalesStatus),
            texts.needsEur.takeIf { !s.eurAvailable },
            texts.needsHolding.takeIf { s.unclassifiedCount > 0 && s.eurAvailable },
            texts.incomplete.takeIf { shortIncomplete && s.eurAvailable && s.unclassifiedCount == 0 },
        ).joinToString(", ")
        sb.append(textLine(listOf(oriented(texts.privateSalesLimit), "", money(s.privateSalesLimitEur), salesNote), format, setOf(1, 2)))
        val incomeNote = listOfNotNull(status(s.incomeStatus), texts.needsEur.takeIf { !s.eurAvailable }).joinToString(", ")
        sb.append(textLine(listOf(oriented(texts.incomeLimit), "", money(s.incomeLimitEur), incomeNote), format, setOf(1, 2)))
        if (texts.limitScope.isNotEmpty()) sb.append(textLine(listOf(oriented(texts.limitScope)), format))
        return sb.toString()
    }

    /**
     * Summe eines Teils der Zeilen: USDT, Steuerwährung, Anzahl ohne Gewinn, Anzahl mit Gewinn aus unvollständigem
     * Einstand (FR: Erwerbspreis-Summe nicht gesichert, [TaxGainRow.costIncomplete]), Anzahl ohne Devisenkurs.
     */
    private class Acc(rows: List<TaxGainRow>, pick: (BigDecimal) -> Boolean, fx: Boolean) {
        var usdt: BigDecimal = BigDecimal.ZERO
        var target: BigDecimal = BigDecimal.ZERO
        var unknown = 0
        var partial = 0
        var fxMissing = 0

        init {
            for (r in rows) {
                val g = r.gainUsdt
                if (g == null) { unknown++; continue }
                // Gewinn oder Verlust entscheidet die Steuerwährung (ein USDT-Verlust kann in CAD/EUR ein Gewinn sein);
                // ohne Kurs USDT
                val gT = r.gainTarget
                if (!pick(if (fx && gT != null) gT else g)) continue
                usdt += g
                // Gewinn aus unvollständigem Einstand: gezählt, aber der Posten ist «unvollständig»
                if (r.costIncomplete) partial++
                if (gT != null) target += gT else if (fx) fxMissing++
            }
        }

        /** Ein Gewinn fehlt oder beruht auf unvollständigem Einstand. */
        val incomplete: Boolean get() = unknown > 0 || partial > 0

        fun line(l: TaxLine, fx: Boolean, countUnknown: Boolean = true, note: TaxSummaryNote? = null): TaxSummaryLine =
            TaxSummaryLine(
                l, usdt, target.takeIf { fx }, incomplete = (countUnknown && unknown > 0) || partial > 0, noFx = fxMissing > 0,
                note = note,
            )
    }

    /**
     * Jahresübersicht der Vorlage [template] für das Steuerjahr ab [startYear] — ORIENTIERUNG, KEINE STEUERBERATUNG.
     * Aus den Zeilen ([gainRows] nach [method], in [currency] mit [rates]; null = keine Kurse) und den Einkünften:
     *  - Tatsachen: Gewinne (> 0), Verluste (≤ 0) und netto — mit Haltedauer-Gruppen ≤ 1 / > 1 Jahr bei DE, US, AU und
     *    Neutral (ohne Haltedauer: eigener Posten), AT getrennt nach Neubestand und Altbestand ≤ / > 1 Jahr; CA nicht
     *    abziehbare Verluste; Erlöse (FR: Abtretungspreise vor Gebühren); Einkünfte (Belohnungen zum Wert beim Erhalt).
     *  - AT Einkünfte: Belohnungen nur Information ([TaxSummaryNote.AT_REWARD_INFO]) — sie stecken mit Anschaffungskosten 0
     *    im Neubestand und zählen erst beim Verkauf (kein Doppel aus Zufluss und Verkaufsgewinn); Zinsen aus Lending
     *    ([TaxLine.AT_INTEREST]) sind dagegen beim Zufluss Einkünfte (§ 27b Abs. 2 Z 1 EStG) und zählen.
     *  - Orientierung ([TaxLine.orientation]): DE Freigrenzen § 23 (netto ≤ 1 Jahr < Grenze; netto ein Verlust: eigener
     *    Status [TaxLimitStatus.LOSS]) und § 22 Nr. 3 (< 256 €),
     *    > 1 Jahr nicht steuerbar; AT 27,5 % auf Neubestand netto + Zinsen (Verlustausgleich innerhalb der Einkünfte zum
     *    Sondersteuersatz, § 27 Abs. 8 EStG), Altbestand > 1 Jahr nicht steuerbar; CH ohne Einordnung:
     *    Kapitalgewinne «nicht gerechnet – siehe ESTV» (kein Betrag), Belohnungen aufgeführt (Betrag, Behandlung siehe
     *    ESTV-Arbeitspapier), Vermögenssteuer «nicht gerechnet» (ESTV-Kursliste; Vermerk: Bestand-CSV per 31.12. =
     *    Börsenkurs × USD/CHF, [TaxSummaryNote.CH_EXCHANGE_VALUE]); UK Annual Exempt Amount und netto darüber;
     *    US kurz-/langfristig; CA steuerbarer Anteil (netto + nicht abziehbar) × 1/2; AU Verluste zuerst gegen Gewinne
     *    ≤ 12 Monate, dann 50 % Rabatt auf den Rest > 12 Monate; FR Abtretungen ≤ 305 € befreit (sonst ganz steuerbar);
     *    JP sonstige Einkünfte = netto + Einkünfte.
     * Gezählt wird nur Bekanntes; was fehlt, steht als «unvollständig» bzw. «ohne Devisenkurs» am Posten (auch ein Gewinn
     * aus unvollständigem Einstand, FR). Gewinn oder Verlust entscheidet die Steuerwährung (ohne Kurs USDT). CH: Gewinne,
     * Verluste, netto nur als Information ([TaxSummaryNote.CH_FIFO_INFO]).
     */
    fun yearSummary(
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
    ): TaxYearSummary {
        val zero = BigDecimal.ZERO
        val period = template.period(startYear)
        val fx = rates != null || currency.trim().uppercase() == "USD"
        val allRows = gainRows(
            trades, method, period, zone, currency, rates ?: emptyMap(), priceAt, rules = template.eventRules,
            walletFrom = walletFrom,
        )
        // DE/AT: Auszahlungsgebühren sind keine Veräusserung — eigener Posten, nicht in den Summen (DE: § 23 und Freigrenze;
        // AT: Aufwendungen beim Sondersteuersatz nicht abzugsfähig, § 20 Abs. 2 EStG); UK, US, CA, AU, JP: kein Verlust, nur
        // Information ([TaxTemplate.quoteFeeIsInfo])
        val feeApart = template == TaxTemplate.DE || template == TaxTemplate.AT || template.quoteFeeIsInfo
        val (withdrawalFees, sales) = if (feeApart) allRows.partition { it.feeExpense == PortfolioFeeExpense.WITHDRAWAL }
        else emptyList<TaxGainRow>() to allRows
        // Umbuchungsgebühren nur zur Information ([PortfolioFeeExpense.TRANSFER], Build 57): eigener Posten
        val (transferFees, rows) = sales.partition { it.feeExpense == PortfolioFeeExpense.TRANSFER }
        // AT: Zinsen eigener Posten (beim Zufluss Einkunft), übrige Belohnungen bleiben «Einkünfte»
        val (interest, income) = incomeRows(trades, period, zone, currency, rates ?: emptyMap())
            .partition { template == TaxTemplate.AT && it.interest }
        val pos: (BigDecimal) -> Boolean = { it.signum() > 0 }
        val neg: (BigDecimal) -> Boolean = { it.signum() <= 0 }
        val all: (BigDecimal) -> Boolean = { true }
        val lines = ArrayList<TaxSummaryLine>()
        val net: Acc
        val short = rows.filter { it.longTerm == false }
        val long = rows.filter { it.longTerm == true }
        val unclassifiedRows = rows.filter { it.longTerm == null }
        if (template.holdingBuckets) {
            lines += Acc(short, pos, fx).line(TaxLine.SHORT_GAINS, fx, false)
            lines += Acc(short, neg, fx).line(TaxLine.SHORT_LOSSES, fx, false)
            lines += Acc(short, all, fx).line(TaxLine.SHORT_NET, fx)
            lines += Acc(long, pos, fx).line(TaxLine.LONG_GAINS, fx, false)
            lines += Acc(long, neg, fx).line(TaxLine.LONG_LOSSES, fx, false)
            lines += Acc(long, all, fx).line(TaxLine.LONG_NET, fx)
            if (unclassifiedRows.isNotEmpty()) lines += Acc(unclassifiedRows, all, fx).line(TaxLine.UNCLASSIFIED, fx)
            net = Acc(rows, all, fx)
        } else if (template == TaxTemplate.AT) {
            val old = rows.filter { it.oldHolding == true }
            net = Acc(rows.filter { it.oldHolding != true }, all, fx)
            lines += Acc(rows.filter { it.oldHolding != true }, pos, fx).line(TaxLine.GAINS, fx, false)
            lines += Acc(rows.filter { it.oldHolding != true }, neg, fx).line(TaxLine.LOSSES, fx, false)
            lines += net.line(TaxLine.NEW_NET, fx)
            if (old.isNotEmpty()) {
                lines += Acc(old.filter { it.longTerm != true }, all, fx).line(TaxLine.OLD_SHORT_NET, fx)
                lines += Acc(old.filter { it.longTerm == true }, all, fx).line(TaxLine.OLD_LONG_NET, fx)
            }
        } else {
            net = Acc(rows, all, fx)
            // CH: die App ordnet Kapitalgewinne nicht ein — die FIFO-Summen sind nur Information, keine Tatsache der
            // Veranlagung (Vermerk wie «nicht gerechnet – siehe ESTV»)
            val info = TaxSummaryNote.CH_FIFO_INFO.takeIf { template == TaxTemplate.CH }
            lines += Acc(rows, pos, fx).line(TaxLine.GAINS, fx, false, info)
            lines += Acc(rows, neg, fx).line(TaxLine.LOSSES, fx, false, info)
            lines += net.line(TaxLine.NET, fx, note = info)
        }
        if (withdrawalFees.isNotEmpty()) {
            val note = when (template) {
                TaxTemplate.AT -> TaxSummaryNote.AT_WITHDRAWAL_FEE
                TaxTemplate.DE -> TaxSummaryNote.DE_WITHDRAWAL_FEE
                else -> TaxSummaryNote.WITHDRAWAL_FEE_INFO
            }
            lines += Acc(withdrawalFees, all, fx).line(TaxLine.WITHDRAWAL_FEES, fx, note = note)
        }
        if (transferFees.isNotEmpty()) {
            lines += Acc(transferFees, all, fx).line(TaxLine.TRANSFER_FEES, fx, note = TaxSummaryNote.TRANSFER_FEE_INFO)
        }
        // CA: nicht abziehbare Verluste
        val adjRows = rows.filter { it.adjustmentUsdt != null }
        val deniedUsdt = adjRows.fold(zero) { a, r -> a + r.adjustmentUsdt!! }
        val deniedTarget = adjRows.fold(zero) { a, r -> a + (r.adjustmentTarget ?: zero) }
        val deniedFx = fx && adjRows.any { it.adjustmentTarget == null }
        if (adjRows.isNotEmpty()) lines += TaxSummaryLine(TaxLine.DENIED_LOSS, deniedUsdt, deniedTarget.takeIf { fx }, noFx = deniedFx)
        // Erlöse (FR: vor Gebühren); ohne Schenkungen an Ehe-/Lebenspartner — ihr «Erlös» ist nur der übergehende Einstand,
        // kein Verkaufserlös (UK s. 58, CA 73(1))
        val grossFr = template == TaxTemplate.FR
        val sold = rows.filter { !it.spouseGift }
        val procUsdt = sold.fold(zero) { a, r -> a + ((if (grossFr) r.grossProceedsUsdt else r.proceedsUsdt) ?: zero) }
        val procTarget = sold.fold(zero) { a, r -> a + ((if (grossFr) r.grossProceedsTarget else r.proceedsTarget) ?: zero) }
        val procMissing = sold.any { (if (grossFr) it.grossProceedsUsdt else it.proceedsUsdt) == null }
        val procFx = fx && sold.any { r ->
            (if (grossFr) r.grossProceedsUsdt else r.proceedsUsdt) != null && (if (grossFr) r.grossProceedsTarget else r.proceedsTarget) == null
        }
        lines += TaxSummaryLine(TaxLine.PROCEEDS, procUsdt, procTarget.takeIf { fx }, incomplete = procMissing, noFx = procFx)
        // Einkünfte
        val incUsdt = income.fold(zero) { a, r -> a + (r.valueUsdt ?: zero) }
        val incTarget = income.fold(zero) { a, r -> a + (r.valueTarget ?: zero) }
        val incMissing = income.any { it.valueMissing }
        val incFx = fx && income.any { it.fxMissing }
        // AT: Belohnungen im Neubestand mit Anschaffungskosten 0 (kein Zufluss besteuert, erst der Verkauf) — der Posten ist
        // nur Information und zählt in keine Summe (sonst doppelt: Zufluss + Verkaufsgewinn mit AK 0)
        lines += TaxSummaryLine(
            TaxLine.INCOME, incUsdt, incTarget.takeIf { fx }, incomplete = incMissing, noFx = incFx,
            note = TaxSummaryNote.AT_REWARD_INFO.takeIf { template == TaxTemplate.AT },
        )
        // AT: Zinsen aus Lending beim Zufluss (§ 27b Abs. 2 Z 1 EStG, 27,5 %) — zählen, Anschaffungskosten = dieser Wert
        val intUsdt = interest.fold(zero) { a, r -> a + (r.valueUsdt ?: zero) }
        val intTarget = interest.fold(zero) { a, r -> a + (r.valueTarget ?: zero) }
        val intMissing = interest.any { it.valueMissing }
        val intFx = fx && interest.any { it.fxMissing }
        if (interest.isNotEmpty()) {
            lines += TaxSummaryLine(
                TaxLine.AT_INTEREST, intUsdt, intTarget.takeIf { fx }, incomplete = intMissing, noFx = intFx,
                note = TaxSummaryNote.AT_INTEREST_INCOME,
            )
        }
        // FR: Abgänge ohne Verkauf im Jahr — Erwerbspreis-Summe gekürzt (BOFiP § 100); das Jahr ist «unvollständig»
        if (template == TaxTemplate.FR) {
            // Mit Adress-Einträgen: der Gesamtwert vor dem Abgang zählt beobachtete Adressen mit
            val outs = TaxEngines.outflows(trades.filter { it.amount.signum() > 0 }, method, priceAt).filter { localDate(it.time, zone) in period }
            if (outs.isNotEmpty()) {
                var outUsdt = zero
                var outTarget = zero
                var outFx = false
                for (o in outs) {
                    val r = o.ptaReductionUsdt ?: continue
                    outUsdt += r
                    val rate = rateOn(rates, localDate(o.time, zone), currency)
                    if (rate != null) outTarget += r.multiply(rate, PortfolioDecimal.MC) else if (fx) outFx = true
                }
                lines += TaxSummaryLine(TaxLine.FR_OUTFLOW, outUsdt, outTarget.takeIf { fx }, incomplete = true, noFx = outFx)
            }
        }
        val netIncomplete = net.incomplete
        val netFx = net.fxMissing > 0
        fun scaled(v: BigDecimal, f: BigDecimal): BigDecimal = v.multiply(f, PortfolioDecimal.MC)
        // Orientierung je Vorlage
        when (template) {
            TaxTemplate.DE -> {
                val l = Acc(long, all, fx)
                lines += l.line(TaxLine.DE_LONG, fx)
                val sh = Acc(short, all, fx)
                val limit = privateSalesLimitEur(startYear)
                val salesStatus = when {
                    !fx || sh.unknown > 0 || unclassifiedRows.isNotEmpty() || sh.fxMissing > 0 -> TaxLimitStatus.UNKNOWN
                    // Netto-Verlust: eigener Status (nur mit Gewinnen aus privaten Veräusserungsgeschäften verrechenbar)
                    sh.target.signum() < 0 -> TaxLimitStatus.LOSS
                    sh.target < limit -> TaxLimitStatus.BELOW
                    else -> TaxLimitStatus.REACHED
                }
                lines += TaxSummaryLine(TaxLine.DE_PRIVATE_LIMIT, null, limit, salesStatus)
                val incomeStatus = when {
                    !fx || incMissing || incFx -> TaxLimitStatus.UNKNOWN
                    incTarget < INCOME_LIMIT_EUR -> TaxLimitStatus.BELOW
                    else -> TaxLimitStatus.REACHED
                }
                lines += TaxSummaryLine(TaxLine.DE_INCOME_LIMIT, null, INCOME_LIMIT_EUR, incomeStatus)
            }
            TaxTemplate.AT -> {
                // Sondersteuersatz 27,5 % auf Neubestand netto + Zinsen (beide Einkünfte aus Kryptowährungen, § 27b EStG;
                // Gebühren darin nicht abgezogen, § 20 Abs. 2 EStG)
                lines += TaxSummaryLine(
                    TaxLine.AT_RATE, net.usdt + intUsdt, if (fx) net.target + intTarget else null,
                    incomplete = netIncomplete || intMissing, noFx = netFx || intFx,
                )
                if (rows.any { it.oldHolding == true }) {
                    lines += Acc(rows.filter { it.oldHolding == true && it.longTerm == true }, all, fx).line(TaxLine.AT_OLD_LONG, fx)
                }
            }
            TaxTemplate.CH -> {
                // Kapitalgewinne im Privatvermögen: kein Betrag, keine Einordnung — «nicht gerechnet, siehe ESTV»
                lines += TaxSummaryLine(TaxLine.CH_PRIVATE)
                lines += TaxSummaryLine(TaxLine.CH_INCOME, incUsdt, incTarget.takeIf { fx }, incomplete = incMissing, noFx = incFx)
                // Vermögenssteuer: kein Betrag (Steuerwerte der ESTV-Kursliste) — ausdrücklich «nicht gerechnet»; der Wert per
                // 31.12. der Bestand-CSV ist Börsenkurs × USD/CHF, keine ESTV-Kurse (Vermerk)
                lines += TaxSummaryLine(TaxLine.CH_WEALTH, notCalculated = true, note = TaxSummaryNote.CH_EXCHANGE_VALUE)
            }
            TaxTemplate.UK -> {
                val aea = TaxTemplate.ukAnnualExemptGbp(startYear)
                lines += TaxSummaryLine(TaxLine.UK_EXEMPT, null, aea, status = if (aea == null) TaxLimitStatus.UNKNOWN else null)
                val after = if (aea != null && fx) net.target.subtract(aea).max(zero) else null
                lines += TaxSummaryLine(TaxLine.UK_AFTER_EXEMPT, null, after, incomplete = netIncomplete, noFx = netFx)
            }
            TaxTemplate.US -> lines += TaxSummaryLine(TaxLine.US_TERMS)
            TaxTemplate.CA -> {
                val f = TaxTemplate.CA_INCLUSION
                lines += TaxSummaryLine(
                    TaxLine.CA_TAXABLE, scaled(net.usdt + deniedUsdt, f), if (fx) scaled(net.target + deniedTarget, f) else null,
                    incomplete = netIncomplete, noFx = netFx || deniedFx,
                )
            }
            TaxTemplate.AU -> {
                // Verluste zuerst gegen Gewinne ohne Rabatt (≤ 12 Monate, ohne Haltedauer), dann gegen die übrigen; 50 % Rabatt
                fun discounted(pick: (TaxGainRow) -> BigDecimal?): BigDecimal {
                    var nonDisc = zero
                    var disc = zero
                    var losses = zero
                    for (r in rows) {
                        val g = pick(r) ?: continue
                        when {
                            g.signum() <= 0 -> losses -= g
                            r.longTerm == true -> disc += g
                            else -> nonDisc += g
                        }
                    }
                    var rest = nonDisc - losses
                    if (rest.signum() < 0) {
                        disc += rest
                        rest = zero
                    }
                    return if (disc.signum() < 0) disc else rest + disc.multiply(BigDecimal.ONE - TaxTemplate.AU_DISCOUNT, PortfolioDecimal.MC)
                }
                lines += TaxSummaryLine(
                    TaxLine.AU_DISCOUNTED, discounted { it.gainUsdt }, if (fx) discounted { it.gainTarget } else null,
                    incomplete = netIncomplete, noFx = netFx,
                )
            }
            TaxTemplate.FR -> {
                val status = when {
                    !fx || procMissing || procFx -> TaxLimitStatus.UNKNOWN
                    procTarget <= TaxTemplate.FR_EXEMPT_EUR -> TaxLimitStatus.BELOW
                    else -> TaxLimitStatus.REACHED
                }
                lines += TaxSummaryLine(TaxLine.FR_THRESHOLD, null, TaxTemplate.FR_EXEMPT_EUR, status)
                lines += TaxSummaryLine(TaxLine.FR_RATE)
            }
            TaxTemplate.JP -> lines += TaxSummaryLine(
                TaxLine.JP_MISC, net.usdt + incUsdt, if (fx) net.target + incTarget else null,
                incomplete = netIncomplete || incMissing, noFx = netFx || incFx,
            )
            TaxTemplate.NEUTRAL -> Unit
        }
        return TaxYearSummary(template, method, period, currency, fx, lines, walletFrom)
    }

    /**
     * CSV «Jahresübersicht» mit BOM: [header] (je ein Feld: Titel, Vorlage/Methode/Steuerjahr/Währung, Arbeitsunterlage),
     * Hinweis «Orientierung – keine Steuerberatung», Zeitzone ([zoneLine]), dann Posten | USDT | Währung | Hinweis — zuerst
     * nur Tatsachen (Summen). Schlüsse nach den Regeln der Vorlage ([TaxLine.orientation]; Sätze, Grenzen, Freibeträge)
     * stehen NUR im eigenen Block danach: Kopf [TaxSummaryTexts.orientationHeading], jeder Posten mit Vorsatz
     * «Orientierung:» ([taxOrientationLabel]).
     */
    fun summaryCsv(
        s: TaxYearSummary,
        texts: TaxSummaryTexts,
        header: List<String>,
        zoneLine: String = "",
        format: CutoffCsvFormat = CutoffCsvFormat.SEMICOLON_POINT,
    ): String {
        fun n(text: String): String = if (format.decimal == ',') text.replace('.', ',') else text
        fun money(v: BigDecimal?): String = n(v?.let { CutoffExport.money(it) }.orEmpty())
        fun row(l: TaxSummaryLine, label: String): String {
            val notes = listOfNotNull(
                when (l.status) {
                    TaxLimitStatus.BELOW -> texts.below
                    TaxLimitStatus.REACHED -> texts.reached
                    TaxLimitStatus.UNKNOWN -> texts.unknown
                    TaxLimitStatus.LOSS -> texts.loss.ifEmpty { texts.below }
                    null -> null
                },
                texts.incomplete.takeIf { l.incomplete },
                texts.noFx.takeIf { l.noFx },
                texts.notCalculated.takeIf { l.notCalculated && it.isNotEmpty() },
                l.note?.let { texts.notes[it] }?.takeIf { it.isNotEmpty() },
            ).joinToString(", ")
            return textLine(listOf(label, money(l.usdt), money(l.target), notes), format, setOf(1, 2))
        }
        val sb = StringBuilder(BOM)
        header.forEach { sb.append(textLine(listOf(it), format)) }
        sb.append(textLine(listOf(texts.title), format))
        sb.append(textLine(listOf(texts.disclaimer), format))
        if (zoneLine.isNotEmpty()) sb.append(textLine(listOf(zoneLine), format))
        sb.append(textLine(listOf(texts.item, texts.usdt, texts.target, texts.note), format))
        val (orientation, facts) = s.lines.partition { it.line.orientation }
        for (l in facts) sb.append(row(l, texts.labels[l.line].orEmpty()))
        if (orientation.isNotEmpty()) {
            sb.append(textLine(listOf(texts.orientationHeading.ifEmpty { texts.orientation }), format))
            for (l in orientation) {
                sb.append(row(l, taxOrientationLabel(texts.orientationItem, texts.orientation, texts.labels[l.line].orEmpty())))
            }
        }
        return sb.toString()
    }

    /** Zeile aus typisierten Zellen (Text gegen Formel-Injektion geschützt, Zahlen unverändert). */
    private fun line(cells: List<CsvCell>, format: CutoffCsvFormat): String = CutoffExport.joinCells(cells, format.separator) + EOL

    /** Zeile [texts] (Text) und [numbers] (Zahlen, z. B. Beträge der Übersicht) in dieser Reihenfolge der Spalten. */
    private fun textLine(fields: List<String>, format: CutoffCsvFormat, numeric: Set<Int> = emptySet()): String =
        line(fields.mapIndexed { i, f -> CsvCell(f, numeric = i in numeric) }, format)
}
