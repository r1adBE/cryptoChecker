import Foundation

/// Volle Namen der Coins für die Prüfung «gleicher Token» (`TokenIdentity`, K-8): je Börse aus ihrer
/// Paarliste (beim Abgleich der Paare, `saveMarket`) und von Binance aus der Symbolliste der Logos
/// (`saveBinance`). Keine eigene Anfrage — nur, was ohnehin geladen wird. Eine Datei im App-Group-Ordner,
/// aus Geräte-Backups ausgenommen (neu ladbar). Wie `TokenIdentityStore.kt`.
final class TokenIdentityStore: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
    static let shared = TokenIdentityStore()

    private let url: URL
    private let lock = NSLock()
    private var index: TokenIdentity.NameIndex?

    init(url: URL = SharedStorage.directory.appendingPathComponent("token_names.json")) {
        self.url = url
    }

    /// Nur mit gehaltenem `lock` aufrufen.
    private func currentLocked() -> TokenIdentity.NameIndex {
        if let index { return index }
        let loaded = TokenIdentity.decode(try? Data(contentsOf: url))
        index = loaded
        return loaded
    }

    private func update(_ change: (TokenIdentity.NameIndex) -> TokenIdentity.NameIndex) {
        lock.lock(); defer { lock.unlock() }
        let before = currentLocked()
        let next = change(before)
        if next == before { return }
        index = next
        guard let data = TokenIdentity.encode(next) else { return }
        do {
            try data.write(to: url, options: .atomic)
            // Atomares Schreiben ersetzt die Datei: Merkmal erneut setzen
            BackupExclusion.set(excluded: true, for: url)
        } catch {
            // Nur im Speicher; beim nächsten Abgleich erneut
        }
    }

    /// Datei einmal lesen und dekodieren (Hintergrund beim Start, seit Build 103), damit der erste Zugriff aus der
    /// Oberfläche nicht auf dem Hauptthread liest.
    func warmUp() {
        lock.lock(); defer { lock.unlock() }
        _ = currentLocked()
    }

    /// Name des Coins `base` in der Paarliste der Börse `marketKey`; nil = unbekannt.
    func pairName(marketKey: String, base: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        return currentLocked().pairName(marketKey: marketKey, base: base)
    }

    /// Namen der Coins der Börse `marketKey` (Kürzel gross, `TokenIdentity.key` → Name); leer = unbekannt.
    func marketNames(_ marketKey: String) -> [String: String] {
        lock.lock(); defer { lock.unlock() }
        return currentLocked().markets[marketKey] ?? [:]
    }

    /// `fullName` von Binance für `base`; nil = unbekannt.
    func binanceName(_ base: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        return currentLocked().binanceName(base)
    }

    /// Namen des Kürzels `base` in allen gespeicherten Paarlisten (je Börse einer) — für «mehrere Token unter dem
    /// Kürzel?» (`PortfolioPriceIdentity.ambiguous`). Leer = unbekannt. Wie Android.
    func otherNames(_ base: String) -> [String?] {
        lock.lock(); defer { lock.unlock() }
        let key = TokenIdentity.key(base)
        let names: [String] = currentLocked().markets.values.compactMap { $0[key] }
        return names
    }

    /// Namen aus einer frisch geladenen Paarliste; ohne Namen bleibt der bisherige Stand.
    func saveMarket(_ marketKey: String, names: [String: String]) {
        let cleaned = TokenIdentity.cleaned(names)
        if cleaned.isEmpty { return }
        update { index in
            var next = index
            next.markets[marketKey] = cleaned
            return next
        }
    }

    /// Namen aus der Binance-Symbolliste; leer = bisheriger Stand.
    func saveBinance(_ names: [String: String]) {
        let cleaned = TokenIdentity.cleaned(names)
        if cleaned.isEmpty { return }
        update { index in
            var next = index
            next.binance = cleaned
            return next
        }
    }

    /// Name des Coins `coin` laut den aktiven Paaren der Merkliste `watches` (alle Börsen mit Namen);
    /// widersprechen sie sich, nil (`TokenIdentity.consensus`) — dann nur Kürzel + Kursband wie bisher.
    func watchName(_ coin: String, watches: [Watch]) -> String? {
        let key = TokenIdentity.key(coin)
        let names = watches.filter { !$0.disabled && TokenIdentity.key($0.baseAsset) == key }
            .map { pairName(marketKey: $0.marketKey, base: $0.baseAsset) }
        return TokenIdentity.consensus(symbol: key, names)
    }

    /// Darf eine Binance-Quelle für `base` einspringen, wenn die eigene Seite `ownName` heisst? Nein nur bei
    /// klar verschiedenen Namen (`TokenIdentity.compatible`); unbekannt = ja (Kursband entscheidet).
    func binanceAllowed(base: String, ownName: String?) -> Bool {
        guard let ownName else { return true }
        return TokenIdentity.compatible(symbol: base, ownName, binanceName(base))
    }

    /// Darf die Ausweich-Reihe von `provider` (gemeinsamer Zwischenspeicher je Kürzel) beim Paar `base` an
    /// `marketKey` erscheinen? Nein bei klar anderem Namen — dann lieber nichts als die Reihe eines anderen
    /// Tokens. Wie `TokenIdentityStore.fallbackSeriesAllowed` (Android).
    func fallbackSeriesAllowed(marketKey: String, base: String, provider: String?) -> Bool {
        let providerKey: String? = provider.flatMap { TokenIdentity.providerMarkets[$0.trimmingCharacters(in: .whitespaces)] }
        let providerName: String? = providerKey.flatMap { pairName(marketKey: $0, base: base) }
        return TokenIdentity.fallbackSeriesAllowed(symbol: base, ownName: pairName(marketKey: marketKey, base: base),
                                                   provider: provider, binanceName: binanceName(base),
                                                   providerName: providerName)
    }

    /// Darf die Ausweich-Reihe von `provider` die %-Veränderung des Paars `base` an `marketKey` (`marketName`)
    /// liefern? Kerzen derselben Börse (`DayChange.foreignCandleSource` nil): wie `fallbackSeriesAllowed`. Kerzen
    /// einer anderen Börse oder unbekannter Anbieter: nur mit belegt gleichem Namen (`TokenIdentity.fallbackChangeAllowed`)
    /// — sonst keine Veränderung statt eines fremden Werts. Wie `TokenIdentityStore.fallbackChangeAllowed` (Android).
    func fallbackChangeAllowed(marketKey: String, marketName: String, base: String, provider: String?) -> Bool {
        guard let p = provider?.trimmingCharacters(in: .whitespaces), !p.isEmpty else { return false }
        if DayChange.foreignCandleSource(markets: [marketKey, marketName], provider: p) == nil {
            return fallbackSeriesAllowed(marketKey: marketKey, base: base, provider: p)
        }
        let providerKey: String? = TokenIdentity.providerMarkets[p]
        let providerName: String? = providerKey.flatMap { pairName(marketKey: $0, base: base) }
        return TokenIdentity.fallbackChangeAllowed(symbol: base, ownName: pairName(marketKey: marketKey, base: base),
                                                   provider: p, binanceName: binanceName(base),
                                                   providerName: providerName)
    }

    /// Darf die Quelle `host` der Kerzen-Kette für `base` einspringen, wenn die eigene Seite `ownName` heisst?
    /// Gleiche Namenssperre wie Mini-Chart und Widget. Wie `TokenIdentityStore.chainSourceAllowed` (Android).
    func chainSourceAllowed(base: String, ownName: String?, host: String) -> Bool {
        let provider: String = DataFreshness.candleProvider(host: host)
        let providerName: String? = TokenIdentity.providerMarkets[provider].flatMap { pairName(marketKey: $0, base: base) }
        return TokenIdentity.chainSourceAllowed(symbol: base, ownName: ownName, host: host, binanceName: binanceName(base),
                                                providerName: providerName)
    }
}
