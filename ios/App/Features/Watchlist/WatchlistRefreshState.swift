import SwiftUI

/// Beobachtet `RefreshState` für einen Teil eines grösseren Bildschirms: Ändert sich der Status der Aktualisierung,
/// rechnet nur `content` neu — nicht der Bildschirm darum herum (Merkliste: Statuszeile, Bericht).
struct RefreshStateReader<Content: View>: View {
    @ObservedObject var state: RefreshState
    @ViewBuilder let content: (RefreshState) -> Content

    var body: some View {
        content(state)
    }
}

/// Ereignisse der Merkliste aus dem Status der Aktualisierung, ohne dass der Bildschirm selbst `RefreshState`
/// beobachtet: Countdown der 15-s-Sperre (neu nach jeder Aktualisierung), Uhr neu bei Start und Ende eines Durchlaufs
/// bzw. bei neuer Dauer, Wechsel der Paare am Live-Strom (`onLiveChange`, z. B. für «nur veraltete»).
struct WatchlistRefreshEvents: ViewModifier {
    @ObservedObject var state: RefreshState
    let countdown: WatchlistRefreshCountdown
    let onRunChange: () -> Void
    let onLiveChange: () -> Void

    func body(content: Content) -> some View {
        let running = state.refreshing
        let key = "\(running)-\(state.lastRefreshReport?.at ?? 0)"
        let timer = self.countdown
        return content
            // Countdown der 15-s-Sperre: nur solange die Merkliste sichtbar ist, neu nach jeder Aktualisierung
            .task(id: key) {
                await timer.run(refreshing: running)
            }
            .onChange(of: running) { _, _ in onRunChange() }
            .onChange(of: state.lastRefreshMillis) { _, _ in onRunChange() }
            .onChange(of: state.liveWatchIds) { _, _ in onLiveChange() }
    }
}
