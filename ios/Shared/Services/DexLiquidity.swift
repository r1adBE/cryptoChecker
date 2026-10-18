import Foundation

/// Alarme auf DEX-Pools nur mit genug Liquidität (`DexScreener.minAlarmLiquidityUsd`): Ein dünner Pool lässt sich mit
/// kleinen Beträgen bewegen — ein Alarm wäre leicht auszulösen bzw. zu manipulieren. Solche Pools zeigt die Suche mit
/// «Geringe Liquidität – kein Alarm», der Alarm-Editor bleibt zu, und bestehende Alarme bleiben erhalten, lösen aber
/// nicht aus, solange die zuletzt gemessene Liquidität darunter liegt oder unbekannt ist. Börsen-Paare betrifft das
/// nie. Reine Logik — wie `DexLiquidity.kt`.
enum DexLiquidity {
    /// Börsen-Schlüssel der DEX-Pools.
    static let marketKey = "DexScreener"

    static func isDex(_ marketKey: String) -> Bool { marketKey == self.marketKey }

    /// Schlüssel der gemerkten Liquidität: Pool-Kennung «chain/pairAddress», ohne Gross-/Kleinschreibung.
    static func key(_ pairId: String?) -> String? {
        guard let trimmed = pairId?.trimmingCharacters(in: .whitespaces).lowercased(), !trimmed.isEmpty else { return nil }
        return trimmed
    }

    /// Alarme dieses Paars pausieren? Nur DEX-Pools mit zu wenig oder unbekannter Liquidität.
    static func blocksAlarms(marketKey: String, liquidityUsd: Double?) -> Bool {
        isDex(marketKey) && !DexScreener.alarmCapable(liquidityUsd)
    }
}

/// Zuletzt gemessene Liquidität (USD) je DEX-Pool (`DexLiquidity`): aus der Suche beim Hinzufügen und aus jeder
/// Kursabfrage (`Ticker.liquidityUsd`). In den gemeinsamen Einstellungen (App, Hintergrund) statt im Stand —
/// abgeleiteter Marktwert, nicht in der Sicherung; fehlt er, gilt die Liquidität als unbekannt (kein Alarm bis zur
/// nächsten Abfrage). Wie `DexLiquidityStore.kt`.
enum DexLiquidityStore {
    private static let defaultsKey = "dex_liquidity"

    /// Pool-Schlüssel (`DexLiquidity.key`) → Liquidität in USD.
    static var all: [String: Double] {
        SharedStorage.defaults.dictionary(forKey: defaultsKey) as? [String: Double] ?? [:]
    }

    static func get(_ pairId: String?) -> Double? {
        guard let key = DexLiquidity.key(pairId) else { return nil }
        return all[key]
    }

    /// Neue Messung; nil = unbekannt (Eintrag entfällt).
    static func put(_ pairId: String?, _ liquidityUsd: Double?) {
        guard let key = DexLiquidity.key(pairId) else { return }
        let value = liquidityUsd.flatMap { $0.isFinite && $0 >= 0 ? $0 : nil }
        var current = all
        if current[key] == value { return }
        current[key] = value
        SharedStorage.defaults.set(current, forKey: defaultsKey)
    }

    /// Alarme dieses Paars pausieren (`DexLiquidity.blocksAlarms`)?
    static func blocksAlarms(marketKey: String, pairId: String?) -> Bool {
        DexLiquidity.blocksAlarms(marketKey: marketKey, liquidityUsd: get(pairId))
    }
}
