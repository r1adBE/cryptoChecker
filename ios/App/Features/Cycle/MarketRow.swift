import SwiftUI

/// Art einer `MarketRow`: Platzhalter, Inhalt oder Fehler (mit Meldung).
enum MarketRowState: Equatable {
    case loading
    case shown
    case failed(String)
}

/// Ein Eintrag in «Einordnung» und «Daten» des Markt-Tabs — als eigene Karte wie im Register
/// «Jetzt» (Fläche `AppColors.container`, Rundung 20 pt, 16 pt Innenabstand, 12 pt Abstand zur
/// Karte darüber; die erste Karte eines Registers (`first`) wie «Crypto Pulse» nur 4 pt).
/// Oben in der Karte der Titel als Abschnittsüberschrift mit Strich in der Themenfarbe, ganz
/// rechts ein Pfeil nach unten (zu) bzw. oben (offen), wenn sich die Karte aufklappen lässt.
/// Darunter links eine Nebenzeile (Label, Nebenfarbe), rechts der Wert (Title, halbfett,
/// gleich breite Ziffern), dahinter optional die Veränderung in Prozent (`change`) als
/// gefüllte `ChangePill` «+1.20%» (mittel). `below` steht in voller Breite direkt darunter (Skala von
/// Fear & Greed).
///
/// Tippen klappt `details` in derselben Karte auf und zu (`expanded`). Beim Laden stehen der
/// echte Titel und graue Balken in genau der Höhe von Nebenzeile und Wert (kein Springen); bei
/// Fehler die Meldung und «Erneut». Wie `MarketRow.kt`.
///
/// Mit `stamp` endet die Nebenzeile mit Anbieter und Alter der Daten («… · CoinGecko ·
/// vor 3 Min.», siehe `DataFreshness`); ist der Wert älter als drei Gültigkeitsdauern, steht das
/// Alter in Warnfarbe und VoiceOver sagt «veraltet». Die Nebenzeile darf dann zwei Zeilen
/// brauchen (grosse Schrift).
///
/// VoiceOver: der Kopf der Karte ist ein Element und eine Überschrift (Titel, Nebenzeile, Wert,
/// Veränderung, Skala) mit Wert auf-/zugeklappt und dem Hinweis «… einblenden» bzw.
/// «… ausblenden»; `spoken` ersetzt Titel bis Veränderung durch einen Satz.
struct MarketRow: View {
    let title: String
    var secondary: String = ""
    var value: String? = nil
    var state: MarketRowState = .shown
    var first = false
    var change: Double? = nil
    var secondaryLines = 1
    var spoken: String? = nil
    /// Herkunft und Stand der gezeigten Daten; nil = keine Angabe.
    var stamp: DataStamp? = nil
    var onRetry: () -> Void = {}
    /// nil = nicht aufklappbar.
    var expanded: Binding<Bool>? = nil
    /// Thema des Info-Blatts: «ⓘ» direkt hinter dem Titel; nil = ohne.
    var info: MarketInfoTopic? = nil
    /// Wert als farbige Schrift ohne Fläche in dieser Farbe (z. B. Fear & Greed in der Stufenfarbe der
    /// Skala, wie im Puls-Widget); nil = Wert in der normalen Textfarbe. Wie Android `MarketRow(valueColor)`.
    var valueColor: Color? = nil
    var below: AnyView? = nil
    var details: AnyView? = nil

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.appAccent) private var accent

    private var expandable: Bool { expanded != nil && details != nil }
    private var isExpanded: Bool { expandable && expanded?.wrappedValue == true }

    private var cardShape: RoundedRectangle {
        RoundedRectangle(cornerRadius: CardLevel.top.radius, style: .continuous)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            if isExpanded, let details {
                detailsBox(details)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.container, in: cardShape)
        .clipShape(cardShape)
        .padding(.top, first ? 4 : 12)
    }

    /// Aufgeklappte Details in derselben Karte, unter dem Kopf.
    private func detailsBox(_ details: AnyView) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            details
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Spacing.lg)
        .padding(.bottom, Spacing.lg)
        .transition(.opacity)
    }

    /// Kopf der Karte samt Info-Zeichen: Das Zeichen liegt als Überlagerung genau auf seinem
    /// Platzhalter in der Titelzeile — so bleibt es für VoiceOver ein eigener Knopf ausserhalb
    /// des zusammengefassten Kopfs, und sein Tipp klappt die Karte nicht auf.
    private var header: some View {
        headerBody
            .overlayPreferenceValue(MarketInfoAnchorKey.self) { anchor in
                infoOverlay(anchor)
            }
    }

    @ViewBuilder
    private func infoOverlay(_ anchor: Anchor<CGRect>?) -> some View {
        if let anchor, let info {
            GeometryReader { proxy in
                let rect = proxy[anchor]
                MarketInfoButton(topic: info)
                    .position(x: rect.midX, y: rect.midY)
            }
        }
    }

    /// Kopf der Karte (Titel, Zeile, Skala) — als Ganzes antippbar, wenn aufklappbar.
    @ViewBuilder
    private var headerBody: some View {
        if expandable {
            headerContent
                .onTapGesture(perform: toggle)
                .accessibilityElement(children: headerChildren)
                .accessibilityAddTraits([.isButton, .isHeader])
                .accessibilityValue(L(isExpanded ? "a11y_expanded" : "a11y_collapsed"))
                .accessibilityHint(L(isExpanded ? "market_section_collapse" : "market_section_expand", title))
                .accessibilityAction { toggle() }
        } else {
            headerContent
                .accessibilityElement(children: headerChildren)
                .accessibilityAddTraits(.isHeader)
        }
    }

    /// Bei Fehler bleibt «Erneut» ein eigenes Element, sonst ein Element für den ganzen Kopf.
    private var headerChildren: AccessibilityChildBehavior {
        if case .failed = state { return .contain }
        return .combine
    }

    private var headerContent: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            titleRow
            line
            if let below {
                below.padding(.top, Spacing.xs)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Spacing.lg)
        .contentShape(Rectangle())
    }

    /// Überschrift mit Strich in der Themenfarbe, direkt dahinter der Platz des Info-Zeichens
    /// (das Zeichen selbst liegt darüber, siehe `header`), ganz rechts der Pfeil (nur wenn aufklappbar).
    private var titleRow: some View {
        HStack(spacing: Spacing.sm) {
            HStack(spacing: 2) {
                Text(title)
                    .sectionTitleStyle()
                    // Der gesprochene Satz enthält den Titel schon
                    .accessibilityHidden(state == .shown && spoken != nil)
                if info != nil {
                    infoSlot
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if expandable {
                chevron
            }
        }
    }

    /// Unsichtbarer Platzhalter für das Info-Zeichen; meldet seine Lage an `header`.
    private var infoSlot: some View {
        Color.clear
            .frame(width: MarketInfoButton.slotWidth, height: MarketInfoButton.slotHeight)
            .anchorPreference(key: MarketInfoAnchorKey.self, value: .bounds) { $0 }
            .accessibilityHidden(true)
    }

    /// Pfeil nach unten (zu) bzw. oben (offen) in der Themenfarbe wie `ExpandToggleLabel`;
    /// dreht mit dem Auf-/Zuklappen (ohne Animation bei reduzierter Bewegung, siehe `toggle`).
    private var chevron: some View {
        Image(systemName: "chevron.down")
            .font(.footnote.weight(.bold))
            .foregroundStyle(accent.primary)
            .rotationEffect(.degrees(isExpanded ? 180 : 0))
            .accessibilityHidden(true)
    }

    private func toggle() {
        withAnimation(reduceMotion ? nil : .easeInOut(duration: CycleReveal.swapSeconds)) {
            expanded?.wrappedValue.toggle()
        }
    }

    /// Nebenzeile links (bzw. Platzhalter oder Fehlermeldung).
    @ViewBuilder
    private var secondaryText: some View {
        switch state {
        case .loading:
            SkeletonPulse {
                Text(verbatim: " ")
                    .font(AppFont.label)
                    .cycleSkeletonBar(width: 120)
            }
        case .failed(let message):
            // Neutral wie `CycleUnavailableRow`: eine ausgefallene Quelle ist kein Eingabefehler
            HStack(spacing: Spacing.sm) {
                CycleUnavailableIcon()
                Text(message)
                    .font(AppFont.label)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(2)
            }
        case .shown:
            if let stamp {
                MarketStampLine(secondary: secondary, stamp: stamp, lines: max(secondaryLines, 2))
            } else {
                Text(verbatim: secondary.isEmpty ? " " : secondary)
                    .font(AppFont.label)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(secondaryLines)
            }
        }
    }

    /// Wert rechts (bzw. Platzhalter oder «Erneut»).
    @ViewBuilder
    private var trailing: some View {
        switch state {
        case .loading:
            SkeletonPulse {
                Text(verbatim: " ")
                    .font(AppFont.title)
                    .cycleSkeletonBar(width: 56)
            }
        case .failed:
            CycleRetryButton(action: onRetry)
        case .shown:
            if let value, let valueColor {
                // Farbige Schrift in der Bedeutungsfarbe, ohne Pille; die Zahl bleibt (Farbe trägt nie allein)
                Text(verbatim: value)
                    .font(AppFont.title)
                    .monospacedDigit()
                    .foregroundStyle(valueColor)
                    .lineLimit(1)
                    .fixedSize()
            } else if let value {
                Text(verbatim: value)
                    .font(AppFont.title)
                    .monospacedDigit()
                    .foregroundStyle(AppColors.onSurface)
                    .multilineTextAlignment(.trailing)
                    .lineLimit(2)
                    .frame(maxWidth: 200, alignment: .trailing)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let change {
                ChangePill(change: change, size: .medium)
                    .fixedSize()
            }
        }
    }

    @ViewBuilder
    private var line: some View {
        let row = HStack(alignment: .center, spacing: Spacing.md) {
            secondaryText
                .frame(maxWidth: .infinity, alignment: .leading)
            trailing
        }
        .frame(minHeight: 32)

        switch state {
        case .loading:
            row
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(L("loading_hint"))
        case .shown:
            if let spoken {
                // Gesprochener Satz samt Herkunft und Alter («…, CoinGecko, vor 3 Min., veraltet»)
                let full = stamp.map { "\(spoken), \(MarketStampText.spoken($0, now: TimeUtils.nowMillis))" } ?? spoken
                row
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(Text(verbatim: full))
            } else {
                row.accessibilityElement(children: .combine)
            }
        case .failed:
            row
        }
    }
}

/// Texte zu Herkunft und Alter einer Zeile — wie `rememberStampText`/`dataAgeText` (MarketRow.kt).
enum MarketStampText {
    static let separator = " · "

    /// «gerade eben», «vor 3 Min.», «heute 02:00», sonst das Datum (siehe `DataFreshness.age`).
    static func age(_ savedAt: Int64, now: Int64) -> String {
        switch DataFreshness.age(savedAt: savedAt, now: now) {
        case .justNow:
            return L("time_just_now")
        case .minutes(let minutes):
            return L("market_age_minutes", count: minutes)
        case .today(let at):
            return L("market_age_today", PriceFormat.shortTime(at))
        case .date(let at):
            let f = DateFormatter()
            f.dateStyle = .medium
            f.timeStyle = .none
            return f.string(from: Date(millis: at))
        }
    }

    /// Alles vor dem Alter: «Nebenzeile · Anbieter · » (leere Teile fallen weg).
    static func head(_ secondary: String, provider: String?) -> String {
        let parts = [secondary, provider ?? ""].filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        return parts.isEmpty ? "" : parts.joined(separator: separator) + separator
    }

    /// Gesprochene Form: «CoinGecko, vor 3 Min.» bzw. mit «, veraltet».
    static func spoken(_ stamp: DataStamp, now: Int64) -> String {
        let stale = DataFreshness.isStale(savedAt: stamp.savedAt, now: now, ttl: stamp.ttl)
        return [stamp.provider, age(stamp.savedAt, now: now), stale ? L("widget_outdated") : nil]
            .compactMap { $0 }
            .joined(separator: ", ")
    }
}

/// Nebenzeile mit Herkunft: «Nebenzeile · Anbieter · Alter»; das Alter läuft mit (alle 30 s)
/// und steht in Warnfarbe, wenn veraltet (VoiceOver: «veraltet»). Wie `StampedSecondary` (MarketRow.kt).
struct MarketStampLine: View {
    let secondary: String
    let stamp: DataStamp
    var lines = 2

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let now = Int64((context.date.timeIntervalSince1970 * 1000).rounded())
            let stale = DataFreshness.isStale(savedAt: stamp.savedAt, now: now, ttl: stamp.ttl)
            let head = MarketStampText.head(secondary, provider: stamp.provider)
            let age = MarketStampText.age(stamp.savedAt, now: now)
            Text(Self.attributed(head: head, age: age, stale: stale))
                .font(AppFont.label)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .lineLimit(lines)
                .accessibilityLabel(Text(verbatim: stale ? "\(head)\(age), \(L("widget_outdated"))" : head + age))
        }
    }

    private static func attributed(head: String, age: String, stale: Bool) -> AttributedString {
        var text = AttributedString(head)
        var ageText = AttributedString(age)
        if stale { ageText.swiftUI.foregroundColor = AppColors.warningText }
        text.append(ageText)
        return text
    }
}
