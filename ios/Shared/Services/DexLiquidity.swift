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
    /// Ein Schlüssel je Pool (`PerKeyDefaults`); bis Build 101 ein Wörterbuch unter «dex_liquidity».
    static let storage = PerKeyDefaults(prefix: "dex_liquidity.", legacyKey: "dex_liquidity")
    /// Einmal je Prozess vor dem ersten Zugriff (thread-sicher, `static let`).
    private static let migration: Void = storage.migrateLegacy(SharedStorage.defaults)

    static func get(_ pairId: String?, defaults: UserDefaults = SharedStorage.defaults) -> Double? {
        _ = migration
        guard let key = DexLiquidity.key(pairId) else { return nil }
        return (defaults.object(forKey: storage.key(key)) as? NSNumber)?.doubleValue
    }

    /// Neue Messung; nil = unbekannt (Eintrag entfällt). Schreibt nur den eigenen Pool.
    static func put(_ pairId: String?, _ liquidityUsd: Double?, defaults: UserDefaults = SharedStorage.defaults) {
        _ = migration
        guard let key = DexLiquidity.key(pairId) else { return }
        let value = liquidityUsd.flatMap { $0.isFinite && $0 >= 0 ? $0 : nil }
        if get(pairId, defaults: defaults) == value { return }
        if let value {
            defaults.set(value, forKey: storage.key(key))
        } else {
            defaults.removeObject(forKey: storage.key(key))
        }
    }

    /// Alarme dieses Paars pausieren (`DexLiquidity.blocksAlarms`)?
    static func blocksAlarms(marketKey: String, pairId: String?) -> Bool {
        DexLiquidity.blocksAlarms(marketKey: marketKey, liquidityUsd: get(pairId))
    }
}
