package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

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
        get() = if (swapToCoin == null) BigDecimal.ZERO else (toAmount!! - toCoinFee).max(BigDecimal.ZERO)

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
    val thirdFeeAmount: BigDecimal get() = if (thirdFeeCoin != null) feeCoinAmount!! else BigDecimal.ZERO

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

/**
 * Buchhaltung je Coin nach der Einstandsmethode [CostMethod]: FIFO (Standard) in [PortfolioFifo], dort beschrieben;
 * Durchschnittskosten ([CostMethod.AVERAGE], «Ø») wie folgt:
 *  - Transaktionen je Coin zeitlich (bei Gleichstand nach Id) abarbeiten — dazu die Gebühren ANDERER
 *    Transaktionen, die in diesem Coin bezahlt wurden ([PortfolioLedger.events], «Gebühr-Abgang»).
 *  - Gerechnet wird exakt mit [BigDecimal] ([PortfolioDecimal]): Bestand, Einstand, Gebühren und
 *    realisierter Gewinn summieren sich ohne Gleitkomma-Fehler (1 Satoshi = 0.00000001 BTC bleibt
 *    1 Satoshi). Nur Teilungen (Ø, anteiliger Verkauf) runden auf 34 gültige Stellen.
 *  - Kauf, Belohnung, Einzahlung mit Preis: Einstand += Menge × Preis + Gebühr USDT + Wert der Gebühr im
 *    dritten Coin, Bestand mit Preis += erhaltene Menge (Menge − Gebühr im Coin). Ø = Einstand / Bestand
 *    mit Preis. Ohne Preis erhöht nur den Bestand (Teil «ohne Preis»).
 *  - Verkauf (auch Damit bezahlt, [PortfolioTxType.PAYMENT], zum Marktwert): Bestand sinkt um Menge + Gebühr im Coin (nie unter 0; ein Verkauf grösser als der
 *    Bestand wird gekappt und markiert). Beide Teile (mit/ohne Preis) sinken anteilig, der Ø bleibt.
 *    Realisiert += Anteil MIT Kaufpreis × (Menge × Verkaufspreis − Gebühr USDT − Wert der Gebühr im
 *    dritten Coin) − Einstand des abgegebenen Teils, wenn der Verkaufspreis bekannt ist. Der Anteil ohne
 *    Kaufpreis (und Verkäufe ohne Preis) hat unbekannten Gewinn/Verlust und zählt separat
 *    ([CoinPosition.realizedUnknownAmount]).
 *  - Auszahlung: wie ein Verkauf abgegeben (Menge + Gebühr im Coin, anteilig, Einstand zum Ø), aber KEIN
 *    realisierter Gewinn. Gebühr USDT + Wert der Gebühr im dritten Coin: realisierter Aufwand ([PortfolioFeeExpenses],
 *    Steuer-Export «Gebühr Auszahlung»; mehr ausgezahlt als vorhanden: zum gedeckten Anteil) — wie im Zeitraum, wo der
 *    Abfluss um die Gebühr kleiner ist.
 *  - Gebühr-Zeile ([PortfolioLedger.isFeeDisposal], CSV «Fee»): Verkauf zum Tageskurs ([summarize] bewertet sie mit
 *    `withdrawalPrice`); ohne Kurs Erlös unbekannt (Menge in [CoinPosition.realizedUnknownAmount]).
 *  - Gebühr im Coin verbraucht den ganzen Zugang (Kauf/Belohnung/Einzahlung, erhaltener Coin eines Tauschs): kein
 *    Bestand, der Einstand bzw. Wert des Tauschs ist realisierter Aufwand ([PortfolioFeeExpenses]).
 *  - Umbuchung: Menge und Einstand bleiben. Gebühr im Coin (Netzwerk) verringert nur die Menge (beide
 *    Teile anteilig); ihr Einstand bleibt im Rest — der Ø steigt (bleibt kein Teil mit Kaufpreis: vermerkt in
 *    [CoinPosition.lostFeeCostUsdt]). Gebühr USDT + Wert der Gebühr im dritten
 *    Coin erhöhen den Einstand des Teils mit Kaufpreis (Anteil mit Preis / Bestand; der Rest gehört zum
 *    unbekannten Einstand und wird als [PortfolioUnassignedFee] vermerkt — ohne Teil mit Kaufpreis die ganze Gebühr).
 *  - Gebühr-Abgang im Gebühr-Coin (z. B. BNB): Bestand sinkt um die Gebühr (gekappt, markiert), beide
 *    Teile anteilig mit ihrem Einstand — wie eine Veräusserung: realisiert += Anteil mit Kaufpreis ×
 *    Gebühr × Gebühr-Preis − abgegebener Einstand. Ohne Gebühr-Preis: Menge als «unbekannter Gewinn».
 *  - Tausch: im abgegebenen Coin wie ein Verkauf mit Erlös = Wert des Tauschs − Gebühr USDT − Wert der Gebühr im
 *    dritten Coin (Wert unbekannt: Gewinn unbekannt); im erhaltenen Coin wie ein Kauf: Einstand += Wert des Tauschs,
 *    Bestand += erhaltene Menge − Gebühr im erhaltenen Coin (Wert unbekannt: Teil «ohne Preis»).
 *  - Gebühr im dritten Coin ohne Preis: Die Haupttransaktion bekommt keine Gebühr in Einstand/Erlös und
 *    wird markiert ([CoinPosition.feeValueMissing], beim Verkauf auch [CoinPosition.realizedFeeMissing]).
 *  - Gewinn/Verlust je Coin nur, wenn der ganze Restbestand einen Kaufpreis hat. Die Summe
 *    ([PortfolioSummary.unrealized]) zählt nur Coins mit Einstand und Kurs.
 *  - Aktueller Kurs nach der Stablecoin-Regel ([PortfolioStables]): USDT immer 1, andere
 *    Stablecoins ihr Marktkurs, ohne Kurs kein Wert (wie jeder Coin: «—», «ohne Kurs»).
 */
