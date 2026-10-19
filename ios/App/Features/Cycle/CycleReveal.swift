import Foundation

/// Reihenfolge, in der die Teile des Markt-Tabs von oben nach unten erscheinen — je Register
/// (`MarketSections.of`) eine eigene Kette (`CycleReveal`); die Überschrift «Jetzt» gehört zu
/// `pulse`. Wie `MarketRevealSlot.kt`:
/// Jetzt (Pulse, «Heute auffällig» — Karten) → Einordnung (Fear & Greed, Marktphase, Dominanz
/// mit Altcoin-Saison, Zyklus/Halving) → Daten (Krypto-Markt, Gas, Wirtschaftsdaten, Coin) —
/// Einordnung und Daten als Zeilen ohne Karte.
enum CycleRevealSlot: Int, CaseIterable {
    case pulse
    /// «Heute auffällig»; in der Kette «Jetzt» nach dem Pulse, auf dem Bildschirm aber unter Fear & Greed
    /// in «Mehr Marktdaten» (`CycleReveal.nowOrder`).
    case unusual
    case headerContext
    /// Fear & Greed — erste Zeile unter «Einordnung».
    case fearGreed
    case phase
    case dominance
    case halving
    case headerData
    /// «Krypto-Markt» (Marktkapitalisierung, Volumen) — erste Karte unter «Daten».
    case marketTotals
    case gas
    /// Coin-Analyse; davor der Wirtschaftsdaten-Hinweis, wenn kein Termin in ±2 h liegt.
    case coin
}

/// Ruhiges, schrittweises Erscheinen der Karten im Markt-Tab — wie `MarketReveal.kt`: je Register
/// (`MarketSection`: Jetzt, Einordnung, Daten) eine eigene Kette, damit eine langsame Quelle in
/// «Einordnung» «Daten» nicht aufhält. Innerhalb eines Registers erscheint Karte n erst, wenn ihre
/// Daten bereit sind (geladen, gescheitert oder aus dem Zwischenspeicher) UND Karte n−1 schon
/// steht — frühestens `staggerMillis` nach ihr. Braucht eine Karte länger als `waitMillis` nach der
/// vorigen (die erste eines Registers: nach dem Start), erscheint sie trotzdem (als Platzhalter).
/// Was schon beim Start bereit ist (führende Karten je Register), erscheint sofort und ohne Animation.
enum CycleReveal {
    /// Abstand zwischen zwei aufeinanderfolgenden Karten.
    static let staggerMillis: Int64 = 60
    /// Längstens so lange nach der vorigen Karte auf Daten warten.
    static let waitMillis: Int64 = 1500
    /// Einblenden einer Karte (dabei 8 pt von unten nach oben).
    static let enterSeconds: Double = 0.22
    /// Platzhalter → Inhalt in einer schon sichtbaren Karte (Überblenden, Höhe der Karte).
    static let swapSeconds: Double = 0.2

    /// Anzahl Teile (`CycleRevealSlot`).
    static let count = CycleRevealSlot.allCases.count

    /// Reihenfolge auf «Jetzt» von oben: Pulse, Fear & Greed (mit «Gewinner und Verlierer 24 h»), dann
    /// «Heute auffällig» unter «Mehr Marktdaten» — dieses wartet auf die sichtbaren Karten, nie umgekehrt.
    static let nowOrder: [CycleRevealSlot] = [.pulse, .fearGreed, .unusual]

    /// - `revealed`: diese Teile sind jetzt sichtbar (je Register die ersten)
    /// - `nextAt`: spätestens dann neu rechnen (oder früher, sobald Daten kommen); nil = alle sichtbar
    struct Plan: Equatable {
        let revealed: Set<CycleRevealSlot>
        let nextAt: Int64?
    }

    /// Auf «Jetzt» (`nowOrder`): ein Teil erst, wenn auch alles darüber auf «Jetzt» steht — so bleibt
    /// die Seite von oben nach unten. Teile, die nicht auf «Jetzt» stehen: nur `revealed`.
    static func isShownOnNow(_ slot: CycleRevealSlot, revealed: Set<CycleRevealSlot>) -> Bool {
        guard revealed.contains(slot) else { return false }
        let above = nowOrder.firstIndex(of: slot) ?? 0
        return nowOrder.prefix(above).allSatisfy { revealed.contains($0) }
    }

