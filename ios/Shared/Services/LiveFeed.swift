import Foundation

// Live-Kurse per WebSocket: reine Logik — Börsen, Abo-Nachrichten, Parser, Planung
// der Verbindungen, Puffer und Regeln. Wie `LiveFeed.kt`; gemeinsame Testfälle in
// `Tests/Parity/live_feed.json`. Die Verbindungen hält `App/Services/LivePriceStream.swift`
// (nur App, nie Widgets). Liegt in Shared, weil `PriceRefresher` die Regeln braucht.

/// Ein Kurs aus dem Datenstrom. `price` / `change24h` nil = in dieser Nachricht nicht enthalten
/// (Bybit-Futures schicken nach dem ersten Stand nur geänderte Felder). `change24h` in Prozent,
/// nur gleitende 24 h. `time` in ms seit 1970; nil = Empfangszeit nehmen.
struct LiveTick: Equatable, Sendable {
    var symbol: String
    var price: Double?
    var change24h: Double?
    var time: Int64?
}

/// Letzter bekannter Live-Stand eines Paars (Watch-Id). `time` = Zeit des Takts mit diesem Kurs (Empfang bzw.
/// Ereigniszeit, gilt als Abrufzeit). `tradeTime` = belegte Handelszeit: nur gesetzt, wenn der Strom selbst einen
/// Kurswechsel gezeigt hat (zweiter Takt mit anderem Kurs) — der erste Takt nach dem Verbinden ist nur eine
/// Momentaufnahme (Binance «E», Bybit/OKX «ts»), kein Handel; nil = keine Handelszeit bekannt. Wie Android.
struct LiveQuote: Equatable, Sendable {
    var price: Double
    var change24h: Double?
    var time: Int64
    var tradeTime: Int64? = nil
}

/// Paar der sichtbaren Merkliste, soweit der Datenstrom es braucht.
struct LivePair: Equatable, Hashable, Sendable {
    var watchId: Int64
    var exchangeKey: String
    var exchangeName: String
    var pairId: String?
    var base: String
    var quote: String
    /// «NONE», «PERPETUAL», … (wie der Name von `FuturesContractType` in Kotlin).
    var contract: String
    /// Wird nicht mehr gehandelt: kein Abo.
    var notTraded = false
}

/// Paar eines Eintrags, wie es abonniert bzw. gespeichert ist (Börse, Basis, Quote, Kontrakt, Kennung) — wie
/// `PairIdentity` (Android). Ohne Watch-Id, Namen und «nicht gehandelt».
struct LivePairKey: Hashable, Sendable {
    var exchangeKey: String
    var base: String
    var quote: String
    /// «NONE», «PERPETUAL», … (Name von `FuturesContractType` in Kotlin).
    var contract: String
    var pairId: String?
}

extension LivePair {
    var key: LivePairKey {
        LivePairKey(exchangeKey: exchangeKey, base: base, quote: quote, contract: contract, pairId: pairId)
    }
}

/// Eine Verbindung: Börse, Nummer, Symbole (sortiert).
struct LiveConnectionSpec: Equatable, Sendable {
    var exchange: LiveExchange
    var index: Int
    var symbols: [String]

    var key: String { "\(exchange.rawValue)#\(index)" }
}

/// Ergebnis von `LivePlanner.plan`.
struct LivePlan: Equatable, Sendable {
    var connections: [LiveConnectionSpec]
    /// Je Börse: Symbol → Watch-Ids.
    var watchIds: [LiveExchange: [String: [Int64]]]

    var isEmpty: Bool { connections.isEmpty }
}

enum LivePlanner {
    /// Mehr Verbindungen je Börse öffnet die App nicht; die übrigen Paare bleiben bei REST.
    static let maxConnectionsPerExchange = 3

    /// Welche Verbindungen für die sichtbaren Paare? Ohne Börsen ohne WebSocket, nicht mehr
    /// gehandelte Paare und pausierte Börsen (`ExchangeBackoff`).
    static func plan(_ pairs: [LivePair], pausedExchangeKeys: Set<String> = []) -> LivePlan {
        var ids: [LiveExchange: [String: [Int64]]] = [:]
        for pair in pairs {
            guard !pair.notTraded, !pausedExchangeKeys.contains(pair.exchangeKey),
                  let exchange = LiveExchange.from(exchangeKey: pair.exchangeKey),
                  let symbol = exchange.symbol(for: pair) else { continue }
            ids[exchange, default: [:]][symbol, default: []].append(pair.watchId)
        }
        var connections: [LiveConnectionSpec] = []
        var kept: [LiveExchange: [String: [Int64]]] = [:]
        for exchange in LiveExchange.allCases {
            guard let bySymbol = ids[exchange] else { continue }
            let sorted = bySymbol.keys.sorted()
            var subscribed = Set<String>()
            var index = 0
            var start = 0
            while start < sorted.count && index < maxConnectionsPerExchange {
                let chunk = Array(sorted[start..<min(start + exchange.maxSymbolsPerConnection, sorted.count)])
                connections.append(LiveConnectionSpec(exchange: exchange, index: index, symbols: chunk))
                subscribed.formUnion(chunk)
                index += 1
                start += exchange.maxSymbolsPerConnection
            }
            kept[exchange] = bySymbol.filter { subscribed.contains($0.key) }
        }
        return LivePlan(connections: connections, watchIds: kept)
    }
}