object PortfolioCalculator {

    /** Darunter gilt eine Menge als null. */
    const val EPS = 1e-12


    fun normalizeCoin(coin: String): String = coin.trim().uppercase()

    /**
     * Kennzahlen eines Coins aus seinen Transaktionen (fremde Coins zählen nur mit ihren Gebühren in diesem Coin)
     * nach [method]. Adress-Guthaben ([AddressBalance.isAddressTrade]) zählen nur als eigener Teil mit
     * ([CoinPosition.addressHoldingsExact]).
     */
    fun position(
        coin: String,
        trades: List<PortfolioTrade>,
        currentPrice: Double?,
        method: CostMethod = CostMethod.DEFAULT,
    ): CoinPosition = ledger(coin, trades, currentPrice, method).position

    /**
     * Wie [position], dazu offene Posten (nur FIFO) und Veräusserungen für den Steuer-Export.
     * Adress-Guthaben: Einträge mit negativer Id ([AddressBalance.withAddresses]) sind KEINE Posten — ihr
     * Guthaben steht als eigener Teil in [CoinPosition.addressHoldingsExact]: Menge und Wert zählen mit,
     * Einstand, Ø, «Seit Kauf», realisiert und Steuer nur aus den eigenen Transaktionen.
     */
    fun ledger(
        coin: String,
        trades: List<PortfolioTrade>,
        currentPrice: Double?,
        method: CostMethod = CostMethod.DEFAULT,
        /** Marktkurs für Gebühr-Zeilen (Tageskurs, wie [summarize] `withdrawalPrice`); null = Erlös unbekannt. */
        feePrice: ((coin: String, time: Long) -> Double?)? = null,
    ): CoinLedger {
        val symbol = normalizeCoin(coin)
        val (real, address) = AddressBalance.split(feeValued(trades, feePrice))
        return ledgerOf(symbol, PortfolioLedger.events(symbol, real), address[symbol], currentPrice, method)
    }

    /**
     * Gebühr-Zeilen, Verschenkt/bezahlt ohne Marktwert und Tausch ohne Wert zum Tageskurs [price] (> 0, endlich), sonst
     * unbekannt ([PortfolioLedger.valueAtDayClose]).
     */
    private fun feeValued(trades: List<PortfolioTrade>, price: ((String, Long) -> Double?)?): List<PortfolioTrade> =
        PortfolioLedger.valueAtDayClose(trades, price?.let { f ->
            { coin: String, time: Long -> f(coin, time)?.takeIf { it > 0.0 && it.isFinite() }?.let { PortfolioDecimal.of(it) } }
        })

