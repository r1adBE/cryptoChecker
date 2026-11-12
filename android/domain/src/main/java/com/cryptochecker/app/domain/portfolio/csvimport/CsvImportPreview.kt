package com.cryptochecker.app.domain.portfolio.csvimport

/**
 * Vorschau eines Imports: neue Transaktionen, Doppelte (schon importiert), übersprungene Zeilen mit Grund,
 * Zeilen ohne Tageskurs ([missingFx]) bzw. ohne Preis in der Datei ([missingPrice]) — beide werden genannt. Ohne Preis
 * in der Datei: übernommen ohne Preis. Ohne Tageskurs: «Importieren» ist gesperrt, bis die Kurse da sind («Erneut laden»)
 * oder der Import ausdrücklich trotzdem verlangt wird — dann mit Vermerk an jeder betroffenen Transaktion
 * ([CsvImportRateGate]).
 */
data class CsvImportPreview(
    val format: CsvImportFormat,
    val transactions: List<CsvImportTx>,
    val duplicates: Int,
    val skipped: List<CsvImportSkip>,
    val missingFx: List<Int>,
    val missingPrice: List<Int>,
    /** Zeitzone der Zeiten ohne Versatz ([CsvImportResult.zone]) und ob es solche gibt (Auswahl in der Vorschau). */
    val zone: String = CsvImportParser.DEFAULT_ZONE,
    val localTimes: Boolean = false,
    /** Reihenfolge Tag/Monat ([CsvImportResult.dateOrder]) und ob die Datei sie offen lässt (Auswahl in der Vorschau). */
    val dateOrder: CsvDateOrder = CsvDateOrder.DAY_FIRST,
    val dateOrderAmbiguous: Boolean = false,
    /** Mindestens eine neue Zeile ist ein Kauf/Verkauf gegen Stablecoin, gebucht als Tausch ([CsvImportRow.stableSwap]). */
    val stableSwaps: Boolean = false,
    /** Mehrdeutige Zahl und gelesenes Dezimalzeichen ([CsvImportResult.decimalSample]); Auswahl in der Vorschau. */
    val decimalSample: String? = null,
    val decimalMark: Char? = null,
    /** Erkannte Spalten ([CsvImportResult.columns]). */
    val columns: List<String> = emptyList(),
    /**
     * Zeilen, deren USD-/Fiat-Betrag mit USDT = 1 USD umgerechnet ist (kein USDT/USD-Tagesschluss DES Tags,
     * [CsvImportPriced.usdtApproximate]) — die Vorschau nennt sie wie [missingFx].
     */
    val usdtApproximate: List<Int> = emptyList(),
    /** Zeilen mit Fiat-Gebühr im Preis und Gebühr im Coin am Geschäft ([CsvImportRow.feeInPrice]) — Hinweis. */
    val feeInPrice: List<Int> = emptyList(),
    /** Kraken: ausstehende und gebuchte Zeile mit anderer Menge, beide übernommen ([CsvImportRow.pendingDiffers]). */
    val pendingDiffers: List<Int> = emptyList(),
    /** Datei über den Grenzen des Zerlegens ([CsvImportResult.tooLarge]): keine Zeilen, Meldung «zu gross». */
    val tooLarge: Boolean = false,
    /** Zeitzone der Tageskurse ([CsvImportResult.fxZone]: Steuer-Vorlage, Neutral = Gerät); gezeigt, wenn [dayRates]. */
    val fxZone: String = CsvImportParser.DEFAULT_ZONE,
    /** Mindestens eine neue Zeile ist mit einem Kurs ihres Tags umgerechnet ([CsvImportPriced.dayRate]). */
    val dayRates: Boolean = false,
    /**
     * Tageskurse einer gebrauchten Währung liessen sich nicht laden (keine gültige Antwort) — von der App gesetzt; die
     * Vorschau unterscheidet es von «für diese Währung bzw. diesen Tag gibt es keinen Kurs».
     */
    val ratesFailed: Boolean = false,
    /** Zeilen ohne Tageskurs: Währungen ohne mögliche Tageskurse, hilft ein neuer Abruf ([CsvImportRateGate.retry])? */
    val rateRetry: CsvRateRetry = CsvRateRetry(),
)
