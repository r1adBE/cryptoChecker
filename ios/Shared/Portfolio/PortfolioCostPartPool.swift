import Foundation

/// Herkunftsteile eines Durchschnitts-Einstands (`PortfolioCostPart`) mit anteiligen Abgängen in O(1): ein Abgang
/// schreibt nicht jeden Teil neu, er merkt sich nur den verbleibenden Anteil (Rest ÷ Einstand davor). Der Wert eines
/// Zugangs ist sein Betrag × das Produkt der Anteile aller späteren Abgänge (`PortfolioPartStore`, Produkte über
/// Bereiche in O(log n)). Die abgegebenen Teile eines Abgangs (`take`) sind eine Ansicht (`PortfolioScaledParts`),
/// gerechnet erst beim Auslesen; der Steuer-Export rechnet grosse Ansichten ohne Auslesen um (`weightedSum`, `anyTime`,
/// `timeBounds`).
///
/// Wie eine Liste: Summe der abgegebenen Teile exakt = abgegebener Einstand (letzter Teil = Rest), Summe der
/// offenen Teile exakt = Einstand (`toArray`); ohne Abgang dazwischen ist ein Teil exakt sein Betrag. Mit `zone` fasst
/// ein Teil die Zugänge desselben Kalendertags zusammen (gleicher Devisenkurs; der erste Zeitpunkt des Tags bleibt).
/// Kopien des Werts teilen den Speicher, bis eine davon ändert (`own`). Wie `PortfolioCostPartPool.kt` (Android).
struct PortfolioCostPartPool: Sendable {
    private var store = PortfolioPartStore()
    private let zone: TimeZone?
    /// Mit `zone`: Tag → Teil (Index im Speicher).
    private var dayIndex: [LocalDay: Int] = [:]
    /// Stand des Speichers nach der letzten eigenen Änderung.
    private var mark = PortfolioPartStore.Mark()

    init(zone: TimeZone? = nil) {
        self.zone = zone
    }

    var isEmpty: Bool { mark.slots == 0 }

    /// Zugang `usdt` mit Herkunft `time`; 0 = kein Teil.
    mutating func add(_ time: Int64, _ usdt: Decimal) {
        guard usdt != 0 else { return }
        own()
        if let zone {
            let day = LocalDay(date: Date(timeIntervalSince1970: Double(time) / 1000), timeZone: zone)
            if let i = dayIndex[day] {
                store.contribute(i, usdt)
            } else {
                dayIndex[day] = store.slotCount
                store.newSlot(time, usdt)
            }
        } else {
            store.newSlot(time, usdt)
        }
        mark = store.mark
    }

    /// Nimmt `removed` vom Einstand `total` (davor) anteilig aus allen Teilen; Summe exakt `removed`. Ab `total`: alle
    /// Teile (Summe `total`), danach leer.
    mutating func take(_ removed: Decimal, total: Decimal) -> PortfolioCostPartList {
        guard removed != 0, !isEmpty else { return [] }
        if removed >= total {
            let all = store.view(mark, removed: total, total: total)
            clear()
            return all
        }
        own()
        let out = store.view(mark, removed: removed, total: total)
        store.scale(total - removed, total: total)
        mark = store.mark
        return out
    }

    /// Offene Teile; Summe exakt `total` (= Einstand).
    func toArray(total: Decimal) -> [PortfolioCostPart] {
        isEmpty ? [] : store.view(mark, removed: total, total: total).items
    }

    /// Alle Teile verwerfen (abgegebene Ansichten bleiben gültig: sie lesen den bisherigen Speicher).
    mutating func clear() {
        store = PortfolioPartStore()
        dayIndex = [:]
        mark = PortfolioPartStore.Mark()
    }

    /// Vor einer Änderung: hat eine Kopie dieses Werts den gemeinsamen Speicher seither verändert, eigene Kopie bis
    /// `mark` anlegen.
    private mutating func own() {
        if store.mark != mark { store = store.copy(upTo: mark) }
    }
}

/// Gewicht je Zeitpunkt eines Teils (z. B. Devisenkurs seines Tags); nil = fehlt. Ein Objekt je Umrechnung.
final class PortfolioPartWeight {
    let of: (Int64) -> Decimal?

    init(_ of: @escaping (Int64) -> Decimal?) {
        self.of = of
    }
}

/// Prüfung je Zeitpunkt eines Teils (z. B. «USDT = 1 als Näherung»). Ein Objekt je Umrechnung.
final class PortfolioPartTest {
    let of: (Int64) -> Bool

    init(_ of: @escaping (Int64) -> Bool) {
        self.of = of
    }
}

/// Herkunftsteile einer Veräusserung (`PortfolioDisposal.costParts`): eine Liste oder eine Ansicht eines
/// `PortfolioCostPartPool` (gerechnet beim ersten Auslesen) — wie `List<PortfolioCostPart>` (Android).
struct PortfolioCostPartList: RandomAccessCollection, ExpressibleByArrayLiteral, Equatable, Sendable {
    private enum Storage: Sendable {
        case parts([PortfolioCostPart])
        case view(PortfolioScaledParts)
        /// FIFO-Veräusserung: Einträge, ausgelesen beim ersten Zugriff (PortfolioFifoParts.swift).
        case fifo(PortfolioFifoParts)
    }

