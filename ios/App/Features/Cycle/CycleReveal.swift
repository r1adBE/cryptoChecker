import Foundation

/// Reihenfolge, in der die Teile des Markt-Tabs von oben nach unten erscheinen
/// (die Überschrift «Jetzt» gehört zu `pulse`). Wie `MarketRevealSlot.kt`:
/// Jetzt (Pulse, «Heute auffällig» — Karten) → Einordnung (Fear & Greed, Marktphase, Dominanz
/// mit Altcoin-Saison, Zyklus/Halving) → Daten (Krypto-Markt, Gas, Wirtschaftsdaten, Coin) —
/// Einordnung und Daten als Zeilen ohne Karte.
enum CycleRevealSlot: Int, CaseIterable {
    case pulse
    /// «Heute auffällig», direkt unter dem Pulse.
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

/// Ruhiges, schrittweises Erscheinen der Karten im Markt-Tab — wie `MarketReveal.kt`:
/// Karte n erscheint erst, wenn ihre Daten bereit sind (geladen, gescheitert oder aus dem
/// Zwischenspeicher) UND Karte n−1 schon steht — frühestens `staggerMillis` nach ihr.
/// Braucht eine Karte länger als `waitMillis` nach der vorigen, erscheint sie trotzdem
/// (als Platzhalter), damit eine langsame Quelle den Rest nicht aufhält.
/// Was schon beim Start bereit ist (führende Karten), erscheint sofort und ohne Animation.
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

    /// - `revealed`: so viele Teile (von oben) sind jetzt sichtbar
    /// - `nextAt`: spätestens dann neu rechnen (oder früher, sobald Daten kommen); nil = alle sichtbar
    struct Plan: Equatable {
        let revealed: Int
        let nextAt: Int64?
    }

    /// Führende Teile, die beim Start schon bereit waren — erscheinen sofort, ohne Animation.
    static func instantCount(readyAt: [Int64?], start: Int64) -> Int {
        for (index, ready) in readyAt.enumerated() {
            guard let ready, ready <= start else { return index }
        }
        return readyAt.count
    }

    /// Welche Teile zur Zeit `now` sichtbar sind.
    /// - Parameters:
    ///   - readyAt: je Teil (Reihenfolge `CycleRevealSlot`) der Zeitpunkt, zu dem seine Daten
    ///     zum ersten Mal bereit waren; nil = noch nicht
    ///   - start: Beginn der Abfolge (gleiche Uhr wie `readyAt` und `now`)
    static func plan(readyAt: [Int64?], start: Int64, now: Int64) -> Plan {
        let instant = instantCount(readyAt: readyAt, start: start)
        var previous = start
        for index in readyAt.indices {
            let at: Int64
            if index < instant {
                at = start
            } else {
                let earliest = index == 0 ? start : previous + staggerMillis
                let deadline = previous + waitMillis
                if let ready = readyAt[index], ready <= deadline {
                    at = max(earliest, ready)
                } else if readyAt[index] != nil || now >= deadline {
                    // Zu spät oder noch nicht da, Wartezeit aber vorbei: als Platzhalter zeigen
                    at = deadline
                } else {
                    // Noch offen: spätestens zur Frist wieder rechnen
                    return Plan(revealed: index, nextAt: deadline)
                }
            }
            if at > now { return Plan(revealed: index, nextAt: at) }
            previous = at
        }
        return Plan(revealed: readyAt.count, nextAt: nil)
    }
}
