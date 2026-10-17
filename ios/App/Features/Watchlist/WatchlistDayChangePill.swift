import SwiftUI

/// Veränderung gemäss %-Basis (`Watch.change24h`): `ChangePill` und davor klein und grau der Zeitraum
/// «24h», «letzter Stand», «heute» oder «heute UTC» — wie `DayChangePill` in Android. Ohne Bezug
/// (oder bei veralteter Basis) ein graues «—»; die Veränderung seit der letzten
/// Abfrage nur bei der Basis «Seit letzter Aktualisierung». VoiceOver: «gestiegen um 2.30% in 24 Stunden» / «… heute».
struct WatchlistDayChangePill: View {
    /// Das Paar; die Anzeige wählt selbst den Wert gemäss %-Basis (`ChangeView.shown(_: Watch)`).
    let watch: Watch
    /// Klein in der Zeile, mittel im Aktionsblatt (wie Android).
    var size: ChangePillSize = .small
    /// Hinweis für VoiceOver nach dem Wert, z. B. «Veränderung aus Binance-Kerzen».
    var note: String? = nil
    /// Wert aus den Kerzen einer anderen Börse (`DayChange.rowChangeMarker`): kleines «*» in `onSurfaceVariant`
    /// dahinter, nur sichtbar — gesprochen wird die Quelle im Zeilensatz, erklärt im Aktionsblatt (wie Android).
    var marker = false
    @Environment(\.changeView) private var changeView

    /// Marker der %-Änderung aus fremden Kerzen (Android `CHANGE_SOURCE_MARKER`).
    static let sourceMarker = "*"

    var body: some View {
        let value = changeView.shown(watch)
        // Hyperliquid: % Mark gegen Mark, Kurs = Mittelkurs — als «Mark» benennen (wie Android `mark`)
        let mark: String? = ChangeBasisMath.showsMark(
            changeIsMark: MarketsConfig.market(watch.marketKey)?.changeIsMark == true, basis: changeView.basis
        ) ? L("watch_change_mark") : nil
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            // Zeitraum klein und grau vor der Änderung: «24h ↘ −0.25%» — die Zahlen stehen so bündig rechts
            if changeView.showPeriod {
                Text(A11y.changeShortLabel(changeView.basis))
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
            }
            if let mark {
                Text(mark)
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
            }
            // Deaktiviertes Paar: letzter Wert in neutralem Grau, ohne Kursfarbe
            ChangePill(change: value, size: size, dashWhenMissing: true, neutral: watch.disabled)
            if marker {
                // Hochgestellt wie eine Fussnote, dicht an der Zahl
                Text(Self.sourceMarker)
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .baselineOffset(4)
                    .padding(.leading, -3)
            }
        }
        // Label wächst mit der Textgrösse, aber nicht über AX2 (wie die Änderung)
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
        // Vorgelesen mit Richtungswort und Zeitraum statt «+»/«−»
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(A11y.join([A11y.change(value, basis: changeView.basis), mark, note]))
    }
}
