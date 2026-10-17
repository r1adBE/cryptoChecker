package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

/**
 * Einstandsmethode des Portfolios (Einstellung «costMethod», Standard [FIFO] für alle Installationen).
 *  - [FIFO]: zuerst gekauft, zuerst verkauft — je Coin über ALLE Börsen/Wallets, nach Zeit, dann Id.
 *  - [FIFO_WALLET]: FIFO je Börse/Wallet («FIFO je Wallet/Börse»): Wallet einer Transaktion = ihre Börse
 *    ([PortfolioTrade.exchange], leer = eigener Topf «ohne Angabe»); eine Umbuchung verschiebt die Posten mit ihrem
 *    Kaufdatum von [PortfolioTrade.transferFrom] nach [PortfolioTrade.transferTo]. Das BMF-Schreiben zu Kryptowerten
 *    (10.05.2022, neu gefasst 06.03.2025, Rn. 62: walletbezogene Betrachtung) lässt FIFO je Wallet zu; das
 *    universelle FIFO bleibt der Standard.
 *  - [AVERAGE]: gleitender Durchschnitt («Ø»), wie bis Build 45.
 * Name wird so gespeichert (wie iOS); ältere Versionen kennen [FIFO_WALLET] nicht und nehmen dann [DEFAULT].
 */
enum class CostMethod {
    FIFO, FIFO_WALLET, AVERAGE;

    /** Rechnet mit Posten (FIFO universell oder je Wallet). */
    val isFifo: Boolean get() = this != AVERAGE

