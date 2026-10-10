import SwiftUI
import UIKit

/// «Mehr» im Aktionsblatt: auf- oder zugeklappt, für die Dauer der Sitzung gemerkt (Standard zu).
enum WatchSheetMoreState {
    nonisolated(unsafe) static var expanded = false
}

/// Aktionen zu einem Paar als Blatt von unten — wie `WatchActionsSheet`: Kopf mit Stift, Kurs
/// und Chart (`WatchSheetChartView`), gross Alarm, «Warum?» und Favorit, darunter Futures-Daten.
/// Alles Weitere — Gruppe, Notiz, «Zum Portfolio hinzufügen» (nur mit Portfolio), Aktualisieren,
/// Widget, Vorlesen, Mitteilung, Sperrbildschirm (nur iOS), Sortieren und zuletzt Löschen — im
/// aufklappbaren Abschnitt «Mehr» (zu; für die Sitzung gemerkt). Das Blatt steht immer in voller
/// Höhe, Aufklappen ändert nur den Inhalt der Liste.
///
/// `preview`: Paar steht (noch) nicht in der Merkliste (Coin aus «Heute auffällig», nur im Speicher,
/// `watchId` = `WatchPreview.watchId`): Kopf, Kurs, Chart und «Warum?» wie sonst, dazu gross «Zur
/// Merkliste hinzufügen». Alles, was einen gespeicherten Eintrag braucht — Paar bearbeiten, Alarm,
/// Favorit, Notiz und der ganze Abschnitt «Mehr» —, fehlt. Nach dem Hinzufügen wird daraus dasselbe
/// Blatt für den neuen Eintrag. Wie Android `WatchActionsSheet(preview = true)`.
@MainActor
struct WatchActionsSheet: View {
    let watchId: Int64
    /// Paar hat gerade ⚡-Signale (kleines ⚡ an «Warum bewegt sich das?»).
    var hasActivity = false
    /// Alarme öffnen (nach dem Schliessen des Blatts).
    let onOpenAlarms: (Int64) -> Void
    /// Löschen erfragen (nach dem Schliessen des Blatts).
    let onDelete: (Watch) -> Void
    /// «Warum bewegt sich das?» öffnen (nach dem Schliessen des Blatts).
    let onWhy: (Int64) -> Void
    /// Erfassen-Blatt des Portfolios öffnen (nach dem Schliessen des Blatts).
    var onAddToPortfolio: (Int64) -> Void = { _ in }
    /// Vorschau eines Paars, das nicht in der Merkliste steht (siehe oben); nil = normales Blatt.
    var preview: Watch? = nil
    /// Vorschau: «Warum bewegt sich das?» mit dem Vorschau-Paar samt Kurs (nach dem Schliessen des Blatts).
    var onWhyPreview: (Watch) -> Void = { _ in }

    @EnvironmentObject var data: AppData
    @Environment(\.appAccent) var accent
    @Environment(\.dismiss) var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var futures: FuturesInfo? = nil
    /// Bitcoin-Paar: Faktor Quote → Umrechnungswährung für «1 CHF = … Sats»; nil = keine Zeile.
    @State private var satsRate: Double? = nil
    /// Pille aus den Kerzen einer anderen Börse (z. B. «Binance» bei einem Kraken-Paar); nil = nicht.
    @State private var candleSource: String? = nil
    @State var editGroup = false
    @State var editNote = false
    /// «Paar bearbeiten» (Stift neben dem Paar): Börse, Paar, Kontrakt desselben Eintrags.
    @State private var editPair = false
    /// «Als Widget hinzufügen»: iOS lässt Apps keine Widgets anlegen → Anleitung (Runde 13b).
    @State var showWidgetHelp = false
    /// Live-Aktivität dieses Paars läuft (Sperrbildschirm).
    @State var liveActivityOn = false
    /// iOS-Einstellung «Live-Aktivitäten» ist aus — Hinweis zeigen.
    @State var liveActivityDisabled = false
    /// «Mehr» aufgeklappt (Startwert: zuletzt in dieser Sitzung).
    @State private var moreExpanded = WatchSheetMoreState.expanded
    /// Vorschau hinzugefügt: Id des neuen Eintrags (das Blatt zeigt dann ihn).
    @State private var addedId: Int64? = nil
    /// Vorschau mit Kurs (Ticker-Abfrage ohne Speichern); nil = noch keiner.
    @State private var previewQuoted: Watch? = nil
    /// Vorschau wird gerade hinzugefügt (Knopf gesperrt, «Zur Merkliste hinzugefügt.»).
    @State private var adding = false
    /// «Erneut versuchen» der Vorschau.
    @State private var previewReload = 0

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
            // Rollende 24 h aus dem Ticker — so heisst es auch neben der Pille
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
            Task { _ = await Notifier.requestPermission() }
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

                // Kurs-Chart (24h · 7T · 30T, Kerzen/Linie); ohne Kerzenquelle (DEX) ganz ausgeblendet
                WatchSheetChartView(watch: watch)

                if isPreview {
                    // Vorschau: gross «Zur Merkliste hinzufügen», daneben nur «Warum?» (Alarm und
                    // Favorit brauchen einen gespeicherten Eintrag)
                    previewActions(watch, adding: adding)
                } else {
                    // Die drei häufigsten Aktionen zuerst und gleich gross: Alarm, Warum?, Favorit
                    primaryActions(watch, alarmCount: alarmCount)
                }

