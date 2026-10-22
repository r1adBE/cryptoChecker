package com.cryptochecker.app.domain.portfolio.csvimport

/**
 * Seit Build 103: Lesart der CSV-Datei, die in der Vorschau gewählt wird — Zeitzone der Zeiten ohne Versatz,
 * Tag/Monat bei mehrdeutigen Daten, Dezimalzeichen bei mehrdeutigen Zahlen (null = Regel). Die Vorschau merkt sich, mit
 * welcher Lesart sie gebaut wurde; «Importieren» nur, wenn sie zur aktuellen Wahl gehört ([canConfirm]). Wie iOS
 * `CsvReadChoice`.
 */
data class CsvReadChoice(
    val zone: String? = null,
    val dateOrder: CsvDateOrder? = null,
    val decimalMark: Char? = null,
) {
    companion object {
        /**
         * Darf importiert werden? Nicht, solange die Datei neu gelesen wird ([rereading]) oder die gezeigte Vorschau mit
         * einer anderen Lesart gebaut wurde ([builtWith] ≠ [chosen]) — sonst käme die alte Lesart in die Daten.
         */
        fun canConfirm(rereading: Boolean, builtWith: CsvReadChoice?, chosen: CsvReadChoice): Boolean =
            !rereading && builtWith == chosen
    }
}
