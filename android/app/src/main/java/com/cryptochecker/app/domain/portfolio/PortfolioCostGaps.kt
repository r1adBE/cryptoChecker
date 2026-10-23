// Zugänge ohne Einstand, die der Nutzer ergänzen kann (gespiegelt in `PortfolioCostGaps.swift`).
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

/**
 * Wie eine Lücke beim Einstand zu schliessen ist ([PortfolioCostGaps.kind]):
 *  - [EDITABLE]: Kauf/Einzahlung ohne Preis — der Nutzer kann den Einstand erfassen (Vorschlag: Tagesschluss) oder ihn
 *    bewusst unbekannt lassen.
 *  - [INCOME_RECEIPT_VALUE]: Belohnung/Airdrop ohne Wert — der Einstand ist rechtlich der Wert beim Zufluss (Einkommen);
 *    der Steuer-Export rechnet ihn schon zum Tagesschluss ([PortfolioLedger.valueRewardsAtDayClose]). Kein Angebot, ihn zu
 *    ändern — nur der Hinweis «Einstand = Wert bei Zufluss (Einkommen)».
 *  - [INCOME_ZERO]: AT, Belohnung ohne Zins ([TaxEngineAt]: Staking/Airdrop mit Anschaffungskosten 0, § 27b Abs. 2 EStG) —
 *    ebenfalls nicht zu ändern.
 */
enum class CostGapKind { EDITABLE, INCOME_RECEIPT_VALUE, INCOME_ZERO }

/** Ein Zugang ohne Einstand ([trade]) und wie er zu behandeln ist ([kind]). */
data class PortfolioCostGap(val trade: PortfolioTrade, val kind: CostGapKind)

/**
 * Zugänge ohne Einstand («Einträge ohne Einstand»): Kauf, Einzahlung (auch aus einem CSV-Import ohne Preis) und
 * Belohnung ohne Preis zählen auf der Karte zum Bestand ohne Kaufpreis. Gespeichert wird ein erfasster Einstand im
 * Preisfeld der Transaktion (Preis je Coin, [PortfolioTrade.priceUsdt]) — keine neue Spalte. Adress-Einträge sind keine
 * Lücken dieser Art (dafür [AddressCostLots]).
 */
object PortfolioCostGaps {

    /** Eigener Zugang ohne gültigen Preis mit Menge > 0? */
    fun isGap(t: PortfolioTrade): Boolean =
        !AddressBalance.isAddressTrade(t.id) && t.amount.signum() > 0 && t.validPrice == null &&
            (t.type == PortfolioTxType.BUY || t.type == PortfolioTxType.DEPOSIT || t.type == PortfolioTxType.REWARD)

    /** Art der Lücke nach der Steuer-Vorlage [template] (nur AT rechnet Belohnungen ohne Zins mit 0). */
    fun kind(t: PortfolioTrade, template: TaxTemplate): CostGapKind = when {
        t.type != PortfolioTxType.REWARD -> CostGapKind.EDITABLE
        template == TaxTemplate.AT && !t.interestIncome -> CostGapKind.INCOME_ZERO
        else -> CostGapKind.INCOME_RECEIPT_VALUE
    }

    /** Alle Lücken, neueste zuerst (gleiche Zeit: höhere Id zuerst, wie die Transaktionsliste). */
    fun gaps(trades: List<PortfolioTrade>, template: TaxTemplate): List<PortfolioCostGap> =
        trades.filter(::isGap)
            .sortedWith(compareByDescending<PortfolioTrade> { it.time }.thenByDescending { it.id })
            .map { PortfolioCostGap(it, kind(it, template)) }

    /** Offene Lücken für die Karte: erfassbar und nicht bewusst «unbekannt gelassen» ([acknowledged] = Ids). */
    fun pending(gaps: List<PortfolioCostGap>, acknowledged: Set<Long>): List<PortfolioCostGap> =
        gaps.filter { it.kind == CostGapKind.EDITABLE && it.trade.id !in acknowledged }

    /** «Unbekannt gelassen»-Merker ohne Lücke mehr (gelöscht oder inzwischen mit Einstand) — weg damit. */
    fun prune(acknowledged: Set<Long>, gaps: List<PortfolioCostGap>): Set<Long> {
        val open = gaps.map { it.trade.id }.toSet()
        return acknowledged.filterTo(LinkedHashSet()) { it in open }
    }

    /**
     * Vorschlag Einstand gesamt = Menge × Tagesschluss (> 0) — genau das, was der Preis je Coin = Tagesschluss im Rechner
     * ergibt (Gebühren kommen dort wie bei jedem Kauf dazu); sonst null.
     */
    fun suggestion(t: PortfolioTrade, close: BigDecimal?): BigDecimal? =
        close?.takeIf { it.signum() > 0 && t.amount.signum() > 0 }?.let { t.amount * it }

    /** Preis je Coin aus einem Einstand gesamt [total] (≥ 0) für die Menge [amount] (> 0), 34 Stellen; sonst null. */
    fun priceFromTotal(total: BigDecimal?, amount: BigDecimal): BigDecimal? =
        total?.takeIf { it.signum() >= 0 && amount.signum() > 0 }?.divide(amount, PortfolioDecimal.MC)
}
