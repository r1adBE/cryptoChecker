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
            let interval = signal.factor.flatMap { $0.isFinite ? Int($0.rounded()) : nil }
            return L(signal.value >= 0 ? "activity_signal_funding_long" : "activity_signal_funding_short",
                     fundingValue(signal.value, intervalHours: interval))
        }
    }

    /// Funding-Satz der Börse mit drei Nachkommastellen; nicht alle 8 Stunden abgerechnet mit «pro Std.»
    /// bzw. «pro 4 Std.» (`intervalHours`), damit der Wert nicht wie ein 8-Stunden-Satz gelesen wird.
    static func fundingValue(_ value: Double, intervalHours: Int?) -> String {
        let text = percent(value, 3)
        if intervalHours == 1 { return text + " " + L("futures_funding_per_hour") }
        if let h = intervalHours, h != 8, (2...24).contains(h) { return text + " " + L("futures_funding_per_hours", h) }
        return text
    }

    /// «+2.9%» / «−2.9%» / «0.0%» (Deutsch «+2,9 %»), Vorzeichen als echtes Minus, gerundet 0 ohne Vorzeichen.
    /// Prozentzeichen der Sprache über `PercentText` — gleich wie die Kursänderungen (vorher immer «2.9 %»).
    static func percent(_ value: Double, _ decimals: Int) -> String {
        guard value.isFinite else { return "—" }
        // RTL: als Insel, sonst stünde das Vorzeichen hinter der Zahl
        return BidiText.ltr(PercentText.signed(value, decimals: decimals))
    }

    /// «4.2×»-Zahl ohne Zeichen, eine Nachkommastelle.
    static func factor(_ value: Double) -> String {
        guard value.isFinite else { return "—" }
        return String(format: "%.1f", locale: Locale.current, value)
    }
}
