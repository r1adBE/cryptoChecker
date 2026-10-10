package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.util.DecimalText
import java.time.LocalDate
import java.time.ZoneId

/** Bestand eines Coins am Stichtag. */
data class CutoffHolding(val coin: String, val amount: Double)

/** Eine Zeile des Stichtag-Exports. Leere Werte = nicht bekannt. */
data class CutoffRow(
    val coin: String,
    val amount: Double,
    /** Tagesschlusskurs in USDT; null = kein Kurs. */
    val priceUsdt: Double?,
    val valueUsdt: Double?,
    /** USD → Zielwährung; null = kein Devisenkurs. */
    val rate: Double?,
    val valueTarget: Double?,
    val noPrice: Boolean,
    val noFx: Boolean,
)

/** Übersetzte Texte für die CSV-Datei (Kopfzeile, Hinweise); kommen aus den Ressourcen. */
data class CutoffCsvTexts(
    val coin: String,
    val amount: String,
    val priceUsdt: String,
    val valueUsdt: String,
    /** Bereits mit der Zielwährung, z. B. «Kurs USD→CHF». */
    val rate: String,
    /** Bereits mit der Zielwährung, z. B. «Wert CHF». */
    val valueTarget: String,
    val note: String,
    val total: String,
    val noPrice: String,
    val noFx: String,
    val incomplete: String,
    /** Kommentarzeile mit %1$s = Stichtag, %2$s = Währung, %3$s = Datum des Devisenkurses. */
    val commentFormat: String,
)

/**
 * Stichtag-Export (z. B. Wertschriftenverzeichnis per 31.12.): Bestand je Coin am
 * Ende eines Tags, bewertet zum Tagesschlusskurs in USDT und umgerechnet in die
 * Zielwährung. Reines Kotlin ohne Android und ohne org.json — testbar.
 *
 * CSV: UTF-8 mit BOM (für Excel), Trennzeichen «;», Dezimalpunkt «.», Zeilenende CRLF.
 */
object CutoffExport {

    /** Stablecoins: die eine Liste aus [PortfolioStables] (Tagesschluss, sonst 1; USDT immer 1). */
    val STABLES: Set<String> get() = PortfolioStables.COINS

    const val SEPARATOR = ";"
    private const val BOM = "\uFEFF"
    private const val EOL = "\r\n"

    fun isStable(coin: String): Boolean = PortfolioStables.isStable(coin)

    /** Letzte Millisekunde des Tags [date] in der Zeitzone [zone]. */
    fun endOfDayMillis(date: LocalDate, zone: ZoneId): Long =
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

    /** Standardstichtag: 31. Dezember des Vorjahrs. */
    fun defaultDate(today: LocalDate): LocalDate = LocalDate.of(today.year - 1, 12, 31)

    fun fileName(date: LocalDate): String = "cryptochecker-stichtag-$date.csv"

    /**
     * Bestand je Coin aus allen Transaktionen mit Zeit ≤ [cutoffMillis]
     * (gleiche Rechnung wie [PortfolioCalculator]). Coins ohne Bestand fallen weg.
     * Alphabetisch sortiert.
     */
    fun holdingsAt(trades: List<PortfolioTrade>, cutoffMillis: Long): List<CutoffHolding> {
        val until = trades.filter { it.time <= cutoffMillis }
        return until.map { PortfolioCalculator.normalizeCoin(it.coin) }
            .distinct()
            .mapNotNull { coin ->
                val holdings = PortfolioCalculator.position(coin, until, null).holdings
                if (holdings > PortfolioCalculator.EPS) CutoffHolding(coin, holdings) else null
            }
            .sortedBy { it.coin }
    }

    /**
     * Zeilen aus Bestand, Kursen ([prices]: Coin → Tagesschluss in USDT) und dem Devisenkurs
     * [fxRate] (null = unbekannt). Stablecoins nach [PortfolioStables.price]: USDT = 1, die
     * übrigen ihr Tagesschluss, fehlt er, 1.
     */
    fun rows(holdings: List<CutoffHolding>, prices: Map<String, Double>, fxRate: Double?): List<CutoffRow> {
        val rate = fxRate?.takeIf { it > 0.0 && it.isFinite() }
        return holdings.map { h ->
            val price = PortfolioStables.price(h.coin, prices[h.coin])
            val value = price?.let { h.amount * it }
            CutoffRow(
                coin = h.coin,
                amount = h.amount,
                priceUsdt = price,
                valueUsdt = value,
                rate = rate,
                valueTarget = if (value != null && rate != null) value * rate else null,
                noPrice = price == null,
                noFx = rate == null,
            )
        }
    }

