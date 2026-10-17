import Accessibility
import SwiftUI

// Abläufe der Portfolio-Seite (Teil von `PortfolioScreen`): Meldung nach gespeichertem Alarm, Coin löschen und
// Import zurücknehmen mit «Rückgängig», Hinweise zur Überschneidung beobachteter Adressen.
// Eigene Datei, weil die Seite selbst nur den Aufbau regelt.

extension PortfolioScreen {
    /// «Alarm gespeichert: Portfolio über 50’000.00 CHF · einmalig»; Mitteilungen aus → « · Mitteilungen
    /// aus» mit «Einstellungen» — wie Android.
    func showSavedAlarm(_ alarm: PortfolioAlarm) {
        let condition = PortfolioAlarmTexts.sentence(alarm, basis: data.settings.changeBasis.storage,
                                                     hidden: data.settings.hidePortfolioAmounts)
        Task { @MainActor in
            let off = await Notifier.isDenied()
            banner = WatchlistBannerMessage(
                text: AlarmSavedText.text(condition: condition, repeating: alarm.repeating, notificationsOff: off),
                icon: off ? "bell.slash.fill" : "bell.badge.fill",
                action: off ? WatchlistBannerAction(title: L("settings_title"), perform: { NotificationsOffBanner.openSettings() }) : nil
            )
        }
    }

    /// Nach links gewischt oder VoiceOver «Löschen»: alle Transaktionen des Coins weg, Banner
    /// mit «Rückgängig» und Ansage — wie `swipeDelete` in der Merkliste.
    func deleteCoin(_ coin: String) {
        var removed: [PortfolioTx] = []
        withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { removed = data.deletePortfolioCoin(coin) }
        guard !removed.isEmpty else { return }
        deleteTick += 1
        let restore = removed
        let text = L("watchlist_removed", coin)
        banner = WatchlistBannerMessage(
            text: text,
            icon: "trash",
            action: WatchlistBannerAction(title: L("action_undo")) {
                withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { data.restorePortfolioTxs(restore) }
            }
        )
        Task { @MainActor in
            // Nach dem Fokuswechsel ansagen, sonst geht es unter
            try? await Task.sleep(nanoseconds: 300_000_000)
            AccessibilityNotification.Announcement(text).post()
        }
    }

    /// Adressen mit später aufgetauchter Überschneidung einmal automatisch auf «Nur anzeigen» (`AppData`), dann Hinweis.
    func checkAddressOverlap() {
        // Alte Entscheide einmal übernehmen (ohne passende Menge: zählt wieder), dann die Mengenregel
        let restored = data.migrateWatchedAddressOverlap(txs: data.portfolio)
        let switched = data.applyWatchedAddressOverlapShowOnly(txs: data.portfolio)
        guard !switched.isEmpty || !restored.isEmpty else { return }
        overlapSwitched += switched
        overlapRestored += restored
        showAddressOverlapNotice()
    }

    /// Hinweis «… wird jetzt nur angezeigt» mit «Rückgängig» (zählt wieder, gilt als entschieden) — wie Android.
    func showAddressOverlapNotice() {
        guard !addressesOpen else { return }
        if overlapSwitched.isEmpty && !overlapRestored.isEmpty {
            showAddressOverlapRestored()
            return
        }
        guard !overlapSwitched.isEmpty else { return }
        let list = overlapSwitched
        overlapSwitched = []
        let own = Set(data.portfolio.map { PortfolioCalculator.normalizeCoin($0.coin) })
        let names: [String] = list.map { $0.label ?? WatchedAddressRules.shortAddress($0.address) }
        var coins: [String] = []
        for a in list {
            for coin in WatchedAddressRules.overlap(a, ownCoins: own) where !coins.contains(coin) { coins.append(coin) }
        }
        let text = L("portfolio_address_overlap_auto", names.joined(separator: ", "), coins.joined(separator: ", "))
        let ids: [Int64] = list.map(\.id)
        banner = WatchlistBannerMessage(
            text: text,
            icon: "eye",
            action: WatchlistBannerAction(title: L("action_undo")) {
                for id in ids { data.setWatchedAddressCountInPortfolio(id, on: true) }
            }
        )
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 300_000_000)
            AccessibilityNotification.Announcement(text).post()
        }
    }

    /// Einmal: früher automatisch «Nur anzeigen» ohne passende Menge zählt wieder — Hinweis mit «Rückgängig» (wieder «Nur
    /// anzeigen», gilt als entschieden). Wie Android.
    private func showAddressOverlapRestored() {
        let list = overlapRestored
        overlapRestored = []
        let names: [String] = list.map { $0.label ?? WatchedAddressRules.shortAddress($0.address) }
        let text = L("portfolio_address_overlap_restored", names.joined(separator: ", "))
        let ids: [Int64] = list.map(\.id)
        banner = WatchlistBannerMessage(
            text: text,
            icon: "eye",
            action: WatchlistBannerAction(title: L("action_undo")) {
                for id in ids { data.setWatchedAddressCountInPortfolio(id, on: false) }
            }
        )
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 300_000_000)
            AccessibilityNotification.Announcement(text).post()
        }
    }

    /// «Import rückgängig»: Lauf löschen, Banner mit «Rückgängig» (stellt die Transaktionen wieder her) — wie Android.
    func undoImport(_ batch: PortfolioImportBatchInfo) {
        var removed: [PortfolioTx] = []
        withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { removed = data.undoPortfolioImport(batch.id) }
        guard !removed.isEmpty else { return }
        deleteTick += 1
        let restore = removed
        let text = L("portfolio_import_undone", count: removed.count)
        banner = WatchlistBannerMessage(
            text: text,
            icon: "arrow.uturn.backward",
            action: WatchlistBannerAction(title: L("action_undo")) {
                withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { data.restorePortfolioTxs(restore) }
            }
        )
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 300_000_000)
            AccessibilityNotification.Announcement(text).post()
        }
    }
}
