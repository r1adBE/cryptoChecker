import SwiftUI

// MARK: Leere Ansicht

/// Eigene Symbole neben den SF Symbols: «Portfolio» ist überall der Münzstapel (Asset
/// «PortfolioIcon», gleiche Form wie Android `ic_portfolio`). Andere Namen = SF Symbol.
enum AppSymbol {
    static let portfolio = "app.portfolio"

    static func isCustom(_ name: String) -> Bool { name == portfolio }

    /// Bild zum Namen; eigene Symbole als Vorlage (färben sich wie SF Symbols).
    static func image(_ name: String) -> Image {
        isCustom(name) ? Image("PortfolioIcon").renderingMode(.template) : Image(systemName: name)
    }

    /// Wie `image`, eigene Symbole auf `size` Punkte skaliert (SF Symbols folgen der Schrift).
    @ViewBuilder
    static func view(_ name: String, size: CGFloat) -> some View {
        if isCustom(name) {
            image(name).resizable().scaledToFit().frame(width: size, height: size)
        } else {
            image(name)
        }
    }
}

/// Leerer Zustand, überall gleich aufgebaut (wie Android `EmptyState`): Symbol in einem getönten
/// Kreis → Titel → Text → Beispiel → Hauptknopf; Textknöpfe und Hinweise folgen darunter beim
/// Aufrufer. Kreis hell Akzent-Container (halb), dunkel `accent.tint` — ein halb durchsichtiger
/// Akzent-Container wirkt auf dem dunklen Grund bräunlich.
struct EmptyStateView: View {
    let systemImage: String
    let title: String
    var message: String? = nil
    var example: String? = nil
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil
    @Environment(\.appAccent) private var accent
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(spacing: Spacing.md) {
            AppSymbol.view(systemImage, size: 44)
                .scaledFont(size: 44, weight: .light, relativeTo: .largeTitle)
                .foregroundStyle(accent.primary)
                .padding(Spacing.xl)
                .background(colorScheme == .dark ? accent.tint(0.3) : accent.container.opacity(0.5), in: Circle())
                // Nur Schmuck: der Titel sagt, worum es geht (wie Android `contentDescription = null`)
                .accessibilityHidden(true)
            Text(title)
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            if let message { secondary(message) }
            if let example { secondary(example) }
            if let actionTitle, let action {
                Button(action: action) {
                    Text(actionTitle).font(.headline).padding(.horizontal, Spacing.xl).padding(.vertical, 12)
                }
                .buttonStyle(AccentButtonStyle())
                .padding(.top, Spacing.xs)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity)
    }

    /// Text und Beispiel: Nebentext, mehrzeilig in voller Höhe.
    private func secondary(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
    }
}

extension View {
    /// Leerer Zustand in einer `ScrollView`: mindestens so hoch wie der sichtbare Bereich und darin mittig; braucht der
    /// Inhalt (grosse Schrift) mehr Platz, wird er höher und scrollt, statt abgeschnitten zu werden.
    func emptyStateFrame() -> some View {
        ZStack {
            Color.clear.containerRelativeFrame(.vertical)
            self
        }
    }

    /// Large Content Viewer für Bedienelemente nur mit Symbol bzw. mit bei `.xxxLarge` gedeckelter
    /// Grösse (Tableiste, Glocke, Menü, «+»): bei Bedienungshilfen-Schriftgrössen zeigt langes Drücken
    /// Symbol und Namen gross — der HIG-Ersatz dafür, dass solche Elemente nicht unbegrenzt mitwachsen
    /// (wie die Tableiste des Systems). Android: Tooltip beim langen Drücken.
    func largeContentLabel(_ title: String, systemImage: String) -> some View {
        accessibilityShowsLargeContentViewer {
            Label(title, systemImage: systemImage)
        }
    }
}

/// Gefüllter Knopf in der Akzentfarbe.
struct AccentButtonStyle: ButtonStyle {
    @Environment(\.appAccent) private var accent
    @Environment(\.onAccent) private var onAccent
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(onAccent)
            .background(accent.primary.opacity(isEnabled ? 1 : 0.4), in: Capsule())
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
    }
}

/// Getönter Knopf (Akzent-Container).
struct TonalButtonStyle: ButtonStyle {
    @Environment(\.appAccent) private var accent
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(accent.onContainer)
            .background(accent.container.opacity(isEnabled ? 1 : 0.4), in: Capsule())
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
    }
}

/// Text eines Toasts mit eigener Kennung: derselbe Text erneut gesetzt zeigt und sagt ihn noch einmal an.
/// `kind`: Art der Meldung (Rahmen, Symbol), Standard Info.
struct ToastMessage: Equatable {
    let id = UUID()
    let text: String
    let kind: SnackbarKind

    init(_ text: String, kind: SnackbarKind = .info) {
        self.text = text
        self.kind = kind
    }
}

/// Kleines Hinweis-Toast unten am Bildschirm; Fläche, Rahmen und Symbol je Art wie die Banner (`snackbarSurface`).
struct ToastModifier: ViewModifier {
    @Binding var message: ToastMessage?

    /// Ansage erst nach dem Einblenden (wie die Banner der Merkliste), sonst geht sie im Fokuswechsel unter.
    nonisolated static let announceDelay: Double = 0.4

    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let message {
                HStack(spacing: Spacing.sm) {
                    SnackbarKindIcon(kind: message.kind)
                    Text(message.text)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(AppColors.onSurface)
                }
                .padding(.horizontal, Spacing.lg)
                .padding(.vertical, 12)
                .snackbarSurface(message.kind, in: Capsule())
                // Ein Element für VoiceOver (das Symbol ist ausgeblendet), Kennung für die UI-Tests
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier(UiTestTags.toast)
                // Nur Anzeige: fängt keine Tipps ab (sonst blockiert sie kurz Tab-Leiste und Knöpfe darunter)
                .allowsHitTesting(false)
                .padding(.bottom, 24)
                .padding(.horizontal, 16)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .task(id: message) { await show(message) }
            }
        }
        .animation(.spring(duration: 0.3), value: message)
    }

    /// VoiceOver liest den Toast nach `announceDelay` vor (er nimmt keinen Fokus; abgebrochen → keine Ansage); länger
    /// sichtbar je nach Textlänge.
    @MainActor
    private func show(_ shown: ToastMessage) async {
        let total = Self.seconds(for: shown.text)
        do {
            try await Task.sleep(nanoseconds: UInt64(Self.announceDelay * 1_000_000_000))
            AccessibilityNotification.Announcement(shown.text).post()
            try await Task.sleep(nanoseconds: UInt64((total - Self.announceDelay) * 1_000_000_000))
        } catch {
            // Abgebrochen (Bildschirm weg oder neuer Toast): unten ausblenden, falls noch derselbe
        }
        // Auch abgebrochen ausblenden — einen inzwischen neuen Toast (auch mit gleichem Text) aber stehen lassen
        guard message == shown else { return }
        withAnimation { message = nil }
    }

    /// Anzeigedauer: 0,06 s je Zeichen (Codepunkte), mindestens 2,5 s, höchstens 8 s.
    nonisolated static func seconds(for text: String) -> Double {
        min(8, max(2.5, 0.06 * Double(text.unicodeScalars.count)))
    }
}

extension View {
    func toast(_ message: Binding<ToastMessage?>) -> some View { modifier(ToastModifier(message: message)) }
}
