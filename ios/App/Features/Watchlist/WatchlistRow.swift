import SwiftUI
import UIKit

// MARK: Hilfen

/// Kleine Haptik-Helfer für die Merkliste.
enum WatchlistHaptics {
    @MainActor static func impact(_ style: UIImpactFeedbackGenerator.FeedbackStyle = .medium) {
        UIImpactFeedbackGenerator(style: style).impactOccurred()
    }

    @MainActor static func selection() {
        UISelectionFeedbackGenerator().selectionChanged()
    }
}

/// Zeitangaben der Merkliste.
enum WatchlistTime {
    private static let relative: RelativeDateTimeFormatter = {
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .abbreviated
        f.dateTimeStyle = .numeric
        return f
    }()

    /// «gerade eben» bzw. «vor 2 Min.» in der Sprache des Geräts.
    static func ago(_ millis: Int64, now: Int64) -> String {
        if now - millis < 60_000 { return L("time_just_now") }
        return relative.localizedString(for: Date(millis: millis), relativeTo: Date(millis: now))
    }

    /// Veraltet: noch nie aktualisiert oder älter als die Schwelle.
    static func isStale(_ watch: Watch, now: Int64, staleAfter: Int64) -> Bool {
        !ConnectionErrors.isNotTraded(watch.lastError) && (watch.lastUpdate <= 0 || now - watch.lastUpdate > staleAfter)
    }
}

// MARK: Zeile

/// Kompakte Zeile: Coin-Logo (Stern bei Favoriten, nur mit Coin-Logos), Paar, Börse, Kurs und Prozent-Pille.
/// Bestände zeigt die Merkliste nicht mehr — dafür gibt es den Portfolio-Tab.
/// Tippen öffnet die Aktionen sofort (kein Doppeltippen),
/// lange drücken und ziehen sortiert (macht die Liste).
struct WatchlistRow: View {
    let watch: Watch
    let alarmCount: Int
    let now: Int64
    let staleAfter: Int64
    /// Ab diesem Alter «veraltet» in der Zeitzeile (3 × Intervall, mind. 15 Min.); 0 = nie.
    var outdatedAfter: Int64 = 0
    /// Wird gerade (einzeln oder komplett) aktualisiert.
    let loading: Bool
    var highlighted = false
    var sorting = false
    /// ⚡ Ungewöhnliche Aktivität — Tipp öffnet «Warum bewegt sich das?».
    var hasActivity = false
    /// Kurs in der Umrechnungswährung, z. B. «≈ 61’234 CHF»; nil = nichts zeigen.
    var converted: String? = nil
    /// Mini-Chart (24 h) laden und zeigen — Einstellung «Mini-Chart in der Merkliste».
    var sparklineEnabled = false
    /// «Erst-Hinzufügen»: Position der Zeile im Moment (Versatz 90 ms je Zeile); nil = keiner.
    /// Zeile blendet ein, Mini-Chart zeichnet sich, kurz ein Häkchen — nur dieser Zustandswechsel.
    var celebrationIndex: Int? = nil
    let onTap: () -> Void
    let onToggleFavorite: () -> Void
    var onActivity: () -> Void = {}
    /// Lange drücken: Sortiermodus an (nil = nicht möglich, z. B. während der Suche).
    var onLongPress: (() -> Void)? = nil

