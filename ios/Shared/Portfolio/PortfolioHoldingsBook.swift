import Foundation

/// Laufender Bestand EINES Coins nach der Einstandsmethode `method` — die eine Regel für Karte, «Heute/7 T/1 J»,
/// Wertverlauf, Widget, Alarm und Stichtag (gleiche Mengen wie `PortfolioFifo` bzw. der Ø im Rechner). Wie
/// `PortfolioHoldingsBook` (Android): Bei allen Methoden liegen Adress-Teile ohne Einstand (`AddressBalance.isAddressPart`)
/// in einem eigenen Topf wie auf der Karte (`AddressBalance.split`) — ein eigener Abgang über den eigenen Bestand hinaus
/// bleibt ungedeckt und nimmt nichts vom Adress-Teil, ein Adress-Abgang nur aus dem Adress-Teil. FIFO und Ø sonst ein
/// Topf (`PortfolioLedger.holdingsAfter`); FIFO je Wallet je Wallet ein Topf, Abgänge auf den Bestand DIESER Wallet
/// gekappt (der Rest ist ungedeckt, nichts aus anderen Wallets), eine Umbuchung verschiebt (zuerst die Netzwerkgebühr,
/// dann die Menge; was fehlt, kommt nicht an). Eröffnungsposten (`AddressCostLots.isLotTrade`) wie in `PortfolioFifo` in
/// ihrer Wallet (der Adresse), sonst bliebe ein Abgang aus dieser Wallet (Verkauf dort, Umbuchung weg) ungedeckt und der
/// Posten zählte weiter. `cap(for:)` = Bestand, auf den `PortfolioLedger.flow` die Abgänge eines Schritts kappt: der Topf
/// des Schritts.
struct PortfolioHoldingsBook {
    let method: CostMethod
    /// Bestand gesamt, exakt.
    private(set) var total: Decimal = 0
    private var wallets: [String: Decimal] = [:]
    /// Eigener Topf der Adress-Teile ohne Einstand (kein gültiger Wallet-Schlüssel).
    private static let addressPot = "\u{0}address"
    /// FIFO, Ø: Topf der eigenen Transaktionen samt Eröffnungsposten.
    private static let ownPot = "\u{0}own"

    init(method: CostMethod = .defaultMethod) {
        self.method = method
    }

    private var perWallet: Bool { method == .FIFO_WALLET }

    private func walletKey(_ e: PortfolioLedgerEvent, _ raw: String?) -> String {
        AddressBalance.isAddressPart(e.trade.id) ? Self.addressPot : PortfolioWallets.key(raw)
    }

    private func held(_ key: String) -> Decimal { wallets[key] ?? 0 }

    private mutating func add(_ key: String, _ amount: Decimal) {
        wallets[key] = max(0, held(key) + amount)
        total = max(0, total + amount)
    }

    /// FIFO, Ø: eigener Topf bzw. Adress-Topf.
    private func pot(of e: PortfolioLedgerEvent) -> String {
        AddressBalance.isAddressPart(e.trade.id) ? Self.addressPot : Self.ownPot
    }

    /// Topf, aus dem `e` abgeht: je Wallet die Wallet, sonst eigener Bestand bzw. Adress-Teil.
    func cap(for e: PortfolioLedgerEvent) -> Decimal {
        guard perWallet else { return held(pot(of: e)) }
        let t = e.trade
        let raw = e.feeLeg ? PortfolioWallets.of(e) : (t.type == .TRANSFER ? t.transferFrom : t.exchange)
        return held(walletKey(e, raw))
    }

    /// Bestand nach `e`.
    mutating func apply(_ e: PortfolioLedgerEvent) {
        guard perWallet else {
            let key = pot(of: e)
            add(key, PortfolioLedger.holdingsAfter(held(key), e) - held(key))
            return
        }
        let t = e.trade
        guard t.amountExact > 0 else { return }
        if e.feeLeg {
            let key = walletKey(e, PortfolioWallets.of(e))
            add(key, -min(t.thirdFeeAmount, held(key)))
            return
        }
        if e.swapIn {
            if t.swapReceived > 0 { add(walletKey(e, t.exchange), t.swapReceived) }
            return
        }
        switch t.type {
        case .BUY, .REWARD, .DEPOSIT:
            if t.receivedExact > 0 { add(walletKey(e, t.exchange), t.receivedExact) }
        case .SELL, .PAYMENT, .GIFT, .WITHDRAWAL, .SWAP:
            let key = walletKey(e, t.exchange)
            add(key, -min(t.amountExact + t.coinFeeExact, held(key)))
        case .TRANSFER:
            let src = walletKey(e, t.transferFrom)
            let dst = walletKey(e, t.transferTo)
            // Netzwerkgebühr zuerst (verlässt das Portfolio), dann wandert die Menge — soweit die Quelle reicht
            add(src, -min(t.coinFeeExact, held(src)))
            if src != dst {
                let moved = min(t.amountExact, held(src))
                add(src, -moved)
                add(dst, moved)
            }
        }
    }
}
