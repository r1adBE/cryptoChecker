import SwiftUI
import UIKit

// MARK: Zeile

/// Kompakte Zeile: Coin-Logo (Stern bei Favoriten, nur mit Coin-Logos), Paar, Börse, Kurs und Prozent-Änderung.
/// Tippen öffnet die Aktionen sofort (kein Doppeltippen),
/// lange drücken und ziehen sortiert (macht die Liste).
struct WatchlistRow: View {
    let watch: Watch
    let alarmCount: Int
    /// Letzte Auslösung eines Alarms dieses Paars (Epoch-ms, 0 = nie): VoiceOver «Alarm ausgelöst».
    var alarmFiredAt: Int64 = 0
    /// Art des Paars: kleines Symbol vor der Glocke (Token, TradFi, α), im Zeilensatz gesprochen.
    var kind: AssetKind = .TOKEN
    let now: Int64
    let staleAfter: Int64
    /// Ab diesem Alter «veraltet» in der Zeitzeile (3 × Intervall, mind. 15 Min.); 0 = nie.
    var outdatedAfter: Int64 = 0
    /// Gespeichertes Ende einer Pause der Börse (`AppData.exchangePauses`); nil = keine. Läuft sie noch
    /// (`RowPause.shownUntil`), steht «Binance · pausiert bis 19:45» statt der Zeitzeile.
    var exchangePausedUntil: Int64? = nil
    /// Wird gerade (einzeln oder komplett) aktualisiert.
    let loading: Bool
    var highlighted = false
    var sorting = false
    /// Form je nach Platz in der Liste (`ListSegment.shape`); die Liste wirkt wie eine Einheit.
    var shape: UnevenRoundedRectangle = ListSegment.shape(0, 1)
    /// Mehrfachauswahl: Häkchen-Kreis links, Tipp hakt an/ab (macht die Liste über `onTap`).
    var selecting = false
    var selected = false
    /// ⚡ Ungewöhnliche Aktivität — Tipp öffnet «Was bewegt sich mit?».
    var hasActivity = false
    /// Kurs in der Umrechnungswährung, z. B. «≈ 61’234 CHF»; nil = nichts zeigen.
    var converted: String? = nil
    /// VoiceOver-Fassung von `converted` (ganzer Vermerk statt der kurzen Zeile); nil = `converted`.
    var convertedA11y: String? = nil
    /// Mini-Chart (24 h) laden und zeigen — Einstellung «Mini-Chart in der Merkliste».
    var sparklineEnabled = false
    /// «Erst-Hinzufügen»: Position der Zeile im Moment (Versatz 90 ms je Zeile); nil = keiner.
    /// Zeile blendet ein, Mini-Chart zeichnet sich, kurz ein Häkchen — nur dieser Zustandswechsel.
    var celebrationIndex: Int? = nil
    let onTap: () -> Void
    let onToggleFavorite: () -> Void
    var onActivity: () -> Void = {}
    /// Lange drücken: Mehrfachauswahl mit dieser Zeile starten bzw. in der Auswahl an-/abhaken
    /// (nil = nichts, z. B. im Sortiermodus).
    var onLongPress: (() -> Void)? = nil

