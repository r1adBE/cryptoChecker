import Foundation

/// Darf ein beim Start gefundenes Journal (`RestoreJournal`) noch zurückgerollt werden? Wie `RestoreRecoveryPolicy.kt`,
/// gleiche Fälle auf beiden Seiten.
///
/// Seit Build 93: Scheiterte das Zurückrollen, blieb das Journal liegen und jeder Start versuchte es erneut. Der Nutzer
/// arbeitet aber weiter — ein späteres, gelungenes Zurückrollen hätte Tage an Änderungen mit dem Stand vor dem
/// Wiederherstellen überschrieben, und der Hinweis «nicht vollständig» kam bei jedem Start. Darum genau ein weiterer
/// Versuch beim nächsten Start und nie mit einem Journal, das älter als `maxAgeMillis` ist; danach aufgeben (Journal
/// löschen, Hinweis «nicht zurückgeschrieben» einmal).
enum RestoreRecoveryPolicy {
    /// Älter: der Nutzer hat inzwischen sehr wahrscheinlich weitergearbeitet.
    static let maxAgeMillis: Int64 = 24 * 60 * 60 * 1000

    enum Decision: Equatable { case rollBack, giveUp }

    /// Zeitpunkt des Journals: `stored` aus dem Inhalt («createdAt» der Sicherung), sonst die Änderungszeit der Datei
    /// `fileModified` (die Datei wird nur einmal geschrieben). ≤ 0 = unbekannt.
    static func createdAt(stored: Int64, fileModified: Int64) -> Int64 {
        stored > 0 ? stored : fileModified
    }

    /// «createdAt» aus dem Journal-Inhalt; 0 = fehlt oder nicht lesbar.
    static func storedCreatedAt(_ json: Data) -> Int64 {
        guard let root = try? JSONSerialization.jsonObject(with: json) as? [String: Any],
              let value = root["createdAt"] as? NSNumber else { return 0 }
        return value.int64Value
    }

    /// `attemptedFor` = Zeitpunkt des Journals, für das ein früherer Start schon zurückrollen wollte (gescheitert) oder
    /// aufgab; nil = noch keiner. Unbekanntes Alter: nicht zurückrollen — lieber den Stand stehen lassen als womöglich
    /// neuere Änderungen überschreiben.
    static func decide(createdAt: Int64, attemptedFor: Int64?, now: Int64) -> Decision {
        if createdAt <= 0 { return .giveUp }
        if attemptedFor == createdAt { return .giveUp }
        if now - createdAt > maxAgeMillis { return .giveUp }
        return .rollBack
    }

    /// Beim Aufgeben den Hinweis nur zeigen, wenn ihn nicht schon der gescheiterte Versuch gezeigt hat (einmal je Journal).
    static func noticeOnGiveUp(createdAt: Int64, attemptedFor: Int64?) -> Bool {
        attemptedFor != createdAt
    }
}
