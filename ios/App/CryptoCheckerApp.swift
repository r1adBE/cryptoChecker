import SwiftUI
import UIKit
import UserNotifications
import WidgetKit

/// Tabs der App — wie die Tabs der Android-Fassung: Merkliste · Markt · (Portfolio) · Einstellungen.
/// `portfolio` gibt es nur, wenn der Bereich in den Optionen eingeschaltet ist. «Paar hinzufügen»
/// ist seit Runde 31 kein Tab mehr, sondern eine Seite über der Merkliste (`showExplorer`).
enum AppTab: Hashable {
    case watchlist, cycle, portfolio, settings
}

/// Navigation von aussen (Shortcuts, Widgets, Mitteilungen).
@MainActor
final class AppRouter: ObservableObject {
    static let shared = AppRouter()

    @Published var tab: AppTab = .watchlist
    /// Alarm-Übersicht in der Merkliste öffnen.
    @Published var showAlarmsOverview = false
    /// Paar in der Merkliste hervorheben bzw. seine Aktionen öffnen.
    @Published var focusWatchId: Int64?
    /// Alarme eines Paars in der Merkliste öffnen (z. B. «Alarm setzen» nach dem ersten Paar).
    @Published var openAlarmsWatchId: Int64?
    /// «Anpassen» aus einer Alarm-Mitteilung: `AlarmsScreen` öffnet den Editor dieses Alarms (dann gelöscht).
    @Published var pendingEditAlarmId: Int64?
    /// Seite «Paar hinzufügen» über der Merkliste offen («+», Shortcut, Widget, Link "add").
    @Published var showExplorer = false
    /// Seite «Paar hinzufügen» mit dieser Suche öffnen («Heute auffällig» im Markt-Tab).
    @Published var explorerSearch: String?
    /// «Was bewegt sich mit?» aus einer Alarm-Mitteilung: Blatt dieses Paars öffnen.
    @Published var openWhyWatchId: Int64?
    /// Sprung an eine Stelle des Markt-Tabs (Mitteilung «Wirtschaftstermine»); `CycleScreen`
    /// wählt das Register, scrollt hin und löscht ihn.
    @Published var marketJump: MarketJump?
    /// Unterseite «Daten» des Markt-Tabs soll zum Wirtschaftsdaten-Hinweis scrollen (dann gelöscht).
    @Published var marketMacroOnData = false
    /// Seite «Paar hinzufügen» über dem Markt-Tab («Heute auffällig»): zurück führt wieder
    /// in den Markt-Tab, nicht in die Merkliste (G-13).
    @Published var showMarketExplorer = false
    /// Einstellungen › Portfolio öffnen (Portfolio-Widget, während das Portfolio aus ist).
    @Published var showPortfolioSettings = false
    /// K-10: Einstellungen › Aktualisierung («Wie aktuell?») direkt öffnen (Hinweis im Alarm).
    @Published var showUpdateSettings = false
    /// Einstellungen › Sicherung direkt öffnen («Sicherung wiederherstellen» im Hinweis `StorageRecoveryNotice`).
    @Published var showBackupSettings = false

    /// Tab «Einstellungen» mit der Seite «Sicherung» darüber (dort «Wiederherstellen»).
    func openBackupSettings() {
        tab = .settings
        showBackupSettings = true
    }

    /// Gewählten Tab noch einmal getippt, Startseite schon sichtbar: dieser Tab scrollt nach oben (die
    /// Bildschirme reagieren auf eine neue Anfrage, `id` macht jede Anfrage neu).
    struct ScrollToTop: Equatable {
        let tab: AppTab
        let id: Int
    }
    @Published private(set) var scrollToTop: ScrollToTop?

    func requestScrollToTop(_ tab: AppTab) {
        scrollToTop = ScrollToTop(tab: tab, id: (scrollToTop?.id ?? 0) + 1)
    }

