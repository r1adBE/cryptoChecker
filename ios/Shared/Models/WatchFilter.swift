import Foundation

/// Ansichten der Merkliste über den Gruppen-Chips: «Alle» (nil), «Favoriten» (`favorites`), «Token»
/// (`tokens`) und die eigenen Gruppen — wie `WatchFilter.kt`. FAV ist keine Gruppe, sondern ein Filter
/// auf das Favoriten-Kennzeichen: Ein Paar bleibt in seiner Gruppe und steht als Favorit zusätzlich
/// unter FAV — Favorit an, gleich drin; aus, gleich draussen. «Token» ist ebenfalls keine Gruppe: alle
/// Paare ausser denen der Gruppe «TradFi» (`AutoGroup.tradFi`) — also auch Paare ohne Gruppe, «QTLY»
/// und eigene Gruppen. Gilt auch für das Merkliste-Widget.
/// «Alpha» (`alpha`) ist ebenfalls keine Gruppe: nur Paare der Art `AssetKind.ALPHA` (Binance-Alpha-
/// Liste); sie stehen weiterhin auch unter «Token» (ein Alpha-Token ist auch ein Token). Auch in der
/// Einrichtung des Merkliste-Widgets wählbar (gespeicherte Alpha-Liste, `WidgetGroupQuery`).
enum WatchFilter {
    /// Gespeicherter Wert für «FAV» (Steuerzeichen, kann kein Gruppenname sein; gleich wie Android,
    /// damit Sicherungen die Auswahl behalten).
    static let favorites = "\u{1}FAV"

    /// Gespeicherter Wert für «Token» (Steuerzeichen, kann kein Gruppenname sein; gleich wie Android).
    static let tokens = "\u{1}TOK"

    /// Gespeicherter Wert für «Alpha» (Steuerzeichen, kann kein Gruppenname sein; gleich wie Android).
    static let alpha = "\u{1}ALP"

    static func isFavorites(_ selection: String?) -> Bool { selection == favorites }

    static func isTokens(_ selection: String?) -> Bool { selection == tokens }

    static func isAlpha(_ selection: String?) -> Bool { selection == alpha }

    /// Ein Filter statt einer Gruppe (FAV, Token, Alpha): nicht bearbeitbar und kein Ziel für «Gruppe».
    static func isBuiltIn(_ selection: String?) -> Bool {
        isFavorites(selection) || isTokens(selection) || isAlpha(selection)
    }

    /// Steht ein Paar in der Ansicht `selection`? `alpha`: Paar ist ein Alpha-Token
    /// (`AssetKind.ALPHA`); nur für «Alpha» nötig.
    static func matches(_ selection: String?, groupName: String?, favorite: Bool, alpha isAlphaPair: Bool = false) -> Bool {
        guard let selection else { return true }
        if selection == favorites { return favorite }
        if selection == tokens { return groupName != AutoGroup.tradFi }
        if selection == alpha { return isAlphaPair }
        return groupName == selection
    }

    /// Wie oben; die Art (`Watch.assetKind`, gespeicherte Listen) nur für «Alpha» bestimmt.
    static func matches(_ selection: String?, _ watch: Watch) -> Bool {
        matches(selection, groupName: watch.groupName, favorite: watch.favorite,
                alpha: isAlpha(selection) && watch.assetKind == .ALPHA)
    }

    /// «Token»-Chip zeigen? Nur wenn mindestens ein Paar in «TradFi» steht UND mindestens eines nicht —
    /// sonst wäre die Ansicht gleich «Alle» oder leer. `groupNames`: Gruppe je Paar (nil = keine).
    static func showsTokens(_ groupNames: [String?]) -> Bool {
        var tradFi = false
        var other = false
        for name in groupNames {
            if name == AutoGroup.tradFi { tradFi = true } else { other = true }
            if tradFi && other { return true }
        }
        return false
    }

    static func showsTokens(_ watches: [Watch]) -> Bool {
        showsTokens(watches.map(\.groupName))
    }

    /// «Alpha»-Chip zeigen? Nur wenn mindestens ein Paar ein Alpha-Token ist UND mindestens eines
    /// nicht — sonst wäre die Ansicht gleich «Alle» oder leer. `alphaFlags`: je Paar, ob Alpha.
    static func showsAlpha(_ alphaFlags: [Bool]) -> Bool {
        alphaFlags.contains(true) && alphaFlags.contains(false)
    }

