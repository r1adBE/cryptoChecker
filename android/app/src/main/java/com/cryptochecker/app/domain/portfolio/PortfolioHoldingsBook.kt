// Bestand je Coin nach der Einstandsmethode (Teil von `PortfolioLedger.kt`, gespiegelt in `PortfolioHoldingsBook.swift`).
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

/**
 * Laufender Bestand EINES Coins nach der Einstandsmethode [method] — die eine Regel für Karte, «Heute/7 T/1 J»,
 * Wertverlauf, Widget, Alarm und Stichtag (gleiche Mengen wie [PortfolioFifo] bzw. der Ø im Rechner; Spiegel:
 * `PortfolioHoldingsBook` in `PortfolioLedger.swift`). Schritte zeitlich wie [PortfolioLedger.events]:
 *  - FIFO, Ø: ein Topf ([PortfolioLedger.holdingsAfter]).
 *  - FIFO je Wallet: je Wallet ein Topf ([PortfolioWallets]). Abgänge (Verkauf, Tausch, Auszahlung, Gebühr-Abgang,
 *    Netzwerkgebühr) sind auf den Bestand DIESER Wallet gekappt — der Rest ist ungedeckt und wird NICHT aus anderen
 *    Wallets genommen; eine Umbuchung verschiebt (zuerst die Netzwerkgebühr, dann die Menge; was fehlt, kommt nicht
 *    an). Adress-Einträge ([AddressBalance.isAddressTrade]) liegen in einem eigenen Topf.
 * [capFor] = Bestand, auf den [PortfolioLedger.flow] die Abgänge eines Schritts kappt (`holdingsBefore`).
 */
class PortfolioHoldingsBook(val method: CostMethod = CostMethod.DEFAULT) {
    /** Bestand gesamt, exakt. */
    var total: BigDecimal = BigDecimal.ZERO
        private set
    private val wallets = HashMap<String, BigDecimal>()
    private val perWallet: Boolean get() = method == CostMethod.FIFO_WALLET

    private fun walletKey(e: PortfolioLedgerEvent, raw: String?): String =
        if (AddressBalance.isAddressTrade(e.trade.id)) ADDRESS_POT else PortfolioWallets.key(raw)

    private fun held(key: String): BigDecimal = wallets[key] ?: BigDecimal.ZERO

    private fun add(key: String, amount: BigDecimal) {
        wallets[key] = (held(key) + amount).max(BigDecimal.ZERO)
        total = (total + amount).max(BigDecimal.ZERO)
    }

    /** Topf, aus dem [e] abgeht (je Wallet), sonst der ganze Bestand. */
    fun capFor(e: PortfolioLedgerEvent): BigDecimal {
        if (!perWallet) return total
        val t = e.trade
        val raw = if (e.feeLeg) PortfolioWallets.of(e) else if (t.type == PortfolioTxType.TRANSFER) t.transferFrom else t.exchange
        return held(walletKey(e, raw))
    }

    /** Bestand nach [e]. */
    fun apply(e: PortfolioLedgerEvent) {
        if (!perWallet) {
            total = PortfolioLedger.holdingsAfter(total, e)
            return
        }
        val t = e.trade
        if (t.amount.signum() <= 0) return
        if (e.feeLeg) {
            val key = walletKey(e, PortfolioWallets.of(e))
            add(key, -t.thirdFeeAmount.min(held(key)))
            return
        }
        if (e.swapIn) {
            if (t.swapReceived.signum() > 0) add(walletKey(e, t.exchange), t.swapReceived)
            return
        }
        when (t.type) {
            PortfolioTxType.BUY, PortfolioTxType.REWARD, PortfolioTxType.DEPOSIT ->
                if (t.received.signum() > 0) add(walletKey(e, t.exchange), t.received)
            PortfolioTxType.SELL, PortfolioTxType.PAYMENT, PortfolioTxType.GIFT, PortfolioTxType.WITHDRAWAL,
            PortfolioTxType.SWAP -> {
                val key = walletKey(e, t.exchange)
                add(key, -(t.amount + t.coinFee).min(held(key)))
            }
            PortfolioTxType.TRANSFER -> {
                val src = walletKey(e, t.transferFrom)
                val dst = walletKey(e, t.transferTo)
                // Netzwerkgebühr zuerst (verlässt das Portfolio), dann wandert die Menge — soweit die Quelle reicht
                add(src, -t.coinFee.min(held(src)))
                if (src != dst) {
                    val moved = t.amount.min(held(src))
                    add(src, -moved)
                    add(dst, moved)
                }
            }
        }
    }

    private companion object {
        /** Eigener Topf der Adress-Einträge (kein gültiger Wallet-Schlüssel). */
        const val ADDRESS_POT = "\u0000address"
    }
}
