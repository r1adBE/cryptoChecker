import Accessibility
import SwiftUI

/// Portfolio-Tab: Gesamtwert, Coins nach Wert, Erfassen per «+» — wie `PortfolioScreen.kt`.
/// Bedienung wie in der Merkliste: nach unten ziehen oder ⋯ › «Aktualisieren» lädt die Kurse
/// neu; einen Coin nach links wischen löscht alle seine Transaktionen (Banner mit
/// «Rückgängig»); ⋯ › «Portfolio leeren» löscht nach Rückfrage alles.
/// Enthält keinen `NavigationStack` — der Aufrufer bettet den Tab ein.
@MainActor
struct PortfolioScreen: View {
    @EnvironmentObject var data: AppData
    @Environment(\.priceColorScheme) var priceColors
    @Environment(\.priceHighContrast) var highContrast
    @Environment(\.priceColorsInverted) var inverted
    @ObservedObject var model = PortfolioModel.shared
    @Environment(\.appAccent) var accent

    @State var sheet: PortfolioTxDraft?
    @State var openCoin: String?
    var hideAmounts: Bool { data.settings.hidePortfolioAmounts }
    @State var showClosed = false
    @State var exportOpen = false
    /// Neuer Alarm «Portfolio-Wert» (erscheint in der Alarm-Übersicht).
    @State var alarmOpen = false
    @State private var toast: String?
    /// Banner unten («BTC entfernt» mit «Rückgängig») — wie in der Merkliste.
    @State var banner: WatchlistBannerMessage?
    /// «Portfolio leeren» — Rückfrage.
    @State var askClear = false
    @State var deleteTick = 0
    /// ⋯ › App-Logo und Name: «Über».
    @State var showAbout = false
    /// ⋯ › «Adressen» (beobachtete öffentliche Adressen).
    @State var addressesOpen = false
    /// Einmal automatisch «Nur anzeigen» gestellte Adressen (später aufgetauchte Überschneidung), Hinweis noch offen.
    @State var overlapSwitched: [WatchedAddress] = []
    /// Früher automatisch «Nur anzeigen» ohne passende Menge, jetzt wieder zählend (einmal), Hinweis noch offen.
    @State var overlapRestored: [WatchedAddress] = []
    /// ⋯ › «CSV importieren» (Relai, Swissquote): Dateiauswahl, dann Vorschau.
    @State var csvPickerOpen = false
    /// ⋯ › «Importe»: Importläufe mit «Rückgängig».
    @State var importsOpen = false
    @State private var csvPreview: CsvImportLoaded?
    @State private var csvUnreadable = false
    @Environment(\.accessibilityReduceMotion) var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    /// Uhr für «vor 2 Min.» und den Wechsel nach «veraltet» (alle 30 s, wie der Markt-Tab).
    @State private var now = TimeUtils.nowMillis

    init() {}

