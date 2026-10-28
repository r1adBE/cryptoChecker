import SwiftUI

/// «Neuer Alarm» ohne Paar: erst das Paar wählen, dann dessen Alarm-Seite. Ein eigenes Blatt mit Suchfeld
/// (Abgleich wie die Merklisten-Suche, `WatchlistSearch.matches`), Favoriten oben und Lazy-Liste statt eines
/// Bestätigungsdialogs mit bis zu 2000 Knöpfen — wie Android `AlarmPairPicker`. Zeilen ohne Kurs
/// vorberechnet und gefaltet (`Row`), neu nur bei Änderung der Paare (`Source`); «keine Treffer» wird angesagt,
/// Favoriten mit Stern. Der Titel ist als Navigationstitel schon eine Überschrift für VoiceOver.
/// Zeilen nicht bei jeder Erzeugung des Structs (`State(initialValue:)` rechnet eager), sondern in
/// `.task(id:)` im Hintergrund, nur wenn sich der Schlüssel der Paare (`sourceKey`) ändert.
struct AlarmPairPicker: View {
    let watches: [Watch]
    /// Schlüssel der Paare vom Elternteil (`AppData.alarmPairPickerInput`, einmal je Stand);
    /// nil = hier rechnen (gleicher Wert).
    var sourceKey: Int? = nil
    let onPick: (Int64) -> Void

    /// Zeile der Auswahl ohne Kurs, Suchfelder schon gefaltet — wie Android `AlarmPickerRow`.
    struct Row: Identifiable, Equatable {
        let id: Int64
        let displayName: String
        let exchangeName: String
        let favorite: Bool
        /// Suchfelder wie `WatchlistSearch.matches` ohne Coin-Namen, gefaltet (`ExplorerNameMatch.fold`).
        let folded: [String]
    }

    /// Was eine Zeile ausmacht (ohne Kurs): ändert sich das, werden die Zeilen neu gerechnet.
    struct Source: Hashable {
        let id: Int64
        let displayName: String
        let exchangeName: String
        let favorite: Bool
        let note: String?
        let logoPairKey: String

        init(_ watch: Watch) {
            id = watch.id
            displayName = watch.displayName
            exchangeName = watch.exchangeName
            favorite = watch.favorite
            note = watch.note
            logoPairKey = watch.logoPairKey
        }
    }

    @State private var query = ""
    /// Zeilen, beim Öffnen und danach nur bei geänderten Paaren im Hintergrund gerechnet.
    @State private var rows: [Row] = []
    /// Gefaltete Coin-Namen für die Suche (`WatchlistSearch.names`), mit den Zeilen gerechnet.
    @State private var names: [Int64: String] = [:]
    /// Zeilen schon gerechnet? Vorher kein «keine Treffer».
    @State private var loaded = false
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appAccent) private var accent

    #if DEBUG
    /// Aufrufe von `makeRows` (nur Tests: Erzeugen des Structs rechnet nicht). Hinter einer Sperre, weil
    /// `makeRows` im Hintergrund läuft.
    nonisolated private static let makeRowsCounter = LockedBox(0)
    nonisolated static var makeRowsCalls: Int { makeRowsCounter.current }
    #endif

    /// Schlüssel der Paare ohne Kurs (`Source`): Hash statt Array, billig in `body`.
    nonisolated static func sourceKey(_ watches: [Watch]) -> Int {
        var hasher = Hasher()
        hasher.combine(watches.count)
        for watch in watches { hasher.combine(Source(watch)) }
        return hasher.finalize()
    }

    /// Zeilen in der Reihenfolge der Merkliste — wie Android `alarmPickerRows`.
    nonisolated static func makeRows(_ watches: [Watch]) -> [Row] {
        #if DEBUG
        makeRowsCounter.write { $0 += 1 }
        #endif
        return watches.map { watch in
            Row(id: watch.id, displayName: watch.displayName, exchangeName: watch.exchangeName, favorite: watch.favorite,
                folded: [
                    watch.baseAsset,
                    watch.quoteAsset,
                    watch.displayPair,
                    watch.displayName,
                    watch.contractType.shortName ?? "",
                    watch.exchangeName,
                    watch.note ?? "",
                ].map(ExplorerNameMatch.fold))
        }
    }

    /// Coin-Namen je Paar-Id gefaltet.
    nonisolated static func foldedNames(_ names: [Int64: String]) -> [Int64: String] {
        names.mapValues(ExplorerNameMatch.fold)
    }

    /// Treffer der Suche (jedes Wort muss ein Feld oder den Coin-Namen treffen), Favoriten zuerst, sonst in der
    /// Reihenfolge der Merkliste (`alarm_pair_picker.json`) — wie Android `alarmPairChoices`.
    static func choices(_ rows: [Row], query: String, foldedNames: [Int64: String]) -> [Row] {
        let tokens = ExplorerNameMatch.fold(query).split(whereSeparator: { $0.isWhitespace }).map(String.init)
        let hits = tokens.isEmpty ? rows : rows.filter { row in
            let name = foldedNames[row.id]
            return tokens.allSatisfy { token in
                row.folded.contains { $0.contains(token) } || (name?.contains(token) ?? false)
            }
        }
        return hits.filter(\.favorite) + hits.filter { !$0.favorite }
    }

    var body: some View {
        let shown = Self.choices(rows, query: query, foldedNames: names)
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        NavigationStack {
            List {
                if loaded && shown.isEmpty {
                    Text(L("watchlist_search_empty", trimmed))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                ForEach(shown) { row in
                    Button {
                        onPick(row.id)
                    } label: {
                        HStack(spacing: Spacing.sm) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.displayName)
                                    .foregroundStyle(AppColors.onSurface)
                                Text(row.exchangeName)
                                    .font(.footnote)
                                    .foregroundStyle(AppColors.onSurfaceVariant)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            // Favorit: Stern wie in der Merkliste, VoiceOver liest «Favorit» (nicht den Bildnamen)
                            if row.favorite {
                                Image(systemName: "star.fill")
                                    .font(.footnote)
                                    .foregroundStyle(accent.primary)
                                    .accessibilityLabel(L("a11y_favorite"))
                            }
                        }
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                        .accessibilityElement(children: .combine)
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
        // Nur bei geänderten Paaren neu rechnen, nicht bei jedem Kurs; im Hintergrund
        .task(id: sourceKey ?? Self.sourceKey(watches)) {
            let current = watches
            let coinNames = WatchlistSearch.names(for: current)
            let built = await Task.detached(priority: .userInitiated) {
                (Self.makeRows(current), Self.foldedNames(coinNames))
            }.value
            guard !Task.isCancelled else { return }
            rows = built.0
            names = built.1
            loaded = true
        }
        // Keine Treffer: VoiceOver sagt es beim Tippen an (wie die Live-Region in Android)
        .onChange(of: loaded && shown.isEmpty ? trimmed : nil) { _, empty in
            if let empty { AccessibilityNotification.Announcement(L("watchlist_search_empty", empty)).post() }
        }
    }
}
