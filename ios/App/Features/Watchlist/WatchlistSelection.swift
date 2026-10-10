import SwiftUI

/// Leiste der Mehrfachauswahl unten über der Liste: Favorit · Gruppe · Löschen für die
/// angehakten Paare. Ohne Auswahl grau. «Favorit» setzt den Stern bei allen — oder nimmt ihn
/// weg, wenn schon alle Favoriten sind (`allFavorites`). Symbole wie überall: Stern, Gruppe
/// (Ordner, wie «In Gruppe» beim Hinzufügen und im Aktionsblatt), Papierkorb.
/// Wie `SelectionBar` (Android).
struct WatchlistSelectionBar: View {
    let count: Int
    let allFavorites: Bool
    let onFavorite: () -> Void
    let onGroup: () -> Void
    let onDelete: () -> Void

    var body: some View {
        let enabled = count > 0
        HStack(spacing: 0) {
            action(symbol: allFavorites ? "star.fill" : "star", title: L("a11y_favorite"),
                   spoken: L(allFavorites ? "favorite_remove" : "favorite_add"),
                   color: AppColors.onSurface, perform: onFavorite)
            action(symbol: "folder", title: L("group_title"), color: AppColors.onSurface, perform: onGroup)
            action(symbol: "trash", title: L("action_delete"), color: AppColors.error, perform: onDelete)
        }
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(AppColors.containerHighest, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
    }

    private func action(symbol: String, title: String, spoken: String? = nil, color: Color,
                        perform: @escaping () -> Void) -> some View {
        Button(action: perform) {
            VStack(spacing: 2) {
                Image(systemName: symbol)
                    .scaledFont(size: 18, weight: .semibold, relativeTo: .body)
                Text(title)
                    .font(.caption.weight(.medium))
                    .lineLimit(1)
            }
            .foregroundStyle(color)
            .frame(maxWidth: .infinity, minHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(spoken ?? title)
    }
}
