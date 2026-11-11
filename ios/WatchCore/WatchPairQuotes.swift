import Foundation

/// Selbst abgefragter Kurs eines Favoriten des iPhones (`WatchPair`, «Wie iPhone»): zuerst bei derselben Börse und
/// Kontraktart wie auf dem iPhone, andere Börsen nur, wenn diese nicht antwortet oder das Paar dort fehlt — Spot bei
/// Binance, Bybit, OKX; Perpetuals bei Binance USDⓈ-M (`fapi`), Bybit linear, OKX Swap. Die Börse im Ergebnis ist die,
/// die wirklich geliefert hat. Andere Kontrakte und Pools fragt die Uhr nicht selbst ab (`WatchPair.selfFetchable`).
/// Wie Android `WatchPairQuotes` (`Parity/watch_quotes.json`, Abschnitte «pairVenues», «pairUrls», «pairFetch»).
enum WatchPairQuotes {
    /// Börse und Kontraktart, die die Uhr selbst abfragen kann; `label` = Name wie auf dem iPhone.
    enum Venue: String, CaseIterable, Sendable {
        case binance = "Binance"
        case bybit = "Bybit"
        case okx = "OKX"
        case binanceFutures = "Binance Futures"
        case bybitFutures = "Bybit Futures"
        case okxFutures = "OKX Futures"

        var label: String { rawValue }
        var perp: Bool { self == .binanceFutures || self == .bybitFutures || self == .okxFutures }
    }

    /// Reihenfolge der Abfrage: Börse des iPhones zuerst (falls die Uhr sie kennt), dann die anderen gleicher Art.
    static func venues(_ pair: WatchPair) -> [Venue] {
        guard pair.selfFetchable else { return [] }
        let kind = Venue.allCases.filter { $0.perp == pair.perp }
        guard let own = kind.first(where: { $0.label.caseInsensitiveCompare(pair.exchange) == .orderedSame }) else {
            return kind
        }
        return [own] + kind.filter { $0 != own }
    }

    /// Fragt die Uhr die Börse des Paars selbst ab (erste in `venues`)? Nein bei Börsen, die sie nicht kennt (Kraken-Spot-
    /// Paar: nur Binance, Bybit, OKX) und bei Paaren, die sie gar nicht selbst lädt — dann sagt «—» nicht «Kraken nicht
    /// erreichbar» (`WatchText.missingReason`). Wie Android.
    static func asksOwnExchange(_ pair: WatchPair) -> Bool {
        venues(pair).first?.label.caseInsensitiveCompare(pair.exchange) == .orderedSame
    }

    /// Ergebnis des letzten eigenen Abrufs eines Paars — für den Grund bei «—» (`missingReason`). Wie Android.
    enum Outcome: String, Sendable {
        /// Eine Börse lieferte einen Kurs.
        case quote = "QUOTE"
        /// Alle gefragten Börsen antworteten, keine führt das Paar.
        case notListed = "NOT_LISTED"
        /// Gefragt, aber mindestens eine Börse antwortete nicht (Netz, Sperre, Frist) und keine lieferte einen Kurs.
        case unreachable = "UNREACHABLE"
    }

    /// Hat eine Börse geantwortet? Ja bei JSON (Objekt oder Liste) — auch «Paar unbekannt», z. B. Binance HTTP 400
    /// `{"code":-1121}`, Bybit `retCode` 10001, OKX `code` 51001. Nein ohne Antwort (nil) oder bei anderem Inhalt (Sperr-
    /// oder Anmeldeseite eines WLANs): daraus folgt nicht, dass das Paar fehlt. Wie Android.
    static func answered(_ data: Data?) -> Bool {
        guard let data, let root = try? JSONSerialization.jsonObject(with: data) else { return false }
        return root is [String: Any] || root is [Any]
    }

    /// Ergebnis ohne Kurs aus den Antworten der gefragten Börsen (`answered` je Börse): alle → `.notListed`, sonst
    /// `.unreachable`. Wie Android `outcomeWithoutQuote`.
    static func outcomeWithoutQuote(_ answers: [Bool]) -> Outcome {
        !answers.isEmpty && answers.allSatisfy { $0 } ? .notListed : .unreachable
    }

    /// Grund für «—» eines Paars (`missingReason`). Wie Android `Missing`.
    enum Missing: String, Sendable {
        /// Pool, Laufzeit-Kontrakt, unbekannte Kontrakt-Börse: «die Uhr kann dieses Paar nicht abrufen».
        case phoneOnly = "PHONE_ONLY"
        /// «Kurse von <Börse> erhält die Uhr nur vom iPhone».
        case viaPhone = "VIA_PHONE"
        /// «<Börse> nicht erreichbar» (eigene Börse des Paars, noch kein eigenes Ergebnis).
        case ownUnreachable = "OWN_UNREACHABLE"
        /// «Börsen gerade nicht erreichbar».
        case unreachable = "UNREACHABLE"

        /// Sichtbarer Kurzgrund in der Zeile der Uhr-App: «nur vom iPhone» (true) oder «nicht erreichbar» (false).
        var viaPhone: Bool { self == .phoneOnly || self == .viaPhone }
    }

