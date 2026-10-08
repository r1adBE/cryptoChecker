import SwiftUI

/// Veränderung gemäss %-Basis (`Watch.change24h`): `ChangePill` und daneben klein der Zeitraum
/// «24h», «heute» oder «heute UTC» — wie `DayChangePill` in Android. Ohne Bezug (oder bei
/// veralteter Basis) eine graue Pille «—» ohne Pfeil, nie die Veränderung seit der letzten
/// Abfrage. VoiceOver: «gestiegen um 2.30% in 24 Stunden» / «… heute».
struct WatchlistDayChangePill: View {
    /// Gespeicherte Veränderung (`Watch.shownChange24h`); die Pille prüft selbst die %-Basis.
    let change: Double?
    var large = false
    @Environment(\.changeView) private var changeView

    var body: some View {
        let value = changeView.shown(change)
        HStack(spacing: 4) {
            ChangePill(change: value, large: large, dashWhenMissing: true)
            Text(A11y.changeShortLabel(changeView.basis))
                .font(.system(large ? Font.TextStyle.caption : .caption2, design: .rounded))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .lineLimit(1)
        }
        // Label wächst mit der Textgrösse, aber nicht über AX2 (wie die Pille)
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
        // Vorgelesen mit Richtungswort und Zeitraum statt «+»/«−»
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(A11y.change(value, basis: changeView.basis))
    }
}
