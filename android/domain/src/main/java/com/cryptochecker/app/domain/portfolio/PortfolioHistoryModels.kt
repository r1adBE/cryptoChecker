package com.cryptochecker.app.domain.portfolio

// Typen des Wertverlaufs ([PortfolioHistory.build]): Zeitraum, Punkt, Tages-Spanne, Reihe, Zufluss — gespiegelt in
// `Shared/Portfolio/PortfolioHistoryModels.swift`.

/** Zeitraum des Wertverlaufs im Portfolio-Tab ([days] = so viele Tage vor heute beginnt er höchstens). */
enum class PortfolioHistoryRange(val days: Int) {
    WEEK(7),
    MONTH(30),
    YEAR(365),

    /**
     * «Seit 1. Kauf»: ab der ersten Transaktion, ohne feste Zahl Tage (daher 0) — frühestens ab
     * [PortfolioHistory.DATA_START_EPOCH_DAY], dem ersten Tag mit Kursdaten (siehe [PortfolioHistory.startDay]).
     */
    SINCE_FIRST(0),
    ;

    companion object {
        /** Gespeicherter Name; unbekannt oder leer = 30 Tage. */
        fun fromName(name: String?): PortfolioHistoryRange = entries.firstOrNull { it.name == name } ?: MONTH
    }
}

/**
 * Ein Tag des Verlaufs: Wert am Tagesende (heute: jetzt). [epochDay] = Tage seit 1970-01-01.
 * [open], [high] und [low] nur, wenn Tageskerzen mit Eröffnung/Hoch/Tief vorlagen (für die
 * Kerzen-Ansicht, siehe [PortfolioHistory.build] und [PortfolioCandles]); sonst null.
 */
data class PortfolioHistoryPoint(
    val epochDay: Long,
    val value: Double,
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
)

/** Eröffnung, Hoch und Tief einer UTC-Tageskerze in USDT (der Schluss steht bei den Schlusskursen). */
data class PortfolioDayRange(val open: Double, val high: Double, val low: Double) {
    /** Alle drei Werte endlich und grösser 0. */
    val valid: Boolean
        get() = open > 0.0 && high > 0.0 && low > 0.0 && open.isFinite() && high.isFinite() && low.isFinite()
}

