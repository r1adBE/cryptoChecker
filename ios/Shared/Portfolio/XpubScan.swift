import Foundation

/// Zwischenspeicher eines beobachteten xpub/ypub/zpub — wie `XpubCache` (Kotlin): abgeleitete Adressen je Kette
/// (0 = Empfang, 1 = Wechselgeld, Index = Position) und die benutzten Indizes (mindestens eine Transaktion). `fullScan` =
/// letzte vollständige Suche (ms, 0 = nie). `script` = gefundener Adresstyp eines xpub (Segwit-Konto mit xpub,
/// `XpubScan.scripts`); nil = noch keiner mit Verlauf (bis Build 46 immer nil: P2PKH). `emptyReceive`/`emptyChange` (seit
/// Build 102) = benutzte Indizes ohne Guthaben beim letzten Abruf: QUICK und BACKGROUND fragen sie nicht erneut (nur
/// FULL), sonst scheitert ein xpub mit vielen benutzten Adressen an `XpubScan.maxRequests`. `limitedFallback` (seit
/// Build 102) = die tägliche vollständige Suche war zu gross und wurde durch die schnelle ersetzt; bleibt, bis wieder
/// eine vollständige gelingt (Hinweis in der Adresszeile). Gespeichert in `watched_addresses.json` (Feld `xpub` der
/// Adresse).
struct XpubCache: Codable, Equatable, Sendable {
    var receive: [String] = []
    var change: [String] = []
    var usedReceive: [Int] = []
    var usedChange: [Int] = []
    var fullScan: Int64 = 0
    var script: Bip32.Script? = nil
    var emptyReceive: [Int] = []
    var emptyChange: [Int] = []
    var limitedFallback = false

    init(receive: [String] = [], change: [String] = [], usedReceive: [Int] = [], usedChange: [Int] = [],
         fullScan: Int64 = 0, script: Bip32.Script? = nil, emptyReceive: [Int] = [], emptyChange: [Int] = [],
         limitedFallback: Bool = false) {
        self.receive = receive
        self.change = change
        self.usedReceive = usedReceive
        self.usedChange = usedChange
        self.fullScan = fullScan
        self.script = script
        self.emptyReceive = emptyReceive
        self.emptyChange = emptyChange
        self.limitedFallback = limitedFallback
    }

    func addresses(_ chain: Int) -> [String] { chain == 0 ? receive : change }
    func used(_ chain: Int) -> [Int] { chain == 0 ? usedReceive : usedChange }
    func empty(_ chain: Int) -> [Int] { chain == 0 ? emptyReceive : emptyChange }

    /// Anzahl benutzter Adressen (Anzeige).
    var usedCount: Int { usedReceive.count + usedChange.count }

    private enum CodingKeys: String, CodingKey { case r, c, ru, cu, f, s, re, ce, lf }

    /// Gleiche Kurzform wie Android (`{"r":[…],"c":[…],"ru":[…],"cu":[…],"f":…,"s":"P2WPKH","re":[…],"ce":[…],"lf":true}`,
    /// «s», «re», «ce» nur wenn vorhanden, «lf» nur wenn true); unlesbare Teile = leer, unbekannter Typ = keiner.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let receive = (try? c.decodeIfPresent([String].self, forKey: .r)) ?? []
        let change = (try? c.decodeIfPresent([String].self, forKey: .c)) ?? []
        self.receive = receive.contains(where: \.isEmpty) ? [] : receive
        self.change = change.contains(where: \.isEmpty) ? [] : change
        func ints(_ key: CodingKeys, _ max: Int) -> [Int] {
            let raw = (try? c.decodeIfPresent([Int].self, forKey: key)) ?? []
            return Array(Set(raw.filter { $0 >= 0 && $0 < max })).sorted()
        }
        usedReceive = ints(.ru, self.receive.count)
        usedChange = ints(.cu, self.change.count)
        fullScan = max(0, (try? c.decodeIfPresent(Int64.self, forKey: .f)) ?? 0)
        script = ((try? c.decodeIfPresent(String.self, forKey: .s)) ?? nil).flatMap { Bip32.Script(code: $0) }
        // Nur benutzte Indizes (fehlt «re»/«ce»: ältere Fassung, die nächste Suche fragt alle)
        let usedR = Set(usedReceive), usedC = Set(usedChange)
        emptyReceive = ints(.re, self.receive.count).filter { usedR.contains($0) }
        emptyChange = ints(.ce, self.change.count).filter { usedC.contains($0) }
        limitedFallback = ((try? c.decodeIfPresent(Bool.self, forKey: .lf)) ?? nil) ?? false
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(receive, forKey: .r)
        try c.encode(change, forKey: .c)
        try c.encode(usedReceive, forKey: .ru)
        try c.encode(usedChange, forKey: .cu)
        try c.encode(fullScan, forKey: .f)
        try c.encodeIfPresent(script?.code, forKey: .s)
        if !emptyReceive.isEmpty { try c.encode(emptyReceive, forKey: .re) }
        if !emptyChange.isEmpty { try c.encode(emptyChange, forKey: .ce) }
        if limitedFallback { try c.encode(true, forKey: .lf) }
    }
}

