import Accessibility
import SwiftUI

// Leiste oben der Portfolio-Seite (Teil von `PortfolioScreen`): Beträge verbergen, Umrechnung, Adressen, CSV-Import
// und das Menü ⋯ (Importe, Export, Alarm, alles löschen).
// Eigene Datei, weil Leiste und Menü für sich lesbar sein sollen.

extension PortfolioScreen {
    // MARK: Leiste oben

    @ToolbarContentBuilder
    func toolbarContent(hasTransactions: Bool, hasData: Bool, currency: String) -> some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            // «Beträge verbergen»: alle Beträge als «•••» (Prozente bleiben), auch im Widget
            if hasData {
                let hidden = data.settings.hidePortfolioAmounts
                Button {
                    data.settings.hidePortfolioAmounts.toggle()
                } label: {
                    Image(systemName: hidden ? "eye.slash" : "eye")
                }
                .accessibilityLabel(L(hidden ? "a11y_portfolio_show_amounts" : "portfolio_hide_amounts"))
                .sensoryFeedback(.selection, trigger: hidden)
            }
            Menu {
                // Gleicher Anfang wie Merkliste und Markt: App (→ «Über»), Aktualisieren
                AppMenuHead(
                    refreshing: model.refreshing,
                    onOpenAbout: { showAbout = true },
                    onRefresh: { Task { await model.refresh(force: true) } }
                )
                Divider()
                // «Umrechnen in: CHF» — Auswahl der Zielwährung
                Picker(selection: Binding(
                    get: { data.settings.portfolioCurrency },
                    set: { data.settings.portfolioCurrency = $0 }
                )) {
                    ForEach(FxRateSource.currencies, id: \.self) { code in
                        Text(code).tag(code)
                    }
                } label: {
                    Label(L("portfolio_convert_to_value", currency), systemImage: "arrow.left.arrow.right")
                }
                .pickerStyle(.menu)
                // Beobachtete Adressen (Bestand ohne Kaufpreis)
                Button {
                    addressesOpen = true
                } label: {
                    Label(L("portfolio_addresses_title"), systemImage: "wallet.pass")
                }
                // CSV-Export von Relai/Swissquote als Transaktionen übernehmen (Vorschau vor dem Anlegen)
                Button {
                    csvPickerOpen = true
                } label: {
                    // Zweiter Text = Untertitel im Menü: die unterstützten Quellen — wie Android
                    Text(L("portfolio_csv_import_action"))
                    Text(PortfolioCsvImporter.sources)
                    Image(systemName: "square.and.arrow.down")
                }
                // Importläufe (nur mit importierten Transaktionen): «Rückgängig» je Lauf
                if !data.portfolioImportBatches.isEmpty {
                    Button {
                        importsOpen = true
                    } label: {
                        Label(L("portfolio_imports_title"), systemImage: "clock.arrow.circlepath")
                    }
                    .accessibilityIdentifier(UiTestTags.portfolioImports)
                }
                if hasTransactions {
                    // Stichtag-Export (z. B. Bestand per 31.12. für die Steuererklärung)
                    Button {
                        exportOpen = true
                    } label: {
                        Label(L("portfolio_export_action"), systemImage: "doc.text")
                    }
                }
                if hasData {
                    // Alarm «Portfolio-Wert»
                    Button {
                        alarmOpen = true
                    } label: {
                        Label(L("portfolio_alarm_action"), systemImage: "bell.and.waves.left.and.right")
                    }
                    Divider()
                    // Wie «Merkliste leeren»: zuunterst, rot, mit Rückfrage
                    Button(role: .destructive) {
                        askClear = true
                    } label: {
                        Label(L("portfolio_clear"), systemImage: "trash")
                    }
                }
            } label: {
                // Läuft das Aktualisieren, dreht sich hier ein Kreis (der Knopf oben ist weg)
                if model.refreshing {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: "ellipsis.circle")
                }
            }
            .accessibilityLabel(L("action_more"))
            .accessibilityIdentifier(UiTestTags.portfolioMenu)
        }
    }
}
