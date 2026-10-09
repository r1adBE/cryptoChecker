import SwiftUI

/// Kompakte Auswahl «In Gruppe: …» neben den Hinzufügen-Knöpfen — wie
/// `ExplorerGroupTarget.kt`. Gilt nur für neue Paare; bereits vorhandene
/// behalten ihre Gruppe. Die Wahl gilt nur für die laufende Sitzung
/// (`AppData.addTargetGroup`, nicht gespeichert).
@MainActor
struct ExplorerGroupTargetSelector: View {
    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent

    @State private var naming = false
    @State private var newName = ""

    var body: some View {
        let target = data.addTargetGroup
        // Auch eine eben neu benannte (noch leere) Gruppe zur Auswahl anbieten
        let names = Array(Set(data.watchGroups + (target.map { [$0] } ?? [])))
            .sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
        Menu {
            Button {
                data.addTargetGroup = nil
            } label: {
                if target == nil {
                    Label(L("group_none"), systemImage: "checkmark")
                } else {
                    Text(L("group_none"))
                }
            }
            ForEach(names, id: \.self) { name in
                Button {
                    data.addTargetGroup = name
                } label: {
                    if target == name {
                        Label(name, systemImage: "checkmark")
                    } else {
                        Text(name)
                    }
                }
            }
            Divider()
            Button {
                newName = ""
                naming = true
            } label: {
                Label(L("group_new"), systemImage: "plus")
            }
        } label: {
            HStack(spacing: Spacing.xs) {
                Image(systemName: "folder")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(accent.primary)
                Text(L("group_target"))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                Text(target ?? L("group_none"))
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.onSurface)
                    .lineLimit(1)
                Image(systemName: "chevron.down")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, Spacing.sm)
            .background(AppColors.containerHigh, in: Capsule())
            .contentShape(Capsule())
        }
        .alert(L("group_add"), isPresented: $naming) {
            TextField(L("group_name"), text: $newName)
                .textInputAutocapitalization(.words)
            Button(L("action_save")) {
                guard let clean = WatchGroupNames.clean(newName) else { return }
                data.addTargetGroup = WatchGroupNames.canonical(clean, in: data.watchGroups)
            }
            Button(L("action_cancel"), role: .cancel) {}
        }
    }
}
