import SwiftUI
import UIKit

/// Seite eines Paars (ersetzt das frühere Aktionsblatt), per NavigationStack geöffnet: Tipp auf eine Zeile der Merkliste
/// bzw. ein Suchergebnis, ein Coin aus «Heute auffällig» / «Gewinner und Verlierer», eine Mitteilung oder eine
/// Widget-Zeile. Von oben nach unten wie Android `PairScreen`: Kopf mit Kurs (`priceBlock`), Chart über die ganze
/// Breite (`WatchSheetChartView`), «Alarm setzen», kleine Knöpfe (`secondaryActions`), Alarme des Paars mit
/// «Zuletzt geprüft» (`AlarmsScreen(embedded: true)`), Marktdaten (`WatchlistMarketDataSection`) und die Notiz.
/// Seltenes steht im Menü ⋯ (`overflowMenu`).
///
/// `preview`: Paar steht (noch) nicht in der Merkliste (Coin aus dem Markt-Tab, nur im Speicher, `watchId` =
/// `WatchPreview.watchId`): Kopf, Kurs, Chart, «Was bewegt sich mit?» und Marktdaten, dazu gross «Zur Merkliste
/// hinzufügen». Danach zeigt die Seite den neuen Eintrag. Wie Android `PairScreen(preview = …)`.
@MainActor
struct PairScreen: View {
    let watchId: Int64
    /// Vorschau eines Paars, das nicht in der Merkliste steht (siehe oben); nil = gespeichertes Paar.
    var preview: Watch? = nil
    /// Löschen wie nach links wischen (der Aufrufer zeigt «Rückgängig»); fehlt das Paar danach, geht die Seite zurück.
    let onDelete: (Watch) -> Void

    @EnvironmentObject var data: AppData
    /// Status der Aktualisierung (eigenes Objekt neben `AppData`): «Aktualisieren» und «Erneut versuchen» gesperrt,
    /// Frische im Kopf (Live-Strom) folgen ihm.
    @ObservedObject var refreshState = AppData.shared.refreshState
    /// Portfolio-Sperre: «Zum Portfolio hinzufügen» erst nach dem Entsperren.
    @ObservedObject var lock = AppLock.shared
    @Environment(\.appAccent) var accent
    @Environment(\.onAccent) var onAccent
    @Environment(\.dismiss) var dismiss
    @Environment(\.changeView) var changeView
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Bitcoin-Paar: Faktor Quote → Umrechnungswährung für «1 CHF = … Sats»; nil = keine Zeile.
    @State var satsRate: Double? = nil
    /// Änderung aus den Kerzen einer anderen Börse (z. B. «Binance» bei einem Kraken-Paar); nil = nicht.
    @State var candleSource: String? = nil
    /// Was von einer anderen Börse kommt (Änderung, Mini-Chart) — steht nicht in der Zeile, sondern hier unter dem Kurs.
    @State var rowSource: DayChange.RowSource? = nil
    @State var editGroup = false
    @State var editNote = false
    /// «Börse wechseln»: Börse, Paar, Kontrakt desselben Eintrags (`WatchEditSheet`).
    @State var editPair = false
    /// «Als Widget hinzufügen»: iOS lässt Apps keine Widgets anlegen → Anleitung.
    @State var showWidgetHelp = false
    /// Live-Aktivität dieses Paars läuft (Sperrbildschirm).
    @State var liveActivityOn = false
    /// «Alarm setzen»: jeder neue Wert öffnet den Editor im Abschnitt «Alarme».
    @State var newAlarmRequest = 0
    /// «Was bewegt sich mit?» als eigene Seite.
    @State var whyFor: WatchlistSheetTarget?
    @State var whyPreview: Watch?
    /// Erfassen-Blatt des Portfolios (nach dem Entsperren).
    @State var portfolioDraft: PortfolioTxDraft?
    /// Vorschau hinzugefügt: Id des neuen Eintrags (die Seite zeigt dann ihn).
    @State private var addedId: Int64? = nil
    /// Vorschau mit Kurs (Ticker-Abfrage ohne Speichern); nil = noch keiner.
    @State private var previewQuoted: Watch? = nil
    /// Vorschau wird gerade hinzugefügt (Knopf gesperrt, «Zur Merkliste hinzugefügt.»).
    @State private var adding = false
    /// «Erneut versuchen» der Vorschau.
    @State var previewReload = 0
    /// Kurzer Hinweis unten (z. B. «Sprachausgabe ist an», «deaktiviert»).
    @State var toast: ToastMessage? = nil
    /// Bestätigungen der Alarme mit Aktion («Rückgängig», «Testen»), unten am Bildschirm.
    @State private var alarmBanner: WatchlistBannerMessage?
    /// Zählt abgelehnte Schalter (Merkliste gesperrt): wertet den body neu aus, damit sie den echten Zustand zeigen.
    @State var refusedToggles = 0
    /// Uhr für «vor 2 Min.» im Kopf — alle 30 Sekunden neu.
    @State var now: Int64 = EpochTime.nowMillis

