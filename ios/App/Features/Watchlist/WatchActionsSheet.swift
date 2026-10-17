import SwiftUI
import UIKit

/// Aktionen zu einem Paar als Blatt von unten — wie `WatchActionsSheet`: Kopf mit Stift, Kurs und
/// darunter «↗ +2.35% · 24h · Aktualisiert …», gleich darunter gross Alarm, «Was bewegt sich mit?» und Favorit, dann der
/// Chart (`WatchSheetChartView`) mit Zeitraum und Kerzen/Linie, darunter die Karte «Marktdaten»
/// (Spot | Futures, `WatchlistMarketDataSection`).
/// Alles Weitere — Gruppe, Notiz, «Zum Portfolio hinzufügen» (nur mit Portfolio), Aktualisieren,
/// Widget, Vorlesen, Mitteilung, Sperrbildschirm (nur iOS), Sortieren und zuletzt Löschen — im
/// aufklappbaren Abschnitt «Mehr» (zu; für die Sitzung gemerkt). Das Blatt öffnet halb hoch
/// (Kopf, Aktionen, Chart-Anfang; `WatchActionsDetents`) und lässt sich auf volle Höhe ziehen (L-16).
///
/// `preview`: Paar steht (noch) nicht in der Merkliste (Coin aus «Heute auffällig», nur im Speicher,
/// `watchId` = `WatchPreview.watchId`): Kopf, Kurs, Chart und «Was bewegt sich mit?» wie sonst, dazu gross «Zur
/// Merkliste hinzufügen». Alles, was einen gespeicherten Eintrag braucht — Paar bearbeiten, Alarm,
/// Favorit, Notiz und der ganze Abschnitt «Mehr» —, fehlt. Nach dem Hinzufügen wird daraus dasselbe
/// Blatt für den neuen Eintrag. Wie Android `WatchActionsSheet(preview = true)`.
@MainActor
struct WatchActionsSheet: View {
    let watchId: Int64
    /// Paar hat gerade ⚡-Signale (kleines ⚡ an «Was bewegt sich mit?»).
    var hasActivity = false
    /// Alarme öffnen (nach dem Schliessen des Blatts).
    let onOpenAlarms: (Int64) -> Void
    /// Löschen erfragen (nach dem Schliessen des Blatts).
    let onDelete: (Watch) -> Void
    /// «Was bewegt sich mit?» öffnen (nach dem Schliessen des Blatts).
    let onWhy: (Int64) -> Void
    /// Erfassen-Blatt des Portfolios öffnen (nach dem Schliessen des Blatts).
    var onAddToPortfolio: (Int64) -> Void = { _ in }
    /// Vorschau eines Paars, das nicht in der Merkliste steht (siehe oben); nil = normales Blatt.
    var preview: Watch? = nil
    /// Vorschau: «Was bewegt sich mit?» mit dem Vorschau-Paar samt Kurs (nach dem Schliessen des Blatts).
    var onWhyPreview: (Watch) -> Void = { _ in }
    /// Paar deaktiviert bzw. wieder aktiviert (Stand vor dem Wechsel) — für ein kurzes Banner.
    var onDisabledChange: (Watch) -> Void = { _ in }

    @EnvironmentObject var data: AppData
    @Environment(\.appAccent) var accent
    @Environment(\.onAccent) var onAccent
    @Environment(\.dismiss) var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Bitcoin-Paar: Faktor Quote → Umrechnungswährung für «1 CHF = … Sats»; nil = keine Zeile.
    @State var satsRate: Double? = nil
    /// Änderung aus den Kerzen einer anderen Börse (z. B. «Binance» bei einem Kraken-Paar); nil = nicht.
    @State var candleSource: String? = nil
    /// Was von einer anderen Börse kommt (Änderung, Mini-Chart) — L-14: steht nicht mehr in der Zeile,
    /// sondern hier unter dem Kurs («Änderung & Chart: Binance»); nil = alles von der eigenen Börse.
    @State var rowSource: DayChange.RowSource? = nil
    @State var editGroup = false
    @State var editNote = false
    /// «Paar bearbeiten» (Stift neben dem Paar): Börse, Paar, Kontrakt desselben Eintrags.
    @State var editPair = false
    /// «Als Widget hinzufügen»: iOS lässt Apps keine Widgets anlegen → Anleitung (Runde 13b).
    @State var showWidgetHelp = false
    /// Live-Aktivität dieses Paars läuft (Sperrbildschirm).
    @State var liveActivityOn = false
    /// iOS-Einstellung «Live-Aktivitäten» ist aus — Hinweis zeigen.
    @State var liveActivityDisabled = false
    /// «Mehr» aufgeklappt. K1: gilt nur für dieses Blatt — jedes Paar öffnet zugeklappt.
    @State private var moreExpanded = false
    /// Vorschau hinzugefügt: Id des neuen Eintrags (das Blatt zeigt dann ihn).
    @State private var addedId: Int64? = nil
    /// Vorschau mit Kurs (Ticker-Abfrage ohne Speichern); nil = noch keiner.
    @State private var previewQuoted: Watch? = nil
    /// Vorschau wird gerade hinzugefügt (Knopf gesperrt, «Zur Merkliste hinzugefügt.»).
    @State private var adding = false
    /// «Erneut versuchen» der Vorschau.
    @State var previewReload = 0
    /// Kurzer Hinweis unten im Blatt (z. B. «Sprachausgabe ist an»).
    @State private var toast: String? = nil

