package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.util.TextLimit
import java.math.BigDecimal

/**
 * Eine Buchung unter einem Coin im Portfolio-Widget (Ansicht «Liste», Wunsch 27.10.2026): Änderung des Bestands durch
 * diese Buchung ([quantity], Zugang +, Abgang −, Gebühren im Coin eingerechnet), die gekürzte Notiz ([note]) und der
 * heutige Wert ([value] = Menge × aktueller Kurs in der Anzeigewährung; null = ohne Kurs). Wie iOS
 * `PortfolioWidgetEntry` (Paritäts-Fixture `widget_portfolio_entries.json`).
 */
data class PortfolioWidgetEntry(
    val time: Long,
    val quantity: Double,
    val note: String?,
    val value: Double? = null,
)

/** Welche Buchungen das Listen-Widget unter den Coins zeigt (Wahl beim Einrichten; voreingestellt [OFF]). */
enum class PortfolioWidgetEntryFilter(val key: String) {
    OFF("off"), BUYS("buys"), SELLS("sells"), BOTH("both");

    /** Gehört [entry] zu dieser Wahl? Zugänge (Kauf, Einzahlung, Belohnung, Tausch hinein) = Käufe, Abgänge = Verkäufe. */
    fun accepts(entry: PortfolioWidgetEntry): Boolean = when (this) {
        OFF -> false
        BUYS -> entry.quantity > 0.0
        SELLS -> entry.quantity < 0.0
        BOTH -> entry.quantity != 0.0
    }

    companion object {
        val DEFAULT = OFF
        fun fromKey(key: String?): PortfolioWidgetEntryFilter = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * Buchungen je Coin für das Listen-Widget: dieselben Schritte und dieselbe Bestandsregel wie der Rechner
 * ([PortfolioLedger.events], [PortfolioHoldingsBook]) — so ergeben alle Zeilen zusammen genau den Bestand der Kopfzeile
 * (Abgänge auf den Bestand gekappt, Umbuchung nur mit ihrer Gebühr im Coin). Schritte ohne Änderung entfallen.
 */
object PortfolioWidgetEntries {

    /** Sichtbare Zeichen der Notiz; länger → gekürzt und «…» angehängt. */
    const val NOTE_CHARS = 6

    /**
     * Höchstens so viele Buchungen je Coin in der Aufnahme. Mehr passen nie ins Widget; ein Coin mit mehr Buchungen zeigt
     * keine (je Coin alle oder keine, damit die Zeilen den Bestand ergeben).
     */
    const val MAX_PER_COIN = 40

    /** Notiz fürs Widget: ohne Leerraum am Rand, leer → null, länger als [NOTE_CHARS] Zeichen → gekürzt mit «…». */
    fun shortNote(note: String?): String? {
        val text = note?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() } ?: return null
        return if (TextLimit.graphemeCount(text) > NOTE_CHARS) TextLimit.takeGraphemes(text, NOTE_CHARS).trimEnd() + "…" else text
    }

    /**
     * Buchungen von [coin] aus [trades], zeitlich aufsteigend; [notes] = Notiz je Transaktions-Id. Leer, wenn es mehr
     * als [MAX_PER_COIN] sind. Ohne Wert ([PortfolioWidgetEntry.value] setzt [withValues]).
     */
    fun of(
        coin: String,
        trades: List<PortfolioTrade>,
        notes: Map<Long, String?>,
        method: CostMethod = CostMethod.DEFAULT,
    ): List<PortfolioWidgetEntry> {
        val book = PortfolioHoldingsBook(method)
        val out = ArrayList<PortfolioWidgetEntry>()
        for (e in PortfolioLedger.events(coin, trades)) {
            if (e.trade.amount.signum() <= 0) continue
            val before = book.total
            book.apply(e)
            val delta = book.total - before
            if (delta.signum() == 0) continue
            out += PortfolioWidgetEntry(e.trade.time, delta.toDouble(), shortNote(notes[e.trade.id]))
            if (out.size > MAX_PER_COIN) return emptyList()
        }
        return out
    }

    /** [entries] mit heutigem Wert: Menge × [price] (USDT) × [fxRate]; ohne Kurs bleibt der Wert leer. */
    fun withValues(entries: List<PortfolioWidgetEntry>, price: Double?, fxRate: Double): List<PortfolioWidgetEntry> {
        val p = price?.takeIf { it.isFinite() && it > 0.0 }
        return entries.map { it.copy(value = p?.let { _ -> it.quantity * p * fxRate }) }
    }

    /**
     * [snapshot] mit den Buchungen je gezeigter Position ([of], heutiger Wert mit dem Kurs aus [current] und [fxRate]).
     * [trades] und [notes] wie im Rechner (eigene Transaktionen; Adress-Guthaben zählen nicht zum Bestand der Liste).
     */
    fun withEntries(
        snapshot: PortfolioSnapshot,
        trades: List<PortfolioTrade>,
        notes: Map<Long, String?>,
        current: Map<String, Double>,
        fxRate: Double,
        method: CostMethod = CostMethod.DEFAULT,
    ): PortfolioSnapshot {
        if (snapshot.positions.isEmpty()) return snapshot
        val prices = current.mapKeys { (coin, _) -> PortfolioCalculator.normalizeCoin(coin) }
        return snapshot.copy(
            positions = snapshot.positions.map { p ->
                val coin = PortfolioCalculator.normalizeCoin(p.symbol)
                p.copy(entries = withValues(of(coin, trades, notes, method), prices[coin], fxRate))
            },
        )
    }

    /**
     * Welche Buchungen unter den gezeigten Coins Platz haben: je Coin (in der Reihenfolge von [perCoin]) die zur Wahl
     * [filter] passenden Buchungen — alle oder keine —, solange sie zusammen in [available] passen ([rowHeight] je Zeile).
     * Die Coins selbst haben Vorrang: [available] ist der Platz, der unter ihnen frei bleibt. Ein Coin, dessen Buchungen
     * nicht mehr passen, bekommt keine; die folgenden dürfen den Rest noch nutzen.
     * @return je Coin die gezeigten Buchungen (leer = keine)
     */
    fun assign(
        perCoin: List<List<PortfolioWidgetEntry>?>,
        filter: PortfolioWidgetEntryFilter,
        available: Double,
        rowHeight: Double,
    ): List<List<PortfolioWidgetEntry>> {
        if (filter == PortfolioWidgetEntryFilter.OFF || rowHeight <= 0.0) return perCoin.map { emptyList() }
        var left = available
        return perCoin.map { entries ->
            val shown = entries.orEmpty().filter(filter::accepts)
            val need = shown.size * rowHeight
            if (shown.isNotEmpty() && need <= left + FIT_TOLERANCE) {
                left -= need
                shown
            } else {
                emptyList()
            }
        }
    }

    /** Rundungsspielraum beim Vergleich der Höhen (dp/pt). */
    private const val FIT_TOLERANCE = 1e-6

    /** Summe der Mengen (Prüfung: bei «beides» = Bestand). */
    fun total(entries: List<PortfolioWidgetEntry>): BigDecimal =
        entries.fold(BigDecimal.ZERO) { acc, e -> acc + (PortfolioDecimal.of(e.quantity) ?: BigDecimal.ZERO) }
}