    /// Gezeigter Eintrag: der hinzugefügte (Vorschau) bzw. der übergebene.
    var currentId: Int64 { addedId ?? watchId }

    var body: some View {
        // Abgelehnte Schalter neu auswerten, ohne Identitätswechsel (sonst springt der VoiceOver-Fokus)
        let _ = refusedToggles
        // Eben hinzugefügt: die Vorschau bleibt stehen, bis der neue Eintrag einen Kurs (oder Fehler) hat
        if let watch = data.watch(currentId),
           !(adding && preview != nil && watch.lastUpdate <= 0 && watch.lastError == nil) {
            content(watch)
        } else if let preview {
            previewContent(previewQuoted ?? preview)
        } else {
            // Paar wurde gelöscht (hier, per Wischen anderswo oder im Widget): zurück
            Color.clear.onAppear { dismiss() }
        }
    }

    /// Vorschau: Kurs holen (neu mit «Erneut versuchen»); nach dem Hinzufügen höchstens kurz warten.
    private func previewContent(_ watch: Watch) -> some View {
        content(watch, isPreview: true)
            // Rollende 24 h aus dem Ticker — so heisst es auch neben der Änderung
            .environment(\.changeView, ChangeView(basis: .ROLLING_24H, current: true))
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

    /// Vorschau › «Zur Merkliste hinzufügen»: wie die Seite «Paar hinzufügen» — Dublettenprüfung (`addWatch`),
    /// Kurs-Mitteilung an, Erst-Moment beim allerersten Paar, Erlaubnis jetzt fragen; danach gleich den Kurs holen.
    /// Stand das Paar schon in der Merkliste, zeigt die Seite einfach jenes.
    func addPreview() {
        guard let preview, !adding, let exchange = ExchangesConfig.exchange(preview.exchangeKey) else { return }
        // Rückfrage «nicht gespeichert» vorher; «Abbrechen» = nichts angelegt, keine Haptik
        data.withWatchlistConfirm {
            let firstEver = data.isFirstPairAdd
            let outcome = data.tryAddWatch(exchange: exchange, pair: preview.pairInfo)
            // Merkliste gesperrt: nichts angelegt, Hinweis und Haptik kommen von `.watchlistLockedHint`
            if outcome == .locked { return }
            if outcome == .full {
                toast = ToastMessage(WatchlistLimitText.full, kind: .error)
                Haptics.play(.warning)
            } else if case .added(let id) = outcome {
                adding = true
                addedId = id
                Haptics.play(.success)
                if firstEver { _ = data.celebrateFirstAdd(id, announceInWatchlist: true) }
                // Neues Paar mit Kurs-Mitteilungen: erst kurz erklären, dann die System-Abfrage
                NotificationPrePrompt.request(.pairsAdded)
                Task { await data.refreshOne(id) }
            } else if let existing = data.watches.first(where: { $0.samePair(as: preview) }) {
                addedId = existing.id
            }
        }
    }

    private func content(_ watch: Watch, isPreview: Bool = false) -> some View {
        let off = watch.disabled && !isPreview
        let scroll = ScrollView {
            VStack(alignment: .leading, spacing: Spacing.lg) {
                priceBlock(watch, isPreview: isPreview)
                // Kurs-Chart (24h · 7T · 30T · 1J, Kerzen/Linie); ohne Kerzenquelle (DEX) bzw. deaktiviert ausgeblendet
                if !off {
                    WatchSheetChartView(watch: watch)
                }
                if isPreview {
                    // Alarm, Favorit und alles Weitere brauchen einen gespeicherten Eintrag
                    previewActions(watch, adding: adding)
                } else {
                    VStack(alignment: .leading, spacing: Spacing.sm) {
                        alarmButton(watch)
                        secondaryActions(watch)
                    }
                    AlarmsScreen(watchId: watch.id, embedded: true, newAlarmRequest: newAlarmRequest,
                                 externalBanner: $alarmBanner)
                        .padding(.top, Spacing.sm)
                }
                // Karte «Marktdaten» (Spot | Futures) für Krypto-Paare; nicht bei deaktivierten Paaren und DEX-Pools
                if !off, CoinLogos.allowed(forExchange: watch.exchangeKey) {
                    WatchlistMarketDataSection(watch: watch)
                        .padding(.top, Spacing.sm)
                }
                if !isPreview, let note = watch.note?.trimmingCharacters(in: .whitespacesAndNewlines), !note.isEmpty {
                    noteCard(note)
                }
            }
            .padding(.horizontal, Spacing.lg)
            .padding(.top, Spacing.sm)
            .padding(.bottom, Spacing.xl)
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
        }
        .background(AppColors.background.ignoresSafeArea())
        .navigationTitle(watch.displayName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !isPreview {
                ToolbarItem(placement: .topBarTrailing) { overflowMenu(watch) }
            }
        }
        .toast($toast)
        // Vorschau nicht übernommen bzw. Paar nicht geändert (Merkliste gesperrt): kurzer Hinweis mit Warn-Haptik
        .watchlistLockedHint { toast = ToastMessage($0, kind: .error) }
        .overlay(alignment: .bottom) {
            WatchlistBanner(message: $alarmBanner) { _ in }
                .animation(reduceMotion ? nil : .spring(duration: 0.35), value: alarmBanner)
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                now = EpochTime.nowMillis
            }
        }
        // «1 CHF = … Sats»: Faktor zuerst aus dem Zwischenspeicher, sonst frisch
        .task(id: "\(watch.quoteAsset)|\(data.settings.portfolioCurrency)|\(Sats.isBitcoin(watch.baseAsset))") {
            guard Sats.isBitcoin(watch.baseAsset), !watch.isNotTraded else { satsRate = nil; return }
            let target = data.settings.portfolioCurrency
            satsRate = nil
            if let cached = CurrencyConverter.cachedRate(quote: WatchlistConversion.quote(watch), target: target) {
                satsRate = cached
            } else {
                satsRate = await CurrencyConverter.rate(quote: WatchlistConversion.quote(watch), target: target)
            }
        }
        // Woher die Änderung kommt (neu nach jeder Aktualisierung des Paars)
        .task(id: "\(watch.id)|\(watch.lastUpdate)|\(watch.change24h ?? .nan)") {
            now = EpochTime.nowMillis
            candleSource = await PriceRefresher.foreignCandleSource(watch)
            rowSource = isPreview ? nil : await WatchlistRow.rowSource(for: watch)
        }
        return pages(subSheets(scroll, watch: watch))
    }

