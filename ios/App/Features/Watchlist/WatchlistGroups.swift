import SwiftUI

// Gruppen der Merkliste — wie `WatchlistGroups.kt` (GroupChips, GroupDialog). Bestände zeigt die Merkliste
// seit 16.2.2 nicht mehr; sie gehören in den Portfolio-Tab.

// MARK: Gruppen-Chips

/// Gruppen-Auswahl oben: «Alle · ★ · ⚡ · Token · Gruppe 1 · Gruppe 2 … · +», waagrecht scrollbar.
/// Tippen filtert, lange drücken öffnet «Gruppe bearbeiten», «+» legt eine an.
/// «Alle», «Token», die Gruppe «TradFi» und «Alpha» nur als Symbol (Formen, Raute, Bank, «α»).
/// Reihenfolge: Alle · ★ · Token · TradFi · α · ⚡ · übrige Gruppen · «+»; «Alpha» (`WatchFilter.alpha`)
/// nur mit Alpha- und anderen Paaren (`WatchFilter.showsAlpha`).
/// Nur was es gibt (wie Android): ★ (Favoriten) erst mit einem Favoriten, ⚡ (Paare, bei denen
/// gerade etwas passiert; `QuickView.activity`, ohne Zahl) nur mit solchen Paaren, «Token» (alles
/// ausser «TradFi», `WatchFilter.tokens`) nur mit TradFi- und anderen Paaren, «+» erst mit
/// einer Gruppe (die erste entsteht über das Aktionsblatt eines Paars); sonst bleibt die Zeile leer.
/// Ist eine vorübergehende Ansicht an, ist keine Gruppe markiert (ausser ⚡ selbst).
/// Der gewählte Symbol-Chip zeigt neben dem Symbol seinen Namen («Alle», «Favoriten» …); tippt man einen Symbol-Chip
/// ab (⚡), zeigt er kurz den Namen darunter (`ChipHintOverlay`) — immer nur einer, ein weiterer Tipp ersetzt ihn
/// (wie Android `ChipHintPopup`). Geht die Zeile über den Rand hinaus, blendet sie am Ende weich aus
/// (`ChipRowFade`) — Hinweis, dass sie weiter scrollt (wie Android `fadeEnd`).
@MainActor
struct WatchlistGroupChips: View {
    let groups: [String]
    /// nil = «Alle».
    let selected: String?
    var hasFavorites: Bool = false
    let onSelect: (String?) -> Void
    /// Lange drücken auf eine Gruppe (nicht «Alle»).
    var onEdit: ((String) -> Void)? = nil
    /// «+»: neue Gruppe.
    var onAdd: (() -> Void)? = nil
    /// ⚡-Chip zeigen.
    var hasActivity: Bool = false
    var quickView: QuickView? = nil
    var onToggleActivity: () -> Void = {}
    /// «Token»-Chip zeigen (`WatchFilter.showsTokens`).
    var hasTokens: Bool = false
    /// «Alpha»-Chip zeigen (`WatchFilter.showsAlpha`).
    var hasAlpha: Bool = false

    /// Form aller Chips: kleine Stufe `Radius.chip` (8) wie Android `ChipShape` — keine Kapsel.
    static let chipShape = RoundedRectangle(cornerRadius: Radius.chip, style: .continuous)

