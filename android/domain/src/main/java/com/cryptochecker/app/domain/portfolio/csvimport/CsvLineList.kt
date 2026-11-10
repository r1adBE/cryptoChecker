package com.cryptochecker.app.domain.portfolio.csvimport

/**
 * Zeilennummern eines Vorschau-Hinweises (CSV-Import: ohne Tageskurs, ohne Preis, USDT = 1, Gebühr im Preis): Anzahl,
 * die ersten [SHOWN] Nummern und der Rest als «+ N weitere» — statt einer endlosen Kette bei Tausenden Zeilen.
 * Gemeinsam mit iOS (`CsvLineList.swift`, `testdata/parity/csv_line_list.json`).
 */
data class CsvLineList(val count: Int, val shown: List<Int>, val more: Int) {
    companion object {
        /** So viele Zeilennummern je Hinweis. */
        const val SHOWN = 5

        fun of(lines: List<Int>, shown: Int = SHOWN): CsvLineList {
            val first = lines.take(shown.coerceAtLeast(0))
            return CsvLineList(count = lines.size, shown = first, more = lines.size - first.size)
        }

        /** Übersprungene Zeilen: ab so vielen steht «+ N weitere» darunter. */
        fun moreAfter(total: Int, shown: Int): Int = (total - shown.coerceAtLeast(0)).coerceAtLeast(0)
    }
}