/// Suche über die Adressen eines xpub nach der Gap-Regel (BIP44: 20 unbenutzte Adressen in Folge beenden eine Kette) —
/// ohne Netz, schrittweise: `next` nennt die nächste Adresse (Kette, Index), `report` nimmt die Antwort (Anzahl
/// Transaktionen, Satoshi). Reine Logik, gleich wie `XpubScan.kt` (Arten, Grenzen und Rückfall auf die schnelle Suche
/// bei zu grosser vollständiger dort beschrieben); der Abruf steht in `WatchedAddressSource`.
final class XpubScan {

    enum Mode: String, Sendable { case FULL, QUICK, BACKGROUND }

    struct Target: Equatable, Sendable {
        let chain: Int
        let index: Int
    }

    struct TooMany: LocalizedError {
        var errorDescription: String? { "Zu viele Adressen" }
    }

    /// Gap-Limit (BIP44).
    static let gap = 20
    /// QUICK: so viele Adressen nach der letzten benutzten je Kette — das volle Gap-Limit (BIP44). Mit nur 5 blieb eine
    /// Zahlung an eine weiter hinten abgeleitete Adresse bis zur täglichen vollständigen Suche unsichtbar. Wie Android.
    static let lookahead = gap
    /// BACKGROUND: so viele Adressen nach der letzten benutzten je Kette (Empfang und Wechselgeld) — fängt das
    /// Wechselgeld einer Ausgabe auf der nächsten neuen Adresse, kostet je xpub nur vier Anfragen mehr. Wie Android.
    static let backgroundLookahead = 2
    /// Vollständige Suche höchstens einmal täglich.
    static let fullScanMillis: Int64 = 24 * 60 * 60_000
    /// Grenze je Aktualisierung (schont die Anbieter).
    static let maxRequests = 400
    /// Gespeicherter Fehler nach `TooMany` (sprachunabhängig, Anzeige «Zu viele Adressen»). Wie Android.
    static let tooManyMarker = "XPUB_TOO_MANY"

    private struct ChainState {
        var queue: [Int]
        var queuePos = 0
        var used: Set<Int>
        /// Benutzte ohne Guthaben (beim letzten Abruf).
        var empty: Set<Int> = []
        var gap: Bool
        var nextIndex = 0
        var unusedRun = 0
        /// Letzte bekannte benutzte vor der Suche (QUICK: Vorausblick dahinter).
        let lastUsedBefore: Int
        var lastQueried = -1
    }

    let mode: Mode
    private var states: [ChainState]
    private let old: XpubCache
    private var current = 0
    private var pending: Target?

    /// Gestellte Anfragen.
    private(set) var requests = 0
    /// Summe der Satoshi aller gefragten Adressen.
    private(set) var totalSats: Int64 = 0

    /// Benutzte Adressen nach der Suche (bekannte und neu gefundene).
    var usedCount: Int { states.reduce(0) { $0 + $1.used.count } }

    /// Nach der Suche: hinter der neusten benutzten Adresse jeder Kette wurde mindestens eine unbenutzte gefragt — also
    /// keine neue Adresse übersehen. Nur `.BACKGROUND` kann unvollständig sein (Vorausblick ohne Gap-Suche). Wie Android.
    var complete: Bool {
        mode != .BACKGROUND || states.allSatisfy { ($0.used.max() ?? -1) < $0.lastUsedBefore + Self.backgroundLookahead }
    }

