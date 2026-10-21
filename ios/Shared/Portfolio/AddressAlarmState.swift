import Foundation

/// Was die beobachteten Adressen für die Portfolio-Alarme bedeuten — wie Android `AddressAlarmState.kt`.
///
/// - `pending`: zählende Adressen, deren Guthaben noch nie geladen wurde und die noch eine Chance haben (`isPending`): sie
///   fehlen im Gesamtwert — Portfolio-Alarme prüfen dann nicht.
/// - `failing`: zählende Adressen, die nie geladen haben und seit über `pendingMillis` scheitern (Name: Bezeichnung oder
///   gekürzte Adresse). Seit Build 95 sperren sie die Alarme nicht mehr ohne Ende: die Alarme rechnen ohne sie, der Hinweis
///   in der Alarm-Übersicht nennt sie.
/// - `staleTokenAddresses` / `staleTokenAmounts`: zählende Token-Guthaben, deren Wert der letzte Abruf nicht bestätigt hat
///   (`WatchedAddress.staleTokens`, z. B. bekannter TON-Jetton fehlt auf einer vollen Seite) — sie gelten für die Alarme wie
///   ein alter Kurs (`PortfolioAlarmLogic.staleMaterial`).
///
/// Gleiche Regeln wie `AddressBalance.holdings`: einzelne Bitcoin-Adressen in einem beobachteten xpub zählen über den xpub,
/// gleiche Adressen einmal.
struct AddressAlarmState: Equatable, Sendable {
    var pending = 0
    var failing: [String] = []
    var staleTokenAddresses: [String] = []
    /// Menge je Coin der nicht bestätigten Token-Guthaben (Coin wie `TokenHolding.coin`).
    var staleTokenAmounts: [String: Decimal] = [:]

    /// So lange nach dem Hinzufügen (oder Einspielen auf diesem Gerät, `WatchedAddress.pendingSince`) gilt eine nie geladene
    /// Adresse mit Fehler noch als «lädt noch».
    static let pendingMillis: Int64 = 24 * 3_600_000

    /// Wert der nicht bestätigten Token-Guthaben in USDT zu den Kursen `prices` (Coin → USDT-Kurs); Coins ohne Kurs zählen
    /// nicht (sie fehlen schon als «ohne Kurs», `missingPrices`).
    func staleTokenValue(prices: [String: Double]) -> Double {
        guard !staleTokenAmounts.isEmpty else { return 0 }
        var byCoin: [String: Double] = [:]
        for (coin, price) in prices { byCoin[PortfolioCalculator.normalizeCoin(coin)] = price }
        var sum = 0.0
        for (coin, amount) in staleTokenAmounts {
            guard let price = byCoin[PortfolioCalculator.normalizeCoin(coin)], price.isFinite, price > 0 else { continue }
            sum += NSDecimalNumber(decimal: amount).doubleValue * price
        }
        return sum
    }

    /// Noch nie geladen (`lastBalance` fehlt oder ungültig) und entweder noch kein Fehler oder vor weniger als
    /// `pendingMillis` hinzugefügt (Uhr zurückgestellt: zählt als frisch). Danach gilt sie als fehlgeschlagen.
    /// Massgebend ist der spätere von `addedAt` und `pendingSince`: eingespielte Adressen behalten ihr altes `addedAt`
    /// (auch 0) und bekämen sonst keine Chance zu laden.
    static func isPending(_ a: WatchedAddress, now: Int64) -> Bool {
        !loaded(a) && (a.lastError == nil || now - max(a.addedAt, a.pendingSince) < pendingMillis)
    }

    private static func loaded(_ a: WatchedAddress) -> Bool {
        guard let value = PortfolioDecimal.parse(a.lastBalance) else { return false }
        return value >= 0
    }

    private static func name(_ a: WatchedAddress) -> String { a.label ?? WatchedAddressRules.shortAddress(a.address) }

    private static func same(_ x: WatchedAddress, _ y: WatchedAddress) -> Bool {
        x.chain == y.chain && WatchedAddressRules.sameAddress(x.chain, x.address, y.address)
    }

    static func of(_ addresses: [WatchedAddress], now: Int64) -> AddressAlarmState {
        let counted = addresses.filter(\.countInPortfolio)
        let covered = WatchedAddressRules.coveredByXpub(counted)
        let loadedList = counted.filter { loaded($0) }
        var state = AddressAlarmState()
        for (i, a) in counted.enumerated() {
            // Gleiche Adresse doppelt: nur die erste zählt (wie `AddressBalance.holdings`)
            if counted[..<i].contains(where: { same($0, a) }) { continue }
            if !a.staleTokens.isEmpty {
                let kept = a.tokenHoldings().filter { a.staleTokens.contains($0.key) }
                if !kept.isEmpty { state.staleTokenAddresses.append(name(a)) }
                for h in kept {
                    guard let value = PortfolioDecimal.parse(h.amount) else { continue }
                    state.staleTokenAmounts[h.coin] = (state.staleTokenAmounts[h.coin] ?? 0) + value
                }
            }
            if a.chain == .BTC && !a.isXpub && covered.contains(a.address) { continue }
            if loadedList.contains(where: { same($0, a) }) { continue }
            if isPending(a, now: now) { state.pending += 1 } else { state.failing.append(name(a)) }
        }
        return state
    }
}

extension WatchedAddressRules {
    /// Token, deren alter Wert mangels Antwort bleibt (TON: volle Jetton-Seite) → `WatchedAddress.staleTokens`, nur mit
    /// gespeichertem Guthaben > 0 (ohne alten Wert ist nichts veraltet); nil = unverändert (z. B. Token-Abfrage gescheitert).
    /// Teil von `applyFetched` — wie Android.
    static func withKeptTokens(_ a: WatchedAddress, _ keptTokens: [String]?) -> WatchedAddress {
        guard let keptTokens, ChainAssets.hasTokens(a.chain) else { return a }
        var out = a
        var kept: [String] = []
        for key in keptTokens where !kept.contains(key) && (PortfolioDecimal.parse(a.tokenBalances[key]) ?? 0) > 0 {
            kept.append(key)
        }
        out.staleTokens = kept
        return out
    }
}