    /** [ledger] aus den schon gesammelten Schritten [events] des Coins (ohne Adress-Einträge). */
    private fun ledgerOf(
        symbol: String,
        events: List<PortfolioLedgerEvent>,
        addressAmount: BigDecimal?,
        currentPrice: Double?,
        method: CostMethod,
    ): CoinLedger {
        val book = when (method) {
            CostMethod.FIFO -> PortfolioFifo.run(symbol, events)
            CostMethod.FIFO_WALLET -> PortfolioFifo.run(symbol, events, perWallet = true)
            CostMethod.AVERAGE -> average(symbol, events)
        }
        val zero = BigDecimal.ZERO
        val mc = PortfolioDecimal.MC
        val priced = book.priced
        val unpriced = book.unpriced
        val cost = book.cost

        val ledgerHoldings = (priced + unpriced).let { if (PortfolioDecimal.isDust(it)) zero else it }
        val addressHoldings = addressAmount?.takeIf { it.signum() > 0 } ?: zero
        val holdings = ledgerHoldings + addressHoldings
        // Stablecoin-Regel: USDT = 1, andere Stablecoins Marktkurs, sonst kein Wert ([PortfolioStables])
        val price = PortfolioStables.price(symbol, currentPrice)
        val open = holdings.signum() > 0
        val ledgerOpen = ledgerHoldings.signum() > 0
        val priceMissing = ledgerOpen && unpriced.signum() > 0
        val avgCost = if (priced.signum() > 0) cost.divide(priced, mc).toDouble() else null
        val costBasisExact = if (ledgerOpen && !priceMissing) cost else null
        val costBasis = costBasisExact?.toDouble()
        // Einstand des Teils mit Kaufpreis: «Seit Kauf» auch dann, wenn nur ein Teil ohne Kaufpreis ist
        val pricedHoldings = if (ledgerOpen) priced else zero
        val pricedCostExact = if (pricedHoldings.signum() > 0) cost else null
        // Wert und unrealisierter Gewinn exakt (Kurs über seine kürzeste Darstellung); Double nur für die Anzeige.
        // Wert = ganzer Bestand (mit Adressen), «Seit Kauf» nur auf den eigenen Teil mit Kaufpreis.
        val priceExact = price?.let { PortfolioDecimal.of(it) }
        val valueExact = priceExact?.let { holdings * it }
        val value = valueExact?.toDouble()
        // Ganz mit Kaufpreis: ganzer eigener Bestand (wie bisher, Staub inklusive), sonst nur der Teil mit Kaufpreis
        val pricedValueExact = priceExact?.let { (if (priceMissing) pricedHoldings else ledgerHoldings) * it }
        val unrealizedExact = if (pricedCostExact != null && pricedValueExact != null) pricedValueExact - pricedCostExact else null
        val unrealized = unrealizedExact?.toDouble()
        val unrealizedPercent = if (unrealizedExact != null && pricedCostExact != null && pricedCostExact.signum() > 0) {
            unrealizedExact.divide(pricedCostExact, mc).toDouble() * 100.0
        } else null

        val position = CoinPosition(
            coin = symbol,
            holdings = holdings.toDouble(),
            avgCost = avgCost,
            costBasis = costBasis,
            currentPrice = price,
            value = value,
            unrealized = unrealized,
            unrealizedPercent = unrealizedPercent,
            realized = book.realized.toDouble(),
            priceMissing = priceMissing,
            oversold = book.oversold,
            tradeCount = events.count { !it.feeLeg },
            realizedUnknownAmount = if (PortfolioDecimal.isDust(book.unknownSold)) 0.0 else book.unknownSold.toDouble(),
            soldAmount = if (PortfolioDecimal.isDust(book.soldTotal)) 0.0 else book.soldTotal.toDouble(),
            holdingsExact = holdings,
            costBasisExact = costBasisExact,
            realizedExact = book.realized,
            pricedHoldingsExact = pricedHoldings,
            valueExact = valueExact,
            unrealizedExact = unrealizedExact,
            feeValueMissing = book.feeValueMissing,
            realizedFeeMissing = book.realizedFeeMissing,
            withdrawnAmount = if (PortfolioDecimal.isDust(book.withdrawn)) 0.0 else book.withdrawn.toDouble(),
            addressHoldingsExact = addressHoldings,
            addressValueExact = priceExact?.let { addressHoldings * it },
            method = method,
            lostFeeCostUsdt = book.lostFeeCosts.fold(zero) { acc, l -> acc + l.costUsdt },
            unassignedFeeUsdt = book.unassignedFees.fold(zero) { acc, f -> acc + f.usdt },
            // Aus denselben Veräusserungen wie der Steuer-Export: ein Teil ohne Einstand/Erlös → unvollständig
            disposalsIncomplete = book.disposals.any { it.costUsdt == null || it.proceedsUsdt == null || it.feeValueMissing },
            pricedCostExact = pricedCostExact,
        )
        // Offene Posten für die Anzeige: FIFO je Posten, Ø zusammengefasst (Teil mit / ohne Kaufpreis)
        val openLots = if (method.isFifo) {
            book.lots.map { PortfolioOpenLot(symbol, it.txId, it.acquiredTime, it.kind, it.amount, it.costUsdt, it.wallet, aggregated = false) }
        } else {
            listOfNotNull(
                PortfolioOpenLot(symbol, null, null, null, priced, cost, null, aggregated = true).takeIf { priced.signum() > 0 && ledgerOpen },
                PortfolioOpenLot(symbol, null, null, null, unpriced, null, null, aggregated = true).takeIf { unpriced.signum() > 0 && ledgerOpen },
            )
        }
        return CoinLedger(
            position, book.lots, book.disposals, book.withdrawals, openLots, book.shortfalls, book.lostFeeCosts, book.unassignedFees,
        )
    }

    /**
     * Offene Posten eines Coins nach [method] ([PortfolioOpenLot]): FIFO und FIFO je Wallet die tatsächlichen Posten
     * (Kaufdatum, Restmenge, Rest-Einstand, Wallet; auch ohne Kaufpreis), Ø zusammengefasst. Ohne Adress-Guthaben.
     */
    fun openLots(coin: String, trades: List<PortfolioTrade>, method: CostMethod = CostMethod.DEFAULT): List<PortfolioOpenLot> =
        ledger(coin, trades, null, method).openLots

    /**
     * [CostMethod.FIFO_WALLET]: alle Abgänge über den Bestand ihrer Wallet ([PortfolioWalletShortfall]), zeitlich, dann
     * Id, Coin. Leer bei den anderen Methoden. Für den Hinweis «Bestand in … reicht nicht – fehlt eine Umbuchung?».
     */
    fun walletShortfalls(trades: List<PortfolioTrade>, method: CostMethod = CostMethod.FIFO_WALLET): List<PortfolioWalletShortfall> {
        if (method != CostMethod.FIFO_WALLET) return emptyList()
        val real = AddressBalance.split(trades).first
        return PortfolioLedger.eventsByCoin(real).entries
            .flatMap { (coin, events) -> PortfolioFifo.run(coin, events, perWallet = true).shortfalls }
            .sortedWith(compareBy<PortfolioWalletShortfall> { it.time }.thenBy { it.txId }.thenBy { it.coin })
    }