    init(mode: Mode, cache: XpubCache?) {
        self.mode = mode
        let old = cache ?? XpubCache()
        self.old = old
        states = (0...1).map { chain in
            let known = old.used(chain).sorted()
            let last = known.last ?? -1
            // Zuletzt leere benutzte Adressen nicht erneut fragen (nur FULL prüft wieder alle)
            let empty = Set(old.empty(chain)).intersection(known)
            let ask = known.filter { !empty.contains($0) }
            switch mode {
            case .FULL:
                return ChainState(queue: [], used: [], gap: true, lastUsedBefore: -1)
            case .QUICK:
                return ChainState(queue: ask + Array((last + 1)...(last + XpubScan.lookahead)), used: Set(known),
                                  empty: empty, gap: false, lastUsedBefore: last)
            case .BACKGROUND:
                // Mit kurzem Vorausblick: nach einer Ausgabe liegt das Wechselgeld auf einer NEUEN Wechselgeld-Adresse
                return ChainState(queue: ask + Array((last + 1)...(last + XpubScan.backgroundLookahead)), used: Set(known),
                                  empty: empty, gap: false, lastUsedBefore: last)
            }
        }
    }

    /// Nächste Adresse oder nil = fertig. Erst `report`, dann wieder `next`.
    func next() throws -> Target? {
        if let pending { return pending }
        while current < states.count {
            if states[current].queuePos < states[current].queue.count {
                let index = states[current].queue[states[current].queuePos]
                states[current].queuePos += 1
                return try issue(Target(chain: current, index: index))
            }
            if !states[current].gap && mode == .QUICK {
                // Vorausblick fertig: neue benutzte dahinter → weiter bis `gap` unbenutzte in Folge
                let newest = states[current].used.max() ?? -1
                if newest > states[current].lastUsedBefore {
                    states[current].gap = true
                    states[current].nextIndex = states[current].lastQueried + 1
                    states[current].unusedRun = states[current].lastQueried - newest
                }
            }
            if states[current].gap && states[current].unusedRun < Self.gap {
                return try issue(Target(chain: current, index: states[current].nextIndex))
            }
            current += 1
        }
        return nil
    }

    private func issue(_ target: Target) throws -> Target {
        pending = target
        requests += 1
        if requests > Self.maxRequests { throw TooMany() }
        return target
    }

    /// Antwort zu `target` (= letztes `next`): benutzt, wenn `txCount` > 0.
    func report(_ target: Target, txCount: Int, sats: Int64) {
        guard target == pending else { return }
        pending = nil
        let c = target.chain
        totalSats += max(0, sats)
        if target.index > states[c].lastQueried { states[c].lastQueried = target.index }
        if txCount > 0 { states[c].used.insert(target.index) }
        if txCount > 0 && sats <= 0 { states[c].empty.insert(target.index) } else { states[c].empty.remove(target.index) }
        if states[c].gap && target.index == states[c].nextIndex {
            states[c].unusedRun = txCount > 0 ? 0 : states[c].unusedRun + 1
            states[c].nextIndex += 1
        }
    }

    /// Neuer Zwischenspeicher nach der Suche: Adressen je Kette (vom Aufrufer abgeleitet, mindestens bis zum höchsten
    /// gefragten Index), benutzte Indizes, Zeit der vollständigen Suche (`now` bei `.FULL`). `limitedFallback`: nach
    /// `.FULL` false, sonst wie zuvor (den Rückfall setzt der Aufrufer). Wie Android.
    func cache(receive: [String], change: [String], now: Int64, script: Bip32.Script? = nil) -> XpubCache {
        XpubCache(receive: receive, change: change,
                  usedReceive: states[0].used.filter { $0 < receive.count }.sorted(),
                  usedChange: states[1].used.filter { $0 < change.count }.sorted(),
                  fullScan: mode == .FULL ? now : old.fullScan, script: script,
                  emptyReceive: states[0].empty.filter { $0 < receive.count && states[0].used.contains($0) }.sorted(),
                  emptyChange: states[1].empty.filter { $0 < change.count && states[1].used.contains($0) }.sorted(),
                  limitedFallback: mode != .FULL && old.limitedFallback)
    }

