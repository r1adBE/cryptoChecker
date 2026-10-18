import Foundation

/// Darf ein frisch abgefragter Kurs Alarme auslösen? Liefert die Börse eine eigene Zeit des Kurses (`exchangeTime`,
/// z. B. letzter Handel) und liegt sie mehr als `exchangeStaleMillis` zurück, ist der Kurs alt, auch wenn der Abruf
/// eben erst war: dann keine Alarme in diesem Durchlauf — sie bleiben scharf und melden beim nächsten frischen Kurs.
/// Das Alter zeigt die Zeile ohnehin («veraltet», `PriceFreshness`). Ohne Zeit der Börse ändert sich nichts.
/// Wie `AlarmFreshness.kt`.
enum AlarmFreshness {
    /// Ab diesem Alter laut Börse lösen Alarme nicht aus: 2 Stunden.
    static let exchangeStaleMillis: Int64 = 2 * 60 * 60_000

    /// Kurs laut Börse zu alt für Alarme? Ohne (≤ 0) oder mit künftiger Zeit nie.
    static func exchangeTimeTooOld(_ exchangeTime: Int64?, now: Int64) -> Bool {
        guard let exchangeTime, exchangeTime > 0 else { return false }
        return now - exchangeTime > exchangeStaleMillis
    }
}