    /// Gezeigter Eintrag: der hinzugefügte (Vorschau) bzw. der übergebene.
    var currentId: Int64 { addedId ?? watchId }

    var body: some View {
        // Eben hinzugefügt: die Vorschau bleibt stehen, bis der neue Eintrag einen Kurs (oder
        // Fehler) hat — kein «—» dazwischen; höchstens kurz (`addHoldNanos`)
        if let watch = data.watch(currentId),
           !(adding && preview != nil && watch.lastUpdate <= 0 && watch.lastError == nil) {
            content(watch)
        } else if let preview {
            previewContent(previewQuoted ?? preview)
        } else {
            // Paar wurde inzwischen gelöscht
            Color.clear.onAppear { dismiss() }
        }
    }

    /// Vorschau: Kurs holen (neu mit «Erneut versuchen»); nach dem Hinzufügen höchstens kurz warten.
    private func previewContent(_ watch: Watch) -> some View {
        content(watch, isPreview: true)
            // Rollende 24 h aus dem Ticker — so heisst es auch neben der Änderung
            .environment(\.changeView, ChangeView(basis: .ROLLING_24H, current: true,
                                                   showPeriod: data.settings.showChangePeriod))
            .task(id: "\(watch.baseAsset)|\(previewReload)") {
                guard let preview else { return }
                previewQuoted = await WatchPreview.fetchQuote(previewQuoted ?? preview)
            }
            .task(id: adding) {
                guard adding else { return }
                try? await Task.sleep(nanoseconds: Self.addHoldNanos)
                adding = false
            }
    }

    /// So lange bleibt die Vorschau nach «Hinzufügen» höchstens stehen, bis der erste Kurs da ist.
    private static let addHoldNanos: UInt64 = 6_000_000_000

    /// Vorschau › «Zur Merkliste hinzufügen»: wie der Hinzufügen-Tab — Dublettenprüfung
    /// (`addWatch`), Kurs-Mitteilung an, Erst-Moment beim allerersten Paar, Erlaubnis für
    /// Mitteilungen jetzt fragen; danach gleich den Kurs holen. Stand das Paar schon in der
    /// Merkliste, zeigt das Blatt einfach jenes.
    func addPreview() {
        guard let preview, !adding, let market = MarketsConfig.market(preview.marketKey) else { return }
        let firstEver = data.isFirstPairAdd
        if let id = data.addWatch(market: market, pair: preview.pairInfo) {
            adding = true
            addedId = id
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            if firstEver { _ = data.celebrateFirstAdd(id, announceInWatchlist: true) }
            // Neues Paar mit Kurs-Mitteilungen: erst kurz erklären, dann die System-Abfrage
            NotificationPrePrompt.request(.pairsAdded)
            Task { await data.refreshOne(id) }
        } else if let existing = data.watches.first(where: { $0.samePair(as: preview) }) {
            addedId = existing.id
        }
    }

