import SwiftUI

/// Einmaliger Hinweis über der Merkliste, solange Paare einer stillgelegten Börse darin stehen
/// (`ExchangeRetirement.noticeExchanges`): «Bitpanda liefert seit 1. Februar 2027 keine öffentlichen Kurse mehr. Wähle für
/// diese Coins eine andere Börse.» Mit ✕ (44 pt, «Hinweis schliessen») für diese Börse geschlossen
/// (`RetiredNoticeDismissals`), an derselben Stelle wie «2 Coins ohne Logo» (`WatchlistMissingLogos`). Wie Android
/// `WatchlistRetiredNotice`.
@MainActor
struct WatchlistRetiredNotice: View {
    /// Alle Paare der Merkliste.
    let watches: [Watch]
    @ObservedObject private var dismissals = RetiredNoticeDismissals.shared

    var body: some View {
        // Ohne Hinweis gar keine Ansicht (kein Abstand im Kopf); neu bewertet mit jedem Neuzeichnen des Kopfs
        let exchanges = ExchangeRetirement.noticeExchanges(watches.map(\.exchangeName), dismissed: dismissals.keys)
        if let name = exchanges.first { line(name).transition(.opacity) }
    }

    /// Text und ✕ (mindestens 44 pt hoch).
    private func line(_ name: String) -> some View {
        HStack(spacing: 0) {
            Text(L("exchange_retired_notice", name, Self.endDate(name)))
                .font(.caption)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, minHeight: Self.touch, alignment: .leading)
            Button { dismissals.dismiss(name) } label: {
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

    /// Tag des Endes in der Sprache der App («1. Februar 2027»), als UTC-Datum (das Ende liegt um 00:00 UTC).
    static func endDate(_ exchange: String) -> String {
        guard let end = ExchangeRetirement.endsAt(exchange) else { return "" }
        let f = DateFormatter()
        f.dateStyle = .long
        f.timeStyle = .none
        f.timeZone = TimeZone(identifier: "UTC")
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(end) / 1000))
    }

    /// Mindest-Tippfläche (✕).
    private static let touch: CGFloat = 44
}

/// Mit ✕ geschlossene Hinweise (`WatchlistRetiredNotice`) je Börse: nur auf diesem Gerät (`SharedStorage.defaults`, wie
/// die übrigen Oberflächen-Merker), nie synchronisiert, nicht in der eigenen Sicherung. Wie Android `RetiredNoticeStore`.
@MainActor
final class RetiredNoticeDismissals: ObservableObject {
    static let shared = RetiredNoticeDismissals()
    static let key = "retired_exchange_notice_dismissed"
    @Published private(set) var keys: Set<String>

    private init() {
        keys = Set(SharedStorage.defaults.stringArray(forKey: Self.key) ?? [])
    }

    /// ✕: Hinweis für `exchange` schliessen.
    func dismiss(_ exchange: String) {
        let name = exchange.trimmingCharacters(in: .whitespaces).lowercased()
        guard !name.isEmpty else { return }
        keys.insert(name)
        SharedStorage.defaults.set(keys.sorted(), forKey: Self.key)
    }
}