/** Fertiger Verlauf eines Zeitraums. */
data class PortfolioHistorySeries(
    /** Aufsteigend nach Tag; leer, wenn kein Coin einen Verlauf hat. */
    val points: List<PortfolioHistoryPoint>,
    /** Coins mit Bestand im Zeitraum, aber ohne Tageskurse (fehlen im Chart, «ohne …»). */
    val skipped: List<String>,
    /** Letzter minus erster Wert; null mit weniger als zwei Punkten. */
    val change: Double?,
    /** Änderung in Prozent des ersten Werts; null ohne Ausgangswert. */
    val changePercent: Double?,
    /** Im Zeitraum (nach dem ersten Tag) wurde gekauft oder verkauft — die Änderung ist dann nicht nur Kursbewegung. */
    val tradesInRange: Boolean,
    /** «Seit 1. Kauf» reicht nicht bis zum ersten Kauf zurück (Kursdaten erst ab [PortfolioHistory.DATA_START_EPOCH_DAY]). */
    val capped: Boolean = false,
    /**
     * Zuflüsse im Zeitraum (nach dem ersten Tag) der gezeichneten Coins, in der Einheit der Punkte:
     * Kauf + (Menge × Preis + Gebühr USDT), Verkauf − (Erlös − Gebühr USDT), wie [PortfolioPeriodChange.gain].
     * Übrige Arten nach [PortfolioLedger.flow]. null = unbekannt (z. B. Kauf/Verkauf ohne Preis im Zeitraum).
     */
    val cashFlows: List<PortfolioCashFlow>? = emptyList(),
    /**
     * Gewinn/Verlust: Wert der Coins, die dafür zählen (je Punkt, gleiche Tage wie [points], mit
     * Eröffnung/Hoch/Tief wie dort) — ohne [pnlSkipped]. Daraus mit [pnlFlows] die Kurve [pnlPoints].
     */
    val pnlValues: List<PortfolioHistoryPoint> = emptyList(),
    /**
     * Zuflüsse dieser Coins ([PortfolioLedger.flow]) je UTC-Tag: «Seit 1. Kauf» alle (vor dem ersten
     * Punkt auf dessen Tag gelegt), sonst die nach dem Ende des ersten Tags.
     */
    val pnlFlows: List<PortfolioCashFlow> = emptyList(),
    /** Coins im Wertverlauf, die im Gewinn/Verlust fehlen (Zufluss unbekannt oder Bestand ohne Kurs). */
    val pnlSkipped: List<String> = emptyList(),
    /**
     * «Seit 1. Kauf» oder erster Kauf erst im Zeitraum (junges Portfolio): Gewinn/Verlust ab 0 vor dem ersten Kauf
     * (Wert − investiert), sonst ab dem ersten Tag.
     */
    val pnlSinceFirst: Boolean = false,
    /**
     * Im Zeitraum der Gewinn/Verlust-Kurve liegen eigene Auszahlungen ihrer Coins: Die Kurve enthält die
     * Wertänderung der ausgebuchten Coins, solange sie gehalten wurden (Abfluss zum Marktwert) — die Karte
     * («Seit Kauf», «realisiert») nicht. Kopf/Hinweis: «inkl. Wertänderung ausgebuchter Coins».
     */
    val withdrawalsInRange: Boolean = false,
) {
    /** Genug für eine Linie. */
    val hasChart: Boolean get() = points.size >= 2

    /**
     * Gewinn/Verlust je Tag in der Einheit der Punkte: Wert(d) − netto investiert(d). Netto investiert =
     * Σ [pnlFlows] bis Tag d, im Zeitraum zusätzlich der Wert am ersten Tag (die Kurve beginnt dort bei 0);
     * «Seit 1. Kauf» ab 0 vor dem ersten Kauf. Käufe/Verkäufe verschieben die Kurve also nicht, nur
     * Kursbewegungen der gehaltenen Coins. Eröffnung/Hoch/Tief um denselben Betrag verschoben.
     */
    val pnlPoints: List<PortfolioHistoryPoint>
        get() {
            if (pnlValues.isEmpty()) return emptyList()
            val anchor = if (pnlSinceFirst) 0.0 else pnlValues.first().value
            val flows = pnlFlows.sortedBy { it.epochDay }
            var next = 0
            var invested = anchor
            return pnlValues.map { p ->
                while (next < flows.size && flows[next].epochDay <= p.epochDay) invested += flows[next++].amount
                PortfolioHistoryPoint(p.epochDay, p.value - invested, p.open?.minus(invested), p.high?.minus(invested), p.low?.minus(invested))
            }
        }

    /** Genug Gewinn/Verlust-Punkte für eine Linie. */
    val hasPnlChart: Boolean get() = pnlValues.size >= 2

    /** Gewinn/Verlust über den Zeitraum (letzter Punkt von [pnlPoints]); null ohne Kurve. */
    val pnlChange: Double?
        get() = if (hasPnlChart) pnlPoints.last().value else null

    /**
     * Basis des Prozents, wie «Heute» und die Zeilen ([PortfolioPeriodChange]): im Zeitraum Wert am ersten Tag +
     * Zugänge danach (positive Zuflüsse: Käufe, Belohnungen, Einzahlungen samt Gebühren; Verkäufe NICHT
     * abgezogen), «Seit 1. Kauf» Σ aller Zugänge (alles eingesetzte Geld). null ohne Kurve.
     */
    val pnlCapital: Double?
        get() = if (hasPnlChart) {
            (if (pnlSinceFirst) 0.0 else pnlValues.first().value) + pnlFlows.filter { it.amount > 0.0 }.sumOf { it.amount }
        } else null

    /**
     * [pnlChange] in Prozent von [pnlCapital]; null, wenn diese Basis höchstens ~0 ist — dann nur der Betrag.
     */
    val pnlPercent: Double?
        get() {
            val change = pnlChange ?: return null
            val base = pnlCapital ?: return null
            return if (base > PortfolioCalculator.EPS) change / base * 100.0 else null
        }

    /**
     * Änderung ohne Käufe/Verkäufe (wie der Gesamtwert-Kopf): [change] − Σ [cashFlows]; null ohne
     * Verlauf oder wenn ein Zufluss unbekannt ist.
     */
    val netChange: Double?
        get() {
            val gross = change ?: return null
            val flows = cashFlows ?: return null
            return gross - flows.sumOf { it.amount }
        }

    /** [netChange] in Prozent von Wert am Anfang + Käufe im Zeitraum (wie «7 T»); null ohne Basis. */
    val netChangePercent: Double?
        get() {
            val net = netChange ?: return null
            val base = (points.firstOrNull()?.value ?: return null) +
                cashFlows.orEmpty().filter { it.amount > 0.0 }.sumOf { it.amount }
            return if (base > PortfolioCalculator.EPS) net / base * 100.0 else null
        }

    companion object {
        val EMPTY = PortfolioHistorySeries(emptyList(), emptyList(), null, null, false)
    }
}

/** Zufluss am (UTC-)Tag [epochDay] (Kauf positiv, Verkauf negativ), in der Einheit des Verlaufs. */
data class PortfolioCashFlow(val epochDay: Long, val amount: Double)
