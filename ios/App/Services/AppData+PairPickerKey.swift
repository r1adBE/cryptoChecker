import Foundation

/// Paare und Schlüssel (`AlarmPairPicker.sourceKey`) für die Paarwahl einmal je Stand
/// (`snapshotGeneration`) statt bei jedem Neuzeichnen des Blatts (Tastenanschlag, Elternbildschirm) über bis zu 2000
/// Paare. Dieselben Werte wie `watches` und `sourceKey(watches)`, nur gemerkt.
extension AppData {
    /// Eingabe der Paarwahl: Paare in Anzeige-Reihenfolge und ihr Schlüssel ohne Kurs.
    struct AlarmPairPickerInput {
        let watches: [Watch]
        let sourceKey: Int
    }

    /// Gemerkt je AppData und Stand (Haupt-Thread).
    private static var pairPickerMemo: (owner: ObjectIdentifier, generation: Int, input: AlarmPairPickerInput)?

    #if DEBUG
    /// Berechnungen des Schlüssels (nur Tests).
    static var pairPickerKeyComputations = 0
    #endif

    var alarmPairPickerInput: AlarmPairPickerInput {
        let owner = ObjectIdentifier(self)
        if let memo = Self.pairPickerMemo, memo.owner == owner, memo.generation == snapshotGeneration {
            return memo.input
        }
        let current = watches
        let input = AlarmPairPickerInput(watches: current, sourceKey: AlarmPairPicker.sourceKey(current))
        #if DEBUG
        Self.pairPickerKeyComputations += 1
        #endif
        Self.pairPickerMemo = (owner, snapshotGeneration, input)
        return input
    }
}
