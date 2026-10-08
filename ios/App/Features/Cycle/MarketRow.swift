import SwiftUI

/// Art einer `MarketRow`: Platzhalter, Inhalt oder Fehler (mit Meldung).
enum MarketRowState: Equatable {
    case loading
    case shown
    case failed(String)
}

/// Eine Zeile in «Einordnung» und «Daten» des Markt-Tabs — ohne Karte: links der Titel
/// (Body) und eine Nebenzeile (Label, Nebenfarbe), rechts der Wert (Title, halbfett, gleich
/// breite Ziffern), dahinter optional eine kleine Veränderung (`change`, z. B. «▲ +1.20%» in
/// der Kursfarbe). Darüber eine dünne Trennlinie (`divider`), `below` steht in voller Breite
/// direkt unter der Zeile (Skala von Fear & Greed).
///
/// Tippen klappt `details` darunter auf und zu (`expanded`) — dieselben Inhalte, die früher
/// in der Karte standen. Beim Laden stehen der echte Titel und graue Balken in genau der Höhe
/// von Nebenzeile und Wert (kein Springen); bei Fehler die Meldung und «Erneut».
/// Wie `MarketRow.kt`.
///
/// Mit `stamp` endet die Nebenzeile mit Anbieter und Alter der Daten («… · CoinGecko ·
/// vor 3 Min.», siehe `DataFreshness`); ist der Wert älter als drei Gültigkeitsdauern, steht das
/// Alter in Warnfarbe und VoiceOver sagt «veraltet». Die Nebenzeile darf dann zwei Zeilen
/// brauchen (grosse Schrift).
///
/// VoiceOver: die Zeile ist ein Element (Titel, Nebenzeile, Wert, Veränderung, Skala) mit
/// Wert auf-/zugeklappt; `spoken` ersetzt Titel bis Veränderung durch einen Satz.
struct MarketRow: View {
    let title: String
    var secondary: String = ""
    var value: String? = nil
    var state: MarketRowState = .shown
    var divider = true
    var change: String? = nil
    var changeColor: Color = AppColors.onSurfaceVariant
    var secondaryLines = 1
    var spoken: String? = nil
    /// Herkunft und Stand der gezeigten Daten; nil = keine Angabe.
    var stamp: DataStamp? = nil
    var onRetry: () -> Void = {}
    /// nil = nicht aufklappbar.
    var expanded: Binding<Bool>? = nil
    var below: AnyView? = nil
    var details: AnyView? = nil

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var expandable: Bool { expanded != nil && details != nil }
    private var isExpanded: Bool { expandable && expanded?.wrappedValue == true }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if divider {
                Rectangle()
                    .fill(AppColors.outlineVariant)
                    .frame(height: 1)
                    .accessibilityHidden(true)
            }
            header
            if isExpanded, let details {
                VStack(alignment: .leading, spacing: 0) {
                    details
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 4)
                .padding(.bottom, Spacing.md)
                .transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Zeile samt Skala darunter — als Ganzes antippbar (ausser bei Fehler ohne Details).
    @ViewBuilder
    private var header: some View {
        let content = VStack(alignment: .leading, spacing: 0) {
            line
            if let below {
                below
                    .padding(.horizontal, 4)
                    .padding(.bottom, Spacing.md)
            }
        }
        .contentShape(Rectangle())

        if expandable {
            content
                .onTapGesture(perform: toggle)
                .accessibilityElement(children: state == .shown ? .combine : .contain)
                .accessibilityAddTraits(.isButton)
                .accessibilityValue(L(isExpanded ? "a11y_expanded" : "a11y_collapsed"))
                .accessibilityAction(perform: toggle)
        } else {
            content
        }
    }

    private func toggle() {
        withAnimation(reduceMotion ? nil : .easeInOut(duration: CycleReveal.swapSeconds)) {
            expanded?.wrappedValue.toggle()
        }
    }

    @ViewBuilder
    private var line: some View {
        let row = HStack(alignment: .center, spacing: Spacing.md) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(AppFont.body)
                    .foregroundStyle(AppColors.onSurface)
                switch state {
                case .loading:
                    CycleSkeleton {
                        Text(verbatim: " ")
                            .font(AppFont.label)
                            .cycleSkeletonBar(width: 120)
                    }
                case .failed(let message):
                    Text(message)
                        .font(AppFont.label)
                        .foregroundStyle(AppColors.error)
                        .lineLimit(1)
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
            .frame(maxWidth: .infinity, alignment: .leading)

            switch state {
            case .loading:
                CycleSkeleton {
                    Text(verbatim: " ")
                        .font(AppFont.title)
                        .cycleSkeletonBar(width: 56)
                }
            case .failed:
                CycleRetryButton(action: onRetry)
            case .shown:
                if let value {
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
                    Text(verbatim: change)
                        .font(AppFont.label.weight(.semibold))
                        .monospacedDigit()
                        .foregroundStyle(changeColor)
                        .lineLimit(1)
                        .fixedSize()
                }
            }
        }
        // Links und rechts wie die Abschnittsüberschrift eingerückt
        .padding(.horizontal, 4)
        .padding(.vertical, Spacing.md)

        switch state {
        case .loading:
            row
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(verbatim: "\(title), \(L("loading_hint"))"))
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

/// Überschrift eines zuklappbaren Abschnitts im Markt-Tab («Einordnung», «Daten»): links der
/// Titel, zugeklappt rechts eine kurze Zusammenfassung (`summary`, z. B. «Gier 72 · Neutral»),
/// dahinter ein Pfeil. Tippen klappt auf und zu (`onToggle`; die Animation macht der Aufrufer).
///
/// VoiceOver: eine Überschrift und Taste mit Wert auf-/zugeklappt und dem Hinweis «… einblenden»
/// bzw. «… ausblenden»; zugeklappt wird die Zusammenfassung mitgelesen.
/// Wie `MarketSectionHeader` (MarketRow.kt).
struct MarketSectionHeader: View {
    let title: String
    let summary: String
    let expanded: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            HStack(spacing: Spacing.md) {
                Text(title)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
                    .layoutPriority(1)
                // Offen: keine Zusammenfassung (die Zeilen stehen darunter)
                Text(verbatim: expanded ? "" : summary)
                    .font(AppFont.label)
                    .monospacedDigit()
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .trailing)
                Image(systemName: "chevron.down")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .rotationEffect(.degrees(expanded ? 180 : 0))
            }
            .padding(.horizontal, 4)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: expanded || summary.isEmpty ? title : "\(title), \(summary)"))
        .accessibilityValue(L(expanded ? "a11y_expanded" : "a11y_collapsed"))
        .accessibilityHint(L(expanded ? "market_section_collapse" : "market_section_expand", title))
        .accessibilityAddTraits([.isHeader, .isButton])
    }
}
