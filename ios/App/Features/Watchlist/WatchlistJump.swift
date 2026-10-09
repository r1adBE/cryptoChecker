import SwiftUI

/// Sprungknopf der Merkliste («Zum Anfang» / «Zum Ende») — Regeln wie `WatchJump.kt`.
/// Der Knopf erscheint nur bei langen Listen, zeigt in der oberen Hälfte nach unten
/// (springt ans Ende), sonst nach oben (springt an den Anfang).
enum WatchlistJump {
    /// Erst ab mehr als so vielen Paaren in der sichtbaren Liste.
    static let minPairs = 30
    /// Weiter als so viele Zeilen: sofort springen statt lange zu animieren.
    static let instantDistance = 40
    /// Nach dem Scrollen so lange stehen lassen, dann ausblenden.
    static let hideDelayNanos: UInt64 = 2_000_000_000
    /// Durchmesser und Abstand zum Rand.
    static let buttonSize: CGFloat = 44
    static let margin: CGFloat = 16
    /// Ziele für `ScrollViewProxy.scrollTo`: Kopfzeile bzw. Abstand unter der letzten Zeile.
    static let topId = "watchlist-jump-top"
    static let endId = "watchlist-jump-end"

    /// Knopf möglich? Nicht im Sortiermodus; `pairs` = sichtbare (ggf. gefilterte) Paare.
    static func eligible(pairs: Int, sorting: Bool) -> Bool {
        !sorting && pairs > minPairs
    }

    /// Liegt die Mitte des sichtbaren Bereichs in der oberen Hälfte? Dann Pfeil nach unten.
    static func pointsDown(firstVisible: Int, lastVisible: Int, total: Int) -> Bool {
        guard total > 0 else { return true }
        return firstVisible + lastVisible < total - 1
    }

    /// Sanft scrollen nur über kurze Strecken und ohne «Bewegung reduzieren».
    static func animate(distance: Int, reduceMotion: Bool) -> Bool {
        !reduceMotion && abs(distance) <= instantDistance
    }
}

/// Gerade sichtbare Paare (per onAppear/onDisappear der Zeilen). Bewusst kein
/// beobachteter Zustand: Scrollen soll die Merkliste nicht bei jeder Zeile neu aufbauen.
final class WatchlistJumpTracker {
    var visible: Set<Int64> = []
}

/// Kleiner runder Knopf mit Pfeil: «Zum Ende» (nach unten) bzw. «Zum Anfang» (nach oben).
struct WatchlistJumpButton: View {
    let down: Bool
    let action: () -> Void

    @Environment(\.appAccent) private var accent

    var body: some View {
        Button(action: action) {
            Image(systemName: down ? "arrow.down" : "arrow.up")
                .scaledFont(size: 16, weight: .semibold, relativeTo: .body)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .foregroundStyle(accent.primary)
                .frame(width: WatchlistJump.buttonSize, height: WatchlistJump.buttonSize)
                .background(.regularMaterial, in: Circle())
                .overlay(Circle().strokeBorder(AppColors.outlineVariant.opacity(0.5), lineWidth: 0.5))
                .shadow(color: AppColors.shadow.opacity(0.15), radius: 6, y: 2)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L(down ? "watchlist_jump_end" : "watchlist_jump_start"))
    }
}

/// iOS 18: meldet, ob die Liste gerade gescrollt wird (auch bei ruhendem Finger).
/// Unter iOS 17 ohne Wirkung — dort zeigt das Kommen und Gehen der Zeilen das Scrollen an.
struct WatchlistScrollPhaseModifier: ViewModifier {
    let onChange: (Bool) -> Void

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onScrollPhaseChange { _, phase in
                onChange(phase != .idle)
            }
        } else {
            content
        }
    }
}

