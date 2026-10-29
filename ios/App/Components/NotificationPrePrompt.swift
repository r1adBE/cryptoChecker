import SwiftUI
import UIKit
import UserNotifications

/// Anlass der Frage nach der Mitteilungs-Erlaubnis — wie `NotificationAsk` (Android).
enum NotificationAsk: Sendable {
    /// Ausdrücklich eingeschaltet (Schalter in den Einstellungen, Meldung eines Paars): gleich die System-Abfrage.
    case direct
    /// Neue Paare (mit Kurs-Mitteilungen) hinzugefügt: vorher einmal kurz erklären.
    case pairsAdded
    /// Alarm angelegt: erklärt, falls noch nie gezeigt — nach «Später» noch genau einmal.
    case alarmCreated
}

/// Kurze Erklärung vor der System-Abfrage — wie `NotificationPrePrompt` (Android): nur, wenn das System wirklich
/// fragen würde (`.notDetermined`) — nie bei schon erlaubt oder abgelehnt. «Weiter» → System-Abfrage; «Später» →
/// nichts, beim ersten angelegten Alarm höchstens noch einmal. Danach nicht mehr.
///
/// Erscheint in einem eigenen Fenster über allem (wie der Sichtschutz in `AppLock`): Paare und Alarme entstehen
/// auch in offenen Blättern (Sheets), über denen eine Abfrage der Hauptansicht nicht erscheinen könnte.
@MainActor
final class NotificationPrePrompt {
    static let shared = NotificationPrePrompt()

    /// Noch nie gezeigt.
    nonisolated static let stageNew = 0
    /// Einmal «Später»: beim ersten Alarm noch einmal.
    nonisolated static let stageLaterOnce = 1
    /// Zweimal «Später»: nicht mehr erklären, nicht fragen.
    nonisolated static let stageDone = 2
    /// «Weiter» getippt: würde das System trotzdem noch fragen, gleich die System-Abfrage.
    nonisolated static let stageContinued = 3

    enum Action: Sendable, Equatable { case explain, system, nothing }

    /// Reine Regel — wie `NotificationPrePrompt.action` (Android). `notDetermined`: das System würde jetzt fragen.
    nonisolated static func action(notDetermined: Bool, stage: Int, reason: NotificationAsk) -> Action {
        if !notDetermined || reason == .direct { return .system }
        if stage == stageContinued { return .system }
        if stage == stageNew { return .explain }
        if stage == stageLaterOnce && reason == .alarmCreated { return .explain }
        return .nothing
    }

    /// Stufe nach «Später»: beim ersten Mal noch eine Chance (Alarm), danach erledigt.
    nonisolated static func afterLater(_ stage: Int) -> Int { stage == stageNew ? stageLaterOnce : stageDone }

    /// Gerätebezogen (UserDefaults der App, nicht im App-Group-Speicher): die Erlaubnis gehört zu diesem Gerät.
    private static let stageKey = "notification_preprompt_stage"

    private var stage: Int {
        get { UserDefaults.standard.integer(forKey: Self.stageKey) }
        set { UserDefaults.standard.set(newValue, forKey: Self.stageKey) }
    }

    /// Steht die Erklärung, und die Stufe beim Zeigen — wie `PrePromptSlot` (Android).
    private var slot = PrePromptSlot(stage: NotificationPrePrompt.stageNew)
    private var window: UIWindow?
    private weak var previousKeyWindow: UIWindow?

    private init() {}

    /// Erlaubnis bei Bedarf fragen (ohne Warten); bei `pairsAdded`/`alarmCreated` vorher die Erklärung.
    nonisolated static func request(_ reason: NotificationAsk) {
        Task { @MainActor in await shared.ask(reason) }
    }

