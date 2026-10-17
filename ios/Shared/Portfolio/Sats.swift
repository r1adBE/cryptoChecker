import Foundation

/// «1 CHF = 1’234 Sats» unter dem Kurs eines Bitcoin-Paars (Aktionsblatt): wie viele Satoshi
/// (1 BTC = 100 Mio. Sats) eine Einheit der Umrechnungswährung kauft. Der Kurs des Paars wird mit
/// dem bestehenden Umrechnungsfaktor (Quote → Umrechnungswährung) umgerechnet. Wie `Sats.kt`.
enum Sats {
    static let perBtc = 100_000_000.0

    /// Nur Paare mit Basis BTC (nicht WBTC o. Ä.).
    static func isBitcoin(_ baseAsset: String?) -> Bool {
        baseAsset?.trimmingCharacters(in: .whitespaces).uppercased() == "BTC"
    }

    /// Sats je Einheit der Zielwährung: 1e8 / (Kurs in der Quote × Faktor Quote → Ziel);
    /// nil ohne gültigen Kurs oder Faktor.
    static func perUnit(price: Double?, rate: Double?) -> Double? {
        guard let price, price.isFinite, price > 0, let rate, rate.isFinite, rate > 0 else { return nil }
        let inTarget = price * rate
        guard inTarget.isFinite, inTarget > 0 else { return nil }
        let sats = perBtc / inTarget
        return sats.isFinite && sats > 0 ? sats : nil
    }

    /// Nachkommastellen der Anzeige: ab 100 Sats ganze Zahlen, ab 1 eine Stelle, sonst drei.
    static func decimals(_ sats: Double) -> Int {
        if sats >= 100 { return 0 }
        if sats >= 1 { return 1 }
        return 3
    }
}
