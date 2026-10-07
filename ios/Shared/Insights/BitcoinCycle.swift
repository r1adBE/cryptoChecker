import Foundation

/// Phase im vierjährigen Bitcoin-Zyklus, abgeleitet aus früheren Zyklen.
enum CyclePhase: String, Sendable, Codable {
    /// Kurz nach dem Halving: Kurs sammelt sich, Aufwärtsbewegung beginnt.
    case EARLY_BULL
    /// Historisch der stärkste Anstieg; Hochs lagen 12–18 Monate nach dem Halving.
    case BULL
    /// Nach dem Zyklushoch; Tiefs lagen rund 12–13 Monate nach dem Hoch.
    case BEAR
    /// Bodenbildung und Erholung bis zum nächsten Halving.
    case RECOVERY
}

struct CycleInfo: Equatable, Sendable, Codable {
    let phase: CyclePhase
    let monthsSinceHalving: Int
    let lastHalving: LocalDay
    let nextHalvingEstimate: LocalDay
}

/// Bitcoin-Halving-Zyklus. Rein kalenderbasiert, ohne Internet — wie `BitcoinCycle.kt`.
///
/// Grundlage sind die bisherigen Zyklen (Hochs: Dez. 2013, Dez. 2017,
/// Nov. 2021, Okt. 2025 — jeweils 12–18 Monate nach dem Halving; Tiefs
/// rund ein Jahr danach). Das ist ein historisches Muster, keine Prognose.
enum BitcoinCycle {

    /// Tatsächliche Halving-Termine.
    static let halvings: [LocalDay] = [
        LocalDay(2012, 11, 28),
        LocalDay(2016, 7, 9),
        LocalDay(2020, 5, 11),
        LocalDay(2024, 4, 20),
    ]

    /// Ein Halving alle 210'000 Blöcke, im Schnitt knapp vier Jahre.
    static let cycleDays = 1_440

    static func info(today: LocalDay = .today()) -> CycleInfo {
        let last = halvings.last(where: { !$0.isAfter(today) }) ?? halvings[0]

        // Liegt heute schon nach einem geschätzten künftigen Halving, weiterzählen.
        var lastHalving = last
        var next = last.plusDays(cycleDays)
        while !next.isAfter(today) {
            lastHalving = next
            next = next.plusDays(cycleDays)
        }

        let months = lastHalving.months(until: today)
        let phase: CyclePhase
        if months < 6 {
            phase = .EARLY_BULL
        } else if months < 18 {
            phase = .BULL
        } else if months < 31 {
            phase = .BEAR
        } else {
            phase = .RECOVERY
        }
        return CycleInfo(phase: phase, monthsSinceHalving: months, lastHalving: lastHalving, nextHalvingEstimate: next)
    }
}