    /**
     * Alle Veräusserungen (Steuer-Export) nach [method]: zeitlich (Verkauf), dann Id des Verkaufs, Coin; je
     * Verkauf die Posten in FIFO-Reihenfolge. Adress-Guthaben kommen nicht vor.
     */
    fun disposals(trades: List<PortfolioTrade>, method: CostMethod = CostMethod.DEFAULT): List<PortfolioDisposal> {
        val real = AddressBalance.split(trades).first
        // Schritte je Coin einmal gesammelt (nicht je Coin über alle Transaktionen)
        val byCoin = PortfolioLedger.eventsByCoin(real)
        return byCoin.entries
            .flatMap { (coin, events) -> ledgerOf(coin, events, null, null, method).disposals }
            .sortedWith(compareBy<PortfolioDisposal> { it.sellTime }.thenBy { it.sellTxId }.thenBy { it.coin })
    }

    /** Wertänderung ausgebuchter Coins bis zur Auszahlung: bekannter Teil und ob etwas fehlt. */
    data class WithdrawnGain(val amount: BigDecimal, val incomplete: Boolean, val count: Int)

    /**
     * Wertänderung ausgebuchter Coins: je Auszahlung (Wert beim Abgang − Einstand der verbrauchten Posten bzw.
     * zum Ø). Wert beim Abgang = ausgezahlte Menge × Preis der Auszahlung (sonst [marketPrice], Kurs zum
     * Zeitpunkt). Gebühr USDT und Wert der Gebühr im dritten Coin zählen NICHT hier, sondern als realisierter
     * Aufwand ([PortfolioFeeExpenses]); zusammen ergibt das den Abfluss im Wertverlauf ([PortfolioLedger.flow]).
     * Gezählt wird nur der Teil mit Kaufpreis (Wert anteilig); fehlt ein Teil oder ein Kurs:
     * [WithdrawnGain.incomplete]. Diese Wertänderung steckt in der Kurve des Wertverlaufs (Kursbewegung, solange
     * gehalten), aber nicht in «Seit Kauf» und «realisiert».
     */
    fun withdrawnGain(
        withdrawals: List<PortfolioWithdrawalCost>,
        marketPrice: ((coin: String, time: Long) -> Double?)? = null,
    ): WithdrawnGain {
        var sum = BigDecimal.ZERO
        var incomplete = false
        var count = 0
        for (w in withdrawals) {
            if (w.removed.signum() <= 0) continue
            count++
            val t = w.trade
            val price = t.validPrice
                ?: marketPrice?.invoke(normalizeCoin(t.coin), t.time)?.takeIf { it >= 0.0 && it.isFinite() }?.let { PortfolioDecimal.of(it) }
            if (price == null || w.pricedRemoved.signum() <= 0) { incomplete = true; continue }
            if (w.pricedRemoved < w.removed) incomplete = true
            // Gebühren: realisierter Aufwand, nicht hier
            val value = w.sold * price
            val pricedValue = if (w.pricedRemoved.compareTo(w.removed) == 0) value
            else value.multiply(w.pricedRemoved).divide(w.removed, PortfolioDecimal.MC)
            sum += pricedValue - w.cost
        }
        return WithdrawnGain(sum, incomplete, count)
    }