    @Environment(\.coinLogosEnabled) private var coinLogosEnabled
    @Environment(\.appAccent) var accent
    @Environment(\.rowHighlight) var highlight
    @Environment(\.priceNotificationsOn) var priceNotificationsOn
    @Environment(\.priceColorScheme) var priceColors
    @Environment(\.priceHighContrast) var highContrast
    @Environment(\.priceColorsInverted) var inverted
    /// Grosse Schrift: Paar und Zeitzeile umbrechen statt abschneiden, ab accessibility1 Kurs unter dem
    /// Paar (`DenseRowText`, wie Android).
    @Environment(\.dynamicTypeSize) private var typeSize
    var typeScale: Double { DenseRowText.scale(typeSize) }
    private var stacked: Bool { DenseRowText.stacksTrailing(typeScale) }
    /// %-Basis (Zeitraum und Gültigkeit der Veränderung, für VoiceOver).
    @Environment(\.changeView) var changeView
    @Environment(\.coinNamesEnabled) var namesEnabled
    // «Namen anzeigen»: drei feste Zeilen links und rechts — Paar/Kurs, Name/Änderung,
    // Börse/≈ Umrechnung stehen je auf einer Linie (wachsen mit der Schriftgrösse)
    @ScaledMetric(relativeTo: .headline) var line1: CGFloat = 22
    @ScaledMetric(relativeTo: .footnote) var line2: CGFloat = 20
    @ScaledMetric(relativeTo: .subheadline) var line3: CGFloat = 20
    /// Mitte der drei Zeilen (Abstand 2): Logo und Mini-Chart stehen mittig dazu, weitere
    /// Zeilen (Fehler, Notiz) wachsen nach unten.
    private var threeLineCenter: CGFloat { (line1 + line2 + line3 + 4) / 2 }
    /// Neue Namen geladen (Abgleich im Hintergrund): Zeile neu zeichnen.
    @ObservedObject private var logoRevision = CoinLogoRevision.shared
    @Environment(\.accessibilityReduceMotion) var reduceMotion
    @State var flash: Double = 0
    @State var flashUp = true
    /// 24-Stunden-Verlauf für das Mini-Chart; nil = keines.
    @State var sparkline: [Double]? = nil
    /// Börse des Mini-Charts, falls nicht die eigene: im VoiceOver-Satz «Chart: Binance» (sichtbar im Aktionsblatt).
    @State var sparklineSource: String? = nil
    /// Börse der Kerzen hinter der %-Änderung, falls nicht die eigene (`PriceRefresher.foreignCandleSource`);
    /// nil = eigene Börse bzw. Ticker. Nur aus dem Speicher.
    @State private var changeSource: String?
    /// Mindestbreite der Paar-Spalte, damit das Mini-Chart erscheint (wächst mit der Schrift).
    @ScaledMetric(relativeTo: .headline) private var minInfoWidth: CGFloat = 84
    /// «Erst-Hinzufügen»: Zeile schon eingeblendet, Fortschritt des Mini-Charts, Häkchen sichtbar.
    @State private var celebrationEntered = false
    @State private var sparkDraw: CGFloat = 1
    /// Breite der ganzen Zeile (für die Breite des Mini-Charts, `DenseRowText.sparklineWidth`);
    /// bis zur ersten Messung die normale Breite.
    @State var rowWidth: CGFloat = DenseRowText.narrowRow
    @State var showCheck = false
    /// Hinweis zur Quelle der %-Änderung («*», `changeSourceHint`) als Popover am Zeichen.
    @State var showSourceHint = false

    /// Vor dem Einblenden unsichtbar (nur während eines Moments).
    private var entryHidden: Bool { celebrationIndex != nil && !celebrationEntered }

    var stale: Bool { WatchlistTime.isStale(watch, now: now, staleAfter: staleAfter) }

    /// Gezeigte %-Änderung aus fremden Kerzen: Anbieter für den Marker «*» und den VoiceOver-Satz
    /// «Veränderung aus Binance-Kerzen»; nil = kein Marker (gleiche Regel wie Android, `DayChange.rowChangeMarker`).
    var changeMarker: String? {
        DayChange.rowChangeMarker(foreignSource: changeSource, shownChange: changeView.shown(watch),
                                  sinceLast: changeView.basis.isSinceLast)
    }

    /// Kurzer Hinweis beim Tippen auf das «*» (bzw. VoiceOver-Aktion): woher die Änderung kommt; nil = kein Marker.
    /// Im Sortier- und Auswahlmodus kein Tippziel — dort gehört jeder Tipp der Zeile (wie Android).
    var changeSourceHint: String? {
        guard !sorting, !selecting else { return nil }
        return changeMarker.map { L("change_candle_source_hint", $0) }
    }

    /// Deaktiviert: ganz grau mit dem letzten Kurs, kein Mini-Chart, kein ⚡, nie «veraltet».
    var off: Bool { watch.disabled }

    /// Deckkraft von Logo und kleinen Symbolen eines deaktivierten Paars. Texte (Paar, Kurs, Anzahl
    /// Alarme) werden nicht mehr abgeblasst (45 % lag unter 4.5:1), sondern stehen in `onSurfaceVariant`;
    /// «Binance · deaktiviert» sagt den Zustand in Worten (L-13, wie Android `DISABLED_ICON_ALPHA`).
    /// Veraltet: Kurs ebenfalls in `onSurfaceVariant` samt «Börse · vor 13 Min.» in Fehlerfarbe.
    static let disabledIconOpacity = 0.45

    var iconOpacity: Double { off ? WatchlistRow.disabledIconOpacity : 1 }

