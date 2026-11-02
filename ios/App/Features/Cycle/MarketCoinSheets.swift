import Accessibility
import SwiftUI

// Paarseite für Coins aus «Heute auffällig» bzw. «Gewinner und Verlierer», dazu «Rückgängig» nach «Löschen».
// Eigene Datei, weil der Ablauf dem der Merkliste folgt und mit dem Aufbau des Tabs nichts teilt
// (wie Android `PairDestination.kt` / `MarketCoinEffects.kt`).

// MARK: - Paarseite aus dem Markt-Tab

/// Paarseite für einen Coin aus dem Markt-Tab — dieselbe Seite wie ein Tipp auf die Zeile in der Merkliste
/// (`PairScreen`), per NavigationStack über dem Tab (zurück führt hierher). Vorschau (Coin nicht in der Merkliste):
/// Kurs aus einer Ticker-Abfrage ohne Speichern, «Zur Merkliste hinzufügen» legt das Paar wie die Seite «Paar
/// hinzufügen» an. Löschen sofort mit «Rückgängig» im Banner dieses Tabs. Wie Android `pairDestination`.
@MainActor
struct CycleCoinSheets: ViewModifier {
    @Binding var target: CycleCoinTarget?

    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// «… entfernt» mit «Rückgängig» nach «Löschen».
    @State private var banner: WatchlistBannerMessage?

    func body(content: Content) -> some View {
        content
            .navigationDestination(item: $target) { target in
                page(for: target)
                    .environmentObject(data)
                    .environment(\.appAccent, accent)
                    .environment(\.changeView, data.changeView())
            }
            // Paar nicht übernommen bzw. geändert (Merkliste gesperrt): kurzer Hinweis mit Warn-Haptik
            .watchlistLockedHint { banner = WatchlistBannerMessage(text: $0, kind: .error, long: true) }
            .overlay(alignment: .bottom) {
                WatchlistBanner(message: $banner) { deleted in
                    data.withWatchlistConfirm {
                        var result = UndoRestoreResult()
                        withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { result = data.restore(deleted) }
                        // Merkliste inzwischen voll → Hinweis (wie Android)
                        if result.full > 0 {
                            banner = WatchlistBannerMessage(text: L("watchlist_undo_full", count: result.full),
                                                            kind: .error, long: true)
                        }
                    }
                }
                .animation(reduceMotion ? nil : .spring(duration: 0.35), value: banner)
            }
    }

    private func page(for target: CycleCoinTarget) -> some View {
        switch target {
        case .stored(let id):
            return PairScreen(watchId: id, onDelete: { delete($0) })
        case .preview(let watch):
            return PairScreen(watchId: WatchPreview.watchId, preview: watch, onDelete: { delete($0) })
        }
    }

    /// Wie nach links wischen in der Merkliste: sofort löschen, «Rückgängig» im Banner, Ansage.
    private func delete(_ watch: Watch) {
        // Rückfrage vorher; nach «Trotzdem» Banner mit «Rückgängig» und Ansage, nach «Abbrechen» nichts
        data.withWatchlistConfirm {
            var result: DeletedWatch?
            withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { result = data.deleteForUndo(watch) }
            guard let deleted = result else { return }
            let text = L("watchlist_removed", deleted.watch.displayPair)
            banner = WatchlistBannerMessage(text: text, undo: deleted)
            Task { @MainActor in
                // Nach dem Fokuswechsel ansagen, sonst geht es unter
                try? await Task.sleep(nanoseconds: 300_000_000)
                AccessibilityNotification.Announcement(text).post()
            }
        }
    }
}