/// Neuer Verbindungsversuch nach einem Abbruch: 1, 2, 4 … höchstens 60 s; abgewiesen (429/418) 5 min.
enum LiveBackoff {
    static let maxMillis: Int64 = 60_000
    static let rateLimitedMillis: Int64 = 300_000

    /// Erst eine Verbindung, die so lange hielt und Kurse brachte, setzt die Pause zurück. Sonst
    /// verbände eine Börse, die nach dem ersten Stand trennt, jede Sekunde neu.
    static let stableMillis: Int64 = 60_000

    /// Streuung der Pause (±20 %), damit nicht alle Verbindungen im selben Takt neu verbinden.
    static let jitter = 0.2

    /// Obergrenze des Zählers (die Pause wächst ab 6 ohnehin nicht mehr).
    private static let maxAttempt = 100

    /// Grundpause ohne Streuung (gemeinsame Testfälle).
    static func delayMillis(attempt: Int, rateLimited: Bool) -> Int64 {
        if rateLimited { return rateLimitedMillis }
        let step = min(max(attempt, 0), 6)
        return min(maxMillis, Int64(1_000) << Int64(step))
    }

    /// Zähler für den nächsten Versuch: 0 nur, wenn die Verbindung Kurse brachte (`receivedData`) und
    /// mindestens `stableMillis` bestand (`connectedMillis` = Dauer seit dem Verbindungsaufbau).
    static func nextAttempt(_ attempt: Int, receivedData: Bool, connectedMillis: Int64) -> Int {
        receivedData && connectedMillis >= stableMillis ? 0 : min(attempt, maxAttempt) + 1
    }

    /// Pause vor dem nächsten Versuch: Grundpause ±`jitter`, `random` in 0…1 (0,5 = ohne Streuung).
    /// Abgewiesen (429/418): fest 5 min, nie kürzer als von der Börse verlangt.
    static func reconnectDelayMillis(attempt: Int, rateLimited: Bool, random: Double) -> Int64 {
        let base = delayMillis(attempt: attempt, rateLimited: rateLimited)
        if rateLimited { return base }
        let r = random.isFinite ? min(max(random, 0), 1) : 0.5
        return Int64((Double(base) * (1 + jitter * (2 * r - 1))).rounded())
    }
}

/// Regeln — wie `LiveRules` (Kotlin).
enum LiveRules {
    /// Zeit eines neuen Kurses: die der Börse, ausser sie fehlt oder liegt mehr als 5 Minuten in der Zukunft (falsch
    /// gelesen, Ortszeit als Epoche) – dann jetzt. Wie die REST-Abfrage (`Exchange.markFetched`): eine Zeit aus der Zukunft
    /// liesse einen stehenden Kurs lange frisch aussehen. Wie `LiveRules.tickTime` (Android).
    static func tickTime(_ reported: Int64?, now: Int64) -> Int64 {
        guard let reported, reported > 0, reported <= now + Exchange.exchangeTimeFutureToleranceMillis else { return now }
        return reported
    }

    static let uiIntervalMillis: Int64 = 500
    static let dbIntervalMillis: Int64 = 10_000
    static let widgetIntervalMillis: Int64 = 60_000
    /// So lange gilt ein Paar nach dem letzten Kurs als live; danach wieder REST.
    static let freshMillis: Int64 = 30_000
    /// Kein Lebenszeichen der Verbindung so lange (Börsen mit Text-Ping): neu verbinden.
    static let staleConnectionMillis: Int64 = 60_000
    /// Grösste Nachricht des Stroms (1 MiB, `maximumMessageSize`); grössere trennen die Verbindung — wie Android
    /// `LiveRules.MAX_MESSAGE_BYTES`.
    static let maxMessageBytes = 1 << 20

