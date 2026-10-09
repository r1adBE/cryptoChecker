import Foundation

/// Erkennt Futures auf etwas, das kein Krypto-Token ist — Aktien, ETFs, Rohstoffe (Gold, Öl …),
/// Devisen und Firmen vor dem Börsengang («TradFi»). Jede Börse kennzeichnet das anders; die Regeln
/// stammen aus ihren öffentlichen Paarlisten (Stand Oktober 2026). Wie `TradFi.kt`.
enum TradFi {

    /// Vertragsart der Binance-Kontrakte auf Aktien, Rohstoffe, Devisen und Pre-IPO.
    static let binanceTradFiPerpetual = "TRADIFI_PERPETUAL"

    /// Binance Futures (`/fapi/v1/exchangeInfo`): Vertragsart «TRADIFI_PERPETUAL» oder Kategorie
    /// «TradFi» in `underlyingSubType` (z. B. ["TradFi"] oder ["Pre-IPO", "TradFi"]).
    static func binance(contractType: String, subTypes: [String]) -> Bool {
        contractType == binanceTradFiPerpetual || subTypes.contains { $0.caseInsensitiveCompare("TradFi") == .orderedSame }
    }

    /// Bybit (`/v5/market/instruments-info`, linear): `symbolType` stock, ETF, commodity oder forex.
    static func bybit(symbolType: String) -> Bool { bybitTypes.contains(symbolType.lowercased()) }

    private static let bybitTypes: Set<String> = ["stock", "etf", "commodity", "forex"]

    /// OKX (`/api/v5/public/instruments`, SWAP): `instCategory` gesetzt und nicht 1 (Krypto); 3 = Aktien.
    static func okx(instCategory: String) -> Bool { !instCategory.isEmpty && instCategory != "1" }

    /// MEXC (`/api/v1/contract/detail`): Bereich «…-tradfi», «…-Stock», «…-metals», «…-Commodities» oder
    /// «…-forex» in `conceptPlate`, oder `type` 2 (Aktien und ETFs; Gold hat Typ 1).
    static func mexc(conceptPlates: [String], type: Int) -> Bool {
        type == 2 || conceptPlates.contains { plate in mexcZones.contains { plate.range(of: $0, options: .caseInsensitive) != nil } }
    }

    private static let mexcZones = ["tradfi", "stock", "metals", "commodit", "forex"]

    /// Bitget (`/api/v2/mix/market/contracts`): `isRwa` «YES»/«NO» je Kontrakt (Aktien-, Rohstoff- und Index-Futures, «RWA»; Live-Daten: rund 335 von 801).
    static func bitget(isRwa: String) -> Bool {
        isRwa.caseInsensitiveCompare("YES") == .orderedSame || isRwa.caseInsensitiveCompare("true") == .orderedSame
    }

    /// Texte eines Felds mit Liste; fehlt es oder ist es keine Liste: leer.
    static func strings(_ object: JObject, _ key: String) -> [String] {
        object.optArray(key)?.raw.compactMap { $0 as? String } ?? []
    }
}