    @Environment(\.appAccent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Symbol-Chip, dessen Name gerade darunter steht.
    @State private var hintKey: String?
    /// Zählt die Tipps: jeder startet den Zeitgeber neu (auch derselbe Chip nochmals).
    @State private var hintTap = 0

    /// So lange bleibt der Namenshinweis stehen.
    private static let hintSeconds: Double = 1.5
    /// Koordinatenraum der Chip-Zeile (für den Ausblend-Rand).
    nonisolated private static let rowSpace = "watchlistGroupChips"
    /// Breite der sichtbaren Zeile und Ende (Hinterkante) der Chips darin.
    @State private var rowWidth: CGFloat = 0
    @State private var chipsEnd: CGFloat = 0

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
              let activitySelected = quickView == .activity
              if Self.showsChips(groups: groups, selected: selected, hasFavorites: hasFavorites,
                                 hasActivity: hasActivity || activitySelected, hasAlpha: hasAlpha) {
                // Nur das Formen-Symbol (VoiceOver: «Alle»)
                chip(L("group_all"), icon: .category, isSelected: isGroupSelected(nil), action: { onSelect(nil) },
                     hint: "all")
                // Nur der Stern (VoiceOver: «Favoriten»); auch ohne Favoriten sichtbar, solange die
                // Ansicht gewählt ist (sonst kein Weg zurück)
                if hasFavorites || WatchFilter.isFavorites(selected) {
                    chip(L("group_favorites"), icon: .system("star.fill"),
                         isSelected: isGroupSelected(WatchFilter.favorites),
                         action: { onSelect(WatchFilter.favorites) }, hint: "favorites")
                }
                // Token (Raute) und TradFi (Bank) nur zusammen, dann α, ⚡ und die übrigen Gruppen
                tokenChips
                if hasAlpha || WatchFilter.isAlpha(selected) {
                    // «α» (VoiceOver: «Alpha-Tokens»); keine Gruppe, also nicht bearbeitbar; bleibt
                    // sichtbar, solange gewählt
                    chip(L("group_alpha"), icon: .kind(.ALPHA),
                         isSelected: isGroupSelected(WatchFilter.alpha),
                         action: { onSelect(WatchFilter.alpha) }, hint: "alpha")
                }
                if hasActivity || activitySelected {
                    // ⚡ wie in den Zeilen, nur wenn gerade etwas passiert; VoiceOver: «Ungewöhnliche Aktivität»
                    chip(L("activity_card_title"), icon: .system("bolt.fill"),
                         isSelected: activitySelected, action: onToggleActivity, hint: "activity")
                }
                ForEach(groups.filter { $0 != AutoGroup.tradFi }, id: \.self) { group in
                    groupChip(group)
                }
                if let onAdd, !groups.isEmpty {
                    Button(action: onAdd) {
                        Image(systemName: "plus")
                            .font(.subheadline.weight(.semibold))
                            // Neutral wie die übrigen Chips: Textfarbe, grauer Rand, keine Füllung
                            .foregroundStyle(AppColors.onSurface)
                            // Gleicher Innenabstand wie die Symbol-Chips: wächst mit der Schrift mit
                            .padding(.horizontal, Spacing.md)
                            .padding(.vertical, Spacing.sm)
                            // Rand ist die einzige Kontur: outline (≥ 3:1, WCAG 1.4.11) statt outlineVariant (≈ 2:1)
                            .overlay(Self.chipShape.strokeBorder(AppColors.outline, lineWidth: 1))
                            // Tippfläche 44 pt hoch, sichtbar bleibt das kleine Feld
                            .contentShape(Self.chipShape.inset(by: -6))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L("group_add"))
                    .largeContentLabel(L("group_add"), systemImage: "plus")
                }
              }
            }
            .padding(.vertical, 2)
            .onGeometryChange(for: CGFloat.self) { $0.frame(in: .named(Self.rowSpace)).maxX } action: { chipsEnd = $0 }
        }
        .coordinateSpace(.named(Self.rowSpace))
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { rowWidth = $0 }
        .scrollClipDisabled()
        // Weicher Rand am Ende, solange weitere Chips folgen (nur Zeichnung; VoiceOver unverändert)
        .modifier(ChipRowFade(active: chipsEnd > rowWidth + 1))
        .task(id: hintTap) { await hideHintLater() }
        .haptic(.selection, trigger: selected)
        .haptic(.selection, trigger: quickView)
    }

    private func isGroupSelected(_ group: String?) -> Bool { quickView == nil && selected == group }

    /// Namenshinweis unter dem Symbol-Chip `key` zeigen (ersetzt einen anderen).
    private func showHint(_ key: String) {
        withAnimation(hintAnimation) { hintKey = key }
        hintTap += 1
    }

    /// Nach `hintSeconds` ausblenden; ein neuer Tipp bricht das ab und beginnt neu.
    private func hideHintLater() async {
        guard hintKey != nil else { return }
        try? await Task.sleep(for: .seconds(Self.hintSeconds))
        guard !Task.isCancelled else { return }
        withAnimation(hintAnimation) { hintKey = nil }
    }

    /// Ein- und Ausblenden; mit «Bewegung reduzieren» sofort.
    private var hintAnimation: Animation? { reduceMotion ? nil : .easeInOut(duration: 0.15) }

    /// Token und TradFi nur zusammen: beide nur, wenn es TradFi- und andere Paare gibt
    /// (`WatchFilter.showsTokens`); TradFi bzw. Token bleiben sichtbar, solange sie gewählt sind.
    @ViewBuilder
    private var tokenChips: some View {
        if hasTokens || WatchFilter.isTokens(selected) {
            // Raute (VoiceOver: «Token»); keine Gruppe, also nicht bearbeitbar
            chip(L("group_tokens"), icon: .kind(.TOKEN),
                 isSelected: isGroupSelected(WatchFilter.tokens),
                 action: { onSelect(WatchFilter.tokens) }, hint: "tokens")
        }
        if groups.contains(AutoGroup.tradFi) && (hasTokens || selected == AutoGroup.tradFi) {
            groupChip(AutoGroup.tradFi)
        }
    }

    /// Chip einer Gruppe: «TradFi» als Bank-Symbol (VoiceOver: der Gruppenname), die übrigen als Text.
    /// Nur das Symbol bekommt den Namenshinweis; Text-Chips zeigen ihren Namen schon.
    private func groupChip(_ group: String) -> some View {
        let icon = Self.groupIcon(group)
        return chip(group, icon: icon,
                    isSelected: isGroupSelected(group), action: { onSelect(group) },
                    longPress: onEdit.map { edit -> () -> Void in { edit(group) } },
                    hint: icon == nil ? nil : "group:\(group)")
    }

    /// Symbol statt Text für eine Gruppe: nur «TradFi» (Bank wie `ic_bank.xml`).
    private static func groupIcon(_ group: String) -> GroupChipIcon? {
        group == AutoGroup.tradFi ? .kind(.TRADFI) : nil
    }

    /// Chips zeigen? Nur wenn es neben «Alle» etwas zu wählen gibt (oder eine Auswahl aktiv ist).
    nonisolated static func showsChips(groups: [String], selected: String?, hasFavorites: Bool, hasActivity: Bool = false,
                                       hasAlpha: Bool = false) -> Bool {
        !groups.isEmpty || hasFavorites || hasActivity || hasAlpha || selected != nil
    }

    /// Ab so vielen Paaren lohnt sich Gruppieren: vorher bleibt die leere Chip-Zeile ohne Hinweis
    /// (Gruppen oder Favoriten zeigen die Chips trotzdem; G-14). Wie Android `GROUPS_HINT_MIN_PAIRS`.
    nonisolated static let hintMinPairs = 5

    /// Hinweis «Hier erscheinen deine Gruppen» statt der Chips? Nur ohne Chips (`showsChips`),
    /// ab `hintMinPairs` Paaren und ohne vorübergehende Ansicht, Suche oder Sortieren — wie
    /// Android `showsGroupsHint`. `onboarded`: erster Gebrauch vorbei (`WatchlistOnboarding.Visible.groupsHint`).
    nonisolated static func showsHint(groups: [String], selected: String?, hasFavorites: Bool, hasActivity: Bool,
                                      quickView: QuickView?, searching: Bool, sorting: Bool, hasAlpha: Bool = false,
                                      pairCount: Int = hintMinPairs, onboarded: Bool = true) -> Bool {
        guard onboarded, quickView == nil, !searching, !sorting, pairCount >= hintMinPairs else { return false }
        return !showsChips(groups: groups, selected: selected, hasFavorites: hasFavorites, hasActivity: hasActivity,
                           hasAlpha: hasAlpha)
    }

    /// `icon`: nur ein Symbol statt `title` (Alle, ★, ⚡, Token, TradFi); `title` ist dann der VoiceOver-Name und
    /// steht beim gewählten Chip als Text neben dem Symbol.
    /// `hint`: Schlüssel eines Symbol-Chips — Tippen zeigt `title` kurz darunter (nur ungewählt).
    /// Alle Chips neutral: Text und Symbol in der Textfarbe, grauer Rand, keine Füllung. Der gewählte
    /// bekommt Rand, Text und Symbol in der Themenfarbe (orange) — ebenfalls ohne Füllung (wie Android).
    private func chip(_ title: String, icon: GroupChipIcon? = nil,
                      isSelected: Bool, action: @escaping () -> Void,
                      longPress: (() -> Void)? = nil, spoken: String? = nil,
                      hint: String? = nil) -> some View {
        let tint = isSelected ? accent.primary : AppColors.onSurface
        // Gewählter Symbol-Chip: Name daneben, darum kein Hinweis darunter
        let hintShown = hint != nil && hintKey == hint && !isSelected
        let tap: () -> Void = {
            action()
            if let hint { showHint(hint) }
        }
        return Group {
            if let icon {
                HStack(spacing: Spacing.xs) {
                    GroupChipIconView(icon: icon)
                        .foregroundStyle(tint)
                        .padding(.horizontal, -2)
                    if isSelected {
                        // Wie die Text-Chips; VoiceOver liest den Namen einmal (Label des Chips)
                        Text(title)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                            .dynamicTypeSize(...AppTabBarMetrics.maxTypeSize)
                    }
                }
            } else {
                // Schrift wächst bis ≈ 1,5-fach mit (wie die Tab-Leiste, `AppTabBarMetrics.maxTypeSize`, und Android)
                Text(title)
                    .font(.subheadline.weight(isSelected ? .semibold : .regular))
                    .lineLimit(1)
                    .dynamicTypeSize(...AppTabBarMetrics.maxTypeSize)
            }
        }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.sm)
            // Mindestens 32 pt hoch wie Android, bei grosser Schrift höher — nie abgeschnitten
            .frame(minHeight: 32)
            .foregroundStyle(tint)
            .overlay(Self.chipShape.strokeBorder(isSelected ? accent.primary : AppColors.outline,
                                                 lineWidth: isSelected ? 1.5 : 1))
            .modifier(ChipHintOverlay(text: title, shown: hintShown))
            // Tippfläche höher als der Chip (44 pt), ohne die Zeile höher zu machen
            .contentShape(Rectangle().inset(by: -6))
            // Tippen und langes Drücken getrennt (ein Button kennt kein langes Drücken)
            .onTapGesture(perform: tap)
            .onLongPressGesture(minimumDuration: 0.45) {
                guard let longPress else { return }
                Haptics.play(.longPress)
                longPress()
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(spoken ?? title)
            .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
            // Symbol-Chips: Symbol und Name gross beim langen Drücken (Text-Chips wachsen selbst mit
            // und behalten das lange Drücken für «Gruppe bearbeiten»)
            .modifier(ChipLargeContent(title: title, icon: icon))
            .accessibilityAction(named: Text(L("group_edit_title"))) {
                if let longPress { longPress() }
            }
    }
}