    /// Veränderung, die mit dem Live-Kurs gilt: die des Stroms nur bei Basis «Letzte 24 Std.»,
    /// passendem Stempel und Börse mit gleitendem Wert — sonst bleibt die bisherige.
    static func chooseChange(rollingBasis: Bool, stampCurrent: Bool, exchangeRolling: Bool,
                             live: Double?, existing: Double?) -> Double? {
        if rollingBasis && stampCurrent && exchangeRolling, let live, live.isFinite { return live }
        return existing
    }

    /// Zeit laut Börse (`exchangeTime`), die ein Live-Kurs hinterlässt: die belegte Handelszeit des Stroms
    /// (`LiveQuote.tradeTime`); ohne sie bei gleichem Kurs die bisherige (nil bleibt nil), bei anderem Kurs als dem
    /// gespeicherten nil — gehandelt wurde dann irgendwann seit dem letzten Abruf, die alte Zeit wäre falsch.
    /// Die Takt- bzw. Abrufzeit ist nie Börsenzeit (sie steckt in `lastUpdate`). Wie Android.
    static func exchangeTime(_ quote: LiveQuote, storedPrice: Double?, storedExchangeTime: Int64?) -> Int64? {
        if let trade = quote.tradeTime, trade > 0 { return trade }
        return storedPrice == quote.price ? storedExchangeTime : nil
    }

    static func isFresh(lastTickAt: Int64?, now: Int64) -> Bool {
        guard let lastTickAt, lastTickAt > 0 else { return false }
        let age = now - lastTickAt
        return age >= 0 && age <= freshMillis
    }

    /// Darf die REST-Abfrage dieses Paar auslassen?
    static func skipRest(lastTickAt: Int64?, now: Int64, rollingBasis: Bool, stampUnchanged: Bool,
                         exchangeRolling: Bool) -> Bool {
        rollingBasis && stampUnchanged && exchangeRolling && isFresh(lastTickAt: lastTickAt, now: now)
    }

    /// Paare mit «Live» in der Pille: je Paar der eigene letzte Kurs aus dem Strom frisch (`isFresh`) — nicht je Börse.
    /// Bei vielen Paaren hat eine Börse mehrere Verbindungen; ist eine davon getrennt oder ihr Abo abgelehnt, sind nur
    /// ihre Paare nicht mehr live. Wie Android.
    static func liveIds(lastTickAt: [Int64: Int64], now: Int64) -> Set<Int64> {
        Set(lastTickAt.filter { isFresh(lastTickAt: $0.value, now: now) }.keys)
    }

    /// So lange nach dem Abonnieren ohne einen einzigen Kurs: Abo abgelehnt bzw. ins Leere — Börsen ohne Text-Ping
    /// (Binance, Coinbase) halten die Verbindung mit Ping-Frames trotzdem offen. Dann wie getrennt neu verbinden
    /// (ohne Kurse wächst die Pause, `LiveBackoff.nextAttempt`). Wie Android.
    static let silentSubscriptionMillis: Int64 = 180_000

    /// Verbindung seit `subscribedAt` (0 = noch nicht abonniert) ohne Kurs (`receivedData`) zu lange still?
    static func silentSubscription(subscribedAt: Int64, receivedData: Bool, now: Int64) -> Bool {
        !receivedData && subscribedAt > 0 && now - subscribedAt >= silentSubscriptionMillis
    }

    /// Gehört ein gepufferter Kurs noch zum Eintrag? Nur, wenn das Paar, für das er abonniert war (`subscribed`), das
    /// aktuelle des Eintrags ist (`current`) — nach «Paar bearbeiten» trägt ein Takt des alten Symbols das alte Paar
    /// und wird verworfen, egal welche Zeit die Börse meldet. Ohne Zuordnung (nil): verwerfen. Wie Android.
    static func samePair(subscribed: LivePairKey?, current: LivePairKey) -> Bool {
        subscribed == current
    }
}

/// Puffer der Live-Kurse je Watch-Id — wie `LiveBuffer` (Kotlin).
struct LiveBuffer: Sendable {
    private var latest: [Int64: LiveQuote] = [:]
    private var unsaved: Set<Int64> = []
    private var uiDirty = false

