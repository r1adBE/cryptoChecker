// Kennzahlen je Coin und Gesamtsicht des Portfolios (Teil von `PortfolioModels.kt`, gespiegelt in `PortfolioPositions.swift`).
package com.cryptochecker.app.domain.portfolio

import java.math.BigDecimal

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