/// Sprungknopf: sichtbare Zeilen, Richtung, Ein- und Ausblenden, Sprung.
extension WatchlistScreen {
    /// Sichtbare Paare in Listenreihenfolge (Gruppe, Suche, Favoriten zuerst) — wie `list(_:proxy:)`.
    private func currentRows() -> [Watch] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let filtering = searching && !trimmed.isEmpty
        let watches = data.visibleWatches
        let found = filtering ? watches.filter { WatchlistSearch.matches($0, query: trimmed) } : watches
        return found.filter(\.favorite) + found.filter { !$0.favorite }
    }

    /// Erste und letzte sichtbare Position in `rows`; nil, wenn keine Zeile sichtbar ist.
    private func visibleRange(in rows: [Watch]) -> ClosedRange<Int>? {
        let visible = jumpTracker.visible
        let indices = rows.indices.filter { visible.contains(rows[$0].id) }
        guard let first = indices.first, let last = indices.last else { return nil }
        return first...last
    }

    /// Zeile kommt oder geht: Richtung nachführen; geht eine Zeile, die noch in der Liste
    /// steht, wird gescrollt (gelöschte oder weggefilterte Zeilen zählen nicht).
    func rowVisibilityChanged(_ id: Int64, visible: Bool) {
        if visible {
            jumpTracker.visible.insert(id)
        } else {
            jumpTracker.visible.remove(id)
        }
        let rows = currentRows()
        guard WatchlistJump.eligible(pairs: rows.count, sorting: sorting) else { return }
        if let range = visibleRange(in: rows) {
            let down = WatchlistJump.pointsDown(firstVisible: range.lowerBound, lastVisible: range.upperBound,
                                                total: rows.count)
            if down != jumpDown { jumpDown = down }
        }
        if !visible, rows.contains(where: { $0.id == id }) {
            showJump()
            if !scrollActive { scheduleJumpHide() }
        }
    }

    /// iOS 18: Scrollphase — sichtbar ab Beginn, ausblenden 2 s nach dem Ende.
    func scrollPhaseChanged(_ scrolling: Bool) {
        scrollActive = scrolling
        guard WatchlistJump.eligible(pairs: currentRows().count, sorting: sorting) else { return }
        if scrolling {
            showJump()
        } else {
            scheduleJumpHide()
        }
    }

    private func showJump() {
        jumpHideTask?.cancel()
        jumpHideTask = nil
        if !jumpScrolled { jumpScrolled = true }
    }

    private func scheduleJumpHide() {
        jumpHideTask?.cancel()
        jumpHideTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: WatchlistJump.hideDelayNanos)
            guard !Task.isCancelled else { return }
            jumpScrolled = false
        }
    }

    /// Oben → ans Ende, unten → an den Anfang. Kurze Strecken sanft, lange sofort
    /// (keine lange Animation); mit «Bewegung reduzieren» immer sofort.
    /// `hasTopAnchor`: steht über den Paaren eine Zeile mit `WatchlistJump.topId` (Puls, Karte,
    /// Hinweis)? Sonst geht «Zum Anfang» zum ersten Paar.
    func jump(rows: [Watch], proxy: ScrollViewProxy, hasTopAnchor: Bool) {
        guard let firstRow = rows.first else { return }
        WatchlistHaptics.impact(.light)
        let down = jumpDown
        let range = visibleRange(in: rows)
        let distance = down ? rows.count - 1 - (range?.upperBound ?? 0) : (range?.lowerBound ?? rows.count)
        let firstId = firstRow.id
        let scroll: () -> Void = {
            if down {
                proxy.scrollTo(WatchlistJump.endId, anchor: .bottom)
            } else if hasTopAnchor {
                proxy.scrollTo(WatchlistJump.topId, anchor: .top)
            } else {
                proxy.scrollTo(firstId, anchor: .top)
            }
        }
        if WatchlistJump.animate(distance: distance, reduceMotion: reduceMotion) {
            withAnimation(.easeInOut(duration: 0.35)) { scroll() }
        } else {
            scroll()
        }
        // Richtung gleich umstellen (die Zeilen melden sich erst nach dem Sprung)
        jumpDown = !down
        if !scrollActive { scheduleJumpHide() }
    }
}
