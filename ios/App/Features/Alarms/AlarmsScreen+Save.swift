import SwiftUI

/// Speichern aus dem Alarm-Editor und Schnell-Alarme von `AlarmsScreen` — seit Build 105 ausgelagert (der body war für
/// den Swift-Typprüfer zu gross, und die Datei stand an der 400-Zeilen-Grenze).
extension AlarmsScreen {
    /// Editor «Speichern» (`AlarmEditorSheet.onSave`). Seit Build 106 (UX4-06) schliesst der Editor erst nach bestätigter
    /// Rückfrage F-08 (`close`), «Abbrechen» lässt ihn mit den Eingaben offen (`cancelled`). Seit Build 107 (IOS5-R01):
    /// genau eine Frage — danach speichert `saveDraftNow` ohne zweite (vorher fragte `saveDraft` nach einem Durchlass
    /// ohne Szene erneut, ggf. erst nach dem Schliessen).
    func saveFromEditor(_ saved: AlarmDraft, close: @escaping @MainActor () -> Void,
                        cancelled: @escaping @MainActor () -> Void) {
        data.withWatchlistConfirm({
            saveDraftNow(saved)
            close()
        }, cancelled: cancelled)
    }

    /// Speicher-Kern ohne Rückfrage; einziger Aufrufer ist `saveFromEditor` (schon bestätigt).
    private func saveDraftNow(_ saved: AlarmDraft) {
        if data.alarmSaveBlocked { return showSaveLocked() }
        // Paar inzwischen gelöscht: nichts gespeichert, der Hinweis «Wann geprüft wird» bleibt unverbraucht
        guard let threshold = saved.threshold, data.watch(watchId) != nil else { return }
        // Sicherheitsnetz: Paar inzwischen voll — nichts angelegt, sagen warum
        if saved.id == 0 && !WatchlistLimits.canAddAlarm(data.alarms(for: watchId).count) { return showAlarmLimit() }
        // Haptik und Bestätigung erst nach dem Speichern (Rückfrage F-08 schon in `saveFromEditor`)
        let outcome = withAnimation {
            data.saveAlarm(watchId: watchId, id: saved.id, condition: saved.condition, threshold: threshold,
                           repeating: saved.repeating, sound: saved.sound, vibrate: saved.vibrate, speak: saved.speak,
                           windowHours: saved.windowHours, currency: saved.condition.isPriceThreshold ? saved.currency : nil)
        }
        guard case .saved(let firstAlarm) = outcome else {
            if outcome == .full { showAlarmLimit() }
            return
        }
        Haptics.play(.success)
        firstAlarmPending = firstAlarm
        noteTimingHint(firstAlarm: firstAlarm)
        showSaved(id: saved.id, firstAlarm: firstAlarm)
        if draft == nil { showPendingDialogs() }
    }

    /// Schnell-Alarm aus einer Vorlage (`AlarmEditorSheet.onTemplate`): wie `saveFromEditor` genau eine Rückfrage F-08,
    /// danach anlegen und schliessen; «Abbrechen» = Blatt bleibt offen (seit Build 107, IOS5-R01).
    func templateFromEditor(_ template: AlarmTemplates.Template, close: @escaping @MainActor () -> Void,
                            cancelled: @escaping @MainActor () -> Void) {
        data.withWatchlistConfirm({
            createFromTemplateNow(template)
            close()
        }, cancelled: cancelled)
    }

    /// Anlege-Kern ohne Rückfrage; einziger Aufrufer ist `templateFromEditor` (schon bestätigt).
    private func createFromTemplateNow(_ template: AlarmTemplates.Template) {
        if data.alarmSaveBlocked { return showSaveLocked() }
        guard let def = AlarmTemplates.definition(template, currentPrice: data.watch(watchId)?.lastPrice) else { return }
        if !WatchlistLimits.canAddAlarm(data.alarms(for: watchId).count) { return showAlarmLimit() }
        let created = withAnimation { data.createAlarm(watchId: watchId, from: def) }
        guard created.id != 0 else { return } // nichts angelegt (Paar gelöscht): keine Bestätigung, wie Android
        Haptics.play(.success)
        if created.firstAlarm { firstAlarmPending = true }
        noteTimingHint(firstAlarm: created.firstAlarm)
        let id = created.id
        let message = WatchlistBannerMessage(
            // Den angelegten Alarm nennen («Alarm gespeichert: BTC Steigt um 5 % · einmalig») — wie Android
            text: templateCreatedText(id), icon: "bell.and.waves.left.and.right.fill",
            action: WatchlistBannerAction.undo {
                data.withWatchlistConfirm { withAnimation { _ = data.deleteAlarm(id) } }
            }
        )
        // Kommt nach dem Editor ein Dialog: «Rückgängig» erst danach zeigen
        if firstAlarmPending || timingHintPending { bannerAfterDialogs = message } else { banner = message }
        if draft == nil { showPendingDialogs() }
    }