/// Large Content Viewer nur für Symbol-Chips (siehe `largeContentLabel`): Symbol und Name gross.
private struct ChipLargeContent: ViewModifier {
    let title: String
    let icon: GroupChipIcon?

    @ViewBuilder
    func body(content: Content) -> some View {
        if let icon {
            content.accessibilityShowsLargeContentViewer {
                GroupChipIconView(icon: icon)
                Text(title)
            }
        } else {
            content
        }
    }
}

// MARK: Ausblend-Rand

/// Weicher Ausblend-Rand am Ende der Chip-Zeile (rechts, bei RTL links), solange `active` — Hinweis, dass die Zeile
/// weiter scrollt. Die Maske liegt immer an (gleiche View-Identität); `active` ändert nur das Ende des Verlaufs
/// (ausgeblendet bzw. voll deckend). So wird die ScrollView beim Wechsel nicht neu aufgebaut und springt nicht an den
/// Anfang zurück. Die Maske reicht oben und unten weit über die Zeile, damit der Namenshinweis unter einem Chip
/// sichtbar bleibt. Wie Android `fadeEnd` (nur Zeichnung).
private struct ChipRowFade: ViewModifier {
    let active: Bool

    /// Breite des Rands (wie Android `CHIP_FADE_WIDTH`).
    private static let width: CGFloat = 24