    /// Wie oben mit der Art der Paare (`Watch.assetKind`).
    static func showsAlpha(_ watches: [Watch]) -> Bool {
        // Ohne Alpha-Liste nur Binance-Alpha-Paare (Kennung), sonst auch gleiche Kürzel anderer Börsen
        let alphaSymbols = CoinLogoStore.alphaSymbols
        let tradFiPairs = CoinLogoStore.tradFiPairs
        let alphaNames = CoinLogoStore.alphaNames
        let flags: [Bool] = watches.map { w in
            w.assetKind(tradFiPairs: tradFiPairs, alphaSymbols: alphaSymbols, alphaNames: alphaNames,
                        pairName: TokenIdentityStore.shared.pairName(marketKey: w.marketKey, base: w.baseAsset)) == .ALPHA
        }
        return showsAlpha(flags)
    }

    /// Gilt die gespeicherte Auswahl `selection` noch? «Alle» und FAV immer, «Token» nur mit
    /// `showsTokens`, «Alpha» nur mit `showsAlpha`, eine Gruppe, solange ein Paar sie nutzt. Sonst
    /// gilt wieder «Alle».
    static func isAvailable(_ selection: String?, in watches: [Watch]) -> Bool {
        guard let selection else { return true }
        if isFavorites(selection) { return true }
        if isTokens(selection) { return showsTokens(watches) }
        if isAlpha(selection) { return showsAlpha(watches) }
        return watches.contains { $0.groupName == selection }
    }
}

/// Art eines Paars — kleines Symbol vor der Glocke in der Merkliste, Chip «Alpha» und «inkl.
/// Alpha-Tokens» in der Paar-Auswahl. Wie `AssetKind.kt` (Android).
///
///  - `TRADFI`: Futures auf Aktien, Rohstoffe, Devisen, Pre-IPO (Kennzeichen der Paarliste).
///  - `ALPHA`: Token aus der Binance-Alpha-Liste, die Binance nicht regulär am Spot führt — frühe,
///    meist kleine Projekte. Gilt nur als Alpha, obwohl es auch ein Token ist.
///  - `TOKEN`: alles übrige (Krypto: Spot, Perpetuals, Laufzeit-Futures).
enum AssetKind: String, CaseIterable, Sendable {
    case TOKEN, TRADFI, ALPHA

    /// Spot-Börse von Binance: was dort gehandelt wird, ist regulär gelistet — nie Alpha.
    static let binanceSpotMarket = "Binance"

    /// Art eines Paars. `alphaSymbols`: Kürzel der Alpha-Liste ohne die regulär an Binance Spot
    /// gelisteten (wie `CoinLogos.normalize`; aus der Alpha-Liste, `CoinLogos.alphaSymbols`). Leer
    /// (Liste noch nie geladen, Logos aus) → nie Alpha. DEX-Pools nie: Dort darf ein fremder Token
    /// gleich heissen. Paare von Binance Spot nie: dort regulär gelistet. Hebel-Kürzel zählen wie
    /// das Kürzel («1000AIA» → «AIA»).
    ///
    /// Alpha-Paare von Binance selbst (Kennung «ALPHA_<id>USDT», `BinanceAlpha.isAlphaPair`) sind immer
    /// Alpha. Weniger Fehltreffer: Kennen beide Seiten einen Namen (`pairName` aus der Paarliste der
    /// Börse, `alphaNames` je Kürzel aus der Alpha-Liste) und widersprechen sie sich klar
    /// (`TokenIdentity.compatible`), ist es ein anderer Token gleichen Kürzels — kein Alpha. Wie Android.
    static func of(marketKey: String, base: String, tradFi: Bool, alphaSymbols: Set<String>,
                   pairId: String? = nil, pairName: String? = nil,
                   alphaNames: [String: String] = [:]) -> AssetKind {
        if tradFi { return .TRADFI }
        if BinanceAlpha.isAlphaPair(marketKey: marketKey, pairId: pairId) { return .ALPHA }
        if alphaSymbols.isEmpty { return .TOKEN }
        if marketKey == CoinLogos.dexMarketKey || marketKey == binanceSpotMarket { return .TOKEN }
        let symbol = CoinLogos.normalize(base)
        guard alphaSymbols.contains(symbol) else { return .TOKEN }
        return TokenIdentity.compatible(symbol: symbol, pairName, alphaNames[symbol]) ? .ALPHA : .TOKEN
    }
}