    /// Schalter einer Alarmkarte. Seit Build 105: Rückfrage F-08 vorher; «Abbrechen» bzw. abgelehnt: der Schalter zeigt
    /// wieder den echten Stand (`refusedToggles`).
    func toggleAlarm(_ id: Int64, enabled: Bool) {
        data.withWatchlistConfirm {
            // Gesperrt: nur die Warn-Haptik des Hinweises (wie Android), seit Build 104
            if !data.watchlistWritesBlocked { Haptics.play(.selection) }
            if !data.setAlarmEnabled(id, enabled) { refusedToggles &+= 1 }
        } cancelled: {
            refusedToggles &+= 1
        }
    }

    /// Löschen nach der Rückfrage «Alarm löschen?». Seit Build 105: Rückfrage F-08 vorher, Haptik nur nach dem Löschen.
    func deleteConfirmed(_ id: Int64) {
        data.withWatchlistConfirm {
            let deleted = withAnimation { data.deleteAlarm(id) }
            if deleted { Haptics.play(.destructive) }
        }
    }

    /// Merkliste gerade nicht lesbar (`AppData.alarmSaveBlocked`): nichts angelegt, sagen warum (der Editor schliesst sich)
    func showSaveLocked() {
        Haptics.play(.error)
        banner = WatchlistBannerMessage(text: L("alarm_save_storage_locked"), icon: "lock.fill", long: true)
    }

    /// Paar hat schon die Höchstzahl Alarme (`WatchlistLimits`): nichts angelegt, sagen warum (der Editor schliesst sich)
    func showAlarmLimit() {
        Haptics.play(.warning)
        banner = WatchlistBannerMessage(text: AlarmLimitText.text, icon: "bell.badge", long: true)
    }

    /// Bestätigung eines Schnell-Alarms: Bedingung wie nach dem Speichern (`AlarmSavedText`), ohne Mitteilungs-Hinweis.
    func templateCreatedText(_ id: Int64) -> String {
        guard let alarm = data.alarms(for: watchId).first(where: { $0.id == id }) else { return L("alarm_saved_active") }
        let watch = data.watch(watchId)
        let title = AlarmStyle.title(alarm, quote: watch?.quoteAsset ?? "")
        let condition = AlarmSavedText.pairCondition(symbol: watch?.baseAsset ?? "", title: title)
        return AlarmSavedText.text(condition: condition, repeating: alarm.repeating, notificationsOff: false)
    }

    /// Hinweis «Wann Alarme geprüft werden» prüfen und merken (`AlarmTimingHint`): einmal, nicht
    /// zusätzlich zur Bestätigung des allerersten Alarms (sie zeigt denselben Satz) — wie Android.
    /// Nach jedem Speichern gilt er als gezeigt.
    func noteTimingHint(firstAlarm: Bool) {
        let shown = data.settings.alarmTimingHintShown
        if AlarmTimingHint.shouldShow(alreadyShown: shown, firstAlarmDialog: firstAlarm) {
            timingHintPending = true
        }
        if !shown { data.settings.alarmTimingHintShown = true }
    }

    /// «Alarm gespeichert: BTC Kurs über 70’000 USDT · einmalig»; Mitteilungen aus → « · Mitteilungen
    /// aus» mit «Einstellungen». `id` 0 = neu angelegt (dann der Alarm mit der höchsten Id): eine Zeile
    /// «Alarm aktiv · Testen» (wie Android) — nicht beim allerersten Alarm (`firstAlarm`), dessen
    /// Bestätigung das Testen schon anbietet.
    func showSaved(id: Int64, firstAlarm: Bool = false) {
        let list = data.alarms(for: watchId)
        let found = id != 0 ? list.first(where: { $0.id == id }) : list.max(by: { $0.id < $1.id })
        guard let alarm = found else { return }
        let watch = data.watch(watchId)
        let title = AlarmStyle.title(alarm, quote: watch?.quoteAsset ?? "")
        let condition = AlarmSavedText.pairCondition(symbol: watch?.baseAsset ?? "", title: title)
        let repeating = alarm.repeating
        Task { @MainActor in
            let off = await Notifier.isDenied()
            if id == 0 && !off {
                if firstAlarm { return }
                banner = WatchlistBannerMessage(
                    text: L("alarm_saved_active"), icon: "bell.badge.fill",
                    action: WatchlistBannerAction(title: L("alarm_test_action"), perform: { alarmTestTrigger += 1 })
                )
                return
            }
            banner = WatchlistBannerMessage(
                text: AlarmSavedText.text(condition: condition, repeating: repeating, notificationsOff: off),
                icon: off ? "bell.slash.fill" : "bell.badge.fill",
                action: off ? WatchlistBannerAction(title: L("settings_title"), perform: { NotificationsOffBanner.openSettings() }) : nil
            )
        }
    }
}
