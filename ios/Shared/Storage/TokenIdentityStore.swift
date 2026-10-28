import Foundation

/// Volle Namen der Coins für die Prüfung «gleicher Token» (`TokenIdentity`): je Börse aus ihrer
/// Paarliste (beim Abgleich der Paare, `saveExchange`) und von Binance aus der Symbolliste der Logos
/// (`saveBinance`). Keine eigene Anfrage — nur, was ohnehin geladen wird. Eine Datei im App-Group-Ordner,
/// aus Geräte-Backups ausgenommen (neu ladbar). Wie `TokenIdentityStore.kt`.
final class TokenIdentityStore: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
    static let shared = TokenIdentityStore()

    private let url: URL
    private let lock = NSLock()
    private var index: TokenIdentity.NameIndex?
    /// Zählt jede Änderung der Namen im Speicher (Lesen der Datei, `update`) — für Zwischenspeicher, die Namen nutzen
    /// (`AppData.watchKinds`).
    private var changes = 0

    init(url: URL = SharedStorage.directory.appendingPathComponent("token_names.json")) {
        self.url = url
    }

    /// Nur mit gehaltenem `lock` aufrufen.
    private func currentLocked() -> TokenIdentity.NameIndex {
        if let index { return index }
        let loaded = TokenIdentity.decode(try? Data(contentsOf: url))
        index = loaded
        changes &+= 1
        return loaded
    }

    private func update(_ change: (TokenIdentity.NameIndex) -> TokenIdentity.NameIndex) {
        lock.lock(); defer { lock.unlock() }
        let before = currentLocked()
        let next = change(before)
        if next == before { return }
        index = next
        changes &+= 1
        guard let data = TokenIdentity.encode(next) else { return }
        do {
            try data.write(to: url, options: .atomic)
            // Atomares Schreiben ersetzt die Datei: Merkmal erneut setzen
            BackupExclusion.set(excluded: true, for: url)
        } catch {
            // Nur im Speicher; beim nächsten Abgleich erneut
        }
    }

    /// Stand der Namen (ändert sich mit jeder Änderung); gleich = dieselben Namen wie beim letzten Lesen.
    var version: Int {
        lock.lock(); defer { lock.unlock() }
        return changes
    }

    /// Datei einmal lesen und dekodieren (Hintergrund beim Start), damit der erste Zugriff aus der
    /// Oberfläche nicht auf dem Hauptthread liest.
    func warmUp() {
        lock.lock(); defer { lock.unlock() }
        _ = currentLocked()
    }

    /// Name des Coins `base` in der Paarliste der Börse `exchangeKey`; nil = unbekannt.
    func pairName(exchangeKey: String, base: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        return currentLocked().pairName(exchangeKey: exchangeKey, base: base)
    }

    /// Namen der Coins der Börse `exchangeKey` (Kürzel gross, `TokenIdentity.key` → Name); leer = unbekannt.
    func exchangeNames(_ exchangeKey: String) -> [String: String] {
        lock.lock(); defer { lock.unlock() }
        return currentLocked().exchanges[exchangeKey] ?? [:]
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
        let names: [String] = currentLocked().exchanges.values.compactMap { $0[key] }
        return names
    }

    /// Namen aus einer frisch geladenen Paarliste; ohne Namen bleibt der bisherige Stand.
    func saveExchange(_ exchangeKey: String, names: [String: String]) {
        let cleaned = TokenIdentity.cleaned(names)
        if cleaned.isEmpty { return }
        update { index in
            var next = index
            next.exchanges[exchangeKey] = cleaned
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
    /// widersprechen sie sich, nil (`TokenIdentity.consensus`) — dann nur Kürzel + Kursband.
    func watchName(_ coin: String, watches: [Watch]) -> String? {
        let key = TokenIdentity.key(coin)
        let names = watches.filter { !$0.disabled && TokenIdentity.key($0.baseAsset) == key }
            .map { pairName(exchangeKey: $0.exchangeKey, base: $0.baseAsset) }
        return TokenIdentity.consensus(symbol: key, names)
    }

    /// Darf eine Binance-Quelle für `base` einspringen, wenn die eigene Seite `ownName` heisst? Nein nur bei
    /// klar verschiedenen Namen (`TokenIdentity.compatible`); unbekannt = ja (Kursband entscheidet).
    func binanceAllowed(base: String, ownName: String?) -> Bool {
        guard let ownName else { return true }
        return TokenIdentity.compatible(symbol: base, ownName, binanceName(base))
    }

    /// Darf die Ausweich-Reihe von `provider` (gemeinsamer Zwischenspeicher je Kürzel) beim Paar `base` an
    /// `exchangeKey` erscheinen? Nein bei klar anderem Namen — dann lieber nichts als die Reihe eines anderen
    /// Tokens. Wie `TokenIdentityStore.fallbackSeriesAllowed` (Android).
    func fallbackSeriesAllowed(exchangeKey: String, base: String, provider: String?) -> Bool {
        let providerKey: String? = provider.flatMap { TokenIdentity.providerExchanges[$0.trimmingCharacters(in: .whitespaces)] }
        let providerName: String? = providerKey.flatMap { pairName(exchangeKey: $0, base: base) }
        return TokenIdentity.fallbackSeriesAllowed(symbol: base, ownName: pairName(exchangeKey: exchangeKey, base: base),
                                                   provider: provider, binanceName: binanceName(base),
                                                   providerName: providerName)
    }

    /// Darf die Ausweich-Reihe von `provider` die %-Veränderung des Paars `base` an `exchangeKey` (`exchangeName`)
    /// liefern? Kerzen derselben Börse (`DayChange.foreignCandleSource` nil): wie `fallbackSeriesAllowed`. Kerzen
    /// einer anderen Börse oder unbekannter Anbieter: nur mit belegt gleichem Namen (`TokenIdentity.fallbackChangeAllowed`)
    /// — sonst keine Veränderung statt eines fremden Werts. Wie `TokenIdentityStore.fallbackChangeAllowed` (Android).
    func fallbackChangeAllowed(exchangeKey: String, exchangeName: String, base: String, provider: String?) -> Bool {
        guard let p = provider?.trimmingCharacters(in: .whitespaces), !p.isEmpty else { return false }
        if DayChange.foreignCandleSource(exchanges: [exchangeKey, exchangeName], provider: p) == nil {
            return fallbackSeriesAllowed(exchangeKey: exchangeKey, base: base, provider: p)
        }
        let providerKey: String? = TokenIdentity.providerExchanges[p]
        let providerName: String? = providerKey.flatMap { pairName(exchangeKey: $0, base: base) }
        return TokenIdentity.fallbackChangeAllowed(symbol: base, ownName: pairName(exchangeKey: exchangeKey, base: base),
                                                   provider: p, binanceName: binanceName(base),
                                                   providerName: providerName)
    }

    /// Darf die Quelle `host` der Kerzen-Kette für `base` einspringen, wenn die eigene Seite `ownName` heisst?
    /// Gleiche Namenssperre wie Mini-Chart und Widget. Wie `TokenIdentityStore.chainSourceAllowed` (Android).
    func chainSourceAllowed(base: String, ownName: String?, host: String) -> Bool {
        let provider: String = DataFreshness.candleProvider(host: host)
        let providerName: String? = TokenIdentity.providerExchanges[provider].flatMap { pairName(exchangeKey: $0, base: base) }
        return TokenIdentity.chainSourceAllowed(symbol: base, ownName: ownName, host: host, binanceName: binanceName(base),
                                                providerName: providerName)
    }
}
