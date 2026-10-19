// Speicherformat der Tageskerzen des Wertverlaufs (Teil von `PortfolioHistorySource`): eigene Datei, damit die Quelle
// unter der Längengrenze bleibt; Format unverändert.
package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.CacheCodec
import com.cryptochecker.app.domain.portfolio.PortfolioDayRange
import com.cryptochecker.app.domain.portfolio.PortfolioHistory
import org.json.JSONArray
import org.json.JSONObject

internal object PortfolioHistoryCodecs {
    /**
     * {"span": 366, "days":[[epochDay, close, open, high, low], …]} — leere Liste = kein Paar.
     * Ältere Einträge ohne «span» stammen aus der festen Jahres-Abfrage ([PortfolioHistory.MAX_DAYS]);
     * ältere Tage nur mit [epochDay, close] haben keine Eröffnung/Hoch/Tief (Kerzen ohne Docht,
     * bis der Eintrag nach 12 h neu geholt wird).
     */
    val closes = CacheCodec<CoinCloses>(
        encode = { entry ->
            val days = JSONArray()
            entry.closes.entries.sortedBy { it.key }.forEach { (day, close) ->
                if (close.isFinite()) {
                    val row = JSONArray().put(day).put(close)
                    entry.ranges[day]?.let { row.put(it.open).put(it.high).put(it.low) }
                    days.put(row)
                }
            }
            JSONObject().put("span", entry.span).put("days", days)
        },
        decode = { o ->
            val days = o.getJSONArray("days")
            val byDay = HashMap<Long, Double>()
            val ranges = HashMap<Long, PortfolioDayRange>()
            for (i in 0 until days.length()) {
                val row = days.getJSONArray(i)
                val day = row.getLong(0)
                byDay[day] = row.getDouble(1)
                if (row.length() >= 5) {
                    PortfolioDayRange(row.getDouble(2), row.getDouble(3), row.getDouble(4))
                        .takeIf { it.valid }?.let { ranges[day] = it }
                }
            }
            CoinCloses(byDay, o.optInt("span", PortfolioHistory.MAX_DAYS), ranges)
        },
    )

    /** Wie [closes], dazu der Weg («route»): ein anderer Weg (Börse/Paar geändert) gilt als nicht gemerkt. */
    val own = CacheCodec<OwnCloses>(
        encode = { value -> closes.encode(value.entry).put("route", value.route) },
        decode = { o -> OwnCloses(o.getString("route"), closes.decode(o)) },
    )
}
