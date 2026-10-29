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
    /// Gemerkte Kurse; nil = Datei noch nicht gelesen.
    // nonisolated(unsafe): jeder Zugriff nur unter `lock` (NSLock)
    nonisolated(unsafe) private static var storedEntries: [String: StoredPrice]?

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
        lock.lock()
        let merged = PortfolioPriceFallback.merge(current: currentEntriesLocked(), stored: stored)
        lock.unlock()
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
        lock.lock()
        decorateLocked(&out, coins: wanted)
        // «Kurs ungeprüft» aus dem gemerkten Stand (auch nach einem Neustart); gekapselte Coins erben ihn von der Basis
        out.unverified = PortfolioPriceFallback.unverified(wanted, entries: merged, via: via)
        // Ungeprüft = ohne Kurs (zählt nicht als Geld)
        out.unchecked = Set(wanted.filter { uncheckedCoins.contains($0) && merged[$0] == nil && via[$0] == nil })
        out.unavailable = Set(wanted.filter {
            unavailableCoins.contains($0) && merged[$0] == nil && !uncheckedCoins.contains($0) && via[$0] == nil
        })
        lock.unlock()
        return out
    }

    /// «Portfolio leeren»: gemerkte Kurse (nennen die gehaltenen Coins) und Zwischenspeicher weg. Eine gerade laufende
    /// Abfrage übernimmt danach nichts mehr (`clearGeneration`); die Datei fällt unter der Sperre weg wie `persist` sie
    /// schreibt — sie entsteht danach nicht neu.
    static func clearStored() {
        lock.lock()
        clearGeneration.withLock { $0 += 1 }
        cache = [:]
        uncheckedCoins = []
        unverifiedCoins = []
        // Auch «Kurs nicht verfügbar» (wie Android) — nennt gehaltene Coins
        unavailableCoins = []
        storedEntries = [:]
        sourceOf = [:]
        failedOf = [:]
        binanceOf = [:]
        mixedCoins = []
        try? FileManager.default.removeItem(at: storeURL)
        lock.unlock()
        PortfolioExchangeRouteStore.clear()
    }

    /// Gemerkter Stand (einmal je Prozess aus der Datei gelesen).
    static func loadStored() -> [String: StoredPrice] {
        lock.lock()
        let known = storedEntries
        lock.unlock()
        if let known { return known }
        let text = try? String(contentsOf: storeURL, encoding: .utf8)
        let usable = PortfolioPriceFallback.prune(PortfolioPriceFallback.decode(text), now: EpochTime.nowMillis)
        lock.lock(); defer { lock.unlock() }
        if let raced = storedEntries { return raced }
        storedEntries = usable
        return usable
    }

    /// Nach jedem erfolgreichen Abruf (innerhalb von `priceGate`, also nacheinander): gemerkter Stand und
    /// Zwischenspeicher (je Coin der neuere) schreiben, danach aus dem Backup nehmen (das atomare
    /// Schreiben verliert das Merkmal). Unter `lock` (kleine Datei), damit `clearStored` sie nicht dazwischen löscht;
    /// nicht mehr, wenn seit Beginn der Abfrage (`generation`) geleert wurde.
    static func persist(generation: Int) {
        let stored = loadStored()
        lock.lock(); defer { lock.unlock() }
        guard clearGeneration.withLock({ $0 }) == generation else { return }
        let entries = PortfolioPriceFallback.prune(
            PortfolioPriceFallback.merge(current: currentEntriesLocked(), stored: storedEntries ?? stored),
            now: EpochTime.nowMillis
        )
        storedEntries = entries
        guard let data = PortfolioPriceFallback.encode(entries).data(using: .utf8),
              (try? data.write(to: storeURL, options: [.atomic])) != nil
        else { return }
        BackupExclusion.set(excluded: true, for: storeURL)
    }

    /// Zwischenspeicher als gemerkte Kurse; nur mit gehaltenem `lock` aufrufen.
    static func currentEntriesLocked() -> [String: StoredPrice] {
        var out: [String: StoredPrice] = [:]
        // «Kurs ungeprüft» wird mitgemerkt (`StoredPrice.unverified`)
        for (coin, entry) in cache {
            out[coin] = StoredPrice(price: entry.price, at: entry.at, unverified: unverifiedCoins.contains(coin))
        }
        return out
    }
}
