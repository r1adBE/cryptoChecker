import SwiftUI

/// Banner unten in der Merkliste: «… wird jetzt überwacht» (ggf. mit «Alarm setzen»),
/// «BTC/USDT entfernt» (mit «Rückgängig») und «… zu den Favoriten hinzugefügt».
struct WatchlistBannerMessage: Identifiable, Equatable {
    let id = UUID()
    let text: String
    /// SF Symbol links.
    var icon = "checkmark.circle.fill"
    /// Gelöschtes Paar — dann mit «Rückgängig».
    var undo: DeletedWatch?
    /// Länger stehen lassen (Erst-Hinzufügen).
    var long = false
    /// Weitere Aktion rechts, z. B. «Alarm setzen» nach dem Hinzufügen (nicht zusammen mit `undo`).
    var action: WatchlistBannerAction?

    /// «Rückgängig» ~5 s, andere Aktion ~6 s (mit VoiceOver je länger, damit der Knopf
    /// erreichbar ist), lange Meldungen 5 s, sonst 3 s.
    func nanos(voiceOver: Bool) -> UInt64 {
        if undo != nil { return voiceOver ? 10_000_000_000 : 5_000_000_000 }
        if action != nil { return voiceOver ? 12_000_000_000 : 6_000_000_000 }
        return long ? 5_000_000_000 : 3_000_000_000
    }

    static func == (a: Self, b: Self) -> Bool { a.id == b.id }
}

/// Aktion im Banner: Beschriftung und was beim Tippen passiert.
struct WatchlistBannerAction {
    let title: String
    let perform: () -> Void
}

/// Wie `ExplorerSnackbar`, dazu optional «Rückgängig». Nach unten wischen schliesst;
/// Weniger Bewegung: nur einblenden.
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
                Image(systemName: message.icon)
                    .foregroundStyle(accent.primary)
                    .accessibilityHidden(true)
                Text(message.text)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let undo = message.undo {
                    Button {
                        self.message = nil
                        onUndo(undo)
                    } label: {
                        Text(L("action_undo"))
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(accent.primary)
                            .padding(.vertical, 6)
                            .padding(.horizontal, 4)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.borderless)
                } else if let action = message.action {
                    Button {
                        self.message = nil
                        action.perform()
                    } label: {
                        Text(action.title)
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(accent.primary)
                            .padding(.vertical, 6)
                            .padding(.horizontal, 4)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.borderless)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(.black.opacity(0.86), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .shadow(color: .black.opacity(0.25), radius: 16, y: 6)
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
