import SwiftUI
import UIKit

/// Portfolio als eigener Tab und die Portfolio-Sperre (nur mit eingeschaltetem Portfolio).
@MainActor
struct PortfolioSettingsPage: View {
    @EnvironmentObject private var data: AppData
    @State private var toast: String?

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
                    RowDivider()
                    // «Beträge verbergen»: wie das Auge im Portfolio-Kopf (Portfolio und Widget)
                    SwitchRow(
                        title: L("portfolio_hide_amounts"),
                        subtitle: L("portfolio_hide_amounts_hint"),
                        isOn: $data.settings.hidePortfolioAmounts
                    )
                    .settingsAnchor("portfolio.hide")
                }
            }
            // Ehrlich: Die Sperre schützt die Anzeige; die Daten schützt die Geräteverschlüsselung
            if PortfolioLockPolicy.showSetting(portfolioEnabled: data.settings.portfolioEnabled) {
                SettingsHint(text: L("settings_portfolio_lock_footer"), top: 2)
                    .padding(.horizontal, 16)
            }
        }
        .toast($toast)
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
}
