import Foundation

/// Anordnung von Fear & Greed und Marktbreite (Top 30) im Widget «Was gerade auffällt» — wie Android
/// `PulseSideLayout` / `PulseWidgetMath.sideCandidates`. Liegt in Shared, damit die Tests die Regel prüfen
/// (das Widget wählt mit `ViewThatFits` die erste Anordnung, die ganz passt).
enum PulseSideLayout: CaseIterable, Hashable {
    /// «● Fear & Greed 72 · Gier · Top 30 · 24h  ↗ 22  ↘ 8» in einer Zeile.
    case oneLine
    /// Fear & Greed in der ersten Zeile, die Marktbreite in einer zweiten darunter.
    case twoLines
    /// Nur Fear & Greed.
    case fearGreed
    /// Nur die Marktbreite.
    case breadth

    var showsFearGreed: Bool { self != .breadth }
    var showsBreadth: Bool { self != .fearGreed }

    /// Reihenfolge der Versuche: beides in einer Zeile, sonst untereinander (nur mit Höhe für zwei Zeilen,
    /// `twoLines`), sonst nur Fear & Greed, sonst nur die Marktbreite. So verschwindet die Marktbreite
    /// nicht mehr, wenn das Widget hoch genug ist.
    static func candidates(hasFearGreed: Bool, hasBreadth: Bool, twoLines: Bool) -> [PulseSideLayout] {
        switch (hasFearGreed, hasBreadth) {
        case (true, true): twoLines ? [.oneLine, .twoLines, .fearGreed, .breadth] : [.oneLine, .fearGreed, .breadth]
        case (true, false): [.fearGreed]
        case (false, true): [.breadth]
        case (false, false): []
        }
    }

    /// Erste Anordnung aus `candidates`, die ganz passt (`fits`); nil = keine Nebenzeile.
    static func choose(hasFearGreed: Bool, hasBreadth: Bool, twoLines: Bool,
                       fits: (PulseSideLayout) -> Bool) -> PulseSideLayout? {
        candidates(hasFearGreed: hasFearGreed, hasBreadth: hasBreadth, twoLines: twoLines).first(where: fits)
    }
}
