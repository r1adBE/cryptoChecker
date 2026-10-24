import Accessibility
import SwiftUI

/// Portfolio-Tab: Gesamtwert, Coins nach Wert, Erfassen per «+» — wie `PortfolioScreen.kt`.
/// Bedienung wie in der Merkliste: nach unten ziehen oder ⋯ › «Aktualisieren» lädt die Kurse
/// neu; einen Coin nach links wischen löscht nach Rückfrage alle seine Transaktionen (Banner
/// mit «Rückgängig»); ⋯ › «Portfolio leeren» löscht nach Rückfrage alles.
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
    @State private var toast: ToastMessage?
    /// Banner unten («BTC entfernt» mit «Rückgängig») — wie in der Merkliste.
    @State var banner: WatchlistBannerMessage?
    /// «Portfolio leeren» — Rückfrage.
    @State var askClear = false
    /// Coin, dessen Transaktionen nach Rückfrage gelöscht werden (Wischen bzw. VoiceOver «Löschen»).
    @State var askDeleteCoin: String?
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
    /// Einträge ohne Einstand (Karte, Hinweise unter der Gesamtkarte).
    @State var costGapsOpen = false
    /// «Unbekannt lassen»: Ids, die die Karte nicht mehr zählt (nur auf diesem Gerät).
    @AppStorage(PortfolioCostGapUi.unknownKey) var costGapsUnknown = ""
    @State private var csvPreview: CsvImportLoaded?
    @State private var csvUnreadable = false
    /// Grund der Meldung «nicht lesbar»: zu gross oder kein unterstütztes Format.
    @State private var csvRefusal = CsvImportRefusal.unreadable
    /// Laden der gewählten Datei (lesen, Tageskurse, Vorschau) mit Fortschritt und «Abbrechen» — wie Android Loading.
    @StateObject private var csvLoad = CsvImportRun()
    @Environment(\.accessibilityReduceMotion) var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) var voiceOver
    /// Uhr für «vor 2 Min.» und den Wechsel nach «veraltet» (alle 30 s, wie der Markt-Tab).
    @State var now = TimeUtils.nowMillis
    /// FIFO je Wallet: Abgänge über den Bestand ihrer Wallet — im Hintergrund (`PortfolioWalletShortfallKey`).
    @State var walletShortfalls: [PortfolioWalletShortfall] = []

    init() {}

    var body: some View {
        // Mit Adress-Guthaben (Einzahlung ohne Kaufpreis); auch nur mit Adressen gibt es Inhalt
        let transactions = model.calcPortfolio
        let currency = data.settings.portfolioCurrency
        let hasData = !data.portfolio.isEmpty || !data.watchedAddresses.isEmpty
        // In Stufen zusammengesetzt, damit der Compiler `body` in vertretbarer Zeit prüft
        let framed = chrome(core(hasData: hasData, currency: currency), hasData: hasData, currency: currency)
        let loading = refreshing(framed, transactions: transactions, currency: currency)
        return presentations(observing(loading, transactions: transactions))
    }

    @ViewBuilder
    private func core(hasData: Bool, currency: String) -> some View {
        if !hasData {
            emptyState
        } else {
            // Zwischengespeichert im Modell (neu nur bei geänderten Eingaben, im Hintergrund gerechnet)
            content(model.shownSummary, currency: currency)
        }
    }

    /// Umgebung, Hintergrund, Titel und Werkzeugleiste.
    private func chrome<V: View>(_ base: V, hasData: Bool, currency: String) -> some View {
        base
            // «Beträge verbergen» (Auge oben bzw. Einstellung): Beträge als «•••», Prozente bleiben
            .environment(\.hidePortfolioAmounts, data.settings.hidePortfolioAmounts)
            .background(AppColors.background.ignoresSafeArea())
            .tabTitle(L("portfolio_title"), icon: .portfolio)
            .toolbar { toolbarContent(hasTransactions: !data.portfolio.isEmpty, hasData: hasData, currency: currency) }
    }

    /// Kurse, Devisen und Uhr laden bzw. auffrischen.
    private func refreshing<V: View>(_ base: V, transactions: [PortfolioTx], currency: String) -> some View {
        base
            // Beim Öffnen und bei neuen Coins Kurse auffrischen (60 s Zwischenspeicher)
            .task(id: model.calcCoins) { await model.refresh(force: false) }
            .task(id: currency) { await model.loadFx(currency) }
            .task(id: PortfolioWalletShortfallKey(trades: transactions, method: data.settings.costMethod)) {
                let key = PortfolioWalletShortfallKey(trades: transactions, method: data.settings.costMethod)
                let result = await key.compute()
                if !Task.isCancelled { walletShortfalls = result }
            }
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
    }

    /// Reaktionen auf geänderte Transaktionen, Adressen und Speicherzustand.
    private func observing<V: View>(_ base: V, transactions: [PortfolioTx]) -> some View {
        base
            // Wertverlauf: geänderte Transaktionen (auch ohne neuen Coin) neu rechnen
            // «Seit Kauf» in der Anzeigewährung: ein früherer Erwerbstag braucht ältere Kurse
            .onChange(of: transactions) {
                model.recomputeHistory()
                Task { await model.loadCostFx() }
            }
            // Einstandsmethode bestimmt die Bestände (FIFO je Wallet): «7 T/1 J» und Verlauf neu rechnen
            .onChange(of: data.settings.costMethod) {
                model.recomputeHistory()
                Task { await model.loadCostFx() }
            }
            // Adresse hinzugefügt/entfernt: Gesamtwert, Widget und Alarme nachziehen. Nur die Liste (Chain + Adresse)
            // zählt — neue Guthaben/Versuche kommen aus `refresh` selbst und lösten sonst gleich den nächsten aus
            .onChange(of: data.watchedAddresses.map { "\($0.chain.rawValue):\($0.address)" }) {
                Task { await model.refresh(force: false) }
            }
            // Nicht versiegelt speicherbar (Schlüsselbund gerade nicht lesbar): Änderungen warten im Speicher — sagen,
            // nicht still (nie im Klartext geschrieben); das nächste Speichern bzw. die Rückkehr der App versucht es erneut
            .onChange(of: data.portfolioSavePending) { _, pending in
                if pending {
                    banner = WatchlistBannerMessage(text: L("portfolio_save_pending"), long: true)
                }
            }
            // Später aufgetauchte Überschneidung Adresse ↔ eigene Transaktionen: einmal automatisch «Nur anzeigen» — wie Android
            .onAppear { checkAddressOverlap() }
            .onChange(of: data.watchedAddresses) { checkAddressOverlap() }
            .onChange(of: transactions) { checkAddressOverlap() }
            // Bei offenem Adressen-Blatt erst danach (der Banner läge hinter dem Blatt)
            .onChange(of: addressesOpen) { showAddressOverlapNotice() }
    }

    /// Blätter, Ziele, Dialoge und Hinweise — eigene Funktion, damit der Compiler `body` in vertretbarer Zeit prüft.
    private func presentations<V: View>(_ base: V) -> some View {
        base
        .sheet(isPresented: $addressesOpen) {
            PortfolioAddressSheet(onPortfolioEnabled: { toast = ToastMessage(L("portfolio_enabled_banner")) })
                .environmentObject(data)
                .environment(\.appAccent, accent)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $costGapsOpen) {
            PortfolioCostGapsSheet { tx in openAfterSheet(tx) }
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
                // Merkliste gesperrt: nichts angelegt — sagen warum statt «gespeichert»
                guard data.addPortfolioAlarm(kind: kind, threshold: threshold, currency: data.settings.portfolioCurrency,
                                             repeating: repeating) else {
                    if data.alarmSaveBlocked {
                        banner = WatchlistBannerMessage(text: L("alarm_save_storage_locked"), kind: .error, long: true)
                    }
                    return
                }
                Haptics.play(.success)
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
                    toast = ToastMessage(L("portfolio_export_done"), kind: .success)
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
            let addresses = data.watchedAddresses
            let stored = data.portfolio.compactMap(PortfolioCsvImporter.storedMove)
            let report = csvLoad.begin(.loading)
            csvLoad.attach(Task {
                // Lesen und Zuordnen zu Ende führen, auch wenn die App währenddessen in den Hintergrund geht
                let backgroundTask = BackgroundTaskGuard.begin("csvImport")
                defer { backgroundTask.end() }
                let outcome = await PortfolioCsvImporter.load(url, existingKeys: keys, addresses: addresses,
                                                              stored: stored, progress: report)
                // «Abbrechen»: nichts zeigen, gespeichert war ohnehin nichts
                guard !Task.isCancelled else { return }
                csvLoad.end()
                switch outcome {
                case .success(let loaded):
                    csvPreview = loaded
                case .failure(let refusal):
                    csvRefusal = refusal
                    csvUnreadable = true
                }
            })
        }
        // Laden: Fortschritt mit «Abbrechen» über der Ansicht
        .overlay {
            if let phase = csvLoad.phase {
                CsvImportProgressOverlay(phase: phase, progress: csvLoad.progress) { csvLoad.cancel() }
            }
        }
        .sheet(item: $csvPreview) { loaded in
            PortfolioCsvImportSheet(loaded: loaded) { count, enabled in
                toast = ToastMessage(enabled ? L("portfolio_enabled_banner") : L("portfolio_csv_import_done", count: count),
                                     kind: enabled ? .info : .success)
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
            // Zu gross: eigener Text mit der Zeilengrenze (16 MB steht im Text), wie Android
            Text(csvRefusal == .tooLarge
                 ? L("portfolio_csv_import_too_large", LocaleNumbers.integer(CsvImportParser.maxRows, grouping: true))
                 : L("portfolio_csv_import_unreadable"))
        }
        .toast($toast)
        // Ungültige Einträge aus der Systemsicherung einmal nennen
        .systemRestoreNotice()
        .overlay(alignment: .bottom) {
            // Animation nur für den Banner, nicht für die Liste darunter
            WatchlistBanner(message: $banner) { _ in }
                .animation(reduceMotion ? nil : .spring(duration: 0.35), value: banner)
        }
        .haptic(.destructive, trigger: deleteTick)
        .confirmationDialog(L("portfolio_clear"), isPresented: $askClear, titleVisibility: .visible) {
            // Danach «Rückgängig» im Banner (alle Transaktionen mit ihren ids zurück) — wie Android
            Button(L("portfolio_clear"), role: .destructive) { clearAll() }
            Button(L("action_cancel"), role: .cancel) {}
        } message: {
            // Anzahl nennen; beobachtete Adressen bleiben (nur Transaktionen werden gelöscht) — wie Android
            Text(L("portfolio_clear_confirm_count", count: data.portfolio.count))
        }
        // Ganzen Coin löschen: erst nachfragen, mit der Zahl der Transaktionen — wie Android `DeleteCoinDialog`
        .alert(askDeleteCoin.map { L("portfolio_coin_delete_title", $0) } ?? "", isPresented: Binding(
            get: { askDeleteCoin != nil },
            set: { if !$0 { askDeleteCoin = nil } }
        ), presenting: askDeleteCoin) { coin in
            Button(L("action_delete"), role: .destructive) {
                askDeleteCoin = nil
                deleteCoin(coin)
            }
            Button(L("action_cancel"), role: .cancel) { askDeleteCoin = nil }
        } message: { coin in
            let refs = coinFeeRefCount(coin)
            let confirm = L("portfolio_coin_delete_confirm", count: coinTxCount(coin))
            Text(refs > 0 ? confirm + "\n\n" + L("portfolio_coin_delete_fee_refs", coin, refs) : confirm)
        }
    }
}
