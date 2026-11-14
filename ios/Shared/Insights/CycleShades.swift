import Foundation

/// Grautöne der früheren Zyklen im Zyklus-Chart: gleichmässig von `oldest` (älteste, am hellsten) bis `newest`
/// (jüngster früherer Zyklus) verteilt, damit auch nach dem Halving 2028 (drei frühere Zyklen) jeder Zyklus seinen
/// eigenen Ton hat. Auch `oldest` erreicht als Grafik mindestens 3:1. Wie Android `CycleShades`.
enum CycleShades {
    static let oldest = 0.65
    static let newest = 0.85

    /// Deckkraft von onSurfaceVariant für den früheren Zyklus `index` (0 = ältester) von `olderCount`.
    static func olderAlpha(_ index: Int, olderCount: Int) -> Double {
        guard olderCount > 1 else { return newest }
        let i = min(max(index, 0), olderCount - 1)
        return oldest + (newest - oldest) * Double(i) / Double(olderCount - 1)
    }
}