    /**
     * Ganze CSV-Datei (mit BOM). [fxDate] = tatsächliches Datum des Devisenkurses
     * (null, z. B. bei USD oder ohne Kurs → «—»).
     */
    fun csv(
        date: LocalDate,
        currency: String,
        fxDate: String?,
        rows: List<CutoffRow>,
        texts: CutoffCsvTexts,
    ): String {
        val sb = StringBuilder(BOM)
        sb.append(line(listOf(texts.commentFormat.format(date.toString(), currency, fxDate ?: "—"))))
        sb.append(
            line(
                listOf(
                    texts.coin, texts.amount, texts.priceUsdt, texts.valueUsdt,
                    texts.rate, texts.valueTarget, texts.note,
                )
            )
        )
        for (r in rows) {
            val notes = listOfNotNull(
                texts.noPrice.takeIf { r.noPrice },
                texts.noFx.takeIf { r.noFx },
            ).joinToString(", ")
            sb.append(
                line(
                    listOf(
                        r.coin,
                        amount(r.amount),
                        r.priceUsdt?.let { price(it) }.orEmpty(),
                        r.valueUsdt?.let { money(it) }.orEmpty(),
                        r.rate?.let { rate(it) }.orEmpty(),
                        r.valueTarget?.let { money(it) }.orEmpty(),
                        notes,
                    )
                )
            )
        }

        // Total: Summe der bekannten Werte; fehlt irgendwo ein Kurs, steht «unvollständig» dabei
        val totalUsdt = rows.sumOf { it.valueUsdt ?: 0.0 }
        val rate = rows.firstOrNull()?.rate
        val anyValue = rows.any { it.valueUsdt != null }
        val totalTarget = if (rows.any { it.valueTarget != null }) rows.sumOf { it.valueTarget ?: 0.0 } else null
        val totalNotes = listOfNotNull(
            texts.incomplete.takeIf { rows.any { it.noPrice } },
            texts.noFx.takeIf { rows.isNotEmpty() && rows.all { it.noFx } },
        ).joinToString(", ")
        sb.append(
            line(
                listOf(
                    texts.total,
                    "",
                    "",
                    if (anyValue || rows.isEmpty()) money(totalUsdt) else "",
                    rate?.let { rate(it) }.orEmpty(),
                    totalTarget?.let { money(it) }.orEmpty(),
                    totalNotes,
                )
            )
        )
        return sb.toString()
    }

    private fun line(fields: List<String>): String = fields.joinToString(SEPARATOR) { escape(it) } + EOL

    /** Feld in Anführungszeichen, wenn es «;», «"» oder einen Zeilenumbruch enthält. */
    internal fun escape(field: String): String =
        if (field.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else field

    // ---------------- Zahlen (Dezimalpunkt, ohne Tausendertrennung) ----------------
    // Gerundet wird kaufmännisch (HALF_UP) ab der kürzesten Dezimaldarstellung ([DecimalText]),
    // gleich wie iOS (`PortfolioCutoffExport`): 1.005 → «1.01». Nicht endlich → leeres Feld.

    /** Menge: bis 10 Nachkommastellen, unter 1 bis 10 gültige Stellen (3e-11 → «0.00000000003»), ohne Nullen am Ende. */
    internal fun amount(v: Double): String = significant(v)

    /** Kurs: wie [amount] — auch Kleinstkurse (3e-11) bleiben lesbar statt «0». */
    internal fun price(v: Double): String = significant(v)

    /** Devisenkurs: bis 6 Nachkommastellen. */
    internal fun rate(v: Double): String = DecimalText.plain(v, 6)

    /** Geldbetrag: genau 2 Nachkommastellen; «-0.00» → «0.00». */
    internal fun money(v: Double): String = DecimalText.fixed(v, 2)

    private fun significant(v: Double): String =
        DecimalText.plain(v, DecimalText.significantScale(v, minDecimals = 10, significant = 10))
}
