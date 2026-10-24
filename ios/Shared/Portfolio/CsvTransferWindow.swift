import Foundation

/// Kandidaten-Paare des Umbuchungs-Abgleichs (`CsvTransferMatch.candidates`) ohne «jede Auszahlung mit jeder
/// Einzahlung»: Einzahlungen je Coin nach Zeit sortiert, das Zeitfenster per Binärsuche — O((A + E) · log E) plus die
/// Einzahlungen im Fenster statt A × E. Wie `CsvTransferWindow` (Android).
enum CsvTransferWindow {

    /// Alle Paare (Index in `outs`, Index in `ins`) mit `fits`, in derselben Reihenfolge wie die doppelte Schleife über
    /// `outs` und `ins` (Auszahlung, dann Einzahlung nach Index). `fits` muss für einen anderen Coin (`coin`) und eine
    /// Einzahlung ausserhalb von `early` vor bis `window` nach der Auszahlung (`time`) falsch sein.
    static func pairs<T>(_ outs: [T], _ ins: [T], coin: (T) -> String, time: (T) -> Int64, early: Int64, window: Int64,
                         fits: (T, T) -> Bool) -> [(Int, Int)] {
        // Je Coin die Einzahlungen nach Zeit (bei gleicher Zeit nach Index)
        var byCoin: [String: [Int]] = [:]
        for i in ins.indices { byCoin[coin(ins[i]), default: []].append(i) }
        for (key, list) in byCoin {
            byCoin[key] = list.sorted { a, b in
                let ta = time(ins[a])
                let tb = time(ins[b])
                return ta != tb ? ta < tb : a < b
            }
        }
        var out: [(Int, Int)] = []
        var hits: [Int] = []
        for (wi, w) in outs.enumerated() {
            guard let list = byCoin[coin(w)] else { continue }
            let from = time(w) - early
            let to = time(w) + window
            var lo = 0
            var hi = list.count
            while lo < hi {
                let mid = (lo + hi) / 2
                if time(ins[list[mid]]) < from { lo = mid + 1 } else { hi = mid }
            }
            hits.removeAll(keepingCapacity: true)
            while lo < list.count && time(ins[list[lo]]) <= to {
                if fits(w, ins[list[lo]]) { hits.append(list[lo]) }
                lo += 1
            }
            // Reihenfolge der doppelten Schleife: Einzahlungen nach Index
            hits.sort()
            for di in hits { out.append((wi, di)) }
        }
        return out
    }
}