    /// Zurück zur Startseite eines Tabs (erneutes Tippen auf den Tab): Merker, die Seiten öffnen, löschen —
    /// sonst öffnete der neue Navigationsstapel sie gleich wieder.
    func resetPages(of tab: AppTab) {
        switch tab {
        case .watchlist:
            showAlarmsOverview = false
            openAlarmsWatchId = nil
            pendingEditAlarmId = nil
            openWhyWatchId = nil
        case .settings:
            showPortfolioSettings = false
            showUpdateSettings = false
        case .cycle, .portfolio:
            break
        }
    }

    /// K-10: Tab «Einstellungen» mit der Seite «Aktualisierung» darüber.
    func openUpdateSettings() {
        tab = .settings
        showUpdateSettings = true
    }

    /// Seite «Paar hinzufügen» über der Merkliste öffnen; zurück führt zur Merkliste.
    func openExplorer() {
        showMarketExplorer = false
        tab = .watchlist
        showExplorer = true
    }

    /// Seite «Paar hinzufügen» schliessen (über der Merkliste bzw. dem Markt-Tab) und zur Merkliste.
    func closeExplorerToWatchlist() {
        if showMarketExplorer {
            showMarketExplorer = false
            tab = .watchlist
        }
        showExplorer = false
    }

    /// Ziel aus einem Shortcut oder Link: "add", "alarms", "cycle", "cycle/macro", "portfolio",
    /// "watch/<id>", "why/<id>", "alarm/<Paar>/<Alarm>".
    func open(_ target: String) {
        switch target {
        case "add": openExplorer()
        case "cycle": tab = .cycle
        case "cycle/macro":
            // Mitteilung «Wirtschaftstermine»: Markt-Tab, Register mit dem Hinweis (dort gescrollt)
            marketJump = .macro
            tab = .cycle
        case "portfolio":
            // Portfolio-Widget: Portfolio-Tab; ist es aus, Einstellungen › Portfolio (dort der Schalter)
            switch PortfolioOff.widgetTarget(portfolioEnabled: AppData.shared.settings.portfolioEnabled) {
            case .portfolioTab:
                tab = .portfolio
            case .portfolioSettings:
                tab = .settings
                showPortfolioSettings = true
            }
        case "alarms":
            tab = .watchlist
            showAlarmsOverview = true
        default:
            if target.hasPrefix("watch/"), let id = Int64(target.dropFirst(6)) {
                tab = .watchlist
                focusWatchId = id
            } else if target.hasPrefix("why/"), let id = Int64(target.dropFirst(4)) {
                tab = .watchlist
                openWhyWatchId = id
            } else if let edit = AlarmEditTarget.parse(target) {
                // «Anpassen»: Alarme des Paars, darin der Editor des Alarms
                tab = .watchlist
                pendingEditAlarmId = edit.alarmId
                openAlarmsWatchId = edit.watchId
            }
        }
    }
}

