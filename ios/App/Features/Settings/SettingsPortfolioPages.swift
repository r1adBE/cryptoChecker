import SwiftUI
import UIKit

/// Portfolio als eigener Tab, die Portfolio-Sperre (nur mit eingeschaltetem Portfolio) und die Einstandsmethode.
@MainActor
struct PortfolioSettingsPage: View {
    @EnvironmentObject private var data: AppData
    @State private var toast: String?
    /// Build 46: Gerätecode eingerichtet? Ohne hebt sich die Sperre selbst auf — ehrlich darunter sagen (wie Android).
    @State private var deviceSecure = AppLock.canAuthenticate()
    @Environment(\.scenePhase) private var scenePhase

    init() {}

    var body: some View {
        SettingsCardsPage(title: L("portfolio_title")) {
            SettingsCard {
                SwitchRow(
                    title: L("settings_portfolio_tab"),
                    subtitle: L("portfolio_setting_hint"),
                    isOn: $data.settings.portfolioEnabled
                )
                .settingsAnchor("portfolio.tab")
                // Ausgeblendet ohne Portfolio; der Wert bleibt erhalten (Widget und Sicherung bleiben geschützt)
                if PortfolioLockPolicy.showSetting(portfolioEnabled: data.settings.portfolioEnabled) {
                    RowDivider()
                    SwitchRow(
                        title: L("settings_portfolio_lock"),
                        subtitle: L("settings_portfolio_lock_hint"),
                        isOn: Binding(
                            get: { data.settings.appLock },
                            set: { on in setAppLock(on) }
                        )
                    )
                    .settingsAnchor("portfolio.lock")
                    if !deviceSecure {
                        Text(L("settings_portfolio_lock_no_device_lock"))
                            .font(.footnote)
                            .foregroundStyle(AppColors.warningText)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.bottom, Spacing.sm)
                    }
                    RowDivider()
                    // «Beträge verbergen»: wie das Auge im Portfolio-Kopf (Portfolio und Widget)
                    SwitchRow(
                        title: L("portfolio_hide_amounts"),
                        subtitle: L("portfolio_hide_amounts_hint"),
                        isOn: $data.settings.hidePortfolioAmounts
                    )
                    .settingsAnchor("portfolio.hide")
                }
                // Build 46: Methode und Steuer-Vorlage erst mit eingeschaltetem Portfolio — wie Android
                if data.settings.portfolioEnabled {
                RowDivider()
                // Einstandsmethode für Einstand, «Seit Kauf», realisiert und Steuer-Export (Standard FIFO) — wie Android
                // Drei Zeilen untereinander (Titel + Hinweis) statt Chips — wie Android `RadioRow`
                VStack(alignment: .leading, spacing: 0) {
                    Text(L("settings_cost_method"))
                        .font(.body)
                        .padding(.bottom, 4)
                        .accessibilityAddTraits(.isHeader)
                    ForEach(CostMethod.allCases, id: \.self) { method in
                        SettingsChoiceRow(
                            title: L(Self.costMethodLabelKey(method)),
                            subtitle: L(Self.costMethodHintKey(method)),
                            selected: data.settings.costMethod == method
                        ) { data.settings.costMethod = method }
                    }
                }
                .padding(.vertical, Spacing.sm)
                .settingsAnchor("portfolio.cost_method")
                RowDivider()
                // Steuer-Vorlage der Arbeitsunterlage (Standard Neutral); die Region wird nur vorgeschlagen — wie Android
                TaxTemplateSettingRow(selected: $data.settings.taxTemplate, costMethod: data.settings.costMethod,
                                      confirmed: $data.settings.taxTemplateConfirmed)
                    .settingsAnchor("portfolio.tax_template")
                }
                RowDivider()
                // Immer sichtbar: Die Daten bleiben auch bei ausgeblendetem Portfolio gespeichert
                SwitchRow(
                    title: L("settings_portfolio_system_backup"),
                    subtitle: L("settings_portfolio_system_backup_hint"),
                    isOn: Binding(
                        get: { data.settings.portfolioSystemBackup },
                        set: { on in setPortfolioSystemBackup(on) }
                    )
                )
                .settingsAnchor("portfolio.system_backup")
            }
            // Ehrlich: Die Sperre schützt die Anzeige; die Daten schützt die Geräteverschlüsselung
            if PortfolioLockPolicy.showSetting(portfolioEnabled: data.settings.portfolioEnabled) {
                SettingsHint(text: L("settings_portfolio_lock_footer"), top: 2)
                    .padding(.horizontal, 16)
            }
        }
        .toast($toast)
        // Zurück aus den Systemeinstellungen: Gerätecode neu prüfen
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { deviceSecure = AppLock.canAuthenticate() }
        }
        .onAppear { deviceSecure = AppLock.canAuthenticate() }
    }

    /// «Portfolio in Systemsicherung»: Einschalten verlangt Entsperren, solange das Portfolio gesperrt ist
    /// (sonst gelangten die Trades über das Geräte-Backup an der Sperre vorbei); Ausschalten geht immer.
    private func setPortfolioSystemBackup(_ on: Bool) {
        Task {
            let open = await AppLock.shared.requireUnlock { locked in
                PortfolioLockPolicy.systemBackupNeedsUnlock(locked: locked, enabling: on)
            }
            if open { data.settings.portfolioSystemBackup = on }
        }
    }

    /// Einschalten erst nach einmaligem Entsperren; ohne Displaysperre bleibt es aus.
    /// Ausschalten verlangt Entsperren, solange das Portfolio gesperrt ist (sonst wäre die
    /// Sperre hier zu umgehen).
    private func setAppLock(_ on: Bool) {
        guard on else {
            Task {
                let open = await AppLock.shared.requireUnlock(PortfolioLockPolicy.disableNeedsUnlock(locked:))
                if open { data.settings.appLock = false }
            }
            return
        }
        guard AppLock.canAuthenticate() else {
            UINotificationFeedbackGenerator().notificationOccurred(.error)
            toast = L("app_lock_unavailable")
            return
        }
        Task {
            let ok = await AppLock.authenticate()
            if ok { data.settings.appLock = true }
        }
    }

    /// Text der Einstandsmethode — wie Android `SettingsPortfolioPages`.
    static func costMethodLabelKey(_ method: CostMethod) -> String {
        switch method {
        case .FIFO: return "settings_cost_method_fifo"
        case .FIFO_WALLET: return "settings_cost_method_fifo_wallet"
        case .AVERAGE: return "settings_cost_method_average"
        }
    }

    /// Hinweis (eine Zeile unter dem Titel) zu einer Einstandsmethode.
    static func costMethodHintKey(_ method: CostMethod) -> String {
        switch method {
        case .FIFO: return "settings_cost_method_fifo_hint"
        case .FIFO_WALLET: return "settings_cost_method_fifo_wallet_hint"
        case .AVERAGE: return "settings_cost_method_average_hint"
        }
    }
}

