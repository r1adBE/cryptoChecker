import SwiftUI

/// Eine ruhige Zeile unter dem Status, solange nach der Installation bzw. nach dem Leeren des Zwischenspeichers Logos
/// laden: «Logos werden geladen …» (`CoinLogoLoading`, `LogoLoadingHint`), sobald die Gesamtzahl bekannt ist mit
/// Fortschritt «Logos werden geladen · 40 %» (Prozent in der Schreibweise der Sprache, `PercentText`; gedrosselt).
/// Schrift wie der Status («Börsenkurse · gerade eben»), in der ruhigen Nebenfarbe; blendet aus, wenn der Durchgang
/// endet. Nur beim ersten Laden. Schalter «Coin-Logos › In der App» aus: nie. VoiceOver: einmal höflich angesagt (ohne
/// Prozent, nicht bei jedem neuen Wert). Wie Android `WatchlistLogoHint`.
@MainActor
struct WatchlistLogoHint: View {
    @ObservedObject private var loading = CoinLogoLoading.shared
    @Environment(\.coinLogosEnabled) private var coinLogosEnabled

    var body: some View {
        // Ohne Zeile gar keine Ansicht (kein Abstand im Kopf); ein- und ausblenden über `CoinLogoLoading` (wie Android,
        // Bewegung reduzieren: ohne Überblenden)
        if coinLogosEnabled && loading.visible { Line(percent: loading.percent).transition(.opacity) }
    }

    /// Die Zeile selbst; ihr Zustand (schon angesagt) gilt, solange sie steht.
    private struct Line: View {
        let percent: Int?
        @State private var announced = false

        private var text: String {
            guard let percent else { return L("watchlist_logos_loading") }
            return L("watchlist_logos_loading_percent", PercentText.unsigned(Double(percent), decimals: 0))
        }

        var body: some View {
            Text(text)
                .font(.caption.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("logo_loading_line")
                .onAppear(perform: announce)
        }

        /// Einmal je Erscheinen (nicht erneut nach einem Tab-Wechsel); höflich, unterbricht keine laufende Ansage.
        private func announce() {
            guard !announced else { return }
            announced = true
            var announcement = AttributedString(L("watchlist_logos_loading"))
            announcement.accessibilitySpeechAnnouncementPriority = .low
            AccessibilityNotification.Announcement(announcement).post()
        }
    }
}
