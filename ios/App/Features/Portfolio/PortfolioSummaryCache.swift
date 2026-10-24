import Foundation

/// Schlüssel der Gesamtrechnung (`PortfolioCalculator.summarize`): Transaktionen samt Adress-Guthaben, aktuelle Kurse,
/// Einstandsmethode und welche Ladung der Tagesschlüsse (Zähler). Vergleich meist billig — unveränderte Arrays und
/// Dictionaries teilen ihren Speicher, `==` erkennt das sofort.
struct PortfolioSummaryKey: Equatable, Sendable {
    let trades: [PortfolioTx]
    let prices: [String: Double]
    let method: CostMethod
    let closesStamp: Int
    /// Herkunftsteile des Ø-Einstands führen (`summarize` `withParts`) — nur für den Einstand in einer Anzeigewährung
    /// (`PortfolioCostFx`); in USDT bleibt die Ø-Rechnung ohne sie linear, alle gezeigten Zahlen gleich.
    var withParts = true
}

/// Eingabe einer Rechnung: Schlüssel und die Tagesschlüsse, zu denen Auszahlungen und Gebühr-Zeilen «ausgebucht» werden.
struct PortfolioSummaryInput: Sendable {
    let key: PortfolioSummaryKey
    /// Reihen je Coin (eigene Börse, sonst Binance skaliert), wie `PortfolioModel.feePrice`.
    let closes: [String: [Int: Double]]

    /// «Ausgebucht»: Auszahlung ohne Preis zum Tagesschluss ihres Tags (danach aktueller Kurs); ohne geladene
    /// Schlusskurse unbekannt → «unvollständig» (`PortfolioSummary.withdrawnGainIncomplete`); Gebühr-Zeilen ebenso —
    /// wie Android. Rein, ohne Hauptthread: läuft im Hintergrund.
    nonisolated static func summarize(_ input: PortfolioSummaryInput) -> PortfolioSummary {
        let live = input.key.prices
        let closes = input.closes
        return PortfolioCalculator.summarize(input.key.trades, prices: live, method: input.key.method,
                                             withdrawalPrice: { coin, time in
            PortfolioPeriodChange.marketPriceAt(coin, live: live[PortfolioCalculator.normalizeCoin(coin)],
                                                closes: closes, time: time)
        }, withParts: input.key.withParts)
    }
}

/// Zwischenspeicher der Gesamtrechnung für den Portfolio-Tab. Die Rechnung (FIFO je Coin) läuft abseits
/// des Hauptthreads, wie auf Android (`flowOn(Dispatchers.Default)`): `body` bekommt bis zum neuen Ergebnis das letzte
/// (kein leeres Portfolio dazwischen), nur ganz ohne Ergebnis wird einmal sofort gerechnet. Gleiche Eingaben laufen
/// höchstens einmal — auch wenn Ansicht und «7 T» gleichzeitig danach fragen.
@MainActor
final class PortfolioSummaryCache {
    typealias Compute = @Sendable (PortfolioSummaryInput) -> PortfolioSummary

    private let compute: Compute
    private var cached: (key: PortfolioSummaryKey, result: PortfolioSummary, seq: Int)?
    /// Laufende Rechnung im Hintergrund (höchstens eine je Schlüssel).
    private var job: (key: PortfolioSummaryKey, seq: Int, task: Task<PortfolioSummary, Never>)?
    /// Zählt Rechnungen in Auftragsreihenfolge: ein älteres Ergebnis überschreibt nie ein neueres.
    private var seq = 0
    /// Neues Ergebnis aus dem Hintergrund übernommen — die Ansicht neu zeichnen.
    var onPublish: (() -> Void)?

    init(compute: @escaping Compute = { PortfolioSummaryInput.summarize($0) }) {
        self.compute = compute
    }

    /// Ergebnis genau zu `key`, falls schon gerechnet.
    func hit(_ key: PortfolioSummaryKey) -> PortfolioSummary? {
        guard let c = cached, c.key == key else { return nil }
        return c.result
    }

    /// Genau zu `key`, sofort: Treffer, sonst jetzt (auf dem Hauptthread) gerechnet. Für Aufrufer, die keinen
    /// älteren Stand brauchen können. `input` wird nur bei einem Fehltreffer gebildet.
    func now(_ key: PortfolioSummaryKey, input: () -> PortfolioSummaryInput) -> PortfolioSummary {
        if let result = hit(key) { return result }
        let result = compute(input())
        seq += 1
        cached = (key, result, seq)
        return result
    }

    /// Für `body`: Treffer, sonst das letzte Ergebnis und die neue Rechnung im Hintergrund (danach `onPublish`).
    /// Noch gar keins: einmal sofort, damit der erste Aufbau gleich bleibt.
    func shown(_ key: PortfolioSummaryKey, input: () -> PortfolioSummaryInput) -> PortfolioSummary {
        if let result = hit(key) { return result }
        guard let last = cached?.result else { return now(key, input: input) }
        if job?.key != key {
            let started = start(key, input: input())
            Task { _ = await self.finish(key, started) }
        }
        return last
    }

    /// Genau zu `key`, im Hintergrund gerechnet (oder an eine laufende Rechnung derselben Eingaben angehängt).
    func fresh(_ key: PortfolioSummaryKey, input: () -> PortfolioSummaryInput) async -> PortfolioSummary {
        if let result = hit(key) { return result }
        let started = job.flatMap { $0.key == key ? ($0.seq, $0.task) : nil } ?? start(key, input: input())
        return await finish(key, started)
    }

    private func start(_ key: PortfolioSummaryKey, input: PortfolioSummaryInput) -> (Int, Task<PortfolioSummary, Never>) {
        seq += 1
        let compute = self.compute
        let task = Task.detached(priority: .userInitiated) { compute(input) }
        job = (key, seq, task)
        return (seq, task)
    }

    private func finish(_ key: PortfolioSummaryKey, _ started: (Int, Task<PortfolioSummary, Never>)) async -> PortfolioSummary {
        let (number, task) = started
        let result = await task.value
        if job?.seq == number { job = nil }
        if number > (cached?.seq ?? 0) {
            cached = (key, result, number)
            onPublish?()
        }
        return result
    }
}
