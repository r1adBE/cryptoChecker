import Foundation

/// Portfolio-Wert auf der Uhr (nur mit dem Schalter «Portfolio-Wert auf der Uhr», Standard aus): Gesamtwert `total`,
/// Veränderung heute in Prozent `changePercent`, Währung `currency` und Stand `time` (Epoch-ms) — keine Bestände, keine
/// Transaktionen. `hidden`: «Beträge verbergen» oder Portfolio-Sperre auf dem iPhone; dann gehen keine Zahlen an die
/// Uhr, sie zeigt «•••». Wie Android `WatchPortfolio` (`Parity/watch_link.json`).
struct WatchPortfolio: Codable, Equatable, Sendable {
    var total: Double?
    var changePercent: Double?
    var currency: String
    var time: Int64
    var hidden: Bool

    /// Sendung an die Uhr: Wert oder `.clear` (Schalter aus bzw. Portfolio aus — die Uhr entfernt den Wert).
    enum Message: Equatable, Sendable {
        case clear
        case value(WatchPortfolio)
    }

    private static func validCurrency(_ text: String) -> Bool {
        text.range(of: "^[A-Z]{3,5}$", options: .regularExpression) != nil
    }

    /// Währung einer verborgenen Sendung, wenn weder Aufnahme noch Einstellung eine gültige nennt.
    static let maskCurrency = "USD"

    /// Was das iPhone schickt: `.clear` ohne Schalter (`onWatch`) oder ohne Portfolio (`portfolioEnabled`); nil = noch
    /// keine Aufnahme (`time` ≤ 0, nichts senden). Verborgen (`hidden` oder `locked`) ohne Zahlen — und zwar immer, auch
    /// wenn die Aufnahme gerade nicht lesbar ist (fail closed): Währung dann `fallbackCurrency` (Portfolio-Währung der
    /// Einstellungen, sonst `maskCurrency`), Zeit `now`. Sonst blieben die Zahlen auf der Uhr. Wie Android.
    static func message(onWatch: Bool, portfolioEnabled: Bool, hidden: Bool, locked: Bool, total: Double?,
                        changePercent: Double?, currency: String?, time: Int64, fallbackCurrency: String? = nil,
                        now: Int64 = 0) -> Message? {
        guard onWatch, portfolioEnabled else { return .clear }
        func valid(_ text: String?) -> String? {
            guard let code = text?.trimmingCharacters(in: .whitespaces).uppercased(), validCurrency(code) else { return nil }
            return code
        }
        if hidden || locked {
            let code = valid(currency) ?? valid(fallbackCurrency) ?? maskCurrency
            let at = time > 0 ? time : (now > 0 ? now : 1)
            return .value(WatchPortfolio(total: nil, changePercent: nil, currency: code, time: at, hidden: true))
        }
        guard let code = valid(currency), time > 0 else { return nil }
        func finite(_ v: Double?) -> Double? { v.flatMap { $0.isFinite ? $0 : nil } }
        return .value(WatchPortfolio(total: finite(total), changePercent: finite(changePercent), currency: code,
                                     time: time, hidden: false))
    }

    static func toJson(_ message: Message) -> String {
        var o: [String: Any] = ["v": 1]
        switch message {
        case .clear:
            o["on"] = false
        case .value(let p):
            o["on"] = true
            o["h"] = p.hidden
            o["cur"] = p.currency
            o["t"] = p.time
            if !p.hidden {
                if let total = p.total { o["tot"] = total }
                if let pct = p.changePercent { o["pct"] = pct }
            }
        }
        let data = (try? JSONSerialization.data(withJSONObject: o)) ?? Data()
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    /// Liest `text`; Unlesbares, ohne Währung oder ohne Zeit → nil. Verborgen: Zahlen fallen weg, auch wenn da.
    static func fromJson(_ text: String?) -> Message? {
        guard let data = text?.data(using: .utf8),
              let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let on = o["on"] as? Bool else { return nil }
        guard on else { return .clear }
        guard let currency = o["cur"] as? String, validCurrency(currency),
              let time = (o["t"] as? NSNumber)?.int64Value, time > 0 else { return nil }
        let hidden = o["h"] as? Bool ?? false
        func number(_ key: String) -> Double? {
            guard !hidden, let v = (o[key] as? NSNumber)?.doubleValue, v.isFinite else { return nil }
            return v
        }
        return .value(WatchPortfolio(total: number("tot"), changePercent: number("pct"), currency: currency,
                                     time: time, hidden: hidden))
    }
}
