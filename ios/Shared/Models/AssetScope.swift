import Foundation

/// «Token & TradFi» (Einstellungen › Merkliste) — wie `AssetScope.kt`: Was die Paar-Auswahl
/// (Paar hinzufügen, Suche über alle Börsen, «Alle …-Paare») zeigt.
///
///  - `TOKENS` (Standard): nur Krypto — Spot, Perpetuals und (mit QTLY) Laufzeit-Futures.
///  - `TRADFI`: nur Futures auf Aktien, Rohstoffe, Devisen und Pre-IPO (`MarketPair.isTradFi`).
///  - `BOTH`: alles.
///
/// Alpha-Tokens (`AssetKind.ALPHA`) gehören zu Token; der Schalter «inkl. Alpha-Tokens»
/// (`AppSettings.includeAlphaTokens`, Standard aus) blendet sie in der Auswahl aus (`allows` mit
/// `hiddenAlpha`).
///
/// Gemerkte Paare bleiben immer in der Merkliste. Gespeichert und gesichert unter dem Namen
/// (Schlüssel «assetScope», wie Android); der frühere Schalter «includeTradFiFutures»: an → `BOTH`,
/// aus → `TOKENS`.
enum AssetScope: String, Codable, CaseIterable, Sendable {
    case TOKENS
    case TRADFI
    case BOTH

    static let `default`: AssetScope = .TOKENS

    /// TradFi-Kontrakte erscheinen in der Auswahl.
    var allowsTradFi: Bool { self != .TOKENS }

    /// Krypto-Paare (Token) erscheinen in der Auswahl.
    var allowsTokens: Bool { self != .TRADFI }

    /// Paar der Börse `marketKey` in der Auswahl zeigen? `hiddenAlpha`: Kürzel der Alpha-Tokens, die
    /// ausgeblendet sind («inkl. Alpha-Tokens» aus; `hiddenAlphaSymbols`), sonst leer. Alpha-Paare von
    /// Binance selbst (`BinanceAlpha`) nur mit «inkl. Alpha-Tokens». `pairNames` (Kürzel gross → Name aus
    /// der Paarliste der Börse) und `alphaNames` wie bei `AssetKind.of`: klar anderer Name → kein Alpha.
    func allows(_ pair: MarketPair, marketKey: String = "", hiddenAlpha: Set<String> = [],
                pairNames: [String: String] = [:], alphaNames: [String: String] = [:]) -> Bool {
        if pair.isTradFi { return allowsTradFi }
        guard allowsTokens else { return false }
        if BinanceAlpha.isAlphaPair(marketKey: marketKey, pairId: pair.pairId) { return hiddenAlpha.isEmpty }
        if hiddenAlpha.isEmpty { return true }
        let kind = AssetKind.of(marketKey: marketKey, base: pair.base, tradFi: false, alphaSymbols: hiddenAlpha,
                                pairName: pairNames[TokenIdentity.key(pair.base)], alphaNames: alphaNames)
        return kind != .ALPHA
    }

    /// Platzhalter, wenn «inkl. Alpha-Tokens» aus ist, aber noch keine Alpha-Liste vorliegt: hält
    /// `hiddenAlphaSymbols` nicht leer, damit die Alpha-Paare von Binance trotzdem ausgeblendet sind
    /// (kein gültiges Kürzel, trifft nie ein Paar).
    static let hideAlphaMark = "#alpha"

    /// Auszublendende Alpha-Kürzel für `allows`: «inkl. Alpha-Tokens» an → keine, aus → alle
    /// bekannten (`alphaSymbols`; ohne Liste `hideAlphaMark`).
    static func hiddenAlphaSymbols(includeAlpha: Bool, alphaSymbols: Set<String>) -> Set<String> {
        if includeAlpha { return [] }
        return alphaSymbols.isEmpty ? [hideAlphaMark] : alphaSymbols
    }

    /// Börse in der Auswahl zeigen? Bei «Nur TradFi» nur Börsen, deren Paarliste TradFi kennt
    /// (`CoinLogos.tradFiMarkets`) — die übrigen (auch DEX) hätten dort keine Paare.
    func allowsMarket(_ marketKey: String) -> Bool {
        allowsTokens || CoinLogos.tradFiMarkets.contains(marketKey)
    }

    /// Gespeicherter Name; unbekannt (neuere Version) oder leer → nil.
    static func from(name: String?) -> AssetScope? {
        guard let n = name?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(), !n.isEmpty else { return nil }
        return AssetScope(rawValue: n)
    }

    /// Früherer Schalter «TradFi»: an → `BOTH`, sonst `TOKENS`.
    static func fromLegacy(_ includeTradFi: Bool?) -> AssetScope { includeTradFi == true ? .BOTH : .TOKENS }

    /// Gespeicherte Einstellung: der neue Wert, sonst (Update) der frühere Schalter, sonst der Standard.
    static func resolve(stored: String?, legacyTradFi: Bool?) -> AssetScope {
        fromBackup(stored, legacyTradFi: legacyTradFi) ?? .default
    }

    /// Aus einer Sicherung: «assetScope», sonst «includeTradFiFutures» (ältere Sicherung); fehlen
    /// beide, nil = aktuelle Einstellung stehen lassen.
    static func fromBackup(_ scope: String?, legacyTradFi: Bool?) -> AssetScope? {
        if let s = from(name: scope) { return s }
        guard let legacyTradFi else { return nil }
        return fromLegacy(legacyTradFi)
    }
}
