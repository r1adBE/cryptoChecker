import Foundation
import os

// Letzter Stand der Portfolio-Kurse auf dem Gerät (Teil von `PortfolioPriceSource`) — wie
// `PortfolioPriceSourceStore.kt` (Android).

extension PortfolioPriceSource {
    /// Gemerkte Kurse: Datei in der App Group, nie im Geräte-Backup — sie nennt die gehaltenen Coins
    /// (wie die Zwischenspeicher des Portfolio-Widgets, `PortfolioStore.applyBackupPolicy`; Android:
    /// `noBackupFilesDir`). Getrennt vom Zwischenspeicher: `cached` und `prices` (auch Widget und
    /// Hintergrund) sehen nur Kurse dieses Prozesses.
    static var storeURL: URL { SharedStorage.directory.appendingPathComponent("portfolio_last_prices.txt") }
    /// Wie `cached`, ergänzt um den letzten gemerkten Stand (einmal je Prozess gelesen; je Coin gewinnt
    /// der neuere Kurs) — nur für die Anzeige im Portfolio-Tab nach einem Neustart oder ohne Netz:
    /// lieber der letzte Wert mit «Stand …» als «0.00». `updatedAt` ist dann alt; ob der Stand veraltet
    /// ist, entscheidet `PortfolioPriceFallback.state`. Wie Android `PortfolioPriceSource.stored`.
    static func stored(_ coins: [String]) async -> PortfolioPrices {
        let wanted = normalized(coins)
        return await Task.detached(priority: .userInitiated) { Self.collectStored(wanted) }.value
    }

    /// Zwischenspeicher und gemerkter Stand für `wanted` (synchron, abseits des Hauptthreads aufgerufen).
    private static func collectStored(_ wanted: [String]) -> PortfolioPrices {
        let stored = loadStored()
        let merged = PortfolioPriceFallback.merge(current: state.withLock { $0.currentEntries() }, stored: stored)
        // Gekapselte Coins ohne eigenen Kurs: Kurs der Basis, markiert (`PortfolioPrices.viaBase`) — wie Android
        let via = ChainAssets.priceViaBase(wanted, available: Set(merged.keys))
        var result: [String: Double] = [:]
        var times: [String: Int64] = [:]
        var oldest: Int64?
        for coin in wanted {
            guard let entry = merged[coin] ?? via[coin].flatMap({ merged[$0] }) else { continue }
            result[coin] = entry.price
            times[coin] = entry.at
            oldest = min(oldest ?? entry.at, entry.at)
        }
        result[stable] = 1
        var out = PortfolioPrices(prices: result, updatedAt: oldest ?? 0, times: times)
        out.viaBase = via
        let partial = out
        return state.withLock { s in
            var out = partial
            s.decorate(&out, coins: wanted)
            // «Kurs ungeprüft» aus dem gemerkten Stand (auch nach einem Neustart); gekapselte Coins erben ihn von der
            // Basis
            out.unverified = PortfolioPriceFallback.unverified(wanted, entries: merged, via: via)
            // Ungeprüft = ohne Kurs (zählt nicht als Geld)
            out.unchecked = Set(wanted.filter { s.uncheckedCoins.contains($0) && merged[$0] == nil && via[$0] == nil })
            out.noMarket = Set(wanted.filter { s.noMarketCoins.contains($0) && merged[$0] == nil && via[$0] == nil })
            out.unavailable = Set(wanted.filter {
                s.unavailableCoins.contains($0) && merged[$0] == nil && !s.uncheckedCoins.contains($0) && via[$0] == nil
            })
            return out
        }
    }

    /// «Portfolio leeren»: gemerkte Kurse (nennen die gehaltenen Coins) und Zwischenspeicher weg. Eine gerade laufende
    /// Abfrage übernimmt danach nichts mehr (`clearGeneration`); die Datei fällt unter der Sperre weg wie `persist` sie
    /// schreibt — sie entsteht danach nicht neu.
    static func clearStored() {
        state.withLock { s in
            s.clearGeneration += 1
            s.cache = [:]
            s.uncheckedCoins = []
            s.noMarketCoins = []
            s.binanceUnlisted = [:]
            s.unverifiedCoins = []
            // Auch «Kurs nicht verfügbar» (wie Android) — nennt gehaltene Coins
            s.unavailableCoins = []
            s.storedEntries = [:]
            s.sourceOf = [:]
            s.failedOf = [:]
            s.binanceOf = [:]
            s.mixedCoins = []
            try? FileManager.default.removeItem(at: storeURL)
        }
        PortfolioExchangeRouteStore.clear()
    }

    /// Gemerkter Stand (einmal je Prozess aus der Datei gelesen).
    static func loadStored() -> [String: StoredPrice] {
        if let known = state.withLock({ $0.storedEntries }) { return known }
        let text = try? String(contentsOf: storeURL, encoding: .utf8)
        let usable = PortfolioPriceFallback.prune(PortfolioPriceFallback.decode(text), now: EpochTime.nowMillis)
        return state.withLock { s in
            if let raced = s.storedEntries { return raced }
            s.storedEntries = usable
            return usable
        }
    }

    /// Nach jedem erfolgreichen Abruf (innerhalb von `priceGate`, also nacheinander): gemerkter Stand und
    /// Zwischenspeicher (je Coin der neuere) schreiben, danach aus dem Backup nehmen (das atomare
    /// Schreiben verliert das Merkmal). Unter `state` (kleine Datei), damit `clearStored` sie nicht dazwischen löscht;
    /// nicht mehr, wenn seit Beginn der Abfrage (`generation`) geleert wurde.
    static func persist(generation: Int) {
        let stored = loadStored()
        state.withLock { s in
            guard s.clearGeneration == generation else { return }
            let entries = PortfolioPriceFallback.prune(
                PortfolioPriceFallback.merge(current: s.currentEntries(), stored: s.storedEntries ?? stored),
                now: EpochTime.nowMillis
            )
            s.storedEntries = entries
            guard let data = PortfolioPriceFallback.encode(entries).data(using: .utf8),
                  (try? data.write(to: storeURL, options: [.atomic])) != nil
            else { return }
            BackupExclusion.set(excluded: true, for: storeURL)
        }
    }
}
