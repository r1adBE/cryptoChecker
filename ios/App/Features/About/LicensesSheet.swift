import SwiftUI

/// Runde 14: Lizenzhinweise unter «Über». Der Lizenztext der Börsen-Anbindung liegt
/// unübersetzt in `App/Resources/Licenses/MIT-marketdata.txt`; übersetzt sind nur
/// Titel und Einleitung. Swift-Pakete von Dritten nutzt die App nicht (wie `LicensesSheet.kt`).
enum LicenseNotices {
    /// Eigene Zeile für die Änderungen, wie in der Datei `LICENSE` des Projekts.
    static let ownerCopyright = "Copyright (c) 2026 r1AD <riad.work@outlook.com>"

    /// Lizenztext aus dem App-Bundle (leer, falls die Datei fehlt).
    static func text(_ name: String) -> String {
        guard let url = Bundle.main.url(forResource: name, withExtension: "txt"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return "" }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

struct LicensesSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appAccent) private var accent
    @State private var marketdata = ""

    /// true: als Seite der Einstellungen (im Navigationsstapel, ohne «Schliessen»).
    var asPage = false

    var body: some View {
        if asPage {
            content
        } else {
            NavigationStack {
                content
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button(L("action_close")) { dismiss() }
                        }
                    }
            }
            .tint(accent.primary)
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationBackground(AppColors.background)
        }
    }

    private var content: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.sm) {
                // Runde 15: die App selbst ist quelloffen (MIT)
                Text(L("licenses_app_intro"))
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurface)
                    .padding(.bottom, Spacing.xs)
                Text(L("licenses_marketdata_title"))
                    .sectionTitleStyle()
                    .accessibilityAddTraits(.isHeader)
                Text(L("licenses_marketdata_intro"))
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurface)
                if !marketdata.isEmpty {
                    Text(marketdata)
                        .font(.caption.monospaced())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                Text(L("licenses_marketdata_changes", LicenseNotices.ownerCopyright))
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurface)
            }
            .textSelection(.enabled)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .readableContentWidth()
        }
        .background(AppColors.background)
        .navigationTitle(L("about_licenses"))
        .navigationBarTitleDisplayMode(.inline)
        .task { marketdata = LicenseNotices.text("MIT-marketdata") }
    }
}
