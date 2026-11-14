import Foundation

/// Vorschau eines Imports — wie `CsvImportPreview` (Kotlin).
struct CsvImportPreview {
    let format: CsvImportFormat
    let transactions: [CsvImportTx]
    let duplicates: Int
    /// Zeilen der Datei, die schon importiert sind (`duplicates`) — die Vorschau nennt sie.
    var duplicateLines: [Int] = []
    /// «Trotzdem importieren» gewählt (`CsvReadChoice.keepDuplicates`): sie stehen unter `transactions`.
    var duplicatesKept = false
    let skipped: [CsvImportSkip]
    /// Zeilen ohne Tageskurs: «Importieren» gesperrt, bis die Kurse da sind oder der Import ausdrücklich trotzdem
    /// verlangt wird — dann mit Vermerk an jeder betroffenen Transaktion (`CsvImportRateGate`). Wie Android.
    let missingFx: [Int]
    /// Kauf/Verkauf ohne Preis in der Datei (ohne Preis übernommen).
    let missingPrice: [Int]
    /// Zeitzone der Zeiten ohne Versatz (`CsvImportResult.zone`) und ob es solche gibt (Auswahl in der Vorschau).
    var zone: String = CsvImportParser.defaultZone
    var localTimes = false
    /// Reihenfolge Tag/Monat (`CsvImportResult.dateOrder`) und ob die Datei sie offen lässt (Auswahl in der Vorschau).
    var dateOrder: CsvDateOrder = .DAY_FIRST
    var dateOrderAmbiguous = false
    /// Mindestens eine neue Zeile ist ein Kauf/Verkauf gegen Stablecoin, gebucht als Tausch (`CsvImportRow.stableSwap`).
    var stableSwaps = false
    /// Mehrdeutige Zahl und gelesenes Dezimalzeichen (`CsvImportResult.decimalSample`); Auswahl in der Vorschau.
    var decimalSample: String? = nil
    var decimalMark: Character? = nil
    /// Erkannte Spalten (`CsvImportResult.columns`).
    var columns: [String] = []
    /// Zeilen, deren USD-/Fiat-Betrag mit USDT = 1 USD umgerechnet ist (kein USDT/USD-Tagesschluss DES Tags,
    /// `CsvImportPriced.usdtApproximate`) — die Vorschau nennt sie wie `missingFx`. Wie Android.
    var usdtApproximate: [Int] = []
    /// Zeilen mit Fiat-Gebühr im Preis und Gebühr im Coin am Geschäft (`CsvImportRow.feeInPrice`) — Hinweis. Wie Android.
    var feeInPrice: [Int] = []
    /// Kraken: ausstehende und gebuchte Zeile mit anderer Menge, beide übernommen (`CsvImportRow.pendingDiffers`).
    var pendingDiffers: [Int] = []
    /// Datei über den Grenzen des Zerlegens (`CsvImportResult.tooLarge`): keine Zeilen, Meldung «zu gross».
    var tooLarge = false
    /// Zeitzone der Tageskurse (`CsvImportResult.fxZone`: Steuer-Vorlage, Neutral = Gerät); gezeigt, wenn `dayRates`.
    var fxZone: String = CsvImportParser.defaultZone
    /// Mindestens eine neue Zeile ist mit einem Kurs ihres Tags umgerechnet (`CsvImportPriced.dayRate`).
    var dayRates = false
    /// Tageskurse einer gebrauchten Währung liessen sich nicht laden (keine gültige Antwort) — von der App gesetzt; die
    /// Vorschau unterscheidet es von «für diese Währung bzw. diesen Tag gibt es keinen Kurs». Wie Android.
    var ratesFailed = false
    /// Zeilen ohne Tageskurs: Währungen ohne mögliche Tageskurse, hilft ein neuer Abruf (`CsvImportRateGate.retry`)?
    var rateRetry = CsvRateRetry()
}
