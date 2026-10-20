import SwiftUI
import UIKit

// Vertragsadresse des Tokens im Kopf des Aktionsblatts (nur DEX-Pools), gekürzt, Tippen kopiert sie ganz.
// Eigene Datei wie `WatchSheetDexAddress.kt`; der Kopf (`WatchActionsHeader.swift`) setzt sie unter die Börse.

/// «0x1234…abcd» unter der Börse eines DEX-Pools (`DexTokenAddress`); Tippen legt die volle Adresse in die
/// Zwischenablage. Ohne gemerkte Adresse (Paar vor Build 91 hinzugefügt) nichts.
struct DexTokenAddressLine: View {
    let watch: Watch

    var body: some View {
        if DexLiquidity.isDex(watch.marketKey), let address = DexTokenAddressStore.get(watch.pairId) {
            Button {
                UIPasteboard.general.string = address
                WatchlistHaptics.selection()
            } label: {
                Text(BidiText.isolate(DexTokenAddress.shown(address)))
                    .font(.caption)
                    .foregroundStyle(.tint)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(L("dex_token_address_copy"))
        }
    }
}
