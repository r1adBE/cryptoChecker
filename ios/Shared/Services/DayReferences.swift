import Foundation

/// 24-h- und Tages-Bezüge aus Kerzen für die %-Veränderung: welche Paare Kerzen brauchen, Laden
/// anstossen und kurz abwarten, Veränderung gemäss %-Basis. Wie `DayReferences.kt`; Teil von `PriceRefresher`.
extension PriceRefresher {
    // MARK: 24-h-Veränderung

    /// So lange wartet ein Durchlauf nach den Kursen noch auf fehlende 24-h-Bezüge.
    static let dayReferenceWaitNanos: UInt64 = 5_000_000_000
    /// So lange werden späte Tages-Bezüge im Hintergrund noch nachgetragen.
    static let lateFillWaitNanos: UInt64 = 90_000_000_000

    /// Benötigte Kerzenreihen: je Basis-Asset die Reihe in der Quote des Paars (USD-artige
    /// teilen sich die USDT-Reihe des Mini-Charts), bei Fiat-Quotes zusätzlich die USDT-Reihe.
    static func dayReferenceKeys(_ watches: [Watch]) -> [(base: String, quote: String)] {
        var seen = Set<String>()
        var keys: [(base: String, quote: String)] = []
        for watch in watches {
            let base = watch.baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
            let quote = DayChange.candleQuote(watch.quoteAsset)
            var quotes = [quote]
            if quote != DayChange.usdtQuote && DayChange.isFiat(watch.quoteAsset) { quotes.append(DayChange.usdtQuote) }
            for q in quotes {
                let (inserted, _) = seen.insert("\(base)|\(q)")
                if inserted { keys.append((base: base, quote: q)) }
            }
        }
        return keys
    }

    /// Paare, die beim letzten vollen Durchlauf Kerzen brauchten (Ticker ohne 24-h-Wert).
    static let candleNeeds = CandleNeeds()

    /// Paare mit Kurs, die Kerzen brauchen (`ChangeBasisMath.needsCandles`): rollend nur ohne
    /// brauchbaren 24-h-Wert im Ticker, Tages-Basen alle.
    /// `remember`: Menge für den nächsten Durchlauf merken (nur bei vollen Durchläufen).
    static func watchesNeedingCandles(_ watches: [Watch], fetched: [Int64: Fetched], basis: ChangeBasis,
                                              remember: Bool) -> [Watch] {
        let needing = watches.filter { watch in
            guard let ticker = fetched[watch.id]?.ticker else { return false }
            return ChangeBasisMath.needsCandles(basis, tickerChange: ticker.change24hPercent)
        }
        if remember { candleNeeds.ids = Set(needing.map(\.id)) }
        return needing
    }

    /// Lädt die Bezüge in einer eigenen Aufgabe: Was nach dem Warten noch fehlt, wird trotzdem
    /// fertig geladen und liegt beim nächsten Durchlauf bereit. `dayStart`: Tages-Basis —
    /// Bezug seit diesem Tagesbeginn statt rollend.
    static func startDayReferenceLoads(_ keys: [(base: String, quote: String)], dayStart: Int64?) -> Task<Void, Never> {
        Task {
            await withTaskGroup(of: Void.self) { group in
                for key in keys {
                    group.addTask {
                        if let dayStart {
                            _ = await DayReferenceStore.shared.dayStartReference(base: key.base, quote: key.quote, dayStart: dayStart)
                        } else {
                            _ = await DayReferenceStore.shared.dayReference(base: key.base, quote: key.quote)
                        }
                    }
                }
            }
        }
    }

    /// Wartet auf `task`, aber höchstens `nanos`; die Aufgabe selbst läuft weiter.
    static func waitAtMost(nanos: UInt64, for task: Task<Void, Never>) async {
        let gate = ResumeOnce()
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            gate.set(continuation)
            Task {
                await task.value
                gate.resume()
            }
            Task {
                try? await Task.sleep(nanoseconds: nanos)
                gate.resume()
            }
        }
    }

    /// Gemerkte Bezüge («BASE|QUOTE»), auch etwas ältere; fehlende bleiben weg.
    /// `dayStart`: seit diesem Tagesbeginn statt rollend.
    static func cachedDayReferences(_ watches: [Watch], dayStart: Int64?) async -> [String: DayReference] {
        var result: [String: DayReference] = [:]
        for key in dayReferenceKeys(watches) {
            let ref: DayReference?
            if let dayStart {
                ref = await DayReferenceStore.shared.cachedDayStartReference(base: key.base, quote: key.quote, dayStart: dayStart)
            } else {
                ref = await DayReferenceStore.shared.cachedReference(base: key.base, quote: key.quote)
            }
            if let ref { result["\(key.base)|\(key.quote)"] = ref }
        }
        return result
    }

    /// Veränderung zum neuen Kurs gemäss %-Basis (`ChangeBasisMath.choose`). Rollend: zuerst der
    /// 24-h-Wert aus dem Ticker (gilt für das Paar selbst, also schon in seiner Quote — auch bei
    /// Fiat-Quotes), sonst aus Kerzen. Tages-Basen: nur aus Kerzen seit Tagesbeginn (`references`
    /// sind dann solche). Kerzen mit `DayChange.select` (Kursabstand-Prüfung); nil ohne Bezug —
    /// nie die Veränderung seit der letzten Abfrage.
    static func change24h(_ watch: Watch, price: Double, ticker: Ticker, basis: ChangeBasis,
                                  references: [String: DayReference]) -> Double? {
        ChangeBasisMath.choose(basis, tickerChange: ticker.change24hPercent) {
            candleChange(watch, price: price, references: references)
        }
    }

    /// Veränderung aus den Bezügen der Kerzen (Paar, bei Fiat-Quotes ersatzweise die USDT-Reihe).
    static func candleChange(_ watch: Watch, price: Double, references: [String: DayReference]) -> Double? {
        let base = watch.baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
        let quote = DayChange.candleQuote(watch.quoteAsset)
        return DayChange.select(price: price,
                                pairReference: references["\(base)|\(quote)"],
                                usdtReference: references["\(base)|\(DayChange.usdtQuote)"],
                                quoteIsFiat: DayChange.isFiat(watch.quoteAsset))
    }
}

/// Gemerkte Watch-Ids, deren Ticker beim letzten vollen Durchlauf keinen 24-h-Wert hatte
/// (threadsicher; App und Hintergrund können gleichzeitig aktualisieren).
final class CandleNeeds: @unchecked Sendable {
    private let lock = NSLock()
    private var stored = Set<Int64>()

    var ids: Set<Int64> {
        get { lock.lock(); defer { lock.unlock() }; return stored }
        set { lock.lock(); stored = newValue; lock.unlock() }
    }
}

/// Setzt eine Fortsetzung genau einmal fort (wer zuerst kommt: Ende der Aufgabe oder Zeitgrenze).
final class ResumeOnce: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Never>?
    private var done = false

    func set(_ continuation: CheckedContinuation<Void, Never>) {
        lock.lock()
        defer { lock.unlock() }
        if done {
            continuation.resume()
        } else {
            self.continuation = continuation
        }
    }

    func resume() {
        lock.lock()
        let pending = continuation
        continuation = nil
        let first = !done
        done = true
        lock.unlock()
        if first { pending?.resume() }
    }
}
