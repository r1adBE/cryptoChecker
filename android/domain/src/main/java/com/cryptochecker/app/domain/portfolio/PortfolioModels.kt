package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

// Datenmodell des Portfolios: Art, Gebühr-Einheit, Transaktion, Position je Coin, Gesamtsicht (Rechnung in [PortfolioCalculator]);
// Position und Gesamtsicht stehen in `PortfolioPositions.kt`.

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
 *  - [GIFT]: «Verschenkt» — beim Schenkenden in den meisten Ländern KEINE Veräusserung: Karte, Verlauf und die
 *    Einstandsmethoden rechnen sie wie eine Auszahlung (Bestand und Einstand gehen ab, Wert verlässt das Portfolio zum
 *    Marktwert, kein realisierter Gewinn). Nur wo die Schenkung beim Schenkenden eine Veräusserung zum Marktwert ist (UK,
 *    CA, AU, [TaxTemplate.giftIsDisposal]) wird sie im Steuer-Export wie [PAYMENT] gerechnet ([TaxEngines.applyRules],
 *    Vermerk «Schenkung»); an Ehe-/Lebenspartner ([PortfolioTrade.isGiftToSpouse]) in UK/CA ohne Gewinn und Verlust.
 *  - [PAYMENT]: «Damit bezahlt» — Tausch gegen Waren/Dienstleistungen: überall eine Veräusserung zum MARKTWERT
 *    wie ein Verkauf ([isSaleLike]; Preis = Marktwert je Coin, Erlös = Menge × Preis, kein Geld erhalten), Steuer-Export
 *    mit Vermerk «Zahlung mit Krypto». Ohne Preis zum Tagesschluss geschätzt ([PortfolioLedger.valueAtDayClose]).
 * Unbekannte Arten zählen beim Wiederherstellen als ungültig (Rückfrage, nichts stürzt ab). Der Name wird als Text
 * gespeichert (Room-Enum-Konverter).
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
    /**
     * Gebühr-Preis im dritten Coin nur geschätzt (Tagesschluss, [PortfolioLedger.valueThirdFeesAtDayClose]; nur
     * Steuer-Export, jede Art): die Zeile der Gebühr-Coins und jede Zeile mit dem Wert der Gebühr (Erlös eines
     * Verkaufs/Tauschs, Aufwand einer Auszahlung) tragen den Vermerk «Wert geschätzt (Tagesschluss)». Nie gespeichert.
     */
    val feePriceEstimated: Boolean = false,
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
            validPrice?.let { return amount.multiply(it, PortfolioDecimal.MC) }
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
            return thirdFeePrice?.let { thirdFeeAmount.multiply(it, PortfolioDecimal.MC) }
        }
}
