// Texte der Steuer-Exporte (Teil von `TaxExportCsv.kt`, gespiegelt in `TaxCsvTexts.swift`).
package com.cryptochecker.app.domain.portfolio

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
    /** Vermerk «USDT = 1 USD angenommen» ([TaxGainRow.usdtApproximate], [TaxIncomeRow.usdtApproximate]); leer = kein Vermerk. */
    val usdtAssumed: String = "",
    /**
     * AT (nur diese Vorlage füllt sie): Einkünfte getrennt wie in der Jahresübersicht ([TaxIncomeRow.interest]) — Vermerk je
     * Zeile «Zins – steuerpflichtig beim Zufluss» bzw. «Staking – nur zur Information (Anschaffungskosten 0, besteuert
     * beim Verkauf)» und statt der einen Summe zwei Summenzeilen «Summe Zins (steuerpflichtig)» / «Summe Staking
     * (Information)». Leer (alle anderen Vorlagen) = eine Summe wie bisher.
     */
    val incomeInterest: String = "",
    val incomeStaking: String = "",
    val totalInterest: String = "",
    val totalStaking: String = "",
    /**
     * AT: Vermerk für Belohnungen (Zins und Staking) mit Zufluss vor dem 1.3.2022 «vor 1.3.2022 – mit Fachperson prüfen»
     * statt [incomeInterest]/[incomeStaking] — § 27b EStG gilt erst ab dann, die Behandlung davor ist nicht
     * ausdrücklich geklärt. Leer = kein eigener Vermerk.
     */
    val incomeBeforeNewLaw: String = "",
    /**
     * DE/AT (nur diese Vorlagen füllen ihn, [TaxTemplate.withdrawalFeeApart]): Summenzeile «Auszahlungsgebühren» — die
     * Gebühr-Zeilen einer Auszahlung zählen dann NICHT in der Summe der Gewinne, sondern in dieser eigenen Zeile, wie der
     * eigene Posten der Jahresübersicht (keine Veräusserung). Leer (alle anderen Vorlagen) = in der Summe wie bisher.
     */
    val withdrawalFeesTotal: String = "",
)