/// Steuer-Vorlage: aktuelle Wahl (Menü aller Vorlagen), Hinweis «nur Orientierung» und — wenn die Region des Geräts eine
/// andere Vorlage nahelegt — ein Vorschlag mit Knopf (nie still übernommen). Wie `TaxTemplateRow` (Android).
@MainActor
struct TaxTemplateSettingRow: View {
    @Binding var selected: TaxTemplate
    /// Einstandsmethode der App: verlangt die Vorlage eine andere, steht ein Hinweis darunter (wie Android).
    var costMethod: CostMethod = .defaultMethod
    /// Hinweis zu den Länder-Vorlagen (Beta) bestätigt; sonst Dialog mit OK beim ersten Wählen (wie Android).
    @Binding var confirmed: Bool
    @State private var confirmDismissed = false

    var body: some View {
        let suggestion = TaxTemplate.suggest(Locale.current.region?.identifier ?? "")
        VStack(alignment: .leading, spacing: 4) {
            Text(L("tax_template_title"))
                .font(.body)
                .accessibilityAddTraits(.isHeader)
            Menu {
                ForEach(TaxTemplate.allCases, id: \.self) { t in
                    Button(L(t.labelKey)) { selected = t }
                }
            } label: {
                Text(L(selected.labelKey))
                    .font(.body)
                    .foregroundStyle(Color.accentColor)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            SettingsHint(text: L("tax_template_hint"), top: 0)
            // Vorlage verlangt eine andere Methode als die App-Einstellung: der Export rechnet mit der der Vorlage
            if selected.methodOverridden(costMethod) {
                SettingsHint(text: L("tax_export_method_overridden", selected.methodFor(costMethod).label,
                                     TaxMethod.of(costMethod).label), top: 2)
            }
            if suggestion != .NEUTRAL && suggestion != selected {
                let name = L(suggestion.labelKey)
                SettingsHint(text: L("tax_template_suggestion", name), top: 2)
                Button(L("tax_template_use", name)) { selected = suggestion }
                    .font(.footnote)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, Spacing.sm)
        .alert(L("tax_template_confirm_title"), isPresented: Binding(
            get: { selected != .NEUTRAL && !confirmed && !confirmDismissed },
            set: { if !$0 { confirmDismissed = true } }
        )) {
            Button(L("ios_action_ok")) { confirmed = true }
        } message: {
            Text(L("tax_template_confirm_text"))
        }
        .onChange(of: selected) { _, _ in confirmDismissed = false }
    }
}

extension TaxTemplate {
    /// Text-Schlüssel des Namens, z. B. «tax_template_uk» — wie `taxTemplateLabel` (Android).
    var labelKey: String { "tax_template_" + rawValue.lowercased() }
}
