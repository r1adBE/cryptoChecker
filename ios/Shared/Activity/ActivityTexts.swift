import Foundation

/// Texte und Zahlenformate für «Ungewöhnliche Aktivität». Dieselben Sätze in
/// Mitteilung, Liste und «Warum»-Blatt — wie `ActivityTexts.kt`.
enum ActivityTexts {

    /// Ein Satz je Signal, z. B. «Ungewöhnliche Bewegung: +4.2 % in einer Stunde (3.4× normal)».
    static func signal(_ signal: ActivitySignal) -> String {
        switch signal.kind {
        case .PRICE_MOVE:
            return L("activity_signal_price_move", percent(signal.value, 1), factor(signal.factor ?? 0))
        case .VOLUME_SPIKE:
            return L("activity_signal_volume", factor(signal.value))
        case .OPEN_INTEREST_JUMP:
            if let minutes = signal.factor, minutes.isFinite {
                return L("activity_signal_open_interest", percent(signal.value, 1), Int(minutes))
            }
            return L("activity_signal_open_interest_no_time", percent(signal.value, 1))
        case .FUNDING_EXTREME:
            return L(signal.value >= 0 ? "activity_signal_funding_long" : "activity_signal_funding_short",
                     percent(signal.value, 3))
        }
    }

    /// «+2.9 %» / «−2.9 %» / «0.0 %», Vorzeichen als echtes Minus.
    static func percent(_ value: Double, _ decimals: Int) -> String {
        guard value.isFinite else { return "—" }
        let rounded = String(format: "%.\(decimals)f", locale: Locale.current, abs(value))
        let isZero = rounded.allSatisfy { $0 == "0" || $0 == "." || $0 == "," }
        let sign = isZero ? "" : (value > 0 ? "+" : "−")
        return "\(sign)\(rounded) %"
    }

    /// «4.2×»-Zahl ohne Zeichen, eine Nachkommastelle.
    static func factor(_ value: Double) -> String {
        guard value.isFinite else { return "—" }
        return String(format: "%.1f", locale: Locale.current, value)
    }
}
