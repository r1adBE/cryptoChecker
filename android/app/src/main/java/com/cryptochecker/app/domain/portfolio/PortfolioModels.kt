package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

// Datenmodell des Portfolios: Art, Gebühr-Einheit, Transaktion, Position je Coin, Gesamtsicht (Rechnung in [PortfolioCalculator]).

/**
 * Art einer Portfolio-Transaktion. Name wird so in Datenbank und Sicherung geschrieben (wie iOS).
 *  - [BUY]/[SELL]: Handel gegen USDT (Preis = Kurs je Coin).
 *  - [TRANSFER]: Umbuchung zwischen Börsen/Wallets (von/nach) — kein Gewinn, Einstand bleibt; eine
 *    Netzwerkgebühr im Coin verringert nur die Menge (ihr Einstand bleibt im Rest, der Ø steigt).
 *  - [REWARD]: Staking/Airdrop/Zinsen — Zugang zum Wert beim Erhalt (Preis = Wert je Coin; ohne Preis
 *    Teil «ohne Kaufpreis»).
 *  - [DEPOSIT]: Einzahlung von aussen (z. B. Bestand von früher) — Zugang, Preis optional.
 *  - [WITHDRAWAL]: Auszahlung aus dem erfassten Portfolio — Abgang (Ø: zum Ø-Einstand, FIFO: älteste
 *    Posten zuerst), KEIN Verkauf.
 *  - [SWAP]: Tausch Coin gegen Coin in EINER Transaktion ([PortfolioTrade.toCoin]/[PortfolioTrade.toAmount], Wert
 *    [PortfolioTrade.swapValueUsdt]) — Veräusserung von [PortfolioTrade.coin] und Zugang von `toCoin` (Regeln in
 *    [PortfolioLedger] und [PortfolioFifo]). Weder Zu- noch Abgang im Sinne von [isInflow]/[isOutflow].
 *  - [GIFT]: «Verschenkt» (Build 47) — beim Schenkenden in den meisten Ländern KEINE Veräusserung: Karte, Verlauf und die
 *    Einstandsmethoden rechnen sie wie eine Auszahlung (Bestand und Einstand gehen ab, Wert verlässt das Portfolio zum
 *    Marktwert, kein realisierter Gewinn). Nur wo die Schenkung beim Schenkenden eine Veräusserung zum Marktwert ist (UK,
 *    CA, AU, [TaxTemplate.giftIsDisposal]) wird sie im Steuer-Export wie [PAYMENT] gerechnet ([TaxEngines.applyRules],
 *    Vermerk «Schenkung»); an Ehe-/Lebenspartner ([PortfolioTrade.isGiftToSpouse]) in UK/CA ohne Gewinn und Verlust.
 *  - [PAYMENT]: «Damit bezahlt» (Build 47) — Tausch gegen Waren/Dienstleistungen: überall eine Veräusserung zum MARKTWERT
 *    wie ein Verkauf ([isSaleLike]; Preis = Marktwert je Coin, Erlös = Menge × Preis, kein Geld erhalten), Steuer-Export
 *    mit Vermerk «Zahlung mit Krypto». Ohne Preis zum Tagesschluss geschätzt ([PortfolioLedger.valueAtDayClose]).
 * Ältere Versionen kennen nur [BUY]/[SELL] (bis Build 45) bzw. kein [SWAP]/[GIFT]/[PAYMENT]: Sie zählen unbekannte Arten beim
 * Wiederherstellen als ungültig (Rückfrage, nichts stürzt ab). Der Name wird als Text gespeichert (Room-Enum-Konverter):
 * ein neues Mitglied braucht keine Migration, alte Daten lesen sich unverändert.
 */
enum class PortfolioTxType {
    BUY, SELL, TRANSFER, REWARD, DEPOSIT, WITHDRAWAL, SWAP, GIFT, PAYMENT;

    /** Zugang wie ein Kauf (Menge + Einstand, wenn der Preis bekannt ist). */
    val isInflow: Boolean get() = this == BUY || this == REWARD || this == DEPOSIT

    /** Abgang (Verkauf, Auszahlung, Verschenkt, Damit bezahlt). */
    val isOutflow: Boolean get() = this == SELL || this == WITHDRAWAL || this == GIFT || this == PAYMENT