    var body: some View {
        // Mit Adress-Guthaben (Einzahlung ohne Kaufpreis); auch nur mit Adressen gibt es Inhalt
        let transactions = model.calcPortfolio
        let currency = data.settings.portfolioCurrency
        let hasData = !data.portfolio.isEmpty || !data.watchedAddresses.isEmpty
        let base = Group {
            if !hasData {
                emptyState
            } else {
                // Zwischengespeichert im Modell (neu nur bei geänderten Eingaben)
                content(model.summary, currency: currency)
            }
        }
        // «Beträge verbergen» (Auge oben bzw. Einstellung): Beträge als «•••», Prozente bleiben
        .environment(\.hidePortfolioAmounts, data.settings.hidePortfolioAmounts)
        .background(AppColors.background.ignoresSafeArea())
        .tabTitle(L("portfolio_title"), icon: .portfolio)
        .toolbar { toolbarContent(hasTransactions: !data.portfolio.isEmpty, hasData: hasData, currency: currency) }
        // Beim Öffnen und bei neuen Coins Kurse auffrischen (60 s Zwischenspeicher)
        .task(id: Set(transactions.flatMap(\.coins))) { await model.refresh(force: false) }
        .task(id: currency) { await model.loadFx(currency) }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                now = TimeUtils.nowMillis
            }
        }
        // Netz wieder da: Kurse auffrischen (60 s Zwischenspeicher), wie die Merkliste
        .onChange(of: data.online) { _, online in
            if online { Task { await model.refresh(force: false) } }
        }
        // Wertverlauf: geänderte Transaktionen (auch ohne neuen Coin) neu rechnen
        .onChange(of: transactions) { model.recomputeHistory() }
        // Einstandsmethode bestimmt die Bestände (FIFO je Wallet): «7 T/1 J» und Verlauf neu rechnen
        .onChange(of: data.settings.costMethod) { model.recomputeHistory() }
        // Adresse hinzugefügt/entfernt: Gesamtwert, Widget und Alarme nachziehen. Nur die Liste (Chain + Adresse)
        // zählt — neue Guthaben/Versuche kommen aus `refresh` selbst und lösten sonst gleich den nächsten aus
        .onChange(of: data.watchedAddresses.map { "\($0.chain.rawValue):\($0.address)" }) {
            Task { await model.refresh(force: false) }
        }
        // Nicht versiegelt speicherbar (Schlüsselbund gerade nicht lesbar): Änderungen warten im Speicher — sagen, nicht
        // still (nie im Klartext geschrieben); das nächste Speichern bzw. die Rückkehr der App versucht es erneut
        .onChange(of: data.portfolioSavePending) { _, pending in
            if pending {
                banner = WatchlistBannerMessage(text: L("portfolio_save_pending"), icon: "lock.trianglebadge.exclamationmark",
                                                long: true)
            }
        }
        // Später aufgetauchte Überschneidung Adresse ↔ eigene Transaktionen: einmal automatisch «Nur anzeigen» — wie Android
        .onAppear { checkAddressOverlap() }
        .onChange(of: data.watchedAddresses) { checkAddressOverlap() }
        .onChange(of: transactions) { checkAddressOverlap() }
        // Bei offenem Adressen-Blatt erst danach (der Banner läge hinter dem Blatt)
        .onChange(of: addressesOpen) { showAddressOverlapNotice() }
        return presentations(base)
    }

    /// Blätter, Ziele, Dialoge und Hinweise — eigene Funktion, damit der Compiler `body` in vertretbarer Zeit prüft.
    private func presentations<V: View>(_ base: V) -> some View {
        base
        .sheet(isPresented: $addressesOpen) {
            PortfolioAddressSheet(onPortfolioEnabled: { toast = L("portfolio_enabled_banner") })
                .environmentObject(data)
                .environment(\.appAccent, accent)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .sheet(item: $sheet) { draft in
            PortfolioTxSheet(initial: draft)
                .environmentObject(data)
                .environment(\.appAccent, accent)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .presentationCornerRadius(28)
                .presentationBackground(AppColors.background)
        }
        .navigationDestination(item: $openCoin) { coin in
            PortfolioCoinDetail(coin: coin)
        }
        .navigationDestination(isPresented: $showAbout) {
            AboutSettingsPage()
        }
        .sheet(isPresented: $alarmOpen) {
            PortfolioAlarmSheet(currency: data.settings.portfolioCurrency, basis: data.settings.changeBasis.storage) { kind, threshold, repeating in
                data.addPortfolioAlarm(kind: kind, threshold: threshold, currency: data.settings.portfolioCurrency,
                                       repeating: repeating)
                showSavedAlarm(PortfolioAlarm(id: 0, kind: kind, threshold: threshold,
                                              currency: kind.isValue ? data.settings.portfolioCurrency : nil,
                                              repeating: repeating))
                // Neu und scharf: gleich mit den aktuellen Kursen prüfen
                Task { await model.refresh(force: false) }
            }
            .environment(\.appAccent, accent)
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $exportOpen) {
            PortfolioExportSheet(
                transactions: data.portfolio,
                currency: data.settings.portfolioCurrency,
                costMethod: data.settings.costMethod,
                taxTemplate: data.settings.taxTemplate,
                onTaxTemplate: { data.settings.taxTemplate = $0 },
                taxTemplateConfirmed: data.settings.taxTemplateConfirmed,
                onConfirmTemplate: { data.settings.taxTemplateConfirmed = true },
                onExported: {
                    exportOpen = false
                    toast = L("portfolio_export_done")
                }
            )
            .environment(\.appAccent, accent)
            // Eine feste Höhe: Hinweis «heute» und grosse Schrift lassen nur den Inhalt wachsen
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(28)
            .presentationBackground(AppColors.background)
        }
        .testableFileImporter(isPresented: $csvPickerOpen, allowedContentTypes: PortfolioCsvImporter.contentTypes,
                              testFile: .csv) { result in
            guard case .success(let url) = result else { return }
            let keys = data.portfolioImportKeys
            Task {
                // Lesen und Zuordnen zu Ende führen, auch wenn die App währenddessen in den Hintergrund geht
                let backgroundTask = BackgroundTaskGuard.begin("csvImport")
                defer { backgroundTask.end() }
                if let loaded = await PortfolioCsvImporter.load(url, existingKeys: keys) {
                    csvPreview = loaded
                } else {
                    csvUnreadable = true
                }
            }
        }
        .sheet(item: $csvPreview) { loaded in
            PortfolioCsvImportSheet(loaded: loaded) { count, enabled in
                toast = enabled ? L("portfolio_enabled_banner") : L("portfolio_csv_import_done", count: count)
            }
            .environmentObject(data)
            .environment(\.appAccent, accent)
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(28)
            .presentationBackground(AppColors.background)
        }
        .sheet(isPresented: $importsOpen) {
            PortfolioImportsSheet(batches: data.portfolioImportBatches) { batch in
                undoImport(batch)
            }
            .environment(\.appAccent, accent)
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
            .presentationBackground(AppColors.background)
        }
        .alert(L("portfolio_csv_import_action"), isPresented: $csvUnreadable) {
            Button(L("action_close"), role: .cancel) {}
        } message: {
            Text(L("portfolio_csv_import_unreadable"))
        }
        .toast($toast)
        // Ungültige Einträge aus der Systemsicherung einmal nennen
        .systemRestoreNotice()
        .overlay(alignment: .bottom) {
            // Animation nur für den Banner, nicht für die Liste darunter
            WatchlistBanner(message: $banner) { _ in }
                .animation(reduceMotion ? nil : .spring(duration: 0.35), value: banner)
        }
        .sensoryFeedback(.impact(weight: .medium), trigger: deleteTick)
        .confirmationDialog(L("portfolio_clear"), isPresented: $askClear, titleVisibility: .visible) {
            Button(L("portfolio_clear"), role: .destructive) {
                withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { data.clearPortfolio() }
            }
            Button(L("action_cancel"), role: .cancel) {}
        } message: {
            // Anzahl nennen; beobachtete Adressen bleiben (nur Transaktionen werden gelöscht) — wie Android
            Text(L("portfolio_clear_confirm_count", count: data.portfolio.count))
        }
    }

    // MARK: Inhalt

    /// Zustand der Kurse wie in der Merkliste: aktuell / veraltet / offline mit «Stand …».
    private func priceState(_ summary: PortfolioSummary) -> PortfolioPriceState {
        PortfolioPriceFallback.state(updatedAt: model.prices.updatedAt, now: now, staleAfterMillis: model.staleAfterMillis,
                                     online: data.online, hasOpenCoins: !summary.open.isEmpty)
    }

    /// Abgeblasst: veraltet, auch offline mit altem Stand.
    private func pricesStale(_ state: PortfolioPriceState) -> Bool {
        state == .stale || (state == .offline
            && PortfolioPriceFallback.isStale(model.prices.updatedAt, now: now, staleAfterMillis: model.staleAfterMillis))
    }

    private func content(_ summary: PortfolioSummary, currency: String) -> some View {
        let state = priceState(summary)
        let stale = pricesStale(state)
        return List {
            if state != .none {
                PortfolioStatusPill(state: state, updatedAt: model.prices.updatedAt, now: now)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .portfolioListRow(top: 4, bottom: 4)
            }
            PortfolioTotalCard(
                summary: summary,
                updatedAt: model.prices.updatedAt,
                currency: currency,
                // Faktor USDT → Anzeigewährung: Devisenkurs × USDT/USD (USDT zum Marktkurs)
                fxRate: model.displayRate(for: currency),
                today: model.today,
                todaySkipped: model.todaySkipped,
                weekChange: model.weekChange,
                yearChange: model.yearChange,
                stale: stale,
                priceSources: model.prices.sources,
                exchangeFailed: model.prices.exchangeFailed,
                mixedExchange: model.prices.mixedExchange,
                periodScaled: model.periodScaled,
                walletShortfalls: PortfolioCalculator.walletShortfalls(model.calcPortfolio, method: data.settings.costMethod),
                onShortfall: { id in
                    if let tx = data.portfolio.first(where: { $0.id == id }) { sheet = PortfolioTxDraft(tx: tx) }
                },
                // «USDT = 1 angenommen»: Messung 6 Std. gültig, mit der Uhr der Ansicht (`DepegAlarm.cardNote` in der Karte)
                depegReading: DepegAlarmCheck.lastReading,
                depegThresholdTenths: data.settings.depegThresholdTenths,
                now: now
            )
            .portfolioListRow(top: 4, bottom: 8)

            // Wertverlauf über den Positionen
            PortfolioHistoryCard(history: model.history, range: $model.historyRange,
                                 expanded: $data.settings.portfolioHistoryExpanded,
                                 view: $data.settings.portfolioHistoryView,
                                 candles: $data.settings.portfolioHistoryCandles,
                                 pnl: $data.settings.portfolioHistoryPnl)
                .portfolioListRow(top: 0, bottom: 8)

            // Regel der Adress-Guthaben für Heute/7 T/1 J und Verlauf offen nennen (ab der ersten Beobachtung)
            if data.watchedAddresses.contains(where: { !($0.lastBalance ?? "").isEmpty }) {
                Text(L("portfolio_address_period_observed"))
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 4)
                    .portfolioListRow(top: 0, bottom: 8)
            }

            // Aufteilung (vier grösste Coins + «Andere»), erst ab zwei Teilen
            let slices = PortfolioInsights.allocation(summary.open)
            if slices.count >= 2 {
                PortfolioAllocationCard(slices: slices)
                    .portfolioListRow(top: 0, bottom: ListSegment.spacing)
            }

            // Kursänderung je Coin über die %-Basis steht klein in der Zeile
            let basis = data.settings.changeBasis.storage
            // Anzahl Transaktionen je Coin (kleine Zahl neben der Menge)
            // Tausch zählt bei beiden Coins
            let txCounts = Dictionary(grouping: data.portfolio.flatMap { tx in Set(tx.coins.map(PortfolioCalculator.normalizeCoin)) },
                                      by: { $0 })
                .mapValues(\.count)
            let openIndex = Dictionary(summary.open.enumerated().map { ($1.coin, $0) }, uniquingKeysWith: { first, _ in first })
            // Stablecoins mit gebrückten Varianten (USDC.e …): Zeile mit «≈» (`AddressBalance.bridgedStables`)
            let bridgedStables = AddressBalance.bridgedStables(data.watchedAddresses)
            ForEach(summary.open) { position in
                Button {
                    openCoin = position.coin
                } label: {
                    PortfolioCoinRow(
                        position: position,
                        pnl: model.coinPnl[position.coin] ?? model.coinPnl[position.coin.uppercased()],
                        basis: basis,
                        sharePercent: position.value.map { PortfolioWidgetStore.sharePercent($0, total: summary.totalValue) },
                        txCount: txCounts[position.coin] ?? 0,
                        shape: ListSegment.shape(openIndex[position.coin] ?? 0, summary.open.count),
                        stale: stale,
                        unchecked: model.prices.unchecked.contains(position.coin),
                        unavailable: model.prices.unavailable.contains(position.coin),
                        // Wert und 24h wie im Widget in der Anzeigewährung (Detail bleibt USDT)
                        unit: PortfolioCardUnit(currency: currency, fxRate: model.displayRate(for: currency)),
                        // Build 46: Börse des Kurses klein in der Zeile (nicht nur unter «Hinweise»)
                        exchange: model.prices.sources[position.coin],
                        viaBase: model.prices.viaBase[position.coin],
                        bridgedStable: bridgedStables[position.coin]?.joined(separator: ", ")
                    )
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier(UiTestTags.portfolioCoin(position.coin))
                // VoiceOver: Löschen wie nach links wischen (mit «Rückgängig»)
                .accessibilityActions {
                    if (txCounts[position.coin] ?? 0) > 0 {
                        Button(L("action_delete")) { deleteCoin(position.coin) }
                    }
                }
                // Nach links wischen = löschen ohne Rückfrage (Rückgängig im Banner); kein Favorit.
                // Coin nur aus einer Adresse: nichts zu löschen (Adresse über ⋯ › «Adressen» entfernen)
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    if !voiceOver && (txCounts[position.coin] ?? 0) > 0 {
                        Button(role: .destructive) { deleteCoin(position.coin) } label: {
                            Label(L("action_delete"), systemImage: "trash")
                        }
                        // Systemrot: weisse Schrift bleibt auch im Dunkelmodus lesbar
                        .tint(AppColors.destructive)
                    }
                }
                .portfolioListRow(top: ListSegment.gap / 2, bottom: ListSegment.gap / 2)
            }

            if !summary.closed.isEmpty {
                closedSection(summary.closed)
                    .portfolioListRow(top: ListSegment.spacing, bottom: 4)
            }

            PortfolioDisclaimer()
                .portfolioListRow(top: 0, bottom: 0)
            // Unten Platz für den «+»-Knopf (58 pt + Rand + Schatten, K5: verdeckt nichts — wie Android 96 dp)
            Color.clear.frame(height: 96).portfolioListRow(top: 0, bottom: 0)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        // iPad/Querformat: Zeilen höchstens 640 pt breit, mittig
        .readableListMargins()
        .animation(reduceMotion ? nil : .spring(duration: 0.35), value: summary.open.map(\.coin))
        .animation(.easeInOut(duration: 0.25), value: showClosed)
        .refreshable { await model.refresh(force: true) }
        .overlay(alignment: .bottomTrailing) {
            PortfolioAddButton { sheet = PortfolioTxDraft() }
        }
    }

}