                if let futures {
                    WatchlistFuturesSection(info: futures)
                        .transition(.opacity.combined(with: .move(edge: .top)))
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
            .animation(reduceMotion ? nil : .spring(duration: 0.35), value: futures)
            .animation(reduceMotion ? nil : .snappy, value: moreExpanded)
        }
        .scrollIndicators(.hidden)
        .background(AppColors.background.ignoresSafeArea())
        // Futures-Kennzahlen nur für Perpetuals laden (neu, wenn das Paar bearbeitet wurde)
        .task(id: WatchEdit.Key(watch)) {
            guard watch.contractType == .perpetual else { futures = nil; return }
            futures = try? await FuturesDataSource.fetch(watch: watch)
        }
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
        // Woher die Pille kommt (neu nach jeder Aktualisierung des Paars)
        .task(id: "\(watch.id)|\(watch.lastUpdate)|\(watch.change24h ?? .nan)") {
            candleSource = await PriceRefresher.foreignCandleSource(watch)
        }
        return subSheets(scroll, watch: watch, alarmCount: alarmCount)
    }

    /// Kopfzeile «Mehr» mit Pfeil; VoiceOver: Knopf «Mehr» mit Zustand auf-/zugeklappt.
    private var moreToggle: some View {
        Button {
            WatchlistHaptics.selection()
            moreExpanded.toggle()
            WatchSheetMoreState.expanded = moreExpanded
        } label: {
            HStack(spacing: 8) {
                Text(L("sheet_more"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                Spacer(minLength: 8)
                Image(systemName: "chevron.down")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
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
                            if let w = data.watch(currentId) { data.setTtsEnabled(w, enabled) }
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
                .background(AppColors.container, in: RoundedRectangle(cornerRadius: 18, style: .continuous))

                liveActivityCard(watch)

                moveRow(watch)

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
                    .background(AppColors.error.opacity(0.10), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
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

    // MARK: Kopf

    private func header(_ watch: Watch, isPreview: Bool) -> some View {
        HStack(spacing: Spacing.md) {
            CoinBadge(symbol: watch.baseAsset, size: 48, logo: CoinLogos.allowed(forMarket: watch.marketKey),
                      pair: watch.logoPairKey)
            VStack(alignment: .leading, spacing: 2) {
                Text(watch.displayName)
                    .font(AppFont.headline)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                // Vorschau: «Binance · Nicht in der Merkliste»
                Text(isPreview ? "\(watch.marketName) · \(L("watch_preview_not_watched"))" : watch.marketName)
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            Spacer(minLength: 0)
            // «Paar bearbeiten»: 48 pt Tippfläche; Vorschau: nichts zu bearbeiten
            if !isPreview {
                Button {
                    WatchlistHaptics.selection()
                    editPair = true
                } label: {
                    Image(systemName: "pencil")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(accent.primary)
                        .frame(width: 48, height: 48)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L("watch_edit_title"))
            }
        }
    }

    /// «Veränderung aus Binance-Kerzen», wenn die Pille aus fremden Kerzen kommt; sonst nil.
    private func candleNote(_ watch: Watch) -> String? {
        guard watch.lastPrice != nil, let candleSource else { return nil }
        return L("change_candle_source", candleSource)
    }

    private func priceBlock(_ watch: Watch, isPreview: Bool) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset))
                    .displayFont()
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .contentTransition(.numericText(value: watch.lastPrice ?? 0))
                if watch.lastPrice != nil {
                    WatchlistDayChangePill(watch: watch, large: true, note: candleNote(watch))
                }
            }
            // Pille aus fremden Kerzen (z. B. Kraken-Paar → Binance): klein darunter sagen, woher
            if let note = candleNote(watch) {
                Text(note)
                    .font(.caption2)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            // Bitcoin: «1 CHF = 1’234 Sats» in der Umrechnungswährung (Kurs mit dem bestehenden Faktor)
            if Sats.isBitcoin(watch.baseAsset), !watch.isNotTraded,
               let sats = Sats.perUnit(price: watch.lastPrice, rate: satsRate) {
                Text(L("sats_per_unit", LocaleNumbers.integer(1), data.settings.portfolioCurrency,
                       LocaleNumbers.decimal(sats, maxDecimals: Sats.decimals(sats), grouping: true)))
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            if let error = watch.lastError, !error.isEmpty, watch.lastPrice != nil, ConnectionErrors.isRetryable(error) {
                // Börse nicht erreichbar, älterer Kurs steht: ruhiger Satz plus «Erneut versuchen»
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(L("error_market_unreachable"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button(L("try_again")) {
                        if isPreview { previewReload += 1 } else { Task { await data.refreshOne(watch.id) } }
                    }
                    .font(.footnote.weight(.semibold))
                    .buttonStyle(.borderless)
                    .tint(accent.primary)
                    .disabled(data.refreshingWatchIds.contains(watch.id))
                }
            } else if let error = watch.lastError, !error.isEmpty {
                Text(ConnectionErrors.display(error))
                    .font(.footnote)
                    .foregroundStyle(ConnectionErrors.isNotTraded(error) ? AppColors.onSurfaceVariant : AppColors.error)
            } else if isPreview && watch.lastUpdate <= 0 {
                // Vorschau: Kurs wird noch geholt
                Text(L("loading_hint"))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            } else {
                Text(L("watchlist_updated", PriceFormat.time(watch.lastUpdate)))
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(LinearGradient(
                    colors: [accent.tint(0.16), accent.tint(0.04)],
                    startPoint: .topLeading, endPoint: .bottomTrailing))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(accent.tint(0.25), lineWidth: 1)
        )
        .animation(.snappy, value: watch.lastPrice)
    }

}
