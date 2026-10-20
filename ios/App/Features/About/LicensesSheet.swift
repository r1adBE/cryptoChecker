import SwiftUI

/// Lizenzhinweise unter «Über die App»: die App selbst (MIT, Text unübersetzt in
/// `App/Resources/Licenses/MIT-cryptochecker.txt`, byte-gleich mit `LICENSE`, samt den ursprünglichen
/// MIT-Vermerken von BitcoinChecker: mobnetic, Aneonex Software), dazu `NOTICE-origin.txt` (Herkunft, Namen/Logos/Daten
/// Dritter, Alarmtöne — eigene Datei, damit die Lizenzdatei gleich `LICENSE` bleibt), Schrift und Ersatz-Icons.
/// Swift-Pakete von Dritten nutzt die App nicht (wie `LicensesSheet.kt`).
enum LicenseNotices {
    /// Quellenangabe für den Ersatz-Wechselkurs (open.er-api.com, `FxRateSource`): von ExchangeRate-API
    /// verlangte Nennung, nicht übersetzt (wie Android `RATES_BY_EXCHANGERATE_API`).
    static let ratesByExchangeRateApi = "Rates By Exchange Rate API (https://www.exchangerate-api.com)"

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
    @State private var appLicense = ""
    @State private var originNotice = ""

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
                // Die App selbst ist quelloffen (MIT); der ganze Lizenztext wie in `LICENSE`
                Text(L("licenses_app_intro"))
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurface)
                if !appLicense.isEmpty {
                    Text(appLicense)
                        .font(.caption.monospaced())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                if !originNotice.isEmpty {
                    Text(originNotice)
                        .font(.caption.monospaced())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                // Pflichtangabe der CoinGecko-API-Bedingungen (Markenname, nicht übersetzt)
                Text(verbatim: DataFreshness.poweredByCoinGecko)
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurface)
                // Ersatz-Wechselkurse von open.er-api.com: verlangte Quellenangabe (nicht übersetzt)
                Text(verbatim: LicenseNotices.ratesByExchangeRateApi)
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurface)
                    .padding(.bottom, Spacing.xs)
                // Ersatz-Logos (Build 46): freies Icon-Paket, CC0 1.0 — wie Android
                Text(L("licenses_icons_title"))
                    .sectionTitleStyle()
                    .accessibilityAddTraits(.isHeader)
                    .padding(.top, Spacing.sm)
                Text(L("licenses_icons_bundled_text"))
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
        .task {
            appLicense = LicenseNotices.text("MIT-cryptochecker")
            originNotice = LicenseNotices.text("NOTICE-origin")
        }
    }
}