    /// Zeile: Auswahl, Logo, Paar (mit Mini-Chart) und daneben der Kurs — bei grösster Schrift steht der
    /// Kurs darunter (`body`, `DenseRowText`).
    private var mainLine: some View {
        // Vorher gelesen: Die Ausrichtungs-Closures laufen ohne Akteur (Sendable), die Eigenschaften gehören dem MainActor
        let center: CGFloat? = namesEnabled ? threeLineCenter : nil
        return HStack(spacing: Spacing.sm) {
            if selecting {
                // Gefüllt in der Themenfarbe = ausgewählt, sonst nur Rand (VoiceOver: «ausgewählt»)
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .scaledFont(size: 20, weight: .regular, relativeTo: .headline)
                    .foregroundStyle(selected ? accent.primary : AppColors.outline)
                    .accessibilityHidden(true)
            }
            // Coin-Logo (bzw. Initialen), Favorit als kleiner Stern daran. Logos aus: keine Plakette,
            // Favorit dann als eigener kleiner Stern — wie Android.
            if !coinLogosEnabled && watch.favorite && !sorting {
                Image(systemName: "star.fill")
                    .scaledFont(size: 13, weight: .semibold, relativeTo: .headline)
                    .foregroundStyle(highlight.favorites ? accent.primary : AppColors.onSurface)  // wie der Stern am Logo
                    .accessibilityHidden(true)
            }
            CoinBadge(symbol: watch.baseAsset, size: ListSegment.logo, logo: CoinLogos.allowed(forExchange: watch.exchangeKey),
                      pair: watch.logoPairKey,
                      favorite: watch.favorite && !sorting)
                .opacity(iconOpacity)

            // Mini-Chart zwischen Paar und Kurs — nur, wenn das Paar genug Platz behält;
            // im Sortiermodus und bei deaktivierten Paaren ausgeblendet.
            WatchlistSparklineSlot(minInfoWidth: minInfoWidth,
                                   sparkWidth: DenseRowText.sparklineWidth(rowWidth: rowWidth)) {
                // Deaktiviert: nicht mehr abgeblasst, Farben siehe `info` (L-13)
                info
                    .frame(maxWidth: .infinity, alignment: .leading)
                if sparklineEnabled, !sorting, !off, let sparkline {
                    // Veraltet: grau wie der Kurs, ohne Fläche (statt halber Deckkraft in Kursfarbe) — wie Android
                    WatchlistSparkline(values: sparkline, progress: sparkDraw, stale: stale)
                }
            }
            .alignmentGuide(VerticalAlignment.center) { d in
                center ?? d[VerticalAlignment.center]
            }

            if !stacked {
                // Deaktiviert: Kurs in onSurfaceVariant statt abgeblasst (`priceText`)
                priceColumn
                    .alignmentGuide(VerticalAlignment.center) { d in
                        center ?? d[VerticalAlignment.center]
                    }
            }
        }
    }

