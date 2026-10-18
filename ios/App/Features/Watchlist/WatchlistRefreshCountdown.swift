import Foundation
import Observation
import SwiftUI

/// Sekunden der 15-s-Sperre nach einer vollständigen Aktualisierung (`RefreshDebounce`) für
/// «Aktualisieren (5)» im ⋯-Menü der Merkliste — wie der Countdown in `WatchlistMenu` (Android).
/// Eigenes beobachtbares Objekt: Ein Sekundenschritt zeichnet nur `WatchlistMenuHead` neu, nicht
/// die ganze Merkliste (die liest `seconds` nicht).
@MainActor
@Observable
final class WatchlistRefreshCountdown {
    /// 0 = frei (oder es läuft eine — dann zeigt der Eintrag den laufenden Zustand).
    var seconds = 0

    /// Zählt herunter, solange gesperrt; endet bei 0. Einmal je Sekunde, genau auf der Sekundengrenze
    /// (`RefreshDebounce.millisUntilNextChange`). Läuft als `.task` des Bildschirms, also nur solange
    /// die Merkliste sichtbar ist; neu gestartet, sobald eine Aktualisierung endet.
    func run(refreshing: Bool) async {
        guard !refreshing else {
            seconds = 0
            return
        }
        while !Task.isCancelled {
            let last = SharedStorage.lastRefreshAt
            let now = TimeUtils.nowMillis
            seconds = RefreshDebounce.remainingSeconds(lastFinishedAt: last, now: now)
            if seconds == 0 { return }
            let wait = RefreshDebounce.millisUntilNextChange(lastFinishedAt: last, now: now)
            try? await Task.sleep(nanoseconds: UInt64(wait) * 1_000_000)
        }
    }
}

/// Anfang des ⋯-Menüs der Merkliste (`AppMenuHead`) mit Countdown: Eben erst aktualisiert, steht dort
/// «Aktualisieren (5)», «(4)» … grau, danach wird der Eintrag von selbst wieder aktiv — auch bei offenem
/// Menü. Keine Ansage je Sekunde: VoiceOver liest die neue Beschriftung, wenn es den Eintrag erreicht.
struct WatchlistMenuHead: View {
    let refreshing: Bool
    let countdown: WatchlistRefreshCountdown
    let onOpenAbout: () -> Void
    let onRefresh: () -> Void

    var body: some View {
        let seconds = refreshing ? 0 : countdown.seconds
        // Zusätzlich frisch geprüft: Gleich nach dem Ende einer Aktualisierung steht `seconds` noch kurz
        // auf 0, bis `run` neu gestartet hat — dann nicht für einen Augenblick aktiv
        let free = RefreshDebounce.decide(running: refreshing, lastFinishedAt: SharedStorage.lastRefreshAt,
                                          now: TimeUtils.nowMillis) == .start
        AppMenuHead(
            refreshing: refreshing,
            onOpenAbout: onOpenAbout,
            onRefresh: onRefresh,
            refreshEnabled: seconds == 0 && free,
            refreshLabel: seconds > 0 ? L("watchlist_refresh_countdown", seconds) : nil
        )
    }
}