    func body(content: Content) -> some View {
        content.mask {
            HStack(spacing: 0) {
                Rectangle()
                LinearGradient(colors: [.black, active ? .clear : .black], startPoint: .leading, endPoint: .trailing)
                    .flipsForRightToLeftLayoutDirection(true)
                    .frame(width: Self.width)
            }
            .padding(.vertical, -60)
        }
    }
}

// MARK: Namenshinweis

/// Kleiner Namenshinweis unter einem Symbol-Chip: dunkle Blase (inverse Farben), `.caption`.
/// Als Overlay unter dem Chip — macht die Zeile nicht höher; die Chip-Zeile schneidet nicht ab
/// (`.scrollClipDisabled`). Für VoiceOver unsichtbar (der Chip nennt den Namen schon), nicht antippbar.
private struct ChipHintOverlay: ViewModifier {
    let text: String
    let shown: Bool

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .bottom) {
                if shown { label }
            }
            .zIndex(shown ? 1 : 0)
    }

    private var label: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AppColors.background)
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(AppColors.onSurface, in: RoundedRectangle(cornerRadius: Radius.chip, style: .continuous))
            .fixedSize()
            // Oberkante 4 pt unter dem Chip statt bündig mit dessen Unterkante
            .alignmentGuide(.bottom) { d in d[.top] - 4 }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .transition(.opacity)
    }
}

