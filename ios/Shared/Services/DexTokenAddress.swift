import Foundation

/// Vertragsadresse des Basis-Tokens eines DEX-Pools («baseToken.address» aus der DexScreener-Suche) fürs Paar-Blatt:
/// Gleichnamige Token (zweimal «PEPE») sind nur über die Adresse sicher zu unterscheiden. Gemerkt wird sie beim
/// Hinzufügen je Pool-Kennung (`DexLiquidity.key`) in den gemeinsamen Einstellungen — nicht im Paar selbst, weil
/// Basis, Gegenwährung und Kennung zur Identität des Paars gehören. Reine Logik — wie `DexTokenAddress.kt`.
enum DexTokenAddress {
    /// Längste angenommene Adresse; EVM 42, Solana bis 44 Zeichen — mehr ist keine Adresse.
    static let maxLength = 128

    /// Bereinigte Adresse; nil = keine (leer, zu lang oder mit Leerraum mitten drin).
    static func cleaned(_ address: String?) -> String? {
        guard let trimmed = address?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty,
              trimmed.count <= maxLength, !trimmed.contains(where: { $0.isWhitespace }) else { return nil }
        return trimmed
    }

    /// Gekürzt für die Anzeige («0x1234…abcd», wie in der Trefferliste der Suche).
    static func shown(_ address: String) -> String { DexScreener.shortAddress(address) }
}

/// Vertragsadresse des Basis-Tokens je DEX-Pool (`DexTokenAddress`), gemerkt beim Hinzufügen aus der Suche. In den
/// gemeinsamen Einstellungen statt im Stand (wie `DexLiquidityStore`) — nicht in der Sicherung; fehlt sie, zeigt das
/// Paar-Blatt einfach keine Adresse. Wie `DexTokenAddressStore.kt`.
enum DexTokenAddressStore {
    /// Ein Schlüssel je Pool (`PerKeyDefaults`).
    static let storage = PerKeyDefaults(prefix: "dex_token_address.")

    /// Gemerkte Adresse des Pools `pairId`; nil = unbekannt.
    static func get(_ pairId: String?, defaults: UserDefaults = SharedStorage.defaults) -> String? {
        guard let key = DexLiquidity.key(pairId) else { return nil }
        return DexTokenAddress.cleaned(defaults.string(forKey: storage.key(key)))
    }

    /// Adresse aus der Suche merken; leer/ungültig = nichts ändern (eine frühere Adresse bleibt).
    static func put(_ pairId: String?, _ address: String?, defaults: UserDefaults = SharedStorage.defaults) {
        guard let key = DexLiquidity.key(pairId), let value = DexTokenAddress.cleaned(address) else { return }
        if defaults.string(forKey: storage.key(key)) == value { return }
        defaults.set(value, forKey: storage.key(key))
    }
}