    /// Ladezeile unter «Jetzt»: bis der unterste sichtbare Teil steht — bei zugeklapptem «Mehr Marktdaten»
    /// Fear & Greed, aufgeklappt «Heute auffällig». Ein verborgener Teil hält nichts auf.
    static func nowLoading(moreExpanded: Bool, revealed: Set<CycleRevealSlot>) -> Bool {
        !isShownOnNow(moreExpanded ? .unusual : .fearGreed, revealed: revealed)
    }

    /// Wirtschaftsdaten-Hinweis auf «Jetzt» steht fest: «Mehr Marktdaten» offen und alles darüber
    /// (bis «Heute auffällig») erschienen — erst dann zum Hinweis scrollen, sonst rutscht er danach weg.
    static func macroHintReady(moreExpanded: Bool, revealed: Set<CycleRevealSlot>) -> Bool {
        moreExpanded && isShownOnNow(.unusual, revealed: revealed)
    }

    /// Führende Teile je Register, die beim Start schon bereit waren — sofort, ohne Animation.
    static func instantSlots(readyAt: [Int64?], start: Int64) -> Set<CycleRevealSlot> {
        var out: Set<CycleRevealSlot> = []
        for section in MarketSection.allCases {
            out.formUnion(leadingReady(slots(section), readyAt: readyAt, start: start))
        }
        return out
    }

    /// Welche Teile zur Zeit `now` sichtbar sind; je Register eine Kette.
    /// - Parameters:
    ///   - readyAt: je Teil (Reihenfolge `CycleRevealSlot`) der Zeitpunkt, zu dem seine Daten
    ///     zum ersten Mal bereit waren; nil = noch nicht
    ///   - start: Beginn der Abfolge (gleiche Uhr wie `readyAt` und `now`)
    static func plan(readyAt: [Int64?], start: Int64, now: Int64) -> Plan {
        var revealed: Set<CycleRevealSlot> = []
        var nextAt: Int64?
        for section in MarketSection.allCases {
            let (shown, next) = chain(slots(section), readyAt: readyAt, start: start, now: now)
            revealed.formUnion(shown)
            if let next { nextAt = min(nextAt ?? next, next) }
        }
        return Plan(revealed: revealed, nextAt: nextAt)
    }

    /// Teile eines Registers von oben nach unten.
    private static func slots(_ section: MarketSection) -> [CycleRevealSlot] {
        CycleRevealSlot.allCases.filter { MarketSections.of($0) == section }
    }

    /// Führende Teile, die beim Start schon bereit waren.
    private static func leadingReady(_ slots: [CycleRevealSlot], readyAt: [Int64?], start: Int64) -> [CycleRevealSlot] {
        var out: [CycleRevealSlot] = []
        for slot in slots {
            guard let ready = readyAt[slot.rawValue], ready <= start else { break }
            out.append(slot)
        }
        return out
    }

    /// Eine Kette: sichtbare Teile (von oben) und wann neu rechnen (nil = alle sichtbar).
    private static func chain(_ slots: [CycleRevealSlot], readyAt: [Int64?], start: Int64,
                              now: Int64) -> ([CycleRevealSlot], Int64?) {
        let instant = leadingReady(slots, readyAt: readyAt, start: start).count
        var previous = start
        for (index, slot) in slots.enumerated() {
            let at: Int64
            if index < instant {
                at = start
            } else {
                let earliest = index == 0 ? start : previous + staggerMillis
                let deadline = previous + waitMillis
                if let ready = readyAt[slot.rawValue], ready <= deadline {
                    at = max(earliest, ready)
                } else if readyAt[slot.rawValue] != nil || now >= deadline {
                    // Zu spät oder noch nicht da, Wartezeit aber vorbei: als Platzhalter zeigen
                    at = deadline
                } else {
                    // Noch offen: spätestens zur Frist wieder rechnen
                    return (Array(slots.prefix(index)), deadline)
                }
            }
            if at > now { return (Array(slots.prefix(index)), at) }
            previous = at
        }
        return (slots, nil)
    }
}
