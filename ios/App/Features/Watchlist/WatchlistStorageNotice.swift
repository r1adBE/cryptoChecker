import SwiftUI

/// Dauerhinweis unten in der Merkliste (`WatchlistStorageLock.notice`): solange die gespeicherte Merkliste nicht lesbar
/// ist (`AppData.watchlistLocked`: «Die gespeicherte Merkliste lässt sich gerade nicht entsperren …») oder ohne Schlüssel
/// nicht gespeichert wird (`AppData.watchlistSavePending`: «… die Merkliste wird nicht gespeichert …»). Wie Android (Banner
/// ohne Ablauf, gleiche Texte): Ein anderes Banner verdrängt ihn nur, danach kommt er wieder; «Schliessen» blendet ihn bis
/// zum nächsten Banner aus.
struct WatchlistStorageNotice: View {
    let notice: WatchlistStorageLock.Notice?
    /// Ein anderes Banner steht gerade (`WatchlistBanner`).
    let bannerShown: Bool
    @State private var hidden = false
    @Environment(\.appAccent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if let notice, !bannerShown && !hidden {
                HStack(spacing: 12) {
                    Image(systemName: "lock.fill")
                        .foregroundStyle(accent.primary)
                        .accessibilityHidden(true)
                    Text(L(notice == .kept ? "watchlist_storage_kept_in_memory" : "watchlist_storage_in_memory"))
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(AppColors.onToast)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button {
                        withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { hidden = true }
                    } label: {
                        Text(L("action_close"))
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(accent.primary)
                            .padding(.vertical, Spacing.sm)
                            .padding(.horizontal, 4)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.borderless)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, Spacing.lg)
                .background(AppColors.toastBackground, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                .shadow(color: AppColors.shadow.opacity(0.25), radius: 16, y: 6)
                .padding(.horizontal, 16)
                .padding(.bottom, 12)
                .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.move(edge: .bottom).combined(with: .opacity))
            }
        }
        // Nach dem nächsten Banner wieder zeigen, auch wenn er geschlossen wurde
        .onChange(of: bannerShown) { _, shown in if shown { hidden = false } }
    }
}