    /** Veräusserung wie ein Verkauf (Verkauf oder Damit bezahlt zum Marktwert). */
    val isSaleLike: Boolean get() = this == SELL || this == PAYMENT

    /** Abgang ohne Veräusserung wie eine Auszahlung (Auszahlung oder Verschenkt). */
    val isWithdrawalLike: Boolean get() = this == WITHDRAWAL || this == GIFT

    companion object {
        /** Gespeicherter Name; unbekannt (neuere Version) = null. */
        fun fromName(name: String?): PortfolioTxType? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Währung einer Gebühr. Name wird so in Datenbank und Sicherung geschrieben (wie iOS).
 *  - [QUOTE]: in USDT (Währung der Preise) — Kauf: erhöht den Einstand, Verkauf: verringert den Erlös.
 *  - [COIN]: im gehandelten Coin — verringert die Menge (Kauf: weniger erhalten, Verkauf: mehr abgegeben).
 */
enum class PortfolioFeeUnit {
    QUOTE, COIN;

    companion object {
        /** Unbekannt oder fehlend: [QUOTE] (Standard des Editors). */
        fun fromName(name: String?): PortfolioFeeUnit = entries.firstOrNull { it.name == name } ?: QUOTE
    }
}

/**
 * Eine Transaktion, unabhängig von der Datenbank (reines Kotlin, testbar).
 * Mengen, Preise (USDT) und Gebühr exakt als [BigDecimal] ([PortfolioDecimal]); [priceUsdt] null =
 * Preis unbekannt (z. B. übernommener Bestand). [fee] null = keine Gebühr erfasst; Einheit
 * [feeUnit] (null = [PortfolioFeeUnit.QUOTE]).
 *
 * Gebühr in einem DRITTEN Coin (z. B. BNB): [feeCoin] + [feeCoinAmount] (> 0), dazu optional
 * [feeCoinPrice] = USDT je Gebühr-Coin zu dem Zeitpunkt. Gilt nur, wenn [feeCoin] ein anderer Coin ist
 * ([thirdFeeCoin]); sie besteht neben [fee]/[feeUnit].
 *
 * [acquiredTime]: ursprüngliches Kaufdatum (ms) einer Einzahlung ([PortfolioTxType.DEPOSIT], z. B. Bestand
 * von einer anderen Börse); null = Datum der Einzahlung. Zählt für die FIFO-Reihenfolge und die Haltedauer
 * ([acquisitionTime]); bei anderen Arten ohne Wirkung.
 *
 * [exchange], [transferFrom], [transferTo]: Börse/Wallet (FIFO je Wallet, [CostMethod.FIFO_WALLET]).
 *
 * Tausch ([PortfolioTxType.SWAP]): [coin]/[amount] abgegeben, [toCoin]/[toAmount] erhalten, [swapValueUsdt] = Wert
 * des Tauschs in USDT (gesamt; null = unbekannt, dann ersatzweise [priceUsdt] × [amount] bzw. die Stablecoin-Regel,
 * [swapValue]). Gebühren: USDT ([quoteFee]) und dritter Coin mindern den Erlös; im abgegebenen Coin ([coinFee])
 * zusätzlich abgegeben; im erhaltenen Coin ([feeCoin] = [toCoin], [toCoinFee]) weniger erhalten.
 */
data class PortfolioTrade(
    val id: Long,
    val coin: String,
    val type: PortfolioTxType,
    val amount: BigDecimal,
    val priceUsdt: BigDecimal?,
    val time: Long,
    val fee: BigDecimal? = null,
    val feeUnit: PortfolioFeeUnit? = null,
    val feeCoin: String? = null,
    val feeCoinAmount: BigDecimal? = null,
    val feeCoinPrice: BigDecimal? = null,
    val acquiredTime: Long? = null,
    val exchange: String? = null,
    val transferFrom: String? = null,
    val transferTo: String? = null,
    val toCoin: String? = null,
    val toAmount: BigDecimal? = null,
    val swapValueUsdt: BigDecimal? = null,
    /**
     * Verschenkt an Ehe-/Lebenspartner (gespeichert seit Portfolio-Datenbank v4, `giftToSpouse`); wirkt nur bei
     * [PortfolioTxType.GIFT] ([isGiftToSpouse]) und nur in den Steuer-Vorlagen UK/CA ([TaxTemplate.spouseGiftNeutral]) —
     * alle anderen Vorlagen und das Portfolio selbst behandeln die Schenkung wie jede andere.
     */
    val giftToSpouse: Boolean = false,
    /**
     * Wert nur geschätzt (Tagesschluss, [PortfolioLedger.valueAtDayClose]): Verschenkt/bezahlt ohne Marktwert bzw. Tausch
     * ohne Wert — Steuer-Export «Wert geschätzt (Tagesschluss)» (geschätzt, nicht fehlend). Nie gespeichert.
     */
    val valueEstimated: Boolean = false,
    /** Aus einem CSV-Import ([com.cryptochecker.app.data.portfolio.PortfolioTxEntity.importKey] gesetzt). */
    val imported: Boolean = false,
    /**
     * Gebühr-Zeile (CSV «Fee», [com.cryptochecker.app.data.portfolio.PortfolioTxEntity.feeRow]) — eine Veräusserung zum
     * Tageskurs ([PortfolioLedger.isFeeDisposal]); nur bei [PortfolioTxType.SELL].
     */
    val feeRow: Boolean = false,
    /**
     * Verschenkt, im Steuer-Export als Veräusserung gerechnet ([TaxEngines.applyRules]: Typ [PortfolioTxType.PAYMENT]
     * mit diesem Merker, Vermerk «Schenkung»). Nie gespeichert.
     */
    val fromGift: Boolean = false,
    /**
     * Belohnung ist ein Zins (Lending; [com.cryptochecker.app.data.portfolio.PortfolioTxEntity.interest]) — AT:
     * Anschaffungskosten = Wert beim Zufluss statt 0.
     */
    val interestIncome: Boolean = false,
) {
    /** Mit Gleitkomma-Werten (Tests, Kurse aus dem Netz): über die kürzeste Darstellung, NaN/∞ = ungültig. */
    constructor(
        id: Long,
        coin: String,
        type: PortfolioTxType,
        amount: Double,
        priceUsdt: Double?,
        time: Long,
        fee: Double? = null,
        feeUnit: PortfolioFeeUnit? = null,
    ) : this(
        id, coin, type,
        PortfolioDecimal.of(amount) ?: BigDecimal.ZERO,
        PortfolioDecimal.of(priceUsdt),
        time,
        PortfolioDecimal.of(fee),
        feeUnit,
    )

    /**
     * Zeitpunkt des Erwerbs (FIFO-Reihenfolge, Haltedauer, Devisenkurs des Einstands): bei einer Einzahlung das
     * ursprüngliche Kaufdatum [acquiredTime], höchstens die Zeit der Einzahlung (ein späteres ist ein Tippfehler);
     * sonst [time].
     */
    val acquisitionTime: Long
        get() = if (type == PortfolioTxType.DEPOSIT && acquiredTime != null) minOf(acquiredTime, time) else time

    /** Schenkung an Ehe-/Lebenspartner: [giftToSpouse] bei einer Schenkung; nur die Steuer-Vorlagen UK/CA rechnen damit. */
    val isGiftToSpouse: Boolean get() = type == PortfolioTxType.GIFT && giftToSpouse

    /** Gültige Gebühr (> 0), sonst 0. */
    private val validFee: BigDecimal get() = fee?.takeIf { it.signum() > 0 } ?: BigDecimal.ZERO

    /** Gebühr in USDT, sonst 0. */
    val quoteFee: BigDecimal
        get() = if ((feeUnit ?: PortfolioFeeUnit.QUOTE) == PortfolioFeeUnit.QUOTE) validFee else BigDecimal.ZERO

    /** Gebühr im Coin, sonst 0. */
    val coinFee: BigDecimal
        get() = if (feeUnit == PortfolioFeeUnit.COIN) validFee else BigDecimal.ZERO

    /** Bei einem Kauf erhaltene Menge: Menge − Gebühr im Coin (nie negativ). */
    val received: BigDecimal get() = (amount - coinFee).max(BigDecimal.ZERO)

    /** Gültiger Preis (≥ 0), sonst null. */
    val validPrice: BigDecimal? get() = priceUsdt?.takeIf { it.signum() >= 0 }

    /** Menge als Double — nur für Bewertung und Verlauf mit Börsenkursen. */
    val amountValue: Double get() = amount.toDouble()

    /**
     * Gebühr-Coin (normalisiert), wenn eine gültige Gebühr in einem anderen Coin erfasst ist; sonst null. Beim Tausch
     * ist eine Gebühr im erhaltenen Coin keine dritte ([toCoinFee]).
     */
    val thirdFeeCoin: String?
        get() {
            val c = feeCoin?.let { PortfolioCalculator.normalizeCoin(it) }?.takeIf { it.isNotEmpty() } ?: return null
            if (c == PortfolioCalculator.normalizeCoin(coin)) return null
            if (c == swapToCoin) return null
            return c.takeIf { feeCoinAmount?.signum() == 1 }
        }

    /** Tausch: erhaltener Coin (normalisiert, ≠ [coin], mit [toAmount] > 0); sonst null (dann kein Zugang). */
    val swapToCoin: String?
        get() {
            if (type != PortfolioTxType.SWAP) return null
            val c = toCoin?.let { PortfolioCalculator.normalizeCoin(it) }?.takeIf { it.isNotEmpty() } ?: return null
            if (c == PortfolioCalculator.normalizeCoin(coin)) return null
            return c.takeIf { toAmount?.signum() == 1 }
        }

    /** Tausch: Gebühr im erhaltenen Coin ([feeCoin] = [toCoin], > 0), sonst 0. */
    val toCoinFee: BigDecimal
        get() {
            val to = swapToCoin ?: return BigDecimal.ZERO
            val c = feeCoin?.let { PortfolioCalculator.normalizeCoin(it) } ?: return BigDecimal.ZERO
            return if (c == to) feeCoinAmount?.takeIf { it.signum() > 0 } ?: BigDecimal.ZERO else BigDecimal.ZERO
        }

    /** Tausch: erhaltene Menge − Gebühr im erhaltenen Coin (nie negativ); ohne gültigen Zugang 0. */
    val swapReceived: BigDecimal
        get() = if (swapToCoin == null) BigDecimal.ZERO else ((toAmount ?: BigDecimal.ZERO) - toCoinFee).max(BigDecimal.ZERO)

    /**
     * Tausch: Wert in USDT (gesamt, für [amount] abgegeben bzw. [toAmount] erhalten, ohne Gebühren) — [swapValueUsdt]
     * (≥ 0), sonst [priceUsdt] × [amount], sonst Stablecoin-Regel (USDT abgegeben: [amount]; USDT erhalten:
     * [toAmount]); null = unbekannt (Erlös und Einstand des neuen Postens unbekannt). Andere Arten: null.
     */
    val swapValue: BigDecimal?
        get() {
            if (type != PortfolioTxType.SWAP) return null
            swapValueUsdt?.takeIf { it.signum() >= 0 }?.let { return it }
            validPrice?.let { return amount * it }
            if (PortfolioStables.isValuation(PortfolioCalculator.normalizeCoin(coin))) return amount
            val to = swapToCoin
            if (to != null && PortfolioStables.isValuation(to)) return toAmount
            return null
        }

    /** Menge der Gebühr im dritten Coin, sonst 0. */
    val thirdFeeAmount: BigDecimal get() = if (thirdFeeCoin != null) feeCoinAmount ?: BigDecimal.ZERO else BigDecimal.ZERO

    /** USDT je Gebühr-Coin: erfasster Preis (≥ 0), für USDT immer 1; null = unbekannt oder keine Gebühr. */
    val thirdFeePrice: BigDecimal?
        get() {
            val c = thirdFeeCoin ?: return null
            return feeCoinPrice?.takeIf { it.signum() >= 0 } ?: BigDecimal.ONE.takeIf { PortfolioStables.isValuation(c) }
        }

    /** Wert der Gebühr im dritten Coin in USDT: 0 ohne solche Gebühr, null = Preis unbekannt. */
    val thirdFeeValue: BigDecimal?
        get() {
            if (thirdFeeCoin == null) return BigDecimal.ZERO
            return thirdFeePrice?.let { thirdFeeAmount * it }
        }
}

/** Kennzahlen eines Coins. Beträge in USDT. */
data class CoinPosition(
    val coin: String,
    /** Gehaltene Menge nach allen Transaktionen (nie negativ). */
    val holdings: Double,
    /** Ø Kaufpreis = Einstand ÷ Menge mit Kaufpreis (nach der Methode); null ohne solche Menge. */
    val avgCost: Double?,
    /** Einstand des Bestands («investiert»); null, wenn bei einem Teil der Kaufpreis fehlt. */
    val costBasis: Double?,
    val currentPrice: Double?,
    /** Bestand × aktueller Kurs; null ohne Kurs. */
    val value: Double?,
    /**
     * Unrealisierter Gewinn/Verlust («Seit Kauf») des Teils MIT Kaufpreis (Wert dieses Teils − sein Einstand); null
     * ohne Kurs oder ohne Teil mit Kaufpreis. Fehlt nur bei einem Teil der Kaufpreis: [unrealizedPartial].
     */
    val unrealized: Double?,
    val unrealizedPercent: Double?,
    /**
     * Realisierter Gewinn/Verlust des BEKANNTEN Teils: Verkäufe mit Preis, anteilig für die Menge
     * mit Kaufpreis (Ø über die Käufe mit Preis). Der Rest steht in [realizedUnknownAmount].
     */
    val realized: Double,
    /** Ein Teil des Bestands hat keinen Kaufpreis («Preis fehlt»). */
    val priceMissing: Boolean,
    /** Mindestens ein Verkauf war grösser als der Bestand und wurde gekappt. */
    val oversold: Boolean,
    val tradeCount: Int,
    /**
     * Verkaufte Menge mit unbekanntem Gewinn/Verlust: Anteil ohne Kaufpreis an Verkäufen mit Preis
     * sowie Verkäufe ohne Preis. Zählt nicht in [realized]; die Anzeige zeigt dafür «—».
     */
    val realizedUnknownAmount: Double = 0.0,
    /** Insgesamt verkaufte Menge (gekappt auf den Bestand) — für «geschlossen». */
    val soldAmount: Double = 0.0,
    /** [holdings] exakt (Anzeige der Menge ohne Rundung, z. B. 0.00000001 BTC). */
    val holdingsExact: BigDecimal = PortfolioDecimal.of(holdings) ?: BigDecimal.ZERO,
    /** [costBasis] exakt; null wie dort. */
    val costBasisExact: BigDecimal? = PortfolioDecimal.of(costBasis),
    /** [realized] exakt. */
    val realizedExact: BigDecimal = PortfolioDecimal.of(realized) ?: BigDecimal.ZERO,
    /**
     * Menge MIT Kaufpreis — nur über sie geht [avgCost]. Kleiner als [holdingsExact], wenn
     * [priceMissing]: Dann nennt die Anzeige den Ø «von 0.5 BTC mit Kaufpreis».
     */
    val pricedHoldingsExact: BigDecimal = if (priceMissing) BigDecimal.ZERO else holdingsExact,
    /** [value] exakt: Bestand × kürzeste Darstellung des Kurses; null ohne Kurs. */
    val valueExact: BigDecimal? = PortfolioDecimal.of(value),
    /** [unrealized] exakt: Menge mit Kaufpreis × Kurs − [pricedCostExact]; null wie dort. */
    val unrealizedExact: BigDecimal? = PortfolioDecimal.of(unrealized),
    /**
     * Bei mindestens einer Transaktion dieses Coins fehlt der Preis der Gebühr in einem dritten Coin:
     * Einstand bzw. Erlös sind ohne diese Gebühr gerechnet (Anzeige «unvollständig»).
     */
    val feeValueMissing: Boolean = false,
    /** Ein Verkauf mit Gebühr in einem dritten Coin ohne deren Preis — realisiert ohne diese Gebühr. */
    val realizedFeeMissing: Boolean = false,
    /** Ausgezahlte Menge ([PortfolioTxType.WITHDRAWAL], gekappt auf den Bestand) — für «geschlossen». */
    val withdrawnAmount: Double = 0.0,
    /**
     * Guthaben beobachteter Adressen (in [holdingsExact] und [valueExact] enthalten) — eigener Teil ohne
     * Einstand: Einstand, Ø, «Seit Kauf», realisiert und Steuer rechnen nur mit den eigenen Transaktionen.
     */
    val addressHoldingsExact: BigDecimal = BigDecimal.ZERO,
    /** Wert von [addressHoldingsExact]; null ohne Kurs. */
    val addressValueExact: BigDecimal? = null,
    /** Einstandsmethode, nach der gerechnet wurde. */
    val method: CostMethod = CostMethod.DEFAULT,
    /**
     * Einstand von Netzwerkgebühren (Umbuchung, Gebühr im Coin), die den letzten Posten MIT Kaufpreis verbrauchten —
     * übrig nur Bestand ohne Kaufpreis ([PortfolioLostFeeCost]). Weder im Einstand noch realisiert; Anzeige/Steuer-
     * Export: «Netzwerkgebühr-Einstand ohne bezahlten Posten». 0 = keiner.
     */
    val lostFeeCostUsdt: BigDecimal = BigDecimal.ZERO,
    /**
     * Umbuchungsgebühren (Anteil auf Bestand OHNE Kaufpreis), die keinem bekannten Einstand zugeschlagen werden können
     * ([PortfolioUnassignedFee]). Weder im Einstand noch realisiert; Anzeige «Gebühr nicht zugeordnet – Einstand
     * unbekannt», Steuer-Export: Info-Zeile. 0 = keine.
     */
    val unassignedFeeUsdt: BigDecimal = BigDecimal.ZERO,
    /**
     * Mindestens eine Veräusserung ([PortfolioDisposal]) hat einen Teil ohne Einstand oder ohne Erlös (z. B. Gebühr im
     * Coin aus einem Posten ohne Kaufpreis, ungedeckter Rest) bzw. ohne Wert der Gebühr — dieselben Daten, die der
     * Steuer-Export als «unvollständig» markiert.
     */
    val disposalsIncomplete: Boolean = false,
    /**
     * Einstand des Teils MIT Kaufpreis ([pricedHoldingsExact]) — Basis von [unrealized]/[unrealizedPercent]; gleich
     * [costBasisExact], wenn kein Teil ohne Kaufpreis ist. null ohne eigenen Teil mit Kaufpreis.
     */
    val pricedCostExact: BigDecimal? = costBasisExact,
    /**
     * Herkunft von [pricedCostExact] ([PortfolioCostPart]: Zeitpunkt und Betrag je Zugang bzw. zugeschlagener Gebühr,
     * Summe = [pricedCostExact]) nach der Einstandsmethode — FIFO die offenen Posten, Ø der Topf nach anteiligen
     * Abgängen. Leer ohne Teil mit Kaufpreis bzw. bei Einstand 0. Basis von «Seit Kauf» in der Anzeigewährung
     * ([PortfolioCostFx]).
     */
    val pricedCostParts: List<PortfolioCostPart> = emptyList(),
) {
    val isOpen: Boolean get() = holdingsExact.signum() > 0

    /** Eigener Bestand OHNE Kaufpreis (nicht in «Seit Kauf»); 0 = keiner. */
    val unpricedHoldingsExact: BigDecimal get() = (ledgerHoldingsExact - pricedHoldingsExact).max(BigDecimal.ZERO)

    /**
     * «Seit Kauf» gilt nur für den Teil mit Kaufpreis — Anzeige «ohne x COIN ohne Kaufpreis» ([unpricedHoldingsExact])
     * und «unvollständig».
     */
    val unrealizedPartial: Boolean get() = unrealizedExact != null && priceMissing && unpricedHoldingsExact.signum() > 0

    /** Bestand aus den eigenen Transaktionen (ohne Adress-Guthaben) — nur er hat Einstand und Posten. */
    val ledgerHoldingsExact: BigDecimal get() = (holdingsExact - addressHoldingsExact).max(BigDecimal.ZERO)

    /** Ein Teil (oder alles) stammt aus beobachteten Adressen. */
    val hasAddressPart: Boolean get() = addressHoldingsExact.signum() > 0

    /**
     * Ein Teil des realisierten Gewinns/Verlusts ist unbekannt ([realizedUnknownAmount], [realizedFeeMissing],
     * [disposalsIncomplete]) — gleiche Regel wie «unvollständig» im Steuer-Export.
     */
    val realizedIncomplete: Boolean
        get() = realizedUnknownAmount > PortfolioCalculator.EPS || realizedFeeMissing || disposalsIncomplete

    /** Ø gilt nur für einen Teil des Bestands (ein Teil ohne Kaufpreis) — Anzeige «Ø von … mit Kaufpreis». */
    val avgCostPartial: Boolean get() = avgCost != null && priceMissing && pricedHoldingsExact.signum() > 0
}

/** Eigener Bestand ohne Kaufpreis eines Coins, der sonst einen Teil mit Kaufpreis hat ([PortfolioSummary.partialCostParts]). */
data class PortfolioUnpricedPart(val coin: String, val amount: BigDecimal)

/** Gesamtsicht über alle Coins. */
data class PortfolioSummary(
    /** Coins mit Bestand, nach Wert absteigend. */
    val open: List<CoinPosition>,
    /** Coins ohne Bestand, die verkauft wurden oder realisierten Gewinn/Verlust haben (auch 0/unbekannt). */
    val closed: List<CoinPosition>,
    /** Summe der Werte (Coins ohne Kurs zählen nicht mit). */
    val totalValue: Double,
    /** Einstand der offenen Positionen mit Kaufpreis; null, wenn keine einen hat. */
    val invested: Double?,
    /**
     * Gewinn seit Kauf (unrealisiert) über die offenen Coins mit Teil MIT Kaufpreis UND aktuellem Kurs — je Coin
     * nur dieser Teil ([CoinPosition.unrealized]); Coins ganz ohne Kaufpreis ([missingCostCoins]), Teile ohne
     * Kaufpreis ([partialCostParts]) oder Coins ohne Kurs ([missingCurrentPrices]) zählen nicht mit («unvollständig»).
     * null, wenn kein Coin beides hat. [unrealizedPercent] auf den Einstand dieser Teile.
     */
    val unrealized: Double?,
    val unrealizedPercent: Double?,
    val realized: Double,
    /** Bei mindestens einem offenen Coin fehlt ein Kaufpreis. */
    val costMissing: Boolean,
    /** Offene Coins ohne aktuellen Kurs. */
    val missingCurrentPrices: List<String>,
    /** Bei mindestens einem Coin ist ein Teil des realisierten Gewinns/Verlusts unbekannt. */
    val realizedIncomplete: Boolean = false,
    /** Offene Coins ganz ohne Kaufpreis (kein eigener Teil mit Kaufpreis) — fehlen in [unrealized] und [invested]. */
    val missingCostCoins: List<String> = emptyList(),
    /** [invested] exakt (Summe der Einstände); null wie dort. */
    val investedExact: BigDecimal? = PortfolioDecimal.of(invested),
    /** [realized] exakt. */
    val realizedExact: BigDecimal = PortfolioDecimal.of(realized) ?: BigDecimal.ZERO,
    /** Coins mit Gebühr in einem dritten Coin ohne deren Preis ([CoinPosition.feeValueMissing]), alphabetisch. */
    val feeValueMissingCoins: List<String> = emptyList(),
    /** Wert der Adress-Guthaben (in [totalValue] enthalten; Coins ohne Kurs zählen nicht). */
    val addressValue: Double = 0.0,
    /** Einstandsmethode, nach der gerechnet wurde. */
    val method: CostMethod = CostMethod.DEFAULT,
    /**
     * Wertänderung ausgebuchter Coins bis zur Auszahlung ([PortfolioCalculator.withdrawnGain], bekannter Teil):
     * steckt in der Gewinn/Verlust-Kurve des Wertverlaufs, nicht in «Seit Kauf»/«realisiert». null = keine
     * Auszahlung mit bekanntem Teil. Karte: «Ausgebucht: +X» nur, wenn ≠ 0.
     */
    val withdrawnGainExact: BigDecimal? = null,
    /** Bei mindestens einer Auszahlung fehlt ein Teil davon (Kaufpreis, Kurs oder Wert der Gebühr). */
    val withdrawnGainIncomplete: Boolean = false,
    /**
     * Offene Coins, bei denen nur ein TEIL ohne Kaufpreis ist: «Seit Kauf» zählt den Teil mit Kaufpreis, der Rest
     * fehlt (Hinweis «ohne 0.5 BTC ohne Kaufpreis»). In [invested] fehlen diese Coins (Einstand unvollständig).
     */
    val partialCostParts: List<PortfolioUnpricedPart> = emptyList(),
    /**
     * Summe der nicht zugeordneten Umbuchungsgebühren aller Coins ([CoinPosition.unassignedFeeUsdt]) — für den Hinweis
     * «Gebühr nicht zugeordnet – Einstand unbekannt»; 0 = keine.
     */
    val unassignedFeeUsdt: BigDecimal = BigDecimal.ZERO,
) {
    val isEmpty: Boolean get() = open.isEmpty() && closed.isEmpty()
}
