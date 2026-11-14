import SwiftUI
import UIKit

/// Ruhige Zeile «2 Coins ohne Logo» an der Stelle von «Logos werden geladen …» (`WatchlistLogoHint`), wenn Coins der
/// Merkliste nach dem ersten Laden weder ein mitgeliefertes noch ein geladenes Logo haben (`MissingLogoNotice`: welche
/// Coins zählen, wann sichtbar; geprüft von `WatchlistMissingLogosCheck`). Nie, solange Logos laden; Schalter
/// «Coin-Logos › In der App» aus: nie. Tipp auf den Text: Liste dieser Coins (Kürzel, Name falls bekannt) mit einer
/// Zeile Erklärung; ✕ (44 pt, «Hinweis schliessen») merkt genau diese Coins (`MissingLogoDismissals`) — die Zeile kommt
/// erst mit einem weiteren Coin ohne Logo zurück. Kommen Logos, verschwindet sie von selbst. Wie Android
/// `WatchlistMissingLogos`.
@MainActor
struct WatchlistMissingLogos: View {
    @ObservedObject private var model = MissingLogosModel.shared
    @ObservedObject private var loading = CoinLogoLoading.shared
    @ObservedObject private var dismissals = MissingLogoDismissals.shared
    @Environment(\.coinLogosEnabled) private var coinLogosEnabled

    private var notice: MissingLogoNotice.State {
        guard let missing = model.missing else { return .hidden }
        return MissingLogoNotice.state(missing: missing, dismissed: dismissals.keys, loading: loading.visible,
                                       logosEnabled: coinLogosEnabled)
    }

    var body: some View {
        // Ohne Zeile gar keine Ansicht (kein Abstand im Kopf); ein- und ausblenden über `MissingLogosModel`
        let state = notice
        if state.visible { line(count: state.count).transition(.opacity) }
    }

    /// Text (Tipp: Liste) und ✕, beide mindestens 44 pt hoch.
    private func line(count: Int) -> some View {
        HStack(spacing: 0) {
            Button { model.openList() } label: {
                Text(L("watchlist_missing_logos", count: count))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, minHeight: Self.touch, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            Button { model.dismiss() } label: {
                Image(systemName: "xmark")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .frame(width: Self.touch, height: Self.touch)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L("watchlist_missing_logos_dismiss"))
        }
        .accessibilityIdentifier("missing_logos_line")
    }

    /// Mindest-Tippfläche (Text und ✕).
    private static let touch: CGFloat = 44
}

/// Prüft unsichtbar im Hintergrund des Kopfs (immer da, nimmt keinen Platz), welche Coins der Merkliste kein Logo haben,
/// sobald sich Coins, Logos, der Lade-Hinweis oder der Schalter ändern; zeigt auch die Liste (Blatt).
@MainActor
struct WatchlistMissingLogosCheck: View {
    /// Alle Paare der Merkliste (unabhängig von Gruppe und Filter).
    let watches: [Watch]
    @ObservedObject private var model = MissingLogosModel.shared
    @ObservedObject private var loading = CoinLogoLoading.shared
    @ObservedObject private var revision = CoinLogoRevision.shared
    @Environment(\.coinLogosEnabled) private var coinLogosEnabled

    init(watches: [Watch]) {
        self.watches = watches
    }

    var body: some View {
        let entries = MissingLogosModel.entries(watches)
        Color.clear
            .accessibilityHidden(true)
            .task(id: TaskKey(keys: entries.map(\.key), revision: revision.value, loading: loading.visible,
                              enabled: coinLogosEnabled)) {
                await model.refresh(entries, enabled: coinLogosEnabled, loading: loading.visible)
            }
            .sheet(isPresented: $model.showList) {
                MissingLogosSheet(rows: model.rows)
            }
    }

    private struct TaskKey: Equatable {
        let keys: [String]
        let revision: Int
        let loading: Bool
        let enabled: Bool
    }
}

/// Stand des Hinweises «Coins ohne Logo»: welche Coins (nil = noch offen: erstes Laden nicht erledigt, Hinweis «Logos
/// werden geladen» steht oder Schalter aus), ihre Namen und das Blatt mit der Liste.
@MainActor
final class MissingLogosModel: ObservableObject {
    static let shared = MissingLogosModel()
    @Published private(set) var missing: [String]?
    /// Zeilen des Blatts (beim Öffnen festgehalten).
    @Published private(set) var rows: [MissingLogosSheet.Row] = []
    @Published var showList = false
    private var names: [String: String] = [:]
    /// Davon «noch nicht geladen» (Adresse bekannt, nicht gescheitert: kommt mit dem nächsten Abgleich bzw. im WLAN).
    private var notYet: Set<String> = []

