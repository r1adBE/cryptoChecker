import SwiftUI

/// Veränderung gemäss %-Basis (`Watch.change24h`): `ChangePill` und daneben klein der Zeitraum
/// «24h», «letzter Stand», «heute» oder «heute UTC» — wie `DayChangePill` in Android. Ohne Bezug
/// (oder bei veralteter Basis) eine graue Pille «—» ohne Pfeil; die Veränderung seit der letzten
/// Abfrage nur bei der Basis «Seit letzter Aktualisierung». VoiceOver: «gestiegen um 2.30% in 24 Stunden» / «… heute».
struct WatchlistDayChangePill: View {
    /// Das Paar; die Pille wählt selbst den Wert gemäss %-Basis (`ChangeView.shown(_: Watch)`).
    let watch: Watch
    var large = false
    /// Hinweis für VoiceOver nach dem Wert, z. B. «Veränderung aus Binance-Kerzen».
    var note: String? = nil
    @Environment(\.changeView) private var changeView

    var body: some View {
        let value = changeView.shown(watch)
        HStack(spacing: 4) {
            ChangePill(change: value, large: large, dashWhenMissing: true)
            if changeView.showPeriod {
                Text(A11y.changeShortLabel(changeView.basis))
                    .font(.system(large ? Font.TextStyle.caption : .caption2, design: .rounded))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
            }
        }
        // Label wächst mit der Textgrösse, aber nicht über AX2 (wie die Pille)
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
        // Vorgelesen mit Richtungswort und Zeitraum statt «+»/«−»
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(A11y.join([A11y.change(value, basis: changeView.basis), note]))
    }
}
