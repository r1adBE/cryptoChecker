package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

// Bausteine der Buchhaltung je Coin: Einstandsmethode, Wallets, Posten, Veräusserungen, Ergebnis eines Buchs
// (Rechnung in [PortfolioFifo] bzw. [PortfolioCalculator]; Spiegel: `PortfolioLots.swift`).

/**
 * Einstandsmethode des Portfolios (Einstellung «costMethod», Standard [FIFO] für alle Installationen).
 *  - [FIFO]: zuerst gekauft, zuerst verkauft — je Coin über ALLE Börsen/Wallets, nach Zeit, dann Id.
 *  - [FIFO_WALLET]: FIFO je Börse/Wallet («FIFO je Wallet/Börse»): Wallet einer Transaktion = ihre Börse
 *    ([PortfolioTrade.exchange], leer = eigener Topf «ohne Angabe»); eine Umbuchung verschiebt die Posten mit ihrem
 *    Kaufdatum von [PortfolioTrade.transferFrom] nach [PortfolioTrade.transferTo]. Das BMF-Schreiben zu Kryptowerten
 *    (10.05.2022, neu gefasst 06.03.2025, Rn. 62: walletbezogene Betrachtung) lässt FIFO je Wallet zu; das
 *    universelle FIFO bleibt der Standard.
 *  - [AVERAGE]: gleitender Durchschnitt («Ø»).
 * Name wird so gespeichert (wie iOS); ein unbekannter Name gilt als [DEFAULT].
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
 * in `PortfolioLots.swift`. Invariante: Summe der Teile = Einstand (exakt).
 */
object PortfolioCostParts {

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
    /**
     * [TaxMethod.FR_PTA]: [portfolioValueUsdt] enthält Guthaben beobachteter Adressen — die haben keinen Einstand in der
     * Erwerbspreis-Summe, der Gewinn ist dadurch eher zu hoch ([TaxSummaryNote.FR_ADDRESS_NO_COST]).
     */
    val addressValueIncluded: Boolean = false,
    /** Gebühr als Aufwand ([PortfolioFeeExpenses]): Einstand = Aufwand, Erlös 0; sonst null. */
    val feeExpense: PortfolioFeeExpense? = null,
    /**
     * UK/CA: Schenkung an Ehe-/Lebenspartner ([PortfolioTrade.isGiftToSpouse]) — Erlös = Einstand, kein Gewinn/Verlust (TCGA 1992 s. 58,
     * ITA 73(1)); der Einstand geht auf den Partner über. Vermerk im Export.
     */
    val spouseGift: Boolean = false,
    /**
     * [TaxMethod.UK_POOL]: Erlös vor Gebühren (Menge × Preis bzw. Wert des Tauschs, wie [proceedsUsdt] nach Menge
     * verteilt) — die Meldegrenze an HMRC gilt für die Erlöse vor Abzug der Kosten ([TaxTemplate.ukReportProceedsGbp]);
     * sonst null.
     */
    val proceedsBeforeFeesUsdt: BigDecimal? = null,
)

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
 * «Offener Posten sichtbar»: Anteil einer Umbuchungsgebühr (Gebühr USDT + Wert der
 * Gebühr im dritten Coin), der nach Menge auf Bestand OHNE Kaufpreis fällt. Er gehört zu dessen unbekanntem Einstand und
 * kann keinem bekannten Einstand zugeschlagen werden — statt still zu entfallen steht er hier ([CoinPosition.unassignedFeeUsdt],
 * Anzeige «Gebühr nicht zugeordnet – Einstand unbekannt»; Steuer-Export: Info-Zeile). Weder im Einstand noch realisiert.
 * Die Veräusserung einer Gebühr im dritten Coin ([PortfolioDisposal.feeLeg]) bleibt davon unberührt. Ebenso
 * der Anteil einer Auszahlungsgebühr, der auf nicht erfassten Bestand fällt ([PortfolioFeeExpenses.withdrawalUncovered]).
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
class PortfolioBookResult(
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
    /**
     * Herkunft des Einstands [cost] des offenen Bestands mit Kaufpreis ([PortfolioCostPart], Summe = [cost]): FIFO die
     * Teile aller Posten mit Kaufpreis, Ø die Teile des Topfs nach den anteiligen Abgängen — für «Seit Kauf» in der
     * Anzeigewährung zum Kurs des Erwerbstags ([PortfolioCostFx]).
     */
    val openParts: List<PortfolioCostPart> = emptyList(),
)