    /// Bis zu so vielen Teilen rechnen `weightedSum`/`anyTime` Teil für Teil (exakt wie die Teile selbst).
    static let exactMax = 64

    private let storage: Storage

    init(_ parts: [PortfolioCostPart] = []) {
        storage = .parts(parts)
    }

    init(arrayLiteral elements: PortfolioCostPart...) {
        storage = .parts(elements)
    }

    init(view: PortfolioScaledParts) {
        storage = .view(view)
    }

    /// Herkunftsteile einer FIFO-Veräusserung aus ihren Einträgen (Summe `total`); nil-Einstand: keine Teile.
    init(fifo entries: [PortfolioFifo.Entry], total: Decimal?) {
        guard let total, !entries.isEmpty else {
            storage = .parts([])
            return
        }
        storage = .fifo(PortfolioFifoParts(entries: entries, total: total))
    }

    var startIndex: Int { 0 }

    var endIndex: Int {
        switch storage {
        case .parts(let parts): return parts.count
        case .view(let view): return view.len
        case .fifo(let fifo): return fifo.items.count
        }
    }

    subscript(position: Int) -> PortfolioCostPart { items[position] }

    /// Alle Teile als Liste (eine Ansicht wird dabei einmal gerechnet).
    var items: [PortfolioCostPart] {
        switch storage {
        case .parts(let parts): return parts
        case .view(let view): return view.items
        case .fifo(let fifo): return fifo.items
        }
    }

    func makeIterator() -> IndexingIterator<[PortfolioCostPart]> { items.makeIterator() }

    static func == (lhs: PortfolioCostPartList, rhs: PortfolioCostPartList) -> Bool {
        lhs.count == rhs.count && lhs.items == rhs.items
    }

    /// Grosse Ansicht (über `exactMax` Teile), sonst nil.
    private var largeView: PortfolioScaledParts? {
        if case .view(let view) = storage, view.len > Self.exactMax { return view }
        return nil
    }

    /// Σ Teil × `weight` (Zeitpunkt des Teils; Produkte auf 34 Stellen); nil, wenn ein Gewicht fehlt. Kleine Listen
    /// Teil für Teil; eine grosse Ansicht aus der laufenden Summe ihres Speichers in O(1) — gleich bis auf die Rundung
    /// der einzelnen Teile. Wie Android `weightedSum`.
    func weightedSum(_ weight: PortfolioPartWeight) -> Decimal? {
        if let view = largeView { return view.store.weightedSum(view, weight) }
        var sum: Decimal = 0
        for p in self {
            guard let w = weight.of(p.time) else { return nil }
            sum += PortfolioDecimal.multiply(p.usdt, w)
        }
        return sum
    }

    /// Erfüllt ein Teil `test`? Grosse Ansichten ohne Auslesen.
    func anyTime(_ test: PortfolioPartTest) -> Bool {
        if let view = largeView { return view.store.anyTime(view, test) }
        return contains { test.of($0.time) }
    }

    /// Frühester und spätester Zeitpunkt der Teile (leer = keine). Ansichten ohne Auslesen.
    var timeBounds: [Int64] {
        switch storage {
        case .parts(let parts):
            guard let first = parts.first else { return [] }
            var lo = first.time
            var hi = first.time
            for p in parts {
                lo = Swift.min(lo, p.time)
                hi = Swift.max(hi, p.time)
            }
            return [lo, hi]
        case .view(let view):
            return view.len == 0 ? [] : view.store.timeBounds(view.len)
        case .fifo:
            let all = items
            guard let first = all.first else { return [] }
            return [all.reduce(first.time) { Swift.min($0, $1.time) }, all.reduce(first.time) { Swift.max($0, $1.time) }]
        }
    }
}

/// Abgegebene Teile eines `PortfolioCostPartPool.take`: die ersten `len` Teile von `store` nach `takes` Abgängen mit
/// den Zugängen vor `seq`, davon der Anteil `removed` ÷ `total` (gleich: alle). Gerechnet beim ersten Auslesen, dann
/// behalten.
final class PortfolioScaledParts: @unchecked Sendable {
    let store: PortfolioPartStore
    let len: Int
    let takes: Int
    let seq: Int
    let removed: Decimal
    let total: Decimal
    private let lock = NSLock()
    private var cached: [PortfolioCostPart]?

    init(store: PortfolioPartStore, len: Int, takes: Int, seq: Int, removed: Decimal, total: Decimal) {
        self.store = store
        self.len = len
        self.takes = takes
        self.seq = seq
        self.removed = removed
        self.total = total
    }

    var items: [PortfolioCostPart] {
        lock.withLock {
            if let cached { return cached }
            let parts = store.read(len: len, takes: takes, seq: seq, removed: removed, total: total)
            cached = parts
            return parts
        }
    }
}
