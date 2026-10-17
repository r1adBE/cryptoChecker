import SwiftUI

/// Schmale Zeile über den Paaren: «Name ⇅ · Kurs ⇅ · 24h ⇅» wie an der Börse. Erster Tipp: Name
/// A–Z bzw. Kurs und 24h gross zuerst; zweiter Tipp: umgekehrt; dritter: eigene Reihenfolge
/// (`ColumnSort.next`). Die aktive Spalte steht in der Themenfarbe, ihr Pfeil ebenso; die übrigen
/// Pfeile in onSurfaceVariant (11 pt, ohne Deckkraft). Tippfläche mind. 44 pt.
/// Die 24h-Spalte heisst wie die Änderungen («24h», «heute» …). Wie `WatchlistSortBar` (Android).
struct WatchlistSortBar: View {
    let sort: ColumnSort?
    let onTap: (SortKey) -> Void
    @Environment(\.appAccent) private var accent
    @Environment(\.changeView) private var changeView

    var body: some View {
        HStack(spacing: 4) {
            column(L("watchlist_sort_name"), .NAME)
            Spacer(minLength: 8)
            column(L("watchlist_sort_price"), .PRICE)
            column(A11y.changeShortLabel(changeView.basis), .CHANGE)
        }
        .padding(.horizontal, 4)
    }

    private func column(_ title: String, _ key: SortKey) -> some View {
        let active = sort?.key == key
        let color = active ? accent.primary : AppColors.onSurfaceVariant
        let state = !active ? L("watchlist_sort_own")
            : (sort?.descending == true ? L("watchlist_sort_descending") : L("watchlist_sort_ascending"))
        return Button { onTap(key) } label: {
            HStack(spacing: 3) {
                Text(title)
                    .font(.caption.weight(active ? .semibold : .regular))
                    .lineLimit(1)
                // ▲ über ▼: der aktive Pfeil in der Themenfarbe, der andere in onSurfaceVariant
                // (nicht blass, mind. 11 pt — Kontrast AA; wie Android)
                VStack(spacing: 0) {
                    Image(systemName: "arrowtriangle.up.fill")
                        .foregroundStyle(active && sort?.descending == false ? accent.primary : AppColors.onSurfaceVariant)
                    Image(systemName: "arrowtriangle.down.fill")
                        .foregroundStyle(active && sort?.descending == true ? accent.primary : AppColors.onSurfaceVariant)
                }
                .font(.system(size: 11))
                .accessibilityHidden(true)
            }
            .foregroundStyle(color)
            .padding(.horizontal, 6)
            // Tippfläche mind. 44 pt (früher 36)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .accessibilityValue(state)
    }
}
