import SwiftUI

/// Banner unten in der Merkliste: «… wird jetzt überwacht» (ggf. mit «Alarm setzen»),
/// «BTC/USDT entfernt» (mit «Rückgängig») und «… zu den Favoriten hinzugefügt».
struct WatchlistBannerMessage: Identifiable, Equatable {
    let id = UUID()
    let text: String
    /// Art der Meldung: Rahmen und Symbol (Erfolg: Häkchen, Fehler: Ausrufezeichen, Info: keines) — wie Android
    /// `WatchlistBanner.show(text, kind)`.
    var kind: SnackbarKind = .info
    /// Gelöschtes Paar — dann mit «Rückgängig».
    var undo: DeletedWatch?
    /// Länger stehen lassen (Erst-Hinzufügen).
    var long = false
    /// Weitere Aktion rechts, z. B. «Alarm setzen» nach dem Hinzufügen (nicht zusammen mit `undo`).
    var action: WatchlistBannerAction?
    /// Alle Paare, die «Rückgängig» dieses Banners zurückholt (einzeln gelöscht, solange das Banner stand).
    var removed: [DeletedWatch] = []

    /// Banner nach dem Löschen von `deleted`. Steht noch das Banner eines vorigen Löschens (`previous`), fasst es
    /// beide zusammen («2 Paare entfernt») und «Rückgängig» holt alle zurück — sonst wäre das vorige ohne Hinweis
    /// endgültig (wie Android `RemovedBatch`).
    static func removed(
        _ deleted: DeletedWatch,
        after previous: WatchlistBannerMessage?,
        restore: @escaping ([DeletedWatch]) -> Void
    ) -> WatchlistBannerMessage {
        let all = (previous?.removed ?? []) + [deleted]
        guard all.count > 1 else {
            return WatchlistBannerMessage(text: L("watchlist_removed", deleted.watch.displayPair), undo: deleted,
                                          removed: all)
        }
        return WatchlistBannerMessage(
            text: L("watchlist_removed_count", count: all.count),
            action: WatchlistBannerAction.undo { restore(all) },
            removed: all
        )
    }

    /// «Rückgängig» ~5 s, andere Aktion ~6 s (mit VoiceOver je länger, damit der Knopf
    /// erreichbar ist), lange Meldungen 5 s, sonst 3 s.
    func nanos(voiceOver: Bool) -> UInt64 {
        if undo != nil { return voiceOver ? 10_000_000_000 : 5_000_000_000 }
        if action != nil { return voiceOver ? 12_000_000_000 : 6_000_000_000 }
        return long ? 5_000_000_000 : 3_000_000_000
    }

    static func == (a: Self, b: Self) -> Bool { a.id == b.id }
}

/// Aktion im Banner: Beschriftung, Haptik beim Tippen (nil = keine) und was beim Tippen passiert.
struct WatchlistBannerAction {
    let title: String
    var haptic: HapticEvent?
    let perform: () -> Void

    /// «Rückgängig» mit Erfolgs-Haptik — wie Android `WatchlistBanner.show(onUndo)`.
    static func undo(_ perform: @escaping () -> Void) -> WatchlistBannerAction {
        WatchlistBannerAction(title: L("action_undo"), haptic: .success, perform: perform)
    }
}

/// Wie `ExplorerSnackbar`, dazu optional «Rückgängig». Fläche und Rahmen je Art: `snackbarSurface`. Nach unten wischen
/// schliesst; Weniger Bewegung: nur einblenden.
struct WatchlistBanner: View {
    @Binding var message: WatchlistBannerMessage?
    let onUndo: (DeletedWatch) -> Void
    @Environment(\.appAccent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver

    var body: some View {
        if let message {
            let nanos = message.nanos(voiceOver: voiceOver)
            HStack(spacing: 12) {
                SnackbarKindIcon(kind: message.kind)
                Text(message.text)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(AppColors.onSurface)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let undo = message.undo {
                    Button {
                        self.message = nil
                        Haptics.play(.success)
                        onUndo(undo)
                    } label: {
                        Text(L("action_undo"))
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(accent.primary)
                            .padding(.vertical, Spacing.sm)
                            .padding(.horizontal, 4)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.borderless)
                } else if let action = message.action {
                    Button {
                        self.message = nil
                        if let haptic = action.haptic { Haptics.play(haptic) }
                        action.perform()
                    } label: {
                        Text(action.title)
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(accent.primary)
                            .padding(.vertical, Spacing.sm)
                            .padding(.horizontal, 4)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.borderless)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, Spacing.lg)
            .snackbarSurface(message.kind)
            .shadow(color: AppColors.shadow.opacity(0.25), radius: 16, y: 6)
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
            .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.move(edge: .bottom).combined(with: .opacity))
            .task(id: message.id) {
                try? await Task.sleep(nanoseconds: nanos)
                if !Task.isCancelled {
                    withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { self.message = nil }
                }
            }
            .gesture(DragGesture(minimumDistance: 10).onEnded { v in
                if v.translation.height > 10 { withAnimation { self.message = nil } }
            })
        }
    }
}
