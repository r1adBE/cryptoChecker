import SwiftUI
import UIKit
import UserNotifications
import WidgetKit

/// Tabs der App — wie die Tabs der Android-Fassung. `portfolio` gibt es nur,
/// wenn der Bereich in den Optionen eingeschaltet ist.
enum AppTab: Hashable {
    case watchlist, add, cycle, portfolio, settings
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
    /// Hinzufügen-Tab mit dieser Suche öffnen («Heute auffällig» im Markt-Tab).
    @Published var explorerSearch: String?

    /// Hinzufügen-Tab öffnen und dort nach `query` suchen.
    func openExplorer(search query: String) {
        explorerSearch = query
        tab = .add
    }

    /// Ziel aus einem Shortcut oder Link: "add", "alarms", "cycle", "portfolio", "watch/<id>".
    func open(_ target: String) {
        switch target {
        case "add": tab = .add
        case "cycle": tab = .cycle
        case "portfolio":
            // Portfolio-Widget: zum Portfolio-Tab, sofern eingeschaltet
            tab = AppData.shared.settings.portfolioEnabled ? .portfolio : .watchlist
        case "alarms":
            tab = .watchlist
            showAlarmsOverview = true
        default:
            if target.hasPrefix("watch/"), let id = Int64(target.dropFirst(6)) {
                tab = .watchlist
                focusWatchId = id
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

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(data)
                .environmentObject(router)
                // Gesperrt: Inhalt auch für VoiceOver verbergen
                .accessibilityHidden(lock.locked)
                .overlay {
                    if lock.locked {
                        AppLockView(lock: lock)
                    } else if data.settings.appLock && scenePhase != .active {
                        // Sichtschutz im App-Umschalter
                        AppPrivacyCover()
                    }
                }
                .environment(\.appAccent, data.settings.accentColor)
                .environment(\.priceColorScheme, data.settings.priceColorScheme)
                .environment(\.priceColorsInverted, data.settings.priceColorsInverted)
                // Hoher Kontrast: Einstellung oder «Kontrast erhöhen» des Systems
                .highContrastRoot(data.settings.highContrast)
                .tint(data.settings.accentColor.primary)
                .preferredColorScheme(data.settings.darkMode.map { $0 ? .dark : .light })
                .onOpenURL { url in
                    // cryptochecker://add, cryptochecker://watch/12 …
                    let target = [url.host, url.path.isEmpty ? nil : String(url.path.dropFirst())]
                        .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "/")
                    router.open(target)
                }
        }
        .onChange(of: scenePhase) { _, phase in
            // App-Sperre: Zeit im Hintergrund, Sperren, Sichtschutz-Fenster (alle Phasen)
            lock.scenePhaseChanged(phase)
            switch phase {
            case .active:
                data.setAppActive(true)
                QuickActions.install()
                // Morgen-Mitteilungen «Wirtschaftstermine» an Kalender und Einstellung anpassen
                MacroNotifications.refresh(settings: data.settings)
            case .background:
                data.setAppActive(false)
                BackgroundRefresh.schedule(settings: data.settings)
            default:
                break
            }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        BackgroundRefresh.register()
        UNUserNotificationCenter.current().delegate = self
        BackgroundRefresh.schedule(settings: SharedStorage.loadSettings())
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

    // Mitteilungen auch zeigen, wenn die App offen ist.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }

    // Tipp auf eine Mitteilung: zum Paar springen.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        if let id = (response.notification.request.content.userInfo[Notifier.userInfoWatchId] as? NSNumber)?.int64Value {
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
                                      icon: UIApplicationShortcutIcon(systemImageName: "bell"), userInfo: nil),
            UIApplicationShortcutItem(type: "cycle", localizedTitle: L("shortcut_cycle"), localizedSubtitle: nil,
                                      icon: UIApplicationShortcutIcon(systemImageName: "chart.line.uptrend.xyaxis"), userInfo: nil),
        ]
    }
}
