import BackgroundTasks
import Foundation
import SwiftUI
import WidgetKit
import UIKit
import UserNotifications

// Folgen einer geänderten Einstellung in AppData (`settingsChanged`: Widgets, Mitteilungen, Hintergrund, Icon …).
// Eigene Datei, weil die Liste der Folgen lang ist und für sich lesbar sein soll.

extension AppData {
    // MARK: Einstellungen

    func settingsChanged(from old: AppSettings) {
        if old.updateMode != settings.updateMode {
            updateLiveStream()
        }
        if old.backgroundIntervalMinutes != settings.backgroundIntervalMinutes {
            // Neues Intervall: vorgemerkte Aktualisierung ersetzen
            BackgroundRefresh.scheduleRefresh(settings: settings, replace: true)
        }
        if old.fearGreedBelow != settings.fearGreedBelow || old.fearGreedAbove != settings.fearGreedAbove
            || old.indicatorAlerts != settings.indicatorAlerts {
            BackgroundRefresh.scheduleZone(settings: settings)
        }
        // Markt-Meldung ausgeschaltet: gemerkten Stand vergessen, sonst bliebe das Wiedereinschalten mit derselben
        // Grenze stumm, wenn während «Aus» keine Prüfung lief
        MarketAlertReset.settingsChanged(from: old, to: settings, defaults: SharedStorage.defaults)
        if old.accentColor != settings.accentColor {
            // Nur auf ausdrückliche Wahl der Akzentfarbe (App Review 4.6); Hell/Dunkel
            // übernimmt das Icon selbst über die Varianten im Asset-Katalog.
            AppIconSwitcher.apply(accent: settings.accentColor)
        }
        if old.accentColor != settings.accentColor || old.darkMode != settings.darkMode {
            // Akzentfarbe kann Kursfarben ändern (Ersatzfassung bei zu ähnlichem Farbton)
            PriceColors.accent = settings.accentColor
            WidgetCenter.shared.reloadAllTimelines()
        }
        if old.priceColorScheme != settings.priceColorScheme {
            // Kursfarben: App sofort (Environment), Widgets über eine neue Timeline
            PriceColors.scheme = settings.priceColorScheme
            WidgetCenter.shared.reloadAllTimelines()
        }
        if old.priceColorsInverted != settings.priceColorsInverted {
            // Farben tauschen: App sofort (Environment), Widgets über eine neue Timeline
            PriceColors.inverted = settings.priceColorsInverted
            WidgetCenter.shared.reloadAllTimelines()
        }
        // «inkl. Alpha-Tokens» eingeschaltet: Alpha-Liste holen, auch wenn Logos und Namen aus sind
        if (CoinLogoUse.needed(settings) && !CoinLogoUse.needed(old)) || (settings.watchlistNames && !old.watchlistNames)
            || (settings.includeAlphaTokens && !old.includeAlphaTokens) {
            startCoinLogoSync()
        }
        if old.widgetCoinLogos != settings.widgetCoinLogos {
            // Coin-Logos in Widgets an/aus: gleich neu zeichnen
            WidgetCenter.shared.reloadTimelines(ofKind: SharedStorage.watchlistWidgetKind)
            WidgetCenter.shared.reloadTimelines(ofKind: SharedStorage.singleWidgetKind)
        }
        if old.highContrast != settings.highContrast {
            // Hoher Kontrast: App über die Wurzel (Environment, Trait), Widgets neu zeichnen
            HighContrast.setting = settings.highContrast
            WidgetCenter.shared.reloadAllTimelines()
        }
        if old.portfolioCurrency != settings.portfolioCurrency {
            // Portfolio-Widget in der neuen Umrechnungswährung
            Task { await PortfolioWidgetStore.refresh() }
        }
        if old.changeBasis != settings.changeBasis {
            // %-Basis: bis neu gerechnet ist «—» (Stempel passt nicht); gleich neu rechnen,
            // Widgets, Portfolio-Widget und Live-Aktivität folgen
            WidgetCenter.shared.reloadAllTimelines()
            Task { [weak self] in
                await self?.refreshAll()
                await PortfolioWidgetStore.refresh()
            }
        }
        if old.showChangePeriod != settings.showChangePeriod {
            // Zeitraum neben der %-Änderung: Einzel-Widget gleich neu zeichnen (Live-Aktivität beim nächsten Kurs)
            WidgetCenter.shared.reloadAllTimelines()
        }
        if old.portfolioEnabled != settings.portfolioEnabled {
            // Abgelegtes Portfolio-Widget: Werte bzw. «Portfolio ist aus»
            WidgetCenter.shared.reloadTimelines(ofKind: PortfolioWidgetStore.kind)
        }
        if old.hidePortfolioAmounts != settings.hidePortfolioAmounts {
            // Portfolio-Widget: Beträge zeigen bzw. als «•••»
            WidgetCenter.shared.reloadTimelines(ofKind: PortfolioWidgetStore.kind)
        }
        if old.portfolioSystemBackup != settings.portfolioSystemBackup {
            // Portfolio-Dateien (Transaktionen, Portfolio-Alarme) in iCloud-/Geräte-Backups
            // aufnehmen bzw. ausnehmen
            PortfolioStore.applyBackupPolicy(includeInBackup: settings.portfolioSystemBackup)
        }
        if old.appLock != settings.appLock {
            if !settings.appLock { AppLock.shared.disabled() }
            // Portfolio-Widget: Werte zeigen bzw. verbergen
            WidgetCenter.shared.reloadTimelines(ofKind: PortfolioWidgetStore.kind)
        }
        if !old.priceNotifications && settings.priceNotifications {
            Task { _ = await Notifier.requestPermission() }
        }
        // Nur beim Ausschalten und in EINEM Aufruf: vorher lief das bei jeder Einstellungs-Änderung je Paar
        // (bis 2000 Aufrufe auf dem Haupt-Thread)
        if PriceNotificationRule.switchedOff(old: old.priceNotifications, new: settings.priceNotifications) {
            Notifier.cancelAllPrices(snapshot.watches.map(\.id))
        }
    }
}