    /// Kurs übernehmen; fehlende Felder (Delta) vom letzten Stand. false = nichts geändert.
    @discardableResult
    mutating func offer(_ watchIds: [Int64], _ tick: LiveTick, now: Int64) -> Bool {
        var changed = false
        for id in watchIds {
            let previous = latest[id]
            guard let price = tick.price ?? previous?.price else { continue }
            // Neue Zeit nur mit geändertem Kurs: Ein Delta ohne lastPrice (z. B. Bybit, nur 24-h-Wert
            // oder Volumen geändert) behält den alten Kurs — und damit dessen Zeit. Ebenso ein Takt
            // mit gleichem Kurs (Binance schickt «c» jede Sekunde mit neuer Ereigniszeit «E»).
            // Sonst wirkte ein stehender Kurs frisch und «veraltet» käme nie. Wie `LiveBuffer.offer` (Android).
            let time: Int64
            // Handelszeit nur mit einem im Strom gesehenen Kurswechsel; der erste Takt (previous nil) belegt keinen
            // Handel — sonst sähe ein seit Stunden stehendes Buch frisch gehandelt aus («Unverändert seit» fiele weg)
            let tradeTime: Int64?
            if let previous, tick.price == nil || tick.price == previous.price {
                time = previous.time
                tradeTime = previous.tradeTime
            } else {
                time = LiveRules.tickTime(tick.time, now: now)
                tradeTime = previous == nil ? nil : time
            }
            let quote = LiveQuote(price: price, change24h: tick.change24h ?? previous?.change24h, time: time,
                                  tradeTime: tradeTime)
            if quote == previous { continue }
            latest[id] = quote
            if previous == nil || previous?.price != quote.price || previous?.change24h != quote.change24h {
                unsaved.insert(id)
            }
            uiDirty = true
            changed = true
        }
        return changed
    }

    /// Stand für die Anzeige, falls sich seit dem letzten Aufruf etwas geändert hat; sonst nil.
    mutating func takeForUi() -> [Int64: LiveQuote]? {
        guard uiDirty else { return nil }
        uiDirty = false
        return latest
    }

    /// Ungespeicherte Kurse; danach gelten sie als gespeichert.
    mutating func takeForDb() -> [Int64: LiveQuote] {
        guard !unsaved.isEmpty else { return [:] }
        var batch: [Int64: LiveQuote] = [:]
        for id in unsaved { if let q = latest[id] { batch[id] = q } }
        unsaved.removeAll()
        return batch
    }

    /// Speichern ging nicht: beim nächsten Mal nochmals.
    mutating func markUnsaved<C: Sequence>(_ ids: C) where C.Element == Int64 {
        for id in ids where latest[id] != nil { unsaved.insert(id) }
    }

    /// Nur noch diese Paare behalten (andere Gruppe gewählt).
    mutating func retain(_ ids: Set<Int64>) {
        let before = latest.count
        latest = latest.filter { ids.contains($0.key) }
        if latest.count != before { uiDirty = true }
        unsaved = unsaved.intersection(ids)
    }

    mutating func clear() {
        if !latest.isEmpty { uiDirty = true }
        latest.removeAll()
        unsaved.removeAll()
    }

    var count: Int { latest.count }
}

extension Watch {
    /// Was der Live-Strom von einem Paar braucht.
    var livePair: LivePair {
        LivePair(watchId: id, exchangeKey: exchangeKey, exchangeName: exchangeName, pairId: pairId,
                 base: baseAsset, quote: quoteAsset,
                 contract: contractType.backupName,
                 // Deaktivierte Paare werden wie nicht gehandelte nie abonniert
                 notTraded: isNotTraded || disabled)
    }

    /// Live-Kurs darübergelegt (nur Anzeige) — wie `LiveOverlay` (Kotlin).
    func withLive(_ quote: LiveQuote?, rollingBasis: Bool) -> Watch {
        // Deaktiviert: letzter gespeicherter Kurs bleibt stehen (ein später Takt ändert nichts)
        guard let quote, !isNotTraded, !disabled else { return self }
        var copy = self
        // «Seit letzter Aktualisierung»: Live-Kurs gegen den zuletzt gespeicherten
        if let stored = lastPrice, stored != quote.price { copy.previousPrice = stored }
        copy.lastPrice = quote.price
        copy.lastUpdate = max(lastUpdate, quote.time)
        copy.lastError = nil
        // Frische wie beim Speichern (`PriceRefresher`, Live): Börsenzeit nur als belegte Handelszeit
        if lastPrice != quote.price { copy.priceChangedAt = quote.time }
        copy.exchangeTime = LiveRules.exchangeTime(quote, storedPrice: lastPrice, storedExchangeTime: exchangeTime)
        copy.change24h = LiveRules.chooseChange(
            rollingBasis: rollingBasis, stampCurrent: true,
            exchangeRolling: LiveExchange.from(exchangeKey: exchangeKey)?.rollingChange == true,
            live: quote.change24h, existing: change24h)
        return copy
    }
}
