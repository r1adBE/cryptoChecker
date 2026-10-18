// Zeit- und Kennungs-Helfer der eigenen Kerzen (Teil von `ExchangeCandleSourcesMore.kt`, gespiegelt in `CandleTime.swift`).
package com.cryptochecker.app.data.remote.candles

import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.domain.activity.HourCandle

/** Zeit- und Kennungs-Helfer der zweiten Runde. */
internal object CandleTime {
    const val HOUR = 3_600_000L

    /** Beginn des laufenden Intervalls (UTC-Raster). */
    fun aligned(now: Long, step: Long): Long = Math.floorDiv(now, step) * step

    /** Beginn eines Fensters, das [count] Kerzen bis zur laufenden umfasst. */
    fun windowStart(now: Long, step: Long, count: Int): Long = aligned(now, step) - (count - 1).coerceAtLeast(0) * step

    /** Kennung des Paars mit zusätzlich erlaubten Zeichen [extra] (z. B. «:» bei Bitfinex); sonst null. */
    fun pairId(pair: CandlePair, extra: String = ""): String? {
        val t = pair.pairId?.trim().orEmpty()
        return t.takeIf { it.isNotEmpty() && it.all { c -> (c.isLetterOrDigit() && c.code < 128) || c == '-' || c == '_' || c in extra } }
    }

    /** Tage seit 1970 für ein Datum (proleptisch gregorianisch, ohne Zeitzone). */
    private fun daysFromCivil(y0: Long, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** «2025-10-14T00:00:00», «…T13:30:00.000Z» (UTC) → Epoch-ms; null, wenn nicht lesbar. */
    fun parseIso(s: String?): Long? {
        val t = s?.trim()?.removeSuffix("Z") ?: return null
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3})\d*)?$""").matchEntire(t) ?: return null
        val g = m.groupValues
        val days = daysFromCivil(g[1].toLong(), g[2].toInt(), g[3].toInt())
        val ms = g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.toLong() ?: 0L
        return ((days * 24 + g[4].toLong()) * 60 + g[5].toLong()) * 60_000L + g[6].toLong() * 1000L + ms
    }

    /** Epoch-ms → «2025-10-14T00:00:00.000Z» (UTC). */
    fun formatIso(millis: Long): String {
        val days = Math.floorDiv(millis, 86_400_000L)
        val rest = millis - days * 86_400_000L
        // Datum aus Tagen (Umkehrung von daysFromCivil)
        val z = days + 719468
        val era = Math.floorDiv(z, 146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        val h = rest / 3_600_000L
        val min = rest / 60_000L % 60
        val sec = rest / 1000L % 60
        val ms = rest % 1000L
        return "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ".format(java.util.Locale.ROOT, y, m, d, h, min, sec, ms)
    }

    /** 4 h aus Stundenkerzen (UTC-Raster); null bleibt null. */
    fun fourHours(hourly: List<HourCandle>?): List<HourCandle>? =
        hourly?.let { CandleDataSource.aggregate(it.sortedBy { c -> c.openTime }, 4 * HOUR, 0L, 4) }

    /** Rohkerzen für [limit] Kerzen (4 h aus Stundenkerzen: viermal so viele und ein Block Reserve). */
    fun rawCount(interval: CandleInterval, limit: Int): Int = if (interval == CandleInterval.H4) limit * 4 + 4 else limit

    /** Menge in der Basis aus dem Quote-Umsatz (nur genähert) — wenn die Quelle nur Kontrakte zählt. */
    fun baseFromQuote(quote: Double, close: Double): Double =
        if (quote.isFinite() && quote >= 0.0 && close.isFinite() && close > 0.0) quote / close else Double.NaN
}
