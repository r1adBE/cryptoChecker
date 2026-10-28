import SwiftUI
import UIKit
import UniformTypeIdentifiers

// Vertragsadresse des Tokens im Kopf des Aktionsblatts (nur DEX-Pools), gekürzt, Tippen kopiert sie ganz.
// Eigene Datei wie `WatchSheetDexAddress.kt`; der Kopf (`WatchActionsHeader.swift`) setzt sie unter die Börse.

/// «0x1234…abcd» unter der Börse eines DEX-Pools (`DexTokenAddress`); Tippen legt die volle Adresse in die
/// Zwischenablage. Ohne gemerkte Adresse (Paar mit einer älteren Version hinzugefügt) nichts.
struct DexTokenAddressLine: View {
    let watch: Watch
    /// Kurz «kopiert» statt der Adresse: iOS bestätigt das Kopieren nicht selbst (Android 13+ schon).
    @State private var copied = false

    var body: some View {
        if DexLiquidity.isDex(watch.exchangeKey), let address = DexTokenAddressStore.get(watch.pairId) {
            Button {
                // Nur auf diesem Gerät (keine universelle Zwischenablage) und nach 60 s wieder weg (wie
                // Android markiert die Adresse als vertraulich)
                UIPasteboard.general.setItems([[UTType.plainText.identifier: address]],
                                              options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(60)])
                Haptics.play(.success)
                confirmCopy()
            } label: {
                Text(copied ? L("dex_token_address_copied") : BidiText.isolate(DexTokenAddress.shown(address)))
                    .font(.caption)
                    .foregroundStyle(.tint)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            // VoiceOver: statt «0 x 1 2 3 4 …» die letzten vier Zeichen einzeln — reicht zum Wiedererkennen
            .accessibilityLabel(L("dex_token_address_label", Self.spokenTail(address)))
            .accessibilityHint(L("dex_token_address_copy"))
        }
    }

    /// Bestätigung sichtbar (kurz) und für VoiceOver angesagt.
    private func confirmCopy() {
        AccessibilityNotification.Announcement(L("dex_token_address_copied")).post()
        copied = true
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            copied = false
        }
    }

    /// Letzte vier Zeichen der Adresse, einzeln gesprochen («a b c d»).
    nonisolated static func spokenTail(_ address: String) -> String {
        address.trimmingCharacters(in: .whitespacesAndNewlines).suffix(4).map(String.init).joined(separator: " ")
    }
}