@main
struct CryptoCheckerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var data = AppData.shared
    @StateObject private var router = AppRouter.shared
    @StateObject private var lock = AppLock.shared
    @Environment(\.scenePhase) private var scenePhase

    init() {
        #if DEBUG
        // UI-Tests (Startargument «-uiTesting»): leerer Speicher, feste Börsen-Antworten — vor allem anderen
        UITesting.prepareIfRequested()
        #endif
        // Lief die App einmal ohne App Group: deren Daten jetzt einmal übernehmen — vor dem ersten Lesen (AppData)
        SharedStorage.migrateFallbackIfNeeded()
        // App-Start bis zum ersten Bild der Merkliste (Bericht «Ablauf»)
        AppStartClock.onAppInit()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(data)
                .environmentObject(router)
                // Verschlüsselte Daten ohne Schlüssel beiseitegelegt: sagen, wie sie zurückkommen (bis bestätigt);
                // «Sicherung wiederherstellen» öffnet Einstellungen › Sicherung
                .storageRecoveryNotice(onRestoreBackup: { router.openBackupSettings() })
                // Abgebrochenes Wiederherstellen (Journal liegt noch da): Stand davor zurückschreiben, Hinweis vormerken;
                // danach einmalig «fällt um ≥ 100 %»-Alarme ausschalten
                .task { BackupManager.recoverInterruptedRestore(data); data.disableInvalidFallAlarmsOnce() }
                // Portfolio-Sperre: gesperrt ist nur der Portfolio-Tab (RootView); hier nur der
                // Sichtschutz im App-Umschalter, solange die Sperre an ist
                .overlay {
                    if data.appLockEffective && scenePhase != .active {
                        AppPrivacyCover()
                    }
                }
                .environment(\.appAccent, data.settings.accentColor)
                // Coin-Logos (Schalter «In der App», «Im Portfolio»); aus → Initialen
                .environment(\.coinLogosEnabled, data.settings.coinLogos)
                .environment(\.portfolioCoinLogosEnabled, data.settings.portfolioCoinLogos)
                .environment(\.coinNamesEnabled, data.settings.watchlistNames)
                .environment(\.priceColorScheme, data.settings.priceColorScheme)
                .environment(\.priceColorsInverted, data.settings.priceColorsInverted)
                // Hoher Kontrast: Einstellung oder «Kontrast erhöhen» des Systems
                .highContrastRoot(data.settings.highContrast)
                .tint(data.settings.accentColor.primary)
                .preferredColorScheme(data.settings.darkMode.map { $0 ? .dark : .light })
                .onOpenURL { url in
                    // cryptochecker://add, cryptochecker://watch/12 … — nur das eigene Schema;
                    // AppRouter.open kennt nur feste Ziele (Navigation, keine Daten-Änderung).
                    guard url.scheme?.lowercased() == "cryptochecker" else { return }
                    let target = [url.host, url.path.isEmpty ? nil : String(url.path.dropFirst())]
                        .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "/")
                    // Fremde Links (andere Apps, Webseiten) nur zu harmlosen Zielen; Portfolio u. a. nur mit dem
                    // Geheimnis der eigenen Widgets (`OpenTargetGuard`, wie Android)
                    guard OpenTargetGuard.accepts(target, token: OpenTargetGuard.linkToken(of: url),
                                                  expected: OpenTargetGuard.token) else { return }
                    router.open(target)
                }
        }
        .onChange(of: scenePhase) { _, phase in
            // Portfolio-Sperre: Zeit im Hintergrund, Sperren, Sichtschutz-Fenster (alle Phasen)
            lock.scenePhaseChanged(phase)
            switch phase {
            case .active:
                data.setAppActive(true)
                // «Zahl am App-Symbol»: App geöffnet = gesehen, wie bei WhatsApp
                Notifier.clearBadge()
                QuickActions.install()
                // Coin-Logos: fehlende (alle Coins der Rangliste, nie einzeln) nachladen
                data.startCoinLogoSync()
                // Morgen-Mitteilungen «Wirtschaftstermine» an Kalender und Einstellung anpassen
                MacroNotifications.refresh(settings: data.settings)
                // Marktphase / Fear & Greed: nachholen, wenn die letzte Prüfung ≥ 12 h her ist
                BackgroundRefresh.runZoneCheckIfDue(settings: data.settings)
                // Vorgemerkte Hintergrund-Aufgabe prüfen (bleibt stehen; nach verstellter Uhr ersetzt)
                BackgroundRefresh.schedule(settings: data.settings)
            case .background:
                data.setAppActive(false)
                // Letzten Stand sicher auf die Platte, bevor iOS die App anhält — ohne den Haupt-Thread zu blockieren
                // (das Schreiben kann über `NSFileCoordinator` auf das Widget warten): die Hintergrund-Zeit hält die
                // App wach, bis die Warteschlange leer ist; reicht sie nicht, meldet sich die Aufgabe selbst ab
                let flushTask = BackgroundTaskGuard.begin("flushSnapshot")
                Task { @MainActor in
                    await SharedStorage.snapshotWritesSettled()
                    flushTask.end()
                }
                BackgroundRefresh.schedule(settings: data.settings)
            default:
                break
            }
        }
    }
}

