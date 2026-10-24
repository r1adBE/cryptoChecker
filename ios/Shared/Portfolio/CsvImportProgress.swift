import Foundation

// Fortschritt der langen Schritte des CSV-Imports (Vorschau umrechnen, Speichern): gedrosselt melden, abbrechbar anlegen
// und abseits des Haupt-Threads rechnen. Gespiegelt in `CsvImportProgress.kt`.

/// Langer Schritt des CSV-Imports mit Fortschrittsbalken.
enum CsvImportStep: Sendable {
    case PREVIEW
    case SAVE
}

/// Stand eines langen Schritts: `done` von `total` Zeilen.
struct CsvImportProgress: Equatable, Sendable {
    let step: CsvImportStep
    let done: Int
    let total: Int

    /// Anteil 0…1 (ohne Zeilen 0).
    var fraction: Double { total > 0 ? min(max(Double(done) / Double(total), 0), 1) : 0 }
}

/// «Abbrechen» während des Speicherns: nichts angelegt (der Aufrufer verwirft den zusammengestellten Stand).
struct CsvImportCancelled: Error {}

/// Meldet den Fortschritt gedrosselt an `report`: höchstens alle `minRows` Zeilen und frühestens nach `minMillis` ms (die
/// Ansicht zeichnet nicht für jede Zeile neu); die letzte Zeile (`total`) immer, genau einmal. Aus einem einzigen Ablauf
/// rufen — wie Android `CsvProgressThrottle`.
struct CsvProgressThrottle {
    static let minRows = 100
    static let minMillis: Int64 = 50

    let step: CsvImportStep
    let total: Int
    private let minRows: Int
    private let minMillis: Int64
    private let clock: () -> Int64
    private let report: (CsvImportProgress) -> Void
    private var lastDone = 0
    private var lastTime: Int64

    init(step: CsvImportStep, total: Int, minRows: Int = CsvProgressThrottle.minRows,
         minMillis: Int64 = CsvProgressThrottle.minMillis,
         clock: @escaping () -> Int64 = { Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000) },
         report: @escaping (CsvImportProgress) -> Void) {
        self.step = step
        self.total = total
        self.minRows = minRows
        self.minMillis = minMillis
        self.clock = clock
        self.report = report
        lastTime = clock()
    }

    /// «0 von `total`»: der Balken erscheint gleich zu Beginn des Schritts.
    func start() { report(CsvImportProgress(step: step, done: 0, total: total)) }

    /// `done` Zeilen fertig.
    mutating func row(_ done: Int) {
        guard done > lastDone else { return }
        let now = clock()
        if done < total && (done - lastDone < minRows || now - lastTime < minMillis) { return }
        lastDone = done
        lastTime = now
        report(CsvImportProgress(step: step, done: min(done, total), total: total))
    }
}

/// Abbrechbares Anlegen importierter Zeilen — gleiche Regel wie Android `CsvImportInsert`.
enum CsvImportInsert {
    /// Zeilen der Reihe nach anlegen; Zeilen mit einem schon bekannten Schlüssel (`keys`, in `known`) werden übersprungen,
    /// die angelegten kommen zu `known`; `insert` = true bei angelegt. Vor jeder Zeile wird `cancelled` geprüft — dann
    /// `CsvImportCancelled` (der Aufrufer schreibt nichts); nach jeder Zeile `onRow` mit der Zahl fertiger Zeilen.
    /// - Returns: Anzahl angelegter Zeilen
    static func run<T>(_ items: [T], known: inout Set<String>, keys: (T) -> [String], cancelled: () -> Bool,
                       onRow: (Int) -> Void, insert: (T) -> Bool) throws -> Int {
        var count = 0
        for (i, item) in items.enumerated() {
            if cancelled() { throw CsvImportCancelled() }
            let k = keys(item)
            if !k.contains(where: { known.contains($0) }) {
                known.formUnion(k)
                if insert(item) { count += 1 }
            }
            onRow(i + 1)
        }
        return count
    }
}

/// Rechnen abseits des Haupt-Threads (wie `Dispatchers.Default` auf Android).
enum CsvImportBackground {
    /// `work` losgelöst mit Vorrang «vom Nutzer angestossen»; bricht der Aufrufer ab, wird auch `work` abgebrochen
    /// (`Task.isCancelled` darin wird wahr). Das Ergebnis kommt im Kontext des Aufrufers zurück (z. B. MainActor).
    static func run<T: Sendable>(_ work: @escaping @Sendable () async -> T) async -> T {
        let task = Task.detached(priority: .userInitiated, operation: work)
        return await withTaskCancellationHandler {
            await task.value
        } onCancel: {
            task.cancel()
        }
    }
}
