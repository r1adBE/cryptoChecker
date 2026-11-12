import SwiftUI

/// Einmaliger Hinweis über der Merkliste, solange Paare einer stillgelegten Börse darin stehen
/// (`ExchangeRetirement.noticeExchanges`): «Bitpanda liefert seit 1. Februar 2027 keine öffentlichen Kurse mehr. Wähle für
/// diese Coins eine andere Börse.» Davor, ab der Ankündigung (`ExchangeRetirement.advanceNoticeExchanges`, 1. Januar
/// 2027): «Bitpanda liefert ab 1. Februar 2027 keine öffentlichen Kurse mehr …». Je mit ✕ (44 pt, «Hinweis schliessen»)
/// für diese Börse geschlossen — getrennt gespeichert (`RetiredNoticeDismissals`), an derselben Stelle wie «2 Coins ohne
/// Logo» (`WatchlistMissingLogos`). Wie Android `WatchlistRetiredNotice`.
@MainActor
struct WatchlistRetiredNotice: View {
    /// Alle Paare der Merkliste.
    let watches: [Watch]
    @ObservedObject private var dismissals = RetiredNoticeDismissals.shared

    var body: some View {
        // Ohne Hinweis gar keine Ansicht (kein Abstand im Kopf); neu bewertet mit jedem Neuzeichnen des Kopfs
        // Entschieden über den Schlüssel der Börse (nicht den gespeicherten Namen), gezeigt der Name aus dem Katalog
        let keys = watches.map(\.exchangeKey)
        if let key = ExchangeRetirement.noticeExchanges(keys, dismissed: dismissals.keys).first {
            line(key, advance: false).transition(.opacity)
        } else if let key = ExchangeRetirement.advanceNoticeExchanges(keys, dismissed: dismissals.keys).first {
            line(key, advance: true).transition(.opacity)
        }
    }

    /// Text und ✕ (mindestens 44 pt hoch); `advance` = Ankündigung vor dem Ende (eigenes ✕, gemerkt je Schlüssel).
    private func line(_ key: String, advance: Bool) -> some View {
        let date = RetirementText.endDate(key)
        let name = RetirementText.name(ofKey: key)
        return HStack(spacing: 0) {
            Text(advance ? L("exchange_retiring_notice", name, date) : L("exchange_retired_notice", name, date))
                .font(.caption)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, minHeight: Self.touch, alignment: .leading)
            Button { if advance { dismissals.dismissAdvance(key) } else { dismissals.dismiss(key) } } label: {
                Image(systemName: "xmark")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .frame(width: Self.touch, height: Self.touch)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L("watchlist_missing_logos_dismiss"))
        }
        .accessibilityIdentifier("retired_exchange_line")
    }

    /// Mindest-Tippfläche (✕).
    private static let touch: CGFloat = 44
}