    /**
     * Durchschnittskosten-Methode (siehe oben), mit einer Veräusserung je Verkauf (Teil mit/ohne Kaufpreis, dazu
     * der ungedeckte Rest bei «mehr verkauft als erfasst»). Der Einstand wird zusätzlich nach Herkunft geführt
     * ([PortfolioCostPart]: Zeitpunkt und Betrag je Zugang/Gebühr); ein Abgang nimmt alle Teile anteilig mit —
     * so rechnet der Steuer-Export den Ø-Einstand zum Devisenkurs JE Zugang um.
     */
    private fun average(symbol: String, events: List<PortfolioLedgerEvent>): PortfolioBookResult {
        val zero = BigDecimal.ZERO
        val mc = PortfolioDecimal.MC

        var priced = zero       // Menge mit Kaufpreis
        var unpriced = zero     // Menge ohne Kaufpreis
        var cost = zero         // Einstand des Teils mit Kaufpreis (inkl. Gebühren in USDT)
        val parts = ArrayList<PortfolioCostPart>() // Herkunft von [cost]
        var realized = zero
        var unknownSold = zero  // verkaufte Menge mit unbekanntem Gewinn/Verlust
        var soldTotal = zero
        var withdrawn = zero
        var oversold = false
        var feeValueMissing = false
        var realizedFeeMissing = false
        val disposals = ArrayList<PortfolioDisposal>()
        val withdrawals = ArrayList<PortfolioWithdrawalCost>()
        val lostFeeCosts = ArrayList<PortfolioLostFeeCost>()
        val unassignedFees = ArrayList<PortfolioUnassignedFee>()

        /** Ergebnis von [take]: abgegebene Menge, Anteil mit Kaufpreis davor, abgegebener Einstand samt Herkunft. */
        class Taken(
            val removed: BigDecimal,
            val allPriced: Boolean,
            val share: BigDecimal,
            val costRemoved: BigDecimal,
            val removedPriced: BigDecimal,
            val partsRemoved: List<PortfolioCostPart>,
        )

        fun addCost(time: Long, usdt: BigDecimal) {
            cost += usdt
            if (usdt.signum() != 0) parts += PortfolioCostPart(time, usdt)
        }

        /** Nimmt [removed] von [before] (Einstand davor) anteilig aus allen Herkunftsteilen; Summe exakt [removed]. */
        fun takeParts(removed: BigDecimal, before: BigDecimal): List<PortfolioCostPart> {
            if (removed.signum() == 0 || parts.isEmpty()) return emptyList()
            if (removed >= before) {
                val all = parts.toList()
                parts.clear()
                return all
            }
            val out = ArrayList<PortfolioCostPart>(parts.size)
            var left = removed
            for (i in parts.indices) {
                val p = parts[i]
                val r = if (i == parts.size - 1) left else p.usdt.multiply(removed).divide(before, mc)
                left -= r
                out += PortfolioCostPart(p.time, r)
                parts[i] = PortfolioCostPart(p.time, p.usdt - r)
            }
            return out
        }

        /** Gibt [requested] ab (gekappt auf den Bestand > 0), beide Teile anteilig samt Einstand. */
        fun take(requested: BigDecimal): Taken {
            val holdings = priced + unpriced
            val removed = requested.min(holdings)
            val allPriced = unpriced.signum() == 0
            val share = if (allPriced) BigDecimal.ONE else priced.divide(holdings, mc)
            val removedPriced = if (allPriced) removed else PortfolioDecimal.amount(removed.multiply(share, mc)).min(priced)
            val costRemoved = when {
                priced.signum() == 0 -> zero
                removedPriced >= priced -> cost
                else -> cost.multiply(removedPriced).divide(priced, mc)
            }
            val partsRemoved = takeParts(costRemoved, cost)
            // Gesamtbestand bleibt exakt; der Teil ohne Preis ist der Rest
            val rest = holdings - removed
            priced -= removedPriced
            cost -= costRemoved
            if (PortfolioDecimal.isDust(priced) || priced.signum() < 0) { priced = zero; cost = zero; parts.clear() }
            unpriced = (rest - priced).max(zero)
            if (PortfolioDecimal.isDust(unpriced)) unpriced = zero
            return Taken(removed, allPriced, share, costRemoved, removedPriced, partsRemoved)
        }

        /**
         * Veräusserungen eines Abgangs: Teil mit Kaufpreis (Einstand zum Ø), Teil ohne Kaufpreis und der ungedeckte
         * Rest [uncovered] (mehr abgegeben als erfasst). [proceeds] = Erlös des GANZEN Abgangs; [coveredProceeds] =
         * Anteil des gedeckten Teils.
         */
        fun record(
            t: PortfolioTrade,
            taken: Taken?,
            hadPriced: Boolean,
            proceeds: BigDecimal?,
            coveredProceeds: BigDecimal?,
            uncovered: BigDecimal,
            feeLeg: Boolean,
            feeMissing: Boolean,
            swap: Boolean = false,
        ) {
            // Schenkung (als Veräusserung), Zahlung mit Krypto und geschätzter Wert: Vermerke im Steuer-Export
            val gift = !feeLeg && t.fromGift
            val payment = !feeLeg && t.type == PortfolioTxType.PAYMENT && !t.fromGift
            val estimated = !feeLeg && t.valueEstimated
            fun add(
                amount: BigDecimal,
                costPart: BigDecimal?,
                proceedsPart: BigDecimal?,
                gain: BigDecimal?,
                costParts: List<PortfolioCostPart> = emptyList(),
                isUncovered: Boolean = false,
            ) {
                if (amount.signum() <= 0) return
                disposals += PortfolioDisposal(
                    coin = symbol, sellTxId = t.id, sellTime = t.time, lotTxId = null, acquiredTime = null, lotKind = null,
                    amount = amount, costUsdt = costPart, proceedsUsdt = proceedsPart, gainUsdt = gain, holdingDays = null,
                    feeLeg = feeLeg, feeValueMissing = feeMissing, method = CostMethod.AVERAGE,
                    costParts = costParts, uncovered = isUncovered, swap = swap, gift = gift, payment = payment, valueEstimated = estimated,
                )
            }
            if (taken != null) {
                if (!hadPriced) {
                    add(taken.removed, null, coveredProceeds, null)
                } else {
                    val pricedProceeds = coveredProceeds?.let { if (taken.allPriced) it else it.multiply(taken.share, mc) }
                    add(
                        taken.removedPriced, taken.costRemoved, pricedProceeds, pricedProceeds?.let { it - taken.costRemoved },
                        taken.partsRemoved,
                    )
                    if (!taken.allPriced) add(taken.removed - taken.removedPriced, null, coveredProceeds?.let { it - pricedProceeds!! }, null)
                }
            }
            // Ungedeckter Rest: Erlös bekannt, Kaufpreis fehlt
            add(uncovered, null, proceeds?.let { p -> coveredProceeds?.let { p - it } ?: p }, null, isUncovered = true)
        }

        /** Gebühr als Aufwand ([PortfolioFeeExpenses]): realisiert −Aufwand, eine Veräusserung mit Erlös 0. */
        fun expense(e: PortfolioLedgerEvent, kind: PortfolioFeeExpense, x: PortfolioFeeExpenses.Expense?) {
            if (x == null) return
            realized -= x.usdt
            if (x.feeValueMissing) feeValueMissing = true
            disposals += PortfolioFeeExpenses.disposal(symbol, e, kind, x, CostMethod.AVERAGE)
        }

        /** Erlösanteil des gedeckten Teils [removed] von [requested] (nach Menge). */
        fun coveredShare(proceeds: BigDecimal?, removed: BigDecimal, requested: BigDecimal): BigDecimal? = proceeds?.let {
            when {
                removed.compareTo(requested) == 0 -> it
                removed.signum() <= 0 -> null
                else -> it.multiply(removed).divide(requested, mc)
            }
        }

        for (e in events) {
            val t = e.trade
            val amount = t.amount
            if (amount.signum() <= 0) continue
            if (e.swapIn) {
                // Zugang aus einem Tausch: Einstand = Wert des Tauschs (Gebühren zählen beim Abgang); ohne Wert ohne Kaufpreis
                val received = t.swapReceived
                if (received.signum() <= 0) {
                    // Gebühr verbraucht den ganzen Zugang: Wert des Tauschs als Aufwand (kein Schein-Gewinn)
                    expense(e, PortfolioFeeExpense.CONSUMED, PortfolioFeeExpenses.consumed(e))
                    continue
                }
                val value = t.swapValue
                if (value != null) {
                    addCost(t.time, value)
                    priced += received
                } else {
                    unpriced += received
                }
                continue
            }
            if (e.feeLeg) {
                // Gebühr einer anderen Transaktion in diesem Coin bezahlt: Veräusserung zum Gebühr-Preis
                val fee = t.thirdFeeAmount
                val holdings = priced + unpriced
                if (fee > holdings) oversold = true
                val feePrice = t.thirdFeePrice
                val proceeds = feePrice?.let { fee * it }
                if (holdings.signum() <= 0) {
                    record(t, null, false, proceeds, null, fee, feeLeg = true, feeMissing = false)
                    continue
                }
                val hadPriced = priced.signum() > 0
                val taken = take(fee)
                val covered = coveredShare(proceeds, taken.removed, fee)
                if (covered != null && hadPriced) {
                    realized += (if (taken.allPriced) covered else covered.multiply(taken.share, mc)) - taken.costRemoved
                    if (!taken.allPriced) unknownSold += taken.removed.multiply(BigDecimal.ONE - taken.share, mc)
                } else {
                    unknownSold += taken.removed
                }
                record(t, taken, hadPriced, proceeds, covered, fee - taken.removed, feeLeg = true, feeMissing = false)
                soldTotal += taken.removed
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
                        expense(e, PortfolioFeeExpense.CONSUMED, PortfolioFeeExpenses.consumed(e))
                        continue
                    }
                    if (price != null) {
                        // Gebühren in USDT und im dritten Coin gehören zum Einstand (Herkunft: Erwerb)
                        addCost(t.acquisitionTime, amount * price + t.quoteFee + (feeValue ?: zero))
                        priced += received
                        if (feeValue == null) feeValueMissing = true
                    } else {
                        // Ohne Kaufpreis ist der Einstand unbekannt — eine USDT-Gebühr gehört dazu und
                        // zählt deshalb nirgends als bekannter Betrag (Teil «ohne Preis»)
                        unpriced += received
                    }
                }
                PortfolioTxType.SELL, PortfolioTxType.PAYMENT, PortfolioTxType.SWAP -> {
                    // Damit bezahlt: wie ein Verkauf zum Marktwert (Preis = Marktwert je Coin)
                    val swap = t.type == PortfolioTxType.SWAP
                    val holdings = priced + unpriced
                    // Abgegeben: Menge + Gebühr im Coin, gekappt auf den Bestand (mehr als vorhanden: markiert)
                    val requested = amount + t.coinFee
                    if (requested > holdings) oversold = true
                    // Erlös des ganzen Abgangs (Tausch: sein Wert); der gedeckte Teil bekommt seinen Anteil nach Menge
                    // Tausch ohne gültigen Zugang (erhaltener Coin fehlt): Erlös unbekannt → «realisiert unvollständig»
                    val gross = if (swap) t.swapValue?.takeIf { t.swapToCoin != null } else price?.let { amount * it }
                    val proceeds = gross?.let { it - t.quoteFee - (feeValue ?: zero) }
                    val feeMissing = gross != null && feeValue == null
                    if (feeMissing) feeValueMissing = true
                    if (holdings.signum() <= 0) {
                        record(t, null, false, proceeds, null, requested, feeLeg = false, feeMissing = feeMissing, swap = swap)
                        continue
                    }
                    val hadPriced = priced.signum() > 0
                    val taken = take(requested)
                    val sold = if (taken.removed.compareTo(requested) == 0) amount else taken.removed.multiply(amount).divide(requested, mc)
                    val covered = coveredShare(proceeds, taken.removed, requested)
                    // Verkauf anteilig auf beide Teile; nur der Teil mit Kaufpreis ist bekannt
                    if (covered != null && hadPriced) {
                        realized += (if (taken.allPriced) covered else covered.multiply(taken.share, mc)) - taken.costRemoved
                        if (!taken.allPriced) unknownSold += sold.multiply(BigDecimal.ONE - taken.share, mc)
                        if (feeValue == null) realizedFeeMissing = true
                    } else {
                        unknownSold += sold
                    }
                    record(t, taken, hadPriced, proceeds, covered, requested - taken.removed, feeLeg = false, feeMissing = feeMissing, swap = swap)
                    soldTotal += sold
                }
                // Verschenkt: wie eine Auszahlung (beim Schenkenden keine Veräusserung)
                PortfolioTxType.WITHDRAWAL, PortfolioTxType.GIFT -> {
                    // Aus dem Portfolio genommen: zum Ø-Einstand, kein realisierter Gewinn
                    val holdings = priced + unpriced
                    val requested = amount + t.coinFee
                    if (requested > holdings) oversold = true
                    if (holdings.signum() <= 0) continue
                    // Gebühr USDT + Wert der Gebühr im dritten Coin: Aufwand (realisiert), wie im Zeitraum
                    expense(e, PortfolioFeeExpense.WITHDRAWAL, PortfolioFeeExpenses.withdrawal(t, holdings))
                    val taken = take(requested)
                    val sold = if (taken.removed.compareTo(requested) == 0) amount else taken.removed.multiply(amount).divide(requested, mc)
                    withdrawn += sold
                    withdrawals += PortfolioWithdrawalCost(t, sold, taken.removed, taken.removedPriced, taken.costRemoved)
                }
                PortfolioTxType.TRANSFER -> {
                    val holdings = priced + unpriced
                    if (holdings.signum() <= 0) continue
                    val allPriced = unpriced.signum() == 0
                    // Gebühr USDT + Wert der Gebühr im dritten Coin: zum Einstand des Teils mit Kaufpreis; der Anteil
                    // des Teils ohne Kaufpreis (ohne Teil mit Kaufpreis: die ganze Gebühr) wird vermerkt
                    val extra = t.quoteFee + (feeValue ?: zero)
                    val assigned = if (priced.signum() > 0) {
                        if (feeValue == null) feeValueMissing = true
                        if (allPriced) extra else extra.multiply(priced).divide(holdings, mc)
                    } else zero
                    if (extra.signum() > 0) {
                        addCost(t.time, assigned)
                        if (extra > assigned) unassignedFees += PortfolioUnassignedFee(symbol, t.id, t.time, extra - assigned)
                    }
                    // Netzwerkgebühr im Coin: nur die Menge sinkt, der Einstand bleibt im Rest (Kosten des Haltens)
                    val coinFee = t.coinFee
                    if (coinFee > holdings) oversold = true
                    if (coinFee.signum() > 0) {
                        val removed = coinFee.min(holdings)
                        val removedPriced = if (allPriced) removed else PortfolioDecimal.amount(removed.multiply(priced).divide(holdings, mc)).min(priced)
                        priced -= removedPriced
                        if (PortfolioDecimal.isDust(priced) || priced.signum() < 0) {
                            // Kein Teil mit Kaufpreis mehr: Einstand nicht still verlieren, sondern vermerken
                            if (cost.signum() != 0) lostFeeCosts += PortfolioLostFeeCost(symbol, t.id, t.time, cost)
                            priced = zero; cost = zero; parts.clear()
                        }
                        unpriced = (holdings - removed - priced).max(zero)
                        if (PortfolioDecimal.isDust(unpriced)) unpriced = zero
                    }
                }
            }
        }
        return PortfolioBookResult(
            priced = priced, unpriced = unpriced, cost = cost, realized = realized,
            unknownSold = unknownSold, soldTotal = soldTotal, withdrawn = withdrawn, oversold = oversold,
            feeValueMissing = feeValueMissing, realizedFeeMissing = realizedFeeMissing,
            lots = emptyList(), disposals = disposals, withdrawals = withdrawals,
            lostFeeCosts = lostFeeCosts,
            unassignedFees = unassignedFees,
        )
    }

    /**
     * Eigener Bestand je Coin (Grossschreibung) aus den Transaktionen mit Zeit ≤ [time] nach [method] — dieselben
     * Mengen wie [CoinPosition.ledgerHoldingsExact] der Karte ([PortfolioHoldingsBook]: FIFO je Wallet kappt Abgänge
     * auf die Wallet, ungedeckter Rest nimmt nichts aus anderen Wallets). Adress-Einträge zählen nicht; Staub
     * ([PortfolioDecimal.isDust]) = 0. Nur Coins mit Bestand > 0, Reihenfolge wie [PortfolioLedger.coins].
     */
    fun holdingsAt(
        trades: List<PortfolioTrade>,
        time: Long = Long.MAX_VALUE,
        method: CostMethod = CostMethod.DEFAULT,
    ): Map<String, BigDecimal> {
        val real = trades.filter { it.time <= time && !AddressBalance.isAddressTrade(it.id) }
        val out = LinkedHashMap<String, BigDecimal>()
        for ((coin, events) in PortfolioLedger.eventsByCoin(real)) {
            val book = PortfolioHoldingsBook(method)
            events.forEach { book.apply(it) }
            if (book.total.signum() > 0 && !PortfolioDecimal.isDust(book.total)) out[coin] = book.total
        }
        return out
    }

    /**
     * Gesamtsicht nach [method]; [prices] = aktueller USDT-Kurs je Coin (Grossschreibung). Adress-Guthaben
     * zählen im Gesamtwert, aber nicht in Einstand und «Seit Kauf» — ein Coin nur aus Adressen fehlt dort
     * ohne «ohne Kaufpreis»-Hinweis ([missingCostCoins] nennt nur eigene Bestände ohne Kaufpreis).
     */
    fun summarize(
        trades: List<PortfolioTrade>,
        prices: Map<String, Double>,
        method: CostMethod = CostMethod.DEFAULT,
        /**
         * Kurs eines Coins zum Zeitpunkt einer Auszahlung ohne Preis ([withdrawnGain]) bzw. einer Gebühr-Zeile
         * ([PortfolioLedger.valueFeeDisposals]); null = nur erfasste Preise.
         */
        withdrawalPrice: ((coin: String, time: Long) -> Double?)? = null,
    ): PortfolioSummary {
        // Gebühr-Zeilen zum Tageskurs (gleicher Kurs wie der Abfluss im Verlauf); ohne Kurs Erlös unbekannt
        val trades = feeValued(trades, withdrawalPrice)
        // Auch Coins, die nur als Gebühr-Coin vorkommen (Bestand 0, ggf. «mehr als vorhanden»).
        // Schritte je Coin einmal gesammelt (statt je Coin über alle Transaktionen)
        val (real, address) = AddressBalance.split(trades)
        val byCoin = PortfolioLedger.eventsByCoin(real)
        val ledgers = PortfolioLedger.coins(trades)
            .map { coin -> ledgerOf(coin, byCoin[coin].orEmpty(), address[coin], prices[coin], method) }
        val positions = ledgers.map { it.position }
        val withdrawn = withdrawnGain(ledgers.flatMap { it.withdrawals }, withdrawalPrice)

        val open = positions.filter { it.isOpen }
            .sortedWith(
                compareByDescending<CoinPosition> { it.value ?: -1.0 }.thenBy { it.coin }
            )
        // Ganz verkaufte Positionen bleiben sichtbar, auch wenn der Gewinn/Verlust 0 oder unbekannt ist
        val closed = positions.filter {
            !it.isOpen && (it.realizedExact.signum() != 0 || it.soldAmount > 0.0 || it.withdrawnAmount > 0.0)
        }
            .sortedBy { it.coin }

        // Exakt summiert (Wert je Coin als BigDecimal), Double nur für die Anzeige
        val totalValue = open.fold(BigDecimal.ZERO) { acc, p -> acc + (p.valueExact ?: BigDecimal.ZERO) }.toDouble()
        val missingCurrent = open.filter { it.value == null }.map { it.coin }
        // Nur eigene Bestände ohne Kaufpreis; reine Adress-Guthaben haben keinen Einstand und fehlen nicht.
        // Ganz ohne Kaufpreis: fehlt in «Seit Kauf»; nur ein Teil ohne: zählt mit dem Teil mit Kaufpreis.
        val missingCost = open.filter { it.pricedCostExact == null && it.ledgerHoldingsExact.signum() > 0 }.map { it.coin }
        val partialCost = open.filter { it.priceMissing && it.pricedCostExact != null && it.unpricedHoldingsExact.signum() > 0 }
            .map { PortfolioUnpricedPart(it.coin, it.unpricedHoldingsExact) }
        val withCost = open.filter { it.costBasis != null }
        val investedExact = if (withCost.isEmpty()) null else withCost.fold(BigDecimal.ZERO) { acc, p -> acc + (p.costBasisExact ?: BigDecimal.ZERO) }
        val invested = investedExact?.toDouble()
        // Gewinn seit Kauf über die Teile mit Kaufpreis der Coins mit Kurs; die übrigen nennt der Hinweis
        // («ohne 1 Coin (kein Kaufpreis)», «ohne 0.5 BTC ohne Kaufpreis», «Kein aktueller Kurs für …»).
        // Prozent auf den Einstand dieser Teile.
        val included = open.filter { it.unrealizedExact != null && it.pricedCostExact != null }
        // Exakt summiert (Einstand und Gewinn je Coin als BigDecimal), Double nur für die Anzeige
        val includedCost = included.fold(BigDecimal.ZERO) { acc, p -> acc + (p.pricedCostExact ?: BigDecimal.ZERO) }
        val unrealizedExact = if (included.isEmpty()) null
        else included.fold(BigDecimal.ZERO) { acc, p -> acc + (p.unrealizedExact ?: BigDecimal.ZERO) }
        val unrealized = unrealizedExact?.toDouble()
        val unrealizedPercent = if (unrealizedExact != null && includedCost.signum() > 0) {
            unrealizedExact.divide(includedCost, PortfolioDecimal.MC).toDouble() * 100.0
        } else null

        val realizedExact = positions.fold(BigDecimal.ZERO) { acc, p -> acc + p.realizedExact }
        return PortfolioSummary(
            open = open,
            closed = closed,
            totalValue = totalValue,
            invested = invested,
            unrealized = unrealized,
            unrealizedPercent = unrealizedPercent,
            realized = realizedExact.toDouble(),
            costMissing = missingCost.isNotEmpty() || partialCost.isNotEmpty(),
            missingCurrentPrices = missingCurrent,
            missingCostCoins = missingCost,
            realizedIncomplete = positions.any { it.realizedIncomplete },
            investedExact = investedExact,
            realizedExact = realizedExact,
            feeValueMissingCoins = positions.filter { it.feeValueMissing }.map { it.coin }.sorted(),
            addressValue = open.fold(BigDecimal.ZERO) { acc, p -> acc + (p.addressValueExact ?: BigDecimal.ZERO) }.toDouble(),
            method = method,
            withdrawnGainExact = withdrawn.amount.takeIf { withdrawn.count > 0 && (it.signum() != 0 || !withdrawn.incomplete) },
            withdrawnGainIncomplete = withdrawn.incomplete,
            partialCostParts = partialCost,
            unassignedFeeUsdt = positions.fold(BigDecimal.ZERO) { acc, p -> acc + p.unassignedFeeUsdt },
        )
    }
}
