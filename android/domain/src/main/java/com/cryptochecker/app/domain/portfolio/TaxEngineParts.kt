// Hilfen der Steuer-Rechnung: Gebühren-Zeilen von Umbuchungen, Wörter der Regeln, Netzwerkgebühren als Verkauf — Teil von TaxEngines.
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId

/**
 * [TaxEventRules.quoteFeeIsInfo]: je Umbuchung mit Gebühr USDT eine Zeile [PortfolioFeeExpense.TRANSFER] (Menge 0,
 * «Einstand» = Gebühr, Erlös 0) — der Export führt sie nur zur Information, nicht in den Summen.
 */
internal fun TaxEngines.transferFeeRows(trades: List<PortfolioTrade>): List<PortfolioDisposal> =
    AddressBalance.split(trades).first
        .filter { it.type == PortfolioTxType.TRANSFER && it.amount.signum() > 0 && it.quoteFee.signum() > 0 }
        .map { t ->
            PortfolioFeeExpenses.disposal(
                PortfolioCalculator.normalizeCoin(t.coin),
                PortfolioLedgerEvent(t, false),
                PortfolioFeeExpense.TRANSFER,
                PortfolioFeeExpenses.Expense(t.quoteFee, false),
                CostMethod.FIFO,
            )
        }

/** Wörter in Kleinbuchstaben: Trenner ist alles ausser Buchstaben/Ziffern und der Wechsel klein → gross. */
internal fun TaxEngines.words(text: String): List<String> {
    val out = ArrayList<String>()
    val word = StringBuilder()
    fun flush() {
        if (word.isNotEmpty()) out += word.toString().lowercase()
        word.clear()
    }
    var prevLower = false
    for (c in text) {
        if (!c.isLetterOrDigit()) {
            flush()
            prevLower = false
            continue
        }
        if (prevLower && c.isUpperCase()) flush()
        word.append(c)
        prevLower = c.isLowerCase()
    }
    flush()
    return out
}

/**
 * Netzwerkgebühr in Krypto einer Umbuchung (eigene Wallets), Auszahlung oder Schenkung als eigene Veräusserung: je
 * Gebühr-Coin ein Verkauf der Gebühr-Menge zum Marktwert (erfasster Preis bzw. Gebühr-Preis, Stablecoin 1, sonst
 * [priceAt] = Tagesschluss des UTC-Tags, [TaxDayClose], «Wert geschätzt»; ohne Kurs Erlös unbekannt) mit Id und Zeit
 * der Transaktion und ihrer Quell-Wallet (Umbuchung: «von», sonst die Börse) — VOR ihr in die Liste, damit die
 * Gebühr-Coins (stabil sortiert, [PortfolioLedger.ORDER]) die Quelle zuerst verlassen. Die Transaktion selbst verliert diese Gebühr: eine Umbuchung
 * ändert danach weder Menge noch Einstand, die Gebühr im dritten Coin erhöht keinen Einstand und ist bei einer
 * Auszahlung kein Aufwand mehr — sonst zählte ihr Wert ein zweites Mal als Verlust. Die Gebühr USDT bleibt wie sie ist.
 * Der Export erkennt die Zeilen an der Id einer Transaktion ohne Verkauf (Vermerk «Gebühr», [TaxExport.gainRows]).
 */
internal fun TaxEngines.networkFeeSales(t: PortfolioTrade, priceAt: ((String, Long) -> BigDecimal?)?, out: MutableList<PortfolioTrade>) {
    if ((t.type != PortfolioTxType.TRANSFER && !t.type.isWithdrawalLike) || t.amount.signum() <= 0) {
        out += t
        return
    }
    val wallet = if (t.type == PortfolioTxType.TRANSFER) t.transferFrom else t.exchange

    // Kurs zum Tagesschluss (kein erfasster Preis, kein Stablecoin) bzw. schon geschätzter Gebühr-Preis: «Wert geschätzt»
    fun sale(
        coin: String,
        qty: BigDecimal,
        recorded: BigDecimal?,
        estimated: Boolean
    ) = feePrice(coin, recorded, t.time, priceAt).let { p ->
        val atClose = p != null && recorded?.takeIf { it.signum() >= 0 } == null && PortfolioStables.needsQuote(coin)
        PortfolioTrade(t.id, coin, PortfolioTxType.SELL, qty, p, t.time, exchange = wallet, valueEstimated = estimated || atClose)
    }
    var rest = t
    if (t.coinFee.signum() > 0) {
        out += sale(PortfolioCalculator.normalizeCoin(t.coin), t.coinFee, t.validPrice, false)
        rest = rest.copy(fee = null, feeUnit = null)
    }
    t.thirdFeeCoin?.let { c ->
        // Gebühr-Preis schon zum Tagesschluss gesetzt ([dayCloseValued]): Merker mitgeben
        out += sale(c, t.thirdFeeAmount, t.thirdFeePrice, t.feePriceEstimated)
        rest = rest.copy(feeCoin = null, feeCoinAmount = null, feeCoinPrice = null)
    }
    out += rest
}