    companion object {
        val DEFAULT = FIFO

        /** Gespeicherter Name; unbekannt oder leer = [DEFAULT]. */
        fun fromName(name: String?): CostMethod = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Wallet-Zuordnung für [CostMethod.FIFO_WALLET]: Schlüssel = Name getrimmt und klein geschrieben («Binance» und
 * «binance» sind dieselbe Wallet); leer/fehlend = [UNSPECIFIED] (Topf «ohne Angabe»). Angezeigt wird die zuerst
 * gesehene Schreibweise.
 */
object PortfolioWallets {
    /** Topf «ohne Angabe» (Börse leer). */
    const val UNSPECIFIED = ""

    fun key(name: String?): String = name?.trim()?.lowercase() ?: UNSPECIFIED

    /**
     * Wallet eines Schritts: Gebühr-Abgang in der Wallet der Haupttransaktion (bei einer Umbuchung die Quelle), sonst
     * die Börse; Umbuchung selbst hat Quelle und Ziel ([PortfolioTrade.transferFrom]/[PortfolioTrade.transferTo]).
     */
    fun of(e: PortfolioLedgerEvent): String? =
        if (e.trade.type == PortfolioTxType.TRANSFER) e.trade.transferFrom else e.trade.exchange
}

/**
 * Offener Posten (FIFO): Rest eines Zugangs. [costUsdt] = Einstand des Rests (Menge × Preis + Gebühr USDT
 * + Wert der Gebühr im dritten Coin, anteilig; plus übertragene Netzwerkgebühren); null = Kaufpreis unbekannt.
 * [acquiredTime] = Erwerb ([PortfolioTrade.acquisitionTime]: bei einer Einzahlung das ursprüngliche Kaufdatum).
 */
data class PortfolioLot(
    val coin: String,
    /** Transaktion des Zugangs. */
    val txId: Long,
    val acquiredTime: Long,
    /** [PortfolioTxType.BUY], [PortfolioTxType.REWARD], [PortfolioTxType.DEPOSIT] oder [PortfolioTxType.SWAP] (Zugang aus Tausch). */
    val kind: PortfolioTxType,
    val amount: BigDecimal,
    val costUsdt: BigDecimal?,
    /** [CostMethod.FIFO_WALLET]: Wallet (Anzeigename, [PortfolioWallets.UNSPECIFIED] = ohne Angabe); sonst null. */
    val wallet: String? = null,
)

/**
 * Offene Position für die Anzeige «Offene Posten» ([PortfolioCalculator.openLots]): FIFO (beide Arten) je Posten mit
 * Kaufdatum, Restmenge, Rest-Einstand und Wallet; Ø zusammengefasst ([aggregated]): eine Zeile mit Kaufpreis (Menge
 * mit Kaufpreis, Ø-Einstand) und eine ohne (Menge ohne Kaufpreis), je nur wenn > 0. [costUsdt] null = Kaufpreis
 * unbekannt. Adress-Guthaben sind keine Posten.
 */
data class PortfolioOpenLot(
    val coin: String,
    /** Zugang (FIFO); Ø: null. */
    val txId: Long?,
    /** Erwerb (FIFO); Ø: null. */
    val acquiredTime: Long?,
    /** Art des Zugangs (FIFO); Ø: null. */
    val kind: PortfolioTxType?,
    val amount: BigDecimal,
    val costUsdt: BigDecimal?,
    /** [CostMethod.FIFO_WALLET]: Wallet ([PortfolioWallets.UNSPECIFIED] = ohne Angabe); sonst null. */
    val wallet: String?,
    /** Ø: zusammengefasste Zeile. */
    val aggregated: Boolean,
)

/**
 * [CostMethod.FIFO_WALLET]: Abgang (Verkauf, Tausch, Auszahlung, Umbuchung, Gebühr-Abgang) grösser als der Bestand
 * DIESER Wallet — der fehlende Teil [missing] wird nicht aus anderen Wallets genommen (Verkauf/Tausch: ungedeckte
 * Veräusserung ohne Kaufpreis; Umbuchung: kommt nicht an). Anzeige z. B. «Bestand in Binance reicht nicht – fehlt eine
 * Umbuchung?».
 */
data class PortfolioWalletShortfall(
    val coin: String,
    /** Anzeigename; [PortfolioWallets.UNSPECIFIED] = ohne Angabe. */
    val wallet: String,
    val txId: Long,
    val time: Long,
    val type: PortfolioTxType,
    /** Gebühr einer anderen Transaktion in diesem Coin. */
    val feeLeg: Boolean,
    val missing: BigDecimal,
)

/**
 * Teil eines Einstands: Betrag (USDT) aus einem Zugang bzw. einer Gebühr zum Zeitpunkt [time] (Devisenkurs) — FIFO je
 * Posten (Erwerb, zugeschlagene Umbuchungsgebühren, übernommener Netzwerkgebühr-Einstand), Ø je Topf.
 */
data class PortfolioCostPart(val time: Long, val usdt: BigDecimal)

/**
 * Herkunftsteile eines Einstands ([PortfolioCostPart]) — gemeinsame Rechnung der FIFO-Posten ([PortfolioFifo]); Spiegel
 * in `PortfolioFifo.swift`. Invariante: Summe der Teile = Einstand (exakt).
 */
internal object PortfolioCostParts {

    /** [usdt] zum Zeitpunkt [time] in [parts]; gleicher Zeitpunkt wird addiert (gleicher Devisenkurs), 0 = nichts. */
    fun add(parts: MutableList<PortfolioCostPart>, time: Long, usdt: BigDecimal) {
        if (usdt.signum() == 0) return
        val i = parts.indexOfFirst { it.time == time }
        if (i >= 0) parts[i] = PortfolioCostPart(time, parts[i].usdt + usdt) else parts += PortfolioCostPart(time, usdt)
    }

    /**
     * Nimmt [target] von [parts] (Summe [total]) anteilig aus allen Teilen heraus (die Teile sinken entsprechend);
     * Rückgabe = die genommenen Teile, Summe exakt [target] (Rest beim letzten Teil).
     */
    fun take(parts: MutableList<PortfolioCostPart>, total: BigDecimal, target: BigDecimal): List<PortfolioCostPart> {
        if (target.signum() == 0 || parts.isEmpty()) return emptyList()
        if (target >= total) {
            val all = parts.toList()
            parts.clear()
            return all
        }
        val out = ArrayList<PortfolioCostPart>(parts.size)
        var left = target
        for (i in parts.indices) {
            val p = parts[i]
            val r = if (i == parts.size - 1) left else p.usdt.multiply(target).divide(total, PortfolioDecimal.MC)
            left -= r
            out += PortfolioCostPart(p.time, r)
            parts[i] = PortfolioCostPart(p.time, p.usdt - r)
        }
        return out
    }
}

/**
 * Eine Veräusserung (Steuer-Export): Verkauf oder Gebühr in einem dritten Coin ([feeLeg]).
 * FIFO: ein Eintrag je verbrauchtem Posten; Ø: ein Eintrag je Verkauf (bei Bestand teils ohne Kaufpreis zwei:
 * Teil mit und Teil ohne Kaufpreis), ohne Posten-Angaben (null). Mehr verkauft als erfasst: der ungedeckte Rest
 * als eigener Eintrag ([uncovered]) mit Erlös, ohne Kaufpreis.
 * Beträge in USDT, exakt; null = unbekannt.
 */
data class PortfolioDisposal(
    val coin: String,
    /** Verkauf bzw. (bei [feeLeg]) die Transaktion, deren Gebühr in diesem Coin bezahlt wurde. */
    val sellTxId: Long,
    val sellTime: Long,
    /** FIFO: Zugang des Postens; Ø und [uncovered]: null. */
    val lotTxId: Long?,
    val acquiredTime: Long?,
    val lotKind: PortfolioTxType?,
    /** Abgegebene Menge dieses Teils (inkl. anteiliger Gebühr im Coin). */
    val amount: BigDecimal,
    /** Einstand dieses Teils; null = Kaufpreis unbekannt. */
    val costUsdt: BigDecimal?,
    /** Erlös dieses Teils (anteilig: Menge × Preis − Gebühr USDT − Wert der Gebühr im dritten Coin); null = ohne Preis. */
    val proceedsUsdt: BigDecimal?,
    /** Erlös − Einstand; null, wenn eins davon unbekannt ist. */
    val gainUsdt: BigDecimal?,
    /** UTC-Kalendertage zwischen Zugang und Veräusserung (Tag(Verkauf) − Tag(Zugang)); Ø: null. */
    val holdingDays: Long?,
    /** Veräusserung des Gebühr-Coins (Gebühr einer anderen Transaktion in diesem Coin bezahlt). */
    val feeLeg: Boolean,
    /** Erlös ohne die Gebühr im dritten Coin gerechnet (deren Preis fehlt). */
    val feeValueMissing: Boolean,
    val method: CostMethod,
    /**
     * Herkunft von [costUsdt] (Summe = [costUsdt]) für die Umrechnung je Teil zum Kurs seines Datums: FIFO der Posten
     * (Erwerb plus zugeschlagene Gebühren je zu ihrem Zeitpunkt), Ø anteilig aus dem Topf; leer ohne Kaufpreis bzw. bei
     * Einstand 0 und bei Gebühr-Aufwänden.
     */
    val costParts: List<PortfolioCostPart> = emptyList(),
    /** Teil über den erfassten Bestand hinaus (mehr verkauft als erfasst): Kaufpreis fehlt. */
    val uncovered: Boolean = false,
    /** [CostMethod.FIFO_WALLET]: Wallet des Abgangs ([PortfolioWallets.UNSPECIFIED] = ohne Angabe); sonst null. */
    val wallet: String? = null,
    /** Veräusserung durch einen Tausch ([PortfolioTxType.SWAP]). */
    val swap: Boolean = false,
    /**
     * Schenkung als Veräusserung zum Marktwert ([PortfolioTxType.GIFT] bei UK/CA/AU, [PortfolioTrade.fromGift]): Vermerk
     * «Schenkung».
     */
    val gift: Boolean = false,
    /** Zahlung mit Krypto ([PortfolioTxType.PAYMENT], Veräusserung zum Marktwert): Vermerk «Zahlung mit Krypto». */
    val payment: Boolean = false,
    /**
     * Erlös aus einem geschätzten Wert ([PortfolioTrade.valueEstimated], Tagesschluss): Vermerk «Wert geschätzt
     * (Tagesschluss)» — geschätzt, nicht fehlend.
     */
    val valueEstimated: Boolean = false,
    // ---- Nur Steuer-Vorlagen ([TaxEngines]) ----
    /** [TaxMethod.UK_POOL]: Zuordnung (gleicher Tag, 30 Tage, Pool); sonst null. */
    val match: TaxMatch? = null,
    /** Same-Day/30 Tage: Datum des zugeordneten Zugangs (Anzeige «Kaufdatum», keine Haltedauer); sonst null. */
    val matchedTime: Long? = null,
    /** [TaxMethod.CA_ACB]: nicht abziehbarer Verlust (Superficial Loss, ≥ 0), dem ACB zugeschlagen; sonst null. */
    val adjustmentUsdt: BigDecimal? = null,
    /** [TaxMethod.AT_MOVING_AVERAGE]: true = Altbestand (angeschafft vor dem 1.3.2021), false = Neubestand; sonst null. */
    val oldHolding: Boolean? = null,
    /** [TaxMethod.FR_PTA]: Erwerbspreis-Summe unvollständig (Zugang ohne Preis oder Abtretung ohne Formel davor). */
    val costIncomplete: Boolean = false,
    /** [TaxMethod.FR_PTA]: Gesamtwert des Portfolios unmittelbar vor der Abtretung (USDT); sonst null. */
    val portfolioValueUsdt: BigDecimal? = null,
    /** [TaxMethod.FR_PTA]: Abtretungspreis vor Gebühren (Quotient und 305-€-Grenze); sonst null. */
    val grossProceedsUsdt: BigDecimal? = null,
    /** Gebühr als Aufwand ([PortfolioFeeExpenses]): Einstand = Aufwand, Erlös 0; sonst null. */
    val feeExpense: PortfolioFeeExpense? = null,
    /**
     * UK/CA: Schenkung an Ehe-/Lebenspartner ([PortfolioTrade.isGiftToSpouse]) — Erlös = Einstand, kein Gewinn/Verlust (TCGA 1992 s. 58,
     * ITA 73(1)); der Einstand geht auf den Partner über. Vermerk im Export.
     */
    val spouseGift: Boolean = false,
)

/** Art eines Gebühr-Aufwands ([PortfolioFeeExpenses]). */
enum class PortfolioFeeExpense {
    /** Gebühr im Coin verbraucht den ganzen Zugang (Kauf/Belohnung/Einzahlung bzw. erhaltener Coin eines Tauschs). */
    CONSUMED,
    /** Gebühr USDT + Wert der Gebühr im dritten Coin einer Auszahlung. */
    WITHDRAWAL,
    /**
     * Nur Steuer-Vorlagen mit [TaxEventRules.quoteFeeIsInfo] (Build 57): Gebühr USDT einer Umbuchung zwischen eigenen
     * Wallets — nicht im Einstand, Zeile nur zur Information ([TaxEngines.disposals]). Das Portfolio rechnet sie weiter
     * zum Einstand.
     */
    TRANSFER,
}

/**
 * Gebühr als Aufwand — für alle Methoden gleich (Spiegel in `PortfolioFifo.swift`):
 *  - [PortfolioFeeExpense.CONSUMED]: Die Gebühr im Coin verbraucht den ganzen Zugang. Kauf/Belohnung/Einzahlung mit
 *    Preis und Gebühr im Coin ≥ Menge: Aufwand = Menge × Preis + Gebühr USDT + Wert der Gebühr im dritten Coin. Tausch mit
 *    Gebühr im erhaltenen Coin ≥ erhaltener Menge: Aufwand = Wert des Tauschs — der abgegebene Coin bucht den Wert als
 *    Erlös, der Aufwand hebt ihn auf (kein Schein-Gewinn: realisiert = −Einstand des abgegebenen Coins). Das Blatt sperrt
 *    solche Eingaben ([PortfolioTxForm.feeConsumesReceived]), der CSV-Import überspringt sie; bestehende Daten rechnen so.
 *    Ohne Preis bzw. Wert: kein Aufwand (der Erlös des Tauschs ist dann ebenfalls unbekannt).
 *  - [PortfolioFeeExpense.WITHDRAWAL]: Gebühr USDT + Wert der Gebühr im dritten Coin einer Auszahlung (nur mit Bestand;
 *    reicht er nicht, zum gedeckten Anteil [PortfolioLedger.withdrawnShare] wie der Abfluss im Verlauf)
 *    — vorher hing sie an nichts; eine Umbuchungsgebühr erhöht dagegen den Einstand (Coins bleiben im Portfolio).
 * Je Fall eine Veräusserung ([PortfolioDisposal.feeExpense]): Einstand = Aufwand, Erlös 0, Gewinn = −Aufwand, Erwerb =
 * Zeit der Transaktion (Haltedauer 0). Zählt im realisierten Gewinn und im Steuer-Export (Vermerk «Gebühr verbraucht den
 * ganzen Zugang» bzw. «Gebühr Auszahlung»). «Heute/7 T/1 J» und der Wertverlauf ([PortfolioLedger.flow]) zählen dieselbe
 * Gebühr schon als Verlust (Zufluss ohne Bestand bzw. Abfluss um die Gebühr kleiner).
 */
internal object PortfolioFeeExpenses {

    /** Aufwand (USDT) und «Wert der Gebühr im dritten Coin fehlt». */
    class Expense(val usdt: BigDecimal, val feeValueMissing: Boolean)

    /** Gebühr verbraucht den ganzen Zugang [e]; null = nicht so oder ohne Preis/Wert. */
    fun consumed(e: PortfolioLedgerEvent): Expense? {
        val t = e.trade
        if (e.feeLeg || t.amount.signum() <= 0) return null
        if (e.swapIn) {
            if (t.swapToCoin == null || t.swapReceived.signum() > 0) return null
            return t.swapValue?.let { Expense(it, false) }
        }
        if (!t.type.isInflow || t.received.signum() > 0) return null
        val price = t.validPrice ?: return null
        val feeValue = t.thirdFeeValue
        return Expense(t.amount * price + t.quoteFee + (feeValue ?: BigDecimal.ZERO), feeValue == null)
    }

    /**
     * Auszahlung bei Bestand [holdings]: Gebühr USDT + Wert der Gebühr im dritten Coin zum gedeckten Anteil
     * ([PortfolioLedger.withdrawnShare]); null = keine Gebühr oder nichts abgegeben.
     */
    fun withdrawal(t: PortfolioTrade, holdings: BigDecimal): Expense? {
        // Auszahlung und Verschenkt (wie eine Auszahlung)
        if (!t.type.isWithdrawalLike || t.amount.signum() <= 0) return null
        val share = PortfolioLedger.withdrawnShare(t, holdings) ?: return null
        val feeValue = t.thirdFeeValue
        val full = t.quoteFee + (feeValue ?: BigDecimal.ZERO)
        if (full.signum() <= 0 && feeValue != null) return null
        val usdt = if (share.compareTo(BigDecimal.ONE) == 0) full else full.multiply(share, PortfolioDecimal.MC)
        return Expense(usdt, feeValue == null)
    }

    /** Menge der Zeile: verbrauchter Zugang (Tausch: erhaltene Menge), Auszahlung und Umbuchung 0 (Gebühr in USDT). */
    private fun amountOf(e: PortfolioLedgerEvent, kind: PortfolioFeeExpense): BigDecimal = when {
        kind == PortfolioFeeExpense.WITHDRAWAL || kind == PortfolioFeeExpense.TRANSFER -> BigDecimal.ZERO
        e.swapIn -> e.trade.toAmount ?: BigDecimal.ZERO
        else -> e.trade.amount
    }

    fun disposal(
        symbol: String,
        e: PortfolioLedgerEvent,
        kind: PortfolioFeeExpense,
        x: Expense,
        method: CostMethod,
        wallet: String? = null,
    ): PortfolioDisposal {
        val t = e.trade
        return PortfolioDisposal(
            coin = symbol, sellTxId = t.id, sellTime = t.time,
            lotTxId = t.id, acquiredTime = t.time, lotKind = if (e.swapIn) PortfolioTxType.SWAP else t.type,
            amount = amountOf(e, kind), costUsdt = x.usdt, proceedsUsdt = BigDecimal.ZERO, gainUsdt = x.usdt.negate(),
            holdingDays = 0L, feeLeg = false, feeValueMissing = x.feeValueMissing, method = method,
            wallet = wallet, feeExpense = kind,
        )
    }

    /**
     * Alle Gebühr-Aufwände eines Coins (eigene Rechnungen der Steuer-Vorlagen, [TaxEngines]); Auszahlung nur mit Bestand
     * ([PortfolioHoldingsBook], ein Topf). [CostMethod.FIFO] wie die Posten-Teile dort. [swaps] false: ohne den verbrauchten
     * Zugang eines Tauschs (Vorlagen, bei denen der Tausch keine Veräusserung ist).
     */
    fun of(symbol: String, events: List<PortfolioLedgerEvent>, swaps: Boolean = true): List<PortfolioDisposal> {
        val out = ArrayList<PortfolioDisposal>()
        val book = PortfolioHoldingsBook(CostMethod.FIFO)
        for (e in events) {
            consumed(e)?.takeIf { swaps || !e.swapIn }?.let { out += disposal(symbol, e, PortfolioFeeExpense.CONSUMED, it, CostMethod.FIFO) }
            if (!e.feeLeg && !e.swapIn && book.capFor(e).signum() > 0) {
                withdrawal(e.trade, book.capFor(e))?.let { out += disposal(symbol, e, PortfolioFeeExpense.WITHDRAWAL, it, CostMethod.FIFO) }
            }
            book.apply(e)
        }
        return out
    }
}

/** Zuordnung einer Veräusserung nach HMRC ([TaxMethod.UK_POOL], CRYPTO22200). */
enum class TaxMatch { SAME_DAY, THIRTY_DAY, POOL }

/**
 * Eine Auszahlung im Buch: abgegebene Menge und Einstand der verbrauchten Posten (FIFO) bzw. zum Ø — für die
 * Wertänderung bis zur Auszahlung ([PortfolioCalculator.withdrawnGain]).
 */
data class PortfolioWithdrawalCost(
    val trade: PortfolioTrade,
    /** Ausgezahlte Menge ohne Gebühr im Coin (gekappt auf den Bestand). */
    val sold: BigDecimal,
    /** Abgegebene Menge samt Gebühr im Coin (gekappt). */
    val removed: BigDecimal,
    /** Davon mit Kaufpreis. */
    val pricedRemoved: BigDecimal,
    /** Einstand des Teils mit Kaufpreis. */
    val cost: BigDecimal,
)

/**
 * Einstand einer Netzwerkgebühr im Coin (Umbuchung), der keinen bezahlten Posten mehr fand: Die Gebühr verbrauchte den
 * letzten Posten MIT Kaufpreis, übrig sind nur Posten ohne Kaufpreis (oder nichts), und (FIFO) bis zum Ende kam kein
 * Posten mit Kaufpreis mehr dazu, der ihn übernommen hätte. Der Einstand geht nicht still
 * verloren, sondern steht hier ([CoinPosition.lostFeeCostUsdt]; Steuer-Export: Vermerk «Netzwerkgebühr-Einstand ohne
 * bezahlten Posten»). KEIN realisierter Gewinn/Verlust.
 */
data class PortfolioLostFeeCost(val coin: String, val txId: Long, val time: Long, val costUsdt: BigDecimal)

/**
 * «Offener Posten sichtbar» (Entscheid des Inhabers 17.10.2026): Anteil einer Umbuchungsgebühr (Gebühr USDT + Wert der
 * Gebühr im dritten Coin), der nach Menge auf Bestand OHNE Kaufpreis fällt. Er gehört zu dessen unbekanntem Einstand und
 * kann keinem bekannten Einstand zugeschlagen werden — statt still zu entfallen steht er hier ([CoinPosition.unassignedFeeUsdt],
 * Anzeige «Gebühr nicht zugeordnet – Einstand unbekannt»; Steuer-Export: Info-Zeile). Weder im Einstand noch realisiert.
 * Die Veräusserung einer Gebühr im dritten Coin ([PortfolioDisposal.feeLeg]) bleibt davon unberührt.
 */
data class PortfolioUnassignedFee(val coin: String, val txId: Long, val time: Long, val usdt: BigDecimal)

/** Kennzahlen eines Coins samt offener Posten (nur FIFO, sonst leer) und Veräusserungen. */
data class CoinLedger(
    val position: CoinPosition,
    val lots: List<PortfolioLot>,
    val disposals: List<PortfolioDisposal>,
    /** Auszahlungen mit ihrem Einstand. */
    val withdrawals: List<PortfolioWithdrawalCost> = emptyList(),
    /** Offene Posten für die Anzeige (alle Methoden, [PortfolioOpenLot]). */
    val openLots: List<PortfolioOpenLot> = emptyList(),
    /** [CostMethod.FIFO_WALLET]: Abgänge über den Bestand ihrer Wallet ([PortfolioWalletShortfall]). */
    val walletShortfalls: List<PortfolioWalletShortfall> = emptyList(),
    /** Einstand von Netzwerkgebühren ohne bezahlten Posten ([PortfolioLostFeeCost]). */
    val lostFeeCosts: List<PortfolioLostFeeCost> = emptyList(),
    /** Umbuchungsgebühren auf Bestand ohne Kaufpreis ([PortfolioUnassignedFee]), zeitlich. */
    val unassignedFees: List<PortfolioUnassignedFee> = emptyList(),
)

/** Zwischenergebnis einer Methode (Ledger-Teil, ohne Adress-Guthaben), Basis von [CoinPosition]. */
internal class PortfolioBookResult(
    val priced: BigDecimal,
    val unpriced: BigDecimal,
    val cost: BigDecimal,
    val realized: BigDecimal,
    val unknownSold: BigDecimal,
    val soldTotal: BigDecimal,
    val withdrawn: BigDecimal,
    val oversold: Boolean,
    val feeValueMissing: Boolean,
    val realizedFeeMissing: Boolean,
    val lots: List<PortfolioLot>,
    val disposals: List<PortfolioDisposal>,
    val withdrawals: List<PortfolioWithdrawalCost> = emptyList(),
    val shortfalls: List<PortfolioWalletShortfall> = emptyList(),
    val lostFeeCosts: List<PortfolioLostFeeCost> = emptyList(),
    val unassignedFees: List<PortfolioUnassignedFee> = emptyList(),
)

/**
 * FIFO-Buchhaltung eines Coins (reines Kotlin, Spiegel: `Shared/Portfolio/PortfolioFifo.swift`):
 *  - Kauf, Belohnung, Einzahlung: neuer Posten (erhaltene Menge, Einstand Menge × Preis + Gebühr USDT + Wert
 *    der Gebühr im dritten Coin, Erwerb, Id, Art). Ohne Preis: Posten mit unbekanntem Einstand.
 *  - Reihenfolge der Posten: nach Erwerb ([PortfolioTrade.acquisitionTime] — bei einer Einzahlung mit
 *    ursprünglichem Kaufdatum dieses), dann Zeit der Transaktion, dann Id. Eine Einzahlung von früher gekaufter
 *    Coins reiht sich also VOR jüngere Käufe ein; Verkäufe, Auszahlungen und Gebühren verbrauchen immer die
 *    ältesten ERWORBENEN Posten. Haltedauer ab dem Erwerb.
 *  - Verkauf (auch Damit bezahlt, [PortfolioTxType.PAYMENT], zum Marktwert): verbraucht Posten, älteste zuerst (Menge + Gebühr im Coin). Die Gebühr im Coin gehört zur Veräusserung:
 *    zuerst die verkaufte Menge, DANACH die Gebühr-Coins aus den nächsten Posten (FIFO in einem Zug). Erlös nur nach
 *    VERKAUFTER Menge verteilt; ein Teil aus Gebühr-Coins hat Erlös 0 und sein Einstand zählt als Kosten der Veräusserung
 *    (Verlust in DIESEM Posten mit dessen Haltedauer) — so stimmt die Aufteilung kurz-/langfristig (DE-Praxis: die
 *    Gebühr ist Teil der Veräusserung). Je Teil Gewinn = Erlösanteil − Einstand des Teils; Teile aus Posten ohne Kaufpreis (oder
 *    Verkauf ohne Preis) haben unbekannten Gewinn — die bekannten zählen, «realisiert unvollständig» wie bisher.
 *    Mehr verkauft als erfasst: Bestand und Kennzahlen gekappt (markiert, [PortfolioBookResult.oversold]); für
 *    den Steuer-Export wird der ungedeckte Rest trotzdem eine Veräusserung mit seinem Erlösanteil, ohne
 *    Kaufpreis ([PortfolioDisposal.uncovered]) — so fehlt kein Verkauf.
 *  - Auszahlung: verbraucht Posten, älteste zuerst, OHNE Gewinn (kein Verkauf, keine Veräusserung); Einstand
 *    der verbrauchten Posten in [PortfolioBookResult.withdrawals]. Gebühr USDT/dritter Coin: Aufwand ([PortfolioFeeExpenses]).
 *  - Umbuchung: nichts bewegt sich, Posten behalten ihr Datum. Netzwerkgebühr im Coin verbraucht Menge,
 *    älteste zuerst; deren bekannter Einstand geht auf den ältesten verbleibenden Posten MIT Kaufpreis über
 *    (Summe des Einstands bleibt wie beim Ø). Ohne solchen Posten (übrig nur Posten ohne Kaufpreis) geht er nicht still
 *    verloren: Er geht auf den nächsten später hinzukommenden Posten mit Kaufpreis, sonst [PortfolioLostFeeCost] /
 *    [CoinPosition.lostFeeCostUsdt] (kein realisierter Verlust). Begründung: Die Gebühr ist
 *    Teil der Kosten, die Coins zu halten bzw. zu verschieben — sie ist keine Veräusserung (kein Erlös) und
 *    kein Verlust im Jahr der Umbuchung, sondern erhöht den Einstand der übrigen Coins; sie landet beim
 *    ältesten Posten, damit sie mit dem nächsten Verkauf realisiert wird. Gebühr USDT + Wert der Gebühr
 *    im dritten Coin verteilen sich nach Menge auf alle Posten; der Anteil von Posten ohne Kaufpreis gehört
 *    zu deren unbekanntem Einstand (wie beim Ø) und wird als [PortfolioUnassignedFee] vermerkt, nicht still verworfen.
 *  - Herkunft des Einstands: jeder Posten führt seinen Einstand in Teilen mit Zeitpunkt ([PortfolioCostPart]: Erwerb,
 *    zugeschlagene Umbuchungsgebühr zum Zeitpunkt der Umbuchung, übernommener Netzwerkgebühr-Einstand mit seinen Teilen);
 *    ein Abgang nimmt die Teile anteilig mit ([PortfolioDisposal.costParts]) — der Steuer-Export rechnet so jeden Teil
 *    zum Devisenkurs SEINES Datums um (Posten 2018 + Umbuchungsgebühr 2024: je zum eigenen Kurs). US-Übergang
 *    ([TaxEngines.usTransition]): Anfangsposten tragen nur ihr Erwerbsdatum (Steuerwährung USD, Kurs 1).
 *  - Gebühr im dritten Coin: im Gebühr-Coin eine Veräusserung (FIFO) zum Gebühr-Preis ([PortfolioDisposal.feeLeg]);
 *    Gewinn nur mit Gebühr-Preis bekannt. In der Haupttransaktion zählt ihr Wert zum Einstand (Kauf) bzw.
 *    mindert den Erlös (Verkauf).
 *  - Tausch ([PortfolioTxType.SWAP]): im abgegebenen Coin eine Veräusserung wie ein Verkauf (Menge + Gebühr im Coin,
 *    Erlös = Wert des Tauschs − Gebühr USDT − Wert der Gebühr im dritten Coin; Wert unbekannt → Erlös unbekannt); im
 *    erhaltenen Coin ein neuer Posten ([PortfolioTxType.SWAP]) mit erhaltener Menge (− Gebühr im erhaltenen Coin),
 *    Einstand = Wert des Tauschs (unbekannt → Posten ohne Kaufpreis), Erwerb = Zeit des Tauschs. Jede Gebühr zählt so
 *    genau einmal (wie Verkauf + Kauf mit Gebühr im erhaltenen Coin).
 *  - Je Wallet ([CostMethod.FIFO_WALLET], `perWallet`): je Wallet eine eigene Warteschlange ([PortfolioWallets]).
 *    Kauf, Belohnung, Einzahlung, Verkauf, Tausch, Auszahlung in der Wallet ihrer Börse; Gebühr-Abgang in der Wallet
 *    der Haupttransaktion. Umbuchung von A nach B: in A zuerst die Netzwerkgebühr (älteste Posten), dann die Menge;
 *    die verschobenen Teile kommen mit Kaufdatum und Einstand nach B; der Einstand der Netzwerkgebühr geht auf den
 *    ältesten verschobenen Teil mit Kaufpreis (sonst den ältesten in A, sonst in B), Gebühr USDT + Wert der Gebühr im dritten Coin
 *    nach Menge auf die verschobenen Teile. Reicht der Bestand einer Wallet nicht, wird NICHT aus anderen Wallets
 *    genommen: Verkauf/Tausch/Gebühr-Abgang → ungedeckter Rest ([PortfolioDisposal.uncovered]), Umbuchung → der Rest
 *    kommt nicht an; jedes Mal ein [PortfolioWalletShortfall] und [PortfolioBookResult.oversold].
 *  - Gebühr als Aufwand ([PortfolioFeeExpenses]): Gebühr im Coin verbraucht den ganzen Zugang (Kauf/Belohnung/
 *    Einzahlung bzw. erhaltener Coin eines Tauschs) → kein Posten, Aufwand realisiert; Gebühr USDT/dritter Coin einer
 *    Auszahlung → Aufwand realisiert.
 *  - Netzwerkgebühr-Einstand ohne bezahlten Posten: wartet in der Warteschlange und geht auf den nächsten Posten MIT
 *    Kaufpreis, der später hinzukommt (Kauf, Tausch, Einzahlung, verschobener Posten); erst wenn bis zum Ende keiner
 *    kommt, steht er als [PortfolioLostFeeCost] da.
 *  - Bestand läuft als Summe mit (kein erneutes Aufsummieren je Schritt).
 */
internal object PortfolioFifo {

    private const val DAY_MILLIS = 86_400_000L

    private class Lot(
        val txId: Long,
        /** Erwerb (Reihenfolge, Haltedauer). */
        val acquired: Long,
        /** Zeit der Transaktion (bei gleichem Erwerb). */
        val time: Long,
        val kind: PortfolioTxType,
        var amount: BigDecimal,
        cost: BigDecimal?,
        /** Herkunft von [cost]; null = ganzer Einstand zum Erwerb. */
        parts: List<PortfolioCostPart>? = null,
    ) {
        /** Rest-Einstand; null = Kaufpreis unbekannt. Ändert sich nur zusammen mit [parts]. */
        var cost: BigDecimal? = cost
            private set

        /** Herkunft von [cost] (Summe = [cost]); leer ohne Kaufpreis bzw. bei Einstand 0. */
        val parts = ArrayList<PortfolioCostPart>()

        init {
            if (cost != null) {
                if (parts != null) parts.forEach { PortfolioCostParts.add(this.parts, it.time, it.usdt) }
                else PortfolioCostParts.add(this.parts, acquired, cost)
            }
        }

        /** Gebühr bzw. übernommener Einstand [usdt] zum Zeitpunkt [at] (nur mit Kaufpreis). */
        fun addCost(at: Long, usdt: BigDecimal) {
            cost = cost!! + usdt
            PortfolioCostParts.add(parts, at, usdt)
        }

        /** Übernimmt Einstand samt Herkunft [from] (nur mit Kaufpreis). */
        fun addParts(from: List<PortfolioCostPart>) = from.forEach { addCost(it.time, it.usdt) }

        /** Einstand der Teilmenge [qty] (ganze Menge: ganzer Einstand) samt Herkunft herausnehmen; null ohne Kaufpreis. */
        fun takeCost(qty: BigDecimal): Pair<BigDecimal, List<PortfolioCostPart>>? {
            val c = cost ?: return null
            val removed = if (qty.compareTo(amount) == 0) c else c.multiply(qty).divide(amount, PortfolioDecimal.MC)
            val taken = PortfolioCostParts.take(parts, c, removed)
            cost = c - removed
            return removed to taken
        }
    }

    /** Warteschlange einer Wallet (universell: eine einzige). */
    private class Queue(val key: String) {
        val lots = ArrayList<Lot>()
        var held: BigDecimal = BigDecimal.ZERO // Σ Menge der Posten
        /** Netzwerkgebühr-Einstand ohne bezahlten Posten: wartet auf den nächsten Posten mit Kaufpreis. */
        val pendingLost = ArrayList<PortfolioLostFeeCost>()
        /** Herkunft von [pendingLost] (Summe gleich). */
        val pendingParts = ArrayList<PortfolioCostPart>()
    }

    /** Verbrauchter Teil eines Postens: Menge, Einstand (null = ohne Kaufpreis) und dessen Herkunft. */
    private class Slice(val lot: Lot, val amount: BigDecimal, val cost: BigDecimal?, val parts: List<PortfolioCostPart>)

    /** Summe der Einstände (ohne Kaufpreis = 0). */
    private fun List<Slice>.costSum(): BigDecimal = fold(BigDecimal.ZERO) { acc, s -> acc + (s.cost ?: BigDecimal.ZERO) }

    /**
     * Kalendertage zwischen [from] und [to] in [zone] (Standard UTC — so im Feld `holdingDays` der
     * Veräusserungen, gleich wie iOS). Die Steuer-Liste ([TaxExport.gainRows]) rechnet mit derselben
     * Funktion in ihrer Zeitzone — beide Wege können sich nicht widersprechen.
     */
    fun holdingDays(from: Long, to: Long, zone: java.time.ZoneId = java.time.ZoneOffset.UTC): Long =
        if (zone == java.time.ZoneOffset.UTC) {
            Math.floorDiv(to, DAY_MILLIS) - Math.floorDiv(from, DAY_MILLIS)
        } else {
            java.time.temporal.ChronoUnit.DAYS.between(
                java.time.Instant.ofEpochMilli(from).atZone(zone).toLocalDate(),
                java.time.Instant.ofEpochMilli(to).atZone(zone).toLocalDate(),
            )
        }

    /** Posten [a] liegt vor [b]: Erwerb, dann Zeit, dann Id. */
    private fun before(a: Lot, b: Lot): Boolean = when {
        a.acquired != b.acquired -> a.acquired < b.acquired
        a.time != b.time -> a.time < b.time
        else -> a.txId < b.txId
    }

    /**
     * [seed]: Anfangsposten je Wallet ([PortfolioLot.wallet] = Anzeigename) vor den Ereignissen — US-Übergang
     * ([TaxEngines.usTransition]); sonst leer.
     */
    fun run(
        symbol: String,
        events: List<PortfolioLedgerEvent>,
        perWallet: Boolean = false,
        seed: List<PortfolioLot> = emptyList(),
    ): PortfolioBookResult {
        val zero = BigDecimal.ZERO
        val mc = PortfolioDecimal.MC
        val method = if (perWallet) CostMethod.FIFO_WALLET else CostMethod.FIFO
        val queues = LinkedHashMap<String, Queue>()
        val names = HashMap<String, String>()
        var realized = zero
        var unknownSold = zero
        var soldTotal = zero
        var withdrawn = zero
        var oversold = false
        var feeValueMissing = false
        var realizedFeeMissing = false
        val disposals = ArrayList<PortfolioDisposal>()
        val withdrawals = ArrayList<PortfolioWithdrawalCost>()
        val shortfalls = ArrayList<PortfolioWalletShortfall>()
        val lostFeeCosts = ArrayList<PortfolioLostFeeCost>()
        val unassignedFees = ArrayList<PortfolioUnassignedFee>()

        /**
         * Einstand der Netzwerkgebühr ohne bezahlten Posten in [q] ([carried], Summe von [carried] je Teil): nicht still
         * verlieren — wartet auf den nächsten Posten mit Kaufpreis ([insert]), sonst am Ende vermerkt.
         */
        fun lose(q: Queue, t: PortfolioTrade, carried: List<Slice>) {
            val cost = carried.costSum()
            if (cost.signum() == 0) return
            q.pendingLost += PortfolioLostFeeCost(symbol, t.id, t.time, cost)
            carried.forEach { s -> s.parts.forEach { PortfolioCostParts.add(q.pendingParts, it.time, it.usdt) } }
        }

        /** Netzwerkgebühr-Einstand [carried] auf [target] (Posten mit Kaufpreis), sonst wartend in [q] ([lose]). */
        fun carry(target: Lot?, q: Queue, t: PortfolioTrade, carried: List<Slice>) {
            if (carried.costSum().signum() == 0) return
            if (target != null) carried.forEach { target.addParts(it.parts) } else lose(q, t, carried)
        }

        /** Umbuchungsgebühr-Anteil [usdt] auf Bestand ohne Kaufpreis: vermerkt ([PortfolioUnassignedFee]). */
        fun unassign(t: PortfolioTrade, usdt: BigDecimal) {
            if (usdt.signum() > 0) unassignedFees += PortfolioUnassignedFee(symbol, t.id, t.time, usdt)
        }

        /** Warteschlange der Wallet [raw] (universell immer dieselbe). */
        fun queue(raw: String?): Queue {
            val key = if (perWallet) PortfolioWallets.key(raw) else PortfolioWallets.UNSPECIFIED
            if (perWallet && key !in names) names[key] = raw?.trim().orEmpty()
            return queues.getOrPut(key) { Queue(key) }
        }

        /** Anzeigename der Wallet von [q] (nur je Wallet, sonst null). */
        fun walletOf(q: Queue): String? = if (perWallet) names[q.key] ?: PortfolioWallets.UNSPECIFIED else null

        /** Abgang [requested] grösser als der Bestand von [q]: markiert, je Wallet mit Fehlmenge. */
        fun check(q: Queue, requested: BigDecimal, t: PortfolioTrade, feeLeg: Boolean) {
            if (requested <= q.held) return
            oversold = true
            if (perWallet) {
                shortfalls += PortfolioWalletShortfall(symbol, walletOf(q)!!, t.id, t.time, t.type, feeLeg, requested - q.held.max(zero))
            }
        }

        /** Neuer Posten an seinem Platz (meist hinten); ein Posten mit Kaufpreis übernimmt wartenden Gebühr-Einstand. */
        fun insert(q: Queue, lot: Lot) {
            if (lot.cost != null && q.pendingLost.isNotEmpty()) {
                lot.addParts(q.pendingParts)
                q.pendingLost.clear()
                q.pendingParts.clear()
            }
            var i = q.lots.size
            while (i > 0 && before(lot, q.lots[i - 1])) i--
            q.lots.add(i, lot)
            q.held += lot.amount
        }

        /** Verbraucht [requested] (gekappt auf den Bestand von [q]), älteste Posten zuerst. */
        fun consume(q: Queue, requested: BigDecimal): List<Slice> {
            val out = ArrayList<Slice>()
            val lots = q.lots
            var left = requested.min(q.held)
            while (left.signum() > 0 && lots.isNotEmpty()) {
                val lot = lots[0]
                val qty = left.min(lot.amount)
                // Einstand anteilig samt Herkunft (vor dem Kürzen der Menge)
                val taken = lot.takeCost(qty)
                lot.amount -= qty
                q.held -= qty
                if (lot.amount.signum() <= 0) lots.removeAt(0)
                left -= qty
                out += Slice(lot, qty, taken?.first, taken?.second.orEmpty())
            }
            if (lots.isEmpty()) q.held = zero
            return out
        }

        /**
         * Gebühr USDT + Wert der Gebühr im dritten Coin ([extra]) einer Umbuchung zum Zeitpunkt [at] nach Menge auf [lots]
         * (Summe [h]); der letzte Posten bekommt den Rest, wenn alle einen Kaufpreis haben. Rückgabe = Anteil der Posten
         * OHNE Kaufpreis (gehört zu deren unbekanntem Einstand, [unassign]); 0 = alles zugeordnet.
         */
        fun spread(lots: List<Lot>, extra: BigDecimal, h: BigDecimal, at: Long): BigDecimal {
            if (extra.signum() <= 0 || h.signum() <= 0) return zero
            val allPriced = lots.all { it.cost != null }
            var left = extra
            var assigned = zero
            lots.forEachIndexed { i, lot ->
                val part = if (allPriced && i == lots.size - 1) left else extra.multiply(lot.amount).divide(h, mc)
                left -= part
                if (lot.cost != null) {
                    lot.addCost(at, part)
                    assigned += part
                }
            }
            return extra - assigned
        }

        /**
         * Veräusserung: Erlös [proceeds] (null = unbekannt) des ganzen Abgangs nach Menge auf die Teile [slices] und
         * den ungedeckten Rest [uncovered] verteilt; [sold] = verkaufte Menge ohne Gebühr im Coin, gedeckter Teil
         * (für «unbekannt verkauft»).
         */
        fun dispose(
            t: PortfolioTrade,
            q: Queue,
            slices: List<Slice>,
            uncovered: BigDecimal,
            proceeds: BigDecimal?,
            sold: BigDecimal,
            feeLeg: Boolean,
            feeMissing: Boolean,
            swap: Boolean = false,
            /** Je Teil die VERKAUFTE Menge (ohne Gebühr-Coins), Erlös nur danach; null = nach Menge (alles verkauft). */
            soldParts: List<BigDecimal>? = null,
            /** Verkaufte Menge im ungedeckten Rest (mit [soldParts]). */
            uncoveredSold: BigDecimal = uncovered,
        ) {
            val removed = slices.fold(zero) { acc, s -> acc + s.amount }
            val total = removed + uncovered
            if (total.signum() <= 0) return
            val wallet = walletOf(q)
            // Schenkung (als Veräusserung), Zahlung mit Krypto und geschätzter Wert: Vermerke im Steuer-Export
            val gift = !feeLeg && t.fromGift
            val payment = !feeLeg && t.type == PortfolioTxType.PAYMENT && !t.fromGift
            val estimated = !feeLeg && t.valueEstimated
            val weights = soldParts ?: slices.map { it.amount }
            val weightTotal = weights.fold(zero) { acc, w -> acc + w } + (if (soldParts != null) uncoveredSold else uncovered)
            var proceedsLeft = proceeds
            // Letzter Teil MIT Gewicht bekommt den Rest: Summe der Anteile = Erlös, exakt
            val lastWeighted = when {
                (if (soldParts != null) uncoveredSold else uncovered).signum() > 0 -> slices.size
                else -> weights.indexOfLast { it.signum() > 0 }
            }
            slices.forEachIndexed { i, s ->
                val w = weights[i]
                val share = proceeds?.let {
                    when {
                        w.signum() <= 0 -> zero
                        i == lastWeighted -> proceedsLeft!!
                        else -> it.multiply(w).divide(weightTotal, mc)
                    }
                }
                if (share != null) proceedsLeft = proceedsLeft!! - share
                val gain = if (share != null && s.cost != null) share - s.cost else null
                if (gain != null) {
                    realized += gain
                } else if (soldParts != null) {
                    unknownSold += w
                } else {
                    unknownSold += if (s.amount.compareTo(removed) == 0) sold else sold.multiply(s.amount).divide(removed, mc)
                }
                disposals += PortfolioDisposal(
                    coin = symbol, sellTxId = t.id, sellTime = t.time,
                    lotTxId = s.lot.txId, acquiredTime = s.lot.acquired, lotKind = s.lot.kind,
                    amount = s.amount, costUsdt = s.cost, proceedsUsdt = share, gainUsdt = gain,
                    holdingDays = holdingDays(s.lot.acquired, t.time),
                    feeLeg = feeLeg, feeValueMissing = feeMissing, method = method,
                    costParts = s.parts,
                    wallet = wallet, swap = swap, gift = gift, payment = payment, valueEstimated = estimated,
                )
            }
            if (uncovered.signum() > 0) {
                // Mehr abgegeben als erfasst: Erlös bekannt, Kaufpreis fehlt (nicht im realisierten Gewinn)
                disposals += PortfolioDisposal(
                    coin = symbol, sellTxId = t.id, sellTime = t.time,
                    lotTxId = null, acquiredTime = null, lotKind = null,
                    amount = uncovered, costUsdt = null, proceedsUsdt = proceedsLeft, gainUsdt = null,
                    holdingDays = null, feeLeg = feeLeg, feeValueMissing = feeMissing, method = method,
                    uncovered = true, wallet = wallet, swap = swap, gift = gift, payment = payment, valueEstimated = estimated,
                )
            }
        }

        /** Gebühr als Aufwand ([PortfolioFeeExpenses]): realisiert −Aufwand, eine Veräusserung mit Erlös 0. */
        fun expense(e: PortfolioLedgerEvent, q: Queue, kind: PortfolioFeeExpense, x: PortfolioFeeExpenses.Expense?) {
            if (x == null) return
            realized -= x.usdt
            if (x.feeValueMissing) feeValueMissing = true
            disposals += PortfolioFeeExpenses.disposal(symbol, e, kind, x, method, walletOf(q))
        }

        // Anfangsposten (Übergang): mit Erwerb und Rest-Einstand in ihre Wallet
        for (l in seed) {
            if (l.amount.signum() > 0) insert(queue(l.wallet), Lot(l.txId, l.acquiredTime, l.acquiredTime, l.kind, l.amount, l.costUsdt))
        }

        for (e in events) {
            val t = e.trade
            val amount = t.amount
            if (amount.signum() <= 0) continue
            if (e.feeLeg) {
                // Gebühr einer anderen Transaktion in diesem Coin bezahlt: Veräusserung zum Gebühr-Preis
                val q = queue(PortfolioWallets.of(e))
                val fee = t.thirdFeeAmount
                check(q, fee, t, feeLeg = true)
                val slices = consume(q, fee)
                val removed = slices.fold(zero) { acc, s -> acc + s.amount }
                dispose(t, q, slices, fee - removed, t.thirdFeePrice?.let { fee * it }, removed, feeLeg = true, feeMissing = false)
                soldTotal += removed
                continue
            }
            if (e.swapIn) {
                // Zugang aus einem Tausch: Einstand = Wert des Tauschs (Gebühren zählen beim Abgang); die Gebühr im
                // erhaltenen Coin senkt nur die Menge (Einstand je Coin steigt)
                val received = t.swapReceived
                if (received.signum() <= 0) {
                    // Gebühr verbraucht den ganzen Zugang: Wert des Tauschs als Aufwand (kein Schein-Gewinn)
                    expense(e, queue(t.exchange), PortfolioFeeExpense.CONSUMED, PortfolioFeeExpenses.consumed(e))
                    continue
                }
                insert(queue(t.exchange), Lot(t.id, t.time, t.time, PortfolioTxType.SWAP, received, t.swapValue))
                continue
            }
            // Gebühr-Zeile ohne Tageskurs: Erlös unbekannt ([PortfolioLedger.salePrice])
            val price = PortfolioLedger.salePrice(t)
            val feeValue = t.thirdFeeValue
            when (t.type) {
                PortfolioTxType.BUY, PortfolioTxType.REWARD, PortfolioTxType.DEPOSIT -> {
                    val received = t.received
                    if (received.signum() <= 0) {
                        // Gebühr im Coin verbraucht den ganzen Zugang: Einstand als Aufwand
                        expense(e, queue(t.exchange), PortfolioFeeExpense.CONSUMED, PortfolioFeeExpenses.consumed(e))
                        continue
                    }
                    val cost = price?.let { amount * it + t.quoteFee + (feeValue ?: zero) }
                    if (price != null && feeValue == null) feeValueMissing = true
                    insert(queue(t.exchange), Lot(t.id, t.acquisitionTime, t.time, t.type, received, cost))
                }
                PortfolioTxType.SELL, PortfolioTxType.PAYMENT, PortfolioTxType.SWAP -> {
                    // Damit bezahlt: wie ein Verkauf zum Marktwert (Preis = Marktwert je Coin)
                    val swap = t.type == PortfolioTxType.SWAP
                    val q = queue(t.exchange)
                    val requested = amount + t.coinFee
                    check(q, requested, t, feeLeg = false)
                    val slices = consume(q, requested)
                    val removed = slices.fold(zero) { acc, s -> acc + s.amount }
                    // Zuerst die verkaufte Menge, danach die Gebühr-Coins (FIFO in einem Zug): verkaufter Teil je Posten
                    var before = zero
                    val soldParts = slices.map { s ->
                        val part = (amount - before).max(zero).min(s.amount)
                        before += s.amount
                        part
                    }
                    val sold = soldParts.fold(zero) { acc, p -> acc + p }
                    // Erlös des ganzen Abgangs (Tausch: sein Wert) nur nach verkaufter Menge verteilt (Gebühr-Coins: Erlös 0)
                    // Tausch ohne gültigen Zugang (erhaltener Coin fehlt): Erlös unbekannt → «realisiert unvollständig»
                    val gross = if (swap) t.swapValue?.takeIf { t.swapToCoin != null } else price?.let { amount * it }
                    val proceeds = gross?.let { it - t.quoteFee - (feeValue ?: zero) }
                    val feeMissing = gross != null && feeValue == null
                    if (feeMissing && removed.signum() > 0) realizedFeeMissing = true
                    if (feeMissing) feeValueMissing = true
                    dispose(
                        t, q, slices, requested - removed, proceeds, sold, feeLeg = false, feeMissing = feeMissing, swap = swap,
                        soldParts = soldParts, uncoveredSold = amount - sold,
                    )
                    soldTotal += sold
                }
                // Verschenkt: wie eine Auszahlung (beim Schenkenden keine Veräusserung)
                PortfolioTxType.WITHDRAWAL, PortfolioTxType.GIFT -> {
                    // Coins verlassen das Portfolio: älteste Posten zuerst, kein Gewinn, keine Veräusserung
                    val q = queue(t.exchange)
                    val requested = amount + t.coinFee
                    check(q, requested, t, feeLeg = false)
                    if (q.held.signum() <= 0) continue
                    // Gebühr USDT + Wert der Gebühr im dritten Coin: Aufwand (realisiert), wie im Zeitraum
                    expense(e, q, PortfolioFeeExpense.WITHDRAWAL, PortfolioFeeExpenses.withdrawal(t, q.held))
                    val slices = consume(q, requested)
                    val removed = slices.fold(zero) { acc, s -> acc + s.amount }
                    val sold = if (removed.compareTo(requested) == 0) amount else removed.multiply(amount).divide(requested, mc)
                    withdrawn += sold
                    withdrawals += PortfolioWithdrawalCost(
                        trade = t, sold = sold, removed = removed,
                        pricedRemoved = slices.filter { it.cost != null }.fold(zero) { acc, s -> acc + s.amount },
                        cost = slices.fold(zero) { acc, s -> acc + (s.cost ?: zero) },
                    )
                }
                PortfolioTxType.TRANSFER -> {
                    val src = queue(t.transferFrom)
                    val dst = queue(t.transferTo)
                    val coinFee = t.coinFee
                    if (src !== dst) {
                        // Je Wallet: Posten wandern mit Kaufdatum und Einstand von der Quelle zum Ziel
                        check(src, amount + coinFee, t, feeLeg = false)
                        if (src.held.signum() <= 0) continue
                        // Netzwerkgebühr zuerst (älteste Posten der Quelle); ihr Einstand wandert mit
                        val carried = consume(src, coinFee)
                        val moved = consume(src, amount).map { s ->
                            Lot(s.lot.txId, s.lot.acquired, s.lot.time, s.lot.kind, s.amount, s.cost, s.parts)
                        }
                        // Gebühr USDT + Wert der Gebühr im dritten Coin nach Menge auf die verschobenen Teile; der Anteil
                        // ohne Kaufpreis wird vermerkt
                        if (feeValue == null && moved.any { it.cost != null }) feeValueMissing = true
                        unassign(t, spread(moved, t.quoteFee + (feeValue ?: zero), moved.fold(zero) { acc, l -> acc + l.amount }, t.time))
                        // Nächster Posten mit Kaufpreis: verschoben, Rest der Quelle, sonst im Ziel; erst ohne jeden
                        // wartet der Einstand (bei den verschobenen Coins im Ziel, sonst in der Quelle)
                        val target = moved.firstOrNull { it.cost != null } ?: src.lots.firstOrNull { it.cost != null }
                            ?: dst.lots.firstOrNull { it.cost != null }
                        carry(target, if (moved.isEmpty()) src else dst, t, carried)
                        moved.forEach { insert(dst, it) }
                        continue
                    }
                    val q = src
                    val h = q.held
                    if (h.signum() <= 0) continue
                    // Gebühr USDT + Wert der Gebühr im dritten Coin: nach Menge auf die Posten verteilt; der Anteil der
                    // Posten ohne Kaufpreis wird vermerkt
                    if (feeValue == null && q.lots.any { it.cost != null }) feeValueMissing = true
                    unassign(t, spread(q.lots, t.quoteFee + (feeValue ?: zero), h, t.time))
                    // Netzwerkgebühr im Coin: Menge sinkt (älteste zuerst), ihr Einstand geht auf den nächsten
                    // Posten mit Kaufpreis über (Begründung oben: Kosten des Haltens, keine Veräusserung)
                    if (coinFee > h) check(q, coinFee, t, feeLeg = false)
                    if (coinFee.signum() > 0) {
                        val carried = consume(q, coinFee)
                        carry(q.lots.firstOrNull { it.cost != null }, q, t, carried)
                    }
                }
            }
        }

        var priced = zero
        var unpriced = zero
        var cost = zero
        val outLots = ArrayList<PortfolioLot>()
        // Gebühr-Einstand, für den bis zum Ende kein Posten mit Kaufpreis kam: vermerkt (zeitlich)
        queues.values.flatMapTo(lostFeeCosts) { it.pendingLost }
        lostFeeCosts.sortWith(compareBy<PortfolioLostFeeCost> { it.time }.thenBy { it.txId })
        for (q in queues.values) {
            for (l in q.lots) {
                if (l.cost != null) { priced += l.amount; cost += l.cost!! } else unpriced += l.amount
                outLots += PortfolioLot(symbol, l.txId, l.acquired, l.kind, l.amount, l.cost, walletOf(q))
            }
        }
        return PortfolioBookResult(
            priced = priced, unpriced = unpriced, cost = cost, realized = realized,
            unknownSold = unknownSold, soldTotal = soldTotal, withdrawn = withdrawn, oversold = oversold,
            feeValueMissing = feeValueMissing, realizedFeeMissing = realizedFeeMissing,
            lots = outLots,
            disposals = disposals,
            withdrawals = withdrawals,
            shortfalls = shortfalls,
            lostFeeCosts = lostFeeCosts,
            unassignedFees = unassignedFees,
        )
    }
}