    /// Paare → Schlüssel (`MissingLogoNotice.key`, ohne doppelte) samt erstem Paar je Coin (für den Namen).
    static func entries(_ watches: [Watch]) -> [(key: String, watch: Watch)] {
        let tradFiPairs = CoinLogoStore.tradFiPairs
        var seen = Set<String>()
        return watches.compactMap { watch in
            guard let key = MissingLogoNotice.key(exchangeKey: watch.exchangeKey, base: watch.baseAsset,
                                                  tradFi: tradFiPairs.contains(watch.logoPairKey)),
                  seen.insert(key).inserted else { return nil }
            return (key, watch)
        }
    }

    /// Coins ohne mitgeliefertes oder geladenes Logo bestimmen (Dateien lesen im Actor `CoinLogoStore`).
    func refresh(_ entries: [(key: String, watch: Watch)], enabled: Bool, loading: Bool) async {
        guard enabled, !loading, CoinLogoLoading.firstLoadDone() else { return show(nil) }
        var without: [String] = []
        var names: [String: String] = [:]
        var notYet: Set<String> = []
        for entry in entries {
            if await !CoinLogoStore.shared.hasLogo(entry.key) {
                without.append(entry.key)
                names[entry.key] = CoinLogoStore.name(for: entry.watch)
                if await CoinLogoStore.shared.notYetLoaded(entry.key) { notYet.insert(entry.key) }
            }
        }
        guard !Task.isCancelled else { return }
        self.names = names
        self.notYet = notYet
        show(without)
    }

    /// Tipp auf den Text: Liste der Coins (Kürzel, Name falls bekannt).
    func openList() {
        rows = (missing ?? []).map {
            MissingLogosSheet.Row(symbol: MissingLogoNotice.displaySymbol($0), name: names[$0], notYet: notYet.contains($0))
        }
        showList = true
    }

    /// ✕: genau diese Coins merken; die Zeile blendet aus.
    func dismiss() {
        let keys = Set(missing ?? [])
        withAnimation(Self.animation) { MissingLogoDismissals.shared.dismiss(keys) }
    }

    private func show(_ next: [String]?) {
        guard next != missing else { return }
        withAnimation(Self.animation) { missing = next }
    }

    /// Ein- und ausblenden wie `CoinLogoLoading` (Bewegung reduzieren: ohne Überblenden).
    private static var animation: Animation? {
        UIAccessibility.isReduceMotionEnabled ? nil : .easeInOut(duration: 0.3)
    }
}

/// Liste der Coins ohne Logo (Kürzel, Name falls bekannt, «noch nicht geladen») und eine Zeile Erklärung — ohne solche
/// «kein Logo verfügbar», sonst beides unterschieden (wie Android); nur «Schliessen».
struct MissingLogosSheet: View {
    struct Row {
        let symbol: String
        let name: String?
        var notYet = false
    }

    let rows: [Row]
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appAccent) private var accent

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.lg) {
            Text(L("watchlist_missing_logos", count: rows.count))
                .font(.headline)
            Text(rows.contains(where: \.notYet) ? L("watchlist_missing_logos_explain_pending")
                                                 : L("watchlist_missing_logos_explain"))
                .font(.subheadline)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                        HStack(spacing: Spacing.sm) {
                            Text(row.symbol).font(.body.weight(.semibold))
                            if let name = row.name {
                                Text(name)
                                    .foregroundStyle(AppColors.onSurfaceVariant)
                                    .lineLimit(1)
                            }
                            if row.notYet {
                                Text(L("watchlist_missing_logos_pending"))
                                    .foregroundStyle(AppColors.onSurfaceVariant)
                                    .lineLimit(1)
                            }
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            Button(L("action_close")) { dismiss() }
                .font(.headline)
                .foregroundStyle(accent.primary)
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .padding(Spacing.xl)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(AppColors.background)
    }
}

/// Beim ✕ des Hinweises «Coins ohne Logo» gemerkte Coins: nur auf diesem Gerät (`SharedStorage.defaults`, wie die
/// übrigen Oberflächen-Merker), nie synchronisiert, nicht in der eigenen Sicherung. Wie Android `MissingLogoDismissals`.
@MainActor
final class MissingLogoDismissals: ObservableObject {
    static let shared = MissingLogoDismissals()
    static let key = "missing_logos_dismissed"
    @Published private(set) var keys: Set<String>

    private init() {
        keys = MissingLogoNotice.decode(SharedStorage.defaults.string(forKey: Self.key))
    }

    /// ✕: genau diese Coins ohne Logo merken (ersetzt den vorigen Satz).
    func dismiss(_ keys: Set<String>) {
        self.keys = keys
        SharedStorage.defaults.set(MissingLogoNotice.encode(keys), forKey: Self.key)
    }
}