    /// Grund für «—»: `selfFetchable` nein → `.phoneOnly`; die Uhr hat gefragt (`outcome`) und keine gefragte Börse
    /// führt das Paar → `.viaPhone`; gefragt, aber keine antwortete → `.unreachable`; noch kein eigenes Ergebnis → wie
    /// bisher: eigene Börse (`asksOwnExchange`) → `.ownUnreachable`, sonst `.viaPhone`. Wie Android
    /// (`watch_pairs.json` › «missing»).
    static func missingReason(_ pair: WatchPair, outcome: Outcome?) -> Missing {
        guard pair.selfFetchable else { return .phoneOnly }
        switch outcome {
        case .notListed: return .viaPhone
        case .unreachable: return .unreachable
        case .quote, nil: return asksOwnExchange(pair) ? .ownUnreachable : .viaPhone
        }
    }

    /// Abfrage eines Paars bei `venue` (Kürzel nur `[A-Z0-9]`, siehe `WatchPair.of`).
    static func url(_ venue: Venue, _ pair: WatchPair) -> URL? {
        let symbol = pair.base + pair.quote
        switch venue {
        case .binance: return URL(string: "https://api.binance.com/api/v3/ticker/24hr?symbol=\(symbol)")
        case .binanceFutures: return URL(string: "https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=\(symbol)")
        case .bybit: return URL(string: "https://api.bybit.com/v5/market/tickers?category=spot&symbol=\(symbol)")
        case .bybitFutures: return URL(string: "https://api.bybit.com/v5/market/tickers?category=linear&symbol=\(symbol)")
        case .okx: return URL(string: "https://www.okx.com/api/v5/market/ticker?instId=\(pair.base)-\(pair.quote)")
        case .okxFutures:
            return URL(string: "https://www.okx.com/api/v5/market/ticker?instId=\(pair.base)-\(pair.quote)-SWAP")
        }
    }

    /// Antwort von `venue` für `pair`: Kurs > 0 unter dem Schlüssel des Paars, Börse = `Venue.label`; sonst nil.
    static func parse(_ venue: Venue, _ data: Data, _ pair: WatchPair) -> WatchQuote? {
        let symbol = pair.base + pair.quote
        guard let root = try? JSONSerialization.jsonObject(with: data) else { return nil }
        switch venue {
        case .binance, .binanceFutures:
            // Ein Paar: Objekt; zur Sicherheit auch ein Array
            let rows = (root as? [[String: Any]]) ?? [(root as? [String: Any])].compactMap { $0 }
            guard let o = rows.first(where: { $0["symbol"] as? String == symbol }) else { return nil }
            return quote(pair, venue, o["lastPrice"], change: number(o["priceChangePercent"]))
        case .bybit, .bybitFutures:
            let list = ((root as? [String: Any])?["result"] as? [String: Any])?["list"] as? [[String: Any]] ?? []
            guard let o = list.first(where: { $0["symbol"] as? String == symbol }) else { return nil }
            return quote(pair, venue, o["lastPrice"], change: number(o["price24hPcnt"]).map { $0 * 100 })
        case .okx, .okxFutures:
            let inst = "\(pair.base)-\(pair.quote)" + (venue == .okxFutures ? "-SWAP" : "")
            let list = (root as? [String: Any])?["data"] as? [[String: Any]] ?? []
            guard let o = list.first(where: { $0["instId"] as? String == inst }) else { return nil }
            let last = number(o["last"])
            let open = number(o["open24h"]).flatMap { $0 > 0 ? $0 : nil }
            let change: Double? = if let last, let open { (last / open - 1) * 100 } else { nil }
            return quote(pair, venue, o["last"], change: change)
        }
    }

    /// Paar eines Favoriten: nacheinander bei den Börsen aus `venues` (die des iPhones zuerst), bis eine einen Kurs
    /// liefert; Fehler (nil) oder fehlendes Paar → die nächste. Keine → nil. Ergebnis an `record`: bis eine Antwort da
    /// ist «nicht erreichbar» (endet der Abruf an der Frist, stimmt das), dann `Outcome`. Wie Android `pairQuote`.
    static func fetch(_ pair: WatchPair, get: @escaping @Sendable (URL) async -> Data?,
                      record: @Sendable (String, Outcome) -> Void = { WatchPairOutcomes.record($0, $1) }) async -> WatchQuote? {
        let all = venues(pair)
        guard !all.isEmpty else { return nil }
        record(pair.key, .unreachable)
        var answers: [Bool] = []
        for venue in all {
            guard !Task.isCancelled else { return nil }
            var data: Data?
            if let url = url(venue, pair) { data = await get(url) }
            if let data, let q = parse(venue, data, pair) {
                record(pair.key, .quote)
                return q
            }
            answers.append(answered(data))
        }
        record(pair.key, outcomeWithoutQuote(answers))
        return nil
    }

    private static func quote(_ pair: WatchPair, _ venue: Venue, _ raw: Any?, change: Double?) -> WatchQuote? {
        guard let price = number(raw), price > 0 else { return nil }
        return WatchQuote(symbol: pair.key, price: price, change24h: change, source: venue.label)
    }

    private static func number(_ raw: Any?) -> Double? {
        guard let text = raw as? String, let v = Double(text), v.isFinite else { return nil }
        return v
    }
}
