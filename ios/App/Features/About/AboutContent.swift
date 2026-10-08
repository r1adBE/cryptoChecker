import SwiftUI
import UIKit

/// Logo der App in der Akzentfarbe (Asset `Logo<Farbe><Dark|Light>`).
/// Fehlt das Asset, zeigt es ein gezeichnetes Ersatz-Logo.
@MainActor
struct AppLogoView: View {
    var size: CGFloat = 56
    @Environment(\.appAccent) private var accent
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let name = accent.logoName(dark: colorScheme == .dark)
        Group {
            if let image = UIImage(named: name) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
            } else {
                ZStack {
                    RoundedRectangle(cornerRadius: size * 0.26, style: .continuous)
                        .fill(LinearGradient(colors: [accent.primary, accent.primary.opacity(0.65)],
                                             startPoint: .topLeading, endPoint: .bottomTrailing))
                    Image(systemName: "chart.line.uptrend.xyaxis")
                        .font(.system(size: size * 0.46, weight: .semibold))
                        .foregroundStyle(accent.onPrimary)
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.26, style: .continuous))
        .accessibilityHidden(true)
    }
}

/// Was die App kann (Runde 32: früher im Begrüßungsblatt, das es nicht mehr gibt — ein neuer
/// Nutzer landet direkt in der Starter-Auswahl): Kurztext und drei Zeilen (Beobachten,
/// Alarmieren, Verstehen — je mit kurzer Frage darüber). Wie `AboutFeatures.kt`.
@MainActor
struct AboutFeatures: View {
    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            Text(L("welcome_text"))
                .font(.subheadline)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.bottom, Spacing.xs)
            line(L("welcome_watch_q"), L("welcome_watch"))
            line(L("welcome_alert_q"), L("welcome_alert"))
            line(L("welcome_understand_q"), L("welcome_understand"))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Kleine Frage, darunter der Text — für VoiceOver ein Element.
    private func line(_ question: String, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(question)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppColors.onSurfaceVariant)
            Text(text)
                .font(.subheadline)
                .foregroundStyle(AppColors.onSurface)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// Kurzinfo zur App mit Kontaktadresse — wie `AboutContent.kt`.
/// Steht in den Einstellungen unter «Über».
@MainActor
struct AboutContent: View {
    /// Siebenmal tippen schaltet die Entwickleroptionen frei.
    var onVersionTap: (() -> Void)? = nil

    @Environment(\.appAccent) private var accent

    /// z. B. «16.2.2 (17)».
    static var versionText: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "—"
        if let build = info?["CFBundleVersion"] as? String, !build.isEmpty, build != short {
            return "\(short) (\(build))"
        }
        return short
    }

    /// `about_why` mit tippbarem Link zu den GitHub-Issues.
    private var whyText: AttributedString {
        let label = AppLinks.feedbackLabel
        var text = AttributedString(L("about_why", label))
        if let range = text.range(of: label) {
            // Farbe des Links über `.tint` (Akzentfarbe)
            text[range].link = URL(string: AppLinks.feedback)
        }
        return text
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: Spacing.md) {
                AppLogoView(size: 52)
                    .shadow(color: accent.primary.opacity(0.35), radius: 10, y: 4)
                VStack(alignment: .leading, spacing: 3) {
                    Text(L("about_version", Self.versionText))
                        .font(.headline)
                        .foregroundStyle(AppColors.onSurface)
                    Text(L("about_license"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { onVersionTap?() }
            .padding(.vertical, Spacing.md)

            Text(whyText)
                .font(.subheadline)
                .foregroundStyle(AppColors.onSurface)
                .tint(accent.primary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 4)
                .padding(.bottom, Spacing.sm)
        }
    }
}
