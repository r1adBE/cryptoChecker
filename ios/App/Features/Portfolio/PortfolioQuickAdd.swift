import SwiftUI

// Erfassen-Blatt aus der Merkliste bzw. dem Markt (Coin und Kurs vorbelegt) samt Rückfrage «Portfolio-Tab
// einschalten?». Eigene Datei, weil sie das Transaktions-Blatt nur einbettet (wie `PortfolioQuickAdd.kt`).

/// Erfassen-Blatt aus der Merkliste bzw. dem Markt (Coin und Kurs vorbelegt) — wie `PortfolioQuickAddSheet` (Android).
/// Die erste Transaktion mit ausgeschaltetem Portfolio schaltet es nicht still ein; nach dem Schliessen
/// des Blatts fragt «Portfolio-Tab einschalten?»: Einschalten → `onEnabled`; Später → Transaktion bleibt, Tab
/// bleibt aus, einmal der Weg dorthin (`onLater`).
@MainActor
struct PortfolioQuickAddPresenter: ViewModifier {
    @Binding var draft: PortfolioTxDraft?
    let onEnabled: () -> Void
    let onLater: () -> Void
    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent
    /// Rückfrage gemeldet; das Dialogfeld erscheint erst, wenn das Blatt zu ist.
    @State private var pendingAsk = false
    @State private var askEnable = false

    func body(content: Content) -> some View {
        content
            .sheet(item: $draft, onDismiss: {
                if pendingAsk {
                    pendingAsk = false
                    askEnable = true
                }
            }) { item in
                PortfolioTxSheet(initial: item, onAskEnablePortfolio: { pendingAsk = true })
                    .environmentObject(data)
                    .environment(\.appAccent, accent)
                    .presentationDetents([.large])
                    .presentationDragIndicator(.visible)
                    .presentationCornerRadius(28)
                    .presentationBackground(AppColors.background)
            }
            .alert(L("portfolio_enable_ask_title"), isPresented: $askEnable) {
                Button(L("alarms_notifications_turn_on")) {
                    data.settings.portfolioEnabled = true
                    onEnabled()
                }
                Button(L("battery_later"), role: .cancel) { onLater() }
            } message: {
                Text(L("portfolio_enable_ask_text"))
            }
    }
}