    /// Adresstypen der Suche in dieser Reihenfolge — wie Android `scripts`: ypub/zpub/Taproot-Konto nur ihr Typ; xpub mit gemerktem Typ
    /// oder mit benutzten Adressen im Zwischenspeicher bis Build 46 (P2PKH) nur dieser; sonst P2PKH, P2SH-P2WPKH, P2WPKH
    /// (Ledger Live zeigt die xpub auch für Segwit-Konten). `cache` muss zum Schlüssel gehören.
    static func scripts(_ key: Bip32.ExtendedKey, cache: XpubCache?) -> [Bip32.Script] {
        guard key.detectsScript else { return [key.script] }
        if let s = cache?.script { return [s] }
        if let cache, cache.usedCount > 0 { return [.p2pkh] }
        return [.p2pkh, .p2shP2wpkh, .p2wpkh]
    }

    /// Zu merkender Typ nach der Suche über `tried` (je Typ: benutzte Adressen) — wie Android `pickScript`: der erste mit
    /// benutzter Adresse; bei nur einem Typ dieser (ypub/zpub: nil); keiner mit Verlauf = nil (wieder alle prüfen).
    static func pickScript(_ key: Bip32.ExtendedKey, tried: [(script: Bip32.Script, used: Int)]) -> Bip32.Script? {
        guard key.detectsScript else { return nil }
        if let hit = tried.first(where: { $0.used > 0 }) { return hit.script }
        return tried.count == 1 ? tried[0].script : nil
    }

    /// Hintergrund-Ergebnis (`sats`) übernehmen? Vollständig (`complete`): ja. Sonst nur, wenn es nicht unter dem
    /// gespeicherten Guthaben `lastBalance` (BTC) liegt — eine zu tiefe, unvollständige Summe würde sonst als Abfluss
    /// beobachtet und könnte Wert-Alarme auslösen; ohne gespeichertes Guthaben nie. Nicht übernommen = bleibt fällig, die
    /// nächste Suche im Vordergrund klärt es. Wie Android `keepBackground`.
    static func keepBackground(complete: Bool, sats: Int64, lastBalance: String?) -> Bool {
        if complete { return true }
        guard let last = PortfolioDecimal.parse(lastBalance),
              let now = PortfolioDecimal.parse(AddressBalance.unitsText(String(max(0, sats)), decimals: AddressChain.BTC.decimals))
        else { return false }
        return now >= last
    }

    /// Art der Suche: Hintergrund nur mit Zwischenspeicher (`.BACKGROUND`, sonst nil = später im Vordergrund);
    /// Vordergrund vollständig ohne Zwischenspeicher, nach einem Tag oder bei zurückgestellter Uhr (`fullDue`) — mit
    /// Zwischenspeicher aber schnell, wenn die vollständige sicher über `maxRequests` käme (`fullEstimate`; sie scheiterte
    /// sonst jeden Tag mit «Zu viele Adressen»). Wie Android `modeFor`.
    static func mode(for cache: XpubCache?, now: Int64, background: Bool) -> Mode? {
        guard let cache, cache.fullScan > 0 else { return background ? nil : .FULL }
        if background { return .BACKGROUND }
        if !fullDue(cache, now: now) { return .QUICK }
        return fullEstimate(cache) > maxRequests ? .QUICK : .FULL
    }

    /// Vollständige Suche fällig (Vordergrund): ohne Zwischenspeicher, nach einem Tag oder bei zurückgestellter Uhr.
    static func fullDue(_ cache: XpubCache?, now: Int64) -> Bool {
        guard let cache, cache.fullScan > 0 else { return true }
        let elapsed = now - cache.fullScan
        return elapsed < 0 || elapsed >= fullScanMillis
    }

    /// Mindestzahl Anfragen einer vollständigen Suche mit diesem Zwischenspeicher: je Kette höchster benutzter Index + 1 +
    /// `gap` (ohne benutzte: `gap`). Benutzte Adressen bleiben benutzt — liegt das über `maxRequests`, scheitert die
    /// vollständige Suche sicher. Wie Android.
    static func fullEstimate(_ cache: XpubCache) -> Int {
        (0...1).reduce(0) { $0 + (cache.used($1).max() ?? -1) + 1 + gap }
    }
}