    private func ask(_ reason: NotificationAsk) async {
        guard !slot.showing else { return }
        let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        // Nach dem Warten neu lesen: eine zweite Anfrage kann die Erklärung inzwischen gezeigt haben
        let before = stage
        slot.stage = before
        let action = slot.request(notDetermined: status == .notDetermined, reason: reason)
        stage = slot.stage
        switch action {
        case .system: _ = await Notifier.requestPermission()
        case .explain:
            // Kein Fenster möglich (keine aktive Szene): nicht gezeigt, also auch nicht gezählt
            if !show() {
                _ = slot.answer(proceed: false)
                stage = before
            }
        case .nothing: break
        }
    }

    // MARK: Fenster über allem

    /// true = gezeigt.
    private func show() -> Bool {
        guard window == nil,
              let scene = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .first(where: { $0.activationState == .foregroundActive })
        else { return false }
        previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        window.windowLevel = .alert
        window.backgroundColor = .clear
        let host = UIHostingController(rootView: NotificationPrePromptContent(data: AppData.shared) { [weak self] proceed in
            self?.finish(proceed: proceed)
        })
        host.view.backgroundColor = .clear
        window.rootViewController = host
        self.window = window
        window.makeKeyAndVisible()
        return true
    }

    private func finish(proceed: Bool) {
        // «Später»: Stufe schon beim Zeigen weiter (`PrePromptSlot`); nur «Weiter» ändert noch etwas
        let first = slot.answer(proceed: proceed)
        stage = slot.stage
        window?.isHidden = true
        window = nil
        previousKeyWindow?.makeKey()
        if first && proceed { Task { _ = await Notifier.requestPermission() } }
    }
}

/// Die eine Erklärung der App — wie `PrePromptSlot` (Android). Die Stufe geht schon beim Zeigen weiter, als wäre
/// «Später» getippt: Verschwindet die Erklärung ohne Antwort (App beendet), zählt das als «Später». Solange sie
/// steht, kommt keine zweite. Ohne UIKit, damit ein Unit-Test die Regel samt Zeitpunkt des Speicherns prüft.
struct PrePromptSlot: Sendable, Equatable {
    var stage: Int
    private(set) var showing = false

    /// Anfrage aus `reason`; `.system` startet der Aufrufer selbst, bei `.explain` steht die Erklärung jetzt.
    mutating func request(notDetermined: Bool, reason: NotificationAsk) -> NotificationPrePrompt.Action {
        // Schon offen: kein zweites Mal, auch keine System-Abfrage darüber
        if showing { return .nothing }
        let action = NotificationPrePrompt.action(notDetermined: notDetermined, stage: stage, reason: reason)
        if action == .explain {
            stage = NotificationPrePrompt.afterLater(stage)
            showing = true
        }
        return action
    }

    /// Antwort: «Weiter» merkt `stageContinued`; «Später» ändert nichts mehr. true = erste Antwort.
    mutating func answer(proceed: Bool) -> Bool {
        guard showing else { return false }
        showing = false
        if proceed { stage = NotificationPrePrompt.stageContinued }
        return true
    }
}

/// Inhalt des Fensters: nur die Abfrage der App (Titel, ein Satz, «Weiter» / «Später»).
@MainActor
private struct NotificationPrePromptContent: View {
    @ObservedObject var data: AppData
    let onFinish: (Bool) -> Void
    @State private var presented = false

    var body: some View {
        Color.clear
            .ignoresSafeArea()
            .onAppear { presented = true }
            .alert(L("notification_permission_preprompt_title"), isPresented: $presented) {
                Button(L("notification_permission_preprompt_later"), role: .cancel) { onFinish(false) }
                Button(L("notification_permission_preprompt_continue")) { onFinish(true) }
                    .accessibilityIdentifier(UiTestTags.notificationPrepromptContinue)
            } message: {
                Text(L("notification_permission_preprompt_text"))
            }
            .tint(data.settings.accentColor.primary)
            .environment(\.appAccent, data.settings.accentColor)
            .highContrastRoot(data.settings.highContrast)
            .preferredColorScheme(data.settings.darkMode.map { $0 ? .dark : .light })
    }
}
