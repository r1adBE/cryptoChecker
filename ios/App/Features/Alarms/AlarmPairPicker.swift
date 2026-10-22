import SwiftUI

/// «Neuer Alarm» ohne Paar: erst das Paar wählen, dann dessen Alarm-Seite. Seit Build 103 ein eigenes Blatt mit Suchfeld
/// (Abgleich wie die Merklisten-Suche, `WatchlistSearch.matches`), Favoriten oben und Lazy-Liste statt eines
/// Bestätigungsdialogs mit bis zu 2000 Knöpfen — wie Android `AlarmPairPicker`.
struct AlarmPairPicker: View {
    let watches: [Watch]
    let onPick: (Int64) -> Void

    @State private var query = ""
    /// Coin-Namen für die Suche, einmal beim Öffnen gelesen (`WatchlistSearch.names`).
    @State private var names: [Int64: String] = [:]
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appAccent) private var accent

    /// Treffer der Suche, Favoriten zuerst, sonst in der Reihenfolge der Merkliste (`alarm_pair_picker.json`) — wie
    /// Android `alarmPairChoices`.
    static func choices(_ watches: [Watch], query: String, names: [Int64: String]) -> [Watch] {
        let blank = query.trimmingCharacters(in: .whitespaces).isEmpty
        let hits = blank ? watches : watches.filter { WatchlistSearch.matches($0, query: query, name: names[$0.id]) }
        return hits.filter(\.favorite) + hits.filter { !$0.favorite }
    }

    var body: some View {
        let shown = Self.choices(watches, query: query, names: names)
        NavigationStack {
            List {
                if shown.isEmpty {
                    Text(L("watchlist_search_empty", query.trimmingCharacters(in: .whitespaces)))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                ForEach(shown) { watch in
                    Button {
                        onPick(watch.id)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(watch.displayName)
                                .foregroundStyle(AppColors.onSurface)
                            Text(watch.marketName)
                                .font(.footnote)
                                .foregroundStyle(AppColors.onSurfaceVariant)
                        }
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                }
            }
            .listStyle(.plain)
            // Suchfeld mit Bezeichnung für VoiceOver (Platzhalter = Bezeichnung)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always),
                        prompt: Text(L("watchlist_search_hint")))
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .navigationTitle(L("alarms_add"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel")) { dismiss() }
                }
            }
        }
        .tint(accent.primary)
        .onAppear { names = WatchlistSearch.names(for: watches) }
    }
}
