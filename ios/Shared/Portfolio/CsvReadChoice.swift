import Foundation

/// Seit Build 103: Lesart der CSV-Datei, die in der Vorschau gewählt wird — Zeitzone der Zeiten ohne Versatz,
/// Tag/Monat bei mehrdeutigen Daten, Dezimalzeichen bei mehrdeutigen Zahlen (nil = Regel). Die Vorschau merkt sich, mit
/// welcher Lesart sie gebaut wurde; «Importieren» nur, wenn sie zur aktuellen Wahl gehört (`canConfirm`). Wie Android
/// `CsvReadChoice`.
struct CsvReadChoice: Equatable, Sendable {
    var zone: String?
    var dateOrder: CsvDateOrder?
    var decimalMark: Character?

    init(zone: String? = nil, dateOrder: CsvDateOrder? = nil, decimalMark: Character? = nil) {
        self.zone = zone
        self.dateOrder = dateOrder
        self.decimalMark = decimalMark
    }

    /// Darf importiert werden? Nicht, solange die Datei neu gelesen wird (`rereading`) oder die gezeigte Vorschau mit
    /// einer anderen Lesart gebaut wurde (`builtWith` ≠ `chosen`) — sonst käme die alte Lesart in die Daten.
    static func canConfirm(rereading: Bool, builtWith: CsvReadChoice?, chosen: CsvReadChoice) -> Bool {
        !rereading && builtWith == chosen
    }
}