    @Environment(\.appAccent) private var accent
    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceHighContrast) private var highContrast
    @Environment(\.priceColorsInverted) private var inverted
    /// Grosse Schrift (Bedienungshilfen): Paar und Zeitzeile umbrechen statt abschneiden.
    @Environment(\.dynamicTypeSize) private var typeSize
    /// %-Basis (Zeitraum und Gültigkeit der Veränderung, für VoiceOver).
    @Environment(\.changeView) private var changeView
    @Environment(\.coinNamesEnabled) private var namesEnabled
    /// Neue Namen geladen (Abgleich im Hintergrund): Zeile neu zeichnen.
    @ObservedObject private var logoRevision = CoinLogoRevision.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var flash: Double = 0
    @State private var flashUp = true
    /// 24-Stunden-Verlauf für das Mini-Chart; nil = keines.
    @State private var sparkline: [Double]?
    /// Mindestbreite der Paar-Spalte, damit das Mini-Chart erscheint (wächst mit der Schrift).
    @ScaledMetric(relativeTo: .headline) private var minInfoWidth: CGFloat = 84
    /// «Erst-Hinzufügen»: Zeile schon eingeblendet, Fortschritt des Mini-Charts, Häkchen sichtbar.
    @State private var celebrationEntered = false
    @State private var sparkDraw: CGFloat = 1
    @State private var showCheck = false

    /// Vor dem Einblenden unsichtbar (nur während eines Moments).
    private var entryHidden: Bool { celebrationIndex != nil && !celebrationEntered }

    private var stale: Bool { WatchlistTime.isStale(watch, now: now, staleAfter: staleAfter) }

    /// Zeitzeile: normal «Binance · vor 2 Min.»; letzte Abfrage gescheitert
    /// «vor 41 Min. · Binance nicht erreichbar»; ohne Fehler, aber zu alt
    /// «Binance · veraltet · vor 4 Min.». `warning` = Warnfarbe mit Symbol.
    private var timeLine: (text: String, warning: Bool, spoken: String?) {
        guard watch.lastUpdate > 0 else { return (watch.marketName, false, nil) }
        let ago = WatchlistTime.ago(watch.lastUpdate, now: now)
        if let error = watch.lastError, !error.isEmpty, !ConnectionErrors.isNotTraded(error) {
            let text = L("watchlist_row_unreachable", ago, watch.marketName)
            return (text, true, text)
        }
        if watch.lastError == nil, outdatedAfter > 0, now - watch.lastUpdate > outdatedAfter {
            let text = L("watchlist_row_outdated_age", ago)
            return ("\(BidiText.isolate(watch.marketName)) · \(text)", true, text)
        }
        return ("\(BidiText.isolate(watch.marketName)) · \(ago)", false, nil)
    }

    var body: some View {
        HStack(spacing: Spacing.sm) {
            // Coin-Logo (bzw. Initialen), Favorit als kleiner Stern daran. Logos aus: keine Plakette,
            // Favoriten zeigt dann nur der Akzent-Rand — kein eigener Stern, der dem Paar Breite nimmt.
            CoinBadge(symbol: watch.baseAsset, size: 38, logo: CoinLogos.allowed(forMarket: watch.marketKey),
                      pair: watch.logoPairKey,
                      favorite: watch.favorite && !sorting)

            // Mini-Chart zwischen Paar und Kurs — nur, wenn das Paar genug Platz behält;
            // im Sortiermodus ausgeblendet.
            WatchlistSparklineSlot(minInfoWidth: minInfoWidth) {
                info
                    .frame(maxWidth: .infinity, alignment: .leading)
                if sparklineEnabled, !sorting, let sparkline {
                    WatchlistSparkline(values: sparkline, progress: sparkDraw)
                        .opacity(stale ? 0.5 : 1)
                }
            }

            priceColumn
                // Veraltete Kurse abblassen
                .opacity(stale ? 0.5 : 1)
        }
        .padding(.leading, Spacing.md)
        .padding(.trailing, Spacing.md)
        .padding(.vertical, Spacing.md)
        .background(cardBackground)
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(borderColor, lineWidth: highlighted ? 1.5 : 1)
        )
        // «Erst-Hinzufügen»: Einblenden mit leichtem Hochgleiten (weniger Bewegung: nur erscheinen)
        .opacity(entryHidden ? 0 : 1)
        .offset(y: entryHidden && !reduceMotion ? 12 : 0)
        .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        // Nur einfacher Tipp: Ein Doppeltippen liesse jeden Tipp ~0,3 s warten.
        // Favorit über Wischen nach rechts, Aktionsblatt oder VoiceOver-Aktion.
        .onTapGesture {
            guard !sorting else { return }
            onTap()
        }
        // Lange drücken schaltet nur den Sortiermodus ein (dann ziehen am Griff);
        // Tippen öffnet weiter das Blatt, Wischen bleibt unberührt.
        .onLongPressGesture(minimumDuration: 0.45) {
            guard !sorting, let onLongPress else { return }
            WatchlistHaptics.impact()
            onLongPress()
        }
        .animation(.spring(duration: 0.35), value: highlighted)
        .animation(.easeInOut(duration: 0.25), value: stale)
        .onChange(of: watch.lastPrice) { old, new in
            guard let old, let new, old != new else { return }
            flashUp = new > old
            withAnimation(.easeIn(duration: 0.12)) { flash = 1 }
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 150_000_000)
                withAnimation(.easeOut(duration: 1.0)) { flash = 0 }
            }
        }
        .task(id: celebrationIndex) {
            guard let index = celebrationIndex else {
                // Moment vorbei (oder keiner): alles im Normalzustand
                sparkDraw = 1
                showCheck = false
                return
            }
            await runCelebration(index)
        }
        .onChange(of: sparkline) { _, _ in drawSparklineIfReady() }
        // Nur für sichtbare Zeilen (List lädt träge), danach alle 15 Min. neu.
        // Fehler → kein Mini-Chart, keine Meldung.
        .task(id: sparklineEnabled ? watch.baseAsset : "") {
            guard sparklineEnabled else {
                sparkline = nil
                return
            }
            let base = watch.baseAsset
            if sparkline == nil { sparkline = await WatchlistSparklineStore.shared.cached(base: base) }
            var first = true
            while !Task.isCancelled {
                let closes = await WatchlistSparklineStore.shared.closes(base: base)
                if Task.isCancelled { break }
                // Später fehlgeschlagene Abrufe lassen den letzten Verlauf stehen
                if closes != nil || first { sparkline = closes }
                first = false
                try? await Task.sleep(nanoseconds: WatchlistSparklineStore.ttlNanos)
            }
        }
        // VoiceOver: die ganze Zeile als ein Satz; Tippen, Favorit und «Warum?» als Aktionen
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { if !sorting { onTap() } }
        .accessibilityAction(named: Text(L(watch.favorite ? "favorite_remove" : "favorite_add")), onToggleFavorite)
        // «Warum?» nur, wenn der ⚡ sichtbar ist (sonst öffnete die Aktion ein leeres Blatt)
        .accessibilityActions {
            if hasActivity && !sorting {
                Button(L("why_action"), action: onActivity)
            }
        }
    }

    // MARK: Erst-Hinzufügen

    /// 1. Zeile blendet ein (~250 ms), 2. Kurs erscheint mit sanftem Übergang, sobald er
    /// da ist, 3. Mini-Chart zeichnet sich von links nach rechts (~500 ms), 4. Häkchen
    /// in der Akzentfarbe, verblasst nach ~1,5 s. Weniger Bewegung: ohne Gleiten und Zeichnen.
    /// `@MainActor`: Die Zeile ist sonst nicht isoliert — ohne die Markierung liefe diese
    /// async-Funktion abseits des Main-Threads (Zustand und `withAnimation` gehören dorthin).
    @MainActor
    private func runCelebration(_ index: Int) async {
        var reset = Transaction()
        reset.disablesAnimations = true
        withTransaction(reset) {
            celebrationEntered = false
            sparkDraw = reduceMotion ? 1 : 0
            showCheck = false
        }
        if index > 0 {
            try? await Task.sleep(nanoseconds: UInt64(index) * 90_000_000)
            if Task.isCancelled { return }
        }
        if reduceMotion {
            celebrationEntered = true
        } else {
            withAnimation(.easeOut(duration: 0.25)) { celebrationEntered = true }
            try? await Task.sleep(nanoseconds: 250_000_000)
            if Task.isCancelled { return }
            drawSparklineIfReady()
        }
        withAnimation(reduceMotion ? nil : .spring(duration: 0.3)) { showCheck = true }
        try? await Task.sleep(nanoseconds: 1_500_000_000)
        if Task.isCancelled { return }
        withAnimation(.easeOut(duration: 0.4)) { showCheck = false }
    }

    /// Mini-Chart zeichnen, sobald die Zeile steht und der Verlauf da ist.
    private func drawSparklineIfReady() {
        guard celebrationIndex != nil, celebrationEntered, sparkDraw < 1, sparkline != nil else { return }
        if reduceMotion {
            sparkDraw = 1
        } else {
            withAnimation(.easeInOut(duration: 0.5)) { sparkDraw = 1 }
        }
    }

    // MARK: Teile

    /// Paar und Börse, Kurs, Veränderung, ≈ Umrechnung, 24-h-Verlauf, Notiz, Alarme,
    /// Fehler bzw. veralteter Stand — wie Android (`a11y_*`).
    /// Name des Coins bzw. der Firma, nur mit «Namen anzeigen» (nil: aus, DEX-Pool, unbekannt).
    private var coinName: String? {
        _ = logoRevision.value
        return namesEnabled ? CoinLogoStore.name(for: watch) : nil
    }

    private var accessibilityText: String {
        var chart: String?
        if sparklineEnabled, !sorting, let sparkline {
            chart = A11y.chart(period: L("widget_range_24h"), values: sparkline)
        }
        // Nicht erreichbar / veraltet: derselbe Text wie in der Zeile, sonst der bisherige Hinweis
        let staleText = timeLine.spoken ?? (stale && watch.lastUpdate > 0
            ? L("a11y_stale", WatchlistTime.ago(watch.lastUpdate, now: now)) : nil)
        return A11y.watchRow(
            watch,
            name: coinName,
            converted: converted,
            chart: chart,
            alarmCount: alarmCount,
            extra: [watch.notificationEnabled ? L("watchlist_notification") : nil],
            stale: staleText,
            changeView: changeView
        )
    }

    private var info: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: Spacing.xs) {
                Text(watch.displayPair)
                    .font(.headline)
                    .lineLimit(typeSize.isAccessibilitySize ? 2 : 1)
                    .truncationMode(.tail)
                // «Erst-Hinzufügen»: kurzes Häkchen (Banner und Ansage sagen dasselbe)
                if showCheck {
                    Image(systemName: "checkmark.circle.fill")
                        .scaledFont(size: 14, weight: .semibold, relativeTo: .headline)
                        .foregroundStyle(accent.primary)
                        .transition(reduceMotion ? AnyTransition.opacity
                                    : AnyTransition.scale(scale: 0.4).combined(with: .opacity))
                        .accessibilityHidden(true)
                }
                if hasActivity && !sorting {
                    WatchlistActivityBolt(action: onActivity)
                        .padding(.horizontal, -4)
                        .transition(.scale.combined(with: .opacity))
                }
                if let contract = watch.contractType.shortName {
                    Text(contract)
                        .scaledFont(size: 10, weight: .bold, design: .rounded, relativeTo: .caption2)
                        .foregroundStyle(accent.primary)
                        .padding(.horizontal, Spacing.xs)
                        .padding(.vertical, 2)
                        .background(accent.primary.opacity(0.12), in: Capsule())
                        .lineLimit(1)
                        .fixedSize()
                        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
                }
            }

            // «Namen anzeigen»: Name unter dem Paar («Bitcoin», «NVIDIA»); im Zeilensatz enthalten
            if let name = coinName {
                Text(name)
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurface.opacity(0.8))
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .accessibilityHidden(true)
            }

            HStack(spacing: Spacing.xs) {
                let line = timeLine
                if line.warning {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .scaledFont(size: 10, weight: .semibold, relativeTo: .caption2)
                        .foregroundStyle(AppColors.error)
                        .accessibilityHidden(true)
                }
                Text(line.text)
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(line.warning ? AppColors.error
                        : (stale && watch.lastUpdate > 0 ? AppColors.error.opacity(0.85) : AppColors.onSurfaceVariant))
                    .lineLimit(line.warning || typeSize.isAccessibilitySize ? 2 : 1)
                    .layoutPriority(-1)

                // Kleine Zeichen: Mitteilung an, Alarme scharf
                if watch.notificationEnabled {
                    Image(systemName: "bell.fill")
                        .scaledFont(size: 10, relativeTo: .caption2)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .accessibilityLabel(L("watchlist_notification"))
                }
                if alarmCount > 0 {
                    HStack(spacing: 2) {
                        Image(systemName: "alarm.fill").scaledFont(size: 10, relativeTo: .caption2)
                        Text(verbatim: LocaleNumbers.integer(alarmCount)).font(.caption2.weight(.semibold).monospacedDigit())
                    }
                    .foregroundStyle(accent.primary)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(L("watchlist_alarms_count", count: alarmCount))
                }
            }

            // «nicht erreichbar» steht schon in der Zeitzeile: dann keine zweite Fehlerzeile
            if let error = watch.lastError, !error.isEmpty,
               !(watch.lastUpdate > 0 && ConnectionErrors.isRetryable(error)) {
                Text(ConnectionErrors.display(error))
                    .font(.caption)
                    .foregroundStyle(ConnectionErrors.isNotTraded(error) ? AppColors.onSurfaceVariant : AppColors.error)
                    .lineLimit(1)
            }

            // Eigene Notiz (#233), dezent unter dem Paar
            if let note = watch.note {
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Image(systemName: "note.text")
                        .scaledFont(size: 9, weight: .semibold, relativeTo: .caption2)
                        .foregroundStyle(accent.primary.opacity(0.8))
                    Text(note)
                        .font(.caption.italic())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(2)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel("\(L("note_title")): \(note)")
            }
        }
    }

    @ViewBuilder
    private var priceColumn: some View {
        VStack(alignment: .trailing, spacing: 4) {
            if watch.lastPrice == nil && loading {
                SkeletonPulse {
                    VStack(alignment: .trailing, spacing: 4) {
                        SkeletonBlock(width: 80, height: 14)
                        SkeletonBlock(width: 48, height: 12)
                    }
                }
            } else {
                HStack(spacing: Spacing.xs) {
                    if loading {
                        ProgressView().controlSize(.mini)
                    }
                    Text(PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset))
                        .font(AppFont.amount(.body, weight: .semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                        .contentTransition(.numericText(value: watch.lastPrice ?? 0))
                        .padding(.horizontal, 4)
                        .background(
                            RoundedRectangle(cornerRadius: 6, style: .continuous)
                                .fill((flashUp ? priceColors.up(highContrast: highContrast, inverted: inverted)
                                       : priceColors.down(highContrast: highContrast, inverted: inverted))
                                    .opacity(0.28 * flash))
                        )
                }
                // Erster Kurs während «Erst-Hinzufügen»: sanft hereinwachsen statt nur einblenden
                .transition(celebrationIndex != nil && !reduceMotion
                            ? AnyTransition.opacity.combined(with: .scale(scale: 0.85, anchor: .trailing))
                            : AnyTransition.opacity)
                if watch.lastPrice != nil {
                    WatchlistDayChangePill(watch: watch)
                }
                if let converted {
                    Text(converted)
                        .font(AppFont.amount(.caption2))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                        .padding(.trailing, 2)
                }
            }
        }
        .fixedSize(horizontal: true, vertical: false)
        // Ziffern rollen nur bei einer echten Kursänderung (nicht beim ersten Zeichnen oder
        // Scrollen); mit «Bewegung reduzieren» ohne Animation
        .animation(reduceMotion ? nil : .snappy, value: watch.lastPrice)
    }

    private var cardBackground: some View {
        RoundedRectangle(cornerRadius: 18, style: .continuous)
            .fill(AppColors.container)
            .overlay(
                // Favoriten mit feinem Akzent-Schimmer am linken Rand
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(LinearGradient(
                        colors: [accent.primary.opacity(watch.favorite ? 0.12 : 0), .clear],
                        startPoint: .leading, endPoint: .center))
            )
            .shadow(color: AppColors.shadow.opacity(highlighted ? 0.18 : 0), radius: 12, y: 4)
    }

    private var borderColor: Color {
        if highlighted { return accent.primary }
        if watch.favorite { return accent.primary.opacity(0.45) }
        return AppColors.outlineVariant.opacity(0.6)
    }
}