    var body: some View {
        // Grösste Schrift (ab accessibility1): Kurs unter dem Paar statt daneben (`DenseRowText`)
        VStack(alignment: .trailing, spacing: 4) {
            mainLine
            if stacked {
                priceColumn
            }
        }
        .padding(.leading, Spacing.md)
        .padding(.trailing, Spacing.md)
        .padding(.vertical, Spacing.md)
        // Schmale Zeile (< 360 pt): Mini-Chart 48 statt 56 pt — wie Android
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { rowWidth = $0 }
        .background(cardBackground)
        // Liste als eine Einheit: kein eigener Rand je Zeile (auch nicht für Favoriten), sondern
        // Trennlinie und Aussenrand der Liste; gewählt bzw. hervorgehoben = getönt (`cardBackground`)
        .segmentLines(shape)
        // «Erst-Hinzufügen»: Einblenden mit leichtem Hochgleiten (weniger Bewegung: nur erscheinen)
        .opacity(entryHidden ? 0 : 1)
        .offset(y: entryHidden && !reduceMotion ? 12 : 0)
        .contentShape(shape)
        // Nur einfacher Tipp: Ein Doppeltippen liesse jeden Tipp ~0,3 s warten.
        // Favorit über Wischen nach rechts, Aktionsblatt oder VoiceOver-Aktion.
        .onTapGesture {
            guard !sorting else { return }
            onTap()
        }
        // Lange drücken startet die Mehrfachauswahl (bzw. hakt in ihr an/ab), mit Haptik;
        // Tippen öffnet weiter das Blatt, Wischen bleibt unberührt.
        .onLongPressGesture(minimumDuration: 0.45) {
            guard !sorting, let onLongPress else { return }
            Haptics.play(.longPress)
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
        // Anbieter der Kerzen hinter der Änderung: wird bei jeder Aktualisierung vor dem Speichern gemerkt —
        // neu lesen, sobald eine neue Änderung kommt (nicht bei jedem Live-Kurs; wie Android `remember(watch)`)
        .task(id: "\(watch.id)|\(watch.change24h.map { String($0) } ?? "-")") {
            let source = await PriceRefresher.foreignCandleSource(watch)
            if Task.isCancelled { return }
            changeSource = source
        }
        // Nur für sichtbare Zeilen (List lädt träge), danach alle 15 Min. neu.
        // Fehler → kein Mini-Chart, keine Meldung.
        // Deaktiviert: nichts laden (keine Abfrage)
        // Erst mit Kurs prüfbar (gleicher Token, `SheetChart.sparklineFor`)
        .task(id: sparklineEnabled && !off ? "\(watch.baseAsset)|\(watch.quoteAsset)|\(watch.lastPrice != nil)" : "") {
            guard sparklineEnabled, !watch.disabled else {
                sparkline = nil
                return
            }
            let base = watch.baseAsset
            let quote = WatchlistConversion.quote(watch)
            // Eigene Börse zuerst; TradFi und DEX nie mit der USDT-Reihe eines Krypto-Tokens
            let pair = CandlePair.of(watch)
            let fallback = OwnCandlePolicy.allowsFallback(pair)
            if sparkline == nil {
                if let own = await ownSparkline(pair, cachedOnly: true) {
                    sparkline = own
                } else if fallback, await Self.sparklineFallbackAllowed(watch) {
                    let cached = await WatchlistSparklineStore.shared.cached(base: base)
                    sparkline = SheetChart.sparklineFor(cached, price: watch.lastPrice,
                                                        quoteUsd: CurrencyConverter.cachedRate(quote: quote, target: "USD"))
                }
            }
            var first = true
            while !Task.isCancelled {
                let own = await ownSparkline(pair, cachedOnly: false)
                if Task.isCancelled { break }
                var closes = own
                var foreign = false
                if own == nil && fallback {
                    let loaded = await WatchlistSparklineStore.shared.closes(base: base)
                    if Task.isCancelled { break }
                    // Reihe eines klar anders benannten Tokens (K-8): lieber kein Chart
                    let allowed: Bool = await Self.sparklineFallbackAllowed(watch)
                    foreign = !allowed
                    // USDT-Reihe nur bei gleichem Token wie das Paar (letzter Schluss gegen Kurs × USD je Quote)
                    var quoteUsd = CurrencyConverter.cachedRate(quote: quote, target: "USD")
                    if loaded != nil, quoteUsd == nil { quoteUsd = await CurrencyConverter.rate(quote: quote, target: "USD") }
                    if Task.isCancelled { break }
                    closes = allowed ? SheetChart.sparklineFor(loaded, price: watch.lastPrice, quoteUsd: quoteUsd) : nil
                }
                // Später fehlgeschlagene Abrufe lassen den letzten Verlauf stehen
                if closes != nil || first || foreign { sparkline = closes }
                var source: String? = nil
                if sparkline != nil { source = await Self.foreignSparklineSource(for: watch) }
                if Task.isCancelled { break }
                sparklineSource = source
                first = false
                try? await Task.sleep(nanoseconds: WatchlistSparklineStore.ttlNanos)
            }
        }
        // VoiceOver: die ganze Zeile als ein Satz; Tippen, Favorit und «Was bewegt sich mit?» als Aktionen
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityAddTraits(selecting && selected ? [.isButton, .isSelected] : .isButton)
        .accessibilityIdentifier(UiTestTags.watchRow)
        .accessibilityAction { if !sorting { onTap() } }
        .accessibilityAction(named: Text(L(watch.favorite ? "favorite_remove" : "favorite_add")), onToggleFavorite)
        // «Was bewegt sich mit?» nur, wenn der ⚡ sichtbar ist (sonst öffnete die Aktion ein leeres Blatt)
        .accessibilityActions {
            if hasActivity && !off && !sorting && !selecting {
                Button(L("why_action"), action: onActivity)
            }
            if changeSourceHint != nil {
                Button(L("change_candle_source_action")) { showSourceHint = true }
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

    /// Name des Coins bzw. der Firma, nur mit «Namen anzeigen» (nil: aus, DEX-Pool, unbekannt).
    var coinName: String? {
        _ = logoRevision.value
        return namesEnabled ? CoinLogoStore.name(for: watch) : nil
    }

    /// Fläche der Zeile; gewählt bzw. hervorgehoben leicht in der Themenfarbe getönt (statt eines
    /// Rands oder Schattens, die die Einheit der Liste aufbrächen) — wie Android.
    private var cardBackground: some View {
        shape
            .fill(AppColors.container)
            .overlay(shape.fill(highlighted ? accent.tint(0.14) : .clear))
    }
}