// MARK: Chip-Symbole

/// Symbol eines Gruppen-Chips: SF Symbol oder eigene Form (wie die Android-Vektoren).
enum GroupChipIcon: Equatable {
    case system(String)
    /// «Alle»: Dreieck, Kreis, Quadrat (`ic_category.xml`).
    case category
    /// «Token»: Raute als Umriss (`ic_token.xml`).
    case token
    /// Art-Symbol (Token, TradFi, «α»; `AssetKindIconView`).
    case kind(AssetKind)
}

/// Zeichnet ein `GroupChipIcon` so gross wie die SF Symbols im Chip (`.subheadline`, halbfett):
/// Die eigenen Formen liegen über einem unsichtbaren ★ — gleiche Chip-Grösse wie ★/⚡, auch mit
/// grösserer Schrift. Das 24er-Raster hat Rand (wie die Android-Vektoren), darum 2 pt grösser.
struct GroupChipIconView: View {
    let icon: GroupChipIcon

    var body: some View {
        switch icon {
        case .system(let name):
            Image(systemName: name)
                .font(.subheadline.weight(.semibold))
        case .category:
            sizeBox.overlay(CategoryIconShape().padding(-2))
        case .token:
            sizeBox.overlay(TokenIconShape().padding(-2))
        case .kind(let kind):
            switch kind {
            case .TOKEN:
                sizeBox.overlay(TokenIconShape().padding(-2))
            case .TRADFI:
                Image(systemName: AssetKindIconView.tradFiSymbol)
                    .font(.subheadline.weight(.semibold))
            case .ALPHA:
                // Umriss wie die Raute: gleich gross und auf gleicher Höhe (wie Android `ic_alpha`)
                sizeBox.overlay(AlphaIconShape().padding(-2))
            }
        }
    }

    private var sizeBox: some View {
        Image(systemName: "star.fill")
            .font(.subheadline.weight(.semibold))
            .hidden()
            .accessibilityHidden(true)
    }
}
