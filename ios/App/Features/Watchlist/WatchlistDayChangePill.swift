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
    /// dahinter — gesprochen wird die Quelle im Zeilensatz, erklärt im Aktionsblatt (wie Android).
    var marker = false
    /// Kurzer Hinweis zur Quelle beim Tippen auf das «*» (als Popover am Zeichen, ohne das Aktionsblatt der Zeile
    /// zu öffnen); nil = kein Tippziel. VoiceOver: Aktion der Zeile, die `showMarkerHint` setzt (wie Android).
    var markerHint: String? = nil
    var showMarkerHint: Binding<Bool> = .constant(false)
    @Environment(\.changeView) private var changeView

    /// Marker der %-Änderung aus fremden Kerzen (Android `CHANGE_SOURCE_MARKER`).
    static let sourceMarker = "*"

    var body: some View {
        let value = changeView.shown(watch)
        // Änderung gegen den Mark-Preis (`Market.changeIsMark`): als «Mark» benennen (wie Android `mark`)
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
                let star = Text(Self.sourceMarker)
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .baselineOffset(4)
                    .padding(.leading, -3)
                if let markerHint {
                    star.markerTapTarget(hint: markerHint, isPresented: showMarkerHint)
                } else {
                    star
                }
            }
        }
        // Label wächst mit der Textgrösse, aber nicht über AX2 (wie die Änderung)
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
        // Vorgelesen mit Richtungswort und Zeitraum statt «+»/«−»
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(A11y.join([A11y.change(value, basis: changeView.basis), mark, note]))
    }
}

private extension View {
    /// Tippfläche von 44 × 44 pt um das kleine «*», als durchsichtige Fläche darüber gelegt — Zeile und Ausrichtung
    /// bleiben unverändert, die Fläche ragt über (wie Android `markerTouchTarget`). Der eigene Tipp hat Vorrang vor
    /// dem Tipp der Zeile, öffnet also nicht zusätzlich das Aktionsblatt.
    func markerTapTarget(hint: String, isPresented: Binding<Bool>) -> some View {
        overlay {
            Color.clear
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
                .onTapGesture { isPresented.wrappedValue = true }
                .popover(isPresented: isPresented, arrowEdge: .top) {
                    Text(hint)
                        .font(.subheadline)
                        .foregroundStyle(AppColors.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: 280, alignment: .leading)
                        .padding(Spacing.lg)
                        // Auch auf dem iPhone als kleine Sprechblase statt als Blatt
                        .presentationCompactAdaptation(.popover)
                }
        }
    }
}