    private func content(_ watch: Watch, isPreview: Bool = false) -> some View {
        let alarmCount = data.activeAlarmCounts[watch.id] ?? 0
        let refreshingThis = data.refreshingWatchIds.contains(watch.id)
        let scroll = ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header(watch, isPreview: isPreview)
                priceBlock(watch, isPreview: isPreview)

                if isPreview {
                    // Vorschau: gross «Zur Merkliste hinzufügen», daneben nur «Was bewegt sich mit?» (Alarm und
                    // Favorit brauchen einen gespeicherten Eintrag)
                    previewActions(watch, adding: adding)
                } else {
                    // Die drei häufigsten Aktionen gleich unter dem Kurs und gleich gross: Alarm, «Was bewegt sich mit?», Favorit
                    primaryActions(watch, alarmCount: alarmCount)
                }

                // Kurs-Chart (24h · 7T · 30T · 1J, Kerzen/Linie); ohne Kerzenquelle (DEX) ganz ausgeblendet,
                // bei deaktivierten Paaren ebenso (keine Abfrage)
                if !(watch.disabled && !isPreview) {
                    WatchSheetChartView(watch: watch)
                }

                // Karte «Marktdaten» (Spot | Futures) für Krypto-Paare; nicht bei deaktivierten Paaren
                // (keine Abfrage) und DEX-Pools (fremde Token dürfen gleich heissen)
                if !(watch.disabled && !isPreview), CoinLogos.allowed(forMarket: watch.marketKey) {
                    WatchlistMarketDataSection(watch: watch)
                }

                // Alles Weitere unter «Mehr» (zu, für die Sitzung gemerkt); Vorschau: kein «Mehr»
                // (alles darin braucht einen gespeicherten Eintrag)
                if !isPreview {
                    moreToggle
                    if moreExpanded {
                        moreContent(watch, refreshing: refreshingThis)
                            .transition(.opacity.combined(with: .move(edge: .top)))
                    }
                }
            }
            .padding(.horizontal, Spacing.lg)
            .padding(.top, 24)
            .padding(.bottom, 16)
            // Kennzahlen kommen später, «Mehr» klappt auf: weich einfügen (bei reduzierter Bewegung sofort)
            .animation(reduceMotion ? nil : .snappy, value: moreExpanded)
        }
        .scrollIndicators(.hidden)
        .background(AppColors.background.ignoresSafeArea())
        .toast($toast)
        // «1 CHF = … Sats»: Faktor zuerst aus dem Zwischenspeicher, sonst frisch (neu bei anderer
        // Quote oder Umrechnungswährung)
        .task(id: "\(watch.quoteAsset)|\(data.settings.portfolioCurrency)|\(Sats.isBitcoin(watch.baseAsset))") {
            guard Sats.isBitcoin(watch.baseAsset), !watch.isNotTraded else { satsRate = nil; return }
            let target = data.settings.portfolioCurrency
            // Andere Währung gewählt: nie kurz den alten Faktor mit der neuen Währung zeigen
            satsRate = nil
            if let cached = CurrencyConverter.cachedRate(quote: watch.quoteAsset, target: target) {
                satsRate = cached
            } else {
                satsRate = await CurrencyConverter.rate(quote: watch.quoteAsset, target: target)
            }
        }
        // Woher die Änderung kommt (neu nach jeder Aktualisierung des Paars)
        .task(id: "\(watch.id)|\(watch.lastUpdate)|\(watch.change24h ?? .nan)") {
            candleSource = await PriceRefresher.foreignCandleSource(watch)
            // Vorschau: nichts aus der Zeile zu erklären
            if isPreview {
                rowSource = nil
            } else {
                rowSource = await WatchlistRow.rowSource(for: watch)
            }
        }
        return subSheets(scroll, watch: watch, alarmCount: alarmCount)
    }

    /// Kopfzeile «Mehr» mit Pfeil; VoiceOver: Knopf «Mehr» mit Zustand auf-/zugeklappt.
    private var moreToggle: some View {
        Button {
            WatchlistHaptics.selection()
            moreExpanded.toggle()
        } label: {
            HStack(spacing: 8) {
                Text(L("sheet_more"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                Spacer(minLength: 8)
                // Auf-/Zuklapp-Pfeil überall in der Themenfarbe (wie `ExpandToggleLabel`)
                Image(systemName: "chevron.down")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(accent.primary)
                    .rotationEffect(.degrees(moreExpanded ? 180 : 0))
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L("sheet_more"))
        .accessibilityValue(L(moreExpanded ? "a11y_expanded" : "a11y_collapsed"))
    }

    /// Inhalt von «Mehr»: Gruppe, Notiz, Portfolio, Aktualisieren, Widget; Vorlesen und Mitteilung;
    /// Sperrbildschirm; Sortieren; zuletzt Löschen in der Fehlerfarbe.
    private func moreContent(_ watch: Watch, refreshing refreshingThis: Bool) -> some View {
        VStack(alignment: .leading, spacing: 16) {
                groupCard(watch, refreshing: refreshingThis)

                // Vorlesen und Mitteilung
                VStack(spacing: 0) {
                    SwitchRow(title: L("watchlist_tts"), isOn: Binding(
                        get: { data.watch(currentId)?.ttsEnabled ?? false },
                        set: { enabled in
                            // Sprachausgabe war aus bzw. «Nur Alarme»: jetzt an — kurz sagen, wo man es ändert
                            if let w = data.watch(currentId), data.setTtsEnabled(w, enabled) {
                                toast = L("watchlist_tts_speech_on")
                            }
                        }
                    ), icon: "speaker.wave.2")
                    RowDivider()
                    SwitchRow(title: L("watchlist_notification"), isOn: Binding(
                        get: { data.watch(currentId)?.notificationEnabled ?? false },
                        set: { enabled in
                            if enabled { Task { _ = await Notifier.requestPermission() } }
                            if let w = data.watch(currentId) { data.setNotificationEnabled(w, enabled) }
                        }
                    ), icon: data.watch(currentId)?.notificationEnabled == true ? "bell" : "bell.slash")  // wie in der Zeile; aus = durchgestrichen
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 4)
                .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))

                liveActivityCard(watch)

                moveRow(watch)

                // Paar deaktivieren bzw. wieder aktivieren (mit kurzem Hinweis)
                disableRow(watch)

                Button(role: .destructive) {
                    onDelete(watch)
                    dismiss()
                } label: {
                    HStack(spacing: Spacing.md) {
                        Image(systemName: "trash")
                            .scaledFont(size: 17, weight: .semibold, relativeTo: .body)
                        Text(L("action_delete")).font(.body.weight(.medium))
                        Spacer()
                    }
                    .foregroundStyle(AppColors.error)
                    .padding(.horizontal, 16)
                    .padding(.vertical, Spacing.lg)
                    .background(AppColors.error.opacity(0.10), in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
        }
    }

    /// Unterblätter (Paar bearbeiten, Notiz, Widget-Hilfe, Gruppe).
    private func subSheets(_ content: some View, watch: Watch, alarmCount: Int) -> some View {
        content
        .sheet(isPresented: $editPair) {
            WatchEditSheet(watch: watch, alarmCount: alarmCount)
                .environmentObject(data)
                .environment(\.appAccent, accent)
                // Volle Höhe wie das Formular «Genau auswählen»
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .presentationCornerRadius(28)
                .presentationBackground(AppColors.background)
        }
        .sheet(isPresented: $editNote) {
            WatchNoteSheet(initial: watch.note) { note in
                if let w = data.watch(currentId) { data.setNote(w, note) }
            }
            .environment(\.appAccent, accent)
            // Volle Höhe: das Feld fokussiert beim Öffnen, die Tastatur schiebt das Blatt so nicht hoch
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(28)
            .presentationBackground(AppColors.background)
        }
        .sheet(isPresented: $showWidgetHelp) {
            AddWidgetHelpSheet(portfolioEnabled: data.settings.portfolioEnabled)
                .environment(\.appAccent, accent)
                // Eine feste Höhe (wie in den Einstellungen): Inhalt höher als «halb»
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $editGroup) {
            WatchGroupSheet(current: watch.groupName, groups: data.watchGroups) { group in
                if let w = data.watch(currentId) { data.setGroup(w, group) }
            }
            .environment(\.appAccent, accent)
            // Volle Höhe: «Neue Gruppe» blendet ein Feld samt Tastatur ein, das Blatt springt nicht
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(28)
            .presentationBackground(AppColors.background)
        }
    }

}

/// Stufen des Aktionsblatts (L-16): öffnet halb hoch (`.medium`: Kurs, Aktionen, Anfang des Charts),
/// lässt sich auf volle Höhe ziehen — wie Android (`skipPartiallyExpanded = false`). Die Stufe gilt
/// je Öffnen (eigener Zustand im Blatt), Nachladen von Chart und Kennzahlen ändert sie nicht.
struct WatchActionsDetents: ViewModifier {
    @State private var detent: PresentationDetent = .medium

    func body(content: Content) -> some View {
        content.presentationDetents([.medium, .large], selection: $detent)
    }
}