// @preconcurrency: iOS ruft die Mitteilungs-Delegate-Methoden auf dem Haupt-Thread auf; das SDK markiert das
// Protokoll aber nicht als MainActor (die Klasse ist es über UIApplicationDelegate).
final class AppDelegate: NSObject, UIApplicationDelegate, @preconcurrency UNUserNotificationCenterDelegate {

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        BackgroundRefresh.register()
        UNUserNotificationCenter.current().delegate = self
        // Aktionen der Kursalarme («Was bewegt sich mit?», «1 h später», «Anpassen») und der Depeg-Mitteilung
        Notifier.registerCategories()
        BackgroundRefresh.schedule(settings: SharedStorage.loadSettings())
        // Uhr oder Zeitzone verstellt: der vorgemerkte Beginn kann Stunden weg liegen (`isImplausiblyFar`)
        for name in [UIApplication.significantTimeChangeNotification, .NSSystemClockDidChange, .NSSystemTimeZoneDidChange] {
            NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in
                BackgroundRefresh.clockChanged()
            }
        }
        // Alarmtöne auch nach `Library/Sounds` der App Group: dort findet iOS sie sicher, wenn die Widget-Erweiterung
        // einen Kursalarm meldet (`AlarmSounds.installSharedSounds`, wiederholbar, kopiert nur Fehlendes/Geändertes)
        DispatchQueue.global(qos: .utility).async { AlarmSounds.installSharedSounds() }
        // Keine Kennungen: Cookies/Cache-Antworten älterer Versionen weg (eigene Sitzungen speichern
        // keine mehr), ebenso ein nach Absturz oder Beenden liegen gebliebener Diagnosebericht im `tmp`-Ordner
        DispatchQueue.global(qos: .utility).async {
            PrivateSessionConfig.clearSharedStores()
            DiagnosticsShareFile.remove()
        }
        return true
    }

    func application(_ application: UIApplication,
                     configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        config.delegateClass = SceneDelegate.self
        if let item = options.shortcutItem {
            Task { @MainActor in AppRouter.shared.open(item.type) }
        }
        return config
    }

    // Mitteilungen auch zeigen, wenn die App offen ist — passive Kurs-Mitteilungen (Gruppe «prices») nur in der
    // Mitteilungszentrale: Die Kurse stehen ohnehin in der offenen App, ein Banner je Aktualisierung störte nur.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        if notification.request.content.threadIdentifier == "prices" {
            completionHandler([.list])
            return
        }
        completionHandler([.banner, .list, .sound])
    }

    // Tipp auf eine Mitteilung: zum Paar springen.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let watchId = (response.notification.request.content.userInfo[Notifier.userInfoWatchId] as? NSNumber)?.int64Value
        let exchangeUrl = (response.notification.request.content.userInfo[Notifier.userInfoExchangeUrl] as? String)
            .flatMap { URL(string: $0) }
        let alarmId = (response.notification.request.content.userInfo[Notifier.userInfoAlarmId] as? NSNumber)?.int64Value
        if response.actionIdentifier == Notifier.actionSnooze, let alarmId {
            // «1 h später»: Pause vermerken (der Alarm bleibt scharf), Mitteilung weg — App bleibt im Hintergrund.
            // Zuerst die Pause: Kursmarken stellt das Einschalten danach wieder scharf (`rearmsOnSnooze`), die Pause
            // hält die sofortige Meldung zurück — wie AlarmActionReceiver auf Android
            AlarmSnooze.snooze(alarmId, now: TimeUtils.nowMillis)
            Notifier.cancelAlarm(alarmId)
            Task { @MainActor in
                // Schreiben zu Ende führen, auch wenn iOS die App gleich wieder anhält; erst danach fertig melden
                let backgroundTask = BackgroundTaskGuard.begin("snoozeAlarm")
                defer {
                    backgroundTask.end()
                    completionHandler()
                }
                // Abgestimmt auf der Datei statt im Speicher der App: Widget oder Hintergrund können den Alarm
                // inzwischen ausgelöst und gespeichert haben (`AlarmSnooze.rearm`). Abseits des Haupt-Threads — das
                // Schreiben kann über `NSFileCoordinator` auf das Widget warten
                let saved = await Task.detached(priority: .userInitiated) {
                    var changed = false
                    let written = SharedStorage.updateSnapshot { changed = AlarmSnooze.rearm(alarmId, in: &$0) }
                    return written != nil && changed
                }.value
                guard saved else { return }
                // Auch im Speicher der App: Ein Speichern der App zwischen dem Schreiben oben und dem Neuladen
                // schrieb sonst ihr «aus» zurück (das Zusammenführen übernimmt die Datei nur bei neuerer Auslösung, das
                // Einschalten ändert sie nicht) — der Alarm bliebe trotz «1 h später» aus. Gleiche Änderung wie auf der
                // Datei, gespeichert wie der Schalter in der Liste; nur wenn der Alarm im Speicher steht (sonst nichts zu
                // ändern, z. B. Merkliste gesperrt und leer)
                let app = AppData.shared
                var probe = app.snapshot
                if AlarmSnooze.rearm(alarmId, in: &probe) {
                    app.mutate({ _ = AlarmSnooze.rearm(alarmId, in: &$0) }, reloadWidgets: false)
                }
                await app.reloadFromDisk()
            }
            return
        } else if response.actionIdentifier == Notifier.actionEdit, let alarmId, let id = watchId {
            Task { @MainActor in AppRouter.shared.open(AlarmEditTarget.of(watchId: id, alarmId: alarmId)) }
        } else if response.actionIdentifier == Notifier.actionOpenExchange, let url = exchangeUrl, url.scheme == "https" {
            // USDT-Depeg «Börse öffnen»: Seite USDC/USDT der Börse (App der Börse, falls sie den Link übernimmt)
            Task { @MainActor in UIApplication.shared.open(url, options: [:], completionHandler: nil) }
        } else if response.actionIdentifier == Notifier.actionWhy, let id = watchId {
            // «Was bewegt sich mit?»: direkt das Blatt des Paars
            Task { @MainActor in AppRouter.shared.open("why/\(id)") }
        } else if let id = watchId {
            Task { @MainActor in AppRouter.shared.open("watch/\(id)") }
        } else if let target = response.notification.request.content.userInfo["open"] as? String {
            // z. B. «Wirtschaftstermine» → Markt-Tab
            Task { @MainActor in AppRouter.shared.open(target) }
        }
        completionHandler()
    }
}