    /// Eigene Notiz (Tippen bearbeitet) — wie Android.
    private func noteCard(_ note: String) -> some View {
        Button {
            Haptics.play(.selection)
            editNote = true
        } label: {
            Text(note)
                .font(.body)
                .foregroundStyle(AppColors.onSurface)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(Spacing.md)
                .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityHint(L("note_edit"))
    }

    /// «Was bewegt sich mit?» als eigene Seite (zurück führt hierher), Erfassen-Blatt des Portfolios.
    private func pages(_ content: some View) -> some View {
        content
            .navigationDestination(item: $whyFor) { target in
                WatchlistWhySheet(watchId: target.id)
                    .environmentObject(data)
                    .environment(\.appAccent, accent)
            }
            .navigationDestination(item: $whyPreview) { watch in
                WatchlistWhySheet(watchId: WatchPreview.watchId, preview: watch)
                    .environmentObject(data)
                    .environment(\.appAccent, accent)
            }
            // Wieder gesperrt (Hintergrund-Limit), während das Erfassen-Blatt offen ist: schliessen
            .onChange(of: lock.locked) { _, locked in
                if locked { portfolioDraft = nil }
            }
            .modifier(PortfolioQuickAddPresenter(
                draft: $portfolioDraft,
                onEnabled: { toast = ToastMessage(L("portfolio_enabled_banner")) },
                // «Später»: Transaktion gespeichert, Tab bleibt aus — einmal der Weg zum Schalter
                onLater: { toast = ToastMessage(L("portfolio_enable_later_hint")) }
            ))
    }

    /// Unterblätter (Börse wechseln, Notiz, Widget-Hilfe, Gruppe).
    private func subSheets(_ content: some View, watch: Watch) -> some View {
        content
        .sheet(isPresented: $editPair) {
            WatchEditSheet(watch: watch, alarmCount: data.activeAlarmCounts[watch.id] ?? 0)
                .environmentObject(data)
                .environment(\.appAccent, accent)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .presentationCornerRadius(28)
                .presentationBackground(AppColors.background)
        }
        .sheet(isPresented: $editNote) {
            WatchNoteSheet(initial: watch.note, onSave: saveNote)
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
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $editGroup) {
            WatchGroupSheet(current: watch.groupName, groups: data.watchGroups, onSelect: saveGroup)
            .environment(\.appAccent, accent)
            // Volle Höhe: «Neue Gruppe» blendet ein Feld samt Tastatur ein, das Blatt springt nicht
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(28)
            .presentationBackground(AppColors.background)
        }
    }
}
