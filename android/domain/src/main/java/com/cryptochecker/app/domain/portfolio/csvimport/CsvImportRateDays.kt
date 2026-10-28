package com.cryptochecker.app.domain.portfolio.csvimport

/**
 * Tage, für die der CSV-Import Kurse geladen hat, und ob sie nach einem Neu-Lesen noch reichen (reines Kotlin; iOS
 * `CsvImportRateDays.swift`). Eine andere Zeitzone schiebt Zeilen ohne Versatz um einen Tag: dann liegt ein Tag ausserhalb
 * der geladenen Kurse, und der Kurs des Vortags gälte still für ihn — darum neu laden ([cover]).
 */
object CsvImportRateDays {

    /** Schlüssel der USDT/USD-Tagesschlüsse in [of] (nie eine Währung von [CsvImportMapping.fxNeeds]). */
    const val USDT_USD = "USDT"

    /**
     * Gebrauchte Tage: Tageskurse je Währung ([CsvImportMapping.fxNeeds]) und unter [USDT_USD] die USDT/USD-Schlüsse vom
     * frühesten bis zum spätesten Tag aller Zeilen — alles in [zone] (Zeitzone der Tageskurse, `CsvImportResult.fxZone`).
     */
    fun of(rows: List<CsvImportRow>, zone: String = CsvImportParser.DEFAULT_ZONE): Map<String, LongRange> {
        val out = LinkedHashMap(CsvImportMapping.fxNeeds(rows, zone))
        val days = rows.map { CsvImport.epochDay(it.timeMillis, zone) }
        val first = days.minOrNull() ?: return out
        out[USDT_USD] = first..days.max()
        return out
    }

    /**
     * Decken die geladenen Kurse ([loaded] = [of] beim Laden) die nun gebrauchten Tage ([needed]) ab? Nein, sobald ein Tag
     * einer Währung ausserhalb des geladenen Bereichs liegt (oder die Währung neu ist).
     */
    fun cover(loaded: Map<String, LongRange>, needed: Map<String, LongRange>): Boolean =
        needed.all { (currency, days) -> loaded[currency]?.let { days.first >= it.first && days.last <= it.last } == true }
}
