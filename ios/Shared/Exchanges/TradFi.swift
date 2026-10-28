import Foundation

/// Merkt Futures, deren Basiswert kein Krypto-Token ist: Aktien, ETFs, Rohstoffe (Gold, Öl …), Devisen und
/// Firmen vor dem Börsengang («TradFi»). Jede Börse markiert das auf ihre Weise; die Regeln beruhen auf
/// ihren öffentlichen Paarlisten (Stand Oktober 2026). Gleich wie `TradFi.kt`.
enum TradFi {

    /// Binance-Vertragsart für Kontrakte auf Aktien, Rohstoffe, Devisen und Pre-IPO.
    static let binanceTradFiPerpetual = "TRADIFI_PERPETUAL"

    private static let bybitTypes: Set<String> = ["etf", "stock", "forex", "commodity"]
    /// Wortteile in MEXC `conceptPlate` («commodit» deckt «Commodities» ab).
    private static let mexcZones = ["tradfi", "stock", "metals", "commodit", "forex"]

    /// Vergleich ohne Rücksicht auf Gross-/Kleinschreibung.
    private static func same(_ a: String, _ b: String) -> Bool { a.caseInsensitiveCompare(b) == .orderedSame }

    /// Binance Futures (`/fapi/v1/exchangeInfo`): Vertragsart «TRADIFI_PERPETUAL», oder «TradFi» steht unter
    /// `underlyingSubType` (z. B. ["TradFi"] oder ["Pre-IPO", "TradFi"]).
    static func binance(contractType: String, subTypes: [String]) -> Bool {
        if contractType == binanceTradFiPerpetual { return true }
        return subTypes.contains { same($0, "TradFi") }
    }

    /// Bybit (`/v5/market/instruments-info`, linear): `symbolType` ist stock, ETF, commodity oder forex.
    static func bybit(symbolType: String) -> Bool { bybitTypes.contains(symbolType.lowercased()) }

    /// OKX (`/api/v5/public/instruments`, SWAP): `instCategory` vorhanden und nicht 1 (= Krypto); 3 steht für Aktien.
    static func okx(instCategory: String) -> Bool { !(instCategory.isEmpty || instCategory == "1") }

    /// MEXC (`/api/v1/contract/detail`): `type` 2 (Aktien und ETFs; Gold läuft unter Typ 1) oder ein Bereich
    /// in `conceptPlate`, der «tradfi», «Stock», «metals», «Commodities» oder «forex» enthält.
    static func mexc(conceptPlates: [String], type: Int) -> Bool {
        guard type != 2 else { return true }
        return conceptPlates.contains { plate in
            mexcZones.contains { zone in plate.range(of: zone, options: .caseInsensitive) != nil }
        }
    }

    /// Bitget (`/api/v2/mix/market/contracts`): `isRwa` je Kontrakt «YES» oder «NO» (Aktien-, Rohstoff- und
    /// Index-Futures, «RWA»; in den Live-Daten rund 335 von 801).
    static func bitget(isRwa: String) -> Bool { same(isRwa, "YES") || same(isRwa, "true") }

    /// HTX Futures (`swap_contract_info`): `tradfi_labels` enthält etwas (Text oder Liste, z. B. Aktie, Rohstoff).
    static func htx(labels: [String]) -> Bool {
        labels.contains { label in !label.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    /// Phemex Futures (`/public/products`, `perpProductsV2`): `perpProductSubType` lautet «TradFi».
    static func phemex(perpProductSubType: String) -> Bool { same(perpProductSubType, "TradFi") }

    /// Lighter (`/api/v1/tokenlist`): `asset_type` «RWA» (Aktien, ETFs, Rohstoffe, Devisen, Pre-IPO, Rechenleistung)
    /// statt «CRYPTO».
    static func lighter(assetType: String) -> Bool { same(assetType, "RWA") }

    /// Lighter-Spot ohne eigenen `tokenlist`-Eintrag (etwa «rhSPY/USDC»): Basis «rh» + Grossbuchstabe ist ein
    /// Robinhood-Aktientoken (Aktie oder ETF). Kennt `tokenlist` den Rest («SPY»), entscheidet dessen `asset_type`
    /// (`restAssetType`) — so bleibt ein gleich gebauter Krypto-Name unter Krypto; sonst gilt das Paar als TradFi.
    static func lighterRobinhood(base: String, restAssetType: String?) -> Bool {
        let chars = Array(base)
        guard chars.count >= 3, base.hasPrefix("rh"), chars[2] >= "A", chars[2] <= "Z" else { return false }
        guard let rest = restAssetType, !rest.trimmingCharacters(in: .whitespaces).isEmpty else { return true }
        return lighter(assetType: rest)
    }

    /// Einträge in Aster `underlyingSubType` für Aktien, ETFs und Rohstoffe (Krypto: «Top», «Meme», «AI», «AOS2» …).
    private static let asterSubTypes: Set<String> = ["stock", "etf", "commodities", "usd1-rwa"]

    /// Aster (`/fapi/v1/exchangeInfo`): `underlyingType` ist immer «COIN»; Aktien, ETFs und Rohstoffe tragen in
    /// `underlyingSubType` «STOCK», «ETF», «Commodities» oder «USD1-RWA» (etwa ["STOCK", "Semiconductor"]).
    static func aster(subTypes: [String]) -> Bool {
        subTypes.contains { asterSubTypes.contains($0.trimmingCharacters(in: .whitespaces).lowercased()) }
    }

    /// Vorsilben von `asset` der BingX-TradFi-Kontrakte: Aktie, Index/ETF, Rohstoff, Devisen.
    private static let bingxPrefixes = ["NCSK", "NCSI", "NCCO", "NCFX"]

    /// BingX Futures (`/openApi/swap/v2/quote/contracts`): `asset` beginnt mit «NCSK» (Aktie), «NCSI» (Index/ETF),
    /// «NCCO» (Rohstoff) oder «NCFX» (Devisen), etwa «NCSKAAPL2USD». Nur die bekannten Vorsilben, damit ein
    /// Krypto-Token, dessen Kürzel zufällig mit «NC» beginnt, nicht unter TradFi verschwindet.
    static func bingx(asset: String) -> Bool {
        bingxPrefixes.contains { asset.count > $0.count && asset.hasPrefix($0) }
    }

    /// Basis eines BingX-TradFi-Kontrakts aus `displayName` (Teil vor dem letzten «-»): ein Kürzel in Klammern
    /// («GOLD(XAU)» → XAU), sonst der Text ohne Leerzeichen in Grossbuchstaben («Oil Brent» → OILBRENT). `asset`
    /// («NCSKAAPL2USD») taugt nicht zum Anzeigen; ohne brauchbaren Namen bleibt es trotzdem die Basis.
    static func bingxBase(displayName: String, asset: String) -> String {
        var name = Substring(displayName)
        if let cut = name.lastIndex(of: "-") { name = name[..<cut] }
        var short = Substring("")
        if let open = name.firstIndex(of: "(") {
            let after = name[name.index(after: open)...]
            short = after.firstIndex(of: ")").map { after[..<$0] } ?? after
        }
        let picked = short.trimmingCharacters(in: .whitespaces).isEmpty ? name : short
        let base = String(picked.filter { !$0.isWhitespace }).uppercased()
        return base.isEmpty ? asset : base
    }
}