final class SceneDelegate: NSObject, UIWindowSceneDelegate {
    func windowScene(_ windowScene: UIWindowScene, performActionFor shortcutItem: UIApplicationShortcutItem,
                     completionHandler: @escaping (Bool) -> Void) {
        Task { @MainActor in AppRouter.shared.open(shortcutItem.type) }
        completionHandler(true)
    }
}

/// App-Shortcuts (langes Drücken auf das App-Icon) — wie `shortcuts.xml`.
enum QuickActions {
    @MainActor
    static func install() {
        UIApplication.shared.shortcutItems = [
            UIApplicationShortcutItem(type: "add", localizedTitle: L("shortcut_add"), localizedSubtitle: nil,
                                      icon: UIApplicationShortcutIcon(systemImageName: "plus.circle"), userInfo: nil),
            UIApplicationShortcutItem(type: "alarms", localizedTitle: L("shortcut_alarms"), localizedSubtitle: nil,
                                      icon: UIApplicationShortcutIcon(systemImageName: "bell.and.waves.left.and.right"), userInfo: nil),
            UIApplicationShortcutItem(type: "cycle", localizedTitle: L("shortcut_cycle"), localizedSubtitle: nil,
                                      icon: UIApplicationShortcutIcon(systemImageName: "chart.line.uptrend.xyaxis"), userInfo: nil),
        ]
    }
}
