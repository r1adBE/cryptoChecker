import Accessibility
import SwiftUI

/// Ein Coin: Kennzahlen und seine Transaktionen — wie `PortfolioDetailScreen.kt`.
/// Tippen bearbeitet; nach links wischen löscht sofort, mit «Rückgängig» im Banner (wie in der
/// Merkliste). «+» wählt den Coin schon vor. Ist die letzte Transaktion weg (und «Rückgängig»
/// vorbei), geht es zurück.
@MainActor
struct PortfolioCoinDetail: View {
    let coin: String

    @EnvironmentObject private var data: AppData
    @ObservedObject private var model = PortfolioModel.shared
    @Environment(\.appAccent) private var accent
    @Environment(\.dismiss) private var dismiss

    @State private var sheet: PortfolioTxDraft?
    /// «ohne Kaufpreis» → Einträge ohne Einstand dieses Coins; «Aus Adresse» → Einstand für Adressguthaben.
    @State private var costGapsOpen = false
    @State private var lotsFor: WatchedAddress?
    @State private var banner: WatchlistBannerMessage?
    @State private var deleteTick = 0
    /// Im Hintergrund gerechnet (`PortfolioModel.coinLedger`); bis zum neuen Ergebnis bleibt das letzte.
    @State private var ledger: CoinLedger?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver

    init(coin: String) {
        self.coin = PortfolioCalculator.normalizeCoin(coin)
    }

    var body: some View {
        let all = data.portfolio
        // Auch Tausche in diesen Coin («Tausch von BTC»)
        let own = all.filter { tx in tx.coins.contains { PortfolioCalculator.normalizeCoin($0) == coin } }
        // Mit Adress-Guthaben (eigener Teil ohne Einstand) — wie die Übersicht; dazu offene Posten und (FIFO je Wallet)
        // Abgänge über den Bestand ihrer Wallet. Gebühr-Zeilen zum Tageskurs wie auf der Karte (`PortfolioModel.feePrice`);
        // abseits des Hauptthreads (`.task(id: ledgerKey)`)
        let ledgerKey = model.coinLedgerKey(coin)
        let fromAddress = addressAmount
        List {
            if let ledger {
                PortfolioPositionCard(position: ledger.position, addressAmount: fromAddress, overlap: !own.isEmpty,
                                      matches: WatchedAddressRules.transferMatches(coin: coin, addresses: data.watchedAddresses,
                                                                                   txs: own),
                                      onMissingCost: { costGapsOpen = true },
                                      onAddressCost: lotAddress.map { a -> () -> Void in { lotsFor = a } })
                    .portfolioListRow(top: 4, bottom: 4)
            } else {
                // Erste Rechnung läuft noch (Bruchteil einer Sekunde): Platzhalter wie Android `SkeletonList`
                ProgressView()
                    .frame(maxWidth: .infinity, minHeight: 160)
                    .portfolioListRow(top: 4, bottom: 4)
            }

            // FIFO je Wallet: Abgang über den Bestand seiner Wallet — antippen öffnet die Transaktion
            if let ledger, !ledger.walletShortfalls.isEmpty {
                PortfolioWalletShortfallBanner(shortfalls: ledger.walletShortfalls) { id in
                    if let tx = all.first(where: { $0.id == id }) { sheet = PortfolioTxDraft(tx: tx) }
                }
                .portfolioListRow(top: 4, bottom: 4)
            }
            // Offene Kauf-Posten (zugeklappt)
            if let ledger, !ledger.openLots.isEmpty {
                PortfolioOpenLotsSection(lots: ledger.openLots, coin: coin)
                    .portfolioListRow(top: 4, bottom: 4)
            }

            Text(L("portfolio_transactions"))
                .sectionTitleStyle()
                .accessibilityAddTraits(.isHeader)
                .padding(.leading, 4)
                .portfolioListRow(top: 12, bottom: 2)

            ForEach(own) { tx in
                Button {
                    sheet = PortfolioTxDraft(tx: tx)
                } label: {
                    PortfolioTxRow(tx: tx, coin: coin)
                }
                .buttonStyle(.plain)
                // VoiceOver: Löschen wie nach links wischen (mit «Rückgängig»)
                .accessibilityActions {
                    Button(L("action_delete")) { deleteTx(tx) }
                }
                // Nach links wischen = löschen ohne Rückfrage (Rückgängig im Banner)
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    if !voiceOver {
                        Button(role: .destructive) { deleteTx(tx) } label: {
                            Label(L("action_delete"), systemImage: "trash")
                        }
                        // Systemrot: weisse Schrift bleibt auch im Dunkelmodus lesbar
                        .tint(AppColors.destructive)
                    }
                }
                .portfolioListRow(top: 4, bottom: 4)
            }

            PortfolioDisclaimer()
                .portfolioListRow(top: 0, bottom: 0)
            // Platz für den «+»-Knopf
            Color.clear.frame(height: Spacing.fabClearance).portfolioListRow(top: 0, bottom: 0)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        // «Beträge verbergen» (Einstellung bzw. Auge im Portfolio-Kopf)
        .environment(\.hidePortfolioAmounts, data.settings.hidePortfolioAmounts)
        // iPad/Querformat: Zeilen höchstens 640 pt breit, mittig
        .readableListMargins()
        .background(AppColors.background.ignoresSafeArea())
        .refreshable { await model.refresh(force: true) }
        .animation(.spring(duration: 0.35), value: own.map(\.id))
        .overlay(alignment: .bottomTrailing) {
            PortfolioAddButton { sheet = PortfolioTxDraft(coin: coin) }
        }
        .navigationTitle(coin)
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.refresh(force: false) }
        .task(id: ledgerKey) {
            let result = await model.coinLedger(ledgerKey)
            if !Task.isCancelled { ledger = result }
        }
        // Letzte Transaktion gelöscht: zurück zur Übersicht — erst wenn «Rückgängig» vorbei ist
        // (Coin nur aus einer Adresse bleibt offen)
        .onChange(of: own.isEmpty && fromAddress == nil && banner == nil, initial: true) { _, leave in
            if leave { dismiss() }
        }
        .overlay(alignment: .bottom) {
            WatchlistBanner(message: $banner) { _ in }
                .animation(reduceMotion ? nil : .spring(duration: 0.35), value: banner)
        }
        .haptic(.destructive, trigger: deleteTick)
        .sheet(isPresented: $costGapsOpen) {
            PortfolioCostGapsSheet(coin: coin) { tx in
                costGapsOpen = false
                Task {
                    try? await Task.sleep(nanoseconds: 450_000_000)
                    sheet = PortfolioTxDraft(tx: tx)
                }
            }
            .environmentObject(data)
            .presentationDetents([.large])
        }
        .sheet(item: $lotsFor) { a in
            PortfolioAddressLotsSheet(addressId: a.id)
                .environmentObject(data)
                .presentationDetents([.large])
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
    }

