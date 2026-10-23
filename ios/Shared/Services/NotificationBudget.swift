import Foundation

/// Wie viele Kurs-Mitteilungen die App gleichzeitig einzeln zeigt — wie `NotificationBudget.kt`.
/// Höchstens `maxPrice` Kurs-Mitteilungen einzeln, der Rest in EINER Sammelmeldung («+N weitere Kurse») im
/// Thread «prices»; sonst stünden nach dem ersten Durchlauf mit hunderten Paaren ebenso viele Mitteilungen da
/// (Kursmitteilung für neue Paare ist standardmässig an). Reine Regeln, testbar.
enum NotificationBudget {
    /// Offene Mitteilungen je App, ab denen Android neue still verwirft — auf iOS dieselbe Rechnung, damit beide
    /// Plattformen gleich viele Kurse einzeln zeigen.
    static let systemLimit = 50
    /// Höchstens so viele Kurs-Mitteilungen einzeln.
    static let maxPrice = 20
    /// So viele Plätze lassen Kurs-Mitteilungen immer für Alarme frei.
    static let alarmReserve = 10
    /// Sammelmeldung und Gruppen-Zusammenfassung der Kurs-Mitteilungen (Android zählt beide).
    private static let priceExtras = 2
    /// Zeilen der Sammelmeldung (danach «…»).
    static let overflowLines = 5

    /// Plätze für einzelne Kurs-Mitteilungen, wenn `otherActive` andere Mitteilungen offen sind.
    static func priceSlots(otherActive: Int) -> Int {
        min(max(systemLimit - alarmReserve - priceExtras - max(0, otherActive), 0), maxPrice)
    }

    struct PricePlan: Equatable {
        /// Einzeln zeigen bzw. aktualisieren (Paar-ids, Reihenfolge der Meldungen).
        var individual: [Int64]
        /// In der Sammelmeldung, neueste zuerst.
        var overflow: [Int64]
        /// Bisher einzeln gezeigt, jetzt in der Sammelmeldung: einzeln entfernen.
        var demoted: [Int64]
    }

    /// Verteilung der Kurs-Mitteilungen — dieselbe Regel wie Android `planPrices`: `shown` = einzeln offen
    /// (neueste zuerst; behalten ihren Platz), `overflowBefore` = bisher in der Sammelmeldung, `posts` = jetzt zu
    /// melden, `slots` = `priceSlots`. Neue Meldungen bekommen freie Plätze, sonst die Sammelmeldung.
    static func planPrices(shown: [Int64], overflowBefore: [Int64], posts: [Int64], slots: Int) -> PricePlan {
        let limit = max(0, slots)
        let open = distinct(shown)
        let keep = Set(open.prefix(limit))
        let demoted = Array(open.dropFirst(limit))
        var used = keep.count
        var individual: [Int64] = []
        var newOverflow: [Int64] = []
        for id in distinct(posts) {
            if keep.contains(id) {
                individual.append(id)
            } else if used < limit {
                individual.append(id)
                used += 1
            } else {
                newOverflow.append(id)
            }
        }
        let single = keep.union(individual)
        let overflow = distinct(newOverflow + demoted + overflowBefore).filter { !single.contains($0) }
        return PricePlan(individual: individual, overflow: overflow, demoted: demoted)
    }

    /// Text der Sammelmeldung: die ersten `overflowLines` Zeilen, danach «…».
    static func overflowBody(_ lines: [String]) -> String {
        var shown = Array(lines.prefix(overflowLines))
        if lines.count > overflowLines { shown.append("…") }
        return shown.joined(separator: "\n")
    }

    private static func distinct(_ ids: [Int64]) -> [Int64] {
        var seen = Set<Int64>()
        return ids.filter { seen.insert($0).inserted }
    }
}
