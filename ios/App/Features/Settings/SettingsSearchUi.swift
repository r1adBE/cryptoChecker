import SwiftUI

// Runde 31: Suche in den Einstellungen — wie `SettingsSearchUi.kt`. Die Hauptseite hat ein
// Suchfeld (`.searchable`); der Index umfasst ihre Zeilen und die Punkte der Unterseiten (Titel,
// Hinweise als Synonyme). Ein Treffer öffnet die Unterseite und hebt den Punkt kurz hervor
// (scrollt dorthin); Zeilen der Hauptseite werden dort hervorgehoben. Abgleich in `SettingsSearch`.

/// Unterseiten, zu denen ein Treffer führt.
enum SettingsSearchPage: Hashable {
    case currency, updates, watchlist, displayMode, theme, priceColors, changeBasis, coinLogos
    case alarms, marketAlerts, speech, portfolio, backup, about, developer
    /// Widget-Seite (Zeile «Widgets» unter «Darstellung»).
    case widgets
    /// «Token & TradFi» (auf «Merkliste») und die Lizenzhinweise (auf «Über die App»).
    case markets, licenses

    /// Wo die Zeile zu dieser Seite steht (Kurs-Aktualisierung und Portfolio unter «Allgemein», Widgets und Coin-Logos
    /// unter «Darstellung»); nil = Unterseite einer Unterseite (%-Änderung und Token & TradFi auf «Merkliste»,
    /// Markt-Meldungen und Sprachausgabe auf «Alarme», Lizenzen und Entwickler auf «Über die App»). Kein «Erweitert»
    /// mehr. Die Suche zeigt daraus den Pfad ihrer Treffer — wie `SettingsPage.home` (Android).
    var home: SettingsHome? {
        switch self {
        case .currency, .watchlist, .portfolio, .alarms, .updates: return .general
        case .displayMode, .theme, .priceColors, .coinLogos, .widgets: return .appearance
        case .backup, .about: return .more
        case .licenses, .changeBasis, .markets, .marketAlerts, .speech, .developer: return nil
        }
    }
}

/// Ort einer Unterseite: Gruppe der Hauptseite — wie `SettingsHome` (Android).
enum SettingsHome {
    case general, appearance, more
}

/// Wohin ein Treffer führt; `anchor` ist der Punkt, der kurz hervorgehoben wird.
enum SettingsSearchTarget: Hashable {
    /// Zeile der Hauptseite (Sprache, «App zurücksetzen»).
    case main(String)
    case page(SettingsSearchPage, anchor: String?)

    var anchor: String? {
        switch self {
        case .main(let anchor): return anchor
        case .page(_, let anchor): return anchor
        }
    }
}

/// Ein Eintrag der Suche mit seinem Ziel.
struct SettingsSearchItem: Hashable {
    let entry: SettingsSearchEntry
    let target: SettingsSearchTarget
}

// MARK: - Hervorheben

private struct SettingsHighlightKey: EnvironmentKey {
    static let defaultValue: String? = nil
}

extension EnvironmentValues {
    /// Punkt, der auf der gerade gezeigten Seite hervorgehoben werden soll (oder keiner).
    var settingsHighlight: String? {
        get { self[SettingsHighlightKey.self] }
        set { self[SettingsHighlightKey.self] = newValue }
    }
}

/// Ziel der Suche: Ist der Punkt gemeint (`settingsHighlight`), hinterlegt er sich kurz in der
/// Akzentfarbe; zu ihm scrollen `SettingsSubPage`/`SettingsCardsPage` bzw. die Hauptseite (über
/// die `id`). Ohne Bewegung erscheint die Hinterlegung ohne Überblendung.
private struct SettingsAnchorModifier: ViewModifier {
    let id: String
    @Environment(\.settingsHighlight) private var highlight
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.appAccent) private var accent
    @State private var shown = false

    func body(content: Content) -> some View {
        content
            .background {
                RoundedRectangle(cornerRadius: Radius.field, style: .continuous)
                    .fill(accent.primary.opacity(shown ? SettingsHighlightTiming.opacity : 0))
                    .padding(.horizontal, -Spacing.sm)
                    .accessibilityHidden(true)
            }
            .id(id)
            .task(id: highlight == id) {
                guard highlight == id else {
                    shown = false
                    return
                }
                try? await Task.sleep(nanoseconds: SettingsHighlightTiming.delayNanos)
                withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) { shown = true }
                try? await Task.sleep(nanoseconds: SettingsHighlightTiming.holdNanos)
                withAnimation(reduceMotion ? nil : .easeIn(duration: 0.7)) { shown = false }
            }
    }
}

/// Zeiten und Stärke der Hervorhebung (wie Android).
enum SettingsHighlightTiming {
    /// Bis die Seite eingeschoben ist.
    static let delayNanos: UInt64 = 350_000_000
    static let holdNanos: UInt64 = 1_200_000_000
    /// Hauptseite: so lange bleibt das Ziel gesetzt.
    static let mainNanos: UInt64 = 2_500_000_000
    static let opacity: Double = 0.16
}

extension View {
    /// Macht den Punkt zum Ziel der Suche in den Einstellungen.
    func settingsAnchor(_ id: String) -> some View {
        modifier(SettingsAnchorModifier(id: id))
    }
}

// MARK: - Treffer

/// Treffer als Zeilen der Liste: Titel, darunter grau die Seite bzw. Gruppe; leer ein Hinweis.
/// Unterseiten öffnen sich mit dem Anker; Zeilen der Hauptseite meldet `onMain` (die Suche
/// schliesst sich dafür).
@MainActor
struct SettingsSearchResults: View {
    let items: [SettingsSearchItem]
    let query: String
    let onMain: (String) -> Void

    @Environment(\.dismissSearch) private var dismissSearch

    private var hits: [SettingsSearchItem] {
        // Sprache der App (nicht der Region): Türkisch «I» → «ı» usw.
        let language = Bundle.main.preferredLocalizations.first ?? "en"
        let index = SettingsSearch.index(items.map(\.entry), locale: Locale(identifier: language))
        let byId = Dictionary(items.map { ($0.entry.id, $0) }, uniquingKeysWith: { first, _ in first })
        return SettingsSearch.search(index, query: query).compactMap { byId[$0.id] }
    }

    var body: some View {
        let found = hits
        Section {
            if found.isEmpty {
                Text(L("watchlist_search_empty", query.trimmingCharacters(in: .whitespacesAndNewlines)))
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            ForEach(found, id: \.entry.id) { item in
                switch item.target {
                case .main(let anchor):
                    Button {
                        dismissSearch()
                        onMain(anchor)
                    } label: {
                        resultLabel(item.entry, trailing: true)
                    }
                    .buttonStyle(.plain)
                case .page(let page, let anchor):
                    NavigationLink {
                        SettingsSearchCatalog.destination(page)
                            .environment(\.settingsHighlight, anchor)
                    } label: {
                        resultLabel(item.entry, trailing: false)
                    }
                }
            }
        }
        .listRowBackground(AppColors.container)
    }

    private func resultLabel(_ entry: SettingsSearchEntry, trailing: Bool) -> some View {
        HStack(spacing: Spacing.md) {
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.title)
                    .font(.body)
                    .foregroundStyle(AppColors.onSurface)
                if !entry.path.isEmpty && entry.path != entry.title {
                    Text(entry.path)
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: Spacing.sm)
            // NavigationLink setzt den Pfeil selbst; Zeilen der Hauptseite bekommen ihn hier
            if trailing {
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.outline)
                    .accessibilityHidden(true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