    /// Erste zählende Adresse mit Guthaben in diesem Coin (Einstand für Adressguthaben); nil = keine.
    private var lotAddress: WatchedAddress? {
        AddressCostLots.counted(data.watchedAddresses).first { ($0.balances[coin] ?? 0) > 0 }?.address
    }

    /// Guthaben beobachteter Adressen für diesen Coin (> 0); nil = keins.
    private var addressAmount: Decimal? {
        AddressBalance.amount(for: coin, addresses: data.watchedAddresses, realTrades: data.portfolio).flatMap { $0 > 0 ? $0 : nil }
    }

    /// Nach links gewischt oder VoiceOver «Löschen»: sofort löschen, Banner mit «Rückgängig».
    private func deleteTx(_ tx: PortfolioTx) {
        let text = L("portfolio_tx_removed")
        // Banner zuerst: sonst ginge die Ansicht bei der letzten Transaktion gleich zurück
        banner = WatchlistBannerMessage(
            text: text,
            action: WatchlistBannerAction.undo {
                Task { @MainActor in
                    let saved = await data.restorePortfolioTxs([tx], present: portfolioSpring(reduceMotion))
                    if !saved { banner = .portfolioNotWritten(data) }
                }
            }
        )
        Task { @MainActor in
            let deleted = await data.deletePortfolioTx(tx.id, present: portfolioSpring(reduceMotion))
            // Nicht geschrieben: Transaktion bleibt, statt «entfernt / Rückgängig» der Hinweis
            guard deleted else {
                banner = .portfolioNotWritten(data)
                return
            }
            deleteTick += 1
            try? await Task.sleep(nanoseconds: 300_000_000)
            AccessibilityNotification.Announcement(text).post()
        }
    }
}

// MARK: Offene Posten, Wallet-Fehlbestand

/// FIFO je Wallet: «Bestand in Binance reicht nicht – fehlt eine Umbuchung?» je Wallet (erster Abgang); antippen öffnet die
/// betroffene Transaktion — wie `WalletShortfallBanner` (Android).
private struct PortfolioWalletShortfallBanner: View {
    let shortfalls: [PortfolioWalletShortfall]
    let onOpen: (Int64) -> Void

    var body: some View {
        var seen = Set<String>()
        let firsts = shortfalls.filter { seen.insert($0.wallet.trimmingCharacters(in: .whitespaces).lowercased()).inserted }
        return VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(firsts.enumerated()), id: \.offset) { _, s in
                Button {
                    onOpen(s.txId)
                } label: {
                    HStack(spacing: Spacing.sm) {
                        Image(systemName: "exclamationmark.triangle.fill")
                        Text(L("portfolio_wallet_shortfall",
                               s.wallet.trimmingCharacters(in: .whitespaces).isEmpty ? L("tax_export_wallet_unspecified") : s.wallet))
                            .font(.subheadline)
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 0)
                        Image(systemName: "chevron.forward")
                            .font(.caption.weight(.semibold))
                    }
                    .foregroundStyle(AppColors.error)
                    .padding(.horizontal, 16)
                    .padding(.vertical, Spacing.md)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .background(AppColors.error.opacity(0.10), in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
    }
}

extension View {
    /// Listenzeile ohne Trenner und Hintergrund, mit Kartenabstand.
    func portfolioListRow(top: CGFloat, bottom: CGFloat) -> some View {
        self
            .listRowInsets(EdgeInsets(top: top, leading: 16, bottom: bottom, trailing: 16))
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
    }
}
