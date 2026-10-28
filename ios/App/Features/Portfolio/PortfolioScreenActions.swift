import Accessibility
import SwiftUI

// Abläufe der Portfolio-Seite (Teil von `PortfolioScreen`): Meldung nach gespeichertem Alarm, Coin löschen, Portfolio
// leeren und Import zurücknehmen mit «Rückgängig», Hinweise zur Überschneidung beobachteter Adressen.
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
                // Mitteilungen aus: Info statt Erfolg (gespeichert, kommt so aber nicht an) — wie Android
                kind: off ? .info : .success,
                action: off ? WatchlistBannerAction(title: L("settings_title"), perform: { NotificationsOffBanner.openSettings() }) : nil
            )
        }
    }

    /// Zahl der Transaktionen, die mit dem Coin gelöscht würden (Tausch zählt bei beiden Coins) — aus den geladenen
    /// Daten, gleiche Regel wie `AppData.deletePortfolioCoin` und die Zahl in der Zeile.
    func coinTxCount(_ coin: String) -> Int {
        let key = PortfolioCalculator.normalizeCoin(coin)
        return data.portfolio.filter { PortfolioEdits.involves($0, key) }.count
    }

    /// Transaktionen anderer Coins, die beim Löschen ihre Gebühr in `coin` verlieren (`PortfolioEdits.paysFee`).
    func coinFeeRefCount(_ coin: String) -> Int {
        let key = PortfolioCalculator.normalizeCoin(coin)
        return data.portfolio.filter { PortfolioEdits.paysFee($0, in: key) }.count
    }

    /// Nach Rückfrage (gewischt oder VoiceOver «Löschen»): alle Transaktionen des Coins weg, Banner
    /// mit Coin und Zahl, «Rückgängig» und Ansage — wie Android `PortfolioScreen`.
    func deleteCoin(_ coin: String) {
        var removed: [PortfolioTx] = []
        let expected = coinTxCount(coin)
        withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { removed = data.deletePortfolioCoin(coin) }
        guard !removed.isEmpty else {
            // Nicht geschrieben (gesperrte Datei): kein «Coin entfernt», sondern sagen, dass nichts gespeichert wird
            if expected > 0 { banner = .portfolioNotWritten(data) }
            return
        }
        deleteTick += 1
        let restore = removed
        // Zurück kommen auch die Transaktionen, die nur ihre Gebühr in diesem Coin verloren; gezählt werden die gelöschten
        let key = PortfolioCalculator.normalizeCoin(coin)
        let count = removed.filter { PortfolioEdits.involves($0, key) }.count
        let text = L("portfolio_coin_removed", count: count, coin, count)
        banner = WatchlistBannerMessage(
            text: text,
            action: WatchlistBannerAction.undo {
                restoreOrWarn(restore)
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
        let switched = data.applyWatchedAddressOverlapShowOnly(txs: data.portfolio)
        guard !switched.isEmpty else { return }
        overlapSwitched += switched
        showAddressOverlapNotice()
    }

    /// Hinweis «… wird jetzt nur angezeigt» mit «Rückgängig» (zählt wieder, gilt als entschieden) — wie Android.
    func showAddressOverlapNotice() {
        guard !addressesOpen, !overlapSwitched.isEmpty else { return }
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
            action: WatchlistBannerAction.undo {
                for id in ids { data.setWatchedAddressCountInPortfolio(id, on: true) }
            }
        )
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 300_000_000)
            AccessibilityNotification.Announcement(text).post()
        }
    }

    /// «Rückgängig» im Banner: zurücklegen; nicht geschrieben → Hinweis statt still nichts.
    /// «Portfolio leeren» bestätigt: alle Transaktionen weg, Banner mit Zahl, «Rückgängig» (alle mit ihren ids zurück,
    /// die Kurse lädt die Seite danach neu) und Ansage — wie `deleteCoin`, wie Android.
    func clearAll() {
        // Stand vorher für «Rückgängig» (mit ids), wie `PortfolioRepository.clearAll` (Android) ihn zurückgibt
        let before = data.currentPortfolioFile().transactions
        var cleared = true
        withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { cleared = data.clearPortfolio() }
        guard cleared else {
            banner = .portfolioNotWritten(data)
            return
        }
        guard !before.isEmpty else { return }
        deleteTick += 1
        let text = L("portfolio_cleared", count: before.count)
        banner = WatchlistBannerMessage(
            text: text,
            action: WatchlistBannerAction.undo { restoreOrWarn(before) }
        )
        Task { @MainActor in
            // Nach dem Fokuswechsel ansagen, sonst geht es unter
            try? await Task.sleep(nanoseconds: 300_000_000)
            AccessibilityNotification.Announcement(text).post()
        }
    }

    private func restoreOrWarn(_ restore: [PortfolioTx]) {
        var saved = true
        withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { saved = data.restorePortfolioTxs(restore) }
        if !saved { banner = .portfolioNotWritten(data) }
    }

    /// «Import rückgängig»: Lauf löschen, Banner mit «Rückgängig» (stellt die Transaktionen wieder her) — wie Android.
    func undoImport(_ batch: PortfolioImportBatchInfo) {
        var removed: [PortfolioTx] = []
        let expected = data.portfolio.contains { $0.importBatch == batch.id }
        withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { removed = data.undoPortfolioImport(batch.id) }
        guard !removed.isEmpty else {
            // Nicht geschrieben (gesperrt/Fehler) → Hinweis statt still, wie beim Löschen eines Coins
            if expected { banner = .portfolioNotWritten(data) }
            return
        }
        deleteTick += 1
        let restore = removed
        let text = L("portfolio_import_undone", count: removed.count)
        banner = WatchlistBannerMessage(
            text: text,
            action: WatchlistBannerAction.undo {
                restoreOrWarn(restore)
            }
        )
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 300_000_000)
            AccessibilityNotification.Announcement(text).post()
        }
    }
}

extension WatchlistBannerMessage {
    /// Portfolio-Datei nicht geschrieben (gespeicherter Stand gesperrt, Schlüsselbund nicht lesbar): Änderungen erst nach
    /// dem nächsten Start — statt einer Erfolgsmeldung (wie Android `portfolio_storage_locked`).
    static var portfolioNotSaved: WatchlistBannerMessage {
        WatchlistBannerMessage(text: L("portfolio_storage_locked"), kind: .error,
                               long: true)
    }

    /// Nicht geschrieben wegen eines Schreibfehlers (nicht gesperrt): «Konnte nicht gespeichert werden»,
    /// wie Android `portfolio_write_failed`.
    static var portfolioWriteFailed: WatchlistBannerMessage {
        WatchlistBannerMessage(text: L("portfolio_write_failed"), kind: .error, long: true)
    }

    /// Hinweis nach einem nicht geschriebenen Schreibweg: gesperrt (`AppData.portfolioWriteLocked`) → `portfolioNotSaved`,
    /// sonst `portfolioWriteFailed` — wie Android `PortfolioWriteResult.notSavedText`.
    @MainActor static func portfolioNotWritten(_ data: AppData) -> WatchlistBannerMessage {
        data.portfolioWriteLocked ? .portfolioNotSaved : .portfolioWriteFailed
    }
}
