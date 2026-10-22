import SwiftUI

/// Bildschirme, die den Hinweis zur gesperrten Merkliste zeigen können, in der Reihenfolge ihres Erscheinens: Nur der
/// zuletzt erschienene zeigt ihn — sonst meldeten z. B. die Merkliste unter «Paar hinzufügen» oder unter einem Blatt ihn
/// ein zweites Mal (doppelte Haptik). Nur auf dem MainActor.
@MainActor
enum WatchlistLockedHintHosts {
    private(set) static var stack: [UUID] = []

    static func appeared(_ host: UUID) {
        stack.removeAll { $0 == host }
        stack.append(host)
    }

    static func disappeared(_ host: UUID) { stack.removeAll { $0 == host } }

    /// Zeigt `host` den Hinweis (zuletzt erschienen und noch sichtbar)?
    static func isTop(_ host: UUID) -> Bool { stack.last == host }
}

/// Kurzer Hinweis, wenn AppData einen Schreibweg der Merkliste abgelehnt hat (`AppData.watchlistWriteRefused`, seit
/// Build 103): Warn-Haptik und `show` mit dem Text «watchlist_storage_locked» über das Banner bzw. den Hinweis des
/// Bildschirms — wie Android `WatchlistLockedHint`.
private struct WatchlistLockedHintModifier: ViewModifier {
    @EnvironmentObject private var data: AppData
    // Die Frage «Änderungen werden gerade nicht gespeichert …» zeigt seit Build 105 `WatchlistNotSavedGatePresenter`
    // (eigenes Fenster über allem, auch über Blättern) — nicht mehr dieser Bildschirm
    @State private var host = UUID()
    let show: (String) -> Void

    func body(content: Content) -> some View {
        content
            .onAppear { WatchlistLockedHintHosts.appeared(host) }
            .onDisappear { WatchlistLockedHintHosts.disappeared(host) }
            .onChange(of: data.watchlistLockedHints) { _, _ in
                guard WatchlistLockedHintHosts.isTop(host) else { return }
                Haptics.play(.warning)
                show(L("watchlist_storage_locked"))
            }
    }
}

extension View {
    /// Hinweis bei gesperrter Merkliste (`AppData.watchlistWriteRefused`) über `show` dieses Bildschirms.
    func watchlistLockedHint(_ show: @escaping (String) -> Void) -> some View {
        modifier(WatchlistLockedHintModifier(show: show))
    }
}
